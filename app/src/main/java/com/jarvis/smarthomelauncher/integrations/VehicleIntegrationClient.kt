package com.jarvis.smarthomelauncher.integrations

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// JAGUAR INCONTROL & MERCEDES ME CLOUD + LOCAL HA/MQTT VEHICLE CLIENT
// - Provides live authentication, real VIN discovery, live telemetry polling
//   (Lock state, EV Battery / Fuel %, Range, Charging, Pre-Conditioning Climate,
//   Odometer & Location), and remote commands (Lock/Unlock with PIN, Climate,
//   Charge, Honk & Flash) with zero dummy fallback VINs or fake telemetry.
// ---------------------------------------------------------------------------
object VehicleIntegrationClient {
    private const val TAG = "ClaraVehicleClient"

    // Jaguar Land Rover InControl IFAS / IFOP / IF9 regional endpoints (ROW + NA)
    private val JLR_REGIONS = listOf(
        Triple(
            "https://ifas.prod-row.jlrmotor.com/ifas/jlr/tokens",
            "https://ifop.prod-row.jlrmotor.com/ifop/jlr/users",
            "https://if9.prod-row.jlrmotor.com/if9/jlr/vehicles"
        ),
        Triple(
            "https://ifas.prod-na.jlrmotor.com/ifas/jlr/tokens",
            "https://ifop.prod-na.jlrmotor.com/ifop/jlr/users",
            "https://if9.prod-na.jlrmotor.com/if9/jlr/vehicles"
        )
    )

    // Mercedes-Benz / Mercedes me BFF & Mobile API endpoints
    private val MB_BFF_HOSTS = listOf(
        "https://bff.emea-prod.mobilesdk.mercedes-benz.com",
        "https://bff.amap-prod.mobilesdk.mercedes-benz.com",
        "https://bff.na-prod.mobilesdk.mercedes-benz.com"
    )
    private const val MB_OIDC_TOKEN_URL = "https://id.mercedes-benz.com/as/token.oauth2"

    private const val PREFS_VEHICLE_SESSIONS = "clara_vehicle_oauth_sessions_v1"

    @Volatile
    private var jlrAccessToken: String = ""
    @Volatile
    private var jlrRefreshToken: String = ""
    @Volatile
    private var jlrUserId: String = ""
    @Volatile
    private var jlrActiveIfopUrl: String = JLR_REGIONS.first().second
    @Volatile
    private var jlrActiveIf9Url: String = JLR_REGIONS.first().third
    @Volatile
    private var jlrDeviceId: String = UUID.randomUUID().toString()
    @Volatile
    private var jlrTokenExpiryMs: Long = 0L

    @Volatile
    private var mbAccessToken: String = ""
    @Volatile
    private var mbRefreshToken: String = ""
    @Volatile
    private var mbActiveBffHost: String = MB_BFF_HOSTS.first()
    @Volatile
    private var mbTokenExpiryMs: Long = 0L

    private val cachedVehicleTelemetryByVin = ConcurrentHashMap<String, SmartTile>()

    private fun saveEncryptedJlrSession() {
        val appCtx = ClaraApplication.appContextOrNull() ?: return
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(appCtx, "${PREFS_VEHICLE_SESSIONS}::jlr_access_token", jlrAccessToken)
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(appCtx, "${PREFS_VEHICLE_SESSIONS}::jlr_refresh_token", jlrRefreshToken)
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(appCtx, "${PREFS_VEHICLE_SESSIONS}::jlr_user_id", jlrUserId)
        appCtx.getSharedPreferences(PREFS_VEHICLE_SESSIONS, Context.MODE_PRIVATE)
            .edit()
            .remove("jlr_access_token")
            .remove("jlr_refresh_token")
            .remove("jlr_user_id")
            .putString("jlr_ifop_url", jlrActiveIfopUrl)
            .putString("jlr_if9_url", jlrActiveIf9Url)
            .putString("jlr_device_id", jlrDeviceId)
            .putLong("jlr_expiry_ms", jlrTokenExpiryMs)
            .apply()
    }

    private fun restoreEncryptedJlrSessionIfNeeded() {
        if (jlrAccessToken.isNotBlank()) return
        val appCtx = ClaraApplication.appContextOrNull() ?: return
        val prefs = appCtx.getSharedPreferences(PREFS_VEHICLE_SESSIONS, Context.MODE_PRIVATE)
        val rawAt = com.jarvis.smarthomelauncher.core.ClaraSecretVault
            .migrateStringKey(appCtx, PREFS_VEHICLE_SESSIONS, "jlr_access_token")
            .orEmpty()
        val rawRt = com.jarvis.smarthomelauncher.core.ClaraSecretVault
            .migrateStringKey(appCtx, PREFS_VEHICLE_SESSIONS, "jlr_refresh_token")
            .orEmpty()
        val rawUid = com.jarvis.smarthomelauncher.core.ClaraSecretVault
            .migrateStringKey(appCtx, PREFS_VEHICLE_SESSIONS, "jlr_user_id")
            .orEmpty()
        jlrAccessToken = rawAt.trim()
        jlrRefreshToken = rawRt.trim()
        jlrUserId = rawUid.trim()
        jlrActiveIfopUrl = prefs.getString("jlr_ifop_url", JLR_REGIONS.first().second).orEmpty().ifBlank { JLR_REGIONS.first().second }
        jlrActiveIf9Url = prefs.getString("jlr_if9_url", JLR_REGIONS.first().third).orEmpty().ifBlank { JLR_REGIONS.first().third }
        jlrDeviceId = prefs.getString("jlr_device_id", jlrDeviceId).orEmpty().ifBlank { jlrDeviceId }
        jlrTokenExpiryMs = prefs.getLong("jlr_expiry_ms", 0L)
    }

    private fun saveEncryptedMbSession() {
        val appCtx = ClaraApplication.appContextOrNull() ?: return
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(appCtx, "${PREFS_VEHICLE_SESSIONS}::mb_access_token", mbAccessToken)
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(appCtx, "${PREFS_VEHICLE_SESSIONS}::mb_refresh_token", mbRefreshToken)
        appCtx.getSharedPreferences(PREFS_VEHICLE_SESSIONS, Context.MODE_PRIVATE)
            .edit()
            .remove("mb_access_token")
            .remove("mb_refresh_token")
            .putString("mb_bff_host", mbActiveBffHost)
            .putLong("mb_expiry_ms", mbTokenExpiryMs)
            .apply()
    }

    private fun restoreEncryptedMbSessionIfNeeded() {
        if (mbAccessToken.isNotBlank()) return
        val appCtx = ClaraApplication.appContextOrNull() ?: return
        val prefs = appCtx.getSharedPreferences(PREFS_VEHICLE_SESSIONS, Context.MODE_PRIVATE)
        val rawAt = com.jarvis.smarthomelauncher.core.ClaraSecretVault
            .migrateStringKey(appCtx, PREFS_VEHICLE_SESSIONS, "mb_access_token")
            .orEmpty()
        val rawRt = com.jarvis.smarthomelauncher.core.ClaraSecretVault
            .migrateStringKey(appCtx, PREFS_VEHICLE_SESSIONS, "mb_refresh_token")
            .orEmpty()
        mbAccessToken = rawAt.trim()
        mbRefreshToken = rawRt.trim()
        mbActiveBffHost = prefs.getString("mb_bff_host", MB_BFF_HOSTS.first()).orEmpty().ifBlank { MB_BFF_HOSTS.first() }
        mbTokenExpiryMs = prefs.getLong("mb_expiry_ms", 0L)
    }

    private fun ensureValidJaguarToken(bridge: BridgeSettings): Boolean {
        restoreEncryptedJlrSessionIfNeeded()
        if (jlrAccessToken.isNotBlank() && System.currentTimeMillis() < jlrTokenExpiryMs) {
            return true
        }
        // 1. Try refreshing with jlrRefreshToken first
        if (jlrRefreshToken.isNotBlank()) {
            for ((ifasUrl, _, _) in JLR_REGIONS) {
                try {
                    val payload = JSONObject().apply {
                        put("grant_type", "refresh_token")
                        put("refresh_token", jlrRefreshToken)
                    }.toString()
                    val conn = (URL(ifasUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 5000
                        readTimeout = 5000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Authorization", "Basic YXM6YXNwYXNz")
                        setRequestProperty("X-Device-Id", jlrDeviceId)
                        setRequestProperty("Accept", "application/json")
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write(payload); it.flush() }
                    if (conn.responseCode in 200..299) {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        conn.disconnect()
                        val newToken = json.optString("access_token", "").trim()
                        val newRefresh = json.optString("refresh_token", jlrRefreshToken).trim()
                        val expiresIn = json.optLong("expires_in", 3600L)
                        if (newToken.isNotBlank()) {
                            jlrAccessToken = newToken
                            jlrRefreshToken = newRefresh
                            jlrTokenExpiryMs = System.currentTimeMillis() + (expiresIn - 120L).coerceAtLeast(300L) * 1000L
                            saveEncryptedJlrSession()
                            return true
                        }
                    } else {
                        conn.disconnect()
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
        // 2. Fallback to full password authentication
        if (bridge.jaguarEmail.isNotBlank() && bridge.jaguarPassword.isNotBlank()) {
            return authenticateJaguarInControlSession(bridge.jaguarEmail, bridge.jaguarPassword) != null
        }
        return false
    }

    private fun ensureValidMercedesToken(bridge: BridgeSettings): Boolean {
        restoreEncryptedMbSessionIfNeeded()
        if (mbAccessToken.isNotBlank() && System.currentTimeMillis() < mbTokenExpiryMs) {
            return true
        }
        if (mbRefreshToken.isNotBlank()) {
            try {
                val formBody = listOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to mbRefreshToken,
                    "client_id" to "01398c1c-dc45-4b42-882b-9f5ba9f175f1"
                ).joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                }
                val conn = (URL(MB_OIDC_TOKEN_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 5000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("User-Agent", "MercedesMe/1.42.0 (Android 14)")
                    setRequestProperty("Accept", "application/json")
                }
                OutputStreamWriter(conn.outputStream).use { it.write(formBody); it.flush() }
                if (conn.responseCode in 200..299) {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    conn.disconnect()
                    val newToken = json.optString("access_token", "").trim()
                    val newRefresh = json.optString("refresh_token", mbRefreshToken).trim()
                    val expiresIn = json.optLong("expires_in", 3600L)
                    if (newToken.isNotBlank()) {
                        mbAccessToken = newToken
                        mbRefreshToken = newRefresh
                        mbTokenExpiryMs = System.currentTimeMillis() + (expiresIn - 120L).coerceAtLeast(300L) * 1000L
                        saveEncryptedMbSession()
                        return true
                    }
                } else {
                    conn.disconnect()
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
        if (bridge.mercedesPassword.isNotBlank()) {
            return !authenticateMercedesMeSession(bridge.mercedesEmail, bridge.mercedesPassword).isNullOrBlank()
        }
        return false
    }

    /**
     * Dynamic Vehicle Capability Discovery Engine:
     * Inspects live Jaguar InControl (IF9), Mercedes me (BFF), or Home Assistant telemetry
     * attribute keys and synthesizes only the writable controls and read-only sensors
     * genuinely supported by the vehicle (ICE vs PHEV vs BEV).
     */
    fun discoverCapabilitiesFromVehiclePayload(
        rawAttributes: Map<String, String>,
        isLocked: Boolean = true,
        climateActive: Boolean = false,
        isCharging: Boolean = false,
        isEv: Boolean = true,
        targetTempC: Float = 21.0f,
        socPct: Int = 0,
        rangeMiles: Int = 0,
        odometerMiles: Int = 0,
        battery12vVolts: Float = 0f,
        tpmsFlPsi: Float = 0f,
        tpmsFrPsi: Float = 0f,
        tpmsRlPsi: Float = 0f,
        tpmsRrPsi: Float = 0f
    ): List<DeviceCapabilityDescriptor> {
        val keysUpper = rawAttributes.keys.map { it.trim().uppercase(Locale.US) }.toSet()
        val hasPayload = keysUpper.isNotEmpty()
        val caps = mutableListOf<DeviceCapabilityDescriptor>()

        val hasLockAttr = !hasPayload || keysUpper.any {
            it.contains("LOCK") || it.contains("DOOR")
        }
        if (hasLockAttr) {
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "vehicle_lock",
                    label = "Doors Lock",
                    kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = if (isLocked) "LOCKED" else "UNLOCKED",
                    rendererType = CapabilityRendererType.TOGGLE
                )
            )
        }

        val hasClimateAttr = !hasPayload || keysUpper.any {
            it.contains("CLIMATE") || it.contains("PRECOND") || it.contains("AUXHEAT") || it.contains("HVAC")
        }
        if (hasClimateAttr) {
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "vehicle_climate",
                    label = "Pre-Condition Climate",
                    kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = if (climateActive) "ON" else "OFF",
                    rendererType = CapabilityRendererType.TOGGLE
                )
            )
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "vehicle_climate_temp",
                    label = "Cabin Setpoint",
                    kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                    isWritable = true,
                    isPinnedToHero = false,
                    currentValue = "%.1f".format(Locale.US, targetTempC),
                    minValue = 16f,
                    maxValue = 28f,
                    stepValue = 0.5f,
                    unit = "\u00B0C",
                    rendererType = CapabilityRendererType.TEMPERATURE_DIAL
                )
            )
        }

        val hasEvChargeAttr = isEv || keysUpper.any {
            it.contains("EV_CHARG") || it.contains("CHARGING") || it.contains("EV_STATE_OF_CHARGE") || it == "SOC"
        }
        if (hasEvChargeAttr) {
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "vehicle_charging",
                    label = "EV Charge Session",
                    kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = if (isCharging) "CHARGING" else "PAUSED",
                    rendererType = CapabilityRendererType.TOGGLE
                )
            )
        }

