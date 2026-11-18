package com.jarvis.smarthomelauncher.integrations

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

data class PooledHttpResponse(
    val code: Int,
    val body: String,
    val setCookies: List<String> = emptyList()
) {
    val isSuccessful: Boolean
        get() = code in 200..299
}

object SharedHttpClient {
    val instance: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectionPool(okhttp3.ConnectionPool(16, 5, java.util.concurrent.TimeUnit.MINUTES))
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val webSocketInstance: okhttp3.OkHttpClient by lazy {
        instance.newBuilder()
            .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    fun clientWithTimeouts(
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        writeTimeoutMs: Int = readTimeoutMs
    ): okhttp3.OkHttpClient = instance.newBuilder()
        .connectTimeout(connectTimeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(writeTimeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    fun requestText(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String = "application/json",
        connectTimeoutMs: Int = 4500,
        readTimeoutMs: Int = 5000
    ): PooledHttpResponse {
        val upperMethod = method.uppercase(Locale.US)
        val reqBody = when {
            body != null -> okhttp3.RequestBody.create(null, body.toByteArray(Charsets.UTF_8))
            upperMethod == "POST" || upperMethod == "PUT" -> okhttp3.RequestBody.create(null, ByteArray(0))
            else -> null
        }
        val reqBuilder = okhttp3.Request.Builder()
            .url(url)
            .method(upperMethod, reqBody)
        for ((k, v) in headers) {
            if (k.isNotBlank()) reqBuilder.header(k, v)
        }
        if (body != null && headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            reqBuilder.header("Content-Type", contentType)
        }
        return clientWithTimeouts(connectTimeoutMs, readTimeoutMs)
            .newCall(reqBuilder.build())
            .execute()
            .use { resp ->
                PooledHttpResponse(
                    code = resp.code,
                    body = resp.body?.string().orEmpty(),
                    setCookies = resp.headers("Set-Cookie")
                )
            }
    }

    fun downloadBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 2500,
        readTimeoutMs: Int = 2500
    ): ByteArray? {
        val reqBuilder = okhttp3.Request.Builder().url(url).get()
        for ((k, v) in headers) {
            if (k.isNotBlank()) reqBuilder.header(k, v)
        }
        return clientWithTimeouts(connectTimeoutMs, readTimeoutMs)
            .newCall(reqBuilder.build())
            .execute()
            .use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
    }
}

// ---------------------------------------------------------------------------
// LIVE WEATHER API CLIENT (Auto-Detects User Coordinates + 7-Day Forecast)
// ---------------------------------------------------------------------------
object WeatherApiClient {
    @Volatile
    private var cachedLatitude: Double? = null

    @Volatile
    private var cachedLongitude: Double? = null

    @Volatile
    private var lastGeoLookupMs: Long = 0L

    @SuppressLint("MissingPermission")
    private fun resolveDeviceOrIpCoordinates(context: Context?): Pair<Double, Double>? {
        val existingLat = cachedLatitude
        val existingLon = cachedLongitude
        if (existingLat != null && existingLon != null &&
            System.currentTimeMillis() - lastGeoLookupMs < 6 * 3600_000L
        ) {
            return existingLat to existingLon
        }

        // 1. Try Android last-known location if permission is already granted (F-15)
        val ctx = ClaraApplication.appContextOrNull(context)
        if (ctx != null) {
            try {
                val lm = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                val providers = lm?.getProviders(true).orEmpty()
                for (provider in providers) {
                    val loc = runCatching { lm?.getLastKnownLocation(provider) }.getOrNull()
                    if (loc != null && (loc.latitude != 0.0 || loc.longitude != 0.0)) {
                        cachedLatitude = loc.latitude
                        cachedLongitude = loc.longitude
                        lastGeoLookupMs = System.currentTimeMillis()
                        return loc.latitude to loc.longitude
                    }
                }
            } catch (e: Exception) {
                ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = "WEATHER_LOCATION",
                    operationStage = "resolveDeviceOrIpCoordinates.gps",
                    logToDiscoveryRecorder = false
                )
            }
        }

        // 2. Zero-permission automatic IP Geolocation lookup
        val geoEndpoints = listOf(
            "https://ipwho.is/",
            "https://ipapi.co/json/",
            "https://freeipapi.com/api/json"
        )
        for (endpoint in geoEndpoints) {
            try {
                val resp = SharedHttpClient.requestText(
                    url = endpoint,
                    headers = mapOf("User-Agent" to "Mozilla/5.0"),
                    connectTimeoutMs = 3500,
                    readTimeoutMs = 3500
                )
                if (resp.isSuccessful && resp.body.isNotBlank()) {
                    val json = JSONObject(resp.body)
                    val lat = json.optDouble("latitude", Double.NaN)
                    val lon = json.optDouble("longitude", Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) {
                        cachedLatitude = lat
                        cachedLongitude = lon
                        lastGeoLookupMs = System.currentTimeMillis()
                        return lat to lon
                    }
                }
            } catch (e: Exception) {
                ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = "WEATHER_GEO_CLOUD",
                    operationStage = "resolveDeviceOrIpCoordinates.http",
                    logToDiscoveryRecorder = false
                )
            }
        }

        if (existingLat != null && existingLon != null) {
            return existingLat to existingLon
        }
        return inferCoordinatesFromTimeZone()
    }

    private fun inferCoordinatesFromTimeZone(): Pair<Double, Double> {
        val tzId = java.util.TimeZone.getDefault().id.lowercase(Locale.US)
        return when {
            tzId.contains("new_york") || tzId.contains("eastern") -> 40.7128 to -74.0060
            tzId.contains("chicago") || tzId.contains("central") -> 41.8781 to -87.6298
            tzId.contains("denver") || tzId.contains("mountain") -> 39.7392 to -104.9903
            tzId.contains("los_angeles") || tzId.contains("pacific") -> 34.0522 to -118.2437
            tzId.contains("paris") || tzId.contains("berlin") || tzId.contains("amsterdam") -> 48.8566 to 2.3522
            tzId.contains("sydney") || tzId.contains("melbourne") -> -33.8688 to 151.2093
            tzId.contains("tokyo") -> 35.6762 to 139.6503
            else -> 51.5074 to -0.1278
        }
    }

    suspend fun refreshWeather(context: Context? = null) = withContext(Dispatchers.IO) {
        try {
            val coords = resolveDeviceOrIpCoordinates(context) ?: inferCoordinatesFromTimeZone()
            val (lat, lon) = coords
            val resp = SharedHttpClient.requestText(
                url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current_weather=true" +
                        "&daily=weathercode,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                        "&timezone=auto&forecast_days=7",
                connectTimeoutMs = 5000,
                readTimeoutMs = 5000
            )
            if (resp.isSuccessful && resp.body.isNotBlank()) {
                val json = JSONObject(resp.body)
                val current = json.optJSONObject("current_weather")
                val daily = json.optJSONObject("daily")

                val parsedDailyList = mutableListOf<DailyWeatherForecast>()
                if (daily != null) {
                    val timeArr = daily.optJSONArray("time") ?: JSONArray()
                    val codeArr = daily.optJSONArray("weathercode") ?: JSONArray()
                    val maxArr = daily.optJSONArray("temperature_2m_max") ?: JSONArray()
                    val minArr = daily.optJSONArray("temperature_2m_min") ?: JSONArray()
                    val precipArr = daily.optJSONArray("precipitation_probability_max") ?: JSONArray()

                    val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val dayNameFmt = SimpleDateFormat("EEE", Locale.getDefault())
                    val dateSubFmt = SimpleDateFormat("d MMM", Locale.getDefault())

                    val count = timeArr.length().coerceAtMost(7)
                    for (i in 0 until count) {
                        val dateStr = timeArr.optString(i, "")
                        val parsedDate = runCatching { inFmt.parse(dateStr) }.getOrNull()
                        val dayLabel = when (i) {
                            0 -> "Today"
                            1 -> "Tomorrow"
                            else -> if (parsedDate != null) dayNameFmt.format(parsedDate) else "Day ${i + 1}"
                        }
                        val dateSubtitle = if (parsedDate != null) dateSubFmt.format(parsedDate) else dateStr
                        val dCode = codeArr.optInt(i, 1)
                        val dMax = maxArr.optDouble(i, 16.0).roundToInt()
                        val dMin = minArr.optDouble(i, 9.0).roundToInt()
                        val dPrecip = precipArr.optInt(i, 0).coerceIn(0, 100)

                        parsedDailyList.add(
                            DailyWeatherForecast(
                                dayLabel = dayLabel,
                                dateSubtitle = dateSubtitle,
                                conditionText = weatherCodeToLabel(dCode),
                                maxTempCelsius = dMax,
                                minTempCelsius = dMin,
                                precipProbabilityPct = dPrecip
                            )
                        )
                    }
                }

                if (current != null) {
                    val temp = current.optDouble("temperature", 15.0).roundToInt()
                    val code = current.optInt("weathercode", 1)
                    val isDay = current.optInt("is_day", 1) == 1
                    val lowTemp = parsedDailyList.firstOrNull()?.minTempCelsius ?: (temp - 6)
                    JarvisLiveBridge.weatherFlow.value = WeatherInfo(
                        temperatureCelsius = temp,
                        lowTemperatureCelsius = lowTemp,
                        conditionText = weatherCodeToLabel(code),
                        isDay = isDay,
                        dailyForecast = if (parsedDailyList.isNotEmpty()) {
                            parsedDailyList
                        } else {
                            buildFallbackSevenDayForecast(temp, weatherCodeToLabel(code))
                        }
                    )
                }
            }
        } catch (e: Exception) {
            ErrorClassifier.recordHandledException(
                t = e,
                transportSource = "OPEN_METEO_CLOUD",
                operationStage = "refreshWeather",
                logToDiscoveryRecorder = false
            )
        }
    }

    fun buildFallbackSevenDayForecast(baseTemp: Int, baseCondition: String): List<DailyWeatherForecast> {
        val cal = Calendar.getInstance()
        val dayNameFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val dateSubFmt = SimpleDateFormat("d MMM", Locale.getDefault())
        val offsets = listOf(0, 1, -1, 2, 0, -2, 1)
        val conditions = listOf(
            baseCondition,
            "Partly Cloudy",
            "Clear Sky",
            "Light Rain",
            "Partly Cloudy",
            "Clear Sky",
            "Cloudy"
        )
        val precips = listOf(10, 20, 5, 65, 15, 10, 25)
        return (0 until 7).map { idx ->
            val dayDate = cal.time
            val dayLabel = when (idx) {
                0 -> "Today"
                1 -> "Tomorrow"
                else -> dayNameFmt.format(dayDate)
            }
            val dateSub = dateSubFmt.format(dayDate)
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val hi = baseTemp + offsets[idx]
            val lo = hi - 6
            DailyWeatherForecast(
                dayLabel = dayLabel,
                dateSubtitle = dateSub,
                conditionText = conditions[idx],
                maxTempCelsius = hi,
                minTempCelsius = lo,
                precipProbabilityPct = precips[idx]
            )
        }
    }

    fun weatherCodeToLabel(code: Int): String = when (code) {
        0 -> "Clear Sky"
        1, 2 -> "Partly Cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55, 61, 63, 65 -> "Rain"
        71, 73, 75 -> "Snow"
        80, 81, 82 -> "Showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Cloudy"
    }
}

// ---------------------------------------------------------------------------
// MULTI-PROTOCOL LAN AUTO-SCANNER (GOVEE, TWINKLY, LYTMI, TUYA, MATTER, HA),
// CAPABILITY INTROSPECTION, RECOVERY ENGINE DELEGATION & DVR SNAPSHOTS
// ---------------------------------------------------------------------------
object SmartHomeApiClient {
    private const val TAG = "ClaraSmartHomeApi"
    private const val HA_CIRCUIT_BREAKER_COOLDOWN_MS = 30_000L

    private val tuyaUdpAesKey by lazy {
        MessageDigest.getInstance("MD5").digest("yGAdlopoPVldABfn".toByteArray(Charsets.UTF_8))
    }

    private val goveeModelByDeviceId = ConcurrentHashMap<String, String>()
    private val twinklyTokenByIp = ConcurrentHashMap<String, String>()

    // Home Assistant Circuit Breaker state (MED-02 fix)
    @Volatile
    private var haCircuitOpenUntilMs: Long = 0L

    @Volatile
    private var haConsecutiveFailures: Int = 0

    private var haWebSocket: okhttp3.WebSocket? = null

    fun disconnectHaWebSocket() {
        haWebSocket?.cancel()
        haWebSocket = null
    }

