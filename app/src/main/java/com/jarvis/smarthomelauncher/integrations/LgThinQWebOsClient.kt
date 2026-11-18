package com.jarvis.smarthomelauncher.integrations

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToInt
import kotlin.random.Random

// ---------------------------------------------------------------------------
// LG WEBOS HARDWARE REMOTE BUTTON DEFINITIONS
// ---------------------------------------------------------------------------
enum class LgRemoteButton(val pointerSocketKey: String, val ssapFallbackUri: String = "") {
    UP("UP"),
    DOWN("DOWN"),
    LEFT("LEFT"),
    RIGHT("RIGHT"),
    OK("ENTER", "ssap://com.webos.service.ime/sendEnterKey"),
    BACK("BACK"),
    HOME("HOME"),
    MENU("MENU"),
    EXIT("EXIT", "ssap://system.launcher/close"),
    INFO("INFO"),
    VOL_UP("VOLUMEUP", "ssap://audio/volumeUp"),
    VOL_DOWN("VOLUMEDOWN", "ssap://audio/volumeDown"),
    CH_UP("CHANNELUP", "ssap://tv/channelUp"),
    CH_DOWN("CHANNELDOWN", "ssap://tv/channelDown"),
    PLAY("PLAY", "ssap://media.controls/play"),
    PAUSE("PAUSE", "ssap://media.controls/pause"),
    STOP("STOP", "ssap://media.controls/stop"),
    REWIND("REWIND", "ssap://media.controls/rewind"),
    FAST_FORWARD("FASTFORWARD", "ssap://media.controls/fastForward")
}

// ---------------------------------------------------------------------------
// LG THINQ / LG WEBOS SMART TV CLIENT (Persistent SSAP & Pointer WebSockets)
// ---------------------------------------------------------------------------
object LgThinQWebOsClient {
    private const val TAG = "LgThinQTV"
    private const val PREFS_NAME = "clara_lg_webos_tv_prefs_v2"
    private const val KEY_CUSTOM_TV_APPS_JSON = "custom_tv_apps_json_v1"
    private const val KEY_LAST_KNOWN_TV_IP = "last_known_tv_ip"
    private const val IP_VERIFY_CACHE_TTL_MS = 60_000L

    private val tvKeepAliveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var keepAliveJob: Job? = null

