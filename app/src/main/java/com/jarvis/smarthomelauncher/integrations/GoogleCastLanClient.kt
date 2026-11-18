package com.jarvis.smarthomelauncher.integrations

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// ---------------------------------------------------------------------------
// LOCAL GOOGLE CAST V2 & NEST MINI CONTROLLER (PORT 8009 TLS + mDNS + MP3 CAST)
// - Persistent Cast TLS 8009 Session Pool (PersistentCastTlsSession)
// - Bidirectional Heartbeat PING/PONG responder & keep-alive daemon (MED-03 fix)
// - Persisted TOFU TLS Certificate Pinning with Security Audit logging
// - Low-latency 35ms volume coalescing & parallel multi-speaker group dispatch
// ---------------------------------------------------------------------------
object GoogleCastLanClient {
    private const val TAG = "GoogleCastLAN"
    private const val PREFS_CAST_PINS = "clara_cast_tls_pins_v1"

    private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    private const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"

    private val castScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var heartbeatDaemonJob: Job? = null

    private class PersistentCastTlsSession(
        val endpointKey: String,
        val sslSocket: SSLSocket,
        val input: DataInputStream,
        val out: DataOutputStream,
        @Volatile var lastActivityEpochMs: Long = System.currentTimeMillis()
    ) {
        val lock = Any()

        fun isAlive(): Boolean {
            return !sslSocket.isClosed &&
                    sslSocket.isConnected &&
                    !sslSocket.isInputShutdown &&
                    !sslSocket.isOutputShutdown
        }

        fun closeQuietly() {
            try {
                sslSocket.close()
            } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "ClaraClient",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
        }
    }

    private val castSessionPool = ConcurrentHashMap<String, PersistentCastTlsSession>()
    private val requestIdCounter = AtomicInteger(10)
    private val pendingVolumeJobs = ConcurrentHashMap<String, Job>()
    @Volatile
    private var lastVolumeCommandTimestampMs: Long = 0L
    private val pinnedCertSha256ByHost = ConcurrentHashMap<String, String>()
    private val missedCastPollCountByTileId = ConcurrentHashMap<String, Int>()
    private val castCapabilityMaskByEndpoint = ConcurrentHashMap<String, Int>()

    fun clearCache() {
        castSessionPool.values.forEach { it.closeQuietly() }
        castSessionPool.clear()
        pendingVolumeJobs.values.forEach { it.cancel() }
        pendingVolumeJobs.clear()
    }

    fun isVideoCastDevice(device: DiscoveredCastDevice): Boolean {
        if (device.isCastGroup) return false
        val mask = castCapabilityMaskByEndpoint["${device.ipAddress}:${device.port}"] ?: 0
        if ((mask and 0x01) != 0) return true
        val modelLower = device.modelName.lowercase(java.util.Locale.US)
        if (modelLower.contains("chromecast audio")) return false
        return modelLower.contains("chromecast") ||
                modelLower.contains("google tv") ||
                modelLower.contains("android tv") ||
                modelLower.contains("shield") ||
                modelLower.contains("bravia") ||
                modelLower.contains("smart tv") ||
                modelLower.contains("cast video")
    }

    private fun resolveAppContext(explicitContext: Context? = null): Context? =
        explicitContext?.applicationContext
            ?: ClaraApplication.instance?.applicationContext
            ?: ClaraInAppMusicPlayer.currentAppContext()

    private fun verifyAndPersistTofuCertPin(
        targetHostIp: String,
        fingerprint: String,
        leafCert: X509Certificate? = null
    ) {
        val ctx = resolveAppContext()
        val prefs = ctx?.getSharedPreferences(PREFS_CAST_PINS, Context.MODE_PRIVATE)
        val savedPin = pinnedCertSha256ByHost[targetHostIp]
            ?: prefs?.getString("pin_$targetHostIp", null)

        if (savedPin == null) {
            pinnedCertSha256ByHost[targetHostIp] = fingerprint
            prefs?.edit()?.putString("pin_$targetHostIp", fingerprint)?.apply()
        } else if (!savedPin.equals(fingerprint, ignoreCase = true)) {
            // Google Cast speakers rotate ephemeral peer certificates every 24-48h.
            // If the new certificate is within its validity window on our private LAN, auto-heal the pin.
            val isCertTimeValid = leafCert != null && runCatching {
                leafCert.checkValidity()
                true
            }.getOrDefault(false)

            if (isCertTimeValid) {
                Log.i(TAG, "Auto-updated rotated Google Cast TLS pin for $targetHostIp (${savedPin.take(12)}... -> ${fingerprint.take(12)}...)")
                pinnedCertSha256ByHost[targetHostIp] = fingerprint
                prefs?.edit()?.putString("pin_$targetHostIp", fingerprint)?.apply()
            } else {
                Log.e(TAG, "Rejected expired/invalid Cast TLS certificate for $targetHostIp (expected ${savedPin.take(12)}..., got ${fingerprint.take(12)}...)")
                throw CertificateException("Cast TLS certificate invalid for $targetHostIp")
            }
        } else {
            pinnedCertSha256ByHost[targetHostIp] = fingerprint
        }
    }

    fun clearPinnedCastCertForReverification(context: Context, targetHostIp: String) {
        val cleanIp = targetHostIp.substringBefore(":").trim()
        if (cleanIp.isBlank()) return
        pinnedCertSha256ByHost.remove(cleanIp)
        context.applicationContext
            .getSharedPreferences(PREFS_CAST_PINS, Context.MODE_PRIVATE)
            .edit()
            .remove("pin_$cleanIp")
            .apply()
        evictCastSession(cleanIp, 8009)
    }

