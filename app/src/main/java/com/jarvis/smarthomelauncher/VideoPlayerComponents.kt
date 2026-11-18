package com.jarvis.smarthomelauncher

import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Doorbell
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ClaraCamera"

// Default auto-pause timeout for inline cloud streams to protect monthly cloud data quotas
private const val DEFAULT_CLOUD_STREAM_AUTO_SAVER_TIMEOUT_MS = 120_000L

// Watchdog timeout before stepping down from an H.265 Main Stream (101 / subtype=0) to H.264 Sub-Stream (102 / subtype=1)
private const val MAIN_STREAM_STALL_WATCHDOG_MS = 3_500L

// Continuous mid-stream frame-heartbeat stall threshold for 24/7 wall-tablet resilience (HIGH-06 fix)
private const val CONTINUOUS_FRAME_FREEZE_THRESHOLD_MS = 7_500L

// Cache TTL for verified local RTSP camera IPs so expanding to fullscreen is instantaneous
private const val RTSP_IP_VERIFY_CACHE_TTL_MS = 60_000L

private fun redactCredentialsFromUrl(rawUrl: String): String {
    if (rawUrl.isBlank()) return ""
    return rawUrl.replace(Regex("(rtsp[s]?://[^:/@]+:)([^@]+)(@)", RegexOption.IGNORE_CASE), "$1***$3")
}

// ---------------------------------------------------------------------------
// SHARED BACKGROUND COROUTINE SCOPE & LIBVLC ENGINE (LOW-LATENCY RTSP-OVER-TCP)
// ---------------------------------------------------------------------------
val PlayerBackgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

object SharedVlcEngine {
    @Volatile
    private var instance: LibVLC? = null

    private val vlcOptions = arrayListOf(
        "--rtsp-tcp",
        "--rtsp-frame-buffer-size=500000",
        "--http-reconnect",
        "--network-caching=320",
        "--live-caching=320",
        "--clock-jitter=0",
        "--clock-synchro=0",
        "--avcodec-fast",
        "--drop-late-frames",
        "--skip-frames"
    )

    fun get(context: Context): LibVLC {
        return instance ?: synchronized(this) {
            instance ?: LibVLC(context.applicationContext, vlcOptions).also { instance = it }
        }
    }

    /**
     * HIGH-03 Fix: Reuses the shared singleton LibVLC native engine so 4x4 camera grids
     * do not allocate multiple redundant 60MB native LibVLC instances.
     */
    fun createDedicatedInstance(context: Context): LibVLC = get(context)
}

// ---------------------------------------------------------------------------
// FAST SUBNET SCANNER & AUTO-HEALER FOR CAMERAS (IMOU & EZVIZ)
// ---------------------------------------------------------------------------
data class EzvizSubnetScanHit(
    val ip: String,
    val rtspPort: Int,
    val port8000Open: Boolean,
    val rtspReady: Boolean
)

object EzvizLanWakeScanner {
    private val verifiedRtspIpTimestampMap = ConcurrentHashMap<String, Long>()

    fun isIpRecentlyVerified(ip: String): Boolean {
        val clean = ip.substringBefore(":").trim()
        if (clean.isBlank()) return false
        val lastOk = verifiedRtspIpTimestampMap[clean] ?: return false
        return (System.currentTimeMillis() - lastOk) < RTSP_IP_VERIFY_CACHE_TTL_MS
    }

    fun markIpVerified(ip: String) {
        val clean = ip.substringBefore(":").trim()
        if (clean.isNotBlank()) {
            verifiedRtspIpTimestampMap[clean] = System.currentTimeMillis()
            SmartHomeApiClient.registerVerifiedLanNeighbor(clean)
        }
    }

    fun invalidateIpCache(ip: String) {
        val clean = ip.substringBefore(":").trim()
        if (clean.isNotBlank()) {
            verifiedRtspIpTimestampMap.remove(clean)
        }
    }

