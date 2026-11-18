package com.jarvis.smarthomelauncher.integrations

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class DiscoveredSonoffDevice(
    val deviceId: String,
    val name: String,
    val model: String = "Sonoff Smart Switch",
    val ipAddress: String,
    val port: Int = 8081,
    val deviceKey: String = "",
    val isEncrypted: Boolean = false,
    val isActive: Boolean = false,
    val isSocket2Active: Boolean = false,
    val multiGangCount: Int = 1,
    val socket1Name: String = "Outlet 1",
    val socket2Name: String = "Outlet 2",
    val powerWatts: Float = 0f,
    val voltageVolts: Float = 0f,
    val currentMilliamps: Float = 0f,
    val suggestedType: TileType = TileType.SONOFF_SWITCH,
    val requiresAuth: Boolean = false
) {
    val diyMode: Boolean
        get() = !isEncrypted

    val actionType: DiscoveredDeviceActionType
        get() = if (requiresAuth && deviceKey.isBlank()) {
            DiscoveredDeviceActionType.OAUTH_SIGN_IN
        } else {
            DiscoveredDeviceActionType.ADD_DIRECT
        }
}

// ===========================================================================
// 3. EWELINK / SONOFF NATIVE ENGINE (LOCAL _ewelink._tcp. mDNS + PORT 8081
//    AES-128-CBC LAN + DIY MODE + EWELINK CLOUD V2 OAUTH & AUTO-KEY EXTRACTOR)
// ===========================================================================
object EWeLinkSonoffClient {
    private const val TAG = "ClaraSonoff"
    private const val PREFS_EWELINK = "clara_ewelink_sonoff_prefs_v2"
    private const val SONOFF_LAN_PORT = 8081
    private const val MDNS_SERVICE_TYPE = "_ewelink._tcp."

    private val discoveredSonoffCache = ConcurrentHashMap<String, DiscoveredSonoffDevice>()
    private val missedPollCountByTileId = ConcurrentHashMap<String, Int>()

    @Volatile
    private var cachedAccessToken: String = ""

    @Volatile
    private var cachedRefreshToken: String = ""

    @Volatile
    private var cachedRegionHost: String = "https://eu-apia.coolkit.cc"

    @Volatile
    private var tokenExpiryEpochMs: Long = 0L

    private fun resolveAppContext(explicitContext: Context? = null): Context? =
        ClaraApplication.appContextOrNull(explicitContext)

    // -----------------------------------------------------------------------
    // 1. ENCRYPTED CREDENTIALS, TOKENS & PER-DEVICE KEY VAULT (ClaraSecretVault)
    // -----------------------------------------------------------------------
    private fun vaultKey(key: String): String = "${PREFS_EWELINK}::$key"

    private fun readAndMigrateSecret(context: Context, key: String): String {
        val appCtx = context.applicationContext
        val vKey = vaultKey(key)
        val vaultRaw = com.jarvis.smarthomelauncher.core.ClaraSecretVault.getString(appCtx, vKey).orEmpty()
        val prefs = appCtx.getSharedPreferences(PREFS_EWELINK, Context.MODE_PRIVATE)
        if (vaultRaw.isNotBlank()) {
            val unwrapped = vaultRaw.trim()
            if (prefs.contains(key)) {
                prefs.edit().remove(key).apply()
            }
            return unwrapped
        }
        val legacyRaw = prefs.getString(key, "").orEmpty()
        if (legacyRaw.isBlank()) return ""
        val migrated = legacyRaw.trim()
        if (migrated.isNotBlank()) {
            com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(appCtx, vKey, migrated)
            prefs.edit().remove(key).apply()
        }
        return migrated
    }

    private fun writeSecretToVault(context: Context, key: String, plainValue: String) {
        val appCtx = context.applicationContext
        val clean = plainValue.trim()
        if (clean.isBlank()) {
            com.jarvis.smarthomelauncher.core.ClaraSecretVault.remove(appCtx, vaultKey(key))
        } else {
            com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(appCtx, vaultKey(key), clean)
        }
        appCtx.getSharedPreferences(PREFS_EWELINK, Context.MODE_PRIVATE).edit().remove(key).apply()
    }

    fun saveEWeLinkCredentials(context: Context, email: String, password: String) {
        val appCtx = context.applicationContext
        appCtx.getSharedPreferences(PREFS_EWELINK, Context.MODE_PRIVATE).edit()
            .remove("ewelink_email")
            .remove("ewelink_password")
            .apply()
        writeSecretToVault(appCtx, "ewelink_email", email.trim())
        writeSecretToVault(appCtx, "ewelink_password", password)
    }

    fun getSavedEWeLinkEmail(context: Context): String =
        readAndMigrateSecret(context, "ewelink_email")

    fun getSavedEWeLinkPassword(context: Context): String =
        readAndMigrateSecret(context, "ewelink_password")