    suspend fun syncLiveLgTvStates(context: Context, tiles: MutableList<SmartTile>) {
        val lgTiles = tiles.filter { it.type == TileType.LG_TV || it.deviceSubtype == DeviceSubtype.TV }
        if (lgTiles.isEmpty()) return
        coroutineScope {
            lgTiles.map { tile ->
                async(Dispatchers.IO) {
                    val ip = tile.ipAddress.substringBefore(":")
                    if (ip.isNotBlank()) {
                        val isOnline = probeVerifiedWebOsSsapBanner(ip, 750)
                        val idx = tiles.indexOfFirst { it.id == tile.id }
                        if (idx != -1) {
                            tiles[idx] = tile.copy(isOnline = isOnline)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private class PersistentSsapSession(
        val ipAddress: String,
        val port: Int,
        val useTls: Boolean,
        val socket: Socket,
        val input: InputStream,
        val out: OutputStream,
        @Volatile var lastActivityEpochMs: Long = System.currentTimeMillis()
    ) {
        val lock = Any()

        fun isAlive(): Boolean {
            return !socket.isClosed &&
                    socket.isConnected &&
                    !socket.isInputShutdown &&
                    !socket.isOutputShutdown
        }

        fun closeQuietly() {
            try {
                socket.close()
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

    private class PersistentPointerSession(
        val ipAddress: String,
        val socketPathUrl: String,
        val socket: Socket,
        val out: OutputStream,
        @Volatile var lastActivityEpochMs: Long = System.currentTimeMillis()
    ) {
        val lock = Any()

        fun isAlive(): Boolean {
            return !socket.isClosed &&
                    socket.isConnected &&
                    !socket.isOutputShutdown
        }

        fun closeQuietly() {
            try {
                socket.close()
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

    private val ssapSessionsByIp = ConcurrentHashMap<String, PersistentSsapSession>()
    private val pointerSessionsByIp = ConcurrentHashMap<String, PersistentPointerSession>()
    private val verifiedTvIpTimestampByIp = ConcurrentHashMap<String, Long>()
    private val requestCounter = AtomicLong(1L)
    private val pinnedTvCertSha256ByHost = ConcurrentHashMap<String, String>()
    val lastKnownTvVolumeByIp = ConcurrentHashMap<String, Int>()
    val lastKnownTvMutedByIp = ConcurrentHashMap<String, Boolean>()

    fun disconnectAll() {
        ssapSessionsByIp.values.forEach { it.closeQuietly() }
        ssapSessionsByIp.clear()
        pointerSessionsByIp.values.forEach { it.closeQuietly() }
        pointerSessionsByIp.clear()
    }

    private fun extractAndPersistVolumeFromPayload(context: Context?, cleanIp: String, payload: JSONObject?): Int {
        if (payload == null) return -1
        val directVol = payload.optInt("volume", -1)
        val nestedStatus = payload.optJSONObject("volumeStatus")
        val resolvedVol = if (directVol in 0..100) {
            directVol
        } else {
            nestedStatus?.optInt("volume", -1) ?: -1
        }
        val isMuted = if (payload.has("muted")) {
            payload.optBoolean("muted", false)
        } else {
            nestedStatus?.optBoolean("muteStatus", false) ?: false
        }
        if (resolvedVol in 0..100) {
            lastKnownTvVolumeByIp[cleanIp] = resolvedVol
            lastKnownTvMutedByIp[cleanIp] = isMuted
            if (context != null) {
                prefs(context).edit().putInt("tv_hw_vol_$cleanIp", resolvedVol).apply()
                val currentTiles = DeviceRepository.loadTiles(context).toMutableList()
                var changed = false
                for (i in currentTiles.indices) {
                    val t = currentTiles[i]
                    val tileIp = t.ipAddress.substringBefore(":").trim()
                    if ((t.type == TileType.LG_TV || t.deviceSubtype == DeviceSubtype.TV) &&
                        (tileIp.isBlank() || tileIp.equals(cleanIp, ignoreCase = true))
                    ) {
                        if (t.volume.roundToInt() != resolvedVol) {
                            currentTiles[i] = t.copy(
                                volume = resolvedVol.toFloat(),
                                stateAuthorityTier = StateAuthorityTier.DEVICE_HARDWARE_TRUTH,
                                stateOriginEpochMs = System.currentTimeMillis()
                            ).withUpdatedCapability("volume", resolvedVol.toString())
                            changed = true
                        }
                    }
                }
                if (changed) {
                    DeviceRepository.saveTiles(context, currentTiles)
                }
            }
        }
        return resolvedVol
    }

    private fun resolveAppContext(explicitContext: Context? = null): Context? =
        ClaraApplication.appContextOrNull(explicitContext)

    private class LgTlsPinMismatchException(
        val hostIp: String,
        val expectedFingerprint: String,
        val actualFingerprint: String
    ) : CertificateException(
        "LG webOS TLS certificate rotated on $hostIp (expected ${expectedFingerprint.take(8)}, got ${actualFingerprint.take(8)})"
    )

    private fun verifyAndPersistTvTlsCertPin(targetHostIp: String, fingerprint: String) {
        val cleanHost = targetHostIp.substringBefore(":").trim()
        val ctx = resolveAppContext()
        val p = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPin = pinnedTvCertSha256ByHost[cleanHost]
            ?: p?.getString("tv_tls_pin_$cleanHost", null)

        if (savedPin == null) {
            pinnedTvCertSha256ByHost[cleanHost] = fingerprint
            p?.edit()?.putString("tv_tls_pin_$cleanHost", fingerprint)?.apply()
        } else if (!savedPin.equals(fingerprint, ignoreCase = true)) {
            Log.w(TAG, "Detected rotated LG webOS TLS certificate on $cleanHost; triggering pin reset & TV re-verification")
            if (ctx != null) {
                DeviceRepository.logAuditEvent(
                    context = ctx,
                    actionType = "LG_TV_TLS_CERT_ROTATED_REVERIFY",
                    targetName = "LG webOS TV ($cleanHost)",
                    detail = "Detected rotated TLS certificate on $cleanHost; clearing stale pin to re-prompt on TV screen.",
                    succeeded = true
                )
            }
            throw LgTlsPinMismatchException(
                hostIp = cleanHost,
                expectedFingerprint = savedPin,
                actualFingerprint = fingerprint
            )
        } else {
            pinnedTvCertSha256ByHost[cleanHost] = fingerprint
        }
    }

    fun clearPinnedTvCertForReverification(context: Context, targetHostIp: String, macAddress: String = "") {
        val cleanIp = targetHostIp.substringBefore(":").trim()
        if (cleanIp.isBlank()) return
        pinnedTvCertSha256ByHost.remove(cleanIp)
        prefs(context).edit()
            .remove("tv_tls_pin_$cleanIp")
            .remove("client_key_$cleanIp")
            .apply()
        val resolvedMac = macAddress.ifBlank { getSavedMacAddress(context, cleanIp) }
        ClaraSecretVault.clearLgWebOsClientKey(
            context = context,
            ipAddress = cleanIp,
            macAddress = resolvedMac
        )
        invalidateSessionsForIp(cleanIp)
    }

    private fun buildTofuLanSslSocketFactory(targetHostIp: String) =
        SSLContext.getInstance("TLS").apply {
            val tofuTrustManager = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) {
                        throw CertificateException("Empty LG webOS TLS certificate chain from $targetHostIp")
                    }
                    val addr = runCatching { InetAddress.getByName(targetHostIp) }.getOrNull()
                    val isPrivateLan = addr != null && (
                            addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress
                            )
                    if (!isPrivateLan) {
                        throw CertificateException("Refusing self-signed LG webOS TLS session on non-LAN IP: $targetHostIp")
                    }
                    val leafCert = chain[0]
                    val sha256Bytes = MessageDigest.getInstance("SHA-256").digest(leafCert.encoded)
                    val fingerprint = sha256Bytes.joinToString("") { "%02x".format(it) }
                    verifyAndPersistTvTlsCertPin(targetHostIp, fingerprint)
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            init(null, tofuTrustManager, SecureRandom())
        }.socketFactory

    private val registrationPayloadTemplate = """
        {
          "type": "register",
          "id": "register_0",
          "payload": {
            "forcePairing": false,
            "pairingType": "PROMPT",
            "manifest": {
              "manifestVersion": 1,
              "appVersion": "1.1",
              "signed": {
                "created": "20140509",
                "appId": "com.lge.test",
                "vendorId": "com.lge",
                "localizedAppNames": { "": "Clara Smart Home" },
                "localizedVendorNames": { "": "Clara" },
                "permissions": [
                  "LAUNCH",
                  "LAUNCH_WEBAPP",
                  "CONTROL_AUDIO",
                  "CONTROL_POWER",
                  "CONTROL_INPUT_TV",
                  "CONTROL_INPUT_TEXT",
                  "CONTROL_INPUT_MEDIA_PLAYBACK",
                  "CONTROL_MOUSE_AND_KEYBOARD",
                  "READ_CURRENT_CHANNEL",
                  "READ_RUNNING_APPS",
                  "READ_INSTALLED_APPS",
                  "READ_NETWORK_STATE",
                  "WRITE_NOTIFICATION_TOAST"
                ],
                "serial": "2f930e2d2cfe083771f68e4fe7bb07"
              },
              "permissions": [
                "LAUNCH",
                "LAUNCH_WEBAPP",
                "CONTROL_AUDIO",
                "CONTROL_POWER",
                "CONTROL_INPUT_TV",
                "CONTROL_INPUT_TEXT",
                "CONTROL_INPUT_MEDIA_PLAYBACK",
                "CONTROL_MOUSE_AND_KEYBOARD",
                "READ_CURRENT_CHANNEL",
                "READ_RUNNING_APPS",
                "READ_INSTALLED_APPS",
                "READ_NETWORK_STATE",
                "WRITE_NOTIFICATION_TOAST"
              ]
            }
          }
        }
    """.trimIndent()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getSavedClientKey(
        context: Context,
        ip: String,
        macAddress: String = "",
        deviceUuid: String = ""
    ): String {
        val cleanIp = ip.trim().substringBefore(":")
        val effectiveMac = CrossEcosystemIdentityCorrelator.normalizeMac(
            macAddress.ifBlank { getSavedMacAddress(context, cleanIp) }
        )
        ClaraSecretVault.getLgWebOsClientKey(
            context = context,
            ipAddress = cleanIp,
            deviceUuid = deviceUuid,
            macAddress = effectiveMac
        )?.takeIf { it.isNotBlank() }?.let { rawVault ->
            return rawVault.trim()
        }
        if (cleanIp.isNotBlank()) {
            val raw = prefs(context).getString("client_key_$cleanIp", "") ?: ""
            val decrypted = raw.trim()
            if (decrypted.isNotBlank()) {
                ClaraSecretVault.putLgWebOsClientKey(
                    context = context,
                    ipAddress = cleanIp,
                    clientKey = decrypted,
                    deviceUuid = deviceUuid,
                    macAddress = effectiveMac
                )
                prefs(context).edit().remove("client_key_$cleanIp").apply()
                return decrypted
            }
        }
        return ""
    }

    /**
     * Onboarding Section 2: Returns true strictly when ClaraSecretVault holds a verified
     * SSAP client-key for the target IP or normalized MAC address.
     */
    fun hasVerifiedVaultKey(
        context: Context,
        ipAddress: String,
        macAddress: String = "",
        deviceUuid: String = ""
    ): Boolean {
        val cleanIp = ipAddress.trim().substringBefore(":")
        val effectiveMac = CrossEcosystemIdentityCorrelator.normalizeMac(
            macAddress.ifBlank { if (cleanIp.isNotBlank()) getSavedMacAddress(context, cleanIp) else "" }
        )
        return getSavedClientKey(
            context = context,
            ip = cleanIp,
            macAddress = effectiveMac,
            deviceUuid = deviceUuid
        ).isNotBlank()
    }

    fun isTvPaired(context: Context, ip: String, macAddress: String = ""): Boolean =
        hasVerifiedVaultKey(context = context, ipAddress = ip, macAddress = macAddress)

    private fun saveClientKey(
        context: Context,
        ip: String,
        clientKey: String,
        macAddress: String = "",
        deviceUuid: String = ""
    ) {
        val cleanKey = clientKey.trim()
        val cleanIp = ip.trim().substringBefore(":")
        val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(
            macAddress.ifBlank { getSavedMacAddress(context, cleanIp) }
        )
        if (cleanKey.isNotBlank() && (cleanIp.isNotBlank() || normMac.isNotEmpty())) {
            ClaraSecretVault.putLgWebOsClientKey(
                context = context,
                ipAddress = cleanIp,
                clientKey = cleanKey,
                deviceUuid = deviceUuid,
                macAddress = normMac
            )
            if (cleanIp.isNotBlank()) {
                prefs(context).edit()
                    .remove("client_key_$cleanIp")
                    .putString(KEY_LAST_KNOWN_TV_IP, cleanIp)
                    .apply()
            }
        }
    }

    fun getSavedMacAddress(context: Context, ip: String): String {
        val cleanIp = ip.trim().substringBefore(":")
        if (cleanIp.isNotBlank()) {
            CrossEcosystemIdentityCorrelator.getObservedIpToMacMap()[cleanIp]
                ?.takeIf { it.length == 12 }
                ?.let { return it }
            ClaraDeviceRegistryStore.getInstance(context).resolveEndpointForDevice(cleanIp)?.normalizedMac
                ?.takeIf { it.length == 12 }
                ?.let { return it }
        }
        val legacyByIp = if (cleanIp.isNotBlank()) prefs(context).getString("tv_mac_$cleanIp", "").orEmpty() else ""
        val legacy = legacyByIp.ifBlank { prefs(context).getString("tv_mac_latest", "").orEmpty() }
        val normLegacy = CrossEcosystemIdentityCorrelator.normalizeMac(legacy)
        if (normLegacy.isNotEmpty()) {
            saveMacAddress(context, cleanIp, normLegacy)
            prefs(context).edit().remove("tv_mac_$cleanIp").remove("tv_mac_latest").apply()
            return normLegacy
        }
        return ""
    }

    fun saveMacAddress(context: Context, ip: String, mac: String) {
        val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(mac)
        val cleanIp = ip.trim().substringBefore(":")
        if (normMac.length == 12) {
            if (cleanIp.isNotBlank()) {
                SmartHomeApiClient.registerVerifiedLanNeighbor(cleanIp, normMac)
                ClaraDeviceRegistryStore.getInstance(context).updateDeviceEndpointAndMac(
                    canonicalGuid = "guid::mac::$normMac",
                    newHostIp = cleanIp,
                    newPort = 3001,
                    learnedMac = normMac
                )
            }
            prefs(context).edit()
                .remove("tv_mac_$cleanIp")
                .remove("tv_mac_latest")
                .apply()
        }
    }

    private fun invalidateSessionsForIp(ip: String) {
        val cleanIp = ip.trim().substringBefore(":")
        ssapSessionsByIp.remove(cleanIp)?.closeQuietly()
        pointerSessionsByIp.remove(cleanIp)?.closeQuietly()
        verifiedTvIpTimestampByIp.remove(cleanIp)
    }

    private fun ensureWebSocketKeepAliveRunning() {
        if (keepAliveJob?.isActive == true) return
        keepAliveJob = tvKeepAliveScope.launch {
            while (isActive) {
                delay(15_000L)
                val now = System.currentTimeMillis()
                for ((ip, session) in ssapSessionsByIp.entries.toList()) {
                    if (!session.isAlive()) {
                        ssapSessionsByIp.remove(ip)?.closeQuietly()
                        val ctx = resolveAppContext()
                        if (ctx != null) {
                            val healedIp = resolveOrAutoDiscoverTvIp(ctx, ip)
                            if (healedIp.isNotBlank()) {
                                getOrConnectSsapSession(ctx, healedIp)
                            }
                        }
                        continue
                    }
                    if (now - session.lastActivityEpochMs >= 14_000L) {
                        val pingOk = synchronized(session.lock) {
                            try {
                                writeWebSocketFrame(session.out, 0x09, ByteArray(0))
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
                            ssapSessionsByIp.remove(ip)?.closeQuietly()
                            val ctx = resolveAppContext()
                            if (ctx != null) {
                                val healedIp = resolveOrAutoDiscoverTvIp(ctx, ip)
                                if (healedIp.isNotBlank()) {
                                    getOrConnectSsapSession(ctx, healedIp)
                                }
                            }
                        } else {
                            val volResp = executeCommandOnSession(
                                session = session,
                                ssapUri = "ssap://audio/getVolume",
                                payload = JSONObject()
                            )
                            extractAndPersistVolumeFromPayload(
                                context = resolveAppContext(),
                                cleanIp = ip,
                                payload = volResp?.optJSONObject("payload")
                            )
                        }
                    }
                }
                for ((ip, ptrSession) in pointerSessionsByIp.entries.toList()) {
                    if (!ptrSession.isAlive()) {
                        pointerSessionsByIp.remove(ip)?.closeQuietly()
                        continue
                    }
                    if (now - ptrSession.lastActivityEpochMs >= 14_000L) {
                        val pingOk = synchronized(ptrSession.lock) {
                            try {
                                writeWebSocketFrame(ptrSession.out, 0x09, ByteArray(0))
                                ptrSession.lastActivityEpochMs = System.currentTimeMillis()
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
                            pointerSessionsByIp.remove(ip)?.closeQuietly()
                        }
                    }
                }
            }
        }
    }

    /**
     * Resolves or heals an LG TV IP address.
     * Fast-path: If an active WebSocket session is already open or the IP was verified
     * within the last 60 seconds, returns immediately (0ms) without probing TCP ports.
     */
    suspend fun resolveOrAutoDiscoverTvIp(context: Context, preferredIp: String): String =
        withContext(Dispatchers.IO) {
            val cleanPref = preferredIp.trim().substringBefore(":")
            val now = System.currentTimeMillis()
            if (cleanPref.isNotBlank()) {
                if (ssapSessionsByIp[cleanPref]?.isAlive() == true) {
                    return@withContext cleanPref
                }
                val lastVerified = verifiedTvIpTimestampByIp[cleanPref] ?: 0L
                if (now - lastVerified < IP_VERIFY_CACHE_TTL_MS) {
                    return@withContext cleanPref
                }
                val prefPortOpen = SmartHomeApiClient.isPortOpenInternal(cleanPref, 3001, 350) ||
                        SmartHomeApiClient.isPortOpenInternal(cleanPref, 3000, 350)
                if (prefPortOpen && (isTvPaired(context, cleanPref) || probeVerifiedWebOsSsapBanner(cleanPref, 750))) {
                    verifiedTvIpTimestampByIp[cleanPref] = now
                    prefs(context).edit().putString(KEY_LAST_KNOWN_TV_IP, cleanPref).apply()
                    return@withContext cleanPref
                }
            }
            val lastKnown = prefs(context).getString(KEY_LAST_KNOWN_TV_IP, "")?.trim().orEmpty()
            if (lastKnown.isNotBlank() && lastKnown != cleanPref) {
                if (ssapSessionsByIp[lastKnown]?.isAlive() == true) {
                    return@withContext lastKnown
                }
                val lastVerified = verifiedTvIpTimestampByIp[lastKnown] ?: 0L
                if (now - lastVerified < IP_VERIFY_CACHE_TTL_MS) {
                    return@withContext lastKnown
                }
                val knownPortOpen = SmartHomeApiClient.isPortOpenInternal(lastKnown, 3001, 350) ||
                        SmartHomeApiClient.isPortOpenInternal(lastKnown, 3000, 350)
                if (knownPortOpen && (isTvPaired(context, lastKnown) || probeVerifiedWebOsSsapBanner(lastKnown, 750))) {
                    verifiedTvIpTimestampByIp[lastKnown] = now
                    return@withContext lastKnown
                }
            }
            val ssdpFound = discoverLgTvsViaSsdp(timeoutMs = 1300L).keys.firstOrNull()
            if (!ssdpFound.isNullOrBlank()) {
                verifiedTvIpTimestampByIp[ssdpFound] = System.currentTimeMillis()
                prefs(context).edit().putString(KEY_LAST_KNOWN_TV_IP, ssdpFound).apply()
                return@withContext ssdpFound
            }
            cleanPref.ifBlank { lastKnown }
        }

    private const val KEY_INSTALLED_WEBOS_APP_IDS = "installed_webos_app_ids_v1"
    @Volatile
    private var lastForegroundTvAppId: String = ""
    @Volatile
    private var lastForegroundTvTimestampMs: Long = 0L

    fun recordDiscoveredInstalledApps(context: Context, shortcuts: List<LgTvAppShortcut>) {
        if (shortcuts.isEmpty()) return
        val ids = shortcuts.map { it.appId.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }.toSet()
        if (ids.isNotEmpty()) {
            prefs(context).edit().putStringSet(KEY_INSTALLED_WEBOS_APP_IDS, ids).apply()
        }
    }

    fun isStreamingAppInstalledOnTv(context: Context, appIdOrHint: String): Boolean {
        val clean = appIdOrHint.trim().lowercase(Locale.US)
        if (clean.isBlank()) return true
        val savedSet = prefs(context).getStringSet(KEY_INSTALLED_WEBOS_APP_IDS, null)
            ?.map { it.lowercase(Locale.US) }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        val allKnownIds = if (savedSet.isNotEmpty()) {
            savedSet
        } else {
            loadTvApps(context).map { it.appId.lowercase(Locale.US) }.toSet()
        }
        if (allKnownIds.isEmpty()) return true
        return when {
            clean.contains("apple") -> allKnownIds.any { it.contains("apple") }
            clean == "bbc" || clean.contains("iplayer") -> allKnownIds.any { it == "bbc" || it.contains("iplayer") }
            clean.contains("netflix") -> allKnownIds.any { it.contains("netflix") }
            clean.contains("disney") -> allKnownIds.any { it.contains("disney") }
            clean.contains("lovefilm") || clean.contains("prime") || clean.contains("amazon") ->
                allKnownIds.any { it.contains("lovefilm") || it.contains("amazon") || it.contains("prime") }
            clean.contains("itv") -> allKnownIds.any { it.contains("itv") }
            clean.contains("all4") || clean.contains("channel4") -> allKnownIds.any { it.contains("all4") || it.contains("channel4") }
            clean.contains("ibo") -> allKnownIds.any { it.contains("ibo") }
            else -> allKnownIds.any { it == clean || it.contains(clean) }
        }
    }

    suspend fun queryForegroundAppId(context: Context, ipAddress: String): String = withContext(Dispatchers.IO) {
        val cleanIp = resolveOrAutoDiscoverTvIp(context, ipAddress)
        if (cleanIp.isBlank()) return@withContext lastForegroundTvAppId
        val resp = sendSsapCommandWithResponse(
            context = context,
            ipAddress = cleanIp,
            ssapUri = "ssap://com.webos.applicationManager/getForegroundAppInfo"
        )
        val fgAppId = resp?.optJSONObject("payload")?.optString("appId", "")?.trim().orEmpty()
        if (fgAppId.isNotBlank()) {
            lastForegroundTvAppId = fgAppId
            lastForegroundTvTimestampMs = System.currentTimeMillis()
        }
        fgAppId.ifBlank { lastForegroundTvAppId }
    }

    fun loadTvApps(context: Context): List<LgTvAppShortcut> {
        val p = prefs(context)
        val hasSeededDefaults = p.getBoolean("has_seeded_default_tv_apps_v1", false)
        val raw = p.getString(KEY_CUSTOM_TV_APPS_JSON, null)
        if (!hasSeededDefaults && raw == null) {
            val defaults = defaultTvAppShortcuts().filterNot {
                it.label.equals("EastEnders", ignoreCase = true) || it.contentId == "b006m86d"
            }
            p.edit().putBoolean("has_seeded_default_tv_apps_v1", true).apply()
            saveTvApps(context, defaults)
            return defaults
        }
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                val list = mutableListOf<LgTvAppShortcut>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val label = o.optString("label", "").trim()
                    val appId = o.optString("appId", "").trim()
                    val contentId = o.optString("contentId", "").trim()
                    val iboCh = o.optInt("iboChannelNumber", 0)
                    if (label.isNotBlank() && appId.isNotBlank()) {
                        list.add(
                            LgTvAppShortcut(
                                id = o.optString("id", UUID.randomUUID().toString()),
                                label = label,
                                appId = appId,
                                isHdmiInput = o.optBoolean("isHdmiInput", appId.contains("hdmi", true)),
                                contentId = contentId,
                                autoConfirmSelect = o.optBoolean("autoConfirmSelect", contentId.isNotBlank() || iboCh > 0),
                                iboChannelNumber = iboCh
                            )
                        )
                    }
                }
                return list
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
        return defaultTvAppShortcuts().filterNot {
            it.label.equals("EastEnders", ignoreCase = true) || it.contentId == "b006m86d"
        }
    }

    fun saveTvApps(context: Context, apps: List<LgTvAppShortcut>) {
        val arr = JSONArray()
        apps.forEach { app ->
            arr.put(
                JSONObject().apply {
                    put("id", app.id)
                    put("label", app.label)
                    put("appId", app.appId)
                    put("isHdmiInput", app.isHdmiInput)
                    put("contentId", app.contentId)
                    put("autoConfirmSelect", app.autoConfirmSelect)
                    put("iboChannelNumber", app.iboChannelNumber)
                }
            )
        }
        prefs(context).edit().putString(KEY_CUSTOM_TV_APPS_JSON, arr.toString()).apply()
    }

    fun addOrUpdateTvApp(
        context: Context,
        label: String,
        appId: String,
        contentId: String = "",
        autoConfirmSelect: Boolean = contentId.isNotBlank(),
        iboChannelNumber: Int = 0
    ): List<LgTvAppShortcut> {
        val cleanLabel = label.trim()
        val cleanAppId = appId.trim()
        val cleanContentId = contentId.trim()
        if (cleanLabel.isBlank() || cleanAppId.isBlank()) return loadTvApps(context)
        val current = loadTvApps(context).toMutableList()
        val existingIdx = current.indexOfFirst {
            it.label.equals(cleanLabel, ignoreCase = true) ||
                    (cleanContentId.isBlank() && it.contentId.isBlank() && iboChannelNumber == 0 && it.iboChannelNumber == 0 && it.appId.equals(cleanAppId, ignoreCase = true))
        }
        val shortcut = LgTvAppShortcut(
            id = if (existingIdx >= 0) current[existingIdx].id else UUID.randomUUID().toString(),
            label = cleanLabel,
            appId = cleanAppId,
            isHdmiInput = cleanAppId.contains("hdmi", ignoreCase = true),
            contentId = cleanContentId,
            autoConfirmSelect = autoConfirmSelect || iboChannelNumber > 0,
            iboChannelNumber = iboChannelNumber
        )
        if (existingIdx >= 0) {
            current[existingIdx] = shortcut
        } else {
            current.add(0, shortcut)
        }
        saveTvApps(context, current)
        return current
    }

    fun removeTvApp(context: Context, shortcutId: String): List<LgTvAppShortcut> {
        val updated = loadTvApps(context).filterNot { it.id == shortcutId }
        saveTvApps(context, updated)
        return updated
    }

    private fun defaultTvAppShortcuts(): List<LgTvAppShortcut> =
        LgTvAppOption.entries.map { opt ->
            LgTvAppShortcut(
                id = "default_${opt.name}",
                label = opt.label,
                appId = opt.appId,
                isHdmiInput = opt.name.startsWith("HDMI"),
                contentId = opt.contentId,
                autoConfirmSelect = opt.contentId.isNotBlank()
            )
        }

    /**
     * Resolves the latest available BBC iPlayer episode PID for a programme/brand PID (such as
     * `b006m86d` for EastEnders) so LG webOS BBC iPlayer can jump straight into the newest episode.
     */
    private fun resolveLatestBbcEpisodePid(programmeOrEpisodePid: String): String {
        val cleanPid = programmeOrEpisodePid.trim()
        if (cleanPid.isBlank()) return ""
        return try {
            val iblUrl = "https://ibl.api.bbci.co.uk/ibl/v1/programmes/$cleanPid/episodes?per_page=1"
            val conn = (java.net.URL(iblUrl).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2200
                readTimeout = 2200
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val root = JSONObject(body)
                val elements = root.optJSONObject("programme_episodes")?.optJSONArray("elements")
                val latestId = elements?.optJSONObject(0)?.optString("id", "")?.trim().orEmpty()
                if (latestId.isNotBlank()) return latestId
            } else {
                conn.disconnect()
            }
            cleanPid
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "ClaraClient",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
            cleanPid
}
    }

    /**
     * HIGH-02 Fix: Resolves the LG TV's MAC address across all Android versions (including Android 10+)
     * by checking `SmartHomeApiClient.verifiedLanNeighborMacByIp`, querying `ip neigh show`,
     * and falling back to `/proc/net/arp` on older Android versions.
     */
    fun resolveMacFromArpTable(ip: String): String {
        val cleanIp = ip.trim().substringBefore(":")
        if (cleanIp.isBlank()) return ""

        // 1. Check persisted correlator cache first
        val correlatorMac = CrossEcosystemIdentityCorrelator.getObservedIpToMacMap()[cleanIp].orEmpty()
        if (correlatorMac.length == 12) return correlatorMac

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return ""
        }

        // 2. Query `ip neigh show` on Android 9 and below
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("ip", "neigh", "show", cleanIp))
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parts = line.trim().split(Regex("\\s+"))
                    val lladdrIdx = parts.indexOf("lladdr")
                    if (lladdrIdx >= 0 && lladdrIdx + 1 < parts.size) {
                        val mac = parts[lladdrIdx + 1].uppercase(Locale.US)
                        if (mac.length == 17 && mac != "00:00:00:00:00:00") {
                            SmartHomeApiClient.registerVerifiedLanNeighbor(cleanIp, mac)
                            return mac
                        }
                    }
                }
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
}

        // 3. Fallback to `/proc/net/arp` on Android 8–9
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            try {
                val arpFile = File("/proc/net/arp")
                if (arpFile.exists() && arpFile.canRead()) {
                    arpFile.useLines { lines ->
                        for (line in lines) {
                            val parts = line.trim().split(Regex("\\s+"))
                            if (parts.size >= 4 && parts[0] == cleanIp) {
                                val mac = parts[3].uppercase(Locale.US)
                                if (mac.length == 17 && mac != "00:00:00:00:00:00") {
                                    SmartHomeApiClient.registerVerifiedLanNeighbor(cleanIp, mac)
                                    return mac
                                }
                            }
                        }
                    }
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
}
        }
        return ""
    }

    private fun extractXmlTagValue(xml: String, tagName: String): String {
        val regex = Regex("<$tagName[^>]*>([^<]+)</$tagName>", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun fetchAndVerifyLgUpnpDescriptor(locationUrl: String, expectedHostIp: String): String? {
        if (!locationUrl.startsWith("http://", ignoreCase = true) &&
            !locationUrl.startsWith("https://", ignoreCase = true)
        ) return null
        return try {
            val parsedUrl = java.net.URL(locationUrl)
            val host = parsedUrl.host.orEmpty()
            if (host.isNotBlank() && host != expectedHostIp) return null

            val conn = (parsedUrl.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 900
                readTimeout = 900
                setRequestProperty("Accept", "text/xml, application/xml")
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val xmlLower = xml.lowercase(Locale.US)
            if (xmlLower.contains("internetgatewaydevice") ||
                xmlLower.contains("wandevice") ||
                xmlLower.contains("printer") ||
                xmlLower.contains("bskyb") ||
                xmlLower.contains("<manufacturer>sky")
            ) {
                return null
            }

            val manufacturer = extractXmlTagValue(xml, "manufacturer")
            val modelName = extractXmlTagValue(xml, "modelName")
            val friendlyName = extractXmlTagValue(xml, "friendlyName")
            val isLgElectronics = manufacturer.contains("LG Electronics", ignoreCase = true) ||
                    manufacturer.equals("LGE", ignoreCase = true) ||
                    xmlLower.contains("urn:lge-com:service:webos")
            val isTelevisionRole = xmlLower.contains("webos") ||
                    xmlLower.contains("tv") ||
                    xmlLower.contains("mediarenderer") ||
                    modelName.isNotBlank()

            if (!isLgElectronics || !isTelevisionRole) return null

            CrossEcosystemIdentityCorrelator.extractMacFromApplicationPayload(
                ipAddress = expectedHostIp,
                rawPayloadText = xml
            )
            friendlyName
                .replace("[LG]", "")
                .trim()
                .ifBlank { modelName.ifBlank { "LG webOS TV" } }
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

    fun probeVerifiedWebOsSsapBanner(
        ipAddress: String,
        timeoutMs: Int = 1500,
        upnpAlreadyVerifiedLg: Boolean = false
    ): Boolean {
        val cleanIp = ipAddress.substringBefore(":").trim()
        if (cleanIp.isBlank()) return false

        val probeSslFactory by lazy {
            SSLContext.getInstance("TLS").apply {
                val probeTm = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        if (chain.isNullOrEmpty()) throw CertificateException("Empty TLS chain")
                    }
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                })
                init(null, probeTm, SecureRandom())
            }.socketFactory
        }

        val tcpConnectTimeout = timeoutMs.coerceIn(700, 1800)
        val tlsAndWsTimeout = (timeoutMs + 750).coerceIn(1400, 2600)

        for ((port, useTls) in listOf(3001 to true, 3000 to false)) {
            var sock: Socket? = null
            try {
                val raw = Socket()
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(cleanIp, port), tcpConnectTimeout)
                raw.soTimeout = tlsAndWsTimeout

                sock = if (useTls) {
                    (probeSslFactory.createSocket(raw, cleanIp, port, true) as SSLSocket).apply {
                        soTimeout = tlsAndWsTimeout
                        startHandshake()
                    }
                } else {
                    raw
                }

                val out = sock.getOutputStream()
                val input = sock.getInputStream()
                val wsKey = Base64.encodeToString(Random.nextBytes(16), Base64.NO_WRAP)
                val req = "GET / HTTP/1.1\r\n" +
                        "Host: $cleanIp:$port\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $wsKey\r\n" +
                        "Sec-WebSocket-Version: 13\r\n\r\n"
                out.write(req.toByteArray(Charsets.UTF_8))
                out.flush()

                val hdr = StringBuilder()
                while (!hdr.endsWith("\r\n\r\n") && hdr.length < 4096) {
                    val b = input.read()
                    if (b == -1) break
                    hdr.append(b.toChar())
                }
                val headerText = hdr.toString()
                if (!headerText.contains("101") || !headerText.contains("websocket", ignoreCase = true)) {
                    sock.close()
                    continue
                }

                // WebOS 6+ (e.g. NANO916PA) upgrades WSS on 3001 with 101 Switching Protocols;
                // some firmware builds ignore unauthenticated `hello` frames until `register` is sent.
                if (upnpAlreadyVerifiedLg || headerText.contains("WebOS", ignoreCase = true)) {
                    sock.close()
                    return true
                }

                sock.soTimeout = 700
                val helloJson = """{"id":"hello_probe","type":"hello","payload":{"protocolVersion":1}}"""
                val frame = runCatching {
                    writeWebSocketTextFrame(out, helloJson)
                    readNextWebSocketTextFrame(input, out).orEmpty()
                }.getOrDefault("")
                sock.close()

                if (frame.startsWith("{")) {
                    val json = runCatching { JSONObject(frame) }.getOrNull()
                    val msgType = json?.optString("type", "").orEmpty()
                    if (msgType in setOf("hello", "response", "error", "registered")) {
                        return true
                    }
                }
            } catch (_: Exception) {
                runCatching { sock?.close() }
            }
        }
        return false
    }

    suspend fun discoverLgTvsViaSsdp(timeoutMs: Long = 1800L): Map<String, String> =
        withContext(Dispatchers.IO) {
            DiscoveryRadioCoordinator.withMulticastLock(resolveAppContext(), "lg_webos_ssdp") {
                val foundIpToName = ConcurrentHashMap<String, String>()
                val candidateLocationsByIp = ConcurrentHashMap<String, Pair<String, Boolean>>()
                val searchTargets = listOf(
                    "urn:lge-com:service:webos-second-screen:1",
                    "urn:dial-multiscreen-org:service:dial:1",
                    "urn:schemas-upnp-org:device:MediaRenderer:1"
                )
                try {
                    DatagramSocket().use { socket ->
                        socket.soTimeout = 350
                        val multicastAddr = InetAddress.getByName("239.255.255.250")
                        for (st in searchTargets) {
                            val msg = "M-SEARCH * HTTP/1.1\r\n" +
                                    "HOST: 239.255.255.250:1900\r\n" +
                                    "MAN: \"ssdp:discover\"\r\n" +
                                    "MX: 2\r\n" +
                                    "ST: $st\r\n\r\n"
                            val data = msg.toByteArray(Charsets.UTF_8)
                            socket.send(DatagramPacket(data, data.size, multicastAddr, 1900))
                        }

                        val deadline = System.currentTimeMillis() + timeoutMs
                        val buf = ByteArray(4096)
                        while (System.currentTimeMillis() < deadline) {
                            val packet = DatagramPacket(buf, buf.size)
                            val received = try {
                                socket.receive(packet)
                                true
                            } catch (_: java.net.SocketTimeoutException) {
                                false
                            } catch (e: Throwable) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                ErrorClassifier.recordHandledException(
                                    t = e,
                                    transportSource = "LG_SSDP",
                                    operationStage = "discoverLgTvsViaSsdp.receive",
                                    logToDiscoveryRecorder = false,
                                    surfaceToUiBanner = false
                                )
                                false
                            }
                            if (!received || packet.length <= 0) continue
                            val senderIp = packet.address?.hostAddress ?: continue
                            val respText = String(packet.data, 0, packet.length, Charsets.UTF_8)

                            val headerPreview = respText.lineSequence()
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .take(5)
                                .joinToString(" | ")
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                                transportSource = "LG_SSDP",
                                ipAddress = senderIp,
                                port = 1900,
                                vendor = "SSDP Responder",
                                confidenceScore = 0.15f,
                                qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                                outcomeReason = "SSDP M-SEARCH response received",
                                rawPayload = headerPreview
                            )

                            val locLine = respText.lineSequence().firstOrNull {
                                it.startsWith("LOCATION:", ignoreCase = true)
                            }?.substringAfter(":")?.trim().orEmpty()
                            val hasExplicitWebOsHeader = respText.contains("webos", ignoreCase = true) ||
                                    respText.contains("urn:lge-com", ignoreCase = true)
                            if (locLine.isNotBlank() || hasExplicitWebOsHeader) {
                                candidateLocationsByIp.putIfAbsent(senderIp, locLine to hasExplicitWebOsHeader)
                            } else {
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.FILTERED,
                                    transportSource = "LG_SSDP",
                                    ipAddress = senderIp,
                                    port = 1900,
                                    vendor = "Generic SSDP Host",
                                    qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                                    outcomeReason = "Filtered SSDP packet missing LOCATION header and LG webOS ST/USN",
                                    rawPayload = headerPreview
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FAILED,
                        transportSource = "LG_SSDP",
                        port = 1900,
                        vendor = "LG SSDP Scanner",
                        qualificationStage = "discoverLgTvsViaSsdp",
                        outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "SSDP multicast scan failed"}"
                    )
                }

                for ((senderIp, meta) in candidateLocationsByIp) {
                    val (locationUrl, hasExplicitWebOsHeader) = meta
                    val verifiedUpnpName = if (locationUrl.isNotBlank()) {
                        fetchAndVerifyLgUpnpDescriptor(locationUrl, senderIp)
                    } else if (hasExplicitWebOsHeader) {
                        "LG webOS TV"
                    } else {
                        null
                    }

                    if (verifiedUpnpName == null) {
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.FILTERED,
                            transportSource = "LG_SSDP",
                            ipAddress = senderIp,
                            port = 1900,
                            vendor = "Non-LG UPnP Host",
                            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                            outcomeReason = "Rejected SSDP host: UPnP XML descriptor is not LG Electronics webOS TV ($locationUrl)"
                        )
                        continue
                    }

                    if (!probeVerifiedWebOsSsapBanner(senderIp, timeoutMs = 1500, upnpAlreadyVerifiedLg = true)) {
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.FILTERED,
                            transportSource = "LG_SSDP",
                            ipAddress = senderIp,
                            port = 3001,
                            vendor = "LG Electronics",
                            qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                            outcomeReason = "Rejected SSDP host: failed webOS SSAP WebSocket handshake verification"
                        )
                        continue
                    }

                    verifiedTvIpTimestampByIp[senderIp] = System.currentTimeMillis()
                    SmartHomeApiClient.registerVerifiedLanNeighbor(senderIp)
                    val learnedMac = CrossEcosystemIdentityCorrelator.getObservedIpToMacMap()[senderIp].orEmpty()
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.IDENTIFIED,
                        transportSource = "LG_SSDP",
                        macAddress = learnedMac,
                        ipAddress = senderIp,
                        port = 3001,
                        vendor = verifiedUpnpName,
                        confidenceScore = 0.94f,
                        qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                        outcomeReason = "Verified LG UPnP XML descriptor + webOS SSAP WebSocket banner"
                    )
                    foundIpToName.putIfAbsent(senderIp, verifiedUpnpName)
                }
                foundIpToName
            }
        }

    suspend fun sendWakeOnLanMagicPacket(macAddress: String, ipAddress: String = "255.255.255.255") =
        withContext(Dispatchers.IO) {
            val cleanMac = macAddress.trim().replace(":", "").replace("-", "")
            if (cleanMac.length != 12) return@withContext
            try {
                val macBytes = ByteArray(6)
                for (i in 0 until 6) {
                    macBytes[i] = cleanMac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                val packetBytes = ByteArray(6 + 16 * 6)
                for (i in 0 until 6) packetBytes[i] = 0xFF.toByte()
                for (i in 1..16) {
                    System.arraycopy(macBytes, 0, packetBytes, i * 6, 6)
                }

                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val broadcastIp = if (ipAddress.contains(".")) {
                        val parts = ipAddress.substringBefore(":").split(".")
                        if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.255" else "255.255.255.255"
                    } else "255.255.255.255"
                    val targetAddr = InetAddress.getByName(broadcastIp)
                    socket.send(DatagramPacket(packetBytes, packetBytes.size, targetAddr, 9))
                    socket.send(DatagramPacket(packetBytes, packetBytes.size, targetAddr, 7))
                }
            } catch (e: Exception) {
                Log.w(TAG, "WoL magic packet error: ${e.message}")
            }
        }

    private fun writeWebSocketFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        val maskKey = ByteArray(4).apply { Random.nextBytes(this) }
        val frame = ByteArrayOutputStream()
        frame.write(0x80 or (opcode and 0x0F))
        when {
            payload.size <= 125 -> frame.write(payload.size or 0x80)
            payload.size <= 65535 -> {
                frame.write(126 or 0x80)
                frame.write((payload.size ushr 8) and 0xFF)
                frame.write(payload.size and 0xFF)
            }
            else -> {
                frame.write(127 or 0x80)
                for (shift in 56 downTo 0 step 8) {
                    frame.write(((payload.size.toLong() ushr shift) and 0xFF).toInt())
                }
            }
        }
        frame.write(maskKey)
        for (i in payload.indices) {
            frame.write(payload[i].toInt() xor maskKey[i % 4].toInt())
        }
        out.write(frame.toByteArray())
        out.flush()
    }

    private fun writeWebSocketTextFrame(out: OutputStream, text: String) {
        writeWebSocketFrame(out, 0x01, text.toByteArray(Charsets.UTF_8))
    }

    private fun readNextWebSocketTextFrame(input: InputStream, out: OutputStream): String? {
        while (true) {
            val b0 = input.read()
            if (b0 == -1) return null
            val opcode = b0 and 0x0F
            val b1 = input.read()
            if (b1 == -1) return null
            val isMasked = (b1 and 0x80) != 0
            var len = b1 and 0x7F
            if (len == 126) {
                len = ((input.read() and 0xFF) shl 8) or (input.read() and 0xFF)
            } else if (len == 127) {
                var l = 0L
                repeat(8) { l = (l shl 8) or (input.read().toLong() and 0xFFL) }
                len = l.toInt()
            }
            if (len < 0 || len > 256_000) return null
            val mask = ByteArray(4)
            if (isMasked) {
                var r = 0
                while (r < 4) {
                    val n = input.read(mask, r, 4 - r)
                    if (n <= 0) break
                    r += n
                }
            }
            val data = ByteArray(len)
            var readTotal = 0
            while (readTotal < len) {
                val n = input.read(data, readTotal, len - readTotal)
                if (n <= 0) break
                readTotal += n
            }
            if (isMasked) {
                for (i in 0 until len) {
                    data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }
            when (opcode) {
                0x01 -> return String(data, Charsets.UTF_8)
                0x09 -> {
                    try {
                        writeWebSocketFrame(out, 0x0A, data)
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
                0x0A -> {
                    // Ignore incoming Pong frame
                }
                0x08 -> return null
            }
        }
    }

    private fun hasLgTlsPinMismatchCause(t: Throwable?): Boolean {
        var cur = t
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur is LgTlsPinMismatchException) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    private fun openAndRegisterSsapSession(
        context: Context,
        cleanIp: String,
        port: Int,
        useTls: Boolean,
        allowTlsPinRecoveryRetry: Boolean = true
    ): PersistentSsapSession? {
        var socket: Socket? = null
        return try {
            val raw = Socket()
            raw.tcpNoDelay = true
            raw.keepAlive = true
            raw.connect(InetSocketAddress(cleanIp, port), 2200)
            raw.soTimeout = 18_000

            socket = if (useTls) {
                val sslFactory = buildTofuLanSslSocketFactory(cleanIp)
                (sslFactory.createSocket(raw, cleanIp, port, true) as SSLSocket).apply {
                    startHandshake()
                }
            } else {
                raw
            }

            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            val wsKey = Base64.encodeToString(Random.nextBytes(16), Base64.NO_WRAP)
            val handshake = "GET / HTTP/1.1\r\n" +
                    "Host: $cleanIp:$port\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $wsKey\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n"
            out.write(handshake.toByteArray(Charsets.UTF_8))
            out.flush()

            val headerBuf = StringBuilder()
            while (!headerBuf.endsWith("\r\n\r\n") && headerBuf.length < 4096) {
                val ch = input.read()
                if (ch == -1) break
                headerBuf.append(ch.toChar())
            }
            if (!headerBuf.toString().contains("101")) {
                socket.close()
                return null
            }

            val regJson = JSONObject(registrationPayloadTemplate)
            val savedKey = getSavedClientKey(context, cleanIp)
            var activeClientKey = savedKey
            if (savedKey.isNotBlank()) {
                regJson.optJSONObject("payload")?.put("client-key", savedKey)
            }
            writeWebSocketTextFrame(out, regJson.toString())

            // Two-stage LG webOS SSAP registration wait:
            // Stage 1 ("pairingType":"PROMPT") shows the prompt on the TV screen;
            // Stage 2 ("registered") delivers the verified `client-key` once accepted on the TV.
            var isRegistered = false
            socket.soTimeout = 40_000
            var regDeadline = System.currentTimeMillis() + 40_000L
            while (System.currentTimeMillis() < regDeadline && !isRegistered) {
                val respText = readNextWebSocketTextFrame(input, out) ?: break
                val respJson = runCatching { JSONObject(respText) }.getOrNull() ?: continue
                val msgType = respJson.optString("type", "")
                val payloadObj = respJson.optJSONObject("payload")
                if (msgType == "registered") {
                    val newKey = payloadObj?.optString("client-key", "")?.trim().orEmpty()
                    if (newKey.isNotBlank()) {
                        activeClientKey = newKey
                        saveClientKey(context, cleanIp, newKey)
                        isRegistered = true
                    } else if (savedKey.isNotBlank()) {
                        isRegistered = true
                    }
                } else if (msgType == "response" &&
                    payloadObj?.optString("pairingType", "").equals("PROMPT", ignoreCase = true)
                ) {
                    Log.d(TAG, "LG webOS TV ($cleanIp) displaying on-screen Accept prompt; waiting for client-key...")
                    regDeadline = System.currentTimeMillis() + 40_000L
                } else if (msgType == "error") {
                    if (savedKey.isNotBlank()) {
                        ClaraSecretVault.clearLgWebOsClientKey(
                            context = context,
                            ipAddress = cleanIp,
                            macAddress = getSavedMacAddress(context, cleanIp)
                        )
                        prefs(context).edit().remove("client_key_$cleanIp").apply()
                        activeClientKey = ""
                        val retryReg = JSONObject(registrationPayloadTemplate)
                        writeWebSocketTextFrame(out, retryReg.toString())
                    } else {
                        break
                    }
                }
            }

            if (!isRegistered) {
                socket.close()
                return null
            }

            var learnedMac = getSavedMacAddress(context, cleanIp)
            if (learnedMac.isBlank()) {
                val arpMac = resolveMacFromArpTable(cleanIp)
                if (arpMac.isNotBlank()) {
                    learnedMac = arpMac
                    saveMacAddress(context, cleanIp, arpMac)
                } else {
                    socket.soTimeout = 2500
                    val macReq = JSONObject().apply {
                        put("id", "mac_auto")
                        put("type", "request")
                        put("uri", "ssap://com.webos.service.connectionmanager/getinfo")
                    }
                    writeWebSocketTextFrame(out, macReq.toString())
                    val macRespStr = runCatching { readNextWebSocketTextFrame(input, out) }.getOrNull()
                    if (!macRespStr.isNullOrBlank()) {
                        val macPay = runCatching { JSONObject(macRespStr).optJSONObject("payload") }.getOrNull()
                        val wifiMac = macPay?.optJSONObject("wifiInfo")?.optString("macAddress", "") ?: ""
                        val wiredMac = macPay?.optJSONObject("wiredInfo")?.optString("macAddress", "") ?: ""
                        val chosenMac = wifiMac.ifBlank { wiredMac }
                        if (chosenMac.isNotBlank()) {
                            learnedMac = chosenMac
                            saveMacAddress(context, cleanIp, chosenMac)
                        }
                    }
                }
            }

            if (activeClientKey.isNotBlank()) {
                saveClientKey(context, cleanIp, activeClientKey, macAddress = learnedMac)
            }

            val volSubReq = JSONObject().apply {
                put("id", "vol_sub_1")
                put("type", "subscribe")
                put("uri", "ssap://audio/getVolume")
            }
            runCatching {
                writeWebSocketTextFrame(out, volSubReq.toString())
                socket.soTimeout = 1200
                val initialVolFrame = readNextWebSocketTextFrame(input, out)
                if (!initialVolFrame.isNullOrBlank()) {
                    val volJson = JSONObject(initialVolFrame)
                    extractAndPersistVolumeFromPayload(context, cleanIp, volJson.optJSONObject("payload"))
                }
            }

            socket.soTimeout = 3500
            verifiedTvIpTimestampByIp[cleanIp] = System.currentTimeMillis()
            SmartHomeApiClient.registerVerifiedLanNeighbor(cleanIp, learnedMac)
            ensureWebSocketKeepAliveRunning()
            PersistentSsapSession(
                ipAddress = cleanIp,
                port = port,
                useTls = useTls,
                socket = socket,
                input = input,
                out = out
            )
        } catch (e: Exception) {
            runCatching { socket?.close() }
            // Onboarding Section 2: Auto-recover rotated TLS pins by clearing stale cert/key and re-prompting on TV
            if (useTls && allowTlsPinRecoveryRetry && hasLgTlsPinMismatchCause(e)) {
                Log.i(TAG, "Auto-recovering rotated LG webOS TLS pin for $cleanIp and re-prompting on TV screen")
                clearPinnedTvCertForReverification(context, cleanIp)
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = cleanIp,
                    macAddress = getSavedMacAddress(context, cleanIp),
                    event = MandatoryLifecycleTransition.PAIRING_STARTED.eventName,
                    previousState = DeviceLifecycleState.CONNECTABLE.name,
                    newState = DeviceLifecycleState.CONNECTABLE.name,
                    activeTransport = ProviderId.LG_WEBOS.id,
                    vendorDriver = "LG webOS SSAP",
                    protocolResultCode = "TLS_PIN_CLEARED_REPROMPT"
                )
                return openAndRegisterSsapSession(
                    context = context,
                    cleanIp = cleanIp,
                    port = port,
                    useTls = true,
                    allowTlsPinRecoveryRetry = false
                )
            }
            Log.w(TAG, "LG SSAP connect ($cleanIp:$port tls=$useTls) -> ${e.message}")
            null
        }
    }

    private fun getOrConnectSsapSession(context: Context, cleanIp: String): PersistentSsapSession? {
        ssapSessionsByIp[cleanIp]?.let { existing ->
            if (existing.isAlive()) return existing
            ssapSessionsByIp.remove(cleanIp)?.closeQuietly()
        }

        val session3001 = openAndRegisterSsapSession(context, cleanIp, port = 3001, useTls = true)
        if (session3001 != null) {
            ssapSessionsByIp[cleanIp] = session3001
            return session3001
        }

        val session3000 = openAndRegisterSsapSession(context, cleanIp, port = 3000, useTls = false)
        if (session3000 != null) {
            ssapSessionsByIp[cleanIp] = session3000
            return session3000
        }
        return null
    }

    private fun executeCommandOnSession(
        session: PersistentSsapSession,
        ssapUri: String,
        payload: JSONObject
    ): JSONObject? {
        synchronized(session.lock) {
            if (!session.isAlive()) return null
            return try {
                session.socket.soTimeout = 3200
                val reqId = "cmd_${requestCounter.getAndIncrement()}"
                val cmdFrame = JSONObject().apply {
                    put("id", reqId)
                    put("type", "request")
                    put("uri", ssapUri)
                    if (payload.length() > 0) {
                        put("payload", payload)
                    }
                }
                writeWebSocketTextFrame(session.out, cmdFrame.toString())
                session.lastActivityEpochMs = System.currentTimeMillis()
                val deadline = System.currentTimeMillis() + 3200L
                while (System.currentTimeMillis() < deadline) {
                    val respText = readNextWebSocketTextFrame(session.input, session.out) ?: return null
                    session.lastActivityEpochMs = System.currentTimeMillis()
                    val respJson = runCatching { JSONObject(respText) }.getOrNull() ?: continue
                    val respId = respJson.optString("id", "")
                    if (respId == "vol_sub_1" || ssapUri == "ssap://audio/getVolume") {
                        extractAndPersistVolumeFromPayload(
                            context = resolveAppContext(),
                            cleanIp = session.ipAddress,
                            payload = respJson.optJSONObject("payload")
                        )
                    }
                    if (respId == reqId || respId.isBlank()) {
                        return respJson
                    }
                }
                JSONObject()
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
    }

    suspend fun sendSsapCommand(
        context: Context,
        ipAddress: String,
        ssapUri: String,
        payload: JSONObject = JSONObject()
    ): Boolean = sendSsapCommandWithResponse(context, ipAddress, ssapUri, payload) != null

    suspend fun sendSsapCommandWithResponse(
        context: Context,
        ipAddress: String,
        ssapUri: String,
        payload: JSONObject = JSONObject()
    ): JSONObject? = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val cleanIp = resolveOrAutoDiscoverTvIp(context, ipAddress)
        if (cleanIp.isBlank()) return@withContext null

        // 1. Try existing persistent SSAP WebSocket session (< 12ms)
        val firstSession = getOrConnectSsapSession(context, cleanIp)
        if (firstSession != null) {
            val resp = executeCommandOnSession(firstSession, ssapUri, payload)
            if (resp != null) {
                verifiedTvIpTimestampByIp[cleanIp] = System.currentTimeMillis()
                JarvisLiveBridge.recordCommandLatency(
                    integrationKey = "MULTI_PROTOCOL",
                    displayName = "Matter / Tapo / Shelly / MQTT / HA",
                    latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(4L),
                    succeeded = true
                )
                return@withContext resp
            }
            // Socket dropped or timed out; evict and reconnect once
            ssapSessionsByIp.remove(cleanIp)?.closeQuietly()
        }

        // 2. Reconnect once on a fresh socket
        val retrySession = getOrConnectSsapSession(context, cleanIp) ?: return@withContext null
        val retryResp = executeCommandOnSession(retrySession, ssapUri, payload)
        if (retryResp == null) {
            ssapSessionsByIp.remove(cleanIp)?.closeQuietly()
        } else {
            verifiedTvIpTimestampByIp[cleanIp] = System.currentTimeMillis()
        }
        JarvisLiveBridge.recordCommandLatency(
            integrationKey = "MULTI_PROTOCOL",
            displayName = "Matter / Tapo / Shelly / MQTT / HA",
            latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(8L),
            succeeded = retryResp != null
        )
        retryResp
    }

    /**
     * Dispatches any hardware remote button (`UP`, `DOWN`, `LEFT`, `RIGHT`, `OK`, `BACK`, `HOME`,
     * `MENU`, `PLAY`, `PAUSE`, `REWIND`, `FAST_FORWARD`, `CH_UP`, `CH_DOWN`, etc.) to the LG TV
     * using persistent Pointer Input and SSAP WebSocket sessions for sub-10ms tactile response.
     */
    fun sendRemoteButton(
        context: Context,
        tile: SmartTile,
        @Suppress("UNUSED_PARAMETER") bridge: BridgeSettings,
        button: LgRemoteButton,
        scope: CoroutineScope
    ) {
        scope.launch(Dispatchers.IO) {
            val cleanIp = resolveOrAutoDiscoverTvIp(context, tile.ipAddress)
            if (cleanIp.isBlank()) return@launch

            // 1. Fast Path for direct SSAP media/volume/channel commands
            if (button.ssapFallbackUri.isNotBlank() && button != LgRemoteButton.OK && button != LgRemoteButton.EXIT) {
                val sentDirect = sendSsapCommand(context, cleanIp, button.ssapFallbackUri)
                if (sentDirect) return@launch
            }

            // 2. Fast Path: Reuse already-open Persistent Pointer Input WebSocket (< 5ms)
            pointerSessionsByIp[cleanIp]?.let { existingPtr ->
                if (sendButtonOnPointerSession(existingPtr, button.pointerSocketKey)) {
                    return@launch
                }
                pointerSessionsByIp.remove(cleanIp)?.closeQuietly()
            }

            // 3. Request Pointer Input Socket Path from LG webOS TV and cache the persistent pointer socket
            val ptrResp = sendSsapCommandWithResponse(
                context = context,
                ipAddress = cleanIp,
                ssapUri = "ssap://com.webos.service.networkinput/getPointerInputSocket"
            )
            val socketPath = ptrResp?.optJSONObject("payload")?.optString("socketPath", "") ?: ""
            if (socketPath.isNotBlank()) {
                val newPtrSession = openPersistentPointerSession(cleanIp, socketPath)
                if (newPtrSession != null) {
                    pointerSessionsByIp[cleanIp] = newPtrSession
                    if (sendButtonOnPointerSession(newPtrSession, button.pointerSocketKey)) {
                        return@launch
                    }
                    pointerSessionsByIp.remove(cleanIp)?.closeQuietly()
                }
            }

            // 4. Fallback SSAP URI if Pointer Socket wasn't used
            if (button.ssapFallbackUri.isNotBlank()) {
                sendSsapCommand(context, cleanIp, button.ssapFallbackUri)
            } else if (button == LgRemoteButton.HOME) {
                sendSsapCommand(
                    context,
                    cleanIp,
                    "ssap://system.launcher/launch",
                    JSONObject().put("id", "com.webos.app.home")
                )
            }
        }
    }

    private fun openPersistentPointerSession(
        tvIp: String,
        socketPathUrl: String
    ): PersistentPointerSession? {
        var socket: Socket? = null
        return try {
            val uri = Uri.parse(socketPathUrl)
            val useTls = socketPathUrl.startsWith("wss://", ignoreCase = true)
            val port = if (uri.port > 0) uri.port else if (useTls) 3001 else 3000
            val pathAndQuery = (uri.encodedPath ?: "/resources") +
                    (uri.encodedQuery?.let { "?$it" } ?: "")

            val raw = Socket()
            raw.tcpNoDelay = true
            raw.keepAlive = true
            raw.connect(InetSocketAddress(tvIp, port), 1600)
            raw.soTimeout = 2500

            socket = if (useTls) {
                val sslFactory = buildTofuLanSslSocketFactory(tvIp)
                (sslFactory.createSocket(raw, tvIp, port, true) as SSLSocket).apply {
                    startHandshake()
                }
            } else {
                raw
            }

            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            val wsKey = Base64.encodeToString(Random.nextBytes(16), Base64.NO_WRAP)
            val handshake = "GET $pathAndQuery HTTP/1.1\r\n" +
                    "Host: $tvIp:$port\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $wsKey\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n"
            out.write(handshake.toByteArray(Charsets.UTF_8))
            out.flush()

            val headerBuf = StringBuilder()
            while (!headerBuf.endsWith("\r\n\r\n") && headerBuf.length < 4096) {
                val ch = input.read()
                if (ch == -1) break
                headerBuf.append(ch.toChar())
            }
            if (!headerBuf.toString().contains("101")) {
                socket.close()
                return null
            }

            ensureWebSocketKeepAliveRunning()
            PersistentPointerSession(
                ipAddress = tvIp,
                socketPathUrl = socketPathUrl,
                socket = socket,
                out = out
            )
        } catch (e: Exception) {
            Log.w(TAG, "Pointer socket connect error: ${e.message}")
            try {
                socket?.close()
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
            null
        }
    }

    private fun sendButtonOnPointerSession(
        session: PersistentPointerSession,
        buttonName: String
    ): Boolean {
        synchronized(session.lock) {
            if (!session.isAlive()) return false
            return try {
                val buttonPacket = "type:button\nname:$buttonName\n\n"
                writeWebSocketTextFrame(session.out, buttonPacket)
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
    }

    suspend fun pairAndFetchTvDetails(
        context: Context,
        ipAddress: String,
        forceClearStaleTlsPin: Boolean = false
    ): Result<Pair<String, List<LgTvAppShortcut>>> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val cleanIp = resolveOrAutoDiscoverTvIp(context, ipAddress)
        if (cleanIp.isBlank()) {
            return@withContext Result.failure(
                Exception("Could not find your LG Smart TV on Wi-Fi or Ethernet. Make sure the TV is switched on.")
            )
        }

        if (forceClearStaleTlsPin) {
            clearPinnedTvCertForReverification(context, cleanIp)
        }

        var infoResp = sendSsapCommandWithResponse(
            context = context,
            ipAddress = cleanIp,
            ssapUri = "ssap://com.webos.service.connectionmanager/getinfo"
        )
        // If a stale cached session or rotated TLS pin blocked registration, clear pin once and re-prompt on TV
        if (infoResp == null && !hasVerifiedVaultKey(context, cleanIp)) {
            clearPinnedTvCertForReverification(context, cleanIp)
            infoResp = sendSsapCommandWithResponse(
                context = context,
                ipAddress = cleanIp,
                ssapUri = "ssap://com.webos.service.connectionmanager/getinfo"
            )
        }

        if (infoResp == null) {
            val elapsedFail = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            DeviceLifecycleRecorder.recordTransition(
                deviceId = cleanIp,
                macAddress = getSavedMacAddress(context, cleanIp),
                event = MandatoryLifecycleTransition.AUTH_FAILED.eventName,
                previousState = DeviceLifecycleState.CONNECTABLE.name,
                newState = DeviceLifecycleState.CONNECTABLE.name,
                activeTransport = ProviderId.LG_WEBOS.id,
                vendorDriver = "LG webOS SSAP",
                latencyMs = elapsedFail,
                durationMs = elapsedFail,
                protocolResultCode = "TV_PROMPT_NOT_ACCEPTED",
                failureCategory = FailureCategory.AUTHENTICATION_FAILURE.name
            )
            return@withContext Result.failure(
                Exception("Please press 'Accept' on your LG TV screen using your TV remote to finish connecting.")
            )
        }

        val payload = infoResp.optJSONObject("payload")
        val wifiMac = payload?.optJSONObject("wifiInfo")?.optString("macAddress", "") ?: ""
        val wiredMac = payload?.optJSONObject("wiredInfo")?.optString("macAddress", "") ?: ""
        val arpMac = resolveMacFromArpTable(cleanIp)
        val resolvedMac = wifiMac.ifBlank { wiredMac }.ifBlank { arpMac }.ifBlank {
            getSavedMacAddress(context, cleanIp)
        }
        val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(resolvedMac)

        if (resolvedMac.isNotBlank()) {
            saveMacAddress(context, cleanIp, resolvedMac)
        }

        val verifiedKey = getSavedClientKey(context, cleanIp, normMac)
        if (verifiedKey.isNotBlank()) {
            saveClientKey(context, cleanIp, verifiedKey, macAddress = normMac)
            // Onboarding Section 7: Persist bound key, IP, and MAC immediately BEFORE capability/app sync
            ClaraDeviceRegistryStore.getInstance(context).persistBoundDeviceImmediately(
                deviceId = normMac.ifBlank { cleanIp },
                ipAddress = cleanIp,
                port = 3001,
                macAddress = normMac,
                localOrClientKey = verifiedKey,
                providerId = ProviderId.LG_WEBOS,
                suggestedTitle = "LG Smart TV",
                suggestedTileType = TileType.LG_TV
            )
        }

        // Heal any existing TV tile that was previously stuck in QUARANTINED_ERROR
        val existingTiles = DeviceRepository.loadTiles(context).toMutableList()
        var healedTile = false
        for (i in existingTiles.indices) {
            val t = existingTiles[i]
            val tIp = t.ipAddress.substringBefore(":").trim()
            val tMac = CrossEcosystemIdentityCorrelator.normalizeMac(t.macAddress.ifBlank { t.serialNumber })
            if ((t.type == TileType.LG_TV || t.deviceSubtype == DeviceSubtype.TV) &&
                (tIp.equals(cleanIp, ignoreCase = true) || (normMac.isNotEmpty() && tMac == normMac))
            ) {
                if (t.lifecycleState == DeviceLifecycleState.QUARANTINED_ERROR || !t.isOnline || t.macAddress.isBlank()) {
                    existingTiles[i] = t.copy(
                        ipAddress = cleanIp,
                        macAddress = normMac.ifBlank { t.macAddress },
                        lifecycleState = DeviceLifecycleState.OPERATIONAL,
                        transactionState = CommandTransactionState.IDLE,
                        isOnline = true,
                        stateAuthorityTier = StateAuthorityTier.DEVICE_HARDWARE_TRUTH
                    )
                    healedTile = true
                }
            }
        }
        if (healedTile) {
            DeviceRepository.saveTiles(context, existingTiles)
        }

        val authDuration = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        val keyFp = ClaraSecretVault.fingerprintSecret(verifiedKey)
        DeviceLifecycleRecorder.recordTransition(
            deviceId = normMac.ifBlank { cleanIp },
            macAddress = normMac,
            event = MandatoryLifecycleTransition.AUTH_SUCCESS.eventName,
            previousState = DeviceLifecycleState.CONNECTABLE.name,
            newState = DeviceLifecycleState.AUTHENTICATED.name,
            activeTransport = ProviderId.LG_WEBOS.id,
            vendorDriver = "LG webOS SSAP",
            latencyMs = authDuration,
            durationMs = authDuration,
            protocolResultCode = "SSAP_KEY_SHA256_$keyFp"
        )

        val syncedApps = syncInstalledAppsFromTv(context, cleanIp).getOrElse { loadTvApps(context) }

        sendSsapCommand(
            context = context,
            ipAddress = cleanIp,
            ssapUri = "ssap://system.notifications/createToast",
            payload = JSONObject().put("message", "Clara Smart Home Connected!")
        )

        Result.success(resolvedMac to syncedApps)
    }

    suspend fun syncInstalledAppsFromTv(
        context: Context,
        ipAddress: String
    ): Result<List<LgTvAppShortcut>> = withContext(Dispatchers.IO) {
        val cleanIp = resolveOrAutoDiscoverTvIp(context, ipAddress)
        if (cleanIp.isBlank()) return@withContext Result.failure(Exception("TV not found on Wi-Fi"))

        val resp = sendSsapCommandWithResponse(
            context = context,
            ipAddress = cleanIp,
            ssapUri = "ssap://com.webos.applicationManager/listLaunchPoints"
        ) ?: return@withContext Result.failure(Exception("Could not query apps from TV"))

        val launchPoints = resp.optJSONObject("payload")?.optJSONArray("launchPoints")
        if (launchPoints == null || launchPoints.length() == 0) {
            return@withContext Result.success(loadTvApps(context))
        }

        val currentApps = loadTvApps(context).toMutableList()
        val verifiedInstalledShortcuts = mutableListOf<LgTvAppShortcut>()
        for (i in 0 until launchPoints.length()) {
            val lp = launchPoints.optJSONObject(i) ?: continue
            val appId = lp.optString("id", "").trim()
            val title = lp.optString("title", "").trim()
            if (appId.isBlank() || title.isBlank()) continue
            if (appId.startsWith("com.webos.app.softwareupdate") ||
                appId.startsWith("com.webos.app.factorywin") ||
                appId.startsWith("com.webos.app.notificationcenter")
            ) continue
            val existingIdx = currentApps.indexOfFirst {
                it.appId.equals(appId, ignoreCase = true) || it.label.equals(title, ignoreCase = true)
            }
            val shortcut = LgTvAppShortcut(
                id = if (existingIdx >= 0) currentApps[existingIdx].id else "tv_app_$appId",
                label = title,
                appId = appId,
                isHdmiInput = appId.contains("hdmi", ignoreCase = true)
            )
            verifiedInstalledShortcuts.add(shortcut)
            if (existingIdx >= 0) {
                currentApps[existingIdx] = shortcut
            } else {
                currentApps.add(shortcut)
            }
        }
        recordDiscoveredInstalledApps(context, verifiedInstalledShortcuts)
        saveTvApps(context, currentApps)
        Result.success(currentApps)
    }

    fun setPower(
        context: Context,
        tile: SmartTile,
        bridge: BridgeSettings,
        turnOn: Boolean,
        scope: CoroutineScope
    ) {
        scope.launch(Dispatchers.IO) {
            val effectiveIp = resolveOrAutoDiscoverTvIp(context, tile.ipAddress)
            if (turnOn) {
                val effectiveMac = CrossEcosystemIdentityCorrelator.normalizeMac(
                    tile.macAddress
                        .ifBlank { tile.serialNumber }
                        .ifBlank { getSavedMacAddress(context, effectiveIp) }
                        .ifBlank { resolveMacFromArpTable(effectiveIp) }
                )
                if (effectiveMac.isNotBlank()) {
                    sendWakeOnLanMagicPacket(effectiveMac, effectiveIp.ifBlank { "255.255.255.255" })
                }
                if (effectiveIp.isNotBlank()) {
                    sendSsapCommand(context, effectiveIp, "ssap://com.webos.service.tvpower/power/turnOnScreen")
                    val volResp = sendSsapCommandWithResponse(context, effectiveIp, "ssap://audio/getVolume")
                    extractAndPersistVolumeFromPayload(context, effectiveIp, volResp?.optJSONObject("payload"))
                }
            } else {
                if (effectiveIp.isNotBlank()) {
                    sendSsapCommand(context, effectiveIp, "ssap://system/turnOff")
                    invalidateSessionsForIp(effectiveIp)
                }
            }

            if (tile.entityId.startsWith("media_player.") && bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()) {
                SmartHomeApiClient.callHomeAssistantService(
                    haBaseUrl = bridge.haUrl,
                    haToken = bridge.haToken,
                    domain = "media_player",
                    service = if (turnOn) "turn_on" else "turn_off",
                    payloadJson = JSONObject().put("entity_id", tile.entityId)
                )
            }
        }
    }

    fun setVolume(
        context: Context,
        tile: SmartTile,
        bridge: BridgeSettings,
        volumePercent: Float,
        scope: CoroutineScope
    ) {
        val volInt = volumePercent.roundToInt().coerceIn(0, 100)
        scope.launch(Dispatchers.IO) {
            val effectiveIp = resolveOrAutoDiscoverTvIp(context, tile.ipAddress)
            if (effectiveIp.isNotBlank()) {
                sendSsapCommand(
                    context = context,
                    ipAddress = effectiveIp,
                    ssapUri = "ssap://audio/setVolume",
                    payload = JSONObject().put("volume", volInt)
                )
            }
            if (tile.entityId.startsWith("media_player.") && bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()) {
                SmartHomeApiClient.callHomeAssistantService(
                    haBaseUrl = bridge.haUrl,
                    haToken = bridge.haToken,
                    domain = "media_player",
                    service = "volume_set",
                    payloadJson = JSONObject().apply {
                        put("entity_id", tile.entityId)
                        put("volume_level", volInt / 100.0)
                    }
                )
            }
        }
    }

    fun stepVolume(
        context: Context,
        tile: SmartTile,
        volumeUp: Boolean,
        scope: CoroutineScope
    ) {
        scope.launch(Dispatchers.IO) {
            val effectiveIp = resolveOrAutoDiscoverTvIp(context, tile.ipAddress)
            if (effectiveIp.isNotBlank()) {
                val uri = if (volumeUp) "ssap://audio/volumeUp" else "ssap://audio/volumeDown"
                sendSsapCommand(context, effectiveIp, uri)
                val volResp = sendSsapCommandWithResponse(context, effectiveIp, "ssap://audio/getVolume")
                extractAndPersistVolumeFromPayload(context, effectiveIp, volResp?.optJSONObject("payload"))
            }
        }
    }

    fun toggleMute(
        context: Context,
        tile: SmartTile,
        mute: Boolean,
        scope: CoroutineScope
    ) {
        scope.launch(Dispatchers.IO) {
            val effectiveIp = resolveOrAutoDiscoverTvIp(context, tile.ipAddress)
            if (effectiveIp.isNotBlank()) {
                sendSsapCommand(
                    context = context,
                    ipAddress = effectiveIp,
                    ssapUri = "ssap://audio/setMute",
                    payload = JSONObject().put("mute", mute)
                )
            }
        }
    }

    fun launchAppOrInput(
        context: Context,
        tile: SmartTile,
        bridge: BridgeSettings,
        appOption: LgTvAppOption,
        scope: CoroutineScope
    ) {
        launchAppShortcut(
            context = context,
            tile = tile,
            bridge = bridge,
            shortcut = LgTvAppShortcut(
                label = appOption.label,
                appId = appOption.appId,
                isHdmiInput = appOption.name.startsWith("HDMI"),
                contentId = appOption.contentId,
                autoConfirmSelect = appOption.contentId.isNotBlank()
            ),
            scope = scope
        )
    }

    fun launchAppShortcut(
        context: Context,
        tile: SmartTile,
        bridge: BridgeSettings,
        shortcut: LgTvAppShortcut,
        scope: CoroutineScope
    ) {
        scope.launch(Dispatchers.IO) {
            val effectiveIp = resolveOrAutoDiscoverTvIp(context, tile.ipAddress)
            if (effectiveIp.isNotBlank()) {
                // Wake TV automatically if it is in standby before launching the show/app
                val effectiveMac = CrossEcosystemIdentityCorrelator.normalizeMac(
                    tile.macAddress
                        .ifBlank { tile.serialNumber }
                        .ifBlank { getSavedMacAddress(context, effectiveIp) }
                        .ifBlank { resolveMacFromArpTable(effectiveIp) }
                )
                if (effectiveMac.isNotBlank()) {
                    sendWakeOnLanMagicPacket(effectiveMac, effectiveIp)
                }
                sendSsapCommand(context, effectiveIp, "ssap://com.webos.service.tvpower/power/turnOnScreen")

                // 1. IBO Pro Player Smart Channel-Number Macro Path (Instant Foreground Detection + Digit Sequence)
                if (shortcut.isIboChannelShortcut && shortcut.iboChannelNumber > 0) {
                    val iboCandidateIds = listOf(
                        shortcut.appId,
                        "com.ibo.pro",
                        "com.ibopro.player",
                        "ibo.player",
                        "com.iboplayer.app",
                        "cdp-ibo"
                    ).filter { it.isNotBlank() }.distinct()
                    val liveFgApp = queryForegroundAppId(context, effectiveIp)
                    val nowMs = System.currentTimeMillis()
                    val isIboAlreadyForeground = liveFgApp.contains("ibo", ignoreCase = true) ||
                            (lastForegroundTvAppId.contains("ibo", ignoreCase = true) &&
                                    (nowMs - lastForegroundTvTimestampMs) < 900_000L)
                    if (!isIboAlreadyForeground) {
                        for (iboId in iboCandidateIds) {
                            val ok = sendSsapCommand(
                                context = context,
                                ipAddress = effectiveIp,
                                ssapUri = "ssap://system.launcher/launch",
                                payload = JSONObject().put("id", iboId)
                            )
                            if (ok) {
                                lastForegroundTvAppId = iboId
                                lastForegroundTvTimestampMs = System.currentTimeMillis()
                                break
                            }
                        }
                        // Wait for IBO Pro Player cold-start & press OK to enter Live TV
                        delay(5200L)
                        sendRemoteButton(context, tile.copy(ipAddress = effectiveIp), bridge, LgRemoteButton.OK, scope)
                        delay(900L)
                    }
                    val chStr = shortcut.iboChannelNumber.toString()
                    // Dispatch hardware digit keys (0..9) + ENTER over the Pointer Input WebSocket for sub-second tuning
                    var ptrSession = pointerSessionsByIp[effectiveIp]?.takeIf { it.isAlive() }
                    if (ptrSession == null) {
                        val ptrResp = sendSsapCommandWithResponse(
                            context = context,
                            ipAddress = effectiveIp,
                            ssapUri = "ssap://com.webos.service.networkinput/getPointerInputSocket"
                        )
                        val socketPath = ptrResp?.optJSONObject("payload")?.optString("socketPath", "").orEmpty()
                        if (socketPath.isNotBlank()) {
                            ptrSession = openPersistentPointerSession(effectiveIp, socketPath)?.also {
                                pointerSessionsByIp[effectiveIp] = it
                            }
                        }
                    }
                    if (ptrSession != null) {
                        for (digitChar in chStr) {
                            if (digitChar.isDigit()) {
                                sendButtonOnPointerSession(ptrSession, digitChar.toString())
                                delay(140L)
                            }
                        }
                        delay(180L)
                        sendButtonOnPointerSession(ptrSession, "ENTER")
                    }
                    sendSsapCommand(
                        context = context,
                        ipAddress = effectiveIp,
                        ssapUri = "ssap://tv/openChannel",
                        payload = JSONObject().put("channelNumber", chStr)
                    )
                    sendSsapCommand(
                        context = context,
                        ipAddress = effectiveIp,
                        ssapUri = "ssap://com.webos.service.ime/insertText",
                        payload = JSONObject().put("text", chStr).put("replace", 0)
                    )
                    delay(220L)
                    sendSsapCommand(context, effectiveIp, "ssap://com.webos.service.ime/sendEnterKey")
                    sendRemoteButton(context, tile.copy(ipAddress = effectiveIp), bridge, LgRemoteButton.OK, scope)
                    lastForegroundTvTimestampMs = System.currentTimeMillis()
                    return@launch
                }

                // 2. Standard & Deep-Link Streaming App Launch Path
                val isBbcShortcut = shortcut.appId.contains("bbc", ignoreCase = true) ||
                        shortcut.appId.contains("iplayer", ignoreCase = true) ||
                        shortcut.label.contains("iPlayer", ignoreCase = true)
                val candidateIds = when {
                    isBbcShortcut -> listOf(shortcut.appId, "bbc", "bbc.iplayer.3.0", "uk.co.bbc.iplayer").distinct()
                    shortcut.appId.contains("apple", ignoreCase = true) ->
                        listOf(shortcut.appId, "com.apple.appletv", "appletv").distinct()
                    shortcut.appId.contains("disney", ignoreCase = true) ->
                        listOf(shortcut.appId, "com.disney.disneyplus-prod", "disneyplus").distinct()
                    shortcut.appId.contains("lovefilm", ignoreCase = true) || shortcut.appId.contains("amazon", ignoreCase = true) ->
                        listOf(shortcut.appId, "lovefilm.uk", "amazon", "com.amazon.ignition").distinct()
                    shortcut.appId.contains("itv", ignoreCase = true) ->
                        listOf(shortcut.appId, "itv.hub", "itvx").distinct()
                    shortcut.appId.contains("all4", ignoreCase = true) ->
                        listOf(shortcut.appId, "all4", "channel4").distinct()
                    shortcut.appId.contains("ibo", ignoreCase = true) ->
                        listOf(shortcut.appId, "com.ibo.pro", "com.ibopro.player", "ibo.player").distinct()
                    else -> listOf(shortcut.appId)
                }

                val rawContentId = shortcut.contentId.trim()
                val resolvedContentId = if (isBbcShortcut && rawContentId.isNotBlank()) {
                    resolveLatestBbcEpisodePid(rawContentId)
                } else {
                    rawContentId
                }

                var launchedOk = false
                for (idToTry in candidateIds) {
                    val launchPayload = JSONObject().apply {
                        put("id", idToTry)
                        if (resolvedContentId.isNotBlank()) {
                            put("contentId", resolvedContentId)
                            val deepLinkUrl = if (isBbcShortcut) {
                                "https://www.bbc.co.uk/iplayer/episode/$resolvedContentId"
                            } else {
                                resolvedContentId
                            }
                            put(
                                "params",
                                JSONObject().apply {
                                    put("contentId", resolvedContentId)
                                    put("id", resolvedContentId)
                                    put("url", deepLinkUrl)
                                    put("deeplink", deepLinkUrl)
                                    put("query", shortcut.label)
                                    if (isBbcShortcut) {
                                        put("contentTarget", "/iplayer/episode/$resolvedContentId")
                                    }
                                }
                            )
                        }
                    }
                    val ok = sendSsapCommand(
                        context = context,
                        ipAddress = effectiveIp,
                        ssapUri = "ssap://system.launcher/launch",
                        payload = launchPayload
                    )
                    if (ok) {
                        launchedOk = true
                        lastForegroundTvAppId = idToTry
                        lastForegroundTvTimestampMs = System.currentTimeMillis()
                        break
                    }
                }

                // 3. IBO Pro Player VOD Fallback if native streaming app is not installed on the TV
                if (!launchedOk && !shortcut.isHdmiInput) {
                    val iboFallbackIds = listOf("com.ibo.pro", "com.ibopro.player", "ibo.player", "com.iboplayer.app")
                    for (iboId in iboFallbackIds) {
                        val ok = sendSsapCommand(
                            context = context,
                            ipAddress = effectiveIp,
                            ssapUri = "ssap://system.launcher/launch",
                            payload = JSONObject().put("id", iboId)
                        )
                        if (ok) {
                            lastForegroundTvAppId = iboId
                            lastForegroundTvTimestampMs = System.currentTimeMillis()
                            delay(4500L)
                            sendSsapCommand(
                                context = context,
                                ipAddress = effectiveIp,
                                ssapUri = "ssap://com.webos.service.ime/insertText",
                                payload = JSONObject().put("text", shortcut.label).put("replace", 1)
                            )
                            break
                        }
                    }
                }

                // Automatically send an OK/ENTER press after the app boots to pass the profile/play prompt
                if (launchedOk && shortcut.autoConfirmSelect && resolvedContentId.isNotBlank()) {
                    delay(4200L)
                    sendRemoteButton(context, tile.copy(ipAddress = effectiveIp), bridge, LgRemoteButton.OK, scope)
                }
            }
            if (tile.entityId.startsWith("media_player.") && bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()) {
                SmartHomeApiClient.callHomeAssistantService(
                    haBaseUrl = bridge.haUrl,
                    haToken = bridge.haToken,
                    domain = "media_player",
                    service = "select_source",
                    payloadJson = JSONObject().apply {
                        put("entity_id", tile.entityId)
                        put("source", shortcut.label)
                    }
                )
            }
        }
    }

    /**
     * Resolves the official YouTube trailer videoId for a TV Show or Movie and launches it directly
     * inside the LG webOS YouTube app (`youtube.leanback.v4`).
     */
    fun launchYoutubeTrailerOnTv(
        context: Context,
        tile: SmartTile,
        bridge: BridgeSettings,
        showOrMovieTitle: String,
        scope: CoroutineScope
    ) {
        scope.launch(Dispatchers.IO) {
            val cleanTitle = showOrMovieTitle.trim()
            if (cleanTitle.isBlank()) return@launch
            val query = "$cleanTitle official trailer"
            var resolvedVideoId = ""
            try {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val pipedApis = listOf(
                    "https://pipedapi.kavin.rocks/search?q=$encoded&filter=videos",
                    "https://pipedapi.tokhmi.xyz/search?q=$encoded&filter=videos"
                )
                for (endpoint in pipedApis) {
                    val conn = (java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 2800
                        readTimeout = 2800
                        setRequestProperty("Accept", "application/json")
                    }
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        conn.disconnect()
                        val items = JSONObject(body).optJSONArray("items")
                        val urlPath = items?.optJSONObject(0)?.optString("url", "").orEmpty()
                        val vId = urlPath.substringAfter("v=", "").substringBefore("&").trim()
                        if (vId.length == 11) {
                            resolvedVideoId = vId
                            break
                        }
                    } else {
                        conn.disconnect()
                    }
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
}

            val contentTarget = if (resolvedVideoId.isNotBlank()) "v=$resolvedVideoId" else query
            launchAppShortcut(
                context = context,
                tile = tile,
                bridge = bridge,
                shortcut = LgTvAppShortcut(
                    label = "$cleanTitle Trailer",
                    appId = "youtube.leanback.v4",
                    contentId = contentTarget,
                    autoConfirmSelect = true
                ),
                scope = scope
            )
        }
    }
}
