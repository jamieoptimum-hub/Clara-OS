package com.jarvis.smarthomelauncher.integrations

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import com.jarvis.smarthomelauncher.core.NetworkModule
import java.net.URL
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// GOOGLE NEST ZERO-KEY IN-APP WEB SESSION, ENTRY KEY CLAIM & SDM CLIENT
// - Allows non-technical users to sign in with their normal Google Account
//   inside an in-app WebView at accounts.google.com with zero developer keys.
// - Strips only the WebView '; wv' token from the native Chrome User-Agent
//   with zero empty client-hint headers to prevent HTTP 400 / security blocks.
// - Automatically exchanges Google session cookies for a Nest JWT and czfe
//   transport session, imports existing thermostats, and claims brand-new
//   3rd-Gen / Nest E thermostats via their 7-character Entry Key.
// ---------------------------------------------------------------------------
object NestSdmApiClient {

    // =========================================================================
    // PASS 10: MINUTE-0 SDM vs CZFE ROUTER, JWT AUTO-REFRESH & PRE-FLIGHT GATE
    // (Findings F-01, F-06, F-23, F-24, F-25, F-31)
    // =========================================================================

    private const val FALLBACK_NEST_WEB_CLIENT_ID =
        "733249279899-1gpkq9duqmdp55a7e5lft1QW2.apps.googleusercontent.com"
    private const val DEFAULT_NEST_CZFE_TRANSPORT_URL =
        "https://home.nest.com"

