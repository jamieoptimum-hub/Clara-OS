package com.jarvis.smarthomelauncher.integrations

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------
// IMOU CLOUD OPENAPI & LOCAL RTSP CLIENT
// (Auto-Serial Discovery, bindDeviceLive / getLiveStreamInfo Token Resolution,
//  Local RTSP /cam/realmonitor?channel=1&subtype=0/1 & Cloud live.m3u8 Fallback)
// ---------------------------------------------------------------------------
object ImouCloudApiClient {
    private const val TAG = "ClaraCamera"
    private const val HLS_CACHE_TTL_MS = 8 * 60_000L

    private data class CachedHlsStream(
        val url: String,
        val timestampMs: Long = System.currentTimeMillis()
    )

    @Volatile
    private var cachedToken: String = ""

    @Volatile
    private var cachedTokenAppId: String = ""

    @Volatile
    private var cachedWorkingHost: String = DEFAULT_IMOU_API_HOST

    @Volatile
    private var tokenExpiryMs: Long = 0L

    private val hlsUrlCache = ConcurrentHashMap<String, CachedHlsStream>()
    private val snapshotUrlCache = ConcurrentHashMap<String, String>()
    private val discoveredCamerasCache = ConcurrentHashMap<String, DiscoveredCamera>()
    private val ptzCapabilityBySerial = ConcurrentHashMap<String, Boolean>()
    private val deviceSafetyCodeBySerial = ConcurrentHashMap<String, String>()

    fun clearStreamCache() {
        hlsUrlCache.clear()
    }

    fun invalidateStreamForSerial(serialNumber: String) {
        val clean = serialNumber.trim()
        if (clean.isBlank()) {
            hlsUrlCache.clear()
            return
        }
        val keysToRemove = hlsUrlCache.keys.filter { it.startsWith("${clean}_", ignoreCase = true) }
        keysToRemove.forEach { hlsUrlCache.remove(it) }
    }

    private const val PREFS_IMOU = "clara_imou_camera_prefs_v1"

    fun saveDeviceSafetyCode(serialNumber: String, safetyCode: String) {
        val cleanSn = serialNumber.trim()
        val cleanCode = safetyCode.trim()
        if (cleanSn.isNotBlank() && cleanCode.isNotBlank()) {
            val ctx = ClaraApplication.appContextOrNull() ?: ClaraInAppMusicPlayer.currentAppContext() ?: return
            ClaraSecretVault.putCameraVerificationPin(ctx, cleanSn, cleanCode)
            runCatching { ctx.applicationContext.deleteSharedPreferences(PREFS_IMOU) }
        }
    }

    fun getSavedDeviceSafetyCode(serialNumber: String): String {
        val cleanSn = serialNumber.trim()
        if (cleanSn.isBlank()) return ""
        val ctx = ClaraApplication.appContextOrNull() ?: ClaraInAppMusicPlayer.currentAppContext() ?: return ""
        ClaraSecretVault.getCameraVerificationPin(ctx, cleanSn)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val legacyPrefs = ctx.applicationContext.getSharedPreferences(PREFS_IMOU, android.content.Context.MODE_PRIVATE)
        val legacy = legacyPrefs.getString("safety_code_$cleanSn", "").orEmpty().trim()
        if (legacy.isNotBlank()) {
            ClaraSecretVault.putCameraVerificationPin(ctx, cleanSn, legacy)
            legacyPrefs.edit().remove("safety_code_$cleanSn").apply()
        }
        return legacy
    }

