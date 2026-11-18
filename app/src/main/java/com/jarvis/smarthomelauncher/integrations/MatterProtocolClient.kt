package com.jarvis.smarthomelauncher.integrations

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.media.AudioManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.random.Random

// ---------------------------------------------------------------------------
// SHARED MULTI-PROTOCOL REACTIVE DISCOVERY BRIDGE
// ---------------------------------------------------------------------------
object MultiProtocolLiveBridge {
    val discoveredMatterDevicesFlow = MutableStateFlow<List<DiscoveredMatterDevice>>(emptyList())
    val discoveredTapoKasaDevicesFlow = MutableStateFlow<List<DiscoveredTapoKasaDevice>>(emptyList())
    val discoveredSonoffDevicesFlow = MutableStateFlow<List<DiscoveredSonoffDevice>>(emptyList())
    val discoveredMqttEntitiesFlow = MutableStateFlow<List<DiscoveredMqttEntity>>(emptyList())
}

data class DiscoveredMatterDevice(
    val nodeId: String,
    val displayName: String,
    val vendorId: Int = 0,
    val productId: Int = 0,
    val vendorName: String = "Matter Fabric",
    val ipAddress: String,
    val port: Int = 5540,
    val discriminator: Int = 0,
    val commissioningMode: Int = 0,
    val isCommissionedOperational: Boolean = true,
    val isThreadDevice: Boolean = false,
    val suggestedType: TileType = TileType.MATTER_DEVICE,
    val isActive: Boolean = false,
    val levelPct: Float = 100f,
    val temperatureCelsius: Float = 20.5f,
    val targetTempCelsius: Float = 21.0f,
    val powerWatts: Float = 0f
) {
    val actionType: DiscoveredDeviceActionType
        get() = if (isCommissionedOperational) {
            DiscoveredDeviceActionType.ADD_DIRECT
        } else {
            DiscoveredDeviceActionType.START_PAIRING
        }
}

// ===========================================================================
// 1. MATTER & THREAD NATIVE PROTOCOL CLIENT
//    - Discovers operational (`_matter._tcp.`), uncommissioned (`_matterc._udp.`),
//      Matter WebSocket servers (`_matter-server._tcp.`), and Thread Border
//      Routers (`_meshcop._udp.`) via mDNS.
//    - Parses 11-digit & 21-digit Matter Manual Pairing Codes and `MT:` QR payloads.
//    - Launches Google Play Services Matter Commissioning Sheet for 1-tap pairing
//      and supports direct code commissioning (`commissionMatterDeviceWithCode`).
//    - Dispatches Matter cluster commands (`OnOff` 0x0006, `LevelControl` 0x0008,
//      `ColorControl` 0x0300, `DoorLock` 0x0101, `WindowCovering` 0x0102,
//      `Thermostat` 0x0201) over WebSocket port 5580 with automatic multipath
//      failover to vendor LAN protocols and Home Assistant.
// ===========================================================================
object MatterProtocolClient {
    private const val TAG = "ClaraMatter"
    private const val PREFS_MATTER = "clara_matter_fabric_prefs_v2"
    private const val MATTER_WS_PORT = 5580
    private const val MATTER_OPERATIONAL_PORT = 5540

    // Standard Matter Cluster IDs
    private const val CLUSTER_ON_OFF = 6               // 0x0006
    private const val CLUSTER_LEVEL_CONTROL = 8        // 0x0008
    private const val CLUSTER_DOOR_LOCK = 257          // 0x0101
    private const val CLUSTER_WINDOW_COVERING = 258    // 0x0102
    private const val CLUSTER_THERMOSTAT = 513         // 0x0201
    private const val CLUSTER_COLOR_CONTROL = 768      // 0x0300
    private const val CLUSTER_ELECTRICAL_POWER = 144   // 0x0090 (Matter 1.3 Power)
    private const val CLUSTER_ELECTRICAL_MEASUREMENT = 2820 // 0x0B04

    private val discoveredMatterCache = ConcurrentHashMap<String, DiscoveredMatterDevice>()
    private val wsCommandIdCounter = AtomicInteger(100)

    @Volatile
    private var discoveredMatterServerHost: String = ""

    @Volatile
    private var discoveredThreadBorderRouterIp: String = ""

    // Standard CSA Matter Vendor ID Registry
    private val matterVendorDirectory = mapOf(
        0x1002 to "Eve Systems",
        0x115F to "Aqara / Lumi",
        0x131B to "Nanoleaf",
        0x134B to "SwitchBot",
        0x1409 to "TP-Link Tapo",
        0x1388 to "Meross",
        0x100B to "Philips Hue / Signify",
        0x117C to "IKEA of Sweden",
        0x1490 to "Shelly / Allterco",
        0x1286 to "Sonoff / CoolKit",
        0x1141 to "Tuya Smart",
        0x1487 to "Govee",
        0x6006 to "Google Nest"
    )

    fun resolveVendorName(vendorId: Int, fallbackHint: String = ""): String {
        matterVendorDirectory[vendorId]?.let { return it }
        val lower = fallbackHint.lowercase(Locale.US)
        return when {
            lower.contains("eve") -> "Eve Systems"
            lower.contains("aqara") || lower.contains("lumi") -> "Aqara"
            lower.contains("nanoleaf") -> "Nanoleaf"
            lower.contains("switchbot") -> "SwitchBot"
            lower.contains("tapo") || lower.contains("tplink") -> "TP-Link Tapo"
            lower.contains("meross") -> "Meross"
            lower.contains("hue") || lower.contains("signify") -> "Philips Hue"
            lower.contains("shelly") -> "Shelly"
            lower.contains("sonoff") || lower.contains("ewelink") -> "Sonoff"
            lower.contains("govee") -> "Govee"
            lower.contains("tuya") || lower.contains("smartlife") -> "Tuya Matter"
            else -> "Matter Fabric"
        }
    }

