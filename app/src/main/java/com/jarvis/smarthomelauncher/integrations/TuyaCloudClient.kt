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
import android.util.Log
import com.thingclips.smart.android.user.api.ILoginCallback
import com.thingclips.smart.android.user.bean.User
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.home.sdk.bean.HomeBean
import com.thingclips.smart.home.sdk.callback.IThingGetHomeListCallback
import com.thingclips.smart.home.sdk.callback.IThingHomeResultCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// Dynamic set of Tuya device IDs discovered via local UDP broadcast (ports 6666/6667) or mDNS
private val discoveredLanTuyaDeviceIds = ConcurrentHashMap.newKeySet<String>()

// In-memory cache of discovered Tuya devices used for zero-config auto-linking and self-healing
private val discoveredTuyaDeviceCache = ConcurrentHashMap<String, DiscoveredTuyaDevice>()

/**
 * Registers a Tuya hardware device ID discovered on the local Wi-Fi network (via UDP 6666/6667
 * broadcast or mDNS) so that cloud metadata and DP schemas are automatically correlated.
 */
fun TuyaApiClient.registerLanDiscoveredDeviceId(deviceId: String) {
    val clean = deviceId.trim()
    if (clean.isNotBlank()) {
        discoveredLanTuyaDeviceIds.add(clean)
        markDeviceSeenOnline(clean)
    }
}

/**
 * Caches a discovered Tuya device in memory for fast ID resolution and local/cloud correlation.
 */
fun TuyaApiClient.cacheDiscoveredDevice(device: DiscoveredTuyaDevice) {
    if (device.deviceId.isNotBlank()) {
        discoveredTuyaDeviceCache[device.deviceId] = device
    }
}

/**
 * Returns all Tuya hardware `gwId`s seen broadcasting on local Wi-Fi UDP ports 6666/6667.
 */
fun TuyaApiClient.getLanDiscoveredDeviceIds(): Set<String> = discoveredLanTuyaDeviceIds.toSet()

/**
 * Checks whether a local Tuya hardware `gwId` is already linked in our cloud/SDK cache.
 */
fun TuyaApiClient.isDeviceIdKnownInCloudOrSdk(deviceId: String): Boolean {
    val clean = deviceId.trim()
    if (clean.isBlank()) return false
    if (discoveredTuyaDeviceCache.containsKey(clean)) return true
    if (!ClaraApplication.isTuyaSdkReady()) return false
    return runCatching { ThingHomeSdk.getDataInstance().getDeviceBean(clean) != null }.getOrDefault(false)
}