    suspend fun syncLiveShellyStates(tiles: MutableList<SmartTile>) {
        val shellyTiles = tiles.filter { it.type == TileType.SHELLY_RELAY }
        if (shellyTiles.isEmpty()) return
        coroutineScope {
            shellyTiles.map { tile ->
                async(Dispatchers.IO) {
                    val ip = tile.ipAddress.substringBefore(":")
                    if (ip.isNotBlank()) {
                        val verified = probeShellyHueOrTasmotaOnPort80(ip)
                        val isOnline = verified != null && verified.first == TileType.SHELLY_RELAY
                        val idx = tiles.indexOfFirst { it.id == tile.id }
                        if (idx != -1) {
                            tiles[idx] = tile.copy(isOnline = isOnline)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun syncLiveGoveeStates(bridge: BridgeSettings, tiles: MutableList<SmartTile>) {
        val goveeTiles = tiles.filter { it.type == TileType.GOVEE_LIGHT }
        if (goveeTiles.isEmpty()) return
        // Govee uses UDP multicast or cloud to discover
        val goveeDevices = discoverGoveeDevices(bridge, timeoutMs = 1500L)
        val onlineIps = goveeDevices.map { it.ipAddress }.toSet()
        for (i in tiles.indices) {
            val tile = tiles[i]
            if (tile.type == TileType.GOVEE_LIGHT) {
                tiles[i] = tile.copy(isOnline = onlineIps.contains(tile.ipAddress))
            }
        }
    }

    suspend fun syncLiveTwinklyStates(bridge: BridgeSettings, tiles: MutableList<SmartTile>) {
        val twinklyTiles = tiles.filter { it.type == TileType.TWINKLY_LIGHT }
        if (twinklyTiles.isEmpty()) return
        coroutineScope {
            twinklyTiles.map { tile ->
                async(Dispatchers.IO) {
                    val ip = tile.ipAddress.substringBefore(":")
                    if (ip.isNotBlank()) {
                        val verified = probeTwinklyGestaltOnPort80(ip)
                        val isOnline = verified != null
                        val idx = tiles.indexOfFirst { it.id == tile.id }
                        if (idx != -1) {
                            tiles[idx] = tile.copy(isOnline = isOnline)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun ensureHaWebSocket(bridge: BridgeSettings, context: Context) {
        if (bridge.haUrl.isBlank() || bridge.haToken.isBlank()) return
        if (haWebSocket != null) return

        if (haConsecutiveFailures > 0) {
            val backoffMs = (1000L * (1 shl minOf(haConsecutiveFailures, 5))) + (Math.random() * 500).toLong()
            kotlinx.coroutines.delay(backoffMs)
        }

        val url = bridge.haUrl.trim().removeSuffix("/") + "/api/websocket"
        val wsUrl = url.replace("http://", "ws://").replace("https://", "wss://")

        val request = okhttp3.Request.Builder().url(wsUrl).build()

        haWebSocket = SharedHttpClient.webSocketInstance.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                val authMsg = org.json.JSONObject().apply {
                    put("type", "auth")
                    put("access_token", bridge.haToken)
                }
                webSocket.send(authMsg.toString())
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                try {
                    val json = org.json.JSONObject(text)
                    val type = json.optString("type")
                    if (type == "auth_ok") {
                        haConsecutiveFailures = 0
                        val subMsg = org.json.JSONObject().apply {
                            put("id", 1)
                            put("type", "subscribe_events")
                            put("event_type", "state_changed")
                        }
                        webSocket.send(subMsg.toString())
                    } else if (type == "event") {
                        val event = json.optJSONObject("event")
                        val eventType = event?.optString("event_type")
                        if (eventType == "state_changed") {
                            val data = event.optJSONObject("data")
                            val entityId = data?.optString("entity_id")
                            val newState = data?.optJSONObject("new_state")
                            if (entityId != null && newState != null) {
                                val state = newState.optString("state", "")
                                val attrs = newState.optJSONObject("attributes") ?: org.json.JSONObject()
                                val domain = entityId.substringBefore(".")
                                val (extractedCaps, hasPower) = extractCapabilitiesFromHaAttributes(domain, state, attrs)
                                val friendlyName = attrs.optString("friendly_name", entityId.substringAfter("."))
                                val haResolution = CapabilityClassifier.resolve(extractedCaps, domain, 1, hasPower, friendlyName)
                                val levelOrBrightness = attrs.optDouble("brightness", -1.0).toFloat().takeIf { it >= 0f }
                                    ?: attrs.optDouble("percentage", -1.0).toFloat().takeIf { it >= 0f }
                                    ?: attrs.optDouble("volume_level", -1.0).toFloat().let { if (it >= 0f) it * 100f else 100f }
                                
                                val rawHaOnline = !state.equals("unavailable", true) && !state.equals("unknown", true)

                                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                    val tiles = DeviceRepository.loadTiles(context).toMutableList()
                                    var updated = false
                                    for (i in tiles.indices) {
                                        val tile = tiles[i]
                                        if (tile.entityId == entityId) {
                                            if (DeviceRepository.isTilePendingAckLocked(tile.id) ||
                                                tile.transactionState == CommandTransactionState.PENDING_ACK) {
                                                continue
                                            }
                                            
                                            val (mergedCaps, mergedHash) = DeviceRepository.mergeCapabilitiesWithSchemaEvolution(tile, extractedCaps)
                                            
                                            val candidateUpdate = when {
                                                tile.isTowerFanTile -> {
                                                    val computedSpeed = ((levelOrBrightness / 100f) * 6f).roundToInt().coerceIn(1, 6)
                                                    tile.copy(
                                                        isOnline = rawHaOnline,
                                                        isActive = state.equals("on", true),
                                                        volume = if (state.equals("on", true)) levelOrBrightness else tile.volume,
                                                        fanSpeedLevel = if (state.equals("on", true)) computedSpeed else tile.fanSpeedLevel,
                                                        capabilities = mergedCaps,
                                                        capabilitySchemaHash = mergedHash
                                                    )
                                                }
                                                tile.isThermostatTile -> tile.copy(
                                                    isOnline = rawHaOnline,
                                                    temperature = attrs.optDouble("current_temperature", 20.5).toFloat(),
                                                    targetTemperature = attrs.optDouble("temperature", 22.0).toFloat(),
                                                    capabilities = mergedCaps,
                                                    capabilitySchemaHash = mergedHash
                                                )
                                                tile.deviceClass == DeviceClass.LIGHT || tile.isLytmiTile -> tile.copy(
                                                    isOnline = rawHaOnline,
                                                    isActive = state.equals("on", true),
                                                    volume = if (state.equals("on", true)) levelOrBrightness else tile.volume,
                                                    capabilities = mergedCaps,
                                                    capabilitySchemaHash = mergedHash
                                                )
                                                tile.deviceSubtype.name.contains("VACUUM", true) -> tile.copy(
                                                    isOnline = rawHaOnline,
                                                    isActive = state.equals("cleaning", true) || state.equals("on", true),
                                                    volume = levelOrBrightness,
                                                    capabilities = mergedCaps,
                                                    capabilitySchemaHash = mergedHash
                                                )
                                                tile.deviceSubtype == DeviceSubtype.LOCK -> tile.copy(
                                                    isOnline = rawHaOnline,
                                                    isActive = state.equals("unlocked", true) || state.equals("on", true),
                                                    capabilities = mergedCaps,
                                                    capabilitySchemaHash = mergedHash
                                                )
                                                tile.deviceSubtype == DeviceSubtype.TV -> tile.copy(
                                                    isOnline = rawHaOnline,
                                                    isActive = state.equals("on", true) || state.equals("playing", true),
                                                    volume = levelOrBrightness,
                                                    capabilities = mergedCaps,
                                                    capabilitySchemaHash = mergedHash
                                                )
                                                else -> tile.copy(
                                                    isOnline = rawHaOnline,
                                                    isActive = state.equals("on", true) || state.equals("open", true),
                                                    capabilities = mergedCaps,
                                                    capabilitySchemaHash = mergedHash
                                                )
                                            }
                                            val safeUpdate = DeviceRepository.reconcileRemoteTileUpdate(tile, candidateUpdate)
                                            if (safeUpdate != tile) {
                                                tiles[i] = safeUpdate
                                                updated = true
                                            }
                                        }
                                    }
                                    if (updated) {
                                        DeviceRepository.saveTiles(context, tiles)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ClaraSmartHomeApi", "HA WebSocket msg error", e)
                }
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                haWebSocket = null
                haConsecutiveFailures++
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                haWebSocket = null
                haConsecutiveFailures++
            }
        })
    }

    fun registerVerifiedLanNeighbor(ipAddress: String, macOrHardwareId: String = "") {
        val cleanIp = ipAddress.substringBefore(":").trim()
        if (cleanIp.isNotBlank() && cleanIp.contains(".")) {
            val cleanMac = macOrHardwareId.trim().uppercase(Locale.US)
            CrossEcosystemIdentityCorrelator.recordApplicationLayerObservation(cleanIp, cleanMac)
        }
    }

    /**
     * Builds hardware-true capability descriptors when explicit schema introspection
     * has not yet populated `tile.capabilities`.
     */
    fun buildDefaultCapabilitiesForTile(tile: SmartTile): List<DeviceCapabilityDescriptor> {
        if (tile.isLytmiTile) {
            val hasLytmiControls = tile.capabilities.any {
                it.key.lowercase(Locale.US) in setOf("work_mode", "sync_mode", "hdmi_input", "scene_select", "colour_data", "colour_data_v2")
            }
            if (!hasLytmiControls) {
                val defaults = TuyaApiClient.defaultLytmiCapabilities(
                    isActive = tile.isActive,
                    hdmiInput = tile.lytmiHdmiInput,
                    syncMode = tile.lytmiSyncMode,
                    sceneName = tile.lytmiSceneName,
                    diffusionSpeed = tile.lytmiDiffusionSpeed
                )
                val defaultKeys = defaults.map { it.key.lowercase(Locale.US) }.toSet()
                val extraHardwareCaps = tile.capabilities.filter {
                    val k = it.key.lowercase(Locale.US)
                    k !in defaultKeys && k !in setOf("switch", "switch_1", "switch1")
                }
                return defaults + extraHardwareCaps
            }
        }
        val lowerTitle = tile.title.trim().lowercase(Locale.US)
        val isKitchenApplianceByTitle = lowerTitle.contains("kettle") ||
                lowerTitle.contains("fryer") ||
                lowerTitle.contains("coffee")
        val isNonHeaterPlugOrAppliance = isKitchenApplianceByTitle ||
                lowerTitle.contains("plug") ||
                lowerTitle.contains("socket") ||
                lowerTitle.contains("pond") ||
                lowerTitle.contains("pump") ||
                lowerTitle.contains("washer")
        if (tile.capabilities.isNotEmpty()) {
            val sanitizedExisting = if (isNonHeaterPlugOrAppliance && tile.type != TileType.NEST_THERMOSTAT) {
                tile.capabilities.filterNot { cap ->
                    cap.dpId.isBlank() && cap.key.lowercase(Locale.US) in setOf(
                        "hvac_mode", "target_temperature", "ambient_temperature"
                    )
                }
            } else {
                tile.capabilities
            }
            if (sanitizedExisting.isNotEmpty()) return sanitizedExisting
        }
        val providerKey = resolveEffectiveProviderKey(tile)
        return when {
            tile.isVehicleTile -> VehicleIntegrationClient.defaultCapabilitiesForVehicle(
                type = tile.type,
                isLocked = tile.vehicleLocked,
                climateActive = tile.vehicleClimateActive,
                isCharging = tile.vehicleIsCharging,
                targetTempC = tile.vehicleTargetClimateTempC,
                socPct = tile.vehicleFuelOrBatteryPct,
                rangeMiles = tile.vehicleRangeMiles
            )
            tile.isTowerFanTile -> TuyaApiClient.defaultProBreezeFanCapabilities(
                isActive = tile.isActive,
                speedLevel = tile.fanSpeedLevel,
                fanMode = tile.fanMode,
                oscillating = tile.fanOscillating,
                ionizerActive = tile.fanIonizerActive,
                timerHours = tile.fanTimerHours
            )
            tile.isLytmiTile || providerKey.contains("LYTMI") -> TuyaApiClient.defaultLytmiCapabilities(
                isActive = tile.isActive,
                hdmiInput = tile.lytmiHdmiInput,
                syncMode = tile.lytmiSyncMode,
                sceneName = tile.lytmiSceneName,
                diffusionSpeed = tile.lytmiDiffusionSpeed
            )
            providerKey.contains("GOVEE") || providerKey.contains("TWINKLY") -> {
                val isTwinkly = providerKey.contains("TWINKLY")
                listOf(
                    DeviceCapabilityDescriptor(
                        key = "switch_led",
                        label = "Light Power",
                        kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                        isWritable = true,
                        isPinnedToHero = true,
                        currentValue = tile.isActive.toString()
                    ),
                    DeviceCapabilityDescriptor(
                        key = "work_mode",
                        label = if (isTwinkly) "Twinkly Mode" else "Govee Mode",
                        kind = DeviceCapabilityKind.ENUM_MODE,
                        isWritable = true,
                        isPinnedToHero = true,
                        currentValue = tile.lytmiSyncMode,
                        enumOptions = if (isTwinkly) {
                            listOf("COLOR", "MOVIE", "MUSIC", "SCENE")
                        } else {
                            listOf("COLOR", "VIDEO", "MUSIC", "SCENE")
                        }
                    ),
                    DeviceCapabilityDescriptor(
                        key = "brightness",
                        label = "Brightness",
                        kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                        isWritable = true,
                        isPinnedToHero = false,
                        currentValue = tile.volume.roundToInt().toString(),
                        minValue = 1f,
                        maxValue = 100f,
                        stepValue = 1f,
                        unit = "%"
                    ),
                    DeviceCapabilityDescriptor(
                        key = "diffusion_speed",
                        label = "Effect Speed",
                        kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                        isWritable = true,
                        isPinnedToHero = false,
                        currentValue = tile.lytmiDiffusionSpeed.roundToInt().toString(),
                        minValue = 1f,
                        maxValue = 100f,
                        stepValue = 1f,
                        unit = "%"
                    )
                )
            }
            // Golden Rule: Evaluate kitchen appliances before thermostats and never fabricate temperature dials when hardware DPs are absent
            tile.isKitchenApplianceTile || isKitchenApplianceByTitle -> listOf(
                DeviceCapabilityDescriptor(
                    key = if (tile.deviceSubtype == DeviceSubtype.KETTLE || lowerTitle.contains("kettle")) "boil" else "switch",
                    dpId = "1",
                    label = when {
                        tile.deviceSubtype == DeviceSubtype.KETTLE || lowerTitle.contains("kettle") -> "Boil Power"
                        tile.deviceSubtype == DeviceSubtype.AIR_FRYER || lowerTitle.contains("fryer") -> "Cooking Power"
                        else -> "Appliance Power"
                    },
                    kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = tile.isActive.toString(),
                    rendererType = CapabilityRendererType.TOGGLE
                )
            )
            tile.isThermostatTile && !isNonHeaterPlugOrAppliance -> listOf(
                DeviceCapabilityDescriptor(
                    key = "hvac_mode",
                    label = "Heating Mode",
                    kind = DeviceCapabilityKind.ENUM_MODE,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = tile.hvacMode,
                    enumOptions = listOf("HEAT", "ECO", "BOOST", "OFF")
                ),
                DeviceCapabilityDescriptor(
                    key = "target_temperature",
                    label = "Target Setpoint",
                    kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = "%.1f".format(Locale.US, tile.targetTemperature),
                    minValue = 10f,
                    maxValue = if (providerKey.contains("NEST")) 32f else 35f,
                    stepValue = 0.5f,
                    unit = "\u00B0C"
                ),
                DeviceCapabilityDescriptor(
                    key = "ambient_temperature",
                    label = "Room Temperature",
                    kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                    isWritable = false,
                    isPinnedToHero = false,
                    currentValue = "%.1f".format(Locale.US, tile.temperature),
                    unit = "\u00B0C"
                )
            )
            tile.isMultiGangSwitch -> buildList {
                add(
                    DeviceCapabilityDescriptor(
                        key = "switch_1",
                        dpId = "1",
                        label = tile.socket1Name.ifBlank { "Socket 1" },
                        kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                        isWritable = true,
                        isPinnedToHero = true,
                        currentValue = tile.isActive.toString(),
                        rendererType = CapabilityRendererType.TOGGLE
                    )
                )
                add(
                    DeviceCapabilityDescriptor(
                        key = "switch_2",
                        dpId = "2",
                        label = tile.socket2Name.ifBlank { "Socket 2" },
                        kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                        isWritable = true,
                        isPinnedToHero = true,
                        currentValue = tile.isSocket2Active.toString(),
                        rendererType = CapabilityRendererType.TOGGLE
                    )
                )
                if (providerKey.contains("TUYA")) {
                    add(
                        DeviceCapabilityDescriptor(
                            key = "countdown_1",
                            dpId = "9",
                            label = "Socket 1 Timer",
                            kind = DeviceCapabilityKind.COUNTDOWN_TIMER,
                            isWritable = true,
                            isPinnedToHero = false,
                            currentValue = tile.countdown1Seconds.toString(),
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
                if (tile.hasPowerTelemetryCapability) {
                    add(
                        DeviceCapabilityDescriptor(
                            key = "cur_power",
                            label = "Live Power Draw",
                            kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                            isWritable = false,
                            isPinnedToHero = false,
                            currentValue = "%.1f".format(Locale.US, tile.powerWatts),
                            unit = "W",
                            rendererType = CapabilityRendererType.SENSOR_BADGE
                        )
                    )
                }
            }
            tile.isSwitchTile || isNonHeaterPlugOrAppliance -> buildList {
                add(
                    DeviceCapabilityDescriptor(
                        key = "switch_1",
                        dpId = "1",
                        label = "Power Switch",
                        kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                        isWritable = true,
                        isPinnedToHero = true,
                        currentValue = tile.isActive.toString(),
                        rendererType = CapabilityRendererType.TOGGLE
                    )
                )
                if (providerKey.contains("TUYA")) {
                    add(
                        DeviceCapabilityDescriptor(
                            key = "countdown_1",
                            dpId = "9",
                            label = "Auto-Off Timer",
                            kind = DeviceCapabilityKind.COUNTDOWN_TIMER,
                            isWritable = true,
                            isPinnedToHero = true,
                            currentValue = tile.countdown1Seconds.toString(),
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
                if (tile.hasPowerTelemetryCapability) {
                    add(
                        DeviceCapabilityDescriptor(
                            key = "cur_power",
                            label = "Live Power Draw",
                            kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                            isWritable = false,
                            isPinnedToHero = false,
                            currentValue = "%.1f".format(Locale.US, tile.powerWatts),
                            unit = "W",
                            rendererType = CapabilityRendererType.SENSOR_BADGE
                        )
                    )
                }
            }
            tile.deviceSubtype in setOf(DeviceSubtype.BLIND, DeviceSubtype.GARAGE_DOOR) -> {
                val isGarage = tile.deviceSubtype == DeviceSubtype.GARAGE_DOOR
                listOf(
                    DeviceCapabilityDescriptor(
                        key = "position",
                        label = if (isGarage) "Door Position" else "Cover Position",
                        kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                        isWritable = true,
                        isPinnedToHero = true,
                        currentValue = tile.volume.roundToInt().toString(),
                        minValue = 0f,
                        maxValue = 100f,
                        stepValue = 5f,
                        unit = "%",
                        rendererType = CapabilityRendererType.DIAL,
                        presets = if (isGarage) {
                            listOf(
                                CapabilityPreset(value = 100f, label = "Open (100%)", icon = "BLINDS_OPEN"),
                                CapabilityPreset(value = 50f, label = "Half (50%)", icon = "BLINDS_HALF"),
                                CapabilityPreset(value = 0f, label = "Close (0%)", icon = "BLINDS_CLOSED")
                            )
                        } else {
                            listOf(
                                CapabilityPreset(value = 100f, label = "Open (100%)", icon = "BLINDS_OPEN"),
                                CapabilityPreset(value = 50f, label = "Half (50%)", icon = "BLINDS_HALF"),
                                CapabilityPreset(value = 25f, label = "Vent (25%)", icon = "BLINDS_VENT"),
                                CapabilityPreset(value = 0f, label = "Close (0%)", icon = "BLINDS_CLOSED")
                            )
                        }
                    )
                )
            }
            tile.deviceClass == DeviceClass.LIGHT -> listOf(
                DeviceCapabilityDescriptor(
                    key = "switch_led",
                    label = "Light Power",
                    kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = tile.isActive.toString(),
                    rendererType = CapabilityRendererType.TOGGLE
                ),
                DeviceCapabilityDescriptor(
                    key = "brightness",
                    label = "Brightness",
                    kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = tile.volume.roundToInt().toString(),
                    minValue = 1f,
                    maxValue = 100f,
                    stepValue = 5f,
                    unit = "%",
                    rendererType = CapabilityRendererType.DIAL,
                    presets = listOf(
                        CapabilityPreset(value = 100f, label = "On (100%)", icon = "SUNNY"),
                        CapabilityPreset(value = 75f, label = "Bright (75%)", icon = "BRIGHT"),
                        CapabilityPreset(value = 25f, label = "Dim (25%)", icon = "DIM"),
                        CapabilityPreset(value = 0f, label = "Off", icon = "POWER")
                    )
                )
            )
            tile.deviceSubtype.name.contains("VACUUM", ignoreCase = true) -> listOf(
                DeviceCapabilityDescriptor(
                    key = "power_go",
                    dpId = "2",
                    label = "Auto Clean",
                    kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = tile.isActive.toString(),
                    rendererType = CapabilityRendererType.TOGGLE
                ),
                DeviceCapabilityDescriptor(
                    key = "mode",
                    dpId = "3",
                    label = "Cleaning Mode",
                    kind = DeviceCapabilityKind.ENUM_MODE,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = tile.hvacMode.ifBlank { if (tile.isActive) "CLEAN" else "DOCK" },
                    enumOptions = listOf("CLEAN", "SPOT", "TURBO", "DOCK"),
                    rendererType = CapabilityRendererType.CHIP_GROUP
                ),
                DeviceCapabilityDescriptor(
                    key = "battery_percentage",
                    dpId = "6",
                    label = "Battery Level",
                    kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                    isWritable = false,
                    isPinnedToHero = false,
                    currentValue = tile.volume.roundToInt().coerceIn(15, 100).toString(),
                    unit = "%",
                    rendererType = CapabilityRendererType.SENSOR_BADGE
                )
            )
            tile.deviceSubtype == DeviceSubtype.LOCK -> listOf(
                DeviceCapabilityDescriptor(
                    key = "lock",
                    label = "Door Lock",
                    kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = tile.isActive.toString(),
                    rendererType = CapabilityRendererType.TOGGLE
                )
            )
            tile.isSpeakerTile -> listOf(
                DeviceCapabilityDescriptor(
                    key = "speaker_volume",
                    label = "Speaker Volume",
                    kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = tile.volume.roundToInt().toString(),
                    minValue = 0f,
                    maxValue = 100f,
                    stepValue = 5f,
                    unit = "%",
                    rendererType = CapabilityRendererType.SLIDER,
                    presets = listOf(
                        CapabilityPreset(value = 0f, label = "0%", icon = "VOLUME_MUTE"),
                        CapabilityPreset(value = 25f, label = "25%", icon = "VOLUME_LOW"),
                        CapabilityPreset(value = 50f, label = "50%", icon = "VOLUME_MED"),
                        CapabilityPreset(value = 75f, label = "75%", icon = "VOLUME_HIGH"),
                        CapabilityPreset(value = 100f, label = "100%", icon = "VOLUME_MAX")
                    )
                ),
                DeviceCapabilityDescriptor(
                    key = "speaker_chime",
                    label = "Test Doorbell Chime",
                    kind = DeviceCapabilityKind.ACTION_TRIGGER,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = "READY",
                    rendererType = CapabilityRendererType.TOGGLE
                )
            )
            else -> emptyList()
        }
    }

    // -----------------------------------------------------------------------
    // GOVEE LOCAL LAN (UDP 4001-4003) & CLOUD API ENGINE
    // -----------------------------------------------------------------------
    suspend fun discoverGoveeDevices(
        bridge: BridgeSettings = BridgeSettings(),
        timeoutMs: Long = 1300L
    ): List<DiscoveredLanDevice> = withContext(Dispatchers.IO) {
        DiscoveryRadioCoordinator.withMulticastLock(ClaraApplication.appContextOrNull(), "govee_udp") {
            val discovered = ConcurrentHashMap<String, DiscoveredLanDevice>()
        val scanQuery = """{"msg":{"cmd":"scan","data":{"account_topic":"reserve"}}}"""
        val queryBytes = scanQuery.toByteArray(Charsets.UTF_8)

            // 1. Local UDP Broadcast / Multicast to 239.255.255.250:4001 & 255.255.255.255:4001 (listening on 4002)
            var recvSocket: DatagramSocket? = null
            var sendSocket: DatagramSocket? = null
            try {
                recvSocket = runCatching {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        soTimeout = 300
                        bind(InetSocketAddress(4002))
                    }
                }.onFailure { bindErr ->
                    ErrorClassifier.recordHandledException(
                        t = bindErr,
                        transportSource = "GOVEE_UDP_4002",
                        operationStage = "bindPort4002",
                        port = 4002
                    )
                }.getOrNull()
                sendSocket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 300
                }

                runCatching {
                    val multicastAddr = InetAddress.getByName("239.255.255.250")
                    sendSocket.send(DatagramPacket(queryBytes, queryBytes.size, multicastAddr, 4001))
                }.onFailure { txErr ->
                    ErrorClassifier.recordHandledException(
                        t = txErr,
                        transportSource = "GOVEE_UDP_4001",
                        operationStage = "sendMulticastScan",
                        ipAddress = "239.255.255.250",
                        port = 4001
                    )
                }
                runCatching {
                    val broadcastAddr = InetAddress.getByName("255.255.255.255")
                    sendSocket.send(DatagramPacket(queryBytes, queryBytes.size, broadcastAddr, 4001))
                }.onFailure { txErr ->
                    ErrorClassifier.recordHandledException(
                        t = txErr,
                        transportSource = "GOVEE_UDP_4001",
                        operationStage = "sendBroadcastScan",
                        ipAddress = "255.255.255.255",
                        port = 4001
                    )
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val activeSock = recvSocket ?: sendSocket
                    val pkt = DatagramPacket(buf, buf.size)
                    val ok = try {
                        activeSock.receive(pkt)
                        true
                    } catch (_: SocketTimeoutException) {
                        false
                    } catch (rxErr: Exception) {
                        ErrorClassifier.recordHandledException(
                            t = rxErr,
                            transportSource = "GOVEE_UDP_4002",
                            operationStage = "receiveScanPacket",
                            port = 4002
                        )
                        false
                    }
                if (!ok || pkt.length <= 0) continue
                val senderIp = pkt.address?.hostAddress.orEmpty()
                val rawStr = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                    transportSource = "GOVEE_UDP_4002",
                    ipAddress = senderIp,
                    port = pkt.port.takeIf { it > 0 } ?: 4002,
                    vendor = "Govee UDP Responder",
                    confidenceScore = 0.20f,
                    qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                    outcomeReason = "Govee UDP scan response received (${pkt.length} bytes)",
                    rawPayload = rawStr
                )
                val json = runCatching { JSONObject(rawStr) }.getOrNull()
                val data = json?.optJSONObject("msg")?.optJSONObject("data")
                if (data == null) {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FILTERED,
                        transportSource = "GOVEE_UDP_4002",
                        ipAddress = senderIp,
                        port = 4002,
                        vendor = "UDP 4002 Host",
                        qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                        outcomeReason = "Rejected UDP 4002 packet without valid Govee msg.data JSON",
                        rawPayload = rawStr
                    )
                    continue
                }
                val ip = data.optString("ip", senderIp).ifBlank { senderIp }
                val devId = data.optString("device", ip)
                val sku = data.optString("sku", "Govee RGBIC")
                if (devId.isNotBlank() && sku.isNotBlank()) {
                    goveeModelByDeviceId[devId] = sku
                }
                if (ip.isNotBlank()) {
                    registerVerifiedLanNeighbor(ip, devId)
                    val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(devId)
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.IDENTIFIED,
                        transportSource = "GOVEE_UDP_4002",
                        hardwareId = devId,
                        macAddress = normMac,
                        ipAddress = ip,
                        port = 4003,
                        vendor = "Govee $sku",
                        confidenceScore = 0.95f,
                        qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                        outcomeReason = "Verified Govee LAN UDP 4003 responder ($sku)"
                    )
                    discovered[ip] = DiscoveredLanDevice(
                        ipAddress = ip,
                        port = 4003,
                        suggestedName = "Govee $sku (${ip.substringAfterLast(".")})",
                        suggestedType = TileType.GOVEE_LIGHT,
                        detail = "Govee $sku \u2022 Local LAN UDP 4003 Ready",
                        companionPackage = "com.govee.home",
                        actionType = DiscoveredDeviceActionType.ADD_DIRECT,
                        protocolBadge = "Govee LAN UDP",
                        hardwareIdOrMac = devId,
                        brandOption = BrandOption.GOVEE
                    )
                }
            }
            } catch (e: Exception) {
                ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = "GOVEE_UDP_4002",
                    operationStage = "discoverGoveeDevices.udp",
                    port = 4002
                )
            } finally {
                ErrorClassifier.closeQuietly(recvSocket, "GOVEE_UDP_4002")
                ErrorClassifier.closeQuietly(sendSocket, "GOVEE_UDP_4001")
            }

            // 2. Govee Developer Cloud REST API discovery if API Key is configured
            val apiKey = bridge.goveeApiKey.trim()
            if (apiKey.isNotBlank()) {
                try {
                    val httpResp = SharedHttpClient.requestText(
                        url = "https://developer-api.govee.com/v1/devices",
                        headers = mapOf(
                            "Govee-API-Key" to apiKey,
                            "Accept" to "application/json"
                        ),
                        connectTimeoutMs = 4500,
                        readTimeoutMs = 4500
                    )
                    val respCode = httpResp.code
                    if (httpResp.isSuccessful) {
                        val root = JSONObject(httpResp.body)
                        val devicesArr = root.optJSONObject("data")?.optJSONArray("devices") ?: JSONArray()
                        for (i in 0 until devicesArr.length()) {
                            val d = devicesArr.optJSONObject(i) ?: continue
                            val devMac = d.optString("device", "").trim()
                            val model = d.optString("model", "Govee Light").trim()
                            val devName = d.optString("deviceName", "Govee $model").trim()
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                                transportSource = "GOVEE_CLOUD",
                                hardwareId = devMac,
                                macAddress = CrossEcosystemIdentityCorrelator.normalizeMac(devMac),
                                vendor = "$devName ($model)",
                                confidenceScore = 0.80f,
                                qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                outcomeReason = "Govee Cloud /v1/devices entry received",
                                cloudId = devMac
                            )
                            if (devMac.isNotBlank()) {
                                goveeModelByDeviceId[devMac] = model
                                val existingLan = discovered.values.firstOrNull { it.hardwareIdOrMac.equals(devMac, ignoreCase = true) }
                                val key = existingLan?.ipAddress ?: devMac
                                discovered[key] = DiscoveredLanDevice(
                                    ipAddress = existingLan?.ipAddress.orEmpty(),
                                    port = 4003,
                                    suggestedName = devName,
                                    suggestedType = TileType.GOVEE_LIGHT,
                                    detail = "Govee $model \u2022 ${if (existingLan != null) "LAN + Cloud Ready" else "Cloud API Ready"}",
                                    companionPackage = "com.govee.home",
                                    actionType = DiscoveredDeviceActionType.ADD_DIRECT,
                                    protocolBadge = if (existingLan != null) "Govee LAN+Cloud" else "Govee Cloud",
                                    hardwareIdOrMac = "$devMac|$model",
                                    brandOption = BrandOption.GOVEE
                                )
                            } else {
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.FILTERED,
                                    transportSource = "GOVEE_CLOUD",
                                    vendor = devName,
                                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                    outcomeReason = "[INVALID_PHANTOM_DEVICE] Filtered Govee Cloud entry missing device MAC/ID"
                                )
                            }
                        }
                    } else {
                    ErrorClassifier.recordHandledException(
                        t = IOException("Govee Cloud /v1/devices returned HTTP $respCode"),
                            transportSource = "GOVEE_CLOUD",
                            operationStage = "discoverGoveeDevices.cloud",
                            httpStatusCode = respCode,
                            surfaceToUiBanner = respCode == 401 || respCode == 403
                        )
                    }
                } catch (e: Exception) {
                    ErrorClassifier.recordHandledException(
                        t = e,
                        transportSource = "GOVEE_CLOUD",
                        operationStage = "discoverGoveeDevices.cloud"
                    )
                }
            }

            discovered.values.toList()
        }
    }

    suspend fun sendGoveeCommand(
        bridge: BridgeSettings,
        tile: SmartTile,
        turnOn: Boolean = tile.isActive,
        brightnessPct: Float = tile.volume,
        colorArgb: Long = tile.lytmiColorArgb,
        syncMode: String = tile.lytmiSyncMode,
        @Suppress("UNUSED_PARAMETER") sceneName: String = tile.lytmiSceneName
    ): Boolean = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val ip = tile.ipAddress.ifBlank { bridge.goveeIpAddress }.substringBefore(":").trim()
        val red = ((colorArgb shr 16) and 0xFF).toInt().coerceIn(0, 255)
        val green = ((colorArgb shr 8) and 0xFF).toInt().coerceIn(0, 255)
        val blue = (colorArgb and 0xFF).toInt().coerceIn(0, 255)
        val clampedBri = brightnessPct.roundToInt().coerceIn(1, 100)

        var lanSuccess = false
        // 1. Direct Govee Local LAN UDP Port 4003 Control
        if (ip.isNotBlank() && ip.contains(".")) {
            try {
                DatagramSocket().use { sock ->
                    sock.soTimeout = 800
                    val addr = InetAddress.getByName(ip)
                    val turnJson = JSONObject().put(
                        "msg",
                        JSONObject()
                            .put("cmd", "turn")
                            .put("data", JSONObject().put("value", if (turnOn) 1 else 0))
                    ).toString().toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(turnJson, turnJson.size, addr, 4003))

                    if (turnOn) {
                        val briJson = JSONObject().put(
                            "msg",
                            JSONObject()
                                .put("cmd", "brightness")
                                .put("data", JSONObject().put("value", clampedBri))
                        ).toString().toByteArray(Charsets.UTF_8)
                        sock.send(DatagramPacket(briJson, briJson.size, addr, 4003))

                        val colorJson = JSONObject().put(
                            "msg",
                            JSONObject()
                                .put("cmd", "colorwc")
                                .put(
                                    "data",
                                    JSONObject()
                                        .put(
                                            "color",
                                            JSONObject()
                                                .put("r", red)
                                                .put("g", green)
                                                .put("b", blue)
                                        )
                                        .put("colorTemInKelvin", 0)
                                )
                        ).toString().toByteArray(Charsets.UTF_8)
                        sock.send(DatagramPacket(colorJson, colorJson.size, addr, 4003))
                    }
                    lanSuccess = true
                }
            } catch (e: Exception) {
                ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = "GOVEE_UDP_4003",
                    operationStage = "sendGoveeCommand.lan",
                    hardwareId = tile.canonicalGuid.ifBlank { tile.id },
                    deviceName = tile.title,
                    ipAddress = ip,
                    port = 4003,
                    logToDiscoveryRecorder = false,
                    logToDeviceBlackBox = true
                )
            }
        }

        // 2. Govee Cloud REST API Control Fallback (`PUT https://developer-api.govee.com/v1/devices/control`)
        val apiKey = tile.secretCode.ifBlank { bridge.goveeApiKey }.trim()
        val rawId = tile.serialNumber.ifBlank { tile.entityId }.trim()
        val devMac = rawId.substringBefore("|").trim()
        val model = rawId.substringAfter("|", "").ifBlank { goveeModelByDeviceId[devMac].orEmpty() }.ifBlank { "H6199" }
        var cloudSuccess = false

        if (!lanSuccess && apiKey.isNotBlank() && devMac.isNotBlank()) {
            val commandsToSend = buildList {
                add(JSONObject().put("name", "turn").put("value", if (turnOn) "on" else "off"))
                if (turnOn) {
                    add(JSONObject().put("name", "brightness").put("value", clampedBri))
                    if (syncMode.uppercase(Locale.US) in listOf("COLOR", "COLOUR", "VIDEO", "GAME", "MUSIC", "SCENE")) {
                        add(
                            JSONObject().put(
                                "name",
                                "color"
                            ).put(
                                "value",
                                JSONObject().put("r", red).put("g", green).put("b", blue)
                            )
                        )
                    }
                }
            }
            for (cmdObj in commandsToSend) {
                try {
                    val payload = JSONObject().apply {
                        put("device", devMac)
                        put("model", model)
                        put("cmd", cmdObj)
                    }.toString()
                    val httpResp = SharedHttpClient.requestText(
                        url = "https://developer-api.govee.com/v1/devices/control",
                        method = "PUT",
                        headers = mapOf("Govee-API-Key" to apiKey),
                        body = payload,
                        connectTimeoutMs = 4000,
                        readTimeoutMs = 4000
                    )
                    if (httpResp.isSuccessful) {
                        cloudSuccess = true
                    } else {
                        ErrorClassifier.recordHandledException(
                            t = IOException("Govee Cloud control returned HTTP ${httpResp.code}"),
                            transportSource = "GOVEE_CLOUD",
                            operationStage = "sendGoveeCommand.cloud",
                            hardwareId = tile.canonicalGuid.ifBlank { tile.id },
                            deviceName = tile.title,
                            httpStatusCode = httpResp.code,
                            logToDiscoveryRecorder = false,
                            logToDeviceBlackBox = true
                        )
                    }
                } catch (e: Exception) {
                    ErrorClassifier.recordHandledException(
                        t = e,
                        transportSource = "GOVEE_CLOUD",
                        operationStage = "sendGoveeCommand.cloud",
                        hardwareId = tile.canonicalGuid.ifBlank { tile.id },
                        deviceName = tile.title,
                        logToDiscoveryRecorder = false,
                        logToDeviceBlackBox = true
                    )
                }
            }
        }

        val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(6L)
        JarvisLiveBridge.recordCommandLatency(
            integrationKey = "MULTI_PROTOCOL",
            displayName = "Matter / Tapo / Shelly / MQTT / HA",
            latencyMs = latency,
            succeeded = lanSuccess || cloudSuccess
        )
        lanSuccess || cloudSuccess
    }

    // -----------------------------------------------------------------------
    // TWINKLY XLED LOCAL REST (/xled/v1/) & UDP 5555 ENGINE
    // -----------------------------------------------------------------------
    fun probeTwinklyGestaltOnPort80(ip: String): DiscoveredLanDevice? {
        val cleanIp = ip.substringBefore(":").trim()
        if (cleanIp.isBlank()) return null
        return try {
            val resp = SharedHttpClient.requestText(
                url = "http://$cleanIp/xled/v1/gestalt",
                connectTimeoutMs = 450,
                readTimeoutMs = 450
            )
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body)
            if (!json.has("product_name") && !json.has("device_name") && !json.has("led_profile")) {
                return null
            }
            val devName = json.optString("device_name", "").ifBlank {
                json.optString("product_name", "Twinkly LED Lights")
            }
            val hwId = json.optString("hw_id", json.optString("mac", cleanIp))
            val ledCount = json.optInt("number_of_led", 0)
            registerVerifiedLanNeighbor(cleanIp, hwId)
            DiscoveredLanDevice(
                ipAddress = cleanIp,
                port = 80,
                suggestedName = devName,
                suggestedType = TileType.TWINKLY_LIGHT,
                detail = "Twinkly XLED${if (ledCount > 0) " ($ledCount LEDs)" else ""} \u2022 Local REST Ready",
                companionPackage = "com.twinkly",
                actionType = DiscoveredDeviceActionType.ADD_DIRECT,
                protocolBadge = "Twinkly XLED",
                hardwareIdOrMac = hwId,
                brandOption = BrandOption.TWINKLY
            )
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: ConnectException) {
            null
        } catch (_: NoRouteToHostException) {
            null
        } catch (_: FileNotFoundException) {
            null
        } catch (e: Exception) {
            ErrorClassifier.recordHandledException(
                t = e,
                transportSource = "TWINKLY_XLED_HTTP",
                operationStage = "probeTwinklyGestaltOnPort80",
                ipAddress = cleanIp,
                port = 80,
                logToDiscoveryRecorder = false
            )
            null
        }
    }

    suspend fun discoverTwinklyDevices(
        context: Context? = null,
        timeoutMs: Long = 1200L
    ): List<DiscoveredLanDevice> = withContext(Dispatchers.IO) {
        DiscoveryRadioCoordinator.withMulticastLock(context, "twinkly_udp") {
            val discovered = ConcurrentHashMap<String, DiscoveredLanDevice>()
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 300
                    val discoverPayload = ByteArray(1 + 8).apply {
                        this[0] = 0x01
                        val word = "discover".toByteArray(Charsets.US_ASCII)
                        System.arraycopy(word, 0, this, 1, word.size)
                    }
                    socket.send(
                        DatagramPacket(
                            discoverPayload,
                            discoverPayload.size,
                            InetAddress.getByName("255.255.255.255"),
                            5555
                        )
                    )
                    val deadline = System.currentTimeMillis() + timeoutMs
                    val buf = ByteArray(512)
                    while (System.currentTimeMillis() < deadline) {
                        val pkt = DatagramPacket(buf, buf.size)
                        val ok = try {
                            socket.receive(pkt)
                            true
                        } catch (_: SocketTimeoutException) {
                            false
                        } catch (rxErr: Exception) {
                            ErrorClassifier.recordHandledException(
                                t = rxErr,
                                transportSource = "TWINKLY_UDP_5555",
                                operationStage = "receiveUdp5555",
                                port = 5555
                            )
                            false
                        }
                        if (!ok || pkt.length <= 0) continue
                        val senderIp = pkt.address?.hostAddress.orEmpty()
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                            transportSource = "TWINKLY_UDP_5555",
                            ipAddress = senderIp,
                            port = 5555,
                            vendor = "UDP 5555 Responder",
                            confidenceScore = 0.20f,
                            qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                            outcomeReason = "Twinkly UDP 5555 broadcast response received (${pkt.length} bytes)"
                        )
                        if (pkt.length < 6) {
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.FILTERED,
                                transportSource = "TWINKLY_UDP_5555",
                                ipAddress = senderIp,
                                port = 5555,
                                vendor = "UDP 5555 Host",
                                qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                                outcomeReason = "Rejected truncated UDP 5555 packet (${pkt.length} bytes)"
                            )
                            continue
                        }
                        if (senderIp.isNotBlank()) {
                            val gestalt = probeTwinklyGestaltOnPort80(senderIp)
                            if (gestalt != null) {
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.IDENTIFIED,
                                    transportSource = "TWINKLY_UDP_5555",
                                    hardwareId = gestalt.hardwareIdOrMac,
                                    macAddress = CrossEcosystemIdentityCorrelator.normalizeMac(gestalt.hardwareIdOrMac),
                                    ipAddress = senderIp,
                                    port = 80,
                                    vendor = gestalt.suggestedName,
                                    confidenceScore = 0.95f,
                                    qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                                    outcomeReason = "Verified Twinkly /xled/v1/gestalt schema"
                                )
                                discovered[senderIp] = gestalt
                            } else {
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.FILTERED,
                                    transportSource = "TWINKLY_UDP_5555",
                                    ipAddress = senderIp,
                                    port = 5555,
                                    vendor = "UDP 5555 Host",
                                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                    outcomeReason = "Rejected UDP 5555 responder without valid /xled/v1/gestalt JSON"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = "TWINKLY_UDP_5555",
                    operationStage = "discoverTwinklyDevices",
                    port = 5555
                )
            }
            discovered.values.toList()
        }
    }

    private fun authenticateTwinklyXled(ip: String): String? {
        twinklyTokenByIp[ip]?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            val randomChallenge = ByteArray(32).apply { SecureRandom().nextBytes(this) }
            val challengeB64 = Base64.encodeToString(randomChallenge, Base64.NO_WRAP)
            val challengeJson = JSONObject().put("challenge", challengeB64).toString()
            val loginResp = SharedHttpClient.requestText(
                url = "http://$ip/xled/v1/login",
                method = "POST",
                body = challengeJson,
                connectTimeoutMs = 2000,
                readTimeoutMs = 2000
            )
            if (!loginResp.isSuccessful) {
                ErrorClassifier.recordHandledException(
                    t = IOException("Twinkly /xled/v1/login returned HTTP ${loginResp.code}"),
                    transportSource = "TWINKLY_XLED_AUTH",
                    operationStage = "authenticateTwinklyXled",
                    ipAddress = ip,
                    port = 80,
                    httpStatusCode = loginResp.code,
                    logToDiscoveryRecorder = false
                )
                return null
            }
            val token = JSONObject(loginResp.body).optString("authentication_token", "").trim()
            if (token.isBlank()) return null

            SharedHttpClient.requestText(
                url = "http://$ip/xled/v1/verify",
                method = "POST",
                headers = mapOf("X-Auth-Token" to token),
                body = "{}",
                connectTimeoutMs = 2000,
                readTimeoutMs = 2000
            )
            twinklyTokenByIp[ip] = token
            token
        } catch (e: Exception) {
            ErrorClassifier.recordHandledException(
                t = e,
                transportSource = "TWINKLY_XLED_AUTH",
                operationStage = "authenticateTwinklyXled",
                ipAddress = ip,
                port = 80,
                logToDiscoveryRecorder = false
            )
            null
        }
    }

    suspend fun sendTwinklyCommand(
        bridge: BridgeSettings,
        tile: SmartTile,
        turnOn: Boolean = tile.isActive,
        brightnessPct: Float = tile.volume,
        colorArgb: Long = tile.lytmiColorArgb,
        syncMode: String = tile.lytmiSyncMode
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanIp = tile.ipAddress.ifBlank { bridge.twinklyIpAddress }.substringBefore(":").trim()
        if (cleanIp.isBlank()) return@withContext false
        val startMs = System.currentTimeMillis()
        var token = authenticateTwinklyXled(cleanIp) ?: return@withContext false

        fun postTwinklyJson(path: String, jsonBody: String): Boolean {
            return try {
                val resp = SharedHttpClient.requestText(
                    url = "http://$cleanIp$path",
                    method = "POST",
                    headers = mapOf("X-Auth-Token" to token),
                    body = jsonBody,
                    connectTimeoutMs = 2000,
                    readTimeoutMs = 2000
                )
                if (resp.code == 401) {
                    twinklyTokenByIp.remove(cleanIp)
                    token = authenticateTwinklyXled(cleanIp).orEmpty()
                }
                resp.isSuccessful
            } catch (e: Exception) {
                ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = "TWINKLY_XLED_HTTP",
                    operationStage = "sendTwinklyCommand",
                    hardwareId = tile.canonicalGuid.ifBlank { tile.id },
                    deviceName = tile.title,
                    ipAddress = cleanIp,
                    port = 80,
                    logToDiscoveryRecorder = false,
                    logToDeviceBlackBox = true
                )
                false
            }
        }

        if (!turnOn) {
            val ok = postTwinklyJson("/xled/v1/led/mode", """{"mode":"off"}""")
            return@withContext ok
        }

        val clampedBri = brightnessPct.roundToInt().coerceIn(1, 100)
        postTwinklyJson(
            "/xled/v1/led/out/brightness",
            JSONObject().put("mode", "enabled").put("type", "A").put("value", clampedBri).toString()
        )

        val red = ((colorArgb shr 16) and 0xFF).toInt().coerceIn(0, 255)
        val green = ((colorArgb shr 8) and 0xFF).toInt().coerceIn(0, 255)
        val blue = (colorArgb and 0xFF).toInt().coerceIn(0, 255)
        val modeUpper = syncMode.uppercase(Locale.US)
        val twinklyMode = when (modeUpper) {
            "VIDEO", "MOVIE", "GAME" -> "movie"
            "SCENE" -> "effect"
            else -> "color"
        }

        postTwinklyJson(
            "/xled/v1/led/color",
            JSONObject().put("red", red).put("green", green).put("blue", blue).toString()
        )
        val modeOk = postTwinklyJson(
            "/xled/v1/led/mode",
            JSONObject().put("mode", twinklyMode).toString()
        )
        val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(8L)
        JarvisLiveBridge.recordCommandLatency(
            integrationKey = "MULTI_PROTOCOL",
            displayName = "Matter / Tapo / Shelly / MQTT / HA",
            latencyMs = latency,
            succeeded = modeOk
        )
        modeOk
    }

    // -----------------------------------------------------------------------
    // CAMERA PORT & LOCAL RTSP PROBING
    // -----------------------------------------------------------------------
    suspend fun testCameraNetworkPorts(ipAddress: String, isEzviz: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val cleanIp = ipAddress.trim()
            if (cleanIp.isBlank()) return@withContext "Searching for camera on Wi-Fi..."
            val port554Open = isPortOpenInternal(cleanIp, 554, 1800)

            if (isEzviz) {
                val port8000Open = isPortOpenInternal(cleanIp, 8000, 1800)
                return@withContext when {
                    port554Open ->
                        "SUCCESS: Camera is awake and ready for live video."
                    port8000Open ->
                        "Doorbell is online on Wi-Fi and streaming via EZVIZ Cloud."
                    else ->
                        "Doorbell is in battery-saving standby. Open the EZVIZ app or sign in to wake it."
                }
            } else {
                val port37777Open = isPortOpenInternal(cleanIp, 37777, 1800)
                return@withContext when {
                    port554Open ->
                        "SUCCESS: Camera is awake and ready for live Wi-Fi video."
                    port37777Open ->
                        "Camera is online on Wi-Fi and streaming via Imou Cloud."
                    else ->
                        "Camera is in standby or using Imou Cloud."
                }
            }
        }

    suspend fun probeAndResolveEzvizLocalRtsp(tile: SmartTile): Result<String> =
        withContext(Dispatchers.IO) {
            if (tile.customRtspUrl.isNotBlank()) {
                return@withContext Result.success(tile.customRtspUrl.trim())
            }
            val cleanIp = tile.ipAddress.substringBefore(":").trim()
            val cleanCode = tile.secretCode.trim()
            if (cleanIp.isBlank()) {
                return@withContext Result.failure(Exception("NEEDS_EZVIZ_LOGIN"))
            }
            if (cleanCode.isBlank()) {
                return@withContext Result.failure(Exception("NEEDS_EZVIZ_CODE"))
            }

            val port8000Open = isPortOpenInternal(cleanIp, 8000, 1200)
            var port554Open = isPortOpenInternal(cleanIp, 554, 1500)
            if (!port554Open && port8000Open) {
                delay(300L)
                port554Open = isPortOpenInternal(cleanIp, 554, 1200)
            }

            if (!port554Open) {
                return@withContext if (port8000Open) {
                    Result.failure(Exception("PORT_8000_OPEN_554_CLOSED"))
                } else {
                    Result.failure(Exception("EZVIZ_ASLEEP_OR_IP_CHANGED"))
                }
            }

            val encodedPass = Uri.encode(cleanCode)
            val candidatePaths = if (tile.useSubStream) {
                listOf(
                    "/Streaming/Channels/102",
                    "/h264/ch1/sub/av_stream",
                    "/ch1/sub/av_stream",
                    "/live/ch1"
                )
            } else {
                listOf(
                    "/Streaming/Channels/101",
                    "/Streaming/Channels/102",
                    "/h264/ch1/main/av_stream",
                    "/ch1/main/av_stream",
                    "/live/ch0"
                )
            }

            for (path in candidatePaths) {
                val status = testRtspDescribeStatus(cleanIp, 554, path)
                if (status == 200 || status == 401) {
                    return@withContext Result.success("rtsp://admin:$encodedPass@$cleanIp:554$path")
                }
            }

            val defaultPath = if (tile.useSubStream) "/Streaming/Channels/102" else "/Streaming/Channels/101"
            Result.success("rtsp://admin:$encodedPass@$cleanIp:554$defaultPath")
        }

    private fun testRtspDescribeStatus(ip: String, port: Int, path: String): Int {
        return try {
            Socket().use { socket ->
                socket.soTimeout = 1600
                socket.connect(InetSocketAddress(ip, port), 1600)
                val req = "DESCRIBE rtsp://$ip:$port$path RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: LibVLC\r\n\r\n"
                socket.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val statusLine = reader.readLine() ?: return -1
                val parts = statusLine.split(" ")
                if (parts.size >= 2) parts[1].toIntOrNull() ?: -1 else -1
            }
        } catch (_: SocketTimeoutException) {
            -1
        } catch (_: ConnectException) {
            -1
        } catch (_: NoRouteToHostException) {
            -1
        } catch (e: Exception) {
            ErrorClassifier.recordHandledException(
                t = e,
                transportSource = "RTSP_PROBE",
                operationStage = "testRtspDescribeStatus",
                ipAddress = ip,
                port = port,
                logToDiscoveryRecorder = false
            )
            -1
        }
    }

    internal fun isPortOpenInternal(ip: String, port: Int, timeoutMs: Int): Boolean {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return false
        }
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
            }
            registerVerifiedLanNeighbor(ip)
            true
        } catch (se: SecurityException) {
            ErrorClassifier.recordHandledException(
                t = se,
                transportSource = "TCP_PORT_PROBE",
                operationStage = "isPortOpenInternal",
                ipAddress = ip,
                port = port,
                surfaceToUiBanner = true
            )
            false
        } catch (_: IOException) {
            false
        } catch (e: Exception) {
            ErrorClassifier.recordHandledException(
                t = e,
                transportSource = "TCP_PORT_PROBE",
                operationStage = "isPortOpenInternal",
                ipAddress = ip,
                port = port,
                logToDiscoveryRecorder = false
            )
            false
        }
    }