    fun isTcpOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(ip, port), timeoutMs)
            }
            if (port == 554 || port == 8554) {
                markIpVerified(ip)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun verifyRtspEndpoint(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.soTimeout = 1000
                socket.connect(InetSocketAddress(ip, port), 1000)
                val req = "OPTIONS rtsp://$ip:$port/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: LibVLC\r\n\r\n"
                socket.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
                val line = BufferedReader(InputStreamReader(socket.getInputStream())).readLine() ?: ""
                val ok = line.startsWith("RTSP/1.0", ignoreCase = true)
                if (ok) markIpVerified(ip)
                ok
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveSubnetPrefix(context: Context): String? =
        SmartHomeApiClient.resolveLocalSubnetPrefixInternal(context)

    suspend fun findLocalImouRtspIps(
        context: Context,
        preferredIp: String
    ): List<String> = withContext(Dispatchers.IO) {
        val foundIps = mutableListOf<String>()
        val cleanPref = preferredIp.substringBefore(":").trim()
        if (cleanPref.isNotBlank() && (isIpRecentlyVerified(cleanPref) || isTcpOpen(cleanPref, 554, 450))) {
            foundIps.add(cleanPref)
            return@withContext foundIps
        }
        val prefix = resolveSubnetPrefix(context) ?: return@withContext foundIps
        val discovered = ConcurrentHashMap.newKeySet<String>()

        // Fast Pass: Probe known active neighbor IPs first (< 250ms)
        val knownNeighbors = SmartHomeApiClient.readActiveArpHosts(prefix).keys.filter { it != cleanPref }
        if (knownNeighbors.isNotEmpty()) {
            coroutineScope {
                knownNeighbors.map { ip ->
                    async(Dispatchers.IO) {
                        if (isTcpOpen(ip, 554, 220)) {
                            val isImou = isTcpOpen(ip, 37777, 180)
                            val isEzviz = isTcpOpen(ip, 8000, 160)
                            if (isImou || !isEzviz) {
                                discovered.add(ip)
                            }
                        }
                    }
                }.awaitAll()
            }
            if (discovered.isNotEmpty()) {
                foundIps.addAll(discovered.sorted())
                return@withContext foundIps.distinct()
            }
        }

        coroutineScope {
            (1..254).chunked(42).forEach { batch ->
                val jobs = batch.map { host ->
                    async(Dispatchers.IO) {
                        val ip = "$prefix.$host"
                        if (ip != cleanPref && isTcpOpen(ip, 554, 210)) {
                            val isImou = isTcpOpen(ip, 37777, 180)
                            val isEzviz = isTcpOpen(ip, 8000, 160)
                            if (isImou || !isEzviz) {
                                discovered.add(ip)
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
        }
        foundIps.addAll(discovered.sorted())
        foundIps.distinct()
    }

    suspend fun findAwakeEzvizOnLan(
        context: Context,
        preferredIp: String
    ): EzvizSubnetScanHit? = withContext(Dispatchers.IO) {
        val cleanPref = preferredIp.substringBefore(":").trim()
        if (cleanPref.isNotBlank()) {
            if (isIpRecentlyVerified(cleanPref)) {
                return@withContext EzvizSubnetScanHit(
                    ip = cleanPref,
                    rtspPort = 554,
                    port8000Open = true,
                    rtspReady = true
                )
            }
            val isImou = isTcpOpen(cleanPref, 37777, 200)
            if (!isImou) {
                val p554 = isTcpOpen(cleanPref, 554, 400)
                val p8554 = if (!p554) isTcpOpen(cleanPref, 8554, 300) else false
                val p8000 = isTcpOpen(cleanPref, 8000, 400)
                if (p554 || p8554 || p8000) {
                    return@withContext EzvizSubnetScanHit(
                        ip = cleanPref,
                        rtspPort = if (p8554) 8554 else 554,
                        port8000Open = p8000,
                        rtspReady = p554 || p8554
                    )
                }
            }
        }

        val prefix = resolveSubnetPrefix(context) ?: return@withContext null
        val hits = ConcurrentHashMap.newKeySet<EzvizSubnetScanHit>()

        // Fast Pass: Check known active neighbor IPs first
        val knownNeighbors = SmartHomeApiClient.readActiveArpHosts(prefix).keys.filter { it != cleanPref }
        if (knownNeighbors.isNotEmpty()) {
            coroutineScope {
                knownNeighbors.map { ip ->
                    async(Dispatchers.IO) {
                        val p8000 = isTcpOpen(ip, 8000, 200)
                        val p554 = isTcpOpen(ip, 554, 200)
                        val p8554 = if (!p554 && p8000) isTcpOpen(ip, 8554, 180) else false
                        if (p8000 || p554 || p8554) {
                            val isImou = isTcpOpen(ip, 37777, 160)
                            if (!isImou) {
                                val rtspOk = if (p554) verifyRtspEndpoint(ip, 554) else p8554
                                hits.add(
                                    EzvizSubnetScanHit(
                                        ip = ip,
                                        rtspPort = if (p8554) 8554 else 554,
                                        port8000Open = p8000,
                                        rtspReady = p554 || p8554 || rtspOk
                                    )
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
            hits.sortedWith(
                compareByDescending<EzvizSubnetScanHit> { it.port8000Open && it.rtspReady }
                    .thenByDescending { it.rtspReady }
                    .thenByDescending { it.port8000Open }
            ).firstOrNull()?.let { return@withContext it }
        }

        coroutineScope {
            (1..254).chunked(42).forEach { batch ->
                val jobs = batch.map { host ->
                    async(Dispatchers.IO) {
                        val ip = "$prefix.$host"
                        val p8000 = isTcpOpen(ip, 8000, 200)
                        val p554 = isTcpOpen(ip, 554, 200)
                        val p8554 = if (!p554 && p8000) isTcpOpen(ip, 8554, 180) else false
                        if (p8000 || p554 || p8554) {
                            val isImou = isTcpOpen(ip, 37777, 160)
                            if (!isImou) {
                                val rtspOk = if (p554) verifyRtspEndpoint(ip, 554) else p8554
                                hits.add(
                                    EzvizSubnetScanHit(
                                        ip = ip,
                                        rtspPort = if (p8554) 8554 else 554,
                                        port8000Open = p8000,
                                        rtspReady = p554 || p8554 || rtspOk
                                    )
                                )
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
        }
        hits.sortedWith(
            compareByDescending<EzvizSubnetScanHit> { it.port8000Open && it.rtspReady }
                .thenByDescending { it.rtspReady }
                .thenByDescending { it.port8000Open }
        ).firstOrNull()
    }

    fun buildRtspUrl(ip: String, port: Int, verificationCode: String, useSubStream: Boolean): String {
        return EzvizCloudApiClient.buildLocalRtspCandidateUrls(
            ipAddress = ip,
            port = port,
            verificationCode = verificationCode,
            preferSubStream = useSubStream
        ).firstOrNull().orEmpty()
    }

    suspend fun probeLocalImouSerialNumber(ip: String, safetyCode: String): String = withContext(Dispatchers.IO) {
        val cleanIp = ip.substringBefore(":").trim()
        if (cleanIp.isBlank()) return@withContext ""
        val pass = safetyCode.trim()
        if (pass.isNotBlank()) {
            try {
                val basicAuth = "Basic " + android.util.Base64.encodeToString(
                    "admin:$pass".toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                val conn = (java.net.URL("http://$cleanIp/cgi-bin/magicBox.cgi?action=getSystemInfo").openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1500
                    readTimeout = 1500
                    setRequestProperty("Authorization", basicAuth)
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val snLine = body.lineSequence().firstOrNull { it.trim().startsWith("serialNo=", ignoreCase = true) }
                    val sn = snLine?.substringAfter("=")?.trim().orEmpty()
                    if (sn.isNotBlank()) return@withContext sn
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
        }
        com.jarvis.smarthomelauncher.core.CrossEcosystemIdentityCorrelator.getObservedIpToMacMap()[cleanIp].orEmpty()
    }

    fun buildImouRtspUrl(ip: String, safetyCode: String, channelId: String, useSubStream: Boolean): String {
        return ImouCloudApiClient.buildLocalRtspCandidateUrls(
            ipAddress = ip,
            safetyCode = safetyCode,
            channelId = channelId,
            preferSubStream = useSubStream
        ).firstOrNull().orEmpty()
    }
}

// ---------------------------------------------------------------------------
// PERSISTENT CAMERA STREAM SESSION POOL
// - Shares a single LibVLC native instance across all MediaPlayers (HIGH-03 fix)
// - Forces RTSP-over-TCP (:rtsp-tcp) with low-latency 320ms network caching
// - Runs a continuous Two-Phase Liveness & Frame-Heartbeat Watchdog (HIGH-06 fix)
// ---------------------------------------------------------------------------
class CameraStreamSession(val tileId: String, val libVlc: LibVLC) {
    val mediaPlayer: MediaPlayer = MediaPlayer(libVlc)

    var configSignature by mutableStateOf("")
    var resolvedUrl by mutableStateOf("")
    var fallbackSubStreamUrl by mutableStateOf("")
    var fallbackCloudUrl by mutableStateOf("")
    var resolverStatus by mutableStateOf("Connecting to camera...")
    var streamStatus by mutableStateOf("")
    var hasError by mutableStateOf(false)
    var steppedDownToSubStream by mutableStateOf(false)
    var retryNonce by mutableIntStateOf(0)
    var autoHealRequestNonce by mutableIntStateOf(0)

    @Volatile
    var isReleased: Boolean = false
        private set

    @Volatile
    private var isAudioDesired: Boolean = false

    @Volatile
    private var isStartingPlayback: Boolean = false

    @Volatile
    private var hasRenderedFirstFrame: Boolean = false

    @Volatile
    private var playbackStartRequestMs: Long = 0L

    @Volatile
    private var lastFrameHeartbeatEpochMs: Long = 0L

    private var currentlyPlayingUrl: String = ""
    private var attachedLayout: VLCVideoLayout? = null
    private var stallWatchdogJob: Job? = null

    val isUsable: Boolean
        get() = !isReleased && !runCatching { mediaPlayer.isReleased }.getOrDefault(true)

    val isUsingCloudBandwidth: Boolean
        get() = resolvedUrl.startsWith("http://", ignoreCase = true) ||
                resolvedUrl.startsWith("https://", ignoreCase = true)

    private fun deriveSubStreamUrlFromMain(url: String): String {
        val clean = url.trim()
        return when {
            clean.contains("/Streaming/Channels/101") ->
                clean.replace("/Streaming/Channels/101", "/Streaming/Channels/102")
            clean.contains("/h264/ch1/main/av_stream") ->
                clean.replace("/h264/ch1/main/av_stream", "/h264/ch1/sub/av_stream")
            clean.contains("subtype=0") ->
                clean.replace("subtype=0", "subtype=1")
            else -> ""
        }
    }

    private fun handleStreamStallOrError(reason: String) {
        stallWatchdogJob?.cancel()
        isStartingPlayback = false
        hasRenderedFirstFrame = false

        // 1. Step down from H.265 Main Stream (101 / subtype=0) to H.264 Sub-Stream (102 / subtype=1)
        val candidateSub = fallbackSubStreamUrl.ifBlank { deriveSubStreamUrlFromMain(resolvedUrl) }
        if (candidateSub.isNotBlank() && candidateSub != resolvedUrl) {
            Log.w(
                TAG,
                "Main stream stalled/failed ($reason) on tile $tileId -> auto-stepping down to H.264 sub-stream: ${redactCredentialsFromUrl(candidateSub)}"
            )
            fallbackSubStreamUrl = ""
            currentlyPlayingUrl = ""
            steppedDownToSubStream = true
            resolvedUrl = candidateSub
            hasError = false
            streamStatus = "Switching to smooth H.264 stream..."
            startOrResumeIfNeeded()
            return
        }

        // 2. Fallback from local RTSP to Cloud HLS (live.m3u8)
        if (resolvedUrl.startsWith("rtsp://", ignoreCase = true) && fallbackCloudUrl.isNotBlank()) {
            Log.w(TAG, "Local RTSP unavailable ($reason) on tile $tileId -> switching automatically to Cloud HLS stream")
            val nextUrl = fallbackCloudUrl
            fallbackCloudUrl = ""
            currentlyPlayingUrl = ""
            resolvedUrl = nextUrl
            hasError = false
            streamStatus = "Switching to Cloud stream..."
            startOrResumeIfNeeded()
            return
        }

        // 3. Trigger auto-heal & token/URL refresh
        ImouCloudApiClient.clearStreamCache()
        EzvizCloudApiClient.clearStreamCache()
        hasError = true
        currentlyPlayingUrl = ""
        streamStatus = "Reconnecting to camera..."
        autoHealRequestNonce++
    }

    init {
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> {
                    lastFrameHeartbeatEpochMs = System.currentTimeMillis()
                    if (!hasRenderedFirstFrame) {
                        streamStatus = "Opening live video..."
                    }
                    hasError = false
                    applyHardwareVolume()
                    applyBestFitScale()
                }
                MediaPlayer.Event.Buffering -> {
                    lastFrameHeartbeatEpochMs = System.currentTimeMillis()
                    if (!hasRenderedFirstFrame && event.buffering < 100f) {
                        streamStatus = "Connecting (${event.buffering.toInt()}%)..."
                    } else if (event.buffering >= 100f) {
                        markFirstFrameRendered()
                    }
                    applyHardwareVolume()
                    applyBestFitScale()
                }
                MediaPlayer.Event.Playing,
                MediaPlayer.Event.Vout,
                MediaPlayer.Event.ESAdded,
                MediaPlayer.Event.TimeChanged,
                MediaPlayer.Event.PositionChanged -> {
                    lastFrameHeartbeatEpochMs = System.currentTimeMillis()
                    if (event.type == MediaPlayer.Event.Vout ||
                        event.type == MediaPlayer.Event.TimeChanged ||
                        event.type == MediaPlayer.Event.PositionChanged
                    ) {
                        markFirstFrameRendered()
                    }
                    hasError = false
                    applyHardwareVolume()
                    applyBestFitScale()
                }
                MediaPlayer.Event.EndReached,
                MediaPlayer.Event.EncounteredError -> {
                    handleStreamStallOrError(
                        if (event.type == MediaPlayer.Event.EndReached) "StreamEndReached" else "EncounteredError"
                    )
                }
            }
        }
    }

    private fun markFirstFrameRendered() {
        val wasFirst = !hasRenderedFirstFrame
        hasRenderedFirstFrame = true
        isStartingPlayback = false
        lastFrameHeartbeatEpochMs = System.currentTimeMillis()
        if (streamStatus.isNotEmpty()) {
            streamStatus = ""
        }
        if (wasFirst && playbackStartRequestMs > 0L) {
            val latencyMs = (System.currentTimeMillis() - playbackStartRequestMs).coerceIn(10L, 8_000L)
            JarvisLiveBridge.recordCommandLatency(
                integrationKey = "CAMERAS_RTSP",
                displayName = "Imou / EZVIZ Cameras & DVR",
                latencyMs = latencyMs,
                succeeded = true,
                statusMessage = if (isUsingCloudBandwidth) "Cloud HLS Active" else "Local RTSP Active (${latencyMs}ms)"
            )
        }
    }

    private fun isPlayerActivelyPlayingSafe(): Boolean {
        if (!isUsable) return false
        return runCatching { mediaPlayer.isPlaying }.getOrDefault(false)
    }

    private fun applyHardwareVolume() {
        if (!isUsable) return
        try {
            val targetVol = if (isAudioDesired && attachedLayout != null) 100 else 0
            mediaPlayer.volume = targetVol
        } catch (_: Exception) {
        }
    }

    private fun applyBestFitScale() {
        if (!isUsable) return
        try {
            mediaPlayer.aspectRatio = null
            mediaPlayer.scale = 0f
            mediaPlayer.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
        } catch (_: Exception) {
        }
    }

    fun attachToLayout(
        layout: VLCVideoLayout,
        enableAudio: Boolean
    ) {
        if (!isUsable) return
        isAudioDesired = enableAudio
        if (attachedLayout !== layout) {
            try {
                mediaPlayer.detachViews()
            } catch (_: Exception) {
            }
            attachedLayout = layout
            try {
                if (isUsable) {
                    mediaPlayer.attachViews(layout, null, false, true)
                }
            } catch (_: Exception) {
            }
        }
        applyHardwareVolume()
        applyBestFitScale()
        startOrResumeIfNeeded()
    }

    fun detachLayout(layout: VLCVideoLayout) {
        if (attachedLayout === layout) {
            isAudioDesired = false
            stallWatchdogJob?.cancel()
            if (isUsable) {
                applyHardwareVolume()
                try {
                    mediaPlayer.detachViews()
                } catch (_: Exception) {
                }
            }
            attachedLayout = null
        }
    }

    fun setAudioEnabled(enableAudio: Boolean) {
        isAudioDesired = enableAudio
        applyHardwareVolume()
    }

    /**
     * HIGH-06 Fix: Continuous Two-Phase Liveness & Frame-Heartbeat Watchdog.
     * Phase 1: Verifies that the first frame decodes within 3.5s (or steps down from H.265 to H.264).
     * Phase 2: Continuously verifies that `TimeChanged` / `PositionChanged` events advance while attached,
     * automatically recovering if an RTSP/HLS stream freezes mid-session hours later.
     */
    private fun startContinuousLivenessWatchdog(expectedUrl: String) {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = PlayerBackgroundScope.launch {
            // Phase 1: First-Frame Startup Check
            delay(MAIN_STREAM_STALL_WATCHDOG_MS)
            if (!isUsable || attachedLayout == null || currentlyPlayingUrl != expectedUrl) return@launch
            if (!hasRenderedFirstFrame) {
                withContext(Dispatchers.Main) {
                    if (isUsable && !hasRenderedFirstFrame && attachedLayout != null) {
                        handleStreamStallOrError("MainStreamFirstFrameTimeout")
                    }
                }
                return@launch
            }

            // Phase 2: Continuous Mid-Stream Freeze Monitor
            while (isActive && isUsable && attachedLayout != null && currentlyPlayingUrl == expectedUrl) {
                delay(2_500L)
                if (!isUsable || attachedLayout == null || currentlyPlayingUrl != expectedUrl) break
                val idleSinceLastFrameMs = System.currentTimeMillis() - lastFrameHeartbeatEpochMs
                if (hasRenderedFirstFrame && idleSinceLastFrameMs > CONTINUOUS_FRAME_FREEZE_THRESHOLD_MS) {
                    withContext(Dispatchers.Main) {
                        if (isUsable && attachedLayout != null) {
                            handleStreamStallOrError("MidStreamFrameHeartbeatFreeze(${idleSinceLastFrameMs}ms)")
                        }
                    }
                    break
                }
            }
        }
    }

    fun startOrResumeIfNeeded() {
        if (!isUsable) return
        val cleanUrl = resolvedUrl.trim()
        if (cleanUrl.isBlank() || attachedLayout == null) return

        val playingNow = isPlayerActivelyPlayingSafe()
        if (currentlyPlayingUrl == cleanUrl && !hasError &&
            (isStartingPlayback || hasRenderedFirstFrame || playingNow)
        ) {
            if (hasRenderedFirstFrame || playingNow) {
                streamStatus = ""
            }
            applyHardwareVolume()
            applyBestFitScale()
            return
        }

        currentlyPlayingUrl = cleanUrl
        isStartingPlayback = true
        hasRenderedFirstFrame = false
        hasError = false
        val now = System.currentTimeMillis()
        playbackStartRequestMs = now
        lastFrameHeartbeatEpochMs = now
        streamStatus = "Opening live video..."

        startContinuousLivenessWatchdog(cleanUrl)

        PlayerBackgroundScope.launch {
            try {
                if (!isUsable) return@launch
                val isLocalRtsp = cleanUrl.startsWith("rtsp://", ignoreCase = true)
                val cacheMs = if (isLocalRtsp) 320 else 450
                val media = Media(libVlc, Uri.parse(cleanUrl)).apply {
                    val activeCount = CameraStreamSessionManager.activeSessionCount()
                    val isSubStream = cleanUrl.contains("/102") || cleanUrl.contains("subtype=1") || cleanUrl.endsWith(".m3u8", true)
                    if (activeCount > 1 && !isSubStream) {
                        setHWDecoderEnabled(false, false)
                    } else {
                        setHWDecoderEnabled(true, false)
                    }
                    addOption(":rtsp-tcp")
                    addOption(":rtsp-frame-buffer-size=500000")
                    addOption(":network-caching=$cacheMs")
                    addOption(":live-caching=$cacheMs")
                    addOption(":clock-jitter=0")
                    addOption(":clock-synchro=0")
                }
                if (!isUsable) {
                    media.release()
                    return@launch
                }
                mediaPlayer.media = media
                media.release()
                applyHardwareVolume()
                applyBestFitScale()
                if (isUsable) {
                    mediaPlayer.play()
                }
                applyHardwareVolume()
                applyBestFitScale()
            } catch (_: Exception) {
                isStartingPlayback = false
            }
        }
    }

    fun forceRetry() {
        if (!isUsable) return
        stallWatchdogJob?.cancel()
        ImouCloudApiClient.clearStreamCache()
        EzvizCloudApiClient.clearStreamCache()
        isStartingPlayback = false
        hasRenderedFirstFrame = false
        currentlyPlayingUrl = ""
        resolvedUrl = ""
        fallbackSubStreamUrl = ""
        fallbackCloudUrl = ""
        hasError = false
        resolverStatus = "Reconnecting to camera..."
        retryNonce++
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        stallWatchdogJob?.cancel()
        isAudioDesired = false
        isStartingPlayback = false
        hasRenderedFirstFrame = false
        attachedLayout = null
        currentlyPlayingUrl = ""
        try {
            mediaPlayer.setEventListener(null)
            mediaPlayer.volume = 0
            mediaPlayer.detachViews()
        } catch (_: Exception) {
        }
        // HIGH-03 Fix: Release only the MediaPlayer instance; keep the shared singleton LibVLC alive
        PlayerBackgroundScope.launch {
            try {
                mediaPlayer.stop()
                mediaPlayer.release()
            } catch (_: Exception) {
            }
        }
    }
}

object CameraStreamSessionManager {
    private val sessions = ConcurrentHashMap<String, CameraStreamSession>()

    @Synchronized
    fun getOrCreate(context: Context, tileId: String): CameraStreamSession {
        val existing = sessions[tileId]
        if (existing != null && existing.isUsable) {
            return existing
        }
        val fresh = CameraStreamSession(tileId, SharedVlcEngine.get(context))
        sessions[tileId] = fresh
        return fresh
    }

    fun activeSessionCount(): Int = sessions.size

    fun muteAll() {
        sessions.values.forEach { session ->
            session.setAudioEnabled(false)
        }
    }

    fun releaseSession(tileId: String) {
        sessions.remove(tileId)?.release()
    }

    fun releaseAllExcept(activeTileIds: Set<String>) {
        val toRemove = sessions.keys.filter { it !in activeTileIds }
        toRemove.forEach { id ->
            sessions.remove(id)?.release()
        }
    }
}

// ---------------------------------------------------------------------------
// SMART CAMERA STREAM RESOLVER (IMOU, EZVIZ & GENERIC RTSP/HLS)
// ---------------------------------------------------------------------------
@Composable
fun SmartCameraStreamResolverView(
    tile: SmartTile,
    bridgeSettings: BridgeSettings,
    enableAudio: Boolean,
    cropToFillTile: Boolean = true,
    onUpdateTile: (SmartTile) -> Unit = {},
    onVideoSurfaceTap: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var session by remember(tile.id) {
        mutableStateOf(CameraStreamSessionManager.getOrCreate(context, tile.id))
    }
    if (!session.isUsable) {
        session = CameraStreamSessionManager.getOrCreate(context, tile.id)
    }

    var showCameraSetupPopup by remember { mutableStateOf(false) }
    var showEzvizOAuthModal by remember { mutableStateOf(false) }
    var showAdvancedCameraFields by remember { mutableStateOf(false) }
    var diagnosticMessage by remember {
        mutableStateOf("Clara automatically connects to your camera over Wi-Fi or Cloud.")
    }

    var draftEzvizEmail by remember { mutableStateOf(EzvizCloudApiClient.getSavedEzvizEmail(context)) }
    var draftEzvizPassword by remember { mutableStateOf(EzvizCloudApiClient.getSavedEzvizPassword(context)) }
    var draftSecretCode by remember(tile.id, tile.secretCode, tile.serialNumber) {
        mutableStateOf(
            when (tile.type) {
                TileType.EZVIZ_DOORBELL -> EzvizCloudApiClient.resolveVerificationCodeForTile(context, tile)
                TileType.IMOU_CAMERA -> tile.secretCode.ifBlank {
                    ImouCloudApiClient.getSavedDeviceSafetyCode(tile.serialNumber)
                }
                else -> tile.secretCode
            }
        )
    }
    var draftIpAddress by remember(tile.id, tile.ipAddress) { mutableStateOf(tile.ipAddress) }
    var draftSerial by remember(tile.id, tile.serialNumber) { mutableStateOf(tile.serialNumber) }
    var imouCloudAppId by remember(bridgeSettings.imouAppId) {
        mutableStateOf(bridgeSettings.imouAppId.ifBlank { DEFAULT_IMOU_APP_ID })
    }
    var imouCloudAppSecret by remember(bridgeSettings.imouAppSecret) {
        mutableStateOf(bridgeSettings.imouAppSecret)
    }
    var ezvizCloudKey by remember(bridgeSettings.ezvizAppKey) { mutableStateOf(bridgeSettings.ezvizAppKey) }
    var ezvizCloudSecret by remember(bridgeSettings.ezvizAppSecret) { mutableStateOf(bridgeSettings.ezvizAppSecret) }
    var popupStatusBanner by remember { mutableStateOf("") }

    val signature = remember(
        tile.id,
        tile.useSubStream,
        tile.ipAddress,
        tile.secretCode,
        tile.serialNumber,
        tile.customRtspUrl,
        bridgeSettings
    ) {
        "${tile.ipAddress}|${tile.secretCode}|${tile.serialNumber}|${tile.customRtspUrl}|${tile.useSubStream}|${bridgeSettings.imouAppId}|${bridgeSettings.ezvizAppKey}"
    }

    // Persist automatic sub-stream step-down on the tile if main H.265 stream stalled
    LaunchedEffect(session.steppedDownToSubStream) {
        if (session.steppedDownToSubStream && !tile.useSubStream) {
            onUpdateTile(tile.copy(useSubStream = true))
        }
    }

    LaunchedEffect(tile.id, tile.isLiveFeedActive, cropToFillTile, session.resolvedUrl, bridgeSettings.cloudStreamAutoPauseSeconds) {
        val autoPauseSec = bridgeSettings.cloudStreamAutoPauseSeconds
        if (cropToFillTile && tile.isLiveFeedActive && session.isUsingCloudBandwidth && autoPauseSec > 0) {
            val timeoutMs = (autoPauseSec * 1000L).coerceAtLeast(30_000L)
            delay(timeoutMs)
            onUpdateTile(tile.copy(isLiveFeedActive = false))
        } else if (cropToFillTile && tile.isLiveFeedActive && session.isUsingCloudBandwidth && autoPauseSec < 0) {
            delay(DEFAULT_CLOUD_STREAM_AUTO_SAVER_TIMEOUT_MS)
            onUpdateTile(tile.copy(isLiveFeedActive = false))
        }
    }

    LaunchedEffect(session.autoHealRequestNonce) {
        if (session.autoHealRequestNonce == 0) return@LaunchedEffect
        EzvizLanWakeScanner.invalidateIpCache(tile.ipAddress)
        delay(800L)
        if (tile.type == TileType.IMOU_CAMERA) {
            ImouCloudApiClient.invalidateStreamForSerial(tile.serialNumber)
            val cloudRes = ImouCloudApiClient.fetchOrBindHlsStream(
                bridge = bridgeSettings,
                serialNumber = tile.serialNumber,
                channelId = tile.channelId,
                preferSdSubStream = true
            )
            if (cloudRes.isSuccess) {
                val cloudUrl = cloudRes.getOrThrow()
                if (cloudUrl.isNotBlank() && cloudUrl != session.resolvedUrl) {
                    session.resolvedUrl = cloudUrl
                    session.hasError = false
                    session.startOrResumeIfNeeded()
                    return@LaunchedEffect
                }
            }
            val healedIps = EzvizLanWakeScanner.findLocalImouRtspIps(context, "")
            val newIp = healedIps.firstOrNull()
            if (!newIp.isNullOrBlank() && newIp != tile.ipAddress) {
                draftIpAddress = newIp
                onUpdateTile(tile.copy(ipAddress = newIp, useSubStream = true))
                session.forceRetry()
                return@LaunchedEffect
            }
        } else if (tile.type == TileType.EZVIZ_DOORBELL) {
            EzvizCloudApiClient.invalidateStreamForSerial(tile.serialNumber)
            val effectiveBridge = DeviceRepository.loadBridge(context)
            val effectiveCode = EzvizCloudApiClient.resolveVerificationCodeForTile(context, tile)
            EzvizCloudApiClient.wakeDoorbellIfNeeded(context, tile.serialNumber)
            val cloudRes = EzvizCloudApiClient.fetchHlsStream(
                bridge = effectiveBridge,
                serialNumber = tile.serialNumber,
                channelNo = tile.channelId,
                verificationCode = effectiveCode,
                preferSdSubStream = true
            )
            if (cloudRes.isSuccess) {
                val cloudUrl = cloudRes.getOrThrow()
                if (cloudUrl.isNotBlank() && cloudUrl != session.resolvedUrl) {
                    session.resolvedUrl = cloudUrl
                    session.hasError = false
                    session.startOrResumeIfNeeded()
                    return@LaunchedEffect
                }
            }
            val hit = EzvizLanWakeScanner.findAwakeEzvizOnLan(context, "")
            if (hit != null && hit.rtspReady && hit.ip != tile.ipAddress) {
                draftIpAddress = hit.ip
                onUpdateTile(tile.copy(ipAddress = hit.ip, useSubStream = true))
                session.forceRetry()
                return@LaunchedEffect
            }
        } else if (tile.type == TileType.NEST_DOORBELL) {
            val nestRes = NestSdmApiClient.fetchNestCameraLiveStreamUrl(bridgeSettings, tile)
            if (nestRes.isSuccess) {
                val nestUrl = nestRes.getOrThrow()
                if (nestUrl.isNotBlank() && nestUrl != session.resolvedUrl) {
                    session.resolvedUrl = nestUrl
                    session.hasError = false
                    session.startOrResumeIfNeeded()
                    return@LaunchedEffect
                }
            }
        }
        session.streamStatus = "Connection paused \u2014 Tap Retry to reconnect."
    }

    LaunchedEffect(signature, session.retryNonce) {
        if (session.configSignature == signature && session.resolvedUrl.isNotBlank() && !session.hasError) {
            session.resolverStatus = ""
            return@LaunchedEffect
        }
        session.configSignature = signature
        session.resolverStatus = "Connecting to ${tile.title}..."

        if (tile.customRtspUrl.isNotBlank() &&
            tile.type != TileType.EZVIZ_DOORBELL &&
            tile.type != TileType.IMOU_CAMERA
        ) {
            session.resolvedUrl = tile.customRtspUrl.trim()
            session.resolverStatus = ""
            return@LaunchedEffect
        }

        when (tile.type) {
            TileType.IMOU_CAMERA -> {
                var effectiveTile = tile

                // 1. Auto-discover Imou serial number & channelId if missing
                if (effectiveTile.serialNumber.isBlank() &&
                    bridgeSettings.imouAppId.isNotBlank() &&
                    bridgeSettings.imouAppSecret.isNotBlank()
                ) {
                    val discRes = ImouCloudApiClient.discoverBoundCameras(bridgeSettings)
                    if (discRes.isSuccess) {
                        val list = discRes.getOrThrow()
                        val matched = list.firstOrNull { it.name.equals(effectiveTile.title, ignoreCase = true) }
                            ?: list.firstOrNull()
                        if (matched != null && matched.deviceId.isNotBlank()) {
                            val savedSafety = ImouCloudApiClient.getSavedDeviceSafetyCode(matched.deviceId)
                            effectiveTile = effectiveTile.copy(
                                serialNumber = matched.deviceId,
                                channelId = matched.channelId.ifBlank { effectiveTile.channelId },
                                secretCode = effectiveTile.secretCode.ifBlank { savedSafety }
                            )
                            draftSerial = matched.deviceId
                            if (effectiveTile.secretCode.isNotBlank()) {
                                draftSecretCode = effectiveTile.secretCode
                            }
                            onUpdateTile(effectiveTile)
                        }
                    }
                }

                val effectiveSafetyCode = effectiveTile.secretCode.trim().ifBlank {
                    ImouCloudApiClient.getSavedDeviceSafetyCode(effectiveTile.serialNumber)
                }
                if (effectiveTile.serialNumber.isNotBlank() && effectiveSafetyCode.isNotBlank()) {
                    ImouCloudApiClient.saveDeviceSafetyCode(effectiveTile.serialNumber, effectiveSafetyCode)
                }

                val preferSub = if (cropToFillTile) true else effectiveTile.useSubStream

                // 2. Fast Path: Check cached or live local RTSP port 554 reachability first
                var isLocalReachable = if (effectiveTile.ipAddress.isNotBlank()) {
                    val cleanIp = effectiveTile.ipAddress.substringBefore(":").trim()
                    if (EzvizLanWakeScanner.isIpRecentlyVerified(cleanIp)) {
                        true
                    } else {
                        withContext(Dispatchers.IO) {
                            val port = effectiveTile.ipAddress.substringAfter(":", "554").toIntOrNull() ?: 554
                            EzvizLanWakeScanner.isTcpOpen(cleanIp, port, 450)
                        }
                    }
                } else false

                if (!isLocalReachable && effectiveSafetyCode.isNotBlank()) {
                    session.resolverStatus = "Locating ${effectiveTile.title} on Wi-Fi..."
                    val scannedIps = EzvizLanWakeScanner.findLocalImouRtspIps(context, effectiveTile.ipAddress)
                    val bestIp = scannedIps.firstOrNull()
                    if (!bestIp.isNullOrBlank()) {
                        isLocalReachable = true
                        if (bestIp != effectiveTile.ipAddress) {
                            effectiveTile = effectiveTile.copy(ipAddress = bestIp)
                            draftIpAddress = bestIp
                            onUpdateTile(effectiveTile)
                        }
                    }
                }

                val localCandidates = if (effectiveTile.customRtspUrl.isNotBlank()) {
                    listOf(effectiveTile.customRtspUrl.trim())
                } else {
                    ImouCloudApiClient.buildLocalRtspCandidateUrls(
                        ipAddress = effectiveTile.ipAddress,
                        safetyCode = effectiveSafetyCode,
                        channelId = effectiveTile.channelId,
                        preferSubStream = preferSub
                    )
                }
                val primaryLocalRtsp = localCandidates.firstOrNull().orEmpty()
                val subStreamLocalRtsp = localCandidates.getOrNull(1).orEmpty()

                if (isLocalReachable && primaryLocalRtsp.isNotBlank()) {
                    session.fallbackSubStreamUrl = if (!preferSub) subStreamLocalRtsp else ""
                    session.resolvedUrl = primaryLocalRtsp
                    session.resolverStatus = ""
                    if (effectiveTile.serialNumber.isNotBlank() &&
                        bridgeSettings.imouAppId.isNotBlank() &&
                        bridgeSettings.imouAppSecret.isNotBlank()
                    ) {
                        scope.launch(Dispatchers.IO) {
                            val res = ImouCloudApiClient.fetchOrBindHlsStream(
                                bridge = bridgeSettings,
                                serialNumber = effectiveTile.serialNumber,
                                channelId = effectiveTile.channelId,
                                preferSdSubStream = true
                            )
                            res.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                                session.fallbackCloudUrl = it
                            }
                        }
                    }
                    return@LaunchedEffect
                }

                // 3. Otherwise resolve Imou Cloud HLS (live.m3u8) stream token via bindDeviceLive / getLiveStreamInfo
                var cloudHlsUrl = ""
                if (effectiveTile.serialNumber.isNotBlank() &&
                    bridgeSettings.imouAppId.isNotBlank() &&
                    bridgeSettings.imouAppSecret.isNotBlank()
                ) {
                    session.resolverStatus = "Opening ${effectiveTile.title}..."
                    val res = ImouCloudApiClient.fetchOrBindHlsStream(
                        bridge = bridgeSettings,
                        serialNumber = effectiveTile.serialNumber,
                        channelId = effectiveTile.channelId,
                        preferSdSubStream = preferSub
                    )
                    if (res.isSuccess) {
                        cloudHlsUrl = res.getOrThrow()
                    }
                }

                if (cloudHlsUrl.isNotBlank()) {
                    session.fallbackSubStreamUrl = ""
                    session.fallbackCloudUrl = ""
                    session.resolvedUrl = cloudHlsUrl
                    session.resolverStatus = ""
                    return@LaunchedEffect
                }

                if (primaryLocalRtsp.isNotBlank()) {
                    session.fallbackSubStreamUrl = if (!preferSub) subStreamLocalRtsp else ""
                    session.resolvedUrl = primaryLocalRtsp
                    session.resolverStatus = ""
                } else {
                    diagnosticMessage = "Tap 'Auto-Configure Camera' below to automatically locate and link ${effectiveTile.title}."
                    session.resolverStatus = "Camera Setup Needed"
                }
            }

            TileType.EZVIZ_DOORBELL -> {
                var effectiveTile = tile
                val effectiveBridge = DeviceRepository.loadBridge(context)
                var effectiveCode = EzvizCloudApiClient.resolveVerificationCodeForTile(context, effectiveTile)

                if ((effectiveTile.serialNumber.isBlank() || effectiveTile.ipAddress.isBlank() || effectiveCode.isBlank()) &&
                    draftEzvizEmail.isNotBlank() && draftEzvizPassword.isNotBlank()
                ) {
                    session.resolverStatus = "Syncing ${effectiveTile.title} & PIN from EZVIZ Cloud..."
                    val consumerRes = EzvizCloudApiClient.loginAndDiscoverWithConsumerAccount(
                        context = context,
                        email = draftEzvizEmail,
                        password = draftEzvizPassword
                    )
                    if (consumerRes.isSuccess) {
                        val list = consumerRes.getOrThrow()
                        val matched = list.firstOrNull {
                            effectiveTile.serialNumber.isNotBlank() && it.serial.equals(effectiveTile.serialNumber, ignoreCase = true)
                        } ?: list.firstOrNull {
                            it.name.equals(effectiveTile.title, ignoreCase = true)
                        } ?: list.firstOrNull()

                        if (matched != null) {
                            if (matched.verificationCode.isNotBlank()) {
                                effectiveCode = matched.verificationCode
                                draftSecretCode = matched.verificationCode
                            }
                            effectiveTile = effectiveTile.copy(
                                serialNumber = matched.serial.ifBlank { effectiveTile.serialNumber },
                                ipAddress = matched.localIp.ifBlank { effectiveTile.ipAddress },
                                secretCode = effectiveCode
                            )
                            draftSerial = effectiveTile.serialNumber
                            draftIpAddress = effectiveTile.ipAddress
                            onUpdateTile(effectiveTile)
                        }
                    }
                }

                val preferSub = if (cropToFillTile) true else effectiveTile.useSubStream

                scope.launch(Dispatchers.IO) {
                    EzvizCloudApiClient.wakeDoorbellIfNeeded(context, effectiveTile.serialNumber)
                }

                val hit = EzvizLanWakeScanner.findAwakeEzvizOnLan(context, effectiveTile.ipAddress)
                if (hit != null) {
                    if (hit.ip != effectiveTile.ipAddress || (effectiveCode.isNotBlank() && effectiveCode != effectiveTile.secretCode)) {
                        effectiveTile = effectiveTile.copy(ipAddress = hit.ip, secretCode = effectiveCode)
                        draftIpAddress = hit.ip
                        onUpdateTile(effectiveTile)
                    }
                    val cachedDetail = EzvizCloudApiClient.getCachedDeviceDetail(effectiveTile.serialNumber)
                    val canStreamLocalRtsp = hit.rtspReady && (effectiveCode.isNotBlank() || cachedDetail?.isEncrypted == false)
                    if (canStreamLocalRtsp) {
                        val rtspCandidates = EzvizCloudApiClient.buildLocalRtspCandidateUrls(
                            ipAddress = hit.ip,
                            port = hit.rtspPort,
                            verificationCode = effectiveCode,
                            preferSubStream = preferSub
                        )
                        val primaryRtsp = rtspCandidates.firstOrNull().orEmpty()
                        val subRtsp = rtspCandidates.getOrNull(1).orEmpty()
                        if (primaryRtsp.isNotBlank()) {
                            session.fallbackSubStreamUrl = if (!preferSub) subRtsp else ""
                            session.resolvedUrl = primaryRtsp
                            session.resolverStatus = ""
                            scope.launch(Dispatchers.IO) {
                                EzvizCloudApiClient.fetchHlsStream(
                                    bridge = effectiveBridge,
                                    serialNumber = effectiveTile.serialNumber,
                                    channelNo = effectiveTile.channelId,
                                    verificationCode = effectiveCode,
                                    preferSdSubStream = true
                                ).getOrNull()?.takeIf { it.isNotBlank() }?.let {
                                    session.fallbackCloudUrl = it
                                }
                            }
                            return@LaunchedEffect
                        }
                    }
                }

                var cloudStreamUrl = ""
                val hasEzvizAccountOrKeys = (draftEzvizEmail.isNotBlank() && draftEzvizPassword.isNotBlank()) ||
                        (effectiveBridge.ezvizAppKey.isNotBlank() && effectiveBridge.ezvizAppSecret.isNotBlank()) ||
                        effectiveTile.serialNumber.isNotBlank() ||
                        EzvizCloudApiClient.hasActiveSessionOrCredentials(context, effectiveBridge)

                if (hasEzvizAccountOrKeys) {
                    session.resolverStatus = "Waking & connecting to ${effectiveTile.title}..."
                    val cloudRes = EzvizCloudApiClient.fetchHlsStream(
                        bridge = effectiveBridge,
                        serialNumber = effectiveTile.serialNumber,
                        channelNo = effectiveTile.channelId,
                        verificationCode = effectiveCode,
                        preferSdSubStream = preferSub
                    )
                    if (cloudRes.isSuccess) {
                        cloudStreamUrl = cloudRes.getOrThrow()
                    }
                }

                if (cloudStreamUrl.isNotBlank()) {
                    session.fallbackSubStreamUrl = ""
                    session.fallbackCloudUrl = ""
                    session.resolvedUrl = cloudStreamUrl
                    session.resolverStatus = ""
                    return@LaunchedEffect
                }

                if (effectiveTile.customRtspUrl.isNotBlank()) {
                    session.resolvedUrl = effectiveTile.customRtspUrl.trim()
                    session.resolverStatus = ""
                    return@LaunchedEffect
                }

                if (effectiveTile.ipAddress.isNotBlank() && effectiveCode.isNotBlank()) {
                    val directCandidates = EzvizCloudApiClient.buildLocalRtspCandidateUrls(
                        ipAddress = effectiveTile.ipAddress,
                        port = 554,
                        verificationCode = effectiveCode,
                        preferSubStream = preferSub
                    )
                    val firstDirect = directCandidates.firstOrNull().orEmpty()
                    if (firstDirect.isNotBlank()) {
                        session.fallbackSubStreamUrl = directCandidates.getOrNull(1).orEmpty()
                        session.resolvedUrl = firstDirect
                        session.resolverStatus = ""
                        return@LaunchedEffect
                    }
                }

                diagnosticMessage =
                    "Sign in with your EZVIZ account under Camera Setup so Clara can automatically extract your doorbell PIN and wake the live stream directly inside the dashboard."
                session.resolverStatus = if (draftEzvizEmail.isBlank()) "EZVIZ Sign-In Needed" else "Doorbell in Standby"
            }

            TileType.NEST_DOORBELL -> {
                session.resolverStatus = "Opening ${tile.title} via Google Nest SDM..."
                val nestStreamRes = NestSdmApiClient.fetchNestCameraLiveStreamUrl(bridgeSettings, tile)
                if (nestStreamRes.isSuccess) {
                    val streamUrl = nestStreamRes.getOrThrow()
                    if (streamUrl.isNotBlank()) {
                        session.fallbackSubStreamUrl = ""
                        session.fallbackCloudUrl = ""
                        session.resolvedUrl = streamUrl
                        session.resolverStatus = ""
                        return@LaunchedEffect
                    }
                }
                if (tile.customRtspUrl.isNotBlank()) {
                    session.resolvedUrl = tile.customRtspUrl.trim()
                    session.resolverStatus = ""
                } else {
                    diagnosticMessage = nestStreamRes.exceptionOrNull()?.message
                        ?: "Sign in to your Google Nest account in Control Center to stream live video from ${tile.title}."
                    session.resolverStatus = "Nest Stream Standby"
                }
            }

            TileType.RING_CAMERA -> {
                if (tile.customRtspUrl.isNotBlank()) {
                    session.resolvedUrl = tile.customRtspUrl.trim()
                    session.resolverStatus = ""
                    return@LaunchedEffect
                }
                session.resolverStatus = "Connecting to ${tile.title}..."
                val haHostIp = runCatching {
                    Uri.parse(bridgeSettings.haUrl.trim()).host.orEmpty()
                }.getOrDefault("")
                val candidateHost = tile.ipAddress.substringBefore(":").trim().ifBlank { haHostIp }
                var resolvedRingRtsp = ""
                if (candidateHost.isNotBlank()) {
                    val slug = tile.entityId.substringAfter(".", tile.title)
                        .lowercase()
                        .replace(Regex("[^a-z0-9_]+"), "_")
                        .trim('_')
                        .ifBlank { "ring_front_door" }
                    for (port in listOf(8554, 554)) {
                        if (withContext(Dispatchers.IO) { EzvizLanWakeScanner.isTcpOpen(candidateHost, port, 400) }) {
                            resolvedRingRtsp = "rtsp://$candidateHost:$port/$slug"
                            break
                        }
                    }
                }
                if (resolvedRingRtsp.isNotBlank()) {
                    session.resolvedUrl = resolvedRingRtsp
                    session.resolverStatus = ""
                } else {
                    diagnosticMessage =
                        "Link your Ring RTSP bridge (ring-mqtt / go2rtc / Scrypted on port 8554) or custom RTSP URL under Camera Setup."
                    session.resolverStatus = "Ring Bridge Setup Needed"
                }
            }

            else -> {
                val localRtsp = tile.resolvedLocalRtspUrl()
                if (localRtsp.isNotBlank()) {
                    session.resolvedUrl = localRtsp
                    session.resolverStatus = ""
                } else {
                    session.resolverStatus = "Camera Setup Needed"
                }
            }
        }
    }

    if (session.resolverStatus.isNotEmpty()) {
        val isConnecting = session.resolverStatus.startsWith("Connecting") ||
                session.resolverStatus.startsWith("Opening") ||
                session.resolverStatus.startsWith("Locating") ||
                session.resolverStatus.startsWith("Reconnecting") ||
                session.resolverStatus.startsWith("Waking") ||
                session.resolverStatus.startsWith("Syncing")

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF141519))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!isConnecting) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (tile.type == TileType.EZVIZ_DOORBELL)
                            Icons.Outlined.Doorbell else Icons.Outlined.Videocam,
                        contentDescription = null,
                        tint = GhAccentWarm,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = session.resolverStatus,
                        color = GhTextSecondary,
                        fontSize = 11.sp,
                        fontFamily = GhFont,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = {
                            if (tile.type == TileType.EZVIZ_DOORBELL &&
                                draftEzvizEmail.isBlank() &&
                                bridgeSettings.ezvizAppKey.isBlank() &&
                                draftSecretCode.isBlank()
                            ) {
                                popupStatusBanner = ""
                                showCameraSetupPopup = true
                            } else {
                                session.forceRetry()
                            }
                        },
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
                        modifier = Modifier.height(30.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GhAccentGreen,
                            contentColor = GhBackground
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (tile.type == TileType.EZVIZ_DOORBELL) "Wake & Stream Live" else "Retry Live Stream",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = GhFont
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    FilledTonalButton(
                        onClick = {
                            popupStatusBanner = ""
                            showCameraSetupPopup = true
                        },
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(28.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = GhTileSurfaceElevated,
                            contentColor = GhAccentBlue
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Camera Setup",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = GhFont
                        )
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = session.resolverStatus,
                        color = GhTextPrimary,
                        fontSize = 11.sp,
                        fontFamily = GhFont,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    } else if (session.resolvedUrl.isNotBlank()) {
        LiveStreamVideoView(
            session = session,
            enableAudio = enableAudio,
            cropToFillTile = cropToFillTile,
            onOpenSetupPopup = {
                popupStatusBanner = ""
                showCameraSetupPopup = true
            },
            onVideoSurfaceTap = onVideoSurfaceTap
        )
    }

    if (showCameraSetupPopup) {
        val compactInputStyle = TextStyle(fontSize = 13.sp, fontFamily = GhFont, color = GhTextPrimary)
        val isEzviz = tile.type == TileType.EZVIZ_DOORBELL
        AlertDialog(
            onDismissRequest = { showCameraSetupPopup = false },
            containerColor = GhTileSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isEzviz) Icons.Outlined.Doorbell else Icons.Outlined.Videocam,
                        contentDescription = null,
                        tint = GhAccentWarm,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${tile.title} \u2014 In-App Camera Setup",
                        color = GhTextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GhFont
                    )
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Surface(
                        color = GhTileSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, GhAccentWarm.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (isEzviz) {
                                    "In-App EZVIZ Cloud & Local RTSP PIN Setup"
                                } else {
                                    "Automatic Imou Cloud & Local RTSP Connection"
                                },
                                color = GhAccentWarm,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GhFont
                            )
                            Text(
                                text = if (isEzviz) {
                                    "Sign in with your EZVIZ account below (or enter the 6-letter Verification Code from your camera sticker). Clara automatically configures local RTSP (/Streaming/Channels/101 & 102) and Cloud HLS."
                                } else {
                                    "Tap 'Auto-Configure Camera' below to automatically locate your Imou camera on Wi-Fi (/cam/realmonitor?channel=1&subtype=0) and link your Imou Cloud live.m3u8 stream."
                                },
                                color = GhTextPrimary,
                                fontSize = 11.sp,
                                fontFamily = GhFont
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isEzviz) {
                            FilledTonalButton(
                                onClick = { showEzvizOAuthModal = true },
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = GhAccentGreen.copy(alpha = 0.22f),
                                    contentColor = GhAccentGreen
                                )
                            ) {
                                Icon(Icons.Outlined.OpenInBrowser, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "In-App Web OAuth",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        FilledTonalButton(
                            onClick = {
                                popupStatusBanner = "Configuring & locating ${tile.title}..."
                                scope.launch {
                                    if (isEzviz) {
                                        if (draftEzvizEmail.isNotBlank() && draftEzvizPassword.isNotBlank()) {
                                            EzvizCloudApiClient.saveEzvizConsumerCredentials(
                                                context = context,
                                                email = draftEzvizEmail,
                                                password = draftEzvizPassword,
                                                verificationCode = draftSecretCode
                                            )
                                            val consumerRes = EzvizCloudApiClient.loginAndDiscoverWithConsumerAccount(
                                                context = context,
                                                email = draftEzvizEmail,
                                                password = draftEzvizPassword
                                            )
                                            if (consumerRes.isSuccess) {
                                                val list = consumerRes.getOrThrow()
                                                val found = list.firstOrNull {
                                                    draftSerial.isNotBlank() && it.serial.equals(draftSerial, ignoreCase = true)
                                                } ?: list.firstOrNull {
                                                    it.name.equals(tile.title, ignoreCase = true)
                                                } ?: list.firstOrNull()

                                                if (found != null) {
                                                    draftSerial = found.serial
                                                    if (found.localIp.isNotBlank()) draftIpAddress = found.localIp
                                                    if (found.verificationCode.isNotBlank()) {
                                                        draftSecretCode = found.verificationCode
                                                    }
                                                    onUpdateTile(
                                                        tile.copy(
                                                            serialNumber = found.serial,
                                                            ipAddress = found.localIp.ifBlank { draftIpAddress },
                                                            secretCode = draftSecretCode
                                                        )
                                                    )
                                                    val pinNote = if (found.verificationCode.isNotBlank()) {
                                                        " & auto-extracted PIN (${found.verificationCode})"
                                                    } else ""
                                                    popupStatusBanner = "SUCCESS: Linked '${found.name}'$pinNote! Tap 'Save & Connect' below."
                                                    return@launch
                                                }
                                            }
                                        }
                                        val hit = EzvizLanWakeScanner.findAwakeEzvizOnLan(context, "")
                                        if (hit != null) {
                                            draftIpAddress = hit.ip
                                            onUpdateTile(tile.copy(ipAddress = hit.ip, secretCode = draftSecretCode))
                                            popupStatusBanner = "SUCCESS: Found doorbell on Wi-Fi (${hit.ip})! Tap 'Save & Connect' below."
                                        } else {
                                            popupStatusBanner = "Enter your EZVIZ email & password or 6-letter Verification Code below."
                                        }
                                    } else {
                                        val effectiveImouBridge = bridgeSettings.copy(
                                            imouAppId = imouCloudAppId.trim().ifBlank { DEFAULT_IMOU_APP_ID },
                                            imouAppSecret = imouCloudAppSecret.trim()
                                        )
                                        if (imouCloudAppSecret.isNotBlank()) {
                                            DeviceRepository.saveBridge(
                                                context,
                                                DeviceRepository.loadBridge(context).copy(
                                                    imouAppId = effectiveImouBridge.imouAppId,
                                                    imouAppSecret = effectiveImouBridge.imouAppSecret
                                                )
                                            )
                                        }
                                        val imouIps = EzvizLanWakeScanner.findLocalImouRtspIps(context, draftIpAddress)
                                        if (imouIps.isNotEmpty()) {
                                            draftIpAddress = imouIps.first()
                                        }
                                        if (draftIpAddress.isNotBlank() && draftSerial.isBlank()) {
                                            val localSn = EzvizLanWakeScanner.probeLocalImouSerialNumber(draftIpAddress, draftSecretCode)
                                            if (localSn.isNotBlank()) {
                                                draftSerial = localSn
                                            }
                                        }
                                        if (effectiveImouBridge.imouAppSecret.isNotBlank()) {
                                            val cloudRes = ImouCloudApiClient.discoverBoundCameras(effectiveImouBridge)
                                            if (cloudRes.isSuccess) {
                                                val matched = cloudRes.getOrThrow().firstOrNull {
                                                    it.name.equals(tile.title, ignoreCase = true)
                                                } ?: cloudRes.getOrThrow().firstOrNull()
                                                if (matched != null) {
                                                    draftSerial = matched.deviceId
                                                    val savedSafety = ImouCloudApiClient.getSavedDeviceSafetyCode(matched.deviceId)
                                                    if (draftSecretCode.isBlank() && savedSafety.isNotBlank()) {
                                                        draftSecretCode = savedSafety
                                                    }
                                                }
                                            }
                                        }
                                        onUpdateTile(
                                            tile.copy(
                                                ipAddress = draftIpAddress.trim(),
                                                serialNumber = draftSerial.trim(),
                                                secretCode = draftSecretCode.trim()
                                            )
                                        )
                                        popupStatusBanner = when {
                                            draftIpAddress.isNotBlank() && draftSecretCode.isNotBlank() ->
                                                "SUCCESS: Linked local camera at $draftIpAddress! Tap 'Save & Connect' below."
                                            draftSerial.isNotBlank() && effectiveImouBridge.imouAppSecret.isNotBlank() ->
                                                "SUCCESS: Pulled Serial ($draftSerial) from Imou Cloud! Enter the 8-char Safety Code from the camera sticker if using local RTSP."
                                            draftIpAddress.isNotBlank() ->
                                                "Found camera on Wi-Fi at $draftIpAddress! Enter the 8-character Safety Code from the sticker on your Imou camera (or enter your Imou Cloud App Secret below to pull via Cloud)."
                                            else ->
                                                "Could not find camera on Wi-Fi yet. Enter your Imou Safety Code or Imou Cloud App Secret below."
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = GhTileSurfaceElevated,
                                contentColor = GhAccentBlue
                            )
                        ) {
                            Icon(Icons.Outlined.WifiFind, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isEzviz) "Sign In & Auto-Pull PIN" else "Auto-Configure Camera",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (isEzviz) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = draftEzvizEmail,
                                onValueChange = { draftEzvizEmail = it.trim() },
                                label = { Text("EZVIZ Account Email", fontSize = 11.sp) },
                                textStyle = compactInputStyle,
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = draftEzvizPassword,
                                onValueChange = { draftEzvizPassword = it },
                                label = { Text("EZVIZ Password", fontSize = 11.sp) },
                                visualTransformation = PasswordVisualTransformation(),
                                textStyle = compactInputStyle,
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedTextField(
                            value = draftSecretCode,
                            onValueChange = { draftSecretCode = it.trim() },
                            label = {
                                Text(
                                    if (draftSecretCode.isNotBlank())
                                        "Verification Code / PIN (Used for Local RTSP & Cloud)"
                                    else
                                        "Verification Code / PIN (6-letter code on camera sticker)",
                                    fontSize = 11.sp
                                )
                            },
                            textStyle = compactInputStyle,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = draftSecretCode,
                            onValueChange = { draftSecretCode = it.trim() },
                            label = {
                                Text("8-Character Safety Code / Camera Password (on camera sticker)", fontSize = 11.sp)
                            },
                            textStyle = compactInputStyle,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = imouCloudAppId,
                                onValueChange = { imouCloudAppId = it.trim() },
                                label = { Text("Imou Cloud App ID", fontSize = 10.sp) },
                                textStyle = compactInputStyle,
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = imouCloudAppSecret,
                                onValueChange = { imouCloudAppSecret = it.trim() },
                                label = { Text("Imou Cloud App Secret (for Cloud Pull)", fontSize = 10.sp) },
                                visualTransformation = PasswordVisualTransformation(),
                                textStyle = compactInputStyle,
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAdvancedCameraFields = !showAdvancedCameraFields },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (showAdvancedCameraFields) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = GhTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (showAdvancedCameraFields) "Hide Advanced Technical Settings" else "Advanced Technical Settings (IP / Serial)",
                            color = GhTextSecondary,
                            fontSize = 11.sp,
                            fontFamily = GhFont
                        )
                    }

                    AnimatedVisibility(visible = showAdvancedCameraFields) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = draftIpAddress,
                                    onValueChange = { draftIpAddress = it.trim() },
                                    label = { Text("Local IP Address (Auto-Discovered)", fontSize = 11.sp) },
                                    textStyle = compactInputStyle,
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = draftSerial,
                                    onValueChange = { draftSerial = it.uppercase().trim() },
                                    label = { Text("Hardware Serial (Auto-Discovered)", fontSize = 11.sp) },
                                    textStyle = compactInputStyle,
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (isEzviz) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = ezvizCloudKey,
                                        onValueChange = { ezvizCloudKey = it.trim() },
                                        label = { Text("EZVIZ Cloud Key (Optional)", fontSize = 10.sp) },
                                        textStyle = compactInputStyle,
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = ezvizCloudSecret,
                                        onValueChange = { ezvizCloudSecret = it.trim() },
                                        label = { Text("EZVIZ Cloud Secret (Optional)", fontSize = 10.sp) },
                                        textStyle = compactInputStyle,
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    if (popupStatusBanner.isNotBlank()) {
                        Surface(
                            color = GhTileSurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, GhAccentBlue.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = popupStatusBanner,
                                color = if (popupStatusBanner.startsWith("SUCCESS")) GhAccentGreen else GhAccentWarm,
                                fontSize = 11.sp,
                                fontFamily = GhFont,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isEzviz) {
                            EzvizCloudApiClient.saveEzvizConsumerCredentials(
                                context = context,
                                email = draftEzvizEmail,
                                password = draftEzvizPassword,
                                verificationCode = draftSecretCode
                            )
                            if (draftSerial.isNotBlank() && draftSecretCode.isNotBlank()) {
                                EzvizCloudApiClient.saveEzvizPinForSerial(context, draftSerial, draftSecretCode)
                            }
                            val updatedBridge = DeviceRepository.loadBridge(context).copy(
                                ezvizAppKey = ezvizCloudKey.trim(),
                                ezvizAppSecret = ezvizCloudSecret.trim()
                            )
                            DeviceRepository.saveBridge(context, updatedBridge)
                        } else if (draftSerial.isNotBlank() && draftSecretCode.isNotBlank()) {
                            ImouCloudApiClient.saveDeviceSafetyCode(draftSerial, draftSecretCode)
                        }
                        onUpdateTile(
                            tile.copy(
                                ipAddress = draftIpAddress.trim(),
                                serialNumber = draftSerial.trim(),
                                secretCode = draftSecretCode.trim(),
                                isLiveFeedActive = true
                            )
                        )
                        showCameraSetupPopup = false
                        session.forceRetry()
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GhAccentGreen,
                        contentColor = GhBackground
                    )
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Save & Connect",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCameraSetupPopup = false }) {
                    Text("Close", color = GhTextSecondary)
                }
            }
        )
    }

    if (showEzvizOAuthModal) {
        EzvizOAuthWebSignInModal(
            context = context,
            appKey = ezvizCloudKey,
            onTokenCaptured = { _, _ ->
                showEzvizOAuthModal = false
                popupStatusBanner = "SUCCESS: EZVIZ OAuth connected! Tap 'Save & Connect' to start live video."
                session.forceRetry()
            },
            onDismiss = { showEzvizOAuthModal = false }
        )
    }
}

// ---------------------------------------------------------------------------
// ZERO-RELOAD LIBVLC RENDERER
// ---------------------------------------------------------------------------
@Composable
fun LiveStreamVideoView(
    session: CameraStreamSession,
    enableAudio: Boolean = false,
    cropToFillTile: Boolean = true,
    onOpenSetupPopup: (() -> Unit)? = null,
    onVideoSurfaceTap: (() -> Unit)? = null
) {
    var localVlcLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }

    LaunchedEffect(session, enableAudio) {
        session.setAudioEnabled(enableAudio)
    }

    LaunchedEffect(session.resolvedUrl, localVlcLayout) {
        val layout = localVlcLayout ?: return@LaunchedEffect
        if (session.resolvedUrl.isNotBlank()) {
            layout.post {
                session.attachToLayout(layout, enableAudio)
            }
        }
    }

    DisposableEffect(session) {
        onDispose {
            localVlcLayout?.let { layout ->
                session.detachLayout(layout)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val containerW = maxWidth
        val containerH = maxHeight
        val streamAspect = 16f / 9f

        val targetWidth = if (!cropToFillTile) {
            containerW
        } else if (containerW / containerH < streamAspect) {
            containerH * streamAspect
        } else {
            containerW
        }

        val targetHeight = if (!cropToFillTile) {
            containerH
        } else if (containerW / containerH > streamAspect) {
            containerW / streamAspect
        } else {
            containerH
        }

        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).also { layout ->
                    localVlcLayout = layout
                    layout.post {
                        session.attachToLayout(layout, enableAudio)
                    }
                }
            },
            modifier = Modifier.requiredSize(width = targetWidth, height = targetHeight)
        )

        if (onVideoSurfaceTap != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onVideoSurfaceTap()
                    }
            )
        }

        if (session.streamStatus.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = session.streamStatus,
                        color = if (session.hasError) GhAccentWarm else GhTextPrimary,
                        fontSize = 12.sp,
                        fontFamily = GhFont,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                    if (session.hasError) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { session.forceRetry() },
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = GhTileSurfaceElevated,
                                    contentColor = GhTextPrimary
                                )
                            ) {
                                Text("Retry", fontSize = 11.sp, fontFamily = GhFont)
                            }
                            if (onOpenSetupPopup != null) {
                                FilledTonalButton(
                                    onClick = { onOpenSetupPopup() },
                                    shape = RoundedCornerShape(50),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = GhTileSurfaceElevated,
                                        contentColor = GhAccentBlue
                                    )
                                ) {
                                    Text("Camera Setup", fontSize = 11.sp, fontFamily = GhFont)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