// ---------------------------------------------------------------------------
// TUYA / SMART LIFE / SMARTKNIGHT / PRO BREEZE CLOUD, SKILL & DISPATCH ENGINE
// ---------------------------------------------------------------------------
private fun sha256Hex(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun hmacSha256UpperHex(secret: String, data: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val bytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02X".format(it) }
}
private fun tokenSimilarityScore(a: String, b: String): Int {
    val tokensA = a.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()
    val tokensB = b.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()
    if (tokensA.isEmpty() || tokensB.isEmpty()) return 0
    return tokensA.intersect(tokensB).size
}

/**
 * Automatically resolves or heals a missing hardware Device ID for a tile by matching its name,
 * semantic token overlap, or device type against discovered Tuya / SmartKnight / Smart Life devices.
 */
suspend fun TuyaApiClient.resolveDeviceIdForTile(
    bridge: BridgeSettings,
    tile: SmartTile
): String {
    val explicitId = tile.serialNumber.ifBlank { tile.entityId }.trim()
    if (explicitId.isNotBlank() && !explicitId.startsWith("enterprises/") && !explicitId.contains(".")) {
        return explicitId
    }
    if (discoveredTuyaDeviceCache.isEmpty()) {
        discoverAllTuyaDevices(bridge)
    }
    val cachedList = discoveredTuyaDeviceCache.values.toList()
    val matched = cachedList.firstOrNull { dev ->
        dev.name.equals(tile.title, ignoreCase = true) ||
                (dev.suggestedType == tile.type && tile.title.contains(dev.name, ignoreCase = true)) ||
                (tile.type == TileType.AIRROBO_VACUUM && (dev.category == "sd" || dev.name.contains("airrobo", true) || dev.name.contains("p30", true) || dev.name.contains("vacuum", true))) ||
                (tile.type == TileType.PROBREEZE_FAN && (dev.category in listOf("fs", "fskg", "fsd") || dev.name.contains("breeze", true) || dev.name.contains("fan", true)))
    } ?: cachedList
        .filter { it.suggestedType == tile.type || (it.suggestedType.isSwitchType() && tile.isSwitchTile) }
        .maxByOrNull { tokenSimilarityScore("${tile.roomOrSubtitle} ${tile.title}", it.name) }
        ?.takeIf { tokenSimilarityScore("${tile.roomOrSubtitle} ${tile.title}", it.name) > 0 }
    ?: cachedList.firstOrNull { dev ->
        dev.suggestedType == tile.type
    }
    return matched?.deviceId ?: discoveredLanTuyaDeviceIds.firstOrNull().orEmpty()
}

data class TuyaHomeSummary(
    val homeId: Long,
    val name: String,
    val geoName: String = "",
    val deviceCount: Int = 0
)

private fun regionEndpointForCountryCode(countryCode: String): String = when (countryCode.trim()) {
    "1" -> "https://openapi.tuyaus.com"
    "86" -> "https://openapi.tuyacn.com"
    "91" -> "https://openapi.tuyain.com"
    else -> "https://openapi.tuyaeu.com"
}

/**
 * Change 6.2: Lists all official SmartLife / Tuya Homes under the signed-in user account
 * so the user can select their primary household instead of an isolated shadow UID.
 */
suspend fun TuyaApiClient.listAvailableTuyaHomes(): Result<List<TuyaHomeSummary>> =
    withContext(Dispatchers.IO) {
        if (!ClaraApplication.isTuyaSdkReady()) {
            return@withContext Result.failure(Exception("Tuya SDK is not initialized yet."))
        }
        if (!ThingHomeSdk.getUserInstance().isLogin) {
            return@withContext Result.failure(Exception("Please sign in to your SmartLife / Tuya account first."))
        }
        suspendCancellableCoroutine { cont ->
            try {
                ThingHomeSdk.getHomeManagerInstance().queryHomeList(object : IThingGetHomeListCallback {
                    override fun onSuccess(homeBeans: MutableList<HomeBean>?) {
                        val summaries = homeBeans.orEmpty().map { hb ->
                            TuyaHomeSummary(
                                homeId = hb.homeId,
                                name = hb.name?.trim().orEmpty().ifBlank { "Smart Home" },
                                geoName = hb.geoName?.trim().orEmpty(),
                                deviceCount = hb.deviceList?.size ?: 0
                            )
                        }
                        if (cont.isActive) cont.resume(Result.success(summaries))
                    }

                    override fun onError(errorCode: String?, errorMsg: String?) {
                        if (cont.isActive) {
                            cont.resume(
                                Result.failure(
                                    Exception("Could not load SmartLife Homes ($errorCode): ${errorMsg.orEmpty()}")
                                )
                            )
                        }
                    }
                })
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(Result.failure(e))
            }
        }
    }

/**
 * Change 6.2: Selects an existing primary SmartLife Home (or creates one if the account has none),
 * persists the selection in ClaraSecretVault, and synchronizes all devices, DP schemas, protocol
 * versions, and rotatable 16-byte localKeys into ClaraDeviceRegistryStore & ClaraSecretVault.
 */
suspend fun TuyaApiClient.selectAndSyncPrimaryTuyaHome(
    context: Context,
    preferredHomeId: Long = -1L,
    createHomeNameIfEmpty: String = "Clara Home"
): Result<List<DiscoveredTuyaDevice>> = withContext(Dispatchers.IO) {
    val appCtx = context.applicationContext
    if (!ClaraApplication.isTuyaSdkReady(appCtx) || !ThingHomeSdk.getUserInstance().isLogin) {
        return@withContext Result.failure(Exception("Please sign in to your SmartLife / Tuya account first."))
    }

    val homesRes = listAvailableTuyaHomes()
    val homes = homesRes.getOrDefault(emptyList())
    val vaultHomeId = ClaraSecretVault.getTuyaSelectedHomeId(appCtx) ?: -1L
    val targetSummary: TuyaHomeSummary? = when {
        preferredHomeId > 0L -> homes.firstOrNull { it.homeId == preferredHomeId }
        vaultHomeId > 0L -> homes.firstOrNull { it.homeId == vaultHomeId }
        else -> null
    } ?: homes.maxByOrNull { it.deviceCount } ?: homes.firstOrNull()

    val resolvedHomeId: Long = if (targetSummary != null) {
        ClaraSecretVault.saveTuyaSelectedHome(appCtx, targetSummary.homeId, targetSummary.name)
        targetSummary.homeId
    } else {
        val created = suspendCancellableCoroutine<HomeBean?> { cont ->
            ThingHomeSdk.getHomeManagerInstance().createHome(
                createHomeNameIfEmpty.trim().ifBlank { "Clara Home" },
                0.0,
                0.0,
                "",
                emptyList(),
                object : IThingHomeResultCallback {
                    override fun onSuccess(bean: HomeBean?) {
                        if (cont.isActive) cont.resume(bean)
                    }

                    override fun onError(errorCode: String?, errorMsg: String?) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        } ?: return@withContext Result.failure(Exception("Could not create a primary SmartLife Home."))
        ClaraSecretVault.saveTuyaSelectedHome(appCtx, created.homeId, created.name.orEmpty())
        created.homeId
    }

    val detailedHome = suspendCancellableCoroutine<HomeBean?> { cont ->
        ThingHomeSdk.newHomeInstance(resolvedHomeId).getHomeDetail(object : IThingHomeResultCallback {
            override fun onSuccess(bean: HomeBean?) {
                if (cont.isActive) cont.resume(bean)
            }

            override fun onError(errorCode: String?, errorMsg: String?) {
                if (cont.isActive) cont.resume(null)
            }
        })
    }

    val registryStore = ClaraDeviceRegistryStore.getInstance(appCtx)
    val syncedDevices = mutableListOf<DiscoveredTuyaDevice>()
    for (bean in detailedHome?.deviceList.orEmpty()) {
        val disc = convertDeviceBeanToDiscovered(bean)
        if (disc.deviceId.isBlank()) continue
        val knownIp = localIpByDevice[disc.deviceId].orEmpty()
        val protoVer = protocolVersionByDevice[disc.deviceId].orEmpty()
        if (disc.localKey.length == 16) {
            ClaraSecretVault.persistBoundTuyaCredentialsImmediate(
                context = appCtx,
                deviceId = disc.deviceId,
                localKey = disc.localKey,
                ipAddress = knownIp
            )
        }
        if (protoVer.isNotBlank()) {
            registryStore.updatePersistedProtocolVersion(disc.deviceId, protoVer)
        }
        discoveredTuyaDeviceCache[disc.deviceId] = disc
        syncedDevices.add(disc)
    }

    if (syncedDevices.isEmpty()) {
        val fallbackNative = discoverNativeSdkDevices(forceRefresh = true)
        if (fallbackNative.isSuccess) {
            syncedDevices.addAll(fallbackNative.getOrThrow())
        }
    }
    Result.success(syncedDevices)
}

/**
 * Change 6.2: Official SmartLife / Tuya Account Sign-In with automatic EU / US / CN / IN
 * data-center region detection and full Home + 16-byte localKey synchronization.
 */
suspend fun TuyaApiClient.loginWithEmailAndPassword(
    bridge: BridgeSettings,
    emailOrPhone: String,
    password: String,
    countryCode: String = "44"
): Result<List<DiscoveredTuyaDevice>> = withContext(Dispatchers.IO) {
    val cleanUser = emailOrPhone.trim()
    val cleanPass = password
    val preferredCountry = countryCode.trim().removePrefix("+").ifBlank { "44" }
    DiagnosticsSanitizer.registerTransientWifiSecretForRedaction(cleanPass)

    if (cleanUser.isBlank() || cleanPass.isBlank()) {
        val allRes = discoverAllTuyaDevices(bridge)
        if (allRes.isSuccess && allRes.getOrThrow().isNotEmpty()) {
            return@withContext allRes
        }
        return@withContext Result.failure(
            Exception("Enter your SmartLife email/phone and password to sync existing devices, or use Add Device to pair a blinking device.")
        )
    }

    val combined = LinkedHashMap<String, DiscoveredTuyaDevice>()
    val appCtx = ClaraApplication.appContextOrNull()
    val isEmailLogin = cleanUser.contains("@")

    // Auto-detect across preferred country code first, then EU (44), US (1), CN (86), and IN (91)
    val regionCandidates = listOf(preferredCountry, "44", "1", "86", "91").distinct()
    var matchedRegionCode = preferredCountry
    var authenticatedUser: User? = null
    var lastSdkError = ""

    if (ClaraApplication.isTuyaSdkReady(appCtx)) {
        for (candidateRegion in regionCandidates) {
            val attemptOutcome = suspendCancellableCoroutine<Pair<User?, String>> { cont ->
                val callback = object : ILoginCallback {
                    override fun onSuccess(user: User?) {
                        if (cont.isActive) cont.resume(user to "")
                    }

                    override fun onError(code: String?, error: String?) {
                        if (cont.isActive) cont.resume(null to "${code.orEmpty()}: ${error.orEmpty()}")
                    }
                }
                try {
                    if (isEmailLogin) {
                        ThingHomeSdk.getUserInstance().loginWithEmail(
                            candidateRegion,
                            cleanUser,
                            cleanPass,
                            callback
                        )
                    } else {
                        ThingHomeSdk.getUserInstance().loginWithPhonePassword(
                            candidateRegion,
                            cleanUser.removePrefix("+"),
                            cleanPass,
                            callback
                        )
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null to (e.message ?: "SDK login exception"))
                }
            }

            if (attemptOutcome.first != null || ThingHomeSdk.getUserInstance().isLogin) {
                authenticatedUser = attemptOutcome.first ?: ThingHomeSdk.getUserInstance().user
                matchedRegionCode = candidateRegion
                break
            } else {
                lastSdkError = attemptOutcome.second
                val lowerErr = lastSdkError.lowercase(Locale.US)
                // Stop probing other regions if password itself is explicitly wrong for an existing user
                if (lowerErr.contains("password") && !lowerErr.contains("not exist") && !lowerErr.contains("user")) {
                    break
                }
            }
        }
    }

    if (authenticatedUser != null || (ClaraApplication.isTuyaSdkReady(appCtx) && ThingHomeSdk.getUserInstance().isLogin)) {
        val uid = authenticatedUser?.uid.orEmpty()
        val sid = authenticatedUser?.sid.orEmpty()
        val regionEndpoint = regionEndpointForCountryCode(matchedRegionCode)
        cachedWorkingHost = regionEndpoint
        if (appCtx != null) {
            ClaraSecretVault.saveTuyaConsumerSession(
                context = appCtx,
                sid = sid,
                uid = uid,
                regionEndpoint = regionEndpoint
            )
            cachedConsumerAccessToken = ""

            val homeSyncRes = selectAndSyncPrimaryTuyaHome(appCtx)
            if (homeSyncRes.isSuccess) {
                homeSyncRes.getOrThrow().forEach {
                    combined[it.deviceId] = it
                    discoveredTuyaDeviceCache[it.deviceId] = it
                }
            }
        } else {
            val nativeDevices = discoverNativeSdkDevices(forceRefresh = true)
            if (nativeDevices.isSuccess) {
                nativeDevices.getOrThrow().forEach {
                    combined[it.deviceId] = it
                    discoveredTuyaDeviceCache[it.deviceId] = it
                }
            }
        }
    } else if (lastSdkError.isNotBlank()) {
        DiscoveryFlightRecorder.record(
            eventType = DiscoveryEventType.FAILED,
            transportSource = "TUYA_NATIVE_SDK",
            vendor = "SmartLife Official Auth",
            qualificationStage = "loginWithEmailAndPassword",
            outcomeReason = "Official SmartLife login failed across regions ($lastSdkError)",
            protocolResultCode = "AUTH_REJECTED"
        )
    }

    val effectiveBridge = if (!authenticatedUser?.uid.isNullOrBlank() && bridge.tuyaUid.isBlank()) {
        bridge.copy(
            tuyaUid = authenticatedUser?.uid.orEmpty(),
            tuyaApiHost = regionEndpointForCountryCode(matchedRegionCode)
        )
    } else {
        bridge
    }
    val openApiRes = discoverAllTuyaDevices(effectiveBridge)
    if (openApiRes.isSuccess) {
        openApiRes.getOrThrow().forEach {
            combined[it.deviceId] = it
            discoveredTuyaDeviceCache[it.deviceId] = it
        }
    }

    if (combined.isNotEmpty() || (ClaraApplication.isTuyaSdkReady(appCtx) && ThingHomeSdk.getUserInstance().isLogin)) {
        return@withContext Result.success(combined.values.toList())
    }

    Result.failure(
        Exception(
            if (lastSdkError.isNotBlank()) {
                "SmartLife sign-in did not succeed ($lastSdkError). Verify your SmartLife email/password."
            } else {
                "Could not sign in to '$cleanUser'. Verify your SmartLife credentials and Tuya SDK configuration."
            }
        )
    )
}

// Step 1 of In-App QR Login: Request a live QR token
suspend fun TuyaApiClient.requestInAppQrCode(userCode: String): Result<TuyaQrSession> = withContext(Dispatchers.IO) {
    val cleanCode = userCode.trim()
    if (cleanCode.isBlank()) {
        return@withContext Result.failure(
            Exception("Please enter your Smart Life account code to generate a sign-in QR code.")
        )
    }
    try {
        val urlStr = "$IOTBING_BASE/v1.0/m/life/home-assistant/qrcode/tokens" +
                "?clientid=${URLEncoder.encode(HA_SHARING_CLIENT_ID, "UTF-8")}" +
                "&usercode=${URLEncoder.encode(cleanCode, "UTF-8")}" +
                "&schema=${URLEncoder.encode(HA_SHARING_SCHEMA, "UTF-8")}"

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 5000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        OutputStreamWriter(conn.outputStream).use { it.write("{}"); it.flush() }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        if (code in 200..299 && body.isNotBlank()) {
            val json = JSONObject(body)
            if (json.optBoolean("success", false)) {
                val qrcodeToken = json.optJSONObject("result")?.optString("qrcode", "") ?: ""
                if (qrcodeToken.isNotBlank()) {
                    val qrPayload = "tuyaSmart--qrLogin?token=$qrcodeToken"
                    return@withContext Result.success(
                        TuyaQrSession(
                            qrToken = qrcodeToken,
                            qrContentUrl = qrPayload,
                            userCode = cleanCode
                        )
                    )
                }
            }
            return@withContext Result.failure(Exception("Could not generate QR code. Please check your account code."))
        }
        Result.failure(Exception("Smart Life QR sign-in service is temporarily unavailable."))
    } catch (e: Exception) {
            com.jarvis.smarthomelauncher.core.ErrorClassifier.classifyException(e).let { category ->
                com.jarvis.smarthomelauncher.core.DiscoveryFlightRecorder.record(
                    eventType = com.jarvis.smarthomelauncher.core.DiscoveryEventType.FAILED,
                    transportSource = "TuyaCloudClient",
                    hardwareId = "-",
                    vendor = "Vendor",
                    qualificationStage = "Exception Caught",
                    outcomeReason = com.jarvis.smarthomelauncher.core.ErrorClassifier.getHumanReadableRemediation(category, e),
                    rawPayload = e.stackTraceToString().take(200)
                )
            }
            
        Result.failure(Exception("Could not connect to Smart Life QR service. Please check your internet connection."))
    
        }
}

// Step 2 of In-App QR Login: Poll whether the user scanned & confirmed the QR code in their Smart Life app
suspend fun TuyaApiClient.pollInAppQrLoginResult(
    qrToken: String,
    userCode: String
): Result<String?> = withContext(Dispatchers.IO) {
    try {
        val urlStr = "$IOTBING_BASE/v1.0/m/life/home-assistant/qrcode/tokens/$qrToken" +
                "?clientid=${URLEncoder.encode(HA_SHARING_CLIENT_ID, "UTF-8")}" +
                "&usercode=${URLEncoder.encode(userCode.trim(), "UTF-8")}"

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4500
            readTimeout = 4500
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        if (code in 200..299 && body.isNotBlank()) {
            val json = JSONObject(body)
            if (json.optBoolean("success", false)) {
                val res = json.optJSONObject("result")
                val uid = res?.optString("uid", "")
                    ?: res?.optString("username", "")
                    ?: ""
                if (!uid.isNullOrBlank()) {
                    return@withContext Result.success(uid)
                }
            }
        }
        Result.success(null)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

private fun executeSignedRequest(
    host: String,
    accessId: String,
    accessSecret: String,
    accessToken: String?,
    method: String,
    pathWithQuery: String,
    bodyJson: String = ""
): JSONObject? {
    if (com.jarvis.smarthomelauncher.integrations.JarvisLiveBridge.isCircuitOpen("TUYA_CLOUD")) {
        throw com.jarvis.smarthomelauncher.integrations.CircuitBreakerOpenException("Cloud Degraded \u2014 Running Locally")
    }
    val cleanHost = host.trim().trimEnd('/')
    val t = System.currentTimeMillis().toString()
    val nonce = UUID.randomUUID().toString()
    val contentHash = sha256Hex(bodyJson)
    val stringToSign = "$method\n$contentHash\n\n$pathWithQuery"
    val signPayload = accessId.trim() + (accessToken ?: "") + t + nonce + stringToSign
    val sign = hmacSha256UpperHex(accessSecret.trim(), signPayload)

    val conn = (URL("$cleanHost$pathWithQuery").openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 3800
        readTimeout = 3800
        setRequestProperty("client_id", accessId.trim())
        setRequestProperty("sign", sign)
        setRequestProperty("t", t)
        setRequestProperty("nonce", nonce)
        setRequestProperty("sign_method", "HMAC-SHA256")
        setRequestProperty("Content-Type", "application/json")
        if (!accessToken.isNullOrBlank()) {
            setRequestProperty("access_token", accessToken)
        }
        if (method == "POST" && bodyJson.isNotEmpty()) {
            doOutput = true
        }
    }

    if (method == "POST" && bodyJson.isNotEmpty()) {
        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(bodyJson)
            writer.flush()
        }
    }

    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val respText = stream?.bufferedReader()?.use { it.readText() } ?: ""
    conn.disconnect()
    return if (respText.isNotBlank()) JSONObject(respText) else null
}

private const val TUYA_PROACTIVE_REFRESH_WINDOW_MS = 15 * 60_000L

suspend fun TuyaApiClient.getAccessToken(bridge: BridgeSettings): Result<String> = withContext(Dispatchers.IO) {
    val cleanId = bridge.tuyaAccessId.trim()
    val cleanSecret = bridge.tuyaAccessSecret.trim()
    if (cleanId.isBlank() || cleanSecret.isBlank()) {
        return@withContext Result.failure(Exception("Smart Life cloud service is not linked yet."))
    }
    val nowMs = System.currentTimeMillis()
    if (cachedToken.isNotBlank() && nowMs < (tokenExpiryMs - TUYA_PROACTIVE_REFRESH_WINDOW_MS)) {
        return@withContext Result.success(cachedToken)
    }

    val savedVaultEndpoint = ClaraApplication.appContextOrNull()
        ?.let { ClaraSecretVault.getTuyaConsumerRegionEndpoint(it) }
        .orEmpty()
    val candidateHosts = listOf(
        savedVaultEndpoint,
        bridge.tuyaApiHost.trim().ifBlank { DEFAULT_TUYA_API_HOST },
        "https://openapi.tuyaeu.com",
        "https://openapi-weaz.tuyaeu.com",
        "https://openapi.tuyaus.com",
        "https://openapi.tuyacn.com",
        "https://openapi.tuyain.com"
    ).filter { it.isNotBlank() }.distinct()

    var lastErr = "Could not connect to Smart Life cloud service."
    for (host in candidateHosts) {
        try {
            val resp = executeSignedRequest(
                host = host,
                accessId = cleanId,
                accessSecret = cleanSecret,
                accessToken = null,
                method = "GET",
                pathWithQuery = "/v1.0/token?grant_type=1"
            ) ?: continue

            if (resp.optBoolean("success", false)) {
                val resObj = resp.optJSONObject("result")
                val token = resObj?.optString("access_token", "") ?: ""
                val expireSec = resObj?.optLong("expire_time", 7200L) ?: 7200L
                if (token.isNotBlank()) {
                    cachedToken = token
                    cachedWorkingHost = host.trimEnd('/')
                    tokenExpiryMs = System.currentTimeMillis() + (expireSec - 120L) * 1000L
                    return@withContext Result.success(token)
                }
            } else {
                lastErr = "Could not authenticate with Smart Life cloud."
            }
        } catch (e: Exception) {
            com.jarvis.smarthomelauncher.core.ErrorClassifier.classifyException(e).let { category ->
                com.jarvis.smarthomelauncher.core.DiscoveryFlightRecorder.record(
                    eventType = com.jarvis.smarthomelauncher.core.DiscoveryEventType.FAILED,
                    transportSource = "TuyaCloudClient",
                    hardwareId = "-",
                    vendor = "Vendor",
                    qualificationStage = "Exception Caught",
                    outcomeReason = com.jarvis.smarthomelauncher.core.ErrorClassifier.getHumanReadableRemediation(category, e),
                    rawPayload = e.stackTraceToString().take(200)
                )
            }
            
            lastErr = "Smart Life cloud is temporarily unreachable."
        
        }
    }
    if (cachedToken.isNotBlank() && System.currentTimeMillis() < tokenExpiryMs) {
        return@withContext Result.success(cachedToken)
    }
    Result.failure(Exception(lastErr))
}

/**
 * Discovers and MERGES devices from all 3 verified sources:
 * 1. Native ThingHomeSdk (`Clara Home`)
 * 2. Consumer Smart Life Token (`cachedConsumerAccessToken`)
 * 3. Tuya Cloud OpenAPI (strictly scoped to `bridge.tuyaUid` or active LAN UDP 6666/6667 `gwId`s —
 *    never dumps project-wide associated-users onto an empty home).
 */
suspend fun TuyaApiClient.discoverAllTuyaDevices(bridge: BridgeSettings): Result<List<DiscoveredTuyaDevice>> =
    withContext(Dispatchers.IO) {
        val mergedDevices = LinkedHashMap<String, DiscoveredTuyaDevice>()

        // 1. Collect Native ThingHomeSdk devices
        val nativeRes = discoverNativeSdkDevices(forceRefresh = false)
        if (nativeRes.isSuccess) {
            for (dev in nativeRes.getOrThrow()) {
                mergedDevices[dev.deviceId] = dev
                discoveredTuyaDeviceCache[dev.deviceId] = dev
            }
        }

        val sdkSignedIn = ClaraApplication.isTuyaSdkReady() &&
                runCatching { ThingHomeSdk.getUserInstance().isLogin }.getOrDefault(false)
        val savedVaultUid = ClaraApplication.appContextOrNull()
            ?.let { ClaraSecretVault.getTuyaConsumerUid(it) }
            .orEmpty()
        val hasExplicitUserCloudAccount = sdkSignedIn ||
                bridge.tuyaUid.trim().isNotBlank() ||
                savedVaultUid.isNotBlank()
        // If no personal Smart Life cloud account is linked, filter out stale/offline ghost devices
        // from ThingHomeSdk's silent staging home unless they are actively broadcasting on local LAN.
        if (!hasExplicitUserCloudAccount) {
            val activeLanIds = discoveredLanTuyaDeviceIds
            val staleEntries = mergedDevices.entries
                .filter { (id, dev) ->
                    TuyaApiClient.localIpByDevice[id].isNullOrBlank() &&
                            id !in activeLanIds &&
                            !dev.isOnline
                }
            for ((staleId, staleDev) in staleEntries) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FILTERED,
                    transportSource = "TUYA_NATIVE_SDK",
                    hardwareId = staleId,
                    vendor = staleDev.name,
                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                    outcomeReason = "Filtered offline staging-home Tuya device without active LAN presence",
                    cloudId = staleId
                )
                mergedDevices.remove(staleId)
            }
        }
        val hasUnresolvedLanDevices = discoveredLanTuyaDeviceIds.any { !mergedDevices.containsKey(it) }
        val shouldQueryCloudOpenApi = bridge.tuyaUid.trim().isNotBlank() || hasUnresolvedLanDevices

        // 3. Collect Tuya Cloud OpenAPI devices ONLY when an explicit tuyaUid is configured
        //    or when a physical device is actively broadcasting its gwId on local UDP 6666/6667.
        //    Stage 1 Fix: Never dump project-wide associated-users devices when local home is empty.
        if (shouldQueryCloudOpenApi) {
            val tokenRes = getAccessToken(bridge)
            if (tokenRes.isFailure) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "TUYA_CLOUD_OPENAPI",
                    vendor = "Tuya Cloud OpenAPI",
                    qualificationStage = "discoverAllTuyaDevices.token",
                    outcomeReason = tokenRes.exceptionOrNull()?.message ?: "Could not obtain Tuya Cloud OpenAPI token"
                )
            }
            if (tokenRes.isSuccess) {
                val token = tokenRes.getOrThrow()
                val cleanId = bridge.tuyaAccessId.trim()
                val cleanSecret = bridge.tuyaAccessSecret.trim()
                val cleanUid = bridge.tuyaUid.trim()

                try {
                    val rawDevices = mutableListOf<JSONObject>()

                    if (cleanUid.isNotBlank()) {
                        val userResp = executeSignedRequest(
                            host = cachedWorkingHost,
                            accessId = cleanId,
                            accessSecret = cleanSecret,
                            accessToken = token,
                            method = "GET",
                            pathWithQuery = "/v1.0/users/$cleanUid/devices"
                        )
                        val arr = userResp?.optJSONArray("result")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                arr.optJSONObject(i)?.let { rawDevices.add(it) }
                            }
                        }
                    }

                    // Query only devices verified to be actively broadcasting on our local Wi-Fi LAN
                    for (lanDevId in discoveredLanTuyaDeviceIds) {
                        if (rawDevices.none { it.optString("id") == lanDevId } &&
                            !mergedDevices.containsKey(lanDevId)
                        ) {
                            val directDevResp = executeSignedRequest(
                                host = cachedWorkingHost,
                                accessId = cleanId,
                                accessSecret = cleanSecret,
                                accessToken = token,
                                method = "GET",
                                pathWithQuery = "/v1.0/devices/$lanDevId"
                            )
                            if (directDevResp?.optBoolean("success", false) == true) {
                                directDevResp.optJSONObject("result")?.let { rawDevices.add(it) }
                            }
                        }
                    }

                    val seenIds = mutableSetOf<String>()
                    for (d in rawDevices) {
                        val devId = d.optString("id", "").trim()
                        val name = d.optString("name", "Smart Plug")
                        val category = d.optString("category", "").lowercase(Locale.US)
                        val rawMac = CrossEcosystemIdentityCorrelator.normalizeMac(
                            d.optString("mac", d.optString("uuid", ""))
                        )
                        val knownLanIp = if (devId.isNotBlank()) localIpByDevice[devId].orEmpty() else ""
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                            transportSource = "TUYA_CLOUD_OPENAPI",
                            hardwareId = devId,
                            macAddress = rawMac,
                            ipAddress = knownLanIp,
                            port = if (knownLanIp.isNotBlank()) 6668 else 0,
                            vendor = "$name ($category)",
                            confidenceScore = 0.80f,
                            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                            outcomeReason = "Tuya Cloud OpenAPI device payload received",
                            cloudId = devId
                        )
                        if (devId.isBlank()) {
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.FILTERED,
                                transportSource = "TUYA_CLOUD_OPENAPI",
                                macAddress = rawMac,
                                vendor = name,
                                qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                outcomeReason = "Filtered Tuya OpenAPI record missing device id"
                            )
                            continue
                        }
                        if (!seenIds.add(devId) || mergedDevices.containsKey(devId)) {
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.FILTERED,
                                transportSource = "TUYA_CLOUD_OPENAPI",
                                hardwareId = devId,
                                macAddress = rawMac,
                                ipAddress = knownLanIp,
                                port = if (knownLanIp.isNotBlank()) 6668 else 0,
                                vendor = "$name ($category)",
                                qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                                outcomeReason = "Suppressed duplicate Tuya OpenAPI record already present in session merge",
                                cloudId = devId
                            )
                            continue
                        }
                        val vaultCtx = ClaraApplication.appContextOrNull()
                        val cloudPv = d.optString("pv", d.optString("product_ver", "")).trim()
                        if (cloudPv.isNotBlank()) {
                            protocolVersionByDevice[devId] = cloudPv
                            if (vaultCtx != null) {
                                ClaraDeviceRegistryStore.getInstance(vaultCtx)
                                    .updatePersistedProtocolVersion(devId, cloudPv)
                            }
                        }
                        val rawCloudKey = d.optString("local_key", d.optString("localKey", "")).trim()
                        val localKey = if (rawCloudKey.length == 16) {
                            localKeyByDevice[devId] = rawCloudKey
                            if (vaultCtx != null) {
                                ClaraSecretVault.persistBoundTuyaCredentialsImmediate(
                                    context = vaultCtx,
                                    deviceId = devId,
                                    localKey = rawCloudKey,
                                    ipAddress = knownLanIp,
                                    macAddress = rawMac
                                )
                            }
                            rawCloudKey
                        } else {
                            localKeyByDevice[devId]
                                ?: vaultCtx?.let {
                                    ClaraSecretVault.getTuyaLocalKey(it, devId, rawMac)
                                }.orEmpty()
                        }
                        val rawOnline = d.optBoolean("online", true)
                        val isOnline = isDeviceReachableOrOnline(
                            deviceId = devId,
                            ipAddress = localIpByDevice[devId].orEmpty(),
                            reportedCloudOnline = rawOnline
                        )

                        var isActive = false
                        var socket2Active = false
                        var hasSocket2 = false
                        var hasEnergyTelemetry = false
                        var currentTemp = 20.5f
                        var targetTemp = 21.0f
                        var levelOrBattery = 80f
                        var powerWatts = 0f
                        var voltageVolts = 0f
                        var currentMilliamps = 0f
                        var totalEnergyKwh = 0f
                        var countdown1Sec = 0
                        var countdown2Sec = 0
                        var fanSpeed = 3
                        var fanWindMode = "NORMAL"
                        var fanOscillating = false
                        var fanIonizer = false
                        var fanTimerHours = 0
                        var switchCode = "switch_1"
                        var switch2Code = "switch_2"
                        var tempSetCode = "temp_set"
                        var tempDivisor = 1.0f
                        val openApiCaps = mutableListOf<DeviceCapabilityDescriptor>()

                        val statusArr = d.optJSONArray("status") ?: JSONArray()
                        for (j in 0 until statusArr.length()) {
                            val st = statusArr.optJSONObject(j) ?: continue
                            val code = st.optString("code", "")
                            val value = st.opt("value")

                            when {
                                code in listOf("switch_1", "switch1", "switch", "switch_led", "power_go", "switch_go", "power", "fan_switch") && value is Boolean -> {
                                    switchCode = code
                                    isActive = value
                                    openApiCaps.add(
                                        DeviceCapabilityDescriptor(
                                            key = code,
                                            dpId = "1",
                                            label = "Power Switch",
                                            kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                                            isWritable = true,
                                            isPinnedToHero = true,
                                            currentValue = value.toString()
                                        )
                                    )
                                }
                                code in listOf("switch_2", "switch2") && value is Boolean -> {
                                    switch2Code = code
                                    hasSocket2 = true
                                    socket2Active = value
                                    openApiCaps.add(
                                        DeviceCapabilityDescriptor(
                                            key = code,
                                            dpId = "2",
                                            label = "Socket 2 Power",
                                            kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                                            isWritable = true,
                                            isPinnedToHero = true,
                                            currentValue = value.toString()
                                        )
                                    )
                                }
                                code in listOf("fan_speed", "fan_speed_percent", "windspeed") -> {
                                    val parsedSpd = value?.toString()?.filter { it.isDigit() }?.toIntOrNull()
                                    if (parsedSpd != null) {
                                        fanSpeed = if (parsedSpd > 6) (parsedSpd / 16).coerceIn(1, 6) else parsedSpd.coerceIn(1, 6)
                                        levelOrBattery = (fanSpeed / 6f) * 100f
                                    }
                                }
                                code in listOf("mode", "fan_mode") && value is String -> {
                                    val vUpper = value.uppercase(Locale.US)
                                    fanWindMode = when {
                                        vUpper.contains("NAT") -> "NATURAL"
                                        vUpper.contains("SLP") || vUpper.contains("SLEEP") -> "SLEEP"
                                        vUpper.contains("AUTO") || vUpper.contains("SMART") -> "AUTO"
                                        else -> "NORMAL"
                                    }
                                }
                                code in listOf("switch_horizontal", "switch_vertical", "shake", "swing") && value is Boolean -> {
                                    fanOscillating = value
                                }
                                code in listOf("anion", "ionizer", "fresh") && value is Boolean -> {
                                    fanIonizer = value
                                }
                                code == "countdown_set" -> {
                                    val parsedHrs = value?.toString()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                                    fanTimerHours = parsedHrs.coerceIn(0, 12)
                                }
                                code in listOf("temp_current", "va_temperature", "current_temp", "room_temp", "temp_indoor") && value is Number -> {
                                    val raw = value.toFloat()
                                    currentTemp = if (raw > 55f) raw / 10f else raw
                                    openApiCaps.add(
                                        DeviceCapabilityDescriptor(
                                            key = code,
                                            dpId = "4",
                                            label = "Current Temperature",
                                            kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                                            isWritable = false,
                                            isPinnedToHero = false,
                                            currentValue = "%.1f".format(Locale.US, currentTemp),
                                            unit = "\u00B0C",
                                            rendererType = CapabilityRendererType.SENSOR_BADGE
                                        )
                                    )
                                }
                                code in listOf("temp_set", "set_temp", "upper_temp", "target_temp") && value is Number -> {
                                    tempSetCode = code
                                    val raw = value.toFloat()
                                    if (raw > 55f) {
                                        tempDivisor = 10f
                                        targetTemp = raw / 10f
                                    } else {
                                        tempDivisor = 1f
                                        targetTemp = raw
                                    }
                                    openApiCaps.add(
                                        DeviceCapabilityDescriptor(
                                            key = code,
                                            dpId = "3",
                                            label = "Target Temperature",
                                            kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                                            isWritable = true,
                                            isPinnedToHero = true,
                                            currentValue = "%.1f".format(Locale.US, targetTemp),
                                            minValue = 5f,
                                            maxValue = 35f,
                                            stepValue = if (tempDivisor >= 10f) 0.5f else 1f,
                                            unit = "\u00B0C",
                                            rendererType = CapabilityRendererType.TEMPERATURE_DIAL
                                        )
                                    )
                                }
                                code in listOf("bright_value_v2", "bright_value") && value is Number -> {
                                    val raw = value.toFloat()
                                    levelOrBattery = if (raw > 100f) (raw / 10f).coerceIn(1f, 100f) else raw.coerceIn(1f, 100f)
                                }
                                code in listOf("electricity_left", "residual_electricity", "battery_percentage") && value is Number -> {
                                    levelOrBattery = value.toFloat().coerceIn(0f, 100f)
                                }
                                code == "cur_power" && value is Number -> {
                                    hasEnergyTelemetry = true
                                    powerWatts = value.toFloat() / 10f
                                    openApiCaps.add(
                                        DeviceCapabilityDescriptor(
                                            key = "cur_power",
                                            dpId = "19",
                                            label = "Live Power",
                                            kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                                            isWritable = false,
                                            isPinnedToHero = false,
                                            currentValue = "%.1f".format(Locale.US, powerWatts),
                                            unit = "W"
                                        )
                                    )
                                }
                                code == "cur_voltage" && value is Number -> {
                                    hasEnergyTelemetry = true
                                    voltageVolts = value.toFloat() / 10f
                                }
                                code == "cur_current" && value is Number -> {
                                    hasEnergyTelemetry = true
                                    currentMilliamps = value.toFloat()
                                }
                                code == "add_ele" && value is Number -> {
                                    hasEnergyTelemetry = true
                                    totalEnergyKwh = value.toFloat() / 1000f
                                }
                                code in listOf("countdown_1", "countdown") && value is Number -> {
                                    countdown1Sec = value.toInt()
                                    openApiCaps.add(
                                        DeviceCapabilityDescriptor(
                                            key = code,
                                            dpId = "9",
                                            label = "Countdown Timer",
                                            kind = DeviceCapabilityKind.COUNTDOWN_TIMER,
                                            isWritable = true,
                                            isPinnedToHero = true,
                                            currentValue = countdown1Sec.toString(),
                                            minValue = 0f,
                                            maxValue = 86400f,
                                            unit = "s",
                                            rendererType = CapabilityRendererType.CHIP_GROUP,
                                            presets = listOf(
                                                CapabilityPreset(value = 900f, label = "15m Timer", icon = "TIMER"),
                                                CapabilityPreset(value = 1800f, label = "30m Timer", icon = "TIMER"),
                                                CapabilityPreset(value = 3600f, label = "1h Timer", icon = "TIMER")
                                            )
                                        )
                                    )
                                }
                                code == "countdown_2" && value is Number -> {
                                    countdown2Sec = value.toInt()
                                }
                            }
                        }

                        val openApiSchemaHash = CapabilitySchemaHasher.computeHash(openApiCaps)
                        val classification = CapabilityClassifier.resolve(
                            capabilities = openApiCaps,
                            categoryCode = category,
                            multiGangSocketCount = if (hasSocket2) 2 else 1,
                            hasPowerTelemetry = hasEnergyTelemetry,
                            schemaHash = openApiSchemaHash,
                            titleHint = name
                        )
                        CapabilityClassifier.recordResolution(devId, classification)
                        val suggestedType = classification.suggestedTileType
                        if (classification.deviceSubtype == DeviceSubtype.TOWER_FAN && openApiCaps.size <= 1) {
                            openApiCaps.clear()
                            openApiCaps.addAll(
                                defaultProBreezeFanCapabilities(
                                    isActive = isActive,
                                    speedLevel = fanSpeed,
                                    fanMode = fanWindMode,
                                    oscillating = fanOscillating,
                                    ionizerActive = fanIonizer,
                                    timerHours = fanTimerHours
                                )
                            )
                        }
                        val isMultiGang = hasSocket2 &&
                                classification.deviceSubtype != DeviceSubtype.VACUUM &&
                                classification.deviceSubtype != DeviceSubtype.TOWER_FAN
                        val effectiveLevelOrSocket2 = if (isMultiGang) {
                            if (socket2Active) 100f else 0f
                        } else {
                            levelOrBattery
                        }
                        switchDpCodeByDevice[devId] = switchCode
                        if (isMultiGang) {
                            switch2DpCodeByDevice[devId] = switch2Code
                        } else {
                            switch2DpCodeByDevice.remove(devId)
                        }
                        tempSetDpCodeByDevice[devId] = tempSetCode
                        tempScaleByDevice[devId] = tempDivisor
                        capabilitiesByDevice[devId] = openApiCaps
                        hasPowerTelemetryByDevice[devId] = hasEnergyTelemetry

                        val discoveredItem = DiscoveredTuyaDevice(
                            deviceId = devId,
                            name = name,
                            category = category,
                            localKey = localKey,
                            isOnline = isOnline,
                            suggestedType = suggestedType,
                            isActive = isActive,
                            isSocket2Active = if (isMultiGang) socket2Active else false,
                            multiGangSocketCount = if (isMultiGang) 2 else 1,
                            currentTempC = currentTemp,
                            targetTempC = targetTemp,
                            brightnessOrBatteryPct = effectiveLevelOrSocket2,
                            powerWatts = powerWatts,
                            voltageVolts = voltageVolts,
                            currentMilliamps = currentMilliamps,
                            totalEnergyKwh = totalEnergyKwh,
                            countdown1Seconds = countdown1Sec,
                            countdown2Seconds = countdown2Sec,
                            primarySwitchCode = switchCode,
                            tempSetCode = tempSetCode,
                            tempScaleDivisor = tempDivisor,
                            capabilities = openApiCaps,
                            hasPowerTelemetry = hasEnergyTelemetry,
                            fanSpeedLevel = fanSpeed,
                            fanMode = fanWindMode,
                            fanOscillating = fanOscillating,
                            fanIonizerActive = fanIonizer,
                            fanTimerHours = fanTimerHours,
                            deviceClass = classification.deviceClass,
                            deviceSubtype = classification.deviceSubtype,
                            classificationConfidence = classification.confidence,
                            classificationSource = classification.source,
                            classificationVersion = classification.version,
                            classificationLastEvaluatedEpochMs = classification.evaluatedAtEpochMs
                        )
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.IDENTIFIED,
                            transportSource = "TUYA_CLOUD_OPENAPI",
                            hardwareId = devId,
                            macAddress = rawMac,
                            ipAddress = knownLanIp,
                            port = if (knownLanIp.isNotBlank()) 6668 else 0,
                            vendor = "$name ($category)",
                            confidenceScore = classification.confidence,
                            qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                            outcomeReason = "Introspected Tuya OpenAPI schema (${openApiCaps.size} DPs, keyLen=${localKey.length})",
                            cloudId = devId
                        )
                        mergedDevices[devId] = discoveredItem
                        discoveredTuyaDeviceCache[devId] = discoveredItem
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    ErrorClassifier.classifyException(e).let { category ->
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.FAILED,
                            transportSource = "TUYA_CLOUD_OPENAPI",
                            vendor = "Tuya Cloud OpenAPI",
                            qualificationStage = "discoverAllTuyaDevices",
                            outcomeReason = ErrorClassifier.getHumanReadableRemediation(category, e),
                            rawPayload = e.stackTraceToString().take(200)
                        )
                    }
                }
            }
        }

        if (mergedDevices.isNotEmpty()) {
            return@withContext Result.success(mergedDevices.values.toList())
        }
        Result.failure(Exception("No plugs, heaters, fans, or smart switches found yet."))
    }

