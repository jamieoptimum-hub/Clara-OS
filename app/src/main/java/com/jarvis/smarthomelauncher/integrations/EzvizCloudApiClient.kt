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
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------
// EZVIZ CLOUD, OAUTH & DIRECT CONSUMER ACCOUNT CLIENT
// (Auto-Extracts Device Verification PINs, Local IPs & Cloud HLS / RTSP Streams)
// ---------------------------------------------------------------------------
data class EzvizDiscoveredDeviceDetail(
    val serial: String,
    val name: String,
    val localIp: String,
    val localRtspPort: Int,
    val isOnline: Boolean,
    val isSleeping: Boolean,
    val verificationCode: String = "",
    val isEncrypted: Boolean = true
)

object EzvizCloudApiClient {
    private const val TAG = "EzvizClient"
    private const val PREFS_EZVIZ = "clara_ezviz_account_prefs"
    private const val HLS_CACHE_TTL_MS = 8 * 60_000L

    private data class CachedEzvizStream(
        val url: String,
        val timestampMs: Long = System.currentTimeMillis()
    )

    @Volatile
    private var cachedToken: String = ""

    @Volatile
    private var cachedAreaDomain: String = DEFAULT_EZVIZ_API_HOST

    @Volatile
    private var cachedAppKey: String = ""

    @Volatile
    private var tokenExpiryMs: Long = 0L

    @Volatile
    private var cachedSessionId: String = ""

    @Volatile
    private var cachedConsumerRegionHost: String = "https://apiieu.ezvizlife.com"

    private val ezvizHlsCache = ConcurrentHashMap<String, CachedEzvizStream>()
    private val discoveredConsumerDevices = ConcurrentHashMap<String, EzvizDiscoveredDeviceDetail>()
    private val autoExtractedPinBySerial = ConcurrentHashMap<String, String>()

    private fun resolveAppContext(explicitContext: Context? = null): Context? =
        ClaraApplication.appContextOrNull(explicitContext)

    fun clearStreamCache() {
        ezvizHlsCache.clear()
    }

    fun invalidateStreamForSerial(serialNumber: String) {
        val clean = serialNumber.trim()
        if (clean.isBlank()) {
            ezvizHlsCache.clear()
            return
        }
        val keys = ezvizHlsCache.keys.filter { it.startsWith("${clean}_", ignoreCase = true) }
        keys.forEach { ezvizHlsCache.remove(it) }
    }