    /**
     * Builds ordered local RTSP candidate URLs for Dahua / Imou cameras:
     * - Primary: `/cam/realmonitor?channel=1&subtype=0` (Main HD) or `subtype=1` (H.264 Sub-Stream)
     * - Automatic fallback to the alternate subtype and `/live` endpoint.
     */
    fun buildLocalRtspCandidateUrls(
        ipAddress: String,
        safetyCode: String,
        channelId: String = "1",
        preferSubStream: Boolean = false
    ): List<String> {
        val rawIp = ipAddress.trim()
        val cleanIp = rawIp.substringBefore(":").trim()
        val port = rawIp.substringAfter(":", "554").toIntOrNull()?.takeIf { it > 0 } ?: 554
        val cleanPass = safetyCode.trim()
        if (cleanIp.isBlank() || cleanPass.isBlank()) return emptyList()

        val encodedPass = Uri.encode(cleanPass)
        val rawChanInt = channelId.trim().toIntOrNull() ?: 1
        // Local Dahua/Imou RTSP channels are 1-indexed (channel=1)
        val rtspChannel = if (rawChanInt <= 0) 1 else rawChanInt
        val primarySubtype = if (preferSubStream) 1 else 0
        val secondarySubtype = if (preferSubStream) 0 else 1

        return listOf(
            "rtsp://admin:$encodedPass@$cleanIp:$port/cam/realmonitor?channel=$rtspChannel&subtype=$primarySubtype",
            "rtsp://admin:$encodedPass@$cleanIp:$port/cam/realmonitor?channel=$rtspChannel&subtype=$secondarySubtype",
            "rtsp://admin:$encodedPass@$cleanIp:$port/live"
        ).distinct()
    }

    fun hasActiveSessionOrCredentials(bridge: BridgeSettings): Boolean {
        if (cachedToken.isNotBlank() && System.currentTimeMillis() < tokenExpiryMs) return true
        val effectiveAppId = bridge.imouAppId.trim().ifBlank { DEFAULT_IMOU_APP_ID }
        val effectiveSecret = bridge.imouAppSecret.trim().ifBlank { DEFAULT_IMOU_APP_SECRET }
        return effectiveAppId.isNotBlank() && effectiveSecret.isNotBlank()
    }

    fun hasDiscoveredCameras(): Boolean = discoveredCamerasCache.isNotEmpty()

    fun getCachedCameras(): List<DiscoveredCamera> = discoveredCamerasCache.values.toList()

    fun isPtzSupported(serialNumber: String): Boolean {
        val clean = serialNumber.trim()
        if (clean.isBlank()) return false
        return ptzCapabilityBySerial[clean] ?: false
    }

    private fun md5Hex(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun buildSignedEnvelope(appId: String, appSecret: String, params: JSONObject): JSONObject {
        val timeSec = System.currentTimeMillis() / 1000L
        val nonce = UUID.randomUUID().toString()
        val rawSign = "time:$timeSec,nonce:$nonce,appSecret:${appSecret.trim()}"
        val sign = md5Hex(rawSign)

        val systemObj = JSONObject().apply {
            put("ver", "1.0")
            put("appId", appId.trim())
            put("sign", sign)
            put("time", timeSec)
            put("nonce", nonce)
        }
        return JSONObject().apply {
            put("system", systemObj)
            put("id", UUID.randomUUID().toString())
            put("params", params)
        }
    }

    private fun postOpenApi(host: String, endpoint: String, body: JSONObject): JSONObject? {
        if (JarvisLiveBridge.isCircuitOpen("IMOU_CLOUD")) {
            throw CircuitBreakerOpenException("Cloud Degraded \u2014 Running Locally")
        }
        val cleanHost = host.trim().trimEnd('/')
        val url = URL("$cleanHost/openapi/$endpoint")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(body.toString())
            writer.flush()
        }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val responseText = stream?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        return if (!responseText.isNullOrBlank()) runCatching { JSONObject(responseText) }.getOrNull() else null
    }

    private fun isTokenExpiredResponse(resp: JSONObject?): Boolean {
        val code = resp?.optJSONObject("result")?.optString("code", "").orEmpty()
        return code.equals("TK1002", ignoreCase = true) ||
                code.equals("TK1001", ignoreCase = true) ||
                code.equals("SN1001", ignoreCase = true)
    }