suspend fun TuyaApiClient.syncLiveTuyaStates(
    bridge: BridgeSettings,
    tiles: MutableList<SmartTile>
) {
    if (System.currentTimeMillis() - lastCommandTimestampMs < 5000L) return
    val pollStartMs = System.currentTimeMillis()
    val res = discoverAllTuyaDevices(bridge)
    val pollLatencyMs = (System.currentTimeMillis() - pollStartMs).coerceAtLeast(10L)
    if (res.isFailure) {
        val tileProbeInputs = withContext(Dispatchers.Main) {
            tiles.map { t -> Triple(t.id, t.serialNumber.ifBlank { t.entityId }, t.ipAddress) }
        }
        val lanProbeByTileId = withContext(Dispatchers.IO) {
            runCatching { SmartHomeApiClient.listenForTuyaUdpBroadcasts(timeoutMs = 700L) }
            val resultMap = HashMap<String, Pair<String, Boolean>>()
            for ((tileId, devId, fallbackIp) in tileProbeInputs) {
                if (devId.isBlank() && fallbackIp.isBlank()) continue
                val knownIp = localIpByDevice[devId].orEmpty().ifBlank { fallbackIp }
                val ok = isDeviceReachableOrOnline(
                    deviceId = devId,
                    ipAddress = knownIp,
                    reportedCloudOnline = false
                )
                resultMap[tileId] = knownIp to ok
            }
            resultMap
        }
        val hasCloudConfigured = bridge.tuyaAccessId.trim().isNotBlank() ||
                !ClaraApplication.appContextOrNull()?.let { ClaraSecretVault.getTuyaConsumerUid(it) }.isNullOrBlank() ||
                ClaraApplication.isTuyaSdkReady()
        val errMsg = res.exceptionOrNull()?.message.orEmpty().ifBlank { "Tuya background poll skipped" }
        withContext(Dispatchers.Main) {
            for (i in tiles.indices) {
                val tile = tiles[i]
                val isTuyaTile = tile.activeProviderId.contains("TUYA", ignoreCase = true) ||
                        tile.type in setOf(
                    TileType.SMARTKNIGHT_PLUG,
                    TileType.SMARTLIFE_DEVICE,
                    TileType.TUYA_RADIATOR,
                    TileType.PROBREEZE_FAN,
                    TileType.AIRROBO_VACUUM,
                    TileType.LYTMI_SYNC_BOX
                )
                if (!isTuyaTile || !tile.isConfiguredAndLinked) continue
                val probePair = lanProbeByTileId[tile.id]
                val knownIp = probePair?.first ?: tile.ipAddress
                val lanReachable = probePair?.second == true
                if (lanReachable) {
                    if (!tile.isOnline || tile.lifecycleState != DeviceLifecycleState.ONLINE || (knownIp.isNotBlank() && tile.ipAddress != knownIp)) {
                        tiles[i] = tile.copy(
                            ipAddress = knownIp.ifBlank { tile.ipAddress },
                            isOnline = true,
                            lifecycleState = DeviceLifecycleState.ONLINE,
                            transactionState = CommandTransactionState.IDLE
                        )
                    }
                    JarvisLiveBridge.recordBackgroundPollOutcome(
                        tile = tiles[i],
                        providerId = "TUYA_LAN_6668",
                        succeeded = true,
                        latencyMs = pollLatencyMs
                    )
                } else if (hasCloudConfigured) {
                    JarvisLiveBridge.recordBackgroundPollOutcome(
                        tile = tile,
                        providerId = tile.activeProviderId.ifBlank { "TUYA_LAN_6668" },
                        succeeded = false,
                        latencyMs = pollLatencyMs,
                        failureReason = errMsg
                    )
                }
            }
        }
        return
    }
    val discoveredList = res.getOrThrow()
    if (discoveredList.isEmpty()) return

    val map = discoveredList.associateBy { it.deviceId }
    val appCtx = ClaraApplication.appContextOrNull()
    val audioManager = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    var onlineCount = 0
    var offlineCount = 0

    coroutineScope {
        val scope = this
        for (dev in discoveredList) {
            ensureLiveDeviceListenerRegistered(dev.deviceId, tiles, scope)
            if (dev.isOnline) onlineCount++ else offlineCount++
        }

        JarvisLiveBridge.updateIntegrationDeviceCounts(
            integrationKey = "TUYA_LAN",
            displayName = "Tuya / SmartKnight / Lytmi LAN",
            onlineCount = onlineCount,
            offlineCount = offlineCount
        )

        val resolvedOnlineByDevId = withContext(Dispatchers.IO) {
            discoveredList.associate { dev ->
                val knownLanIp = localIpByDevice[dev.deviceId].orEmpty()
                dev.deviceId to isDeviceReachableOrOnline(
                    deviceId = dev.deviceId,
                    ipAddress = knownLanIp,
                    reportedCloudOnline = dev.isOnline
                )
            }
        }

        withContext(Dispatchers.Main) {
            // Phase 3.1: State synchronization only binds/updates existing dashboard tiles and never calls tiles.add(...)
            for (dev in discoveredList) {
                cacheDiscoveredDevice(dev)
                val alreadyInTiles = tiles.any {
                    it.entityId.equals(dev.deviceId, ignoreCase = true) ||
                            it.serialNumber.equals(dev.deviceId, ignoreCase = true)
                }
                if (!alreadyInTiles) {
                    val unlinkedIdx = tiles.indexOfFirst { t ->
                        t.entityId.isBlank() &&
                                t.serialNumber.isBlank() &&
                                t.title.equals(dev.name, ignoreCase = true)
                    }
                    if (unlinkedIdx >= 0) {
                        val current = tiles[unlinkedIdx]
                        val knownLanIp = localIpByDevice[dev.deviceId].orEmpty().ifBlank { current.ipAddress }
                        val resolvedOnline = resolvedOnlineByDevId[dev.deviceId] ?: dev.isOnline
                        val incomingAuth = if (knownLanIp.isNotBlank()) {
                            StateAuthorityTier.DEVICE_HARDWARE_TRUTH
                        } else {
                            StateAuthorityTier.CLOUD_SHADOW_STATE
                        }
                        val (evolvedCaps, evolvedHash) = DeviceRepository.mergeCapabilitiesWithSchemaEvolution(
                            existing = current,
                            incomingCapabilities = dev.capabilities
                        )
                        val incoming = current.copy(
                            title = current.title.ifBlank { dev.name },
                            ipAddress = knownLanIp,
                            isOnline = resolvedOnline,
                            isActive = dev.isActive,
                            isSocket2Active = dev.isSocket2Active,
                            multiGangSocketCount = dev.multiGangSocketCount,
                            temperature = dev.currentTempC,
                            targetTemperature = dev.targetTempC,
                            volume = dev.brightnessOrBatteryPct,
                            powerWatts = dev.powerWatts,
                            voltageVolts = dev.voltageVolts,
                            currentMilliamps = dev.currentMilliamps,
                            totalEnergyKwh = dev.totalEnergyKwh,
                            countdown1Seconds = dev.countdown1Seconds,
                            countdown2Seconds = dev.countdown2Seconds,
                            secretCode = dev.localKey.ifBlank { current.secretCode },
                            serialNumber = dev.deviceId,
                            entityId = dev.deviceId,
                            fanSpeedLevel = dev.fanSpeedLevel,
                            fanMode = dev.fanMode,
                            fanOscillating = dev.fanOscillating,
                            fanIonizerActive = dev.fanIonizerActive,
                            fanTimerHours = dev.fanTimerHours,
                            capabilities = evolvedCaps,
                            capabilitySchemaHash = evolvedHash,
                            hasExplicitPowerTelemetry = current.hasExplicitPowerTelemetry || dev.hasPowerTelemetry
                        )
                        tiles[unlinkedIdx] = DeviceRepository.reconcileRemoteTileUpdate(
                            existing = current,
                            incoming = incoming,
                            incomingAuthority = incomingAuth,
                            sourceName = if (knownLanIp.isNotBlank()) "Tuya LAN" else "Tuya Cloud"
                        )
                    }
                }
            }

            for (i in tiles.indices) {
                val tile = tiles[i]
                val live = map[tile.serialNumber]
                    ?: map[tile.entityId]
                    ?: discoveredList.firstOrNull {
                        it.suggestedType == tile.type && it.name.equals(tile.title, ignoreCase = true)
                    }
                    ?: continue

                val wasOffline = !tile.isOnline
                val resolvedOnline = resolvedOnlineByDevId[live.deviceId] ?: live.isOnline
                val (mergedCaps, mergedHash) = DeviceRepository.mergeCapabilitiesWithSchemaEvolution(
                    existing = tile,
                    incomingCapabilities = live.capabilities
                )

                val isSplitSocketChild = tile.channelId.startsWith("SPLIT_SOCKET_")
                val lowerTileTitle = tile.title.ifBlank { live.name }.trim().lowercase(Locale.US)
                val isPlugOrApplianceByTitle = lowerTileTitle.contains("plug") ||
                        lowerTileTitle.contains("socket") ||
                        lowerTileTitle.contains("pond") ||
                        lowerTileTitle.contains("pump") ||
                        lowerTileTitle.contains("kettle") ||
                        lowerTileTitle.contains("fryer") ||
                        lowerTileTitle.contains("coffee") ||
                        lowerTileTitle.contains("washer") ||
                        live.category.lowercase(Locale.US) in setOf("cz", "pc", "kg", "tdq", "bh", "ymq", "kf", "kfj", "kz", "af")
                val isPromotedClimateHeater = !isSplitSocketChild && !isPlugOrApplianceByTitle && (
                        tile.isThermostatTile ||
                                (live.deviceClass == DeviceClass.CLIMATE && live.deviceSubtype != DeviceSubtype.TOWER_FAN) ||
                                CapabilityClassifier.isClimateHeatingDevice(mergedCaps, live.category, tile.title.ifBlank { live.name })
                        )

                val updated = when {
                    isPromotedClimateHeater || (tile.type == TileType.TUYA_RADIATOR && !isPlugOrApplianceByTitle) -> {
                        val resolvedCurTemp = if (live.currentTempC > 0f) live.currentTempC else tile.temperature
                        val resolvedTgtTemp = if (live.targetTempC > 0f) live.targetTempC else tile.targetTemperature
                        val preservedMode = if (!live.isActive) {
                            "OFF"
                        } else if (tile.hvacMode.equals("ECO", ignoreCase = true) || tile.hvacMode.equals("BOOST", ignoreCase = true)) {
                            tile.hvacMode
                        } else {
                            "HEAT"
                        }
                        tile.copy(
                            type = if (tile.type.isSwitchType()) TileType.TUYA_RADIATOR else tile.type,
                            deviceClassOverride = DeviceClass.CLIMATE,
                            deviceSubtypeOverride = if (tile.deviceSubtype in setOf(DeviceSubtype.THERMOSTAT, DeviceSubtype.TRV)) {
                                tile.deviceSubtype
                            } else {
                                DeviceSubtype.RADIATOR
                            },
                            serialNumber = tile.serialNumber.ifBlank { live.deviceId },
                            entityId = tile.entityId.ifBlank { live.deviceId },
                            secretCode = live.localKey.ifBlank { tile.secretCode },
                            isOnline = resolvedOnline,
                            temperature = resolvedCurTemp,
                            targetTemperature = resolvedTgtTemp,
                            isActive = live.isActive,
                            hvacMode = preservedMode,
                            hvacStatus = if (live.isActive && resolvedTgtTemp > resolvedCurTemp + 0.05f) "HEATING" else "OFF",
                            capabilities = mergedCaps,
                            capabilitySchemaHash = mergedHash
                        )
                    }
                    tile.type == TileType.SMARTKNIGHT_PLUG || (isPlugOrApplianceByTitle && tile.type == TileType.TUYA_RADIATOR) -> {
                        val isManualClass = tile.classificationSource == ClassificationSource.MANUAL_OVERRIDE
                        val isSplitSocket = tile.channelId.startsWith("SPLIT_SOCKET_")
                        val sanitizedCaps = mergedCaps.map { cap ->
                            cap.copy(rendererType = CapabilityClassifier.inferRendererType(cap))
                        }
                        val inferredAppliance = AppliancePowerFingerprinter.inferApplianceFromTelemetry(
                            powerWatts = live.powerWatts,
                            titleHint = "${tile.title} ${tile.socket1Name} ${tile.socket2Name}",
                            previous = tile.detectedApplianceCategory
                        )
                        tile.copy(
                            type = if (!isManualClass && !isSplitSocket) live.suggestedType else TileType.SMARTKNIGHT_PLUG,
                            serialNumber = tile.serialNumber.ifBlank { live.deviceId },
                            entityId = tile.entityId.ifBlank { live.deviceId },
                            secretCode = live.localKey.ifBlank { tile.secretCode },
                            isOnline = resolvedOnline,
                            isActive = live.isActive,
                            isSocket2Active = live.isSocket2Active,
                            multiGangSocketCount = live.multiGangSocketCount,
                            temperature = if (live.currentTempC > 0f) live.currentTempC else tile.temperature,
                            targetTemperature = if (live.targetTempC > 0f) live.targetTempC else tile.targetTemperature,
                            volume = live.brightnessOrBatteryPct,
                            powerWatts = live.powerWatts,
                            voltageVolts = live.voltageVolts,
                            currentMilliamps = live.currentMilliamps,
                            totalEnergyKwh = live.totalEnergyKwh,
                            countdown1Seconds = live.countdown1Seconds,
                            countdown2Seconds = live.countdown2Seconds,
                            capabilities = sanitizedCaps,
                            capabilitySchemaHash = mergedHash,
                            hasExplicitPowerTelemetry = tile.hasExplicitPowerTelemetry || live.hasPowerTelemetry,
                            detectedApplianceCategory = inferredAppliance,
                            deviceClassOverride = when {
                                isManualClass -> tile.deviceClassOverride
                                isSplitSocket -> DeviceClass.SWITCH
                                else -> live.deviceClass
                            },
                            deviceSubtypeOverride = when {
                                isManualClass -> tile.deviceSubtypeOverride
                                isSplitSocket -> DeviceSubtype.SMART_PLUG
                                else -> live.deviceSubtype
                            }
                        )
                    }
                    tile.type in setOf(TileType.SMARTLIFE_DEVICE, TileType.TAPO_DEVICE, TileType.SONOFF_SWITCH) -> {
                        val isManualClass = tile.classificationSource == ClassificationSource.MANUAL_OVERRIDE
                        val sanitizedCaps = mergedCaps.map { cap ->
                            cap.copy(rendererType = CapabilityClassifier.inferRendererType(cap))
                        }
                        tile.copy(
                            type = if (!isManualClass) live.suggestedType else tile.type,
                            serialNumber = tile.serialNumber.ifBlank { live.deviceId },
                            entityId = tile.entityId.ifBlank { live.deviceId },
                            secretCode = live.localKey.ifBlank { tile.secretCode },
                            isOnline = resolvedOnline,
                            isActive = live.isActive,
                            isSocket2Active = live.isSocket2Active,
                            multiGangSocketCount = live.multiGangSocketCount,
                            temperature = if (live.currentTempC > 0f) live.currentTempC else tile.temperature,
                            targetTemperature = if (live.targetTempC > 0f) live.targetTempC else tile.targetTemperature,
                            powerWatts = live.powerWatts,
                            voltageVolts = live.voltageVolts,
                            currentMilliamps = live.currentMilliamps,
                            totalEnergyKwh = live.totalEnergyKwh,
                            capabilities = sanitizedCaps,
                            capabilitySchemaHash = mergedHash,
                            hasExplicitPowerTelemetry = tile.hasExplicitPowerTelemetry || live.hasPowerTelemetry,
                            deviceClassOverride = if (isManualClass) tile.deviceClassOverride else live.deviceClass,
                            deviceSubtypeOverride = if (isManualClass) tile.deviceSubtypeOverride else live.deviceSubtype
                        )
                    }
                    tile.isTowerFanTile || tile.type == TileType.PROBREEZE_FAN -> tile.copy(
                        serialNumber = tile.serialNumber.ifBlank { live.deviceId },
                        entityId = tile.entityId.ifBlank { live.deviceId },
                        secretCode = live.localKey.ifBlank { tile.secretCode },
                        isOnline = resolvedOnline,
                        isActive = live.isActive,
                        volume = live.brightnessOrBatteryPct,
                        fanSpeedLevel = live.fanSpeedLevel,
                        fanMode = live.fanMode,
                        fanOscillating = live.fanOscillating,
                        fanIonizerActive = live.fanIonizerActive,
                        fanTimerHours = live.fanTimerHours,
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash
                    )
                    tile.deviceSubtype == DeviceSubtype.VACUUM || tile.type == TileType.AIRROBO_VACUUM -> tile.copy(
                        serialNumber = tile.serialNumber.ifBlank { live.deviceId },
                        entityId = tile.entityId.ifBlank { live.deviceId },
                        secretCode = live.localKey.ifBlank { tile.secretCode },
                        isOnline = resolvedOnline,
                        isActive = live.isActive,
                        volume = live.brightnessOrBatteryPct,
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash
                    )
                    tile.isLytmiTile || tile.type == TileType.LYTMI_SYNC_BOX -> tile.copy(
                        serialNumber = tile.serialNumber.ifBlank { live.deviceId },
                        entityId = tile.entityId.ifBlank { live.deviceId },
                        secretCode = live.localKey.ifBlank { tile.secretCode },
                        isOnline = resolvedOnline,
                        isActive = live.isActive,
                        volume = live.brightnessOrBatteryPct,
                        lytmiHdmiInput = live.lytmiHdmiInput,
                        lytmiSyncMode = live.lytmiSyncMode,
                        lytmiSceneName = live.lytmiSceneName,
                        lytmiDiffusionSpeed = live.lytmiDiffusionSpeed,
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash
                    )
                    tile.deviceClass == DeviceClass.LIGHT || tile.type == TileType.HUE_LIGHT -> tile.copy(
                        serialNumber = tile.serialNumber.ifBlank { live.deviceId },
                        entityId = tile.entityId.ifBlank { live.deviceId },
                        secretCode = live.localKey.ifBlank { tile.secretCode },
                        isOnline = resolvedOnline,
                        isActive = live.isActive,
                        volume = live.brightnessOrBatteryPct,
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash
                    )
                    tile.deviceClass == DeviceClass.SENSOR || tile.type == TileType.SMART_SENSOR -> tile.copy(
                        serialNumber = tile.serialNumber.ifBlank { live.deviceId },
                        entityId = tile.entityId.ifBlank { live.deviceId },
                        isOnline = resolvedOnline,
                        temperature = live.currentTempC,
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash
                    )
                    else -> tile
                }

                val knownLanIp = localIpByDevice[live.deviceId].orEmpty().ifBlank { tile.ipAddress }
                val hasVerifiedLan = knownLanIp.isNotBlank() &&
                        (live.localKey.length == 16 || tile.secretCode.length == 16 || TuyaApiClient.localKeyByDevice[live.deviceId]?.length == 16)
                val effectiveAuthority = if (hasVerifiedLan) {
                    StateAuthorityTier.DEVICE_HARDWARE_TRUTH
                } else {
                    StateAuthorityTier.CLOUD_SHADOW_STATE
                }

                JarvisLiveBridge.recordBackgroundPollOutcome(
                    tile = tile,
                    providerId = if (hasVerifiedLan) "TUYA_LAN_6668" else "TUYA_CLOUD",
                    succeeded = resolvedOnline,
                    latencyMs = pollLatencyMs,
                    failureReason = if (resolvedOnline) "" else "Device unreachable on LAN and Cloud"
                )

                val reconciled = DeviceRepository.reconcileRemoteTileUpdate(
                    existing = tile,
                    incoming = updated.copy(ipAddress = knownLanIp),
                    incomingAuthority = effectiveAuthority,
                    sourceName = if (hasVerifiedLan) "Tuya LAN" else "Tuya Cloud"
                )
                if (reconciled != tile) {
                    tiles[i] = reconciled
                }

                if (appCtx != null && live.powerWatts > 0f) {
                    scope.launch(Dispatchers.IO) {
                        DeviceRepository.recordTelemetryPoint(
                            context = appCtx,
                            point = TelemetryDataPoint(
                                deviceId = tile.id,
                                deviceTitle = tile.title,
                                metricKey = "power_w",
                                value = live.powerWatts,
                                unit = "W"
                            )
                        )
                    }
                }

                // Stage 6 & 4: Replay any queued Digital Twin Shadow intents upon device reconnect
                if (wasOffline && resolvedOnline && appCtx != null && audioManager != null) {
                    replayQueuedShadowIntentsForTile(
                        context = appCtx,
                        tile = tiles[i],
                        bridge = bridge,
                        audioManager = audioManager,
                        scope = scope
                    )
                }
            }
        }
    }
}

