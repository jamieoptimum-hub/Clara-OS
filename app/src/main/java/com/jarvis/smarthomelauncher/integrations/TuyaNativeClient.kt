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
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.home.sdk.bean.HomeBean
import com.thingclips.smart.home.sdk.callback.IThingGetHomeListCallback
import com.thingclips.smart.home.sdk.callback.IThingHomeResultCallback
import com.thingclips.smart.sdk.api.IDevListener
import com.thingclips.smart.sdk.api.IResultCallback
import com.thingclips.smart.sdk.api.IThingDevice
import com.thingclips.smart.sdk.bean.DeviceBean
import com.thingclips.smart.sdk.enums.ThingDevicePublishModeEnum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.math.pow
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// TUYA / SMART LIFE / SMARTKNIGHT / AIRROBO / LYTMI NATIVE SDK & LAN ENGINE
// - Automatic Hardware Schema Introspection: Parses every Data Point (schemaMap
//   & dps) into structured DeviceCapabilityDescriptor items (switches, enum
//   modes, sliders, timers, and read-only sensors) and detects power telemetry.
// - Pure Local LAN AES-128-ECB Socket Engine (Port 6668) with 10-second
//   0x00000009 HEART_BEAT Keep-Alive Daemon + Lytmi HDMI Sync Box & 360° RGB
//   Wheel support for sub-15ms offline control with SDK/HTTP fallback.
// - Full Robovac Action Mode Dispatch (Auto, Spot, Turbo Suction, Dock).
// - Live IDevListener with onDpUpdate, onStatusChanged, and onDevInfoUpdate
//   schema refresh, reconciled via DeviceRepository.reconcileRemoteTileUpdate.
// ---------------------------------------------------------------------------
object TuyaApiClient {
    internal const val TAG = "TuyaSDM"
    internal const val HA_SHARING_CLIENT_ID = "HA_3y9q4ak7g4ephrvke"
    internal const val HA_SHARING_SCHEMA = "haauthorize"
    internal const val IOTBING_BASE = "https://apigw.iotbing.com"

    // Minimum interval between full cloud `getHomeDetail` batch network refreshes (5 minutes)
    private const val FULL_CLOUD_SYNC_INTERVAL_MS = 5 * 60_000L
    private const val TUYA_LAN_PORT = 6668
    private const val TUYA_CMD_CONTROL = 0x00000007
    private const val TUYA_CMD_HEART_BEAT = 0x00000009
    private const val ONLINE_GRACE_WINDOW_MS = 30_000L

    private class PersistentTuyaLanSocket(
        val ipAddress: String,
        val deviceId: String,
        val socket: Socket,
        val input: DataInputStream,
        val out: DataOutputStream,
        @Volatile var lastActivityEpochMs: Long = System.currentTimeMillis(),
        @Volatile var protocolVersion: String = "3.3",
        @Volatile var sessionKey: ByteArray? = null
    ) {
        val lock = Any()
        @Volatile var readerJob: Job? = null

        fun isAlive(): Boolean {
            return !socket.isClosed &&
                    socket.isConnected &&
                    !socket.isInputShutdown &&
                    !socket.isOutputShutdown
        }

        fun closeQuietly() {
            readerJob?.cancel()
            try {
                socket.close()
            } catch (e: Exception) {
            com.jarvis.smarthomelauncher.core.ErrorClassifier.classifyException(e).let { category ->
                com.jarvis.smarthomelauncher.core.DiscoveryFlightRecorder.record(
                    eventType = com.jarvis.smarthomelauncher.core.DiscoveryEventType.FAILED,
                    transportSource = "TuyaNativeClient",
                    hardwareId = "-",
                    vendor = "Vendor",
                    qualificationStage = "Exception Caught",
                    outcomeReason = com.jarvis.smarthomelauncher.core.ErrorClassifier.getHumanReadableRemediation(category, e),
                    rawPayload = e.stackTraceToString().take(200)
                )
            }
            
        }
        }
    }

    private val lanSocketPoolByIp = ConcurrentHashMap<String, PersistentTuyaLanSocket>()
    internal val lastSeenOnlineEpochMsByDevice = ConcurrentHashMap<String, Long>()
    internal val consecutiveFailuresByDevice = ConcurrentHashMap<String, Int>()

    @Volatile
    private var lanHeartbeatDaemonJob: Job? = null

    @Volatile
    internal var cachedToken: String = ""

    @Volatile
    internal var cachedConsumerAccessToken: String = ""

    @Volatile
    internal var cachedWorkingHost: String = DEFAULT_TUYA_API_HOST

    @Volatile
    internal var tokenExpiryMs: Long = 0L

    @Volatile
    internal var lastCommandTimestampMs: Long = 0L

    @Volatile
    internal var hasInitializedNativeHomeInSession: Boolean = false

    @Volatile
    private var lastFullNativeSyncTimestampMs: Long = 0L

    @Volatile
    private var cachedPrimaryHomeId: Long = -1L

    private val nativeDiscoveryMutex = Mutex()
    private val knownNativeDeviceIds = ConcurrentHashMap.newKeySet<String>()
    private val lastLoggedSummaryByDevice = ConcurrentHashMap<String, String>()
    private val lanSeqCounter = AtomicInteger(1)

    internal val localKeyByDevice = ConcurrentHashMap<String, String>()
    internal val localIpByDevice = ConcurrentHashMap<String, String>()
    internal val protocolVersionByDevice = ConcurrentHashMap<String, String>()
    internal val sessionKeyByDevice = ConcurrentHashMap<String, ByteArray>()
    internal val capabilitiesByDevice = ConcurrentHashMap<String, List<DeviceCapabilityDescriptor>>()
    internal val hasPowerTelemetryByDevice = ConcurrentHashMap<String, Boolean>()

    internal val switchDpCodeByDevice = ConcurrentHashMap<String, String>()
    internal val switch2DpCodeByDevice = ConcurrentHashMap<String, String>()
    internal val tempSetDpCodeByDevice = ConcurrentHashMap<String, String>()
    internal val tempScaleByDevice = ConcurrentHashMap<String, Float>()

    internal val switchDpIdByDevice = ConcurrentHashMap<String, String>()
    internal val switch2DpIdByDevice = ConcurrentHashMap<String, String>()
    internal val tempSetDpIdByDevice = ConcurrentHashMap<String, String>()
    internal val currentTempDpIdByDevice = ConcurrentHashMap<String, String>()
    internal val dpIdByDeviceAndCode = ConcurrentHashMap<String, String>()

    internal val activeDeviceInstances = ConcurrentHashMap<String, IThingDevice>()
    internal val activeDeviceListeners = ConcurrentHashMap<String, IDevListener>()
    internal val pendingRadiatorJobs = ConcurrentHashMap<String, Job>()

    internal fun findCapability(deviceId: String, vararg candidateKeys: String): DeviceCapabilityDescriptor? {
        val caps = capabilitiesByDevice[deviceId].orEmpty()
        if (caps.isEmpty()) return null
        return caps.firstOrNull { cap ->
            candidateKeys.any { k -> cap.key.equals(k, ignoreCase = true) }
        }
    }

    internal fun findDpId(deviceId: String, vararg candidateKeys: String): String? {
        for (key in candidateKeys) {
            dpIdByDeviceAndCode["$deviceId:$key"]?.takeIf { it.isNotBlank() }?.let { return it }
            dpIdByDeviceAndCode["$deviceId:${key.lowercase(Locale.US)}"]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return findCapability(deviceId, *candidateKeys)?.dpId?.takeIf { it.isNotBlank() }
    }

    fun clearAllDeviceCaches() {
        lanSocketPoolByIp.values.forEach { it.closeQuietly() }
        lanSocketPoolByIp.clear()
        activeDeviceInstances.values.forEach { runCatching { it.onDestroy() } }
        activeDeviceInstances.clear()
        activeDeviceListeners.clear()
        pendingRadiatorJobs.values.forEach { it.cancel() }
        pendingRadiatorJobs.clear()
        knownNativeDeviceIds.clear()
        lastLoggedSummaryByDevice.clear()
        localIpByDevice.clear()
        localKeyByDevice.clear()
        capabilitiesByDevice.clear()
        hasPowerTelemetryByDevice.clear()
        lastSeenOnlineEpochMsByDevice.clear()
        consecutiveFailuresByDevice.clear()
        switchDpCodeByDevice.clear()
        switch2DpCodeByDevice.clear()
        tempSetDpCodeByDevice.clear()
        tempScaleByDevice.clear()
        switchDpIdByDevice.clear()
        switch2DpIdByDevice.clear()
        tempSetDpIdByDevice.clear()
        currentTempDpIdByDevice.clear()
        dpIdByDeviceAndCode.clear()
    }

    private fun resolveAppContext(): Context? =
        ClaraApplication.appContextOrNull()

    fun markDeviceSeenOnline(deviceId: String) {
        val clean = deviceId.trim()
        if (clean.isNotBlank()) {
            lastSeenOnlineEpochMsByDevice[clean] = System.currentTimeMillis()
            consecutiveFailuresByDevice[clean] = 0
        }
    }

    fun recordDeviceCommandOutcome(deviceId: String, succeeded: Boolean) {
        val clean = deviceId.trim()
        if (clean.isBlank()) return
        if (succeeded) {
            markDeviceSeenOnline(clean)
        } else {
            consecutiveFailuresByDevice[clean] = (consecutiveFailuresByDevice[clean] ?: 0) + 1
        }
    }

    /**
     * Local-First Reachability & Grace Period Guard:
     * Prevents transient Tuya Cloud MQTT blips from marking a device offline when it is
     * still alive on local Wi-Fi (ports 6668, 80, 9999, 8081, 8009, 554) or within a 3-minute
     * verified online grace window without 3 consecutive command failures.
     */
    fun resolveKnownLanIp(deviceId: String): String {
        val cleanId = deviceId.trim()
        if (cleanId.isBlank()) return ""
        resolveAppContext()?.let { ctx ->
            ClaraDeviceRegistryStore.getInstance(ctx).resolveEndpointForDevice(cleanId)?.hostIp
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    localIpByDevice[cleanId] = it
                    return it
                }
        }
        return localIpByDevice[cleanId].orEmpty()
    }

    fun resolveKnownLocalKey(deviceId: String, macAddress: String = ""): String {
        val cleanId = deviceId.trim()
        if (cleanId.isBlank() && macAddress.isBlank()) return ""
        resolveAppContext()?.let { ctx ->
            ClaraSecretVault.getTuyaLocalKey(ctx, cleanId, macAddress)
                ?.takeIf { it.length == 16 }
                ?.let {
                    if (cleanId.isNotBlank()) localKeyByDevice[cleanId] = it
                    return it
                }
        }
        return localKeyByDevice[cleanId].orEmpty()
    }

    fun resolveProtocolVersion(deviceId: String): String {
        val cleanId = deviceId.trim()
        if (cleanId.isBlank()) return ""
        resolveAppContext()?.let { ctx ->
            ClaraDeviceRegistryStore.getInstance(ctx).getPersistedProtocolVersion(cleanId)
                .takeIf { it.isNotBlank() }
                ?.let {
                    protocolVersionByDevice[cleanId] = it
                    return it
                }
        }
        return protocolVersionByDevice[cleanId].orEmpty()
    }

    fun isDeviceReachableOrOnline(
        deviceId: String,
        ipAddress: String = "",
        reportedCloudOnline: Boolean = false,
        checkPorts: List<Int> = listOf(6668, 80, 9999, 8081, 8009, 554)
    ): Boolean {
        val cleanId = deviceId.trim()
        if (reportedCloudOnline) {
            if (cleanId.isNotBlank()) markDeviceSeenOnline(cleanId)
            return true
        }
        val cleanIp = ipAddress.substringBefore(":").trim()
            .ifBlank { if (cleanId.isNotBlank()) resolveKnownLanIp(cleanId) else "" }
        if (cleanIp.isNotBlank()) {
            if (lanSocketPoolByIp[cleanIp]?.isAlive() == true) {
                if (cleanId.isNotBlank()) markDeviceSeenOnline(cleanId)
                return true
            }
            for (port in checkPorts) {
                if (SmartHomeApiClient.isPortOpenInternal(cleanIp, port, 180)) {
                    if (cleanId.isNotBlank()) markDeviceSeenOnline(cleanId)
                    return true
                }
            }
        }
        if (cleanId.isNotBlank()) {
            val failures = consecutiveFailuresByDevice[cleanId] ?: 0
            val lastSeen = lastSeenOnlineEpochMsByDevice[cleanId] ?: 0L
            if (failures < 3 && (System.currentTimeMillis() - lastSeen) < ONLINE_GRACE_WINDOW_MS) {
                return true
            }
        }
        return false
    }

    fun registerDeviceLanAddress(deviceId: String, ipAddress: String, localKey: String = "") {
        val cleanId = deviceId.trim()
        val cleanIp = ipAddress.substringBefore(":").trim()
        val cleanKey = localKey.trim()
        val appCtx = resolveAppContext()
        if (cleanId.isNotBlank() && cleanIp.isNotBlank()) {
            localIpByDevice[cleanId] = cleanIp
            SmartHomeApiClient.registerVerifiedLanNeighbor(cleanIp, cleanId)
            markDeviceSeenOnline(cleanId)
            if (appCtx != null) {
                val store = ClaraDeviceRegistryStore.getInstance(appCtx)
                val matchedGuid = store.resolveEndpointForDevice(cleanId)?.canonicalGuid
                    ?: CrossEcosystemIdentityCorrelator.resolveCanonicalGuidForCandidate(
                        context = appCtx,
                        tuyaGwId = cleanId,
                        hardwareSerial = cleanId,
                        cloudDeviceId = cleanId
                    )
                if (!matchedGuid.isNullOrBlank()) {
                    store.updateDeviceEndpointAndMac(
                        canonicalGuid = matchedGuid,
                        newHostIp = cleanIp,
                        newPort = TUYA_LAN_PORT
                    )
                }
            }
        }
        if (cleanId.isNotBlank() && cleanKey.length == 16) {
            localKeyByDevice[cleanId] = cleanKey
            appCtx?.let { ctx ->
                com.jarvis.smarthomelauncher.core.ClaraSecretVault.putTuyaLocalKey(ctx, cleanId, cleanKey)
            }
        }
    }

    /**
     * Encodes an ARGB colour and 0–100% brightness into Tuya's standard v2 12-character HSV hex
     * format (`HHHHSSSSVVVV` where H = 0..360, S = 0..1000, V = 10..1000).
     */
    fun encodeTuyaHsvHex12(colorArgb: Long, brightnessPct: Float): String {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(colorArgb.toInt(), hsv)
        val hInt = hsv[0].roundToInt().coerceIn(0, 360)
        val sInt = (hsv[1] * 1000f).roundToInt().coerceIn(0, 1000)
        val vInt = ((brightnessPct.coerceIn(1f, 100f) / 100f) * 1000f).roundToInt().coerceIn(10, 1000)
        return "%04x%04x%04x".format(Locale.US, hInt, sInt, vInt)
    }

    fun defaultLytmiCapabilities(
        isActive: Boolean = true,
        hdmiInput: Int = 1,
        syncMode: String = "VIDEO",
        sceneName: String = "Cyberpunk",
        diffusionSpeed: Float = 75f
    ): List<DeviceCapabilityDescriptor> = listOf(
        DeviceCapabilityDescriptor(
            key = "switch_led",
            dpId = "20",
            label = "Backlight Power",
            kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
            isWritable = true,
            isPinnedToHero = true,
            currentValue = if (isActive) "true" else "false"
        ),
        DeviceCapabilityDescriptor(
            key = "work_mode",
            dpId = "21",
            label = "Sync Mode",
            kind = DeviceCapabilityKind.ENUM_MODE,
            isWritable = true,
            isPinnedToHero = true,
            currentValue = syncMode,
            enumOptions = listOf("VIDEO", "GAME", "MUSIC", "SCENE", "COLOR")
        ),
        DeviceCapabilityDescriptor(
            key = "hdmi_input",
            dpId = "101",
            label = "HDMI Source",
            kind = DeviceCapabilityKind.ENUM_MODE,
            isWritable = true,
            isPinnedToHero = true,
            currentValue = "HDMI $hdmiInput",
            enumOptions = listOf("HDMI 1", "HDMI 2", "HDMI 3", "HDMI 4")
        ),
        DeviceCapabilityDescriptor(
            key = "scene_select",
            dpId = "25",
            label = "Ambient Scene",
            kind = DeviceCapabilityKind.ENUM_MODE,
            isWritable = true,
            isPinnedToHero = true,
            currentValue = sceneName,
            enumOptions = listOf("Cyberpunk", "Aurora", "Sunset", "Ocean", "Cinema", "Firelight")
        ),
        DeviceCapabilityDescriptor(
            key = "diffusion_speed",
            dpId = "102",
            label = "Diffusion Speed",
            kind = DeviceCapabilityKind.NUMERIC_SLIDER,
            isWritable = true,
            isPinnedToHero = false,
            currentValue = diffusionSpeed.roundToInt().toString(),
            minValue = 1f,
            maxValue = 100f,
            stepValue = 1f,
            unit = "%"
        )
    )

    fun defaultProBreezeFanCapabilities(
        isActive: Boolean = true,
        speedLevel: Int = 3,
        fanMode: String = "NORMAL",
        oscillating: Boolean = false,
        ionizerActive: Boolean = false,
        timerHours: Int = 0
    ): List<DeviceCapabilityDescriptor> = listOf(
        DeviceCapabilityDescriptor(
            key = "switch",
            dpId = "1",
            label = "Fan Power",
            kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
            isWritable = true,
            isPinnedToHero = true,
            currentValue = isActive.toString()
        ),
        DeviceCapabilityDescriptor(
            key = "mode",
            dpId = "2",
            label = "Wind Mode",
            kind = DeviceCapabilityKind.ENUM_MODE,
            isWritable = true,
            isPinnedToHero = true,
            currentValue = fanMode.uppercase(Locale.US),
            enumOptions = listOf("NORMAL", "NATURAL", "SLEEP", "AUTO")
        ),
        DeviceCapabilityDescriptor(
            key = "fan_speed",
            dpId = "3",
            label = "Fan Speed",
            kind = DeviceCapabilityKind.NUMERIC_SLIDER,
            isWritable = true,
            isPinnedToHero = true,
            currentValue = speedLevel.coerceIn(1, 6).toString(),
            minValue = 1f,
            maxValue = 6f,
            stepValue = 1f,
            unit = ""
        ),
        DeviceCapabilityDescriptor(
            key = "switch_horizontal",
            dpId = "4",
            label = "Horizontal Oscillation",
            kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
            isWritable = true,
            isPinnedToHero = true,
            currentValue = oscillating.toString()
        ),
        DeviceCapabilityDescriptor(
            key = "anion",
            dpId = "5",
            label = "Ionizer / Purifier",
            kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
            isWritable = true,
            isPinnedToHero = true,
            currentValue = ionizerActive.toString()
        ),
        DeviceCapabilityDescriptor(
            key = "countdown_set",
            dpId = "6",
            label = "Auto-Off Timer",
            kind = DeviceCapabilityKind.ENUM_MODE,
            isWritable = true,
            isPinnedToHero = false,
            currentValue = if (timerHours > 0) "${timerHours}h" else "OFF",
            enumOptions = listOf("OFF", "1h", "2h", "4h", "8h")
        )
    )