    suspend fun getAccessToken(
        host: String,
        appId: String,
        appSecret: String,
        forceRefresh: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanId = appId.trim().ifBlank { DEFAULT_IMOU_APP_ID }
        val cleanSecret = appSecret.trim().ifBlank { DEFAULT_IMOU_APP_SECRET }
        if (cleanId.isBlank() || cleanSecret.isBlank()) {
            return@withContext Result.failure(Exception("Imou account is not linked yet. Enter your Imou App ID & Secret in Control Center."))
        }
        if (!forceRefresh && cachedToken.isNotBlank() && cachedTokenAppId == cleanId && System.currentTimeMillis() < tokenExpiryMs) {
            return@withContext Result.success(cachedToken)
        }

        val candidateHosts = listOf(
            host.trim().ifBlank { DEFAULT_IMOU_API_HOST },
            "https://openapi-fk.easy4ip.com",
            "https://openapi.easy4ip.com",
            "https://openapi-sg.easy4ip.com",
            "https://openapi-or.easy4ip.com"
        ).distinct()

        var lastError = "Could not connect to Imou Cloud. Please check your internet connection."
        for (candidateHost in candidateHosts) {
            try {
                val req = buildSignedEnvelope(cleanId, cleanSecret, JSONObject())
                val resp = postOpenApi(candidateHost, "accessToken", req) ?: continue
                val resultObj = resp.optJSONObject("result") ?: continue
                val code = resultObj.optString("code")
                if (code == "0") {
                    val data = resultObj.optJSONObject("data")
                    val token = data?.optString("accessToken", "") ?: ""
                    val expireSec = data?.optLong("expireTime", 86400L) ?: 86400L
                    if (token.isNotBlank()) {
                        cachedToken = token
                        cachedTokenAppId = cleanId
                        cachedWorkingHost = candidateHost.trimEnd('/')
                        tokenExpiryMs = System.currentTimeMillis() + (expireSec - 300).coerceAtLeast(300L) * 1000L
                        return@withContext Result.success(token)
                    }
                } else {
                    val msg = resultObj.optString("msg", "")
                    lastError = if (msg.isNotBlank()) "Imou Cloud ($code): $msg" else "Could not sign in to Imou Cloud."
                }
            } catch (_: Exception) {
                lastError = "Imou Cloud is temporarily unreachable."
            }
        }
        Result.failure(Exception(lastError))
    }

    suspend fun discoverBoundCameras(
        bridge: BridgeSettings
    ): Result<List<DiscoveredCamera>> = withContext(Dispatchers.IO) {
        val effectiveAppId = bridge.imouAppId.ifBlank { DEFAULT_IMOU_APP_ID }
        val effectiveSecret = bridge.imouAppSecret.ifBlank { DEFAULT_IMOU_APP_SECRET }
        var tokenRes = getAccessToken(bridge.imouApiHost, effectiveAppId, effectiveSecret)
        if (tokenRes.isFailure) {
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.FAILED,
                transportSource = "IMOU_CLOUD",
                vendor = "Imou Cloud OpenAPI",
                qualificationStage = "discoverBoundCameras.token",
                outcomeReason = tokenRes.exceptionOrNull()?.message ?: "Could not obtain Imou Cloud token"
            )
            return@withContext Result.failure(
                tokenRes.exceptionOrNull() ?: Exception("Could not connect to Imou Cloud.")
            )
        }
        var token = tokenRes.getOrThrow()
        val targetHost = cachedWorkingHost.ifBlank { bridge.imouApiHost }

        try {
            val discovered = mutableListOf<DiscoveredCamera>()
            // 1. Try listDeviceDetailsByPage (v2 detail endpoint)
            val params = JSONObject().apply {
                put("token", token)
                put("page", 1)
                put("pageSize", 50)
                put("source", "bindAndShare")
            }
            var resp = postOpenApi(
                targetHost,
                "listDeviceDetailsByPage",
                buildSignedEnvelope(effectiveAppId, effectiveSecret, params)
            )
            if (isTokenExpiredResponse(resp)) {
                tokenRes = getAccessToken(bridge.imouApiHost, effectiveAppId, effectiveSecret, forceRefresh = true)
                if (tokenRes.isSuccess) {
                    token = tokenRes.getOrThrow()
                    params.put("token", token)
                    resp = postOpenApi(
                        targetHost,
                        "listDeviceDetailsByPage",
                        buildSignedEnvelope(effectiveAppId, effectiveSecret, params)
                    )
                }
            }

            // 2. Fallback to deviceBaseList if listDeviceDetailsByPage is unsupported in region
            var resultObj = resp?.optJSONObject("result")
            if (resultObj == null || resultObj.optString("code") != "0") {
                val baseParams = JSONObject().apply {
                    put("token", token)
                    put("bindId", "-1")
                    put("limit", 50)
                    put("type", "bindAndShare")
                    put("needApInfo", "true")
                }
                val baseResp = postOpenApi(
                    targetHost,
                    "deviceBaseList",
                    buildSignedEnvelope(effectiveAppId, effectiveSecret, baseParams)
                )
                resultObj = baseResp?.optJSONObject("result")
            }

            if (resultObj == null || resultObj.optString("code") != "0") {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "IMOU_CLOUD",
                    vendor = "Imou Cloud OpenAPI ($targetHost)",
                    qualificationStage = "discoverBoundCameras.list",
                    outcomeReason = "Imou device list API failed (code=${resultObj?.optString("code", "null")})"
                )
                return@withContext Result.failure(Exception("Could not retrieve cameras from your Imou account."))
            }