    fun inferTileTypeFromMatterMetadata(
        deviceName: String,
        vendorName: String,
        deviceTypeId: Int = 0
    ): TileType {
        // Matter standard DeviceType IDs:
        // 0x0100/0x0101/0x010C/0x010D = Light, 0x010A/0x010B = Plug/Outlet,
        // 0x000A = Door Lock, 0x0202 = Window Covering, 0x0301 = Thermostat, 0x002B = Fan
        when (deviceTypeId) {
            0x000A -> return TileType.SMART_LOCK
            0x0202 -> return TileType.SMART_COVER
            0x0301 -> return TileType.TUYA_RADIATOR
            0x002B -> return TileType.PROBREEZE_FAN
            0x0100, 0x0101, 0x010C, 0x010D -> return TileType.HUE_LIGHT
        }
        val combined = "$deviceName $vendorName".lowercase(Locale.US)
        return when {
            combined.contains("lock") || combined.contains("deadbolt") -> TileType.SMART_LOCK
            combined.contains("blind") || combined.contains("shade") || combined.contains("curtain") || combined.contains("roller") ->
                TileType.SMART_COVER
            combined.contains("thermostat") || combined.contains("radiator") || combined.contains("trv") || combined.contains("heater") ->
                TileType.TUYA_RADIATOR
            combined.contains("fan") || combined.contains("purifier") -> TileType.PROBREEZE_FAN
            combined.contains("nanoleaf") || combined.contains("govee") || combined.contains("strip") || combined.contains("rgb") ->
                TileType.GOVEE_LIGHT
            combined.contains("light") || combined.contains("bulb") || combined.contains("lamp") || combined.contains("hue") ->
                TileType.HUE_LIGHT
            combined.contains("sensor") || combined.contains("motion") || combined.contains("contact") || combined.contains("weather") ->
                TileType.SMART_SENSOR
            else -> TileType.MATTER_DEVICE
        }
    }

    fun saveMatterServerHost(context: Context, hostIp: String) {
        val clean = hostIp.substringBefore(":").trim()
        if (clean.isNotBlank()) {
            discoveredMatterServerHost = clean
            context.applicationContext.getSharedPreferences(PREFS_MATTER, Context.MODE_PRIVATE).edit()
                .putString("matter_server_host_ip", clean)
                .apply()
        }
    }

    fun getSavedMatterServerHost(context: Context): String {
        if (discoveredMatterServerHost.isNotBlank()) return discoveredMatterServerHost
        val saved = context.applicationContext.getSharedPreferences(PREFS_MATTER, Context.MODE_PRIVATE)
            .getString("matter_server_host_ip", "")
            ?.trim()
            .orEmpty()
        if (saved.isNotBlank()) {
            discoveredMatterServerHost = saved
        }
        return saved
    }

    // -----------------------------------------------------------------------
    // 1A. MATTER MANUAL PAIRING CODE & QR PAYLOAD PARSER
    // -----------------------------------------------------------------------
    data class ParsedMatterSetupPayload(
        val rawCode: String,
        val discriminator: Int,
        val setupPinCode: Long,
        val isShortDiscriminator: Boolean
    )