    /**
     * Resolves the 6-character EZVIZ Verification Code (PIN) for a tile by checking:
     * 1. `tile.secretCode`
     * 2. Auto-extracted per-serial PIN cache (`getSavedEzvizPinForSerial`)
     * 3. Cached consumer discovery detail by serial or IP
     * 4. Global saved EZVIZ verification code (`getSavedEzvizVerifyCode`)
     */
    fun resolveVerificationCodeForTile(context: Context?, tile: SmartTile): String {
        val direct = tile.secretCode.trim()
        if (direct.isNotBlank()) return direct
        val sn = tile.serialNumber.trim()
        val ctx = resolveAppContext(context)
        if (ctx != null && sn.isNotBlank()) {
            val bySn = getSavedEzvizPinForSerial(ctx, sn).trim()
            if (bySn.isNotBlank()) return bySn
        }
        if (sn.isNotBlank()) {
            discoveredConsumerDevices[sn]?.verificationCode?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val cleanIp = tile.ipAddress.substringBefore(":").trim()
        if (cleanIp.isNotBlank()) {
            getCachedDeviceDetailByIp(cleanIp)?.verificationCode?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        if (ctx != null) {
            val globalPin = getSavedEzvizVerifyCode(ctx).trim()
            if (globalPin.isNotBlank()) return globalPin
        }
        return ""
    }

    /**
     * Builds ordered local RTSP candidate URLs for EZVIZ cameras and doorbells:
     * - `rtsp://admin:<verification_code>@<ip>:554/Streaming/Channels/101` (Main stream)
     * - `rtsp://admin:<verification_code>@<ip>:554/Streaming/Channels/102` (H.264 Sub-stream)
     * - `rtsp://admin:<verification_code>@<ip>:554/h264/ch1/main/av_stream` & `sub/av_stream`
     */
    fun buildLocalRtspCandidateUrls(
        ipAddress: String,
        port: Int = 554,
        verificationCode: String,
        preferSubStream: Boolean = false
    ): List<String> {
        val cleanIp = ipAddress.substringBefore(":").trim()
        if (cleanIp.isBlank()) return emptyList()
        val effectivePort = if (port > 0) port else 554
        val cleanCode = verificationCode.trim()
        val authPrefix = if (cleanCode.isNotBlank()) {
            "admin:${Uri.encode(cleanCode)}@"
        } else {
            ""
        }
        val primaryChan = if (preferSubStream) "102" else "101"
        val secondaryChan = if (preferSubStream) "101" else "102"
        val primaryLegacy = if (preferSubStream) "sub" else "main"
        val secondaryLegacy = if (preferSubStream) "main" else "sub"

        return buildList {
            add("rtsp://$authPrefix$cleanIp:$effectivePort/Streaming/Channels/$primaryChan")
            add("rtsp://$authPrefix$cleanIp:$effectivePort/Streaming/Channels/$secondaryChan")
            add("rtsp://$authPrefix$cleanIp:$effectivePort/h264/ch1/$primaryLegacy/av_stream")
            add("rtsp://$authPrefix$cleanIp:$effectivePort/h264/ch1/$secondaryLegacy/av_stream")
        }.distinct()
    }

    /**
     * Wakes a sleeping battery EZVIZ doorbell over the cloud consumer session so its local RTSP
     * port 554 or cloud HLS responder comes online.
     */
    suspend fun wakeDoorbellIfNeeded(context: Context?, serialNumber: String) = withContext(Dispatchers.IO) {
        val cleanSn = serialNumber.trim().ifBlank {
            discoveredConsumerDevices.keys.firstOrNull().orEmpty()
        }
        if (cleanSn.isBlank()) return@withContext
        val sid = ensureConsumerSession(context)
        if (sid.isNotBlank()) {
            wakeConsumerDevice(cleanSn, sid)
        }
    }

    /**
     * Checks whether the user has an active EZVIZ session, saved consumer login, OAuth token,
     * or verification code so the discovery list can decide whether to show "Add" vs "Sign In / OAuth".
     */
    fun hasActiveSessionOrCredentials(
        context: Context,
        bridge: BridgeSettings = BridgeSettings()
    ): Boolean {
        if (cachedSessionId.isNotBlank() || (cachedToken.isNotBlank() && System.currentTimeMillis() < tokenExpiryMs)) {
            return true
        }
        if (getSavedOAuthSession(context) != null) return true
        if (loadPersistedConsumerSession(context) != null) return true
        if (getSavedEzvizEmail(context).isNotBlank() && getSavedEzvizPassword(context).isNotBlank()) return true
        if (getSavedEzvizVerifyCode(context).isNotBlank()) return true
        return bridge.ezvizAppKey.isNotBlank() && bridge.ezvizAppSecret.isNotBlank()
    }

    private fun md5LowerHex(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun saveEzvizConsumerCredentials(
        context: Context,
        email: String,
        password: String,
        verificationCode: String
    ) {
        val appCtx = context.applicationContext
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(appCtx, "${PREFS_EZVIZ}::ezviz_email", email.trim())
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(appCtx, "${PREFS_EZVIZ}::ezviz_password", password)
        if (verificationCode.isNotBlank()) {
            com.jarvis.smarthomelauncher.core.ClaraSecretVault.putCameraVerificationPin(appCtx, "DEFAULT", verificationCode.trim())
            com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(
                appCtx,
                "${PREFS_EZVIZ}::ezviz_verify_code",
                verificationCode.trim()
            )
        }
        appCtx.getSharedPreferences(PREFS_EZVIZ, Context.MODE_PRIVATE).edit()
            .remove("ezviz_email")
            .remove("ezviz_password")
            .remove("ezviz_verify_code")
            .apply()
    }

    fun saveEzvizPinForSerial(context: Context, serialNumber: String, verificationCode: String) {
        val cleanSn = serialNumber.trim()
        val cleanPin = verificationCode.trim()
        if (cleanSn.isBlank() || cleanPin.isBlank()) return
        autoExtractedPinBySerial[cleanSn] = cleanPin
        val appCtx = context.applicationContext
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.putCameraVerificationPin(appCtx, cleanSn, cleanPin)
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(
            appCtx,
            "${PREFS_EZVIZ}::ezviz_verify_code_$cleanSn",
            cleanPin
        )
        if (getSavedEzvizVerifyCode(appCtx).isBlank()) {
            com.jarvis.smarthomelauncher.core.ClaraSecretVault.putCameraVerificationPin(appCtx, "DEFAULT", cleanPin)
            com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(
                appCtx,
                "${PREFS_EZVIZ}::ezviz_verify_code",
                cleanPin
            )
        }
        appCtx.getSharedPreferences(PREFS_EZVIZ, Context.MODE_PRIVATE).edit()
            .remove("ezviz_verify_code_$cleanSn")
            .apply()
    }

    fun getSavedEzvizPinForSerial(context: Context, serialNumber: String): String {
        val cleanSn = serialNumber.trim()
        val appCtx = context.applicationContext
        if (cleanSn.isNotBlank()) {
            autoExtractedPinBySerial[cleanSn]?.takeIf { it.isNotBlank() }?.let { return it }
            com.jarvis.smarthomelauncher.core.ClaraSecretVault.getCameraVerificationPin(appCtx, cleanSn)
                ?.takeIf { it.isNotBlank() }
                ?.let { pin ->
                    val unwrapped = pin.trim()
                    autoExtractedPinBySerial[cleanSn] = unwrapped
                    return unwrapped
                }
            val rawPerSerial = com.jarvis.smarthomelauncher.core.ClaraSecretVault
                .migrateStringKey(appCtx, PREFS_EZVIZ, "ezviz_verify_code_$cleanSn")
                .orEmpty()
            val perSerial = rawPerSerial.trim()
            if (perSerial.isNotBlank()) {
                autoExtractedPinBySerial[cleanSn] = perSerial
                return perSerial
            }
        }
        return getSavedEzvizVerifyCode(appCtx)
    }

    fun saveEzvizOAuthTokens(
        context: Context,
        accessToken: String,
        refreshToken: String = "",
        areaDomain: String = DEFAULT_EZVIZ_API_HOST,
        expiresInSec: Long = 604800L
    ) {
        val cleanToken = accessToken.trim()
        if (cleanToken.isBlank()) return
        val cleanArea = areaDomain.trim().ifBlank { DEFAULT_EZVIZ_API_HOST }.let {
            if (it.startsWith("http", ignoreCase = true)) it.trimEnd('/') else "https://${it.trimEnd('/')}"
        }
        val expiryEpoch = System.currentTimeMillis() + (expiresInSec * 1000L)
        cachedToken = cleanToken
        cachedAreaDomain = cleanArea
        tokenExpiryMs = expiryEpoch - 300_000L
        val appCtx = context.applicationContext
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.saveEzvizSession(
            context = appCtx,
            accessToken = cleanToken,
            apiDomain = cleanArea,
            refreshToken = refreshToken.trim()
        )
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(
            appCtx,
            "${PREFS_EZVIZ}::ezviz_oauth_access_token",
            cleanToken
        )
        if (refreshToken.isNotBlank()) {
            com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(
                appCtx,
                "${PREFS_EZVIZ}::ezviz_oauth_refresh_token",
                refreshToken.trim()
            )
        }
        appCtx.getSharedPreferences(PREFS_EZVIZ, Context.MODE_PRIVATE).edit().apply {
            remove("ezviz_oauth_access_token")
            remove("ezviz_oauth_refresh_token")
            putString("ezviz_oauth_area_domain", cleanArea)
            putLong("ezviz_oauth_expiry_ms", expiryEpoch)
            apply()
        }
    }

    /**
     * HIGH-05 Fix: Reads the saved EZVIZ OAuth session and exchanges `ezviz_oauth_refresh_token`
     * when the access token is expired or within 5 minutes of expiry.
     */
    private fun getSavedOAuthSession(
        context: Context?,
        allowNetworkRefresh: Boolean = false
    ): Pair<String, String>? {
        val ctx = resolveAppContext(context) ?: return null
        val prefs = ctx.getSharedPreferences(PREFS_EZVIZ, Context.MODE_PRIVATE)
        val token = ClaraSecretVault.getEzvizAccessToken(ctx).orEmpty().trim()
        val rawRefresh = ClaraSecretVault.getString(ctx, "ezviz::refresh_token") ?: ClaraSecretVault.getString(ctx, "${PREFS_EZVIZ}::ezviz_oauth_refresh_token").orEmpty()
        val refreshToken = rawRefresh.trim()
        val area = ClaraSecretVault.getEzvizApiDomain(ctx) ?: prefs.getString("ezviz_oauth_area_domain", DEFAULT_EZVIZ_API_HOST)
            .orEmpty()
            .ifBlank { DEFAULT_EZVIZ_API_HOST }
        val expiry = prefs.getLong("ezviz_oauth_expiry_ms", 0L)
        val now = System.currentTimeMillis()

        if (token.isNotBlank() && now < expiry - 300_000L) {
            cachedToken = token
            cachedAreaDomain = area
            tokenExpiryMs = expiry
            return token to area
        }

        if (allowNetworkRefresh && refreshToken.isNotBlank()) {
            val refreshed = refreshEzvizOAuthTokenSync(ctx, refreshToken, area)
            if (refreshed != null) {
                return refreshed
            }
        }

        if (token.isNotBlank() && now < expiry) {
            cachedToken = token
            cachedAreaDomain = area
            tokenExpiryMs = expiry
            return token to area
        }
        return null
    }

    private fun refreshEzvizOAuthTokenSync(
        context: Context,
        refreshToken: String,
        areaDomain: String
    ): Pair<String, String>? {
        val candidateHosts = listOf(
            areaDomain.trimEnd('/'),
            "https://openauth.ezvizlife.com",
            DEFAULT_EZVIZ_API_HOST
        ).distinct()

        for (host in candidateHosts) {
            try {
                val resp = postForm(
                    "$host/api/lapp/token/refresh",
                    mapOf(
                        "refreshToken" to refreshToken,
                        "refresh_token" to refreshToken,
                        "grant_type" to "refresh_token"
                    )
                ) ?: continue
                val data = resp.optJSONObject("data") ?: resp
                val newToken = data.optString("accessToken", data.optString("access_token", "")).trim()
                val newRefresh = data.optString("refreshToken", data.optString("refresh_token", refreshToken)).trim()
                val newArea = data.optString("areaDomain", areaDomain).trim().ifBlank { areaDomain }
                val expireTime = data.optLong("expireTime", 0L)
                val expiresInSec = if (expireTime > System.currentTimeMillis()) {
                    ((expireTime - System.currentTimeMillis()) / 1000L).coerceAtLeast(3600L)
                } else {
                    data.optLong("expires_in", 604800L).coerceAtLeast(3600L)
                }
                if (newToken.isNotBlank()) {
                    saveEzvizOAuthTokens(
                        context = context,
                        accessToken = newToken,
                        refreshToken = newRefresh,
                        areaDomain = newArea,
                        expiresInSec = expiresInSec
                    )
                    Log.d(TAG, "Refreshed EZVIZ OAuth accessToken via refresh_token")
                    return newToken to cachedAreaDomain
                }
            } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
        }
        return null
    }

    fun getSavedEzvizEmail(context: Context): String {
        val appCtx = context.applicationContext
        return com.jarvis.smarthomelauncher.core.ClaraSecretVault
            .migrateStringKey(appCtx, PREFS_EZVIZ, "ezviz_email")
            .orEmpty()
    }

    fun getSavedEzvizPassword(context: Context): String {
        val appCtx = context.applicationContext
        val raw = com.jarvis.smarthomelauncher.core.ClaraSecretVault
            .migrateStringKey(appCtx, PREFS_EZVIZ, "ezviz_password")
            .orEmpty()
        return raw
    }

    fun getSavedEzvizVerifyCode(context: Context): String {
        val appCtx = context.applicationContext
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.getCameraVerificationPin(appCtx, "DEFAULT")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it.trim() }
        val raw = com.jarvis.smarthomelauncher.core.ClaraSecretVault
            .migrateStringKey(appCtx, PREFS_EZVIZ, "ezviz_verify_code")
            .orEmpty()
        return raw.trim()
    }

    fun getCachedDeviceDetail(serialNumber: String): EzvizDiscoveredDeviceDetail? {
        val clean = serialNumber.trim()
        return if (clean.isNotBlank()) discoveredConsumerDevices[clean] else discoveredConsumerDevices.values.firstOrNull()
    }

    fun getCachedDeviceDetailByIp(ipAddress: String): EzvizDiscoveredDeviceDetail? {
        val cleanIp = ipAddress.substringBefore(":").trim()
        if (cleanIp.isBlank()) return null
        return discoveredConsumerDevices.values.firstOrNull { it.localIp == cleanIp }
    }

    /**
     * Inspects all possible EZVIZ v3 device resource objects (`STATUS`, `KMS`, `deviceInfos`, `FEATURE`)
     * to extract the 6-character device Verification Code (PIN) automatically.
     */
    private fun extractPinFromEzvizJson(
        serial: String,
        devObj: JSONObject,
        statusObj: JSONObject?,
        kmsObj: JSONObject?,
        switchObj: JSONObject?
    ): Pair<String, Boolean> {
        val devStatus = statusObj?.optJSONObject(serial)
        val optionals = devStatus?.optJSONObject("optionals")
        val devKms = kmsObj?.optJSONObject(serial)

        val candidatePins = listOf(
            optionals?.optString("OperationCode", "").orEmpty(),
            optionals?.optString("operationCode", "").orEmpty(),
            optionals?.optString("validateCode", "").orEmpty(),
            optionals?.optString("verifyCode", "").orEmpty(),
            optionals?.optString("encryptPwd", "").orEmpty(),
            devStatus?.optString("OperationCode", "").orEmpty(),
            devStatus?.optString("validateCode", "").orEmpty(),
            devStatus?.optString("encryptPwd", "").orEmpty(),
            devKms?.optString("verifyCode", "").orEmpty(),
            devKms?.optString("validateCode", "").orEmpty(),
            devKms?.optString("operationCode", "").orEmpty(),
            devKms?.optString("secretKey", "").orEmpty(),
            devKms?.optString("code", "").orEmpty(),
            devObj.optString("validateCode", ""),
            devObj.optString("verifyCode", ""),
            devObj.optString("operationCode", ""),
            devObj.optString("OperationCode", ""),
            devObj.optString("encryptPwd", ""),
            devObj.optString("deviceCode", "")
        )

        val extractedPin = candidatePins
            .map { it.trim() }
            .firstOrNull { it.length in 4..16 && !it.equals("null", ignoreCase = true) }
            .orEmpty()

        // Check if video encryption is enabled (switchType 21 / isEncrypt)
        var isEncrypted = devObj.optInt("isEncrypt", 1) == 1 ||
                devStatus?.optInt("isEncrypt", 1) == 1
        val devSwitches = switchObj?.optJSONArray(serial)
        if (devSwitches != null) {
            for (i in 0 until devSwitches.length()) {
                val sw = devSwitches.optJSONObject(i) ?: continue
                if (sw.optInt("type", -1) == 21) {
                    isEncrypted = sw.optBoolean("enable", isEncrypted)
                }
            }
        }
        return extractedPin to isEncrypted
    }

    /**
     * Secondary fallback query to `/v3/userdevices/v1/kms/info` or `/v3/devices/{serial}/info`
     * if the primary pagelist response omitted the `OperationCode`.
     */
    private fun queryDevicePinFallback(serial: String, sessionId: String): String {
        if (serial.isBlank() || sessionId.isBlank()) return ""
        val candidateUrls = listOf(
            "$cachedConsumerRegionHost/v3/userdevices/v1/kms/info?deviceSerial=${URLEncoder.encode(serial, "UTF-8")}",
            "$cachedConsumerRegionHost/v3/userdevices/v1/resources/detail?deviceSerial=${URLEncoder.encode(serial, "UTF-8")}&filter=STATUS,KMS,CONNECTION"
        )
        for (urlStr in candidateUrls) {
            try {
                val res = runCatching {
                    kotlinx.coroutines.runBlocking {
                        NetworkModule.jsonService.get(
                            urlStr,
                            mapOf(
                                "sessionId" to sessionId,
                                "clientType" to "13"
                            )
                        )
                    }
                }.getOrNull()
                val body = res?.string() ?: ""
                if (res != null) {
                    val json = JSONObject(body)
                    val data = json.optJSONObject("data") ?: json
                    val pin = listOf(
                        data.optString("OperationCode", ""),
                        data.optString("operationCode", ""),
                        data.optString("validateCode", ""),
                        data.optString("verifyCode", ""),
                        data.optString("secretKey", ""),
                        json.optJSONObject("STATUS")?.optJSONObject(serial)?.optJSONObject("optionals")?.optString("OperationCode", "").orEmpty(),
                        json.optJSONObject("KMS")?.optJSONObject(serial)?.optString("verifyCode", "").orEmpty()
                    ).map { it.trim() }
                        .firstOrNull { it.length in 4..16 && !it.equals("null", ignoreCase = true) }
                        .orEmpty()
                    if (pin.isNotBlank()) return pin
                }
            } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
        }
        return ""
    }

    private fun autoSyncDiscoveredEzvizDetailsToSavedTiles(
        context: Context,
        discovered: List<EzvizDiscoveredDeviceDetail>
    ) {
        for (dev in discovered) {
            if (dev.localIp.isNotBlank()) {
                SmartHomeApiClient.registerVerifiedLanNeighbor(dev.localIp, dev.serial)
            }
            if (dev.serial.isNotBlank() && dev.verificationCode.isNotBlank()) {
                saveEzvizPinForSerial(context, dev.serial, dev.verificationCode)
            }
        }
    }

    private fun savePersistedConsumerSession(context: Context, sessionId: String, regionHost: String) {
        if (sessionId.isBlank()) return
        context.getSharedPreferences(PREFS_EZVIZ, Context.MODE_PRIVATE).edit().apply {
            putString("ezviz_saved_session_id", sessionId.trim())
            putString("ezviz_saved_region_host", regionHost.trim().trimEnd('/'))
            apply()
        }
    }

    private fun clearPersistedConsumerSession(context: Context?) {
        cachedSessionId = ""
        val ctx = resolveAppContext(context) ?: return
        ctx.getSharedPreferences(PREFS_EZVIZ, Context.MODE_PRIVATE).edit()
            .remove("ezviz_saved_session_id")
            .apply()
    }

    private fun loadPersistedConsumerSession(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS_EZVIZ, Context.MODE_PRIVATE)
        val rawSid = prefs.getString("ezviz_saved_session_id", "").orEmpty()
        val sid = rawSid.trim()
        val host = prefs.getString("ezviz_saved_region_host", "https://apiieu.ezvizlife.com")
            .orEmpty()
            .ifBlank { "https://apiieu.ezvizlife.com" }
        return if (sid.isNotBlank()) sid to host else null
    }

    private fun triggerEzvizOtpDispatch(
        hostUrl: String,
        cleanEmail: String,
        hashedPass: String,
        featureCode: String
    ) {
        val cleanHost = hostUrl.trimEnd('/')
        runCatching {
            performEzvizV5LoginRequest(
                hostUrl = cleanHost,
                cleanEmail = cleanEmail,
                hashedPass = hashedPass,
                featureCode = featureCode,
                smsCode = "",
                bizType = "TERMINAL_BIND",
                msgType = "1"
            )
        }
        runCatching {
            performEzvizV5LoginRequest(
                hostUrl = cleanHost,
                cleanEmail = cleanEmail,
                hashedPass = hashedPass,
                featureCode = featureCode,
                smsCode = "",
                bizType = "TERMINAL_BIND",
                msgType = "2"
            )
        }
        val otpEndpoints = listOf(
            "$cleanHost/v3/users/veriCode",
            "$cleanHost/v3/users/smsCode"
        )
        for (endpoint in otpEndpoints) {
            for (msgType in listOf("1", "2")) {
                try {
                    val form = mapOf(
                        "account" to cleanEmail,
                        "featureCode" to featureCode,
                        "bizType" to "TERMINAL_BIND",
                        "msgType" to msgType
                    )
                    val formBody = form.entries.joinToString("&") { (k, v) ->
                        "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                    }
                    runCatching {
                        kotlinx.coroutines.runBlocking {
                            NetworkModule.jsonService.postJson(
                                endpoint,
                                formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                                mapOf(
                                    "User-Agent" to "okhttp/3.12.1",
                                    "featureCode" to featureCode,
                                    "clientType" to "3",
                                    "osVersion" to "12",
                                    "clientVersion" to "5.9.8.0215",
                                    "appId" to "ys7",
                                    "lang" to "en-GB"
                                )
                            )
                        }
                    }
                } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
            }
        }
    }

    private fun performEzvizV5LoginRequest(
        hostUrl: String,
        cleanEmail: String,
        hashedPass: String,
        featureCode: String,
        smsCode: String = "",
        bizType: String = "",
        msgType: String = "0"
    ): JSONObject? {
        val form = mapOf(
            "account" to cleanEmail,
            "password" to hashedPass,
            "featureCode" to featureCode,
            "msgType" to msgType,
            "bizType" to bizType,
            "cuName" to "Y2xhcmFfc21hcnRfaG9tZQ==",
            "smsCode" to smsCode.trim()
        )
        val formBody = form.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val res = runCatching {
            kotlinx.coroutines.runBlocking {
                NetworkModule.jsonService.postJson(
                    "${hostUrl.trimEnd('/')}/v3/users/login/v5",
                    formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                    mapOf(
                        "User-Agent" to "okhttp/3.12.1",
                        "featureCode" to featureCode,
                        "clientType" to "3",
                        "osVersion" to "12",
                        "clientVersion" to "5.9.8.0215",
                        "netType" to "WIFI",
                        "customno" to "1000001",
                        "ssid" to "",
                        "clientNo" to "google",
                        "appId" to "ys7",
                        "language" to "en_GB",
                        "lang" to "en_GB"
                    )
                )
            }
        }.getOrNull()
        val body = res?.string() ?: ""
        return if (body.isNotBlank()) runCatching { JSONObject(body) }.getOrNull() else null
    }

    suspend fun loginAndDiscoverWithConsumerAccount(
        context: Context,
        email: String,
        password: String,
        smsVerifyCode: String = ""
    ): Result<List<EzvizDiscoveredDeviceDetail>> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val cleanEmail = email.trim()
        val cleanPass = password.trim()
        val cleanSmsCode = smsVerifyCode.trim()
        if (cleanEmail.isBlank() || cleanPass.isBlank()) {
            return@withContext Result.failure(Exception("Please enter your EZVIZ email and password."))
        }

        val hashedPass = md5LowerHex(cleanPass)
        val featureCode = md5LowerHex("clara_smart_home_$cleanEmail")

        // 1. Reuse persisted session token if already authorized and verified alive
        if (cleanSmsCode.isBlank()) {
            loadPersistedConsumerSession(context)?.let { (savedSid, savedHost) ->
                try {
                    val testUrl = "$savedHost/v3/userdevices/v1/resources/pagelist?groupId=-1&limit=5&offset=0&filter=STATUS"
                    val testRes = runCatching {
                        NetworkModule.jsonService.get(
                            testUrl,
                            mapOf(
                                "User-Agent" to "okhttp/3.12.1",
                                "sessionId" to savedSid,
                                "featureCode" to featureCode,
                                "clientType" to "3"
                            )
                        )
                    }.getOrNull()
                    val ok = testRes != null
                    if (ok) {
                        cachedSessionId = savedSid
                        cachedConsumerRegionHost = savedHost
                    } else {
                        clearPersistedConsumerSession(context)
                    }
                } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
            }
        }

        val candidateHosts = listOf(
            "https://apiieu.ezvizlife.com",
            "https://apiius.ezvizlife.com",
            "https://apiisgp.ezvizlife.com"
        )

        var sessionId = if (cleanSmsCode.isBlank()) cachedSessionId else ""
        var workingHost = cachedConsumerRegionHost
        var lastErr = "Could not sign in to your EZVIZ account. Please check your email and password."

        if (sessionId.isBlank()) {
            for (host in candidateHosts) {
                try {
                    val bizTypesToTry = if (cleanSmsCode.isNotBlank()) {
                        listOf("TERMINAL_BIND" to "1", "TERMINAL_BIND" to "0", "" to "1", "MFA" to "1")
                    } else {
                        listOf("" to "0")
                    }

                    var matchedSid = ""
                    var activeHost = host
                    var lastMetaCode = -1
                    var lastMetaMsg = ""

                    for ((bizType, msgType) in bizTypesToTry) {
                        var json = performEzvizV5LoginRequest(
                            hostUrl = activeHost,
                            cleanEmail = cleanEmail,
                            hashedPass = hashedPass,
                            featureCode = featureCode,
                            smsCode = cleanSmsCode,
                            bizType = bizType,
                            msgType = msgType
                        ) ?: continue

                        var meta = json.optJSONObject("meta")
                        var metaCode = meta?.optInt("code", -1) ?: -1
                        var loginArea = json.optJSONObject("loginArea")
                        var redirectedDomain = loginArea?.optString("apiDomain", "").orEmpty().trim()

                        // Handle EZVIZ regional redirect (code 1100)
                        if (metaCode == 1100 && redirectedDomain.isNotBlank()) {
                            activeHost = if (redirectedDomain.startsWith("http", ignoreCase = true)) {
                                redirectedDomain
                            } else {
                                "https://$redirectedDomain"
                            }
                            val redirectedJson = performEzvizV5LoginRequest(
                                hostUrl = activeHost,
                                cleanEmail = cleanEmail,
                                hashedPass = hashedPass,
                                featureCode = featureCode,
                                smsCode = cleanSmsCode,
                                bizType = bizType,
                                msgType = msgType
                            )
                            if (redirectedJson != null) {
                                json = redirectedJson
                                meta = json.optJSONObject("meta")
                                metaCode = meta?.optInt("code", -1) ?: -1
                                loginArea = json.optJSONObject("loginArea")
                                redirectedDomain = loginArea?.optString("apiDomain", "").orEmpty().trim().ifBlank { activeHost }
                            }
                        }

                        lastMetaCode = metaCode
                        lastMetaMsg = meta?.optString("message", "").orEmpty().trim()

                        val loginSession = json.optJSONObject("loginSession")
                        val loginUser = json.optJSONObject("loginUser")
                        val tokenSid = listOf(
                            loginSession?.optString("sessionId", "").orEmpty(),
                            loginUser?.optString("sessionId", "").orEmpty(),
                            json.optString("sessionId", "")
                        ).map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()

                        if (tokenSid.isNotBlank()) {
                            matchedSid = tokenSid
                            if (redirectedDomain.isNotBlank()) {
                                activeHost = if (redirectedDomain.startsWith("http", ignoreCase = true)) {
                                    redirectedDomain
                                } else {
                                    "https://$redirectedDomain"
                                }
                            }
                            break
                        }
                    }

                    if (matchedSid.isNotBlank()) {
                        sessionId = matchedSid
                        workingHost = activeHost
                        break
                    } else {
                        if (cleanSmsCode.isBlank() &&
                            (lastMetaCode == 6002 || lastMetaCode == 1061 || lastMetaCode == 1101)
                        ) {
                            triggerEzvizOtpDispatch(
                                hostUrl = activeHost,
                                cleanEmail = cleanEmail,
                                hashedPass = hashedPass,
                                featureCode = featureCode
                            )
                        }

                        val isChineseMsg = lastMetaMsg.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
                        lastErr = when {
                            lastMetaCode == 1015 ->
                                "Google Account Detected (Code 1015): Set an EZVIZ password for your Gmail in the EZVIZ phone app (Profile -> Change Password or Forgot Password), then sign in here."
                            lastMetaCode == 1012 || lastMetaCode == 1013 ->
                                "Incorrect EZVIZ email or password."
                            lastMetaCode == 1014 ->
                                "EZVIZ account is temporarily locked. Please wait a few minutes and try again."
                            lastMetaCode == 20022 || lastMetaCode == 20021 || lastMetaCode == 20018 ->
                                "OWNERSHIP_CONFLICT: Device is bound to another user account. Reset the device to factory defaults."
                            lastMetaCode == 6002 || lastMetaCode == 1061 || lastMetaCode == 1101 ||
                                    lastMetaMsg.contains("verification", ignoreCase = true) ->
                                "EZVIZ_OTP_REQUIRED: Terminal Binding (Code $lastMetaCode) is active. Clara has requested a verification code to your email/SMS \u2014 enter it in the 'One-Time Email/SMS Code' box below, or turn off 'Terminal Binding' in the EZVIZ phone app under Account Security Settings."
                            lastMetaMsg.isNotBlank() && !isChineseMsg -> lastMetaMsg
                            else -> "Could not sign in to EZVIZ (Code $lastMetaCode). Please verify your email and password."
                        }
                        if (lastMetaCode in listOf(1012, 1013, 1014, 1015, 6002, 1061, 1101)) {
                            break
                        }
                    }
                } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
                    lastErr = "Could not reach EZVIZ service. Please check your internet connection."
}
            }
        }

        if (sessionId.isBlank()) {
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.FAILED,
                transportSource = "EZVIZ_CLOUD",
                vendor = "EZVIZ Consumer Cloud",
                qualificationStage = "loginAndDiscoverWithConsumerAccount.auth",
                outcomeReason = lastErr
            )
            JarvisLiveBridge.recordCommandLatency(
                integrationKey = "CAMERAS_RTSP",
                displayName = "Imou / EZVIZ Cameras & DVR",
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(15L),
                succeeded = false,
                tokenValid = false
            )
            return@withContext Result.failure(Exception(lastErr))
        }