suspend fun TuyaApiClient.deleteDevice(bridge: BridgeSettings, deviceId: String): Boolean = withContext(Dispatchers.IO) {
    val cleanId = deviceId.trim()
    if (cleanId.isBlank()) return@withContext false
    val tokenRes = getAccessToken(bridge)
    if (tokenRes.isFailure) return@withContext false
    val token = tokenRes.getOrThrow()
    try {
        val resp = executeSignedRequest(
            host = cachedWorkingHost,
            accessId = bridge.tuyaAccessId,
            accessSecret = bridge.tuyaAccessSecret,
            accessToken = token,
            method = "DELETE",
            pathWithQuery = "/v1.0/devices/$cleanId"
        )
        resp?.optBoolean("success", false) == true
    } catch(e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
            t = e,
            transportSource = "TuyaCloudClient",
            operationStage = "deleteDevice",
            logToDiscoveryRecorder = true,
            surfaceToUiBanner = true
        )
        false
    }
}

suspend fun TuyaApiClient.sendCommands(
    bridge: BridgeSettings,
    deviceId: String,
    commandsArray: JSONArray
): Boolean = withContext(Dispatchers.IO) {
    val cleanDevId = deviceId.trim()
    if (cleanDevId.isBlank() || cleanDevId.contains(".")) return@withContext false

    // 1. Try Native ThingHomeSdk / Persistent LAN 6668 control first
    var allNativeSucceeded = commandsArray.length() > 0
    for (i in 0 until commandsArray.length()) {
        val cmd = commandsArray.optJSONObject(i) ?: continue
        val code = cmd.optString("code", "")
        val value = cmd.opt("value") ?: continue
        if (!sendNativeSdkDpCommand(cleanDevId, code, value)) {
            allNativeSucceeded = false
            break
        }
    }
    if (allNativeSucceeded) return@withContext true

    // 2. Fallback to Tuya Cloud OpenAPI
    val tokenRes = getAccessToken(bridge)
    if (tokenRes.isFailure) return@withContext false
    val token = tokenRes.getOrThrow()

    try {
        lastCommandTimestampMs = System.currentTimeMillis()
        val payload = JSONObject().put("commands", commandsArray).toString()
        val resp = executeSignedRequest(
            host = cachedWorkingHost,
            accessId = bridge.tuyaAccessId,
            accessSecret = bridge.tuyaAccessSecret,
            accessToken = token,
            method = "POST",
            pathWithQuery = "/v1.0/iot-03/devices/$cleanDevId/commands",
            bodyJson = payload
        )
        val ok = resp?.optBoolean("success", false) == true
        recordDeviceCommandOutcome(cleanDevId, ok)
        ok
    } catch (e: Exception) {
            com.jarvis.smarthomelauncher.core.ErrorClassifier.classifyException(e).let { category ->
                com.jarvis.smarthomelauncher.core.DiscoveryFlightRecorder.record(
                    eventType = com.jarvis.smarthomelauncher.core.DiscoveryEventType.FAILED,
                    transportSource = "TuyaCloudClient",
                    hardwareId = "-",
                    vendor = "Vendor",
                    qualificationStage = "Exception Caught",
                    outcomeReason = com.jarvis.smarthomelauncher.core.ErrorClassifier.getHumanReadableRemediation(category, e),
                    rawPayload = e.stackTraceToString().take(200)
                )
            }
            
        false
    
        }
}