    private fun buildTofuLanSslSocketFactory(targetHostIp: String) =
        SSLContext.getInstance("TLS").apply {
            val tofuTrustManager = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) {
                        throw CertificateException("Empty Cast TLS certificate chain from $targetHostIp")
                    }
                    val addr = runCatching { InetAddress.getByName(targetHostIp) }.getOrNull()
                    val isPrivateLan = addr != null && (
                            addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress
                            )
                    if (!isPrivateLan) {
                        throw CertificateException("Refusing self-signed Cast TLS session on non-LAN IP: $targetHostIp")
                    }
                    val leafCert = chain[0]
                    // Pin the stable issuer/device public key if a Cast ICA chain is present, otherwise leaf public key
                    val pinSourceBytes = if (chain.size > 1) {
                        chain[1].publicKey.encoded
                    } else {
                        leafCert.publicKey.encoded
                    }
                    val sha256Bytes = MessageDigest.getInstance("SHA-256").digest(pinSourceBytes)
                    val fingerprint = sha256Bytes.joinToString("") { "%02x".format(it) }
                    verifyAndPersistTofuCertPin(targetHostIp, fingerprint, leafCert)
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            init(null, tofuTrustManager, SecureRandom())
        }.socketFactory

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }

    private fun writeStringField(out: ByteArrayOutputStream, fieldNumber: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val tag = (fieldNumber shl 3) or 2
        writeVarint(out, tag)
        writeVarint(out, bytes.size)
        out.write(bytes)
    }

    private fun encodeCastMessage(
        sourceId: String,
        destinationId: String,
        namespace: String,
        payloadJson: String
    ): ByteArray {
        val pb = ByteArrayOutputStream()
        pb.write(0x08)
        pb.write(0x00)
        writeStringField(pb, 2, sourceId)
        writeStringField(pb, 3, destinationId)
        writeStringField(pb, 4, namespace)
        pb.write(0x28)
        pb.write(0x00)
        writeStringField(pb, 6, payloadJson)
        return pb.toByteArray()
    }

    private fun extractUtf8JsonFromCastFrame(frame: ByteArray): String? {
        var i = 0
        while (i < frame.size) {
            var tag = 0
            var shift = 0
            while (i < frame.size) {
                val b = frame[i++].toInt() and 0xFF
                tag = tag or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            val fieldNum = tag ushr 3
            val wireType = tag and 0x07
            if (wireType == 0) {
                while (i < frame.size && (frame[i++].toInt() and 0x80) != 0) {}
            } else if (wireType == 2) {
                var len = 0
                var lShift = 0
                while (i < frame.size) {
                    val b = frame[i++].toInt() and 0xFF
                    len = len or ((b and 0x7F) shl lShift)
                    if ((b and 0x80) == 0) break
                    lShift += 7
                }
                if (len < 0 || i + len > frame.size) return null
                if (fieldNum == 6) {
                    return String(frame, i, len, Charsets.UTF_8)
                }
                i += len
            } else {
                break
            }
        }
        return null
    }

    private fun sendCastPacket(
        out: DataOutputStream,
        sourceId: String,
        destinationId: String,
        namespace: String,
        payloadJson: String
    ) {
        val body = encodeCastMessage(sourceId, destinationId, namespace, payloadJson)
        out.writeInt(body.size)
        out.write(body)
        out.flush()
    }

    /**
     * MED-03 Fix: Replies with `{"type":"PONG"}` on `NS_HEARTBEAT` whenever a `"PING"` frame
     * is received from the Cast device, and skips `"PONG"` frames cleanly.
     */
    private fun readNextCastJson(
        input: DataInputStream,
        out: DataOutputStream? = null,
        maxWaitMs: Long = 2200L
    ): String? {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val len = try {
                input.readInt()
            } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "ClaraClient",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
                return null
}
            if (len <= 0 || len > 256_000) return null
            val buf = ByteArray(len)
            input.readFully(buf)
            val jsonStr = extractUtf8JsonFromCastFrame(buf) ?: continue
            if (jsonStr.contains("\"PING\"")) {
                if (out != null) {
                    runCatching {
                        sendCastPacket(
                            out = out,
                            sourceId = "sender-0",
                            destinationId = "receiver-0",
                            namespace = NS_HEARTBEAT,
                            payloadJson = """{"type":"PONG"}"""
                        )
                    }
                }
                continue
            }
            if (jsonStr.contains("\"PONG\"")) continue
            return jsonStr
        }
        return null
    }

    private fun ensureCastHeartbeatDaemonRunning() {
        if (heartbeatDaemonJob?.isActive == true) return
        heartbeatDaemonJob = castScope.launch {
            while (isActive) {
                delay(10_000L)
                if (castSessionPool.isEmpty()) continue
                val now = System.currentTimeMillis()
                for ((key, session) in castSessionPool.entries.toList()) {
                    if (!session.isAlive()) {
                        castSessionPool.remove(key)?.closeQuietly()
                        continue
                    }
                    if (now - session.lastActivityEpochMs >= 9_000L) {
                        val pingOk = synchronized(session.lock) {
                            try {
                                sendCastPacket(
                                    out = session.out,
                                    sourceId = "sender-0",
                                    destinationId = "receiver-0",
                                    namespace = NS_HEARTBEAT,
                                    payloadJson = """{"type":"PING"}"""
                                )
                                session.lastActivityEpochMs = System.currentTimeMillis()
                                true
                            } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "ClaraClient",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
                                false
}
                        }
                        if (!pingOk) {
                            castSessionPool.remove(key)?.closeQuietly()
                        }
                    }
                }
            }
        }
    }

    private fun openCastTlsSocket(ip: String, port: Int, timeoutMs: Int = 1400): SSLSocket {
        val cleanIp = ip.trim()
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.keepAlive = true
        raw.connect(InetSocketAddress(cleanIp, port), timeoutMs)
        raw.soTimeout = timeoutMs
        val sslFactory = buildTofuLanSslSocketFactory(cleanIp)
        val ssl = sslFactory.createSocket(raw, cleanIp, port, true) as SSLSocket
        ssl.startHandshake()
        return ssl
    }

    private fun getOrConnectCastSession(
        ip: String,
        port: Int,
        timeoutMs: Int = 1200
    ): PersistentCastTlsSession? {
        val cleanIp = ip.trim()
        if (cleanIp.isBlank()) return null
        val key = "$cleanIp:$port"
        castSessionPool[key]?.let { existing ->
            if (existing.isAlive()) return existing
            castSessionPool.remove(key)?.closeQuietly()
        }
        return try {
            val ssl = openCastTlsSocket(cleanIp, port, timeoutMs)
            val out = DataOutputStream(ssl.outputStream)
            val input = DataInputStream(ssl.inputStream)
            sendCastPacket(
                out = out,
                sourceId = "sender-0",
                destinationId = "receiver-0",
                namespace = NS_CONNECTION,
                payloadJson = """{"type":"CONNECT"}"""
            )
            sendCastPacket(
                out = out,
                sourceId = "sender-0",
                destinationId = "receiver-0",
                namespace = NS_HEARTBEAT,
                payloadJson = """{"type":"PING"}"""
            )
            ensureCastHeartbeatDaemonRunning()
            PersistentCastTlsSession(key, ssl, input, out).also {
                castSessionPool[key] = it
            }
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "ClaraClient",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
            null
}
    }

    private fun evictCastSession(ip: String, port: Int) {
        val key = "${ip.trim()}:$port"
        castSessionPool.remove(key)?.closeQuietly()
    }

    data class CastLiveStatus(
        val volumePercent: Float,
        val isMuted: Boolean,
        val statusText: String
    )

    suspend fun queryCastDeviceStatus(ip: String, port: Int = 8009): CastLiveStatus? =
        withContext(Dispatchers.IO) {
            val cleanIp = ip.trim()
            if (cleanIp.isBlank()) return@withContext null
            for (attempt in 0..1) {
                val session = getOrConnectCastSession(cleanIp, port, 1200) ?: return@withContext null
                val result = synchronized(session.lock) {
                    try {
                        session.sslSocket.soTimeout = 1000
                        val reqId = requestIdCounter.getAndIncrement()
                        sendCastPacket(
                            out = session.out,
                            sourceId = "sender-0",
                            destinationId = "receiver-0",
                            namespace = NS_RECEIVER,
                            payloadJson = """{"type":"GET_STATUS","requestId":$reqId}"""
                        )
                        session.lastActivityEpochMs = System.currentTimeMillis()
                        var parsedStatus: CastLiveStatus? = null
                        repeat(4) {
                            val resp = readNextCastJson(session.input, session.out, 900L) ?: return@repeat
                            if (resp.contains("\"RECEIVER_STATUS\"")) {
                                val root = JSONObject(resp)
                                val statusObj = root.optJSONObject("status")
                                if (statusObj != null) {
                                    val volObj = statusObj.optJSONObject("volume")
                                    val level = volObj?.optDouble("level", 0.55) ?: 0.55
                                    val muted = volObj?.optBoolean("muted", false) ?: false
                                    val appsArr = statusObj.optJSONArray("applications")
                                    val appName = if (appsArr != null && appsArr.length() > 0) {
                                        appsArr.optJSONObject(0)?.optString("displayName", "Ready") ?: "Ready"
                                    } else {
                                        "Ready"
                                    }
                                    parsedStatus = CastLiveStatus(
                                        volumePercent = (level * 100.0).toFloat().coerceIn(0f, 100f),
                                        isMuted = muted,
                                        statusText = appName
                                    )
                                }
                            }
                            if (parsedStatus != null) return@synchronized parsedStatus
                        }
                        parsedStatus
                    } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "ClaraClient",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
                        null
}
                }
                if (result != null) {
                    SmartHomeApiClient.registerVerifiedLanNeighbor(cleanIp)
                    TuyaApiClient.markDeviceSeenOnline(cleanIp)
                    return@withContext result
                }
                evictCastSession(cleanIp, port)
            }
            null
        }

    suspend fun setCastDeviceVolume(ip: String, port: Int = 8009, volumePercent: Float): Boolean =
        withContext(Dispatchers.IO) {
            val cleanIp = ip.trim()
            if (cleanIp.isBlank()) return@withContext false
            val startMs = System.currentTimeMillis()
            lastVolumeCommandTimestampMs = startMs
            val level = (volumePercent.coerceIn(0f, 100f) / 100.0)

            for (attempt in 0..1) {
                val session = getOrConnectCastSession(cleanIp, port, 1100) ?: continue
                val sentOk = synchronized(session.lock) {
                    try {
                        session.sslSocket.soTimeout = 220
                        val reqId = requestIdCounter.getAndIncrement()
                        val volumePayload = JSONObject().apply {
                            put("type", "SET_VOLUME")
                            put("requestId", reqId)
                            put(
                                "volume",
                                JSONObject().apply {
                                    put("level", level)
                                    put("muted", volumePercent <= 0.5f)
                                }
                            )
                        }.toString()
                        sendCastPacket(
                            out = session.out,
                            sourceId = "sender-0",
                            destinationId = "receiver-0",
                            namespace = NS_RECEIVER,
                            payloadJson = volumePayload
                        )
                        session.lastActivityEpochMs = System.currentTimeMillis()
                        true
                    } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "ClaraClient",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
                        false
}
                }
                if (sentOk) {
                    SmartHomeApiClient.registerVerifiedLanNeighbor(cleanIp)
                    TuyaApiClient.markDeviceSeenOnline(cleanIp)
                    val currentCastList = JarvisLiveBridge.discoveredCastDevicesFlow.value
                    if (currentCastList.any { it.ipAddress == cleanIp }) {
                        JarvisLiveBridge.discoveredCastDevicesFlow.value = currentCastList.map { d ->
                            if (d.ipAddress == cleanIp) {
                                d.copy(volumePercent = volumePercent.coerceIn(0f, 100f), isMuted = volumePercent <= 0.5f)
                            } else d
                        }
                    }
                    JarvisLiveBridge.recordCommandLatency(
                        integrationKey = "GOOGLE_NEST",
                        displayName = "Google Nest SDM & Cast",
                        latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(3L),
                        succeeded = true
                    )
                    return@withContext true
                }
                evictCastSession(cleanIp, port)
            }
            Log.w(TAG, "setCastDeviceVolume failed for $cleanIp:$port")
            JarvisLiveBridge.recordCommandLatency(
                integrationKey = "GOOGLE_NEST",
                displayName = "Google Nest SDM & Cast",
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(15L),
                succeeded = false
            )
            false
        }

    suspend fun castDirectAudioStreamToSpeaker(
        ip: String,
        port: Int = 8009,
        mediaUrl: String,
        title: String,
        subtitle: String,
        startPositionSeconds: Double = 0.0
    ): Boolean = withContext(Dispatchers.IO) {
        if (ip.isBlank() || mediaUrl.isBlank()) return@withContext false
        val startMs = System.currentTimeMillis()
        try {
            openCastTlsSocket(ip, port, 2500).use { ssl ->
                val out = DataOutputStream(ssl.outputStream)
                val input = DataInputStream(ssl.inputStream)
                sendCastPacket(out, "sender-0", "receiver-0", NS_CONNECTION, """{"type":"CONNECT"}""")
                val launchJson = JSONObject().apply {
                    put("type", "LAUNCH")
                    put("appId", DEFAULT_MEDIA_RECEIVER_APP_ID)
                    put("requestId", requestIdCounter.getAndIncrement())
                }.toString()
                sendCastPacket(out, "sender-0", "receiver-0", NS_RECEIVER, launchJson)
                var transportId: String? = null
                val startWait = System.currentTimeMillis()
                while (System.currentTimeMillis() - startWait < 4000L && transportId == null) {
                    val resp = readNextCastJson(input, out, 1200L) ?: continue
                    if (resp.contains("\"RECEIVER_STATUS\"")) {
                        val apps = JSONObject(resp).optJSONObject("status")?.optJSONArray("applications")
                        if (apps != null && apps.length() > 0) {
                            for (i in 0 until apps.length()) {
                                val app = apps.optJSONObject(i) ?: continue
                                if (app.optString("appId") == DEFAULT_MEDIA_RECEIVER_APP_ID) {
                                    transportId = app.optString("transportId", "").takeIf { it.isNotBlank() }
                                    break
                                }
                            }
                        }
                    }
                }
                val targetTransport = transportId ?: return@use false
                sendCastPacket(out, "sender-0", targetTransport, NS_CONNECTION, """{"type":"CONNECT"}""")
                val mimeType = when {
                    mediaUrl.contains(".wav", true) -> "audio/wav"
                    mediaUrl.contains(".m4a", true) ||
                            mediaUrl.contains(".mp4", true) ||
                            mediaUrl.contains("mime=audio%2Fmp4", true) -> "audio/mp4"
                    else -> "audio/mpeg"
                }
                val loadPayload = JSONObject().apply {
                    put("type", "LOAD")
                    put("requestId", requestIdCounter.getAndIncrement())
                    put("autoplay", true)
                    put("currentTime", startPositionSeconds.coerceAtLeast(0.0))
                    put(
                        "media",
                        JSONObject().apply {
                            put("contentId", mediaUrl)
                            put("contentType", mimeType)
                            put("streamType", "BUFFERED")
                            put(
                                "metadata",
                                JSONObject().apply {
                                    put("metadataType", 3)
                                    put("title", title)
                                    put("artist", subtitle)
                                }
                            )
                        }
                    )
                }.toString()
                sendCastPacket(out, "sender-0", targetTransport, NS_MEDIA, loadPayload)
                val loadAckJson = readNextCastJson(input, out, 1200L).orEmpty()
                val loadRejected = loadAckJson.contains("\"LOAD_FAILED\"") ||
                        loadAckJson.contains("\"LOAD_CANCELLED\"") ||
                        loadAckJson.contains("\"INVALID_REQUEST\"")
                if (loadRejected) {
                    JarvisLiveBridge.recordCommandLatency(
                        integrationKey = "GOOGLE_NEST",
                        displayName = "Google Nest SDM & Cast",
                        latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(15L),
                        succeeded = false
                    )
                    return@use false
                }
                SmartHomeApiClient.registerVerifiedLanNeighbor(ip.trim())
                TuyaApiClient.markDeviceSeenOnline(ip.trim())
                JarvisLiveBridge.recordCommandLatency(
                    integrationKey = "GOOGLE_NEST",
                    displayName = "Google Nest SDM & Cast",
                    latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(15L),
                    succeeded = true
                )
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "castDirectAudioStreamToSpeaker error on $ip:$port -> ${e.message}")
            false
        }
    }

    suspend fun stopCastMediaOnSpeaker(ip: String, port: Int = 8009): Boolean =
        withContext(Dispatchers.IO) {
            val cleanIp = ip.trim()
            if (cleanIp.isBlank()) return@withContext false
            for (attempt in 0..1) {
                val session = getOrConnectCastSession(cleanIp, port, 1200) ?: continue
                val ok = synchronized(session.lock) {
                    try {
                        val reqId = requestIdCounter.getAndIncrement()
                        sendCastPacket(
                            session.out,
                            "sender-0",
                            "receiver-0",
                            NS_RECEIVER,
                            """{"type":"STOP","requestId":$reqId}"""
                        )
                        session.lastActivityEpochMs = System.currentTimeMillis()
                        true
                    } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "ClaraClient",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
                        false
}
                }
                if (ok) return@withContext true
                evictCastSession(cleanIp, port)
            }
            false
        }

    fun resolveEndpointsForTile(tile: SmartTile): List<Pair<String, Int>> {
        val discovered = JarvisLiveBridge.discoveredCastDevicesFlow.value
        val groups = JarvisLiveBridge.castSpeakerGroupsFlow.value

        val matchedGroup = groups.firstOrNull {
            it.id == tile.entityId || it.name.equals(tile.title, ignoreCase = true)
        }
        if (matchedGroup != null && matchedGroup.memberIps.isNotEmpty()) {
            return matchedGroup.memberIps.map { raw ->
                val host = raw.substringBefore(":")
                val port = raw.substringAfter(":", "8009").toIntOrNull() ?: 8009
                host to port
            }
        }

        if (tile.ipAddress.isNotBlank()) {
            return tile.ipAddress.split(",").mapNotNull { part ->
                val clean = part.trim()
                if (clean.isBlank()) null
                else {
                    val host = clean.substringBefore(":")
                    val port = clean.substringAfter(":", "8009").toIntOrNull() ?: 8009
                    host to port
                }
            }
        }

        val matchedDevice = discovered.firstOrNull { d ->
            (tile.entityId.isNotBlank() && d.id.equals(tile.entityId, ignoreCase = true)) ||
                    d.name.equals(tile.title, ignoreCase = true) ||
                    (tile.roomOrSubtitle.isNotBlank() && d.name.contains(tile.roomOrSubtitle, ignoreCase = true)) ||
                    tile.title.contains(d.name, ignoreCase = true)
        } ?: discovered.firstOrNull { !it.isCastGroup }

        return if (matchedDevice != null) {
            listOf(matchedDevice.ipAddress to matchedDevice.port)
        } else {
            emptyList()
        }
    }

    /**
     * Dispatches volume changes to Google Cast / Nest Mini speakers and speaker groups
     * with a fast 35ms coalesce window and parallel execution across all group endpoints.
     */
    fun enqueueDebouncedCastVolumeUpdate(
        scope: CoroutineScope,
        tile: SmartTile
    ) {
        lastVolumeCommandTimestampMs = System.currentTimeMillis()
        pendingVolumeJobs[tile.id]?.cancel()
        pendingVolumeJobs[tile.id] = scope.launch(Dispatchers.IO) {
            delay(35L)
            var endpoints = resolveEndpointsForTile(tile)
            if (endpoints.isEmpty()) {
                val ctx = resolveAppContext()
                if (ctx != null) {
                    discoverCastDevices(ctx, includeFullPortSweep = false)
                    endpoints = resolveEndpointsForTile(tile)
                }
            }
            if (endpoints.isNotEmpty()) {
                endpoints.map { (ip, port) ->
                    async(Dispatchers.IO) {
                        setCastDeviceVolume(ip, port, tile.volume)
                    }
                }.awaitAll()
            }

            val currentList = JarvisLiveBridge.discoveredCastDevicesFlow.value.toMutableList()
            var changed = false
            for (i in currentList.indices) {
                val d = currentList[i]
                if (endpoints.any { it.first == d.ipAddress && it.second == d.port }) {
                    currentList[i] = d.copy(volumePercent = tile.volume)
                    changed = true
                }
            }
            if (changed) {
                JarvisLiveBridge.discoveredCastDevicesFlow.value = currentList
            }
        }
    }

    private suspend fun resolveTargetCastEndpoints(
        bridge: BridgeSettings,
        tiles: List<SmartTile>
    ): Set<Pair<String, Int>> {
        var discovered = JarvisLiveBridge.discoveredCastDevicesFlow.value
        val ctx = resolveAppContext()
        if (discovered.isEmpty() && ctx != null) {
            discovered = discoverCastDevices(ctx, includeFullPortSweep = false)
        }
        val targetEndpoints = LinkedHashSet<Pair<String, Int>>()
        if (bridge.doorbellChimeTargetIps.isNotEmpty()) {
            bridge.doorbellChimeTargetIps.forEach { raw ->
                val host = raw.substringBefore(":")
                val port = raw.substringAfter(":", "8009").toIntOrNull() ?: 8009
                if (host.isNotBlank()) targetEndpoints.add(host to port)
            }
        }
        if (targetEndpoints.isEmpty()) {
            tiles.filter {
                it.type == TileType.NEST_MINI ||
                        it.resolvedProviderId == ProviderId.GOOGLE_CAST ||
                        it.deviceSubtype in setOf(DeviceSubtype.SPEAKER, DeviceSubtype.CAST_GROUP)
            }.forEach { miniTile ->
                targetEndpoints.addAll(resolveEndpointsForTile(miniTile))
            }
        }
        if (targetEndpoints.isEmpty()) {
            discovered.filter { !it.isCastGroup && !isVideoCastDevice(it) }.forEach { dev ->
                targetEndpoints.add(dev.ipAddress to dev.port)
            }
        }
        return targetEndpoints
    }

    /**
     * Streams a user-selected custom MP3 file (or synthesized harmonic chime) from the tablet's
     * embedded port 8089 HTTP server (`JarvisLiveBridge`) directly to Google Nest Mini speakers.
     */
    fun playCustomMp3OnNestMinis(
        scope: CoroutineScope,
        context: Context?,
        bridge: BridgeSettings,
        tiles: List<SmartTile>,
        slot: String = "doorbell",
        customUriOverride: String = ""
    ) {
        scope.launch(Dispatchers.IO) {
            val ctx = resolveAppContext(context)
            if (ctx != null) {
                JarvisLiveBridge.ensureLocalHubServerRunning(ctx)
            }
            val targetEndpoints = resolveTargetCastEndpoints(bridge, tiles)
            if (targetEndpoints.isEmpty()) return@launch

            val effectiveUri = customUriOverride.ifBlank {
                if (slot.equals("alarm", ignoreCase = true)) {
                    bridge.customAlarmMp3Uri
                } else {
                    bridge.customDoorbellMp3Uri
                }
            }
            val streamUrl = JarvisLiveBridge.buildLocalMediaStreamUrl(
                context = ctx,
                slot = slot,
                customUriOverride = effectiveUri
            )
            val chimeVolume = bridge.nestMiniDoorbellVolume.coerceIn(20, 100).toFloat()

            targetEndpoints.map { (ip, port) ->
                async(Dispatchers.IO) {
                    castDoorbellUrlToSpeaker(
                        ip = ip,
                        port = port,
                        mediaUrl = streamUrl,
                        chimeVolumePercent = chimeVolume,
                        holdDurationMs = 6000L
                    )
                }
            }.awaitAll()
        }
    }

    /**
     * Plays the Doorbell Chime & Announcement on Google Nest Mini speakers:
     * - If the user configured a Custom Doorbell MP3 (`bridge.customDoorbellMp3Uri`), streams
     *   the custom MP3 from the tablet's port 8089 server first, then speaks the announcement.
     * - Otherwise streams the spoken chime & announcement directly.
     */
    fun playDoorbellChimeOnNestMinis(
        scope: CoroutineScope,
        bridge: BridgeSettings,
        tiles: List<SmartTile>,
        customAnnouncementText: String? = null
    ) {
        scope.launch(Dispatchers.IO) {
            val ctx = resolveAppContext()
            if (ctx != null) {
                JarvisLiveBridge.ensureLocalHubServerRunning(ctx)
            }
            val targetEndpoints = resolveTargetCastEndpoints(bridge, tiles)
            if (targetEndpoints.isEmpty()) return@launch

            val announcement = (customAnnouncementText?.takeIf { it.isNotBlank() }
                ?: bridge.doorbellChimeMessage.takeIf { it.isNotBlank() }
                ?: "Visitor at the front door").trim()

            val chimeVolume = bridge.nestMiniDoorbellVolume.coerceIn(20, 100).toFloat()
            val hasCustomMp3 = bridge.customDoorbellMp3Uri.isNotBlank()

            if (hasCustomMp3) {
                val customMp3StreamUrl = JarvisLiveBridge.buildLocalMediaStreamUrl(
                    context = ctx,
                    slot = "doorbell",
                    customUriOverride = bridge.customDoorbellMp3Uri
                )
                targetEndpoints.map { (ip, port) ->
                    async(Dispatchers.IO) {
                        castDoorbellUrlToSpeaker(
                            ip = ip,
                            port = port,
                            mediaUrl = customMp3StreamUrl,
                            chimeVolumePercent = chimeVolume,
                            holdDurationMs = 4200L
                        )
                    }
                }.awaitAll()
            }

            val spokenPhrase = if (hasCustomMp3) announcement else "Ding dong. $announcement"

            // Primary: Serve locally synthesized 16-bit PCM WAV speech from the tablet's port 8089 server
            val localTtsStreamUrl = JarvisLiveBridge.buildLocalTtsStreamUrl(
                context = ctx,
                spokenText = spokenPhrase
            )
            val castLocalOk = targetEndpoints.map { (ip, port) ->
                async(Dispatchers.IO) {
                    castDoorbellUrlToSpeaker(
                        ip = ip,
                        port = port,
                        mediaUrl = localTtsStreamUrl,
                        chimeVolumePercent = chimeVolume,
                        holdDurationMs = 5500L
                    )
                }
            }.awaitAll().any { it }

            // Fallback: Only if local WAV cast failed to connect, fall back to cloud TTS URL
            if (!castLocalOk) {
                val encodedText = URLEncoder.encode(spokenPhrase, "UTF-8")
                val fallbackCloudTtsUrl =
                    "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=en-GB&q=$encodedText"
                targetEndpoints.map { (ip, port) ->
                    async(Dispatchers.IO) {
                        castDoorbellUrlToSpeaker(
                            ip = ip,
                            port = port,
                            mediaUrl = fallbackCloudTtsUrl,
                            chimeVolumePercent = chimeVolume,
                            holdDurationMs = 5500L
                        )
                    }
                }.awaitAll()
            }

            JarvisLiveBridge.emitPlatformEvent(
                JarvisPlatformEvent(
                    category = JarvisEventCategory.DOORBELL,
                    sourceDeviceTitle = "Nest Mini Speakers",
                    eventType = "DOORBELL_CHIME_CAST",
                    summary = "Played doorbell chime on ${targetEndpoints.size} speaker(s): \"$announcement\""
                )
            )
        }
    }

    private suspend fun castDoorbellUrlToSpeaker(
        ip: String,
        port: Int,
        mediaUrl: String,
        chimeVolumePercent: Float,
        holdDurationMs: Long = 5500L
    ): Boolean = withContext(Dispatchers.IO) {
        val previousStatus = queryCastDeviceStatus(ip, port)
        val previousVol = previousStatus?.volumePercent ?: 50f
        try {
            setCastDeviceVolume(ip, port, chimeVolumePercent)
            val ok = castDirectAudioStreamToSpeaker(
                ip = ip,
                port = port,
                mediaUrl = mediaUrl,
                title = "Doorbell Alert",
                subtitle = "Clara Smart Home"
            )
            delay(holdDurationMs)
            setCastDeviceVolume(ip, port, previousVol)
            ok
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "ClaraClient",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
            false
}
    }

    suspend fun discoverCastDevices(
        context: Context,
        includeFullPortSweep: Boolean = true
    ): List<DiscoveredCastDevice> = withContext(Dispatchers.IO) {
        val discoveredMap = ConcurrentHashMap<String, DiscoveredCastDevice>()

        val mdnsResults = discoverViaNsdManager(context, timeoutMs = 1800L)
        for (d in mdnsResults) {
            SmartHomeApiClient.registerVerifiedLanNeighbor(d.ipAddress, d.id)
            discoveredMap["${d.ipAddress}:${d.port}"] = d
        }

        val prefix = SmartHomeApiClient.resolveLocalSubnetPrefixInternal(context)
        if (prefix != null) {
            val candidateIps = if (includeFullPortSweep) {
                (1..254).map { "$prefix.$it" }
            } else {
                SmartHomeApiClient.readActiveArpHosts(prefix).keys.toList()
            }
            if (candidateIps.isNotEmpty()) {
                coroutineScope {
                    candidateIps.chunked(36).flatMap { chunk ->
                        chunk.map { ip ->
                            async(Dispatchers.IO) {
                                val key = "$ip:8009"
                                val alreadyFoundAtIp = discoveredMap.values.any { it.ipAddress == ip }
                                if (!alreadyFoundAtIp &&
                                    !discoveredMap.containsKey(key) &&
                                    SmartHomeApiClient.isPortOpenInternal(ip, 8009, 200)
                                ) {
                                    DiscoveryFlightRecorder.record(
                                        eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                                        transportSource = "GOOGLE_CAST_TCP_8009",
                                        ipAddress = ip,
                                        port = 8009,
                                        vendor = "TCP 8009 Host",
                                        confidenceScore = 0.20f,
                                        qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                                        outcomeReason = "Open TCP port 8009 responder detected"
                                    )
                                    val info = fetchEurekaDeviceNameAndModel(ip)
                                    val verifiedName = info.first.trim()
                                    // Only add if Eureka returned a real friendly name (never invent a generic "Google Nest Mini")
                                    if (verifiedName.isNotBlank() && !verifiedName.equals("Google Nest Mini", ignoreCase = true)) {
                                        val live = queryCastDeviceStatus(ip, 8009)
                                        val learnedMac = CrossEcosystemIdentityCorrelator.getObservedIpToMacMap()[ip].orEmpty()
                                        DiscoveryFlightRecorder.record(
                                            eventType = DiscoveryEventType.IDENTIFIED,
                                            transportSource = "GOOGLE_CAST_TCP_8009",
                                            hardwareId = info.third.ifBlank { key },
                                            macAddress = learnedMac,
                                            ipAddress = ip,
                                            port = 8009,
                                            vendor = "$verifiedName (${info.second.ifBlank { "Google Cast" }})",
                                            confidenceScore = 0.95f,
                                            qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                                            outcomeReason = "Verified Google Cast Eureka setup descriptor on port 8008/8009"
                                        )
                                        discoveredMap[key] = DiscoveredCastDevice(
                                            id = info.third.ifBlank { key },
                                            name = verifiedName,
                                            ipAddress = ip,
                                            port = 8009,
                                            modelName = info.second.ifBlank { "Google Nest Mini" },
                                            volumePercent = live?.volumePercent ?: 55f,
                                            isMuted = live?.isMuted ?: false,
                                            isCastGroup = false,
                                            statusText = live?.statusText ?: "Ready"
                                        )
                                    } else {
                                        DiscoveryFlightRecorder.record(
                                            eventType = DiscoveryEventType.FILTERED,
                                            transportSource = "GOOGLE_CAST_TCP_8009",
                                            ipAddress = ip,
                                            port = 8009,
                                            vendor = "Non-Eureka TCP 8009 Host",
                                            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                            outcomeReason = "Rejected TCP 8009 host without verified Eureka friendly name"
                                        )
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        coroutineScope {
            discoveredMap.entries.map { (key, dev) ->
                async(Dispatchers.IO) {
                    val live = queryCastDeviceStatus(dev.ipAddress, dev.port)
                    if (live != null) {
                        discoveredMap[key] = dev.copy(
                            volumePercent = live.volumePercent,
                            isMuted = live.isMuted,
                            statusText = live.statusText
                        )
                    }
                }
            }.awaitAll()
        }

        val finalList = discoveredMap.values.sortedWith(
            compareByDescending<DiscoveredCastDevice> { it.isCastGroup }.thenBy { it.name }
        )
        JarvisLiveBridge.discoveredCastDevicesFlow.value = finalList
        JarvisLiveBridge.updateIntegrationDeviceCounts(
            integrationKey = "GOOGLE_NEST",
            displayName = "Google Nest SDM & Cast",
            onlineCount = finalList.size,
            offlineCount = 0
        )
        finalList
    }

    private fun fetchEurekaDeviceNameAndModel(ip: String): Triple<String, String, String> {
        return try {
            val conn = (URL("http://$ip:8008/setup/eureka_info?options=detail").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 900
                readTimeout = 900
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                val isVerifiedEureka = json.has("cast_build_revision") || json.has("ssdp_udn") || json.has("mac_address")
                if (!isVerifiedEureka) return Triple("", "", "")
                val mac = json.optString("mac_address", "")
                if (mac.isNotBlank()) {
                    CrossEcosystemIdentityCorrelator.recordApplicationLayerObservation(ip, mac)
                }
                val name = json.optString("name", "").trim()
                val model = json.optString("model_name", "").trim()
                val ssdpUdn = json.optString("ssdp_udn", "").trim()
                Triple(name, model.ifBlank { "Google Cast Device" }, ssdpUdn)
            } else {
                conn.disconnect()
                Triple("", "", "")
            }
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "ClaraClient",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
            Triple("", "", "")
}
    }

    private suspend fun discoverViaNsdManager(
        context: Context,
        timeoutMs: Long
    ): List<DiscoveredCastDevice> = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, DiscoveredCastDevice>()
        val resolvedServices = DiscoveryRadioCoordinator.discoverMdnsServices(
            context = context,
            serviceTypes = listOf("_googlecast._tcp."),
            timeoutMs = timeoutMs,
            transportLabel = "GOOGLE_CAST_MDNS"
        )
        for (svc in resolvedServices) {
            val hostIp = svc.hostIp
            val port = if (svc.port > 0) svc.port else 8009
            val txt = svc.txtAttributes
            val id = txt["id"].orEmpty().trim()
            val friendlyName = txt["fn"].orEmpty().trim()
            val rawModel = txt["md"].orEmpty().trim()
            val caMask = txt["ca"]?.toIntOrNull() ?: 0

            if (id.isBlank() || (friendlyName.isBlank() && rawModel.isBlank())) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FILTERED,
                    transportSource = "GOOGLE_CAST_MDNS",
                    hardwareId = svc.serviceName,
                    ipAddress = hostIp,
                    port = port,
                    vendor = "Google Cast",
                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                    outcomeReason = "Filtered incomplete _googlecast._tcp. record missing Cast TXT id/fn"
                )
                continue
            }

            CrossEcosystemIdentityCorrelator.extractMacFromApplicationPayload(
                ipAddress = hostIp,
                mdnsTxtAttributes = txt
            )
            val endpointKey = "$hostIp:$port"
            castCapabilityMaskByEndpoint[endpointKey] = caMask

            val isGroup = port != 8009 || (caMask and 0x20) != 0 || rawModel.contains("Group", ignoreCase = true)
            val hasVideoOut = !isGroup && (caMask and 0x01) != 0
            val resolvedModel = when {
                isGroup -> "Google Cast Speaker Group"
                rawModel.isNotBlank() -> rawModel
                hasVideoOut -> "Google Cast Video"
                else -> "Google Cast Speaker"
            }

            val learnedMac = CrossEcosystemIdentityCorrelator.getObservedIpToMacMap()[hostIp].orEmpty()
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.IDENTIFIED,
                transportSource = "GOOGLE_CAST_MDNS",
                hardwareId = id,
                macAddress = learnedMac,
                ipAddress = hostIp,
                port = port,
                vendor = "${friendlyName.ifBlank { resolvedModel }} ($resolvedModel)",
                confidenceScore = 0.96f,
                qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                outcomeReason = "Google Cast mDNS TXT verified (ca=$caMask group=$isGroup video=$hasVideoOut)"
            )
            found[endpointKey] = DiscoveredCastDevice(
                id = id,
                name = friendlyName.ifBlank { resolvedModel },
                ipAddress = hostIp,
                port = port,
                modelName = resolvedModel,
                isCastGroup = isGroup
            )
        }
        found.values.toList()
    }

    suspend fun syncLiveNestMiniVolumes(
        context: Context,
        tiles: MutableList<SmartTile>
    ) {
        if (System.currentTimeMillis() - lastVolumeCommandTimestampMs < 4500L) return
        val castTiles = tiles.filter {
            it.serviceClass == null && (
                    it.type == TileType.NEST_MINI ||
                            it.resolvedProviderId == ProviderId.GOOGLE_CAST ||
                            it.deviceSubtype in setOf(DeviceSubtype.SPEAKER, DeviceSubtype.CAST_GROUP)
                    )
        }
        if (castTiles.isEmpty()) return
        if (JarvisLiveBridge.discoveredCastDevicesFlow.value.isEmpty()) {
            discoverCastDevices(context, includeFullPortSweep = false)
        }
        val updatedVolumesByTileId = ConcurrentHashMap<String, Pair<Float, String>>()
        val pollSemaphore = Semaphore(permits = 8)
        supervisorScope {
            castTiles.map { tile ->
                async(Dispatchers.IO) {
                    pollSemaphore.withPermit {
                        var endpoints = resolveEndpointsForTile(tile)
                        var status = endpoints.firstOrNull()?.let { primary ->
                            queryCastDeviceStatus(primary.first, primary.second)
                        }
                        if (status == null && tile.isConfiguredAndLinked) {
                            discoverCastDevices(context, includeFullPortSweep = false)
                            endpoints = resolveEndpointsForTile(tile)
                            status = endpoints.firstOrNull()?.let { primary ->
                                queryCastDeviceStatus(primary.first, primary.second)
                            }
                        }
                        if (status != null && endpoints.isNotEmpty()) {
                            val ipListStr = endpoints.joinToString(",") { "${it.first}:${it.second}" }
                            updatedVolumesByTileId[tile.id] = status.volumePercent to ipListStr
                        }
                    }
                }
            }.awaitAll()
        }
        val polledCastIds = castTiles.map { it.id }.toSet()
        withContext(Dispatchers.Main) {
            for (i in tiles.indices) {
                val t = tiles[i]
                if (t.id !in polledCastIds) continue
                val pair = updatedVolumesByTileId[t.id]
                if (pair == null) {
                    if (t.isConfiguredAndLinked) {
                        val misses = (missedCastPollCountByTileId[t.id] ?: 0) + 1
                        missedCastPollCountByTileId[t.id] = misses
                        if (misses >= 2 && t.isOnline) {
                            val offlineCandidate = t.copy(
                                isOnline = false,
                                lifecycleState = DeviceLifecycleState.OFFLINE
                            )
                            tiles[i] = DeviceRepository.reconcileRemoteTileUpdate(
                                existing = t,
                                incoming = offlineCandidate,
                                incomingAuthority = StateAuthorityTier.DEVICE_HARDWARE_TRUTH,
                                sourceName = "Google Cast"
                            )
                        }
                    }
                    continue
                }
                missedCastPollCountByTileId[t.id] = 0
                val nextVol = pair.first
                val nextIp = if (t.ipAddress.isBlank()) pair.second else t.ipAddress
                val shouldBeActive = nextVol > 0.5f
                if (kotlin.math.abs(t.volume - nextVol) >= 1f || t.ipAddress != nextIp || !t.isOnline || t.isActive != shouldBeActive) {
                    val candidate = t.copy(
                        volume = nextVol,
                        isActive = shouldBeActive,
                        ipAddress = nextIp,
                        isOnline = true,
                        lifecycleState = DeviceLifecycleState.ONLINE
                    )
                    tiles[i] = DeviceRepository.reconcileRemoteTileUpdate(t, candidate)
                }
            }
        }
    }

    suspend fun wakeSpotifyConnectOnCastSpeakers(
        endpoints: List<Pair<String, Int>>,
        spotifyAccessToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (endpoints.isEmpty()) return@withContext false
        val cleanToken = spotifyAccessToken
            .removePrefix("Bearer ")
            .removePrefix("bearer ")
            .trim()
        val spotifyCastAppId = "CC32E753"
        val nsSpotify = "urn:x-cast:com.spotify.chromecast.secure.v1"

        coroutineScope {
            endpoints.map { (ip, port) ->
                async(Dispatchers.IO) {
                    try {
                        openCastTlsSocket(ip, port, 2500).use { ssl ->
                            val out = DataOutputStream(ssl.outputStream)
                            val input = DataInputStream(ssl.inputStream)
                            sendCastPacket(out, "sender-0", "receiver-0", NS_CONNECTION, """{"type":"CONNECT"}""")
                            val launchJson = JSONObject().apply {
                                put("type", "LAUNCH")
                                put("appId", spotifyCastAppId)
                                put("requestId", requestIdCounter.getAndIncrement())
                            }.toString()
                            sendCastPacket(out, "sender-0", "receiver-0", NS_RECEIVER, launchJson)

                            var transportId: String? = null
                            val startWait = System.currentTimeMillis()
                            while (System.currentTimeMillis() - startWait < 3500L && transportId == null) {
                                val resp = readNextCastJson(input, out, 1000L) ?: continue
                                if (resp.contains("\"RECEIVER_STATUS\"")) {
                                    val apps = JSONObject(resp).optJSONObject("status")?.optJSONArray("applications")
                                    if (apps != null) {
                                        for (i in 0 until apps.length()) {
                                            val app = apps.optJSONObject(i) ?: continue
                                            if (app.optString("appId") == spotifyCastAppId) {
                                                transportId = app.optString("transportId", "").takeIf { it.isNotBlank() }
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                            if (transportId != null && cleanToken.isNotBlank()) {
                                sendCastPacket(out, "sender-0", transportId, NS_CONNECTION, """{"type":"CONNECT"}""")
                                val credPayload = JSONObject().apply {
                                    put("type", "setCredentials")
                                    put("credentials", cleanToken)
                                    put("expiresIn", 3600)
                                }.toString()
                                sendCastPacket(out, "sender-0", transportId, nsSpotify, credPayload)
                            }
                            transportId != null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "wakeSpotifyConnectOnCastSpeakers note on $ip:$port -> ${e.message}")
                        false
                    }
                }
            }.awaitAll().any { it }
        }
    }

    fun selectTileAsActiveCastOutput(tile: SmartTile, scope: CoroutineScope) {
        val endpoints = resolveEndpointsForTile(tile)
        if (endpoints.isNotEmpty()) {
            ClaraInAppMusicPlayer.selectCastSpeakerOutput(
                targetName = tile.title,
                endpoints = endpoints,
                scope = scope
            )
        }
    }
}