            val data = resultObj.optJSONObject("data")
            val deviceList = data?.optJSONArray("deviceList") ?: JSONArray()

            for (i in 0 until deviceList.length()) {
                val d = deviceList.optJSONObject(i) ?: continue
                val devId = d.optString("deviceId", "").trim()
                val devName = d.optString("deviceName", d.optString("name", "Imou Camera")).trim()
                val devStatus = d.optString("deviceStatus", d.optString("status", "online"))
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                    transportSource = "IMOU_CLOUD",
                    hardwareId = devId,
                    vendor = devName,
                    confidenceScore = 0.80f,
                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                    outcomeReason = "Imou OpenAPI deviceList entry received (status=$devStatus)",
                    cloudId = devId
                )
                val ability = d.optString("ability", "")
                val safetyCode = d.optString("playToken", d.optString("encryptCode", "")).trim()
                if (devId.isNotBlank() && safetyCode.isNotBlank()) {
                    saveDeviceSafetyCode(devId, safetyCode)
                }

                val channels = d.optJSONArray("channelList") ?: d.optJSONArray("channels")
                val firstChanObj = if (channels != null && channels.length() > 0) {
                    channels.optJSONObject(0)
                } else null
                val firstChanId = firstChanObj?.optString("channelId", "0") ?: "0"
                val chanAbility = firstChanObj?.optString("ability", "") ?: ""
                val combinedAbility = "$ability,$chanAbility"

