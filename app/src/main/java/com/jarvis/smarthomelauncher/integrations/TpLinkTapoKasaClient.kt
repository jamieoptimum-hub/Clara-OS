package com.jarvis.smarthomelauncher.integrations

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.Context
import android.media.AudioManager
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class DiscoveredTapoKasaDevice(
    val deviceId: String,
    val name: String,
    val model: String,
    val ipAddress: String,
    val macAddress: String = "",
    val isKasaProtocol: Boolean,
    val suggestedType: TileType,
    val isActive: Boolean = false,
    val isSocket2Active: Boolean = false,
    val multiGangCount: Int = 1,
    val socket1Name: String = "Socket 1",
    val socket2Name: String = "Socket 2",
    val brightnessPct: Float = 100f,
    val powerWatts: Float = 0f,
    val voltageVolts: Float = 0f,
    val currentMilliamps: Float = 0f,
    val totalEnergyKwh: Float = 0f,
    val requiresAuth: Boolean = false
) {
    val actionType: DiscoveredDeviceActionType
        get() = if (requiresAuth) {
            DiscoveredDeviceActionType.OAUTH_SIGN_IN
        } else {
            DiscoveredDeviceActionType.ADD_DIRECT
        }
}

// ===========================================================================
// 2. TP-LINK NATIVE ENGINE (TAPO P100/P105/P110/P210M/P300/L530 & KASA HS100/HS110/KP303/EP40)
//    - Kasa: Zero-login UDP broadcast & TCP port 9999 XOR cipher with live
//      Watts / Voltage / Current / kWh energy telemetry & single/dual socket detection.
//    - Tapo: TP-Link Cloud account sync + Local HTTP Port 80 KLAP v1/v2 &
//      AES-128-CBC Passthrough protocol with live P110 energy monitoring
//      and multi-gang `control_child` sub-socket actuation (HIGH-04 fix).
// ===========================================================================
object TpLinkTapoKasaClient {
    private const val TAG = "ClaraTpLink"
    private const val PREFS_TPLINK = "clara_tplink_tapo_kasa_prefs_v1"
    private const val KASA_PORT = 9999

    private val discoveredDevicesCache = ConcurrentHashMap<String, DiscoveredTapoKasaDevice>()
    private val klapSessionByIp = ConcurrentHashMap<String, KlapHandshakeSession>()
    private val kasaChildIdsByIp = ConcurrentHashMap<String, List<String>>()
    private val tapoChildIdsByIp = ConcurrentHashMap<String, List<String>>()
    private val missedPollCountByTileId = ConcurrentHashMap<String, Int>()

    fun clearSessions() {
        klapSessionByIp.clear()
    }

    private data class KlapHandshakeSession(
        val cookie: String,
        val key: ByteArray,
        val ivPrefix: ByteArray,
        val sigPrefix: ByteArray,
        val seq: AtomicInteger,
        val isV2: Boolean
    )

    private const val VAULT_TPLINK_PASSWORD_KEY = "${PREFS_TPLINK}::tplink_password"

    fun saveTpLinkCredentials(context: Context, email: String, password: String) {
        val appCtx = context.applicationContext
        val cleanEmail = email.trim()
        val cleanPass = password.trim()
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.saveTpLinkCredentials(appCtx, cleanEmail, cleanPass)
        if (cleanPass.isBlank()) {
            com.jarvis.smarthomelauncher.core.ClaraSecretVault.remove(appCtx, VAULT_TPLINK_PASSWORD_KEY)
        } else {
            com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(appCtx, VAULT_TPLINK_PASSWORD_KEY, cleanPass)
        }
        appCtx.getSharedPreferences(PREFS_TPLINK, Context.MODE_PRIVATE).edit()
            .remove("tplink_email")
            .remove("tplink_password")
            .apply()
        klapSessionByIp.clear()
    }

    fun getSavedTpLinkEmail(context: Context): String {
        val appCtx = context.applicationContext
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.getTpLinkUsername(appCtx)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return com.jarvis.smarthomelauncher.core.ClaraSecretVault
            .migrateStringKey(appCtx, PREFS_TPLINK, "tplink_email")
            .orEmpty()
    }

    fun getSavedTpLinkPassword(context: Context): String {
        val appCtx = context.applicationContext
        val vaultRaw = com.jarvis.smarthomelauncher.core.ClaraSecretVault
            .getString(appCtx, VAULT_TPLINK_PASSWORD_KEY).orEmpty()
        val prefs = appCtx.getSharedPreferences(PREFS_TPLINK, Context.MODE_PRIVATE)
        if (vaultRaw.isNotBlank()) {
            val unwrapped = vaultRaw.trim()
            if (prefs.contains("tplink_password")) {
                prefs.edit().remove("tplink_password").apply()
            }
            return unwrapped
        }
        val legacyRaw = prefs.getString("tplink_password", "").orEmpty()
        if (legacyRaw.isBlank()) return ""
        val migrated = legacyRaw.trim()
        if (migrated.isNotBlank()) {
            com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(appCtx, VAULT_TPLINK_PASSWORD_KEY, migrated)
            prefs.edit().remove("tplink_password").apply()
        }
        return migrated
    }

    fun hasSavedCredentials(context: Context): Boolean =
        getSavedTpLinkEmail(context).isNotBlank() && getSavedTpLinkPassword(context).isNotBlank()

    // -----------------------------------------------------------------------
    // 2A. TP-LINK KASA LOCAL XOR CIPHER (Port 9999 UDP & TCP)
    // -----------------------------------------------------------------------
    private fun encryptKasaXorPayload(plainText: String, includeLengthHeader: Boolean): ByteArray {
        val plainBytes = plainText.toByteArray(Charsets.UTF_8)
        val out = ByteBuffer.allocate(if (includeLengthHeader) plainBytes.size + 4 else plainBytes.size)
        if (includeLengthHeader) {
            out.putInt(plainBytes.size)
        }
        var key = 171 // 0xAB
        for (b in plainBytes) {
            val cipherByte = (b.toInt() and 0xFF) xor key
            key = cipherByte
            out.put(cipherByte.toByte())
        }
        return out.array()
    }