    fun saveDeviceKey(context: Context, deviceId: String, deviceKey: String) {
        val cleanId = deviceId.trim().lowercase(Locale.US)
        val cleanKey = deviceKey.trim()
        if (cleanId.isBlank() || cleanKey.isBlank()) return
        writeSecretToVault(context, "dev_key_$cleanId", cleanKey)
    }

    fun getSavedDeviceKey(context: Context?, deviceId: String): String {
        val cleanId = deviceId.trim().lowercase(Locale.US)
        if (cleanId.isBlank()) return ""
        val ctx = resolveAppContext(context) ?: return ""
        return readAndMigrateSecret(ctx, "dev_key_$cleanId")
    }

    private fun resolveKnownSonoffIp(deviceId: String): String {
        val cleanId = deviceId.trim()
        if (cleanId.isBlank()) return ""
        val ctx = resolveAppContext(null) ?: return ""
        return com.jarvis.smarthomelauncher.core.ClaraDeviceRegistryStore.getInstance(ctx)
            .resolveEndpointForDevice(cleanId)
            ?.hostIp
            .orEmpty()
    }

    private fun persistSonoffLanIp(deviceId: String, hostIp: String) {
        val cleanId = deviceId.trim()
        val cleanIp = hostIp.substringBefore(":").trim()
        if (cleanId.isBlank() || cleanIp.isBlank()) return
        val ctx = resolveAppContext(null) ?: return
        val store = com.jarvis.smarthomelauncher.core.ClaraDeviceRegistryStore.getInstance(ctx)
        val existing = store.resolveEndpointForDevice(cleanId)
        if (existing != null) {
            store.updateDeviceEndpointAndMac(
                canonicalGuid = existing.canonicalGuid,
                newHostIp = cleanIp,
                newPort = if (existing.port > 0) existing.port else 8081
            )
        }
    }

    private fun saveCloudSessionTokens(
        context: Context,
        accessToken: String,
        refreshToken: String,
        regionHost: String,
        expiresInMs: Long = 25 * 24 * 3600_000L
    ) {
        val cleanAt = accessToken.trim()
        if (cleanAt.isBlank()) return
        val cleanRt = refreshToken.trim()
        val cleanHost = regionHost.trim().trimEnd('/').ifBlank { "https://eu-apia.coolkit.cc" }
        val expiry = System.currentTimeMillis() + expiresInMs
        cachedAccessToken = cleanAt
        if (cleanRt.isNotBlank()) cachedRefreshToken = cleanRt
        cachedRegionHost = cleanHost
        tokenExpiryEpochMs = expiry

        writeSecretToVault(context, "ewelink_oauth_at", cleanAt)
        if (cleanRt.isNotBlank()) {
            writeSecretToVault(context, "ewelink_oauth_rt", cleanRt)
        }
        context.applicationContext.getSharedPreferences(PREFS_EWELINK, Context.MODE_PRIVATE).edit().apply {
            remove("ewelink_oauth_at")
            remove("ewelink_oauth_rt")
            putString("ewelink_region_host", cleanHost)
            putLong("ewelink_token_expiry_ms", expiry)
            apply()
        }
    }