/**
 * Sends an independent ON/OFF command to ONLY Socket 1 (`socketNumber = 1`) or
 * ONLY Socket 2 (`socketNumber = 2`) on a SmartKnight / Tuya plug without altering
 * the other socket. Routes through the Persistent TCP 6668 LAN Socket Pool first (< 10ms).
 */
suspend fun TuyaApiClient.sendSingleSocketPlugCommand(
    bridge: BridgeSettings,
    deviceId: String,
    socketNumber: Int,
    turnOn: Boolean,
    ipAddress: String = "",
    localKey: String = ""
): Boolean = withContext(Dispatchers.IO) {
    var cleanId = deviceId.trim()
    if (cleanId.isBlank()) {
        if (discoveredTuyaDeviceCache.isEmpty()) {
            discoverAllTuyaDevices(bridge)
        }
        cleanId = discoveredTuyaDeviceCache.values
            .firstOrNull { it.suggestedType == TileType.SMARTKNIGHT_PLUG }
            ?.deviceId
            ?: discoveredLanTuyaDeviceIds.firstOrNull().orEmpty()
    }
    if (cleanId.isBlank() || cleanId.startsWith("enterprises/") || cleanId.contains(".")) {
        return@withContext false
    }

    if (ipAddress.isNotBlank()) {
        registerDeviceLanAddress(cleanId, ipAddress, localKey)
    }

    if (!switchDpIdByDevice.containsKey(cleanId) && ClaraApplication.isTuyaSdkReady()) {
        runCatching { ThingHomeSdk.getDataInstance().getDeviceBean(cleanId) }
            .getOrNull()
            ?.let { convertDeviceBeanToDiscovered(it) }
    }

    val targetDpId = if (socketNumber == 2) {
        switch2DpIdByDevice[cleanId] ?: "2"
    } else {
        switchDpIdByDevice[cleanId] ?: "1"
    }
    val targetDpCode = if (socketNumber == 2) {
        switch2DpCodeByDevice[cleanId] ?: "switch_2"
    } else {
        switchDpCodeByDevice[cleanId] ?: "switch_1"
    }

    // 1. Fast Path: Verified ThingHomeSdk Local LAN / MQTT or v3.3 AES Socket
    val lanOrNativeOk = sendLocalLanDpCommand(
        deviceId = cleanId,
        ipAddress = ipAddress,
        localKey = localKey,
        dpsPayload = JSONObject().put(targetDpId, turnOn)
    )
    if (lanOrNativeOk) return@withContext true

    // 2. Fallback to Tuya Cloud OpenAPI (with switch_1 <-> switch DP code fallback)
    val cmds = JSONArray().put(
        JSONObject().apply {
            put("code", targetDpCode)
            put("value", turnOn)
        }
    )
    val primaryCloudOk = sendCommands(bridge, cleanId, cmds)
    if (primaryCloudOk) return@withContext true
    if (socketNumber != 2) {
        val altCode = if (targetDpCode == "switch_1") "switch" else "switch_1"
        val altCmds = JSONArray().put(JSONObject().put("code", altCode).put("value", turnOn))
        if (sendCommands(bridge, cleanId, altCmds)) {
            switchDpCodeByDevice[cleanId] = altCode
            return@withContext true
        }
    }
    false
}