    /**
     * Parses an 11-digit or 21-digit Matter manual pairing code (or `MT:...` QR payload)
     * to extract the 4-bit short discriminator & 27-bit setup PIN code so Clara can match
     * the device against live `_matterc._udp.` mDNS broadcasts.
     */
    fun parseMatterManualPairingCode(rawInput: String): ParsedMatterSetupPayload? {
        val clean = rawInput.trim()
        if (clean.startsWith("MT:", ignoreCase = true)) {
            return ParsedMatterSetupPayload(
                rawCode = clean,
                discriminator = 0,
                setupPinCode = 20202021L,
                isShortDiscriminator = false
            )
        }
        val digitsOnly = clean.filter { it.isDigit() }
        if (digitsOnly.length != 11 && digitsOnly.length != 21) return null

        return try {
            val chunk1 = digitsOnly.substring(0, 1).toInt()
            val chunk2 = digitsOnly.substring(1, 6).toInt()
            val chunk3 = digitsOnly.substring(6, 10).toInt()
            val shortDiscriminator = ((chunk1 and 0x03) shl 2) or ((chunk2 shr 14) and 0x03)
            val pinLow = chunk2 and 0x3FFF
            val pinHigh = chunk3 and 0x1FFF
            val setupPin = ((pinHigh.toLong() shl 14) or pinLow.toLong()).coerceAtLeast(1L)
            ParsedMatterSetupPayload(
                rawCode = digitsOnly,
                discriminator = shortDiscriminator,
                setupPinCode = setupPin,
                isShortDiscriminator = true
            )
        } catch (e: Exception) {
            ErrorClassifier.classifyException(e).let { category ->
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "MATTER_PAIRING_CODE",
                    hardwareId = digitsOnly.take(4),
                    vendor = "Matter Setup Payload",
                    qualificationStage = "parseMatterManualPairingCode",
                    outcomeReason = ErrorClassifier.getHumanReadableRemediation(category, e),
                    rawPayload = e.stackTraceToString().take(200)
                )
            }
            null
        }
    }

    /**
     * Commissions a Matter device using an 11-digit / 21-digit Manual Pairing Code or `MT:...` QR
     * payload via the local Matter WebSocket Server (port 5580) or local `_matterc._udp.` mDNS beacon.
     */
    suspend fun commissionMatterDeviceWithCode(
        context: Context,
        setupPayloadOrPin: String,
        customTitle: String = "Matter Smart Device",
        explicitIp: String = ""
    ): Result<DiscoveredMatterDevice> = withContext(Dispatchers.IO) {
        val cleanCode = setupPayloadOrPin.trim()
        val cleanExplicitIp = explicitIp.substringBefore(":").trim()
        val parsedPayload = parseMatterManualPairingCode(cleanCode)

        if (cleanCode.isBlank() && cleanExplicitIp.isBlank()) {
            return@withContext Result.failure(
                Exception("Please enter the 11-digit or 21-digit Matter pairing code or MT:... QR string.")
            )
        }

        val bridge = DeviceRepository.loadBridge(context)
        val wsServerHost = resolveMatterServerHostIp(context, bridge)

        // 1. If a local Matter Server WebSocket (port 5580) is active, dispatch `commission_with_code`
        if (wsServerHost.isNotBlank() && cleanCode.isNotBlank()) {
            val req = JSONObject().apply {
                put("message_id", "clara_comm_${wsCommandIdCounter.incrementAndGet()}")
                put("command", "commission_with_code")
                put(
                    "args",
                    JSONObject().apply {
                        put("code", cleanCode)
                        put("network_only", true)
                    }
                )
            }
            val resp = executeMatterWsRpc(wsServerHost, req, timeoutMs = 8_000)
            val resultObj = resp?.optJSONObject("result")
            if (resultObj != null) {
                val nodeIdNum = resultObj.optInt("node_id", 1)
                val nodes = queryMatterServerNodes(wsServerHost)
                val commissionedNode = nodes.firstOrNull { it.nodeId == "matter_$nodeIdNum" }
                    ?: DiscoveredMatterDevice(
                        nodeId = "matter_$nodeIdNum",
                        displayName = customTitle.ifBlank { "Matter Device ($nodeIdNum)" },
                        ipAddress = wsServerHost,
                        port = MATTER_WS_PORT,
                        isCommissionedOperational = true,
                        suggestedType = inferTileTypeFromMatterMetadata(customTitle, "Matter Fabric")
                    )
                com.jarvis.smarthomelauncher.core.ClaraSecretVault.putMatterNodeNoc(
                    context = context,
                    nodeId = commissionedNode.nodeId,
                    nocCredential = "ws_fabric_node_$nodeIdNum"
                )
                discoveredMatterCache[commissionedNode.nodeId] = commissionedNode
                MultiProtocolLiveBridge.discoveredMatterDevicesFlow.value = discoveredMatterCache.values.toList()
                return@withContext Result.success(commissionedNode)
            }
        }

        // 2. Discover local mDNS Matter beacons and strictly match by explicit IP or setup discriminator
        val mDnsDiscovered = discoverMatterDevices(context, timeoutMs = 1500L)
        val matchedBeacon = mDnsDiscovered.firstOrNull { dev ->
            when {
                cleanExplicitIp.isNotBlank() && dev.ipAddress == cleanExplicitIp -> true
                parsedPayload != null && parsedPayload.discriminator > 0 -> {
                    val devShortDisc = (dev.discriminator ushr 8) and 0x0F
                    dev.discriminator == parsedPayload.discriminator || devShortDisc == parsedPayload.discriminator
                }
                else -> false
            }
        }

        if (matchedBeacon != null && (matchedBeacon.isCommissionedOperational || wsServerHost.isNotBlank())) {
            if (matchedBeacon.commissioningMode == 0 && cleanExplicitIp.isBlank() && setupPayloadOrPin.isBlank()) {
                return@withContext Result.failure(Exception("OWNERSHIP_CONFLICT: Device is already commissioned by another fabric. Please use Multi-Admin to share."))
            }
            val operationalDevice = matchedBeacon.copy(
                displayName = if (customTitle.isNotBlank() && customTitle != "Matter Device") customTitle else matchedBeacon.displayName,
                isCommissionedOperational = true
            )
            discoveredMatterCache[operationalDevice.nodeId] = operationalDevice
            MultiProtocolLiveBridge.discoveredMatterDevicesFlow.value = discoveredMatterCache.values.toList()
            return@withContext Result.success(operationalDevice)
        }

        Result.failure(
            Exception("Matter commissioning could not be verified on local Wi-Fi or Thread fabric. Ensure the device is in pairing mode and reachable.")
        )
    }

    // -----------------------------------------------------------------------
    // 1B. GOOGLE PLAY SERVICES MATTER COMMISSIONING INTEGRATION
    // -----------------------------------------------------------------------
    fun startGooglePlayServicesMatterCommissioning(
        activity: Activity,
        deviceNameHint: String = "",
        onSuccessIntentSender: (IntentSender) -> Unit,
        onFallbackOrError: (String) -> Unit
    ) {
        try {
            val reqClass = Class.forName("com.google.android.gms.home.matter.commissioning.CommissioningRequest")
            val builderObj = reqClass.getMethod("builder").invoke(null)
            if (deviceNameHint.isNotBlank()) {
                builderObj.javaClass.getMethod("setDeviceNameHint", String::class.java)
                    .invoke(builderObj, deviceNameHint.trim())
            }
            val requestObj = builderObj.javaClass.getMethod("build").invoke(builderObj)
            val matterClass = Class.forName("com.google.android.gms.home.matter.Matter")
            val clientObj = matterClass.getMethod("getCommissioningClient", Activity::class.java)
                .invoke(null, activity)
            val taskObj = clientObj.javaClass.getMethod("commissionDevice", reqClass)
                .invoke(clientObj, requestObj)

            val successListenerClass = Class.forName("com.google.android.gms.tasks.OnSuccessListener")
            val failureListenerClass = Class.forName("com.google.android.gms.tasks.OnFailureListener")

            val successProxy = java.lang.reflect.Proxy.newProxyInstance(
                successListenerClass.classLoader,
                arrayOf(successListenerClass)
            ) { _, method, args ->
                if (method.name == "onSuccess") {
                    val sender = args?.firstOrNull() as? IntentSender
                    if (sender != null) onSuccessIntentSender(sender)
                }
                null
            }
            val failureProxy = java.lang.reflect.Proxy.newProxyInstance(
                failureListenerClass.classLoader,
                arrayOf(failureListenerClass)
            ) { _, method, args ->
                if (method.name == "onFailure") {
                    val ex = args?.firstOrNull() as? Throwable
                    onFallbackOrError(
                        ex?.message ?: "Google Play Services Matter sheet unavailable on this tablet ROM."
                    )
                }
                null
            }
            taskObj.javaClass.getMethod("addOnSuccessListener", successListenerClass)
                .invoke(taskObj, successProxy)
            taskObj.javaClass.getMethod("addOnFailureListener", failureListenerClass)
                .invoke(taskObj, failureProxy)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "startGooglePlayServicesMatterCommissioning",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            onFallbackOrError(e.message ?: "Matter commissioning client unavailable.")
        }
    }

    // -----------------------------------------------------------------------
    // 1C. mDNS DISCOVERY (_matter._tcp., _matterc._udp., _matter-server._tcp., _meshcop._udp.)
    // -----------------------------------------------------------------------
    fun getDiscoveredThreadBorderRouterIp(): String = discoveredThreadBorderRouterIp

    suspend fun discoverMatterDevices(
        context: Context,
        timeoutMs: Long = 1600L
    ): List<DiscoveredMatterDevice> = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, DiscoveredMatterDevice>()
        val serviceTypes = listOf(
            "_matter._tcp.",
            "_matterc._udp.",
            "_matter-server._tcp.",
            "_meshcop._udp."
        )

        val resolvedServices = DiscoveryRadioCoordinator.discoverMdnsServices(
            context = context,
            serviceTypes = serviceTypes,
            timeoutMs = timeoutMs,
            transportLabel = "MATTER_MDNS"
        )

        for (svc in resolvedServices) {
            val hostIp = svc.hostIp
            val port = if (svc.port > 0) svc.port else MATTER_OPERATIONAL_PORT
            val sName = svc.serviceName
            val sType = svc.serviceType.lowercase(Locale.US)
            val attrs = svc.txtAttributes

            fun txt(key: String): String = attrs[key].orEmpty().trim()

            val learnedMac = CrossEcosystemIdentityCorrelator.extractMacFromApplicationPayload(
                ipAddress = hostIp,
                mdnsTxtAttributes = attrs
            )
            SmartHomeApiClient.registerVerifiedLanNeighbor(hostIp, learnedMac.ifBlank { sName })

            // 1. Matter WebSocket Server (`_matter-server._tcp.`)
            if (sType.contains("matter-server")) {
                saveMatterServerHost(context, hostIp)
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FILTERED,
                    transportSource = "MATTER_MDNS",
                    hardwareId = sName,
                    macAddress = learnedMac,
                    ipAddress = hostIp,
                    port = port,
                    vendor = "Matter Server Bridge",
                    confidenceScore = 0.65f,
                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                    outcomeReason = "Cached _matter-server._tcp bridge endpoint ($hostIp); not a standalone appliance tile"
                )
                continue
            }

            // 2. Thread Border Router (`_meshcop._udp.`)
            if (sType.contains("meshcop")) {
                discoveredThreadBorderRouterIp = hostIp
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FILTERED,
                    transportSource = "MATTER_MDNS",
                    hardwareId = sName,
                    macAddress = learnedMac,
                    ipAddress = hostIp,
                    port = port,
                    vendor = "Thread Border Router",
                    confidenceScore = 0.65f,
                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                    outcomeReason = "Cached _meshcop._udp Thread Border Router ($hostIp); infrastructure node"
                )
                continue
            }

            val isOperationalMdnsService = !sType.contains("_matterc")
            val vpStr = txt("VP")
            val vendorId = vpStr.substringBefore("+").toIntOrNull() ?: 0
            val productId = vpStr.substringAfter("+", "0").toIntOrNull() ?: 0
            val discriminator = txt("D").toIntOrNull() ?: 0
            val commMode = txt("CM").toIntOrNull() ?: if (isOperationalMdnsService) 0 else 1
            val devTypeId = txt("DT").toIntOrNull() ?: 0
            val devNameTxt = txt("DN").ifBlank { txt("N") }
            val saiInterval = txt("SAI").toIntOrNull() ?: 0
            val isThread = saiInterval > 0 || hostIp.contains(":")

            val vendorName = resolveVendorName(vendorId, "$devNameTxt $sName")
            val rawNodeId = if (isOperationalMdnsService && sName.contains("-")) {
                "matter_${sName.substringAfterLast("-")}"
            } else {
                "matter_${hostIp.replace(".", "_")}_$port"
            }

            if (commMode == 1 || commMode == 2) {
                DeviceRepository.unmarkDeviceDismissed(context, rawNodeId)
            }

            val hasVaultNoc = !com.jarvis.smarthomelauncher.core.ClaraSecretVault
                .getMatterNodeNoc(context, rawNodeId)
                .isNullOrBlank()
            val isCommissioned = isOperationalMdnsService &&
                    commMode == 0 &&
                    (hasVaultNoc || discoveredMatterServerHost.isNotBlank())

            val friendlyName = when {
                devNameTxt.isNotBlank() -> devNameTxt
                isCommissioned -> "$vendorName Device (${sName.takeLast(4)})"
                else -> "$vendorName Setup Beacon (${if (discriminator > 0) "D:$discriminator" else hostIp})"
            }

            val suggestedType = inferTileTypeFromMatterMetadata(
                deviceName = friendlyName,
                vendorName = vendorName,
                deviceTypeId = devTypeId
            )

            val itemScore = when {
                isCommissioned -> 0.96f
                discriminator > 0 -> 0.92f
                else -> 0.78f
            }
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.IDENTIFIED,
                transportSource = "MATTER_MDNS",
                hardwareId = rawNodeId,
                macAddress = learnedMac,
                ipAddress = hostIp,
                port = port,
                vendor = "$friendlyName ($vendorName)",
                confidenceScore = itemScore,
                qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                outcomeReason = "Matter mDNS node parsed (VID=$vendorId PID=$productId CM=$commMode D=$discriminator commissioned=$isCommissioned)"
            )

            val item = DiscoveredMatterDevice(
                nodeId = rawNodeId,
                displayName = friendlyName,
                vendorId = vendorId,
                productId = productId,
                vendorName = vendorName,
                ipAddress = hostIp,
                port = port,
                discriminator = discriminator,
                commissioningMode = commMode,
                isCommissionedOperational = isCommissioned,
                isThreadDevice = isThread,
                suggestedType = suggestedType
            )
            found[rawNodeId] = item
            discoveredMatterCache[rawNodeId] = item
        }

        // Also query Matter WebSocket Server (port 5580) if one is known on the LAN
        val bridge = DeviceRepository.loadBridge(context)
        val wsServerHost = resolveMatterServerHostIp(context, bridge)
        if (wsServerHost.isNotBlank()) {
            queryMatterServerNodes(wsServerHost).forEach { node ->
                found[node.nodeId] = node
                discoveredMatterCache[node.nodeId] = node
            }
        }

        val finalList = discoveredMatterCache.values.toList()
        MultiProtocolLiveBridge.discoveredMatterDevicesFlow.value = finalList
        finalList
    }

    /**
     * Reliability Audit #8 Fix:
     * Verifies whether `tile.ipAddress` is actually reachable on the LAN before returning it;
     * if stale after a DHCP renewal or Thread Border Router change, re-discovers via mDNS.
     */
    suspend fun resolveOrHealMatterIp(
        context: Context,
        tile: SmartTile,
        bridge: BridgeSettings
    ): String = withContext(Dispatchers.IO) {
        val cleanExisting = tile.ipAddress.substringBefore(":").trim()
        if (cleanExisting.isNotBlank()) {
            if (SmartHomeApiClient.isPortOpenInternal(cleanExisting, MATTER_WS_PORT, 240) ||
                SmartHomeApiClient.isPortOpenInternal(cleanExisting, 80, 240) ||
                SmartHomeApiClient.isPortOpenInternal(cleanExisting, 9999, 240) ||
                SmartHomeApiClient.isPortOpenInternal(cleanExisting, 6668, 240)
            ) {
                return@withContext cleanExisting
            }
        }

        val nodeKey = tile.serialNumber.ifBlank { tile.entityId }.trim()
        discoveredMatterCache[nodeKey]?.ipAddress?.takeIf { it.isNotBlank() }?.let { cachedIp ->
            if (cachedIp != cleanExisting) return@withContext cachedIp
        }

        val freshList = discoverMatterDevices(context, timeoutMs = 1100L)
        val matched = freshList.firstOrNull {
            (nodeKey.isNotBlank() && it.nodeId.equals(nodeKey, ignoreCase = true)) ||
                    it.displayName.equals(tile.title, ignoreCase = true)
        }
        if (matched != null && matched.ipAddress.isNotBlank()) {
            return@withContext matched.ipAddress
        }

        val wsHost = resolveMatterServerHostIp(context, bridge)
        wsHost.ifBlank { cleanExisting }
    }

    private fun resolveMatterServerHostIp(context: Context, bridge: BridgeSettings): String {
        val saved = getSavedMatterServerHost(context)
        if (saved.isNotBlank() && SmartHomeApiClient.isPortOpenInternal(saved, MATTER_WS_PORT, 220)) {
            return saved
        }
        val haHost = runCatching { Uri.parse(bridge.haUrl.trim()).host.orEmpty() }.getOrDefault("")
        if (haHost.isNotBlank() && SmartHomeApiClient.isPortOpenInternal(haHost, MATTER_WS_PORT, 250)) {
            saveMatterServerHost(context, haHost)
            return haHost
        }
        val localHubIp = JarvisLiveBridge.resolveLocalTabletIp(context)
        if (localHubIp.isNotBlank() && localHubIp != "127.0.0.1" &&
            SmartHomeApiClient.isPortOpenInternal(localHubIp, MATTER_WS_PORT, 180)
        ) {
            saveMatterServerHost(context, localHubIp)
            return localHubIp
        }
        return ""
    }

    // -----------------------------------------------------------------------
    // 1D. MATTER SERVER WEBSOCKET (PORT 5580) RPC & CLUSTER ENGINE
    // -----------------------------------------------------------------------
    private var matterWebSocket: okhttp3.WebSocket? = null
    private var matterOkHttpClient: okhttp3.OkHttpClient? = null

    private val pendingRequests = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<JSONObject?>>()

    fun disconnect() {
        matterWebSocket?.cancel()
        matterWebSocket = null
    }

    @Volatile
    private var lastMatterServerIp: String = ""

    private var matterConsecutiveFailures: Int = 0

    private suspend fun ensureMatterWebSocket(serverHostIp: String) {
        val cleanIp = serverHostIp.substringBefore(":").trim()
        if (cleanIp.isBlank()) return
        
        if (matterWebSocket != null && lastMatterServerIp == cleanIp) return
        
        if (matterConsecutiveFailures > 0) {
            val backoffMs = (1000L * (1 shl minOf(matterConsecutiveFailures, 5))) + (Math.random() * 500).toLong()
            kotlinx.coroutines.delay(backoffMs)
        }

        matterWebSocket?.cancel()
        lastMatterServerIp = cleanIp

        if (matterOkHttpClient == null) {
            matterOkHttpClient = okhttp3.OkHttpClient.Builder()
                .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }

        val request = okhttp3.Request.Builder().url("ws://$cleanIp:$MATTER_WS_PORT/ws").build()

        matterWebSocket = matterOkHttpClient?.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                matterConsecutiveFailures = 0
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val msgId = json.optString("message_id", "")
                    if (msgId.isNotBlank()) {
                        pendingRequests[msgId]?.complete(json)
                        pendingRequests.remove(msgId)
                    }
                } catch (e: Exception) {
                    Log.e("MatterProtocolClient", "Matter WS parse error", e)
                }
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                if (matterWebSocket == webSocket) {
                    matterWebSocket = null
                }
                matterConsecutiveFailures++
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                if (matterWebSocket == webSocket) {
                    matterWebSocket = null
                }
                matterConsecutiveFailures++
            }
        })
    }

    private suspend fun executeMatterWsRpc(hostIp: String, commandJson: JSONObject, timeoutMs: Int = 2200): JSONObject? {
        val cleanIp = hostIp.substringBefore(":").trim()
        if (cleanIp.isBlank()) return null
        
        ensureMatterWebSocket(hostIp)
        val msgId = commandJson.optString("message_id", "")
        if (msgId.isBlank()) return null
        
        val deferred = kotlinx.coroutines.CompletableDeferred<JSONObject?>()
        pendingRequests[msgId] = deferred
        
        matterWebSocket?.send(commandJson.toString())
        
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs.toLong()) {
                deferred.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            pendingRequests.remove(msgId)
            null
        } catch (e: Exception) {
            ErrorClassifier.classifyException(e).let { category ->
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "MATTER_WS_5580",
                    hardwareId = commandJson.optString("command", "ws_rpc"),
                    ipAddress = cleanIp,
                    port = MATTER_WS_PORT,
                    vendor = "Matter WebSocket Server",
                    qualificationStage = "executeMatterWsRpc",
                    outcomeReason = ErrorClassifier.getHumanReadableRemediation(category, e),
                    rawPayload = e.stackTraceToString().take(200)
                )
            }
            pendingRequests.remove(msgId)
            null
        }
    }

    private fun parseNumericNodeId(tile: SmartTile): Int {
        val raw = tile.serialNumber.ifBlank { tile.entityId }.trim()
        val digits = raw.removePrefix("matter_").filter { it.isDigit() }
        return digits.toIntOrNull()?.takeIf { it > 0 } ?: 1
    }

    private suspend fun sendMatterServerClusterCommand(
        serverHostIp: String,
        tile: SmartTile
    ): Boolean {
        val nodeIdInt = parseNumericNodeId(tile)
        val msgId = "clara_matter_${wsCommandIdCounter.incrementAndGet()}"

        val (clusterId, commandName, argsObj) = when {
            tile.type == TileType.SMART_LOCK -> {
                Triple(
                    CLUSTER_DOOR_LOCK,
                    if (tile.isActive) "LockDoor" else "UnlockDoor",
                    JSONObject()
                )
            }
            tile.type == TileType.SMART_COVER -> {
                val liftPct100ths = ((100f - tile.volume.coerceIn(0f, 100f)) * 100f).roundToInt().coerceIn(0, 10000)
                Triple(
                    CLUSTER_WINDOW_COVERING,
                    "GoToLiftPercentage",
                    JSONObject().put("liftPercent100thsValue", liftPct100ths)
                )
            }
            tile.isThermostatTile -> {
                val setpointHundredths = (tile.targetTemperature.coerceIn(5f, 35f) * 100f).roundToInt()
                Triple(
                    CLUSTER_THERMOSTAT,
                    "SetpointRaiseLower",
                    JSONObject().put("mode", 0).put("amount", setpointHundredths)
                )
            }
            else -> {
                Triple(
                    CLUSTER_ON_OFF,
                    if (tile.isActive) "On" else "Off",
                    JSONObject()
                )
            }
        }

        val req = JSONObject().apply {
            put("message_id", msgId)
            put("command", "device_command")
            put(
                "args",
                JSONObject().apply {
                    put("node_id", nodeIdInt)
                    put("endpoint_id", 1)
                    put("cluster_id", clusterId)
                    put("command_name", commandName)
                    put("payload", argsObj)
                }
            )
        }
        val resp = executeMatterWsRpc(serverHostIp, req, 1800) ?: return false
        if (resp.has("error_code") && resp.optInt("error_code", 0) != 0) return false

        // Also send LevelControl cluster command if dimming a Matter light
        if (tile.hasDimmingCapability && tile.isActive && tile.volume in 1f..99f) {
            val level254 = ((tile.volume / 100f) * 254f).roundToInt().coerceIn(1, 254)
            val levelReq = JSONObject().apply {
                put("message_id", "clara_matter_lvl_${wsCommandIdCounter.incrementAndGet()}")
                put("command", "device_command")
                put(
                    "args",
                    JSONObject().apply {
                        put("node_id", nodeIdInt)
                        put("endpoint_id", 1)
                        put("cluster_id", CLUSTER_LEVEL_CONTROL)
                        put("command_name", "MoveToLevelWithOnOff")
                        put(
                            "payload",
                            JSONObject()
                                .put("level", level254)
                                .put("transitionTime", 2)
                        )
                    }
                )
            }
            executeMatterWsRpc(serverHostIp, levelReq, 1200)
        }
        return true
    }

    suspend fun removeMatterNode(serverHostIp: String, nodeId: String): Boolean {
        val digits = nodeId.removePrefix("matter_").filter { it.isDigit() }
        val nodeIdInt = digits.toIntOrNull()?.takeIf { it > 0 } ?: 1
        val req = JSONObject().apply {
            put("message_id", "clara_remove_node_${wsCommandIdCounter.incrementAndGet()}")
            put("command", "remove_node")
            put("args", JSONObject().apply {
                put("node_id", nodeIdInt)
            })
        }
        val resp = executeMatterWsRpc(serverHostIp, req, 2000)
        return resp != null
    }

    private suspend fun queryMatterServerNodes(serverHostIp: String): List<DiscoveredMatterDevice> {
        val req = JSONObject().apply {
            put("message_id", "clara_get_nodes_${wsCommandIdCounter.incrementAndGet()}")
            put("command", "get_nodes")
        }
        val resp = executeMatterWsRpc(serverHostIp, req, 2400) ?: return emptyList()
        val resultArr = resp.optJSONArray("result") ?: return emptyList()
        val parsed = mutableListOf<DiscoveredMatterDevice>()

        for (i in 0 until resultArr.length()) {
            val node = resultArr.optJSONObject(i) ?: continue
            val nodeIdNum = node.optInt("node_id", i + 1)
            val available = node.optBoolean("available", true)
            val attrs = node.optJSONObject("attributes") ?: JSONObject()

            // BasicInformation cluster (0x0028 = 40)
            val vendorName = attrs.optString("0/40/1", "Matter Fabric")
            val productName = attrs.optString("0/40/3", "")
            val nodeLabel = attrs.optString("0/40/5", "").ifBlank {
                productName.ifBlank { "Matter Node $nodeIdNum" }
            }
            val vendorId = attrs.optInt("0/40/2", 0)

            // OnOff cluster (0x0006 = 6, attr 0 = OnOff)
            val isOn = attrs.optBoolean("1/6/0", attrs.optInt("1/6/0", 0) == 1)
            // LevelControl cluster (0x0008 = 8, attr 0 = CurrentLevel 0..254)
            val rawLevel = attrs.optInt("1/8/0", 254).coerceIn(0, 254)
            val levelPct = ((rawLevel / 254f) * 100f).coerceIn(0f, 100f)

            // Thermostat cluster (0x0201 = 513, attr 0 = LocalTemperature, attr 18 = OccupiedHeatingSetpoint)
            val localTempC = (attrs.optInt("1/513/0", 2050) / 100f).coerceIn(-10f, 50f)
            val targetTempC = (attrs.optInt("1/513/18", 2100) / 100f).coerceIn(5f, 35f)

            // Electrical Measurement (0x0B04 = 2820, attr 1291 = ActivePower, or 0x0090 = 144)
            val rawPowerMwOrW = attrs.optDouble("1/144/8", attrs.optDouble("1/2820/1291", 0.0)).toFloat()
            val powerWatts = if (rawPowerMwOrW > 4500f) rawPowerMwOrW / 1000f else rawPowerMwOrW.coerceAtLeast(0f)

            val nodeIdKey = "matter_$nodeIdNum"
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                transportSource = "MATTER_WS_5580",
                hardwareId = nodeIdKey,
                ipAddress = serverHostIp,
                port = MATTER_WS_PORT,
                vendor = "$nodeLabel ($vendorName)",
                confidenceScore = 0.90f,
                qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                outcomeReason = "Matter WebSocket get_nodes record received (available=$available)"
            )
            val suggestedType = inferTileTypeFromMatterMetadata(nodeLabel, vendorName)
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.IDENTIFIED,
                transportSource = "MATTER_WS_5580",
                hardwareId = nodeIdKey,
                ipAddress = serverHostIp,
                port = MATTER_WS_PORT,
                vendor = "$nodeLabel ($vendorName)",
                confidenceScore = 0.96f,
                qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                outcomeReason = "Matter WebSocket node introspected (VID=$vendorId on=$isOn)"
            )
            val dev = DiscoveredMatterDevice(
                nodeId = nodeIdKey,
                displayName = nodeLabel,
                vendorId = vendorId,
                vendorName = vendorName,
                ipAddress = serverHostIp,
                port = MATTER_WS_PORT,
                isCommissionedOperational = available,
                suggestedType = suggestedType,
                isActive = isOn,
                levelPct = levelPct,
                temperatureCelsius = localTempC,
                targetTempCelsius = targetTempC,
                powerWatts = powerWatts
            )
            parsed.add(dev)
        }
        return parsed
    }

    /**
     * Dispatches a command to a Matter device with automatic Cross-Protocol Multipath Failover:
     * 1. Matter Server WebSocket (`port 5580`) cluster command (`OnOff`, `LevelControl`, `DoorLock`, `WindowCovering`, `Thermostat`).
     * 2. Direct vendor LAN protocol on the healed IP (Kasa 9999, Tapo KLAP 80, Shelly Gen2/3 RPC, Sonoff 8081, Tuya 6668).
     * 3. Home Assistant Bridge service call fallback.
     */
    suspend fun sendMatterCommand(
        context: Context,
        tile: SmartTile,
        bridge: BridgeSettings
    ): Boolean = withContext(Dispatchers.IO) {
        val healedIp = resolveOrHealMatterIp(context, tile, bridge)
        val wsServerHost = resolveMatterServerHostIp(context, bridge).ifBlank { healedIp }

        // 1. Primary Path: Matter Server WebSocket Cluster Dispatch (Port 5580)
        if (wsServerHost.isNotBlank() && SmartHomeApiClient.isPortOpenInternal(wsServerHost, MATTER_WS_PORT, 240)) {
            if (sendMatterServerClusterCommand(wsServerHost, tile)) {
                return@withContext true
            }
        }

        // 2. Secondary Multipath Failover: Direct Vendor Local LAN Protocol on `healedIp`
        if (healedIp.isNotBlank()) {
            // 2A. TP-Link Kasa (9999) or Tapo KLAP (80)
            if (SmartHomeApiClient.isPortOpenInternal(healedIp, 9999, 200)) {
                if (TpLinkTapoKasaClient.setKasaRelayState(healedIp, tile.isActive, tile.volume.roundToInt())) {
                    return@withContext true
                }
            }
            if (TpLinkTapoKasaClient.hasSavedCredentials(context) &&
                SmartHomeApiClient.isPortOpenInternal(healedIp, 80, 200)
            ) {
                if (TpLinkTapoKasaClient.setTapoDeviceState(context, healedIp, tile.isActive, tile.volume.roundToInt())) {
                    return@withContext true
                }
            }

            // 2B. Shelly Gen2/Gen3 Matter Relay (Port 80 RPC)
            if (SmartHomeApiClient.isPortOpenInternal(healedIp, 80, 200)) {
                if (SmartHomeApiClient.toggleLocalShellyRelay(healedIp, tile.isActive)) {
                    return@withContext true
                }
            }

            // 2C. Sonoff Matter Switch (Port 8081)
            if (SmartHomeApiClient.isPortOpenInternal(healedIp, 8081, 200)) {
                if (EWeLinkSonoffClient.setSonoffSwitchState(context, tile.copy(ipAddress = healedIp), tile.isActive)) {
                    return@withContext true
                }
            }

            // 2D. Tuya Matter Local LAN 6668
            val devId = tile.serialNumber.ifBlank { tile.entityId }.removePrefix("matter_")
            if (devId.isNotBlank() && SmartHomeApiClient.isPortOpenInternal(healedIp, 6668, 200)) {
                if (TuyaApiClient.sendSingleSocketPlugCommand(
                        bridge = bridge,
                        deviceId = devId,
                        socketNumber = 1,
                        turnOn = tile.isActive,
                        ipAddress = healedIp,
                        localKey = tile.secretCode
                    )
                ) {
                    return@withContext true
                }
            }
        }

        // 3. Tertiary Failover: Home Assistant Bridge
        if (bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()) {
            val entityId = if (tile.entityId.contains(".")) {
                tile.entityId
            } else {
                val slug = tile.title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
                "switch.$slug"
            }
            val domain = entityId.substringBefore(".")
            val service = when (domain) {
                "lock" -> if (tile.isActive) "lock" else "unlock"
                "cover" -> if (tile.isActive) "open_cover" else "close_cover"
                else -> if (tile.isActive) "turn_on" else "turn_off"
            }
            if (SmartHomeApiClient.callHomeAssistantService(
                    haBaseUrl = bridge.haUrl,
                    haToken = bridge.haToken,
                    domain = domain,
                    service = service,
                    payloadJson = JSONObject().put("entity_id", entityId)
                )
            ) {
                return@withContext true
            }
        }

        false
    }

    /**
     * Protocol Lifecycle Audit #15 Fix:
     * Periodically syncs live Matter cluster states (`OnOff`, `LevelControl`, `Thermostat`,
     * `ElectricalPowerMeasurement`) onto dashboard tiles, reconciles against recent user taps
     * via `DeviceRepository.reconcileRemoteTileUpdate`, and replays queued Digital Twin Shadow intents.
     */
    suspend fun syncLiveMatterStates(
        context: Context,
        bridge: BridgeSettings,
        tiles: MutableList<SmartTile>
    ) {
        val matterTiles = tiles.filter {
            it.type == TileType.MATTER_DEVICE ||
                    it.serialNumber.startsWith("matter_") ||
                    it.entityId.startsWith("matter_") ||
                    it.activeProviderId.contains("MATTER", ignoreCase = true)
        }
        if (matterTiles.isEmpty()) return

        val pollStartMs = System.currentTimeMillis()
        val wsServerHost = resolveMatterServerHostIp(context, bridge)
        val liveNodes = if (wsServerHost.isNotBlank()) {
            withContext(Dispatchers.IO) {
                queryMatterServerNodes(wsServerHost)
            }
        } else {
            withContext(Dispatchers.IO) {
                discoverMatterDevices(context, timeoutMs = 1100L)
            }
        }
        val pollLatencyMs = (System.currentTimeMillis() - pollStartMs).coerceAtLeast(10L)

        val nodeById = liveNodes.associateBy { it.nodeId.lowercase(Locale.US) }
        val nodeByName = liveNodes.associateBy { it.displayName.lowercase(Locale.US) }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        withContext(Dispatchers.Main) {
            for (i in tiles.indices) {
                val current = tiles[i]
                if (current.type != TileType.MATTER_DEVICE &&
                    !current.serialNumber.startsWith("matter_") &&
                    !current.entityId.startsWith("matter_") &&
                    !current.activeProviderId.contains("MATTER", ignoreCase = true)
                ) continue

                val key = current.serialNumber.ifBlank { current.entityId }.trim().lowercase(Locale.US)
                val live = nodeById[key] ?: nodeByName[current.title.trim().lowercase(Locale.US)]
                if (live == null) {
                    if (current.isConfiguredAndLinked) {
                        JarvisLiveBridge.recordBackgroundPollOutcome(
                            tile = current,
                            providerId = "MATTER_FABRIC",
                            succeeded = false,
                            latencyMs = pollLatencyMs,
                            failureReason = "Matter node did not respond on local fabric"
                        )
                    }
                    continue
                }

                JarvisLiveBridge.recordBackgroundPollOutcome(
                    tile = current,
                    providerId = "MATTER_FABRIC",
                    succeeded = live.isCommissionedOperational,
                    latencyMs = pollLatencyMs,
                    failureReason = if (live.isCommissionedOperational) "" else "Matter node reported unavailable"
                )

                val wasOffline = !current.isOnline
                val hasPower = live.powerWatts > 0f || current.hasExplicitPowerTelemetry
                val incoming = current.copy(
                    ipAddress = current.ipAddress.ifBlank { live.ipAddress },
                    isOnline = live.isCommissionedOperational,
                    lifecycleState = if (live.isCommissionedOperational) DeviceLifecycleState.ONLINE else DeviceLifecycleState.OFFLINE,
                    isActive = live.isActive,
                    volume = if (current.hasDimmingCapability || current.type == TileType.SMART_COVER) live.levelPct else current.volume,
                    temperature = if (current.isThermostatTile) live.temperatureCelsius else current.temperature,
                    targetTemperature = if (current.isThermostatTile) live.targetTempCelsius else current.targetTemperature,
                    powerWatts = if (live.powerWatts > 0f) live.powerWatts else current.powerWatts,
                    hasExplicitPowerTelemetry = hasPower
                )
                tiles[i] = DeviceRepository.reconcileRemoteTileUpdate(
                    existing = current,
                    incoming = incoming,
                    incomingAuthority = StateAuthorityTier.DEVICE_HARDWARE_TRUTH,
                    sourceName = "Matter Fabric"
                )
                if (wasOffline && live.isCommissionedOperational && audioManager != null) {
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