    private fun humanizeDpCodeLabel(rawCode: String): String {
        val lower = rawCode.lowercase(Locale.US)
        return when (lower) {
            "switch", "switch_1", "switch1" -> "Socket 1 Power"
            "switch_2", "switch2" -> "Socket 2 Power"
            "switch_3", "switch3" -> "Socket 3 Power"
            "switch_led", "led_switch" -> "Light Power"
            "relay_status" -> "Power-On State"
            "light_mode" -> "Indicator LED Mode"
            "child_lock", "lock" -> "Child Lock"
            "overcharge_switch" -> "Overcharge Protection"
            "countdown_1", "countdown", "countdown_set" -> "Auto-Off Timer"
            "countdown_2" -> "Socket 2 Timer"
            "cur_power" -> "Live Power"
            "cur_voltage" -> "Mains Voltage"
            "cur_current" -> "Live Current"
            "add_ele" -> "Total Energy"
            "temp_set", "set_temp" -> "Target Temperature"
            "temp_current", "current_temp" -> "Current Temperature"
            "mode", "work_mode" -> "Operating Mode"
            "fan_speed", "fan_speed_percent", "windspeed", "level", "suction", "cistern" -> "Fan / Suction Level"
            "switch_horizontal", "switch_vertical", "shake", "swing" -> "Oscillation"
            "anion", "ionizer", "fresh" -> "Ionizer / Purifier"
            "power_go", "switch_go" -> "Auto Clean"
            "switch_charge" -> "Return to Dock"
            "bright_value", "bright_value_v2" -> "Brightness"
            "temp_value", "temp_value_v2" -> "Color Temperature"
            else -> rawCode.split("_", "-")
                .filter { it.isNotBlank() }
                .joinToString(" ") { word ->
                    word.replaceFirstChar { c -> c.uppercase(Locale.US) }
                }
        }
    }

    /**
     * Safely extracts the device list from a [HomeBean], guarding against Tuya SDK's internal
     * `DevUtil.decodeRaw` `ConcurrentHashMap` NullPointerException when a device has an empty raw DP.
     */
    private fun extractSafeDevicesFromHomeBean(bean: HomeBean?, homeId: Long): List<DeviceBean> {
        if (!ClaraApplication.isTuyaSdkReady()) return emptyList()
        val fromDataInstance = runCatching {
            ThingHomeSdk.getDataInstance().getHomeDeviceList(homeId)
        }.getOrNull()
        if (!fromDataInstance.isNullOrEmpty()) {
            return fromDataInstance.filterNotNull()
        }
        val fromBean = runCatching {
            bean?.deviceList
        }.getOrNull()
        if (!fromBean.isNullOrEmpty()) {
            return fromBean.filterNotNull()
        }
        return knownNativeDeviceIds.mapNotNull { id ->
            runCatching { ThingHomeSdk.getDataInstance().getDeviceBean(id) }.getOrNull()
        }
    }