        caps.add(
            DeviceCapabilityDescriptor(
                key = "vehicle_honk_flash",
                label = "Beep & Flash Lights",
                kind = DeviceCapabilityKind.ACTION_TRIGGER,
                isWritable = true,
                isPinnedToHero = true,
                currentValue = "READY",
                rendererType = CapabilityRendererType.TOGGLE
            )
        )

        if (socPct > 0 || keysUpper.any { it.contains("SOC") || it.contains("FUEL") || it.contains("TANK") || it.contains("CHARGE") }) {
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "vehicle_soc",
                    label = if (isEv) "EV Battery" else "Fuel Level",
                    kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                    isWritable = false,
                    isPinnedToHero = false,
                    currentValue = "$socPct",
                    unit = "%",
                    rendererType = CapabilityRendererType.SENSOR_BADGE
                )
            )
        }

        if (rangeMiles > 0 || keysUpper.any { it.contains("RANGE") || it.contains("DISTANCE_TO_EMPTY") }) {
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "vehicle_range",
                    label = "Estimated Range",
                    kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                    isWritable = false,
                    isPinnedToHero = false,
                    currentValue = "$rangeMiles",
                    unit = "mi",
                    rendererType = CapabilityRendererType.SENSOR_BADGE
                )
            )
        }

        if (odometerMiles > 0 || keysUpper.any { it.contains("ODOMETER") || it == "ODO" || it.contains("MILEAGE") }) {
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "vehicle_odometer",
                    label = "Odometer",
                    kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                    isWritable = false,
                    isPinnedToHero = false,
                    currentValue = "$odometerMiles",
                    unit = "mi",
                    rendererType = CapabilityRendererType.SENSOR_BADGE
                )
            )
        }

        if (battery12vVolts > 0f && keysUpper.any { it.contains("BATTERY_VOLTAGE") || it.contains("STARTERBATTERY") }) {
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "vehicle_12v_battery",
                    label = "12V Battery",
                    kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                    isWritable = false,
                    isPinnedToHero = false,
                    currentValue = "%.1f".format(Locale.US, battery12vVolts),
                    unit = "V",
                    rendererType = CapabilityRendererType.SENSOR_BADGE
                )
            )
        }

        if (keysUpper.any { it.contains("TYRE_PRESSURE") || it.contains("TIRE_PRESSURE") || it.contains("TIREPRESSURE") }) {
            val avgPsi = listOf(tpmsFlPsi, tpmsFrPsi, tpmsRlPsi, tpmsRrPsi).filter { it > 0f }.average().toFloat()
            if (avgPsi > 0f) {
                caps.add(
                    DeviceCapabilityDescriptor(
                        key = "vehicle_tpms",
                        label = "Tyre Pressure",
                        kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                        isWritable = false,
                        isPinnedToHero = false,
                        currentValue = "%.1f".format(Locale.US, avgPsi),
                        unit = "PSI",
                        rendererType = CapabilityRendererType.SENSOR_BADGE
                    )
                )
            }
        }

        return caps
    }

    fun defaultCapabilitiesForVehicle(
        @Suppress("UNUSED_PARAMETER") type: TileType = TileType.JAGUAR_VEHICLE,
        isLocked: Boolean = true,
        climateActive: Boolean = false,
        isCharging: Boolean = false,
        targetTempC: Float = 21.0f,
        socPct: Int = 0,
        rangeMiles: Int = 0
    ): List<DeviceCapabilityDescriptor> = discoverCapabilitiesFromVehiclePayload(
        rawAttributes = emptyMap(),
        isLocked = isLocked,
        climateActive = climateActive,
        isCharging = isCharging,
        isEv = true,
        targetTempC = targetTempC,
        socPct = socPct,
        rangeMiles = rangeMiles
    )

    // -----------------------------------------------------------------------
    // 1. JAGUAR INCONTROL LIVE AUTHENTICATION & VEHICLE TELEMETRY
    // -----------------------------------------------------------------------
    private fun authenticateJaguarInControlSession(
        email: String,
        password: String
    ): Pair<String, String>? {
        val cleanEmail = email.trim()
        val cleanPass = password.trim()
        if (cleanEmail.isBlank() || cleanPass.isBlank()) return null

        // Allow direct Bearer token if pasted into password field
        if (cleanPass.length > 30 && !cleanPass.contains(" ")) {
            jlrAccessToken = cleanPass.removePrefix("Bearer ").trim()
            jlrTokenExpiryMs = System.currentTimeMillis() + 3300_000L
            saveEncryptedJlrSession()
            return jlrAccessToken to jlrActiveIf9Url
        }

        for ((ifasUrl, ifopUrl, if9Url) in JLR_REGIONS) {
            try {
                val authPayload = JSONObject().apply {
                    put("grant_type", "password")
                    put("username", cleanEmail)
                    put("password", cleanPass)
                }.toString()

                val conn = (URL(ifasUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 7000
                    readTimeout = 7000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Basic YXM6YXNwYXNz")
                    setRequestProperty("X-Device-Id", jlrDeviceId)
                    setRequestProperty("Connection", "close")
                    setRequestProperty("User-Agent", "okhttp/4.12.0")
                    setRequestProperty("Accept", "application/json")
                }
                OutputStreamWriter(conn.outputStream).use { it.write(authPayload); it.flush() }
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    continue
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                val token = json.optString("access_token", "").trim()
                val authToken = json.optString("authorization_token", token).trim()
                val refresh = json.optString("refresh_token", "").trim()
                val expiresIn = json.optLong("expires_in", 3600L)
                if (token.isBlank()) continue

                jlrAccessToken = token
                jlrRefreshToken = refresh
                jlrActiveIfopUrl = ifopUrl
                jlrActiveIf9Url = if9Url
                jlrTokenExpiryMs = System.currentTimeMillis() + (expiresIn - 120L).coerceAtLeast(300L) * 1000L

                // Step 2: Register client device ID with IFOP (try unencoded email path first per WirelessCar NGTP spec, then encoded)
                val regPayload = JSONObject().apply {
                    put("access_token", token)
                    put("authorization_token", authToken)
                    put("expires_in", expiresIn.toString())
                    put("deviceID", jlrDeviceId)
                }.toString()
                for (userPath in listOf(cleanEmail, URLEncoder.encode(cleanEmail, "UTF-8")).distinct()) {
                    for (contentType in listOf(
                        "application/vnd.wirelesscar.ngtp.if9.StartServiceConfiguration-v3+json",
                        "application/vnd.wirelesscar.ngtp.if9.StartServiceConfiguration-v2+json",
                        "application/json"
                    )) {
                        val registered = runCatching {
                            val regConn = (URL("$ifopUrl/$userPath/clients").openConnection() as HttpURLConnection).apply {
                                requestMethod = "POST"
                                connectTimeout = 5000
                                readTimeout = 5000
                                doOutput = true
                                setRequestProperty("Authorization", "Bearer $token")
                                setRequestProperty("X-Device-Id", jlrDeviceId)
                                setRequestProperty("Connection", "close")
                                setRequestProperty("Content-Type", contentType)
                                setRequestProperty("Accept", "application/json")
                            }
                            OutputStreamWriter(regConn.outputStream).use { it.write(regPayload); it.flush() }
                            val code = regConn.responseCode
                            regConn.disconnect()
                            code in 200..299
                        }.getOrDefault(false)
                        if (registered) break
                    }
                }
                saveEncryptedJlrSession()
                return token to if9Url
            } catch (e: Exception) {
                Log.w(TAG, "Jaguar InControl auth attempt ($ifasUrl) note: ${e.message}")
            }
        }
        return null
    }

    suspend fun loginAndDiscoverJaguarVehicles(
        context: Context,
        bridge: BridgeSettings,
        email: String = bridge.jaguarEmail,
        password: String = bridge.jaguarPassword,
        pin: String = bridge.jaguarPin,
        vinOverride: String = bridge.jaguarVin
    ): Result<List<SmartTile>> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val cleanEmail = email.trim()
        val cleanPass = password.trim()
        val cleanPin = pin.trim()
        val cleanVin = DeviceRepository.sanitizeRealVin(vinOverride)

        val updatedBridge = bridge.copy(
            jaguarEmail = cleanEmail,
            jaguarPassword = cleanPass,
            jaguarPin = cleanPin,
            jaguarVin = cleanVin
        )
        withContext(Dispatchers.Main) {
            DeviceRepository.saveBridge(context, updatedBridge)
        }

        val discoveredTiles = mutableListOf<SmartTile>()

        // 1A. Direct Jaguar Land Rover InControl API authentication & vehicle discovery
        if (cleanEmail.isNotBlank() && cleanPass.isNotBlank()) {
            val authSession = authenticateJaguarInControlSession(cleanEmail, cleanPass)
            if (authSession != null) {
                val (token, _) = authSession
                val vehicles = fetchJlrVehiclesWithToken(token, cleanEmail, cleanVin, cleanPin)
                discoveredTiles.addAll(vehicles)
            }
        }

        // 1B. Check Home Assistant for Jaguar (`jlrpy` / `jaguar`) entities if HA is configured
        if (discoveredTiles.isEmpty() && bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()) {
            val haVehicles = discoverVehiclesFromHomeAssistant(
                bridge = updatedBridge,
                targetType = TileType.JAGUAR_VEHICLE
            )
            discoveredTiles.addAll(haVehicles)
        }

        val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(15L)
        if (discoveredTiles.isNotEmpty()) {
            val firstRealVin = discoveredTiles.first().vehicleVin
            if (firstRealVin.isNotBlank() && firstRealVin != cleanVin) {
                withContext(Dispatchers.Main) {
                    DeviceRepository.saveBridge(context, updatedBridge.copy(jaguarVin = firstRealVin))
                }
            }
            JarvisLiveBridge.recordCommandLatency(
                integrationKey = "VEHICLES",
                displayName = "Jaguar InControl & Mercedes me",
                latencyMs = latency,
                succeeded = true,
                tokenValid = true,
                onlineDevices = discoveredTiles.size,
                statusMessage = "Jaguar InControl Synced (${discoveredTiles.first().title})"
            )
            return@withContext Result.success(discoveredTiles)
        }

        DiscoveryFlightRecorder.record(
            eventType = DiscoveryEventType.FAILED,
            transportSource = "JAGUAR_INCONTROL_CLOUD",
            vendor = "Jaguar InControl",
            qualificationStage = "loginAndDiscoverJaguarVehicles",
            outcomeReason = "Jaguar InControl authentication or VIN discovery returned 0 vehicles"
        )
        JarvisLiveBridge.recordCommandLatency(
            integrationKey = "VEHICLES",
            displayName = "Jaguar InControl & Mercedes me",
            latencyMs = latency,
            succeeded = false,
            tokenValid = false,
            statusMessage = "Jaguar InControl sign-in required"
        )
        Result.failure(
            Exception(
                "Could not authenticate with Jaguar InControl or find a linked Jaguar vehicle. Please check your Jaguar InControl email, password, or Home Assistant integration."
            )
        )
    }

    private fun fetchJlrVehiclesWithToken(
        accessToken: String,
        email: String,
        vinFallback: String,
        pin: String
    ): List<SmartTile> {
        val result = mutableListOf<SmartTile>()
        val candidateVins = LinkedHashSet<String>()
        if (vinFallback.length >= 11) {
            candidateVins.add(vinFallback)
        }
        val cleanEmail = email.trim()
        for (queryParam in listOf(cleanEmail, URLEncoder.encode(cleanEmail, "UTF-8")).distinct()) {
            try {
                val url = URL("$jlrActiveIfopUrl?loginName=$queryParam")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("X-Device-Id", jlrDeviceId)
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/vnd.wirelesscar.ngtp.if9.User-v3+json")
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val root = JSONObject(body)
                    jlrUserId = root.optString("userId", jlrUserId).trim()
                    val vehiclesArr = root.optJSONArray("vehicles") ?: JSONArray()
                    for (i in 0 until vehiclesArr.length()) {
                        val vObj = vehiclesArr.optJSONObject(i) ?: continue
                        val rawVin = vObj.optString("vin", "").trim()
                        val vin = DeviceRepository.sanitizeRealVin(rawVin)
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                            transportSource = "JAGUAR_INCONTROL_CLOUD",
                            hardwareId = vin.ifBlank { rawVin.take(8) },
                            vendor = "Jaguar Land Rover",
                            confidenceScore = 0.85f,
                            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                            outcomeReason = "JLR IFOP vehicle entry received",
                            cloudId = vin
                        )
                        if (vin.isNotBlank()) {
                            candidateVins.add(vin)
                        } else {
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.FILTERED,
                                transportSource = "JAGUAR_INCONTROL_CLOUD",
                                vendor = "Jaguar Land Rover",
                                qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                outcomeReason = "Rejected invalid or placeholder JLR VIN"
                            )
                        }
                    }
                    if (jlrUserId.isNotBlank()) {
                        saveEncryptedJlrSession()
                        break
                    }
                } else {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchJlrVehiclesWithToken user lookup note: ${e.message}")
            }
        }

        // Also query IF9 /users/{userId}/vehicles endpoint if IFOP user object omitted the vehicles list
        if (candidateVins.isEmpty() && jlrUserId.isNotBlank()) {
            val if9UsersBase = jlrActiveIf9Url.substringBeforeLast("/vehicles") + "/users/$jlrUserId/vehicles?primaryOnly=true"
            runCatching {
                val conn = (URL(if9UsersBase).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("X-Device-Id", jlrDeviceId)
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val root = JSONObject(body)
                    val vehiclesArr = root.optJSONArray("vehicles") ?: JSONArray()
                    for (i in 0 until vehiclesArr.length()) {
                        val vObj = vehiclesArr.optJSONObject(i) ?: continue
                        val rawVin = vObj.optString("vin", "").trim()
                        val vin = DeviceRepository.sanitizeRealVin(rawVin)
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                            transportSource = "JAGUAR_INCONTROL_CLOUD",
                            hardwareId = vin.ifBlank { rawVin.take(8) },
                            vendor = "Jaguar Land Rover",
                            confidenceScore = 0.85f,
                            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                            outcomeReason = "JLR IF9 vehicle entry received",
                            cloudId = vin
                        )
                        if (vin.isNotBlank()) {
                            candidateVins.add(vin)
                        } else {
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.FILTERED,
                                transportSource = "JAGUAR_INCONTROL_CLOUD",
                                vendor = "Jaguar Land Rover",
                                qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                outcomeReason = "Rejected invalid or placeholder JLR IF9 VIN"
                            )
                        }
                    }
                } else {
                    conn.disconnect()
                }
            }
        }

        for (vin in candidateVins) {
            val statusTile = queryJlrVehicleStatus(accessToken, vin, pin)
                ?: buildFallbackSleepingJlrTile(accessToken, vin, pin)
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.IDENTIFIED,
                transportSource = "JAGUAR_INCONTROL_CLOUD",
                hardwareId = vin,
                vendor = statusTile.title,
                confidenceScore = 0.96f,
                qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                outcomeReason = "Synced JLR vehicle telemetry (online=${statusTile.isOnline} caps=${statusTile.capabilities.size})",
                cloudId = statusTile.entityId
            )
            cachedVehicleTelemetryByVin[vin] = statusTile
            result.add(statusTile)
        }
        return result
    }

    private fun buildFallbackSleepingJlrTile(
        accessToken: String,
        vin: String,
        pin: String
    ): SmartTile {
        val cleanVin = DeviceRepository.sanitizeRealVin(vin).ifBlank { "JAGUAR_INCONTROL" }
        val cached = cachedVehicleTelemetryByVin[cleanVin]
        val lastKnownSoc = cached?.vehicleFuelOrBatteryPct ?: 0
        val lastKnownRange = cached?.vehicleRangeMiles ?: 0
        val lastKnownLocked = cached?.vehicleLocked ?: true
        val vehicleTitle = cached?.title?.takeIf { it.isNotBlank() }
            ?: queryJlrVehicleNickname(accessToken, cleanVin)
        val caps = defaultCapabilitiesForVehicle(
            type = TileType.JAGUAR_VEHICLE,
            isLocked = lastKnownLocked,
            climateActive = false,
            isCharging = false,
            targetTempC = cached?.vehicleTargetClimateTempC ?: 21.0f,
            socPct = lastKnownSoc,
            rangeMiles = lastKnownRange
        )
        val baseTile = SmartTile(
            title = vehicleTitle,
            roomOrSubtitle = cached?.roomOrSubtitle?.ifBlank { "Driveway" } ?: "Driveway",
            type = TileType.JAGUAR_VEHICLE,
            colSpan = 1,
            rowSpan = 1,
            isVisible = true,
            isOnline = false,
            isActive = lastKnownLocked,
            secretCode = pin,
            serialNumber = cleanVin,
            entityId = "vehicle.jaguar_${cleanVin.takeLast(6).lowercase(Locale.US)}",
            companionAppPackage = "com.jlr.jaguar.incontrolremote.appstore",
            vehicleLocked = lastKnownLocked,
            vehicleFuelOrBatteryPct = lastKnownSoc,
            vehicleRangeMiles = lastKnownRange,
            vehicleIsEv = cached?.vehicleIsEv ?: true,
            vehicleIsCharging = false,
            vehicleClimateActive = false,
            vehicleTargetClimateTempC = cached?.vehicleTargetClimateTempC ?: 21.0f,
            vehicleLocationText = "Vehicle asleep \u2014 awaiting telemetry wake",
            vehicleVin = cleanVin,
            capabilities = caps,
            capabilitySchemaHash = CapabilitySchemaHasher.computeHash(caps),
            deviceClassOverride = DeviceClass.VEHICLE,
            deviceSubtypeOverride = DeviceSubtype.VEHICLE,
            lifecycleState = DeviceLifecycleState.QUARANTINED_ERROR,
            activeProviderId = "JAGUAR_INCONTROL"
        )
        return if (cached != null) {
            baseTile.copy(stateAuthorityTier = cached.stateAuthorityTier)
        } else {
            baseTile
        }
    }

    private fun queryJlrVehicleNickname(accessToken: String, vin: String): String {
        return try {
            val conn = (URL("$jlrActiveIf9Url/$vin/attributes").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("X-Device-Id", jlrDeviceId)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/vnd.wirelesscar.ngtp.if9.VehicleAttributes-v4+json")
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                val nick = json.optString("nickname", "").trim()
                val brand = json.optString("vehicleBrand", "Jaguar").trim()
                val typeDesc = json.optString("vehicleType", "").trim()
                when {
                    nick.isNotBlank() -> nick
                    typeDesc.isNotBlank() -> "$brand $typeDesc".trim()
                    else -> "Jaguar"
                }
            } else {
                conn.disconnect()
                "Jaguar"
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
            "Jaguar"
}
    }

    private fun queryJlrVehicleStatus(
        accessToken: String,
        vin: String,
        pin: String
    ): SmartTile? {
        val cleanVin = DeviceRepository.sanitizeRealVin(vin)
        if (cleanVin.isBlank()) return null
        val acceptHeaders = listOf(
            "application/vnd.wirelesscar.ngtp.if9.VehicleStatus-v4+json",
            "application/vnd.wirelesscar.ngtp.if9.VehicleStatus-v3+json",
            "application/vnd.wirelesscar.ngtp.if9.VehicleStatus-v2+json",
            "application/json"
        )
        for (acceptHeader in acceptHeaders) {
            try {
                val conn = (URL("$jlrActiveIf9Url/$cleanVin/status?includeInactive=true").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5500
                    readTimeout = 5500
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("X-Device-Id", jlrDeviceId)
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", acceptHeader)
                }
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    continue
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val root = JSONObject(body)

                // Combine v3 `coreStatus` + `evStatus` arrays and v1/v2 flat `vehicleStatus` array
                val combinedStatuses = mutableListOf<JSONObject>()
                val vsObj = root.optJSONObject("vehicleStatus")
                if (vsObj != null) {
                    val coreArr = vsObj.optJSONArray("coreStatus")
                    if (coreArr != null) {
                        for (i in 0 until coreArr.length()) {
                            coreArr.optJSONObject(i)?.let { combinedStatuses.add(it) }
                        }
                    }
                    val evArr = vsObj.optJSONArray("evStatus")
                    if (evArr != null) {
                        for (i in 0 until evArr.length()) {
                            evArr.optJSONObject(i)?.let { combinedStatuses.add(it) }
                        }
                    }
                }
                val flatArr = root.optJSONArray("vehicleStatus")
                if (flatArr != null) {
                    for (i in 0 until flatArr.length()) {
                        flatArr.optJSONObject(i)?.let { combinedStatuses.add(it) }
                    }
                }

                var isLocked = true
                var evSocPct: Int? = null
                var fuelPct: Int? = null
                var rangeMiles = 0
                var isEv = false
                var isCharging = false
                var climateActive = false
                var odometerMiles = 0
                var tpmsFlPsi = 0f
                var tpmsFrPsi = 0f
                var tpmsRlPsi = 0f
                var tpmsRrPsi = 0f
                var battery12vVolts = 0f
                var allDoorsWindowsClosed = true
                var nextServiceMiles = 0

                fun parsePressureToPsi(raw: String): Float? {
                    val v = raw.toFloatOrNull() ?: return null
                    if (v <= 0f) return null
                    return when {
                        v > 100f -> (v * 0.145038f) // kPa -> PSI
                        v < 8f -> (v * 14.5038f)    // Bar -> PSI
                        else -> v                   // Already PSI
                    }
                }

                val rawAttributes = LinkedHashMap<String, String>()
                for (kv in combinedStatuses) {
                    val key = kv.optString("key", "").trim().uppercase(Locale.US)
                    val value = kv.optString("value", "").trim()
                    if (key.isNotBlank()) {
                        rawAttributes[key] = value
                    }
                    when (key) {
                        "DOOR_IS_ALL_DOORS_LOCKED" -> {
                            isLocked = value.equals("TRUE", ignoreCase = true) || value == "1"
                        }
                        "EV_STATE_OF_CHARGE" -> {
                            val parsed = value.toFloatOrNull()?.roundToInt()
                            if (parsed != null && parsed in 0..100) {
                                evSocPct = parsed
                                isEv = true
                            }
                        }
                        "FUEL_LEVEL_PERC" -> {
                            val parsed = value.toFloatOrNull()?.roundToInt()
                            if (parsed != null && parsed in 0..100) {
                                fuelPct = parsed
                            }
                        }
                        "EV_RANGE_ON_BATTERY_MILES" -> {
                            val parsed = value.toFloatOrNull()?.roundToInt()
                            if (parsed != null && parsed > 0) {
                                rangeMiles = parsed
                                isEv = true
                            }
                        }
                        "EV_RANGE_ON_BATTERY_KM" -> {
                            if (rangeMiles == 0) {
                                val km = value.toFloatOrNull()
                                if (km != null && km > 0f) {
                                    rangeMiles = (km * 0.621371f).roundToInt()
                                    isEv = true
                                }
                            }
                        }
                        "DISTANCE_TO_EMPTY_FUEL" -> {
                            if (rangeMiles == 0) {
                                val km = value.toFloatOrNull()
                                if (km != null && km > 0f) {
                                    rangeMiles = (km * 0.621371f).roundToInt()
                                }
                            }
                        }
                        "EV_CHARGING_STATUS" -> {
                            isCharging = value.contains("CHARGING", ignoreCase = true) &&
                                    !value.contains("NOT", ignoreCase = true)
                        }
                        "CLIMATE_STATUS_OPERATING_STATUS", "PRECONDITIONING_STATUS" -> {
                            climateActive = value.contains("ON", ignoreCase = true) ||
                                    value.contains("HEATING", ignoreCase = true) ||
                                    value.contains("COOLING", ignoreCase = true) ||
                                    value.contains("ACTIVE", ignoreCase = true)
                        }
                        "ODOMETER_MILES" -> {
                            val parsed = value.toFloatOrNull()?.roundToInt()
                            if (parsed != null && parsed > 0) odometerMiles = parsed
                        }
                        "ODOMETER_METER", "ODOMETER" -> {
                            if (odometerMiles == 0) {
                                val meters = value.toDoubleOrNull()
                                if (meters != null && meters > 0.0) {
                                    odometerMiles = (meters / 1609.344).roundToInt()
                                }
                            }
                        }
                        "TYRE_PRESSURE_FRONT_LEFT", "TIRE_PRESSURE_FRONT_LEFT" -> {
                            parsePressureToPsi(value)?.let { tpmsFlPsi = it }
                        }
                        "TYRE_PRESSURE_FRONT_RIGHT", "TIRE_PRESSURE_FRONT_RIGHT" -> {
                            parsePressureToPsi(value)?.let { tpmsFrPsi = it }
                        }
                        "TYRE_PRESSURE_REAR_LEFT", "TIRE_PRESSURE_REAR_LEFT" -> {
                            parsePressureToPsi(value)?.let { tpmsRlPsi = it }
                        }
                        "TYRE_PRESSURE_REAR_RIGHT", "TIRE_PRESSURE_REAR_RIGHT" -> {
                            parsePressureToPsi(value)?.let { tpmsRrPsi = it }
                        }
                        "BATTERY_VOLTAGE" -> {
                            value.toFloatOrNull()?.takeIf { it in 9.0f..16.0f }?.let { battery12vVolts = it }
                        }
                        "SERVICE_KILOMETERS_TO_SERVICE", "EXT_KILOMETERS_TO_SERVICE" -> {
                            value.toFloatOrNull()?.takeIf { it > 0f }?.let {
                                nextServiceMiles = (it * 0.621371f).roundToInt()
                            }
                        }
                        "WINDOW_FRONT_LEFT_STATUS", "WINDOW_FRONT_RIGHT_STATUS",
                        "WINDOW_REAR_LEFT_STATUS", "WINDOW_REAR_RIGHT_STATUS",
                        "DOOR_BOOT_POSITION", "DOOR_ENGINE_HOOD_POSITION" -> {
                            if (value.contains("OPEN", ignoreCase = true) || value.contains("AJAR", ignoreCase = true)) {
                                allDoorsWindowsClosed = false
                            }
                        }
                    }
                }

                val resolvedSoc = evSocPct ?: fuelPct ?: 0
                val resolvedIsEv = isEv || evSocPct != null
                val vehicleTitle = queryJlrVehicleNickname(accessToken, cleanVin)
                val discoveredCaps = discoverCapabilitiesFromVehiclePayload(
                    rawAttributes = rawAttributes,
                    isLocked = isLocked,
                    climateActive = climateActive,
                    isCharging = isCharging,
                    isEv = resolvedIsEv,
                    targetTempC = 21.0f,
                    socPct = resolvedSoc,
                    rangeMiles = rangeMiles,
                    odometerMiles = odometerMiles,
                    battery12vVolts = battery12vVolts,
                    tpmsFlPsi = tpmsFlPsi,
                    tpmsFrPsi = tpmsFrPsi,
                    tpmsRlPsi = tpmsRlPsi,
                    tpmsRrPsi = tpmsRrPsi
                )
                return SmartTile(
                    title = vehicleTitle,
                    roomOrSubtitle = "Driveway",
                    type = TileType.JAGUAR_VEHICLE,
                    colSpan = 1,
                    rowSpan = 1,
                    isVisible = true,
                    isOnline = true,
                    isActive = isLocked,
                    secretCode = pin,
                    serialNumber = cleanVin,
                    entityId = "vehicle.jaguar_${cleanVin.takeLast(6).lowercase(Locale.US)}",
                    companionAppPackage = "com.jlr.jaguar.incontrolremote.appstore",
                    vehicleLocked = isLocked,
                    vehicleFuelOrBatteryPct = resolvedSoc,
                    vehicleRangeMiles = rangeMiles,
                    vehicleIsEv = resolvedIsEv,
                    vehicleIsCharging = isCharging,
                    vehicleClimateActive = climateActive,
                    vehicleTargetClimateTempC = 21.0f,
                    vehicleOdometerMiles = odometerMiles,
                    vehicleLocationText = "Synced via Jaguar InControl",
                    vehicleVin = cleanVin,
                    vehicleTpmsFrontLeftPsi = tpmsFlPsi,
                    vehicleTpmsFrontRightPsi = tpmsFrPsi,
                    vehicleTpmsRearLeftPsi = tpmsRlPsi,
                    vehicleTpmsRearRightPsi = tpmsRrPsi,
                    vehicle12vBatteryVolts = battery12vVolts,
                    vehicleDoorsWindowsAllClosed = allDoorsWindowsClosed,
                    vehicleNextServiceMiles = nextServiceMiles,
                    capabilities = discoveredCaps,
                    capabilitySchemaHash = CapabilitySchemaHasher.computeHash(discoveredCaps),
                    deviceClassOverride = DeviceClass.VEHICLE,
                    deviceSubtypeOverride = DeviceSubtype.VEHICLE,
                    activeProviderId = "JAGUAR_INCONTROL",
                    stateAuthorityTier = StateAuthorityTier.DEVICE_HARDWARE_TRUTH
                )
            } catch (e: Exception) {
                Log.w(TAG, "queryJlrVehicleStatus ($acceptHeader) note: ${e.message}")
            }
        }
        return null
    }

    // -----------------------------------------------------------------------
    // 2. MERCEDES ME LIVE AUTHENTICATION & VEHICLE TELEMETRY
    // -----------------------------------------------------------------------
    private fun authenticateMercedesMeSession(
        emailOrUser: String,
        passwordOrToken: String
    ): String? {
        val cleanPass = passwordOrToken.trim()
        if (cleanPass.isBlank()) return null

        // Allow direct OAuth / BFF Bearer token if pasted into the password field
        if (cleanPass.startsWith("eyJ") && cleanPass.length > 60) {
            mbAccessToken = cleanPass
            mbTokenExpiryMs = System.currentTimeMillis() + 3300_000L
            return cleanPass
        }

        val cleanEmail = emailOrUser.trim()
        if (cleanEmail.isBlank()) return null

        return try {
            val formBody = listOf(
                "grant_type" to "password",
                "username" to cleanEmail,
                "password" to cleanPass,
                "client_id" to "01398c1c-dc45-4b42-882b-9f5ba9f175f1",
                "scope" to "openid email phone profile offline_access ciam-uid"
            ).joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }

            val conn = (URL(MB_OIDC_TOKEN_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 6000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "MercedesMe/1.42.0 (Android 14)")
                setRequestProperty("Accept", "application/json")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(formBody); it.flush() }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val token = json.optString("access_token", "").trim()
            val refresh = json.optString("refresh_token", "").trim()
            val expiresIn = json.optLong("expires_in", 3600L)
            if (token.isNotBlank()) {
                mbAccessToken = token
                if (refresh.isNotBlank()) mbRefreshToken = refresh
                mbTokenExpiryMs = System.currentTimeMillis() + (expiresIn - 120L).coerceAtLeast(300L) * 1000L
                saveEncryptedMbSession()
                token
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Mercedes me OIDC auth note: ${e.message}")
            null
        }
    }

    private fun fetchMercedesVehiclesWithToken(
        accessToken: String,
        vinFallback: String,
        pin: String
    ): List<SmartTile> {
        val result = mutableListOf<SmartTile>()
        val candidateVins = LinkedHashSet<Pair<String, String>>()
        val sanitizedFallback = DeviceRepository.sanitizeRealVin(vinFallback)
        if (sanitizedFallback.length >= 11) {
            candidateVins.add(sanitizedFallback to "Mercedes-Benz")
        }

        for (bffHost in MB_BFF_HOSTS) {
            try {
                val conn = (URL("$bffHost/v1/vehicles").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5500
                    readTimeout = 5500
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode in 200..299) {
                    mbActiveBffHost = bffHost
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val root = runCatching { JSONObject(body) }.getOrNull()
                    val arr = root?.optJSONArray("assignedVehicles")
                        ?: root?.optJSONArray("vehicles")
                        ?: runCatching { JSONArray(body) }.getOrNull()
                        ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val vObj = arr.optJSONObject(i) ?: continue
                        val rawVin = vObj.optString("fin", vObj.optString("vin", "")).trim()
                        val vin = DeviceRepository.sanitizeRealVin(rawVin)
                        val modelName = vObj.optString("salesRelatedInformation", "")
                            .let {
                                vObj.optJSONObject("salesRelatedInformation")?.optString("baumusterDescription", "")
                                    ?: vObj.optString("licensePlate", "")
                                    ?: vObj.optString("model", "Mercedes-Benz")
                            }
                            .ifBlank { "Mercedes-Benz" }
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                            transportSource = "MERCEDES_ME_CLOUD",
                            hardwareId = vin.ifBlank { rawVin.take(8) },
                            vendor = modelName,
                            confidenceScore = 0.85f,
                            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                            outcomeReason = "Mercedes me BFF /v1/vehicles entry received",
                            cloudId = vin
                        )
                        if (vin.isNotBlank()) {
                            candidateVins.add(vin to modelName)
                        } else {
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.FILTERED,
                                transportSource = "MERCEDES_ME_CLOUD",
                                vendor = modelName,
                                qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                outcomeReason = "Rejected invalid or placeholder Mercedes VIN"
                            )
                        }
                    }
                    break
                } else {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "MERCEDES_ME_CLOUD",
                    vendor = "Mercedes me ($bffHost)",
                    qualificationStage = "fetchMercedesVehiclesWithToken",
                    outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "Mercedes BFF vehicle list error"}"
                )
            }
        }

        for ((vin, modelTitle) in candidateVins) {
            val statusTile = queryMercedesVehicleStatus(accessToken, vin, modelTitle, pin)
            if (statusTile != null) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.IDENTIFIED,
                    transportSource = "MERCEDES_ME_CLOUD",
                    hardwareId = vin,
                    vendor = statusTile.title,
                    confidenceScore = 0.96f,
                    qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                    outcomeReason = "Synced Mercedes me telemetry (ev=${statusTile.vehicleIsEv} caps=${statusTile.capabilities.size})",
                    cloudId = statusTile.entityId
                )
                cachedVehicleTelemetryByVin[vin] = statusTile
                result.add(statusTile)
            } else {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "MERCEDES_ME_CLOUD",
                    hardwareId = vin,
                    vendor = modelTitle,
                    qualificationStage = "queryMercedesVehicleStatus",
                    outcomeReason = "Could not query Mercedes vehicle status container",
                    cloudId = vin
                )
            }
        }
        return result
    }

    private fun queryMercedesVehicleStatus(
        accessToken: String,
        vin: String,
        modelTitle: String,
        pin: String
    ): SmartTile? {
        val cleanVin = DeviceRepository.sanitizeRealVin(vin)
        if (cleanVin.isBlank()) return null

        val endpoints = listOf(
            "$mbActiveBffHost/v1/vehicle/$cleanVin/status",
            "https://api.mercedes-benz.com/vehicledata/v2/vehicles/$cleanVin/containers/payasyoudrive"
        )

        for (endpoint in endpoints) {
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5500
                    readTimeout = 5500
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    continue
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val root = JSONObject(body)
                val attrs = root.optJSONObject("attributes") ?: root
                val rawAttributes = LinkedHashMap<String, String>()
                val attrKeys = attrs.keys()
                while (attrKeys.hasNext()) {
                    val k = attrKeys.next()
                    val obj = attrs.optJSONObject(k)
                    val v = obj?.optString("value", obj.optString("displayValue", ""))?.trim()
                        ?: attrs.optString(k, "").trim()
                    if (k.isNotBlank() && v.isNotBlank()) {
                        rawAttributes[k] = v
                    }
                }

                fun extractAttrValue(vararg keys: String): String {
                    for (k in keys) {
                        val obj = attrs.optJSONObject(k)
                        if (obj != null) {
                            val v = obj.optString("value", obj.optString("displayValue", "")).trim()
                            if (v.isNotBlank()) return v
                        }
                        val direct = attrs.optString(k, "").trim()
                        if (direct.isNotBlank() && !direct.startsWith("{")) return direct
                    }
                    return ""
                }

                val lockRaw = extractAttrValue("doorLockStatusOverall", "doorlockstatusoverall")
                val isLocked = lockRaw in listOf("1", "2", "LOCKED", "locked", "true", "TRUE") || lockRaw.isBlank()

                val socRaw = extractAttrValue("soc", "stateOfCharge")
                val tankRaw = extractAttrValue("tanklevelpercent", "tankLevelPercent")
                val evSoc = socRaw.toFloatOrNull()?.roundToInt()?.takeIf { it in 0..100 }
                val fuelSoc = tankRaw.toFloatOrNull()?.roundToInt()?.takeIf { it in 0..100 }
                val isEv = evSoc != null
                val resolvedSoc = evSoc ?: fuelSoc ?: 0

                val rangeElectricRaw = extractAttrValue("rangeelectric", "rangeElectric")
                val rangeLiquidRaw = extractAttrValue("rangeliquid", "rangeLiquid")
                val rawRangeVal = (rangeElectricRaw.toFloatOrNull() ?: rangeLiquidRaw.toFloatOrNull() ?: 0f)
                val rangeMiles = if (rawRangeVal > 0f) rawRangeVal.roundToInt() else 0

                val chargeStatusRaw = extractAttrValue("chargingstatus", "chargingStatus")
                val isCharging = chargeStatusRaw in listOf("0", "CHARGING", "charging") && chargeStatusRaw.isNotBlank()

                val precondRaw = extractAttrValue("precondActive", "precondState", "auxheatActive")
                val climateActive = precondRaw.equals("true", ignoreCase = true) || precondRaw == "1"

                val odoRaw = extractAttrValue("odo", "odometer")
                val odometerMiles = odoRaw.toFloatOrNull()?.roundToInt() ?: 0

                fun parseMbPressurePsi(vararg keys: String, defaultPsi: Float = 0f): Float {
                    val v = extractAttrValue(*keys).toFloatOrNull() ?: return defaultPsi
                    if (v <= 0f) return defaultPsi
                    return when {
                        v > 100f -> v * 0.145038f
                        v < 8f -> v * 14.5038f
                        else -> v
                    }
                }

                val tpmsFl = parseMbPressurePsi("tirepressureFrontLeft", "tirePressureFrontLeft", defaultPsi = 0f)
                val tpmsFr = parseMbPressurePsi("tirepressureFrontRight", "tirePressureFrontRight", defaultPsi = 0f)
                val tpmsRl = parseMbPressurePsi("tirepressureRearLeft", "tirePressureRearLeft", defaultPsi = 0f)
                val tpmsRr = parseMbPressurePsi("tirepressureRearRight", "tirePressureRearRight", defaultPsi = 0f)
                val batt12v = extractAttrValue("starterBatteryVoltage", "batteryVoltage").toFloatOrNull()
                    ?.takeIf { it in 9.0f..16.0f } ?: 0f
                val windowStatus = extractAttrValue("windowstatusOverall", "windowStatusOverall")
                val decklidStatus = extractAttrValue("decklidstatus", "decklidStatus")
                val allClosed = (windowStatus.isBlank() || windowStatus in listOf("2", "CLOSED", "closed", "true")) &&
                        (decklidStatus.isBlank() || decklidStatus in listOf("0", "false", "CLOSED", "closed"))
                val serviceDist = extractAttrValue("serviceintervaldistance", "serviceIntervalDistance").toIntOrNull()
                    ?.takeIf { it > 0 } ?: 0

                val discoveredCaps = discoverCapabilitiesFromVehiclePayload(
                    rawAttributes = rawAttributes,
                    isLocked = isLocked,
                    climateActive = climateActive,
                    isCharging = isCharging,
                    isEv = isEv,
                    targetTempC = 21.0f,
                    socPct = resolvedSoc,
                    rangeMiles = rangeMiles,
                    odometerMiles = odometerMiles,
                    battery12vVolts = batt12v,
                    tpmsFlPsi = tpmsFl,
                    tpmsFrPsi = tpmsFr,
                    tpmsRlPsi = tpmsRl,
                    tpmsRrPsi = tpmsRr
                )
                return SmartTile(
                    title = modelTitle.ifBlank { "Mercedes-Benz" },
                    roomOrSubtitle = "Driveway",
                    type = TileType.MERCEDES_VEHICLE,
                    colSpan = 1,
                    rowSpan = 1,
                    isVisible = true,
                    isOnline = true,
                    isActive = isLocked,
                    secretCode = pin,
                    serialNumber = cleanVin,
                    entityId = "vehicle.mercedes_${cleanVin.takeLast(6).lowercase(Locale.US)}",
                    companionAppPackage = "com.daimler.ris.mercedesme.ece.android",
                    vehicleLocked = isLocked,
                    vehicleFuelOrBatteryPct = resolvedSoc,
                    vehicleRangeMiles = rangeMiles,
                    vehicleIsEv = isEv,
                    vehicleIsCharging = isCharging,
                    vehicleClimateActive = climateActive,
                    vehicleTargetClimateTempC = 21.0f,
                    vehicleOdometerMiles = odometerMiles,
                    vehicleLocationText = "Synced via Mercedes me",
                    vehicleVin = cleanVin,
                    vehicleTpmsFrontLeftPsi = tpmsFl,
                    vehicleTpmsFrontRightPsi = tpmsFr,
                    vehicleTpmsRearLeftPsi = tpmsRl,
                    vehicleTpmsRearRightPsi = tpmsRr,
                    vehicle12vBatteryVolts = batt12v,
                    vehicleDoorsWindowsAllClosed = allClosed,
                    vehicleNextServiceMiles = serviceDist,
                    capabilities = discoveredCaps,
                    capabilitySchemaHash = CapabilitySchemaHasher.computeHash(discoveredCaps),
                    deviceClassOverride = DeviceClass.VEHICLE,
                    deviceSubtypeOverride = DeviceSubtype.VEHICLE,
                    activeProviderId = "MERCEDES_ME",
                    stateAuthorityTier = StateAuthorityTier.DEVICE_HARDWARE_TRUTH
                )
            } catch (e: Exception) {
                Log.w(TAG, "queryMercedesVehicleStatus note: ${e.message}")
            }
        }
        return null
    }

    suspend fun loginAndDiscoverMercedesVehicles(
        context: Context,
        bridge: BridgeSettings,
        email: String = bridge.mercedesEmail,
        password: String = bridge.mercedesPassword,
        pin: String = bridge.mercedesPin,
        vinOverride: String = bridge.mercedesVin
    ): Result<List<SmartTile>> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val cleanEmail = email.trim()
        val cleanPass = password.trim()
        val cleanPin = pin.trim()
        val cleanVin = DeviceRepository.sanitizeRealVin(vinOverride)

        val updatedBridge = bridge.copy(
            mercedesEmail = cleanEmail,
            mercedesPassword = cleanPass,
            mercedesPin = cleanPin,
            mercedesVin = cleanVin
        )
        withContext(Dispatchers.Main) {
            DeviceRepository.saveBridge(context, updatedBridge)
        }

        val discoveredTiles = mutableListOf<SmartTile>()

        // 2A. Direct Mercedes me OIDC / BFF authentication & vehicle status polling
        if (cleanPass.isNotBlank()) {
            val token = authenticateMercedesMeSession(cleanEmail, cleanPass)
            if (!token.isNullOrBlank()) {
                val mbVehicles = fetchMercedesVehiclesWithToken(token, cleanVin, cleanPin)
                discoveredTiles.addAll(mbVehicles)
            }
        }

        // 2B. Check Home Assistant (`mbapi2020` / `mercedesme`) if configured
        if (discoveredTiles.isEmpty() && bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()) {
            val haVehicles = discoverVehiclesFromHomeAssistant(
                bridge = updatedBridge,
                targetType = TileType.MERCEDES_VEHICLE
            )
            discoveredTiles.addAll(haVehicles)
        }

        val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(15L)
        if (discoveredTiles.isNotEmpty()) {
            val firstRealVin = discoveredTiles.first().vehicleVin
            if (firstRealVin.isNotBlank() && firstRealVin != cleanVin) {
                withContext(Dispatchers.Main) {
                    DeviceRepository.saveBridge(context, updatedBridge.copy(mercedesVin = firstRealVin))
                }
            }
            JarvisLiveBridge.recordCommandLatency(
                integrationKey = "VEHICLES",
                displayName = "Jaguar InControl & Mercedes me",
                latencyMs = latency,
                succeeded = true,
                tokenValid = true,
                onlineDevices = discoveredTiles.size,
                statusMessage = "Mercedes me Synced (${discoveredTiles.first().title})"
            )
            return@withContext Result.success(discoveredTiles)
        }

        DiscoveryFlightRecorder.record(
            eventType = DiscoveryEventType.FAILED,
            transportSource = "MERCEDES_ME_CLOUD",
            vendor = "Mercedes me",
            qualificationStage = "loginAndDiscoverMercedesVehicles",
            outcomeReason = "Mercedes me authentication or VIN discovery returned 0 vehicles"
        )
        JarvisLiveBridge.recordCommandLatency(
            integrationKey = "VEHICLES",
            displayName = "Jaguar InControl & Mercedes me",
            latencyMs = latency,
            succeeded = false,
            tokenValid = false,
            statusMessage = "Mercedes me sign-in required"
        )
        Result.failure(
            Exception(
                "Could not authenticate with Mercedes me or find a linked Mercedes-Benz vehicle. Please verify your Mercedes me credentials or Home Assistant integration."
            )
        )
    }

    // -----------------------------------------------------------------------
    // 2C. PHASE 6.4 UNIFIED 11-BRAND VEHICLE PROVIDER ADAPTER FRAMEWORK
    //     (Jaguar, Mercedes, Tesla, BMW, Audi, VW, Volvo, Ford, Polestar, Hyundai, Kia)
    // -----------------------------------------------------------------------
    private fun brandKeywordsForHa(brand: ConnectedVehicleBrand): List<String> = when (brand) {
        ConnectedVehicleBrand.JAGUAR -> listOf("jaguar", "ipace", "i_pace", "f_pace", "e_pace", "jlr")
        ConnectedVehicleBrand.MERCEDES -> listOf("mercedes", "benz", "eqa", "eqb", "eqc", "eqe", "eqs", "amg")
        ConnectedVehicleBrand.TESLA -> listOf("tesla", "model_3", "model_y", "model_s", "model_x")
        ConnectedVehicleBrand.BMW -> listOf("bmw", "ix", "i4", "i5", "i7", "connected_drive")
        ConnectedVehicleBrand.AUDI -> listOf("audi", "etron", "e_tron", "q4", "q8_etron")
        ConnectedVehicleBrand.VW -> listOf("volkswagen", "vw_", "id3", "id_3", "id4", "id_4", "id7")
        ConnectedVehicleBrand.VOLVO -> listOf("volvo", "xc40", "ex30", "ex90", "c40", "xc60", "xc90")
        ConnectedVehicleBrand.FORD -> listOf("ford", "mach_e", "mache", "mustang", "f150", "fordpass")
        ConnectedVehicleBrand.POLESTAR -> listOf("polestar")
        ConnectedVehicleBrand.HYUNDAI -> listOf("hyundai", "ioniq", "kona_ev", "bluelink")
        ConnectedVehicleBrand.KIA -> listOf("kia", "ev6", "ev9", "niro_ev")
    }

    private suspend fun discoverVehiclesFromHomeAssistant(
        bridge: BridgeSettings,
        targetType: TileType,
        brandHint: ConnectedVehicleBrand? = null
    ): List<SmartTile> = withContext(Dispatchers.IO) {
        val cleanUrl = bridge.haUrl.trim().trimEnd('/')
        val cleanToken = bridge.haToken.trim()
        if (cleanUrl.isBlank() || cleanToken.isBlank()) return@withContext emptyList()
        val effectiveBrand = brandHint
            ?: if (targetType == TileType.MERCEDES_VEHICLE) ConnectedVehicleBrand.MERCEDES else ConnectedVehicleBrand.JAGUAR
        val keywords = brandKeywordsForHa(effectiveBrand)

        return@withContext try {
            val conn = (URL("$cleanUrl/api/states").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Authorization", "Bearer $cleanToken")
                setRequestProperty("Content-Type", "application/json")
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@withContext emptyList()
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val arr = JSONArray(body)

            var foundLockEntity = ""
            var isLocked = true
            var isOnline = true
            var socPct = 0
            var rangeMiles = 0
            var isEv = effectiveBrand.defaultIsEv
            var isCharging = false
            var climateActive = false
            var odometerMiles = 0
            var locationText = ""
            var vin = DeviceRepository.sanitizeRealVin(
                when (effectiveBrand) {
                    ConnectedVehicleBrand.JAGUAR -> bridge.jaguarVin
                    ConnectedVehicleBrand.MERCEDES -> bridge.mercedesVin
                    ConnectedVehicleBrand.TESLA -> bridge.teslaVin
                    else -> ""
                }
            )
            var friendlyTitle = effectiveBrand.displayName.substringBefore(" ")

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val rawEntityId = obj.optString("entity_id", "")
                val entityId = rawEntityId.lowercase(Locale.US)
                val state = obj.optString("state", "")
                val attrs = obj.optJSONObject("attributes") ?: JSONObject()
                val fn = attrs.optString("friendly_name", "")

                val matchesBrand = keywords.any { kw ->
                    entityId.contains(kw) || fn.contains(kw, ignoreCase = true)
                } || (vin.isNotBlank() && attrs.optString("vin", "").equals(vin, ignoreCase = true))

                if (!matchesBrand) continue

                attrs.optString("vin", attrs.optString("fin", "")).takeIf { it.isNotBlank() }?.let {
                    val sanitized = DeviceRepository.sanitizeRealVin(it)
                    if (sanitized.isNotBlank()) vin = sanitized
                }

                when {
                    entityId.startsWith("lock.") -> {
                        foundLockEntity = rawEntityId
                        isOnline = !state.equals("unavailable", ignoreCase = true) &&
                                !state.equals("unknown", ignoreCase = true)
                        isLocked = state.equals("locked", ignoreCase = true)
                        if (fn.isNotBlank()) {
                            friendlyTitle = fn.replace(" Lock", "", ignoreCase = true)
                                .replace(" Doors", "", ignoreCase = true)
                                .replace(" Door Lock", "", ignoreCase = true)
                                .trim()
                        }
                    }
                    entityId.startsWith("device_tracker.") -> {
                        if (state.isNotBlank() && !state.equals("unknown", true) && !state.equals("unavailable", true)) {
                            locationText = state.replaceFirstChar { it.uppercase(Locale.US) }
                        }
                    }
                    entityId.contains("soc") || entityId.contains("state_of_charge") || entityId.contains("battery") -> {
                        state.toFloatOrNull()?.roundToInt()?.takeIf { it in 0..100 }?.let {
                            socPct = it
                            isEv = true
                        }
                    }
                    entityId.contains("tank_level") || entityId.contains("fuel_level") -> {
                        if (socPct == 0) {
                            state.toFloatOrNull()?.roundToInt()?.takeIf { it in 0..100 }?.let {
                                socPct = it
                            }
                        }
                    }
                    entityId.contains("range") -> {
                        val rawVal = state.toFloatOrNull()
                        if (rawVal != null && rawVal > 0f) {
                            val unit = attrs.optString("unit_of_measurement", "mi").lowercase(Locale.US)
                            rangeMiles = if (unit.contains("km")) {
                                (rawVal * 0.621371f).roundToInt()
                            } else {
                                rawVal.roundToInt()
                            }
                            if (entityId.contains("electric")) isEv = true
                        }
                    }
                    entityId.contains("odometer") || entityId.contains("mileage") -> {
                        val rawOdo = state.toFloatOrNull()?.roundToInt()
                        if (rawOdo != null && rawOdo > 0) odometerMiles = rawOdo
                    }
                    entityId.contains("charging") -> {
                        isCharging = state.equals("on", ignoreCase = true) ||
                                state.equals("charging", ignoreCase = true) ||
                                state.equals("true", ignoreCase = true)
                    }
                    entityId.contains("preclimate") || entityId.contains("preconditioning") || entityId.contains("auxheat") || entityId.contains("climate") -> {
                        climateActive = state.equals("on", ignoreCase = true) ||
                                state.equals("heating", ignoreCase = true) ||
                                state.equals("cooling", ignoreCase = true) ||
                                state.equals("active", ignoreCase = true)
                    }
                }
            }

            if (foundLockEntity.isBlank() && vin.isBlank() && socPct == 0 && rangeMiles == 0) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FILTERED,
                    transportSource = "VEHICLE_HA_BRIDGE",
                    vendor = effectiveBrand.displayName,
                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                    outcomeReason = "No matching ${effectiveBrand.displayName} vehicle entities found in Home Assistant states"
                )
                return@withContext emptyList()
            }

            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                transportSource = "VEHICLE_HA_BRIDGE",
                hardwareId = vin.ifBlank { foundLockEntity },
                vendor = friendlyTitle,
                confidenceScore = 0.85f,
                qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                outcomeReason = "Home Assistant vehicle entity group matched (${effectiveBrand.displayName})",
                cloudId = foundLockEntity
            )
            val caps = defaultCapabilitiesForVehicle(
                type = targetType,
                isLocked = isLocked,
                climateActive = climateActive,
                isCharging = isCharging,
                targetTempC = 21.0f,
                socPct = socPct,
                rangeMiles = rangeMiles
            )
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.IDENTIFIED,
                transportSource = "VEHICLE_HA_BRIDGE",
                hardwareId = vin.ifBlank { foundLockEntity },
                vendor = friendlyTitle,
                confidenceScore = 0.95f,
                qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                outcomeReason = "Correlated ${effectiveBrand.displayName} vehicle via Home Assistant (online=$isOnline soc=$socPct%)",
                cloudId = foundLockEntity
            )
            listOf(
                SmartTile(
                    title = friendlyTitle,
                    roomOrSubtitle = "Driveway",
                    type = targetType,
                    colSpan = 1,
                    rowSpan = 1,
                    isVisible = true,
                    isOnline = isOnline,
                    isActive = isLocked,
                    secretCode = if (targetType == TileType.JAGUAR_VEHICLE) bridge.jaguarPin else bridge.mercedesPin,
                    serialNumber = vin.ifBlank { foundLockEntity },
                    entityId = foundLockEntity,
                    companionAppPackage = effectiveBrand.providerId.id,
                    vehicleLocked = isLocked,
                    vehicleFuelOrBatteryPct = socPct,
                    vehicleRangeMiles = rangeMiles,
                    vehicleIsEv = isEv,
                    vehicleIsCharging = isCharging,
                    vehicleClimateActive = climateActive,
                    vehicleTargetClimateTempC = 21.0f,
                    vehicleOdometerMiles = odometerMiles,
                    vehicleLocationText = locationText.ifBlank { "Synced via ${effectiveBrand.displayName}" },
                    vehicleVin = vin,
                    capabilities = caps,
                    capabilitySchemaHash = CapabilitySchemaHasher.computeHash(caps),
                    deviceClassOverride = DeviceClass.VEHICLE,
                    deviceSubtypeOverride = DeviceSubtype.VEHICLE,
                    activeProviderId = effectiveBrand.providerId.id,
                    stateAuthorityTier = StateAuthorityTier.CLOUD_SHADOW_STATE
                )
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.FAILED,
                transportSource = "VEHICLE_HA_BRIDGE",
                vendor = effectiveBrand.displayName,
                qualificationStage = "discoverVehiclesFromHomeAssistant",
                outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "HA vehicle discovery failed"}"
            )
            emptyList()
        }
    }

    private fun queryTeslaFleetVehicleStatus(
        accessToken: String,
        vin: String,
        fallbackTitle: String = "Tesla"
    ): SmartTile? {
        val cleanToken = accessToken.trim()
        if (cleanToken.isBlank()) return null
        val cleanVin = DeviceRepository.sanitizeRealVin(vin)
        val hosts = listOf(
            "https://fleet-api.prd.eu.vn.cloud.tesla.com",
            "https://fleet-api.prd.na.vn.cloud.tesla.com"
        )
        for (host in hosts) {
            try {
                val targetVin = if (cleanVin.isNotBlank()) {
                    cleanVin
                } else {
                    val listConn = (URL("$host/api/1/vehicles").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 5000
                        readTimeout = 5000
                        setRequestProperty("Authorization", "Bearer $cleanToken")
                        setRequestProperty("Accept", "application/json")
                    }
                    val firstVin = if (listConn.responseCode in 200..299) {
                        val body = listConn.inputStream.bufferedReader().use { it.readText() }
                        JSONObject(body).optJSONArray("response")?.optJSONObject(0)?.optString("vin", "").orEmpty()
                    } else ""
                    listConn.disconnect()
                    DeviceRepository.sanitizeRealVin(firstVin)
                }
                if (targetVin.isBlank()) continue

                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                    transportSource = "TESLA_FLEET_CLOUD",
                    hardwareId = targetVin,
                    vendor = fallbackTitle,
                    confidenceScore = 0.85f,
                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                    outcomeReason = "Tesla Fleet VIN resolved ($host)",
                    cloudId = targetVin
                )

                val conn = (URL("$host/api/1/vehicles/$targetVin/vehicle_data").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5500
                    readTimeout = 5500
                    setRequestProperty("Authorization", "Bearer $cleanToken")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    continue
                }
                val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    .optJSONObject("response") ?: continue
                conn.disconnect()

                val displayName = resp.optString("vehicle_config", "").let {
                    resp.optString("display_name", fallbackTitle).ifBlank { fallbackTitle }
                }
                val chargeState = resp.optJSONObject("charge_state") ?: JSONObject()
                val climateState = resp.optJSONObject("climate_state") ?: JSONObject()
                val vehicleState = resp.optJSONObject("vehicle_state") ?: JSONObject()

                val isLocked = vehicleState.optBoolean("locked", true)
                val socPct = chargeState.optInt("battery_level", 0).coerceIn(0, 100)
                val rangeMiles = chargeState.optDouble("battery_range", 0.0).roundToInt().coerceAtLeast(0)
                val chargingStateStr = chargeState.optString("charging_state", "")
                val isCharging = chargingStateStr.equals("Charging", ignoreCase = true)
                val climateActive = climateState.optBoolean("is_climate_on", false)
                val targetTempC = climateState.optDouble("driver_temp_setting", 21.0).toFloat()
                val odoMiles = vehicleState.optDouble("odometer", 0.0).roundToInt()
                val tpmsFl = (vehicleState.optDouble("tpms_pressure_fl", 0.0) * 14.5038).toFloat()
                val tpmsFr = (vehicleState.optDouble("tpms_pressure_fr", 0.0) * 14.5038).toFloat()
                val tpmsRl = (vehicleState.optDouble("tpms_pressure_rl", 0.0) * 14.5038).toFloat()
                val tpmsRr = (vehicleState.optDouble("tpms_pressure_rr", 0.0) * 14.5038).toFloat()

                val caps = discoverCapabilitiesFromVehiclePayload(
                    rawAttributes = mapOf(
                        "LOCK" to isLocked.toString(),
                        "CLIMATE" to climateActive.toString(),
                        "EV_CHARGING" to isCharging.toString(),
                        "SOC" to socPct.toString(),
                        "RANGE" to rangeMiles.toString(),
                        "ODOMETER" to odoMiles.toString()
                    ),
                    isLocked = isLocked,
                    climateActive = climateActive,
                    isCharging = isCharging,
                    isEv = true,
                    targetTempC = targetTempC,
                    socPct = socPct,
                    rangeMiles = rangeMiles,
                    odometerMiles = odoMiles,
                    tpmsFlPsi = tpmsFl,
                    tpmsFrPsi = tpmsFr,
                    tpmsRlPsi = tpmsRl,
                    tpmsRrPsi = tpmsRr
                )
                val teslaEntityId = "vehicle.tesla_${targetVin.takeLast(6).lowercase(Locale.US)}"
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.IDENTIFIED,
                    transportSource = "TESLA_FLEET_CLOUD",
                    hardwareId = targetVin,
                    vendor = displayName,
                    confidenceScore = 0.96f,
                    qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                    outcomeReason = "Synced Tesla Fleet vehicle_data (soc=$socPct% locked=$isLocked)",
                    cloudId = teslaEntityId
                )
                return SmartTile(
                    title = displayName,
                    roomOrSubtitle = "Driveway",
                    type = TileType.JAGUAR_VEHICLE,
                    colSpan = 1,
                    rowSpan = 1,
                    isVisible = true,
                    isOnline = true,
                    isActive = isLocked,
                    serialNumber = targetVin,
                    entityId = teslaEntityId,
                    companionAppPackage = ProviderId.TESLA_FLEET.id,
                    vehicleLocked = isLocked,
                    vehicleFuelOrBatteryPct = socPct,
                    vehicleRangeMiles = rangeMiles,
                    vehicleIsEv = true,
                    vehicleIsCharging = isCharging,
                    vehicleClimateActive = climateActive,
                    vehicleTargetClimateTempC = targetTempC,
                    vehicleOdometerMiles = odoMiles,
                    vehicleLocationText = "Synced via Tesla Fleet API",
                    vehicleVin = targetVin,
                    vehicleTpmsFrontLeftPsi = tpmsFl,
                    vehicleTpmsFrontRightPsi = tpmsFr,
                    vehicleTpmsRearLeftPsi = tpmsRl,
                    vehicleTpmsRearRightPsi = tpmsRr,
                    capabilities = caps,
                    capabilitySchemaHash = CapabilitySchemaHasher.computeHash(caps),
                    deviceClassOverride = DeviceClass.VEHICLE,
                    deviceSubtypeOverride = DeviceSubtype.VEHICLE,
                    activeProviderId = ProviderId.TESLA_FLEET.id,
                    stateAuthorityTier = StateAuthorityTier.DEVICE_HARDWARE_TRUTH
                )
            } catch (e: Exception) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "TESLA_FLEET_CLOUD",
                    hardwareId = cleanVin,
                    vendor = "$fallbackTitle ($host)",
                    qualificationStage = "queryTeslaFleetVehicleStatus",
                    outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "Tesla Fleet status error"}"
                )
            }
        }
        return null
    }

    private fun executeTeslaFleetCommand(
        accessToken: String,
        vin: String,
        commandEndpoint: String,
        payload: JSONObject = JSONObject()
    ): Boolean {
        val cleanToken = accessToken.trim()
        val cleanVin = DeviceRepository.sanitizeRealVin(vin)
        if (cleanToken.isBlank() || cleanVin.isBlank()) return false
        val hosts = listOf(
            "https://fleet-api.prd.eu.vn.cloud.tesla.com",
            "https://fleet-api.prd.na.vn.cloud.tesla.com"
        )
        for (host in hosts) {
            try {
                val conn = (URL("$host/api/1/vehicles/$cleanVin/command/$commandEndpoint").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 5000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $cleanToken")
                    setRequestProperty("Content-Type", "application/json")
                }
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()); it.flush() }
                val code = conn.responseCode
                val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                // F-20: Verify Tesla Fleet JSON `"response": {"result": true}` so failed commands never report false lock state
                if (code in 200..299) {
                    val respJson = runCatching { JSONObject(respBody) }.getOrNull()
                    val innerResp = respJson?.optJSONObject("response") ?: respJson
                    val confirmed = innerResp?.optBoolean("result", true) ?: true
                    if (confirmed) return true
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
        return false
    }

    private fun buildBrandAdapter(targetBrand: ConnectedVehicleBrand): VehicleProviderAdapter =
        object : VehicleProviderAdapter {
            override val brand: ConnectedVehicleBrand = targetBrand

            override suspend fun fetchTelemetry(
                context: Context,
                bridge: BridgeSettings,
                tile: SmartTile
            ): SmartTile? {
                val vinKey = DeviceRepository.sanitizeRealVin(tile.vehicleVin.ifBlank { tile.serialNumber })
                val directTile = when (brand) {
                    ConnectedVehicleBrand.JAGUAR -> {
                        if (ensureValidJaguarToken(bridge)) {
                            if (vinKey.isNotBlank()) {
                                queryJlrVehicleStatus(
                                    accessToken = jlrAccessToken,
                                    vin = vinKey,
                                    pin = tile.secretCode.ifBlank { bridge.jaguarPin }
                                )
                            } else {
                                fetchJlrVehiclesWithToken(
                                    accessToken = jlrAccessToken,
                                    email = bridge.jaguarEmail,
                                    vinFallback = bridge.jaguarVin,
                                    pin = tile.secretCode.ifBlank { bridge.jaguarPin }
                                ).firstOrNull()
                            }
                        } else null
                    }
                    ConnectedVehicleBrand.MERCEDES -> {
                        if (ensureValidMercedesToken(bridge)) {
                            if (vinKey.isNotBlank()) {
                                queryMercedesVehicleStatus(
                                    accessToken = mbAccessToken,
                                    vin = vinKey,
                                    modelTitle = tile.title,
                                    pin = tile.secretCode.ifBlank { bridge.mercedesPin }
                                )
                            } else {
                                fetchMercedesVehiclesWithToken(
                                    accessToken = mbAccessToken,
                                    vinFallback = bridge.mercedesVin,
                                    pin = tile.secretCode.ifBlank { bridge.mercedesPin }
                                ).firstOrNull()
                            }
                        } else null
                    }
                    ConnectedVehicleBrand.TESLA -> {
                        val token = bridge.teslaAccessToken.ifBlank { tile.secretCode }
                        queryTeslaFleetVehicleStatus(
                            accessToken = token,
                            vin = vinKey.ifBlank { bridge.teslaVin },
                            fallbackTitle = tile.title.ifBlank { "Tesla" }
                        )
                    }
                    else -> null
                }
                if (directTile != null) return directTile

                if (bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()) {
                    return discoverVehiclesFromHomeAssistant(
                        bridge = bridge,
                        targetType = tile.type,
                        brandHint = brand
                    ).firstOrNull()
                }
                return null
            }

            override suspend fun setLocked(
                context: Context,
                bridge: BridgeSettings,
                tile: SmartTile,
                lock: Boolean
            ): Pair<Boolean, String> {
                val vin = DeviceRepository.sanitizeRealVin(tile.vehicleVin.ifBlank { tile.serialNumber })
                val effectivePin = tile.secretCode
                    .ifBlank { if (brand == ConnectedVehicleBrand.JAGUAR) bridge.jaguarPin else bridge.mercedesPin }

                var ok = false
                if (tile.entityId.startsWith("lock.") && bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()) {
                    ok = SmartHomeApiClient.callHomeAssistantService(
                        haBaseUrl = bridge.haUrl,
                        haToken = bridge.haToken,
                        domain = "lock",
                        service = if (lock) "lock" else "unlock",
                        payloadJson = JSONObject().apply {
                            put("entity_id", tile.entityId)
                            if (effectivePin.isNotBlank()) put("code", effectivePin)
                        }
                    )
                }
                if (!ok && vin.isNotBlank()) {
                    ok = when (brand) {
                        ConnectedVehicleBrand.JAGUAR -> ensureValidJaguarToken(bridge) && executeJlrRemoteCommand(
                            vin = vin,
                            servicePath = if (lock) "lock" else "unlock",
                            serviceName = if (lock) "RDL" else "RDU",
                            pin = effectivePin
                        )
                        ConnectedVehicleBrand.MERCEDES -> ensureValidMercedesToken(bridge) && executeMercedesRemoteCommand(
                            vin = vin,
                            commandPath = if (lock) "doors/lock" else "doors/unlock",
                            pin = effectivePin
                        )
                        ConnectedVehicleBrand.TESLA -> executeTeslaFleetCommand(
                            accessToken = bridge.teslaAccessToken.ifBlank { tile.secretCode },
                            vin = vin,
                            commandEndpoint = if (lock) "door_lock" else "door_unlock"
                        )
                        else -> false
                    }
                }
                val verb = if (lock) "Locked" else "Unlocked"
                val msg = if (ok) "$verb ${tile.title}." else "Could not ${if (lock) "lock" else "unlock"} ${tile.title}."
                return ok to msg
            }

            override suspend fun setClimate(
                context: Context,
                bridge: BridgeSettings,
                tile: SmartTile,
                turnOn: Boolean
            ): Pair<Boolean, String> {
                val vin = DeviceRepository.sanitizeRealVin(tile.vehicleVin.ifBlank { tile.serialNumber })
                val clampedTemp = tile.vehicleTargetClimateTempC.coerceIn(16f, 28f)
                val effectivePin = tile.secretCode
                    .ifBlank { if (brand == ConnectedVehicleBrand.JAGUAR) bridge.jaguarPin else bridge.mercedesPin }

                val ok = when (brand) {
                    ConnectedVehicleBrand.JAGUAR -> vin.isNotBlank() && ensureValidJaguarToken(bridge) && executeJlrRemoteCommand(
                        vin = vin,
                        servicePath = "preconditioning",
                        serviceName = "ECC",
                        pin = effectivePin,
                        extraParams = JSONArray().apply {
                            put(JSONObject().put("key", "PRECONDITIONING").put("value", if (turnOn) "START" else "STOP"))
                            put(JSONObject().put("key", "TARGET_TEMPERATURE_CELSIUS").put("value", (clampedTemp * 10).roundToInt().toString()))
                        }
                    )
                    ConnectedVehicleBrand.MERCEDES -> vin.isNotBlank() && ensureValidMercedesToken(bridge) && executeMercedesRemoteCommand(
                        vin = vin,
                        commandPath = if (turnOn) "precond/start" else "precond/stop",
                        pin = effectivePin
                    )
                    ConnectedVehicleBrand.TESLA -> vin.isNotBlank() && executeTeslaFleetCommand(
                        accessToken = bridge.teslaAccessToken.ifBlank { tile.secretCode },
                        vin = vin,
                        commandEndpoint = if (turnOn) "auto_conditioning_start" else "auto_conditioning_stop"
                    )
                    else -> {
                        if (bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank() && tile.entityId.isNotBlank()) {
                            SmartHomeApiClient.callHomeAssistantService(
                                haBaseUrl = bridge.haUrl,
                                haToken = bridge.haToken,
                                domain = "climate",
                                service = if (turnOn) "turn_on" else "turn_off",
                                payloadJson = JSONObject().put("entity_id", tile.entityId.replace("lock.", "climate."))
                            )
                        } else false
                    }
                }
                val msg = if (turnOn) {
                    "Started ${tile.title} climate pre-conditioning (${"%.1f".format(clampedTemp)}\u00B0C)."
                } else {
                    "Stopped ${tile.title} climate pre-conditioning."
                }
                return ok to msg
            }

            override suspend fun setCharging(
                context: Context,
                bridge: BridgeSettings,
                tile: SmartTile,
                startCharge: Boolean
            ): Pair<Boolean, String> {
                val vin = DeviceRepository.sanitizeRealVin(tile.vehicleVin.ifBlank { tile.serialNumber })
                val effectivePin = tile.secretCode
                    .ifBlank { if (brand == ConnectedVehicleBrand.JAGUAR) bridge.jaguarPin else bridge.mercedesPin }

                val ok = when (brand) {
                    ConnectedVehicleBrand.JAGUAR -> vin.isNotBlank() && ensureValidJaguarToken(bridge) && executeJlrRemoteCommand(
                        vin = vin,
                        servicePath = "chargeProfile",
                        serviceName = "CP",
                        pin = effectivePin,
                        extraParams = JSONArray().put(
                            JSONObject().put("key", "CHARGE_NOW_SETTING").put("value", if (startCharge) "FORCE_ON" else "FORCE_OFF")
                        )
                    )
                    ConnectedVehicleBrand.MERCEDES -> vin.isNotBlank() && ensureValidMercedesToken(bridge) && executeMercedesRemoteCommand(
                        vin = vin,
                        commandPath = if (startCharge) "charge/start" else "charge/stop",
                        pin = effectivePin
                    )
                    ConnectedVehicleBrand.TESLA -> vin.isNotBlank() && executeTeslaFleetCommand(
                        accessToken = bridge.teslaAccessToken.ifBlank { tile.secretCode },
                        vin = vin,
                        commandEndpoint = if (startCharge) "charge_start" else "charge_stop"
                    )
                    else -> {
                        if (bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank() && tile.entityId.isNotBlank()) {
                            SmartHomeApiClient.callHomeAssistantService(
                                haBaseUrl = bridge.haUrl,
                                haToken = bridge.haToken,
                                domain = "switch",
                                service = if (startCharge) "turn_on" else "turn_off",
                                payloadJson = JSONObject().put("entity_id", tile.entityId.replace("lock.", "switch."))
                            )
                        } else false
                    }
                }
                val msg = if (startCharge) "Started EV charging on ${tile.title}." else "Paused EV charging on ${tile.title}."
                return ok to msg
            }

            override suspend fun triggerHonkAndFlash(
                context: Context,
                bridge: BridgeSettings,
                tile: SmartTile
            ): Pair<Boolean, String> {
                val vin = DeviceRepository.sanitizeRealVin(tile.vehicleVin.ifBlank { tile.serialNumber })
                val effectivePin = tile.secretCode
                    .ifBlank { if (brand == ConnectedVehicleBrand.JAGUAR) bridge.jaguarPin else bridge.mercedesPin }

                val ok = when (brand) {
                    ConnectedVehicleBrand.JAGUAR -> vin.isNotBlank() && ensureValidJaguarToken(bridge) && executeJlrRemoteCommand(
                        vin = vin,
                        servicePath = "honkBlink",
                        serviceName = "HBLF",
                        pin = effectivePin
                    )
                    ConnectedVehicleBrand.MERCEDES -> vin.isNotBlank() && ensureValidMercedesToken(bridge) && executeMercedesRemoteCommand(
                        vin = vin,
                        commandPath = "sigpos/start",
                        pin = effectivePin
                    )
                    ConnectedVehicleBrand.TESLA -> vin.isNotBlank() && executeTeslaFleetCommand(
                        accessToken = bridge.teslaAccessToken.ifBlank { tile.secretCode },
                        vin = vin,
                        commandEndpoint = "flash_lights"
                    )
                    else -> false
                }
                return ok to "Triggered horn & hazard flash on ${tile.title}."
            }
        }

    private val adapterRegistry: Map<ConnectedVehicleBrand, VehicleProviderAdapter> =
        ConnectedVehicleBrand.entries.associateWith { buildBrandAdapter(it) }

    fun getAdapter(brand: ConnectedVehicleBrand): VehicleProviderAdapter =
        adapterRegistry[brand] ?: buildBrandAdapter(brand)

    fun resolveAdapterForTile(tile: SmartTile): VehicleProviderAdapter =
        getAdapter(ConnectedVehicleBrand.fromTileOrKey(tile))

    suspend fun loginAndDiscoverVehiclesByBrand(
        context: Context,
        bridge: BridgeSettings,
        brand: ConnectedVehicleBrand,
        email: String = "",
        passwordOrToken: String = "",
        pin: String = "",
        vinOverride: String = ""
    ): Result<List<SmartTile>> = withContext(Dispatchers.IO) {
        when (brand) {
            ConnectedVehicleBrand.JAGUAR -> loginAndDiscoverJaguarVehicles(
                context = context,
                bridge = bridge,
                email = email.ifBlank { bridge.jaguarEmail },
                password = passwordOrToken.ifBlank { bridge.jaguarPassword },
                pin = pin.ifBlank { bridge.jaguarPin },
                vinOverride = vinOverride.ifBlank { bridge.jaguarVin }
            )
            ConnectedVehicleBrand.MERCEDES -> loginAndDiscoverMercedesVehicles(
                context = context,
                bridge = bridge,
                email = email.ifBlank { bridge.mercedesEmail },
                password = passwordOrToken.ifBlank { bridge.mercedesPassword },
                pin = pin.ifBlank { bridge.mercedesPin },
                vinOverride = vinOverride.ifBlank { bridge.mercedesVin }
            )
            else -> {
                val cleanVin = DeviceRepository.sanitizeRealVin(vinOverride)
                val probeTile = SmartTile(
                    title = brand.displayName.substringBefore(" "),
                    roomOrSubtitle = "Driveway",
                    type = TileType.JAGUAR_VEHICLE,
                    secretCode = passwordOrToken.trim(),
                    serialNumber = cleanVin,
                    vehicleVin = cleanVin,
                    activeProviderId = brand.providerId.id
                )
                val synced = getAdapter(brand).fetchTelemetry(context, bridge, probeTile)
                if (synced != null) {
                    Result.success(listOf(synced))
                } else {
                    Result.failure(Exception("Could not discover a ${brand.displayName} vehicle. Check your credentials or Home Assistant bridge."))
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 3. LIVE VEHICLE TELEMETRY POLLING & STATE SYNC (ALL 11 BRANDS)
    // -----------------------------------------------------------------------
    suspend fun syncVehicleStates(
        context: Context,
        bridge: BridgeSettings,
        tiles: MutableList<SmartTile>
    ): Boolean = withContext(Dispatchers.IO) {
        val vehicleIndices = tiles.indices.filter { tiles[it].isVehicleTile || tiles[it].deviceClass == DeviceClass.VEHICLE }
        if (vehicleIndices.isEmpty()) return@withContext false

        var anyMutated = false
        val startMs = System.currentTimeMillis()
        var onlineVehicles = 0
        var offlineVehicles = 0

        for (idx in vehicleIndices) {
            val currentTile = tiles.getOrNull(idx) ?: continue
            val vinKey = DeviceRepository.sanitizeRealVin(
                currentTile.vehicleVin.ifBlank { currentTile.serialNumber }
            )
            val adapter = resolveAdapterForTile(currentTile)
            val liveTile = adapter.fetchTelemetry(context, bridge, currentTile)

            val regPlate = currentTile.vehicleRegPlate.ifBlank {
                if (adapter.brand == ConnectedVehicleBrand.MERCEDES) bridge.mercedesRegPlate else bridge.jaguarRegPlate
            }.trim().uppercase(Locale.UK)

            val dvlaMotInfo = resolveUkDvlaAndMotStatus(
                regPlate = regPlate,
                vehicleTitle = currentTile.title,
                existingTaxDue = currentTile.vehicleTaxDueDateIso,
                existingMotExpiry = currentTile.vehicleMotExpiryDateIso,
                existingAdvisories = currentTile.vehicleMotAdvisories
            )

            if (liveTile != null) {
                onlineVehicles++
                withContext(Dispatchers.Main) {
                    if (idx in tiles.indices) {
                        val updated = currentTile.copy(
                            title = if (currentTile.title in listOf("Jaguar", "Jaguar I-PACE", "Mercedes-Benz", "Connected Vehicle") && liveTile.title.isNotBlank()) liveTile.title else currentTile.title,
                            isOnline = liveTile.isOnline,
                            vehicleLocked = liveTile.vehicleLocked,
                            isActive = liveTile.vehicleLocked,
                            vehicleFuelOrBatteryPct = liveTile.vehicleFuelOrBatteryPct,
                            vehicleRangeMiles = liveTile.vehicleRangeMiles,
                            vehicleIsEv = liveTile.vehicleIsEv,
                            vehicleIsCharging = liveTile.vehicleIsCharging,
                            vehicleClimateActive = liveTile.vehicleClimateActive,
                            vehicleOdometerMiles = liveTile.vehicleOdometerMiles,
                            vehicleLocationText = liveTile.vehicleLocationText.ifBlank { currentTile.vehicleLocationText },
                            vehicleVin = liveTile.vehicleVin.ifBlank { vinKey },
                            vehicleRegPlate = regPlate,
                            vehicleTaxStatus = dvlaMotInfo.taxStatus,
                            vehicleTaxDueDateIso = dvlaMotInfo.taxDueDateIso,
                            vehicleMotStatus = dvlaMotInfo.motStatus,
                            vehicleMotExpiryDateIso = dvlaMotInfo.motExpiryDateIso,
                            vehicleMotAdvisories = dvlaMotInfo.advisories,
                            vehicleRecallsCount = dvlaMotInfo.recallsCount,
                            vehicleUlezCompliant = dvlaMotInfo.ulezCompliant,
                            vehicleTpmsFrontLeftPsi = liveTile.vehicleTpmsFrontLeftPsi,
                            vehicleTpmsFrontRightPsi = liveTile.vehicleTpmsFrontRightPsi,
                            vehicleTpmsRearLeftPsi = liveTile.vehicleTpmsRearLeftPsi,
                            vehicleTpmsRearRightPsi = liveTile.vehicleTpmsRearRightPsi,
                            vehicle12vBatteryVolts = liveTile.vehicle12vBatteryVolts,
                            vehicleDoorsWindowsAllClosed = liveTile.vehicleDoorsWindowsAllClosed,
                            vehicleNextServiceMiles = liveTile.vehicleNextServiceMiles,
                            serialNumber = liveTile.serialNumber.ifBlank { vinKey },
                            entityId = liveTile.entityId.ifBlank { currentTile.entityId },
                            activeProviderId = liveTile.activeProviderId.ifBlank { adapter.brand.providerId.id },
                            capabilities = liveTile.capabilities.ifEmpty {
                                discoverCapabilitiesFromVehiclePayload(
                                    rawAttributes = emptyMap(),
                                    isLocked = liveTile.vehicleLocked,
                                    climateActive = liveTile.vehicleClimateActive,
                                    isCharging = liveTile.vehicleIsCharging,
                                    isEv = liveTile.vehicleIsEv,
                                    targetTempC = currentTile.vehicleTargetClimateTempC,
                                    socPct = liveTile.vehicleFuelOrBatteryPct,
                                    rangeMiles = liveTile.vehicleRangeMiles,
                                    odometerMiles = liveTile.vehicleOdometerMiles,
                                    battery12vVolts = liveTile.vehicle12vBatteryVolts,
                                    tpmsFlPsi = liveTile.vehicleTpmsFrontLeftPsi,
                                    tpmsFrPsi = liveTile.vehicleTpmsFrontRightPsi,
                                    tpmsRlPsi = liveTile.vehicleTpmsRearLeftPsi,
                                    tpmsRrPsi = liveTile.vehicleTpmsRearRightPsi
                                )
                            }
                        )
                        if (updated != currentTile) {
                            tiles[idx] = updated
                            anyMutated = true
                        }
                    }
                }
            } else if (regPlate.isNotBlank() && currentTile.vehicleMotExpiryDateIso.isBlank()) {
                withContext(Dispatchers.Main) {
                    if (idx in tiles.indices) {
                        tiles[idx] = currentTile.copy(
                            vehicleRegPlate = regPlate,
                            vehicleTaxStatus = dvlaMotInfo.taxStatus,
                            vehicleTaxDueDateIso = dvlaMotInfo.taxDueDateIso,
                            vehicleMotStatus = dvlaMotInfo.motStatus,
                            vehicleMotExpiryDateIso = dvlaMotInfo.motExpiryDateIso,
                            vehicleMotAdvisories = dvlaMotInfo.advisories,
                            vehicleUlezCompliant = dvlaMotInfo.ulezCompliant
                        )
                        anyMutated = true
                    }
                }
            } else {
                offlineVehicles++
                if (currentTile.capabilities.isEmpty()) {
                    val populatedCaps = defaultCapabilitiesForVehicle(
                        type = currentTile.type,
                        isLocked = currentTile.vehicleLocked,
                        climateActive = currentTile.vehicleClimateActive,
                        isCharging = currentTile.vehicleIsCharging,
                        targetTempC = currentTile.vehicleTargetClimateTempC,
                        socPct = currentTile.vehicleFuelOrBatteryPct,
                        rangeMiles = currentTile.vehicleRangeMiles
                    )
                    withContext(Dispatchers.Main) {
                        if (idx in tiles.indices) {
                            tiles[idx] = currentTile.copy(capabilities = populatedCaps)
                            anyMutated = true
                        }
                    }
                }
            }
        }

        val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(18L)
        JarvisLiveBridge.updateIntegrationDeviceCounts(
            integrationKey = "VEHICLES",
            displayName = "Connected Vehicles",
            onlineCount = onlineVehicles,
            offlineCount = offlineVehicles,
            tokenValid = onlineVehicles > 0 || offlineVehicles == 0
        )
        JarvisLiveBridge.recordCommandLatency(
            integrationKey = "VEHICLES",
            displayName = "Connected Vehicles",
            latencyMs = latency,
            succeeded = onlineVehicles > 0
        )

        if (anyMutated) {
            withContext(Dispatchers.Main) {
                DeviceRepository.saveTiles(context, tiles)
            }
        }
        anyMutated
    }

    // -----------------------------------------------------------------------
    // 4. REMOTE VEHICLE COMMANDS ROUTED VIA VEHICLEPROVIDERADAPTER
    // -----------------------------------------------------------------------
    suspend fun setVehicleLocked(
        context: Context,
        bridge: BridgeSettings,
        tile: SmartTile,
        locked: Boolean,
        pinOverride: String = ""
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val tileWithPin = if (pinOverride.isNotBlank()) tile.copy(secretCode = pinOverride.trim()) else tile
        val adapter = resolveAdapterForTile(tileWithPin)
        val (cloudDispatched, rawMsg) = adapter.setLocked(context, bridge, tileWithPin, locked)

        val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(24L)
        JarvisLiveBridge.recordCommandLatency(
            integrationKey = "VEHICLES",
            displayName = adapter.brand.displayName,
            latencyMs = latency,
            succeeded = cloudDispatched
        )

        val actionWord = if (locked) "Locked" else "Unlocked"
        val statusMsg = if (cloudDispatched) {
            rawMsg
        } else if (!tile.isConfiguredAndLinked) {
            "${tile.title} is not linked yet \u2014 sign in to ${adapter.brand.displayName} to send remote commands."
        } else {
            rawMsg
        }

        DeviceRepository.logAuditEvent(
            context = context,
            actorRole = bridge.activeUserRole,
            actionType = if (locked) "VEHICLE_LOCK" else "VEHICLE_UNLOCK",
            targetName = tile.title,
            detail = "$actionWord ${tile.title} via ${adapter.brand.displayName} (${latency}ms, cloud=$cloudDispatched)",
            succeeded = cloudDispatched
        )
        JarvisLiveBridge.emitPlatformEvent(
            JarvisPlatformEvent(
                category = JarvisEventCategory.VEHICLE,
                sourceDeviceId = tile.id,
                sourceDeviceTitle = tile.title,
                roomName = tile.roomOrSubtitle.ifBlank { "Driveway" },
                eventType = if (locked) "VEHICLE_LOCKED" else "VEHICLE_UNLOCKED",
                summary = statusMsg
            )
        )
        cloudDispatched to statusMsg
    }

    suspend fun setVehicleClimate(
        context: Context,
        bridge: BridgeSettings,
        tile: SmartTile,
        active: Boolean,
        targetTempC: Float = tile.vehicleTargetClimateTempC
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val tileWithTemp = tile.copy(vehicleTargetClimateTempC = targetTempC.coerceIn(16f, 28f))
        val adapter = resolveAdapterForTile(tileWithTemp)
        val (dispatchedOk, msg) = adapter.setClimate(context, bridge, tileWithTemp, active)

        val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(22L)
        JarvisLiveBridge.recordCommandLatency(
            integrationKey = "VEHICLES",
            displayName = adapter.brand.displayName,
            latencyMs = latency,
            succeeded = dispatchedOk
        )
        DeviceRepository.logAuditEvent(
            context = context,
            actorRole = bridge.activeUserRole,
            actionType = "VEHICLE_CLIMATE",
            targetName = tile.title,
            detail = msg,
            succeeded = dispatchedOk
        )
        JarvisLiveBridge.emitPlatformEvent(
            JarvisPlatformEvent(
                category = JarvisEventCategory.VEHICLE,
                sourceDeviceId = tile.id,
                sourceDeviceTitle = tile.title,
                roomName = tile.roomOrSubtitle.ifBlank { "Driveway" },
                eventType = if (active) "VEHICLE_CLIMATE_ON" else "VEHICLE_CLIMATE_OFF",
                summary = msg
            )
        )
        dispatchedOk to msg
    }

    suspend fun setVehicleCharging(
        context: Context,
        bridge: BridgeSettings,
        tile: SmartTile,
        enableCharging: Boolean
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val adapter = resolveAdapterForTile(tile)
        val (dispatchedOk, msg) = adapter.setCharging(context, bridge, tile, enableCharging)

        val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(20L)
        JarvisLiveBridge.recordCommandLatency(
            integrationKey = "VEHICLES",
            displayName = adapter.brand.displayName,
            latencyMs = latency,
            succeeded = dispatchedOk
        )
        JarvisLiveBridge.emitPlatformEvent(
            JarvisPlatformEvent(
                category = JarvisEventCategory.VEHICLE,
                sourceDeviceId = tile.id,
                sourceDeviceTitle = tile.title,
                roomName = tile.roomOrSubtitle.ifBlank { "Driveway" },
                eventType = if (enableCharging) "VEHICLE_CHARGE_START" else "VEHICLE_CHARGE_STOP",
                summary = msg
            )
        )
        dispatchedOk to msg
    }

    suspend fun triggerHonkAndFlash(
        context: Context,
        bridge: BridgeSettings,
        tile: SmartTile
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val adapter = resolveAdapterForTile(tile)
        val (dispatchedOk, msg) = adapter.triggerHonkAndFlash(context, bridge, tile)

        val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(19L)
        JarvisLiveBridge.recordCommandLatency(
            integrationKey = "VEHICLES",
            displayName = adapter.brand.displayName,
            latencyMs = latency,
            succeeded = dispatchedOk
        )
        DeviceRepository.logAuditEvent(
            context = context,
            actorRole = bridge.activeUserRole,
            actionType = "VEHICLE_HONK_FLASH",
            targetName = tile.title,
            detail = msg,
            succeeded = dispatchedOk
        )
        JarvisLiveBridge.emitPlatformEvent(
            JarvisPlatformEvent(
                category = JarvisEventCategory.VEHICLE,
                sourceDeviceId = tile.id,
                sourceDeviceTitle = tile.title,
                roomName = tile.roomOrSubtitle.ifBlank { "Driveway" },
                eventType = "VEHICLE_HONK_FLASH",
                summary = msg
            )
        )
        dispatchedOk to msg
    }

    private fun executeJlrRemoteCommand(
        vin: String,
        servicePath: String,
        serviceName: String,
        pin: String,
        extraParams: JSONArray? = null
    ): Boolean {
        if (JarvisLiveBridge.isCircuitOpen("JAGUAR_CLOUD")) {
            throw CircuitBreakerOpenException("Cloud Service Temporarily Unavailable")
        }
        val cleanVin = DeviceRepository.sanitizeRealVin(vin)
        if (cleanVin.isBlank()) return false
        return try {
            val userSegment = jlrUserId.ifBlank { "me" }
            val tokenUrl = URL("$jlrActiveIf9Url/$cleanVin/users/$userSegment/authenticate")
            val authPayload = JSONObject().apply {
                put("serviceName", serviceName)
                put("pin", pin)
            }.toString()
            val authConn = (tokenUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $jlrAccessToken")
                setRequestProperty("X-Device-Id", jlrDeviceId)
                setRequestProperty("Content-Type", "application/vnd.wirelesscar.ngtp.if9.AuthenticateRequest-v2+json")
            }
            OutputStreamWriter(authConn.outputStream).use { it.write(authPayload); it.flush() }
            val cmdToken = if (authConn.responseCode in 200..299) {
                val resp = authConn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(resp).optString("token", "")
            } else ""
            authConn.disconnect()
            if (cmdToken.isBlank()) return false

            val cmdBody = JSONObject().apply {
                put("token", cmdToken)
                if (extraParams != null) {
                    put("serviceParameters", extraParams)
                }
            }.toString()

            val cmdConn = (URL("$jlrActiveIf9Url/$cleanVin/$servicePath").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $jlrAccessToken")
                setRequestProperty("X-Device-Id", jlrDeviceId)
                setRequestProperty("Content-Type", "application/vnd.wirelesscar.ngtp.if9.Preamble-v3+json")
            }
            OutputStreamWriter(cmdConn.outputStream).use { it.write(cmdBody); it.flush() }
            val code = cmdConn.responseCode
            val respBody = (if (code in 200..299) cmdConn.inputStream else cmdConn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            cmdConn.disconnect()
            if (code !in 200..299) return false
            val failureStatus = runCatching {
                JSONObject(respBody).optString("failureDescription", "").isNotBlank()
            }.getOrDefault(false)
            !failureStatus
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
            false
}
    }

    private fun executeMercedesRemoteCommand(
        vin: String,
        commandPath: String,
        pin: String
    ): Boolean {
        if (JarvisLiveBridge.isCircuitOpen("MERCEDES_CLOUD")) {
            throw CircuitBreakerOpenException("Cloud Service Temporarily Unavailable")
        }
        val cleanVin = DeviceRepository.sanitizeRealVin(vin)
        if (cleanVin.isBlank()) return false
        return try {
            val url = URL("$mbActiveBffHost/v1/vehicle/$cleanVin/$commandPath")
            val body = JSONObject().apply {
                if (pin.isNotBlank()) put("pin", pin)
            }.toString()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $mbAccessToken")
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body); it.flush() }
            val code = conn.responseCode
            val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) return false
            val hasError = runCatching {
                val json = JSONObject(respBody)
                json.optJSONArray("errors")?.length()?.let { it > 0 } ?: false
            }.getOrDefault(false)
            !hasError
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
            false
}
    }

    data class UkDvlaMotSummary(
        val taxStatus: String = "Taxed",
        val taxDueDateIso: String = "",
        val motStatus: String = "Valid",
        val motExpiryDateIso: String = "",
        val advisories: List<String> = emptyList(),
        val recallsCount: Int = 0,
        val ulezCompliant: Boolean = true
    )

    fun resolveUkDvlaAndMotStatus(
        regPlate: String,
        @Suppress("UNUSED_PARAMETER") vehicleTitle: String,
        existingTaxDue: String = "",
        existingMotExpiry: String = "",
        existingAdvisories: List<String> = emptyList()
    ): UkDvlaMotSummary {
        val cleanReg = regPlate.replace(" ", "").uppercase(Locale.UK)
        val hasTaxDate = existingTaxDue.isNotBlank()
        val hasMotDate = existingMotExpiry.isNotBlank()
        return UkDvlaMotSummary(
            taxStatus = if (hasTaxDate) "Taxed" else if (cleanReg.isNotBlank()) "Awaiting DVLA Sync" else "Not Configured",
            taxDueDateIso = existingTaxDue.trim(),
            motStatus = if (hasMotDate) "Valid" else if (cleanReg.isNotBlank()) "Awaiting DVSA Sync" else "Not Configured",
            motExpiryDateIso = existingMotExpiry.trim(),
            advisories = existingAdvisories,
            recallsCount = 0,
            ulezCompliant = true
        )
    }
}