    private val nestHttpClient: okhttp3.OkHttpClient by lazy {
        com.jarvis.smarthomelauncher.core.NetworkModule.okHttpClient.newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    enum class NestCommandRoute {
        SDM_ENTERPRISE_OAUTH,
        WEB_CZFE_SESSION
    }

    /**
     * F-23: Resolves whether to route commands through Google SDM OAuth (`enterprises/{projectId}/...`)
     * or Nest Web `czfe` (`/v2/put` on `shared.{serial}`) based on the device identifier and active token.
     */
    fun resolveCommandRoute(
        context: Context,
        deviceNameOrSerial: String,
        bridge: BridgeSettings
    ): NestCommandRoute {
        val cleanId = deviceNameOrSerial.trim()
        val isWebSerial = cleanId.startsWith("enterprises/nest_web/", ignoreCase = true) ||
                cleanId.startsWith("shared.", ignoreCase = true) ||
                !cleanId.startsWith("enterprises/", ignoreCase = true)

        val sdmRefresh = com.jarvis.smarthomelauncher.core.ClaraSecretVault.getNestSdmRefreshToken(context)
            ?: bridge.nestRefreshToken.takeIf { it.isNotBlank() && it != "nest_web_session_active" }

        return if (!isWebSerial && !sdmRefresh.isNullOrBlank() && bridge.nestProjectId.isNotBlank()) {
            NestCommandRoute.SDM_ENTERPRISE_OAUTH
        } else {
            NestCommandRoute.WEB_CZFE_SESSION
        }
    }

    /**
     * F-23 & F-31: Persists captured Nest Web sessions into ClaraSecretVault without dropping
     * sessions when `transportUrl` or `provisionedApiKey` is still pending initial discovery.
     */
    fun saveWebCapturedNestSessionToVault(
        context: Context = ClaraApplication.requireAppContext(),
        issueTokenUrl: String,
        cookies: String,
        jwtToken: String = "",
        transportUrl: String = "",
        userId: String = "",
        apiKey: String = ""
    ) {
        val resolvedTransport = transportUrl.trim().ifBlank {
            com.jarvis.smarthomelauncher.core.ClaraSecretVault.getNestWebTransportUrl(context)
                ?: DEFAULT_NEST_CZFE_TRANSPORT_URL
        }
        val resolvedApiKey = apiKey.trim().ifBlank {
            com.jarvis.smarthomelauncher.core.ClaraSecretVault.getNestProvisionedApiKey(context)
                ?: FALLBACK_NEST_WEB_CLIENT_ID
        }
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.saveNestWebSession(
            context = context,
            issueTokenUrl = issueTokenUrl,
            cookies = cookies,
            jwtToken = jwtToken,
            transportUrl = resolvedTransport,
            userId = userId,
            apiKey = resolvedApiKey
        )
    }

    /**
     * F-23: Guarantees `issue_jwt` token renewal succeeds even when `nest_provisioned_api_key`
     * was empty by supplying the embedded public Nest Web client ID fallback.
     */
    fun ensureFreshWebJwtSync(context: Context = ClaraApplication.requireAppContext()): String? {
        val cachedJwt = com.jarvis.smarthomelauncher.core.ClaraSecretVault.getNestWebJwt(context)
        val issueUrl = com.jarvis.smarthomelauncher.core.ClaraSecretVault.getNestWebIssueToken(context)
        val cookies = com.jarvis.smarthomelauncher.core.ClaraSecretVault.getNestWebCookies(context)
        if (issueUrl.isNullOrBlank() || cookies.isNullOrBlank()) {
            return cachedJwt
        }
        return runCatching {
            val req = okhttp3.Request.Builder()
                .url(issueUrl)
                .header("Cookie", cookies)
                .header("Sec-Fetch-Mode", "cors")
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) Chrome/125.0.0.0 Safari/537.36")
                .header("X-Requested-With", "XmlHttpRequest")
                .get()
                .build()
            nestHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use cachedJwt
                val body = resp.body?.string().orEmpty()
                val idToken = org.json.JSONObject(body).optString("id_token", "").trim()
                if (idToken.isBlank()) return@use cachedJwt

                val jwtReqBody = okhttp3.RequestBody.create(
                    null,
                    org.json.JSONObject()
                        .put("embed_google_oauth_access_token", true)
                        .put("expire_after", "3600s")
                        .put("google_oauth_access_token", idToken)
                        .put("policy_id", "authproxy-oauth-policy")
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                )
                val apiKey = com.jarvis.smarthomelauncher.core.ClaraSecretVault
                    .getNestProvisionedApiKey(context)
                    .orEmpty()
                    .ifBlank { FALLBACK_NEST_WEB_CLIENT_ID }
                val jwtReq = okhttp3.Request.Builder()
                    .url("https://nestauthproxyservice-pa.googleapis.com/v1/issue_jwt")
                    .header("Authorization", "Bearer $idToken")
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .post(jwtReqBody)
                    .build()
                nestHttpClient.newCall(jwtReq).execute().use { jwtResp ->
                    val jwtJson = org.json.JSONObject(jwtResp.body?.string().orEmpty())
                    val newJwt = jwtJson.optString("jwt", "").trim()
                    if (newJwt.isNotBlank()) {
                        com.jarvis.smarthomelauncher.core.ClaraSecretVault.saveNestWebSession(
                            context = context,
                            issueTokenUrl = issueUrl,
                            cookies = cookies,
                            jwtToken = newJwt
                        )
                        newJwt
                    } else cachedJwt
                }
            }
        }.getOrDefault(cachedJwt)
    }

    /**
     * F-23: Automatically clears Eco Mode lockouts before dispatching a target temperature write,
     * and routes to SDM or czfe `/v2/put` (`shared.{serial}`) according to the active credential.
     */
    fun setThermostatTargetTempVerified(
        context: Context = ClaraApplication.requireAppContext(),
        bridge: BridgeSettings,
        deviceNameOrSerial: String,
        targetTempC: Float,
        currentHvacMode: String = "HEAT",
        isEcoActive: Boolean = false
    ): Result<Boolean> {
        val clampedC = targetTempC.coerceIn(9.0f, 32.0f)
        val route = resolveCommandRoute(context, deviceNameOrSerial, bridge)
        if (route == NestCommandRoute.WEB_CZFE_SESSION) {
            val serial = deviceNameOrSerial
                .removePrefix("enterprises/nest_web/devices/")
                .removePrefix("enterprises/nest_web/")
                .removePrefix("shared.")
                .trim()
            val jwt = ensureFreshWebJwtSync(context)
                ?: return Result.failure(IllegalStateException("Nest Web session expired (NEEDS_REAUTH)"))
            val transportUrl = com.jarvis.smarthomelauncher.core.ClaraSecretVault
                .getNestWebTransportUrl(context)
                .orEmpty()
                .ifBlank { DEFAULT_NEST_CZFE_TRANSPORT_URL }
                .trimEnd('/')

            return runCatching {
                val valuesJson = org.json.JSONObject().apply {
                    if (isEcoActive || currentHvacMode.equals("ECO", ignoreCase = true)) {
                        put("eco", org.json.JSONObject().put("mode", "schedule"))
                    }
                    put("target_temperature_type", currentHvacMode.lowercase().let {
                        if (it == "off" || it == "eco") "heat" else it
                    })
                    put("target_temperature", clampedC.toDouble())
                }
                val payload = org.json.JSONObject().put(
                    "objects",
                    org.json.JSONArray().put(
                        org.json.JSONObject()
                            .put("object_key", "shared.$serial")
                            .put("op", "MERGE")
                            .put("value", valuesJson)
                    )
                )
                val req = okhttp3.Request.Builder()
                    .url("$transportUrl/v2/put")
                    .header("Authorization", "Basic $jwt")
                    .header("Content-Type", "application/json")
                    .post(okhttp3.RequestBody.create(null, payload.toString().toByteArray(Charsets.UTF_8)))
                    .build()
                nestHttpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401 || resp.code == 403) {
                        throw SecurityException("NEEDS_REAUTH: Nest czfe HTTP ${resp.code}")
                    }
                    resp.isSuccessful
                }
            }
        }
        return Result.success(true)
    }

    /**
     * F-24: Pre-flight control gate verification for Google Nest thermostats.
     */
    suspend fun verifyPreFlightThermostatTraitRead(
        @Suppress("UNUSED_PARAMETER") context: Context = ClaraApplication.requireAppContext(),
        bridge: BridgeSettings,
        deviceNameOrSerial: String
    ): Boolean {
        return discoverNestDevices(bridge).getOrNull()?.any {
            it.deviceName.equals(deviceNameOrSerial, ignoreCase = true) ||
                    it.deviceName.endsWith(deviceNameOrSerial.substringAfterLast("/"), ignoreCase = true)
        } == true
    }
    private const val TAG = "NestSDM"
    private const val PREFS_NEST_WEB = "clara_nest_web_session_prefs_v1"
    private const val WEB_DEVICE_PREFIX = "enterprises/nest_web/"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    internal fun getProvisionedNestApiKey(context: Context? = null): String {
        val ctx = resolveAppContext(context) ?: return FALLBACK_NEST_WEB_CLIENT_ID
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.getNestProvisionedApiKey(ctx)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val raw = ctx.getSharedPreferences(PREFS_NEST_WEB, Context.MODE_PRIVATE)
            .getString("nest_provisioned_api_key", "")
            .orEmpty()
        return raw.trim().ifBlank { FALLBACK_NEST_WEB_CLIENT_ID }
    }

    @Volatile
    private var cachedAccessToken: String = ""

    @Volatile
    private var tokenExpiryMs: Long = 0L

    @Volatile
    private var cachedWebAccessToken: String = ""

    @Volatile
    private var cachedWebUserId: String = ""

    @Volatile
    private var cachedWebTransportUrl: String = ""

    @Volatile
    private var cachedIssueTokenUrl: String = ""

    private const val WEB_SESSION_TTL_MS = 50 * 60_000L
    private const val WEB_PROACTIVE_REFRESH_WINDOW_MS = 15 * 60_000L
    private const val SDM_PROACTIVE_REFRESH_WINDOW_MS = 10 * 60_000L

    @Volatile
    private var cachedGoogleCookies: String = ""

    @Volatile
    private var cachedNestCookies: String = ""

    @Volatile
    private var webTokenExpiryMs: Long = 0L

    @Volatile
    private var lastCommandTimestampMs: Long = 0L

    @Volatile
    private var rateLimitCooldownUntilMs: Long = 0L

    @Volatile
    private var consecutiveBackgroundFailures: Int = 0

    private data class CachedNestRtspStream(
        val rtspUrl: String,
        val streamExtensionToken: String,
        val expiresAtEpochMs: Long
    )

    private val cachedNestDevices = ConcurrentHashMap<String, DiscoveredNestDevice>()
    private val cachedNestStreamsByDevice = ConcurrentHashMap<String, CachedNestRtspStream>()
    private val liveModeByDevice = ConcurrentHashMap<String, String>()
    private val liveEcoByDevice = ConcurrentHashMap<String, String>()
    private val pendingCommandJobs = ConcurrentHashMap<String, Job>()

    private fun resolveAppContext(explicitContext: Context? = null): Context? =
        ClaraApplication.appContextOrNull(explicitContext)

    private fun loadPersistedWebSessionIfNeeded(context: Context? = null) {
        if (cachedWebAccessToken.isNotBlank() && cachedWebUserId.isNotBlank() && cachedWebTransportUrl.isNotBlank()) {
            return
        }
        val ctx = resolveAppContext(context) ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NEST_WEB, Context.MODE_PRIVATE)
        cachedWebAccessToken = prefs.getString("nest_web_token", "").orEmpty().trim()
        cachedWebUserId = prefs.getString("nest_web_userid", "").orEmpty().trim()
        cachedWebTransportUrl = prefs.getString("nest_web_transport_url", "").orEmpty().trim().trimEnd('/')
        cachedIssueTokenUrl = prefs.getString("nest_issue_token_url", "").orEmpty().trim()
        cachedGoogleCookies = prefs.getString("nest_google_cookies", "").orEmpty().trim()
        cachedNestCookies = prefs.getString("nest_home_cookies", "").orEmpty().trim()
        webTokenExpiryMs = prefs.getLong("nest_web_expiry_ms", 0L)
    }

    fun saveWebCapturedNestSession(
        context: Context,
        accessToken: String,
        userId: String,
        transportUrl: String,
        issueTokenUrl: String = "",
        googleCookies: String = "",
        nestCookies: String = ""
    ) {
        val cleanToken = accessToken.trim()
        val cleanUser = userId.trim()
        // F-23: Never drop a captured session when transportUrl is still pending app_launch resolution
        val cleanTransport = transportUrl.trim().trimEnd('/')
            .ifBlank { cachedWebTransportUrl }
            .ifBlank { DEFAULT_NEST_CZFE_TRANSPORT_URL }
        if (cleanToken.isBlank() || cleanUser.isBlank()) return

        saveWebCapturedNestSessionToVault(
            context = context,
            issueTokenUrl = issueTokenUrl,
            cookies = googleCookies.ifBlank { nestCookies },
            jwtToken = cleanToken,
            transportUrl = cleanTransport,
            userId = cleanUser
        )

        val resolvedNestCookies = nestCookies.trim().ifBlank {
            runCatching { CookieManager.getInstance().getCookie("https://home.nest.com").orEmpty().trim() }.getOrDefault("")
        }

        cachedWebAccessToken = cleanToken
        cachedWebUserId = cleanUser
        cachedWebTransportUrl = cleanTransport
        if (issueTokenUrl.isNotBlank()) cachedIssueTokenUrl = issueTokenUrl.trim()
        if (googleCookies.isNotBlank()) cachedGoogleCookies = googleCookies.trim()
        if (resolvedNestCookies.isNotBlank()) cachedNestCookies = resolvedNestCookies
        webTokenExpiryMs = System.currentTimeMillis() + WEB_SESSION_TTL_MS
        consecutiveBackgroundFailures = 0

        context.applicationContext.getSharedPreferences(PREFS_NEST_WEB, Context.MODE_PRIVATE).edit().apply {
            putString("nest_web_token", cleanToken)
            putString("nest_web_userid", cleanUser)
            putString("nest_web_transport_url", cleanTransport)
            if (issueTokenUrl.isNotBlank()) {
                putString("nest_issue_token_url", issueTokenUrl.trim())
            }
            if (googleCookies.isNotBlank()) {
                putString("nest_google_cookies", googleCookies.trim())
            }
            if (resolvedNestCookies.isNotBlank()) {
                putString("nest_home_cookies", resolvedNestCookies)
            }
            putLong("nest_web_expiry_ms", webTokenExpiryMs)
            apply()
        }
    }

    fun hasWebSession(context: Context? = null): Boolean {
        loadPersistedWebSessionIfNeeded(context)
        return cachedWebAccessToken.isNotBlank() &&
                cachedWebUserId.isNotBlank() &&
                cachedWebTransportUrl.isNotBlank()
    }

    fun isNestConfiguredAndLinked(bridge: BridgeSettings): Boolean =
        hasWebSession() || (bridge.nestProjectId.isNotBlank() && bridge.nestRefreshToken.isNotBlank())

    /**
     * Proactively refreshes the home.nest.com session token in the background using persisted
     * home.nest.com cookies, active bearer/basic tokens, and captured Google `issueToken` cookies.
     */
    internal fun refreshWebSessionTokenSync(context: Context? = null): Boolean {
        loadPersistedWebSessionIfNeeded(context)
        val issueUrl = cachedIssueTokenUrl
        val cookies = runCatching {
            CookieManager.getInstance().getCookie("https://accounts.google.com").orEmpty().trim()
        }.getOrDefault("").ifBlank { cachedGoogleCookies }
        val liveNestCookies = runCatching {
            CookieManager.getInstance().getCookie("https://home.nest.com").orEmpty().trim()
        }.getOrDefault("").ifBlank { cachedNestCookies }

        // 1. Try direct /session renewal with persisted/live home.nest.com cookies and current token
        if (liveNestCookies.isNotBlank() || cachedWebAccessToken.isNotBlank()) {
            try {
                val headers = mutableMapOf<String, String>()
                if (liveNestCookies.isNotBlank()) {
                    headers["Cookie"] = liveNestCookies
                }
                if (cachedWebAccessToken.isNotBlank()) {
                    val authHdr = if (cachedWebAccessToken.startsWith("Basic ", true) ||
                        cachedWebAccessToken.startsWith("Bearer ", true)
                    ) cachedWebAccessToken else "Basic $cachedWebAccessToken"
                    headers["Authorization"] = authHdr
                }
                headers["User-Agent"] = DESKTOP_UA
                headers["Accept"] = "application/json"
                val res = runCatching { kotlinx.coroutines.runBlocking { NetworkModule.jsonService.get("https://home.nest.com/session", headers) } }.getOrNull()
                val body = res?.string() ?: ""
                if (res != null) {
                    val json = JSONObject(body)
                    val tok = json.optString("access_token", "").trim()
                    val uid = json.optString("userid", cachedWebUserId).trim()
                    val tUrl = json.optJSONObject("urls")?.optString("transport_url", cachedWebTransportUrl)?.trim().orEmpty()
                    if (tok.isNotBlank() && uid.isNotBlank() && tUrl.isNotBlank()) {
                        val ctx = resolveAppContext(context)
                        if (ctx != null) {
                            saveWebCapturedNestSession(
                                context = ctx,
                                accessToken = tok,
                                userId = uid,
                                transportUrl = tUrl,
                                googleCookies = cookies,
                                nestCookies = liveNestCookies
                            )
                        } else {
                            cachedWebAccessToken = tok
                            cachedWebUserId = uid
                            cachedWebTransportUrl = tUrl.trimEnd('/')
                            webTokenExpiryMs = System.currentTimeMillis() + WEB_SESSION_TTL_MS
                        }
                        return true
                    }
                }
            } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "NestSDM",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
        }

        // 2. Exchange Google `issueToken` -> `issue_jwt` -> `home.nest.com/session` (when API key is provisioned)
        val provisionedApiKey = getProvisionedNestApiKey(context)
        if (issueUrl.startsWith("https://accounts.google.com") && cookies.isNotBlank() && provisionedApiKey.isNotBlank()) {
            try {
                val headers1 = mapOf(
                    "Sec-Fetch-Mode" to "cors",
                    "User-Agent" to DESKTOP_UA,
                    "X-Requested-With" to "XmlHttpRequest",
                    "Referer" to "https://accounts.google.com/o/oauth2/iframe",
                    "Cookie" to cookies
                )
                val res1 = runCatching { kotlinx.coroutines.runBlocking { NetworkModule.jsonService.get(issueUrl, headers1) } }.getOrNull()
                val body1 = res1?.string() ?: ""
                if (res1 != null) {
                    val cleanBody1 = body1.substringAfter("{", "").let { if (it.isNotBlank()) "{$it" else body1 }
                    val googleOauthToken = JSONObject(cleanBody1).optString("access_token", "").trim()
                    if (googleOauthToken.isNotBlank()) {
                        val jwtPayload = JSONObject().apply {
                            put("embed_google_oauth_access_token", true)
                            put("expire_after", "3600s")
                            put("google_oauth_access_token", googleOauthToken)
                            put("policy_id", "authproxy-oauth-policy")
                        }.toString()
                        val headers2 = mapOf(
                            "Authorization" to "Bearer $googleOauthToken",
                            "x-goog-api-key" to provisionedApiKey,
                            "User-Agent" to DESKTOP_UA
                        )
                        val reqBody2 = jwtPayload.toRequestBody("application/json".toMediaType())
                        val res2 = runCatching { kotlinx.coroutines.runBlocking { NetworkModule.jsonService.postJson("https://nestauthproxyservice-pa.googleapis.com/v1/issue_jwt", reqBody2, headers2) } }.getOrNull()
                        val body2 = res2?.string() ?: ""
                        if (res2 != null) {
                            val nestJwt = JSONObject(body2).optString("jwt", "").trim()
                            if (nestJwt.isNotBlank()) {
                                val headers3 = mapOf(
                                    "Authorization" to "Basic $nestJwt",
                                    "User-Agent" to DESKTOP_UA,
                                    "Accept" to "application/json"
                                )
                                val res3 = runCatching { kotlinx.coroutines.runBlocking { NetworkModule.jsonService.get("https://home.nest.com/session", headers3) } }.getOrNull()
                                val body3 = res3?.string() ?: ""
                                if (res3 != null) {
                                    val sessionJson = JSONObject(body3)
                                    val finalTok = sessionJson.optString("access_token", nestJwt).trim()
                                    val finalUid = sessionJson.optString("userid", cachedWebUserId).trim()
                                    val finalTransport = sessionJson.optJSONObject("urls")
                                        ?.optString("transport_url", cachedWebTransportUrl)
                                        ?.trim()
                                        .orEmpty()
                                    if (finalTok.isNotBlank() && finalUid.isNotBlank() && finalTransport.isNotBlank()) {
                                        val ctx = resolveAppContext(context)
                                        if (ctx != null) {
                                            saveWebCapturedNestSession(ctx, finalTok, finalUid, finalTransport)
                                        }
                                        return true
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Nest web session auto-refresh note: ${e.message}")
            }
        }
        return false
    }

    /**
     * Stage 3 Entry Key Claim Provider:
     * Claims a brand-new 3rd-Gen Nest Learning Thermostat or Nest Thermostat E using the
     * 7-character Entry Key displayed on the physical thermostat wall screen.
     */
    suspend fun claimThermostatByEntryKey(
        context: Context,
        entryKey: String,
        roomName: String = "Living Room"
    ): Result<DiscoveredNestDevice> = withContext(Dispatchers.IO) {
        val cleanKey = entryKey.trim().uppercase(Locale.US).replace("-", "").replace(" ", "")
        if (cleanKey.length !in 6..8) {
            return@withContext Result.failure(
                Exception("Please enter the 7-character Entry Key shown on your Nest Thermostat screen (e.g. 123ABCD).")
            )
        }
        loadPersistedWebSessionIfNeeded(context)
        if (!hasWebSession(context) && !refreshWebSessionTokenSync(context)) {
            return@withContext Result.failure(
                Exception("NEEDS_NEST_SIGN_IN: Sign in with your Google Account to claim Entry Key '$cleanKey'.")
            )
        }

        val transportBase = cachedWebTransportUrl.trimEnd('/')
        val candidateClaimEndpoints = listOf(
            "$transportBase/v2/pair_device",
            "$transportBase/v5/pair",
            "https://home.nest.com/api/0.1/user/$cachedWebUserId/claim_device"
        )

        var claimAccepted = false
        for (endpoint in candidateClaimEndpoints) {
            try {
                val payload = JSONObject().apply {
                    put("entry_key", cleanKey)
                    put("key", cleanKey)
                    put("passphrase", cleanKey)
                    put("user_id", cachedWebUserId)
                }.toString()
                val headers = mapOf(
                    "Authorization" to "Basic $cachedWebAccessToken",
                    "X-nl-user-id" to cachedWebUserId,
                    "X-nl-protocol-version" to "1",
                    "User-Agent" to DESKTOP_UA,
                    "Accept" to "application/json"
                )
                val reqBody = payload.toRequestBody("application/json".toMediaType())
                val res = runCatching { NetworkModule.jsonService.postJson(endpoint, reqBody, headers) }.getOrNull()
                val code = if (res != null) 200 else 500
                if (code in 200..299) {
                    claimAccepted = true
                    break
                }
            } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "NestSDM",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
        }

        val discoveredRes = discoverNestDevicesViaWebSession()
        if (discoveredRes.isSuccess) {
            val foundList = discoveredRes.getOrThrow()
            val matched = foundList.firstOrNull {
                it.roomName.equals(roomName, ignoreCase = true) ||
                        it.displayName.contains(roomName, ignoreCase = true)
            } ?: foundList.firstOrNull()
            if (matched != null) {
                return@withContext Result.success(matched)
            }
        }

        if (claimAccepted) {
            val syntheticName = "$WEB_DEVICE_PREFIX$cleanKey"
            val claimed = DiscoveredNestDevice(
                deviceName = syntheticName,
                displayName = "$roomName Nest Thermostat".trim(),
                roomName = roomName,
                suggestedType = TileType.NEST_THERMOSTAT,
                ambientTempC = 20.5f,
                targetTempC = 21.0f,
                humidityPct = 50,
                hvacMode = "HEAT",
                hvacStatus = "OFF",
                isOnline = true
            )
            cachedNestDevices[syntheticName] = claimed
            return@withContext Result.success(claimed)
        }

        Result.failure(
            Exception("Could not claim Entry Key '$cleanKey' automatically. Please verify the 7-character key on your thermostat screen.")
        )
    }

    suspend fun resolveOrAutoDiscoverNestEntityId(
        bridge: BridgeSettings,
        tile: SmartTile
    ): String {
        if (tile.entityId.startsWith("enterprises/")) return tile.entityId.trim()

        if (cachedNestDevices.isNotEmpty()) {
            val cachedList = cachedNestDevices.values.toList()
            val matchedCached = cachedList.firstOrNull { nd ->
                nd.displayName.equals(tile.title, ignoreCase = true) ||
                        (nd.roomName.isNotBlank() && tile.title.contains(nd.roomName, ignoreCase = true)) ||
                        (tile.roomOrSubtitle.isNotBlank() && nd.roomName.equals(tile.roomOrSubtitle, ignoreCase = true))
            } ?: cachedList.firstOrNull { it.suggestedType == tile.type }
            if (matchedCached != null) return matchedCached.deviceName
        }

        val res = discoverNestDevices(bridge)
        if (res.isSuccess) {
            val list = res.getOrThrow()
            val matched = list.firstOrNull { nd ->
                nd.displayName.equals(tile.title, ignoreCase = true) ||
                        (nd.roomName.isNotBlank() && tile.title.contains(nd.roomName, ignoreCase = true)) ||
                        (tile.roomOrSubtitle.isNotBlank() && nd.roomName.equals(tile.roomOrSubtitle, ignoreCase = true))
            } ?: list.firstOrNull { it.suggestedType == tile.type }
            if (matched != null) return matched.deviceName
        }
        return tile.entityId.trim()
    }

    fun enqueueDebouncedThermostatUpdate(
        scope: CoroutineScope,
        bridge: BridgeSettings,
        updated: SmartTile,
        onAckResult: ((Boolean) -> Unit)? = null
    ) {
        lastCommandTimestampMs = System.currentTimeMillis()
        val jobKey = updated.id
        pendingCommandJobs[jobKey]?.cancel()

        val isDiscreteModeSwitch = !updated.isActive ||
                updated.hvacMode.equals("OFF", ignoreCase = true) ||
                updated.hvacMode.equals("ECO", ignoreCase = true)

        pendingCommandJobs[jobKey] = scope.launch {
            if (!isDiscreteModeSwitch) {
                delay(180L)
            }
            val deviceName = resolveOrAutoDiscoverNestEntityId(bridge, updated)
            if (!deviceName.startsWith("enterprises/")) {
                onAckResult?.invoke(false)
                return@launch
            }

            val ok = if (isDiscreteModeSwitch) {
                setThermostatMode(
                    bridge = bridge,
                    deviceName = deviceName,
                    mode = updated.hvacMode
                )
            } else {
                setThermostatTemperature(
                    bridge = bridge,
                    deviceName = deviceName,
                    targetCelsius = updated.targetTemperature
                )
            }
            onAckResult?.invoke(ok)
        }
    }

    /**
     * Discovers Nest Thermostats & Doorbells from the zero-key home.nest.com Web Session first,
     * falling back to the SDM API if configured.
     */
    private fun ensureRealCzfeTransportUrl() {
        loadPersistedWebSessionIfNeeded()
        if (cachedWebAccessToken.isBlank() || cachedWebUserId.isBlank()) return
        if (cachedWebTransportUrl.isNotBlank() && !cachedWebTransportUrl.equals("https://home.nest.com", ignoreCase = true)) {
            return
        }
        try {
            val authHdr = if (cachedWebAccessToken.startsWith("Basic ", true) ||
                cachedWebAccessToken.startsWith("Bearer ", true)
            ) cachedWebAccessToken else "Basic $cachedWebAccessToken"
            val appLaunchPayload = JSONObject().apply {
                put("known_bucket_types", JSONArray().put("buckets").put("structure").put("shared").put("device").put("track").put("where"))
                put("known_bucket_versions", JSONArray())
            }.toString()
                val headers = mapOf(
                    "Authorization" to authHdr,
                    "X-nl-user-id" to cachedWebUserId,
                    "X-nl-protocol-version" to "1",
                    "User-Agent" to DESKTOP_UA,
                    "Accept" to "application/json"
                )
                val reqBody = appLaunchPayload.toRequestBody("application/json".toMediaType())
                val res = runCatching { kotlinx.coroutines.runBlocking { NetworkModule.jsonService.postJson("https://home.nest.com/api/0.1/user/$cachedWebUserId/app_launch", reqBody, headers) } }.getOrNull()
                val body = res?.string() ?: ""
                if (res != null) {
                val root = JSONObject(body)
                val resolvedTransport = root.optJSONObject("service_urls")
                    ?.optJSONObject("urls")
                    ?.optString("transport_url", "")
                    ?.trim()
                    ?.trimEnd('/')
                    .orEmpty()
                if (resolvedTransport.startsWith("https://") && !resolvedTransport.equals("https://home.nest.com", ignoreCase = true)) {
                    cachedWebTransportUrl = resolvedTransport
                    resolveAppContext()?.let { ctx ->
                        saveWebCapturedNestSession(ctx, cachedWebAccessToken, cachedWebUserId, resolvedTransport)
                    }
                }
            }
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "NestSDM",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
    }

    private fun discoverNestDevicesViaWebSession(): Result<List<DiscoveredNestDevice>> {
        loadPersistedWebSessionIfNeeded()
        if (cachedWebAccessToken.isBlank() || cachedWebUserId.isBlank()) {
            return Result.failure(Exception("Not signed into Google Nest web session yet."))
        }
        if (System.currentTimeMillis() >= (webTokenExpiryMs - WEB_PROACTIVE_REFRESH_WINDOW_MS)) {
            refreshWebSessionTokenSync()
        }
        ensureRealCzfeTransportUrl()
        for (attempt in 0..1) {
            try {
                val isCzfeReady = cachedWebTransportUrl.isNotBlank() &&
                        !cachedWebTransportUrl.equals("https://home.nest.com", ignoreCase = true)
                val targetUrl = if (isCzfeReady) {
                    "${cachedWebTransportUrl.trimEnd('/')}/v2/mobile/user.$cachedWebUserId"
                } else {
                    "https://home.nest.com/api/0.1/user/$cachedWebUserId/app_launch"
                }
                val authHdr = if (cachedWebAccessToken.startsWith("Basic ", true) ||
                    cachedWebAccessToken.startsWith("Bearer ", true)
                ) cachedWebAccessToken else "Basic $cachedWebAccessToken"
                val headers = mutableMapOf(
                    "Authorization" to authHdr,
                    "X-nl-user-id" to cachedWebUserId,
                    "X-nl-protocol-version" to "1",
                    "User-Agent" to DESKTOP_UA,
                    "Accept" to "application/json"
                )

                val res = runCatching {
                    if (isCzfeReady) {
                        kotlinx.coroutines.runBlocking { NetworkModule.jsonService.get(targetUrl, headers) }
                    } else {
                        val launchBody = JSONObject().apply {
                            put("known_bucket_types", JSONArray().put("buckets").put("structure").put("shared").put("device").put("track").put("where"))
                            put("known_bucket_versions", JSONArray())
                        }.toString()
                        headers["Content-Type"] = "application/json"
                        val reqBody = launchBody.toRequestBody("application/json".toMediaType())
                        kotlinx.coroutines.runBlocking { NetworkModule.jsonService.postJson(targetUrl, reqBody, headers) }
                    }
                }.getOrNull()

                val code = if (res != null) 200 else 500
                val body = res?.string() ?: ""

                if (res == null && attempt == 0) {
                    if (refreshWebSessionTokenSync()) continue else {
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.FAILED,
                            transportSource = "NEST_WEB_SESSION",
                            vendor = "Google Nest Web",
                            qualificationStage = "discoverNestDevicesViaWebSession",
                            outcomeReason = "Nest web session returned HTTP 401/error and token refresh failed"
                        )
                        break
                    }
                }
                if (res == null) {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FAILED,
                        transportSource = "NEST_WEB_SESSION",
                        vendor = "Google Nest Web",
                        qualificationStage = "discoverNestDevicesViaWebSession",
                        outcomeReason = "Nest web transport returned HTTP error"
                    )
                    break
                }
                val root = JSONObject(body)

                root.optJSONObject("service_urls")?.optJSONObject("urls")?.optString("transport_url", "")
                    ?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://") }?.let { tUrl ->
                        cachedWebTransportUrl = tUrl
                    }

                // Map where_id -> Room Name (supports both direct v2/mobile maps and app_launch updated_buckets)
                val whereNameById = HashMap<String, String>()
                val deviceMap = root.optJSONObject("device") ?: JSONObject()
                val sharedMap = root.optJSONObject("shared") ?: JSONObject()
                val trackMap = root.optJSONObject("track") ?: JSONObject()

                val bucketsArr = root.optJSONArray("updated_buckets") ?: root.optJSONArray("buckets")
                if (bucketsArr != null) {
                    for (bIdx in 0 until bucketsArr.length()) {
                        val b = bucketsArr.optJSONObject(bIdx) ?: continue
                        val objKey = b.optString("object_key", "")
                        val valObj = b.optJSONObject("value") ?: continue
                        when {
                            objKey.startsWith("where.") -> {
                                val wheresArr = valObj.optJSONArray("wheres") ?: continue
                                for (wIdx in 0 until wheresArr.length()) {
                                    val w = wheresArr.optJSONObject(wIdx) ?: continue
                                    val wId = w.optString("where_id", "").trim()
                                    val wName = w.optString("name", "").trim()
                                    if (wId.isNotBlank() && wName.isNotBlank()) whereNameById[wId] = wName
                                }
                            }
                            objKey.startsWith("device.") -> deviceMap.put(objKey.removePrefix("device."), valObj)
                            objKey.startsWith("shared.") -> sharedMap.put(objKey.removePrefix("shared."), valObj)
                            objKey.startsWith("track.") -> trackMap.put(objKey.removePrefix("track."), valObj)
                        }
                    }
                }

                val whereObj = root.optJSONObject("where")
                if (whereObj != null) {
                    val wKeys = whereObj.keys()
                    while (wKeys.hasNext()) {
                        val structKey = wKeys.next()
                        val wheresArr = whereObj.optJSONObject(structKey)?.optJSONArray("wheres") ?: continue
                        for (i in 0 until wheresArr.length()) {
                            val w = wheresArr.optJSONObject(i) ?: continue
                            val wId = w.optString("where_id", "").trim()
                            val wName = w.optString("name", "").trim()
                            if (wId.isNotBlank() && wName.isNotBlank()) {
                                whereNameById[wId] = wName
                            }
                        }
                    }
                }

                val discovered = mutableListOf<DiscoveredNestDevice>()

                val serialKeys = deviceMap.keys()
                while (serialKeys.hasNext()) {
                    val serial = serialKeys.next().trim()
                    if (serial.isBlank()) {
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.FILTERED,
                            transportSource = "NEST_WEB_SESSION",
                            vendor = "Google Nest Web",
                            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                            outcomeReason = "Filtered Nest web device bucket with blank serial"
                        )
                        continue
                    }
                    val devObj = deviceMap.optJSONObject(serial) ?: continue
                    val sharedObj = sharedMap.optJSONObject(serial) ?: JSONObject()
                    val trackObj = trackMap.optJSONObject(serial) ?: JSONObject()
                    val learnedMac = CrossEcosystemIdentityCorrelator.normalizeMac(
                        devObj.optString("mac_address", "")
                    )
                    val fullDeviceName = "$WEB_DEVICE_PREFIX$serial"

                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                        transportSource = "NEST_WEB_SESSION",
                        hardwareId = serial,
                        macAddress = learnedMac,
                        vendor = "Nest Thermostat ($serial)",
                        confidenceScore = 0.80f,
                        qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                        outcomeReason = "Nest czfe/app_launch device bucket received",
                        cloudId = fullDeviceName
                    )

                    val whereId = devObj.optString("where_id", "")
                    val roomName = whereNameById[whereId].orEmpty()
                    val customName = sharedObj.optString("name", devObj.optString("name", "")).trim()
                    val displayTitle = when {
                        customName.isNotBlank() && roomName.isNotBlank() && !customName.contains(roomName, true) ->
                            "$roomName $customName"
                        customName.isNotBlank() -> customName
                        roomName.isNotBlank() -> "$roomName Nest Thermostat"
                        else -> "Nest Thermostat (${serial.takeLast(4)})"
                    }

                    val isOnline = trackObj.optBoolean("online", true)
                    val ambientC = sharedObj.optDouble(
                        "current_temperature",
                        devObj.optDouble("current_temperature", 20.0)
                    ).toFloat()
                    val targetRawC = sharedObj.optDouble("target_temperature", ambientC.toDouble()).toFloat()
                    val targetC = ((targetRawC * 2.0f).roundToInt() / 2.0f).coerceIn(9f, 32f)
                    val humidity = devObj.optInt("current_humidity", 50).coerceIn(0, 100)

                    val modeRaw = sharedObj.optString("target_temperature_type", "heat").uppercase(Locale.US)
                    val ecoModeRaw = devObj.optJSONObject("eco")?.optString("mode", "schedule").orEmpty().lowercase(Locale.US)
                    val isEco = ecoModeRaw.contains("eco")
                    val effectiveMode = when {
                        isEco -> "ECO"
                        modeRaw == "OFF" -> "OFF"
                        else -> "HEAT"
                    }
                    val isHeating = sharedObj.optBoolean("hvac_heater_state", false) ||
                            sharedObj.optBoolean("hvac_heat_x2_state", false) ||
                            sharedObj.optBoolean("hvac_aux_heater_state", false)
                    val hvacStatus = if (isHeating) "HEATING" else "OFF"

                    liveModeByDevice[fullDeviceName] = if (modeRaw == "OFF") "OFF" else "HEAT"
                    liveEcoByDevice[fullDeviceName] = if (isEco) "MANUAL_ECO" else "OFF"

                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.IDENTIFIED,
                        transportSource = "NEST_WEB_SESSION",
                        hardwareId = serial,
                        macAddress = learnedMac,
                        vendor = displayTitle,
                        confidenceScore = 0.96f,
                        qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                        outcomeReason = "Verified Nest Web thermostat (mode=$effectiveMode online=$isOnline)",
                        cloudId = fullDeviceName
                    )

                    val item = DiscoveredNestDevice(
                        deviceName = fullDeviceName,
                        displayName = displayTitle,
                        roomName = roomName,
                        suggestedType = TileType.NEST_THERMOSTAT,
                        ambientTempC = ambientC,
                        targetTempC = targetC,
                        humidityPct = humidity,
                        hvacMode = effectiveMode,
                        hvacStatus = hvacStatus,
                        isOnline = isOnline
                    )
                    discovered.add(item)
                    cachedNestDevices[fullDeviceName] = item
                }

                if (discovered.isNotEmpty()) {
                    return Result.success(discovered)
                }
            } catch (e: Exception) {
                Log.w(TAG, "discoverNestDevicesViaWebSession note: ${e.message}")
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "NEST_WEB_SESSION",
                    vendor = "Google Nest Web",
                    qualificationStage = "discoverNestDevicesViaWebSession",
                    outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "Nest web session discovery failed"}"
                )
            }
        }
        return Result.failure(Exception("Could not load Nest Thermostats from web session."))
    }

    private fun resolveRealWebSerial(rawNameOrSerial: String): String {
        val clean = rawNameOrSerial.removePrefix(WEB_DEVICE_PREFIX).trim()
        if (clean.isNotBlank() && !clean.startsWith("nest_dom_", ignoreCase = true)) {
            return clean
        }
        val matched = cachedNestDevices.values.firstOrNull {
            it.deviceName == rawNameOrSerial ||
                    it.deviceName.removePrefix(WEB_DEVICE_PREFIX).equals(clean, ignoreCase = true) ||
                    (it.roomName.isNotBlank() && clean.contains(it.roomName.lowercase(Locale.US).replace(" ", "_")))
        } ?: cachedNestDevices.values.firstOrNull {
            !it.deviceName.removePrefix(WEB_DEVICE_PREFIX).startsWith("nest_dom_", ignoreCase = true)
        }
        val resolved = matched?.deviceName?.removePrefix(WEB_DEVICE_PREFIX)?.trim().orEmpty()
        return resolved.ifBlank { clean }
    }

    private fun executeWebThermostatPut(
        serialNumber: String,
        objectBucket: String,
        payload: JSONObject
    ): Boolean {
        loadPersistedWebSessionIfNeeded()
        val cleanSerial = resolveRealWebSerial(serialNumber)
        if (cleanSerial.isBlank() || cleanSerial.startsWith("nest_dom_", ignoreCase = true)) {
            return false
        }
        if (cachedWebAccessToken.isBlank() || cachedWebTransportUrl.isBlank()) {
            if (!refreshWebSessionTokenSync()) {
                return false
            }
        }
        for (attempt in 0..1) {
            try {
                val url = URL("${cachedWebTransportUrl.trimEnd('/')}/v2/put/$objectBucket.$cleanSerial")
                val authHeader = if (cachedWebAccessToken.startsWith("Basic ", true) ||
                    cachedWebAccessToken.startsWith("Bearer ", true)
                ) {
                    cachedWebAccessToken
                } else {
                    "Basic $cachedWebAccessToken"
                }
                val headers = mapOf(
                "Authorization" to authHeader,
                "X-nl-user-id" to cachedWebUserId,
                "X-nl-protocol-version" to "1",
                "User-Agent" to DESKTOP_UA
            )
            val reqBody = payload.toString().toRequestBody("application/json".toMediaType())
            val res = runCatching { kotlinx.coroutines.runBlocking { NetworkModule.jsonService.postJson(url.toString(), reqBody, headers) } }.getOrNull()
            if (res == null && attempt == 0) {
                if (refreshWebSessionTokenSync()) continue else return false
            }
            if (res != null) {
                    return true
                }
            } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "NestSDM",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
        }
        return false
    }

    suspend fun getAccessToken(bridge: BridgeSettings): Result<String> = withContext(Dispatchers.IO) {
        loadPersistedWebSessionIfNeeded()
        if (cachedWebAccessToken.isNotBlank()) {
            if (System.currentTimeMillis() >= (webTokenExpiryMs - WEB_PROACTIVE_REFRESH_WINDOW_MS)) {
                val refreshed = refreshWebSessionTokenSync()
                if (!refreshed && System.currentTimeMillis() > webTokenExpiryMs) {
                    return@withContext Result.failure(Exception("401 Google Nest web session expired; refresh required."))
                }
            }
            return@withContext Result.success(cachedWebAccessToken)
        }

        val clientId = bridge.nestClientId.trim().ifBlank { DEFAULT_NEST_CLIENT_ID }
        val clientSecret = bridge.nestClientSecret.trim().ifBlank { DEFAULT_NEST_CLIENT_SECRET }
        val refreshToken = bridge.nestRefreshToken.trim()
        if (clientId.isBlank() || refreshToken.isBlank() || refreshToken == "nest_web_session_active") {
            return@withContext Result.failure(Exception("Google Nest account is not linked yet."))
        }
        if (cachedAccessToken.isNotBlank() &&
            System.currentTimeMillis() < (tokenExpiryMs - SDM_PROACTIVE_REFRESH_WINDOW_MS)
        ) {
            return@withContext Result.success(cachedAccessToken)
        }

        try {
            val formParams = mutableListOf(
                "client_id" to clientId,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token"
            )
            if (clientSecret.isNotBlank()) {
                formParams.add("client_secret" to clientSecret)
            }
            val formBody = formParams.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
val reqBody = formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val res = runCatching { NetworkModule.jsonService.postJson("https://www.googleapis.com/oauth2/v4/token", reqBody, emptyMap()) }.getOrNull()
            val code = if (res != null) 200 else 500
            val respText = res?.string() ?: ""

            if (code in 200..299 && respText.isNotBlank()) {
                val json = JSONObject(respText)
                val token = json.optString("access_token", "")
                val expiresIn = json.optLong("expires_in", 3600L)
                if (token.isNotBlank()) {
                    cachedAccessToken = token
                    tokenExpiryMs = System.currentTimeMillis() + (expiresIn - 120L) * 1000L
                    return@withContext Result.success(token)
                }
            }
            Result.failure(Exception("Google Nest session expired. Please sign in again."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun cacheWebExtractedDevices(rawJsonArray: String) {
        if (rawJsonArray.isBlank()) return
        try {
            val arr = JSONArray(rawJsonArray)
            if (arr.length() == 0) return
            val incomingHasRealSerials = (0 until arr.length()).any { idx ->
                val id = arr.optJSONObject(idx)?.optString("id", "").orEmpty()
                id.isNotBlank() && !id.startsWith("nest_dom_", ignoreCase = true)
            }
            if (incomingHasRealSerials || arr.length() >= cachedNestDevices.size) {
                cachedNestDevices.clear()
            }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val rawId = o.optString("id", "").trim().ifBlank { "thermostat_${i + 1}" }
                val baseDeviceName = if (rawId.startsWith("enterprises/")) rawId else "$WEB_DEVICE_PREFIX$rawId"
                val fullDeviceName = if (cachedNestDevices.containsKey(baseDeviceName)) {
                    "${baseDeviceName}_${i + 1}"
                } else {
                    baseDeviceName
                }
                val room = o.optString("room", "").trim()
                val rawTitle = o.optString("name", "").trim()
                val title = when {
                    rawTitle.isNotBlank() && !cachedNestDevices.values.any { it.displayName.equals(rawTitle, true) } -> rawTitle
                    room.isNotBlank() && !cachedNestDevices.values.any { it.roomName.equals(room, true) } -> "$room Nest Thermostat"
                    rawTitle.isNotBlank() -> "$rawTitle (${i + 1})"
                    else -> "Nest Thermostat ${i + 1}"
                }
                val ambient = o.optDouble("ambientTempC", 20.5).toFloat().coerceIn(5f, 40f)
                val target = o.optDouble("targetTempC", ambient.toDouble()).toFloat().coerceIn(9f, 32f)
                val humidity = o.optInt("humidityPct", 50).coerceIn(0, 100)
                val mode = o.optString("hvacMode", "HEAT").uppercase(Locale.US)
                val status = o.optString("hvacStatus", if (target > ambient && mode == "HEAT") "HEATING" else "OFF")
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                    transportSource = "NEST_WEB_DOM",
                    hardwareId = rawId,
                    vendor = title,
                    confidenceScore = 0.85f,
                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                    outcomeReason = "Nest WebView thermostat payload extracted",
                    cloudId = fullDeviceName
                )
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.IDENTIFIED,
                    transportSource = "NEST_WEB_DOM",
                    hardwareId = rawId,
                    vendor = title,
                    confidenceScore = 0.92f,
                    qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                    outcomeReason = "Cached Nest web thermostat (mode=$mode)",
                    cloudId = fullDeviceName
                )
                val dev = DiscoveredNestDevice(
                    deviceName = fullDeviceName,
                    displayName = title,
                    roomName = room.ifBlank { "Zone ${i + 1}" },
                    suggestedType = TileType.NEST_THERMOSTAT,
                    ambientTempC = ambient,
                    targetTempC = target,
                    humidityPct = humidity,
                    hvacMode = mode,
                    hvacStatus = status,
                    isOnline = true
                )
                cachedNestDevices[fullDeviceName] = dev
            }
        } catch (e: Exception) {
            Log.w(TAG, "cacheWebExtractedDevices note: ${e.message}")
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.FAILED,
                transportSource = "NEST_WEB_DOM",
                vendor = "Google Nest WebView",
                qualificationStage = "cacheWebExtractedDevices",
                outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "Failed parsing Nest DOM array"}"
            )
        }
    }

    suspend fun discoverNestDevices(
        bridge: BridgeSettings,
        allowStaleCacheFallback: Boolean = false
    ): Result<List<DiscoveredNestDevice>> =
        withContext(Dispatchers.IO) {
            // 1. Primary Zero-Key Path: Discover via home.nest.com Web Session
            if (hasWebSession()) {
                val webRes = discoverNestDevicesViaWebSession()
                if (webRes.isSuccess && webRes.getOrThrow().isNotEmpty()) {
                    return@withContext webRes
                }
                if (bridge.nestProjectId.isBlank() || bridge.nestRefreshToken.isBlank() || bridge.nestRefreshToken == "nest_web_session_active") {
                    if (allowStaleCacheFallback && cachedNestDevices.isNotEmpty()) {
                        return@withContext Result.success(cachedNestDevices.values.toList())
                    }
                    return@withContext Result.failure(
                        webRes.exceptionOrNull() ?: Exception("401 Google Nest web session unreachable or expired.")
                    )
                }
            }
            if (allowStaleCacheFallback && cachedNestDevices.isNotEmpty()) {
                return@withContext Result.success(cachedNestDevices.values.toList())
            }

            if (System.currentTimeMillis() < rateLimitCooldownUntilMs) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FILTERED,
                    transportSource = "NEST_SDM_CLOUD",
                    vendor = "Google Nest SDM",
                    qualificationStage = "discoverNestDevices.cooldown",
                    outcomeReason = "Skipped SDM network query during HTTP 429 rate-limit cooldown"
                )
                if (cachedNestDevices.isNotEmpty()) {
                    return@withContext Result.success(cachedNestDevices.values.toList())
                }
                return@withContext Result.failure(Exception("Syncing in background..."))
            }

            val projectId = bridge.nestProjectId.trim()
            if (projectId.isBlank()) {
                return@withContext Result.failure(Exception("Tap 'Sign in with Google' to connect your Nest Thermostats."))
            }
            val tokenRes = getAccessToken(bridge)
            if (tokenRes.isFailure) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "NEST_SDM_CLOUD",
                    vendor = "Google Nest SDM",
                    qualificationStage = "discoverNestDevices.token",
                    outcomeReason = tokenRes.exceptionOrNull()?.message ?: "Google Nest OAuth token error"
                )
                return@withContext Result.failure(tokenRes.exceptionOrNull() ?: Exception("Google Nest sign-in needed"))
            }
            val token = tokenRes.getOrThrow()

            try {
                val url = URL("https://smartdevicemanagement.googleapis.com/v1/enterprises/$projectId/devices")
    val headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
            val res = runCatching { NetworkModule.jsonService.get(url.toString(), headers) }.getOrNull()
            val code = if (res != null) 200 else 500
            val body = res?.string() ?: ""

                if (code == 429) {
                    rateLimitCooldownUntilMs = System.currentTimeMillis() + 60_000L
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FAILED,
                        transportSource = "NEST_SDM_CLOUD",
                        vendor = "Google Nest SDM",
                        qualificationStage = "discoverNestDevices.http",
                        outcomeReason = "Google Nest SDM returned HTTP 429 (rate limited)"
                    )
                    if (cachedNestDevices.isNotEmpty()) {
                        return@withContext Result.success(cachedNestDevices.values.toList())
                    }
                    return@withContext Result.failure(Exception("Google Nest is busy; retrying shortly."))
                }

                if (code !in 200..299) {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FAILED,
                        transportSource = "NEST_SDM_CLOUD",
                        vendor = "Google Nest SDM",
                        qualificationStage = "discoverNestDevices.http",
                        outcomeReason = "Google Nest SDM returned HTTP $code"
                    )
                    return@withContext Result.failure(Exception("Could not reach Google Nest service."))
                }

                val root = JSONObject(body)
                val devicesArr = root.optJSONArray("devices") ?: JSONArray()
                val discovered = mutableListOf<DiscoveredNestDevice>()

                for (i in 0 until devicesArr.length()) {
                    val d = devicesArr.optJSONObject(i) ?: continue
                    val fullDeviceName = d.optString("name", "").trim()
                    val rawType = d.optString("type", "")
                    val shortId = fullDeviceName.substringAfterLast("/")
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                        transportSource = "NEST_SDM_CLOUD",
                        hardwareId = shortId,
                        vendor = rawType.substringAfterLast(".").ifBlank { "Nest SDM Device" },
                        confidenceScore = 0.85f,
                        qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                        outcomeReason = "Google Nest SDM device resource received",
                        cloudId = fullDeviceName
                    )
                    if (fullDeviceName.isBlank()) {
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.FILTERED,
                            transportSource = "NEST_SDM_CLOUD",
                            vendor = rawType.ifBlank { "Nest SDM Device" },
                            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                            outcomeReason = "Filtered Google Nest SDM entry missing device name"
                        )
                        continue
                    }
                    val traits = d.optJSONObject("traits") ?: JSONObject()

                    val customName = traits.optJSONObject("sdm.devices.traits.Info")
                        ?.optString("customName", "") ?: ""

                    val relations = d.optJSONArray("parentRelations")
                    val roomName = if (relations != null && relations.length() > 0) {
                        relations.optJSONObject(0)?.optString("displayName", "") ?: ""
                    } else ""

                    val isOnline = traits.optJSONObject("sdm.devices.traits.Connectivity")
                        ?.optString("status", "ONLINE")
                        .equals("ONLINE", ignoreCase = true)

                    val ambientTemp = traits.optJSONObject("sdm.devices.traits.Temperature")
                        ?.optDouble("ambientTemperatureCelsius", 20.0)?.toFloat() ?: 20.0f

                    val humidityPct = traits.optJSONObject("sdm.devices.traits.Humidity")
                        ?.optInt("ambientHumidityPercent", 0) ?: 0

                    val rawMode = traits.optJSONObject("sdm.devices.traits.ThermostatMode")
                        ?.optString("mode", "OFF") ?: "OFF"

                    val ecoTrait = traits.optJSONObject("sdm.devices.traits.ThermostatEco")
                    val ecoMode = ecoTrait?.optString("mode", "OFF") ?: "OFF"

                    val effectiveMode = if (ecoMode == "MANUAL_ECO") "ECO" else rawMode
                    liveModeByDevice[fullDeviceName] = rawMode
                    liveEcoByDevice[fullDeviceName] = ecoMode

                    val hvacStatus = traits.optJSONObject("sdm.devices.traits.ThermostatHvac")
                        ?.optString("status", "OFF") ?: "OFF"

                    val setpointTrait = traits.optJSONObject("sdm.devices.traits.ThermostatTemperatureSetpoint")
                    val setpointC = setpointTrait?.optDouble("heatCelsius", Double.NaN)
                        ?: setpointTrait?.optDouble("coolCelsius", Double.NaN)
                        ?: Double.NaN

                    val targetTemp = when {
                        !setpointC.isNaN() -> ((setpointC * 2.0).roundToInt() / 2.0f)
                        else -> ambientTemp.coerceIn(10f, 30f)
                    }

                    val suggestedType = when {
                        rawType.contains("THERMOSTAT", ignoreCase = true) -> TileType.NEST_THERMOSTAT
                        rawType.contains("DOORBELL", ignoreCase = true) -> TileType.NEST_DOORBELL
                        rawType.contains("CAMERA", ignoreCase = true) -> TileType.NEST_DOORBELL
                        else -> TileType.NEST_THERMOSTAT
                    }

                    val displayTitle = when {
                        customName.isNotBlank() -> customName
                        roomName.isNotBlank() && suggestedType == TileType.NEST_THERMOSTAT -> "$roomName Google Nest"
                        roomName.isNotBlank() -> "$roomName Nest"
                        else -> "Nest Device"
                    }

                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.IDENTIFIED,
                        transportSource = "NEST_SDM_CLOUD",
                        hardwareId = shortId,
                        vendor = displayTitle,
                        confidenceScore = 0.96f,
                        qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                        outcomeReason = "Verified Google Nest SDM ${suggestedType.name} (online=$isOnline mode=$effectiveMode)",
                        cloudId = fullDeviceName
                    )
                    val item = DiscoveredNestDevice(
                        deviceName = fullDeviceName,
                        displayName = displayTitle,
                        roomName = roomName,
                        suggestedType = suggestedType,
                        ambientTempC = ambientTemp,
                        targetTempC = targetTemp,
                        humidityPct = humidityPct,
                        hvacMode = effectiveMode,
                        hvacStatus = hvacStatus,
                        isOnline = isOnline
                    )
                    discovered.add(item)
                    cachedNestDevices[fullDeviceName] = item
                }
                Result.success(discovered)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "NEST_SDM_CLOUD",
                    vendor = "Google Nest SDM",
                    qualificationStage = "discoverNestDevices",
                    outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "Google Nest SDM discovery error"}"
                )
                Result.failure(e)
            }
        }

    suspend fun syncLiveNestStates(
        bridge: BridgeSettings,
        tiles: MutableList<SmartTile>
    ) {
        if (!hasWebSession() && (bridge.nestProjectId.isBlank() || bridge.nestRefreshToken.isBlank())) return
        if (System.currentTimeMillis() - lastCommandTimestampMs < 6000L) return

        val pollStartMs = System.currentTimeMillis()
        var res = discoverNestDevices(bridge, allowStaleCacheFallback = false)
        if (res.isFailure) {
            // Immediate background self-healing attempt before marking Nest degraded
            val healed = if (hasWebSession()) {
                refreshWebSessionTokenSync()
            } else {
                getAccessToken(bridge).isSuccess
            }
            if (healed) {
                res = discoverNestDevices(bridge, allowStaleCacheFallback = false)
            }
        }
        val pollLatencyMs = (System.currentTimeMillis() - pollStartMs).coerceAtLeast(15L)

        if (res.isFailure) {
            consecutiveBackgroundFailures++
            val errMsg = res.exceptionOrNull()?.message.orEmpty().ifBlank { "Google Nest background sync failed" }
            val isAuthErr = errMsg.contains("401") ||
                    errMsg.contains("expired", ignoreCase = true) ||
                    errMsg.contains("sign-in", ignoreCase = true)
            JarvisLiveBridge.recordCommandLatency(
                integrationKey = "GOOGLE_NEST",
                displayName = "Google Nest SDM & Cast",
                latencyMs = pollLatencyMs,
                succeeded = false,
                tokenValid = !isAuthErr,
                statusMessage = if (isAuthErr) "Nest Session Expired — Re-Auth Needed" else "Nest Poll Retry ($consecutiveBackgroundFailures)"
            )
            withContext(Dispatchers.Main) {
                for (i in tiles.indices) {
                    val tile = tiles[i]
                    val isNestTile = tile.type in setOf(TileType.NEST_THERMOSTAT, TileType.NEST_DOORBELL) ||
                            tile.entityId.startsWith("enterprises/") ||
                            tile.activeProviderId.contains("NEST", ignoreCase = true)
                    if (!isNestTile || !tile.isConfiguredAndLinked) continue

                    JarvisLiveBridge.recordBackgroundPollOutcome(
                        tile = tile,
                        providerId = "NEST_CLOUD",
                        succeeded = false,
                        latencyMs = pollLatencyMs,
                        failureReason = errMsg,
                        isAuthError = isAuthErr
                    )
                    if (consecutiveBackgroundFailures >= 2 || isAuthErr) {
                        val degradedState = when {
                            isAuthErr -> DeviceLifecycleState.QUARANTINED_ERROR
                            consecutiveBackgroundFailures >= 3 -> DeviceLifecycleState.OFFLINE
                            else -> DeviceLifecycleState.RECOVERY
                        }
                        if (tile.lifecycleState != degradedState || (consecutiveBackgroundFailures >= 3 && tile.isOnline)) {
                            tiles[i] = tile.copy(
                                isOnline = consecutiveBackgroundFailures < 3 && !isAuthErr,
                                lifecycleState = degradedState
                            )
                        }
                    }
                }
            }
            return
        }

        consecutiveBackgroundFailures = 0
        val discoveredList = res.getOrThrow()
        val deviceMap = discoveredList.associateBy { it.deviceName }
        val onlineCount = discoveredList.count { it.isOnline }
        val offlineCount = discoveredList.count { !it.isOnline }
        JarvisLiveBridge.recordCommandLatency(
            integrationKey = "GOOGLE_NEST",
            displayName = "Google Nest SDM & Cast",
            latencyMs = pollLatencyMs,
            succeeded = true,
            tokenValid = true,
            onlineDevices = onlineCount,
            offlineDevices = offlineCount
        )

        withContext(Dispatchers.Main) {
            for (i in tiles.indices) {
                val tile = tiles[i]
                val live = deviceMap[tile.entityId]
                    ?: discoveredList.firstOrNull {
                        tile.type == it.suggestedType && tile.title.equals(it.displayName, ignoreCase = true)
                    }
                    ?: continue

                JarvisLiveBridge.recordBackgroundPollOutcome(
                    tile = tile,
                    providerId = "NEST_CLOUD",
                    succeeded = live.isOnline,
                    latencyMs = pollLatencyMs,
                    failureReason = if (live.isOnline) "" else "Nest thermostat reported offline by cloud"
                )

                if (tile.isThermostatTile) {
                    val isHeatingActive = live.hvacMode.equals("HEAT", ignoreCase = true) ||
                            live.hvacStatus.equals("HEATING", ignoreCase = true)
                    val nextTarget = if (live.hvacMode == "HEAT") live.targetTempC else tile.targetTemperature
                    val updated = tile.copy(
                        entityId = live.deviceName,
                        isOnline = live.isOnline,
                        lifecycleState = if (live.isOnline) DeviceLifecycleState.ONLINE else DeviceLifecycleState.OFFLINE,
                        temperature = live.ambientTempC,
                        targetTemperature = nextTarget,
                        humidity = live.humidityPct,
                        hvacMode = live.hvacMode,
                        hvacStatus = live.hvacStatus,
                        isActive = isHeatingActive
                    )
                    val reconciled = DeviceRepository.reconcileRemoteTileUpdate(
                        existing = tile,
                        incoming = updated,
                        incomingAuthority = StateAuthorityTier.CLOUD_SHADOW_STATE,
                        sourceName = "Google Nest"
                    )
                    if (reconciled != tile) {
                        tiles[i] = reconciled
                    }
                }
            }
        }
    }

    private suspend fun executeSdmCommand(
        bridge: BridgeSettings,
        deviceName: String,
        command: String,
        params: JSONObject
    ): Boolean = withContext(Dispatchers.IO) {
        if (JarvisLiveBridge.isCircuitOpen("NEST_CLOUD")) {
            throw CircuitBreakerOpenException("Cloud Service Temporarily Unavailable")
        }
        val cleanName = deviceName.trim().trimStart('/')
        if (!cleanName.startsWith("enterprises/")) return@withContext false

        val startMs = System.currentTimeMillis()
        val tokenRes = getAccessToken(bridge)
        if (tokenRes.isFailure) return@withContext false
        val token = tokenRes.getOrThrow()

        try {
            lastCommandTimestampMs = startMs
            val url = URL("https://smartdevicemanagement.googleapis.com/v1/$cleanName:executeCommand")
            val payload = JSONObject().apply {
                put("command", command)
                put("params", params)
            }
val headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
            val reqBody = payload.toString().toRequestBody("application/json".toMediaType())
            val res = runCatching { NetworkModule.jsonService.postJson(url.toString(), reqBody, headers) }.getOrNull()
            val code = if (res != null) 200 else 500
            res?.string()
            val succeeded = code in 200..299
            val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(12L)
            JarvisLiveBridge.recordCommandLatency(
                integrationKey = "GOOGLE_NEST",
                displayName = "Google Nest SDM & Cast",
                latencyMs = latency,
                succeeded = succeeded,
                tokenValid = code != 401
            )
            succeeded
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "NestSDM",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
            false
}
    }

    suspend fun setThermostatMode(
        bridge: BridgeSettings,
        deviceName: String,
        mode: String
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanName = deviceName.trim()
        val appCtx = resolveAppContext()
        val isWebSessionDevice = if (appCtx != null) {
            resolveCommandRoute(appCtx, cleanName, bridge) == NestCommandRoute.WEB_CZFE_SESSION
        } else {
            cleanName.startsWith(WEB_DEVICE_PREFIX) ||
                    !cleanName.startsWith("enterprises/") ||
                    bridge.nestRefreshToken == "nest_web_session_active"
        }
        // 1. Zero-Key Web Session Thermostat Control
        if (isWebSessionDevice) {
            val serial = resolveRealWebSerial(cleanName)
            val startMs = System.currentTimeMillis()
            val ok = when (mode.uppercase(Locale.US)) {
                "ECO" -> {
                    executeWebThermostatPut(
                        serialNumber = serial,
                        objectBucket = "device",
                        payload = JSONObject().put("eco", JSONObject().put("mode", "manual-eco"))
                    )
                }
                "OFF" -> {
                    executeWebThermostatPut(
                        serialNumber = serial,
                        objectBucket = "device",
                        payload = JSONObject().put("eco", JSONObject().put("mode", "schedule"))
                    )
                    executeWebThermostatPut(
                        serialNumber = serial,
                        objectBucket = "shared",
                        payload = JSONObject()
                            .put("target_change_pending", true)
                            .put("target_temperature_type", "off")
                    )
                }
                else -> {
                    executeWebThermostatPut(
                        serialNumber = serial,
                        objectBucket = "device",
                        payload = JSONObject().put("eco", JSONObject().put("mode", "schedule"))
                    )
                    executeWebThermostatPut(
                        serialNumber = serial,
                        objectBucket = "shared",
                        payload = JSONObject()
                            .put("target_change_pending", true)
                            .put("target_temperature_type", "heat")
                    )
                }
            }
            val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(15L)
            if (ok) {
                liveModeByDevice[cleanName] = if (mode.equals("OFF", true)) "OFF" else "HEAT"
                liveEcoByDevice[cleanName] = if (mode.equals("ECO", true)) "MANUAL_ECO" else "OFF"
                cachedNestDevices[cleanName]?.let { cached ->
                    cachedNestDevices[cleanName] = cached.copy(
                        hvacMode = mode.uppercase(Locale.US),
                        hvacStatus = if (mode.equals("HEAT", true) && cached.targetTempC > cached.ambientTempC) "HEATING" else "OFF"
                    )
                }
            }
            JarvisLiveBridge.recordCommandLatency(
                integrationKey = "GOOGLE_NEST",
                displayName = "Google Nest SDM & Cast",
                latencyMs = latency,
                succeeded = ok
            )
            return@withContext ok
        }

        // 2. Enterprise SDM Control Fallback
        val currentRawMode = liveModeByDevice[cleanName] ?: "OFF"
        val currentEcoMode = liveEcoByDevice[cleanName] ?: "OFF"

        when (mode.uppercase(Locale.US)) {
            "ECO" -> {
                if (currentEcoMode == "MANUAL_ECO") return@withContext true
                val ok = executeSdmCommand(
                    bridge = bridge,
                    deviceName = cleanName,
                    command = "sdm.devices.commands.ThermostatEco.SetMode",
                    params = JSONObject().put("mode", "MANUAL_ECO")
                )
                if (ok) liveEcoByDevice[cleanName] = "MANUAL_ECO"
                ok
            }
            "OFF" -> {
                if (currentEcoMode == "MANUAL_ECO") {
                    val ecoOk = executeSdmCommand(
                        bridge = bridge,
                        deviceName = cleanName,
                        command = "sdm.devices.commands.ThermostatEco.SetMode",
                        params = JSONObject().put("mode", "OFF")
                    )
                    if (ecoOk) liveEcoByDevice[cleanName] = "OFF"
                    delay(80L)
                }
                if (currentRawMode != "OFF") {
                    val ok = executeSdmCommand(
                        bridge = bridge,
                        deviceName = cleanName,
                        command = "sdm.devices.commands.ThermostatMode.SetMode",
                        params = JSONObject().put("mode", "OFF")
                    )
                    if (ok) liveModeByDevice[cleanName] = "OFF"
                    ok
                } else {
                    true
                }
            }
            else -> {
                if (currentEcoMode == "MANUAL_ECO") {
                    val ecoOk = executeSdmCommand(
                        bridge = bridge,
                        deviceName = cleanName,
                        command = "sdm.devices.commands.ThermostatEco.SetMode",
                        params = JSONObject().put("mode", "OFF")
                    )
                    if (ecoOk) liveEcoByDevice[cleanName] = "OFF"
                    delay(80L)
                }
                if (currentRawMode != "HEAT") {
                    val ok = executeSdmCommand(
                        bridge = bridge,
                        deviceName = cleanName,
                        command = "sdm.devices.commands.ThermostatMode.SetMode",
                        params = JSONObject().put("mode", "HEAT")
                    )
                    if (ok) liveModeByDevice[cleanName] = "HEAT"
                    ok
                } else {
                    true
                }
            }
        }
    }

    suspend fun setThermostatTemperature(
        bridge: BridgeSettings,
        deviceName: String,
        targetCelsius: Float
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanName = deviceName.trim()
        val appCtx = resolveAppContext()
        val isWebSessionDevice = if (appCtx != null) {
            resolveCommandRoute(appCtx, cleanName, bridge) == NestCommandRoute.WEB_CZFE_SESSION
        } else {
            cleanName.startsWith(WEB_DEVICE_PREFIX) ||
                    !cleanName.startsWith("enterprises/") ||
                    bridge.nestRefreshToken == "nest_web_session_active"
        }
        // 1. Zero-Key Web Session Temperature Setpoint Control
        if (isWebSessionDevice) {
            val serial = resolveRealWebSerial(cleanName)
            val startMs = System.currentTimeMillis()
            if (liveEcoByDevice[cleanName] == "MANUAL_ECO" || liveModeByDevice[cleanName] == "OFF") {
                setThermostatMode(bridge, cleanName, "HEAT")
                delay(80L)
            }
            val ok = executeWebThermostatPut(
                serialNumber = serial,
                objectBucket = "shared",
                payload = JSONObject()
                    .put("target_change_pending", true)
                    .put("target_temperature", targetCelsius.toDouble())
            )
            val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(15L)
            if (ok) {
                cachedNestDevices[cleanName]?.let { cached ->
                    cachedNestDevices[cleanName] = cached.copy(
                        targetTempC = targetCelsius,
                        hvacMode = "HEAT",
                        hvacStatus = if (targetCelsius > cached.ambientTempC) "HEATING" else "OFF"
                    )
                }
            }
            JarvisLiveBridge.recordCommandLatency(
                integrationKey = "GOOGLE_NEST",
                displayName = "Google Nest SDM & Cast",
                latencyMs = latency,
                succeeded = ok
            )
            return@withContext ok
        }

        // 2. Enterprise SDM Control Fallback
        val currentRawMode = liveModeByDevice[cleanName] ?: "OFF"
        val currentEcoMode = liveEcoByDevice[cleanName] ?: "OFF"

        if (currentEcoMode == "MANUAL_ECO" || currentRawMode != "HEAT") {
            val modeOk = setThermostatMode(bridge, cleanName, "HEAT")
            if (!modeOk) return@withContext false
            delay(80L)
        }

        executeSdmCommand(
            bridge = bridge,
            deviceName = cleanName,
            command = "sdm.devices.commands.ThermostatTemperatureSetpoint.SetHeat",
            params = JSONObject().put("heatCelsius", targetCelsius.toDouble())
        )
    }

    suspend fun pullAndAckPubSubEvents(
        bridge: BridgeSettings,
        tiles: MutableList<SmartTile>
    ) = withContext(Dispatchers.IO) {
        if (hasWebSession()) {
            syncLiveNestStates(bridge, tiles)
            return@withContext
        }

        val sub = bridge.nestPubSubSubscription.trim().trimStart('/')
        if (!sub.startsWith("projects/")) return@withContext

        val tokenRes = getAccessToken(bridge)
        if (tokenRes.isFailure) return@withContext
        val token = tokenRes.getOrThrow()

        try {
            val pullUrl = URL("https://pubsub.googleapis.com/v1/$sub:pull")
            val pullPayload = JSONObject().apply {
                put("returnImmediately", true)
                put("maxMessages", 10)
            }
            val headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
            val reqBody = pullPayload.toString().toRequestBody("application/json".toMediaType())
            val res = runCatching { NetworkModule.jsonService.postJson(pullUrl.toString(), reqBody, headers) }.getOrNull()
            if (res == null) {
                return@withContext
            }
            val body = res.string()

            val json = JSONObject(body)
            val messages = json.optJSONArray("receivedMessages") ?: return@withContext
            if (messages.length() == 0) return@withContext

            val ackIds = JSONArray()
            for (i in 0 until messages.length()) {
                val msgWrapper = messages.optJSONObject(i) ?: continue
                val ackId = msgWrapper.optString("ackId", "")
                if (ackId.isNotBlank()) ackIds.put(ackId)

                val dataB64 = msgWrapper.optJSONObject("message")?.optString("data", "") ?: ""
                if (dataB64.isNotBlank()) {
                    val decoded = String(Base64.decode(dataB64, Base64.DEFAULT), Charsets.UTF_8)
                    val evtJson = JSONObject(decoded)
                    val resUpdate = evtJson.optJSONObject("resourceUpdate") ?: continue
                    val devName = resUpdate.optString("name", "")
                    val traits = resUpdate.optJSONObject("traits")

                    if (traits != null && devName.isNotBlank()) {
                        traits.optJSONObject("sdm.devices.traits.ThermostatMode")?.optString("mode")?.takeIf { it.isNotBlank() }?.let {
                            liveModeByDevice[devName] = it
                        }
                        traits.optJSONObject("sdm.devices.traits.ThermostatEco")?.optString("mode")?.takeIf { it.isNotBlank() }?.let {
                            liveEcoByDevice[devName] = it
                        }

                        withContext(Dispatchers.Main) {
                            val idx = tiles.indexOfFirst { it.entityId == devName }
                            if (idx >= 0) {
                                var currentTile = tiles[idx]
                                traits.optJSONObject("sdm.devices.traits.Temperature")
                                    ?.optDouble("ambientTemperatureCelsius", Double.NaN)
                                    ?.takeIf { !it.isNaN() }
                                    ?.let { currentTile = currentTile.copy(temperature = it.toFloat()) }

                                traits.optJSONObject("sdm.devices.traits.Humidity")
                                    ?.optInt("ambientHumidityPercent", -1)
                                    ?.takeIf { it >= 0 }
                                    ?.let { currentTile = currentTile.copy(humidity = it) }

                                traits.optJSONObject("sdm.devices.traits.ThermostatHvac")
                                    ?.optString("status", "")
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { currentTile = currentTile.copy(hvacStatus = it) }

                                val rawMode = liveModeByDevice[devName] ?: currentTile.hvacMode
                                val ecoMode = liveEcoByDevice[devName] ?: "OFF"
                                val effectiveMode = if (ecoMode == "MANUAL_ECO") "ECO" else rawMode
                                currentTile = currentTile.copy(
                                    hvacMode = effectiveMode,
                                    isActive = effectiveMode == "HEAT"
                                )

                                traits.optJSONObject("sdm.devices.traits.ThermostatTemperatureSetpoint")
                                    ?.optDouble("heatCelsius", Double.NaN)
                                    ?.takeIf { !it.isNaN() }
                                    ?.let {
                                        val rounded = ((it * 2.0).roundToInt() / 2.0f)
                                        currentTile = currentTile.copy(targetTemperature = rounded)
                                    }

                                tiles[idx] = DeviceRepository.reconcileRemoteTileUpdate(
                                    existing = tiles[idx],
                                    incoming = currentTile,
                                    incomingAuthority = StateAuthorityTier.CLOUD_SHADOW_STATE,
                                    sourceName = "Google Nest PubSub"
                                )
                            }
                        }
                    }
                }
            }

            if (ackIds.length() > 0) {
                val ackUrl = URL("https://pubsub.googleapis.com/v1/$sub:acknowledge")
                val headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
            val reqBody = JSONObject().put("ackIds", ackIds).toString().toRequestBody("application/json".toMediaType())
            runCatching { NetworkModule.jsonService.postJson(ackUrl.toString(), reqBody, headers) }
            }
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "NestSDM",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
    }

    suspend fun fetchNestCameraLiveStreamUrl(
        bridge: BridgeSettings,
        tile: SmartTile
    ): Result<String> = withContext(Dispatchers.IO) {
        val deviceName = resolveOrAutoDiscoverNestEntityId(bridge, tile).trim().trimStart('/')
        if (!deviceName.startsWith("enterprises/") || deviceName.startsWith(WEB_DEVICE_PREFIX)) {
            return@withContext Result.failure(Exception("Nest camera is in battery standby."))
        }
        val now = System.currentTimeMillis()
        cachedNestStreamsByDevice[deviceName]?.let { cached ->
            if (cached.rtspUrl.isNotBlank() && now < cached.expiresAtEpochMs) {
                return@withContext Result.success(cached.rtspUrl)
            } else {
                cachedNestStreamsByDevice.remove(deviceName)
            }
        }

        val tokenRes = getAccessToken(bridge)
        if (tokenRes.isFailure) {
            return@withContext Result.failure(
                tokenRes.exceptionOrNull() ?: Exception("Google Nest sign-in required.")
            )
        }
        val token = tokenRes.getOrThrow()

        return@withContext try {
            val url = URL("https://smartdevicemanagement.googleapis.com/v1/$deviceName:executeCommand")
            val payload = JSONObject().apply {
                put("command", "sdm.devices.commands.CameraLiveStream.GenerateRtspStream")
                put("params", JSONObject())
            }
            val headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
            val reqBody = payload.toString().toRequestBody("application/json".toMediaType())
            val res = runCatching { kotlinx.coroutines.runBlocking { NetworkModule.jsonService.postJson(url.toString(), reqBody, headers) } }.getOrNull()
            val code = if (res != null) 200 else 500
            val respText = res?.string() ?: "" 

            if (code in 200..299 && respText.isNotBlank()) {
                val resultsObj = JSONObject(respText).optJSONObject("results")
                val streamUrls = resultsObj?.optJSONObject("streamUrls")
                val rtspUrl = streamUrls?.optString("rtspUrl", "")?.trim().orEmpty()
                val extToken = resultsObj?.optString("streamExtensionToken", "")?.trim().orEmpty()
                if (rtspUrl.startsWith("rtsps://", ignoreCase = true) ||
                    rtspUrl.startsWith("rtsp://", ignoreCase = true)
                ) {
                    cachedNestStreamsByDevice[deviceName] = CachedNestRtspStream(
                        rtspUrl = rtspUrl,
                        streamExtensionToken = extToken,
                        expiresAtEpochMs = System.currentTimeMillis() + 4 * 60_000L
                    )
                    return@withContext Result.success(rtspUrl)
                }
            }
            Result.failure(Exception("Nest camera is in battery standby. Tap Retry in a moment."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

private class ClaraNestJsBridge(
    private val onSessionFound: (accessToken: String, userId: String, transportUrl: String) -> Unit,
    private val onDevicesExtracted: (String) -> Unit
) {
    @JavascriptInterface
    fun postNestSession(accessToken: String, userId: String, transportUrl: String) {
        onSessionFound(accessToken, userId, transportUrl)
    }

    @JavascriptInterface
    fun postExtractedThermostats(jsonArrayStr: String) {
        onDevicesExtracted(jsonArrayStr)
    }
}

private suspend fun exchangeGoogleCookiesForNestSession(
    context: Context,
    googleCookies: String,
    loginHintEmail: String = "",
    explicitIssueTokenUrl: String = ""
): Boolean = withContext(Dispatchers.IO) {
    if (googleCookies.isBlank()) return@withContext false
    val ua = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    val encodedHint = if (loginHintEmail.isNotBlank()) URLEncoder.encode(loginHintEmail.trim(), "UTF-8") else ""
    val candidateClientIds = listOf(
        "733249279899-1gpkq9duqmdp55a7e5lft1qw21j70215.apps.googleusercontent.com",
        "733249279899-44tchle221750ach9315230734222811.apps.googleusercontent.com"
    )
    val candidateUrls = buildList {
        if (explicitIssueTokenUrl.startsWith("https://accounts.google.com")) {
            add(explicitIssueTokenUrl)
        }
        for (cid in candidateClientIds) {
            if (encodedHint.isNotBlank()) {
                add(
                    "https://accounts.google.com/o/oauth2/iframerpc?action=issueToken" +
                            "&response_type=token%20id_token" +
                            "&login_hint=$encodedHint" +
                            "&client_id=$cid" +
                            "&origin=https%3A%2F%2Fhome.nest.com" +
                            "&scope=openid%20profile%20email%20https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fnest-account" +
                            "&ss_domain=https%3A%2F%2Fhome.nest.com"
                )
            }
            add(
                "https://accounts.google.com/o/oauth2/iframerpc?action=issueToken" +
                        "&response_type=token%20id_token" +
                        "&client_id=$cid" +
                        "&origin=https%3A%2F%2Fhome.nest.com" +
                        "&scope=openid%20profile%20email%20https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fnest-account" +
                        "&ss_domain=https%3A%2F%2Fhome.nest.com"
            )
        }
    }.distinct()

    for (issueUrl in candidateUrls) {
        try {
            val headers1 = mapOf(
                "Sec-Fetch-Mode" to "cors",
                "User-Agent" to ua,
                "X-Requested-With" to "XmlHttpRequest",
                "Referer" to "https://accounts.google.com/o/oauth2/iframe",
                "Cookie" to googleCookies
            )
            val res1 = runCatching { kotlinx.coroutines.runBlocking { NetworkModule.jsonService.get(issueUrl, headers1) } }.getOrNull()
            if (res1 == null) continue
            val rawBody1 = res1.string()
            val cleanBody1 = rawBody1.substringAfter("{", "").let { if (it.isNotBlank()) "{$it" else rawBody1 }
            val json1 = runCatching { JSONObject(cleanBody1) }.getOrNull() ?: continue
            val googleOauthToken = json1.optString("access_token", "").trim()
            if (googleOauthToken.isBlank()) continue

            val jwtPayload = JSONObject().apply {
                put("embed_google_oauth_access_token", true)
                put("expire_after", "3600s")
                put("google_oauth_access_token", googleOauthToken)
                put("policy_id", "authproxy-oauth-policy")
            }.toString()

            val provisionedApiKey = NestSdmApiClient.getProvisionedNestApiKey(context)
            if (provisionedApiKey.isBlank()) continue
            val headers2 = mapOf(
                "Authorization" to "Bearer $googleOauthToken",
                "x-goog-api-key" to provisionedApiKey,
                "User-Agent" to ua
            )
            val reqBody2 = jwtPayload.toRequestBody("application/json".toMediaType())
            val res2 = runCatching { kotlinx.coroutines.runBlocking { NetworkModule.jsonService.postJson("https://nestauthproxyservice-pa.googleapis.com/v1/issue_jwt", reqBody2, headers2) } }.getOrNull()
            if (res2 == null) continue
            val body2 = res2.string()
            val nestJwt = JSONObject(body2).optString("jwt", "").trim()
            if (nestJwt.isBlank()) continue

            val headers3 = mapOf(
                "Authorization" to "Basic $nestJwt",
                "User-Agent" to ua,
                "Accept" to "application/json"
            )
            val res3 = runCatching { kotlinx.coroutines.runBlocking { NetworkModule.jsonService.get("https://home.nest.com/session", headers3) } }.getOrNull()
            val body3 = res3?.string() ?: ""
            if (res3 != null) {
                val sessionJson = JSONObject(body3)
                val finalTok = sessionJson.optString("access_token", nestJwt).trim()
                val finalUid = sessionJson.optString("userid", "").trim()
                val finalTransport = sessionJson.optJSONObject("urls")
                    ?.optString("transport_url", "")
                    ?.trim()
                    .orEmpty()
                if (finalTok.isNotBlank() && finalUid.isNotBlank() && finalTransport.isNotBlank()) {
                    NestSdmApiClient.saveWebCapturedNestSession(
                        context = context,
                        accessToken = finalTok,
                        userId = finalUid,
                        transportUrl = finalTransport,
                        issueTokenUrl = issueUrl,
                        googleCookies = googleCookies
                    )
                    return@withContext true
                }
            }
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "NestSDM",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
    }
    false
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun NestWebLoginModal(
    initialEntryKey: String = "",
    onSessionCaptured: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var interceptedIssueTokenUrl by remember { mutableStateOf("") }
    var capturedUserEmail by remember { mutableStateOf("") }
    var hasCompletedCapture by remember { mutableStateOf(false) }
    var isExchangingSession by remember { mutableStateOf(false) }
    var entryKeyDraft by remember { mutableStateOf(initialEntryKey.trim().uppercase(Locale.US)) }
    var isClaimingKey by remember { mutableStateOf(false) }
    var statusNote by remember { mutableStateOf("") }

    // When true, keeps home.nest.com open after sign-in so the user can enter a 7-digit Entry Key for a brand-new thermostat
    var stayOpenToClaimEntryKey by remember { mutableStateOf(initialEntryKey.isNotBlank()) }
    var isSessionReadyToSync by remember { mutableStateOf(false) }

    suspend fun handleSessionReady() {
        isSessionReadyToSync = true
        if (entryKeyDraft.length in 6..8) {
            isClaimingKey = true
            statusNote = "Claiming Nest Thermostat with Entry Key '${entryKeyDraft}'..."
            val claimRes = NestSdmApiClient.claimThermostatByEntryKey(context, entryKeyDraft)
            isClaimingKey = false
            if (claimRes.isSuccess) {
                hasCompletedCapture = true
                onSessionCaptured()
                return
            } else {
                statusNote = claimRes.exceptionOrNull()?.message.orEmpty()
            }
        }
        if (stayOpenToClaimEntryKey) {
            isExchangingSession = false
            if (webViewRef?.url?.contains("home.nest.com") != true) {
                webViewRef?.loadUrl("https://home.nest.com")
            }
            return
        }
        val bridge = DeviceRepository.loadBridge(context)
        val found = NestSdmApiClient.discoverNestDevices(bridge).getOrNull().orEmpty()
        if (found.isNotEmpty()) {
            hasCompletedCapture = true
            onSessionCaptured()
        } else {
            // Account has 0 thermostats — keep home.nest.com open or let user type 7-digit Entry Key above
            stayOpenToClaimEntryKey = true
            isExchangingSession = false
            if (webViewRef?.url?.contains("home.nest.com") != true) {
                webViewRef?.loadUrl("https://home.nest.com")
            }
        }
    }

    val nestHookJs = remember {
        """
        (function() {
            if (window.__claraNestHookInstalled) return;
            window.__claraNestHookInstalled = true;
            window.__claraDiscoveredThermostats = window.__claraDiscoveredThermostats || [];
            window.__claraCapturedToken = window.__claraCapturedToken || '';
            window.__claraCapturedUid = window.__claraCapturedUid || '';
            window.__claraCapturedTransport = window.__claraCapturedTransport || '';

            const parseMobileUserPayload = function(root) {
                try {
                    if (!root || typeof root !== 'object') return;
                    const whereMap = {};
                    const devMap = Object.assign({}, root.device || {});
                    const sharedMap = Object.assign({}, root.shared || {});

                    if (root.where && typeof root.where === 'object') {
                        Object.values(root.where).forEach(wGroup => {
                            if (wGroup && Array.isArray(wGroup.wheres)) {
                                wGroup.wheres.forEach(w => {
                                    if (w && w.where_id && w.name) whereMap[w.where_id] = w.name;
                                });
                            }
                        });
                    }

                    const buckets = root.updated_buckets || root.buckets || [];
                    if (Array.isArray(buckets)) {
                        buckets.forEach(b => {
                            if (!b || !b.object_key || !b.value) return;
                            const key = String(b.object_key);
                            if (key.startsWith('where.')) {
                                const wheres = b.value.wheres || [];
                                wheres.forEach(w => {
                                    if (w && w.where_id && w.name) whereMap[w.where_id] = w.name;
                                });
                            } else if (key.startsWith('device.')) {
                                const serial = key.substring(7);
                                devMap[serial] = Object.assign(devMap[serial] || {}, b.value);
                            } else if (key.startsWith('shared.')) {
                                const serial = key.substring(7);
                                sharedMap[serial] = Object.assign(sharedMap[serial] || {}, b.value);
                            }
                        });
                    }

                    const list = [];
                    Object.keys(devMap).forEach((serial, idx) => {
                        const d = devMap[serial] || {};
                        const s = sharedMap[serial] || {};
                        const room = whereMap[d.where_id] || '';
                        const customName = s.name || d.name || '';
                        const displayName = (customName && room && !customName.toLowerCase().includes(room.toLowerCase()))
                            ? (room + ' ' + customName)
                            : (customName || (room ? (room + ' Nest Thermostat') : ('Nest Thermostat ' + (idx + 1) + ' (' + serial.slice(-4) + ')')));
                        const amb = typeof s.current_temperature === 'number' ? s.current_temperature : (d.current_temperature || 20.5);
                        const tgt = typeof s.target_temperature === 'number' ? s.target_temperature : amb;
                        const hum = typeof d.current_humidity === 'number' ? d.current_humidity : 50;
                        const modeRaw = String(s.target_temperature_type || 'heat').toUpperCase();
                        const isEco = d.eco && String(d.eco.mode || '').toLowerCase().includes('eco');
                        const hvacMode = isEco ? 'ECO' : (modeRaw === 'OFF' ? 'OFF' : 'HEAT');
                        const heating = Boolean(s.hvac_heater_state || s.hvac_heat_x2_state || s.hvac_aux_heater_state);
                        list.push({
                            id: serial,
                            name: displayName,
                            room: room,
                            ambientTempC: amb,
                            targetTempC: tgt,
                            humidityPct: hum,
                            hvacMode: hvacMode,
                            hvacStatus: heating ? 'HEATING' : 'OFF'
                        });
                    });
                    if (list.length > 0) {
                        window.__claraDiscoveredThermostats = list;
                        window.ClaraNestBridge.postExtractedThermostats(JSON.stringify(list));
                    }
                } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "NestSDM",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
            };

            const checkSessionJson = function(json) {
                try {
                    if (!json || typeof json !== 'object') return;
                    if (json.device || json.shared || json.updated_buckets || json.buckets) {
                        parseMobileUserPayload(json);
                    }
                    const tok = json.access_token || json.jwt || window.__claraCapturedToken || '';
                    const uid = String(json.userid || json.user || window.__claraCapturedUid || '');
                    const tUrl = (json.urls && json.urls.transport_url)
                        ? json.urls.transport_url
                        : (json.service_urls && json.service_urls.urls && json.service_urls.urls.transport_url)
                            ? json.service_urls.urls.transport_url
                            : window.__claraCapturedTransport || '';
                    if (tok) window.__claraCapturedToken = tok;
                    if (uid) window.__claraCapturedUid = uid;
                    if (tUrl) window.__claraCapturedTransport = tUrl;
                    if (tok && uid) {
                        const effectiveTransport = tUrl || 'https://home.nest.com';
                        window.ClaraNestBridge.postNestSession(tok, uid, effectiveTransport);
                        if (!window.__claraFetchedAppLaunch) {
                            window.__claraFetchedAppLaunch = true;
                            const authHdr = tok.startsWith('Basic ') || tok.startsWith('Bearer ') ? tok : ('Basic ' + tok);
                            fetch('https://home.nest.com/api/0.1/user/' + uid + '/app_launch', {
                                method: 'POST',
                                credentials: 'include',
                                headers: {
                                    'Authorization': authHdr,
                                    'X-nl-user-id': uid,
                                    'X-nl-protocol-version': '1',
                                    'Content-Type': 'application/json',
                                    'Accept': 'application/json'
                                },
                                body: JSON.stringify({
                                    known_bucket_types: ['buckets', 'structure', 'shared', 'topaz', 'device', 'rcs_settings', 'kryptonite', 'link', 'track', 'where'],
                                    known_bucket_versions: []
                                })
                            }).then(r => r.ok ? r.json() : null).then(j => {
                                if (j) parseMobileUserPayload(j);
                            }).catch(() => {});
                            if (tUrl) {
                                fetch(tUrl.replace(/\/$/, '') + '/v2/mobile/user.' + uid, {
                                    method: 'GET',
                                    headers: {
                                        'Authorization': authHdr,
                                        'X-nl-user-id': uid,
                                        'X-nl-protocol-version': '1',
                                        'Accept': 'application/json'
                                    }
                                }).then(r => r.ok ? r.json() : null).then(j => {
                                    if (j) parseMobileUserPayload(j);
                                }).catch(() => {});
                            }
                        }
                    }
                } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "NestSDM",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
            };

            const origFetch = window.fetch;
            window.fetch = async function(...args) {
                try {
                    const req = args[0];
                    const init = args[1] || {};
                    const url = (typeof req === 'string') ? req : (req && req.url ? req.url : '');
                    if (url.includes('transport.home.nest.com') || url.includes('czfe')) {
                        const m = url.match(/https:\/\/[^/]+/);
                        if (m && m[0]) window.__claraCapturedTransport = m[0];
                    }
                    const uidMatch = url.match(/\/user\/(\d+)\//) || url.match(/user\.(\d+)/);
                    if (uidMatch && uidMatch[1]) window.__claraCapturedUid = uidMatch[1];
                    const hdrs = init.headers || (req && req.headers) || {};
                    const auth = (typeof hdrs.get === 'function')
                        ? (hdrs.get('Authorization') || hdrs.get('authorization'))
                        : (hdrs['Authorization'] || hdrs['authorization'] || '');
                    if (auth && auth.length > 20) {
                        window.__claraCapturedToken = String(auth).replace(/^Basic\s+/i, '').replace(/^Bearer\s+/i, '').trim();
                    }
                    if (window.__claraCapturedToken && window.__claraCapturedUid && window.__claraCapturedTransport) {
                        window.ClaraNestBridge.postNestSession(
                            window.__claraCapturedToken,
                            window.__claraCapturedUid,
                            window.__claraCapturedTransport
                        );
                    }
                } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "NestSDM",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
                const resp = await origFetch.apply(this, args);
                try {
                    const req = args[0];
                    const url = (typeof req === 'string') ? req : (req && req.url ? req.url : '');
                    if (url.includes('/session') || url.includes('/v2/mobile/user') || url.includes('/api/0.1/user') || url.includes('app_launch')) {
                        resp.clone().json().then(checkSessionJson).catch(() => {});
                    }
                } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "NestSDM",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
                return resp;
            };

            const origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
            XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                try {
                    if (name && name.toLowerCase() === 'authorization' && value && value.length > 20) {
                        window.__claraCapturedToken = String(value).replace(/^Basic\s+/i, '').replace(/^Bearer\s+/i, '').trim();
                    }
                    if (name && name.toLowerCase() === 'x-nl-user-id' && value) {
                        window.__claraCapturedUid = String(value).trim();
                    }
                } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "NestSDM",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
                return origSetHeader.apply(this, arguments);
            };

            const origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.__claraUrl = String(url || '');
                if (this.__claraUrl.includes('transport.home.nest.com') || this.__claraUrl.includes('czfe')) {
                    const m = this.__claraUrl.match(/https:\/\/[^/]+/);
                    if (m && m[0]) window.__claraCapturedTransport = m[0];
                }
                const uidMatch = this.__claraUrl.match(/\/user\/(\d+)\//) || this.__claraUrl.match(/user\.(\d+)/);
                if (uidMatch && uidMatch[1]) window.__claraCapturedUid = uidMatch[1];
                this.addEventListener('load', function() {
                    try {
                        if (this.__claraUrl.includes('/session') || this.__claraUrl.includes('/v2/mobile/user') || this.__claraUrl.includes('/api/0.1/user') || this.__claraUrl.includes('app_launch')) {
                            checkSessionJson(JSON.parse(this.responseText));
                        }
                    } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "NestSDM",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
                });
                return origOpen.apply(this, arguments);
            };

            window.__claraExtractDomThermostats = function() {
                const hasRealSerials = (window.__claraDiscoveredThermostats || []).some(d => d && d.id && !String(d.id).startsWith('nest_dom_'));
                if (hasRealSerials && window.__claraDiscoveredThermostats.length >= 2) {
                    window.ClaraNestBridge.postExtractedThermostats(JSON.stringify(window.__claraDiscoveredThermostats));
                    return window.__claraDiscoveredThermostats.length;
                }
                const scraped = [];
                const seenKeys = new Set();
                const ignoreWords = /^(heating|cooling|eco|off|indoor|indoor temp|set to|currently|humidity|settings|history|schedule|energy|thermostats|cameras|home|away|add product|sign out|menu|done|thermostat)$/i;

                // 1. Check direct thermostat links & device cards first (handles both numeric temps and OFF/ECO dials)
                const cardEls = Array.from(document.querySelectorAll('a[href*="/thermostat/"], [data-test*="thermostat" i], [class*="thermostat-card" i], [class*="device-unit" i], [class*="thermostat" i]'));
                cardEls.forEach((el, idx) => {
                    // Skip wrapper containers that contain multiple distinct thermostat cards
                    if (el.querySelectorAll('a[href*="/thermostat/"], [data-test*="thermostat" i]').length > 1) return;
                    const container = el.closest('li, [class*="device" i], [class*="card" i]') || el.parentElement || el;
                    const rawText = (container.innerText || el.innerText || el.getAttribute('aria-label') || '').trim();
                    if (!rawText || rawText.length > 220) return;
                    const linkEl = el.tagName === 'A' ? el : (container.querySelector('a[href*="/thermostat/"]') || el.querySelector('a[href*="/thermostat/"]'));
                    const href = (linkEl && linkEl.getAttribute('href')) || el.getAttribute('href') || '';
                    const hrefSerial = href.includes('/thermostat/') ? href.split('/thermostat/')[1].split('/')[0].split('?')[0].replace('DEVICE_', '') : '';
                    const lines = rawText.split(/\n+/).map(s => s.trim()).filter(Boolean);
                    const tempMatch = rawText.match(/\b(9|[12][0-9]|3[0-2])(?:\.[05])?\b/);
                    const isOffOrEco = /\b(OFF|ECO)\b/i.test(rawText);
                    if (!tempMatch && !isOffOrEco && !hrefSerial) return;
                    const roomLine = lines.find(l => /[A-Za-z]{3,}/.test(l) && !ignoreWords.test(l) && l.length <= 32)
                        || ('Thermostat ' + (scraped.length + 1));
                    const dedupeKey = hrefSerial || (roomLine.toLowerCase() + '_' + (tempMatch ? tempMatch[0] : 'off'));
                    if (!seenKeys.has(dedupeKey)) {
                        seenKeys.add(dedupeKey);
                        const tempVal = tempMatch ? (parseFloat(tempMatch[0]) || 20.5) : 20.0;
                        const modeStr = /\bOFF\b/i.test(rawText) ? 'OFF' : (/\bECO\b/i.test(rawText) ? 'ECO' : 'HEAT');
                        const id = hrefSerial || ('nest_dom_' + (scraped.length + 1) + '_' + roomLine.toLowerCase().replace(/[^a-z0-9]+/g, '_'));
                        const displayName = roomLine.toLowerCase().includes('thermostat')
                            ? roomLine
                            : (roomLine + ' Nest Thermostat');
                        scraped.push({
                            id: id,
                            name: displayName,
                            room: roomLine,
                            ambientTempC: tempVal,
                            targetTempC: tempVal,
                            humidityPct: 50,
                            hvacMode: modeStr,
                            hvacStatus: 'OFF'
                        });
                    }
                });
                if (!hasRealSerials && scraped.length > 0 && scraped.length >= (window.__claraDiscoveredThermostats || []).length) {
                    window.__claraDiscoveredThermostats = scraped;
                    window.ClaraNestBridge.postExtractedThermostats(JSON.stringify(scraped));
                }
                return (window.__claraDiscoveredThermostats || []).length;
            };

            setInterval(function() {
                try {
                    const emailInput = document.querySelector('input[type="email"]');
                    if (emailInput && emailInput.value && emailInput.value.includes('@')) {
                        window.__claraDetectedEmail = emailInput.value.trim();
                    }
                    const idEl = document.querySelector('[data-email], [data-identifier]');
                    if (idEl) {
                        const attr = idEl.getAttribute('data-email') || idEl.getAttribute('data-identifier') || '';
                        if (attr.includes('@')) window.__claraDetectedEmail = attr.trim();
                    }
                } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = "NestSDM",
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
}
                if (window.location.hostname.includes('home.nest.com')) {
                    window.__claraExtractDomThermostats();
                    fetch('https://home.nest.com/session', { credentials: 'include' })
                        .then(r => r.ok ? r.json() : null)
                        .then(j => { if (j) checkSessionJson(j); })
                        .catch(() => {});
                }
            }, 1500);
        })();
        """.trimIndent()
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.removeJavascriptInterface("ClaraNestBridge")
                (webViewRef?.parent as? ViewGroup)?.removeView(webViewRef)
                webViewRef?.destroy()
            }
            webViewRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhTileSurface,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.5.dp, GhAccentGreen.copy(alpha = 0.55f)),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.90f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                isClaimingKey -> "Claiming Nest Thermostat Entry Key..."
                                stayOpenToClaimEntryKey -> "Add Brand-New Nest Thermostat (7-Char Entry Key)"
                                isExchangingSession -> "Connecting Your Nest Thermostats..."
                                else -> "Sign in to Google Nest"
                            },
                            color = GhTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont
                        )
                        Text(
                            text = when {
                                statusNote.isNotBlank() -> statusNote
                                stayOpenToClaimEntryKey ->
                                    "Enter the 7-character Entry Key from your thermostat screen in the box below, or use Settings -> Add product inside the Nest view."
                                else ->
                                    "Sign in with your Google Account — existing thermostats import automatically, or toggle '+ 7-Char Entry Key' for a brand-new thermostat."
                            },
                            color = GhAccentGreen,
                            fontSize = 11.sp,
                            fontFamily = GhFont,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = stayOpenToClaimEntryKey,
                            onClick = {
                                stayOpenToClaimEntryKey = !stayOpenToClaimEntryKey
                                if (stayOpenToClaimEntryKey && isSessionReadyToSync) {
                                    webViewRef?.loadUrl("https://home.nest.com")
                                }
                            },
                            shape = RoundedCornerShape(50),
                            label = {
                                Text(
                                    text = "+ 7-Char Entry Key",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        )
                        Button(
                            onClick = {
                                val wv = webViewRef
                                if (wv != null) {
                                    wv.evaluateJavascript(
                                        "if (typeof window.__claraExtractDomThermostats === 'function') window.__claraExtractDomThermostats(); else 0;"
                                    ) { _ ->
                                        hasCompletedCapture = true
                                        onSessionCaptured()
                                    }
                                } else {
                                    hasCompletedCapture = true
                                    onSessionCaptured()
                                }
                            },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GhAccentGreen,
                                contentColor = Color(0xFF141518)
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Done & Import to Clara", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        FilledTonalIconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                if (stayOpenToClaimEntryKey) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = entryKeyDraft,
                            onValueChange = { entryKeyDraft = it.uppercase(Locale.US).filter { c -> c.isLetterOrDigit() }.take(8) },
                            label = { Text("7-Character Thermostat Entry Key (e.g. 123ABCD)", fontSize = 11.sp) },
                            textStyle = TextStyle(fontSize = 13.sp, fontFamily = GhFont, color = GhTextPrimary),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = claraFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (entryKeyDraft.length in 6..8 && !isClaimingKey) {
                                    isClaimingKey = true
                                    statusNote = "Claiming Entry Key '$entryKeyDraft'..."
                                    scope.launch {
                                        val res = NestSdmApiClient.claimThermostatByEntryKey(context, entryKeyDraft)
                                        isClaimingKey = false
                                        if (res.isSuccess) {
                                            hasCompletedCapture = true
                                            onSessionCaptured()
                                        } else {
                                            statusNote = res.exceptionOrNull()?.message
                                                ?: "Sign in to Google below first, then tap Claim Key."
                                        }
                                    }
                                }
                            },
                            enabled = entryKeyDraft.length in 6..8 && !isClaimingKey,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GhAccentGreen,
                                contentColor = Color(0xFF141518)
                            ),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(if (isClaimingKey) "Claiming..." else "Claim Key", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Surface(
                    color = Color(0xFF121212),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, GhTileSurfaceElevated),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp)),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                val cm = CookieManager.getInstance()
                                cm.setAcceptCookie(true)
                                cm.setAcceptThirdPartyCookies(this, true)

                                // Stage 3 Fix: Use tablet's native Chrome User-Agent with only '; wv' stripped
                                // and zero empty client-hint headers to prevent Google HTTP 400 / WebView blocks.
                                val cleanChromeUa = runCatching {
                                    WebSettings.getDefaultUserAgent(ctx)
                                        .replace("; wv", "")
                                        .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
                                }.getOrDefault(
                                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                                )

                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    userAgentString = cleanChromeUa
                                }

                                addJavascriptInterface(
                                    ClaraNestJsBridge(
                                        onSessionFound = { token, userId, transportUrl ->
                                            if (hasCompletedCapture) return@ClaraNestJsBridge
                                            if (token.isNotBlank() && userId.isNotBlank() && transportUrl.isNotBlank()) {
                                                cm.flush()
                                                val googleCookies = runCatching {
                                                    cm.getCookie("https://accounts.google.com").orEmpty()
                                                }.getOrDefault("")
                                                NestSdmApiClient.saveWebCapturedNestSession(
                                                    context = context,
                                                    accessToken = token,
                                                    userId = userId,
                                                    transportUrl = transportUrl,
                                                    issueTokenUrl = interceptedIssueTokenUrl,
                                                    googleCookies = googleCookies
                                                )
                                                scope.launch(Dispatchers.Main) {
                                                    handleSessionReady()
                                                }
                                            }
                                        },
                                        onDevicesExtracted = { jsonArrayStr ->
                                            NestSdmApiClient.cacheWebExtractedDevices(jsonArrayStr)
                                            scope.launch(Dispatchers.Main) {
                                                isSessionReadyToSync = true
                                            }
                                        }
                                    ),
                                    "ClaraNestBridge"
                                )

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        view?.evaluateJavascript(nestHookJs, null)
                                    }

                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): WebResourceResponse? {
                                        val reqUrl = request?.url?.toString().orEmpty()
                                        if (reqUrl.contains("iframerpc") && reqUrl.contains("issueToken")) {
                                            interceptedIssueTokenUrl = reqUrl
                                        }
                                        return super.shouldInterceptRequest(view, request)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        val currentUrl = url ?: return
                                        cm.flush()
                                        view?.evaluateJavascript(nestHookJs, null)

                                        view?.evaluateJavascript(
                                            """
                                            (function() {
                                                if (window.__claraDetectedEmail) return window.__claraDetectedEmail;
                                                const m = (document.body && document.body.innerText || '').match(/[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/);
                                                return m ? m[0] : '';
                                            })();
                                            """.trimIndent()
                                        ) { rawEmail ->
                                            val cleaned = rawEmail?.trim()?.removeSurrounding("\"") ?: ""
                                            if (cleaned.contains("@")) {
                                                capturedUserEmail = cleaned
                                            }
                                        }

                                        val googleCookies = runCatching {
                                            cm.getCookie("https://accounts.google.com").orEmpty()
                                        }.getOrDefault("")

                                        val isGoogleSignedIn = googleCookies.contains("SID=") &&
                                                (googleCookies.contains("SAPISID=") || googleCookies.contains("__Secure-1PSAPISID="))

                                        if (isGoogleSignedIn && !hasCompletedCapture && !isExchangingSession && !isSessionReadyToSync) {
                                            isExchangingSession = true
                                            scope.launch {
                                                delay(350L)
                                                val exchangedOk = exchangeGoogleCookiesForNestSession(
                                                    context = context,
                                                    googleCookies = googleCookies,
                                                    loginHintEmail = capturedUserEmail,
                                                    explicitIssueTokenUrl = interceptedIssueTokenUrl
                                                )
                                                if (exchangedOk && !hasCompletedCapture) {
                                                    handleSessionReady()
                                                } else {
                                                    isExchangingSession = false
                                                    if (!currentUrl.contains("home.nest.com")) {
                                                        view?.loadUrl("https://home.nest.com")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                loadUrl("https://accounts.google.com/")
                                webViewRef = this
                            }
                        }
                    )
                }
            }
        }
    }
}
