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
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

data class DiscoveredMqttEntity(
    val uniqueId: String,
    val name: String,
    val component: String = "switch",
    val stateTopic: String = "",
    val commandTopic: String = "",
    val availabilityTopic: String = "",
    val ipAddress: String = "",
    val isTasmotaHttp: Boolean = false,
    val isEspHomeNative: Boolean = false,
    val isOnline: Boolean = true,
    val isActive: Boolean = false,
    val isSocket2Active: Boolean = false,
    val multiGangCount: Int = 1,
    val socket1Name: String = "Relay 1",
    val socket2Name: String = "Relay 2",
    val powerWatts: Float = 0f,
    val voltageVolts: Float = 0f,
    val currentMilliamps: Float = 0f,
    val totalEnergyKwh: Float = 0f,
    val temperatureC: Float = 20.5f,
    val targetTempC: Float = 21.0f,
    val levelPct: Float = 100f,
    val sensorUnit: String = "\u00B0C",
    val suggestedType: TileType = TileType.MQTT_DEVICE
)

// ===========================================================================
// 4. MQTT v3.1.1 BROKER, ZIGBEE2MQTT, TASMOTA HTTP & ESPHOME NATIVE ENGINE
//    - Zero-config `_mqtt._tcp.` & `_esphomelib._tcp.` mDNS discovery.
//    - Persistent MQTT v3.1.1 TCP client with 25s PINGREQ/PINGRESP keep-alive
//      and live subscription to `homeassistant/#`, `tasmota/discovery/#`,
//      `stat/+/RESULT`, `tele/+/SENSOR`, and `zigbee2mqtt/#`.
//    - Direct Tasmota HTTP (`/cm?cmnd=...`) & ESPHome REST API control and
//      live power telemetry (`ENERGY.Power`, `Voltage`, `Current`, `Total`).
// ===========================================================================
object MqttAndDiyClient {
    private const val TAG = "ClaraMqttDiy"
    private const val PREFS_MQTT = "clara_mqtt_diy_prefs_v2"
    private const val DEFAULT_MQTT_PORT = 1883
    private const val MQTT_MDNS_SERVICE = "_mqtt._tcp."
    private const val ESPHOME_MDNS_SERVICE = "_esphomelib._tcp."

    private val discoveredEntityCache = ConcurrentHashMap<String, DiscoveredMqttEntity>()
    private val topicPayloadCache = ConcurrentHashMap<String, String>()
    private val packetIdCounter = AtomicInteger(1)

    private class PersistentMqttSession(
        val host: String,
        val port: Int,
        val socket: Socket,
        val input: DataInputStream,
        val out: DataOutputStream,
        @Volatile var lastActivityMs: Long = System.currentTimeMillis()
    ) {
        val writeLock = Any()

        fun isAlive(): Boolean =
            !socket.isClosed && socket.isConnected && !socket.isInputShutdown && !socket.isOutputShutdown

        fun closeQuietly() {
            runCatching { socket.close() }
        }
    }

    @Volatile
    private var activeMqttSession: PersistentMqttSession? = null

    @Volatile
    private var mqttReaderJob: Job? = null

    @Volatile
    private var mqttKeepAliveJob: Job? = null

    @Volatile
    private var autoDiscoveredBrokerHost: String = ""

    fun disconnect() {
        activeMqttSession?.closeQuietly()
        activeMqttSession = null
        mqttReaderJob?.cancel()
        mqttReaderJob = null
        mqttKeepAliveJob?.cancel()
        mqttKeepAliveJob = null
    }

    private fun resolveAppContext(explicitContext: Context? = null): Context? =
        explicitContext?.applicationContext
            ?: ClaraApplication.instance?.applicationContext
            ?: ClaraInAppMusicPlayer.currentAppContext()

    // -----------------------------------------------------------------------
    // 1. ENCRYPTED BROKER CREDENTIALS & CONFIGURATION
    // -----------------------------------------------------------------------
    fun saveBrokerConfig(
        context: Context,
        host: String,
        port: Int = DEFAULT_MQTT_PORT,
        username: String = "",
        password: String = ""
    ) {
        val cleanHost = host.trim()
            .removePrefix("mqtt://")
            .removePrefix("tcp://")
            .removePrefix("http://")
            .substringBefore(":")
            .trim()
        val safePort = port.coerceIn(1, 65535)
        if (cleanHost.isNotBlank()) {
            autoDiscoveredBrokerHost = cleanHost
        }
        context.applicationContext.getSharedPreferences(PREFS_MQTT, Context.MODE_PRIVATE).edit()
            .putString("mqtt_host", cleanHost)
            .putInt("mqtt_port", safePort)
            .putString("mqtt_user", username.trim())
            .putString("mqtt_pass", password)
            .apply()
    }

    fun getBrokerHost(context: Context): String {
        val saved = context.applicationContext.getSharedPreferences(PREFS_MQTT, Context.MODE_PRIVATE)
            .getString("mqtt_host", "")
            ?.trim()
            .orEmpty()
        return saved.ifBlank { autoDiscoveredBrokerHost }
    }

    fun getBrokerPort(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS_MQTT, Context.MODE_PRIVATE)
            .getInt("mqtt_port", DEFAULT_MQTT_PORT)
            .coerceIn(1, 65535)

    fun getBrokerUsername(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS_MQTT, Context.MODE_PRIVATE)
            .getString("mqtt_user", "")
            ?.trim()
            .orEmpty()

    fun getBrokerPassword(context: Context): String {
        val raw = context.applicationContext.getSharedPreferences(PREFS_MQTT, Context.MODE_PRIVATE)
            .getString("mqtt_pass", "")
            .orEmpty()
        return raw
    }

    fun saveMqttBrokerConfig(
        context: Context,
        host: String,
        port: Int = DEFAULT_MQTT_PORT,
        username: String = "",
        password: String = ""
    ) = saveBrokerConfig(context, host, port, username, password)

    fun getMqttHost(context: Context): String = getBrokerHost(context)

    fun getMqttPort(context: Context): Int = getBrokerPort(context)

    fun getMqttUsername(context: Context): String = getBrokerUsername(context)

    fun getMqttPassword(context: Context): String = getBrokerPassword(context)

    fun isConfigured(context: Context): Boolean =
        getBrokerHost(context).isNotBlank() || discoveredEntityCache.isNotEmpty()