                if (devId.isNotBlank()) {
                    val supportsPanTilt = combinedAbility.contains("PT", ignoreCase = true) ||
                            devName.contains("PT", ignoreCase = true) ||
                            devName.contains("Cruiser", ignoreCase = true) ||
                            devName.contains("Ranger", ignoreCase = true)
                    ptzCapabilityBySerial[devId] = supportsPanTilt
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.IDENTIFIED,
                        transportSource = "IMOU_CLOUD",
                        hardwareId = devId,
                        vendor = devName,
                        confidenceScore = 0.96f,
                        qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                        outcomeReason = "Verified Imou Cloud camera (channel=$firstChanId ptz=$supportsPanTilt status=$devStatus)",
                        cloudId = devId
                    )
                    val cam = DiscoveredCamera(
                        deviceId = devId,
                        name = devName,
                        status = devStatus,
                        channelId = firstChanId
                    )
                    discovered.add(cam)
                    discoveredCamerasCache[devId] = cam
                } else {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FILTERED,
                        transportSource = "IMOU_CLOUD",
                        vendor = devName,
                        qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                        outcomeReason = "Filtered Imou Cloud entry missing deviceId"
                    )
                }
            }
            Result.success(discovered)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.FAILED,
                transportSource = "IMOU_CLOUD",
                vendor = "Imou Cloud OpenAPI",
                qualificationStage = "discoverBoundCameras",
                outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "Imou camera discovery failed"}"
            )
            Result.failure(Exception("Could not load your Imou cameras right now."))
        }
    }

    /**
     * Automatically resolves a camera's Serial Number in the background if the tile was created
     * without one.
     */
    private suspend fun resolveOrAutoDiscoverSerial(
        bridge: BridgeSettings,
        serialNumber: String,
        tileTitleHint: String = ""
    ): String {
        val clean = serialNumber.trim()
        if (clean.isNotBlank()) return clean
        if (discoveredCamerasCache.isEmpty()) {
            discoverBoundCameras(bridge)
        }
        if (discoveredCamerasCache.isNotEmpty()) {
            if (tileTitleHint.isNotBlank()) {
                discoveredCamerasCache.values.firstOrNull {
                    it.name.equals(tileTitleHint, ignoreCase = true) ||
                            tileTitleHint.contains(it.name, ignoreCase = true)
                }?.let { return it.deviceId }
            }
            return discoveredCamerasCache.keys.first()
        }
        return ""
    }

    /**
     * Point 8: Resolves an Imou Cloud HLS (`live.m3u8`) or RTSP stream URL.
     * - Automatically probes both the requested `channelId` and `"0"` (Imou OpenAPI 0-indexed default).
     * - Calls `getLiveStreamInfo`, `bindDeviceLive`, and `modifyLivePlanStatus` (if live stream is paused).
     * - Automatically unbinds stale `liveToken` slots if a stream slot conflict occurs.
     */
    suspend fun fetchOrBindHlsStream(
        bridge: BridgeSettings,
        serialNumber: String,
        channelId: String = "0",
        preferSdSubStream: Boolean = true
    ): Result<String> = withContext(Dispatchers.IO) {
        val sn = resolveOrAutoDiscoverSerial(bridge, serialNumber)
        if (sn.isBlank()) {
            return@withContext Result.failure(Exception("Searching for your Imou camera..."))
        }

        val targetStreamId = if (preferSdSubStream) 1 else 0
        val requestedChan = channelId.trim().ifBlank { "0" }
        // Imou OpenAPI uses channelId "0" for single-channel cameras; try "0" and requestedChan
        val candidateChannels = listOf("0", requestedChan, "1").distinct()

        val cacheKey = "${sn}_${requestedChan}_$targetStreamId"
        hlsUrlCache[cacheKey]?.let { cached ->
            if (cached.url.isNotBlank() && (System.currentTimeMillis() - cached.timestampMs) < HLS_CACHE_TTL_MS) {
                return@withContext Result.success(cached.url)
            } else {
                hlsUrlCache.remove(cacheKey)
            }
        }

        val effectiveAppId = bridge.imouAppId.ifBlank { DEFAULT_IMOU_APP_ID }
        val effectiveSecret = bridge.imouAppSecret.ifBlank { DEFAULT_IMOU_APP_SECRET }

        var tokenResult = getAccessToken(bridge.imouApiHost, effectiveAppId, effectiveSecret)
        if (tokenResult.isFailure) {
            return@withContext Result.failure(
                tokenResult.exceptionOrNull() ?: Exception("Could not connect to Imou Cloud.")
            )
        }
        var token = tokenResult.getOrThrow()
        val targetHost = cachedWorkingHost.ifBlank { bridge.imouApiHost }

        try {
            for (chanToTry in candidateChannels) {
                // 1. Query existing bound streams via `getLiveStreamInfo`
                val infoParams = JSONObject().apply {
                    put("token", token)
                    put("deviceId", sn)
                    put("channelId", chanToTry)
                }
                var infoResp = postOpenApi(
                    targetHost,
                    "getLiveStreamInfo",
                    buildSignedEnvelope(effectiveAppId, effectiveSecret, infoParams)
                )
                if (isTokenExpiredResponse(infoResp)) {
                    tokenResult = getAccessToken(bridge.imouApiHost, effectiveAppId, effectiveSecret, forceRefresh = true)
                    if (tokenResult.isSuccess) {
                        token = tokenResult.getOrThrow()
                        infoParams.put("token", token)
                        infoResp = postOpenApi(
                            targetHost,
                            "getLiveStreamInfo",
                            buildSignedEnvelope(effectiveAppId, effectiveSecret, infoParams)
                        )
                    }
                }

                extractCoverSnapshotFromStreams(infoResp)?.let { snap ->
                    snapshotUrlCache["${sn}_$chanToTry"] = snap
                }

                // If stream plan exists but is paused/disabled, enable it via modifyLivePlanStatus
                val existingLiveTokens = extractAllLiveTokens(infoResp)
                if (isStreamDisabledInInfo(infoResp) && existingLiveTokens.isNotEmpty()) {
                    for (lt in existingLiveTokens) {
                        val enableParams = JSONObject().apply {
                            put("token", token)
                            put("liveToken", lt)
                            put("status", 0) // 0 = enable live stream in Imou OpenAPI
                        }
                        postOpenApi(
                            targetHost,
                            "modifyLivePlanStatus",
                            buildSignedEnvelope(effectiveAppId, effectiveSecret, enableParams)
                        )
                    }
                }

                val exactExistingUrl = extractExactHlsForStreamId(infoResp, targetStreamId)
                if (!exactExistingUrl.isNullOrBlank()) {
                    Log.d(TAG, "Imou Cloud: Using existing streamId=$targetStreamId on channel=$chanToTry for $sn")
                    hlsUrlCache[cacheKey] = CachedHlsStream(exactExistingUrl)
                    return@withContext Result.success(exactExistingUrl)
                }

                // 2. Bind the requested targetStreamId (streamId=1 for SD sub-stream, streamId=0 for HD main stream)
                for (streamIdToBind in listOf(targetStreamId, if (targetStreamId == 1) 0 else 1)) {
                    val bindParams = JSONObject().apply {
                        put("token", token)
                        put("deviceId", sn)
                        put("channelId", chanToTry)
                        put("streamId", streamIdToBind)
                        put("liveMode", "proxy")
                    }
                    var bindResp = postOpenApi(
                        targetHost,
                        "bindDeviceLive",
                        buildSignedEnvelope(effectiveAppId, effectiveSecret, bindParams)
                    )
                    var boundUrl = extractExactHlsForStreamId(bindResp, streamIdToBind)
                        ?: extractHlsFromStreams(bindResp, streamIdToBind)

                    if (!boundUrl.isNullOrBlank()) {
                        hlsUrlCache[cacheKey] = CachedHlsStream(boundUrl)
                        return@withContext Result.success(boundUrl)
                    }

                    val bindCode = bindResp?.optJSONObject("result")?.optString("code", "").orEmpty()
                    if (bindCode == "SN1011" || bindCode == "SN1003") {
                        return@withContext Result.failure(Exception("OWNERSHIP_CONFLICT: Device is bound to another Imou account. Reset the device to factory defaults."))
                    }

                    // 3. If binding failed because an older liveToken is occupying the camera's live slot (LV1001),
                    // unbind the old liveToken and re-bind cleanly
                    if (existingLiveTokens.isNotEmpty() || bindCode.startsWith("LV", ignoreCase = true)) {
                        for (oldLiveToken in existingLiveTokens) {
                            val unbindParams = JSONObject().apply {
                                put("token", token)
                                put("liveToken", oldLiveToken)
                            }
                            postOpenApi(
                                targetHost,
                                "unbindLive",
                                buildSignedEnvelope(effectiveAppId, effectiveSecret, unbindParams)
                            )
                        }
                        bindResp = postOpenApi(
                            targetHost,
                            "bindDeviceLive",
                            buildSignedEnvelope(effectiveAppId, effectiveSecret, bindParams)
                        )
                        boundUrl = extractExactHlsForStreamId(bindResp, streamIdToBind)
                            ?: extractHlsFromStreams(bindResp, streamIdToBind)
                        if (!boundUrl.isNullOrBlank()) {
                            hlsUrlCache[cacheKey] = CachedHlsStream(boundUrl)
                            return@withContext Result.success(boundUrl)
                        }
                    }
                }

                // 4. Fallback to any valid HLS stream in infoResp for this channel
                val fallbackExistingUrl = extractHlsFromStreams(infoResp, targetStreamId)
                if (!fallbackExistingUrl.isNullOrBlank()) {
                    hlsUrlCache[cacheKey] = CachedHlsStream(fallbackExistingUrl)
                    return@withContext Result.success(fallbackExistingUrl)
                }
            }

            Result.failure(Exception("Camera is in standby or waking up. Tap Retry in a moment."))
        } catch (_: Exception) {
            Result.failure(Exception("Could not open live video from Imou Cloud right now."))
        }
    }

    suspend fun fetchCameraSnapshotUrl(
        bridge: BridgeSettings,
        serialNumber: String,
        channelId: String = "0"
    ): Result<String> = withContext(Dispatchers.IO) {
        val sn = resolveOrAutoDiscoverSerial(bridge, serialNumber)
        val cleanChan = channelId.trim().ifBlank { "0" }
        if (sn.isBlank()) {
            return@withContext Result.failure(Exception("Camera not found"))
        }

        val effectiveAppId = bridge.imouAppId.ifBlank { DEFAULT_IMOU_APP_ID }
        val effectiveSecret = bridge.imouAppSecret.ifBlank { DEFAULT_IMOU_APP_SECRET }

        val tokenRes = getAccessToken(bridge.imouApiHost, effectiveAppId, effectiveSecret)
        if (tokenRes.isFailure) {
            return@withContext Result.failure(Exception("Could not connect to Imou Cloud"))
        }
        val token = tokenRes.getOrThrow()
        val targetHost = cachedWorkingHost.ifBlank { bridge.imouApiHost }

        try {
            for (chanToTry in listOf("0", cleanChan).distinct()) {
                val snapParams = JSONObject().apply {
                    put("token", token)
                    put("deviceId", sn)
                    put("channelId", chanToTry)
                }
                val snapResp = postOpenApi(
                    targetHost,
                    "setDeviceSnapEnhanced",
                    buildSignedEnvelope(effectiveAppId, effectiveSecret, snapParams)
                )
                val resultObj = snapResp?.optJSONObject("result")
                if (resultObj?.optString("code") == "0") {
                    val url = resultObj.optJSONObject("data")?.optString("url", "")?.trim() ?: ""
                    if (url.isNotBlank()) {
                        snapshotUrlCache["${sn}_$chanToTry"] = url
                        return@withContext Result.success(url)
                    }
                }
                snapshotUrlCache["${sn}_$chanToTry"]?.let { cached ->
                    if (cached.isNotBlank()) return@withContext Result.success(cached)
                }
            }
            Result.failure(Exception("Camera preview is updating"))
        } catch (_: Exception) {
            Result.failure(Exception("Camera preview is unavailable"))
        }
    }

    suspend fun controlMovePTZ(
        bridge: BridgeSettings,
        serialNumber: String,
        channelId: String = "0",
        direction: PtzDirection
    ): Boolean = withContext(Dispatchers.IO) {
        val sn = resolveOrAutoDiscoverSerial(bridge, serialNumber)
        if (sn.isBlank()) return@withContext false

        val effectiveAppId = bridge.imouAppId.ifBlank { DEFAULT_IMOU_APP_ID }
        val effectiveSecret = bridge.imouAppSecret.ifBlank { DEFAULT_IMOU_APP_SECRET }

        val tokenRes = getAccessToken(bridge.imouApiHost, effectiveAppId, effectiveSecret)
        if (tokenRes.isFailure) return@withContext false
        val token = tokenRes.getOrThrow()
        val targetHost = cachedWorkingHost.ifBlank { bridge.imouApiHost }

        try {
            for (chanToTry in listOf("0", channelId.ifBlank { "0" }).distinct()) {
                val params = JSONObject().apply {
                    put("token", token)
                    put("deviceId", sn)
                    put("channelId", chanToTry)
                    put("operation", direction.imouCode.toString())
                    put("duration", "500")
                }
                val resp = postOpenApi(
                    targetHost,
                    "controlMovePTZ",
                    buildSignedEnvelope(effectiveAppId, effectiveSecret, params)
                )
                val code = resp?.optJSONObject("result")?.optString("code") ?: ""
                if (code == "0") {
                    ptzCapabilityBySerial[sn] = true
                    return@withContext true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeImouStreamUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return ""
        return if (trimmed.contains(":8890/") && trimmed.startsWith("https://", ignoreCase = true)) {
            "http://" + trimmed.removePrefix("https://").removePrefix("HTTPS://")
        } else {
            trimmed
        }
    }

    private fun isStreamDisabledInInfo(resp: JSONObject?): Boolean {
        val resultObj = resp?.optJSONObject("result") ?: return false
        if (resultObj.optString("code") != "0") return false
        val streamsArr = resultObj.optJSONObject("data")?.optJSONArray("streams") ?: return false
        for (i in 0 until streamsArr.length()) {
            val s = streamsArr.optJSONObject(i) ?: continue
            if (s.optInt("status", 0) == 1) return true
        }
        return false
    }

    /**
     * Finds the exact requested `streamId` (e.g. `1` for SD sub-stream or `0` for HD main stream).
     * Prioritizes genuine `https://` `live.m3u8` URLs (port 443/8891), and preserves native `http://`
     * on port 8890 so LibVLC never hangs negotiating TLS on plain-HTTP port 8890[cite: 1].
     */
    private fun extractExactHlsForStreamId(resp: JSONObject?, targetStreamId: Int): String? {
        val resultObj = resp?.optJSONObject("result") ?: return null
        if (resultObj.optString("code") != "0") return null
        val streamsArr = resultObj.optJSONObject("data")?.optJSONArray("streams") ?: return null
        var fallbackHttpMatch: String? = null
        for (i in 0 until streamsArr.length()) {
            val s = streamsArr.optJSONObject(i) ?: continue
            val rawHls = s.optString("hls", "").trim()
            if (rawHls.isBlank()) continue
            val hls = normalizeImouStreamUrl(rawHls)
            val sId = s.optString("streamId", "-1").toIntOrNull() ?: s.optInt("streamId", -1)
            if (sId == targetStreamId) {
                if (hls.startsWith("https://", ignoreCase = true)) {
                    return hls
                } else if (fallbackHttpMatch == null) {
                    fallbackHttpMatch = hls
                }
            }
        }
        return fallbackHttpMatch
    }

    private fun extractAllLiveTokens(resp: JSONObject?): List<String> {
        val resultObj = resp?.optJSONObject("result") ?: return emptyList()
        if (resultObj.optString("code") != "0") return emptyList()
        val streamsArr = resultObj.optJSONObject("data")?.optJSONArray("streams") ?: return emptyList()
        val tokens = mutableListOf<String>()
        for (i in 0 until streamsArr.length()) {
            val s = streamsArr.optJSONObject(i) ?: continue
            val lt = s.optString("liveToken", "").trim()
            if (lt.isNotBlank() && lt !in tokens) {
                tokens.add(lt)
            }
        }
        return tokens
    }

    private fun extractCoverSnapshotFromStreams(resp: JSONObject?): String? {
        val resultObj = resp?.optJSONObject("result") ?: return null
        if (resultObj.optString("code") != "0") return null
        val streamsArr = resultObj.optJSONObject("data")?.optJSONArray("streams") ?: return null
        for (i in 0 until streamsArr.length()) {
            val s = streamsArr.optJSONObject(i) ?: continue
            val cover = s.optString("coverUrl", "").trim()
            if (cover.isNotBlank()) return cover
        }
        return null
    }

    private fun extractHlsFromStreams(resp: JSONObject?, preferredStreamId: Int): String? {
        val resultObj = resp?.optJSONObject("result") ?: return null
        if (resultObj.optString("code") != "0") return null
        val streamsArr = resultObj.optJSONObject("data")?.optJSONArray("streams") ?: return null

        var anyHttpsHls: String? = null
        var preferredHttpHls: String? = null
        var anyHttpHls: String? = null

        for (i in 0 until streamsArr.length()) {
            val s = streamsArr.optJSONObject(i) ?: continue
            val rawHls = s.optString("hls", "").trim()
            if (rawHls.isBlank()) continue
            val hls = normalizeImouStreamUrl(rawHls)
            val sId = s.optString("streamId", "-1").toIntOrNull() ?: s.optInt("streamId", -1)
            val isHttps = hls.startsWith("https://", ignoreCase = true)

            if (sId == preferredStreamId && isHttps) {
                return hls
            }
            if (isHttps && anyHttpsHls == null) {
                anyHttpsHls = hls
            }
            if (sId == preferredStreamId && preferredHttpHls == null) {
                preferredHttpHls = hls
            }
            if (anyHttpHls == null) {
                anyHttpHls = hls
            }
        }
        return anyHttpsHls ?: preferredHttpHls ?: anyHttpHls
    }
}