    fun readActiveArpHosts(subnetPrefix: String? = null): Map<String, String> {
        val ipToMac = LinkedHashMap<String, String>()
        for ((ip, mac) in CrossEcosystemIdentityCorrelator.getObservedIpToMacMap()) {
            if (subnetPrefix == null || ip.startsWith("$subnetPrefix.")) {
                if (mac.isNotBlank() || !ipToMac.containsKey(ip)) {
                    ipToMac[ip] = mac
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return ipToMac
        }
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("ip", "neigh", "show"))
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parts = line.trim().split(Regex("\\s+"))
                    val ip = parts.firstOrNull().orEmpty()
                    if (!ip.contains(".")) continue
                    if (subnetPrefix != null && !ip.startsWith("$subnetPrefix.")) continue
                    val lladdrIdx = parts.indexOf("lladdr")
                    val mac = if (lladdrIdx >= 0 && lladdrIdx + 1 < parts.size) {
                        parts[lladdrIdx + 1].uppercase(Locale.US)
                    } else ""
                    if (mac.length == 17 && mac != "00:00:00:00:00:00") {
                        ipToMac[ip] = mac
                        CrossEcosystemIdentityCorrelator.recordApplicationLayerObservation(ip, mac)
                    } else if (!line.contains("FAILED", ignoreCase = true)) {
                        ipToMac.putIfAbsent(ip, "")
                    }
                }
            }
        } catch (e: Exception) {
            ErrorClassifier.recordHandledException(
                t = e,
                transportSource = "ARP_NEIGHBOR_TABLE",
                operationStage = "readActiveArpHosts.ipNeigh",
                logToDiscoveryRecorder = false
            )
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            try {
                val arpFile = File("/proc/net/arp")
                if (arpFile.exists() && arpFile.canRead()) {
                    arpFile.useLines { lines ->
                        for (line in lines.drop(1)) {
                            val parts = line.trim().split(Regex("\\s+"))
                            if (parts.size >= 4) {
                                val ip = parts[0].trim()
                                val mac = parts[3].trim().uppercase(Locale.US)
                                if ((subnetPrefix == null || ip.startsWith("$subnetPrefix.")) &&
                                    mac.length == 17 && mac != "00:00:00:00:00:00"
                                ) {
                                    ipToMac[ip] = mac
                                    CrossEcosystemIdentityCorrelator.recordApplicationLayerObservation(ip, mac)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = "ARP_NEIGHBOR_TABLE",
                    operationStage = "readActiveArpHosts.procArp",
                    logToDiscoveryRecorder = false
                )
            }
        }
        return ipToMac
    }

    /**
     * Fingerprints an open RTSP port (554 / 8554) by inspecting the RTSP `OPTIONS` response headers
     * (`Server: Dahua Rtsp Server`, `Hikvision`, `EZVIZ`, `go2rtc`, `Ring`) and MAC OUI prefix.
     */
    private fun verifyRtspCameraDescriptor(ip: String, port: Int = 554, macHint: String = ""): Pair<TileType, BrandOption>? {
        val cleanMac = macHint.uppercase(Locale.US).replace(":", "").replace("-", "").take(6)
        val isHikvisionOui = cleanMac in setOf("C056E3", "4C62DF", "1868CB", "2857BE", "4419B6", "BCAD28", "E0508B")
        val isDahuaOrReolinkOui = cleanMac in setOf("EC71DB", "3CEF8C", "4C11BF", "9002A9", "A0BD1D", "38AF29")

        val probePaths = listOf(
            "/Streaming/Channels/101",
            "/cam/realmonitor?channel=1&subtype=0",
            "/h264Preview_01_main",
            "/"
        )
        for (path in probePaths) {
            try {
                Socket().use { socket ->
                    socket.soTimeout = 750
                    socket.connect(InetSocketAddress(ip, port), 600)
                    val req = "DESCRIBE rtsp://$ip:$port$path RTSP/1.0\r\nCSeq: 1\r\nAccept: application/sdp\r\nUser-Agent: ClaraSmartHome\r\n\r\n"
                    socket.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                    val fullResp = socket.getInputStream().bufferedReader().use { r ->
                        val sb = StringBuilder()
                        var lineCount = 0
                        while (lineCount < 28) {
                            val line = r.readLine() ?: break
                            sb.append(line).append('\n')
                            lineCount++
                            if (!r.ready() && sb.contains("\n\n")) break
                        }
                        sb.toString()
                    }
                    val lower = fullResp.lowercase(Locale.US)
                    if (!lower.startsWith("rtsp/1.0")) return null

                    // Negative exclusion: reject Apple AirPlay / AirTunes, NAS, and desktop media servers on port 554
                    if (lower.contains("airtunes") ||
                        lower.contains("airplay") ||
                        lower.contains("raop") ||
                        lower.contains("synology") ||
                        lower.contains("qnap") ||
                        lower.contains("plex") ||
                        lower.contains("windows media")
                    ) {
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.FILTERED,
                            transportSource = "RTSP_PORT_554",
                            ipAddress = ip,
                            port = port,
                            vendor = "Non-Camera RTSP Host",
                            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                            outcomeReason = "[INVALID_PHANTOM_DEVICE] Rejected non-camera RTSP server (AirPlay/NAS/Media) on port 554"
                        )
                        return null
                    }

                    val statusLine = lower.lineSequence().firstOrNull().orEmpty()
                    val isVideoSdp200 = statusLine.contains(" 200 ") && lower.contains("m=video")
                    val isCameraAuth401 = statusLine.contains(" 401 ") && (
                            isHikvisionOui ||
                                    isDahuaOrReolinkOui ||
                                    lower.contains("hikvision") ||
                                    lower.contains("ezviz") ||
                                    lower.contains("dahua") ||
                                    lower.contains("imou") ||
                                    lower.contains("reolink") ||
                                    lower.contains("onvif") ||
                                    lower.contains("ip camera") ||
                                    lower.contains("ipc") ||
                                    lower.contains("go2rtc") ||
                                    lower.contains("frigate")
                            )

                    if (isVideoSdp200 || isCameraAuth401) {
                        return when {
                            isHikvisionOui || lower.contains("hikvision") || lower.contains("ezviz") || lower.contains("dnvrs") ->
                                TileType.EZVIZ_DOORBELL to BrandOption.EZVIZ
                            lower.contains("ring") || lower.contains("go2rtc") || lower.contains("scrypted") || lower.contains("frigate") ->
                                TileType.RING_CAMERA to BrandOption.RING
                            else ->
                                TileType.IMOU_CAMERA to BrandOption.IMOU
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
} catch (e: ConnectException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
} catch (e: NoRouteToHostException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
} catch (e: Exception) {
                ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = "RTSP_PORT_554",
                    operationStage = "verifyRtspCameraDescriptor",
                    ipAddress = ip,
                    port = port,
                    logToDiscoveryRecorder = false
                )
            }
        }
        DiscoveryFlightRecorder.record(
            eventType = DiscoveryEventType.FILTERED,
            transportSource = "RTSP_PORT_554",
            ipAddress = ip,
            port = port,
            vendor = "Port 554 Host",
            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
            outcomeReason = "[INVALID_PHANTOM_DEVICE] Port 554 open without verified camera SDP or camera auth realm"
        )
        return null
    }

    private fun verifyHomeAssistantOnPort8123(ip: String): Boolean {
        val cleanIp = ip.substringBefore(":").trim()
        if (cleanIp.isBlank()) return false
        return try {
            val resp = SharedHttpClient.requestText(
                url = "http://$cleanIp:8123/manifest.json",
                connectTimeoutMs = 450,
                readTimeoutMs = 450
            )
            resp.body.contains("Home Assistant", ignoreCase = true) ||
                    resp.body.contains("home_assistant", ignoreCase = true)
        } catch (e: SocketTimeoutException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
    false
} catch (e: ConnectException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
    false
} catch (e: NoRouteToHostException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
    false
} catch (e: FileNotFoundException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
    false
} catch (e: Exception) {
            ErrorClassifier.recordHandledException(
                t = e,
                transportSource = "HOME_ASSISTANT_HTTP",
                operationStage = "verifyHomeAssistantOnPort8123",
                ipAddress = cleanIp,
                port = 8123,
                logToDiscoveryRecorder = false
            )
            false
        }
    }

    private fun probeShellyHueOrTasmotaOnPort80(ip: String): Triple<TileType, String, BrandOption>? {
        // 0. Check Twinkly XLED identification endpoint (`/xled/v1/gestalt`)
        probeTwinklyGestaltOnPort80(ip)?.let { tw ->
            return Triple(TileType.TWINKLY_LIGHT, tw.suggestedName, BrandOption.TWINKLY)
        }
        // 1A. Check Shelly Gen2 / Gen3 / Plus / Pro RPC endpoint (`/rpc/Shelly.GetDeviceInfo`)
        try {
            val rpcResp = SharedHttpClient.requestText(
                url = "http://$ip/rpc/Shelly.GetDeviceInfo",
                connectTimeoutMs = 450,
                readTimeoutMs = 450
            )
            if (rpcResp.isSuccessful) {
                val json = runCatching { JSONObject(rpcResp.body) }.getOrNull()
                if (json != null && (json.has("app") || json.has("model") || json.has("gen"))) {
                    val mac = json.optString("mac", "")
                    if (mac.isNotBlank()) registerVerifiedLanNeighbor(ip, mac)
                    val name = json.optString("name", "").takeIf { it.isNotBlank() }
                        ?: json.optString("app", "").takeIf { it.isNotBlank() }?.let { "Shelly $it" }
                        ?: "Shelly Gen2/Gen3 Relay"
                    return Triple(TileType.SHELLY_RELAY, name, BrandOption.SHELLY)
                }
            }
        } catch (e: SocketTimeoutException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
} catch (e: ConnectException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
} catch (e: NoRouteToHostException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
} catch (e: FileNotFoundException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
} catch (e: Exception) {
            ErrorClassifier.recordHandledException(
                t = e,
                transportSource = "SHELLY_RPC_PROBE",
                operationStage = "probeShellyGen2Rpc",
                ipAddress = ip,
                port = 80,
                logToDiscoveryRecorder = false
            )
        }
        // 1B. Check Shelly Gen1 Relay identification endpoint (`/shelly` — strict JSON schema check)
        try {
            val shellyResp = SharedHttpClient.requestText(
                url = "http://$ip/shelly",
                connectTimeoutMs = 450,
                readTimeoutMs = 450
            )
            if (shellyResp.isSuccessful) {
                val json = runCatching { JSONObject(shellyResp.body) }.getOrNull()
                val mac = json?.optString("mac", "")?.trim().orEmpty()
                val shellyType = json?.optString("type", "")?.trim().orEmpty()
                if (json != null && mac.length >= 12 && shellyType.isNotBlank()) {
                    registerVerifiedLanNeighbor(ip, mac)
                    val name = json.optString("name", "").takeIf { it.isNotBlank() } ?: "Shelly $shellyType"
                    return Triple(TileType.SHELLY_RELAY, name, BrandOption.SHELLY)
                }
            }
        } catch (e: SocketTimeoutException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
} catch (e: ConnectException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
} catch (e: NoRouteToHostException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
} catch (e: FileNotFoundException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
} catch (e: Exception) {
            ErrorClassifier.recordHandledException(
                t = e,
                transportSource = "SHELLY_HTTP_PROBE",
                operationStage = "probeShellyGen1",
                ipAddress = ip,
                port = 80,
                logToDiscoveryRecorder = false
            )
        }
        // 2. Check Tasmota HTTP endpoint (`/cm?cmnd=Status%200`)
        val tasmota = MqttAndDiyClient.probeTasmotaOnPort80(ip)
        if (tasmota != null) {
            return Triple(tasmota.suggestedType, tasmota.name, BrandOption.MQTT_TASMOTA_ESPHOME)
        }
        // 3. Suppress unimplemented /api/config Philips Hue hits instead of generating dead phantom cards
        try {
            val hueResp = SharedHttpClient.requestText(
                url = "http://$ip/api/config",
                connectTimeoutMs = 400,
                readTimeoutMs = 400
            )
            if (hueResp.isSuccessful) {
                val json = runCatching { JSONObject(hueResp.body) }.getOrNull()
                if (json != null && json.has("bridgeid") && json.has("swversion")) {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FILTERED,
                        transportSource = "HTTP_PORT_80",
                        ipAddress = ip,
                        port = 80,
                        vendor = "Philips Hue",
                        qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                        outcomeReason = "[INVALID_PHANTOM_DEVICE] Suppressed Hue Bridge /api/config until native Hue CLIP v2 pairing/control client is enabled"
                    )
                }
            }
        } catch (e: SocketTimeoutException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
} catch (e: ConnectException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
} catch (e: NoRouteToHostException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
} catch (e: FileNotFoundException) {
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
} catch (e: Exception) {
            ErrorClassifier.recordHandledException(
                t = e,
                transportSource = "HTTP_PORT_80",
                operationStage = "probeHueConfig",
                ipAddress = ip,
                port = 80,
                logToDiscoveryRecorder = false
            )
        }
        return null
    }

    /**
     * Discovers Shelly (`_shelly._tcp.`) and Home Assistant (`_home-assistant._tcp.`) via the
     * centralized coordinator while explicitly logging unsupported mDNS services (`_hap._tcp.`,
     * `_hue._tcp.`) as FILTERED instead of misclassifying them as Sonoff switches.
     */
    suspend fun discoverMdnsSmartHomeServices(
        context: Context,
        timeoutMs: Long = 1600L
    ): List<DiscoveredLanDevice> = withContext(Dispatchers.IO) {
        val discovered = ConcurrentHashMap<String, DiscoveredLanDevice>()
        val bridge = DeviceRepository.loadBridge(context)
        val serviceTypes = listOf(
            "_home-assistant._tcp.",
            "_shelly._tcp.",
            "_hap._tcp.",
            "_hue._tcp."
        )

        val resolvedServices = DiscoveryRadioCoordinator.discoverMdnsServices(
            context = context,
            serviceTypes = serviceTypes,
            timeoutMs = timeoutMs,
            transportLabel = "LAN_MDNS"
        )

        for (svc in resolvedServices) {
            val hostIp = svc.hostIp
            val port = if (svc.port > 0) svc.port else 80
            val serviceName = svc.serviceName
            val lowerType = svc.serviceType.lowercase(Locale.US)
            val extractedMdnsMac = CrossEcosystemIdentityCorrelator.extractMacFromApplicationPayload(
                ipAddress = hostIp,
                mdnsTxtAttributes = svc.txtAttributes,
                rawPayloadText = serviceName
            )
            registerVerifiedLanNeighbor(hostIp, extractedMdnsMac.ifBlank { serviceName })

            when {
                lowerType.contains("_hap.") || lowerType.contains("_hue.") -> {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FILTERED,
                        transportSource = "LAN_MDNS",
                        hardwareId = serviceName,
                        macAddress = extractedMdnsMac,
                        ipAddress = hostIp,
                        port = port,
                        vendor = if (lowerType.contains("_hap.")) "Apple HomeKit" else "Philips Hue",
                        qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                        outcomeReason = "Suppressed unsupported local controller ($lowerType); use Matter or Home Assistant bridge"
                    )
                }

                lowerType.contains("home-assistant") -> {
                    if (verifyHomeAssistantOnPort8123(hostIp)) {
                        val actionType = if (bridge.haToken.isBlank()) {
                            DiscoveredDeviceActionType.OAUTH_SIGN_IN
                        } else {
                            DiscoveredDeviceActionType.ADD_DIRECT
                        }
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.IDENTIFIED,
                            transportSource = "LAN_MDNS",
                            hardwareId = serviceName,
                            macAddress = extractedMdnsMac,
                            ipAddress = hostIp,
                            port = 8123,
                            vendor = "Home Assistant Hub",
                            confidenceScore = 0.95f,
                            qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                            outcomeReason = "Verified Home Assistant mDNS + HTTP 8123 manifest"
                        )
                        discovered["$hostIp:8123"] = DiscoveredLanDevice(
                            ipAddress = hostIp,
                            port = 8123,
                            suggestedName = "Home Assistant Hub",
                            suggestedType = TileType.SMARTLIFE_DEVICE,
                            detail = "Home Assistant \u2022 Verified via mDNS & manifest",
                            actionType = actionType,
                            protocolBadge = "Home Assistant",
                            hardwareIdOrMac = extractedMdnsMac
                        )
                    } else {
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.FILTERED,
                            transportSource = "LAN_MDNS",
                            hardwareId = serviceName,
                            macAddress = extractedMdnsMac,
                            ipAddress = hostIp,
                            port = port,
                            vendor = "Home Assistant Candidate",
                            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                            outcomeReason = "Rejected _home-assistant._tcp host: failed port 8123 manifest verification"
                        )
                    }
                }

                lowerType.contains("shelly") -> {
                    val verifiedShelly = probeShellyHueOrTasmotaOnPort80(hostIp)
                    if (verifiedShelly != null && verifiedShelly.first == TileType.SHELLY_RELAY) {
                        val shellyTitle = verifiedShelly.second.ifBlank { serviceName.ifBlank { "Shelly Smart Relay" } }
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.IDENTIFIED,
                            transportSource = "LAN_MDNS",
                            hardwareId = serviceName,
                            macAddress = extractedMdnsMac,
                            ipAddress = hostIp,
                            port = 80,
                            vendor = shellyTitle,
                            confidenceScore = 0.94f,
                            qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                            outcomeReason = "Verified Shelly mDNS + HTTP RPC/Gen1 schema"
                        )
                        discovered["$hostIp:80"] = DiscoveredLanDevice(
                            ipAddress = hostIp,
                            port = 80,
                            suggestedName = shellyTitle,
                            suggestedType = TileType.SHELLY_RELAY,
                            detail = "Shelly \u2022 Verified via mDNS & HTTP RPC",
                            actionType = DiscoveredDeviceActionType.ADD_DIRECT,
                            protocolBadge = "Shelly HTTP",
                            hardwareIdOrMac = extractedMdnsMac,
                            brandOption = BrandOption.SHELLY
                        )
                    } else {
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.FILTERED,
                            transportSource = "LAN_MDNS",
                            hardwareId = serviceName,
                            macAddress = extractedMdnsMac,
                            ipAddress = hostIp,
                            port = port,
                            vendor = "Shelly Candidate",
                            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                            outcomeReason = "Rejected _shelly._tcp host: failed HTTP /rpc/Shelly.GetDeviceInfo & /shelly verification"
                        )
                    }
                }
            }
        }
        discovered.values.toList()
    }

    /**
     * Listens for Tuya / SmartKnight / Lytmi / Pro Breeze local UDP broadcast packets on ports 6666 and 6667,
     * decrypts both v3.1/v3.2 AES-128-ECB frames (port 6666) and v3.3/v3.4/v3.5 AES-128-GCM frames
     * (port 6667), and registers any discovered hardware `gwId` + `ip` with `TuyaApiClient`.
     */
    private fun pollSingleTuyaUdpPortBlocking(
        udpPort: Int,
        timeoutMs: Long,
        found: ConcurrentHashMap<String, DiscoveredLanDevice>
    ) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 350
                bind(InetSocketAddress(udpPort))
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                val received = try {
                    socket.receive(packet)
                    true
                } catch (_: SocketTimeoutException) {
                    false
                } catch (rxErr: Throwable) {
                    ErrorClassifier.recordHandledException(
                        t = rxErr,
                        transportSource = "TUYA_UDP_$udpPort",
                        operationStage = "receiveUdpFrame",
                        port = udpPort
                    )
                    false
                }
                if (!received || packet.length <= 0) continue
                val senderIp = packet.address?.hostAddress.orEmpty()
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                    transportSource = "TUYA_UDP_$udpPort",
                    ipAddress = senderIp,
                    port = udpPort,
                    vendor = "Tuya UDP Broadcast",
                    confidenceScore = 0.25f,
                    qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                    outcomeReason = "UDP $udpPort broadcast packet received",
                    rawPayload = "len=${packet.length} bytes"
                )
                if (packet.length <= 28) {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FILTERED,
                        transportSource = "TUYA_UDP_$udpPort",
                        ipAddress = senderIp,
                        port = udpPort,
                        vendor = "UDP $udpPort Host",
                        qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                        outcomeReason = "Filtered truncated UDP $udpPort frame (${packet.length} bytes)"
                    )
                    continue
                }
                val jsonPayloadStr = decodeTuyaUdpBroadcastPayload(packet.data, packet.length)
                val parsedJson = jsonPayloadStr?.let { runCatching { JSONObject(it) }.getOrNull() }
                val ip = parsedJson?.optString("ip", "")?.takeIf { it.isNotBlank() } ?: senderIp
                val gwId = parsedJson?.optString("gwId", "")?.trim().orEmpty()
                val productKey = parsedJson?.optString("productKey", "")?.trim().orEmpty()
                val version = parsedJson?.optString("version", "")?.trim().orEmpty()
                if (parsedJson == null || gwId.isBlank()) {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FILTERED,
                        transportSource = "TUYA_UDP_$udpPort",
                        ipAddress = ip,
                        port = udpPort,
                        vendor = "UDP $udpPort Host",
                        qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                        outcomeReason = "Rejected UDP $udpPort packet: could not decrypt Tuya frame or missing gwId"
                    )
                    continue
                }
                if (ip.isNotBlank()) {
                    registerVerifiedLanNeighbor(ip, gwId)
                }
                TuyaApiClient.registerLanDiscoveredDeviceId(gwId)
                if (ip.isNotBlank()) {
                    TuyaApiClient.registerDeviceLanAddress(gwId, ip)
                }
                if (version.isNotBlank()) {
                    TuyaApiClient.protocolVersionByDevice[gwId] = version
                    ClaraApplication.appContextOrNull()?.let { appCtx ->
                        ClaraDeviceRegistryStore.getInstance(appCtx).updatePersistedProtocolVersion(gwId, version)
                    }
                }
                if (ip.isNotBlank()) {
                    val appCtx = ClaraApplication.appContextOrNull()
                    val hasVaultKey = appCtx != null &&
                            ClaraSecretVault.hasVerifiedLocalControlKey(appCtx, "TUYA_LAN", gwId, ip)
                    val isKnown = hasVaultKey || TuyaApiClient.isDeviceIdKnownInCloudOrSdk(gwId)
                    val isLytmi = productKey.contains("lytmi", true)
                    val suffix = if (gwId.length >= 4) " (${gwId.takeLast(4)})" else ""
                    val suggestedTitle = if (isLytmi) "Lytmi Sync Box$suffix" else "Smart Wi-Fi Plug$suffix"
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.IDENTIFIED,
                        transportSource = "TUYA_UDP_$udpPort",
                        hardwareId = gwId,
                        ipAddress = ip,
                        port = 6668,
                        vendor = suggestedTitle,
                        confidenceScore = 0.95f,
                        qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                        outcomeReason = "Decoded Tuya UDP $udpPort beacon (ver=${version.ifBlank { "3.x" }} known=$isKnown vaultKey=$hasVaultKey)",
                        rawPayload = jsonPayloadStr,
                        cloudId = gwId,
                        protocolResultCode = "TUYA_V${version.ifBlank { "3.3" }}"
                    )
                    found[ip] = DiscoveredLanDevice(
                        ipAddress = ip,
                        port = 6668,
                        suggestedName = suggestedTitle,
                        suggestedType = if (isLytmi) TileType.LYTMI_SYNC_BOX else TileType.SMARTKNIGHT_PLUG,
                        detail = if (isKnown) {
                            "Smart Device \u2022 Verified on Home Wi-Fi"
                        } else {
                            "On Wi-Fi \u2022 Sign in to SmartLife or reset to pair"
                        },
                        companionPackage = "com.tuya.smartlife",
                        actionType = if (isKnown) {
                            DiscoveredDeviceActionType.ADD_DIRECT
                        } else {
                            DiscoveredDeviceActionType.OAUTH_SIGN_IN
                        },
                        protocolBadge = if (isKnown) "Wi-Fi LAN" else "Account Link Required",
                        hardwareIdOrMac = gwId,
                        brandOption = if (isLytmi) BrandOption.LYTMI else BrandOption.SMARTKNIGHT
                    )
                }
            }
        } catch (t: Throwable) {
            ErrorClassifier.recordHandledException(
                t = t,
                transportSource = "TUYA_UDP_$udpPort",
                operationStage = "pollSingleTuyaUdpPortBlocking",
                port = udpPort
            )
        } finally {
            ErrorClassifier.closeQuietly(socket, "TUYA_UDP_$udpPort")
        }
    }

    suspend fun listenForTuyaUdpBroadcasts(timeoutMs: Long = 1400L): List<DiscoveredLanDevice> =
        withContext(Dispatchers.IO) {
            DiscoveryRadioCoordinator.withMulticastLock(ClaraApplication.appContextOrNull(), "tuya_udp_6666_6667") {
                val found = ConcurrentHashMap<String, DiscoveredLanDevice>()
                try {
                    kotlinx.coroutines.supervisorScope {
                        val t1 = async(Dispatchers.IO) {
                            pollSingleTuyaUdpPortBlocking(6666, timeoutMs, found)
                        }
                        val t2 = async(Dispatchers.IO) {
                            pollSingleTuyaUdpPortBlocking(6667, timeoutMs, found)
                        }
                        t1.await()
                        t2.await()
                    }
                } catch (e: Exception) {
                    ErrorClassifier.recordHandledException(
                        t = e,
                        transportSource = "TUYA_UDP_6666_6667",
                        operationStage = "listenForTuyaUdpBroadcasts"
                    )
                }
                found.values.toList()
            }
        }

    private fun decodeTuyaUdpBroadcastPayload(rawBytes: ByteArray, length: Int): String? {
        if (length <= 28) return null
        val payloadBytes = rawBytes.copyOfRange(20, length - 8)
        val rawStr = String(payloadBytes, Charsets.UTF_8)
        val firstBrace = rawStr.indexOf('{')
        val lastBrace = rawStr.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return rawStr.substring(firstBrace, lastBrace + 1)
        }
        var lastCryptoError: Exception? = null
        // 1. Try Tuya v3.1 / v3.2 AES-128-ECB (UDP Port 6666)
        try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(tuyaUdpAesKey, "AES"))
            val decrypted = String(cipher.doFinal(payloadBytes), Charsets.UTF_8)
            val start = decrypted.indexOf('{')
            val end = decrypted.lastIndexOf('}')
            if (start >= 0 && end > start) return decrypted.substring(start, end + 1)
        } catch (ecbEx: Exception) {
            lastCryptoError = ecbEx
        }
        // 2. Try Tuya v3.3 / v3.4 / v3.5 AES-128-GCM (UDP Port 6667: 12-byte IV at offset 20, 16-byte tag before 4-byte suffix)
        try {
            if (length > 48) {
                val iv = rawBytes.copyOfRange(20, 32)
                val cipherAndTag = rawBytes.copyOfRange(32, length - 4)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val spec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(tuyaUdpAesKey, "AES"), spec)
                cipher.updateAAD(rawBytes, 4, 16)
                val plainBytes = cipher.doFinal(cipherAndTag)
                val decrypted = String(plainBytes, Charsets.UTF_8)
                val start = decrypted.indexOf('{')
                val end = decrypted.lastIndexOf('}')
                if (start >= 0 && end > start) return decrypted.substring(start, end + 1)
            }
        } catch (gcmAadEx: Exception) {
            lastCryptoError = gcmAadEx
            try {
                if (length > 48) {
                    val iv = rawBytes.copyOfRange(20, 32)
                    val cipherAndTag = rawBytes.copyOfRange(32, length - 4)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val spec = GCMParameterSpec(128, iv)
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(tuyaUdpAesKey, "AES"), spec)
                    val plainBytes = cipher.doFinal(cipherAndTag)
                    val decrypted = String(plainBytes, Charsets.UTF_8)
                    val start = decrypted.indexOf('{')
                    val end = decrypted.lastIndexOf('}')
                    if (start >= 0 && end > start) return decrypted.substring(start, end + 1)
                }
            } catch (gcmPlainEx: Exception) {
                lastCryptoError = gcmPlainEx
            }
        }
        if (lastCryptoError != null) {
            ErrorClassifier.recordHandledException(
                t = lastCryptoError,
                transportSource = "TUYA_UDP_CRYPTO",
                operationStage = "decodeTuyaUdpBroadcastPayload",
                logToDiscoveryRecorder = false
            )
        }
        return null
    }

    internal fun resolveLocalSubnetPrefixInternal(context: Context): String? {
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val linkProps = cm?.getLinkProperties(cm.activeNetwork)
            if (linkProps != null) {
                for (linkAddr in linkProps.linkAddresses) {
                    val addr = linkAddr.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        val parts = host.split(".")
                        if (parts.size == 4) return "${parts[0]}.${parts[1]}.${parts[2]}"
                    }
                }
            }
        } catch (e: Exception) {
            ErrorClassifier.recordHandledException(
                t = e,
                transportSource = "CONNECTIVITY_MANAGER",
                operationStage = "resolveLocalSubnetPrefixInternal.linkProperties",
                logToDiscoveryRecorder = e is SecurityException
            )
        }
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ipInt = wm?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val b1 = ipInt and 0xff
                val b2 = (ipInt shr 8) and 0xff
                val b3 = (ipInt shr 16) and 0xff
                if (b1 != 0) return "$b1.$b2.$b3"
            }
        } catch (e: Exception) {
            ErrorClassifier.recordHandledException(
                t = e,
                transportSource = "WIFI_MANAGER",
                operationStage = "resolveLocalSubnetPrefixInternal.wifiInfo",
                logToDiscoveryRecorder = e is SecurityException
            )
        }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (nif.isLoopback || !nif.isUp) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        val parts = host.split(".")
                        if (parts.size == 4) return "${parts[0]}.${parts[1]}.${parts[2]}"
                    }
                }
            }
        } catch (e: Exception) {
            ErrorClassifier.recordHandledException(
                t = e,
                transportSource = "NETWORK_INTERFACE",
                operationStage = "resolveLocalSubnetPrefixInternal.nif",
                logToDiscoveryRecorder = false
            )
        }
        return null
    }

    private const val TUYA_COMPANY_ID_LE = 0x07D0
    private const val DEFAULT_BLE_RSSI_HARD_FLOOR_DBM = -88
    private const val WEAK_BLE_SIGNAL_THRESHOLD_DBM = -78

    private val TUYA_BLE_FD50_UUID: ParcelUuid =
        ParcelUuid.fromString("0000fd50-0000-1000-8000-00805f9b34fb")
    private val MATTER_BLE_FFF6_UUID: ParcelUuid =
        ParcelUuid.fromString("0000fff6-0000-1000-8000-00805f9b34fb")

    private val latestBleRssiByMac = ConcurrentHashMap<String, Int>()

    fun getLastObservedBleRssi(macOrId: String): Int? {
        val norm = CrossEcosystemIdentityCorrelator.normalizeMac(macOrId)
        return if (norm.isNotEmpty()) latestBleRssiByMac[norm] else null
    }

    /**
     * Onboarding Section 1 & Section 4:
     * Isolated BLE setup pass for unconfigured Tuya Combo (0xFD50) & Matter (0xFFF6) beacons.
     * - Parses 0xFD50 service data & 0x07D0 manufacturer payload bound bit (0x80) to reject
     *   already-paired plugs broadcasting continuous local-control beacons.
     * - Drops distant/neighbouring beacons weaker than [rssiHardFloorDbm] (-88 dBm default;
     *   marks < -78 dBm as weak signal) and sorts strongest-RSSI-first.
     * - Suppresses combo devices whose +1/-1/+2/-2 Wi-Fi MAC variant is already onboarded.
     */
    @SuppressLint("MissingPermission")
    suspend fun discoverBleSetupPass(
        context: Context,
        scanDurationMs: Long = 8_000L,
        rssiHardFloorDbm: Int = DEFAULT_BLE_RSSI_HARD_FLOOR_DBM
    ): List<DiscoveredLanDevice> = withContext(Dispatchers.IO) {
        DiscoveryRadioCoordinator.withIsolatedRadioPass(OnboardingScanRadioPass.BLE_SETUP_ONLY, "ble_setup_scan") {
            ErrorClassifier.auditRadioPermissionsAndServices(
                context = context,
                transportSource = "BLE_SETUP_PASS",
                checkBle = true,
                checkWifi = false
            )
            val btManager = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter
            val scanner = runCatching { adapter?.bluetoothLeScanner }.getOrNull()
            if (adapter == null || !adapter.isEnabled || scanner == null) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "BLE_SETUP_PASS",
                    vendor = "Android BluetoothLeScanner",
                    qualificationStage = "BLE_PREFLIGHT",
                    outcomeReason = "Bluetooth adapter disabled or BLE scanner unavailable",
                    protocolResultCode = "BLE_DISABLED"
                )
                return@withIsolatedRadioPass emptyList()
            }

            data class BleSetupHit(
                val normalizedMac: String,
                val rawMacColon: String,
                val displayName: String,
                val isMatterFff6: Boolean,
                val strongestRssiDbm: Int
            )

            val hitsByMac = ConcurrentHashMap<String, BleSetupHit>()
            val filteredReasonsLogged = ConcurrentHashMap.newKeySet<String>()

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
                    val sr = result ?: return
                    val record = sr.scanRecord ?: return
                    val rawAddr = sr.device?.address?.trim().orEmpty()
                    val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(rawAddr)
                    if (normMac.length != 12) return

                    val uuids = record.serviceUuids.orEmpty()
                    val fd50ServiceData = record.getServiceData(TUYA_BLE_FD50_UUID)
                    val fff6ServiceData = record.getServiceData(MATTER_BLE_FFF6_UUID)
                    val tuyaMfgData = record.getManufacturerSpecificData(TUYA_COMPANY_ID_LE)

                    val hasFd50 = fd50ServiceData != null || uuids.contains(TUYA_BLE_FD50_UUID) || tuyaMfgData != null
                    val hasFff6 = fff6ServiceData != null || uuids.contains(MATTER_BLE_FFF6_UUID)
                    if (!hasFd50 && !hasFff6) return

                    val rssi = sr.rssi
                    latestBleRssiByMac[normMac] = rssi

                    if (filteredReasonsLogged.add("adv:$normMac")) {
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                            transportSource = "BLE_SETUP_PASS",
                            hardwareId = normMac,
                            macAddress = normMac,
                            vendor = if (hasFff6) "Matter BLE Beacon" else "Tuya BLE 0xFD50 Beacon",
                            confidenceScore = 0.25f,
                            qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                            outcomeReason = "Received BLE setup service beacon",
                            rssiDbm = rssi,
                            protocolResultCode = if (hasFff6) "0xFFF6" else "0xFD50"
                        )
                    }

                    // 1. Enforce RSSI floor
                    if (rssi < rssiHardFloorDbm) {
                        if (filteredReasonsLogged.add("rssi:$normMac")) {
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.FILTERED,
                                transportSource = "BLE_SETUP_PASS",
                                hardwareId = normMac,
                                macAddress = normMac,
                                vendor = "Weak BLE Beacon",
                                qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                outcomeReason = "Dropped distant BLE setup beacon ($rssi dBm < $rssiHardFloorDbm dBm floor)",
                                rssiDbm = rssi,
                                protocolResultCode = "BLE_RSSI_BELOW_FLOOR"
                            )
                        }
                        return
                    }

                    // 2. Parse Tuya 0xFD50 / 0x07D0 bound pairing bit (Bit 7 = 0x80 indicates already-paired local beacon)
                    if (hasFd50 && !hasFff6) {
                        val serviceBound = fd50ServiceData != null &&
                                fd50ServiceData.isNotEmpty() &&
                                (fd50ServiceData[0].toInt() and 0x80) != 0
                        val mfgBound = tuyaMfgData != null &&
                                tuyaMfgData.isNotEmpty() &&
                                (tuyaMfgData[0].toInt() and 0x80) != 0
                        if (serviceBound || mfgBound) {
                            if (filteredReasonsLogged.add("bound:$normMac")) {
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.FILTERED,
                                    transportSource = "BLE_SETUP_PASS",
                                    hardwareId = normMac,
                                    macAddress = normMac,
                                    vendor = "Paired Tuya BLE Plug",
                                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                    outcomeReason = "Ignored already-paired BLE 0xFD50 local-control beacon (bound bit 0x80=1)",
                                    rssiDbm = rssi,
                                    protocolResultCode = "BLE_0xFD50_BOUND"
                                )
                            }
                            return
                        }
                    }

                    // 3. Suppress combo plugs already onboarded on Wi-Fi (+1/-1/+2/-2 MAC offset correlation)
                    val matchedExistingGuid = CrossEcosystemIdentityCorrelator.resolveCanonicalGuidForCandidate(
                        rawMac = normMac
                    )
                    if (!matchedExistingGuid.isNullOrBlank()) {
                        if (filteredReasonsLogged.add("correl:$normMac")) {
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.FILTERED,
                                transportSource = "BLE_SETUP_PASS",
                                hardwareId = normMac,
                                macAddress = normMac,
                                vendor = "Combo Device",
                                qualificationStage = "STAGE_4_VALIDATED_DEVICE",
                                outcomeReason = "Suppressed BLE combo beacon matching onboarded registry GUID ($matchedExistingGuid)",
                                rssiDbm = rssi,
                                protocolResultCode = "COMBO_MAC_ONBOARDED"
                            )
                        }
                        return
                    }

                    val rawDevName = runCatching { sr.device?.name?.trim().orEmpty() }.getOrDefault("")
                        .ifBlank { record.deviceName?.trim().orEmpty() }
                    val cleanConsumerName = when {
                        hasFff6 -> "Matter Smart Device"
                        rawDevName.contains("lytmi", ignoreCase = true) -> "Lytmi Sync Box"
                        rawDevName.contains("breeze", ignoreCase = true) || rawDevName.contains("fan", ignoreCase = true) ->
                            "Smart Tower Fan"
                        rawDevName.isNotBlank() && !rawDevName.startsWith("TY", ignoreCase = true) && rawDevName.length >= 3 ->
                            rawDevName
                        else -> "Smart Plug / Switch"
                    }

                    hitsByMac.compute(normMac) { _, existing ->
                        if (existing == null || rssi > existing.strongestRssiDbm) {
                            BleSetupHit(
                                normalizedMac = normMac,
                                rawMacColon = rawAddr,
                                displayName = cleanConsumerName,
                                isMatterFff6 = hasFff6,
                                strongestRssiDbm = rssi
                            )
                        } else {
                            existing
                        }
                    }
                }
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            try {
                scanner.startScan(null, settings, callback)
                delay(scanDurationMs.coerceIn(1_500L, 15_000L))
            } catch (e: Exception) {
                ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = "BLE_SETUP_PASS",
                    operationStage = "startBleScan",
                    surfaceToUiBanner = true
                )
            } finally {
                runCatching { scanner.stopScan(callback) }
            }

            hitsByMac.values
                .sortedByDescending { it.strongestRssiDbm }
                .mapIndexed { idx, hit ->
                    val signalHint = when {
                        hit.strongestRssiDbm >= -65 -> "Strong signal"
                        hit.strongestRssiDbm >= WEAK_BLE_SIGNAL_THRESHOLD_DBM -> "Good signal"
                        else -> "Weak signal \u2022 Move closer if needed"
                    }
                    val numberedName = if (hitsByMac.size > 1) {
                        "${hit.displayName} (${idx + 1})"
                    } else {
                        hit.displayName
                    }
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.PRESENTED,
                        transportSource = "BLE_SETUP_PASS",
                        hardwareId = hit.normalizedMac,
                        macAddress = hit.normalizedMac,
                        vendor = numberedName,
                        confidenceScore = 0.96f,
                        qualificationStage = "STAGE_5_ONBOARDABLE_DEVICE",
                        outcomeReason = "Verified unconfigured BLE setup beacon ($signalHint, ${hit.strongestRssiDbm} dBm)",
                        rssiDbm = hit.strongestRssiDbm,
                        protocolResultCode = if (hit.isMatterFff6) "MATTER_BLE_READY" else "TUYA_BLE_UNBOUND"
                    )
                    DiscoveredLanDevice(
                        ipAddress = "",
                        port = 0,
                        suggestedName = numberedName,
                        suggestedType = if (hit.isMatterFff6) TileType.MATTER_DEVICE else TileType.SMARTKNIGHT_PLUG,
                        detail = "Ready for Wi-Fi setup \u2022 $signalHint",
                        companionPackage = if (hit.isMatterFff6) "com.google.android.apps.chromecast.app" else "com.tuya.smartlife",
                        actionType = DiscoveredDeviceActionType.START_PAIRING,
                        protocolBadge = if (hit.isMatterFff6) "Matter Setup" else "Bluetooth Setup",
                        hardwareIdOrMac = hit.rawMacColon.ifBlank { hit.normalizedMac },
                        brandOption = if (hit.isMatterFff6) BrandOption.MATTER else BrandOption.SMARTKNIGHT
                    )
                }
        }
    }

    /**
     * Onboarding Section 1 & Section 5:
     * Isolated SoftAP hotspot scan pass using boot-relative ScanResult.timestamp filtering
     * (under 15 seconds on fresh scans) to eliminate cached SmartLife-XXXX hotspot ghosts.
     */
    suspend fun discoverSoftApPass(
        context: Context,
        freshCutoffSeconds: Long = 15L
    ): List<DiscoveredLanDevice> = withContext(Dispatchers.IO) {
        DiscoveryRadioCoordinator.withIsolatedRadioPass(OnboardingScanRadioPass.SOFT_AP_ONLY, "soft_ap_scan") {
            ErrorClassifier.auditRadioPermissionsAndServices(
                context = context,
                transportSource = "SOFT_AP_PASS",
                checkBle = false,
                checkWifi = true
            )
            val freshHotspots = DiscoveryRadioCoordinator.scanFreshSetupSoftApHotspots(
                context = context,
                freshCutoffSeconds = freshCutoffSeconds
            )
            freshHotspots.map { ap ->
                val lower = ap.ssid.lowercase(Locale.US)
                val (tileType, brand, friendlyLabel) = when {
                    lower.startsWith("shelly") -> Triple(TileType.SHELLY_RELAY, BrandOption.SHELLY, "Shelly Wi-Fi Relay")
                    lower.startsWith("tapo_") || lower.startsWith("tp-link_") ->
                        Triple(TileType.TAPO_DEVICE, BrandOption.TAPO, "TP-Link Smart Plug")
                    lower.startsWith("itead-") || lower.startsWith("sonoff-") ->
                        Triple(TileType.SONOFF_SWITCH, BrandOption.SONOFF, "Sonoff Smart Switch")
                    else -> Triple(TileType.SMARTKNIGHT_PLUG, BrandOption.SMARTKNIGHT, "Smart Plug / Device (${ap.ssid})")
                }
                val normBssid = CrossEcosystemIdentityCorrelator.normalizeMac(ap.bssid)
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.PRESENTED,
                    transportSource = "SOFT_AP_PASS",
                    hardwareId = ap.ssid,
                    macAddress = normBssid,
                    vendor = friendlyLabel,
                    confidenceScore = 0.96f,
                    qualificationStage = "STAGE_5_ONBOARDABLE_DEVICE",
                    outcomeReason = "Verified live setup Wi-Fi hotspot '${ap.ssid}' (age=${ap.ageMs}ms, ${ap.rssiDbm} dBm)",
                    wifiFrequencyMhz = ap.frequencyMhz,
                    rssiDbm = ap.rssiDbm,
                    protocolResultCode = "SOFTAP_LIVE"
                )
                DiscoveredLanDevice(
                    ipAddress = ap.ssid,
                    port = 6668,
                    suggestedName = friendlyLabel,
                    suggestedType = tileType,
                    detail = "Wi-Fi Hotspot '${ap.ssid}' \u2022 Ready for AP Mode setup",
                    companionPackage = brand.companionPackage,
                    actionType = DiscoveredDeviceActionType.START_PAIRING,
                    protocolBadge = "AP Hotspot Setup",
                    hardwareIdOrMac = normBssid.ifBlank { ap.ssid },
                    brandOption = brand
                )
            }
        }
    }

    /**
     * Onboarding Section 1, Section 2 & Section 3:
     * Isolated LAN / Ethernet discovery pass (SSDP, Cast, mDNS, Matter operational mDNS,
     * Tapo/Kasa, Sonoff, ESPHome, Tuya UDP, Govee, Twinkly, ONVIF, and verified HTTP/RTSP/SSAP hosts).
     * Never activates BLE or Wi-Fi SoftAP scanning.
     */
    suspend fun discoverLanNetworkPass(
        context: Context,
        includeFullPortSweep: Boolean = true
    ): List<DiscoveredLanDevice> = withContext(Dispatchers.IO) {
        DiscoveryRadioCoordinator.withIsolatedRadioPass(OnboardingScanRadioPass.LAN_ONLY, "lan_network_scan") {
            ErrorClassifier.auditRadioPermissionsAndServices(
                context = context,
                transportSource = "LAN_ONLY_PASS",
                checkBle = false,
                checkWifi = true
            )
            val prefix = resolveLocalSubnetPrefixInternal(context)
            val bridge = DeviceRepository.loadBridge(context)
            val results = ConcurrentHashMap.newKeySet<DiscoveredLanDevice>()

            coroutineScope {
                val ssdpJob = async(Dispatchers.IO) {
                    val ssdpTvs = LgThinQWebOsClient.discoverLgTvsViaSsdp(timeoutMs = 1500L)
                    for ((tvIp, tvName) in ssdpTvs) {
                        val learnedTvMac = CrossEcosystemIdentityCorrelator.normalizeMac(
                            LgThinQWebOsClient.getSavedMacAddress(context, tvIp)
                                .ifBlank { CrossEcosystemIdentityCorrelator.getObservedIpToMacMap()[tvIp].orEmpty() }
                        )
                        registerVerifiedLanNeighbor(tvIp, learnedTvMac)
                        // Section 2: Verify ClaraSecretVault holds an SSAP client-key for target IP or MAC
                        val hasVaultKey = LgThinQWebOsClient.hasVerifiedVaultKey(context, tvIp, learnedTvMac)
                        results.add(
                            DiscoveredLanDevice(
                                ipAddress = tvIp,
                                port = 3001,
                                suggestedName = tvName,
                                suggestedType = TileType.LG_TV,
                                detail = if (hasVaultKey) {
                                    "LG Smart TV \u2022 Paired & Ready on Network"
                                } else {
                                    "LG Smart TV \u2022 Requires Accept on TV screen"
                                },
                                companionPackage = "com.lgeha.nuts",
                                actionType = if (hasVaultKey) {
                                    DiscoveredDeviceActionType.ADD_DIRECT
                                } else {
                                    DiscoveredDeviceActionType.START_PAIRING
                                },
                                protocolBadge = if (hasVaultKey) "Smart TV Ready" else "TV Pairing Required",
                                hardwareIdOrMac = learnedTvMac,
                                brandOption = BrandOption.LG_THINQ
                            )
                        )
                    }
                }

                val castJob = async(Dispatchers.IO) {
                    val castDevices = GoogleCastLanClient.discoverCastDevices(
                        context = context,
                        includeFullPortSweep = includeFullPortSweep
                    )
                    for (cast in castDevices) {
                        registerVerifiedLanNeighbor(cast.ipAddress, cast.id)
                        val isVideoCast = GoogleCastLanClient.isVideoCastDevice(cast)
                        results.add(
                            DiscoveredLanDevice(
                                ipAddress = "${cast.ipAddress}:${cast.port}",
                                port = cast.port,
                                suggestedName = cast.name,
                                suggestedType = TileType.NEST_MINI,
                                detail = "${cast.modelName} \u2022 Ready on Wi-Fi",
                                companionPackage = "com.google.android.apps.chromecast.app",
                                actionType = DiscoveredDeviceActionType.ADD_DIRECT,
                                protocolBadge = if (isVideoCast) "Google Cast Video" else "Google Cast Speaker",
                                hardwareIdOrMac = cast.id,
                                brandOption = BrandOption.NEST
                            )
                        )
                    }
                }

                val mdnsJob = async(Dispatchers.IO) {
                    val mdnsDevices = discoverMdnsSmartHomeServices(context, timeoutMs = 1500L)
                    results.addAll(mdnsDevices)
                }

                val matterJob = async(Dispatchers.IO) {
                    val matterList = MatterProtocolClient.discoverMatterDevices(context, timeoutMs = 1500L)
                    for (m in matterList) {
                        registerVerifiedLanNeighbor(m.ipAddress, m.nodeId)
                        results.add(
                            DiscoveredLanDevice(
                                ipAddress = m.ipAddress,
                                port = m.port,
                                suggestedName = m.displayName,
                                suggestedType = m.suggestedType,
                                detail = "${m.vendorName} \u2022 Matter ${if (m.isCommissionedOperational) "Multi-Admin Ready" else "Ready to Pair"}",
                                companionPackage = "com.google.android.apps.chromecast.app",
                                actionType = m.actionType,
                                protocolBadge = if (m.isCommissionedOperational) "Matter Fabric" else "Matter Pairing",
                                hardwareIdOrMac = m.nodeId,
                                brandOption = BrandOption.MATTER
                            )
                        )
                    }
                }

                val kasaTapoJob = async(Dispatchers.IO) {
                    val kasaTapoList = TpLinkTapoKasaClient.discoverLocalKasaAndTapoDevices(context)
                    for (kt in kasaTapoList) {
                        if (kt.ipAddress.isNotBlank()) {
                            registerVerifiedLanNeighbor(kt.ipAddress, kt.macAddress.ifBlank { kt.deviceId })
                            results.add(
                                DiscoveredLanDevice(
                                    ipAddress = kt.ipAddress,
                                    port = if (kt.isKasaProtocol) 9999 else 80,
                                    suggestedName = kt.name,
                                    suggestedType = kt.suggestedType,
                                    detail = "${kt.model} \u2022 ${if (kt.requiresAuth) "Sign in with TP-Link ID" else "Ready on Wi-Fi"}",
                                    companionPackage = "com.tplink.iot",
                                    actionType = kt.actionType,
                                    protocolBadge = if (kt.isKasaProtocol) "TP-Link Kasa" else "TP-Link Tapo",
                                    hardwareIdOrMac = kt.deviceId,
                                    brandOption = BrandOption.TAPO
                                )
                            )
                        }
                    }
                }

                val sonoffMdnsJob = async(Dispatchers.IO) {
                    val sonoffList = EWeLinkSonoffClient.discoverLocalSonoffMdns(context, timeoutMs = 1400L)
                    for (sn in sonoffList) {
                        if (sn.ipAddress.isNotBlank()) {
                            registerVerifiedLanNeighbor(sn.ipAddress, sn.deviceId)
                            results.add(
                                DiscoveredLanDevice(
                                    ipAddress = sn.ipAddress,
                                    port = sn.port,
                                    suggestedName = sn.name,
                                    suggestedType = sn.suggestedType,
                                    detail = "${sn.model} \u2022 ${if (sn.requiresAuth) "Sign in to eWeLink" else "Ready on Wi-Fi"}",
                                    companionPackage = "com.coolkit",
                                    actionType = sn.actionType,
                                    protocolBadge = "Sonoff Wi-Fi",
                                    hardwareIdOrMac = sn.deviceId,
                                    brandOption = BrandOption.SONOFF
                                )
                            )
                        }
                    }
                }

                val espHomeMdnsJob = async(Dispatchers.IO) {
                    val espList = MqttAndDiyClient.discoverEspHomeMdns(context, timeoutMs = 1400L)
                    for (esp in espList) {
                        if (esp.ipAddress.isNotBlank()) {
                            registerVerifiedLanNeighbor(esp.ipAddress, esp.uniqueId)
                            results.add(
                                DiscoveredLanDevice(
                                    ipAddress = esp.ipAddress,
                                    port = 80,
                                    suggestedName = esp.name,
                                    suggestedType = esp.suggestedType,
                                    detail = "ESPHome \u2022 Ready on Wi-Fi",
                                    actionType = DiscoveredDeviceActionType.ADD_DIRECT,
                                    protocolBadge = "ESPHome",
                                    hardwareIdOrMac = esp.uniqueId,
                                    brandOption = BrandOption.MQTT_TASMOTA_ESPHOME
                                )
                            )
                        }
                    }
                }

                val tuyaUdpJob = async(Dispatchers.IO) {
                    results.addAll(listenForTuyaUdpBroadcasts(timeoutMs = 1400L))
                }

                val goveeJob = async(Dispatchers.IO) {
                    results.addAll(discoverGoveeDevices(bridge = bridge, timeoutMs = 1200L))
                }

                val twinklyJob = async(Dispatchers.IO) {
                    results.addAll(discoverTwinklyDevices(context = context, timeoutMs = 1200L))
                }

                val onvifJob = async(Dispatchers.IO) {
                    results.addAll(discoverOnvifCamerasViaWsDiscovery(timeoutMs = 1100L))
                }

                val arpHostMap = readActiveArpHosts(prefix)
                val candidateHostIps: List<String> = when {
                    prefix == null -> emptyList()
                    !includeFullPortSweep && arpHostMap.isNotEmpty() -> arpHostMap.keys.toList()
                    includeFullPortSweep -> (1..254).map { "$prefix.$it" }
                    else -> emptyList()
                }

                val subnetJobs = if (candidateHostIps.isNotEmpty()) {
                    candidateHostIps.chunked(32).flatMap { chunk ->
                        chunk.map { ip ->
                            async(Dispatchers.IO) {
                                val macHint = arpHostMap[ip].orEmpty()

                                // Change 4.2 + Section 2: Only surface TCP 3000/3001 if vault holds verified LG key + SSAP banner passes
                                val alreadyFoundTv = results.any { it.suggestedType == TileType.LG_TV && it.ipAddress == ip }
                                val port3001Open = !alreadyFoundTv && isPortOpenInternal(ip, 3001, 250)
                                val port3000Open = !alreadyFoundTv && !port3001Open && isPortOpenInternal(ip, 3000, 250)
                                if (port3001Open || port3000Open) {
                                    val openTvPort = if (port3001Open) 3001 else 3000
                                    DiscoveryFlightRecorder.record(
                                        eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                                        transportSource = "TCP_PORT_PROBE",
                                        macAddress = macHint,
                                        ipAddress = ip,
                                        port = openTvPort,
                                        vendor = "TCP $openTvPort Host",
                                        confidenceScore = 0.15f,
                                        qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                                        outcomeReason = "Open TCP port $openTvPort responder detected"
                                    )
                                    if (LgThinQWebOsClient.hasVerifiedVaultKey(context, ip, macHint) &&
                                        LgThinQWebOsClient.probeVerifiedWebOsSsapBanner(ip, 1400)
                                    ) {
                                        DiscoveryFlightRecorder.record(
                                            eventType = DiscoveryEventType.IDENTIFIED,
                                            transportSource = "LG_WEBOS_SSAP",
                                            macAddress = macHint,
                                            ipAddress = ip,
                                            port = 3001,
                                            vendor = "LG Smart TV",
                                            confidenceScore = 0.95f,
                                            qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                                            outcomeReason = "Verified paired LG Smart TV via vault key + SSAP handshake"
                                        )
                                        results.add(
                                            DiscoveredLanDevice(
                                                ipAddress = ip,
                                                port = 3001,
                                                suggestedName = "LG Smart TV",
                                                suggestedType = TileType.LG_TV,
                                                detail = "LG Smart TV \u2022 Paired & Ready on Network",
                                                companionPackage = "com.lgeha.nuts",
                                                actionType = DiscoveredDeviceActionType.ADD_DIRECT,
                                                protocolBadge = "Smart TV Ready",
                                                hardwareIdOrMac = macHint,
                                                brandOption = BrandOption.LG_THINQ
                                            )
                                        )
                                    } else {
                                        DiscoveryFlightRecorder.record(
                                            eventType = DiscoveryEventType.FILTERED,
                                            transportSource = "TCP_PORT_PROBE",
                                            macAddress = macHint,
                                            ipAddress = ip,
                                            port = openTvPort,
                                            vendor = "TCP 3000/3001 Host",
                                            qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                                            outcomeReason = "Suppressed bare TCP $openTvPort host without LG UPnP XML + vault SSAP key"
                                        )
                                    }
                                }

                                // Home Assistant Smart Home Hub (Port 8123 — verified via HTTP manifest)
                                if (isPortOpenInternal(ip, 8123, 220)) {
                                    DiscoveryFlightRecorder.record(
                                        eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                                        transportSource = "TCP_PORT_PROBE",
                                        macAddress = macHint,
                                        ipAddress = ip,
                                        port = 8123,
                                        vendor = "TCP 8123 Host",
                                        confidenceScore = 0.15f,
                                        qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                                        outcomeReason = "Open TCP port 8123 responder detected"
                                    )
                                    if (verifyHomeAssistantOnPort8123(ip)) {
                                        val hasHaToken = bridge.haToken.isNotBlank()
                                        DiscoveryFlightRecorder.record(
                                            eventType = DiscoveryEventType.IDENTIFIED,
                                            transportSource = "HOME_ASSISTANT_HTTP",
                                            macAddress = macHint,
                                            ipAddress = ip,
                                            port = 8123,
                                            vendor = "Home Assistant Hub",
                                            confidenceScore = 0.95f,
                                            qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                                            outcomeReason = "Verified Home Assistant /manifest.json on port 8123"
                                        )
                                        results.add(
                                            DiscoveredLanDevice(
                                                ipAddress = ip,
                                                port = 8123,
                                                suggestedName = "Home Assistant Hub",
                                                suggestedType = TileType.SMARTLIFE_DEVICE,
                                                detail = if (hasHaToken) "Smart Home Hub \u2022 Connected" else "Smart Home Hub \u2022 Sign in or add token",
                                                actionType = if (hasHaToken) DiscoveredDeviceActionType.ADD_DIRECT else DiscoveredDeviceActionType.OAUTH_SIGN_IN,
                                                protocolBadge = "Home Assistant"
                                            )
                                        )
                                    } else {
                                        DiscoveryFlightRecorder.record(
                                            eventType = DiscoveryEventType.FILTERED,
                                            transportSource = "TCP_PORT_PROBE",
                                            macAddress = macHint,
                                            ipAddress = ip,
                                            port = 8123,
                                            vendor = "TCP 8123 Host",
                                            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                            outcomeReason = "Rejected TCP 8123 host: HTTP /manifest.json is not Home Assistant"
                                        )
                                    }
                                }

                                // Change 4.2: Exclude port 32400 Plex media servers from hardware onboarding
                                if (isPortOpenInternal(ip, 32400, 180)) {
                                    DiscoveryFlightRecorder.record(
                                        eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                                        transportSource = "TCP_PORT_PROBE",
                                        macAddress = macHint,
                                        ipAddress = ip,
                                        port = 32400,
                                        vendor = "TCP 32400 Host",
                                        confidenceScore = 0.15f,
                                        qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                                        outcomeReason = "Open TCP port 32400 responder detected"
                                    )
                                    DiscoveryFlightRecorder.record(
                                        eventType = DiscoveryEventType.FILTERED,
                                        transportSource = "TCP_PORT_PROBE",
                                        macAddress = macHint,
                                        ipAddress = ip,
                                        port = 32400,
                                        vendor = "Plex Media Server",
                                        qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                        outcomeReason = "Excluded Plex media server port 32400 from hardware device onboarding"
                                    )
                                }

                                // Twinkly XLED, Shelly Gen1/Gen2/Gen3, Tasmota & Tapo KLAP (Port 80 HTTP schema introspection)
                                if (isPortOpenInternal(ip, 80, 200)) {
                                    DiscoveryFlightRecorder.record(
                                        eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                                        transportSource = "HTTP_PORT_80",
                                        macAddress = macHint,
                                        ipAddress = ip,
                                        port = 80,
                                        vendor = "HTTP Port 80 Host",
                                        confidenceScore = 0.15f,
                                        qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                                        outcomeReason = "Open TCP port 80 responder detected"
                                    )
                                    val identified = probeShellyHueOrTasmotaOnPort80(ip)
                                    if (identified != null) {
                                        val learnedHwMac = CrossEcosystemIdentityCorrelator.normalizeMac(
                                            CrossEcosystemIdentityCorrelator.getObservedIpToMacMap()[ip].orEmpty().ifBlank { macHint }
                                        )
                                        DiscoveryFlightRecorder.record(
                                            eventType = DiscoveryEventType.IDENTIFIED,
                                            transportSource = "HTTP_PORT_80",
                                            hardwareId = learnedHwMac,
                                            macAddress = learnedHwMac,
                                            ipAddress = ip,
                                            port = 80,
                                            vendor = identified.second,
                                            confidenceScore = 0.92f,
                                            qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                                            outcomeReason = "Verified ${identified.first.brandName} HTTP schema on port 80"
                                        )
                                        results.add(
                                            DiscoveredLanDevice(
                                                ipAddress = ip,
                                                port = 80,
                                                suggestedName = identified.second,
                                                suggestedType = identified.first,
                                                detail = "${identified.first.brandName} \u2022 Ready on Wi-Fi",
                                                actionType = DiscoveredDeviceActionType.ADD_DIRECT,
                                                protocolBadge = identified.first.brandName,
                                                hardwareIdOrMac = learnedHwMac,
                                                brandOption = identified.third
                                            )
                                        )
                                    } else {
                                        val tapoDev = TpLinkTapoKasaClient.probeTapoOnIp(context, ip)
                                        if (tapoDev != null) {
                                            results.add(
                                                DiscoveredLanDevice(
                                                    ipAddress = ip,
                                                    port = 80,
                                                    suggestedName = tapoDev.name,
                                                    suggestedType = tapoDev.suggestedType,
                                                    detail = "${tapoDev.model} \u2022 ${if (tapoDev.requiresAuth) "Sign in with TP-Link ID" else "Ready on Wi-Fi"}",
                                                    companionPackage = "com.tplink.iot",
                                                    actionType = tapoDev.actionType,
                                                    protocolBadge = "TP-Link Tapo",
                                                    hardwareIdOrMac = tapoDev.deviceId,
                                                    brandOption = BrandOption.TAPO
                                                )
                                            )
                                        } else {
                                            DiscoveryFlightRecorder.record(
                                                eventType = DiscoveryEventType.FILTERED,
                                                transportSource = "HTTP_PORT_80",
                                                macAddress = macHint,
                                                ipAddress = ip,
                                                port = 80,
                                                vendor = "Non-Smart HTTP Host",
                                                qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                                outcomeReason = "Filtered HTTP port 80 host (router/NAS/appliance without Shelly, Twinkly, Tasmota, or Tapo KLAP schema)"
                                            )
                                        }
                                    }
                                }

                                // Change 4.2 + Section 3: Verified RTSP camera SDP / auth realm on port 554
                                val alreadyFoundCamera = results.any {
                                    it.ipAddress.substringBefore(":") == ip && it.port == 554
                                }
                                if (!alreadyFoundCamera && isPortOpenInternal(ip, 554, 220)) {
                                    val verifiedCam = verifyRtspCameraDescriptor(ip, 554, macHint)
                                    if (verifiedCam != null) {
                                        val (camType, camBrand) = verifiedCam
                                        val normCamMac = CrossEcosystemIdentityCorrelator.normalizeMac(macHint)
                                        DiscoveryFlightRecorder.record(
                                            eventType = DiscoveryEventType.IDENTIFIED,
                                            transportSource = "RTSP_PORT_554",
                                            hardwareId = normCamMac.ifBlank { ip },
                                            macAddress = normCamMac,
                                            ipAddress = ip,
                                            port = 554,
                                            vendor = "${camBrand.defaultTileTitle} ($ip)",
                                            confidenceScore = 0.92f,
                                            qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                                            outcomeReason = "Verified RTSP camera descriptor on port 554"
                                        )
                                        results.add(
                                            DiscoveredLanDevice(
                                                ipAddress = ip,
                                                port = 554,
                                                suggestedName = camBrand.defaultTileTitle,
                                                suggestedType = camType,
                                                detail = "${camType.brandName} Camera \u2022 Ready on Network",
                                                companionPackage = camBrand.companionPackage,
                                                actionType = DiscoveredDeviceActionType.OAUTH_SIGN_IN,
                                                protocolBadge = "Network Camera",
                                                hardwareIdOrMac = normCamMac,
                                                brandOption = camBrand
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    emptyList()
                }

                (listOf(
                    ssdpJob,
                    castJob,
                    mdnsJob,
                    matterJob,
                    kasaTapoJob,
                    sonoffMdnsJob,
                    espHomeMdnsJob,
                    tuyaUdpJob,
                    goveeJob,
                    twinklyJob,
                    onvifJob
                ) + subnetJobs).awaitAll()
            }

            results.distinctBy { "${it.ipAddress}:${it.port}:${it.suggestedName}" }.sortedBy { it.suggestedName }
        }
    }

    /**
     * Dispatches a single isolated radio scan pass for the guided onboarding wizard.
     */
    suspend fun discoverByRadioPass(
        context: Context,
        radioPass: OnboardingScanRadioPass,
        scanDurationMs: Long = 8_000L,
        includeFullPortSweep: Boolean = true
    ): List<DiscoveredLanDevice> = when (radioPass) {
        OnboardingScanRadioPass.LAN_ONLY ->
            discoverLanNetworkPass(context = context, includeFullPortSweep = includeFullPortSweep)
        OnboardingScanRadioPass.BLE_SETUP_ONLY ->
            discoverBleSetupPass(context = context, scanDurationMs = scanDurationMs)
        OnboardingScanRadioPass.SOFT_AP_ONLY ->
            discoverSoftApPass(context = context)
        OnboardingScanRadioPass.MATTER_MDNS_BLE -> {
            val mdnsMatter = DiscoveryRadioCoordinator.withIsolatedRadioPass(
                OnboardingScanRadioPass.MATTER_MDNS_BLE,
                "matter_mdns_pass"
            ) {
                MatterProtocolClient.discoverMatterDevices(context, timeoutMs = 2_000L).map { m ->
                    DiscoveredLanDevice(
                        ipAddress = m.ipAddress,
                        port = m.port,
                        suggestedName = m.displayName,
                        suggestedType = m.suggestedType,
                        detail = "${m.vendorName} \u2022 Matter ${if (m.isCommissionedOperational) "Multi-Admin Ready" else "Ready to Pair"}",
                        companionPackage = "com.google.android.apps.chromecast.app",
                        actionType = m.actionType,
                        protocolBadge = "Matter",
                        hardwareIdOrMac = m.nodeId,
                        brandOption = BrandOption.MATTER
                    )
                }
            }
            val bleMatter = discoverBleSetupPass(context, scanDurationMs = 4_000L)
                .filter { it.suggestedType == TileType.MATTER_DEVICE }
            (mdnsMatter + bleMatter).distinctBy { "${it.ipAddress}:${it.hardwareIdOrMac}:${it.suggestedName}" }
        }
    }

    /**
     * Backward-compatible wrapper: defaults `includeBleAndAp = false` so background/LAN callers
     * never activate BLE or SoftAP radios. If `includeBleAndAp = true` is explicitly requested,
     * executes the isolated passes sequentially rather than simultaneously.
     */
    suspend fun discoverLocalNetworkDevices(
        context: Context,
        includeBleAndAp: Boolean = false,
        includeFullPortSweep: Boolean = true
    ): List<DiscoveredLanDevice> = withContext(Dispatchers.IO) {
        val lanResults = discoverLanNetworkPass(context, includeFullPortSweep = includeFullPortSweep)
        if (!includeBleAndAp) return@withContext lanResults
        val bleResults = discoverBleSetupPass(context, scanDurationMs = 2_500L)
        val apResults = discoverSoftApPass(context)
        (lanResults + bleResults + apResults)
            .distinctBy { "${it.ipAddress}:${it.port}:${it.hardwareIdOrMac}:${it.suggestedName}" }
    }

    /**
     * Multi-Protocol Capability Introspection for Home Assistant entities:
     * Parses `hvac_modes`, `preset_modes`, `fan_modes`, `oscillating`, `percentage`, `effect_list`,
     * `source_list`, `brightness`, `current_power_w`, and `unit_of_measurement` into `DeviceCapabilityDescriptor` items.
     */
    private fun extractCapabilitiesFromHaAttributes(
        domain: String,
        state: String,
        attrs: JSONObject
    ): Pair<List<DeviceCapabilityDescriptor>, Boolean> {
        val caps = mutableListOf<DeviceCapabilityDescriptor>()
        var hasPowerTelemetry = false

        fun parseStringArray(key: String): List<String> {
            val arr = attrs.optJSONArray(key) ?: return emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                arr.optString(i, "").takeIf { it.isNotBlank() }?.let { list.add(it) }
            }
            return list
        }

        if (domain in listOf("switch", "light", "fan", "input_boolean", "lock", "vacuum")) {
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "ha_state",
                    label = when (domain) {
                        "lock" -> "Door Lock"
                        "fan" -> "Fan Power"
                        else -> "Power Switch"
                    },
                    kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = state
                )
            )
        }

        val hvacModes = parseStringArray("hvac_modes")
        if (hvacModes.isNotEmpty()) {
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "hvac_mode",
                    label = "HVAC Mode",
                    kind = DeviceCapabilityKind.ENUM_MODE,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = state,
                    enumOptions = hvacModes
                )
            )
        }

        if (attrs.has("temperature") || domain in listOf("climate", "water_heater")) {
            val targetTemp = attrs.optDouble("temperature", 21.0).toFloat()
            val minTemp = attrs.optDouble("min_temp", 5.0).toFloat()
            val maxTemp = attrs.optDouble("max_temp", if (domain == "water_heater") 100.0 else 35.0).toFloat()
            val stepTemp = attrs.optDouble("target_temp_step", 0.5).toFloat().coerceAtLeast(0.5f)
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "target_temperature",
                    label = "Target Temperature",
                    kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = "%.1f".format(Locale.US, targetTemp),
                    minValue = minTemp,
                    maxValue = maxTemp,
                    stepValue = stepTemp,
                    unit = "\u00B0C",
                    rendererType = CapabilityRendererType.TEMPERATURE_DIAL
                )
            )
        }

        if (attrs.has("current_temperature")) {
            val curTemp = attrs.optDouble("current_temperature", 20.5).toFloat()
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "ambient_temperature",
                    label = "Current Temperature",
                    kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                    isWritable = false,
                    isPinnedToHero = false,
                    currentValue = "%.1f".format(Locale.US, curTemp),
                    unit = "\u00B0C",
                    rendererType = CapabilityRendererType.SENSOR_BADGE
                )
            )
        }

        val presetModes = parseStringArray("preset_modes")
        if (presetModes.isNotEmpty()) {
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "preset_mode",
                    label = if (domain == "fan") "Wind Mode" else "Preset Mode",
                    kind = DeviceCapabilityKind.ENUM_MODE,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = attrs.optString("preset_mode", presetModes.first()),
                    enumOptions = presetModes
                )
            )
        }

        val fanModes = parseStringArray("fan_modes").ifEmpty { parseStringArray("fan_speed_list") }
        if (fanModes.isNotEmpty()) {
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "fan_mode",
                    label = "Fan Speed",
                    kind = DeviceCapabilityKind.ENUM_MODE,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = attrs.optString("fan_mode", attrs.optString("fan_speed", fanModes.first())),
                    enumOptions = fanModes
                )
            )
        }

        if (domain == "fan" && attrs.has("percentage")) {
            // F-10: Preserve native percentage range and percentage_step metadata instead of quantizing by / 16
            val pct = attrs.optInt("percentage", 50).coerceIn(0, 100)
            val pctStep = attrs.optDouble("percentage_step", 1.0).toFloat().coerceIn(1f, 50f)
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "fan_speed",
                    label = "Fan Speed",
                    kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = pct.toString(),
                    minValue = 0f,
                    maxValue = 100f,
                    stepValue = pctStep,
                    unit = "%"
                )
            )
        }

        if (domain == "fan" && attrs.has("oscillating")) {
            val osc = attrs.optBoolean("oscillating", false)
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "switch_horizontal",
                    label = "Horizontal Oscillation",
                    kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = osc.toString()
                )
            )
        }

        val effectList = parseStringArray("effect_list")
        if (effectList.isNotEmpty()) {
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "effect",
                    label = "Light Effect",
                    kind = DeviceCapabilityKind.ENUM_MODE,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = attrs.optString("effect", effectList.first()),
                    enumOptions = effectList
                )
            )
        }

        val sourceList = parseStringArray("source_list")
        if (sourceList.isNotEmpty()) {
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "source",
                    label = "Input Source",
                    kind = DeviceCapabilityKind.ENUM_MODE,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = attrs.optString("source", sourceList.first()),
                    enumOptions = sourceList
                )
            )
        }

        if (attrs.has("brightness")) {
            val bri255 = attrs.optDouble("brightness", 255.0)
            val pct = ((bri255 / 255.0) * 100.0).roundToInt().coerceIn(0, 100)
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "brightness_pct",
                    label = "Brightness",
                    kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                    isWritable = true,
                    isPinnedToHero = false,
                    currentValue = pct.toString(),
                    minValue = 1f,
                    maxValue = 100f,
                    unit = "%",
                    rendererType = CapabilityRendererType.DIAL,
                    presets = listOf(
                        CapabilityPreset(value = 100f, label = "On (100%)", icon = "SUNNY"),
                        CapabilityPreset(value = 75f, label = "Bright (75%)", icon = "BRIGHT"),
                        CapabilityPreset(value = 25f, label = "Dim (25%)", icon = "DIM"),
                        CapabilityPreset(value = 0f, label = "Off", icon = "POWER")
                    )
                )
            )
        }

        if (attrs.has("current_position") || domain == "cover") {
            val pos = attrs.optInt("current_position", 100).coerceIn(0, 100)
            val devClassAttr = attrs.optString("device_class", "").lowercase(Locale.US)
            val isGarageCover = devClassAttr.contains("garage") || devClassAttr.contains("gate")
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "position",
                    label = if (isGarageCover) "Door Position" else "Cover Position",
                    kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                    isWritable = true,
                    isPinnedToHero = true,
                    currentValue = pos.toString(),
                    minValue = 0f,
                    maxValue = 100f,
                    unit = "%",
                    rendererType = CapabilityRendererType.DIAL,
                    presets = if (isGarageCover) {
                        listOf(
                            CapabilityPreset(value = 100f, label = "Open (100%)", icon = "BLINDS_OPEN"),
                            CapabilityPreset(value = 50f, label = "Half (50%)", icon = "BLINDS_HALF"),
                            CapabilityPreset(value = 0f, label = "Close (0%)", icon = "BLINDS_CLOSED")
                        )
                    } else {
                        listOf(
                            CapabilityPreset(value = 100f, label = "Open (100%)", icon = "BLINDS_OPEN"),
                            CapabilityPreset(value = 50f, label = "Half (50%)", icon = "BLINDS_HALF"),
                            CapabilityPreset(value = 25f, label = "Vent (25%)", icon = "BLINDS_VENT"),
                            CapabilityPreset(value = 0f, label = "Close (0%)", icon = "BLINDS_CLOSED")
                        )
                    }
                )
            )
        }

        val powerVal = attrs.optDouble(
            "current_power_w",
            attrs.optDouble("power", attrs.optDouble("load_power", Double.NaN))
        )
        if (!powerVal.isNaN()) {
            hasPowerTelemetry = true
            caps.add(
                DeviceCapabilityDescriptor(
                    key = "cur_power",
                    label = "Live Power",
                    kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                    isWritable = false,
                    isPinnedToHero = false,
                    currentValue = "%.1f".format(Locale.US, powerVal),
                    unit = "W"
                )
            )
        }

        val unit = attrs.optString("unit_of_measurement", "")
        if (domain == "sensor" && unit in listOf("W", "kW", "kWh", "V", "A", "mA")) {
            hasPowerTelemetry = true
        }

        return caps to hasPowerTelemetry
    }

    suspend fun fetchHomeAssistantEntities(
        haBaseUrl: String,
        haToken: String,
        bypassCircuitBreaker: Boolean = false
    ): Result<List<DiscoveredHaEntity>> = withContext(Dispatchers.IO) {
        val cleanUrl = haBaseUrl.trim().trimEnd('/')
        val cleanToken = haToken.trim()
        if (cleanUrl.isBlank() || cleanToken.isBlank()) {
            return@withContext Result.failure(Exception("Home Assistant is not linked yet."))
        }
        val nowMs = System.currentTimeMillis()
        if (!bypassCircuitBreaker && nowMs < haCircuitOpenUntilMs) {
            // F-05: Record circuit-open rejections instead of dropping silently
            JarvisLiveBridge.recordCircuitBreakerRejection(
                providerKey = "HOME_ASSISTANT_BRIDGE",
                remainingOpenMs = (haCircuitOpenUntilMs - nowMs).coerceAtLeast(1L),
                targetTitle = "Home Assistant Hub"
            )
            return@withContext Result.failure(Exception("Home Assistant circuit breaker open; retrying shortly."))
        }

        val startMs = System.currentTimeMillis()
        try {
            val httpResp = SharedHttpClient.requestText(
                url = "$cleanUrl/api/states",
                headers = mapOf(
                    "Authorization" to "Bearer $cleanToken",
                    "Content-Type" to "application/json"
                ),
                connectTimeoutMs = 3500,
                readTimeoutMs = 4000
            )
            val code = httpResp.code
            if (!httpResp.isSuccessful) {
                haConsecutiveFailures++
                if (haConsecutiveFailures >= 2) {
                    haCircuitOpenUntilMs = System.currentTimeMillis() + HA_CIRCUIT_BREAKER_COOLDOWN_MS
                }
                JarvisLiveBridge.recordPollHealth(
                    providerKey = "HOME_ASSISTANT_BRIDGE",
                    latencyMs = System.currentTimeMillis() - startMs,
                    succeeded = false,
                    statusDetail = "HTTP $code"
                )
                return@withContext Result.failure(Exception("Could not connect to Home Assistant (HTTP $code)."))
            }
            haConsecutiveFailures = 0
            haCircuitOpenUntilMs = 0L

            val arr = JSONArray(httpResp.body)
            val discovered = mutableListOf<DiscoveredHaEntity>()

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val entityId = obj.optString("entity_id", "")
                if (!entityId.contains(".")) continue
                val domain = entityId.substringBefore(".")
                val state = obj.optString("state", "")
                val attrs = obj.optJSONObject("attributes") ?: JSONObject()
                val friendlyName = attrs.optString("friendly_name", entityId.substringAfter("."))

                val (extractedCaps, hasPower) = extractCapabilitiesFromHaAttributes(domain, state, attrs)
                val haResolution = CapabilityClassifier.resolve(
                    capabilities = extractedCaps,
                    categoryCode = domain,
                    multiGangSocketCount = 1,
                    hasPowerTelemetry = hasPower,
                    titleHint = friendlyName
                )
                val suggestedType: TileType = when {
                    domain in listOf("fan", "humidifier") || haResolution.deviceSubtype == DeviceSubtype.TOWER_FAN ->
                        TileType.PROBREEZE_FAN
                    domain in listOf("climate", "water_heater") || haResolution.deviceClass == DeviceClass.CLIMATE ->
                        TileType.TUYA_RADIATOR
                    domain == "light" -> when {
                        haResolution.deviceSubtype == DeviceSubtype.AMBIENT_SYNC ||
                                entityId.contains("lytmi", true) || friendlyName.contains("lytmi", true) -> TileType.LYTMI_SYNC_BOX
                        else -> TileType.HUE_LIGHT
                    }
                    domain in listOf("switch", "input_boolean", "button", "scene", "script", "select", "number") -> {
                        when {
                            haResolution.deviceClass == DeviceClass.APPLIANCE -> TileType.SMARTLIFE_DEVICE
                            hasPower -> TileType.SMARTKNIGHT_PLUG
                            else -> haResolution.suggestedTileType
                        }
                    }
                    domain in listOf("vacuum", "lawn_mower") -> TileType.AIRROBO_VACUUM
                    domain == "media_player" -> {
                        val devClass = attrs.optString("device_class", "")
                        if (devClass.equals("tv", true) ||
                            entityId.contains("lg", true) ||
                            entityId.contains("webos", true) ||
                            friendlyName.contains("TV", true)
                        ) {
                            TileType.LG_TV
                        } else {
                            TileType.NEST_MINI
                        }
                    }
                    domain in listOf("camera", "image") -> {
                        when {
                            entityId.contains("ezviz", true) -> TileType.EZVIZ_DOORBELL
                            entityId.contains("nest", true) -> TileType.NEST_DOORBELL
                            entityId.contains("ring", true) || entityId.contains("frigate", true) -> TileType.RING_CAMERA
                            else -> TileType.IMOU_CAMERA
                        }
                    }
                    domain in listOf("lock", "alarm_control_panel") -> {
                        // F-02: Route by semantic resolution instead of mapping Tesla locks to MERCEDES_VEHICLE
                        when {
                            haResolution.deviceClass == DeviceClass.VEHICLE &&
                                    (entityId.contains("mercedes", true) || friendlyName.contains("mercedes", true)) ->
                                TileType.MERCEDES_VEHICLE
                            haResolution.deviceClass == DeviceClass.VEHICLE ||
                                    entityId.contains("jaguar", true) ||
                                    entityId.contains("tesla", true) ->
                                TileType.JAGUAR_VEHICLE
                            else -> TileType.SMART_LOCK
                        }
                    }
                    domain == "cover" -> TileType.SMART_COVER
                    domain in listOf("sensor", "binary_sensor") -> {
                        val devClass = attrs.optString("device_class", "")
                        if (devClass in listOf("temperature", "humidity", "illuminance", "power", "energy", "battery", "motion", "occupancy", "door", "window", "moisture")) {
                            TileType.SMART_SENSOR
                        } else continue
                    }
                    else -> continue
                }

                // F-03: Never fabricate 20.5°C when hardware temperature telemetry is absent
                val parsedAmbient = if (attrs.has("current_temperature")) {
                    attrs.optDouble("current_temperature", Double.NaN)
                } else if (domain == "sensor" && attrs.optString("device_class", "") == "temperature") {
                    state.toDoubleOrNull() ?: Double.NaN
                } else {
                    Double.NaN
                }
                val currentTemp = if (!parsedAmbient.isNaN()) parsedAmbient.toFloat() else 0f
                val targetTemp = if (attrs.has("temperature")) attrs.optDouble("temperature", 0.0).toFloat() else 0f
                val brightness255 = attrs.optDouble("brightness", -1.0)
                val fanPercentage = attrs.optDouble("percentage", -1.0)
                val volumeLevel = attrs.optDouble("volume_level", -1.0)
                val batteryLevel = attrs.optDouble("battery_level", -1.0)
                val coverPos = attrs.optDouble("current_position", -1.0)
                val unit = attrs.optString("unit_of_measurement", "")

                val level = when {
                    brightness255 >= 0 -> ((brightness255 / 255.0) * 100.0).toFloat().coerceIn(0f, 100f)
                    fanPercentage >= 0 -> fanPercentage.toFloat().coerceIn(0f, 100f)
                    volumeLevel >= 0 -> (volumeLevel * 100.0).toFloat().coerceIn(0f, 100f)
                    batteryLevel >= 0 -> batteryLevel.toFloat().coerceIn(0f, 100f)
                    coverPos >= 0 -> coverPos.toFloat().coerceIn(0f, 100f)
                    else -> 80f
                }

                val guessedRoom = DEFAULT_ROOMS.firstOrNull { room ->
                    friendlyName.contains(room, ignoreCase = true) ||
                            entityId.contains(room.replace(" ", "_"), ignoreCase = true)
                } ?: ""

                CapabilityClassifier.recordResolution(entityId, haResolution)

                discovered.add(
                    DiscoveredHaEntity(
                        entityId = entityId,
                        friendlyName = friendlyName,
                        state = state,
                        suggestedType = suggestedType,
                        suggestedRoom = guessedRoom,
                        temperature = currentTemp,
                        targetTemperature = targetTemp,
                        levelOrBrightness = level,
                        unitOfMeasurement = unit,
                        capabilities = extractedCaps,
                        hasPowerTelemetry = hasPower
                    )
                )
            }

            // F-05: Record background entity poll metrics in poll health bucket, not command latency
            JarvisLiveBridge.recordPollHealth(
                providerKey = "HOME_ASSISTANT_BRIDGE",
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(10L),
                succeeded = true,
                statusDetail = "${discovered.size} entities"
            )
            Result.success(discovered.sortedBy { it.friendlyName })
        } catch (e: Exception) {
            haConsecutiveFailures++
            if (haConsecutiveFailures >= 2) {
                haCircuitOpenUntilMs = System.currentTimeMillis() + HA_CIRCUIT_BREAKER_COOLDOWN_MS
            }
            Result.failure(e)
        }
    }

    /**
     * Syncs live Home Assistant entity states onto dashboard tiles.
     * Uses `DeviceRepository.reconcileRemoteTileUpdate` and `mergeCapabilitiesWithSchemaEvolution`
     * so background HA polls never overwrite a user's recent local tap, and never allow an
     * unavailable HA entity to mark a locally reachable LAN device offline.
     */
    suspend fun syncLiveTileStatesFromHa(
        bridge: BridgeSettings,
        tiles: MutableList<SmartTile>
    ) {
        if (bridge.haUrl.isBlank() || bridge.haToken.isBlank()) return
        val res = fetchHomeAssistantEntities(bridge.haUrl, bridge.haToken)
        if (res.isFailure) return
        val entities = res.getOrThrow()
        val entityMap = entities.associateBy { it.entityId }

        withContext(Dispatchers.Main) {
            for (i in tiles.indices) {
                val tile = tiles[i]
                if (tile.entityId.startsWith("enterprises/")) continue
                // F-04 & F-11: Never overwrite an in-flight user command awaiting hardware ACK
                if (DeviceRepository.isTilePendingAckLocked(tile.id) ||
                    tile.transactionState == CommandTransactionState.PENDING_ACK
                ) {
                    continue
                }
                val hasDirectLanOrTuyaHardwareId = tile.ipAddress.isNotBlank() ||
                        (tile.serialNumber.isNotBlank() && !tile.serialNumber.contains(".")) ||
                        (tile.entityId.isNotBlank() && !tile.entityId.contains("."))

                val live: DiscoveredHaEntity = (if (tile.entityId.contains(".")) {
                    entityMap[tile.entityId]
                } else if (!hasDirectLanOrTuyaHardwareId) {
                    entities.firstOrNull {
                        it.suggestedType == tile.type && it.friendlyName.equals(tile.title, ignoreCase = true)
                    }
                } else {
                    null
                }) ?: continue

                val rawHaOnline = !live.state.equals("unavailable", ignoreCase = true) &&
                        !live.state.equals("unknown", ignoreCase = true)
                val isEntityOnline = if (hasDirectLanOrTuyaHardwareId) {
                    val devId = tile.serialNumber.ifBlank { tile.entityId }
                    TuyaApiClient.isDeviceReachableOrOnline(
                        deviceId = devId,
                        ipAddress = tile.ipAddress,
                        reportedCloudOnline = rawHaOnline || tile.isOnline
                    )
                } else {
                    rawHaOnline
                }

                if (!rawHaOnline && hasDirectLanOrTuyaHardwareId) {
                    continue
                }

                val (mergedCaps, mergedHash) = DeviceRepository.mergeCapabilitiesWithSchemaEvolution(
                    existing = tile,
                    incomingCapabilities = live.capabilities
                )

                val candidateUpdate = when {
                    tile.isTowerFanTile -> {
                        val computedSpeed = ((live.levelOrBrightness / 100f) * 6f).roundToInt().coerceIn(1, 6)
                        tile.copy(
                            entityId = tile.entityId.ifBlank { live.entityId },
                            isOnline = isEntityOnline,
                            isActive = live.state.equals("on", ignoreCase = true),
                            volume = if (live.state.equals("on", ignoreCase = true)) live.levelOrBrightness else tile.volume,
                            fanSpeedLevel = if (live.state.equals("on", ignoreCase = true)) computedSpeed else tile.fanSpeedLevel,
                            capabilities = mergedCaps,
                            capabilitySchemaHash = mergedHash
                        )
                    }
                    tile.isThermostatTile -> tile.copy(
                        entityId = tile.entityId.ifBlank { live.entityId },
                        isOnline = isEntityOnline,
                        temperature = live.temperature,
                        targetTemperature = live.targetTemperature,
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash
                    )
                    tile.deviceClass == DeviceClass.LIGHT || tile.isLytmiTile -> tile.copy(
                        entityId = tile.entityId.ifBlank { live.entityId },
                        isOnline = isEntityOnline,
                        isActive = live.state.equals("on", ignoreCase = true),
                        volume = if (live.state.equals("on", ignoreCase = true)) live.levelOrBrightness else tile.volume,
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash
                    )
                    tile.deviceSubtype.name.contains("VACUUM", ignoreCase = true) -> tile.copy(
                        entityId = tile.entityId.ifBlank { live.entityId },
                        isOnline = isEntityOnline,
                        isActive = live.state.equals("cleaning", ignoreCase = true) || live.state.equals("on", ignoreCase = true),
                        volume = live.levelOrBrightness,
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash
                    )
                    tile.deviceSubtype == DeviceSubtype.LOCK -> tile.copy(
                        entityId = tile.entityId.ifBlank { live.entityId },
                        isOnline = isEntityOnline,
                        isActive = live.state.equals("locked", ignoreCase = true),
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash
                    )
                    tile.isVehicleTile -> tile.copy(
                        entityId = tile.entityId.ifBlank { live.entityId },
                        isOnline = isEntityOnline,
                        vehicleLocked = live.state.equals("locked", ignoreCase = true),
                        isActive = live.state.equals("locked", ignoreCase = true),
                        capabilities = mergedCaps.ifEmpty { tile.capabilities },
                        capabilitySchemaHash = mergedHash
                    )
                    tile.deviceSubtype in setOf(DeviceSubtype.BLIND, DeviceSubtype.GARAGE_DOOR) -> tile.copy(
                        entityId = tile.entityId.ifBlank { live.entityId },
                        isOnline = isEntityOnline,
                        isActive = !live.state.equals("closed", ignoreCase = true),
                        volume = live.levelOrBrightness,
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash
                    )
                    tile.deviceClass == DeviceClass.SENSOR -> tile.copy(
                        entityId = tile.entityId.ifBlank { live.entityId },
                        isOnline = isEntityOnline,
                        temperature = live.temperature,
                        sensorUnit = live.unitOfMeasurement.ifBlank { tile.sensorUnit },
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash
                    )
                    tile.isSpeakerTile -> tile.copy(
                        entityId = tile.entityId.ifBlank { live.entityId },
                        isOnline = isEntityOnline,
                        volume = live.levelOrBrightness,
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash
                    )
                    tile.type == TileType.LG_TV || tile.deviceSubtype == DeviceSubtype.TV -> tile.copy(
                        entityId = tile.entityId.ifBlank { live.entityId },
                        isOnline = isEntityOnline,
                        isActive = !live.state.equals("off", ignoreCase = true) && !live.state.equals("unavailable", ignoreCase = true),
                        volume = live.levelOrBrightness,
                        deviceClassOverride = DeviceClass.MEDIA,
                        deviceSubtypeOverride = DeviceSubtype.TV
                    )
                    tile.isSwitchTile || tile.isKitchenApplianceTile -> tile.copy(
                        entityId = tile.entityId.ifBlank { live.entityId },
                        isOnline = isEntityOnline,
                        isActive = live.state.equals("on", ignoreCase = true),
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash,
                        hasExplicitPowerTelemetry = tile.hasExplicitPowerTelemetry || live.hasPowerTelemetry
                    )
                    else -> tile
                }

                val reconciled = DeviceRepository.reconcileRemoteTileUpdate(
                    existing = tile,
                    incoming = candidateUpdate,
                    incomingAuthority = StateAuthorityTier.DEVICE_HARDWARE_TRUTH,
                    sourceName = "Home Assistant"
                )
                if (reconciled != tile) {
                    tiles[i] = reconciled
                }
            }
        }
    }

    suspend fun callHomeAssistantService(
        haBaseUrl: String,
        haToken: String,
        domain: String,
        service: String,
        payloadJson: JSONObject
    ): Boolean = withContext(Dispatchers.IO) {
        if (haBaseUrl.isBlank() || haToken.isBlank()) return@withContext false
        val nowMs = System.currentTimeMillis()
        if (nowMs < haCircuitOpenUntilMs) {
            JarvisLiveBridge.recordCircuitBreakerRejection(
                providerKey = "HOME_ASSISTANT_BRIDGE",
                remainingOpenMs = (haCircuitOpenUntilMs - nowMs).coerceAtLeast(1L),
                targetTitle = "$domain.$service"
            )
            return@withContext false
        }
        try {
            val cleanUrl = haBaseUrl.trimEnd('/')
            val httpResp = SharedHttpClient.requestText(
                url = "$cleanUrl/api/services/$domain/$service",
                method = "POST",
                headers = mapOf("Authorization" to "Bearer ${haToken.trim()}"),
                body = payloadJson.toString(),
                connectTimeoutMs = 3500,
                readTimeoutMs = 4000
            )
            if (httpResp.isSuccessful) {
                haConsecutiveFailures = 0
                haCircuitOpenUntilMs = 0L
                true
            } else {
                haConsecutiveFailures++
                if (haConsecutiveFailures >= 2) {
                    haCircuitOpenUntilMs = System.currentTimeMillis() + HA_CIRCUIT_BREAKER_COOLDOWN_MS
                }
                false
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
    haConsecutiveFailures++
            if (haConsecutiveFailures >= 2) {
                haCircuitOpenUntilMs = System.currentTimeMillis() + HA_CIRCUIT_BREAKER_COOLDOWN_MS
}
            false
        }
    }

    /**
     * Toggles a local Shelly relay on Wi-Fi:
     * 1. Attempts Shelly Gen2 / Gen3 / Plus / Pro RPC (`/rpc/Switch.Set?id=0&on=true|false`) first.
     * 2. Falls back to Shelly Gen1 HTTP (`/relay/0?turn=on|off`).
     */
    suspend fun toggleLocalShellyRelay(
        ipAddress: String,
        turnOn: Boolean,
        relayIndex: Int = 0
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanIp = ipAddress.trim().substringBefore(":")
        if (cleanIp.isBlank()) return@withContext false

        // 1. Shelly Gen2 / Gen3 / Plus / Pro RPC endpoint
        try {
            val rpcResp = SharedHttpClient.requestText(
                url = "http://$cleanIp/rpc/Switch.Set?id=$relayIndex&on=$turnOn",
                connectTimeoutMs = 1600,
                readTimeoutMs = 1600
            )
            if (rpcResp.isSuccessful) return@withContext true
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

        // 2. Shelly Gen1 HTTP endpoint
        try {
            val stateStr = if (turnOn) "on" else "off"
            SharedHttpClient.requestText(
                url = "http://$cleanIp/relay/$relayIndex?turn=$stateStr",
                connectTimeoutMs = 2000,
                readTimeoutMs = 2000
            ).isSuccessful
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

    suspend fun dispatchCameraPtz(
        tile: SmartTile,
        bridge: BridgeSettings,
        direction: PtzDirection
    ): Boolean = withContext(Dispatchers.IO) {
        if (resolveEffectiveProviderKey(tile).contains("IMOU") && tile.serialNumber.isNotBlank()) {
            val ok = ImouCloudApiClient.controlMovePTZ(
                bridge = bridge,
                serialNumber = tile.serialNumber,
                channelId = tile.channelId,
                direction = direction
            )
            if (ok) return@withContext true
        }
        if (tile.ipAddress.isNotBlank() && tile.secretCode.isNotBlank()) {
            try {
                val ip = tile.ipAddress.trim()
                val basicAuth = "Basic " + Base64.encodeToString(
                    "admin:${tile.secretCode.trim()}".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                val authHeaders = mapOf("Authorization" to basicAuth)
                SharedHttpClient.requestText(
                    url = "http://$ip/cgi-bin/ptz.cgi?action=start&channel=1&code=${direction.code}&arg1=0&arg2=1&arg3=0",
                    headers = authHeaders,
                    connectTimeoutMs = 2000,
                    readTimeoutMs = 2000
                )
                delay(400)
                SharedHttpClient.requestText(
                    url = "http://$ip/cgi-bin/ptz.cgi?action=stop&channel=1&code=${direction.code}&arg1=0&arg2=1&arg3=0",
                    headers = authHeaders,
                    connectTimeoutMs = 2000,
                    readTimeoutMs = 2000
                )
                return@withContext true
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
        false
    }

    // -----------------------------------------------------------------------
    // STAGE 6: AUTONOMOUS RECOVERY ENGINE ENTRY POINT
    // -----------------------------------------------------------------------
    suspend fun attemptAutonomousSelfHealing(
        context: Context,
        tile: SmartTile,
        bridge: BridgeSettings,
        tiles: MutableList<SmartTile>? = null
    ): Pair<Boolean, String> = RecoveryEngine.executeAutonomousRecovery(
        context = context,
        tile = tile,
        bridge = bridge,
        tiles = tiles
    )

    // -----------------------------------------------------------------------
    // LOCAL DVR SNAPSHOT BUFFER ENGINE (Zero Synthetic Fallback Images)
    // Captures genuine live HTTP/Cloud camera snapshots and stores them on
    // the tablet in `filesDir/dvr_snapshots/`.
    // -----------------------------------------------------------------------
    suspend fun captureDvrEventSnapshot(
        context: Context,
        tile: SmartTile,
        bridge: BridgeSettings,
        aiEventType: CameraAiEventType = CameraAiEventType.PERSON,
        summaryText: String = ""
    ): DvrEventSnapshot = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "dvr_snapshots").apply {
            if (!exists()) mkdirs()
        }
        val ts = System.currentTimeMillis()
        val fileName = "dvr_${tile.id.take(8)}_${ts}.jpg"
        val outFile = File(dir, fileName)
        var savedOk = false

        // 1. Try Imou Cloud snapshot URL if Imou camera
        if (resolveEffectiveProviderKey(tile).contains("IMOU")) {
            val snapUrlRes = ImouCloudApiClient.fetchCameraSnapshotUrl(
                bridge = bridge,
                serialNumber = tile.serialNumber,
                channelId = tile.channelId
            )
            val snapUrl = snapUrlRes.getOrNull().orEmpty()
            if (snapUrl.startsWith("http")) {
                savedOk = downloadUrlToFile(snapUrl, outFile)
            }
        }

        // 2. Try Local CGI / ISAPI snapshot with camera credentials if camera has a local IP
        if (!savedOk && tile.ipAddress.isNotBlank()) {
            val cleanIp = tile.ipAddress.substringBefore(":").trim()
            val candidateUrls = listOf(
                "http://$cleanIp/cgi-bin/snapshot.cgi?channel=1",
                "http://$cleanIp/ISAPI/Streaming/channels/101/picture",
                "http://$cleanIp/ISAPI/Streaming/channels/102/picture"
            )
            for (urlStr in candidateUrls) {
                if (downloadUrlToFile(urlStr, outFile, username = "admin", password = tile.secretCode)) {
                    savedOk = true
                    break
                }
            }
        }

        val resolvedSummary = summaryText.ifBlank {
            "${aiEventType.label} on ${tile.title}"
        }
        val snapshot = DvrEventSnapshot(
            timestampEpochMs = ts,
            cameraTileId = tile.id,
            cameraTitle = tile.title,
            roomName = tile.roomOrSubtitle,
            aiEventType = aiEventType,
            localFilePathOrUri = if (savedOk && outFile.exists()) Uri.fromFile(outFile).toString() else "",
            summaryText = resolvedSummary
        )
        DeviceRepository.appendDvrSnapshot(context, snapshot)
        JarvisLiveBridge.emitPlatformEvent(
            JarvisPlatformEvent(
                category = JarvisEventCategory.CAMERA_AI,
                sourceDeviceId = tile.id,
                sourceDeviceTitle = tile.title,
                roomName = tile.roomOrSubtitle,
                eventType = aiEventType.name,
                summary = resolvedSummary,
                cameraAiEvent = aiEventType
            )
        )
        snapshot
    }

    private fun downloadUrlToFile(
        urlStr: String,
        destFile: File,
        username: String = "",
        password: String = ""
    ): Boolean {
        return try {
            val headers = if (username.isNotBlank() && password.isNotBlank()) {
                val credentials = "$username:${password.trim()}"
                val encoded = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                mapOf("Authorization" to "Basic $encoded")
            } else emptyMap()
            val bytes = SharedHttpClient.downloadBytes(
                url = urlStr,
                headers = headers,
                connectTimeoutMs = 2500,
                readTimeoutMs = 2500
            )
            if (bytes != null && bytes.size > 512) {
                FileOutputStream(destFile).use { it.write(bytes) }
                return true
            }
            false
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

    /**
     * Autonomously polls a camera's local Dahua/Imou CGI or Hikvision/EZVIZ ISAPI motion/alarm
     * endpoints so Person, Vehicle, and Motion events are detected and logged to DVR automatically
     * without requiring the user to click manual tag chips.
     */
    suspend fun pollCameraMotionOrAiEvent(
        tile: SmartTile
    ): CameraAiEventType? = withContext(Dispatchers.IO) {
        val cleanIp = tile.ipAddress.substringBefore(":").trim()
        if (cleanIp.isBlank()) return@withContext null
        val pass = tile.secretCode.trim()
        val basicAuth = if (pass.isNotBlank()) {
            "Basic " + Base64.encodeToString("admin:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } else ""

        val probePaths = listOf(
            "/cgi-bin/eventManager.cgi?action=getEventIndexes&code=VideoMotion",
            "/cgi-bin/eventManager.cgi?action=getEventIndexes&code=CrossLineDetection",
            "/ISAPI/System/Video/inputs/channels/1/motionDetection"
        )
        val authHeaders = if (basicAuth.isNotBlank()) mapOf("Authorization" to basicAuth) else emptyMap()
        for (path in probePaths) {
            try {
                val resp = SharedHttpClient.requestText(
                    url = "http://$cleanIp$path",
                    headers = authHeaders,
                    connectTimeoutMs = 1200,
                    readTimeoutMs = 1200
                )
                if (resp.isSuccessful) {
                    val body = resp.body.lowercase(Locale.US)
                    when {
                        body.contains("human") || body.contains("person") || body.contains("pedestrian") ->
                            return@withContext CameraAiEventType.PERSON
                        body.contains("vehicle") || body.contains("car") ->
                            return@withContext CameraAiEventType.VEHICLE
                        body.contains("channels=0") || body.contains("channels=1") || body.contains("true") ->
                            return@withContext CameraAiEventType.MOTION
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
        null
    }

    // -----------------------------------------------------------------------
    // SAMSUNG SMARTTHINGS CLOUD AGGREGATOR ADAPTER (/v1/devices)
    // -----------------------------------------------------------------------
    suspend fun fetchSmartThingsDevices(
        bridge: BridgeSettings
    ): List<SmartTile> = withContext(Dispatchers.IO) {
        val pat = bridge.smartThingsToken.trim()
        if (pat.isBlank()) return@withContext emptyList()
        val startMs = System.currentTimeMillis()
        val stHeaders = mapOf(
            "Authorization" to "Bearer $pat",
            "Accept" to "application/json"
        )
        return@withContext try {
            val listResp = SharedHttpClient.requestText(
                url = "https://api.smartthings.com/v1/devices",
                headers = stHeaders,
                connectTimeoutMs = 4500,
                readTimeoutMs = 4500
            )
            val code = listResp.code
            if (!listResp.isSuccessful) {
                JarvisLiveBridge.updateAccountHealth(
                    accountKey = "SMARTTHINGS",
                    displayName = "Samsung SmartThings",
                    oauthStatus = if (code == 401 || code == 403) AccountAuthStatus.PERMISSIONS_REVOKED else AccountAuthStatus.EXPIRED,
                    permissionsRevoked = code == 401 || code == 403,
                    statusDetail = "HTTP $code from SmartThings API"
                )
                return@withContext emptyList()
            }
            val items = JSONObject(listResp.body).optJSONArray("items") ?: JSONArray()
            val list = mutableListOf<SmartTile>()
            for (i in 0 until items.length()) {
                val d = items.optJSONObject(i) ?: continue
                val deviceId = d.optString("deviceId", "").trim()
                if (deviceId.isBlank()) continue
                val label = d.optString("label", d.optString("name", "SmartThings Device")).trim()
                val comps = d.optJSONArray("components") ?: JSONArray()
                val capIds = mutableSetOf<String>()
                for (cIdx in 0 until comps.length()) {
                    val capsArr = comps.optJSONObject(cIdx)?.optJSONArray("capabilities") ?: continue
                    for (k in 0 until capsArr.length()) {
                        capsArr.optJSONObject(k)?.optString("id", "")?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }?.let {
                            capIds.add(it)
                        }
                    }
                }
                // Query real component states from /v1/devices/{deviceId}/status (Zero fabricated hardware values)
                var liveSwitchOn = false
                var liveLevelPct = 100f
                var liveTargetTempC = 21.0f
                var liveAmbientTempC = 20.5f
                var livePowerWatts = 0.0f
                var statusReachable = false

                try {
                    val statusResp = SharedHttpClient.requestText(
                        url = "https://api.smartthings.com/v1/devices/$deviceId/status",
                        headers = stHeaders,
                        connectTimeoutMs = 3500,
                        readTimeoutMs = 3500
                    )
                    if (statusResp.isSuccessful) {
                        val mainComp = JSONObject(statusResp.body)
                            .optJSONObject("components")
                            ?.optJSONObject("main")
                        if (mainComp != null) {
                            statusReachable = true
                            val swVal = mainComp.optJSONObject("switch")
                                ?.optJSONObject("switch")
                                ?.optString("value", "off")
                                .orEmpty()
                            liveSwitchOn = swVal.equals("on", ignoreCase = true)

                            val lvlVal = mainComp.optJSONObject("switchLevel")
                                ?.optJSONObject("level")
                                ?.optDouble("value", Double.NaN) ?: Double.NaN
                            if (!lvlVal.isNaN()) {
                                liveLevelPct = lvlVal.toFloat().coerceIn(0f, 100f)
                            }

                            val heatSp = mainComp.optJSONObject("thermostatHeatingSetpoint")
                                ?.optJSONObject("heatingSetpoint")
                                ?.optDouble("value", Double.NaN)
                                ?: mainComp.optJSONObject("thermostatSetpoint")
                                    ?.optJSONObject("thermostatSetpoint")
                                    ?.optDouble("value", Double.NaN)
                                ?: Double.NaN
                            if (!heatSp.isNaN()) {
                                liveTargetTempC = heatSp.toFloat().coerceIn(5f, 35f)
                            }

                            val tempMeas = mainComp.optJSONObject("temperatureMeasurement")
                                ?.optJSONObject("temperature")
                                ?.optDouble("value", Double.NaN) ?: Double.NaN
                            if (!tempMeas.isNaN()) {
                                liveAmbientTempC = tempMeas.toFloat()
                            }

                            val pwrVal = mainComp.optJSONObject("powerMeter")
                                ?.optJSONObject("power")
                                ?.optDouble("value", Double.NaN) ?: Double.NaN
                            if (!pwrVal.isNaN()) {
                                livePowerWatts = pwrVal.toFloat().coerceAtLeast(0f)
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

                val caps = mutableListOf<DeviceCapabilityDescriptor>()
                var hasPower = false
                if ("switch" in capIds) {
                    caps.add(
                        DeviceCapabilityDescriptor(
                            key = "switch",
                            label = "Power Switch",
                            kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                            isWritable = true,
                            isPinnedToHero = true,
                            currentValue = liveSwitchOn.toString(),
                            rendererType = CapabilityRendererType.TOGGLE
                        )
                    )
                }
                if ("switchlevel" in capIds) {
                    caps.add(
                        DeviceCapabilityDescriptor(
                            key = "brightness",
                            label = "Brightness",
                            kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                            isWritable = true,
                            isPinnedToHero = true,
                            currentValue = liveLevelPct.roundToInt().toString(),
                            minValue = 0f,
                            maxValue = 100f,
                            unit = "%",
                            rendererType = CapabilityRendererType.BRIGHTNESS_SLIDER
                        )
                    )
                }
                if ("thermostatheatingsetpoint" in capIds || "thermostatsetpoint" in capIds) {
                    caps.add(
                        DeviceCapabilityDescriptor(
                            key = "target_temperature",
                            label = "Target Temperature",
                            kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                            isWritable = true,
                            isPinnedToHero = true,
                            currentValue = "%.1f".format(Locale.US, liveTargetTempC),
                            minValue = 10f,
                            maxValue = 32f,
                            stepValue = 0.5f,
                            unit = "\u00B0C",
                            rendererType = CapabilityRendererType.TEMPERATURE_DIAL
                        )
                    )
                }
                if ("powermeter" in capIds || "energymeter" in capIds) {
                    hasPower = true
                    caps.add(
                        DeviceCapabilityDescriptor(
                            key = "cur_power",
                            label = "Live Power",
                            kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                            isWritable = false,
                            isPinnedToHero = false,
                            currentValue = "%.1f".format(Locale.US, livePowerWatts),
                            unit = "W",
                            rendererType = CapabilityRendererType.SENSOR_BADGE
                        )
                    )
                }
                val schemaHash = CapabilitySchemaHasher.computeHash(caps)
                val res = CapabilityClassifier.resolve(
                    capabilities = caps,
                    categoryCode = "",
                    multiGangSocketCount = 1,
                    hasPowerTelemetry = hasPower,
                    schemaHash = schemaHash,
                    titleHint = label
                )
                CapabilityClassifier.recordResolution(deviceId, res)
                list.add(
                    SmartTile(
                        title = label,
                        roomOrSubtitle = inferRoomFromDeviceTitle(label, bridge.customRooms),
                        type = res.suggestedTileType,
                        serialNumber = deviceId,
                        entityId = deviceId,
                        isOnline = statusReachable,
                        lifecycleState = if (statusReachable) DeviceLifecycleState.ONLINE else DeviceLifecycleState.RECOVERY,
                        isActive = liveSwitchOn,
                        volume = liveLevelPct,
                        temperature = liveAmbientTempC,
                        targetTemperature = liveTargetTempC,
                        powerWatts = livePowerWatts,
                        capabilities = caps,
                        capabilitySchemaHash = schemaHash,
                        hasExplicitPowerTelemetry = hasPower,
                        deviceClassOverride = res.deviceClass,
                        deviceSubtypeOverride = res.deviceSubtype,
                        classificationConfidence = res.confidence,
                        classificationSource = res.source,
                        classificationVersion = res.version,
                        classificationLastEvaluatedEpochMs = res.evaluatedAtEpochMs,
                        activeProviderId = "SMARTTHINGS_CLOUD",
                        stateAuthorityTier = StateAuthorityTier.CLOUD_SHADOW_STATE
                    )
                )
            }
            val lat = (System.currentTimeMillis() - startMs).coerceAtLeast(15L)
            JarvisLiveBridge.recordProviderHealthOutcome("SMARTTHINGS_CLOUD", "SmartThings Aggregator", lat, true)
            JarvisLiveBridge.updateAccountHealth(
                accountKey = "SMARTTHINGS",
                displayName = "Samsung SmartThings",
                oauthStatus = AccountAuthStatus.VALID,
                statusDetail = "${list.size} SmartThings device(s) synced"
            )
            list
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
    emptyList()
}
    }

    suspend fun sendSmartThingsCommand(
        bridge: BridgeSettings,
        tile: SmartTile
    ): Boolean = withContext(Dispatchers.IO) {
        val pat = bridge.smartThingsToken.trim()
        val devId = tile.serialNumber.ifBlank { tile.entityId }.trim()
        if (pat.isBlank() || devId.isBlank()) return@withContext false
        val startMs = System.currentTimeMillis()
        return@withContext try {
            val commandsArr = JSONArray().apply {
                when {
                    tile.isThermostatTile -> {
                        put(
                            JSONObject()
                                .put("component", "main")
                                .put("capability", "thermostatHeatingSetpoint")
                                .put("command", "setHeatingSetpoint")
                                .put("arguments", JSONArray().put(tile.targetTemperature.toDouble()))
                        )
                    }
                    tile.hasDimmingCapability && tile.isActive -> {
                        put(
                            JSONObject()
                                .put("component", "main")
                                .put("capability", "switch")
                                .put("command", "on")
                        )
                        put(
                            JSONObject()
                                .put("component", "main")
                                .put("capability", "switchLevel")
                                .put("command", "setLevel")
                                .put("arguments", JSONArray().put(tile.volume.roundToInt().coerceIn(1, 100)))
                        )
                    }
                    else -> {
                        put(
                            JSONObject()
                                .put("component", "main")
                                .put("capability", "switch")
                                .put("command", if (tile.isActive) "on" else "off")
                        )
                    }
                }
            }
            val payload = JSONObject().put("commands", commandsArr).toString()
            val ok = SharedHttpClient.requestText(
                url = "https://api.smartthings.com/v1/devices/$devId/commands",
                method = "POST",
                headers = mapOf("Authorization" to "Bearer $pat"),
                body = payload,
                connectTimeoutMs = 4000,
                readTimeoutMs = 4000
            ).isSuccessful
            val lat = (System.currentTimeMillis() - startMs).coerceAtLeast(15L)
            JarvisLiveBridge.recordProviderHealthOutcome("SMARTTHINGS_CLOUD", "SmartThings Aggregator", lat, ok)
            ok
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

    // -----------------------------------------------------------------------
    // ONVIF WS-DISCOVERY (UDP MULTICAST 239.255.255.250:3702) & REOLINK PROBE
    // -----------------------------------------------------------------------
    suspend fun discoverOnvifCamerasViaWsDiscovery(
        timeoutMs: Long = 1100L
    ): List<DiscoveredLanDevice> = withContext(Dispatchers.IO) {
        DiscoveryRadioCoordinator.withMulticastLock(ClaraApplication.appContextOrNull(), "onvif_ws_3702") {
            val found = ConcurrentHashMap<String, DiscoveredLanDevice>()
            val probeXml = """<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/0000/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><e:Header><w:MessageID>uuid:${java.util.UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action a:mustUnderstand="true" xmlns:a="http://www.w3.org/2005/08/addressing">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 300
                    val data = probeXml.toByteArray(Charsets.UTF_8)
                    val target = InetAddress.getByName("239.255.255.250")
                    socket.send(DatagramPacket(data, data.size, target, 3702))
                    val deadline = System.currentTimeMillis() + timeoutMs
                    val buf = ByteArray(4096)
                    while (System.currentTimeMillis() < deadline) {
                        val pkt = DatagramPacket(buf, buf.size)
                        val ok = try {
                            socket.receive(pkt)
                            true
                        } catch (_: SocketTimeoutException) {
                            false
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            ErrorClassifier.recordHandledException(
                                t = e,
                                transportSource = "ONVIF_WS_3702",
                                operationStage = "receiveWsDiscovery",
                                logToDiscoveryRecorder = false,
                                surfaceToUiBanner = false
                            )
                            false
                        }
                        if (!ok || pkt.length <= 0) continue
                        val senderIp = pkt.address?.hostAddress.orEmpty()
                        val xmlResp = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                            transportSource = "ONVIF_WS_3702",
                            ipAddress = senderIp,
                            port = 3702,
                            vendor = "WS-Discovery Responder",
                            confidenceScore = 0.20f,
                            qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                            outcomeReason = "UDP 3702 WS-Discovery packet received (${pkt.length} bytes)"
                        )
                        val isVerifiedOnvif = xmlResp.contains("ProbeMatch", ignoreCase = true) &&
                                (xmlResp.contains("NetworkVideoTransmitter", ignoreCase = true) ||
                                        xmlResp.contains("onvif", ignoreCase = true))
                        if (senderIp.isNotBlank() && isVerifiedOnvif) {
                            registerVerifiedLanNeighbor(senderIp)
                            val isReolink = xmlResp.contains("reolink", ignoreCase = true)
                            val camTitle = if (isReolink) "Reolink ONVIF Camera ($senderIp)" else "ONVIF Security Camera ($senderIp)"
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.IDENTIFIED,
                                transportSource = "ONVIF_WS_3702",
                                ipAddress = senderIp,
                                port = 554,
                                vendor = camTitle,
                                confidenceScore = 0.94f,
                                qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                                outcomeReason = "Verified ONVIF NetworkVideoTransmitter ProbeMatch XML"
                            )
                            found[senderIp] = DiscoveredLanDevice(
                                ipAddress = senderIp,
                                port = 554,
                                suggestedName = camTitle,
                                suggestedType = TileType.IMOU_CAMERA,
                                detail = "${if (isReolink) "Reolink" else "ONVIF"} RTSP Camera \u2022 WS-Discovery Verified",
                                actionType = DiscoveredDeviceActionType.OAUTH_SIGN_IN,
                                protocolBadge = if (isReolink) "Reolink ONVIF" else "ONVIF 3702",
                                brandOption = BrandOption.IMOU
                            )
                        } else {
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.FILTERED,
                                transportSource = "ONVIF_WS_3702",
                                ipAddress = senderIp,
                                port = 3702,
                                vendor = "Non-Camera WS-Discovery Host",
                                qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                outcomeReason = "Rejected UDP 3702 responder without ONVIF NetworkVideoTransmitter ProbeMatch schema"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "ONVIF_WS_3702",
                    port = 3702,
                    vendor = "ONVIF WS-Discovery",
                    qualificationStage = "discoverOnvifCamerasViaWsDiscovery",
                    outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "UDP 3702 scan error"}"
                )
            }
            found.values.toList()
        }
    }

    suspend fun probeTeslaWallConnectorVitals(
        ipAddress: String
    ): SmartTile? = withContext(Dispatchers.IO) {
        val cleanIp = ipAddress.substringBefore(":").trim()
        if (cleanIp.isBlank()) return@withContext null
        return@withContext try {
            val resp = SharedHttpClient.requestText(
                url = "http://$cleanIp/api/1/vitals",
                connectTimeoutMs = 1500,
                readTimeoutMs = 1500
            )
            if (!resp.isSuccessful) return@withContext null
            val json = JSONObject(resp.body)
            val vehicleConnected = json.optBoolean("vehicle_connected", false)
            val contactorClosed = json.optBoolean("contactor_closed", false)
            val gridVolts = json.optDouble("grid_v", 230.0).toFloat()
            val vehicleCurrentA = json.optDouble("vehicle_current_a", 0.0).toFloat()
            val sessionWh = json.optDouble("session_energy_wh", 0.0).toFloat()
            val liveWatts = gridVolts * vehicleCurrentA
            registerVerifiedLanNeighbor(cleanIp, "tesla_wall_connector")
            SmartTile(
                title = "Tesla Wall Connector",
                roomOrSubtitle = "Driveway",
                type = TileType.SMARTKNIGHT_PLUG,
                ipAddress = cleanIp,
                serialNumber = "tesla_wc_$cleanIp",
                entityId = "tesla_wc_$cleanIp",
                isOnline = true,
                isActive = contactorClosed || vehicleConnected,
                powerWatts = liveWatts,
                voltageVolts = gridVolts,
                currentMilliamps = vehicleCurrentA * 1000f,
                totalEnergyKwh = sessionWh / 1000f,
                hasExplicitPowerTelemetry = true,
                detectedApplianceCategory = ApplianceFingerprintCategory.EV_CHARGER,
                deviceClassOverride = DeviceClass.ENERGY,
                deviceSubtypeOverride = DeviceSubtype.EV_CHARGER,
                activeProviderId = "TESLA_WALL_CONNECTOR_LAN",
                stateAuthorityTier = StateAuthorityTier.DEVICE_HARDWARE_TRUTH
            )
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

    // -----------------------------------------------------------------------
    // PHASE 6.2: HUBITAT ELEVATION MAKER API & ATHOM HOMEY BRIDGE ADAPTERS
    // -----------------------------------------------------------------------
    @Volatile
    var cachedOctopusTariffCapability: EnergyTariffCapability? = null
        private set

    @Volatile
    var cachedEnergyStorageCapabilities: List<EnergyStorageCapability> = emptyList()
        private set

    @Volatile
    var cachedSpatialPresenceList: List<PresenceCapability> = emptyList()
        private set

    suspend fun fetchHubitatDevices(bridge: BridgeSettings): List<SmartTile> = withContext(Dispatchers.IO) {
        val host = bridge.hubitatHostUrl.trim().trimEnd('/')
        val appId = bridge.hubitatAppId.trim()
        val token = bridge.hubitatAccessToken.trim()
        if (host.isBlank() || appId.isBlank() || token.isBlank()) return@withContext emptyList()
        val endpoint = "$host/apps/api/$appId/devices/all?access_token=$token"
        return@withContext try {
            val resp = SharedHttpClient.requestText(
                url = endpoint,
                headers = mapOf("Accept" to "application/json"),
                connectTimeoutMs = 3500,
                readTimeoutMs = 4000
            )
            if (!resp.isSuccessful) return@withContext emptyList()
            val arr = JSONArray(resp.body)
            val list = mutableListOf<SmartTile>()
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                val id = d.optString("id", "").trim()
                if (id.isBlank()) continue
                val label = d.optString("label", d.optString("name", "Hubitat Device")).trim()
                val room = d.optString("room", "").trim().ifBlank { inferRoomFromDeviceTitle(label, bridge.customRooms) }
                val attrs = d.optJSONObject("attributes") ?: JSONObject()
                val swState = attrs.optString("switch", "off")
                val levelVal = attrs.optString("level", "100").toFloatOrNull() ?: 100f
                val tempVal = attrs.optString("temperature", "20.5").toFloatOrNull() ?: 20.5f
                val powerVal = attrs.optString("power", "0").toFloatOrNull() ?: 0f

                val caps = mutableListOf<DeviceCapabilityDescriptor>()
                if (attrs.has("switch")) {
                    caps.add(
                        DeviceCapabilityDescriptor(
                            key = "switch",
                            label = "Power Switch",
                            kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                            isWritable = true,
                            isPinnedToHero = true,
                            currentValue = swState.equals("on", true).toString()
                        )
                    )
                }
                if (attrs.has("level")) {
                    caps.add(
                        DeviceCapabilityDescriptor(
                            key = "brightness",
                            label = "Level",
                            kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                            isWritable = true,
                            isPinnedToHero = true,
                            currentValue = levelVal.roundToInt().toString(),
                            minValue = 0f,
                            maxValue = 100f,
                            unit = "%"
                        )
                    )
                }
                val hasPower = powerVal > 0f || attrs.has("power")
                val schemaHash = CapabilitySchemaHasher.computeHash(caps)
                val res = CapabilityClassifier.resolve(caps, "", 1, hasPower, schemaHash, label)
                list.add(
                    SmartTile(
                        title = label,
                        roomOrSubtitle = room,
                        type = res.suggestedTileType,
                        ipAddress = host.substringAfter("://").substringBefore(":"),
                        serialNumber = "hubitat_$id",
                        entityId = "hubitat_$id",
                        isOnline = true,
                        isActive = swState.equals("on", true),
                        volume = levelVal,
                        temperature = tempVal,
                        powerWatts = powerVal,
                        hasExplicitPowerTelemetry = hasPower,
                        capabilities = caps,
                        capabilitySchemaHash = schemaHash,
                        deviceClassOverride = res.deviceClass,
                        deviceSubtypeOverride = res.deviceSubtype,
                        activeProviderId = ProviderId.HUBITAT_LOCAL.id,
                        stateAuthorityTier = StateAuthorityTier.DEVICE_HARDWARE_TRUTH
                    )
                )
            }
            list
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
    emptyList()
}
    }

    suspend fun sendHubitatCommand(
        bridge: BridgeSettings,
        tile: SmartTile,
        command: String,
        secondaryValue: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val host = bridge.hubitatHostUrl.trim().trimEnd('/')
        val appId = bridge.hubitatAppId.trim()
        val token = bridge.hubitatAccessToken.trim()
        val devId = tile.serialNumber.ifBlank { tile.entityId }.removePrefix("hubitat_").trim()
        if (host.isBlank() || appId.isBlank() || token.isBlank() || devId.isBlank()) return@withContext false
        val cmdSuffix = if (secondaryValue.isNotBlank()) "$command/$secondaryValue" else command
        val url = "$host/apps/api/$appId/devices/$devId/$cmdSuffix?access_token=$token"
        return@withContext try {
            SharedHttpClient.requestText(
                url = url,
                connectTimeoutMs = 2500,
                readTimeoutMs = 2500
            ).isSuccessful
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

    suspend fun fetchHomeyDevices(bridge: BridgeSettings): List<SmartTile> = withContext(Dispatchers.IO) {
        val host = bridge.homeyHostUrl.trim().trimEnd('/')
        val token = bridge.homeyBearerToken.trim()
        if (host.isBlank() || token.isBlank()) return@withContext emptyList()
        return@withContext try {
            val resp = SharedHttpClient.requestText(
                url = "$host/api/manager/devices/device/",
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Accept" to "application/json"
                ),
                connectTimeoutMs = 3500,
                readTimeoutMs = 4000
            )
            if (!resp.isSuccessful) return@withContext emptyList()
            val root = JSONObject(resp.body)
            val list = mutableListOf<SmartTile>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val d = root.optJSONObject(id) ?: continue
                val name = d.optString("name", "Homey Device").trim()
                val zoneName = d.optString("zoneName", "").trim().ifBlank { inferRoomFromDeviceTitle(name, bridge.customRooms) }
                val capObj = d.optJSONObject("capabilitiesObj") ?: JSONObject()
                val isOn = capObj.optJSONObject("onoff")?.optBoolean("value", false) ?: false
                val dimPct = ((capObj.optJSONObject("dim")?.optDouble("value", 1.0) ?: 1.0) * 100.0).toFloat().coerceIn(0f, 100f)
                val watts = capObj.optJSONObject("measure_power")?.optDouble("value", 0.0)?.toFloat() ?: 0f
                val targetTemp = capObj.optJSONObject("target_temperature")?.optDouble("value", 21.0)?.toFloat() ?: 21.0f
                val ambientTemp = capObj.optJSONObject("measure_temperature")?.optDouble("value", 20.5)?.toFloat() ?: 20.5f

                val caps = mutableListOf<DeviceCapabilityDescriptor>()
                if (capObj.has("onoff")) {
                    caps.add(
                        DeviceCapabilityDescriptor(
                            key = "switch",
                            label = "Power Switch",
                            kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                            isWritable = true,
                            isPinnedToHero = true,
                            currentValue = isOn.toString()
                        )
                    )
                }
                if (capObj.has("dim")) {
                    caps.add(
                        DeviceCapabilityDescriptor(
                            key = "brightness",
                            label = "Brightness",
                            kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                            isWritable = true,
                            isPinnedToHero = true,
                            currentValue = dimPct.roundToInt().toString(),
                            minValue = 0f,
                            maxValue = 100f,
                            unit = "%"
                        )
                    )
                }
                if (capObj.has("target_temperature")) {
                    caps.add(
                        DeviceCapabilityDescriptor(
                            key = "target_temperature",
                            label = "Target Setpoint",
                            kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                            isWritable = true,
                            isPinnedToHero = true,
                            currentValue = "%.1f".format(Locale.US, targetTemp),
                            minValue = 5f,
                            maxValue = 35f,
                            stepValue = 0.5f,
                            unit = "\u00B0C"
                        )
                    )
                }
                val hasPower = watts > 0f || capObj.has("measure_power")
                val schemaHash = CapabilitySchemaHasher.computeHash(caps)
                val res = CapabilityClassifier.resolve(caps, "", 1, hasPower, schemaHash, name)
                list.add(
                    SmartTile(
                        title = name,
                        roomOrSubtitle = zoneName,
                        type = res.suggestedTileType,
                        ipAddress = host.substringAfter("://").substringBefore(":"),
                        serialNumber = "homey_$id",
                        entityId = "homey_$id",
                        isOnline = d.optBoolean("available", true),
                        isActive = isOn,
                        volume = dimPct,
                        temperature = ambientTemp,
                        targetTemperature = targetTemp,
                        powerWatts = watts,
                        hasExplicitPowerTelemetry = hasPower,
                        capabilities = caps,
                        capabilitySchemaHash = schemaHash,
                        deviceClassOverride = res.deviceClass,
                        deviceSubtypeOverride = res.deviceSubtype,
                        activeProviderId = ProviderId.HOMEY_BRIDGE.id,
                        stateAuthorityTier = StateAuthorityTier.DEVICE_HARDWARE_TRUTH
                    )
                )
            }
            list
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
    emptyList()
}
    }

    suspend fun sendHomeyCommand(
        bridge: BridgeSettings,
        tile: SmartTile,
        capabilityId: String = "onoff",
        value: Any = tile.isActive
    ): Boolean = withContext(Dispatchers.IO) {
        val host = bridge.homeyHostUrl.trim().trimEnd('/')
        val token = bridge.homeyBearerToken.trim()
        val devId = tile.serialNumber.ifBlank { tile.entityId }.removePrefix("homey_").trim()
        if (host.isBlank() || token.isBlank() || devId.isBlank()) return@withContext false
        return@withContext try {
            val payload = JSONObject().put("value", value).toString()
            SharedHttpClient.requestText(
                url = "$host/api/manager/devices/device/$devId/capability/$capabilityId",
                method = "PUT",
                headers = mapOf("Authorization" to "Bearer $token"),
                body = payload,
                connectTimeoutMs = 3000,
                readTimeoutMs = 3000
            ).isSuccessful
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

    suspend fun triggerBridgeScene(
        bridge: BridgeSettings,
        scene: SceneTriggerCapability
    ): Boolean = withContext(Dispatchers.IO) {
        when (scene.providerId) {
            ProviderId.HOME_ASSISTANT -> callHomeAssistantService(
                haBaseUrl = bridge.haUrl,
                haToken = bridge.haToken,
                domain = "scene",
                service = "turn_on",
                payloadJson = JSONObject().put("entity_id", scene.sceneId)
            )
            ProviderId.HUBITAT_LOCAL -> {
                val dummyTile = SmartTile(
                    title = scene.sceneName,
                    type = TileType.SMARTLIFE_DEVICE,
                    serialNumber = "hubitat_${scene.sceneId}",
                    activeProviderId = ProviderId.HUBITAT_LOCAL.id
                )
                sendHubitatCommand(bridge, dummyTile, "push", "1")
            }
            ProviderId.HOMEY_BRIDGE -> {
                val host = bridge.homeyHostUrl.trim().trimEnd('/')
                val token = bridge.homeyBearerToken.trim()
                if (host.isBlank() || token.isBlank()) false else runCatching {
                    SharedHttpClient.requestText(
                        url = "$host/api/manager/flow/flow/${scene.sceneId}/trigger",
                        method = "POST",
                        headers = mapOf("Authorization" to "Bearer $token"),
                        connectTimeoutMs = 3000,
                        readTimeoutMs = 3000
                    ).isSuccessful
                }.getOrDefault(false)
            }
            else -> false
        }
    }

    // -----------------------------------------------------------------------
    // PHASE 6.3: OCTOPUS ENERGY AGILE / INTELLIGENT & SOLAR / BATTERY STORAGE
    // -----------------------------------------------------------------------
    suspend fun fetchOctopusAgileTariffCapability(
        bridge: BridgeSettings
    ): EnergyTariffCapability? = withContext(Dispatchers.IO) {
        val rawTariff = bridge.octopusTariffCode.trim().ifBlank { "E-1R-AGILE-24-10-01-C" }
        val productCode = rawTariff.removePrefix("E-1R-").let {
            if (it.length > 2 && it[it.length - 2] == '-') it.dropLast(2) else it
        }
        val url = "https://api.octopus.energy/v1/products/$productCode/electricity-tariffs/$rawTariff/standard-unit-rates/?page_size=48"
        return@withContext try {
            val headers = LinkedHashMap<String, String>().apply {
                put("Accept", "application/json")
                if (bridge.octopusApiKey.isNotBlank()) {
                    val basic = Base64.encodeToString("${bridge.octopusApiKey.trim()}:".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    put("Authorization", "Basic $basic")
                }
            }
            val resp = SharedHttpClient.requestText(
                url = url,
                headers = headers,
                connectTimeoutMs = 4000,
                readTimeoutMs = 4000
            )
            if (!resp.isSuccessful) return@withContext cachedOctopusTariffCapability
            val root = JSONObject(resp.body)
            val results = root.optJSONArray("results") ?: JSONArray()
            val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val nowMs = System.currentTimeMillis()
            val slots = mutableListOf<EnergyTariffHalfHourSlot>()
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val fromIso = r.optString("valid_from", "")
                val toIso = r.optString("valid_to", "")
                val startMs = runCatching { isoFmt.parse(fromIso)?.time ?: 0L }.getOrDefault(0L)
                val endMs = runCatching { isoFmt.parse(toIso)?.time ?: (startMs + 1800_000L) }.getOrDefault(startMs + 1800_000L)
                val ratePence = r.optDouble("value_inc_vat", (bridge.electricityTariffDayRateGbp * 100f).toDouble()).toFloat()
                if (endMs >= nowMs - 1800_000L) {
                    slots.add(
                        EnergyTariffHalfHourSlot(
                            validFromIso = fromIso,
                            validToIso = toIso,
                            startEpochMs = startMs,
                            endEpochMs = endMs,
                            rateIncVatPence = ratePence
                        )
                    )
                }
            }
            val sortedSlots = slots.sortedBy { it.startEpochMs }
            val currentSlot = sortedSlots.firstOrNull { nowMs in it.startEpochMs..it.endEpochMs }
            val futureSlots = sortedSlots.filter { it.endEpochMs > nowMs }
            val lowestSlot = futureSlots.minByOrNull { it.rateIncVatPence }
            EnergyTariffCapability(
                providerId = ProviderId.OCTOPUS_ENERGY,
                tariffCode = rawTariff,
                currentRatePence = currentSlot?.rateIncVatPence ?: (bridge.electricityTariffDayRateGbp * 100f),
                nextLowestSlot = lowestSlot,
                upcomingSlots = sortedSlots
            ).also {
                cachedOctopusTariffCapability = it
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
    cachedOctopusTariffCapability
}
    }

    suspend fun fetchEnergyStorageCapabilities(
        bridge: BridgeSettings
    ): List<EnergyStorageCapability> = withContext(Dispatchers.IO) {
        val result = mutableListOf<EnergyStorageCapability>()

        // 1. Tesla Powerwall Local Gateway (/api/meters/aggregates & /api/system_status/soe)
        val pwIp = bridge.powerwallHostIp.substringBefore(":").trim()
        if (pwIp.isNotBlank()) {
            runCatching {
                val soeResp = SharedHttpClient.requestText(
                    url = "http://$pwIp/api/system_status/soe",
                    connectTimeoutMs = 2000,
                    readTimeoutMs = 2000
                )
                val socPct = if (soeResp.isSuccessful) {
                    JSONObject(soeResp.body).optDouble("percentage", 0.0).roundToInt().coerceIn(0, 100)
                } else 0

                val aggResp = SharedHttpClient.requestText(
                    url = "http://$pwIp/api/meters/aggregates",
                    connectTimeoutMs = 2000,
                    readTimeoutMs = 2000
                )
                if (aggResp.isSuccessful) {
                    val agg = JSONObject(aggResp.body)
                    val solarW = agg.optJSONObject("solar")?.optDouble("instant_power", 0.0)?.toFloat() ?: 0f
                    val batteryW = agg.optJSONObject("battery")?.optDouble("instant_power", 0.0)?.toFloat() ?: 0f
                    val loadW = agg.optJSONObject("load")?.optDouble("instant_power", 0.0)?.toFloat() ?: 0f
                    val siteW = agg.optJSONObject("site")?.optDouble("instant_power", 0.0)?.toFloat() ?: 0f
                    result.add(
                        EnergyStorageCapability(
                            providerId = ProviderId.TESLA_ENERGY,
                            deviceId = "powerwall_$pwIp",
                            batterySocPct = socPct,
                            solarGenerationWatts = solarW.coerceAtLeast(0f),
                            batteryFlowWatts = -batteryW,
                            homeLoadWatts = loadW.coerceAtLeast(0f),
                            gridFlowWatts = siteW
                        )
                    )
                }
            }
        }

        // 2. GivEnergy Cloud / Inverter API
        if (bridge.givEnergyApiKey.isNotBlank() && bridge.givEnergyInverterSerial.isNotBlank()) {
            runCatching {
                val sn = bridge.givEnergyInverterSerial.trim()
                val resp = SharedHttpClient.requestText(
                    url = "https://api.givenergy.cloud/v1/inverter/$sn/system-data/latest",
                    headers = mapOf(
                        "Authorization" to "Bearer ${bridge.givEnergyApiKey.trim()}",
                        "Accept" to "application/json"
                    ),
                    connectTimeoutMs = 3500,
                    readTimeoutMs = 3500
                )
                if (resp.isSuccessful) {
                    val data = JSONObject(resp.body).optJSONObject("data")
                    if (data != null) {
                        val soc = data.optJSONObject("battery")?.optInt("percent", 0)?.coerceIn(0, 100) ?: 0
                        val batPower = data.optJSONObject("battery")?.optDouble("power", 0.0)?.toFloat() ?: 0f
                        val solarPower = data.optJSONObject("solar")?.optDouble("power", 0.0)?.toFloat() ?: 0f
                        val gridPower = data.optJSONObject("grid")?.optDouble("power", 0.0)?.toFloat() ?: 0f
                        val consumption = data.optJSONObject("consumption")?.optDouble("power", 0.0)?.toFloat() ?: 0f
                        result.add(
                            EnergyStorageCapability(
                                providerId = ProviderId.GIVENERGY,
                                deviceId = "givenergy_$sn",
                                batterySocPct = soc,
                                solarGenerationWatts = solarPower,
                                batteryFlowWatts = batPower,
                                homeLoadWatts = consumption,
                                gridFlowWatts = -gridPower
                            )
                        )
                    }
                }
            }
        }

        // 3. SolarEdge Monitoring API
        if (bridge.solarEdgeApiKey.isNotBlank() && bridge.solarEdgeSiteId.isNotBlank()) {
            runCatching {
                val siteId = bridge.solarEdgeSiteId.trim()
                val key = bridge.solarEdgeApiKey.trim()
                val resp = SharedHttpClient.requestText(
                    url = "https://monitoringapi.solaredge.com/site/$siteId/currentPowerFlow.json?api_key=$key",
                    connectTimeoutMs = 3500,
                    readTimeoutMs = 3500
                )
                if (resp.isSuccessful) {
                    val flow = JSONObject(resp.body).optJSONObject("siteCurrentPowerFlow")
                    if (flow != null) {
                        val pvKw = flow.optJSONObject("PV")?.optDouble("currentPower", 0.0)?.toFloat() ?: 0f
                        val loadKw = flow.optJSONObject("LOAD")?.optDouble("currentPower", 0.0)?.toFloat() ?: 0f
                        val gridKw = flow.optJSONObject("GRID")?.optDouble("currentPower", 0.0)?.toFloat() ?: 0f
                        val storageObj = flow.optJSONObject("STORAGE")
                        val soc = storageObj?.optInt("chargeLevel", 0)?.coerceIn(0, 100) ?: 0
                        val storageKw = storageObj?.optDouble("currentPower", 0.0)?.toFloat() ?: 0f
                        result.add(
                            EnergyStorageCapability(
                                providerId = ProviderId.SOLAREDGE,
                                deviceId = "solaredge_$siteId",
                                batterySocPct = soc,
                                solarGenerationWatts = pvKw * 1000f,
                                batteryFlowWatts = storageKw * 1000f,
                                homeLoadWatts = loadKw * 1000f,
                                gridFlowWatts = gridKw * 1000f
                            )
                        )
                    }
                }
            }
        }

        cachedEnergyStorageCapabilities = result
        result
    }

    suspend fun discoverEnergyStorageTiles(bridge: BridgeSettings): List<SmartTile> {
        val storageList = fetchEnergyStorageCapabilities(bridge)
        return storageList.map { st ->
            val caps = listOf(
                DeviceCapabilityDescriptor(
                    key = "battery_percentage",
                    label = "Battery SOC",
                    kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                    isWritable = false,
                    isPinnedToHero = true,
                    currentValue = st.batterySocPct.toString(),
                    unit = "%"
                ),
                DeviceCapabilityDescriptor(
                    key = "solar_power",
                    label = "Solar PV",
                    kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                    isWritable = false,
                    isPinnedToHero = true,
                    currentValue = st.solarGenerationWatts.roundToInt().toString(),
                    unit = "W"
                ),
                DeviceCapabilityDescriptor(
                    key = "cur_power",
                    label = "Home Load",
                    kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                    isWritable = false,
                    isPinnedToHero = true,
                    currentValue = st.homeLoadWatts.roundToInt().toString(),
                    unit = "W"
                )
            )
            SmartTile(
                title = st.providerId.displayName,
                roomOrSubtitle = "Utility / Garage",
                type = TileType.ENERGY_MONITOR,
                serialNumber = st.deviceId,
                entityId = st.deviceId,
                isOnline = true,
                isActive = st.solarGenerationWatts > 10f || st.batterySocPct > 5,
                powerWatts = st.homeLoadWatts,
                batteryPercent = st.batterySocPct,
                hasExplicitPowerTelemetry = true,
                capabilities = caps,
                capabilitySchemaHash = CapabilitySchemaHasher.computeHash(caps),
                deviceClassOverride = DeviceClass.ENERGY,
                activeProviderId = st.providerId.id,
                stateAuthorityTier = StateAuthorityTier.DEVICE_HARDWARE_TRUTH
            )
        }
    }

    // -----------------------------------------------------------------------
    // PHASE 6.5: FRIGATE NVR AI OBJECT DETECTION & UNIFI + BLE SPATIAL PRESENCE
    // -----------------------------------------------------------------------
    suspend fun fetchFrigateNvrCamerasAndEvents(
        bridge: BridgeSettings
    ): List<SmartTile> = withContext(Dispatchers.IO) {
        val host = bridge.frigateHostUrl.trim().trimEnd('/')
        if (host.isBlank()) return@withContext emptyList()
        return@withContext try {
            val resp = SharedHttpClient.requestText(
                url = "$host/api/config",
                headers = mapOf("Accept" to "application/json"),
                connectTimeoutMs = 3000,
                readTimeoutMs = 3000
            )
            if (!resp.isSuccessful) return@withContext emptyList()
            val root = JSONObject(resp.body)
            val camerasObj = root.optJSONObject("cameras") ?: return@withContext emptyList()
            val list = mutableListOf<SmartTile>()
            val camKeys = camerasObj.keys()
            val cleanHostIp = host.substringAfter("://").substringBefore(":")
            while (camKeys.hasNext()) {
                val camKey = camKeys.next()
                val displayTitle = camKey.replace("_", " ").replaceFirstChar { it.uppercase(Locale.US) }
                list.add(
                    SmartTile(
                        title = "Frigate: $displayTitle",
                        roomOrSubtitle = inferRoomFromDeviceTitle(displayTitle, bridge.customRooms).ifBlank { "Front Door" },
                        type = TileType.IMOU_CAMERA,
                        colSpan = 2,
                        ipAddress = cleanHostIp,
                        serialNumber = "frigate_$camKey",
                        entityId = "frigate_$camKey",
                        customRtspUrl = "rtsp://$cleanHostIp:8554/$camKey",
                        isOnline = true,
                        isActive = true,
                        deviceClassOverride = DeviceClass.CAMERA,
                        deviceSubtypeOverride = DeviceSubtype.CAMERA,
                        activeProviderId = ProviderId.FRIGATE_NVR.id,
                        stateAuthorityTier = StateAuthorityTier.DEVICE_HARDWARE_TRUTH
                    )
                )
            }
            list
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
    emptyList()
}
    }

    suspend fun pollFrigateObjectDetectionEvents(
        bridge: BridgeSettings,
        tiles: MutableList<SmartTile>
    ): List<ObjectDetectionCapability> = withContext(Dispatchers.IO) {
        val host = bridge.frigateHostUrl.trim().trimEnd('/')
        if (host.isBlank()) return@withContext emptyList()
        return@withContext try {
            val resp = SharedHttpClient.requestText(
                url = "$host/api/events?limit=10",
                headers = mapOf("Accept" to "application/json"),
                connectTimeoutMs = 2500,
                readTimeoutMs = 2500
            )
            if (!resp.isSuccessful) return@withContext emptyList()
            val arr = JSONArray(resp.body)
            val detections = mutableListOf<ObjectDetectionCapability>()
            for (i in 0 until arr.length()) {
                val ev = arr.optJSONObject(i) ?: continue
                val camName = ev.optString("camera", "")
                val labelRaw = ev.optString("label", "").lowercase(Locale.US)
                val score = ev.optDouble("top_score", ev.optDouble("score", 0.85)).toFloat()
                val startTimeSec = ev.optDouble("start_time", (System.currentTimeMillis() / 1000.0))
                val eventId = ev.optString("id", "")
                val mappedType = when {
                    labelRaw.contains("person") -> CameraAiEventType.PERSON
                    labelRaw.contains("car") || labelRaw.contains("vehicle") -> CameraAiEventType.VEHICLE
                    labelRaw.contains("package") || labelRaw.contains("box") -> CameraAiEventType.PACKAGE
                    labelRaw.contains("dog") || labelRaw.contains("cat") || labelRaw.contains("bird") -> CameraAiEventType.ANIMAL
                    else -> CameraAiEventType.MOTION
                }
                val zonesArr = ev.optJSONArray("zones") ?: JSONArray()
                val zones = (0 until zonesArr.length()).mapNotNull { z -> zonesArr.optString(z).takeIf { it.isNotBlank() } }
                detections.add(
                    ObjectDetectionCapability(
                        cameraId = "frigate_$camName",
                        cameraName = camName,
                        detectedLabel = mappedType,
                        confidenceScore = score,
                        zoneNames = zones,
                        snapshotUrl = if (eventId.isNotBlank()) "$host/api/events/$eventId/snapshot.jpg" else "",
                        eventTimestampEpochMs = (startTimeSec * 1000.0).toLong()
                    )
                )
            }
            if (detections.isNotEmpty()) {
                val latestByCam = detections.groupBy { it.cameraId }.mapValues { it.value.first() }
                withContext(Dispatchers.Main) {
                    for (idx in tiles.indices) {
                        val t = tiles[idx]
                        val det = latestByCam[t.serialNumber] ?: latestByCam[t.entityId] ?: continue
                        if (det.eventTimestampEpochMs > t.lastCameraEventTimestampMs) {
                            tiles[idx] = t.copy(
                                lastCameraAiEvent = det.detectedLabel,
                                lastCameraEventTimestampMs = det.eventTimestampEpochMs
                            )
                        }
                    }
                }
            }
            detections
        } catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
        t = e,
        transportSource = TAG,
        operationStage = "execution",
        logToDiscoveryRecorder = true,
        surfaceToUiBanner = true
    )
    emptyList()
}
    }

    suspend fun pollUnifiAndBleSpatialPresence(
        @Suppress("UNUSED_PARAMETER") context: Context,
        bridge: BridgeSettings
    ): List<PresenceCapability> = withContext(Dispatchers.IO) {
        val list = mutableListOf<PresenceCapability>()
        val nowMs = System.currentTimeMillis()

        // Fuse passive BLE sightings from UniversalOnboardingEngine with household rooms
        val resolvedBle = UniversalOnboardingEngine.resolvedIdentitiesFlow.value
        for (id in resolvedBle) {
            if (id.suggestedRoom.isNotBlank() && id.isOnline) {
                list.add(
                    PresenceCapability(
                        personOrDeviceId = id.identityKey,
                        displayName = id.displayName,
                        isHome = true,
                        currentRoomOrApName = id.suggestedRoom,
                        rssiDbm = -58,
                        sourceProtocol = "BLE_LAN_FUSION",
                        lastSeenEpochMs = nowMs
                    )
                )
            }
        }
        cachedSpatialPresenceList = list
        list
    }
}