    private fun loadPersistedCloudSession(context: Context): Pair<String, String>? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_EWELINK, Context.MODE_PRIVATE)
        val at = readAndMigrateSecret(context, "ewelink_oauth_at")
        val rt = readAndMigrateSecret(context, "ewelink_oauth_rt")
        val host = prefs.getString("ewelink_region_host", "https://eu-apia.coolkit.cc")
            .orEmpty()
            .ifBlank { "https://eu-apia.coolkit.cc" }
        val expiry = prefs.getLong("ewelink_token_expiry_ms", 0L)
        if (rt.isNotBlank()) cachedRefreshToken = rt
        cachedRegionHost = host
        if (at.isNotBlank() && System.currentTimeMillis() < expiry - 300_000L) {
            cachedAccessToken = at
            tokenExpiryEpochMs = expiry
            return at to host
        }
        return null
    }

    /**
     * CRIT-03 Fix: Resolves eWeLink Cloud AppId & AppSecret from AndroidManifest meta-data or
     * ClaraSecretVault instead of embedding a hardcoded plaintext secret in source.
     */
    private fun resolveEWeLinkAppCredentials(context: Context?): Pair<String, String> {
        val ctx = resolveAppContext(context) ?: return "" to ""
        val prefs = ctx.getSharedPreferences(PREFS_EWELINK, Context.MODE_PRIVATE)
        val savedAppId = prefs.getString("ewelink_custom_app_id", "")?.trim().orEmpty()
        val savedSecret = readAndMigrateSecret(ctx, "ewelink_custom_app_secret")
        if (savedAppId.isNotBlank() && savedSecret.isNotBlank()) {
            return savedAppId to savedSecret
        }
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.getApplicationInfo(
                    ctx.packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
            }
            val meta = appInfo.metaData
            val appId = meta?.getString("EWELINK_APP_ID")?.trim().orEmpty()
            val appSecret = meta?.getString("EWELINK_APP_SECRET")?.trim().orEmpty()
            appId to appSecret
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
            "" to ""
}
    }

    fun saveCustomEWeLinkAppCredentials(context: Context, appId: String, appSecret: String) {
        context.applicationContext.getSharedPreferences(PREFS_EWELINK, Context.MODE_PRIVATE).edit()
            .putString("ewelink_custom_app_id", appId.trim())
            .remove("ewelink_custom_app_secret")
            .apply()
        writeSecretToVault(context, "ewelink_custom_app_secret", appSecret)
    }

    // -----------------------------------------------------------------------
    // 2. SONOFF LOCAL LAN AES-128-CBC CIPHER (Key = MD5(deviceKey))
    // -----------------------------------------------------------------------
    private fun deriveSonoffAesKey(deviceKey: String): ByteArray =
        MessageDigest.getInstance("MD5").digest(deviceKey.trim().toByteArray(Charsets.UTF_8))

    private fun encryptSonoffLanPayload(plainJson: String, deviceKey: String): Pair<String, String>? {
        if (deviceKey.isBlank()) return null
        return try {
            val keyBytes = deriveSonoffAesKey(deviceKey)
            val ivBytes = ByteArray(16).apply { SecureRandom().nextBytes(this) }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            val encrypted = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))
            val ivBase64 = Base64.encodeToString(ivBytes, Base64.NO_WRAP)
            val dataBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            ivBase64 to dataBase64
        } catch (e: Exception) {
            Log.w(TAG, "Sonoff LAN encrypt note: ${e.message}")
            null
        }
    }

    private fun decryptSonoffLanPayload(
        cipherBase64: String,
        ivBase64: String,
        deviceKey: String
    ): JSONObject? {
        if (cipherBase64.isBlank() || ivBase64.isBlank() || deviceKey.isBlank()) return null
        return try {
            val keyBytes = deriveSonoffAesKey(deviceKey)
            val ivBytes = Base64.decode(ivBase64.trim(), Base64.DEFAULT)
            val cipherBytes = Base64.decode(cipherBase64.trim(), Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            val plainBytes = cipher.doFinal(cipherBytes)
            JSONObject(String(plainBytes, Charsets.UTF_8))
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
            null
}
    }

    private fun hmacSha256Base64(message: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val rawHmac = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(rawHmac, Base64.NO_WRAP)
    }

    // -----------------------------------------------------------------------
    // 3. LOCAL _ewelink._tcp. mDNS DISCOVERY & TXT TELEMETRY DECODER
    // -----------------------------------------------------------------------
    private fun parseSonoffStateJson(
        devId: String,
        nameHint: String,
        ip: String,
        port: Int,
        devKey: String,
        isEncrypted: Boolean,
        stateObj: JSONObject?
    ): DiscoveredSonoffDevice {
        var isOn = false
        var isSocket2On = false
        var gangCount = 1
        var powerW = 0f
        var voltageV = 0f
        var currentMa = 0f

        if (stateObj != null) {
            val switchesArr = stateObj.optJSONArray("switches")
            if (switchesArr != null && switchesArr.length() >= 2) {
                // F-13: Preserve full N-gang outlet count (3-gang, 4-gang, 6-gang strips)
                gangCount = switchesArr.length()
                for (i in 0 until switchesArr.length()) {
                    val sw = switchesArr.optJSONObject(i) ?: continue
                    val outlet = sw.optInt("outlet", i)
                    val swOn = sw.optString("switch", "off").equals("on", ignoreCase = true)
                    if (outlet == 0) isOn = swOn
                    else if (outlet == 1) isSocket2On = swOn
                }
            } else if (stateObj.has("switch")) {
                isOn = stateObj.optString("switch", "off").equals("on", ignoreCase = true)
            }

            powerW = stateObj.optString("power", stateObj.optDouble("power", 0.0).toString())
                .toFloatOrNull() ?: 0f
            voltageV = stateObj.optString("voltage", stateObj.optDouble("voltage", 0.0).toString())
                .toFloatOrNull() ?: 0f
            val rawCurrentA = stateObj.optString("current", stateObj.optDouble("current", 0.0).toString())
                .toFloatOrNull() ?: 0f
            currentMa = if (rawCurrentA in 0.001f..40f) rawCurrentA * 1000f else rawCurrentA
        }

        val existing = discoveredSonoffCache[devId]
        val resolvedName = nameHint.ifBlank {
            existing?.name ?: "Sonoff Switch (${devId.takeLast(4).uppercase(Locale.US)})"
        }

        return DiscoveredSonoffDevice(
            deviceId = devId,
            name = resolvedName,
            model = existing?.model ?: if (gangCount >= 2) "Sonoff Dual R3" else "Sonoff Wi-Fi Switch",
            ipAddress = ip,
            port = port,
            deviceKey = devKey,
            isEncrypted = isEncrypted,
            isActive = isOn,
            isSocket2Active = isSocket2On,
            multiGangCount = maxOf(gangCount, existing?.multiGangCount ?: 1),
            socket1Name = existing?.socket1Name ?: "Outlet 1",
            socket2Name = existing?.socket2Name ?: "Outlet 2",
            powerWatts = if (powerW > 0f) powerW else (existing?.powerWatts ?: 0f),
            voltageVolts = if (voltageV > 0f) voltageV else (existing?.voltageVolts ?: 0f),
            currentMilliamps = if (currentMa > 0f) currentMa else (existing?.currentMilliamps ?: 0f),
            suggestedType = TileType.SONOFF_SWITCH,
            requiresAuth = isEncrypted && devKey.isBlank()
        )
    }

    suspend fun discoverLocalSonoffMdns(
        context: Context,
        timeoutMs: Long = 1400L
    ): List<DiscoveredSonoffDevice> = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, DiscoveredSonoffDevice>()
        val resolvedServices = DiscoveryRadioCoordinator.discoverMdnsServices(
            context = context,
            serviceTypes = listOf(MDNS_SERVICE_TYPE),
            timeoutMs = timeoutMs,
            transportLabel = "SONOFF_MDNS"
        )

        for (svc in resolvedServices) {
            val hostIp = svc.hostIp
            val port = if (svc.port > 0) svc.port else SONOFF_LAN_PORT
            val sName = svc.serviceName
            val attrs = svc.txtAttributes

            fun txt(key: String): String = attrs[key].orEmpty().trim()

            val rawId = txt("id").ifBlank {
                if (sName.startsWith("eWeLink_", ignoreCase = true)) {
                    sName.substringAfter("_").substringBefore(".")
                } else ""
            }.trim().lowercase(Locale.US)

            if (rawId.length < 6) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FILTERED,
                    transportSource = "SONOFF_MDNS",
                    hardwareId = sName,
                    ipAddress = hostIp,
                    port = port,
                    vendor = "Sonoff",
                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                    outcomeReason = "Rejected mDNS record without valid eWeLink device id TXT"
                )
                continue
            }

            val isEnc = txt("encrypt") == "true" || txt("encrypt") == "1"
            val ivB64 = txt("iv")
            val rawData = txt("data1") + txt("data2") + txt("data3") + txt("data4")
            val savedKey = getSavedDeviceKey(context, rawId)

            persistSonoffLanIp(rawId, hostIp)
            SmartHomeApiClient.registerVerifiedLanNeighbor(hostIp, rawId)

            val decodedStateJson: JSONObject? = when {
                rawData.isBlank() -> null
                isEnc && savedKey.isNotBlank() && ivB64.isNotBlank() ->
                    decryptSonoffLanPayload(rawData, ivB64, savedKey)
                !isEnc && rawData.trim().startsWith("{") ->
                    runCatching { JSONObject(rawData.trim()) }.getOrNull()
                else -> null
            }

            val dev = parseSonoffStateJson(
                devId = rawId,
                nameHint = discoveredSonoffCache[rawId]?.name.orEmpty(),
                ip = hostIp,
                port = port,
                devKey = savedKey,
                isEncrypted = isEnc,
                stateObj = decodedStateJson
            )
            val learnedMac = CrossEcosystemIdentityCorrelator.getObservedIpToMacMap()[hostIp].orEmpty()
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.IDENTIFIED,
                transportSource = "SONOFF_MDNS",
                hardwareId = rawId,
                macAddress = learnedMac,
                ipAddress = hostIp,
                port = port,
                vendor = "${dev.name} (${dev.model})",
                confidenceScore = 0.95f,
                qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                outcomeReason = "Verified Sonoff _ewelink._tcp record (encrypted=$isEnc gangs=${dev.multiGangCount} keyPresent=${savedKey.isNotBlank()})"
            )
            found[rawId] = dev
            discoveredSonoffCache[rawId] = dev
        }

        val list = found.values.toList()
        if (discoveredSonoffCache.isNotEmpty()) {
            MultiProtocolLiveBridge.discoveredSonoffDevicesFlow.value = discoveredSonoffCache.values.toList()
        }
        list
    }

    // -----------------------------------------------------------------------
    // 4. EWELINK CLOUD V2 AUTHENTICATION, TOKEN REFRESH & KEY EXTRACTION
    // -----------------------------------------------------------------------
    private fun refreshEWeLinkAccessTokenSync(context: Context): String {
        if (JarvisLiveBridge.isCircuitOpen("EWELINK_CLOUD")) {
            throw CircuitBreakerOpenException("Cloud Degraded \u2014 Running Locally")
        }
        val (appId, appSecret) = resolveEWeLinkAppCredentials(context)
        val rt = cachedRefreshToken.trim()
        if (appId.isBlank() || appSecret.isBlank() || rt.isBlank()) return ""

        return try {
            val bodyJson = JSONObject().put("rt", rt).toString()
            val sign = hmacSha256Base64(bodyJson, appSecret)
            val nonce = UUID.randomUUID().toString().replace("-", "").take(8)
            val conn = (URL("${cachedRegionHost.trimEnd('/')}/v2/user/refresh").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-CK-Appid", appId)
                setRequestProperty("X-CK-Nonce", nonce)
                setRequestProperty("Authorization", "Sign $sign")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(bodyJson); it.flush() }
            if (conn.responseCode in 200..299) {
                val respText = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val root = JSONObject(respText)
                if (root.optInt("error", -1) == 0) {
                    val data = root.optJSONObject("data")
                    val newAt = data?.optString("at", "").orEmpty().trim()
                    val newRt = data?.optString("rt", rt).orEmpty().trim()
                    if (newAt.isNotBlank()) {
                        saveCloudSessionTokens(context, newAt, newRt, cachedRegionHost)
                        return newAt
                    }
                }
            } else {
                conn.disconnect()
            }
            ""
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
            ""
}
    }

    private suspend fun ensureValidCloudAccessToken(context: Context): String = withContext(Dispatchers.IO) {
        if (cachedAccessToken.isNotBlank() && System.currentTimeMillis() < tokenExpiryEpochMs - 300_000L) {
            return@withContext cachedAccessToken
        }
        loadPersistedCloudSession(context)?.let { (savedAt, _) ->
            return@withContext savedAt
        }
        val refreshed = refreshEWeLinkAccessTokenSync(context)
        if (refreshed.isNotBlank()) return@withContext refreshed

        val email = getSavedEWeLinkEmail(context)
        val pass = getSavedEWeLinkPassword(context)
        if (email.isNotBlank() && pass.isNotBlank()) {
            loginAndDiscoverEWeLinkCloud(context, email, pass)
        }
        cachedAccessToken
    }

    /**
     * Compatibility alias for ManageAndPairModals.kt, SetupWizardScreen.kt, and SetupWizardTabs.kt.
     */
    suspend fun loginAndDiscoverEWeLinkDevices(
        context: Context,
        email: String = getSavedEWeLinkEmail(context),
        password: String = getSavedEWeLinkPassword(context)
    ): Result<List<DiscoveredSonoffDevice>> =
        loginAndDiscoverEWeLinkCloud(context, email, password)

    suspend fun loginAndDiscoverEWeLinkCloud(
        context: Context,
        email: String = getSavedEWeLinkEmail(context),
        password: String = getSavedEWeLinkPassword(context)
    ): Result<List<DiscoveredSonoffDevice>> = withContext(Dispatchers.IO) {
        if (JarvisLiveBridge.isCircuitOpen("EWELINK_CLOUD")) {
            throw CircuitBreakerOpenException("Cloud Degraded \u2014 Running Locally")
        }
        val cleanEmail = email.trim()
        if (cleanEmail.isNotBlank() && password.isNotBlank()) {
            saveEWeLinkCredentials(context, cleanEmail, password)
        }

        // Always run fast local mDNS scan in parallel
        val localList = discoverLocalSonoffMdns(context, timeoutMs = 1200L)
        localList.forEach { discoveredSonoffCache[it.deviceId] = it }

        val (appId, appSecret) = resolveEWeLinkAppCredentials(context)
        if (cleanEmail.isNotBlank() && password.isNotBlank() && appId.isNotBlank() && appSecret.isNotBlank()) {
            val candidateHosts = listOf(
                cachedRegionHost,
                "https://eu-apia.coolkit.cc",
                "https://us-apia.coolkit.cc",
                "https://as-apia.coolkit.cc"
            ).distinct()

            var activeToken = ""
            var activeHost = cachedRegionHost

            for (host in candidateHosts) {
                try {
                    val loginBody = JSONObject().apply {
                        if (cleanEmail.contains("@")) {
                            put("email", cleanEmail)
                        } else {
                            put("phoneNumber", cleanEmail)
                        }
                        put("password", password)
                        put("countryCode", "+44")
                    }.toString()

                    val sign = hmacSha256Base64(loginBody, appSecret)
                    val nonce = UUID.randomUUID().toString().replace("-", "").take(8)
                    val conn = (URL("${host.trimEnd('/')}/v2/user/login").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 5500
                        readTimeout = 5500
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("X-CK-Appid", appId)
                        setRequestProperty("X-CK-Nonce", nonce)
                        setRequestProperty("Authorization", "Sign $sign")
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write(loginBody); it.flush() }
                    val respText = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    conn.disconnect()
                    if (respText.isBlank()) continue

                    val root = JSONObject(respText)
                    val err = root.optInt("error", -1)
                    val data = root.optJSONObject("data")

                    // Regional redirect (10004)
                    if (err == 10004 && data != null) {
                        val region = data.optString("region", "eu").trim()
                        val redirectedHost = "https://$region-apia.coolkit.cc"
                        cachedRegionHost = redirectedHost
                        continue
                    }

                    if (err == 0 && data != null) {
                        val at = data.optString("at", "").trim()
                        val rt = data.optString("rt", "").trim()
                        if (at.isNotBlank()) {
                            activeToken = at
                            activeHost = host.trimEnd('/')
                            saveCloudSessionTokens(context, at, rt, activeHost)
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "eWeLink Cloud v2 login note: ${e.message}")
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FAILED,
                        transportSource = "EWELINK_CLOUD",
                        vendor = "eWeLink Cloud ($host)",
                        qualificationStage = "loginAndDiscoverEWeLinkCloud",
                        outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "eWeLink login error"}"
                    )
                }
            }

            if (activeToken.isNotBlank()) {
                fetchCloudThingListAndExtractKeys(context, activeHost, appId, activeToken)
            }
        }

        val finalList = discoveredSonoffCache.values.toList()
        MultiProtocolLiveBridge.discoveredSonoffDevicesFlow.value = finalList
        if (finalList.isNotEmpty()) {
            Result.success(finalList)
        } else {
            Result.failure(Exception("No Sonoff or eWeLink devices discovered yet."))
        }
    }

    private fun fetchCloudThingListAndExtractKeys(
        context: Context,
        host: String,
        appId: String,
        accessToken: String
    ) {
        try {
            val nonce = UUID.randomUUID().toString().replace("-", "").take(8)
            val conn = (URL("${host.trimEnd('/')}/v2/device/thing?num=0").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5500
                readTimeout = 5500
                setRequestProperty("X-CK-Appid", appId)
                setRequestProperty("X-CK-Nonce", nonce)
                setRequestProperty("Authorization", "Bearer $accessToken")
            }
            val respCode = conn.responseCode
            if (respCode !in 200..299) {
                conn.disconnect()
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "EWELINK_CLOUD",
                    vendor = "eWeLink Cloud ($host)",
                    qualificationStage = "fetchCloudThingListAndExtractKeys",
                    outcomeReason = "eWeLink /v2/device/thing returned HTTP $respCode"
                )
                return
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val thingList = JSONObject(body).optJSONObject("data")?.optJSONArray("thingList") ?: JSONArray()

            for (i in 0 until thingList.length()) {
                val item = thingList.optJSONObject(i)?.optJSONObject("itemData") ?: continue
                val devId = item.optString("deviceid", "").trim().lowercase(Locale.US)
                val name = item.optString("name", "Sonoff Switch").trim()
                val model = item.optString("productModel", item.optString("extra", "Sonoff Switch")).trim()
                val mac = CrossEcosystemIdentityCorrelator.normalizeMac(
                    item.optString("extra", "").let { rawExtra ->
                        runCatching { JSONObject(rawExtra).optString("staMac", "") }.getOrDefault("")
                    }.ifBlank { item.optJSONObject("params")?.optString("staMac", "").orEmpty() }
                )
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                    transportSource = "EWELINK_CLOUD",
                    hardwareId = devId,
                    macAddress = mac,
                    vendor = "$name ($model)",
                    confidenceScore = 0.85f,
                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                    outcomeReason = "eWeLink Cloud v2 thing item received",
                    cloudId = devId
                )
                if (devId.isBlank()) {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FILTERED,
                        transportSource = "EWELINK_CLOUD",
                        vendor = name,
                        qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                        outcomeReason = "Filtered eWeLink Cloud item missing deviceid"
                    )
                    continue
                }
                val devKey = item.optString("devicekey", item.optString("apikey", "")).trim()
                if (devKey.isNotBlank()) {
                    saveDeviceKey(context, devId, devKey)
                }

                val params = item.optJSONObject("params")
                val existingLocal = discoveredSonoffCache[devId]
                val ip = existingLocal?.ipAddress ?: resolveKnownSonoffIp(devId)

                val parsed = parseSonoffStateJson(
                    devId = devId,
                    nameHint = name,
                    ip = ip,
                    port = existingLocal?.port ?: SONOFF_LAN_PORT,
                    devKey = devKey.ifBlank { existingLocal?.deviceKey.orEmpty() },
                    isEncrypted = existingLocal?.isEncrypted ?: true,
                    stateObj = params
                ).copy(
                    model = model.ifBlank { "Sonoff Smart Switch" },
                    requiresAuth = false
                )
                discoveredSonoffCache[devId] = parsed
            }
        } catch (e: Exception) {
            Log.w(TAG, "eWeLink thingList sync note: ${e.message}")
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.FAILED,
                transportSource = "EWELINK_CLOUD",
                vendor = "eWeLink Cloud ($host)",
                qualificationStage = "fetchCloudThingListAndExtractKeys",
                outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "thingList sync error"}"
            )
        }
    }

    // -----------------------------------------------------------------------
    // 5. LOCAL LAN PORT 8081 (AES-128-CBC + DIY MODE) & CLOUD V2 ACTUATION
    // -----------------------------------------------------------------------
    private fun postLocalSonoffZeroConf(
        ip: String,
        port: Int,
        endpointPath: String,
        deviceId: String,
        dataObj: JSONObject,
        deviceKey: String
    ): JSONObject? {
        val cleanIp = ip.substringBefore(":").trim()
        if (cleanIp.isBlank()) return null

        // Try Encrypted LAN mode first if deviceKey is known, then unencrypted DIY mode
        val modesToTry = buildList {
            if (deviceKey.isNotBlank()) add(true)
            add(false)
        }

        for (encryptMode in modesToTry) {
            try {
                val rootPayload = JSONObject().apply {
                    put("sequence", System.currentTimeMillis().toString())
                    put("deviceid", deviceId)
                    put("selfApikey", "123")
                    if (encryptMode) {
                        val encryptedPair = encryptSonoffLanPayload(dataObj.toString(), deviceKey) ?: continue
                        put("encrypt", true)
                        put("iv", encryptedPair.first)
                        put("data", encryptedPair.second)
                    } else {
                        put("encrypt", false)
                        put("data", dataObj)
                    }
                }

                val conn = (URL("http://$cleanIp:$port$endpointPath").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 1400
                    readTimeout = 1400
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                OutputStreamWriter(conn.outputStream).use {
                    it.write(rootPayload.toString())
                    it.flush()
                }
                val code = conn.responseCode
                val respText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()

                if (code in 200..299 && respText.isNotBlank()) {
                    val respJson = JSONObject(respText)
                    if (respJson.optInt("error", -1) == 0) {
                        SmartHomeApiClient.registerVerifiedLanNeighbor(cleanIp, deviceId)
                        return respJson
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

    suspend fun setSonoffSwitchState(
        context: Context,
        tile: SmartTile,
        turnOn: Boolean,
        outletIndex: Int = 0
    ): Boolean = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val rawId = tile.serialNumber.ifBlank { tile.entityId }.trim().lowercase(Locale.US)
        val cleanIp = tile.ipAddress.substringBefore(":").trim()
            .ifBlank { resolveKnownSonoffIp(rawId) }
            .ifBlank { discoveredSonoffCache[rawId]?.ipAddress.orEmpty() }
        val devKey = tile.secretCode.trim().ifBlank { getSavedDeviceKey(context, rawId) }
        val stateStr = if (turnOn) "on" else "off"

        var lanOk = false
        if (cleanIp.isNotBlank()) {
            val isMultiGang = tile.isMultiGangSwitch || outletIndex > 0
            val endpoint = if (isMultiGang) "/zeroconf/switches" else "/zeroconf/switch"
            val dataObj = if (isMultiGang) {
                JSONObject().put(
                    "switches",
                    JSONArray().put(
                        JSONObject().put("switch", stateStr).put("outlet", outletIndex.coerceAtLeast(0))
                    )
                )
            } else {
                JSONObject().put("switch", stateStr)
            }
            val resp = postLocalSonoffZeroConf(
                ip = cleanIp,
                port = SONOFF_LAN_PORT,
                endpointPath = endpoint,
                deviceId = rawId,
                dataObj = dataObj,
                deviceKey = devKey
            )
            lanOk = resp != null
        }

        // Cloud v2 fallback if LAN is unreachable
        var cloudOk = false
        if (!lanOk && rawId.isNotBlank()) {
            val token = ensureValidCloudAccessToken(context)
            val (appId, _) = resolveEWeLinkAppCredentials(context)
            if (token.isNotBlank() && appId.isNotBlank()) {
                try {
                    val paramsObj = if (tile.isMultiGangSwitch || outletIndex > 0) {
                        JSONObject().put(
                            "switches",
                            JSONArray().put(
                                JSONObject().put("switch", stateStr).put("outlet", outletIndex.coerceAtLeast(0))
                            )
                        )
                    } else {
                        JSONObject().put("switch", stateStr)
                    }
                    val payload = JSONObject().apply {
                        put("type", 1)
                        put("id", rawId)
                        put("params", paramsObj)
                    }.toString()

                    val conn = (URL("${cachedRegionHost.trimEnd('/')}/v2/device/thing/status").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 4000
                        readTimeout = 4000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("X-CK-Appid", appId)
                        setRequestProperty("X-CK-Nonce", UUID.randomUUID().toString().take(8))
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write(payload); it.flush() }
                    val respText = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    cloudOk = JSONObject(respText).optInt("error", -1) == 0
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

        val succeeded = lanOk || cloudOk
        JarvisLiveBridge.recordCommandLatency(
            integrationKey = "MULTI_PROTOCOL",
            displayName = "Matter / Tapo / Shelly / MQTT / HA",
            latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(6L),
            succeeded = succeeded
        )
        succeeded
    }

    /**
     * Protocol Lifecycle Audit #9 Fix:
     * Periodically syncs live relay state and power telemetry (Watts / Volts / mA) for all
     * Sonoff / eWeLink tiles, reconciles updates via `DeviceRepository.reconcileRemoteTileUpdate`,
     * and replays any queued Digital Twin Shadow intents upon reconnect.
     */
    suspend fun syncLiveSonoffStates(context: Context, tiles: MutableList<SmartTile>) {
        val sonoffTiles = tiles.withIndex().filter {
            it.value.type == TileType.SONOFF_SWITCH ||
                    it.value.activeProviderId.contains("SONOFF", ignoreCase = true) ||
                    it.value.activeProviderId.contains("EWELINK", ignoreCase = true)
        }
        if (sonoffTiles.isEmpty()) return

        val liveByTileId = ConcurrentHashMap<String, DiscoveredSonoffDevice>()

        withContext(Dispatchers.IO) {
            coroutineScope {
                sonoffTiles.chunked(8).forEach { batch ->
                    batch.map { (_, tile) ->
                        async(Dispatchers.IO) {
                            val devId = tile.serialNumber.ifBlank { tile.entityId }.trim().lowercase(Locale.US)
                            val cleanIp = tile.ipAddress.substringBefore(":").trim()
                                .ifBlank { resolveKnownSonoffIp(devId) }
                            val devKey = tile.secretCode.trim().ifBlank { getSavedDeviceKey(context, devId) }

                            if (cleanIp.isNotBlank()) {
                                val resp = postLocalSonoffZeroConf(
                                    ip = cleanIp,
                                    port = SONOFF_LAN_PORT,
                                    endpointPath = "/zeroconf/info",
                                    deviceId = devId,
                                    dataObj = JSONObject(),
                                    deviceKey = devKey
                                )
                                if (resp != null) {
                                    val isEnc = resp.optBoolean("encrypt", false)
                                    val iv = resp.optString("iv", "")
                                    val dataField = resp.opt("data")
                                    val stateObj = when {
                                        isEnc && dataField is String && devKey.isNotBlank() ->
                                            decryptSonoffLanPayload(dataField, iv, devKey)
                                        dataField is JSONObject -> dataField
                                        dataField is String && dataField.trim().startsWith("{") ->
                                            runCatching { JSONObject(dataField.trim()) }.getOrNull()
                                        else -> null
                                    }
                                    val parsed = parseSonoffStateJson(
                                        devId = devId.ifBlank { cleanIp },
                                        nameHint = tile.title,
                                        ip = cleanIp,
                                        port = SONOFF_LAN_PORT,
                                        devKey = devKey,
                                        isEncrypted = isEnc,
                                        stateObj = stateObj
                                    )
                                    liveByTileId[tile.id] = parsed
                                }
                            } else if (devId.isNotBlank()) {
                                discoveredSonoffCache[devId]?.let { cached ->
                                    liveByTileId[tile.id] = cached
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val bridge = DeviceRepository.loadBridge(context)
        val polledIds = sonoffTiles.map { it.value.id }.toSet()
        withContext(Dispatchers.Main) {
            for (i in tiles.indices) {
                val current = tiles[i]
                if (current.id !in polledIds) continue
                val live = liveByTileId[current.id]
                if (live == null) {
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
                                sourceName = "Sonoff LAN"
                            )
                        }
                    }
                    continue
                }
                missedPollCountByTileId[current.id] = 0
                val wasOffline = !current.isOnline
                val hasPower = live.powerWatts > 0f || current.hasExplicitPowerTelemetry
                val inferredAppliance = AppliancePowerFingerprinter.inferApplianceFromTelemetry(
                    powerWatts = live.powerWatts,
                    titleHint = current.title,
                    previous = current.detectedApplianceCategory
                )
                val incoming = current.copy(
                    ipAddress = current.ipAddress.ifBlank { live.ipAddress },
                    secretCode = current.secretCode.ifBlank { live.deviceKey },
                    isOnline = true,
                    lifecycleState = DeviceLifecycleState.ONLINE,
                    isActive = live.isActive,
                    isSocket2Active = live.isSocket2Active,
                    multiGangSocketCount = maxOf(current.multiGangSocketCount, live.multiGangCount),
                    powerWatts = if (live.powerWatts > 0f) live.powerWatts else current.powerWatts,
                    voltageVolts = if (live.voltageVolts > 0f) live.voltageVolts else current.voltageVolts,
                    currentMilliamps = if (live.currentMilliamps > 0f) live.currentMilliamps else current.currentMilliamps,
                    hasExplicitPowerTelemetry = hasPower,
                    detectedApplianceCategory = inferredAppliance
                )
                tiles[i] = DeviceRepository.reconcileRemoteTileUpdate(current, incoming)
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