/**
 * Dispatches a hardware countdown timer (in seconds) to Socket 1 or Socket 2 on a SmartKnight plug.
 */
fun TuyaApiClient.dispatchPlugCountdown(
    scope: CoroutineScope,
    bridge: BridgeSettings,
    tile: SmartTile,
    socketNumber: Int,
    countdownSeconds: Int
) {
    scope.launch {
        val devId = resolveDeviceIdForTile(bridge, tile)
        if (devId.isBlank() || devId.startsWith("enterprises/") || devId.contains(".")) return@launch
        if (tile.ipAddress.isNotBlank()) {
            registerDeviceLanAddress(devId, tile.ipAddress, tile.secretCode)
        }
        val nativeOk = sendNativePlugCountdownCommand(
            deviceId = devId,
            socketNumber = socketNumber,
            countdownSeconds = countdownSeconds,
            ipAddress = tile.ipAddress,
            localKey = tile.secretCode
        )
        if (nativeOk) return@launch

        val code = if (socketNumber == 2) "countdown_2" else "countdown_1"
        val cmds = JSONArray().put(
            JSONObject().apply {
                put("code", code)
                put("value", countdownSeconds.coerceIn(0, 86400))
            }
        )
        sendCommands(bridge, devId, cmds)
    }
}

fun TuyaApiClient.dispatchTuyaTileUpdate(
    scope: CoroutineScope,
    bridge: BridgeSettings,
    updated: SmartTile
) {
    scope.launch {
        val devId = resolveDeviceIdForTile(bridge, updated)
        if (devId.isBlank() || devId.startsWith("enterprises/") || devId.contains(".")) return@launch
        if (updated.ipAddress.isNotBlank()) {
            registerDeviceLanAddress(devId, updated.ipAddress, updated.secretCode)
        }

        val isSplitSocketChild = updated.channelId.startsWith("SPLIT_SOCKET_")
        val lowerTitle = updated.title.trim().lowercase(Locale.US)
        val isNonHeaterSwitchOrAppliance = lowerTitle.contains("plug") ||
                lowerTitle.contains("socket") ||
                lowerTitle.contains("pond") ||
                lowerTitle.contains("pump") ||
                lowerTitle.contains("kettle") ||
                lowerTitle.contains("fryer") ||
                lowerTitle.contains("coffee") ||
                lowerTitle.contains("washer")
        when {
            updated.isTowerFanTile || updated.type == TileType.PROBREEZE_FAN -> {
                sendNativeTowerFanCommand(
                    bridge = bridge,
                    tile = updated.copy(
                        serialNumber = updated.serialNumber.ifBlank { devId },
                        entityId = updated.entityId.ifBlank { devId }
                    )
                )
            }
            !isNonHeaterSwitchOrAppliance && ((!isSplitSocketChild && updated.isThermostatTile) || updated.type == TileType.TUYA_RADIATOR) -> {
                lastCommandTimestampMs = System.currentTimeMillis()
                pendingRadiatorJobs[devId]?.cancel()
                pendingRadiatorJobs[devId] = scope.launch {
                    delay(45L) // Ultra-responsive 45ms coalesce window
                    val shouldBeOn = updated.isActive && !updated.hvacMode.equals("OFF", ignoreCase = true)
                    val nativeHandled = sendNativeRadiatorCommand(
                        deviceId = devId,
                        shouldBeOn = shouldBeOn,
                        targetTempCelsius = updated.targetTemperature,
                        ipAddress = updated.ipAddress,
                        localKey = updated.secretCode,
                        hvacMode = updated.hvacMode
                    )
                    if (nativeHandled) return@launch

                    val dpCode = tempSetDpCodeByDevice[devId] ?: "temp_set"
                    val scale = tempScaleByDevice[devId] ?: 1f
                    val scaledValue = (updated.targetTemperature * scale).roundToInt()
                    val cmds = JSONArray().put(
                        JSONObject().apply {
                            put("code", dpCode)
                            put("value", scaledValue)
                        }
                    )
                    sendCommands(bridge, devId, cmds)
                }
            }
            updated.type == TileType.SMARTKNIGHT_PLUG -> {
                val socket1On = updated.isActive
                val socket2On = updated.isSocket2Active
                val isDualHardware = updated.multiGangSocketCount >= 2 || switch2DpCodeByDevice.containsKey(devId)

                val nativeHandled = sendNativeDualPlugCommand(
                    deviceId = devId,
                    socket1On = socket1On,
                    socket2On = socket2On,
                    ipAddress = updated.ipAddress,
                    localKey = updated.secretCode,
                    isDualHardware = isDualHardware
                )
                if (nativeHandled) return@launch

                val dp1Code = switchDpCodeByDevice[devId] ?: "switch_1"
                val cmds = JSONArray().put(JSONObject().put("code", dp1Code).put("value", socket1On))
                if (isDualHardware) {
                    val dp2Code = switch2DpCodeByDevice[devId] ?: "switch_2"
                    cmds.put(JSONObject().put("code", dp2Code).put("value", socket2On))
                }
                sendCommands(bridge, devId, cmds)
            }
            updated.type in setOf(TileType.SMARTLIFE_DEVICE, TileType.TAPO_DEVICE, TileType.SONOFF_SWITCH) -> {
                val dpId = switchDpIdByDevice[devId] ?: "1"
                val lanHandled = sendLocalLanDpCommand(
                    deviceId = devId,
                    ipAddress = updated.ipAddress,
                    localKey = updated.secretCode,
                    dpsPayload = JSONObject().put(dpId, updated.isActive)
                )
                if (lanHandled) return@launch

                val dpCode = switchDpCodeByDevice[devId] ?: "switch_1"
                val cmds = JSONArray().put(
                    JSONObject().apply {
                        put("code", dpCode)
                        put("value", updated.isActive)
                    }
                )
                val ok = sendCommands(bridge, devId, cmds)
                if (!ok && dpCode == "switch_1") {
                    val fallbackCmds = JSONArray().put(
                        JSONObject().apply {
                            put("code", "switch")
                            put("value", updated.isActive)
                        }
                    )
                    if (sendCommands(bridge, devId, fallbackCmds)) {
                        switchDpCodeByDevice[devId] = "switch"
                    }
                }
            }
            updated.deviceSubtype == DeviceSubtype.VACUUM || updated.type == TileType.AIRROBO_VACUUM -> {
                val actionMode = updated.hvacMode.uppercase(Locale.US)
                val wantsReturnToDock = actionMode == "DOCK" || !updated.isActive
                val wantsStartCleaning = updated.isActive && !wantsReturnToDock

                val nativeHandled = sendNativeVacuumCommand(
                    deviceId = devId,
                    startCleaning = wantsStartCleaning,
                    returnToDock = wantsReturnToDock,
                    vacuumActionMode = if (wantsReturnToDock) "DOCK" else actionMode
                )
                if (nativeHandled) return@launch

                val primaryGoCode = switchDpCodeByDevice[devId]?.takeIf { it.isNotBlank() } ?: "power_go"
                if (wantsStartCleaning) {
                    val cleanCmds = JSONArray().apply {
                        put(JSONObject().put("code", "power").put("value", true))
                        put(JSONObject().put("code", primaryGoCode).put("value", true))
                        if (primaryGoCode != "switch_go") {
                            put(JSONObject().put("code", "switch_go").put("value", true))
                        }
                        put(JSONObject().put("code", "mode").put("value", if (actionMode == "SPOT") "spiral" else "smart"))
                    }
                    sendCommands(bridge, devId, cleanCmds)
                } else {
                    val dockCmds = JSONArray().apply {
                        put(JSONObject().put("code", primaryGoCode).put("value", false))
                        if (primaryGoCode != "switch_go") {
                            put(JSONObject().put("code", "switch_go").put("value", false))
                        }
                        put(JSONObject().put("code", "switch_charge").put("value", true))
                        put(JSONObject().put("code", "mode").put("value", "chargego"))
                    }
                    sendCommands(bridge, devId, dockCmds)
                }
            }
            updated.deviceClass == DeviceClass.LIGHT || updated.type == TileType.HUE_LIGHT -> {
                val dpCode = switchDpCodeByDevice[devId] ?: "switch_led"
                val cmds = JSONArray().apply {
                    put(JSONObject().put("code", dpCode).put("value", updated.isActive))
                    if (updated.isActive) {
                        put(
                            JSONObject()
                                .put("code", "bright_value_v2")
                                .put("value", (updated.volume * 10f).roundToInt().coerceIn(10, 1000))
                        )
                    }
                }
                sendCommands(bridge, devId, cmds)
            }
            else -> {}
        }
    }
}
