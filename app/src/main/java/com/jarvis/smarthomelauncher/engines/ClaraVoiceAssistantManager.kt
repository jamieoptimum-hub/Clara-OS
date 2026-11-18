package com.jarvis.smarthomelauncher.engines

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// CLARA / JARVIS HANDS-FREE VOICE ASSISTANT MANAGER
// - Keeps a persistent long-window SpeechRecognizer session open and processes
//   wake words ("Hey Clara", "Clara", "Jarvis") in real time via onPartialResults
//   so Android doesn't repeatedly cycle the green mic status bar indicator or spam
//   `AidlConversionCppNdk: no legacy output audio_channel_mask_t found` in Logcat.
// - Smoothly ducks Spotify / ExoPlayer volume to 18% during active voice commands
//   (like a Google Nest Mini) without ever stealing audio focus or pausing music.
// - Routes recognized commands cleanly without duplicate execution.
// ---------------------------------------------------------------------------
class ClaraVoiceAssistantManager(
    private val context: Context,
    private val onCommandRecognized: ((String) -> Unit)? = null,
    private val onWakeWordDetected: (() -> Unit)? = null
) {
    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val assistantScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady: Boolean = false

    private var isSessionActive: Boolean = false
    private var isEnabled: Boolean = true
    private var isSpeakingResponse: Boolean = false

    private var partialCommandCommitJob: Job? = null
    private var wakeWindowTimeoutJob: Job? = null
    private var restartCooldownJob: Job? = null

    private var lastProcessedCommandText: String = ""
    private var lastProcessedTimestampMs: Long = 0L
    private val pendingWavSyntheses = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    init {
        activeInstance = this
        initTextToSpeech()
    }

    private fun initTextToSpeech() {
        try {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsReady = true
                    tts?.language = Locale.UK
                    tts?.setPitch(1.0f)
                    tts?.setSpeechRate(1.03f)
                    // Use ASSISTANCE_NAVIGATION_GUIDANCE without requesting exclusive audio focus
                    // so Spotify WebView / ExoPlayer never pauses when Clara speaks.
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            if (utteranceId?.startsWith("clara_wav_") == true) return
                            isSpeakingResponse = true
                            ClaraInAppMusicPlayer.setVoiceAssistantDucking(true)
                        }

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId != null && pendingWavSyntheses.containsKey(utteranceId)) {
                                pendingWavSyntheses.remove(utteranceId)?.complete(true)
                                return
                            }
                            isSpeakingResponse = false
                            ClaraInAppMusicPlayer.setVoiceAssistantDucking(false)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId != null && pendingWavSyntheses.containsKey(utteranceId)) {
                                pendingWavSyntheses.remove(utteranceId)?.complete(false)
                                return
                            }
                            isSpeakingResponse = false
                            ClaraInAppMusicPlayer.setVoiceAssistantDucking(false)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            if (utteranceId != null && pendingWavSyntheses.containsKey(utteranceId)) {
                                pendingWavSyntheses.remove(utteranceId)?.complete(false)
                                return
                            }
                            isSpeakingResponse = false
                            ClaraInAppMusicPlayer.setVoiceAssistantDucking(false)
                        }
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS init note: ${e.message}")
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun setAssistantEnabled(enabled: Boolean) {
        isEnabled = enabled
        isAssistantEnabledFlow.value = enabled
        if (!enabled) {
            stopListening()
        }
    }

    fun startAlwaysOnListening() {
        // Do not open an unprompted background SpeechRecognizer loop; wait for manual wake
    }

    fun startListening() {
        if (!isEnabled || !hasRecordAudioPermission()) return
        if (!isAwakeForCommandFlow.value) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return

        mainHandler.post {
            if (isSessionActive) return@post
            try {
                SpotifyHiddenWebPlayer.installChromiumAudioFocusShield(appContext)
                val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
                    it.setRecognitionListener(recognitionListener)
                    speechRecognizer = it
                }
                val intent = buildLongSessionRecognizerIntent()
                isSessionActive = true
                isListeningFlow.value = true
                recognizer.startListening(intent)
            } catch (e: Exception) {
                isSessionActive = false
                isListeningFlow.value = false
                Log.w(TAG, "startListening note: ${e.message}")
            }
        }
    }

    fun stopListening() {
        restartCooldownJob?.cancel()
        partialCommandCommitJob?.cancel()
        wakeWindowTimeoutJob?.cancel()
        mainHandler.post {
            isSessionActive = false
            isListeningFlow.value = false
            isAwakeForCommandFlow.value = false
            ClaraInAppMusicPlayer.setVoiceAssistantDucking(false)
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {
            }
        }
    }

    fun triggerManualWake(promptText: String = "Listening...") {
        if (!hasRecordAudioPermission()) return
        SpotifyHiddenWebPlayer.installChromiumAudioFocusShield(appContext)
        isAwakeForCommandFlow.value = true
        statusTextFlow.value = promptText
        ClaraInAppMusicPlayer.setVoiceAssistantDucking(true)
        onWakeWordDetected?.invoke()
        scheduleWakeWindowTimeout(10_000L)
        if (!isSessionActive) {
            startListening()
        }
    }

    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isBlank() || !isTtsReady) return
        val bridge = runCatching { DeviceRepository.loadBridge(appContext) }.getOrNull()
        if (bridge != null && !bridge.enableVoiceAnnouncements) return
        SpotifyHiddenWebPlayer.installChromiumAudioFocusShield(appContext)
        ClaraInAppMusicPlayer.setVoiceAssistantDucking(true)
        try {
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "clara_utt_${System.currentTimeMillis()}")
        } catch (_: Exception) {
            ClaraInAppMusicPlayer.setVoiceAssistantDucking(false)
        }
    }

    suspend fun synthesizeToWavFile(text: String, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isBlank()) return@withContext false

        var waitLoops = 0
        while (!isTtsReady && waitLoops < 25) {
            delay(100L)
            waitLoops++
        }
        val engine = tts ?: return@withContext false
        if (!isTtsReady) return@withContext false

        val utteranceId = "clara_wav_${System.currentTimeMillis()}_${clean.hashCode()}"
        val deferred = CompletableDeferred<Boolean>()
        pendingWavSyntheses[utteranceId] = deferred

        val startCode = try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()
            engine.synthesizeToFile(clean, Bundle(), outputFile, utteranceId)
        } catch (e: Exception) {
            Log.w(TAG, "synthesizeToWavFile error: ${e.message}")
            pendingWavSyntheses.remove(utteranceId)
            return@withContext false
        }

        if (startCode != TextToSpeech.SUCCESS) {
            pendingWavSyntheses.remove(utteranceId)
            return@withContext false
        }

        val completedOk = withTimeoutOrNull(6_000L) {
            deferred.await()
        } ?: false
        pendingWavSyntheses.remove(utteranceId)
        completedOk && outputFile.exists() && outputFile.length() > 128L
    }

    fun release() {
        stopListening()
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {
            }
            speechRecognizer = null
            try {
                tts?.shutdown()
            } catch (_: Exception) {
            }
            tts = null
        }
    }

    /**
     * Configures a long-lived continuous recognition session (60-second silence tolerance)
     * so Android keeps a single recognition stream open instead of cycling the microphone
     * and flashing the status bar indicator every 1.5 seconds.
     */
    private fun buildLongSessionRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60_000L)
        }
    }

    private fun scheduleRestartWithCooldown(delayMs: Long) {
        if (!isEnabled) return
        restartCooldownJob?.cancel()
        restartCooldownJob = assistantScope.launch {
            delay(delayMs)
            if (isEnabled && !isSessionActive) {
                startListening()
            }
        }
    }

    private fun scheduleWakeWindowTimeout(timeoutMs: Long = 8_500L) {
        wakeWindowTimeoutJob?.cancel()
        wakeWindowTimeoutJob = assistantScope.launch {
            delay(timeoutMs)
            if (isAwakeForCommandFlow.value && !isSpeakingResponse) {
                stopListening()
                statusTextFlow.value = "Tap Mic to Speak"
            }
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isSessionActive = true
            isListeningFlow.value = true
            SpotifyHiddenWebPlayer.installChromiumAudioFocusShield(appContext)
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isSessionActive = false
            isListeningFlow.value = false
        }

        override fun onError(error: Int) {
            isSessionActive = false
            isListeningFlow.value = false
            isAwakeForCommandFlow.value = false
            ClaraInAppMusicPlayer.setVoiceAssistantDucking(false)
            statusTextFlow.value = "Tap Mic to Speak"
        }

        override fun onResults(results: Bundle?) {
            isSessionActive = false
            isListeningFlow.value = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = matches?.firstOrNull()?.trim().orEmpty()
            if (best.isNotBlank()) {
                handleRecognizedUtterance(best, isFinalResult = true)
            }
            isAwakeForCommandFlow.value = false
            ClaraInAppMusicPlayer.setVoiceAssistantDucking(false)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (partial.isNotBlank()) {
                handleRecognizedUtterance(partial, isFinalResult = false)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleRecognizedUtterance(rawTranscript: String, isFinalResult: Boolean) {
        if (isSpeakingResponse) return
        val lower = rawTranscript.lowercase(Locale.getDefault()).trim()
        if (lower.isBlank()) return

        lastTranscriptFlow.value = rawTranscript

        val wakePrefixes = listOf(
            "hey clara",
            "hi clara",
            "ok clara",
            "okay clara",
            "clara",
            "hey jarvis",
            "hi jarvis",
            "jarvis"
        )

        val matchedWake = wakePrefixes.firstOrNull { wake ->
            lower == wake || lower.startsWith("$wake ") || lower.contains(" $wake ")
        }

        if (matchedWake != null) {
            if (!isAwakeForCommandFlow.value) {
                isAwakeForCommandFlow.value = true
                statusTextFlow.value = "Listening..."
                ClaraInAppMusicPlayer.setVoiceAssistantDucking(true)
                onWakeWordDetected?.invoke()
            }
            scheduleWakeWindowTimeout(9_000L)

            val afterWake = lower.substringAfter(matchedWake).trim()
                .removePrefix(",")
                .removePrefix(".")
                .trim()

            if (afterWake.length >= 3) {
                if (isFinalResult) {
                    partialCommandCommitJob?.cancel()
                    commitCommandOnce(afterWake)
                } else {
                    // Fast 550ms debounce on streaming partial results for immediate device response
                    partialCommandCommitJob?.cancel()
                    partialCommandCommitJob = assistantScope.launch {
                        delay(550L)
                        commitCommandOnce(afterWake)
                    }
                }
            }
            return
        }

        // If already woken manually or by a prior "Hey Clara", accept the follow-up command directly
        if (isAwakeForCommandFlow.value) {
            if (isFinalResult) {
                partialCommandCommitJob?.cancel()
                commitCommandOnce(lower)
            } else if (lower.length >= 3) {
                partialCommandCommitJob?.cancel()
                partialCommandCommitJob = assistantScope.launch {
                    delay(550L)
                    commitCommandOnce(lower)
                }
            }
        }
    }

    private fun commitCommandOnce(cleanCommand: String) {
        val now = System.currentTimeMillis()
        if (cleanCommand.equals(lastProcessedCommandText, ignoreCase = true) &&
            (now - lastProcessedTimestampMs) < 3_500L
        ) {
            return
        }
        lastProcessedCommandText = cleanCommand
        lastProcessedTimestampMs = now
        isAwakeForCommandFlow.value = false
        statusTextFlow.value = "Heard: \"$cleanCommand\""

        // Route exclusively through the primary command handler if attached (AutomationEngine),
        // preventing duplicate execution of music/volume/weather commands.
        val externalHandler = onCommandRecognized
        if (externalHandler != null) {
            externalHandler.invoke(cleanCommand)
        } else {
            executeBuiltInSmartHomeCommand(cleanCommand)
        }

        assistantScope.launch {
            delay(1_200L)
            if (!isSpeakingResponse && !isAwakeForCommandFlow.value) {
                ClaraInAppMusicPlayer.setVoiceAssistantDucking(false)
                statusTextFlow.value = "Say \"Hey Clara\""
            }
        }
    }

    private fun executeBuiltInSmartHomeCommand(command: String) {
        val lower = command.lowercase(Locale.getDefault()).trim()
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        when {
            lower in listOf("pause", "pause music", "stop", "stop music") -> {
                if (audioManager != null) {
                    if (JarvisLiveBridge.isPlayingFlow.value) {
                        ClaraInAppMusicPlayer.togglePlayPause(audioManager, assistantScope)
                    }
                } else {
                    SpotifyHiddenWebPlayer.pausePlayback()
                }
                speak("Paused.")
            }

            lower in listOf("resume", "resume music", "continue", "play") -> {
                if (audioManager != null && !JarvisLiveBridge.isPlayingFlow.value) {
                    ClaraInAppMusicPlayer.togglePlayPause(audioManager, assistantScope)
                }
                speak("Resuming playback.")
            }

            lower.contains("next song") || lower.contains("skip") || lower == "next" -> {
                if (audioManager != null) {
                    ClaraInAppMusicPlayer.skipToNext(audioManager, assistantScope)
                }
                speak("Skipping to next track.")
            }

            lower.contains("previous song") || lower == "previous" || lower == "go back" -> {
                if (audioManager != null) {
                    ClaraInAppMusicPlayer.skipToPrevious(audioManager, assistantScope)
                }
                speak("Going back.")
            }

            lower.startsWith("play ") && lower.length > 5 -> {
                val query = command.substring(5).trim()
                    .replace(Regex("\\s+on\\s+spotify$", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (query.isNotBlank()) {
                    speak("Playing $query")
                    assistantScope.launch(Dispatchers.IO) {
                        val provider = if (NativeMusicCatalogClient.isSpotifySignedIn(appContext)) {
                            MusicProvider.SPOTIFY
                        } else {
                            MusicProvider.YOUTUBE_MUSIC
                        }
                        val results = NativeMusicCatalogClient.searchCatalog(
                            context = appContext,
                            provider = provider,
                            query = query
                        )
                        if (results.isNotEmpty()) {
                            ClaraInAppMusicPlayer.playTrackQueue(
                                items = results,
                                startIndex = 0,
                                scope = assistantScope
                            )
                        }
                    }
                }
            }

            lower.contains("volume") -> {
                val pctMatch = Regex("(\\d{1,3})").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (pctMatch != null) {
                    val clamped = pctMatch.coerceIn(0, 100)
                    ClaraInAppMusicPlayer.setVolumePercent(clamped.toFloat())
                    if (audioManager != null) {
                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val target = ((clamped / 100f) * max).roundToInt().coerceIn(0, max)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    }
                    speak("Volume set to $clamped percent.")
                }
            }

            lower.contains("weather") || lower.contains("temperature outside") -> {
                val w = JarvisLiveBridge.weatherFlow.value
                speak("It is currently ${w.temperatureCelsius} degrees and ${w.conditionText}.")
            }
        }
    }

    companion object {
        private const val TAG = "ClaraVoiceAssistant"

        val isAssistantEnabledFlow = MutableStateFlow(true)
        val isListeningFlow = MutableStateFlow(false)
        val isAwakeForCommandFlow = MutableStateFlow(false)
        val lastTranscriptFlow = MutableStateFlow("")
        val statusTextFlow = MutableStateFlow("Say \"Hey Clara\"")

        @Volatile
        private var activeInstance: ClaraVoiceAssistantManager? = null

        fun getOrCreate(
            context: Context,
            onCommandRecognized: ((String) -> Unit)? = null,
            onWakeWordDetected: (() -> Unit)? = null
        ): ClaraVoiceAssistantManager {
            return activeInstance ?: synchronized(this) {
                activeInstance ?: ClaraVoiceAssistantManager(
                    context = context.applicationContext,
                    onCommandRecognized = onCommandRecognized,
                    onWakeWordDetected = onWakeWordDetected
                ).also { activeInstance = it }
            }
        }

        fun start(context: Context) {
            getOrCreate(context).startListening()
        }

        fun stop() {
            activeInstance?.stopListening()
        }

        fun triggerWake(context: Context) {
            getOrCreate(context).triggerManualWake()
        }

        fun speak(context: Context, text: String) {
            getOrCreate(context).speak(text)
        }


        suspend fun synthesizeTextToWavFile(
            context: Context,
            text: String,
            outputFile: File
        ): Boolean {
            return getOrCreate(context).synthesizeToWavFile(text, outputFile)
        }
    }
}