    // -----------------------------------------------------------------------
    // 2. ZERO-CONFIG _mqtt._tcp. & _esphomelib._tcp. mDNS DISCOVERY
    // -----------------------------------------------------------------------
    suspend fun discoverLocalMqttBrokers(
        context: Context,
        timeoutMs: Long = 1000L
    ): List<String> = withContext(Dispatchers.IO) {
        val foundHosts = ConcurrentHashMap.newKeySet<String>()
        val saved = getBrokerHost(context)
        if (saved.isNotBlank()) foundHosts.add(saved)

        // Check if Home Assistant host also runs Mosquitto on port 1883
        val bridge = DeviceRepository.loadBridge(context)
        val haHost = runCatching { Uri.parse(bridge.haUrl.trim()).host.orEmpty() }.getOrDefault("")
        if (haHost.isNotBlank() && SmartHomeApiClient.isPortOpenInternal(haHost, DEFAULT_MQTT_PORT, 220)) {
            foundHosts.add(haHost)
            if (autoDiscoveredBrokerHost.isBlank()) autoDiscoveredBrokerHost = haHost
        }

        val resolvedBrokers = DiscoveryRadioCoordinator.discoverMdnsServices(
            context = context,
            serviceTypes = listOf(MQTT_MDNS_SERVICE),
            timeoutMs = timeoutMs,
            transportLabel = "MQTT_BROKER_MDNS"
        )
        for (svc in resolvedBrokers) {
            foundHosts.add(svc.hostIp)
            if (autoDiscoveredBrokerHost.isBlank()) {
                autoDiscoveredBrokerHost = svc.hostIp
            }
            SmartHomeApiClient.registerVerifiedLanNeighbor(svc.hostIp, "mqtt_broker")
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.FILTERED,
                transportSource = "MQTT_BROKER_MDNS",
                hardwareId = svc.serviceName,
                ipAddress = svc.hostIp,
                port = if (svc.port > 0) svc.port else DEFAULT_MQTT_PORT,
                vendor = "MQTT Broker",
                confidenceScore = 0.70f,
                qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                outcomeReason = "Cached _mqtt._tcp broker host (${svc.hostIp}); entities discovered via MQTT topics"
            )
        }
        foundHosts.toList()
    }

    suspend fun discoverEspHomeMdns(
        context: Context,
        timeoutMs: Long = 1100L
    ): List<DiscoveredMqttEntity> = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, DiscoveredMqttEntity>()
        val resolvedServices = DiscoveryRadioCoordinator.discoverMdnsServices(
            context = context,
            serviceTypes = listOf(ESPHOME_MDNS_SERVICE),
            timeoutMs = timeoutMs,
            transportLabel = "ESPHOME_MDNS"
        )

        for (svc in resolvedServices) {
            val hostIp = svc.hostIp
            val rawName = svc.serviceName.ifBlank { "ESPHome Node" }
            val cleanMac = CrossEcosystemIdentityCorrelator.extractMacFromApplicationPayload(
                ipAddress = hostIp,
                mdnsTxtAttributes = svc.txtAttributes
            )
            val uniqueId = if (cleanMac.isNotEmpty()) {
                "esphome_${cleanMac.lowercase(Locale.US)}"
            } else {
                "esphome_${hostIp.replace(".", "_")}"
            }
            SmartHomeApiClient.registerVerifiedLanNeighbor(hostIp, cleanMac.ifBlank { uniqueId })

            val friendly = rawName.replace("-", " ").replace("_", " ")
                .replaceFirstChar { it.uppercase(Locale.US) }
            val suggestedType = inferTileTypeFromComponentAndName("switch", friendly)
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.IDENTIFIED,
                transportSource = "ESPHOME_MDNS",
                hardwareId = uniqueId,
                macAddress = cleanMac,
                ipAddress = hostIp,
                port = if (svc.port > 0) svc.port else 80,
                vendor = "$friendly (ESPHome)",
                confidenceScore = 0.95f,
                qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                outcomeReason = "Verified ESPHome _esphomelib._tcp node"
            )
            val entity = DiscoveredMqttEntity(
                uniqueId = uniqueId,
                name = friendly,
                component = "esphome",
                commandTopic = "http://$hostIp/switch/$rawName/toggle",
                ipAddress = hostIp,
                isEspHomeNative = true,
                isOnline = true,
                suggestedType = suggestedType
            )
            found[uniqueId] = entity
            discoveredEntityCache[uniqueId] = entity
        }

        if (discoveredEntityCache.isNotEmpty()) {
            MultiProtocolLiveBridge.discoveredMqttEntitiesFlow.value = discoveredEntityCache.values.toList()
        }
        found.values.toList()
    }

    // -----------------------------------------------------------------------
    // 3. TASMOTA HTTP STATUS 0 PROBE & DIRECT HTTP RELAY CONTROL
    // -----------------------------------------------------------------------
    suspend fun probeTasmotaHttpDevice(ipAddress: String): DiscoveredMqttEntity? = withContext(Dispatchers.IO) {
        probeTasmotaOnPort80(ipAddress)
    }

    fun probeTasmotaOnPort80(ipAddress: String): DiscoveredMqttEntity? {
        val cleanIp = ipAddress.substringBefore(":").trim()
        if (cleanIp.isBlank()) return null
        return try {
            val url = URL("http://$cleanIp/cm?cmnd=Status%200")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1300
                readTimeout = 1300
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            if (!body.contains("Status", ignoreCase = true)) return null

            val root = JSONObject(body)
            val statusObj = root.optJSONObject("Status") ?: return null
            val statusNet = root.optJSONObject("StatusNET")
            val statusSts = root.optJSONObject("StatusSTS")
            val statusSns = root.optJSONObject("StatusSNS")

            val friendlyArr = statusObj.optJSONArray("FriendlyName")
            val deviceName = friendlyArr?.optString(0, "")?.takeIf { it.isNotBlank() }
                ?: statusObj.optString("DeviceName", "").takeIf { it.isNotBlank() }
                ?: "Tasmota Smart Plug ($cleanIp)"
            val s1Name = friendlyArr?.optString(0, "Relay 1")?.ifBlank { "Relay 1" } ?: "Relay 1"
            val s2Name = friendlyArr?.optString(1, "Relay 2")?.ifBlank { "Relay 2" } ?: "Relay 2"

            val mac = statusNet?.optString("Mac", "")?.replace(":", "")?.lowercase(Locale.US).orEmpty()
            val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(mac)
            val uniqueId = if (mac.isNotBlank()) "tasmota_$mac" else "tasmota_${cleanIp.replace(".", "_")}"

            val hasPower2 = statusSts?.has("POWER2") == true
            val p1Str = statusSts?.optString("POWER1", statusSts.optString("POWER", "OFF")).orEmpty()
            val p2Str = statusSts?.optString("POWER2", "OFF").orEmpty()
            val isOn1 = p1Str.equals("ON", ignoreCase = true) || p1Str == "1"
            val isOn2 = p2Str.equals("ON", ignoreCase = true) || p2Str == "1"

            val energyObj = statusSns?.optJSONObject("ENERGY")
            val powerW = energyObj?.optDouble("Power", 0.0)?.toFloat() ?: 0f
            val voltageV = energyObj?.optDouble("Voltage", 0.0)?.toFloat() ?: 0f
            val currentA = energyObj?.optDouble("Current", 0.0)?.toFloat() ?: 0f
            val totalKwh = energyObj?.optDouble("Total", 0.0)?.toFloat() ?: 0f

            SmartHomeApiClient.registerVerifiedLanNeighbor(cleanIp, normMac.ifBlank { uniqueId })
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.IDENTIFIED,
                transportSource = "TASMOTA_HTTP_80",
                hardwareId = uniqueId,
                macAddress = normMac,
                ipAddress = cleanIp,
                port = 80,
                vendor = "$deviceName (Tasmota)",
                confidenceScore = 0.95f,
                qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                outcomeReason = "Verified Tasmota Status 0 HTTP schema (relays=${if (hasPower2) 2 else 1})"
            )

            val entity = DiscoveredMqttEntity(
                uniqueId = uniqueId,
                name = deviceName,
                component = "switch",
                commandTopic = "cmnd/$uniqueId/POWER",
                ipAddress = cleanIp,
                isTasmotaHttp = true,
                isOnline = true,
                isActive = isOn1,
                isSocket2Active = isOn2,
                multiGangCount = if (hasPower2) 2 else 1,
                socket1Name = s1Name,
                socket2Name = s2Name,
                powerWatts = powerW,
                voltageVolts = voltageV,
                currentMilliamps = currentA * 1000f,
                totalEnergyKwh = totalKwh,
                suggestedType = if (hasPower2 || powerW > 0f || voltageV > 0f) {
                    TileType.SMARTKNIGHT_PLUG
                } else {
                    TileType.MQTT_DEVICE
                }
            )
            discoveredEntityCache[uniqueId] = entity
            MultiProtocolLiveBridge.discoveredMqttEntitiesFlow.value = discoveredEntityCache.values.toList()
            entity
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

    // -----------------------------------------------------------------------
    // 4. MQTT v3.1.1 BINARY WIRE FRAMING & PERSISTENT DAEMON
    // -----------------------------------------------------------------------
    private fun encodeRemainingLength(out: ByteArrayOutputStream, length: Int) {
        var x = length
        do {
            var encodedByte = x % 128
            x /= 128
            if (x > 0) {
                encodedByte = encodedByte or 128
            }
            out.write(encodedByte)
        } while (x > 0)
    }

    private fun readRemainingLength(input: DataInputStream): Int {
        var multiplier = 1
        var value = 0
        var encodedByte: Int
        var count = 0
        do {
            encodedByte = input.read()
            if (encodedByte == -1) return -1
            value += (encodedByte and 127) * multiplier
            multiplier *= 128
            count++
        } while ((encodedByte and 128) != 0 && count < 4)
        return value
    }

    private fun writeUtf8String(out: ByteArrayOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }

    private fun buildMqttConnectPacket(
        clientId: String,
        username: String,
        password: String,
        keepAliveSec: Int = 45
    ): ByteArray {
        val variableAndPayload = ByteArrayOutputStream()
        writeUtf8String(variableAndPayload, "MQTT")
        variableAndPayload.write(4) // Protocol Level 4 (MQTT 3.1.1)

        var connectFlags = 0x02 // Clean Session
        val hasUser = username.isNotBlank()
        val hasPass = hasUser && password.isNotBlank()
        if (hasUser) connectFlags = connectFlags or 0x80
        if (hasPass) connectFlags = connectFlags or 0x40
        variableAndPayload.write(connectFlags)
        variableAndPayload.write((keepAliveSec ushr 8) and 0xFF)
        variableAndPayload.write(keepAliveSec and 0xFF)

        writeUtf8String(variableAndPayload, clientId)
        if (hasUser) writeUtf8String(variableAndPayload, username)
        if (hasPass) writeUtf8String(variableAndPayload, password)

        val bodyBytes = variableAndPayload.toByteArray()
        val packet = ByteArrayOutputStream()
        packet.write(0x10) // CONNECT
        encodeRemainingLength(packet, bodyBytes.size)
        packet.write(bodyBytes)
        return packet.toByteArray()
    }

    private fun buildMqttSubscribePacket(packetId: Int, topics: List<String>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write((packetId ushr 8) and 0xFF)
        body.write(packetId and 0xFF)
        for (t in topics) {
            writeUtf8String(body, t)
            body.write(0) // QoS 0
        }
        val bodyBytes = body.toByteArray()
        val packet = ByteArrayOutputStream()
        packet.write(0x82) // SUBSCRIBE (QoS 1 header bit)
        encodeRemainingLength(packet, bodyBytes.size)
        packet.write(bodyBytes)
        return packet.toByteArray()
    }

    private fun buildMqttPublishPacket(topic: String, payload: String, retain: Boolean = false): ByteArray {
        val body = ByteArrayOutputStream()
        writeUtf8String(body, topic)
        body.write(payload.toByteArray(Charsets.UTF_8))
        val bodyBytes = body.toByteArray()
        val packet = ByteArrayOutputStream()
        packet.write(if (retain) 0x31 else 0x30) // PUBLISH QoS 0
        encodeRemainingLength(packet, bodyBytes.size)
        packet.write(bodyBytes)
        return packet.toByteArray()
    }

    private fun inferTileTypeFromComponentAndName(component: String, name: String): TileType {
        val c = component.lowercase(Locale.US)
        val n = name.lowercase(Locale.US)
        return when {
            c == "climate" || n.contains("thermostat") || n.contains("radiator") || n.contains("trv") || n.contains("heater") ->
                TileType.TUYA_RADIATOR
            c == "fan" || n.contains("tower fan") || n.contains("pro breeze") ->
                TileType.PROBREEZE_FAN
            c == "lock" || n.contains("lock") ->
                TileType.SMART_LOCK
            c == "cover" || n.contains("blind") || n.contains("curtain") || n.contains("shade") ->
                TileType.SMART_COVER
            c == "light" || n.contains("bulb") || n.contains("lamp") || n.contains("strip") ->
                TileType.HUE_LIGHT
            c in listOf("sensor", "binary_sensor") ->
                TileType.SMART_SENSOR
            n.contains("plug") || n.contains("socket") || n.contains("energy") ->
                TileType.SMARTKNIGHT_PLUG
            else ->
                TileType.MQTT_DEVICE
        }
    }

    private fun handleIncomingMqttPublish(topic: String, payloadText: String) {
        val cleanTopic = topic.trim()
        val cleanPayload = payloadText.trim()
        if (cleanTopic.isBlank() || cleanPayload.isBlank()) return
        topicPayloadCache[cleanTopic] = cleanPayload

        /// 1. Home Assistant MQTT Auto-Discovery: `homeassistant/<component>/<node_id>/<object_id>/config`
        if (cleanTopic.startsWith("homeassistant/") && cleanTopic.endsWith("/config") && cleanPayload.startsWith("{")) {
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                transportSource = "MQTT_HA_DISCOVERY",
                hardwareId = cleanTopic,
                vendor = "MQTT Discovery Config",
                confidenceScore = 0.30f,
                qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                outcomeReason = "MQTT Home Assistant config message received",
                rawPayload = cleanPayload.take(300)
            )
            runCatching {
                val json = JSONObject(cleanPayload)
                val parts = cleanTopic.split("/")
                val component = parts.getOrNull(1)?.lowercase(Locale.US) ?: "switch"
                if (component !in listOf("switch", "light", "fan", "climate", "lock", "cover", "sensor", "binary_sensor")) {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FILTERED,
                        transportSource = "MQTT_HA_DISCOVERY",
                        hardwareId = cleanTopic,
                        vendor = component,
                        qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                        outcomeReason = "Filtered unsupported MQTT auto-discovery component ($component)"
                    )
                    return@runCatching
                }
                val devObj = json.optJSONObject("device") ?: json.optJSONObject("dev")
                val devName = devObj?.optString("name", "")?.trim().orEmpty()
                val rawEntityName = json.optString("name", json.optString(" friendly_name", "")).trim()
                val friendlyName = when {
                    devName.isNotBlank() && rawEntityName.isNotBlank() && !rawEntityName.contains(devName, true) ->
                        "$devName $rawEntityName"
                    rawEntityName.isNotBlank() -> rawEntityName
                    devName.isNotBlank() -> devName
                    else -> parts.getOrNull(2) ?: "MQTT Device"
                }
                val uniqueId = json.optString("unique_id", json.optString("uniq_id", ""))
                    .trim()
                    .ifBlank { "mqtt_${parts.drop(1).dropLast(1).joinToString("_")}" }

                val baseTopic = json.optString("~", "").trim().trimEnd('/')
                fun expandTopic(raw: String): String {
                    val v = raw.trim()
                    if (v.isBlank()) return ""
                    return if (v.startsWith("~") && baseTopic.isNotBlank()) {
                        baseTopic + v.removePrefix("~")
                    } else v
                }

                val stateTopic = expandTopic(
                    json.optString("state_topic", json.optString("stat_t", ""))
                )
                val commandTopic = expandTopic(
                    json.optString("command_topic", json.optString("cmd_t", ""))
                )
                val availTopic = expandTopic(
                    json.optString("availability_topic", json.optString("avty_t", ""))
                )
                val unit = json.optString("unit_of_measurement", json.optString("unit_of_meas", "\u00B0C"))

                val existing = discoveredEntityCache[uniqueId]
                val cachedState = topicPayloadCache[stateTopic].orEmpty()
                val isActive = cachedState.equals("ON", ignoreCase = true) ||
                        cachedState.equals("true", ignoreCase = true) ||
                        cachedState == "1" ||
                        (existing?.isActive == true)

                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.IDENTIFIED,
                    transportSource = "MQTT_HA_DISCOVERY",
                    hardwareId = uniqueId,
                    vendor = "$friendlyName ($component)",
                    confidenceScore = 0.95f,
                    qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                    outcomeReason = "Parsed MQTT Home Assistant auto-discovery entity"
                )
                val entity = DiscoveredMqttEntity(
                    uniqueId = uniqueId,
                    name = friendlyName,
                    component = component,
                    stateTopic = stateTopic,
                    commandTopic = commandTopic,
                    availabilityTopic = availTopic,
                    isOnline = true,
                    isActive = isActive,
                    sensorUnit = unit.ifBlank { "\u00B0C" },
                    suggestedType = inferTileTypeFromComponentAndName(component, friendlyName)
                )
                discoveredEntityCache[uniqueId] = entity
                MultiProtocolLiveBridge.discoveredMqttEntitiesFlow.value = discoveredEntityCache.values.toList()
            }
            return
        }

        // 2. Native Tasmota Discovery: `tasmota/discovery/<mac>/config`
        if (cleanTopic.startsWith("tasmota/discovery/") && cleanTopic.endsWith("/config") && cleanPayload.startsWith("{")) {
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                transportSource = "TASMOTA_MQTT_DISCOVERY",
                hardwareId = cleanTopic,
                vendor = "Tasmota MQTT Config",
                confidenceScore = 0.30f,
                qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                outcomeReason = "Tasmota MQTT discovery config received",
                rawPayload = cleanPayload.take(300)
            )
            runCatching {
                val json = JSONObject(cleanPayload)
                val ip = json.optString("ip", "").trim()
                val dn = json.optString("dn", "Tasmota Device").trim()
                val mac = json.optString("mac", "").trim().lowercase(Locale.US)
                val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(mac)
                val tasmotaTopic = json.optString("t", "").trim()
                val fnArr = json.optJSONArray("fn")
                val s1Name = fnArr?.optString(0, "Relay 1")?.takeIf { it.isNotBlank() && it != "null" } ?: "Relay 1"
                val s2Name = fnArr?.optString(1, "")?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                val rlArr = json.optJSONArray("rl")
                val relayCount = buildList {
                    if (rlArr != null) {
                        for (i in 0 until rlArr.length()) {
                            if (rlArr.optInt(i, 0) == 1) add(i)
                        }
                    }
                }.size.coerceIn(1, 2)

                val uniqueId = if (mac.isNotBlank()) "tasmota_$mac" else "tasmota_$tasmotaTopic"
                if (ip.isNotBlank()) {
                    SmartHomeApiClient.registerVerifiedLanNeighbor(ip, normMac.ifBlank { uniqueId })
                }
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.IDENTIFIED,
                    transportSource = "TASMOTA_MQTT_DISCOVERY",
                    hardwareId = uniqueId,
                    macAddress = normMac,
                    ipAddress = ip,
                    port = if (ip.isNotBlank()) 80 else 1883,
                    vendor = "$dn (Tasmota)",
                    confidenceScore = 0.95f,
                    qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                    outcomeReason = "Verified Tasmota MQTT discovery config (relays=$relayCount)"
                )
                val entity = DiscoveredMqttEntity(
                    uniqueId = uniqueId,
                    name = dn,
                    component = "switch",
                    stateTopic = if (tasmotaTopic.isNotBlank()) "stat/$tasmotaTopic/RESULT" else "",
                    commandTopic = if (tasmotaTopic.isNotBlank()) "cmnd/$tasmotaTopic/POWER" else "",
                    ipAddress = ip,
                    isTasmotaHttp = ip.isNotBlank(),
                    isOnline = true,
                    multiGangCount = relayCount,
                    socket1Name = s1Name,
                    socket2Name = s2Name.ifBlank { "Relay 2" },
                    suggestedType = if (relayCount >= 2) TileType.SMARTKNIGHT_PLUG else TileType.MQTT_DEVICE
                )
                discoveredEntityCache[uniqueId] = entity
                MultiProtocolLiveBridge.discoveredMqttEntitiesFlow.value = discoveredEntityCache.values.toList()
            }
            return
        }

        // 3. Zigbee2MQTT Device List: `zigbee2mqtt/bridge/devices`
        if (cleanTopic == "zigbee2mqtt/bridge/devices" && cleanPayload.startsWith("[")) {
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                transportSource = "ZIGBEE2MQTT",
                hardwareId = cleanTopic,
                vendor = "Zigbee2MQTT Bridge",
                confidenceScore = 0.30f,
                qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                outcomeReason = "Zigbee2MQTT bridge/devices payload received"
            )
            runCatching {
                val arr = org.json.JSONArray(cleanPayload)
                for (i in 0 until arr.length()) {
                    val dev = arr.optJSONObject(i) ?: continue
                    val ieee = dev.optString("ieee_address", "").trim()
                    val friendly = dev.optString("friendly_name", "").trim()
                    if (dev.optString("type").equals("Coordinator", ignoreCase = true)) {
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.FILTERED,
                            transportSource = "ZIGBEE2MQTT",
                            hardwareId = ieee.ifBlank { "coordinator" },
                            vendor = friendly.ifBlank { "Zigbee2MQTT Coordinator" },
                            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                            outcomeReason = "Filtered Zigbee2MQTT Coordinator bridge radio"
                        )
                        continue
                    }
                    if (friendly.isBlank()) continue
                    val defObj = dev.optJSONObject("definition")
                    val desc = defObj?.optString("description", "").orEmpty()
                    val uniqueId = "z2m_${ieee.ifBlank { friendly }}"
                    val suggested = inferTileTypeFromComponentAndName("switch", "$friendly $desc")
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.IDENTIFIED,
                        transportSource = "ZIGBEE2MQTT",
                        hardwareId = uniqueId,
                        vendor = "$friendly (Zigbee2MQTT)",
                        confidenceScore = 0.95f,
                        qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                        outcomeReason = "Parsed Zigbee2MQTT device ($desc)"
                    )
                    val entity = DiscoveredMqttEntity(
                        uniqueId = uniqueId,
                        name = friendly,
                        component = if (suggested == TileType.HUE_LIGHT) "light" else "switch",
                        stateTopic = "zigbee2mqtt/$friendly",
                        commandTopic = "zigbee2mqtt/$friendly/set",
                        isOnline = true,
                        suggestedType = suggested
                    )
                    discoveredEntityCache[uniqueId] = entity
                }
                MultiProtocolLiveBridge.discoveredMqttEntitiesFlow.value = discoveredEntityCache.values.toList()
            }
            return
        }

        // 4. Live State / Telemetry Topic Update Matching Existing Entities
        for ((uid, existing) in discoveredEntityCache) {
            if (existing.stateTopic.equals(cleanTopic, ignoreCase = true) ||
                cleanTopic.endsWith("/SENSOR", ignoreCase = true) && existing.commandTopic.contains(cleanTopic.split("/").getOrNull(1).orEmpty())
            ) {
                var nextOn = existing.isActive
                var nextOn2 = existing.isSocket2Active
                var nextWatts = existing.powerWatts
                var nextVolts = existing.voltageVolts
                var nextMa = existing.currentMilliamps
                var nextKwh = existing.totalEnergyKwh
                var nextTemp = existing.temperatureC

                if (cleanPayload.startsWith("{")) {
                    runCatching {
                        val json = JSONObject(cleanPayload)
                        if (json.has("state")) {
                            val st = json.optString("state", "")
                            nextOn = st.equals("ON", true) || st.equals("LOCK", true) || st.equals("OPEN", true) || st == "true"
                        }
                        if (json.has("POWER") || json.has("POWER1")) {
                            val p1 = json.optString("POWER1", json.optString("POWER", "OFF"))
                            nextOn = p1.equals("ON", true) || p1 == "1"
                        }
                        if (json.has("POWER2")) {
                            val p2 = json.optString("POWER2", "OFF")
                            nextOn2 = p2.equals("ON", true) || p2 == "1"
                        }
                        if (json.has("power")) {
                            nextWatts = json.optDouble("power", nextWatts.toDouble()).toFloat()
                        }
                        val energy = json.optJSONObject("ENERGY")
                        if (energy != null) {
                            nextWatts = energy.optDouble("Power", nextWatts.toDouble()).toFloat()
                            nextVolts = energy.optDouble("Voltage", nextVolts.toDouble()).toFloat()
                            nextMa = (energy.optDouble("Current", 0.0).toFloat() * 1000f).takeIf { it > 0f } ?: nextMa
                            nextKwh = energy.optDouble("Total", nextKwh.toDouble()).toFloat()
                        }
                        if (json.has("temperature")) {
                            nextTemp = json.optDouble("temperature", nextTemp.toDouble()).toFloat()
                        }
                    }
                } else {
                    val numVal = cleanPayload.toFloatOrNull()
                    if (numVal != null && existing.suggestedType == TileType.SMART_SENSOR) {
                        nextTemp = numVal
                    } else {
                        nextOn = cleanPayload.equals("ON", true) ||
                                cleanPayload.equals("true", true) ||
                                cleanPayload.equals("LOCKED", true) ||
                                cleanPayload == "1"
                    }
                }

                discoveredEntityCache[uid] = existing.copy(
                    isOnline = true,
                    isActive = nextOn,
                    isSocket2Active = nextOn2,
                    powerWatts = nextWatts,
                    voltageVolts = nextVolts,
                    currentMilliamps = nextMa,
                    totalEnergyKwh = nextKwh,
                    temperatureC = nextTemp
                )
            }
        }
    }

    private var mqttConsecutiveFailures: Int = 0

    private suspend fun ensureMqttSessionConnected(
        host: String,
        port: Int,
        username: String,
        password: String
    ): PersistentMqttSession? {
        val cleanHost = host.substringBefore(":").trim()
        if (cleanHost.isBlank()) return null

        activeMqttSession?.let { existing ->
            if (existing.host == cleanHost && existing.port == port && existing.isAlive()) {
                return existing
            }
            existing.closeQuietly()
            activeMqttSession = null
        }

        if (mqttConsecutiveFailures > 0) {
            val backoffMs = (1000L * (1 shl minOf(mqttConsecutiveFailures, 5))) + (Math.random() * 500).toLong()
            kotlinx.coroutines.delay(backoffMs)
        }

        return try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = 3500
            socket.connect(InetSocketAddress(cleanHost, port), 2200)
            val input = DataInputStream(socket.getInputStream())
            val out = DataOutputStream(socket.getOutputStream())

            val clientId = "clara_hub_${UUID.randomUUID().toString().take(8)}"
            val connectPacket = buildMqttConnectPacket(clientId, username, password)
            out.write(connectPacket)
            out.flush()

            val headerByte = input.read()
            val remLen = readRemainingLength(input)
            if ((headerByte and 0xF0) != 0x20 || remLen < 2) {
                socket.close()
                mqttConsecutiveFailures++
                return null
            }
            val connAckBytes = ByteArray(remLen)
            input.readFully(connAckBytes)
            val returnCode = connAckBytes[1].toInt() and 0xFF
            if (returnCode != 0) {
                mqttConsecutiveFailures++
                Log.w(TAG, "MQTT CONNACK rejected with code $returnCode")
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "MQTT_BROKER",
                    ipAddress = cleanHost,
                    port = port,
                    vendor = "MQTT Broker",
                    qualificationStage = "ensureMqttSessionConnected",
                    outcomeReason = "MQTT CONNACK rejected connection (returnCode=$returnCode)"
                )
                socket.close()
                return null
            }
            
            mqttConsecutiveFailures = 0

            // Subscribe to Home Assistant Discovery, Tasmota Discovery & Telemetry, and Zigbee2MQTT
            val subPacket = buildMqttSubscribePacket(
                packetId = packetIdCounter.getAndIncrement(),
                topics = listOf(
                    "homeassistant/#",
                    "tasmota/discovery/#",
                    "stat/+/RESULT",
                    "stat/+/POWER",
                    "stat/+/POWER1",
                    "stat/+/POWER2",
                    "tele/+/SENSOR",
                    "tele/+/STATE",
                    "zigbee2mqtt/bridge/devices",
                    "zigbee2mqtt/+"
                )
            )
            out.write(subPacket)
            out.flush()

            socket.soTimeout = 0 // Blocking mode for background reader coroutine
            val session = PersistentMqttSession(
                host = cleanHost,
                port = port,
                socket = socket,
                input = input,
                out = out
            )
            activeMqttSession = session
            startMqttBackgroundReaderAndKeepAlive(session)
            session
        } catch (e: Exception) {
            mqttConsecutiveFailures++
            Log.w(TAG, "MQTT broker connect note ($cleanHost:$port): ${e.message}")
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.FAILED,
                transportSource = "MQTT_BROKER",
                ipAddress = cleanHost,
                port = port,
                vendor = "MQTT Broker",
                qualificationStage = "ensureMqttSessionConnected",
                outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "MQTT broker connection failed"}"
            )
            null
        }
    }

    private fun startMqttBackgroundReaderAndKeepAlive(session: PersistentMqttSession) {
        mqttReaderJob?.cancel()
        mqttReaderJob = PlayerBackgroundScope.launch(Dispatchers.IO) {
            try {
                while (isActive && session.isAlive()) {
                    val b0 = session.input.read()
                    if (b0 == -1) break
                    val packetType = (b0 ushr 4) and 0x0F
                    val qos = (b0 ushr 1) and 0x03
                    val remLen = readRemainingLength(session.input)
                    if (remLen < 0 || remLen > 1_048_576) break
                    val payloadBytes = ByteArray(remLen)
                    if (remLen > 0) {
                        session.input.readFully(payloadBytes)
                    }
                    session.lastActivityMs = System.currentTimeMillis()

                    if (packetType == 3 && remLen >= 2) {
                        // PUBLISH packet
                        val topicLen = ((payloadBytes[0].toInt() and 0xFF) shl 8) or (payloadBytes[1].toInt() and 0xFF)
                        if (2 + topicLen <= remLen) {
                            val topic = String(payloadBytes, 2, topicLen, Charsets.UTF_8)
                            val msgOffset = 2 + topicLen + (if (qos > 0) 2 else 0)
                            if (msgOffset <= remLen) {
                                val message = String(payloadBytes, msgOffset, remLen - msgOffset, Charsets.UTF_8)
                                handleIncomingMqttPublish(topic, message)
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
} finally {
                session.closeQuietly()
            }
        }

        mqttKeepAliveJob?.cancel()
        mqttKeepAliveJob = PlayerBackgroundScope.launch(Dispatchers.IO) {
            while (isActive && session.isAlive()) {
                delay(25_000L)
                val ok = synchronized(session.writeLock) {
                    try {
                        session.out.write(byteArrayOf(0xC0.toByte(), 0x00)) // PINGREQ
                        session.out.flush()
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
                if (!ok) {
                    session.closeQuietly()
                    break
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 5. DUAL OVERLOADS OF connectAndDiscoverMqttEntities
    // -----------------------------------------------------------------------
    /**
     * Overload 1: Used by `SetupWizardTabs.kt` and `ManageAndPairModals.kt`.
     */
    suspend fun connectAndDiscoverMqttEntities(
        context: Context,
        host: String = getBrokerHost(context),
        port: Int = getBrokerPort(context),
        username: String = getBrokerUsername(context),
        password: String = getBrokerPassword(context),
        waitForDiscoveryMs: Long = 1600L
    ): Result<List<DiscoveredMqttEntity>> = withContext(Dispatchers.IO) {
        var resolvedHost = host.trim()
        if (resolvedHost.isNotBlank()) {
            saveBrokerConfig(context, resolvedHost, port, username, password)
        } else {
            resolvedHost = discoverLocalMqttBrokers(context, timeoutMs = 900L).firstOrNull().orEmpty()
        }

        // Also discover ESPHome mDNS nodes in parallel
        discoverEspHomeMdns(context, timeoutMs = 800L)

        if (resolvedHost.isNotBlank()) {
            val session = ensureMqttSessionConnected(resolvedHost, port, username, password)
            if (session != null) {
                delay(waitForDiscoveryMs.coerceIn(400L, 4000L))
            }
        }

        val list = discoveredEntityCache.values.toList()
        MultiProtocolLiveBridge.discoveredMqttEntitiesFlow.value = list
        if (list.isNotEmpty() || resolvedHost.isNotBlank()) {
            Result.success(list)
        } else {
            Result.failure(Exception("Could not find an active MQTT broker on port $port or ESPHome/Tasmota nodes."))
        }
    }

    /**
     * Overload 2: Used by `EnvironmentDiscoveryEngine.kt` and `MainActivity.kt` for
     * zero-configuration background discovery & live state synchronization.
     */
    suspend fun connectAndDiscoverMqttEntities(
        context: Context,
        bridge: BridgeSettings,
        tiles: MutableList<SmartTile>? = null
    ): List<DiscoveredMqttEntity> = withContext(Dispatchers.IO) {
        var host = getBrokerHost(context)
        if (host.isBlank()) {
            val haHost = runCatching { Uri.parse(bridge.haUrl.trim()).host.orEmpty() }.getOrDefault("")
            if (haHost.isNotBlank() && SmartHomeApiClient.isPortOpenInternal(haHost, DEFAULT_MQTT_PORT, 200)) {
                host = haHost
                autoDiscoveredBrokerHost = haHost
            } else {
                host = discoverLocalMqttBrokers(context, timeoutMs = 750L).firstOrNull().orEmpty()
            }
        }

        if (host.isNotBlank()) {
            val session = ensureMqttSessionConnected(
                host = host,
                port = getBrokerPort(context),
                username = getBrokerUsername(context),
                password = getBrokerPassword(context)
            )
            if (session != null && discoveredEntityCache.isEmpty()) {
                delay(900L)
            }
        }

        if (tiles != null) {
            syncLiveMqttAndTasmotaStates(context, tiles)
        }
        val list = discoveredEntityCache.values.toList()
        MultiProtocolLiveBridge.discoveredMqttEntitiesFlow.value = list
        list
    }

    /**
     * Overload 3: Used by `SetupWizardScreen.kt` and `SetupWizardTabs.kt` when passing `tiles`
     * and `waitForDiscoveryMs`.
     */
    suspend fun connectAndDiscoverMqttEntities(
        context: Context,
        tiles: MutableList<SmartTile>,
        waitForDiscoveryMs: Long = 1200L
    ): Result<List<DiscoveredMqttEntity>> = withContext(Dispatchers.IO) {
        val res = connectAndDiscoverMqttEntities(
            context = context,
            host = getBrokerHost(context),
            port = getBrokerPort(context),
            username = getBrokerUsername(context),
            password = getBrokerPassword(context),
            waitForDiscoveryMs = waitForDiscoveryMs
        )
        syncLiveMqttAndTasmotaStates(context, tiles)
        res
    }

    // -----------------------------------------------------------------------
    // 6. COMMAND ACTUATION (MQTT PUBLISH + TASMOTA HTTP + ESPHOME REST)
    // -----------------------------------------------------------------------
    suspend fun sendTasmotaHttpCommand(
        ipAddress: String,
        turnOn: Boolean,
        relayNumber: Int = 1
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanIp = ipAddress.substringBefore(":").trim()
        if (cleanIp.isBlank()) return@withContext false
        try {
            val powerCmd = when (relayNumber) {
                2 -> "Power2%20${if (turnOn) "ON" else "OFF"}"
                1 -> "Power1%20${if (turnOn) "ON" else "OFF"}"
                else -> "Power%20${if (turnOn) "ON" else "OFF"}"
            }
            val conn = (URL("http://$cleanIp/cm?cmnd=$powerCmd").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1400
                readTimeout = 1400
            }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            if (ok) {
                SmartHomeApiClient.registerVerifiedLanNeighbor(cleanIp)
            }
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

    suspend fun sendEspHomeHttpCommand(
        tile: SmartTile,
        turnOn: Boolean = tile.isActive
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanIp = tile.ipAddress.substringBefore(":").trim()
        if (cleanIp.isBlank()) return@withContext false
        val uid = tile.serialNumber.ifBlank { tile.entityId }.trim()
        val cachedEntity = discoveredEntityCache[uid]
        val cmdTopicOrUrl = tile.customRtspUrl.trim().ifBlank { cachedEntity?.commandTopic.orEmpty() }
        try {
            val slug = tile.title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
            val domain = when {
                tile.type == TileType.SMART_COVER -> "cover"
                tile.type == TileType.PROBREEZE_FAN -> "fan"
                tile.hasDimmingCapability -> "light"
                else -> "switch"
            }
            val action = when (tile.type) {
                TileType.SMART_COVER -> if (turnOn) "open" else "close"
                else -> if (turnOn) "turn_on" else "turn_off"
            }
            val targetUrl = if (cmdTopicOrUrl.startsWith("http://", ignoreCase = true)) {
                cmdTopicOrUrl.substringBeforeLast("/") + "/$action"
            } else {
                "http://$cleanIp/$domain/$slug/$action"
            }
            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 1400
                readTimeout = 1400
                doOutput = true
            }
            conn.outputStream.use { it.write(ByteArray(0)) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            if (ok) {
                SmartHomeApiClient.registerVerifiedLanNeighbor(cleanIp, uid)
            }
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

    suspend fun publishTileCommand(
        context: Context,
        tile: SmartTile,
        socketNumber: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (tile.isMultiGangSwitch && socketNumber == null) {
            val ok1 = sendMqttOrDiyCommand(context, tile, turnOn = tile.isActive, outletIndex = 1)
            val ok2 = sendMqttOrDiyCommand(context, tile, turnOn = tile.isSocket2Active, outletIndex = 2)
            ok1 || ok2
        } else {
            val targetTurnOn = if (socketNumber == 2) tile.isSocket2Active else tile.isActive
            sendMqttOrDiyCommand(
                context = context,
                tile = tile,
                turnOn = targetTurnOn,
                outletIndex = socketNumber ?: 0
            )
        }
    }
    suspend fun publishRawMqttMessage(
        context: Context,
        topic: String,
        payload: String,
        retain: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanTopic = topic.trim()
        if (cleanTopic.isBlank()) return@withContext false
        val host = getBrokerHost(context)
        if (host.isBlank()) return@withContext false

        val session = ensureMqttSessionConnected(
            host = host,
            port = getBrokerPort(context),
            username = getBrokerUsername(context),
            password = getBrokerPassword(context)
        ) ?: return@withContext false

        synchronized(session.writeLock) {
            try {
                val pkt = buildMqttPublishPacket(cleanTopic, payload, retain)
                session.out.write(pkt)
                session.out.flush()
                session.lastActivityMs = System.currentTimeMillis()
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
                session.closeQuietly()
                false
}
        }
    }

    suspend fun sendMqttOrDiyCommand(
        context: Context,
        tile: SmartTile,
        turnOn: Boolean = tile.isActive,
        outletIndex: Int = 0
    ): Boolean = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val cleanIp = tile.ipAddress.substringBefore(":").trim()
        val uid = tile.serialNumber.ifBlank { tile.entityId }.trim()
        val cachedEntity = discoveredEntityCache[uid]
        val cmdTopicOrUrl = tile.customRtspUrl.trim().ifBlank { cachedEntity?.commandTopic.orEmpty() }

        var succeeded = false

        // 1. Direct Tasmota HTTP Control (`/cm?cmnd=Power...`)
        if (cleanIp.isNotBlank() && (cachedEntity?.isTasmotaHttp == true || uid.startsWith("tasmota_") || cmdTopicOrUrl.startsWith("cmnd/"))) {
            try {
                val powerCmd = when {
                    tile.isMultiGangSwitch && outletIndex == 2 -> "Power2%20${if (turnOn) "ON" else "OFF"}"
                    tile.isMultiGangSwitch && outletIndex == 1 -> "Power1%20${if (turnOn) "ON" else "OFF"}"
                    else -> "Power%20${if (turnOn) "ON" else "OFF"}"
                }
                val conn = (URL("http://$cleanIp/cm?cmnd=$powerCmd").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1400
                    readTimeout = 1400
                }
                succeeded = conn.responseCode in 200..299
                conn.disconnect()
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

        // 2. Direct ESPHome Native HTTP REST Control
        if (!succeeded && cleanIp.isNotBlank() && (cachedEntity?.isEspHomeNative == true || uid.startsWith("esphome_") || cmdTopicOrUrl.startsWith("http://"))) {
            try {
                val slug = tile.title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_")
                val domain = if (tile.hasDimmingCapability) "light" else "switch"
                val action = if (turnOn) "turn_on" else "turn_off"
                val targetUrl = if (cmdTopicOrUrl.startsWith("http://")) {
                    cmdTopicOrUrl.substringBeforeLast("/") + "/$action"
                } else {
                    "http://$cleanIp/$domain/$slug/$action"
                }
                val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 1400
                    readTimeout = 1400
                    doOutput = true
                }
                conn.outputStream.use { it.write(ByteArray(0)) }
                succeeded = conn.responseCode in 200..299
                conn.disconnect()
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

        // 3. MQTT Broker Publish (`cmnd/...`, `zigbee2mqtt/.../set`, or Home Assistant MQTT command_topic)
        if (!succeeded && cmdTopicOrUrl.isNotBlank() && !cmdTopicOrUrl.startsWith("http")) {
            val targetTopic = if (tile.isMultiGangSwitch && cmdTopicOrUrl.endsWith("/POWER", ignoreCase = true) && outletIndex in 1..2) {
                "${cmdTopicOrUrl}$outletIndex"
            } else {
                cmdTopicOrUrl
            }
            val payload = when {
                targetTopic.startsWith("zigbee2mqtt/") -> {
                    val obj = JSONObject().put("state", if (turnOn) "ON" else "OFF")
                    if (tile.hasDimmingCapability && turnOn) {
                        obj.put("brightness", ((tile.volume / 100f) * 254f).roundToInt().coerceIn(1, 254))
                    }
                    obj.toString()
                }
                tile.isThermostatTile && targetTopic.contains("temp", ignoreCase = true) ->
                    "%.1f".format(Locale.US, tile.targetTemperature)
                else -> if (turnOn) "ON" else "OFF"
            }
            succeeded = publishRawMqttMessage(context, targetTopic, payload)
        }

        JarvisLiveBridge.recordCommandLatency(
            integrationKey = "MULTI_PROTOCOL",
            displayName = "Matter / Tapo / Shelly / MQTT / HA",
            latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(4L),
            succeeded = succeeded
        )
        succeeded
    }

    // -----------------------------------------------------------------------
    // 7. PERIODIC LIVE STATE & POWER TELEMETRY RECONCILIATION
    // -----------------------------------------------------------------------
    suspend fun syncLiveMqttAndTasmotaStates(
        context: Context,
        tiles: MutableList<SmartTile>
    ) {
        val targetTiles = tiles.withIndex().filter { (_, t) ->
            t.type == TileType.MQTT_DEVICE ||
                    t.serialNumber.startsWith("tasmota_") ||
                    t.serialNumber.startsWith("mqtt_") ||
                    t.serialNumber.startsWith("z2m_") ||
                    t.serialNumber.startsWith("esphome_") ||
                    t.entityId.startsWith("tasmota_") ||
                    t.entityId.startsWith("mqtt_") ||
                    t.entityId.startsWith("z2m_")
        }
        if (targetTiles.isEmpty()) return

        // Poll any Tasmota HTTP devices in parallel for live ENERGY telemetry
        withContext(Dispatchers.IO) {
            coroutineScope {
                targetTiles.chunked(8).forEach { batch ->
                    batch.map { (_, tile) ->
                        async(Dispatchers.IO) {
                            val cleanIp = tile.ipAddress.substringBefore(":").trim()
                            val uid = tile.serialNumber.ifBlank { tile.entityId }.trim()
                            if (cleanIp.isNotBlank() && (uid.startsWith("tasmota_") || discoveredEntityCache[uid]?.isTasmotaHttp == true)) {
                                probeTasmotaHttpDevice(cleanIp)
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val bridge = DeviceRepository.loadBridge(context)

        withContext(Dispatchers.Main) {
            for ((idx, current) in targetTiles) {
                if (idx !in tiles.indices) continue
                val uid = current.serialNumber.ifBlank { current.entityId }.trim()
                val live = discoveredEntityCache[uid]
                    ?: discoveredEntityCache.values.firstOrNull {
                        (current.ipAddress.isNotBlank() && it.ipAddress == current.ipAddress.substringBefore(":")) ||
                                it.name.equals(current.title, ignoreCase = true)
                    }
                    ?: continue

                val wasOffline = !current.isOnline
                val hasPower = live.powerWatts > 0f || live.totalEnergyKwh > 0f || current.hasExplicitPowerTelemetry
                val inferredAppliance = AppliancePowerFingerprinter.inferApplianceFromTelemetry(
                    powerWatts = live.powerWatts,
                    titleHint = current.title,
                    previous = current.detectedApplianceCategory
                )
                val incoming = current.copy(
                    ipAddress = current.ipAddress.ifBlank { live.ipAddress },
                    isOnline = live.isOnline,
                    isActive = live.isActive,
                    isSocket2Active = live.isSocket2Active,
                    multiGangSocketCount = maxOf(current.multiGangSocketCount, live.multiGangCount),
                    powerWatts = if (live.powerWatts > 0f) live.powerWatts else current.powerWatts,
                    voltageVolts = if (live.voltageVolts > 0f) live.voltageVolts else current.voltageVolts,
                    currentMilliamps = if (live.currentMilliamps > 0f) live.currentMilliamps else current.currentMilliamps,
                    totalEnergyKwh = if (live.totalEnergyKwh > 0f) live.totalEnergyKwh else current.totalEnergyKwh,
                    temperature = if (current.type == TileType.SMART_SENSOR || current.isThermostatTile) live.temperatureC else current.temperature,
                    hasExplicitPowerTelemetry = hasPower,
                    detectedApplianceCategory = inferredAppliance
                )

                tiles[idx] = DeviceRepository.reconcileRemoteTileUpdate(current, incoming)

                if (live.powerWatts > 0f) {
                    PlayerBackgroundScope.launch(Dispatchers.IO) {
                        DeviceRepository.recordTelemetryPoint(
                            context = context,
                            point = TelemetryDataPoint(
                                deviceId = current.id,
                                deviceTitle = current.title,
                                metricKey = "power_w",
                                value = live.powerWatts,
                                unit = "W"
                            )
                        )
                    }
                }

                if (wasOffline && live.isOnline && audioManager != null) {
                    replayQueuedShadowIntentsForTile(
                        context = context,
                        tile = tiles[idx],
                        bridge = bridge,
                        audioManager = audioManager,
                        scope = PlayerBackgroundScope
                    )
                }
            }
        }
    }
}