        cachedSessionId = sessionId
        cachedConsumerRegionHost = workingHost.trimEnd('/')
        savePersistedConsumerSession(context, sessionId, cachedConsumerRegionHost)
        saveEzvizConsumerCredentials(context, cleanEmail, cleanPass, getSavedEzvizVerifyCode(context))

        try {
            val filter = "CLOUD,CONNECTION,SWITCH,STATUS,WIFI,P2P,CHANNEL,FEATURE,VIDEO_QUALITY,KMS"
            val pagelistUrl = "$cachedConsumerRegionHost/v3/userdevices/v1/resources/pagelist" +
                    "?groupId=-1&limit=30&offset=0&filter=${URLEncoder.encode(filter, "UTF-8")}"
            val res = runCatching {
                NetworkModule.jsonService.get(
                    pagelistUrl,
                    mapOf(
                        "User-Agent" to "okhttp/3.12.1",
                        "sessionId" to sessionId,
                        "featureCode" to featureCode,
                        "clientType" to "3",
                        "osVersion" to "12",
                        "clientVersion" to "5.9.8.0215",
                        "netType" to "WIFI",
                        "customno" to "1000001",
                        "clientNo" to "google",
                        "appId" to "ys7"
                    )
                )
            }.getOrNull()

            val body = res?.string() ?: ""
            val code = if (res != null) 200 else 500

            if (res == null || body.isBlank()) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "EZVIZ_CLOUD",
                    vendor = "EZVIZ Consumer Cloud",
                    qualificationStage = "loginAndDiscoverWithConsumerAccount.pagelist",
                    outcomeReason = "EZVIZ v3 pagelist returned HTTP $code (empty=${body.isBlank()})"
                )
                return@withContext Result.failure(Exception("Could not load your EZVIZ devices right now."))
            }

            val root = JSONObject(body)
            val deviceInfos = root.optJSONArray("deviceInfos") ?: JSONArray()
            val wifiObj = root.optJSONObject("WIFI") ?: JSONObject()
            val connObj = root.optJSONObject("CONNECTION") ?: JSONObject()
            val statusObj = root.optJSONObject("STATUS") ?: JSONObject()
            val kmsObj = root.optJSONObject("KMS") ?: JSONObject()
            val switchObj = root.optJSONObject("SWITCH") ?: JSONObject()

            val results = mutableListOf<EzvizDiscoveredDeviceDetail>()
            for (i in 0 until deviceInfos.length()) {
                val dev = deviceInfos.optJSONObject(i) ?: continue
                val serial = dev.optString("deviceSerial", "").trim()
                val name = dev.optString("name", "EZVIZ Doorbell").trim()
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                    transportSource = "EZVIZ_CLOUD",
                    hardwareId = serial,
                    vendor = name,
                    confidenceScore = 0.80f,
                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                    outcomeReason = "EZVIZ v3 pagelist device entry received",
                    cloudId = serial
                )
                if (serial.isBlank()) {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FILTERED,
                        transportSource = "EZVIZ_CLOUD",
                        vendor = name,
                        qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                        outcomeReason = "Filtered EZVIZ v3 pagelist entry missing deviceSerial"
                    )
                    continue
                }
                val isOnline = dev.optInt("status", 1) == 1

                val devWifi = wifiObj.optJSONObject(serial)
                val devConn = connObj.optJSONObject(serial)
                val devStatus = statusObj.optJSONObject(serial)

                val localIp = devConn?.optString("localIp", "")?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                    ?: devWifi?.optString("address", "")?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                    ?: ""
                val devMac = CrossEcosystemIdentityCorrelator.normalizeMac(
                    devWifi?.optString("mac", "").orEmpty().ifBlank { dev.optString("mac", "") }
                )
                if (localIp.isNotBlank() && devMac.isNotBlank()) {
                    CrossEcosystemIdentityCorrelator.recordApplicationLayerObservation(localIp, devMac)
                }
                val rtspPort = devConn?.optInt("localRtspPort", 554)?.takeIf { it > 0 } ?: 554
                val isSleeping = devStatus?.optJSONObject("optionals")?.optInt("SleepStatus", 0) == 1

                val (extractedPinRaw, isEncrypted) = extractPinFromEzvizJson(
                    serial = serial,
                    devObj = dev,
                    statusObj = statusObj,
                    kmsObj = kmsObj,
                    switchObj = switchObj
                )
                val resolvedPin = extractedPinRaw
                    .ifBlank { queryDevicePinFallback(serial, sessionId) }
                    .ifBlank { getSavedEzvizPinForSerial(context, serial) }

                if (resolvedPin.isNotBlank()) {
                    saveEzvizPinForSerial(context, serial, resolvedPin)
                }

                Log.d(
                    TAG,
                    "Discovered EZVIZ [$name / $serial] localIp=$localIp rtspPort=$rtspPort online=$isOnline sleeping=$isSleeping pinExtracted=${resolvedPin.isNotBlank()} encrypted=$isEncrypted"
                )
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.IDENTIFIED,
                    transportSource = "EZVIZ_CLOUD",
                    hardwareId = serial,
                    macAddress = devMac,
                    ipAddress = localIp,
                    port = rtspPort,
                    vendor = name,
                    confidenceScore = 0.96f,
                    qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                    outcomeReason = "Verified EZVIZ camera/doorbell (online=$isOnline sleeping=$isSleeping pinPresent=${resolvedPin.isNotBlank()})",
                    cloudId = serial
                )

                val detail = EzvizDiscoveredDeviceDetail(
                    serial = serial,
                    name = name,
                    localIp = localIp,
                    localRtspPort = rtspPort,
                    isOnline = isOnline,
                    isSleeping = isSleeping,
                    verificationCode = resolvedPin,
                    isEncrypted = isEncrypted
                )
                results.add(detail)
                discoveredConsumerDevices[serial] = detail
            }
            autoSyncDiscoveredEzvizDetailsToSavedTiles(context, results)
            JarvisLiveBridge.recordCommandLatency(
                integrationKey = "CAMERAS_RTSP",
                displayName = "Imou / EZVIZ Cameras & DVR",
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(12L),
                succeeded = true,
                tokenValid = true
            )
            Result.success(results)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.FAILED,
                transportSource = "EZVIZ_CLOUD",
                vendor = "EZVIZ Consumer Cloud",
                qualificationStage = "loginAndDiscoverWithConsumerAccount",
                outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "Could not retrieve EZVIZ device list"}"
            )
            Result.failure(Exception("Could not retrieve your EZVIZ device list."))
        }
    }

    private suspend fun ensureConsumerSession(
        context: Context?,
        forceReauth: Boolean = false
    ): String {
        val ctx = resolveAppContext(context) ?: return cachedSessionId
        if (forceReauth) {
            clearPersistedConsumerSession(ctx)
        }
        if (cachedSessionId.isNotBlank()) return cachedSessionId
        loadPersistedConsumerSession(ctx)?.let { (savedSid, savedHost) ->
            cachedSessionId = savedSid
            cachedConsumerRegionHost = savedHost
            return savedSid
        }
        val email = getSavedEzvizEmail(ctx)
        val pass = getSavedEzvizPassword(ctx)
        if (email.isNotBlank() && pass.isNotBlank()) {
            loginAndDiscoverWithConsumerAccount(ctx, email, pass)
        }
        return cachedSessionId
    }

    private fun wakeConsumerDevice(serialNumber: String, sessionId: String) {
        if (serialNumber.isBlank() || sessionId.isBlank()) return
        val candidateWakeUrls = listOf(
            "$cachedConsumerRegionHost/v3/devconfig/v1/wakeup?deviceSerial=${URLEncoder.encode(serialNumber, "UTF-8")}",
            "$cachedConsumerRegionHost/v3/userdevices/v1/wakeup?deviceSerial=${URLEncoder.encode(serialNumber, "UTF-8")}"
        )
        for (urlStr in candidateWakeUrls) {
            try {
                val res = runCatching {
                    kotlinx.coroutines.runBlocking {
                        NetworkModule.jsonService.postJson(
                            urlStr,
                            "".toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                            mapOf(
                                "sessionId" to sessionId,
                                "clientType" to "13"
                            )
                        )
                    }
                }.getOrNull()
                if (res != null) break
            } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
        }
    }

    private fun extractLiveStreamUrlFromJson(json: JSONObject): String? {
        val dataObj = json.optJSONObject("data")
        val dataArr = json.optJSONArray("data")
        val candidates = listOf(
            dataObj?.optString("url", "").orEmpty(),
            dataObj?.optString("liveAddress", "").orEmpty(),
            dataObj?.optString("hdAddress", "").orEmpty(),
            dataObj?.optString("hls", "").orEmpty(),
            dataObj?.optString("hlsHd", "").orEmpty(),
            dataArr?.optJSONObject(0)?.optString("url", "").orEmpty(),
            dataArr?.optJSONObject(0)?.optString("liveAddress", "").orEmpty(),
            dataArr?.optJSONObject(0)?.optString("hdAddress", "").orEmpty(),
            dataArr?.optJSONObject(0)?.optString("hls", "").orEmpty(),
            json.optString("url", ""),
            json.optString("liveAddress", "")
        )
        return candidates
            .map { it.trim() }
            .firstOrNull { it.startsWith("http", ignoreCase = true) || it.startsWith("rtsp", ignoreCase = true) }
    }

    private fun fetchConsumerCloudHlsStream(
        serialNumber: String,
        channelNo: String,
        verificationCode: String,
        quality: String,
        sessionId: String
    ): String? {
        if (serialNumber.isBlank() || sessionId.isBlank()) return null

        val lappHosts = listOf(
            cachedConsumerRegionHost,
            cachedConsumerRegionHost.replace("https://apii", "https://i")
                .replace(".ezvizlife.com", "open.ezvizlife.com"),
            "https://ieuopen.ezvizlife.com",
            "https://isgpopen.ezvizlife.com",
            DEFAULT_EZVIZ_API_HOST
        ).distinct()

        val lappPaths = listOf(
            "/api/lapp/v2/live/address/get",
            "/api/lapp/live/address/get"
        )

        for (protocolCode in listOf("2", "4")) {
            for (host in lappHosts) {
                for (path in lappPaths) {
                    try {
                        val params = mutableMapOf(
                            "accessToken" to sessionId,
                            "deviceSerial" to serialNumber,
                            "channelNo" to channelNo,
                            "protocol" to protocolCode,
                            "quality" to quality,
                            "expireTime" to "86400",
                            "type" to "1",
                            "supportH265" to "0"
                        )
                        if (verificationCode.isNotBlank()) {
                            params["code"] = verificationCode
                            params["validateCode"] = verificationCode
                        }
                        val formBody = params.entries.joinToString("&") { (k, v) ->
                            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                        }
                        val res = runCatching {
                            kotlinx.coroutines.runBlocking {
                                NetworkModule.jsonService.postJson(
                                    "${host.trimEnd('/')}$path",
                                    formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                                    mapOf(
                                        "sessionId" to sessionId,
                                        "clientType" to "13"
                                    )
                                )
                            }
                        }.getOrNull()
                        val respText = res?.string() ?: ""
                        if (respText.isNotBlank()) {
                            val json = runCatching { JSONObject(respText) }.getOrNull() ?: continue
                            val url = extractLiveStreamUrlFromJson(json)
                            if (!url.isNullOrBlank()) {
                                return url
                            }
                        }
                    } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
                }
            }
        }

        val v3CandidatePaths = listOf(
            "/v3/userdevices/v1/live/address",
            "/v3/streaming/v1/live/address"
        )
        for (v3Path in v3CandidatePaths) {
            try {
                val v3Url = "$cachedConsumerRegionHost$v3Path" +
                        "?deviceSerial=${URLEncoder.encode(serialNumber, "UTF-8")}" +
                        "&channelNo=${URLEncoder.encode(channelNo, "UTF-8")}" +
                        "&protocol=2&quality=${URLEncoder.encode(quality, "UTF-8")}&supportH265=0" +
                        (if (verificationCode.isNotBlank()) "&code=${URLEncoder.encode(verificationCode, "UTF-8")}" else "")

                val res = runCatching {
                    kotlinx.coroutines.runBlocking {
                        NetworkModule.jsonService.get(v3Url, mapOf("sessionId" to sessionId, "clientType" to "13"))
                    }
                }.getOrNull()
                val body = res?.string() ?: ""
                if (res != null) {
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    if (json != null) {
                        val url = extractLiveStreamUrlFromJson(json)
                        if (!url.isNullOrBlank()) {
                            return url
                        }
                    }
                }
            } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
        }
        return null
    }

    private fun postForm(urlStr: String, params: Map<String, String>): JSONObject? {
        if (JarvisLiveBridge.isCircuitOpen("EZVIZ_CLOUD")) {
            throw CircuitBreakerOpenException("Cloud Degraded \u2014 Running Locally")
        }
        val formBody = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val res = runCatching {
            kotlinx.coroutines.runBlocking {
                NetworkModule.jsonService.postJson(
                    urlStr,
                    formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                    emptyMap()
                )
            }
        }.getOrNull()
        val responseText = res?.string()
        return if (!responseText.isNullOrBlank()) runCatching { JSONObject(responseText) }.getOrNull() else null
    }

    suspend fun getAccessToken(
        host: String,
        appKey: String,
        appSecret: String
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val appCtx = resolveAppContext()
        getSavedOAuthSession(appCtx, allowNetworkRefresh = true)?.let { oauthPair ->
            return@withContext Result.success(oauthPair)
        }
        val cleanKey = appKey.trim()
        val cleanSecret = appSecret.trim()
        if (cleanKey.isBlank() || cleanSecret.isBlank()) {
            val consumerSid = ensureConsumerSession(appCtx)
            if (consumerSid.isNotBlank()) {
                return@withContext Result.success(consumerSid to cachedConsumerRegionHost)
            }
            return@withContext Result.failure(Exception("Sign in to your EZVIZ account to connect."))
        }
        if (cachedToken.isNotBlank() && cachedAppKey == cleanKey && System.currentTimeMillis() < tokenExpiryMs) {
            return@withContext Result.success(cachedToken to cachedAreaDomain)
        }

        val candidateHosts = listOf(
            host.trim().ifBlank { DEFAULT_EZVIZ_API_HOST },
            "https://ieuopen.ezvizlife.com",
            "https://isgpopen.ezvizlife.com",
            "https://open.ezvizlife.com"
        ).distinct()

        var lastError = "Could not connect to EZVIZ Cloud."

        for (candidate in candidateHosts) {
            try {
                val cleanHost = candidate.trimEnd('/')
                val resp = postForm(
                    "$cleanHost/api/lapp/token/get",
                    mapOf("appKey" to cleanKey, "appSecret" to cleanSecret)
                ) ?: continue

                val code = resp.optString("code")
                if (code == "200") {
                    val data = resp.optJSONObject("data")
                    val token = data?.optString("accessToken", "") ?: ""
                    val area = data?.optString("areaDomain", cleanHost)?.ifBlank { cleanHost } ?: cleanHost
                    val expireTime = data?.optLong("expireTime", System.currentTimeMillis() + 86400_000L)
                        ?: (System.currentTimeMillis() + 86400_000L)
                    if (token.isNotBlank()) {
                        cachedToken = token
                        cachedAreaDomain = area.trimEnd('/')
                        cachedAppKey = cleanKey
                        tokenExpiryMs = expireTime - 300_000L
                        return@withContext Result.success(token to cachedAreaDomain)
                    }
                } else {
                    lastError = "Could not authenticate with EZVIZ Cloud."
                }
            } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
                lastError = "EZVIZ Cloud is temporarily unreachable."
}
        }
        Result.failure(Exception(lastError))
    }

    /**
     * Discovers bound EZVIZ doorbells and cameras using either the user's standard consumer
     * EZVIZ account (zero developer keys required) or OpenAPI/OAuth credentials if configured.
     */
    suspend fun discoverBoundDoorbells(
        bridge: BridgeSettings
    ): Result<List<DiscoveredCamera>> = withContext(Dispatchers.IO) {
        val combined = LinkedHashMap<String, DiscoveredCamera>()

        // 1. Try consumer account discovery first if saved credentials exist
        val appCtx = resolveAppContext()
        if (appCtx != null) {
            val savedEmail = getSavedEzvizEmail(appCtx)
            val savedPass = getSavedEzvizPassword(appCtx)
            if (savedEmail.isNotBlank() && savedPass.isNotBlank()) {
                val consumerRes = loginAndDiscoverWithConsumerAccount(appCtx, savedEmail, savedPass)
                if (consumerRes.isSuccess) {
                    for (dev in consumerRes.getOrThrow()) {
                        combined[dev.serial] = DiscoveredCamera(
                            deviceId = dev.serial,
                            name = dev.name,
                            status = if (dev.isOnline) "online" else "standby",
                            channelId = "1"
                        )
                    }
                }
            }
        }

        // 2. Also include OpenAPI / OAuth devices if keys or OAuth token are present
        if ((bridge.ezvizAppKey.isNotBlank() && bridge.ezvizAppSecret.isNotBlank()) ||
            getSavedOAuthSession(appCtx, allowNetworkRefresh = true) != null
        ) {
            val authRes = getAccessToken(bridge.ezvizApiHost, bridge.ezvizAppKey, bridge.ezvizAppSecret)
            if (authRes.isFailure) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "EZVIZ_OPENAPI",
                    vendor = "EZVIZ OpenAPI",
                    qualificationStage = "discoverBoundDoorbells.token",
                    outcomeReason = authRes.exceptionOrNull()?.message ?: "EZVIZ OpenAPI token failure"
                )
            }
            if (authRes.isSuccess) {
                val (token, areaDomain) = authRes.getOrThrow()
                try {
                    val resp = postForm(
                        "$areaDomain/api/lapp/device/list",
                        mapOf("accessToken" to token, "pageStart" to "0", "pageSize" to "50")
                    )
                    if (resp?.optString("code") == "200") {
                        val dataArr = resp.optJSONArray("data") ?: JSONArray()
                        for (i in 0 until dataArr.length()) {
                            val d = dataArr.optJSONObject(i) ?: continue
                            val serial = d.optString("deviceSerial", "").trim()
                            val name = d.optString("deviceName", "EZVIZ Doorbell").trim()
                            val statusInt = d.optInt("status", 1)
                            val statusStr = if (statusInt == 1) "online" else "standby"
                            val pin = d.optString("validateCode", d.optString("verifyCode", "")).trim()
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                                transportSource = "EZVIZ_OPENAPI",
                                hardwareId = serial,
                                vendor = name,
                                confidenceScore = 0.80f,
                                qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                outcomeReason = "EZVIZ OpenAPI /api/lapp/device/list entry received",
                                cloudId = serial
                            )
                            if (serial.isNotBlank()) {
                                if (pin.isNotBlank() && appCtx != null) {
                                    saveEzvizPinForSerial(appCtx, serial, pin)
                                }
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.IDENTIFIED,
                                    transportSource = "EZVIZ_OPENAPI",
                                    hardwareId = serial,
                                    vendor = name,
                                    confidenceScore = 0.96f,
                                    qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                                    outcomeReason = "Verified EZVIZ OpenAPI camera (status=$statusStr)",
                                    cloudId = serial
                                )
                                combined[serial] = DiscoveredCamera(
                                    deviceId = serial,
                                    name = name,
                                    status = statusStr,
                                    channelId = "1"
                                )
                            } else {
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.FILTERED,
                                    transportSource = "EZVIZ_OPENAPI",
                                    vendor = name,
                                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                    outcomeReason = "Filtered EZVIZ OpenAPI entry missing deviceSerial"
                                )
                            }
                        }
                    } else {
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.FAILED,
                            transportSource = "EZVIZ_OPENAPI",
                            vendor = "EZVIZ OpenAPI",
                            qualificationStage = "discoverBoundDoorbells.list",
                            outcomeReason = "EZVIZ /api/lapp/device/list returned code=${resp?.optString("code", "null")}"
                        )
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FAILED,
                        transportSource = "EZVIZ_OPENAPI",
                        vendor = "EZVIZ OpenAPI",
                        qualificationStage = "discoverBoundDoorbells",
                        outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "EZVIZ OpenAPI discovery failed"}"
                    )
                }
            }
        }

        if (combined.isNotEmpty()) {
            return@withContext Result.success(combined.values.toList())
        }
        Result.failure(Exception("Sign in with your EZVIZ account to import your doorbells and cameras."))
    }

    /**
     * Resolves an EZVIZ live stream URL automatically using the user's consumer account session,
     * auto-extracted Verification Code (PIN), Cloud HLS (`.m3u8`), or local RTSP (`/Streaming/Channels/101` & `102`).
     */
    suspend fun fetchHlsStream(
        bridge: BridgeSettings,
        serialNumber: String,
        channelNo: String = "1",
        verificationCode: String = "",
        preferSdSubStream: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        val appCtx = resolveAppContext()
        var sessionId = ensureConsumerSession(appCtx)
        var sn = serialNumber.trim()

        // 1. Auto-discover serial number if missing
        if (sn.isBlank()) {
            sn = discoveredConsumerDevices.keys.firstOrNull().orEmpty()
            if (sn.isBlank()) {
                val disc = discoverBoundDoorbells(bridge).getOrNull()
                sn = disc?.firstOrNull()?.deviceId.orEmpty()
            }
        }

        val cachedDetail = if (sn.isNotBlank()) {
            discoveredConsumerDevices[sn]
        } else {
            discoveredConsumerDevices.values.firstOrNull()
        }

        // 2. Automatically resolve the 6-letter Verification Code (PIN)
        val effectiveCode = verificationCode.trim()
            .ifBlank { cachedDetail?.verificationCode.orEmpty() }
            .ifBlank { if (appCtx != null && sn.isNotBlank()) getSavedEzvizPinForSerial(appCtx, sn) else "" }
            .ifBlank { if (appCtx != null) getSavedEzvizVerifyCode(appCtx) else "" }
            .ifBlank {
                if (sn.isNotBlank() && sessionId.isNotBlank()) {
                    queryDevicePinFallback(sn, sessionId).also { fetched ->
                        if (fetched.isNotBlank() && appCtx != null) {
                            saveEzvizPinForSerial(appCtx, sn, fetched)
                        }
                    }
                } else ""
            }

        val quality = if (preferSdSubStream) "2" else "1"
        val chan = channelNo.ifBlank { "1" }.let { if (it == "0") "1" else it }

        if (sn.isNotBlank()) {
            val cacheKey = "${sn}_${chan}_$quality"
            ezvizHlsCache[cacheKey]?.let { cached ->
                if (cached.url.isNotBlank() && (System.currentTimeMillis() - cached.timestampMs) < HLS_CACHE_TTL_MS) {
                    return@withContext Result.success(cached.url)
                } else {
                    ezvizHlsCache.remove(cacheKey)
                }
            }

            // 3. Wake sleeping doorbell via consumer session if needed
            if (sessionId.isNotBlank() && (cachedDetail == null || cachedDetail.isSleeping)) {
                wakeConsumerDevice(sn, sessionId)
            }

            // 4. Try Consumer Session Cloud HLS Stream Resolution (with automatic session re-auth on expiry)
            if (sessionId.isNotBlank()) {
                var consumerCloudUrl = fetchConsumerCloudHlsStream(
                    serialNumber = sn,
                    channelNo = chan,
                    verificationCode = effectiveCode,
                    quality = quality,
                    sessionId = sessionId
                )
                if (consumerCloudUrl.isNullOrBlank()) {
                    sessionId = ensureConsumerSession(appCtx, forceReauth = true)
                    if (sessionId.isNotBlank()) {
                        consumerCloudUrl = fetchConsumerCloudHlsStream(
                            serialNumber = sn,
                            channelNo = chan,
                            verificationCode = effectiveCode,
                            quality = quality,
                            sessionId = sessionId
                        )
                    }
                }
                if (!consumerCloudUrl.isNullOrBlank()) {
                    ezvizHlsCache[cacheKey] = CachedEzvizStream(consumerCloudUrl)
                    return@withContext Result.success(consumerCloudUrl)
                }
            }

            // 5. Try OpenAPI / OAuth Cloud HLS if developer keys or OAuth token are present
            if ((bridge.ezvizAppKey.isNotBlank() && bridge.ezvizAppSecret.isNotBlank()) ||
                getSavedOAuthSession(appCtx, allowNetworkRefresh = true) != null
            ) {
                val authRes = getAccessToken(bridge.ezvizApiHost, bridge.ezvizAppKey, bridge.ezvizAppSecret)
                if (authRes.isSuccess) {
                    val (token, areaDomain) = authRes.getOrThrow()
                    for (endpoint in listOf("/api/lapp/v2/live/address/get", "/api/lapp/live/address/get")) {
                        try {
                            val params = mutableMapOf(
                                "accessToken" to token,
                                "deviceSerial" to sn,
                                "channelNo" to chan,
                                "protocol" to "2",
                                "quality" to quality,
                                "expireTime" to "86400",
                                "supportH265" to "0"
                            )
                            if (effectiveCode.isNotBlank()) {
                                params["code"] = effectiveCode
                                params["validateCode"] = effectiveCode
                            }
                            val resp = postForm("$areaDomain$endpoint", params)
                            val code = resp?.optString("code") ?: ""
                            if (code == "200" && resp != null) {
                                val url = extractLiveStreamUrlFromJson(resp)
                                if (!url.isNullOrBlank()) {
                                    ezvizHlsCache[cacheKey] = CachedEzvizStream(url)
                                    return@withContext Result.success(url)
                                }
                            }
                        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
                    }
                }
            }
        }

        // 6. Automatic Local RTSP Stream Resolution (with wake retry & auto-extracted PIN)
        if (appCtx != null && cachedDetail != null && cachedDetail.localIp.isNotBlank()) {
            val port = if (cachedDetail.localRtspPort > 0) cachedDetail.localRtspPort else 554
            var isOpen = EzvizLanWakeScanner.isTcpOpen(cachedDetail.localIp, port, 750)
            if (!isOpen && sessionId.isNotBlank()) {
                wakeConsumerDevice(cachedDetail.serial, sessionId)
                delay(850L)
                isOpen = EzvizLanWakeScanner.isTcpOpen(cachedDetail.localIp, port, 900)
            }
            if (isOpen && (effectiveCode.isNotBlank() || !cachedDetail.isEncrypted)) {
                val candidates = buildLocalRtspCandidateUrls(
                    ipAddress = cachedDetail.localIp,
                    port = port,
                    verificationCode = effectiveCode,
                    preferSubStream = preferSdSubStream
                )
                val firstRtsp = candidates.firstOrNull().orEmpty()
                if (firstRtsp.isNotBlank()) {
                    return@withContext Result.success(firstRtsp)
                }
            }
        }

        Result.failure(
            Exception("Doorbell is waking from standby. Tap Retry in a moment to start live video.")
        )
    }
}