    /**
     * Converts a native ThingHomeSdk DeviceBean into Clara's DiscoveredTuyaDevice model,
     * inspects both schemaMap (code + rw/ro mode + property JSON) and raw numeric DPs
     * to build the full DeviceCapabilityDescriptor list, and caches numeric DP IDs.
     */
    fun convertDeviceBeanToDiscovered(d: DeviceBean): DiscoveredTuyaDevice {
        val devId = d.devId ?: ""
        if (devId.isNotBlank()) {
            knownNativeDeviceIds.add(devId)
        }
        val name = d.name?.ifBlank { "Tuya Smart Device" } ?: "Tuya Smart Device"
        val category = (d.productBean?.category ?: d.deviceCategory ?: "").lowercase(Locale.US)
        val beanPv = runCatching { d.pv?.trim().orEmpty() }.getOrDefault("")
        if (devId.isNotBlank() && beanPv.isNotBlank()) {
            protocolVersionByDevice[devId] = beanPv
        }
        val localKey = d.localKey ?: ""
        if (devId.isNotBlank() && localKey.length == 16) {
            localKeyByDevice[devId] = localKey
        }
        val rawOnline = d.isOnline
        if (rawOnline && devId.isNotBlank()) {
            markDeviceSeenOnline(devId)
        }
        val isOnline = isDeviceReachableOrOnline(
            deviceId = devId,
            ipAddress = localIpByDevice[devId].orEmpty(),
            reportedCloudOnline = rawOnline
        )

        var isActive = false
        var socket2Active = false
        var hasSecondSocketDp = false
        var hasEnergyTelemetryDp = false
        var currentTemp = 20.5f
        var targetTemp = 21.0f
        var levelOrBattery = 80f
        var powerWatts = 0f
        var voltageVolts = 0f
        var currentMilliamps = 0f
        var totalEnergyKwh = 0f
        var countdown1Sec = 0
        var countdown2Sec = 0
        var lytmiHdmi = 1
        var lytmiMode = "VIDEO"
        var lytmiScene = "Cyberpunk"
        var lytmiSpeed = 75f
        var fanSpeed = 3
        var fanWindMode = "NORMAL"
        var fanOscillating = false
        var fanIonizer = false
        var fanTimerHours = 0
        var switchCode = "switch_1"
        var switch2Code = "switch_2"
        var tempSetCode = "temp_set"
        var tempDivisor = 1.0f
        var hasTemperatureDp = false

        var resolvedSwitchDpId: String? = null
        var resolvedSwitch2DpId: String? = null
        var resolvedCountdown1DpId: String? = null
        var resolvedCountdown2DpId: String? = null
        var resolvedTempSetDpId: String? = null
        var resolvedCurrentTempDpId: String? = null

        var resolvedVacPowerDpId: String? = null
        var resolvedVacGoDpId: String? = null
        var resolvedVacModeDpId: String? = null
        var resolvedVacChargeDpId: String? = null
        var resolvedVacSuctionDpId: String? = null

        val dpsMap: Map<String, Any?> = runCatching { d.dps ?: emptyMap() }.getOrDefault(emptyMap())
        val schemaMap = runCatching { d.schemaMap ?: emptyMap() }.getOrDefault(emptyMap())

        val schemaCodesLower = schemaMap.values.mapNotNull { it.code?.lowercase(Locale.US) }.toSet()

        val lowerDeviceName = name.lowercase(Locale.US)
        val isLytmiNameHint = lowerDeviceName.contains("lytmi") ||
                lowerDeviceName.contains("neo3") ||
                lowerDeviceName.contains("neo 3") ||
                lowerDeviceName.contains("fantasy") ||
                lowerDeviceName.contains("sync box") ||
                lowerDeviceName.contains("ambilight")
        val isLytmiDevice = category == "fwl" || isLytmiNameHint || (
                schemaCodesLower.any { it in setOf("hdmi_input", "input_source", "hdmi", "video_mode", "sync_switch") } &&
                        schemaCodesLower.any { it in setOf("work_mode", "sync_mode", "colour_data", "colour_data_v2", "scene_select", "scene_data", "music_data") }
                )

        val isVacuumDevice = !isLytmiDevice && (
                category == "sd" ||
                        schemaCodesLower.any {
                            it in setOf("power_go", "switch_go", "clean_switch", "switch_charge", "charge_switch", "suction", "clean_mode")
                        }
                )

        val isTowerFanDevice = !isLytmiDevice && !isVacuumDevice && (
                category in listOf("fs", "fskg", "fsd") || (
                        schemaCodesLower.any { it in setOf("fan_speed", "fan_speed_percent", "windspeed") } &&
                                schemaCodesLower.any { it in setOf("switch_horizontal", "switch_vertical", "shake", "swing", "anion", "ionizer") } &&
                                schemaCodesLower.none { it in setOf("temp_set", "set_temp", "target_temp") }
                        )
                )

        val dpsValuesLower = dpsMap.values.mapNotNull { it?.toString()?.lowercase(Locale.US) }.toSet()
        val hasKettleDpsInValues = !isVacuumDevice && (
                dpsValuesLower.any { it in setOf("heating_quick", "diy", "boil", "keep_warm") } ||
                        (dpsMap["4"] == 100 && dpsMap["15"] is Number && dpsMap["9"] is String)
                )

        // Tier 1: Capability & DP Ground Truth First (writable temperature setpoint <= 70 C + ambient temp DP)
        val hasClimateSetpointSchema = schemaCodesLower.any {
            it in setOf("temp_set", "set_temp", "target_temp", "target_temperature", "upper_temp")
        } && schemaCodesLower.none {
            it in setOf("temp_boiling", "cook_temperature", "warm_temp", "boil")
        }
        val hasClimateAmbientSchema = schemaCodesLower.any {
            it in setOf("temp_current", "current_temp", "va_temperature", "room_temp", "temp_indoor")
        }
        val hasRawHeaterDps = dpsMap["3"] is Number && dpsMap["4"] is Number &&
                dpsMap.keys.all { it in setOf("1", "2", "3", "4", "5", "6", "7", "8") }
        val isClimateByCapabilitiesFirst = !isVacuumDevice && !isLytmiDevice && !isTowerFanDevice && !hasKettleDpsInValues &&
                !schemaCodesLower.contains("switch_2") &&
                ((hasClimateSetpointSchema && hasClimateAmbientSchema) || hasClimateSetpointSchema || hasRawHeaterDps)

        // Tier 2: Vendor Climate Category Metadata Second
        val isClimateByCategorySecond = !isVacuumDevice && !isLytmiDevice && !isTowerFanDevice && !hasKettleDpsInValues &&
                category in listOf("wk", "wkf", "qn", "rs", "rd")

        // Tier 3: Title Matching Last Resort
        val isClimateByTitleThird = !isVacuumDevice && !isLytmiDevice && !isTowerFanDevice && !hasKettleDpsInValues &&
                !schemaCodesLower.contains("switch_2") && (
                name.contains("panel heater", ignoreCase = true) ||
                        name.contains("space heater", ignoreCase = true) ||
                        name.contains("heater", ignoreCase = true) ||
                        name.contains("radiator", ignoreCase = true) ||
                        name.contains("thermostat", ignoreCase = true) ||
                        name.contains("trv", ignoreCase = true)
                )

        val isHeaterDpsPattern = isClimateByCapabilitiesFirst || isClimateByCategorySecond || isClimateByTitleThird
        val isHvacOrWaterHeaterCategory = isHeaterDpsPattern || category in listOf("wk", "wkf", "qn", "rs", "rd")

        val hasSocketOrEnergyDps = !isHeaterDpsPattern && (
                category in listOf("cz", "pc", "kg", "tdq") ||
                        name.contains("socket", ignoreCase = true) ||
                        name.contains("plug", ignoreCase = true) ||
                        schemaCodesLower.any { it in setOf("switch_2", "cur_power", "cur_voltage", "cur_current", "add_ele") } ||
                        dpsMap.containsKey("18") ||
                        dpsMap.containsKey("19") ||
                        dpsMap.containsKey("20")
                )

        val hasHighTempApplianceSchema = !isVacuumDevice && !hasSocketOrEnergyDps && !isHvacOrWaterHeaterCategory && schemaMap.values.any { sc ->
            val c = (sc.code ?: "").lowercase(Locale.US)
            val p = runCatching {
                if (!sc.property.isNullOrBlank() && sc.property.trim().startsWith("{")) JSONObject(sc.property) else null
            }.getOrNull()
            val unitStr = p?.optString("unit", "")?.trim()?.lowercase(Locale.US).orEmpty()
            val isExplicitTempUnit = unitStr in setOf("c", "f", "\u00B0c", "\u00B0f", "\u2103", "\u2109", "celsius", "fahrenheit") ||
                    unitStr.contains("\u00B0c") ||
                    unitStr.contains("\u2103")
            val isTempCode = c in setOf("temp_boiling", "cook_temperature", "warm_temp") ||
                    (c.contains("temp") && !c.contains("countdown") && !c.contains("time"))
            if (p != null && (isTempCode || isExplicitTempUnit)) {
                val scExp = p.optInt("scale", 0).coerceIn(0, 4)
                val scDiv = if (scExp > 0) 10.0.pow(scExp.toDouble()).toFloat() else 1f
                val maxC = p.optDouble("max", 0.0).toFloat() / scDiv
                maxC in 71f..260f
            } else {
                false
            }
        }
        val hasApplianceOnlyDps = hasKettleDpsInValues ||
                name.contains("kettle", ignoreCase = true) ||
                schemaCodesLower.any {
                    it in setOf(
                        "warm", "keep_warm", "boil", "temp_boiling", "water_level",
                        "cook_time", "cook_temperature", "recipe", "shake_remind"
                    )
                }
        val isKitchenAppliance = !isVacuumDevice && !isLytmiDevice && !isTowerFanDevice && !hasSocketOrEnergyDps && !isHeaterDpsPattern && (
                hasHighTempApplianceSchema ||
                        hasApplianceOnlyDps ||
                        category in listOf("bh", "ymq", "kf", "kfj", "kz", "af", "mc", "dfb", "jc", "dk", "ysj", "mjj")
                )
        val isPlugOrSwitchCategory = !isVacuumDevice && !isLytmiDevice && !isTowerFanDevice && !isKitchenAppliance && !isHeaterDpsPattern && hasSocketOrEnergyDps

        val discoveredCapabilities = mutableListOf<DeviceCapabilityDescriptor>()

        for ((dpId, schema) in schemaMap) {
            val rawCode = schema.code ?: continue
            val code = rawCode.lowercase(Locale.US)
            val mode = (schema.mode ?: "rw").lowercase(Locale.US)
            val schemaType = (schema.schemaType ?: schema.type ?: "").lowercase(Locale.US)
            val propStr = schema.property ?: ""
            val propJson = runCatching {
                if (propStr.trim().startsWith("{")) JSONObject(propStr) else null
            }.getOrNull()

            dpIdByDeviceAndCode["$devId:$rawCode"] = dpId
            dpIdByDeviceAndCode["$devId:$code"] = dpId
            val value = dpsMap[dpId]

            // Parse capability descriptor for every hardware DP reported in schemaMap
            val isWritable = mode != "ro"
            val capKind = when {
                !isWritable -> DeviceCapabilityKind.READ_ONLY_SENSOR
                code.contains("countdown") -> DeviceCapabilityKind.COUNTDOWN_TIMER
                schemaType == "bool" || value is Boolean -> DeviceCapabilityKind.BOOLEAN_SWITCH
                schemaType == "enum" || propJson?.has("range") == true -> DeviceCapabilityKind.ENUM_MODE
                schemaType == "value" || value is Number -> DeviceCapabilityKind.NUMERIC_SLIDER
                else -> DeviceCapabilityKind.BOOLEAN_SWITCH
            }

            val enumOptions = mutableListOf<String>()
            val rangeArr = propJson?.optJSONArray("range")
            if (rangeArr != null) {
                for (rIdx in 0 until rangeArr.length()) {
                    rangeArr.optString(rIdx, "").takeIf { it.isNotBlank() }?.let { enumOptions.add(it) }
                }
            }

            val scaleExp = propJson?.optInt("scale", 0)?.coerceIn(0, 4) ?: 0
            val scaleDiv = if (scaleExp > 0) 10.0.pow(scaleExp.toDouble()).toFloat() else 1f
            val rawMin = propJson?.optDouble("min", 0.0)?.toFloat() ?: 0f
            val rawMax = propJson?.optDouble("max", 100.0)?.toFloat() ?: 100f
            val rawStep = propJson?.optDouble("step", 1.0)?.toFloat() ?: 1f
            val rawUnit = propJson?.optString("unit", "") ?: ""

            val formattedCurrentVal = when (value) {
                is Number -> if (scaleDiv > 1f) "%.1f".format(Locale.US, value.toFloat() / scaleDiv) else value.toString()
                is Boolean -> value.toString()
                null -> ""
                else -> value.toString()
            }

            val isAuxiliaryConfigDp = code in listOf(
                "relay_status", "light_mode", "overcharge_switch", "fault",
                "temp_correction", "temp_calibration", "Temp_unit_convert", "temp_unit_convert", "c_f"
            )
            val isPrimarySwitchDp = code in listOf(
                "switch", "switch_1", "switch1", "switch_2", "switch2", "power", "switch_led"
            )
            // Pin all writable hardware capabilities (except raw primary power toggles & internal fault/calibration DPs)
            // so dynamic capability buttons always render on the Hero Card
            val isPinnedDefault = isWritable && !isAuxiliaryConfigDp && !isPrimarySwitchDp
            if (!isVacuumDevice && !isTowerFanDevice && !isLytmiDevice && !isKitchenAppliance) {
                if (code == "eco" && (schemaType == "bool" || value is Boolean)) {
                    dpIdByDeviceAndCode["$devId:eco"] = dpId
                } else if (code in listOf("mode", "work_mode", "level", "gear") && (schemaType == "enum" || enumOptions.isNotEmpty())) {
                    dpIdByDeviceAndCode["$devId:mode"] = dpId
                }
            }

            val scaledMin = rawMin / scaleDiv
            val scaledMax = (rawMax / scaleDiv).coerceAtLeast(1f)
            val autoPresets: List<CapabilityPreset> = when {
                capKind == DeviceCapabilityKind.COUNTDOWN_TIMER -> listOf(
                    CapabilityPreset(value = 300f, label = "5m Timer", icon = "TIMER"),
                    CapabilityPreset(value = 900f, label = "15m Timer", icon = "TIMER"),
                    CapabilityPreset(value = 1800f, label = "30m Timer", icon = "TIMER"),
                    CapabilityPreset(value = 3600f, label = "1h Timer", icon = "TIMER"),
                    CapabilityPreset(value = 7200f, label = "2h Timer", icon = "TIMER")
                ).filter { it.value <= scaledMax.coerceAtLeast(3600f) }
                code in listOf("bright_value", "bright_value_v2", "brightness") -> listOf(
                    CapabilityPreset(value = 100f, label = "On (100%)", icon = "SUNNY"),
                    CapabilityPreset(value = 75f, label = "Bright (75%)", icon = "BRIGHT"),
                    CapabilityPreset(value = 25f, label = "Dim (25%)", icon = "DIM"),
                    CapabilityPreset(value = 0f, label = "Off", icon = "POWER")
                )
                code in listOf("position", "percent_control", "control_back") && capKind == DeviceCapabilityKind.NUMERIC_SLIDER -> listOf(
                    CapabilityPreset(value = 100f, label = "Open (100%)", icon = "BLINDS_OPEN"),
                    CapabilityPreset(value = 50f, label = "Half (50%)", icon = "BLINDS_HALF"),
                    CapabilityPreset(value = 25f, label = "Vent (25%)", icon = "BLINDS_VENT"),
                    CapabilityPreset(value = 0f, label = "Close (0%)", icon = "BLINDS_CLOSED")
                )
                capKind == DeviceCapabilityKind.NUMERIC_SLIDER && !isHvacOrWaterHeaterCategory && scaledMax > 70f && (code.contains("temp") || rawUnit.contains("C", true) || rawUnit.contains("\u00B0")) -> {
                    val unitLbl = rawUnit.ifBlank { "\u00B0C" }
                    val span = (scaledMax - scaledMin).coerceAtLeast(10f)
                    val step = (rawStep / scaleDiv).coerceAtLeast(5f)
                    val p1 = scaledMax
                    val p2 = (scaledMin + span * 0.75f).let { (it / step).roundToInt() * step }.coerceIn(scaledMin, scaledMax)
                    val p3 = (scaledMin + span * 0.45f).let { (it / step).roundToInt() * step }.coerceIn(scaledMin, scaledMax)
                    val p4 = scaledMin
                    listOf(p1, p2, p3, p4).distinct().map { v ->
                        CapabilityPreset(value = v, label = "${v.roundToInt()}$unitLbl", icon = "HEAT")
                    }
                }
                else -> emptyList()
            }

            val baseCap = DeviceCapabilityDescriptor(
                key = rawCode,
                dpId = dpId,
                label = humanizeDpCodeLabel(rawCode),
                kind = capKind,
                isWritable = isWritable,
                isPinnedToHero = isPinnedDefault,
                currentValue = formattedCurrentVal,
                enumOptions = enumOptions,
                minValue = scaledMin,
                maxValue = scaledMax,
                stepValue = (rawStep / scaleDiv).coerceAtLeast(0.1f),
                unit = rawUnit,
                presets = autoPresets
            )
            discoveredCapabilities.add(
                baseCap.copy(rendererType = CapabilityClassifier.inferRendererType(baseCap))
            )

            if (isTowerFanDevice) {
                when {
                    code in listOf("switch", "fan_switch", "power", "switch_fan") -> {
                        resolvedSwitchDpId = dpId
                        switchCode = rawCode
                        if (value is Boolean) isActive = value
                    }
                    code in listOf("mode", "fan_mode", "work_mode") -> {
                        val vStr = value?.toString()?.uppercase(Locale.US).orEmpty()
                        fanWindMode = when {
                            vStr.contains("NAT") || vStr.contains("NATURE") -> "NATURAL"
                            vStr.contains("SLP") || vStr.contains("SLEEP") -> "SLEEP"
                            vStr.contains("AUTO") || vStr.contains("SMART") -> "AUTO"
                            else -> "NORMAL"
                        }
                    }
                    code in listOf("fan_speed", "fan_speed_percent", "windspeed", "speed", "level") -> {
                        val rawNum = value?.toString()?.filter { it.isDigit() }?.toIntOrNull()
                        if (rawNum != null) {
                            fanSpeed = if (rawNum > 6) ((rawNum / 16).coerceIn(1, 6)) else rawNum.coerceIn(1, 6)
                            levelOrBattery = (fanSpeed / 6f) * 100f
                        }
                    }
                    code in listOf("switch_horizontal", "switch_vertical", "shake", "swing", "oscillate") -> {
                        if (value is Boolean) fanOscillating = value
                    }
                    code in listOf("anion", "ionizer", "fresh", "purifier") -> {
                        if (value is Boolean) fanIonizer = value
                    }
                    code in listOf("countdown_set", "countdown", "countdown_1", "timer") -> {
                        val isMinutes = rawUnit.contains("min", true) || rawMax > 24f
                        val rawTimer = value?.toString()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                        fanTimerHours = if (isMinutes && rawTimer > 12) (rawTimer / 60).coerceIn(0, 12) else rawTimer.coerceIn(0, 12)
                    }
                }
            }

            if (isLytmiDevice || category in listOf("dj", "dd", "fwd", "xdd", "dc", "fwl")) {
                when {
                    code in listOf("switch_led", "switch", "power") -> {
                        resolvedSwitchDpId = dpId
                        switchCode = rawCode
                        if (value is Boolean) isActive = value
                    }
                    code in listOf("work_mode", "mode", "sync_mode") -> {
                        if (value is String && value.isNotBlank()) {
                            lytmiMode = value.uppercase(Locale.US)
                        }
                    }
                    code in listOf("hdmi_input", "input_source", "hdmi") -> {
                        val parsedHdmi = value?.toString()?.filter { it.isDigit() }?.toIntOrNull()
                        if (parsedHdmi != null && parsedHdmi in 1..4) {
                            lytmiHdmi = parsedHdmi
                        }
                    }
                    code in listOf("scene_data", "scene_data_v2", "scene_select", "dreamlight_scene_mode") -> {
                        if (value is String && value.isNotBlank()) {
                            lytmiScene = value
                        }
                    }
                    code in listOf("diffusion_speed", "speed", "sensitivity") -> {
                        if (value is Number) {
                            lytmiSpeed = value.toFloat().coerceIn(1f, 100f)
                        }
                    }
                }
            }

            if (isVacuumDevice) {
                when {
                    code in listOf("power_go", "switch_go", "clean_switch", "start", "work_switch") -> {
                        resolvedVacGoDpId = dpId
                        resolvedSwitchDpId = dpId
                        switchCode = rawCode
                        if (value is Boolean) isActive = value
                    }
                    code in listOf("power", "switch", "switch_1") -> {
                        resolvedVacPowerDpId = dpId
                        if (resolvedSwitchDpId == null) {
                            resolvedSwitchDpId = dpId
                            switchCode = rawCode
                            if (value is Boolean) isActive = value
                        }
                    }
                    code in listOf("mode", "work_mode", "clean_mode") -> {
                        resolvedVacModeDpId = dpId
                        if (value is String) {
                            val vLower = value.lowercase(Locale.US)
                            if (vLower in listOf("smart", "auto", "spiral", "spot", "part", "wall_follow", "single", "random", "partial", "pose", "zone")) {
                                if (resolvedVacGoDpId == null) isActive = true
                            } else if (vLower in listOf("chargego", "charge", "standby", "sleep")) {
                                if (resolvedVacGoDpId == null) isActive = false
                            }
                        }
                    }
                    code in listOf("suction", "fan_speed_enum", "fan_speed", "cistern", "clean_power") -> {
                        resolvedVacSuctionDpId = dpId
                    }
                    code in listOf("switch_charge", "charge_switch", "seek", "return_home") -> {
                        resolvedVacChargeDpId = dpId
                    }
                    code in listOf("status", "work_state", "robot_state") && value is String -> {
                        val stLower = value.lowercase(Locale.US)
                        if (stLower.contains("clean") || stLower.contains("smart") || stLower.contains("spiral") || stLower.contains("spot") || stLower.contains("working") || stLower == "1") {
                            isActive = true
                        } else if (stLower.contains("charge") || stLower.contains("dock") || stLower.contains("standby") || stLower.contains("paused") || stLower.contains("sleep")) {
                            if (resolvedVacGoDpId == null) isActive = false
                        }
                    }
                }
            }

            when {
                // 1A. Primary Power Switch (Socket 1) DPs
                !isVacuumDevice && (code in listOf("switch", "switch_1", "switch1", "power", "switch_led", "power_go", "work_state", "start", "boil") ||
                        (dpId == "1" && (value is Boolean || schemaType == "bool"))) -> {
                    if (resolvedSwitchDpId == null || dpId == "1" || code == "switch_1" || code == "switch") {
                        resolvedSwitchDpId = dpId
                        switchCode = rawCode
                        if (value is Boolean) isActive = value
                    }
                }
                // 1B. Secondary Power Switch (Socket 2 on Double Plugs) DPs
                !isVacuumDevice && code in listOf("switch_2", "switch2") -> {
                    resolvedSwitch2DpId = dpId
                    switch2Code = rawCode
                    hasSecondSocketDp = true
                    if (value is Boolean) socket2Active = value
                }
                // 2. Explicit Target Temperature (Setpoint) DPs
                !isPlugOrSwitchCategory && !isVacuumDevice && !isLytmiDevice && (mode != "ro") && (
                        code in listOf("temp_set", "set_temp", "target_temp", "upper_temp", "temperature", "temp_set_f", "tempset", "settemp", "cook_temperature", "temp_boiling") ||
                                (code.contains("temp") && !code.contains("current") && !code.contains("calib") && !code.contains("correct") && !code.contains("sen"))
                        ) -> {
                    hasTemperatureDp = !isKitchenAppliance
                    resolvedTempSetDpId = dpId
                    tempSetCode = rawCode
                    if (value is Number) {
                        val raw = value.toFloat()
                        if (scaleDiv > 1f) {
                            tempDivisor = scaleDiv
                            targetTemp = raw / scaleDiv
                        } else if (!isKitchenAppliance && raw > 55f) {
                            tempDivisor = 10f
                            targetTemp = raw / 10f
                        } else {
                            tempDivisor = 1f
                            targetTemp = raw
                        }
                    }
                }
                // 3. Explicit Current Temperature DPs
                !isPlugOrSwitchCategory && !isVacuumDevice && !isLytmiDevice && (
                        code in listOf("temp_current", "current_temp", "va_temperature", "room_temp", "temp_indoor", "tempcurrent", "currenttemp") ||
                                (code.contains("temp") && (code.contains("current") || mode == "ro"))
                        ) -> {
                    hasTemperatureDp = !isKitchenAppliance
                    resolvedCurrentTempDpId = dpId
                    if (value is Number) {
                        val raw = value.toFloat()
                        currentTemp = if (scaleDiv > 1f) {
                            raw / scaleDiv
                        } else if (!isKitchenAppliance && raw > 55f) {
                            raw / 10f
                        } else {
                            raw
                        }
                    }
                }
                // 4. Brightness DPs
                code in listOf("bright_value_v2", "bright_value") && value is Number -> {
                    val raw = value.toFloat()
                    levelOrBattery = if (raw > 100f) (raw / 10f).coerceIn(1f, 100f) else raw.coerceIn(1f, 100f)
                }
                // 5. Battery DPs
                code in listOf("electricity_left", "residual_electricity", "battery_percentage", "battery") && value is Number -> {
                    levelOrBattery = value.toFloat().coerceIn(0f, 100f)
                }
                // 6. Electrical Telemetry & Countdown DPs (SmartKnight / Tuya Plugs)
                code == "cur_power" -> {
                    hasEnergyTelemetryDp = true
                    if (value is Number) powerWatts = value.toFloat() / 10f
                }
                code == "cur_voltage" -> {
                    hasEnergyTelemetryDp = true
                    if (value is Number) voltageVolts = value.toFloat() / 10f
                }
                code == "cur_current" -> {
                    hasEnergyTelemetryDp = true
                    if (value is Number) currentMilliamps = value.toFloat()
                }
                code == "add_ele" -> {
                    hasEnergyTelemetryDp = true
                    if (value is Number) totalEnergyKwh = value.toFloat() / 1000f
                }
                code in listOf("countdown_1", "countdown") -> {
                    resolvedCountdown1DpId = dpId
                    if (value is Number) countdown1Sec = value.toInt()
                }
                code == "countdown_2" -> {
                    resolvedCountdown2DpId = dpId
                    if (value is Number) countdown2Sec = value.toInt()
                }
            }
        }

        // Raw DP Fallback for Robot Vacuums (Airrobo P30 / Tuya `sd` standard DP numbering)
        if (isVacuumDevice) {
            if (resolvedVacPowerDpId == null && dpsMap["1"] is Boolean) {
                resolvedVacPowerDpId = "1"
            }
            if (resolvedVacGoDpId == null) {
                when {
                    dpsMap["2"] is Boolean -> {
                        resolvedVacGoDpId = "2"
                        isActive = dpsMap["2"] as Boolean
                    }
                    dpsMap["1"] is Boolean -> {
                        resolvedVacGoDpId = "1"
                        isActive = dpsMap["1"] as Boolean
                    }
                    dpsMap["25"] is Boolean -> {
                        resolvedVacGoDpId = "25"
                        isActive = dpsMap["25"] as Boolean
                    }
                }
            }
            if (resolvedVacModeDpId == null) {
                when {
                    dpsMap["3"] is String -> resolvedVacModeDpId = "3"
                    dpsMap["4"] is String -> resolvedVacModeDpId = "4"
                    dpsMap["2"] is String -> resolvedVacModeDpId = "2"
                }
            }
            if (resolvedVacSuctionDpId == null) {
                when {
                    dpsMap["14"] is String -> resolvedVacSuctionDpId = "14"
                    dpsMap["9"] is String -> resolvedVacSuctionDpId = "9"
                    dpsMap["102"] is String -> resolvedVacSuctionDpId = "102"
                }
            }
            if (resolvedVacChargeDpId == null) {
                when {
                    dpsMap["11"] is Boolean -> resolvedVacChargeDpId = "11"
                    dpsMap["15"] is Boolean -> resolvedVacChargeDpId = "15"
                    dpsMap["13"] is Boolean -> resolvedVacChargeDpId = "13"
                }
            }
            for (batDp in listOf("6", "8", "14", "101")) {
                val v = dpsMap[batDp]
                if (v is Number && v.toInt() in 1..100) {
                    levelOrBattery = v.toFloat()
                    break
                }
            }
            resolvedVacPowerDpId?.let { dpIdByDeviceAndCode["$devId:power"] = it }
            resolvedVacGoDpId?.let {
                dpIdByDeviceAndCode["$devId:power_go"] = it
                resolvedSwitchDpId = it
            }
            resolvedVacModeDpId?.let { dpIdByDeviceAndCode["$devId:mode"] = it }
            resolvedVacChargeDpId?.let { dpIdByDeviceAndCode["$devId:switch_charge"] = it }
            resolvedVacSuctionDpId?.let { dpIdByDeviceAndCode["$devId:suction"] = it }
        }

        // Raw DP Fallback for Socket 1 (DP "1") and Socket 2 (DP "2" only when schemaMap is unavailable)
        if (!isVacuumDevice && resolvedSwitchDpId == null && dpsMap["1"] is Boolean) {
            resolvedSwitchDpId = "1"
            isActive = dpsMap["1"] as Boolean
            dpIdByDeviceAndCode["$devId:switch_1"] = "1"
            dpIdByDeviceAndCode["$devId:switch"] = "1"
        }
        if (!isVacuumDevice && resolvedSwitch2DpId == null && schemaMap.isEmpty() && isPlugOrSwitchCategory && dpsMap["2"] is Boolean) {
            resolvedSwitch2DpId = "2"
            hasSecondSocketDp = true
            socket2Active = dpsMap["2"] as Boolean
            dpIdByDeviceAndCode["$devId:switch_2"] = "2"
        }

        // Raw DP Fallback for SmartKnight / Tuya Plug Electrical Telemetry & Countdowns
        if (isPlugOrSwitchCategory) {
            if (powerWatts == 0f && dpsMap["19"] is Number) {
                hasEnergyTelemetryDp = true
                powerWatts = (dpsMap["19"] as Number).toFloat() / 10f
            }
            if (voltageVolts == 0f && dpsMap["20"] is Number) {
                hasEnergyTelemetryDp = true
                voltageVolts = (dpsMap["20"] as Number).toFloat() / 10f
            }
            if (currentMilliamps == 0f && dpsMap["18"] is Number) {
                hasEnergyTelemetryDp = true
                currentMilliamps = (dpsMap["18"] as Number).toFloat()
            }
            if (totalEnergyKwh == 0f && dpsMap["17"] is Number) {
                hasEnergyTelemetryDp = true
                totalEnergyKwh = (dpsMap["17"] as Number).toFloat() / 1000f
            }
            if (resolvedCountdown1DpId == null && dpsMap.containsKey("9")) {
                resolvedCountdown1DpId = "9"
                countdown1Sec = (dpsMap["9"] as? Number)?.toInt() ?: 0
                dpIdByDeviceAndCode["$devId:countdown_1"] = "9"
            }
            if (resolvedCountdown2DpId == null && hasSecondSocketDp && dpsMap.containsKey("10")) {
                resolvedCountdown2DpId = "10"
                countdown2Sec = (dpsMap["10"] as? Number)?.toInt() ?: 0
                dpIdByDeviceAndCode["$devId:countdown_2"] = "10"
            }
        }

        // Raw DP Fallback for radiators/heaters (only when not a smart plug, vacuum, or kitchen appliance)
        val cachedSetDpId = tempSetDpIdByDevice[devId]
        val cachedCurDpId = currentTempDpIdByDevice[devId]
        if (!isPlugOrSwitchCategory && !isVacuumDevice && !isLytmiDevice && !isKitchenAppliance && resolvedTempSetDpId == null) {
            // If DP 2 is the only numeric DP in the range 40..100 (or DP 3 & 4 are present), keep DP 2/4 available for ambient temp
            val candidateIds = if (cachedSetDpId != null) {
                (listOf(cachedSetDpId) + listOf("3", "16", "4", "2")).distinct()
            } else if (dpsMap["3"] is Number && dpsMap["4"] is Number) {
                listOf("3", "4", "16", "2")
            } else {
                listOf("3", "16", "4", "2")
            }
            for (cid in candidateIds) {
                if (cid == cachedCurDpId && dpsMap.keys.count { dpsMap[it] is Number } > 1) continue
                val numVal = (dpsMap[cid] as? Number)?.toFloat() ?: continue
                if (numVal <= 0f) continue
                val schemaMode = schemaMap[cid]?.mode?.lowercase(Locale.US) ?: "rw"
                if (schemaMode != "ro") {
                    resolvedTempSetDpId = cid
                    hasTemperatureDp = true
                    schemaMap[cid]?.code?.takeIf { it.isNotBlank() }?.let { tempSetCode = it }
                    if (numVal > 55f) {
                        tempDivisor = 10f
                        targetTemp = numVal / 10f
                    } else {
                        tempDivisor = 1f
                        targetTemp = numVal
                    }
                    break
                }
            }
        }
        if (!isPlugOrSwitchCategory && !isVacuumDevice && !isLytmiDevice && !isKitchenAppliance && resolvedCurrentTempDpId == null) {
            val candidateIds = if (cachedCurDpId != null) {
                (listOf(cachedCurDpId) + listOf("4", "2", "3", "24")).distinct()
            } else {
                listOf("4", "2", "3", "24")
            }
            for (cid in candidateIds) {
                if (cid == resolvedTempSetDpId) continue
                val numVal = (dpsMap[cid] as? Number)?.toFloat() ?: continue
                if (numVal <= 0f) continue
                val schemaMode = schemaMap[cid]?.mode?.lowercase(Locale.US) ?: "ro"
                if (schemaMode == "ro" || cid in listOf("4", "3", "2", "24")) {
                    resolvedCurrentTempDpId = cid
                    hasTemperatureDp = true
                    currentTemp = if (numVal > 55f) numVal / 10f else numVal
                    break
                }
            }
        }
        // Always extract live recorded water/room temperatures from raw DPs even if schemaMap was partially populated
        if (isKitchenAppliance) {
            (dpsMap["2"] as? Number)?.toFloat()?.let { rawCur ->
                currentTemp = if (rawCur > 200f) rawCur / 10f else rawCur
                resolvedCurrentTempDpId = "2"
            }
            (dpsMap["4"] as? Number)?.toFloat()?.let { rawTgt ->
                targetTemp = if (rawTgt > 200f) rawTgt / 10f else rawTgt
                resolvedTempSetDpId = "4"
            }
        } else if (isHeaterDpsPattern || hasTemperatureDp) {
            // Tuya Panel Heaters (e.g. 9b527d699fd434bb1fes0c) & Thermostats report temperatures across DPs 2, 3, and 4
            val rawDp2 = (dpsMap["2"] as? Number)?.toFloat()
            val rawDp3 = (dpsMap["3"] as? Number)?.toFloat()
            val rawDp4 = (dpsMap["4"] as? Number)?.toFloat()
            if (rawDp3 != null && rawDp4 != null) {
                val norm3 = if (rawDp3 > 55f) rawDp3 / 10f else rawDp3
                val norm4 = if (rawDp4 > 55f) rawDp4 / 10f else rawDp4
                if (resolvedTempSetDpId == null) {
                    targetTemp = norm3
                    resolvedTempSetDpId = "3"
                }
                if (resolvedCurrentTempDpId == null) {
                    currentTemp = if (resolvedTempSetDpId == "3") norm4 else norm3
                    resolvedCurrentTempDpId = if (resolvedTempSetDpId == "3") "4" else "3"
                }
            } else if (rawDp2 != null && (resolvedCurrentTempDpId == null || resolvedCurrentTempDpId == "2")) {
                val norm2 = if (rawDp2 > 55f && !isKitchenAppliance) rawDp2 / 10f else rawDp2
                currentTemp = norm2
                resolvedCurrentTempDpId = "2"
            }
        }
        // Only fall back to targetTemp if no live ambient temperature was reported
        if (resolvedCurrentTempDpId == null && resolvedTempSetDpId != null && currentTemp <= 0f) {
            currentTemp = targetTemp
        }

        // Golden Rule: Only synthesize a temperature dial from raw DPs when the hardware actually reports a numeric temperature DP
        val rawKitchenTargetDp = (dpsMap["4"] as? Number)?.toFloat()
        if (isKitchenAppliance && rawKitchenTargetDp != null && discoveredCapabilities.none { it.kind == DeviceCapabilityKind.NUMERIC_SLIDER }) {
            val rawTarget = rawKitchenTargetDp
            val rawCurrent = (dpsMap["2"] as? Number)?.toFloat() ?: currentTemp
            val rawWarmTemp = (dpsMap["15"] as? Number)?.toFloat() ?: 40f
            val rawKeepWarm = (dpsMap["14"] as? Boolean) ?: false
            val rawMode = (dpsMap["9"] as? String) ?: "heating_quick"
            val rawDiy = (dpsMap["11"] as? String) ?: "diy"
            currentTemp = rawCurrent
            targetTemp = rawTarget
            dpIdByDeviceAndCode["$devId:boil"] = "1"
            dpIdByDeviceAndCode["$devId:temp_boiling"] = "4"
            dpIdByDeviceAndCode["$devId:mode"] = "9"
            dpIdByDeviceAndCode["$devId:keep_warm"] = "14"
            dpIdByDeviceAndCode["$devId:warm_temp"] = "15"
            discoveredCapabilities.clear()
            discoveredCapabilities.addAll(
                listOf(
                    DeviceCapabilityDescriptor(
                        key = "boil",
                        dpId = "1",
                        label = "Boil Power",
                        kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                        isWritable = true,
                        isPinnedToHero = true,
                        currentValue = isActive.toString(),
                        rendererType = CapabilityRendererType.TOGGLE
                    ),
                    DeviceCapabilityDescriptor(
                        key = "temp_boiling",
                        dpId = "4",
                        label = "Target Water Temp",
                        kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                        isWritable = true,
                        isPinnedToHero = true,
                        currentValue = rawTarget.roundToInt().toString(),
                        minValue = 40f,
                        maxValue = 100f,
                        stepValue = 5f,
                        unit = "\u00B0C",
                        rendererType = CapabilityRendererType.TEMPERATURE_DIAL,
                        presets = listOf(
                            CapabilityPreset(100f, "100\u00B0C Boil", "HEAT"),
                            CapabilityPreset(90f, "90\u00B0C Coffee", "HEAT"),
                            CapabilityPreset(80f, "80\u00B0C Green Tea", "HEAT"),
                            CapabilityPreset(70f, "70\u00B0C Delicate", "HEAT")
                        )
                    ),
                    DeviceCapabilityDescriptor(
                        key = "mode",
                        dpId = "9",
                        label = "Heating Mode",
                        kind = DeviceCapabilityKind.ENUM_MODE,
                        isWritable = true,
                        isPinnedToHero = true,
                        currentValue = rawMode,
                        enumOptions = listOf("heating_quick", "boil", "keep_warm", rawDiy).distinct(),
                        rendererType = CapabilityRendererType.CHIP_GROUP
                    ),
                    DeviceCapabilityDescriptor(
                        key = "keep_warm",
                        dpId = "14",
                        label = "Keep Warm",
                        kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                        isWritable = true,
                        isPinnedToHero = true,
                        currentValue = rawKeepWarm.toString(),
                        rendererType = CapabilityRendererType.TOGGLE
                    ),
                    DeviceCapabilityDescriptor(
                        key = "warm_temp",
                        dpId = "15",
                        label = "Keep Warm (${rawWarmTemp.roundToInt()}\u00B0C)",
                        kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                        isWritable = true,
                        isPinnedToHero = true,
                        currentValue = rawWarmTemp.roundToInt().toString(),
                        minValue = 40f,
                        maxValue = 95f,
                        stepValue = 5f,
                        unit = "\u00B0C",
                        rendererType = CapabilityRendererType.SLIDER
                    )
                )
            )
        } else if (isHeaterDpsPattern && discoveredCapabilities.none { it.kind == DeviceCapabilityKind.NUMERIC_SLIDER }) {
            val dp3Val = (dpsMap["3"] as? Number)?.toFloat() ?: 21f
            val dp4Val = (dpsMap["4"] as? Number)?.toFloat() ?: 20f
            targetTemp = dp3Val
            currentTemp = dp4Val
            resolvedTempSetDpId = "3"
            resolvedCurrentTempDpId = "4"
            dpIdByDeviceAndCode["$devId:eco"] = "2"
            val ecoOn = (dpsMap["2"] as? Boolean) ?: false
            discoveredCapabilities.clear()
            discoveredCapabilities.addAll(
                listOf(
                    DeviceCapabilityDescriptor(
                        key = "switch",
                        dpId = "1",
                        label = "Heater Power",
                        kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                        isWritable = true,
                        isPinnedToHero = true,
                        currentValue = isActive.toString(),
                        rendererType = CapabilityRendererType.TOGGLE
                    ),
                    DeviceCapabilityDescriptor(
                        key = "temp_set",
                        dpId = "3",
                        label = "Target Temperature",
                        kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                        isWritable = true,
                        isPinnedToHero = true,
                        currentValue = "%.1f".format(Locale.US, targetTemp),
                        minValue = 5f,
                        maxValue = 37f,
                        stepValue = 1f,
                        unit = "\u00B0C",
                        rendererType = CapabilityRendererType.TEMPERATURE_DIAL
                    ),
                    DeviceCapabilityDescriptor(
                        key = "eco",
                        dpId = "2",
                        label = "Eco Mode",
                        kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                        isWritable = true,
                        isPinnedToHero = true,
                        currentValue = ecoOn.toString(),
                        rendererType = CapabilityRendererType.TOGGLE
                    )
                )
            )
        }

        resolvedSwitchDpId?.let {
            switchDpIdByDevice[devId] = it
            dpIdByDeviceAndCode["$devId:$switchCode"] = it
            dpIdByDeviceAndCode["$devId:switch_1"] = it
            dpIdByDeviceAndCode["$devId:switch"] = it
        }
        if (hasSecondSocketDp && resolvedSwitch2DpId != null) {
            switch2DpIdByDevice[devId] = resolvedSwitch2DpId
            switch2DpCodeByDevice[devId] = switch2Code
            dpIdByDeviceAndCode["$devId:$switch2Code"] = resolvedSwitch2DpId
            dpIdByDeviceAndCode["$devId:switch_2"] = resolvedSwitch2DpId
        } else {
            switch2DpIdByDevice.remove(devId)
            switch2DpCodeByDevice.remove(devId)
        }
        resolvedCountdown1DpId?.let {
            dpIdByDeviceAndCode["$devId:countdown_1"] = it
        }
        resolvedCountdown2DpId?.let {
            dpIdByDeviceAndCode["$devId:countdown_2"] = it
        }
        resolvedTempSetDpId?.let {
            tempSetDpIdByDevice[devId] = it
            dpIdByDeviceAndCode["$devId:$tempSetCode"] = it
            dpIdByDeviceAndCode["$devId:temp_set"] = it
        }
        resolvedCurrentTempDpId?.let {
            currentTempDpIdByDevice[devId] = it
        }

        switchDpCodeByDevice[devId] = switchCode
        tempSetDpCodeByDevice[devId] = tempSetCode
        tempScaleByDevice[devId] = tempDivisor

        if (isTowerFanDevice) {
            if (findDpId(devId, "mode", "fan_mode", "work_mode") == null && dpsMap["2"] is String) {
                dpIdByDeviceAndCode["$devId:mode"] = "2"
            }
            if (findDpId(devId, "fan_speed", "fan_speed_percent", "windspeed", "speed", "level") == null && (dpsMap["3"] != null || dpsMap["4"] is Number)) {
                dpIdByDeviceAndCode["$devId:fan_speed"] = if (dpsMap["3"] != null) "3" else "4"
            }
            val oscDpFallback = findDpId(devId, "switch_horizontal", "switch_vertical", "shake", "swing", "oscillate") ?: when {
                dpsMap["4"] is Boolean -> "4"
                dpsMap["5"] is Boolean -> "5"
                dpsMap["8"] is Boolean -> "8"
                else -> null
            }
            oscDpFallback?.let { dpIdByDeviceAndCode["$devId:switch_horizontal"] = it }
            if (findDpId(devId, "anion", "ionizer", "fresh", "purifier") == null) {
                when {
                    dpsMap["5"] is Boolean && oscDpFallback != "5" -> dpIdByDeviceAndCode["$devId:anion"] = "5"
                    dpsMap["9"] is Boolean -> dpIdByDeviceAndCode["$devId:anion"] = "9"
                    dpsMap["10"] is Boolean -> dpIdByDeviceAndCode["$devId:anion"] = "10"
                }
            }
            if (findDpId(devId, "countdown_set", "countdown", "countdown_1", "timer") == null) {
                when {
                    dpsMap["6"] != null -> dpIdByDeviceAndCode["$devId:countdown_set"] = "6"
                    dpsMap["7"] != null -> dpIdByDeviceAndCode["$devId:countdown_set"] = "7"
                    dpsMap["11"] != null -> dpIdByDeviceAndCode["$devId:countdown_set"] = "11"
                }
            }
        }

        val computedSchemaHash = CapabilitySchemaHasher.computeHash(discoveredCapabilities)
        val classification = CapabilityClassifier.resolve(
            capabilities = discoveredCapabilities,
            categoryCode = category,
            multiGangSocketCount = if (hasSecondSocketDp) 2 else 1,
            hasPowerTelemetry = hasEnergyTelemetryDp,
            schemaHash = computedSchemaHash,
            titleHint = name
        )
        if (devId.isNotBlank()) {
            CapabilityClassifier.recordResolution(devId, classification, resolveAppContext())
        }
        val suggestedType = classification.suggestedTileType

        if (isLytmiDevice) {
            if (resolvedSwitchDpId == null) {
                when {
                    dpsMap["20"] is Boolean -> {
                        resolvedSwitchDpId = "20"
                        switchCode = "switch_led"
                        isActive = dpsMap["20"] as Boolean
                    }
                    dpsMap["1"] is Boolean -> {
                        resolvedSwitchDpId = "1"
                        switchCode = "switch"
                        isActive = dpsMap["1"] as Boolean
                    }
                }
                resolvedSwitchDpId?.let { switchDpIdByDevice[devId] = it }
            }
            val hasFullLytmiCaps = discoveredCapabilities.any {
                it.key.lowercase(Locale.US) in setOf("work_mode", "sync_mode", "hdmi_input", "scene_select")
            }
            if (!hasFullLytmiCaps) {
                val defaults = defaultLytmiCapabilities(
                    isActive = isActive,
                    hdmiInput = lytmiHdmi,
                    syncMode = lytmiMode,
                    sceneName = lytmiScene,
                    diffusionSpeed = lytmiSpeed
                ).map { cap ->
                    if (cap.key == "switch_led" && resolvedSwitchDpId != null) {
                        cap.copy(dpId = resolvedSwitchDpId!!, key = switchCode)
                    } else cap
                }
                val defaultKeys = defaults.map { it.key.lowercase(Locale.US) }.toSet()
                val extras = discoveredCapabilities.filter {
                    val k = it.key.lowercase(Locale.US)
                    k !in defaultKeys && k !in setOf("switch", "switch_1", "switch1", "switch_led")
                }
                discoveredCapabilities.clear()
                discoveredCapabilities.addAll(defaults + extras)
            }
        }

        if (devId.isNotBlank()) {
            capabilitiesByDevice[devId] = discoveredCapabilities
            hasPowerTelemetryByDevice[devId] = hasEnergyTelemetryDp
        }

        val isMultiGang = hasSecondSocketDp && !isVacuumDevice && !isLytmiDevice && !isTowerFanDevice
        val effectiveSocketCount = if (isMultiGang) 2 else 1
        val effectiveLevelOrSocket2 = if (isMultiGang) {
            if (socket2Active) 100f else 0f
        } else {
            levelOrBattery
        }

        val summarySignature = "$suggestedType|$effectiveSocketCount|$isActive|$socket2Active|${powerWatts}W|${voltageVolts}V|caps=${discoveredCapabilities.size}"
        if (lastLoggedSummaryByDevice.put(devId, summarySignature) != summarySignature) {
            Log.d(
                TAG,
                "Resolved [$name / $devId] type=$suggestedType sockets=$effectiveSocketCount caps=${discoveredCapabilities.size} powerSupported=$hasEnergyTelemetryDp: " +
                        "switch1=${switchDpIdByDevice[devId]}($isActive), switch2=${switch2DpIdByDevice[devId]}($socket2Active), " +
                        "power=${powerWatts}W, voltage=${voltageVolts}V"
            )
        }

        return DiscoveredTuyaDevice(
            deviceId = devId,
            name = name,
            category = category,
            localKey = localKey,
            isOnline = isOnline,
            suggestedType = suggestedType,
            isActive = isActive,
            isSocket2Active = if (isMultiGang) socket2Active else false,
            multiGangSocketCount = effectiveSocketCount,
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
            capabilities = discoveredCapabilities,
            hasPowerTelemetry = hasEnergyTelemetryDp,
            lytmiHdmiInput = lytmiHdmi,
            lytmiSyncMode = lytmiMode,
            lytmiSceneName = lytmiScene,
            lytmiDiffusionSpeed = lytmiSpeed,
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
    }

    // -----------------------------------------------------------------------
    // PURE LOCAL LAN AES-128-ECB SOCKET ENGINE (PORT 6668) + HEARTBEAT DAEMON
    // - Frames Tuya v3.3 binary packets (0x000055AA ... CRC32 + 0x0000AA55)
    //   encrypted with the device's 16-byte localKey for sub-15ms execution.
    // - Transmits 0x00000009 HEART_BEAT frames every 10s so idle sockets stay open.
    // -----------------------------------------------------------------------
    private fun buildTuyaPacket(
        commandType: Int,
        encryptedPayload: ByteArray
    ): ByteArray {
        val bodyLength = encryptedPayload.size + 8
        val totalLength = 16 + encryptedPayload.size + 8
        val buf = ByteBuffer.allocate(totalLength)
        buf.putInt(0x000055AA)
        buf.putInt(lanSeqCounter.getAndIncrement())
        buf.putInt(commandType)
        buf.putInt(bodyLength)
        if (encryptedPayload.isNotEmpty()) {
            buf.put(encryptedPayload)
        }
        val crc = CRC32()
        crc.update(buf.array(), 0, 16 + encryptedPayload.size)
        buf.putInt(crc.value.toInt())
        buf.putInt(0x0000AA55.toInt())
        return buf.array()
    }

    private fun buildTuyaLanControlPacket(
        deviceId: String,
        localKey: String,
        dpsPayload: JSONObject,
        version: String,
        sessionKey: ByteArray?
    ): ByteArray? {
        if (localKey.length != 16) return null
        return try {
            val nowSec = (System.currentTimeMillis() / 1000L).toString()
            val rootJson = JSONObject().apply {
                put("devId", deviceId)
                put("uid", deviceId)
                put("t", nowSec)
                put("dps", dpsPayload)
            }.toString()
            val payloadBytes = rootJson.toByteArray(Charsets.UTF_8)

            val encryptedPayload = when (version) {
                "3.4" -> {
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    val iv = ByteArray(16).apply { SecureRandom().nextBytes(this) }
                    val keyToUse = sessionKey ?: localKey.toByteArray(Charsets.UTF_8)
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyToUse, "AES"), javax.crypto.spec.IvParameterSpec(iv))
                    val enc = cipher.doFinal(payloadBytes)
                    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                    mac.init(SecretKeySpec(keyToUse, "HmacSHA256"))
                    val headerData = "3.4".toByteArray(Charsets.UTF_8) + ByteArray(12)
                    val hmac = mac.doFinal(headerData + iv + enc)
                    headerData + iv + enc + hmac
                }
                "3.5" -> {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
                    val keyToUse = sessionKey ?: localKey.toByteArray(Charsets.UTF_8)
                    val spec = javax.crypto.spec.GCMParameterSpec(128, iv)
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyToUse, "AES"), spec)
                    val headerData = "3.5".toByteArray(Charsets.UTF_8) + ByteArray(12)
                    cipher.updateAAD(headerData)
                    val enc = cipher.doFinal(payloadBytes)
                    headerData + iv + enc
                }
                else -> {
                    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(localKey.toByteArray(Charsets.UTF_8), "AES"))
                    "3.3".toByteArray(Charsets.UTF_8) + ByteArray(12) + cipher.doFinal(payloadBytes)
                }
            }

            buildTuyaPacket(
                commandType = TUYA_CMD_CONTROL,
                encryptedPayload = encryptedPayload
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "buildTuyaLanControlPacket",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            Log.w(TAG, "buildTuyaLanControlPacket note: ${e.message}")
            null
        }
    }

    private fun decodeUnsolicitedDpStatus(
        deviceId: String,
        payloadBytes: ByteArray,
        localKey: String,
        sessionKey: ByteArray?,
        version: String
    ) {
        try {
            val decryptedStr = when (version) {
                "3.4" -> {
                    val keyToUse = sessionKey ?: localKey.toByteArray(Charsets.UTF_8)
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    val iv = payloadBytes.copyOfRange(15, 31)
                    val cipherText = payloadBytes.copyOfRange(31, payloadBytes.size - 32)
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyToUse, "AES"), javax.crypto.spec.IvParameterSpec(iv))
                    String(cipher.doFinal(cipherText), Charsets.UTF_8)
                }
                "3.5" -> {
                    val keyToUse = sessionKey ?: localKey.toByteArray(Charsets.UTF_8)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val iv = payloadBytes.copyOfRange(15, 27)
                    val cipherText = payloadBytes.copyOfRange(27, payloadBytes.size)
                    val spec = javax.crypto.spec.GCMParameterSpec(128, iv)
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyToUse, "AES"), spec)
                    cipher.updateAAD(payloadBytes.copyOfRange(0, 15))
                    String(cipher.doFinal(cipherText), Charsets.UTF_8)
                }
                else -> {
                    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(localKey.toByteArray(Charsets.UTF_8), "AES"))
                    val cipherText = payloadBytes.copyOfRange(15, payloadBytes.size)
                    String(cipher.doFinal(cipherText), Charsets.UTF_8)
                }
            }
            
            val json = JSONObject(decryptedStr.trim())
            activeDeviceListeners[deviceId]?.onDpUpdate(deviceId, json.toString())
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "decodeUnsolicitedDpStatus",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            Log.w(TAG, "decodeUnsolicitedDpStatus error: ${e.message}")
        }
    }

    private fun negotiateSessionKeyAndStartReader(session: PersistentTuyaLanSocket, localKey: String) {
        val version = resolveProtocolVersion(session.deviceId).ifBlank { "3.3" }
        session.protocolVersion = version

        if (version == "3.4" || version == "3.5") {
            try {
                val clientNonce = ByteArray(16).apply { SecureRandom().nextBytes(this) }
                val packet = buildTuyaPacket(
                    commandType = 0x00000003,
                    encryptedPayload = clientNonce
                )
                session.out.write(packet)
                session.out.flush()

                val header = ByteArray(16)
                session.input.readFully(header)
                val bodyLen = ByteBuffer.wrap(header, 12, 4).int
                val remaining = ByteArray(bodyLen)
                session.input.readFully(remaining)
                
                val payload = remaining.copyOfRange(4, remaining.size - 4)
                if (payload.size >= 48) {
                    val deviceNonce = payload.copyOfRange(0, 16)
                    
                    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                    mac.init(SecretKeySpec(localKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
                    
                    val sessionKey = mac.doFinal(clientNonce + deviceNonce).copyOfRange(0, 16)
                    session.sessionKey = sessionKey
                    sessionKeyByDevice[session.deviceId] = sessionKey

                    val finishPayload = mac.doFinal(deviceNonce)
                    val finishPacket = buildTuyaPacket(
                        commandType = 0x00000005,
                        encryptedPayload = finishPayload
                    )
                    session.out.write(finishPacket)
                    session.out.flush()
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = TAG,
                    operationStage = "negotiateSessionKeyAndStartReader_handshake",
                    logToDiscoveryRecorder = true,
                    surfaceToUiBanner = true
                )
                Log.w(TAG, "Handshake failed: ${e.message}")
            }
        }

        session.readerJob = PlayerBackgroundScope.launch(Dispatchers.IO) {
            val header = ByteArray(16)
            while (isActive && session.isAlive()) {
                try {
                    session.input.readFully(header, 0, 16)
                    val prefix = ByteBuffer.wrap(header, 0, 4).int
                    val bodyLen = ByteBuffer.wrap(header, 12, 4).int
                    if (prefix != 0x000055AA || bodyLen < 8 || bodyLen > 16384) continue
                    
                    val remaining = ByteArray(bodyLen)
                    session.input.readFully(remaining)
                    val suffix = ByteBuffer.wrap(remaining, bodyLen - 4, 4).int
                    if (suffix == 0x0000AA55.toInt()) {
                        session.lastActivityEpochMs = System.currentTimeMillis()
                        val cmdType = ByteBuffer.wrap(header, 8, 4).int
                        if (cmdType == 0x00000008 || cmdType == 0x0000000A) {
                            val payloadBytes = remaining.copyOfRange(4, remaining.size - 4)
                            decodeUnsolicitedDpStatus(session.deviceId, payloadBytes, localKey, session.sessionKey, version)
                        }
                    }
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                        t = e,
                        transportSource = TAG,
                        operationStage = "negotiateSessionKeyAndStartReader_readLoop",
                        logToDiscoveryRecorder = true,
                        surfaceToUiBanner = true
                    )
                    session.closeQuietly()
                    break
                }
            }
        }
    }

    private fun ensureLanHeartbeatDaemonRunning() {
        if (lanHeartbeatDaemonJob?.isActive == true) return
        lanHeartbeatDaemonJob = PlayerBackgroundScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10_000L)
                if (lanSocketPoolByIp.isEmpty()) continue
                val now = System.currentTimeMillis()
                for ((ip, session) in lanSocketPoolByIp.entries.toList()) {
                    if (!session.isAlive()) {
                        lanSocketPoolByIp.remove(ip)?.closeQuietly()
                        continue
                    }
                    if (now - session.lastActivityEpochMs >= 9_500L) {
                        val hbOk = synchronized(session.lock) {
                            try {
                                val hbFrame = buildTuyaPacket(
                                    commandType = TUYA_CMD_HEART_BEAT,
                                    encryptedPayload = ByteArray(0)
                                )
                                session.out.write(hbFrame)
                                session.out.flush()
                                session.lastActivityEpochMs = System.currentTimeMillis()
                                if (session.deviceId.isNotBlank()) {
                                    markDeviceSeenOnline(session.deviceId)
                                }
                                true
                            } catch (e: Throwable) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                                    t = e,
                                    transportSource = TAG,
                                    operationStage = "ensureLanHeartbeatDaemonRunning",
                                    logToDiscoveryRecorder = true,
                                    surfaceToUiBanner = true
                                )
                                false
                            }
                        }
                        if (!hbOk) lanSocketPoolByIp.remove(ip)?.closeQuietly()
                    }
                }
            }
        }
    }

    private fun getOrOpenPersistentLanSocket(
        ipAddress: String,
        deviceId: String = ""
    ): PersistentTuyaLanSocket? {
        val cleanIp = ipAddress.substringBefore(":").trim()
        if (cleanIp.isBlank()) return null
        lanSocketPoolByIp[cleanIp]?.let { existing ->
            if (existing.isAlive()) return existing
            lanSocketPoolByIp.remove(cleanIp)?.closeQuietly()
        }
        return try {
            val raw = Socket()
            raw.tcpNoDelay = true
            raw.keepAlive = true
            raw.soTimeout = 0
            raw.connect(InetSocketAddress(cleanIp, TUYA_LAN_PORT), 550)
            val input = DataInputStream(raw.getInputStream())
            val out = DataOutputStream(raw.getOutputStream())
            SmartHomeApiClient.registerVerifiedLanNeighbor(cleanIp, deviceId)
            ensureLanHeartbeatDaemonRunning()
            PersistentTuyaLanSocket(
                ipAddress = cleanIp,
                deviceId = deviceId,
                socket = raw,
                input = input,
                out = out
            ).also {
                val localKey = resolveKnownLocalKey(deviceId)
                negotiateSessionKeyAndStartReader(it, localKey)
                lanSocketPoolByIp[cleanIp] = it
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "getOrOpenPersistentLanSocket",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            null
        }
    }

    private fun writePacketOnPersistentLanSocket(
        ipAddress: String,
        packet: ByteArray,
        deviceId: String = ""
    ): Boolean {
        val cleanIp = ipAddress.substringBefore(":").trim()
        if (cleanIp.isBlank()) return false
        for (attempt in 0..1) {
            val session = getOrOpenPersistentLanSocket(cleanIp, deviceId) ?: return false
            val acked = synchronized(session.lock) {
                try {
                    session.out.write(packet)
                    session.out.flush()
                    session.lastActivityEpochMs = System.currentTimeMillis()
                    true
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                        t = e,
                        transportSource = TAG,
                        operationStage = "writePacketOnPersistentLanSocket",
                        logToDiscoveryRecorder = true,
                        surfaceToUiBanner = true
                    )
                    false
                }
            }
            if (acked) {
                SmartHomeApiClient.registerVerifiedLanNeighbor(cleanIp, deviceId)
                return true
            }
            lanSocketPoolByIp.remove(cleanIp)?.closeQuietly()
        }
        return false
    }

    suspend fun sendLocalLanDpCommand(
        deviceId: String,
        ipAddress: String = "",
        localKey: String = "",
        dpsPayload: JSONObject
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanId = deviceId.trim()
        if (cleanId.isBlank() || dpsPayload.length() == 0) return@withContext false
        val startMs = System.currentTimeMillis()

        val resolvedIp = ipAddress.substringBefore(":").trim()
            .ifBlank { resolveKnownLanIp(cleanId) }
        val resolvedKey = localKey.trim()
            .ifBlank { resolveKnownLocalKey(cleanId) }
            .ifBlank {
                if (ClaraApplication.isTuyaSdkReady()) {
                    runCatching { ThingHomeSdk.getDataInstance().getDeviceBean(cleanId)?.localKey.orEmpty() }
                        .getOrDefault("")
                } else ""
            }
        if (resolvedIp.isNotBlank() || resolvedKey.length == 16) {
            registerDeviceLanAddress(cleanId, resolvedIp, resolvedKey)
        }

        // 1. Primary Path: Use ThingHomeSdk's official C++ LAN (v3.3 / v3.4 / v3.5) + MQTT/HTTP engine
        //    with real ACK callbacks, and close any competing raw socket on port 6668 so the plug never drops frames.
        if (ClaraApplication.isTuyaSdkReady()) {
            if (resolvedIp.isNotBlank()) {
                lanSocketPoolByIp.remove(resolvedIp)?.closeQuietly()
            }
            val sdkOk = publishNativeDpsMap(cleanId, dpsPayload)
            if (sdkOk) {
                recordDeviceCommandOutcome(cleanId, true)
                val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(5L)
                JarvisLiveBridge.recordCommandLatency(
                    integrationKey = "TUYA_LAN",
                    displayName = "Tuya / SmartKnight / Lytmi LAN",
                    latencyMs = latency,
                    succeeded = true,
                    statusMessage = "Verified Tuya SDK LAN/MQTT (${latency}ms)"
                )
                return@withContext true
            }
        }

        if (resolvedKey.isBlank()) {
            throw Exception("OWNERSHIP_CONFLICT: Device is bound to another user account. Reset the device to factory defaults.")
        }

        // 2. Pure-Offline Raw AES-ECB Fallback strictly for legacy v3.3 devices when ThingHomeSdk is unavailable
        val version = resolveProtocolVersion(cleanId).ifBlank { "3.3" }
        if (version == "3.3" && resolvedIp.isNotBlank() && resolvedKey.length == 16) {
            val packet = buildTuyaLanControlPacket(cleanId, resolvedKey, dpsPayload, "3.3", null)
            if (packet != null && writePacketOnPersistentLanSocket(resolvedIp, packet, cleanId)) {
                lastCommandTimestampMs = System.currentTimeMillis()
                recordDeviceCommandOutcome(cleanId, true)
                val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(2L)
                JarvisLiveBridge.recordCommandLatency(
                    integrationKey = "TUYA_LAN",
                    displayName = "Tuya / SmartKnight / Lytmi LAN",
                    latencyMs = latency,
                    succeeded = true,
                    statusMessage = "Direct LAN 6668 v3.3 (${latency}ms)"
                )
                return@withContext true
            }
        }

        recordDeviceCommandOutcome(cleanId, false)
        val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(10L)
        JarvisLiveBridge.recordCommandLatency(
            integrationKey = "TUYA_LAN",
            displayName = "Tuya / SmartKnight / Lytmi LAN",
            latencyMs = latency,
            succeeded = false
        )
        false
    }

    /**
     * Point 12: Dedicated Pro Breeze Smart Tower Fan (`fs` / `fskg`) command dispatcher.
     * Fires immediately over the Persistent TCP 6668 LAN Socket Pool, then falls back to
     * Native ThingHomeSdk and Tuya Cloud OpenAPI.
     */
    suspend fun sendNativeTowerFanCommand(
        bridge: BridgeSettings,
        tile: SmartTile,
        turnOn: Boolean = tile.isActive,
        speedLevel: Int = tile.fanSpeedLevel,
        fanMode: String = tile.fanMode,
        oscillating: Boolean = tile.fanOscillating,
        ionizerActive: Boolean = tile.fanIonizerActive,
        timerHours: Int = tile.fanTimerHours
    ): Boolean = withContext(Dispatchers.IO) {
        val devId = tile.serialNumber.ifBlank { tile.entityId }.trim()
        val ip = tile.ipAddress.substringBefore(":").trim()
        val key = tile.secretCode.trim()

        if (devId.isNotBlank() && ip.isNotBlank()) {
            registerDeviceLanAddress(devId, ip, key)
        }

        val switchDp = switchDpIdByDevice[devId] ?: findDpId(devId, "switch", "fan_switch", "power", "switch_fan") ?: "1"
        val modeDp = findDpId(devId, "mode", "fan_mode", "work_mode") ?: "2"
        val speedDp = findDpId(devId, "fan_speed", "fan_speed_percent", "windspeed", "speed", "level") ?: "3"
        val oscDp = findDpId(devId, "switch_horizontal", "switch_vertical", "shake", "swing", "oscillate") ?: "4"
        val ionDp = findDpId(devId, "anion", "ionizer", "fresh", "purifier") ?: "5"
        val timerCap = findCapability(devId, "countdown_set", "countdown", "countdown_1", "timer")
        val timerDp = timerCap?.dpId?.takeIf { it.isNotBlank() } ?: findDpId(devId, "countdown_set", "countdown", "countdown_1", "timer") ?: "6"
        val timerInMinutes = timerCap != null && (timerCap.unit.contains("min", true) || timerCap.maxValue > 24f)

        val modeUpper = fanMode.uppercase(Locale.US)
        val tuyaFanMode = when (modeUpper) {
            "NATURAL", "NATURE" -> "nature"
            "SLEEP" -> "sleep"
            "AUTO", "SMART" -> "smart"
            else -> "normal"
        }
        val clampedSpeed = speedLevel.coerceIn(1, 6)
        val clampedTimerHours = timerHours.coerceIn(0, 12)
        val timerVal: Any = if (timerInMinutes) clampedTimerHours * 60 else "${clampedTimerHours}h"

        val dpsPayload = JSONObject().apply {
            put(switchDp, turnOn)
            if (turnOn) {
                put(modeDp, tuyaFanMode)
                put(speedDp, clampedSpeed)
                put(oscDp, oscillating)
                put(ionDp, ionizerActive)
                if (clampedTimerHours > 0) {
                    put(timerDp, if (timerInMinutes) clampedTimerHours * 60 else clampedTimerHours)
                }
            }
        }

        if (devId.isNotBlank() && !devId.contains(".")) {
            val lanOrSdkOk = sendLocalLanDpCommand(
                deviceId = devId,
                ipAddress = ip,
                localKey = key,
                dpsPayload = dpsPayload
            )
            if (lanOrSdkOk) return@withContext true

            val cmds = JSONArray().apply {
                put(JSONObject().put("code", switchDpCodeByDevice[devId] ?: "switch").put("value", turnOn))
                if (turnOn) {
                    put(JSONObject().put("code", "mode").put("value", tuyaFanMode))
                    put(JSONObject().put("code", "fan_speed").put("value", clampedSpeed))
                    put(JSONObject().put("code", "switch_horizontal").put("value", oscillating))
                    put(JSONObject().put("code", "anion").put("value", ionizerActive))
                    if (clampedTimerHours > 0) {
                        put(JSONObject().put("code", "countdown_set").put("value", timerVal))
                    }
                }
            }
            if (sendCommands(bridge, devId, cmds)) return@withContext true
        }
        false
    }

    /**
     * Point 3: Dispatches Lytmi HDMI 2.1 Sync Box & Ambient Backlight controls (Power, HDMI 1-4,
     * Sync Mode [Video/Movie/Game/Music/Scene/Color], 360° RGB Colour Wheel HSV, Ambient Scene,
     * Diffusion Speed & Brightness) over local port 6668 AES socket first, then Native SDK / Cloud.
     */
    suspend fun sendLytmiSyncCommand(
        bridge: BridgeSettings,
        tile: SmartTile,
        turnOn: Boolean = tile.isActive,
        hdmiInput: Int = tile.lytmiHdmiInput,
        syncMode: String = tile.lytmiSyncMode,
        sceneName: String = tile.lytmiSceneName,
        diffusionSpeed: Float = tile.lytmiDiffusionSpeed,
        brightnessPct: Float = tile.volume,
        colorArgb: Long = tile.lytmiColorArgb
    ): Boolean = withContext(Dispatchers.IO) {
        var devId = tile.serialNumber.ifBlank { tile.entityId }.ifBlank { bridge.lytmiDeviceId }.trim()
        if (devId.isBlank() || devId.contains(".")) {
            devId = resolveDeviceIdForTile(bridge, tile)
        }
        val ip = tile.ipAddress.ifBlank { bridge.lytmiIpAddress }.substringBefore(":").trim()
        val key = tile.secretCode.ifBlank { bridge.lytmiLocalKey }.trim()
        if (devId.isNotBlank() && ip.isNotBlank()) {
            registerDeviceLanAddress(devId, ip, key)
        }
        if (devId.isBlank()) return@withContext false

        // Ensure ThingHomeSdk DeviceBean is loaded if SDK is initialized
        var bean = if (ClaraApplication.isTuyaSdkReady()) {
            runCatching { ThingHomeSdk.getDataInstance().getDeviceBean(devId) }.getOrNull()
        } else null
        if (bean == null && ClaraApplication.isTuyaSdkReady()) {
            discoverNativeSdkDevices(forceRefresh = false)
            bean = runCatching { ThingHomeSdk.getDataInstance().getDeviceBean(devId) }.getOrNull()
        }
        if (bean != null) {
            convertDeviceBeanToDiscovered(bean)
        }
        val beanDps = runCatching { bean?.dps ?: emptyMap() }.getOrDefault(emptyMap())
        val beanSchema = runCatching { bean?.schemaMap ?: emptyMap() }.getOrDefault(emptyMap())

        // 1. Resolve actual power switch DP IDs (Neo3 / Tuya Sync Boxes use DP "20" switch_led or DP "1" switch)
        val candidatePowerDps = buildList {
            switchDpIdByDevice[devId]?.takeIf { it.isNotBlank() }?.let { add(it) }
            beanSchema.entries.forEach { (dpId, sc) ->
                val c = sc.code?.lowercase(Locale.US).orEmpty()
                if (c in setOf("switch_led", "switch", "switch_1", "power", "led_switch", "sync_switch")) {
                    add(dpId)
                }
            }
            if (beanDps["20"] is Boolean) add("20")
            if (beanDps["1"] is Boolean) add("1")
            beanDps.entries.firstOrNull { it.value is Boolean }?.key?.let { add(it) }
            add("20")
            add("1")
        }.distinct()

        // Always send Power ON/OFF as an isolated DP command first so unsupported secondary DPs never reject the frame
        var powerAcked = false
        for (pDp in candidatePowerDps) {
            if (beanDps.isNotEmpty() && !beanDps.containsKey(pDp) && !beanSchema.containsKey(pDp) && powerAcked) {
                continue
            }
            val ok = sendLocalLanDpCommand(
                deviceId = devId,
                ipAddress = ip,
                localKey = key,
                dpsPayload = JSONObject().put(pDp, turnOn)
            )
            if (ok) {
                switchDpIdByDevice[devId] = pDp
                powerAcked = true
                break
            }
        }

        if (!turnOn) {
            if (!powerAcked) {
                val offCode = switchDpCodeByDevice[devId] ?: "switch_led"
                powerAcked = sendCommands(
                    bridge,
                    devId,
                    JSONArray().put(JSONObject().put("code", offCode).put("value", false))
                )
            }
            return@withContext powerAcked
        }

        // 2. When turning ON or adjusting mode/colour/HDMI, only send DPs verified in the device's schema
        val modeUpper = syncMode.uppercase(Locale.US)
        val tuyaWorkMode = when (modeUpper) {
            "COLOR", "COLOUR" -> "colour"
            "MUSIC" -> "music"
            "SCENE" -> "scene"
            "GAME" -> "game"
            "VIDEO", "MOVIE" -> "video"
            else -> syncMode.lowercase(Locale.US)
        }
        val hsvHex12 = encodeTuyaHsvHex12(colorArgb, brightnessPct)
        val secondaryPayload = JSONObject()

        val modeDp = findDpId(devId, "work_mode", "mode", "sync_mode") ?: if (beanDps.containsKey("21") || beanSchema.containsKey("21")) "21" else null
        if (modeDp != null) {
            val allowedModes = capabilitiesByDevice[devId]?.firstOrNull { it.dpId == modeDp }?.enumOptions.orEmpty()
            val matchedMode = if (allowedModes.isNotEmpty()) {
                allowedModes.firstOrNull { it.equals(tuyaWorkMode, true) || it.equals(modeUpper, true) }
                    ?: allowedModes.firstOrNull {
                        (modeUpper in listOf("VIDEO", "MOVIE", "GAME") && (it.contains("video", true) || it.contains("sync", true) || it.contains("dream", true))) ||
                                (modeUpper in listOf("COLOR", "COLOUR") && (it.contains("colour", true) || it.contains("color", true) || it.contains("white", true))) ||
                                (modeUpper == "MUSIC" && it.contains("music", true)) ||
                                (modeUpper == "SCENE" && it.contains("scene", true))
                    }
            } else {
                tuyaWorkMode
            }
            if (!matchedMode.isNullOrBlank()) {
                secondaryPayload.put(modeDp, matchedMode)
            }
        }

        val colourDp = findDpId(devId, "colour_data_v2", "colour_data", "color_data") ?: if (beanDps.containsKey("24") || beanSchema.containsKey("24")) "24" else null
        if (colourDp != null && modeUpper in listOf("COLOR", "COLOUR")) {
            secondaryPayload.put(colourDp, hsvHex12)
        }

        val briCap = findCapability(devId, "bright_value", "bright_value_v2", "brightness")
        val briDp = briCap?.dpId ?: if (beanDps.containsKey("22") || beanSchema.containsKey("22")) "22" else null
        if (briDp != null) {
            val maxBri = briCap?.maxValue ?: 1000f
            val scaledBri = if (maxBri > 100f) {
                (brightnessPct * 10f).roundToInt().coerceIn(10, 1000)
            } else {
                brightnessPct.roundToInt().coerceIn(1, 100)
            }
            secondaryPayload.put(briDp, scaledBri)
        }

        val hdmiDp = findDpId(devId, "hdmi_input", "input_source", "hdmi")
        if (hdmiDp != null && (beanDps.containsKey(hdmiDp) || beanSchema.containsKey(hdmiDp))) {
            val existingHdmiVal = beanDps[hdmiDp]
            val formattedHdmi: Any = when (existingHdmiVal) {
                is Number -> hdmiInput.coerceIn(1, 4)
                is String -> if (existingHdmiVal.startsWith("hdmi_", true)) "hdmi_${hdmiInput.coerceIn(1, 4)}" else hdmiInput.coerceIn(1, 4).toString()
                else -> "hdmi_${hdmiInput.coerceIn(1, 4)}"
            }
            secondaryPayload.put(hdmiDp, formattedHdmi)
        }

        val speedDp = findDpId(devId, "diffusion_speed", "speed", "sensitivity")
        if (speedDp != null && (beanDps.containsKey(speedDp) || beanSchema.containsKey(speedDp))) {
            secondaryPayload.put(speedDp, diffusionSpeed.roundToInt().coerceIn(1, 100))
        }

        var secondaryAcked = false
        if (secondaryPayload.length() > 0) {
            secondaryAcked = sendLocalLanDpCommand(
                deviceId = devId,
                ipAddress = ip,
                localKey = key,
                dpsPayload = secondaryPayload
            )
        }

        if (!powerAcked && !secondaryAcked) {
            val swCode = switchDpCodeByDevice[devId] ?: "switch_led"
            val cmds = JSONArray().put(JSONObject().put("code", swCode).put("value", turnOn))
            powerAcked = sendCommands(bridge, devId, cmds)
        }

        JarvisLiveBridge.emitPlatformEvent(
            JarvisPlatformEvent(
                category = JarvisEventCategory.LYTMI_SYNC,
                sourceDeviceId = tile.id,
                sourceDeviceTitle = tile.title,
                roomName = tile.roomOrSubtitle,
                eventType = "LYTMI_UPDATED",
                summary = "${tile.title}: ${if (turnOn) "HDMI $hdmiInput \u2022 $syncMode" else "OFF"}"
            )
        )
        powerAcked || secondaryAcked
    }

    private fun buildUpdatedTileFromLiveBean(current: SmartTile, live: DiscoveredTuyaDevice): SmartTile {
        val mergedCaps = if (live.capabilities.isNotEmpty()) {
            val pinnedByKey = current.capabilities.associate { it.key to (it.isPinnedToHero to it.label) }
            live.capabilities.map { c ->
                val prev = pinnedByKey[c.key]
                if (prev != null) c.copy(isPinnedToHero = prev.first, label = prev.second) else c
            }
        } else current.capabilities

        val newSchemaHash = CapabilitySchemaHasher.computeHash(mergedCaps)
        val shouldReevaluate = current.classificationSource != ClassificationSource.MANUAL_OVERRIDE &&
                (current.classificationVersion < CapabilityClassifier.CURRENT_VERSION ||
                        current.capabilitySchemaHash != newSchemaHash ||
                        CapabilityClassifier.getResolution(live.deviceId) == null)

        val effectiveTitleHint = current.title.ifBlank { live.name }
        val resolution = if (shouldReevaluate) {
            CapabilityClassifier.resolve(
                capabilities = mergedCaps,
                categoryCode = live.category,
                multiGangSocketCount = live.multiGangSocketCount,
                hasPowerTelemetry = live.hasPowerTelemetry,
                schemaHash = newSchemaHash,
                titleHint = effectiveTitleHint
            ).also { res ->
                CapabilityClassifier.recordResolution(live.deviceId, res, resolveAppContext())
            }
        } else {
            CapabilityClassifier.getResolution(live.deviceId)
                ?: CapabilityClassifier.resolve(
                    capabilities = mergedCaps,
                    categoryCode = live.category,
                    multiGangSocketCount = live.multiGangSocketCount,
                    hasPowerTelemetry = live.hasPowerTelemetry,
                    schemaHash = newSchemaHash,
                    titleHint = effectiveTitleHint
                )
        }

        val preserveManualOverride = current.classificationSource == ClassificationSource.MANUAL_OVERRIDE
        val effectiveClass = if (preserveManualOverride) current.deviceClass else resolution.deviceClass
        val effectiveSubtype = if (preserveManualOverride) current.deviceSubtype else resolution.deviceSubtype
        val effectiveConfidence = if (preserveManualOverride) current.classificationConfidence else resolution.confidence
        val effectiveSource = if (preserveManualOverride) ClassificationSource.MANUAL_OVERRIDE else resolution.source
        val resolvedProviderId = current.activeProviderId.ifBlank {
            if (localIpByDevice.containsKey(live.deviceId)) "TUYA_LAN_6668" else "TUYA_CLOUD"
        }

        val baseStamped = current.copy(
            isOnline = live.isOnline,
            capabilities = mergedCaps,
            capabilitySchemaHash = newSchemaHash,
            deviceClassOverride = effectiveClass,
            deviceSubtypeOverride = effectiveSubtype,
            classificationConfidence = effectiveConfidence,
            classificationSource = effectiveSource,
            classificationVersion = resolution.version,
            classificationLastEvaluatedEpochMs = resolution.evaluatedAtEpochMs,
            activeProviderId = resolvedProviderId,
            stateAuthorityTier = StateAuthorityTier.DEVICE_HARDWARE_TRUTH
        )

        return when (effectiveSubtype) {
            DeviceSubtype.VACUUM -> baseStamped.copy(
                isActive = live.isActive,
                volume = live.brightnessOrBatteryPct
            )
            DeviceSubtype.TOWER_FAN -> baseStamped.copy(
                isActive = live.isActive,
                volume = live.brightnessOrBatteryPct,
                fanSpeedLevel = live.fanSpeedLevel,
                fanMode = live.fanMode,
                fanOscillating = live.fanOscillating,
                fanIonizerActive = live.fanIonizerActive,
                fanTimerHours = live.fanTimerHours
            )
            DeviceSubtype.THERMOSTAT,
            DeviceSubtype.RADIATOR,
            DeviceSubtype.TRV -> {
                val nextCurTemp = if (live.currentTempC > 0f) live.currentTempC else current.temperature
                val nextTgtTemp = if (live.targetTempC > 0f) live.targetTempC else current.targetTemperature
                baseStamped.copy(
                    temperature = nextCurTemp,
                    targetTemperature = nextTgtTemp,
                    isActive = live.isActive,
                    hvacMode = if (live.isActive) "HEAT" else "OFF",
                    hvacStatus = if (live.isActive && nextTgtTemp > nextCurTemp) "HEATING" else "OFF"
                )
            }
            DeviceSubtype.AMBIENT_SYNC -> baseStamped.copy(
                isActive = live.isActive,
                volume = live.brightnessOrBatteryPct,
                lytmiHdmiInput = live.lytmiHdmiInput,
                lytmiSyncMode = live.lytmiSyncMode,
                lytmiSceneName = live.lytmiSceneName,
                lytmiDiffusionSpeed = live.lytmiDiffusionSpeed
            )
            DeviceSubtype.SMART_PLUG,
            DeviceSubtype.WALL_SOCKET,
            DeviceSubtype.RELAY -> {
                val inferredCategory = AppliancePowerFingerprinter.inferApplianceFromTelemetry(
                    powerWatts = live.powerWatts,
                    titleHint = current.title,
                    previous = current.detectedApplianceCategory
                )
                val autoSocket1Name = if (
                    current.socket1Name == "Socket 1" &&
                    inferredCategory != ApplianceFingerprintCategory.UNKNOWN &&
                    inferredCategory.suggestedSocketName.isNotBlank() &&
                    live.isActive &&
                    (!live.isSocket2Active || live.multiGangSocketCount == 1)
                ) {
                    inferredCategory.suggestedSocketName
                } else {
                    current.socket1Name
                }
                baseStamped.copy(
                    isActive = live.isActive,
                    isSocket2Active = live.isSocket2Active,
                    multiGangSocketCount = live.multiGangSocketCount,
                    socket1Name = autoSocket1Name,
                    volume = live.brightnessOrBatteryPct,
                    powerWatts = live.powerWatts,
                    voltageVolts = live.voltageVolts,
                    currentMilliamps = live.currentMilliamps,
                    totalEnergyKwh = live.totalEnergyKwh,
                    countdown1Seconds = live.countdown1Seconds,
                    countdown2Seconds = live.countdown2Seconds,
                    hasExplicitPowerTelemetry = live.hasPowerTelemetry,
                    detectedApplianceCategory = inferredCategory
                )
            }
            else -> baseStamped.copy(
                isActive = live.isActive,
                isSocket2Active = live.isSocket2Active,
                multiGangSocketCount = live.multiGangSocketCount,
                temperature = live.currentTempC,
                targetTemperature = live.targetTempC,
                volume = live.brightnessOrBatteryPct,
                powerWatts = live.powerWatts,
                voltageVolts = live.voltageVolts,
                currentMilliamps = live.currentMilliamps,
                totalEnergyKwh = live.totalEnergyKwh,
                hasExplicitPowerTelemetry = live.hasPowerTelemetry
            )
        }
    }

    /**
     * Registers a real-time local LAN / MQTT listener on a Tuya device so physical or LAN DP updates,
     * online/offline state changes, and firmware/schema updates (`onDevInfoUpdate` — Reliability Audit #17)
     * immediately reflect on the dashboard tiles via `DeviceRepository.reconcileRemoteTileUpdate`.
     */
    internal fun ensureLiveDeviceListenerRegistered(
        devId: String,
        tiles: MutableList<SmartTile>,
        scope: CoroutineScope
    ) {
        if (devId.isBlank() || activeDeviceInstances.containsKey(devId)) return
        if (!ClaraApplication.isTuyaSdkReady()) return
        try {
            val thingDevice = ThingHomeSdk.newDeviceInstance(devId)
            activeDeviceInstances[devId] = thingDevice
            val listener = object : IDevListener {
                override fun onDpUpdate(devId: String?, dpStr: String?) {
                    if (devId.isNullOrBlank() || dpStr.isNullOrBlank()) return
                    markDeviceSeenOnline(devId)
                    scope.launch(Dispatchers.IO) {
                        val freshBean = runCatching {
                            ThingHomeSdk.getDataInstance().getDeviceBean(devId)
                        }.getOrNull()
                        val rawJson = runCatching { JSONObject(dpStr) }.getOrNull()
                        val partialDpsJson = rawJson?.optJSONObject("dps") ?: rawJson
                        val liveFromBean = freshBean?.let { convertDeviceBeanToDiscovered(it) }
                        val appCtx = resolveAppContext()
                        if (appCtx != null && (liveFromBean?.powerWatts ?: 0f) > 0f) {
                            DeviceRepository.recordTelemetryPoint(
                                context = appCtx,
                                point = TelemetryDataPoint(
                                    deviceId = devId,
                                    deviceTitle = liveFromBean?.name ?: "Smart Plug",
                                    metricKey = "power_w",
                                    value = liveFromBean?.powerWatts ?: 0f,
                                    unit = "W"
                                )
                            )
                        }
                        withContext(Dispatchers.Main) {
                            for (i in tiles.indices) {
                                val t = tiles[i]
                                if (t.entityId == devId || t.serialNumber == devId) {
                                    var candidate = if (liveFromBean != null) {
                                        buildUpdatedTileFromLiveBean(t, liveFromBean)
                                    } else {
                                        t.copy(isOnline = true)
                                    }
                                    // Apply partial DP temperature/state updates directly so single-DP LAN frames never get lost
                                    if (partialDpsJson != null && (candidate.isThermostatTile || candidate.isKitchenApplianceTile)) {
                                        val curDp = currentTempDpIdByDevice[devId]
                                        val setDp = tempSetDpIdByDevice[devId]
                                        val scale = tempScaleByDevice[devId] ?: 1f
                                        val partialCurRaw = when {
                                            curDp != null && partialDpsJson.has(curDp) -> partialDpsJson.optDouble(curDp, Double.NaN)
                                            partialDpsJson.has("4") && setDp != "4" -> partialDpsJson.optDouble("4", Double.NaN)
                                            partialDpsJson.has("2") && setDp != "2" -> partialDpsJson.optDouble("2", Double.NaN)
                                            else -> Double.NaN
                                        }
                                        if (!partialCurRaw.isNaN() && partialCurRaw > 0.0) {
                                            val curFloat = partialCurRaw.toFloat()
                                            val normalizedCur = if (!candidate.isKitchenApplianceTile && curFloat > 55f) {
                                                curFloat / 10f
                                            } else if (scale > 1f && curFloat > 55f) {
                                                curFloat / scale
                                            } else {
                                                curFloat
                                            }
                                            candidate = candidate.copy(temperature = normalizedCur)
                                        }
                                        val partialSetRaw = when {
                                            setDp != null && partialDpsJson.has(setDp) -> partialDpsJson.optDouble(setDp, Double.NaN)
                                            partialDpsJson.has("3") && curDp != "3" -> partialDpsJson.optDouble("3", Double.NaN)
                                            else -> Double.NaN
                                        }
                                        if (!partialSetRaw.isNaN() && partialSetRaw > 0.0) {
                                            val setFloat = partialSetRaw.toFloat()
                                            val normalizedSet = if (!candidate.isKitchenApplianceTile && setFloat > 55f) {
                                                setFloat / 10f
                                            } else if (scale > 1f && setFloat > 55f) {
                                                setFloat / scale
                                            } else {
                                                setFloat
                                            }
                                            candidate = candidate.copy(targetTemperature = normalizedSet)
                                        }
                                    }
                                    tiles[i] = DeviceRepository.reconcileRemoteTileUpdate(t, candidate)
                                }
                            }
                        }
                    }
                }

                override fun onRemoved(devId: String?) {
                    val clean = devId?.trim().orEmpty()
                    if (clean.isNotBlank()) {
                        activeDeviceInstances.remove(clean)
                        activeDeviceListeners.remove(clean)
                    }
                }

                override fun onStatusChanged(devId: String?, online: Boolean) {
                    if (devId.isNullOrBlank()) return
                    if (online) {
                        markDeviceSeenOnline(devId)
                        val appCtx = resolveAppContext()
                        val audioManager = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        val bridge = if (appCtx != null) DeviceRepository.loadBridge(appCtx) else null
                        scope.launch(Dispatchers.Main) {
                            for (i in tiles.indices) {
                                val t = tiles[i]
                                if (t.entityId == devId || t.serialNumber == devId) {
                                    val wasOffline = !t.isOnline
                                    if (wasOffline) {
                                        tiles[i] = t.copy(isOnline = true)
                                        if (appCtx != null && bridge != null && audioManager != null) {
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
                    } else {
                        scope.launch(Dispatchers.IO) {
                            val matchingIp = tiles.firstOrNull {
                                it.entityId == devId || it.serialNumber == devId
                            }?.ipAddress.orEmpty()
                            val stillReachableLocally = isDeviceReachableOrOnline(
                                deviceId = devId,
                                ipAddress = matchingIp,
                                reportedCloudOnline = false
                            )
                            if (!stillReachableLocally) {
                                withContext(Dispatchers.Main) {
                                    for (i in tiles.indices) {
                                        val t = tiles[i]
                                        if ((t.entityId == devId || t.serialNumber == devId) && t.isOnline) {
                                            tiles[i] = t.copy(isOnline = false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                override fun onNetworkStatusChanged(devId: String?, status: Boolean) {
                    if (!devId.isNullOrBlank() && status) {
                        markDeviceSeenOnline(devId)
                    }
                }

                override fun onDevInfoUpdate(devId: String?) {
                    val clean = devId?.trim().orEmpty()
                    if (clean.isBlank()) return
                    scope.launch(Dispatchers.IO) {
                        val freshBean = runCatching {
                            ThingHomeSdk.getDataInstance().getDeviceBean(clean)
                        }.getOrNull() ?: return@launch
                        val refreshed = convertDeviceBeanToDiscovered(freshBean)
                        withContext(Dispatchers.Main) {
                            for (i in tiles.indices) {
                                val t = tiles[i]
                                if (t.entityId == clean || t.serialNumber == clean) {
                                    val candidate = buildUpdatedTileFromLiveBean(t, refreshed)
                                    tiles[i] = DeviceRepository.reconcileRemoteTileUpdate(t, candidate)
                                }
                            }
                        }
                        JarvisLiveBridge.emitPlatformEvent(
                            JarvisPlatformEvent(
                                category = JarvisEventCategory.SYSTEM,
                                sourceDeviceId = clean,
                                sourceDeviceTitle = refreshed.name,
                                eventType = "DEV_INFO_SCHEMA_UPDATED",
                                summary = "Refreshed hardware schema & capabilities for '${refreshed.name}'"
                            )
                        )
                    }
                }
            }
            thingDevice.registerDevListener(listener)
            activeDeviceListeners[devId] = listener
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = t,
                transportSource = TAG,
                operationStage = "ensureLiveDeviceListenerRegistered",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            Log.w(TAG, "Could not register IDevListener for $devId: ${t.message}")
        }
    }

    /**
     * Reads already-cached DeviceBeans from ThingHomeSdk's local memory store on `Dispatchers.IO`
     * without triggering a slow `smartlife.m.api.batch.invoke` cloud round-trip.
     */
    private suspend fun readFastCachedNativeDevices(): List<DiscoveredTuyaDevice> =
        withContext(Dispatchers.IO) {
            if (!ClaraApplication.isTuyaSdkReady()) return@withContext emptyList()
            val beans = mutableListOf<DeviceBean>()
            if (cachedPrimaryHomeId > 0L) {
                val homeDevList = runCatching {
                    ThingHomeSdk.getDataInstance().getHomeDeviceList(cachedPrimaryHomeId)
                }.getOrNull()
                if (!homeDevList.isNullOrEmpty()) {
                    beans.addAll(homeDevList.filterNotNull())
                }
            }
            if (beans.isEmpty() && knownNativeDeviceIds.isNotEmpty()) {
                for (id in knownNativeDeviceIds) {
                    runCatching { ThingHomeSdk.getDataInstance().getDeviceBean(id) }
                        .getOrNull()
                        ?.let { beans.add(it) }
                }
            }
            beans.distinctBy { it.devId }.map { convertDeviceBeanToDiscovered(it) }
        }

    /**
     * Queries all devices paired to the native ThingHomeSdk.
     */
    suspend fun discoverNativeSdkDevices(forceRefresh: Boolean = false): Result<List<DiscoveredTuyaDevice>> =
        withContext(Dispatchers.IO) {
            if (!ClaraApplication.isTuyaSdkReady()) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FILTERED,
                    transportSource = "TUYA_NATIVE_SDK",
                    vendor = "ThingHomeSdk",
                    qualificationStage = "discoverNativeSdkDevices",
                    outcomeReason = "Skipped native Tuya SDK discovery: ThingHomeSdk not initialized"
                )
                return@withContext Result.success(emptyList())
            }
            nativeDiscoveryMutex.withLock {
                val now = System.currentTimeMillis()
                if (!forceRefresh &&
                    hasInitializedNativeHomeInSession &&
                    (now - lastFullNativeSyncTimestampMs) < FULL_CLOUD_SYNC_INTERVAL_MS
                ) {
                    val fastCached = readFastCachedNativeDevices()
                    if (fastCached.isNotEmpty()) {
                        for (dev in fastCached) {
                            val knownIp = localIpByDevice[dev.deviceId].orEmpty()
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.IDENTIFIED,
                                transportSource = "TUYA_NATIVE_SDK",
                                hardwareId = dev.deviceId,
                                ipAddress = knownIp,
                                port = if (knownIp.isNotBlank()) 6668 else 0,
                                vendor = "${dev.name} (${dev.category})",
                                confidenceScore = dev.classificationConfidence,
                                qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                                outcomeReason = "Loaded verified Tuya SDK device from session cache (${dev.capabilities.size} DPs)",
                                cloudId = dev.deviceId
                            )
                        }
                        return@withLock Result.success(fastCached)
                    }
                }

                val rawBeansResult = suspendCancellableCoroutine<Result<List<DeviceBean>>> { cont ->
                    TuyaDirectPairingManager.ensureSilentSessionAndHome(
                        onReady = { _ ->
                            ThingHomeSdk.getHomeManagerInstance().queryHomeList(object : IThingGetHomeListCallback {
                                override fun onSuccess(homeBeans: MutableList<HomeBean>?) {
                                    if (homeBeans.isNullOrEmpty()) {
                                        DiscoveryFlightRecorder.record(
                                            eventType = DiscoveryEventType.FILTERED,
                                            transportSource = "TUYA_NATIVE_SDK",
                                            vendor = "ThingHomeSdk",
                                            qualificationStage = "queryHomeList",
                                            outcomeReason = "ThingHomeSdk returned 0 homes for active user"
                                        )
                                        if (cont.isActive) cont.resume(Result.success(emptyList()))
                                        return
                                    }
                                    val collectedBeans = ConcurrentHashMap<String, DeviceBean>()
                                    var remaining = homeBeans.size

                                    for (home in homeBeans) {
                                        cachedPrimaryHomeId = home.homeId
                                        val homeInst = ThingHomeSdk.newHomeInstance(home.homeId)
                                        homeInst.getHomeDetail(object : IThingHomeResultCallback {
                                            override fun onSuccess(bean: HomeBean?) {
                                                PlayerBackgroundScope.launch(Dispatchers.IO) {
                                                    hasInitializedNativeHomeInSession = true
                                                    lastFullNativeSyncTimestampMs = System.currentTimeMillis()
                                                    val safeList = extractSafeDevicesFromHomeBean(bean, home.homeId)
                                                    for (devBean in safeList) {
                                                        val id = devBean.devId?.trim().orEmpty()
                                                        val beanName = devBean.name?.ifBlank { "Tuya Device" } ?: "Tuya Device"
                                                        val beanMac = CrossEcosystemIdentityCorrelator.normalizeMac(
                                                            devBean.mac ?: devBean.uuid
                                                        )
                                                        if (id.isBlank()) {
                                                            DiscoveryFlightRecorder.record(
                                                                eventType = DiscoveryEventType.FILTERED,
                                                                transportSource = "TUYA_NATIVE_SDK",
                                                                macAddress = beanMac,
                                                                vendor = beanName,
                                                                qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                                                outcomeReason = "Filtered ThingHomeSdk DeviceBean missing devId"
                                                            )
                                                            continue
                                                        }
                                                        val knownIp = localIpByDevice[id].orEmpty()
                                                        DiscoveryFlightRecorder.record(
                                                            eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                                                            transportSource = "TUYA_NATIVE_SDK",
                                                            hardwareId = id,
                                                            macAddress = beanMac,
                                                            ipAddress = knownIp,
                                                            port = if (knownIp.isNotBlank()) 6668 else 0,
                                                            vendor = beanName,
                                                            confidenceScore = 0.85f,
                                                            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                                            outcomeReason = "ThingHomeSdk getHomeDetail DeviceBean received (homeId=${home.homeId})",
                                                            cloudId = id
                                                        )
                                                        collectedBeans[id] = devBean
                                                    }
                                                    synchronized(collectedBeans) {
                                                        remaining--
                                                        if (remaining == 0 && cont.isActive) {
                                                            cont.resume(Result.success(collectedBeans.values.toList()))
                                                        }
                                                    }
                                                }
                                            }

                                            override fun onError(errorCode: String?, errorMsg: String?) {
                                                DiscoveryFlightRecorder.record(
                                                    eventType = DiscoveryEventType.FAILED,
                                                    transportSource = "TUYA_NATIVE_SDK",
                                                    hardwareId = "home_${home.homeId}",
                                                    vendor = "ThingHomeSdk",
                                                    qualificationStage = "getHomeDetail",
                                                    outcomeReason = "ThingHomeSdk getHomeDetail failed ($errorCode): ${errorMsg.orEmpty()}"
                                                )
                                                PlayerBackgroundScope.launch(Dispatchers.IO) {
                                                    val fallbackList = extractSafeDevicesFromHomeBean(null, home.homeId)
                                                    for (devBean in fallbackList) {
                                                        val id = devBean.devId?.trim().orEmpty()
                                                        if (id.isBlank()) continue
                                                        collectedBeans[id] = devBean
                                                    }
                                                    synchronized(collectedBeans) {
                                                        remaining--
                                                        if (remaining == 0 && cont.isActive) {
                                                            cont.resume(Result.success(collectedBeans.values.toList()))
                                                        }
                                                    }
                                                }
                                            }
                                        })
                                    }
                                }

                                override fun onError(errorCode: String?, error: String?) {
                                    DiscoveryFlightRecorder.record(
                                        eventType = DiscoveryEventType.FAILED,
                                        transportSource = "TUYA_NATIVE_SDK",
                                        vendor = "ThingHomeSdk",
                                        qualificationStage = "queryHomeList",
                                        outcomeReason = "Native Tuya Home query error ($errorCode): ${error.orEmpty()}"
                                    )
                                    if (cont.isActive) {
                                        cont.resume(Result.failure(Exception("Native Tuya Home query error ($errorCode): $error")))
                                    }
                                }
                            })
                        },
                        onError = { err ->
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.FILTERED,
                                transportSource = "TUYA_NATIVE_SDK",
                                vendor = "ThingHomeSdk",
                                qualificationStage = "ensureSilentSessionAndHome",
                                outcomeReason = err
                            )
                            if (cont.isActive) {
                                cont.resume(Result.failure(Exception(err)))
                            }
                        }
                    )
                }

                if (rawBeansResult.isSuccess) {
                    val converted = rawBeansResult.getOrThrow()
                        .distinctBy { it.devId }
                        .map { bean ->
                            val dev = convertDeviceBeanToDiscovered(bean)
                            val knownIp = localIpByDevice[dev.deviceId].orEmpty()
                            val beanMac = CrossEcosystemIdentityCorrelator.normalizeMac(bean.mac ?: bean.uuid)
                            if (knownIp.isNotBlank() && beanMac.isNotBlank()) {
                                CrossEcosystemIdentityCorrelator.recordApplicationLayerObservation(knownIp, beanMac)
                            }
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.IDENTIFIED,
                                transportSource = "TUYA_NATIVE_SDK",
                                hardwareId = dev.deviceId,
                                macAddress = beanMac,
                                ipAddress = knownIp,
                                port = if (knownIp.isNotBlank()) 6668 else 0,
                                vendor = "${dev.name} (${dev.category})",
                                confidenceScore = dev.classificationConfidence,
                                qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                                outcomeReason = "Introspected ThingHomeSdk schema (${dev.capabilities.size} DPs, online=${dev.isOnline})",
                                cloudId = dev.deviceId
                            )
                            dev
                        }
                    Result.success(converted)
                } else {
                    val fallbackCached = readFastCachedNativeDevices()
                    if (fallbackCached.isNotEmpty()) {
                        Result.success(fallbackCached)
                    } else {
                        Result.failure(rawBeansResult.exceptionOrNull() ?: Exception("Native Tuya discovery error"))
                    }
                }
            }
        }

    /**
     * Publishes a raw DP map (e.g. {"1": true, "2": false}) directly over the native ThingHomeSdk (LAN/MQTT),
     * and automatically falls back to `ThingDevicePublishModeHttp` if LAN/MQTT returns 11005 (`no channel available`).
     */
    internal suspend fun publishNativeDpsMap(
        deviceId: String,
        dpsPayload: JSONObject
    ): Boolean {
        val cleanId = deviceId.trim()
        if (cleanId.isBlank() || dpsPayload.length() == 0) return false
        if (!ClaraApplication.isTuyaSdkReady()) return false

        if (!hasInitializedNativeHomeInSession ||
            runCatching { ThingHomeSdk.getDataInstance().getDeviceBean(cleanId) }.getOrNull() == null
        ) {
            discoverNativeSdkDevices(forceRefresh = false)
        }

        return suspendCancellableCoroutine { cont ->
            try {
                lastCommandTimestampMs = System.currentTimeMillis()
                val dpsJsonString = dpsPayload.toString()
                val devInstance = activeDeviceInstances.getOrPut(cleanId) {
                    ThingHomeSdk.newDeviceInstance(cleanId)
                }
                devInstance.publishDps(
                    dpsJsonString,
                    object : IResultCallback {
                        override fun onError(code: String?, error: String?) {
                            Log.w(TAG, "Primary publishDps [$cleanId] ($code) — retrying via HTTP channel")
                            try {
                                devInstance.publishDps(
                                    dpsJsonString,
                                    ThingDevicePublishModeEnum.ThingDevicePublishModeHttp,
                                    object : IResultCallback {
                                        override fun onError(httpCode: String?, httpError: String?) {
                                            Log.e(TAG, "Native publishDps FAILED [$cleanId] ($httpCode): $httpError")
                                            if (cont.isActive) cont.resume(false)
                                        }

                                        override fun onSuccess() {
                                            markDeviceSeenOnline(cleanId)
                                            if (cont.isActive) cont.resume(true)
                                        }
                                    }
                                )
                            } catch (e: Exception) {
            com.jarvis.smarthomelauncher.core.ErrorClassifier.classifyException(e).let { category ->
                com.jarvis.smarthomelauncher.core.DiscoveryFlightRecorder.record(
                    eventType = com.jarvis.smarthomelauncher.core.DiscoveryEventType.FAILED,
                    transportSource = "TuyaNativeClient",
                    hardwareId = "-",
                    vendor = "Vendor",
                    qualificationStage = "Exception Caught",
                    outcomeReason = com.jarvis.smarthomelauncher.core.ErrorClassifier.getHumanReadableRemediation(category, e),
                    rawPayload = e.stackTraceToString().take(200)
                )
            }
            
                                Log.e(TAG, "Native publishDps HTTP fallback exception [$cleanId]: ${e.message}")
                                if (cont.isActive) cont.resume(false)
                            
        }
                        }

                        override fun onSuccess() {
                            markDeviceSeenOnline(cleanId)
                            if (cont.isActive) cont.resume(true)
                        }
                    }
                )
            } catch (e: Exception) {
            com.jarvis.smarthomelauncher.core.ErrorClassifier.classifyException(e).let { category ->
                com.jarvis.smarthomelauncher.core.DiscoveryFlightRecorder.record(
                    eventType = com.jarvis.smarthomelauncher.core.DiscoveryEventType.FAILED,
                    transportSource = "TuyaNativeClient",
                    hardwareId = "-",
                    vendor = "Vendor",
                    qualificationStage = "Exception Caught",
                    outcomeReason = com.jarvis.smarthomelauncher.core.ErrorClassifier.getHumanReadableRemediation(category, e),
                    rawPayload = e.stackTraceToString().take(200)
                )
            }
            
                Log.e(TAG, "Native publishDps exception [$cleanId]: ${e.message}")
                if (cont.isActive) cont.resume(false)
            
        }
        }
    }

    /**
     * Point 11: Dedicated Airrobo P30 / Tuya Robot Vacuum (`sd`) command dispatcher.
     * Supports `vacuumActionMode` = `"CLEAN"` (Auto), `"SPOT"` (Spot/Spiral), `"TURBO"` (Max/Strong Suction + Auto),
     * and `"DOCK"` (Return to Charging Base).
     */
    internal suspend fun sendNativeVacuumCommand(
        deviceId: String,
        startCleaning: Boolean,
        returnToDock: Boolean = !startCleaning,
        vacuumActionMode: String = if (returnToDock) "DOCK" else "CLEAN"
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanId = deviceId.trim()
        if (cleanId.isBlank()) return@withContext false

        if (!hasInitializedNativeHomeInSession ||
            runCatching { ThingHomeSdk.getDataInstance().getDeviceBean(cleanId) }.getOrNull() == null
        ) {
            discoverNativeSdkDevices(forceRefresh = true)
        }

        val bean = runCatching { ThingHomeSdk.getDataInstance().getDeviceBean(cleanId) }.getOrNull()
        if (bean != null) {
            convertDeviceBeanToDiscovered(bean)
        }
        val beanDps = runCatching { bean?.dps ?: emptyMap() }.getOrDefault(emptyMap())

        val powerDp = findDpId(cleanId, "power", "switch", "switch_1")
            ?: if (beanDps["1"] is Boolean) "1" else null

        val goDp = findDpId(cleanId, "power_go", "switch_go", "clean_switch", "start", "work_switch")
            ?: switchDpIdByDevice[cleanId]
            ?: when {
                beanDps["2"] is Boolean -> "2"
                beanDps["1"] is Boolean -> "1"
                beanDps["25"] is Boolean -> "25"
                else -> "2"
            }

        val modeCap = findCapability(cleanId, "mode", "work_mode", "clean_mode")
        val modeDp = modeCap?.dpId?.takeIf { it.isNotBlank() }
            ?: findDpId(cleanId, "mode", "work_mode", "clean_mode")
            ?: when {
                beanDps["3"] is String -> "3"
                beanDps["4"] is String -> "4"
                else -> "3"
            }

        val suctionCap = findCapability(cleanId, "suction", "fan_speed_enum", "fan_speed", "cistern", "clean_power")
        val suctionDp = suctionCap?.dpId?.takeIf { it.isNotBlank() }
            ?: findDpId(cleanId, "suction", "fan_speed_enum", "fan_speed", "cistern", "clean_power")
            ?: when {
                beanDps["14"] is String -> "14"
                beanDps["9"] is String -> "9"
                beanDps["102"] is String -> "102"
                else -> "14"
            }

        val chargeDp = findDpId(cleanId, "switch_charge", "charge_switch", "seek", "return_home")
            ?: when {
                beanDps["11"] is Boolean -> "11"
                beanDps["15"] is Boolean -> "15"
                beanDps["13"] is Boolean -> "13"
                else -> null
            }

        val modeOpts = modeCap?.enumOptions?.map { it.lowercase(Locale.US) }.orEmpty()
        val suctionOpts = suctionCap?.enumOptions?.map { it.lowercase(Locale.US) }.orEmpty()
        val cleanModeVal = modeOpts.firstOrNull { it in listOf("smart", "auto", "all") } ?: "smart"
        val spotModeVal = modeOpts.firstOrNull { it in listOf("spiral", "spot", "part", "partial", "single") } ?: "spiral"
        val dockModeVal = modeOpts.firstOrNull { it in listOf("chargego", "charge_go", "charge", "dock") } ?: "chargego"
        val turboSuctionVal = suctionOpts.firstOrNull { it in listOf("strong", "max", "high", "turbo", "3") } ?: "strong"
        val normalSuctionVal = suctionOpts.firstOrNull { it in listOf("normal", "standard", "middle", "gentle", "1") } ?: "normal"

        val modeUpper = vacuumActionMode.uppercase(Locale.US)
        var anySuccess = false

        when {
            modeUpper == "SPOT" -> {
                if (powerDp != null && powerDp != goDp) {
                    if (sendLocalLanDpCommand(cleanId, dpsPayload = JSONObject().put(powerDp, true))) {
                        anySuccess = true
                        delay(140L)
                    }
                }
                val spotPayload = JSONObject().apply {
                    if (powerDp != null) put(powerDp, true)
                    put(goDp, true)
                    if (modeDp != goDp && modeDp != powerDp) {
                        put(modeDp, spotModeVal)
                    }
                    if (chargeDp != null && chargeDp != goDp && chargeDp != powerDp) {
                        put(chargeDp, false)
                    }
                }
                if (sendLocalLanDpCommand(cleanId, dpsPayload = spotPayload)) {
                    anySuccess = true
                }
                if (modeDp != goDp && modeDp != powerDp) {
                    if (sendLocalLanDpCommand(cleanId, dpsPayload = JSONObject().put(modeDp, spotModeVal))) {
                        anySuccess = true
                    } else if (spotModeVal != "spot") {
                        sendLocalLanDpCommand(cleanId, dpsPayload = JSONObject().put(modeDp, "spot"))
                    }
                }
                if (sendLocalLanDpCommand(cleanId, dpsPayload = JSONObject().put(goDp, true))) {
                    anySuccess = true
                }
            }
            modeUpper == "TURBO" -> {
                if (powerDp != null && powerDp != goDp) {
                    if (sendLocalLanDpCommand(cleanId, dpsPayload = JSONObject().put(powerDp, true))) {
                        anySuccess = true
                        delay(140L)
                    }
                }
                val turboPayload = JSONObject().apply {
                    if (powerDp != null) put(powerDp, true)
                    put(goDp, true)
                    if (modeDp != goDp && modeDp != powerDp) {
                        put(modeDp, cleanModeVal)
                    }
                    if (suctionDp != goDp && suctionDp != modeDp) {
                        put(suctionDp, turboSuctionVal)
                    }
                }
                if (sendLocalLanDpCommand(cleanId, dpsPayload = turboPayload)) {
                    anySuccess = true
                }
                if (suctionDp != goDp && suctionDp != modeDp) {
                    if (sendLocalLanDpCommand(cleanId, dpsPayload = JSONObject().put(suctionDp, turboSuctionVal))) {
                        anySuccess = true
                    } else if (turboSuctionVal != "max") {
                        sendLocalLanDpCommand(cleanId, dpsPayload = JSONObject().put(suctionDp, "max"))
                    }
                }
                if (sendLocalLanDpCommand(cleanId, dpsPayload = JSONObject().put(goDp, true))) {
                    anySuccess = true
                }
            }
            startCleaning && !returnToDock -> {
                if (powerDp != null && powerDp != goDp) {
                    if (sendLocalLanDpCommand(cleanId, dpsPayload = JSONObject().put(powerDp, true))) {
                        anySuccess = true
                        delay(160L)
                    }
                }
                val combinedCleanPayload = JSONObject().apply {
                    if (powerDp != null) put(powerDp, true)
                    put(goDp, true)
                    if (modeDp != goDp && modeDp != powerDp) {
                        put(modeDp, cleanModeVal)
                    }
                    if (suctionDp != goDp && suctionDp != modeDp && beanDps.containsKey(suctionDp)) {
                        put(suctionDp, normalSuctionVal)
                    }
                    if (chargeDp != null && chargeDp != goDp && chargeDp != powerDp) {
                        put(chargeDp, false)
                    }
                }
                if (sendLocalLanDpCommand(cleanId, dpsPayload = combinedCleanPayload)) {
                    anySuccess = true
                }
                if (sendLocalLanDpCommand(cleanId, dpsPayload = JSONObject().put(goDp, true))) {
                    anySuccess = true
                }
                if (modeDp != goDp && modeDp != powerDp) {
                    sendLocalLanDpCommand(cleanId, dpsPayload = JSONObject().put(modeDp, cleanModeVal))
                }
            }
            returnToDock || modeUpper == "DOCK" -> {
                if (sendLocalLanDpCommand(cleanId, dpsPayload = JSONObject().put(goDp, false))) {
                    anySuccess = true
                    delay(160L)
                }
                val dockPayload = JSONObject().apply {
                    if (modeDp != goDp && modeDp != powerDp) {
                        put(modeDp, dockModeVal)
                    }
                    if (chargeDp != null) {
                        put(chargeDp, true)
                    }
                }
                if (dockPayload.length() > 0 && sendLocalLanDpCommand(cleanId, dpsPayload = dockPayload)) {
                    anySuccess = true
                }
                if (modeDp != goDp && modeDp != powerDp) {
                    if (sendLocalLanDpCommand(cleanId, dpsPayload = JSONObject().put(modeDp, dockModeVal))) {
                        anySuccess = true
                    }
                }
                if (chargeDp != null) {
                    if (sendLocalLanDpCommand(cleanId, dpsPayload = JSONObject().put(chargeDp, true))) {
                        anySuccess = true
                    }
                }
            }
            else -> {
                if (sendLocalLanDpCommand(cleanId, dpsPayload = JSONObject().put(goDp, false))) {
                    anySuccess = true
                }
                if (modeDp != goDp && modeDp != powerDp) {
                    sendLocalLanDpCommand(cleanId, dpsPayload = JSONObject().put(modeDp, "standby"))
                }
            }
        }
        anySuccess
    }

    /**
     * Publishes Socket 1 (DP 1) and (only if dual-socket hardware) Socket 2 (DP 2) states over LAN/MQTT.
     * Fires local LAN TCP 6668 immediately on line 1 without waiting for cloud/SDK home initialization.
     */
    internal suspend fun sendNativeDualPlugCommand(
        deviceId: String,
        socket1On: Boolean,
        socket2On: Boolean,
        ipAddress: String = "",
        localKey: String = "",
        isDualHardware: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanId = deviceId.trim()
        if (cleanId.isBlank()) return@withContext false

        if (ipAddress.isNotBlank()) {
            registerDeviceLanAddress(cleanId, ipAddress, localKey)
        }

        val dp1 = switchDpIdByDevice[cleanId] ?: "1"
        val dp2 = switch2DpIdByDevice[cleanId] ?: if (isDualHardware) "2" else null

        val fastPayload = JSONObject().apply {
            put(dp1, socket1On)
            if (dp2 != null) {
                put(dp2, socket2On)
            }
        }

        val resolvedIp = ipAddress.substringBefore(":").trim().ifBlank { localIpByDevice[cleanId].orEmpty() }
        val resolvedKey = localKey.trim().ifBlank { localKeyByDevice[cleanId].orEmpty() }
        val bean = runCatching { ThingHomeSdk.getDataInstance().getDeviceBean(cleanId) }.getOrNull()
        if (bean != null && !switchDpIdByDevice.containsKey(cleanId)) {
            convertDeviceBeanToDiscovered(bean)
        }
        sendLocalLanDpCommand(cleanId, ipAddress = resolvedIp, localKey = resolvedKey, dpsPayload = fastPayload)
    }

    /**
     * Publishes a hardware countdown timer (in seconds) to Socket 1 (DP 9) or Socket 2 (DP 10)
     * on a SmartKnight / Tuya plug over local LAN/MQTT.
     */
    suspend fun sendNativePlugCountdownCommand(
        deviceId: String,
        socketNumber: Int,
        countdownSeconds: Int,
        ipAddress: String = "",
        localKey: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanId = deviceId.trim()
        if (cleanId.isBlank()) return@withContext false

        val targetDpId = if (socketNumber == 2) {
            findDpId(cleanId, "countdown_2") ?: "10"
        } else {
            findDpId(cleanId, "countdown_1", "countdown") ?: "9"
        }
        val payload = JSONObject().put(targetDpId, countdownSeconds.coerceIn(0, 86400))
        sendLocalLanDpCommand(cleanId, ipAddress = ipAddress, localKey = localKey, dpsPayload = payload)
    }

    /**
     * Sends a DP command directly over the local LAN 6668 engine or native ThingHomeSdk
     * without blocking on cloud home initialization when DP IDs or LAN credentials are known.
     */
    internal suspend fun sendNativeSdkDpCommand(
        deviceId: String,
        dpCode: String,
        value: Any,
        ipAddress: String = "",
        localKey: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanId = deviceId.trim()
        if (cleanId.isBlank()) return@withContext false

        val bean = runCatching { ThingHomeSdk.getDataInstance().getDeviceBean(cleanId) }.getOrNull()
        if (bean != null && !tempSetDpIdByDevice.containsKey(cleanId) && !switchDpIdByDevice.containsKey(cleanId)) {
            convertDeviceBeanToDiscovered(bean)
        }

        val lowerCode = dpCode.lowercase(Locale.US)
        val resolvedDpId = findDpId(cleanId, dpCode, lowerCode)
            ?: runCatching { bean?.schemaMap?.values?.firstOrNull { it.code.equals(dpCode, ignoreCase = true) }?.id }.getOrNull()
            ?: when {
                lowerCode == "switch_2" || lowerCode == "switch2" -> switch2DpIdByDevice[cleanId] ?: "2"
                lowerCode == "countdown_1" -> "9"
                lowerCode == "countdown_2" -> "10"
                lowerCode.contains("temp") -> tempSetDpIdByDevice[cleanId] ?: "3"
                lowerCode == "power_go" || lowerCode == "switch_go" -> switchDpIdByDevice[cleanId] ?: "2"
                lowerCode == "mode" || lowerCode == "work_mode" -> "2"
                lowerCode == "fan_speed" || lowerCode == "windspeed" -> "3"
                lowerCode == "switch_horizontal" || lowerCode == "shake" || lowerCode == "swing" -> "4"
                lowerCode == "anion" || lowerCode == "ionizer" -> "5"
                lowerCode == "countdown_set" -> "6"
                lowerCode == "suction" || lowerCode == "fan_speed_enum" -> "14"
                lowerCode == "switch_charge" || lowerCode == "charge_switch" -> "11"
                lowerCode.contains("colour") || lowerCode.contains("color") -> "24"
                lowerCode.contains("switch") || lowerCode.contains("power") -> switchDpIdByDevice[cleanId] ?: "1"
                dpCode.all { it.isDigit() } -> dpCode
                else -> null
            }

        if (resolvedDpId.isNullOrBlank()) return@withContext false

        var finalValue = value
        if (value is Number && (lowerCode.contains("temp") || resolvedDpId == tempSetDpIdByDevice[cleanId])) {
            val schemaProp = runCatching {
                val propStr = bean?.schemaMap?.get(resolvedDpId)?.property.orEmpty()
                if (propStr.trim().startsWith("{")) JSONObject(propStr) else null
            }.getOrNull()
            val scaleDivisor = if (schemaProp != null && schemaProp.has("scale")) {
                val scExp = schemaProp.optInt("scale", 0).coerceIn(0, 4)
                if (scExp > 0) 10.0.pow(scExp.toDouble()).toFloat() else 1f
            } else {
                tempScaleByDevice[cleanId] ?: 1f
            }
            val floatVal = value.toFloat()
            finalValue = (floatVal * scaleDivisor).roundToInt()
        }

        val payload = JSONObject().put(resolvedDpId, finalValue)
        sendLocalLanDpCommand(cleanId, ipAddress = ipAddress, localKey = localKey, dpsPayload = payload)
    }

    /**
     * Specialized native radiator command publisher that sends both Power Switch (DP 1)
     * and Target Temperature (DP 3 / DP 4) with automatic 1x vs 10x hardware scaling.
     * Fires over the Persistent TCP 6668 LAN Socket Pool first before any SDK initialization.
     */
    internal suspend fun sendNativeRadiatorCommand(
        deviceId: String,
        shouldBeOn: Boolean,
        targetTempCelsius: Float,
        ipAddress: String = "",
        localKey: String = "",
        hvacMode: String = "HEAT"
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanId = deviceId.trim()
        if (cleanId.isBlank()) return@withContext false
        if (ipAddress.isNotBlank()) {
            registerDeviceLanAddress(cleanId, ipAddress, localKey)
        }

        val bean = runCatching { ThingHomeSdk.getDataInstance().getDeviceBean(cleanId) }.getOrNull()
        if (bean != null && !tempSetDpIdByDevice.containsKey(cleanId)) {
            convertDeviceBeanToDiscovered(bean)
        }

        val switchDpId = switchDpIdByDevice[cleanId] ?: "1"
        val tempDpId = tempSetDpIdByDevice[cleanId] ?: "3"
        val ecoDpId = findDpId(cleanId, "eco")
        val modeDpId = findDpId(cleanId, "mode", "work_mode", "level", "gear")

        if (!shouldBeOn) {
            return@withContext sendLocalLanDpCommand(
                deviceId = cleanId,
                ipAddress = ipAddress,
                localKey = localKey,
                dpsPayload = JSONObject().put(switchDpId, false)
            )
        }

        val beanDps = runCatching { bean?.dps }.getOrNull()
        val liveTempRaw = (beanDps?.get(tempDpId) as? Number)?.toFloat()
        val scale = when {
            liveTempRaw != null && liveTempRaw > 55f -> 10f
            liveTempRaw != null && liveTempRaw > 0f -> 1f
            else -> tempScaleByDevice[cleanId] ?: 1f
        }
        val scaledTemp = (targetTempCelsius * scale).roundToInt()
        val currentSwitchState = beanDps?.get(switchDpId) as? Boolean
        val modeUpper = hvacMode.uppercase(Locale.US)
        val isEco = modeUpper == "ECO"

        val payload = JSONObject().apply {
            if (currentSwitchState == false || currentSwitchState == null) {
                put(switchDpId, true)
            }
            if (!ecoDpId.isNullOrBlank()) {
                put(ecoDpId, isEco)
            }
            if (!modeDpId.isNullOrBlank()) {
                val modeCap = capabilitiesByDevice[cleanId]?.firstOrNull { it.dpId == modeDpId }
                val allowedModes = modeCap?.enumOptions.orEmpty()
                val matchedHardwareMode = when {
                    isEco -> allowedModes.firstOrNull { it.contains("eco", true) || it.contains("low", true) || it.contains("night", true) }
                    modeUpper == "BOOST" -> allowedModes.firstOrNull { it.contains("high", true) || it.contains("boost", true) || it.contains("comfort", true) || it.contains("hot", true) }
                    else -> allowedModes.firstOrNull { it.contains("comfort", true) || it.contains("auto", true) || it.contains("heat", true) || it.contains("high", true) }
                }
                if (!matchedHardwareMode.isNullOrBlank()) {
                    put(modeDpId, matchedHardwareMode)
                }
            }
            put(tempDpId, scaledTemp)
        }

        val primaryOk = sendLocalLanDpCommand(
            deviceId = cleanId,
            ipAddress = ipAddress,
            localKey = localKey,
            dpsPayload = payload
        )
        if (primaryOk) return@withContext true

        if (tempDpId == "3" && beanDps?.containsKey("4") == true) {
            tempSetDpIdByDevice[cleanId] = "4"
            return@withContext sendLocalLanDpCommand(
                deviceId = cleanId,
                ipAddress = ipAddress,
                localKey = localKey,
                dpsPayload = JSONObject().put("4", scaledTemp)
            )
        }
        false
    }
}