    private fun decryptKasaXorPayload(
        cipherBytes: ByteArray,
        offset: Int = 0,
        length: Int = cipherBytes.size - offset
    ): String {
        if (length <= 0) return ""
        val plain = ByteArray(length)
        var key = 171 // 0xAB
        for (i in 0 until length) {
            val c = cipherBytes[offset + i].toInt() and 0xFF
            val p = c xor key
            key = c
            plain[i] = p.toByte()
        }
        return String(plain, Charsets.UTF_8)
    }

    private fun sendKasaTcpQuery(ip: String, jsonCommand: String, timeoutMs: Int = 1200): JSONObject? {
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(ip.trim(), KASA_PORT), timeoutMs)
                val out = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())
                out.write(encryptKasaXorPayload(jsonCommand, includeLengthHeader = true))
                out.flush()
                val len = input.readInt()
                if (len <= 0 || len > 65536) return null
                val buf = ByteArray(len)
                input.readFully(buf)
                val decrypted = decryptKasaXorPayload(buf)
                JSONObject(decrypted)
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

    private fun parseKasaSysinfoResponse(ip: String, root: JSONObject): DiscoveredTapoKasaDevice? {
        val sysinfo = root.optJSONObject("system")?.optJSONObject("get_sysinfo") ?: return null
        val devId = sysinfo.optString("deviceId", "").ifBlank { sysinfo.optString("mic_mac", ip) }
        val alias = sysinfo.optString("alias", "").ifBlank { "TP-Link Kasa Plug" }
        val model = sysinfo.optString("model", "Kasa Smart Plug")
        val mac = sysinfo.optString("mac", sysinfo.optString("mic_mac", ""))
        val micType = sysinfo.optString("mic_type", sysinfo.optString("type", "")).lowercase(Locale.getDefault())
        if (ip.isNotBlank()) {
            SmartHomeApiClient.registerVerifiedLanNeighbor(ip, mac.ifBlank { devId })
        }

        val isBulb = micType.contains("smartbulb") || model.startsWith("KL", true) || model.startsWith("LB", true)
        val lightState = sysinfo.optJSONObject("light_state")
        val childrenArr = sysinfo.optJSONArray("children")

        // Strictly inspect the hardware `children` array to know if this Kasa plug has 1 or 2+ outlets
        val hasTwoOutlets = childrenArr != null && childrenArr.length() >= 2
        if (hasTwoOutlets) {
            val childIds = mutableListOf<String>()
            for (cIdx in 0 until childrenArr.length()) {
                val cId = childrenArr.optJSONObject(cIdx)?.optString("id", "").orEmpty()
                if (cId.isNotBlank()) {
                    childIds.add(if (cId.length <= 4 && devId.length > 6) "$devId$cId" else cId)
                }
            }
            if (childIds.size >= 2) {
                kasaChildIdsByIp[ip] = childIds
            }
        }
        val child1Alias = if (hasTwoOutlets) {
            childrenArr?.optJSONObject(0)?.optString("alias", "Socket 1").orEmpty().ifBlank { "Socket 1" }
        } else "Socket 1"
        val child2Alias = if (hasTwoOutlets) {
            childrenArr?.optJSONObject(1)?.optString("alias", "Socket 2").orEmpty().ifBlank { "Socket 2" }
        } else "Socket 2"

        val isActive = when {
            isBulb && lightState != null -> lightState.optInt("on_off", 0) == 1
            hasTwoOutlets -> (childrenArr?.optJSONObject(0)?.optInt("state", 0) ?: 0) == 1
            else -> sysinfo.optInt("relay_state", 0) == 1
        }
        val socket2Active = if (hasTwoOutlets) {
            (childrenArr?.optJSONObject(1)?.optInt("state", 0) ?: 0) == 1
        } else false

        val brightness = if (isBulb && lightState != null) {
            lightState.optInt("brightness", 100).toFloat().coerceIn(1f, 100f)
        } else 100f

        // Parse optional real-time emeter telemetry (HS110 / KP115 / EP25)
        val emeter = root.optJSONObject("emeter")?.optJSONObject("get_realtime")
            ?: root.optJSONObject("smartlife.iot.common.emeter")?.optJSONObject("get_realtime")
        var watts = 0f
        var volts = 0f
        var currentMa = 0f
        var totalKwh = 0f
        if (emeter != null) {
            watts = when {
                emeter.has("power_mw") -> emeter.optDouble("power_mw", 0.0).toFloat() / 1000f
                emeter.has("power") -> emeter.optDouble("power", 0.0).toFloat()
                else -> 0f
            }
            volts = when {
                emeter.has("voltage_mv") -> emeter.optDouble("voltage_mv", 0.0).toFloat() / 1000f
                emeter.has("voltage") -> emeter.optDouble("voltage", 0.0).toFloat()
                else -> 0f
            }
            currentMa = when {
                emeter.has("current_ma") -> emeter.optDouble("current_ma", 0.0).toFloat()
                emeter.has("current") -> emeter.optDouble("current", 0.0).toFloat() * 1000f
                else -> 0f
            }
            totalKwh = when {
                emeter.has("total_wh") -> emeter.optDouble("total_wh", 0.0).toFloat() / 1000f
                emeter.has("total") -> emeter.optDouble("total", 0.0).toFloat()
                else -> 0f
            }
        }

        val suggestedType = when {
            isBulb -> TileType.HUE_LIGHT
            else -> TileType.TAPO_DEVICE
        }

        return DiscoveredTapoKasaDevice(
            deviceId = devId,
            name = alias,
            model = model,
            ipAddress = ip,
            macAddress = mac,
            isKasaProtocol = true,
            suggestedType = suggestedType,
            isActive = isActive,
            isSocket2Active = socket2Active,
            multiGangCount = if (hasTwoOutlets) 2 else 1,
            socket1Name = child1Alias,
            socket2Name = child2Alias,
            brightnessPct = brightness,
            powerWatts = watts,
            voltageVolts = volts,
            currentMilliamps = currentMa,
            totalEnergyKwh = totalKwh,
            requiresAuth = false
        )
    }

    suspend fun setKasaRelayState(
        ipAddress: String,
        turnOn: Boolean,
        brightnessPct: Int = -1,
        socketNumber: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanIp = ipAddress.substringBefore(":").trim()
        if (cleanIp.isBlank()) return@withContext false
        val stateInt = if (turnOn) 1 else 0

        // Lazy-resolve child outlet IDs on cold start if a specific socket was requested
        if (socketNumber != null && kasaChildIdsByIp[cleanIp] == null) {
            val sysJson = sendKasaTcpQuery(cleanIp, """{"system":{"get_sysinfo":{}}}""", 900)
            if (sysJson != null) {
                parseKasaSysinfoResponse(cleanIp, sysJson)
            }
        }

        val childIds = kasaChildIdsByIp[cleanIp]
        val targetChildId = if (socketNumber != null && childIds != null && childIds.size >= socketNumber) {
            childIds[socketNumber - 1]
        } else null

        val cmd = when {
            targetChildId != null ->
                """{"context":{"child_ids":["$targetChildId"]},"system":{"set_relay_state":{"state":$stateInt}}}"""
            brightnessPct in 1..100 ->
                """{"system":{"set_relay_state":{"state":$stateInt}},"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"on_off":$stateInt,"brightness":$brightnessPct}}}"""
            else ->
                """{"system":{"set_relay_state":{"state":$stateInt}}}"""
        }
        val resp = sendKasaTcpQuery(cleanIp, cmd, 1500)
        if (resp != null) {
            val sysErr = resp.optJSONObject("system")?.optJSONObject("set_relay_state")?.optInt("err_code", 0) ?: 0
            if (sysErr == 0) return@withContext true
        }

        // F-19: Fallback to UDP 9999 packet if TCP is closed — decrypt and verify err_code == 0
        return@withContext try {
            DatagramSocket().use { udp ->
                udp.soTimeout = 900
                val data = encryptKasaXorPayload(cmd, includeLengthHeader = false)
                udp.send(DatagramPacket(data, data.size, InetAddress.getByName(cleanIp), KASA_PORT))
                val ackBuf = ByteArray(1024)
                val ackPacket = DatagramPacket(ackBuf, ackBuf.size)
                udp.receive(ackPacket)
                if (ackPacket.length <= 0) return@use false
                val ackJsonStr = decryptKasaXorPayload(ackPacket.data, 0, ackPacket.length)
                val ackJson = JSONObject(ackJsonStr)
                val errCode = ackJson.optJSONObject("system")
                    ?.optJSONObject("set_relay_state")
                    ?.optInt("err_code", -1) ?: 0
                errCode == 0
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
            false
}
    }

    // -----------------------------------------------------------------------
    // 2B. TP-LINK TAPO LOCAL KLAP (v1 & v2) + MULTI-GANG CHILD CONTROL ENGINE
    // -----------------------------------------------------------------------
    private fun sha1Bytes(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(data)

    private fun sha256Bytes(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun concatBytes(vararg arrays: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        arrays.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun performKlapHandshake(
        ip: String,
        email: String,
        password: String
    ): KlapHandshakeSession? {
        val cleanIp = ip.substringBefore(":").trim()
        if (cleanIp.isBlank() || email.isBlank() || password.isBlank()) return null

        val userSha1 = sha1Bytes(email.trim().toByteArray(Charsets.UTF_8))
        val passSha1 = sha1Bytes(password.toByteArray(Charsets.UTF_8))

        // KLAP v2 auth_hash = SHA256(SHA1(email) + SHA1(password))
        // KLAP v1 auth_hash = SHA1(email + password)
        val candidateAuthHashes = listOf(
            true to sha256Bytes(concatBytes(userSha1, passSha1)),
            false to sha1Bytes(concatBytes(email.trim().toByteArray(Charsets.UTF_8), password.toByteArray(Charsets.UTF_8)))
        )

        for ((isV2, authHash) in candidateAuthHashes) {
            try {
                val localSeed = ByteArray(16).apply { SecureRandom().nextBytes(this) }
                val conn1 = (URL("http://$cleanIp/app/handshake1").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 1800
                    readTimeout = 1800
                    doOutput = true
                    setRequestProperty("Content-Type", "application/octet-stream")
                }
                conn1.outputStream.use { it.write(localSeed); it.flush() }
                if (conn1.responseCode !in 200..299) {
                    conn1.disconnect()
                    continue
                }
                val setCookie = conn1.headerFields["Set-Cookie"]?.firstOrNull()?.substringBefore(";").orEmpty()
                val resp1Bytes = conn1.inputStream.use { it.readBytes() }
                conn1.disconnect()

                if (resp1Bytes.size < 48 || setCookie.isBlank()) continue
                val remoteSeed = resp1Bytes.copyOfRange(0, 16)
                val serverHash = resp1Bytes.copyOfRange(16, 48)

                val expectedServerHash = sha256Bytes(concatBytes(localSeed, remoteSeed, authHash))
                if (!serverHash.contentEquals(expectedServerHash)) {
                    continue
                }

                // Handshake 2: Send SHA256(remoteSeed + localSeed + authHash)
                val clientProof = sha256Bytes(concatBytes(remoteSeed, localSeed, authHash))
                val conn2 = (URL("http://$cleanIp/app/handshake2").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 1800
                    readTimeout = 1800
                    doOutput = true
                    setRequestProperty("Cookie", setCookie)
                    setRequestProperty("Content-Type", "application/octet-stream")
                }
                conn2.outputStream.use { it.write(clientProof); it.flush() }
                val code2 = conn2.responseCode
                conn2.disconnect()
                if (code2 !in 200..299) continue

                // Derive KLAP session key, IV prefix, and signature prefix
                val keyHash = sha256Bytes(concatBytes("lsk".toByteArray(Charsets.UTF_8), localSeed, remoteSeed, authHash))
                val key = keyHash.copyOfRange(0, 16)

                val ivHash = sha256Bytes(concatBytes("iv".toByteArray(Charsets.UTF_8), localSeed, remoteSeed, authHash))
                val ivPrefix = ivHash.copyOfRange(0, 12)
                val initialSeq = ByteBuffer.wrap(ivHash, ivHash.size - 4, 4).int

                val sigHash = sha256Bytes(concatBytes("ldk".toByteArray(Charsets.UTF_8), localSeed, remoteSeed, authHash))
                val sigPrefix = sigHash.copyOfRange(0, 28)

                val session = KlapHandshakeSession(
                    cookie = setCookie,
                    key = key,
                    ivPrefix = ivPrefix,
                    sigPrefix = sigPrefix,
                    seq = AtomicInteger(initialSeq),
                    isV2 = isV2
                )
                klapSessionByIp[cleanIp] = session
                SmartHomeApiClient.registerVerifiedLanNeighbor(cleanIp)
                Log.d(TAG, "Established local Tapo KLAP session with $cleanIp (v2=$isV2)")
                return session
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
        return null
    }

    private fun executeKlapRequest(
        ip: String,
        email: String,
        password: String,
        jsonRequest: JSONObject
    ): JSONObject? {
        val cleanIp = ip.substringBefore(":").trim()
        var session = klapSessionByIp[cleanIp] ?: performKlapHandshake(cleanIp, email, password) ?: return null

        for (attempt in 0..1) {
            try {
                val seqNum = session.seq.incrementAndGet()
                val seqBytes = ByteBuffer.allocate(4).putInt(seqNum).array()
                val iv = concatBytes(session.ivPrefix, seqBytes)

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(session.key, "AES"), IvParameterSpec(iv))
                val cipherBytes = cipher.doFinal(jsonRequest.toString().toByteArray(Charsets.UTF_8))

                val sig = sha256Bytes(concatBytes(session.sigPrefix, seqBytes, cipherBytes))
                val requestPayload = concatBytes(sig, cipherBytes)

                val conn = (URL("http://$cleanIp/app/request?seq=$seqNum").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 1800
                    readTimeout = 1800
                    doOutput = true
                    setRequestProperty("Cookie", session.cookie)
                    setRequestProperty("Content-Type", "application/octet-stream")
                }
                conn.outputStream.use { it.write(requestPayload); it.flush() }
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    klapSessionByIp.remove(cleanIp)
                    session = performKlapHandshake(cleanIp, email, password) ?: return null
                    continue
                }
                val respBytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                if (respBytes.size <= 32) return null

                val respCipher = respBytes.copyOfRange(32, respBytes.size)
                val decCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                decCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(session.key, "AES"), IvParameterSpec(iv))
                val plainBytes = decCipher.doFinal(respCipher)
                return JSONObject(String(plainBytes, Charsets.UTF_8))
            } catch (_: Exception) {
                klapSessionByIp.remove(cleanIp)
                if (attempt == 0) {
                    session = performKlapHandshake(cleanIp, email, password) ?: return null
                }
            }
        }
        return null
    }

    /**
     * HIGH-04 Fix: Supports both single-socket `set_device_info` and multi-gang sub-device
     * `control_child` actuation (`socketNumber = 1` or `2`) for Tapo P210M / P300 power strips.
     */
    suspend fun setTapoDeviceState(
        context: Context,
        ipAddress: String,
        turnOn: Boolean,
        brightnessPct: Int = -1,
        socketNumber: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val email = getSavedTpLinkEmail(context)
        val pass = getSavedTpLinkPassword(context)
        val cleanIp = ipAddress.substringBefore(":").trim()

        if (cleanIp.isNotBlank() && email.isNotBlank() && pass.isNotBlank()) {
            // Lazy-resolve Tapo child device IDs if a specific sub-socket was requested
            if (socketNumber != null && tapoChildIdsByIp[cleanIp] == null) {
                queryTapoLiveTelemetry(context, cleanIp)
            }
            val childIds = tapoChildIdsByIp[cleanIp]
            val targetChildId = if (socketNumber != null && childIds != null && childIds.size >= socketNumber) {
                childIds[socketNumber - 1]
            } else null

            val setInfoParams = JSONObject().apply {
                put("device_on", turnOn)
                if (turnOn && brightnessPct in 1..100) {
                    put("brightness", brightnessPct)
                }
            }

            val req = if (targetChildId != null) {
                JSONObject().apply {
                    put("method", "control_child")
                    put(
                        "params",
                        JSONObject().apply {
                            put("device_id", targetChildId)
                            put(
                                "requestData",
                                JSONObject().apply {
                                    put("method", "set_device_info")
                                    put("params", setInfoParams)
                                    put("requestTimeMils", System.currentTimeMillis())
                                }
                            )
                        }
                    )
                    put("requestTimeMils", System.currentTimeMillis())
                }
            } else {
                JSONObject().apply {
                    put("method", "set_device_info")
                    put("params", setInfoParams)
                    put("requestTimeMils", System.currentTimeMillis())
                }
            }

            val resp = executeKlapRequest(cleanIp, email, pass, req)
            if (resp != null && resp.optInt("error_code", -1) == 0) {
                return@withContext true
            }
        }
        false
    }

    suspend fun queryTapoLiveTelemetry(
        context: Context,
        ipAddress: String
    ): DiscoveredTapoKasaDevice? = withContext(Dispatchers.IO) {
        val email = getSavedTpLinkEmail(context)
        val pass = getSavedTpLinkPassword(context)
        val cleanIp = ipAddress.substringBefore(":").trim()
        if (cleanIp.isBlank() || email.isBlank() || pass.isBlank()) return@withContext null

        val infoReq = JSONObject().apply {
            put("method", "get_device_info")
            put("requestTimeMils", System.currentTimeMillis())
        }
        val infoResp = executeKlapRequest(cleanIp, email, pass, infoReq) ?: return@withContext null
        if (infoResp.optInt("error_code", -1) != 0) return@withContext null
        val res = infoResp.optJSONObject("result") ?: return@withContext null

        val devId = res.optString("device_id", cleanIp)
        val mac = res.optString("mac", "")
        SmartHomeApiClient.registerVerifiedLanNeighbor(cleanIp, mac.ifBlank { devId })

        val rawNickname = res.optString("nickname", "")
        val decodedName = try {
            if (rawNickname.isNotBlank()) {
                String(Base64.decode(rawNickname, Base64.DEFAULT), Charsets.UTF_8).trim()
            } else "Tapo Smart Plug"
        } catch (_: Exception) {
            rawNickname.ifBlank { "Tapo Smart Plug" }
        }
        val model = res.optString("model", "Tapo P110")
        var isOn = res.optBoolean("device_on", false)
        val brightness = res.optInt("brightness", 100).toFloat().coerceIn(1f, 100f)
        val isLight = model.startsWith("L", ignoreCase = true)

        // Check if this Tapo device is a multi-socket strip/dual plug (e.g. Tapo P210M / P300)
        var multiGangCount = 1
        var isSocket2On = false
        var socket1Label = "Socket 1"
        var socket2Label = "Socket 2"
        val childReq = JSONObject().apply {
            put("method", "get_child_device_list")
            put("requestTimeMils", System.currentTimeMillis())
        }
        val childResp = executeKlapRequest(cleanIp, email, pass, childReq)
        val childList = childResp?.optJSONObject("result")?.optJSONArray("child_device_list")
        if (childList != null && childList.length() >= 2) {
            multiGangCount = childList.length()
            val childIds = mutableListOf<String>()
            for (cIdx in 0 until childList.length()) {
                val cId = childList.optJSONObject(cIdx)?.optString("device_id", "").orEmpty().trim()
                if (cId.isNotBlank()) childIds.add(cId)
            }
            if (childIds.size >= 2) {
                tapoChildIdsByIp[cleanIp] = childIds
            }
            val c0 = childList.optJSONObject(0)
            val c1 = childList.optJSONObject(1)
            isOn = c0?.optBoolean("device_on", isOn) ?: isOn
            isSocket2On = c1?.optBoolean("device_on", false) ?: false
            val rawNick0 = c0?.optString("nickname", "").orEmpty()
            val rawNick1 = c1?.optString("nickname", "").orEmpty()
            if (rawNick0.isNotBlank()) {
                socket1Label = runCatching { String(Base64.decode(rawNick0, Base64.DEFAULT), Charsets.UTF_8).trim() }
                    .getOrDefault(rawNick0).ifBlank { "Socket 1" }
            }
            if (rawNick1.isNotBlank()) {
                socket2Label = runCatching { String(Base64.decode(rawNick1, Base64.DEFAULT), Charsets.UTF_8).trim() }
                    .getOrDefault(rawNick1).ifBlank { "Socket 2" }
            }
        }

        // Query live energy usage for Tapo P110 / P115 / P210M plugs
        var watts = 0f
        var totalKwh = 0f
        val energyReq = JSONObject().apply {
            put("method", "get_energy_usage")
            put("requestTimeMils", System.currentTimeMillis())
        }
        val energyResp = executeKlapRequest(cleanIp, email, pass, energyReq)
        val energyRes = energyResp?.optJSONObject("result")
        if (energyRes != null) {
            watts = energyRes.optDouble("current_power", 0.0).toFloat() / 1000f
            val monthWh = energyRes.optDouble("month_energy", energyRes.optDouble("today_energy", 0.0)).toFloat()
            totalKwh = monthWh / 1000f
        }

        val item = DiscoveredTapoKasaDevice(
            deviceId = devId,
            name = decodedName,
            model = model,
            ipAddress = cleanIp,
            macAddress = mac,
            isKasaProtocol = false,
            suggestedType = if (isLight) TileType.HUE_LIGHT else TileType.TAPO_DEVICE,
            isActive = isOn,
            isSocket2Active = isSocket2On,
            multiGangCount = multiGangCount,
            socket1Name = socket1Label,
            socket2Name = socket2Label,
            brightnessPct = brightness,
            powerWatts = watts,
            totalEnergyKwh = totalKwh,
            requiresAuth = false
        )
        discoveredDevicesCache[devId] = item
        item
    }

    /**
     * Logs into TP-Link Cloud (`wap.tplinkcloud.com`) to fetch the user's Tapo & Kasa device list,
     * and correlates them with local subnet devices on ports 9999 (Kasa) and 80 (Tapo).
     */
    suspend fun loginAndDiscoverTpLinkDevices(
        context: Context,
        email: String = getSavedTpLinkEmail(context),
        password: String = getSavedTpLinkPassword(context)
    ): Result<List<DiscoveredTapoKasaDevice>> = withContext(Dispatchers.IO) {
        if (email.isNotBlank() && password.isNotBlank()) {
            saveTpLinkCredentials(context, email, password)
        }

        // 1. Discover local Kasa & Tapo devices on Wi-Fi
        val localFound = discoverLocalKasaAndTapoDevices(context)
        localFound.forEach { discoveredDevicesCache[it.deviceId] = it }

        // 2. Also query TP-Link Cloud if email & password were provided
        val cleanEmail = email.trim()
        if (cleanEmail.isNotBlank() && password.isNotBlank()) {
            try {
                val termId = UUID.randomUUID().toString()
                val loginPayload = JSONObject().apply {
                    put("method", "login")
                    put(
                        "params",
                        JSONObject().apply {
                            put("appType", "Tapo_Android")
                            put("cloudUserName", cleanEmail)
                            put("cloudPassword", password)
                            put("terminalUUID", termId)
                        }
                    )
                }
                val conn = (URL("https://wap.tplinkcloud.com").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 6000
                    readTimeout = 6000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                OutputStreamWriter(conn.outputStream).use { it.write(loginPayload.toString()); it.flush() }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val root = JSONObject(body)
                if (root.optInt("error_code", -1) == 0) {
                    val token = root.optJSONObject("result")?.optString("token", "").orEmpty()
                    if (token.isNotBlank()) {
                        val listPayload = JSONObject().put("method", "getDeviceList")
                        val listConn = (URL("https://wap.tplinkcloud.com?token=$token").openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            connectTimeout = 6000
                            readTimeout = 6000
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json")
                        }
                        OutputStreamWriter(listConn.outputStream).use { it.write(listPayload.toString()); it.flush() }
                        val listBody = listConn.inputStream.bufferedReader().use { it.readText() }
                        listConn.disconnect()

                        val devArr = JSONObject(listBody).optJSONObject("result")?.optJSONArray("deviceList") ?: JSONArray()
                        for (i in 0 until devArr.length()) {
                            val d = devArr.optJSONObject(i) ?: continue
                            val devId = d.optString("deviceId", "").trim()
                            val rawAlias = d.optString("alias", "Tapo Plug").trim()
                            val decodedAlias = if (rawAlias.matches(Regex("^[A-Za-z0-9+/=]+$")) && rawAlias.length >= 8) {
                                runCatching { String(Base64.decode(rawAlias, Base64.DEFAULT), Charsets.UTF_8).trim() }
                                    .getOrDefault(rawAlias)
                            } else rawAlias
                            val model = d.optString("deviceModel", "Tapo P110")
                            val mac = d.optString("deviceMac", "").uppercase(Locale.US)
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                                transportSource = "TPLINK_CLOUD",
                                hardwareId = devId,
                                macAddress = CrossEcosystemIdentityCorrelator.normalizeMac(mac),
                                vendor = "$decodedAlias ($model)",
                                confidenceScore = 0.80f,
                                qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                outcomeReason = "TP-Link Cloud getDeviceList item received",
                                cloudId = devId
                            )
                            if (devId.isBlank()) {
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.FILTERED,
                                    transportSource = "TPLINK_CLOUD",
                                    macAddress = CrossEcosystemIdentityCorrelator.normalizeMac(mac),
                                    vendor = decodedAlias,
                                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                    outcomeReason = "Filtered TP-Link Cloud item missing deviceId"
                                )
                                continue
                            }
                            val isLight = model.startsWith("L", true) || model.startsWith("KL", true)

                            // Match against any locally discovered IP by MAC or DeviceID
                            val existingLocal = discoveredDevicesCache[devId]
                                ?: discoveredDevicesCache.values.firstOrNull {
                                    it.macAddress.replace(":", "").replace("-", "").equals(mac.replace(":", "").replace("-", ""), true)
                                }
                            val isDualModel = existingLocal?.multiGangCount ?: if (
                                model.contains("P210", true) ||
                                model.contains("P300", true) ||
                                model.contains("KP200", true) ||
                                model.contains("KP303", true) ||
                                model.contains("KP400", true) ||
                                model.contains("EP40", true)
                            ) 2 else 1

                            val merged = DiscoveredTapoKasaDevice(
                                deviceId = devId,
                                name = decodedAlias.ifBlank { existingLocal?.name ?: "Tapo Smart Plug" },
                                model = model,
                                ipAddress = existingLocal?.ipAddress.orEmpty(),
                                macAddress = mac,
                                isKasaProtocol = model.startsWith("HS", true) || model.startsWith("KP", true) || model.startsWith("EP", true),
                                suggestedType = if (isLight) TileType.HUE_LIGHT else TileType.TAPO_DEVICE,
                                isActive = existingLocal?.isActive ?: (d.optInt("status", 1) == 1),
                                isSocket2Active = existingLocal?.isSocket2Active ?: false,
                                multiGangCount = isDualModel,
                                socket1Name = existingLocal?.socket1Name ?: "Socket 1",
                                socket2Name = existingLocal?.socket2Name ?: "Socket 2",
                                powerWatts = existingLocal?.powerWatts ?: 0f,
                                totalEnergyKwh = existingLocal?.totalEnergyKwh ?: 0f,
                                requiresAuth = false
                            )
                            discoveredDevicesCache[devId] = merged
                        }
                    }
                } else {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FAILED,
                        transportSource = "TPLINK_CLOUD",
                        vendor = "TP-Link Cloud",
                        qualificationStage = "loginAndDiscoverTpLinkDevices",
                        outcomeReason = "TP-Link Cloud login returned error_code=${root.optInt("error_code", -1)}"
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "TP-Link Cloud discovery note: ${e.message}")
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "TPLINK_CLOUD",
                    vendor = "TP-Link Cloud",
                    qualificationStage = "loginAndDiscoverTpLinkDevices",
                    outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "TP-Link Cloud request failed"}"
                )
            }
        }

        val finalList = discoveredDevicesCache.values.toList()
        MultiProtocolLiveBridge.discoveredTapoKasaDevicesFlow.value = finalList
        if (finalList.isNotEmpty()) {
            Result.success(finalList)
        } else {
            Result.failure(Exception("No TP-Link Tapo or Kasa devices found on your Wi-Fi yet."))
        }
    }

    /**
     * Broadcasts UDP 9999 for Kasa devices AND probes TCP 9999 (Kasa) + HTTP `/app` (Tapo KLAP)
     * across the local subnet.
     */
    suspend fun discoverLocalKasaAndTapoDevices(context: Context): List<DiscoveredTapoKasaDevice> =
        withContext(Dispatchers.IO) {
            DiscoveryRadioCoordinator.withMulticastLock(context, "kasa_udp_9999") {
                val found = ConcurrentHashMap<String, DiscoveredTapoKasaDevice>()
                val kasaQuery = """{"system":{"get_sysinfo":{}},"emeter":{"get_realtime":{}}}"""

                // 1. Fast UDP 9999 Broadcast for Kasa Plugs, Strips & Bulbs
                try {
                    DatagramSocket().use { udp ->
                        udp.broadcast = true
                        udp.soTimeout = 350
                        val packetBytes = encryptKasaXorPayload(kasaQuery, includeLengthHeader = false)
                        udp.send(DatagramPacket(packetBytes, packetBytes.size, InetAddress.getByName("255.255.255.255"), KASA_PORT))

                        val deadline = System.currentTimeMillis() + 1100L
                        val buf = ByteArray(4096)
                        while (System.currentTimeMillis() < deadline) {
                            val packet = DatagramPacket(buf, buf.size)
                            val received = try {
                                udp.receive(packet)
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
                            if (!received || packet.length <= 0) continue
                            val senderIp = packet.address?.hostAddress ?: continue
                            val jsonStr = decryptKasaXorPayload(packet.data, 0, packet.length)
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                                transportSource = "KASA_UDP_9999",
                                ipAddress = senderIp,
                                port = KASA_PORT,
                                vendor = "UDP 9999 Responder",
                                confidenceScore = 0.25f,
                                qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                                outcomeReason = "TP-Link Kasa UDP 9999 response received (${packet.length} bytes)",
                                rawPayload = jsonStr.take(300)
                            )
                            val json = runCatching { JSONObject(jsonStr) }.getOrNull()
                            val parsedDev = if (json != null) parseKasaSysinfoResponse(senderIp, json) else null
                            if (parsedDev != null) {
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.IDENTIFIED,
                                    transportSource = "KASA_UDP_9999",
                                    hardwareId = parsedDev.deviceId,
                                    macAddress = CrossEcosystemIdentityCorrelator.normalizeMac(parsedDev.macAddress),
                                    ipAddress = parsedDev.ipAddress,
                                    port = KASA_PORT,
                                    vendor = "${parsedDev.name} (${parsedDev.model})",
                                    confidenceScore = 0.95f,
                                    qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                                    outcomeReason = "Verified Kasa system.get_sysinfo JSON (outlets=${parsedDev.multiGangCount})"
                                )
                                found[parsedDev.ipAddress] = parsedDev
                                discoveredDevicesCache[parsedDev.deviceId] = parsedDev
                            } else {
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.FILTERED,
                                    transportSource = "KASA_UDP_9999",
                                    ipAddress = senderIp,
                                    port = KASA_PORT,
                                    vendor = "UDP 9999 Host",
                                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                    outcomeReason = "Rejected UDP 9999 packet without valid Kasa get_sysinfo JSON"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FAILED,
                        transportSource = "KASA_UDP_9999",
                        port = KASA_PORT,
                        vendor = "TP-Link Kasa Scanner",
                        qualificationStage = "discoverLocalKasaAndTapoDevices",
                        outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "UDP 9999 scan failed"}"
                    )
                }

                // 2. Probe single-host TCP 9999 (Kasa) & HTTP /app (Tapo) if credentials exist
                val email = getSavedTpLinkEmail(context)
                val pass = getSavedTpLinkPassword(context)
                if (email.isNotBlank() && pass.isNotBlank()) {
                    for (cached in discoveredDevicesCache.values) {
                        if (!cached.isKasaProtocol && cached.ipAddress.isNotBlank()) {
                            queryTapoLiveTelemetry(context, cached.ipAddress)?.let { updated ->
                                found[updated.ipAddress] = updated
                            }
                        }
                    }
                }

                val finalList = found.values.toList()
                if (finalList.isNotEmpty()) {
                    MultiProtocolLiveBridge.discoveredTapoKasaDevicesFlow.value = discoveredDevicesCache.values.toList()
                }
                finalList
            }
        }

    /**
     * Checks if a given local IP on port 80 is a TP-Link Tapo plug/bulb (`/app` KLAP endpoint).
     * Even if the user hasn't signed into their TP-Link account yet, detects the KLAP
     * endpoint on `/app` and returns a `requiresAuth = true` entry so the UI can show "Sign In / OAuth".
     */
    suspend fun probeTapoOnIp(context: Context, ip: String): DiscoveredTapoKasaDevice? =
        withContext(Dispatchers.IO) {
            val cleanIp = ip.substringBefore(":").trim()
            if (cleanIp.isBlank()) return@withContext null
            val email = getSavedTpLinkEmail(context)
            val pass = getSavedTpLinkPassword(context)
            if (email.isNotBlank() && pass.isNotBlank()) {
                val authenticatedDev = queryTapoLiveTelemetry(context, cleanIp)
                if (authenticatedDev != null) {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.IDENTIFIED,
                        transportSource = "TAPO_KLAP_HTTP_80",
                        hardwareId = authenticatedDev.deviceId,
                        macAddress = CrossEcosystemIdentityCorrelator.normalizeMac(authenticatedDev.macAddress),
                        ipAddress = cleanIp,
                        port = 80,
                        vendor = "${authenticatedDev.name} (${authenticatedDev.model})",
                        confidenceScore = 0.95f,
                        qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                        outcomeReason = "Authenticated Tapo KLAP get_device_info verified"
                    )
                    return@withContext authenticatedDev
                }
            }
            // Probe `/app` endpoint to see if this IP is an unauthenticated Tapo KLAP device
            try {
                val conn = (URL("http://$cleanIp/app").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 450
                    readTimeout = 450
                }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                if (body.contains("error_code", ignoreCase = true)) {
                    SmartHomeApiClient.registerVerifiedLanNeighbor(cleanIp)
                    val unauthDev = DiscoveredTapoKasaDevice(
                        deviceId = "tapo_$cleanIp",
                        name = "TP-Link Tapo Smart Device ($cleanIp)",
                        model = "Tapo Wi-Fi",
                        ipAddress = cleanIp,
                        isKasaProtocol = false,
                        suggestedType = TileType.TAPO_DEVICE,
                        requiresAuth = true
                    )
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.IDENTIFIED,
                        transportSource = "TAPO_KLAP_HTTP_80",
                        hardwareId = unauthDev.deviceId,
                        ipAddress = cleanIp,
                        port = 80,
                        vendor = unauthDev.name,
                        confidenceScore = 0.92f,
                        qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                        outcomeReason = "Detected Tapo KLAP /app endpoint (requires TP-Link credentials)"
                    )
                    discoveredDevicesCache[unauthDev.deviceId] = unauthDev
                    return@withContext unauthDev
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
            null
        }

    /**
     * Syncs live state, single/dual socket count & Watt/kWh power telemetry for all Kasa and Tapo tiles
     * concurrently in bounded batches, reconciling against recent user taps via
     * `DeviceRepository.reconcileRemoteTileUpdate` and replaying any queued Digital Twin Shadow intents.
     */
    suspend fun syncLiveTapoKasaStates(context: Context, tiles: MutableList<SmartTile>) {
        val tapoTiles = tiles.withIndex().filter { (_, t) ->
            t.type == TileType.TAPO_DEVICE ||
                    t.activeProviderId.contains("TAPO", ignoreCase = true) ||
                    t.activeProviderId.contains("KASA", ignoreCase = true) ||
                    t.activeProviderId.contains("TPLINK", ignoreCase = true)
        }
        if (tapoTiles.isEmpty()) return

        val kasaQuery = """{"system":{"get_sysinfo":{}},"emeter":{"get_realtime":{}}}"""
        val resultsByTileId = ConcurrentHashMap<String, DiscoveredTapoKasaDevice>()

        withContext(Dispatchers.IO) {
            coroutineScope {
                tapoTiles.chunked(8).forEach { batch ->
                    batch.map { (_, tile) ->
                        async(Dispatchers.IO) {
                            val cleanIp = tile.ipAddress.substringBefore(":").trim()
                            if (cleanIp.isNotBlank()) {
                                val liveDev = if (SmartHomeApiClient.isPortOpenInternal(cleanIp, KASA_PORT, 350)) {
                                    val json = sendKasaTcpQuery(cleanIp, kasaQuery, 900)
                                    if (json != null) parseKasaSysinfoResponse(cleanIp, json) else null
                                } else {
                                    queryTapoLiveTelemetry(context, cleanIp)
                                }
                                if (liveDev != null) {
                                    resultsByTileId[tile.id] = liveDev
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val bridge = DeviceRepository.loadBridge(context)
        val polledIds = tapoTiles.map { it.value.id }.toSet()
        withContext(Dispatchers.Main) {
            for (i in tiles.indices) {
                val current = tiles[i]
                if (current.id !in polledIds) continue
                val liveDev = resultsByTileId[current.id]
                if (liveDev == null) {
                    if (current.isConfiguredAndLinked) {
                        val misses = (missedPollCountByTileId[current.id] ?: 0) + 1
                        missedPollCountByTileId[current.id] = misses
                        if (misses >= 2 && current.isOnline) {
                            val offlineTile = current.copy(
                                isOnline = false,
                                lifecycleState = DeviceLifecycleState.OFFLINE
                            )
                            tiles[i] = DeviceRepository.reconcileRemoteTileUpdate(
                                existing = current,
                                incoming = offlineTile,
                                incomingAuthority = StateAuthorityTier.DEVICE_HARDWARE_TRUTH,
                                sourceName = "TP-Link LAN"
                            )
                        }
                    }
                    continue
                }
                missedPollCountByTileId[current.id] = 0
                val wasOffline = !current.isOnline
                val hasPower = liveDev.powerWatts > 0f || liveDev.totalEnergyKwh > 0f || current.hasExplicitPowerTelemetry
                val inferredCategory = AppliancePowerFingerprinter.inferApplianceFromTelemetry(
                    powerWatts = liveDev.powerWatts,
                    titleHint = current.title,
                    previous = current.detectedApplianceCategory
                )
                val incomingTile = current.copy(
                    isOnline = true,
                    lifecycleState = DeviceLifecycleState.ONLINE,
                    isActive = liveDev.isActive,
                    isSocket2Active = liveDev.isSocket2Active,
                    multiGangSocketCount = maxOf(current.multiGangSocketCount, liveDev.multiGangCount),
                    socket1Name = if (current.socket1Name == "Socket 1" && liveDev.socket1Name.isNotBlank()) liveDev.socket1Name else current.socket1Name,
                    socket2Name = if (current.socket2Name == "Socket 2" && liveDev.socket2Name.isNotBlank()) liveDev.socket2Name else current.socket2Name,
                    volume = if (current.deviceClass == DeviceClass.LIGHT) liveDev.brightnessPct else current.volume,
                    powerWatts = liveDev.powerWatts,
                    voltageVolts = liveDev.voltageVolts,
                    currentMilliamps = liveDev.currentMilliamps,
                    totalEnergyKwh = liveDev.totalEnergyKwh,
                    hasExplicitPowerTelemetry = hasPower,
                    detectedApplianceCategory = inferredCategory
                )
                tiles[i] = DeviceRepository.reconcileRemoteTileUpdate(current, incomingTile)
                if (wasOffline && audioManager != null) {
                    replayQueuedShadowIntentsForTile(
                        context = context,
                        tile = tiles[i],
                        bridge = bridge,
                        audioManager = audioManager,
                        scope = PlayerBackgroundScope
                    )
                }
            }
        }
    }
}
