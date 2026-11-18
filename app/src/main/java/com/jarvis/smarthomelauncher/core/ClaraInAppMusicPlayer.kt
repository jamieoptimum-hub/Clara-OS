package com.jarvis.smarthomelauncher.core

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------
// NATIVE MUSIC & PLAYLIST MODELS (100% In-App Compose + Cover Art + Full Streams)
// ---------------------------------------------------------------------------
enum class NativeMusicItemType(val label: String) {
    PLAYLIST("Playlist"),
    TRACK("Song"),
    ALBUM("Album"),
    STATION("Radio / Mix")
}

enum class ActiveAudioEngine {
    NONE,
    EXO_PLAYER,
    SPOTIFY_WEB_SDK,
    CAST
}

data class NativeMusicItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val subtitle: String,
    val provider: MusicProvider,
    val itemType: NativeMusicItemType = NativeMusicItemType.TRACK,
    val playbackUriOrQuery: String = "",
    val streamUrl: String = "",
    val artworkUrl: String = "",
    val durationMs: Long = 0L,
    val trackCount: Int = 0,
    val isSavedInLibrary: Boolean = false
)

data class ResolvedAudioStream(
    val streamUrl: String,
    val durationMs: Long,
    val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

// ---------------------------------------------------------------------------
// IN-APP AUDIO PLAYER, LIVE RADIO STREAMER & CAST ROUTER
// - Explicit ActiveAudioEngine authority (NONE, EXO_PLAYER, SPOTIFY_WEB_SDK, CAST)
// - For Spotify: Verifies the clicked track at currentIndex has a valid 22-char
//   Spotify URI, dispatches to SpotifyHiddenWebPlayer when signed in, and provides
//   an automatic instant ExoPlayer stream fallback (onPlaybackFallbackNeeded).
// - Progress ticker runs strictly when ExoPlayer reaches Player.STATE_READY or
//   when SPOTIFY_WEB_SDK / CAST is actively playing.
// ---------------------------------------------------------------------------
object ClaraInAppMusicPlayer {
    private const val TAG = "ClaraAudioPlayer"
    private const val STANDARD_WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val YOUTUBE_VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

    @Volatile
    var activeEngine: ActiveAudioEngine = ActiveAudioEngine.NONE
        private set

    private var exoPlayer: ExoPlayer? = null
    private var currentExoStreamUrl: String = ""
    private var currentExoUserAgent: String = ""
    private var currentExoIsLiveRadio: Boolean = false
    private var currentExoExpectedDurationMs: Long = 0L
    private var currentExoSearchKey: String = ""
    private var currentExoScope: CoroutineScope? = null
    private var exoRetryAttemptCount: Int = 0
    private var exoRetryJob: Job? = null
    private val exoBackoffDelaysMs = longArrayOf(1_000L, 3_000L, 6_000L)
    private var progressTickerJob: Job? = null
    private var resolveTrackJob: Job? = null
    private var prefetchNextJob: Job? = null
    private var prewarmBatchJob: Job? = null
    private val playbackSessionNonce = java.util.concurrent.atomic.AtomicLong(0L)

    private val currentQueue = mutableListOf<NativeMusicItem>()
    private var currentIndex: Int = -1

    @Volatile
    private var isVoiceAssistantDucked: Boolean = false

    @Volatile
    private var masterVolumeFraction: Float = 1.0f

    val activeArtworkUrlFlow = MutableStateFlow("")
    val activeRadioStationFlow = MutableStateFlow<RadioStationStream?>(null)
    val isRadioPlayingFlow = MutableStateFlow(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var selectedCastEndpoints: List<Pair<String, Int>> = emptyList()

    @Volatile
    private var castSimulatedPositionMs: Long = 0L

    @Volatile
    private var castSimulatedDurationMs: Long = 210_000L

    private val streamCache = ConcurrentHashMap<String, ResolvedAudioStream>()

    /**
     * F-15: Delegates to ClaraApplication canonical startup Context so unloading or
     * resetting the music player never breaks hardware dispatch or ClaraSecretVault access.
     */
    fun currentAppContext(): Context? = ClaraApplication.appContextOrNull(appContext)

    /**
     * Returns true ONLY when ExoPlayer or Google Cast is the active playback authority,
     * never blocking SpotifyHiddenWebPlayer when `activeEngine == ActiveAudioEngine.SPOTIFY_WEB_SDK`.
     */
    fun isLocalOrCastEngineActivelyPlaying(): Boolean {
        if (activeEngine == ActiveAudioEngine.SPOTIFY_WEB_SDK) return false
        val localActive = activeEngine == ActiveAudioEngine.EXO_PLAYER &&
                runCatching { exoPlayer != null }.getOrDefault(false)
        val castOrRadioActive = activeEngine == ActiveAudioEngine.CAST ||
                isRadioPlayingFlow.value ||
                activeRadioStationFlow.value != null
        return localActive || castOrRadioActive
    }

    fun initContextIfNeeded(context: Context) {
        if (appContext == null) {
            appContext = ClaraApplication.requireAppContext(context)
        }
        SpotifyHiddenWebPlayer.ensureInitialized(context) {
            val activeScope = currentExoScope ?: CoroutineScope(Dispatchers.Main)
            if (currentIndex + 1 < currentQueue.size) {
                currentIndex++
                playCurrentQueueIndex(activeScope)
            } else {
                JarvisLiveBridge.isPlayingFlow.value = false
                progressTickerJob?.cancel()
            }
        }
        SpotifyHiddenWebPlayer.setOnPlaybackFallbackNeededListener {
            val activeScope = currentExoScope ?: CoroutineScope(Dispatchers.Main)
            fallbackCurrentSpotifyTrackToDirectStream(activeScope)
        }
    }

    private fun effectiveExoVolume(): Float {
        val duckMultiplier = if (isVoiceAssistantDucked) 0.18f else 1.0f
        return (masterVolumeFraction.coerceIn(0f, 1f) * duckMultiplier).coerceIn(0f, 1f)
    }

    /**
     * Smoothly ducks (lowers) music and live radio volume to 18% while the user is actively
     * speaking a command after saying "Hey Clara" / "Jarvis" (just like a Google Nest Mini),
     * and restores volume immediately after the voice command finishes.
     */
    fun setVoiceAssistantDucking(isDucked: Boolean) {
        isVoiceAssistantDucked = isDucked
        CoroutineScope(Dispatchers.Main).launch {
            try {
                exoPlayer?.volume = effectiveExoVolume()
            } catch (_: Exception) {
            }
            SpotifyHiddenWebPlayer.setVoiceDucking(isDucked)
        }
    }

    // -----------------------------------------------------------------------
    // BACKGROUND STREAM PRE-WARMING FOR PLAYLISTS & SEARCH RESULTS
    // -----------------------------------------------------------------------
    fun prewarmStreamsForTracks(
        items: List<NativeMusicItem>,
        scope: CoroutineScope,
        maxTracks: Int = 4
    ) {
        if (items.isEmpty()) return
        prewarmBatchJob?.cancel()
        prewarmBatchJob = scope.launch(Dispatchers.IO) {
            for (item in items.take(maxTracks)) {
                if (!isActive) break
                if (item.itemType == NativeMusicItemType.STATION || item.provider == MusicProvider.SPOTIFY) continue
                val displayArtist = item.subtitle.substringBefore("\u2022").trim()
                val queryKey = resolveDirectQueryOrVideoId(item, displayArtist)
                resolveCachedOrFetchStream(queryKey, item.title)
            }
        }
    }

    // -----------------------------------------------------------------------
    // LIVE RADIO STREAMING & CAST ROUTING
    // -----------------------------------------------------------------------
    fun playRadioStation(
        station: RadioStationStream,
        scope: CoroutineScope
    ) {
        val cleanUrl = station.streamUrl.trim()
        if (!cleanUrl.startsWith("http", ignoreCase = true)) return

        val sessionId = playbackSessionNonce.incrementAndGet()
        resolveTrackJob?.cancel()
        prefetchNextJob?.cancel()
        exoRetryJob?.cancel()
        progressTickerJob?.cancel()

        // 1. Hard-stop Spotify WebView and any previous local stream immediately so they never overlap
        SpotifyHiddenWebPlayer.hardStopAndSilence()
        stopLocalPlayerOnly()

        activeRadioStationFlow.value = station
        isRadioPlayingFlow.value = true

        val radioItem = NativeMusicItem(
            id = station.id,
            title = station.name,
            subtitle = station.genreOrTagline.ifBlank { "Live Radio" },
            provider = MusicProvider.YOUTUBE_MUSIC,
            itemType = NativeMusicItemType.STATION,
            playbackUriOrQuery = station.name,
            streamUrl = cleanUrl,
            durationMs = 0L
        )
        currentQueue.clear()
        currentQueue.add(radioItem)
        currentIndex = 0

        JarvisLiveBridge.activeTrackFlow.value = "${station.name} \u2022 ${station.genreOrTagline.ifBlank { "LIVE" }}"
        activeArtworkUrlFlow.value = ""
        JarvisLiveBridge.isPlayingFlow.value = true
        JarvisLiveBridge.trackPositionMsFlow.value = 0L
        JarvisLiveBridge.trackDurationMsFlow.value = 0L

        val castTargets = selectedCastEndpoints
        if (castTargets.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                if (playbackSessionNonce.get() != sessionId) return@launch
                castTargets.map { (ip, port) ->
                    async(Dispatchers.IO) {
                        GoogleCastLanClient.castDirectAudioStreamToSpeaker(
                            ip = ip,
                            port = port,
                            mediaUrl = cleanUrl,
                            title = station.name,
                            subtitle = station.genreOrTagline.ifBlank { "Clara Live Radio" }
                        )
                    }
                }.awaitAll()
            }
        } else {
            scope.launch(Dispatchers.Main) {
                if (playbackSessionNonce.get() != sessionId) return@launch
                startLocalExoPlayer(
                    streamUrl = cleanUrl,
                    userAgent = STANDARD_WEB_UA,
                    searchQuery = station.name,
                    expectedDurationMs = 0L,
                    scope = scope,
                    isLiveRadioStream = true
                )
            }
        }
    }

    fun toggleRadioStation(
        station: RadioStationStream,
        audioManager: AudioManager,
        scope: CoroutineScope
    ) {
        val currentRadio = activeRadioStationFlow.value
        if (currentRadio?.id == station.id) {
            val nowPlaying = togglePlayPause(audioManager, scope)
            isRadioPlayingFlow.value = nowPlaying
        } else {
            playRadioStation(station, scope)
        }
    }

    fun cycleRadioStation(
        stations: List<RadioStationStream>,
        forward: Boolean,
        scope: CoroutineScope
    ) {
        if (stations.isEmpty()) return
        val currentId = activeRadioStationFlow.value?.id
        val curIdx = stations.indexOfFirst { it.id == currentId }
        val nextIdx = when {
            curIdx < 0 -> 0
            forward -> (curIdx + 1) % stations.size
            else -> if (curIdx - 1 >= 0) curIdx - 1 else stations.lastIndex
        }
        playRadioStation(stations[nextIdx], scope)
    }

    fun stopRadioPlayback(scope: CoroutineScope) {
        activeRadioStationFlow.value = null
        isRadioPlayingFlow.value = false
        stopLocalPlayerOnly()
        JarvisLiveBridge.isPlayingFlow.value = false
        if (selectedCastEndpoints.isNotEmpty()) {
            val endpoints = selectedCastEndpoints
            scope.launch(Dispatchers.IO) {
                endpoints.forEach { (ip, port) ->
                    GoogleCastLanClient.stopCastMediaOnSpeaker(ip, port)
                }
            }
        }
    }

    fun selectTabletSpeakerOutput(scope: CoroutineScope) {
        val previousEndpoints = selectedCastEndpoints
        selectedCastEndpoints = emptyList()
        JarvisLiveBridge.activeCastTargetNameFlow.value = "Tablet Speaker"

        val activeRadio = activeRadioStationFlow.value
        if (activeRadio != null && isRadioPlayingFlow.value) {
            if (previousEndpoints.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    previousEndpoints.forEach { (ip, port) ->
                        GoogleCastLanClient.stopCastMediaOnSpeaker(ip, port)
                    }
                }
            }
            playRadioStation(activeRadio, scope)
            return
        }

        val ctx = appContext
        val currentTrack = currentQueue.getOrNull(currentIndex)
        if (currentTrack?.provider == MusicProvider.SPOTIFY ||
            (ctx != null && NativeMusicCatalogClient.isSpotifySignedIn(ctx))
        ) {
            scope.launch(Dispatchers.Main) {
                val validCtx = appContext ?: return@launch
                SpotifyHiddenWebPlayer.ensureInitialized(validCtx)
                val token = NativeMusicCatalogClient.getCachedSpotifyTokenSync(validCtx)
                SpotifyHiddenWebPlayer.transferPlaybackToTarget("Tablet Speaker", token)
            }
        }

        if (previousEndpoints.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                previousEndpoints.forEach { (ip, port) ->
                    GoogleCastLanClient.stopCastMediaOnSpeaker(ip, port)
                }
            }
        }
    }

    fun selectCastSpeakerOutput(
        targetName: String,
        endpoints: List<Pair<String, Int>>,
        scope: CoroutineScope
    ) {
        val handoffPositionMs = runCatching {
            exoPlayer?.currentPosition?.takeIf { it > 0L }
        }.getOrNull() ?: JarvisLiveBridge.trackPositionMsFlow.value.coerceAtLeast(0L)
        val handoffPositionSeconds = (handoffPositionMs / 1000.0).coerceAtLeast(0.0)

        selectedCastEndpoints = endpoints
        JarvisLiveBridge.activeCastTargetNameFlow.value = targetName
        stopLocalPlayerOnly()
        val activeRadio = activeRadioStationFlow.value
        if (activeRadio != null && isRadioPlayingFlow.value && endpoints.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val anyRadioCastOk = endpoints.map { (ip, port) ->
                    async(Dispatchers.IO) {
                        GoogleCastLanClient.castDirectAudioStreamToSpeaker(
                            ip = ip,
                            port = port,
                            mediaUrl = activeRadio.streamUrl,
                            title = activeRadio.name,
                            subtitle = activeRadio.genreOrTagline.ifBlank { "Clara Live Radio" }
                        )
                    }
                }.awaitAll().any { it }
                JarvisLiveBridge.isPlayingFlow.value = anyRadioCastOk
                isRadioPlayingFlow.value = anyRadioCastOk
            }
            return
        }
        val currentTrack = currentQueue.getOrNull(currentIndex)
        val ctx = appContext
        if (endpoints.isNotEmpty() &&
            (currentTrack?.provider == MusicProvider.SPOTIFY ||
                    (currentTrack == null && ctx != null && NativeMusicCatalogClient.isSpotifySignedIn(ctx)))
        ) {
            scope.launch(Dispatchers.IO) {
                val validCtx = appContext ?: return@launch
                val token = NativeMusicCatalogClient.getValidSpotifyTokenInternal(validCtx)
                    ?: NativeMusicCatalogClient.getCachedSpotifyTokenSync(validCtx)
                GoogleCastLanClient.wakeSpotifyConnectOnCastSpeakers(endpoints, token)
                withContext(Dispatchers.Main) {
                    activeEngine = ActiveAudioEngine.SPOTIFY_WEB_SDK
                    SpotifyHiddenWebPlayer.ensureInitialized(validCtx)
                    SpotifyHiddenWebPlayer.transferPlaybackToTarget(targetName, token)
                }
            }
            return
        }

        if (currentTrack != null && endpoints.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val displayArtist = currentTrack.subtitle.substringBefore("\u2022").trim()
                val directTrackUrl = currentTrack.streamUrl.takeIf { it.startsWith("http") && !isShortPreviewUrl(it) }
                val queryKey = resolveDirectQueryOrVideoId(currentTrack, displayArtist)
                val resolvedStream = if (directTrackUrl != null) {
                    null
                } else {
                    resolveCachedOrFetchStream(queryKey, currentTrack.title)
                }
                val resolvedUrl = directTrackUrl ?: resolvedStream?.streamUrl.orEmpty()
                val resolvedDurationMs = currentTrack.durationMs.takeIf { it > 0L }
                    ?: resolvedStream?.durationMs?.takeIf { it > 0L }
                    ?: 210_000L

                if (resolvedUrl.isNotBlank()) {
                    SpotifyHiddenWebPlayer.pausePlayback()
                    val anyCastOk = endpoints.map { (ip, port) ->
                        async(Dispatchers.IO) {
                            GoogleCastLanClient.castDirectAudioStreamToSpeaker(
                                ip = ip,
                                port = port,
                                mediaUrl = resolvedUrl,
                                title = currentTrack.title,
                                subtitle = displayArtist,
                                startPositionSeconds = handoffPositionSeconds
                            )
                        }
                    }.awaitAll().any { it }
                    if (anyCastOk) {
                        val clampedHandoff = handoffPositionMs.coerceIn(0L, resolvedDurationMs)
                        castSimulatedPositionMs = clampedHandoff
                        castSimulatedDurationMs = resolvedDurationMs
                        JarvisLiveBridge.trackPositionMsFlow.value = clampedHandoff
                        JarvisLiveBridge.trackDurationMsFlow.value = resolvedDurationMs
                        JarvisLiveBridge.isPlayingFlow.value = true
                        startProgressTicker(scope)
                    } else {
                        JarvisLiveBridge.isPlayingFlow.value = false
                    }
                }
            }
        }
    }

    fun playTrackQueue(
        items: List<NativeMusicItem>,
        startIndex: Int = 0,
        scope: CoroutineScope
    ) {
        if (items.isEmpty()) return
        currentExoScope = scope
        val firstItem = items.getOrNull(startIndex.coerceIn(0, items.lastIndex))
        if (firstItem?.itemType != NativeMusicItemType.STATION) {
            activeRadioStationFlow.value = null
            isRadioPlayingFlow.value = false
        }
        currentQueue.clear()
        currentQueue.addAll(items)
        currentIndex = startIndex.coerceIn(0, items.lastIndex)
        playCurrentQueueIndex(scope)
    }

    private fun isShortPreviewUrl(url: String): Boolean {
        if (url.isBlank()) return true
        val lower = url.lowercase()
        return lower.contains("mzstatic.com") ||
                lower.contains("p.scdn.co/mp3-preview") ||
                lower.contains("audio-ssl.itunes.apple.com")
    }

    private fun resolveDirectQueryOrVideoId(item: NativeMusicItem, displayArtist: String): String {
        val rawUriOrQuery = item.playbackUriOrQuery.trim()
        if (YOUTUBE_VIDEO_ID_REGEX.matches(rawUriOrQuery)) {
            return rawUriOrQuery
        }
        val fromId = item.id.removePrefix("yt_vid_").trim()
        if (YOUTUBE_VIDEO_ID_REGEX.matches(fromId)) {
            return fromId
        }
        return "${item.title} $displayArtist".trim()
    }

    private suspend fun resolveCachedOrFetchStream(
        searchQueryOrVideoId: String,
        originalTrackTitle: String
    ): ResolvedAudioStream? {
        val key = searchQueryOrVideoId.trim()
        val cacheKey = key.lowercase()
        streamCache[cacheKey]?.let { cached ->
            if (cached.streamUrl.startsWith("http")) return cached
        }
        val fetched = NativeMusicCatalogClient.resolveFullLengthAudioStreamDetailed(
            query = key,
            originalTrackTitle = originalTrackTitle
        )
        if (fetched != null && fetched.streamUrl.startsWith("http")) {
            streamCache[cacheKey] = fetched
        }
        return fetched
    }

    /**
     * Pre-resolves both the direct audio stream and Spotify track URI of the next track in
     * `currentQueue` in the background so pressing Next starts instantaneously.
     */
    private fun prefetchNextTrackInQueue(scope: CoroutineScope) {
        prefetchNextJob?.cancel()
        val nextIdx = if (currentIndex + 1 < currentQueue.size) currentIndex + 1 else return
        val nextItem = currentQueue.getOrNull(nextIdx) ?: return
        if (nextItem.itemType == NativeMusicItemType.STATION ||
            nextItem.id.startsWith("plex_") ||
            nextItem.playbackUriOrQuery.startsWith("plex:")
        ) return

        prefetchNextJob = scope.launch(Dispatchers.IO) {
            val displayArtist = nextItem.subtitle.substringBefore("\u2022").trim()
            if (!nextItem.streamUrl.startsWith("http") || isShortPreviewUrl(nextItem.streamUrl)) {
                val queryKey = buildCleanStreamSearchQuery(nextItem, displayArtist)
                val resolved = resolveCachedOrFetchStream(queryKey, nextItem.title)
                if (resolved != null && resolved.streamUrl.startsWith("http") && nextIdx in currentQueue.indices) {
                    currentQueue[nextIdx] = currentQueue[nextIdx].copy(
                        streamUrl = resolved.streamUrl,
                        durationMs = if (resolved.durationMs > 0L) resolved.durationMs else nextItem.durationMs
                    )
                }
            }
        }
    }

    private fun buildCleanStreamSearchQuery(item: NativeMusicItem, displayArtist: String): String {
        val rawKey = resolveDirectQueryOrVideoId(item, displayArtist).trim()
        val isSpotifyUriOrUrl = item.provider == MusicProvider.SPOTIFY ||
                rawKey.startsWith("spotify:", ignoreCase = true) ||
                rawKey.contains("open.spotify.com", ignoreCase = true)
        if (isSpotifyUriOrUrl) {
            return listOf(item.title.trim(), displayArtist)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { item.title.trim() }
        }
        return rawKey.ifBlank {
            listOf(item.title.trim(), displayArtist).filter { it.isNotBlank() }.joinToString(" ")
        }
    }

    fun fallbackCurrentSpotifyTrackToDirectStream(scope: CoroutineScope) {
        val targetIdx = currentIndex
        val item = currentQueue.getOrNull(targetIdx) ?: return
        if (activeRadioStationFlow.value != null && item.itemType != NativeMusicItemType.STATION) {
            return
        }
        val sessionId = playbackSessionNonce.get()
        val displayArtist = item.subtitle.substringBefore("\u2022").trim()
        val expectedBannerTitle = if (displayArtist.isNotBlank()) "${item.title} - $displayArtist" else item.title
        val queryKey = buildCleanStreamSearchQuery(item, displayArtist)

        Log.d(TAG, "Starting direct audio stream for '${item.title}' (query='$queryKey')")

        SpotifyHiddenWebPlayer.hardStopAndSilence()
        if (activeEngine == ActiveAudioEngine.SPOTIFY_WEB_SDK) {
            activeEngine = ActiveAudioEngine.NONE
        }

        resolveTrackJob?.cancel()
        resolveTrackJob = scope.launch(Dispatchers.IO) {
            var streamUrl = item.streamUrl
            var durationMs = item.durationMs
            var userAgent = STANDARD_WEB_UA

            if (!streamUrl.startsWith("http") || isShortPreviewUrl(streamUrl)) {
                val fullStream = resolveCachedOrFetchStream(
                    searchQueryOrVideoId = queryKey,
                    originalTrackTitle = item.title
                )
                if (fullStream != null && fullStream.streamUrl.startsWith("http")) {
                    streamUrl = fullStream.streamUrl
                    userAgent = fullStream.userAgent
                    if (fullStream.durationMs > 0L) {
                        durationMs = fullStream.durationMs
                    }
                }
            } else {
                streamCache[queryKey.lowercase()]?.let { cached ->
                    userAgent = cached.userAgent
                }
            }

            if (playbackSessionNonce.get() != sessionId || activeRadioStationFlow.value != null) {
                return@launch
            }

            if (!streamUrl.startsWith("http")) {
                withContext(Dispatchers.Main) {
                    if (playbackSessionNonce.get() == sessionId) {
                        activeEngine = ActiveAudioEngine.NONE
                        progressTickerJob?.cancel()
                        JarvisLiveBridge.activeTrackFlow.value = expectedBannerTitle
                        JarvisLiveBridge.isPlayingFlow.value = false
                    }
                }
                return@launch
            }

            if (targetIdx in currentQueue.indices && currentQueue[targetIdx].id == item.id) {
                currentQueue[targetIdx] = currentQueue[targetIdx].copy(
                    streamUrl = streamUrl,
                    durationMs = durationMs
                )
            }
                prefetchNextTrackInQueue(scope)

                val castTargets = selectedCastEndpoints
                if (castTargets.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        stopLocalPlayerOnly()
                        activeEngine = ActiveAudioEngine.CAST
                    }
                    castSimulatedPositionMs = 0L
                    castSimulatedDurationMs = if (durationMs > 0L) durationMs else 210_000L
                    JarvisLiveBridge.trackDurationMsFlow.value = castSimulatedDurationMs

                    val anyCastOk = castTargets.map { (ip, port) ->
                        async(Dispatchers.IO) {
                            GoogleCastLanClient.castDirectAudioStreamToSpeaker(
                                ip = ip,
                                port = port,
                                mediaUrl = streamUrl,
                                title = item.title,
                                subtitle = displayArtist
                            )
                        }
                    }.awaitAll().any { it }

                    if (playbackSessionNonce.get() == sessionId) {
                        JarvisLiveBridge.activeTrackFlow.value = expectedBannerTitle
                        if (anyCastOk) {
                            activeEngine = ActiveAudioEngine.CAST
                            JarvisLiveBridge.isPlayingFlow.value = true
                            startProgressTicker(scope)
                        } else {
                            activeEngine = ActiveAudioEngine.NONE
                            JarvisLiveBridge.isPlayingFlow.value = false
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (playbackSessionNonce.get() == sessionId && activeRadioStationFlow.value == null) {
                            JarvisLiveBridge.activeTrackFlow.value = expectedBannerTitle
                            startLocalExoPlayer(
                                streamUrl = streamUrl,
                                userAgent = userAgent,
                                searchQuery = queryKey,
                                expectedDurationMs = durationMs,
                                scope = scope,
                                isLiveRadioStream = false
                            )
                        }
                    }
                }
            }
        }

    private fun playCurrentQueueIndex(scope: CoroutineScope) {
        val item = currentQueue.getOrNull(currentIndex) ?: return
        currentExoScope = scope

        // If the queued item is a Live Radio Station, route through playRadioStation
        if (item.itemType == NativeMusicItemType.STATION && item.streamUrl.startsWith("http")) {
            val station = activeRadioStationFlow.value ?: RadioStationStream(
                id = item.id,
                name = item.title,
                genreOrTagline = item.subtitle,
                streamUrl = item.streamUrl
            )
            playRadioStation(station, scope)
            return
        }

        val sessionId = playbackSessionNonce.incrementAndGet()
        resolveTrackJob?.cancel()
        exoRetryJob?.cancel()
        progressTickerJob?.cancel()
        activeRadioStationFlow.value = null
        isRadioPlayingFlow.value = false
        stopLocalPlayerOnly()

        val ctx = appContext
        val spotifyUri = NativeMusicCatalogClient.extractSpotifyTrackUri(item)
        val isSpotifyItem = item.provider == MusicProvider.SPOTIFY ||
                spotifyUri.startsWith("spotify:track:") ||
                item.playbackUriOrQuery.startsWith("spotify:", ignoreCase = true) ||
                item.playbackUriOrQuery.contains("open.spotify.com", ignoreCase = true)

        if (!isSpotifyItem) {
            SpotifyHiddenWebPlayer.hardStopAndSilence()
        }

        val displayArtist = item.subtitle.substringBefore("\u2022").trim()
        val bannerTitle = if (displayArtist.isNotBlank()) "${item.title} - $displayArtist" else item.title
        JarvisLiveBridge.activeTrackFlow.value = bannerTitle
        activeArtworkUrlFlow.value = item.artworkUrl
        JarvisLiveBridge.isPlayingFlow.value = true
        JarvisLiveBridge.trackPositionMsFlow.value = 0L
        JarvisLiveBridge.trackDurationMsFlow.value = if (item.durationMs > 1000L) item.durationMs else 210_000L

        Log.d(TAG, "playCurrentQueueIndex idx=$currentIndex title='${item.title}' provider=${item.provider} uri='$spotifyUri'")

        // Resolve missing cover art in a separate non-blocking coroutine so audio starts immediately
        if (item.artworkUrl.isBlank()) {
            val targetIdx = currentIndex
            scope.launch(Dispatchers.IO) {
                val art = NativeMusicCatalogClient.resolveArtworkUrlForItem(item)
                if (!art.isNullOrBlank()) {
                    if (currentIndex == targetIdx) {
                        activeArtworkUrlFlow.value = art
                    }
                    if (targetIdx in currentQueue.indices) {
                        currentQueue[targetIdx] = currentQueue[targetIdx].copy(artworkUrl = art)
                    }
                }
            }
        }

        if (isSpotifyItem && ctx != null) {
            activeEngine = ActiveAudioEngine.SPOTIFY_WEB_SDK
            castSimulatedPositionMs = 0L
            castSimulatedDurationMs = if (item.durationMs > 1000L) item.durationMs else 210_000L

            if (spotifyUri.startsWith("spotify:track:")) {
                val queueUris = currentQueue.mapNotNull { qItem ->
                    NativeMusicCatalogClient.extractSpotifyTrackUri(qItem).takeIf { it.startsWith("spotify:track:") }
                }
                val startUriIndex = queueUris.indexOf(spotifyUri).coerceAtLeast(0)
                val token = NativeMusicCatalogClient.getCachedSpotifyTokenSync(ctx)
                val castEndpoints = selectedCastEndpoints
                val targetName = JarvisLiveBridge.activeCastTargetNameFlow.value

                if (castEndpoints.isNotEmpty() && targetName != "Tablet Speaker") {
                    scope.launch(Dispatchers.IO) {
                        val freshTok = NativeMusicCatalogClient.getValidSpotifyTokenInternal(ctx) ?: token
                        GoogleCastLanClient.wakeSpotifyConnectOnCastSpeakers(castEndpoints, freshTok)
                        if (playbackSessionNonce.get() != sessionId) return@launch
                        withContext(Dispatchers.Main) {
                            SpotifyHiddenWebPlayer.playSpotifyUriQueue(
                                context = ctx,
                                trackUris = if (queueUris.isNotEmpty()) queueUris else listOf(spotifyUri),
                                startIndex = startUriIndex,
                                preferredTargetName = targetName,
                                fallbackBearerToken = freshTok
                            )
                        }
                    }
                } else {
                    SpotifyHiddenWebPlayer.playSpotifyUriQueue(
                        context = ctx,
                        trackUris = if (queueUris.isNotEmpty()) queueUris else listOf(spotifyUri),
                        startIndex = startUriIndex,
                        preferredTargetName = targetName,
                        fallbackBearerToken = token
                    )
                }
                startProgressTicker(scope)
                prefetchNextTrackInQueue(scope)
                return
            }

            // Track came from a scraped playlist without a 22-char URI yet:
            // try catalog lookup first, and if that returns null, let SpotifyHiddenWebPlayer search & play in-WebView
            val targetIdx = currentIndex
            val searchFallbackUri = "spotify:search:${item.title} $displayArtist".trim()
            resolveTrackJob = scope.launch(Dispatchers.IO) {
                val lookedUpUri = NativeMusicCatalogClient.lookupSpotifyTrackUriBySearch(
                    context = ctx,
                    title = item.title,
                    artist = displayArtist
                )
                if (playbackSessionNonce.get() != sessionId) return@launch
                val finalUriToPlay = if (!lookedUpUri.isNullOrBlank() && lookedUpUri.startsWith("spotify:track:")) {
                    lookedUpUri
                } else {
                    searchFallbackUri
                }
                val freshTok = NativeMusicCatalogClient.getValidSpotifyTokenInternal(ctx)
                    ?: NativeMusicCatalogClient.getCachedSpotifyTokenSync(ctx)
                val castEndpoints = selectedCastEndpoints
                val targetName = JarvisLiveBridge.activeCastTargetNameFlow.value
                if (castEndpoints.isNotEmpty() && targetName != "Tablet Speaker") {
                    GoogleCastLanClient.wakeSpotifyConnectOnCastSpeakers(castEndpoints, freshTok)
                }
                withContext(Dispatchers.Main) {
                    if (playbackSessionNonce.get() != sessionId) return@withContext
                    if (finalUriToPlay.startsWith("spotify:track:") && targetIdx in currentQueue.indices) {
                        currentQueue[targetIdx] = currentQueue[targetIdx].copy(playbackUriOrQuery = finalUriToPlay)
                    }
                    activeEngine = ActiveAudioEngine.SPOTIFY_WEB_SDK
                    castSimulatedPositionMs = 0L
                    castSimulatedDurationMs = if (item.durationMs > 1000L) item.durationMs else 210_000L
                    SpotifyHiddenWebPlayer.playSpotifyUriQueue(
                        context = ctx,
                        trackUris = listOf(finalUriToPlay),
                        startIndex = 0,
                        preferredTargetName = targetName,
                        fallbackBearerToken = freshTok
                    )
                    startProgressTicker(scope)
                    prefetchNextTrackInQueue(scope)
                }
            }
            return
        }

        // Route YouTube Music or Plex tracks directly through ExoPlayer / Cast
        fallbackCurrentSpotifyTrackToDirectStream(scope)
    }

    private fun startLocalExoPlayer(
        streamUrl: String,
        userAgent: String,
        searchQuery: String,
        expectedDurationMs: Long,
        scope: CoroutineScope,
        isLiveRadioStream: Boolean = false
    ) {
        val ctx = appContext ?: return
        try {
            SpotifyHiddenWebPlayer.hardStopAndSilence()
            activeEngine = ActiveAudioEngine.EXO_PLAYER
            exoRetryJob?.cancel()
            exoRetryAttemptCount = 0
            currentExoStreamUrl = streamUrl
            currentExoIsLiveRadio = isLiveRadioStream
            currentExoExpectedDurationMs = expectedDurationMs
            currentExoSearchKey = searchQuery.lowercase().trim()
            currentExoScope = scope

            if (!isLiveRadioStream) {
                currentQueue.getOrNull(currentIndex)?.let { activeItem ->
                    val artist = activeItem.subtitle.substringBefore("\u2022").trim()
                    JarvisLiveBridge.activeTrackFlow.value =
                        if (artist.isNotBlank()) "${activeItem.title} - $artist" else activeItem.title
                }
            }

            if (expectedDurationMs > 1000L && !isLiveRadioStream) {
                JarvisLiveBridge.trackDurationMsFlow.value = expectedDurationMs
            }

            val existingPlayer = exoPlayer
            if (existingPlayer != null && currentExoUserAgent == userAgent && !isLiveRadioStream) {
                existingPlayer.stop()
                existingPlayer.clearMediaItems()
                existingPlayer.volume = effectiveExoVolume()
                existingPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
                existingPlayer.prepare()
                existingPlayer.playWhenReady = true
                JarvisLiveBridge.isPlayingFlow.value = true
                // Progress ticker starts strictly in Player.STATE_READY listener
                return
            }
            stopLocalPlayerOnly()
            activeEngine = ActiveAudioEngine.EXO_PLAYER
            currentExoStreamUrl = streamUrl
            currentExoUserAgent = userAgent
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(6000)
                .setReadTimeoutMs(6000)
            val mediaSourceFactory = DefaultMediaSourceFactory(ctx)
                .setDataSourceFactory(httpDataSourceFactory)
            // Fast-start 350ms buffer threshold for immediate playback start
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 15_000,
                    /* maxBufferMs = */ 50_000,
                    /* bufferForPlaybackMs = */ 350,
                    /* bufferForPlaybackAfterRebufferMs = */ 700
                )
                .build()
            val player = ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .build()
                .apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .setUsage(C.USAGE_MEDIA)
                            .build(),
                        false
                    )
                    volume = effectiveExoVolume()
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            val activeScope = currentExoScope ?: scope
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    exoRetryAttemptCount = 0
                                    if (currentExoIsLiveRadio) {
                                        isRadioPlayingFlow.value = playWhenReady
                                        JarvisLiveBridge.isPlayingFlow.value = playWhenReady
                                    } else {
                                        val reportedDur = duration
                                        val finalDur = when {
                                            reportedDur > 1000L && reportedDur != C.TIME_UNSET -> reportedDur
                                            currentExoExpectedDurationMs > 1000L -> currentExoExpectedDurationMs
                                            else -> 210_000L
                                        }
                                        JarvisLiveBridge.trackDurationMsFlow.value = finalDur
                                        JarvisLiveBridge.isPlayingFlow.value = playWhenReady
                                        startProgressTicker(activeScope)
                                    }
                                }
                                Player.STATE_ENDED -> {
                                    if (currentExoIsLiveRadio) {
                                        isRadioPlayingFlow.value = false
                                        JarvisLiveBridge.isPlayingFlow.value = false
                                    } else {
                                        JarvisLiveBridge.trackPositionMsFlow.value =
                                            JarvisLiveBridge.trackDurationMsFlow.value
                                        if (currentIndex + 1 < currentQueue.size) {
                                            currentIndex++
                                            playCurrentQueueIndex(activeScope)
                                        } else {
                                            JarvisLiveBridge.isPlayingFlow.value = false
                                            progressTickerJob?.cancel()
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            JarvisLiveBridge.isPlayingFlow.value = isPlaying
                            if (activeRadioStationFlow.value != null) {
                                isRadioPlayingFlow.value = isPlaying
                            }
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            val activeUrl = currentExoStreamUrl.ifBlank { streamUrl }
                            val activeScope = currentExoScope ?: scope
                            Log.w(TAG, "ExoPlayer error (${error.errorCodeName}) on $activeUrl (attempt ${exoRetryAttemptCount + 1}/3)")
                            if (exoRetryAttemptCount < exoBackoffDelaysMs.size) {
                                val delayMs = exoBackoffDelaysMs[exoRetryAttemptCount]
                                exoRetryAttemptCount++
                                val resumePosMs = currentPosition.coerceAtLeast(0L)
                                exoRetryJob?.cancel()
                                exoRetryJob = activeScope.launch(Dispatchers.Main) {
                                    delay(delayMs)
                                    val p = exoPlayer ?: return@launch
                                    if (!currentExoIsLiveRadio && currentExoSearchKey.isNotBlank()) {
                                        streamCache.remove(currentExoSearchKey)
                                    }
                                    p.setMediaItem(MediaItem.fromUri(activeUrl))
                                    if (!currentExoIsLiveRadio && resumePosMs > 1000L) {
                                        p.seekTo(resumePosMs)
                                    }
                                    p.prepare()
                                    p.playWhenReady = true
                                }
                                return
                            }
                            if (currentExoSearchKey.isNotBlank()) {
                                streamCache.remove(currentExoSearchKey)
                            }
                            JarvisLiveBridge.isPlayingFlow.value = false
                            if (currentExoIsLiveRadio) {
                                isRadioPlayingFlow.value = false
                            }
                            progressTickerJob?.cancel()
                        }
                    })
                    setMediaItem(MediaItem.fromUri(streamUrl))
                    prepare()
                    playWhenReady = true
                }
            exoPlayer = player
        } catch (e: Exception) {
            Log.e(TAG, "ExoPlayer error: ${e.message}")
            JarvisLiveBridge.isPlayingFlow.value = false
            if (isLiveRadioStream) {
                isRadioPlayingFlow.value = false
            }
        }
    }

    private fun startProgressTicker(scope: CoroutineScope) {
        progressTickerJob?.cancel()
        progressTickerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val player = exoPlayer
                if (activeEngine == ActiveAudioEngine.EXO_PLAYER && player != null) {
                    try {
                        // Gate progress ticking strictly on Player.STATE_READY and active playback
                        if (player.playbackState == Player.STATE_READY &&
                            player.isPlaying &&
                            activeRadioStationFlow.value == null
                        ) {
                            val pos = player.currentPosition.coerceAtLeast(0L)
                            val rawDur = player.duration
                            val dur = if (rawDur > 1000L && rawDur != C.TIME_UNSET) {
                                rawDur
                            } else {
                                JarvisLiveBridge.trackDurationMsFlow.value.coerceAtLeast(1000L)
                            }
                            JarvisLiveBridge.trackPositionMsFlow.value = pos.coerceAtMost(dur)
                            JarvisLiveBridge.trackDurationMsFlow.value = dur
                        }
                    } catch (_: Exception) {
                    }
                } else if (activeEngine == ActiveAudioEngine.SPOTIFY_WEB_SDK &&
                    selectedCastEndpoints.isNotEmpty() &&
                    JarvisLiveBridge.isPlayingFlow.value &&
                    activeRadioStationFlow.value == null
                ) {
                    val dur = JarvisLiveBridge.trackDurationMsFlow.value.takeIf { it > 1000L } ?: castSimulatedDurationMs
                    val nextPos = (JarvisLiveBridge.trackPositionMsFlow.value + 250L).coerceAtMost(dur)
                    JarvisLiveBridge.trackPositionMsFlow.value = nextPos
                    JarvisLiveBridge.trackDurationMsFlow.value = dur
                } else if (selectedCastEndpoints.isNotEmpty() && JarvisLiveBridge.isPlayingFlow.value && activeRadioStationFlow.value == null) {
                    castSimulatedPositionMs = (castSimulatedPositionMs + 250L).coerceAtMost(castSimulatedDurationMs)
                    JarvisLiveBridge.trackPositionMsFlow.value = castSimulatedPositionMs
                    JarvisLiveBridge.trackDurationMsFlow.value = castSimulatedDurationMs
                    if (castSimulatedPositionMs >= castSimulatedDurationMs) {
                        if (currentIndex + 1 < currentQueue.size) {
                            currentIndex++
                            playCurrentQueueIndex(scope)
                            break
                        } else {
                            JarvisLiveBridge.isPlayingFlow.value = false
                        }
                    }
                }
                delay(250L)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        if (activeRadioStationFlow.value != null) return
        val clamped = positionMs.coerceAtLeast(0L)
        JarvisLiveBridge.trackPositionMsFlow.value = clamped
        if (activeEngine == ActiveAudioEngine.SPOTIFY_WEB_SDK) {
            val ctx = appContext
            val token = if (ctx != null) NativeMusicCatalogClient.getCachedSpotifyTokenSync(ctx) else ""
            SpotifyHiddenWebPlayer.seekTo(clamped, token)
            return
        }
        val player = exoPlayer
        if (player != null) {
            try {
                player.seekTo(clamped)
                return
            } catch (_: Exception) {
            }
        }
        if (selectedCastEndpoints.isNotEmpty()) {
            castSimulatedPositionMs = clamped.coerceAtMost(castSimulatedDurationMs)
        }
    }

    fun setVolumePercent(volumePercent: Float) {
        masterVolumeFraction = (volumePercent.coerceIn(0f, 100f) / 100f)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                exoPlayer?.volume = effectiveExoVolume()
            } catch (_: Exception) {
            }
            if (activeEngine == ActiveAudioEngine.SPOTIFY_WEB_SDK) {
                val ctx = appContext
                val token = if (ctx != null) NativeMusicCatalogClient.getCachedSpotifyTokenSync(ctx) else ""
                SpotifyHiddenWebPlayer.setVolumePercent(volumePercent, token)
            }
        }
    }

    fun togglePlayPause(audioManager: AudioManager, scope: CoroutineScope): Boolean {
        val activeRadio = activeRadioStationFlow.value
        val player = exoPlayer
        val currentItem = currentQueue.getOrNull(currentIndex.coerceAtLeast(0))

        // 1. If currently playing on Spotify WebView or ExoPlayer (non-radio), pause everything immediately on first tap
        if (activeRadio == null && (JarvisLiveBridge.isPlayingFlow.value || player?.isPlaying == true)) {
            resolveTrackJob?.cancel()
            exoRetryJob?.cancel()
            progressTickerJob?.cancel()
            JarvisLiveBridge.isPlayingFlow.value = false
            val ctx = appContext
            val token = if (ctx != null) NativeMusicCatalogClient.getCachedSpotifyTokenSync(ctx) else ""
            SpotifyHiddenWebPlayer.togglePlayPause(false, token)
            try {
                player?.pause()
            } catch (_: Exception) {
            }
            if (selectedCastEndpoints.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    selectedCastEndpoints.forEach { (ip, port) ->
                        GoogleCastLanClient.stopCastMediaOnSpeaker(ip, port)
                    }
                }
            }
            return false
        }

        // 1B. If paused in Spotify Web SDK mode, resume the WebView directly
        if (activeEngine == ActiveAudioEngine.SPOTIFY_WEB_SDK && activeRadio == null && player == null) {
            val ctx = appContext
            val token = if (ctx != null) NativeMusicCatalogClient.getCachedSpotifyTokenSync(ctx) else ""
            JarvisLiveBridge.isPlayingFlow.value = true
            SpotifyHiddenWebPlayer.togglePlayPause(true, token)
            startProgressTicker(scope)
            return true
        }

        // If a Spotify track is queued (even if activeEngine was NONE), start it via playCurrentQueueIndex without silencing the WebView
        if (currentItem?.provider == MusicProvider.SPOTIFY && activeRadio == null && player == null) {
            if (currentIndex !in currentQueue.indices) {
                currentIndex = 0
            }
            playCurrentQueueIndex(scope)
            return true
        }

        // If the queue is empty and Spotify is signed in, toggle the in-app Spotify Web Player directly
        val ctx = appContext
        if (currentQueue.isEmpty() && activeRadio == null && player == null &&
            ctx != null && NativeMusicCatalogClient.isSpotifySignedIn(ctx)
        ) {
            activeEngine = ActiveAudioEngine.SPOTIFY_WEB_SDK
            val nextPlay = !JarvisLiveBridge.isPlayingFlow.value
            val token = NativeMusicCatalogClient.getCachedSpotifyTokenSync(ctx)
            SpotifyHiddenWebPlayer.togglePlayPause(nextPlay, token)
            JarvisLiveBridge.isPlayingFlow.value = nextPlay
            if (nextPlay) {
                startProgressTicker(scope)
            } else {
                progressTickerJob?.cancel()
            }
            return nextPlay
        }

        SpotifyHiddenWebPlayer.hardStopAndSilence()

        // 2. If a track stream is currently resolving in the background, allow pausing or restarting it
        if (resolveTrackJob?.isActive == true && player == null) {
            return if (JarvisLiveBridge.isPlayingFlow.value) {
                resolveTrackJob?.cancel()
                JarvisLiveBridge.isPlayingFlow.value = false
                false
            } else {
                playCurrentQueueIndex(scope)
                true
            }
        }

        // 3. If a local ExoPlayer instance exists, control it directly
        if (player != null) {
            return try {
                if (player.isPlaying) {
                    player.pause()
                    JarvisLiveBridge.isPlayingFlow.value = false
                    if (activeRadio != null) isRadioPlayingFlow.value = false
                    false
                } else {
                    if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                        if (activeRadio != null) {
                            playRadioStation(activeRadio, scope)
                        } else if (currentQueue.isNotEmpty()) {
                            playCurrentQueueIndex(scope)
                        } else {
                            player.prepare()
                            player.play()
                        }
                    } else {
                        player.play()
                    }
                    JarvisLiveBridge.isPlayingFlow.value = true
                    if (activeRadio != null) {
                        isRadioPlayingFlow.value = true
                    } else if (player.playbackState == Player.STATE_READY) {
                        startProgressTicker(scope)
                    }
                    true
                }
            } catch (_: Exception) {
                false
            }
        }

        // 4. If Live Radio is selected (either local or casting to Nest Mini speakers)
        if (activeRadio != null) {
            val currentlyPlaying = JarvisLiveBridge.isPlayingFlow.value
            return if (currentlyPlaying) {
                if (selectedCastEndpoints.isNotEmpty()) {
                    scope.launch(Dispatchers.IO) {
                        selectedCastEndpoints.forEach { (ip, port) ->
                            GoogleCastLanClient.stopCastMediaOnSpeaker(ip, port)
                        }
                    }
                }
                JarvisLiveBridge.isPlayingFlow.value = false
                isRadioPlayingFlow.value = false
                false
            } else {
                playRadioStation(activeRadio, scope)
                true
            }
        }

        // 5. If a track queue is loaded (local tablet speaker or Cast), play/pause the selected queue item
        if (currentQueue.isNotEmpty()) {
            if (currentIndex !in currentQueue.indices) {
                currentIndex = 0
            }
            val currentlyPlaying = JarvisLiveBridge.isPlayingFlow.value
            return if (currentlyPlaying) {
                if (selectedCastEndpoints.isNotEmpty()) {
                    scope.launch(Dispatchers.IO) {
                        selectedCastEndpoints.forEach { (ip, port) ->
                            GoogleCastLanClient.stopCastMediaOnSpeaker(ip, port)
                        }
                    }
                }
                stopLocalPlayerOnly()
                JarvisLiveBridge.isPlayingFlow.value = false
                false
            } else {
                playCurrentQueueIndex(scope)
                true
            }
        }

        dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        val actuallyPlaying = audioManager.isMusicActive
        JarvisLiveBridge.isPlayingFlow.value = actuallyPlaying
        return actuallyPlaying
    }

    fun skipToNext(audioManager: AudioManager, scope: CoroutineScope) {
        if (activeRadioStationFlow.value != null) {
            val ctx = appContext
            val stations = if (ctx != null) {
                DeviceRepository.loadBridge(ctx).allRadioStations
            } else {
                DEFAULT_RADIO_STATIONS
            }
            cycleRadioStation(stations, forward = true, scope = scope)
            return
        }
        if (currentQueue.isNotEmpty()) {
            currentIndex = (currentIndex + 1) % currentQueue.size
            playCurrentQueueIndex(scope)
        } else {
            dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_NEXT)
        }
    }

    fun skipToPrevious(audioManager: AudioManager, scope: CoroutineScope) {
        if (activeRadioStationFlow.value != null) {
            val ctx = appContext
            val stations = if (ctx != null) {
                DeviceRepository.loadBridge(ctx).allRadioStations
            } else {
                DEFAULT_RADIO_STATIONS
            }
            cycleRadioStation(stations, forward = false, scope = scope)
            return
        }
        if (currentQueue.isNotEmpty()) {
            currentIndex = if (currentIndex - 1 >= 0) currentIndex - 1 else currentQueue.lastIndex
            playCurrentQueueIndex(scope)
        } else {
            dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
    }

    private fun stopLocalPlayerOnly() {
        exoRetryJob?.cancel()
        progressTickerJob?.cancel()
        if (activeEngine == ActiveAudioEngine.EXO_PLAYER) {
            activeEngine = ActiveAudioEngine.NONE
        }
        currentExoStreamUrl = ""
        currentExoUserAgent = ""
        try {
            exoPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            exoPlayer?.release()
        } catch (_: Exception) {
        }
        exoPlayer = null
    }
}
