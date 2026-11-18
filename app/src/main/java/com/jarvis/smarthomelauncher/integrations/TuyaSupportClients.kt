package com.jarvis.smarthomelauncher.integrations

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult as WifiScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.thingclips.smart.android.ble.api.BleScanResponse
import com.thingclips.smart.android.ble.api.LeScanSetting
import com.thingclips.smart.android.ble.api.ScanDeviceBean
import com.thingclips.smart.android.ble.api.ScanType
import com.thingclips.smart.android.user.api.ILoginCallback
import com.thingclips.smart.android.user.bean.User
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.home.sdk.bean.HomeBean
import com.thingclips.smart.home.sdk.builder.ActivatorBuilder
import com.thingclips.smart.home.sdk.callback.IThingGetHomeListCallback
import com.thingclips.smart.home.sdk.callback.IThingHomeResultCallback
import com.thingclips.smart.sdk.api.IMultiModeActivatorListener
import com.thingclips.smart.sdk.api.IThingActivator
import com.thingclips.smart.sdk.api.IThingActivatorGetToken
import com.thingclips.smart.sdk.api.IThingSmartActivatorListener
import com.thingclips.smart.sdk.bean.DeviceBean
import com.thingclips.smart.sdk.bean.MultiModeActivatorBean
import com.thingclips.smart.sdk.enums.ActivatorModelEnum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

// ---------------------------------------------------------------------------
// WI-FI SCANNING & PAIRING DATA MODELS
// ---------------------------------------------------------------------------
data class ScannedWifiNetwork(
    val ssid: String,
    val is24Ghz: Boolean = true,
    val is5Ghz: Boolean = false,
    val levelDbm: Int = -50,
    val bssid24Ghz: String = ""
) {
    val bandLabel: String
        get() = when {
            is24Ghz && is5Ghz -> "2.4 GHz & 5 GHz Hybrid \u2022 Auto-Steers to 2.4 GHz \u2713"
            is24Ghz -> "2.4 GHz Home Wi-Fi \u2022 Compatible with all smart devices \u2713"
            is5Ghz -> "5 GHz Wi-Fi (Smart plugs require 2.4 GHz)"
            else -> "Home Wi-Fi"
        }
}

enum class TuyaPairingMode(val label: String, val subtitle: String) {
    AUTO_COMBO_EZ(
        "Fast Blinking (Bluetooth + EZ Mode)",
        "Indicator light blinking rapidly (twice per second) \u2014 pairs over Bluetooth LE + 2.4 GHz Wi-Fi"
    ),
    BLE_DIRECT(
        "Fast Blinking (Direct Bluetooth Setup)",
        "Connects directly to your selected device over Bluetooth LE"
    ),
    PURE_EZ_BROADCAST(
        "Fast Blinking (2.4 GHz Wi-Fi Broadcast)",
        "Receive-only 2.4 GHz Wi-Fi device \u2014 blink one device at a time while tablet is on 2.4 GHz"
    ),
    AP_HOTSPOT(
        "Slow Blinking / AP Mode",
        "Indicator light blinking slowly (once every 2\u20133 seconds) \u2014 connects via device Wi-Fi hotspot"
    );

    companion object {
        val EZ_FAST_BLINK: TuyaPairingMode
            get() = AUTO_COMBO_EZ
    }
}

data class TuyaPreflightCheckResult(
    val passed: Boolean,
    val shouldRedirectPureEzToApMode: Boolean = false,
    val failureTitle: String = "",
    val remediationMessage: String = "",
    val protocolResultCode: String = "PREFLIGHT_OK",
    val tabletFrequencyMhz: Int = 0
)

data class PairedTuyaDevicePacket(
    val gwId: String,
    val ipAddress: String = "",
    val name: String = "",
    val localKey: String = "",
    val suggestedType: TileType = TileType.SMARTLIFE_DEVICE,
    val multiGangSocketCount: Int = 1
)

data class TuyaQrSession(
    val qrToken: String,
    val qrContentUrl: String,
    val userCode: String
)

// ---------------------------------------------------------------------------
// ZERO-LOGIN DIRECT TUYA HARDWARE PAIRING (AUTOMATIC BLE + WI-FI + AP HOTSPOT)
// - Uses per-installation cryptographic household credentials (zero static shared UIDs).
// - Strictly ignores already-connected devices (Panel Heater, SmartKnight, etc.).
// - Detects 5GHz tablet Wi-Fi (4900-5900 MHz), alerts the user, and prioritizes
//   Bluetooth LE combo pairing so 2.4GHz devices never fail silently.
// - Supports Android 10+ WifiNetworkSpecifier SoftAP auto-bridging and automatically
//   unbinds the temporary SoftAP network 2.5 seconds after discovery.
// - Scans for 0xFEAF (Nest Weave), 0xFFF6 (Matter BLE), 0xFD50 (Tuya BLE),
//   and 0xEC88 (Govee) hardware beacons plus strict prefix-matched BLE advertisements.
// ---------------------------------------------------------------------------
object TuyaDirectPairingManager {
    private const val TAG = "TuyaDirectPair"
    private const val PREFS_NAME = "clara_direct_pairing_prefs"
    private const val KEY_CACHED_WIFI_LIST_JSON = "cached_in_range_wifi_list_v2"
    const val DEFAULT_COUNTRY_CODE = "44"

    private val TUYA_FD50_UUID = ParcelUuid.fromString("0000fd50-0000-1000-8000-00805f9b34fb")
    private val TUYA_FE07_UUID = ParcelUuid.fromString("0000fe07-0000-1000-8000-00805f9b34fb")
    private val NEST_WEAVE_BLE_UUID = ParcelUuid.fromString("0000feaf-0000-1000-8000-00805f9b34fb")
    private val MATTER_BLE_UUID = ParcelUuid.fromString("0000fff6-0000-1000-8000-00805f9b34fb")
    private val GOVEE_BLE_UUID = ParcelUuid.fromString("0000ec88-0000-1000-8000-00805f9b34fb")

    private val pairingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeWifiBroadcaster: IThingActivator? = null
    private var activeApFallbackBroadcaster: IThingActivator? = null
    private var pairingPollJob: Job? = null
    private var pairingTimeoutWatchdogJob: Job? = null
    private const val POST_ONBOARDING_UDP_WINDOW_MS = 45_000L
    private var softApAutoUnbindJob: Job? = null
    private var postOnboardingUdpJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiHighPerfLock: WifiManager.WifiLock? = null
    private var softApNetworkCallback: ConnectivityManager.NetworkCallback? = null

    val availableWifiNetworksFlow: MutableStateFlow<List<ScannedWifiNetwork>>
        get() = JarvisLiveBridge.availableWifiNetworksFlow

    @Volatile
    private var cachedHomeId: Long = -1L

    @Volatile
    private var cachedActivatorToken: String = ""

    @Volatile
    private var cachedTokenTimestampMs: Long = 0L

    @Volatile
    private var isPairingActive: Boolean = false

    private val cachedBaselineDeviceIds = HashSet<String>()
    private val discoveredUnpairedBleBeans = ConcurrentHashMap<String, ScanDeviceBean>()

    private fun resolveAppContext(explicitContext: Context? = null): Context? =
        ClaraApplication.appContextOrNull(explicitContext)

    /**
     * F-33: Inspects the Tuya 0xFD50 BLE Service Data Frame Control byte.
     * Bit 7 (`0x80`) == 1 means the device is already bound/paired and broadcasting idle state.
     * Returns `true` ONLY when unbound (`(byte0 and 0x80) == 0`).
     */
    fun isUnboundTuyaFd50ServiceData(serviceDataBytes: ByteArray?): Boolean {
        if (serviceDataBytes == null || serviceDataBytes.isEmpty()) return false
        val frameControlByte0 = serviceDataBytes[0].toInt() and 0xFF
        val isBound = (frameControlByte0 and 0x80) != 0
        return !isBound
    }

    private const val VAULT_WIFI_PASSWORD_KEY = "${PREFS_NAME}::wifi_password"

    fun getSavedWifiSsid(context: Context): String {
        val appCtx = context.applicationContext
        return ClaraSecretVault.getSavedHomeWifiSsid(appCtx).ifBlank {
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("wifi_ssid", "")?.trim().orEmpty()
        }
    }

    fun getSavedWifiPassword(context: Context, ssid: String = ""): String {
        val appCtx = context.applicationContext
        val bySsid = ClaraSecretVault.getWifiPasswordForSsid(appCtx, ssid)
        if (bySsid.isNotEmpty()) return bySsid
        val vaultVal = ClaraSecretVault.getString(appCtx, VAULT_WIFI_PASSWORD_KEY).orEmpty()
        if (vaultVal.isNotBlank()) {
            val resolved = vaultVal
            DiagnosticsSanitizer.registerTransientWifiSecretForRedaction(resolved)
            return resolved
        }
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacyRaw = prefs.getString("wifi_password", "").orEmpty()
        if (legacyRaw.isBlank()) return ""
        val migrated = legacyRaw
        if (migrated.isNotBlank()) {
            ClaraSecretVault.putString(appCtx, VAULT_WIFI_PASSWORD_KEY, migrated)
            prefs.edit().remove("wifi_password").apply()
            DiagnosticsSanitizer.registerTransientWifiSecretForRedaction(migrated)
        }
        return migrated
    }

    fun saveWifiCredentials(context: Context, ssid: String, password: String) {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isNotBlank() && !isPlugHotspotSsid(cleanSsid)) {
            val appCtx = context.applicationContext
            DiagnosticsSanitizer.registerTransientWifiSecretForRedaction(password)
            ClaraSecretVault.saveHomeWifiCredentials(appCtx, cleanSsid, password)
            ClaraSecretVault.putString(appCtx, VAULT_WIFI_PASSWORD_KEY, password)
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("wifi_ssid", cleanSsid)
                .remove("wifi_password")
                .apply()
        }
    }

    fun startDirectPairing(
        context: Context,
        ssid: String,
        password: String,
        mode: TuyaPairingMode,
        @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
        isSilentBackground: Boolean = false,
        targetKey: String = "MODAL_PAIRING",
        targetHardware: String = "",
        targetBeaconName: String = "",
        onError: ((String) -> Unit)? = null,
        onDevicePaired: (PairedTuyaDevicePacket) -> Unit
    ) {
        val hasDiscoveredTarget = targetHardware.isNotBlank() ||
                targetBeaconName.isNotBlank() ||
                (targetKey.isNotBlank() && targetKey != "MODAL_PAIRING")
        if (!isSilentBackground) {
            JarvisLiveBridge.isPairingTuyaFlow.value = true
            val on5G = isTabletOn5GhzBand(context)
            val freqMhz = getCurrentWifiFrequencyMhz(context)
            val displayTarget = targetBeaconName.ifBlank { targetHardware }.ifBlank { "wireless device" }
            if (hasDiscoveredTarget) {
                // Mark Step 1 (Scanning) complete and start immediately at Step 2 (Device Found)
                JarvisLiveBridge.updatePairingTimeline(
                    stage = PairingTimelineStage.SCANNING_DEVICE,
                    customDetail = "Discovered $displayTarget",
                    targetKey = targetKey
                )
                val step2Msg = when (mode) {
                    TuyaPairingMode.BLE_DIRECT ->
                        "Opening direct Bluetooth LE connection to $displayTarget..."
                    TuyaPairingMode.AP_HOTSPOT ->
                        "Preparing to join '$displayTarget' hotspot & push '$ssid' credentials..."
                    TuyaPairingMode.PURE_EZ_BROADCAST ->
                        "Broadcasting 2.4 GHz Wi-Fi ('$ssid') setup frames..."
                    TuyaPairingMode.AUTO_COMBO_EZ ->
                        "Handshake ready for $displayTarget \u2014 sending '$ssid' credentials..."
                }
                JarvisLiveBridge.updatePairingTimeline(
                    stage = PairingTimelineStage.DEVICE_FOUND,
                    customDetail = step2Msg,
                    targetKey = targetKey
                )
            } else {
                val initialMsg = if (on5G && mode == TuyaPairingMode.AUTO_COMBO_EZ) {
                    "Tablet is on 5GHz Wi-Fi ($freqMhz MHz) \u2014 steering to 2.4GHz & scanning BLE for '$ssid'..."
                } else {
                    "Broadcasting 2.4GHz Wi-Fi ('$ssid') & scanning Bluetooth LE / UDP beacons..."
                }
                JarvisLiveBridge.updatePairingTimeline(
                    stage = PairingTimelineStage.SCANNING_DEVICE,
                    customDetail = initialMsg,
                    targetKey = targetKey
                )
            }
        }
        scanAndPairDeviceDirectly(
            context = context,
            wifiSsid = ssid,
            wifiPassword = password,
            pairingMode = mode,
            targetKey = targetKey,
            targetHardware = targetHardware,
            targetBeaconName = targetBeaconName,
            hasPreDiscoveredTarget = hasDiscoveredTarget,
            onStatusUpdate = { msg ->
                if (!isSilentBackground) {
                    JarvisLiveBridge.tuyaPairingStatusFlow.value = msg
                }
            },
            onDevicePaired = { dev ->
                val knownLanIp = TuyaApiClient.localIpByDevice[dev.deviceId].orEmpty()
                if (knownLanIp.isBlank() && dev.deviceId.isNotBlank()) {
                    startPostOnboardingDiscoveryAcceleration(
                        context = context,
                        expectedDeviceId = dev.deviceId
                    )
                }
                if (!isSilentBackground) {
                    JarvisLiveBridge.updatePairingTimeline(
                        stage = PairingTimelineStage.INITIALIZING_TILE,
                        customDetail = "Initializing '${dev.name}' & adding tile to dashboard...",
                        targetKey = targetKey
                    )
                    JarvisLiveBridge.updatePairingTimeline(
                        stage = PairingTimelineStage.COMPLETED,
                        customDetail = "Connected '${dev.name}' (${knownLanIp.ifBlank { "Verifying LAN 6668..." }})!",
                        targetKey = targetKey
                    )
                }
                JarvisLiveBridge.isPairingTuyaFlow.value = false
                onDevicePaired(
                    PairedTuyaDevicePacket(
                        gwId = dev.deviceId,
                        ipAddress = knownLanIp,
                        name = dev.name,
                        localKey = dev.localKey,
                        suggestedType = dev.suggestedType,
                        multiGangSocketCount = dev.multiGangSocketCount
                    )
                )
            },
            onError = { errMsg ->
                if (!isSilentBackground) {
                    JarvisLiveBridge.updatePairingTimeline(
                        stage = PairingTimelineStage.FAILED,
                        customDetail = errMsg,
                        targetKey = targetKey
                    )
                }
                JarvisLiveBridge.isPairingTuyaFlow.value = false
                onError?.invoke(errMsg)
            }
        )
    }

    fun stopDirectPairing() {
        stopPairing(resetTimeline = true)
    }

    fun isPlugHotspotSsid(ssid: String): Boolean {
        val clean = ssid.trim().removePrefix("\"").removeSuffix("\"")
        return clean.startsWith("SmartLife", ignoreCase = true) ||
                clean.startsWith("SL-", ignoreCase = true) ||
                clean.startsWith("Tuya", ignoreCase = true) ||
                clean.startsWith("Hyleton", ignoreCase = true) ||
                clean.startsWith("Jisusu", ignoreCase = true) ||
                clean.startsWith("Cosori", ignoreCase = true) ||
                clean.startsWith("VeSync", ignoreCase = true) ||
                clean.startsWith("CAF-", ignoreCase = true) ||
                clean.startsWith("Kettle", ignoreCase = true) ||
                clean.startsWith("Lytmi", ignoreCase = true) ||
                clean.startsWith("SmartSocket", ignoreCase = true) ||
                clean.startsWith("CloudIntelligence", ignoreCase = true)
    }

    /**
     * Identifies any smart home setup Wi-Fi hotspot across Tuya, SmartKnight, Lytmi, Govee, Twinkly,
     * Tapo, Kasa, Shelly, Tasmota, Sonoff, etc.
     */
    fun isAnySmartHomeSetupHotspot(ssid: String): Pair<TileType, BrandOption>? {
        val clean = ssid.trim().removePrefix("\"").removeSuffix("\"")
        if (clean.isBlank()) return null
        // Guard against false-positive printers, TVs, cameras, phones, and router networks
        val nonSmartHomePrefixes = listOf(
            "DIRECT-", "HP-Print", "HP-Setup", "EPSON", "Canon", "Brother",
            "GoPro", "DJI-", "SONY-", "iPhone", "Galaxy", "AndroidAP",
            "BTWi-fi", "Sky", "VirginMedia", "TalkTalk", "Vodafone", "EE-", "Plusnet"
        )
        if (nonSmartHomePrefixes.any { clean.startsWith(it, ignoreCase = true) }) {
            return null
        }
        val provisioningApPattern = Regex("^(?:SmartLife|SL|Tuya|SmartSocket|CloudIntelligence|Hyleton|Jisusu|Cosori|VeSync|CAF)[-_][A-Za-z0-9]{3,8}$", RegexOption.IGNORE_CASE)
        return when {
            clean.startsWith("Lytmi", ignoreCase = true) ->
                TileType.LYTMI_SYNC_BOX to BrandOption.LYTMI
            clean.startsWith("Twinkly_", ignoreCase = true) || clean.startsWith("Twinkly-", ignoreCase = true) ->
                TileType.TWINKLY_LIGHT to BrandOption.TWINKLY
            clean.startsWith("Govee_", ignoreCase = true) || clean.startsWith("ihoment_", ignoreCase = true) ->
                TileType.GOVEE_LIGHT to BrandOption.GOVEE
            clean.startsWith("Cosori", ignoreCase = true) || clean.startsWith("VeSync", ignoreCase = true) || clean.startsWith("CAF-", ignoreCase = true) ->
                TileType.SMARTLIFE_DEVICE to BrandOption.SMART_LIFE
            isPlugHotspotSsid(clean) || provisioningApPattern.matches(clean) ->
                TileType.SMARTKNIGHT_PLUG to BrandOption.SMARTKNIGHT
            clean.startsWith("Tapo_", ignoreCase = true) || clean.startsWith("TP-LINK_", ignoreCase = true) || clean.startsWith("Kasa_", ignoreCase = true) ->
                TileType.TAPO_DEVICE to BrandOption.TAPO
            clean.startsWith("shelly", ignoreCase = true) ->
                TileType.SHELLY_RELAY to BrandOption.SHELLY
            clean.startsWith("tasmota", ignoreCase = true) || clean.startsWith("ESP_", ignoreCase = true) ->
                TileType.MQTT_DEVICE to BrandOption.MQTT_TASMOTA_ESPHOME
            clean.startsWith("ITEAD-", ignoreCase = true) || clean.startsWith("SONOFF_", ignoreCase = true) ->
                TileType.SONOFF_SWITCH to BrandOption.SONOFF
            else -> null
        }
    }

    fun detectCurrentWifiSsid(context: Context): String =
        RadioEnvironmentProbe.currentConnectedSsid(context)

    fun isConnectedToDeviceHotspot(context: Context): Boolean =
        isPlugHotspotSsid(detectCurrentWifiSsid(context))

    fun getCurrentWifiFrequencyMhz(context: Context): Int =
        RadioEnvironmentProbe.currentWifiFrequencyMhz(context)

    fun isTabletOn5GhzBand(context: Context): Boolean =
        RadioEnvironmentProbe.isTabletOn5GhzBand(context)

    /**
     * Onboarding Section 6: Validates 2.4 GHz hardware compatibility strictly by checking
     * ScanResult.frequency (2400–2499 MHz) across all grouped BSSIDs for [ssid], never by SSID suffix.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun validateSsidSupports24GhzByBssid(context: Context, ssid: String): WifiBandValidationStatus {
        val cleanSsid = ssid.trim().removeSurrounding("\"")
        if (cleanSsid.isEmpty()) return WifiBandValidationStatus.UNKNOWN_UNSCANNED
        val appCtx = context.applicationContext
        val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val matchingResults = runCatching { wm?.scanResults.orEmpty() }
            .getOrDefault(emptyList())
            .filter { sr ->
                sr.SSID?.trim()?.removeSurrounding("\"")?.equals(cleanSsid, ignoreCase = true) == true
            }

        if (matchingResults.isNotEmpty()) {
            val has24 = matchingResults.any { it.frequency in 2400..2499 }
            val has5Or6 = matchingResults.any { it.frequency >= 4900 }
            return when {
                has24 && has5Or6 -> WifiBandValidationStatus.VALID_DUAL_BAND_MESH
                has24 -> WifiBandValidationStatus.VALID_24GHZ_ONLY
                else -> WifiBandValidationStatus.BLOCKED_5GHZ_OR_6GHZ_ONLY
            }
        }

        val cached = availableWifiNetworksFlow.value.firstOrNull {
            it.ssid.equals(cleanSsid, ignoreCase = true)
        } ?: loadCachedInRangeWifiNetworks(appCtx).firstOrNull {
            it.ssid.equals(cleanSsid, ignoreCase = true)
        }
        if (cached != null) {
            return when {
                cached.is24Ghz && cached.is5Ghz -> WifiBandValidationStatus.VALID_DUAL_BAND_MESH
                cached.is24Ghz -> WifiBandValidationStatus.VALID_24GHZ_ONLY
                cached.is5Ghz -> WifiBandValidationStatus.BLOCKED_5GHZ_OR_6GHZ_ONLY
                else -> WifiBandValidationStatus.UNKNOWN_UNSCANNED
            }
        }
        return WifiBandValidationStatus.UNKNOWN_UNSCANNED
    }

    /**
     * Change 6.1 + Onboarding Sections 4 & 6:
     * Mandatory pre-flight validator checking SDK readiness, SmartLife login, BLE/Wi-Fi permissions,
     * OS Location toggle, BSSID 2.4 GHz support, and 5 GHz active-tablet guard for pure UDP EZ mode.
     */
    fun validateProvisioningPreflight(
        context: Context,
        wifiSsid: String,
        pairingMode: TuyaPairingMode
    ): TuyaPreflightCheckResult {
        val appCtx = context.applicationContext
        val activeFreq = getCurrentWifiFrequencyMhz(appCtx)

        if (!ClaraApplication.isTuyaSdkReady(appCtx)) {
            runCatching { ClaraApplication.ensureTuyaSdkInitialized(appCtx, true) }
        }
        if (!ClaraApplication.isTuyaSdkReady(appCtx)) {
            return TuyaPreflightCheckResult(
                passed = false,
                failureTitle = "Smart Home SDK Not Ready",
                remediationMessage = "Tuya App SDK credentials are not initialized yet. Enter your Tuya AppKey and AppSecret in Control Center under Application Settings.",
                protocolResultCode = "TUYA_SDK_UNINITIALIZED",
                tabletFrequencyMhz = activeFreq
            )
        }

        val fineLocationGranted = appCtx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val nearbyWifiGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appCtx.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED
        } else fineLocationGranted
        if (!fineLocationGranted && !nearbyWifiGranted) {
            return TuyaPreflightCheckResult(
                passed = false,
                failureTitle = "Wi-Fi & Location Permission Needed",
                remediationMessage = "Grant Nearby Wi-Fi Devices and Fine Location permissions in Android Settings so Clara can verify 2.4 GHz networks.",
                protocolResultCode = "WIFI_PERMISSION_DENIED",
                tabletFrequencyMhz = activeFreq
            )
        }

        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val osLocationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm?.isLocationEnabled == true
        } else true
        if (!osLocationEnabled) {
            return TuyaPreflightCheckResult(
                passed = false,
                failureTitle = "Turn On Location Services",
                remediationMessage = "Android requires Location Services to be switched ON to read 2.4 GHz Wi-Fi bands and nearby setup beacons.",
                protocolResultCode = "OS_LOCATION_DISABLED",
                tabletFrequencyMhz = activeFreq
            )
        }

        if (pairingMode == TuyaPairingMode.BLE_DIRECT) {
            val bleGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appCtx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                        appCtx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else fineLocationGranted
            val btManager = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val btEnabled = btManager?.adapter?.isEnabled == true
            if (!bleGranted || !btEnabled) {
                return TuyaPreflightCheckResult(
                    passed = false,
                    failureTitle = if (!bleGranted) "Bluetooth Permission Needed" else "Turn On Bluetooth",
                    remediationMessage = if (!bleGranted) {
                        "Allow Nearby Devices (Bluetooth Scan & Connect) permission to pair this device directly."
                    } else {
                        "Turn on Bluetooth on your tablet to send Wi-Fi credentials directly to this device."
                    },
                    protocolResultCode = if (!bleGranted) "BLE_PERMISSION_DENIED" else "BLUETOOTH_OFF",
                    tabletFrequencyMhz = activeFreq
                )
            }
        }

        val cleanSsid = wifiSsid.trim()
        if (cleanSsid.isNotEmpty() && !isPlugHotspotSsid(cleanSsid)) {
            val bandStatus = validateSsidSupports24GhzByBssid(appCtx, cleanSsid)
            if (bandStatus == WifiBandValidationStatus.BLOCKED_5GHZ_OR_6GHZ_ONLY) {
                return TuyaPreflightCheckResult(
                    passed = false,
                    failureTitle = "2.4 GHz Wi-Fi Required",
                    remediationMessage = "'$cleanSsid' only broadcasts on 5 GHz / 6 GHz. Smart plugs and sensors use 2.4 GHz Wi-Fi hardware — select a 2.4 GHz or dual-band network.",
                    protocolResultCode = "SSID_5GHZ_ONLY_BLOCKED",
                    tabletFrequencyMhz = activeFreq
                )
            }
        }

        // Onboarding Section 4: Redirect pure UDP EZ broadcast to AP Mode if tablet is actively on 5 GHz / 6 GHz
        if (pairingMode == TuyaPairingMode.PURE_EZ_BROADCAST && isTabletOn5GhzBand(appCtx)) {
            return TuyaPreflightCheckResult(
                passed = false,
                shouldRedirectPureEzToApMode = true,
                failureTitle = "Tablet Connected on 5 GHz ($activeFreq MHz)",
                remediationMessage = "Your tablet is currently on 5 GHz Wi-Fi, which blocks 2.4 GHz EZ broadcast packets. Switch to Slow Blinking / AP Mode (or connect the tablet to 2.4 GHz) for reliable setup.",
                protocolResultCode = "TABLET_ON_5GHZ_REDIRECT_AP",
                tabletFrequencyMhz = activeFreq
            )
        }

        return TuyaPreflightCheckResult(
            passed = true,
            tabletFrequencyMhz = activeFreq
        )
    }

    // -----------------------------------------------------------------------
    // PERSISTED IN-RANGE HOME WI-FI CACHE
    // -----------------------------------------------------------------------
    private fun saveCachedInRangeWifiNetworks(context: Context, networks: List<ScannedWifiNetwork>) {
        if (networks.isEmpty()) return
        try {
            val arr = JSONArray()
            for (net in networks.take(35)) {
                if (net.ssid.isBlank() || isPlugHotspotSsid(net.ssid)) continue
                arr.put(
                    JSONObject().apply {
                        put("ssid", net.ssid)
                        put("is24Ghz", net.is24Ghz)
                        put("is5Ghz", net.is5Ghz)
                        put("levelDbm", net.levelDbm)
                        if (net.bssid24Ghz.isNotBlank()) {
                            put("bssid24Ghz", net.bssid24Ghz)
                        }
                    }
                )
            }
            if (arr.length() > 0) {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CACHED_WIFI_LIST_JSON, arr.toString())
                    .apply()
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "saveCachedInRangeWifiNetworks",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
        }
    }

    private fun loadCachedInRangeWifiNetworks(context: Context): List<ScannedWifiNetwork> {
        return try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_CACHED_WIFI_LIST_JSON, null)
            val list = mutableListOf<ScannedWifiNetwork>()
            if (!raw.isNullOrBlank()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val ssid = o.optString("ssid", "").trim()
                    if (ssid.isNotBlank() && !isPlugHotspotSsid(ssid)) {
                        list.add(
                            ScannedWifiNetwork(
                                ssid = ssid,
                                is24Ghz = o.optBoolean("is24Ghz", true),
                                is5Ghz = o.optBoolean("is5Ghz", false),
                                levelDbm = o.optInt("levelDbm", -55),
                                bssid24Ghz = o.optString("bssid24Ghz", "").trim()
                            )
                        )
                    }
                }
            }
            val savedPrimarySsid = prefs.getString("wifi_ssid", "")?.trim().orEmpty()
            if (savedPrimarySsid.isNotBlank() &&
                !isPlugHotspotSsid(savedPrimarySsid) &&
                list.none { it.ssid.equals(savedPrimarySsid, ignoreCase = true) }
            ) {
                list.add(
                    0,
                    ScannedWifiNetwork(
                        ssid = savedPrimarySsid,
                        is24Ghz = true,
                        is5Ghz = false,
                        levelDbm = -45
                    )
                )
            }
            list
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "loadCachedInRangeWifiNetworks",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun parseWifiManagerScanResults(
        context: Context,
        wm: WifiManager,
        rawResults: List<WifiScanResult>
    ): List<ScannedWifiNetwork> {
        val grouped = LinkedHashMap<String, ScannedWifiNetwork>()
        val best24LevelBySsid = HashMap<String, Int>()
        val currentSsid = detectCurrentWifiSsid(context)
        if (currentSsid.isNotBlank() && !isPlugHotspotSsid(currentSsid)) {
            val freq = getCurrentWifiFrequencyMhz(context)
            val curBssid = runCatching { wm.connectionInfo?.bssid.orEmpty() }.getOrDefault("")
            val isCur24 = freq in 2400..2499
            val isCur5Or6 = freq >= 4900
            grouped[currentSsid] = ScannedWifiNetwork(
                ssid = currentSsid,
                is24Ghz = isCur24 || freq == 0,
                is5Ghz = isCur5Or6,
                levelDbm = -40,
                bssid24Ghz = if (isCur24 && curBssid != "02:00:00:00:00:00") curBssid else ""
            )
            if (isCur24 && curBssid.isNotBlank()) {
                best24LevelBySsid[currentSsid] = -40
            }
        }

        for (sr in rawResults) {
            val ssid = sr.SSID?.trim()?.removePrefix("\"")?.removeSuffix("\"") ?: continue
            if (ssid.isBlank() ||
                ssid.equals("<unknown ssid>", ignoreCase = true) ||
                isPlugHotspotSsid(ssid)
            ) {
                continue
            }
            val is24 = sr.frequency in 2400..2499
            val is5 = sr.frequency >= 4900
            val candidateBssid = sr.BSSID?.trim().orEmpty()
            val prevBest24 = best24LevelBySsid[ssid] ?: -120
            val shouldUpdate24Bssid = is24 && candidateBssid.isNotBlank() && sr.level >= prevBest24
            if (shouldUpdate24Bssid) {
                best24LevelBySsid[ssid] = sr.level
            }

            val existing = grouped[ssid]
            if (existing == null) {
                grouped[ssid] = ScannedWifiNetwork(
                    ssid = ssid,
                    is24Ghz = is24,
                    is5Ghz = is5,
                    levelDbm = sr.level,
                    bssid24Ghz = if (is24) candidateBssid else ""
                )
            } else {
                grouped[ssid] = existing.copy(
                    is24Ghz = existing.is24Ghz || is24,
                    is5Ghz = existing.is5Ghz || is5,
                    levelDbm = maxOf(existing.levelDbm, sr.level),
                    bssid24Ghz = if (shouldUpdate24Bssid) candidateBssid else existing.bssid24Ghz
                )
            }
        }

        val cachedList = loadCachedInRangeWifiNetworks(context)
        for (cached in cachedList) {
            if (!grouped.containsKey(cached.ssid)) {
                grouped[cached.ssid] = cached
            }
        }

        if (grouped.isEmpty()) {
            val configured = try {
                wm.configuredNetworks ?: emptyList()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = TAG,
                    operationStage = "parseWifiManagerScanResults_configuredNetworks",
                    logToDiscoveryRecorder = true,
                    surfaceToUiBanner = true
                )
                emptyList()
            }
            for (cfg in configured) {
                val ssid = cfg.SSID?.trim()?.removePrefix("\"")?.removeSuffix("\"") ?: continue
                if (ssid.isNotBlank() && !ssid.equals("<unknown ssid>", ignoreCase = true) && !isPlugHotspotSsid(ssid)) {
                    grouped.putIfAbsent(
                        ssid,
                        ScannedWifiNetwork(
                            ssid = ssid,
                            is24Ghz = true,
                            is5Ghz = false,
                            levelDbm = -55
                        )
                    )
                }
            }
        }

        val sorted = grouped.values.sortedWith(
            compareByDescending<ScannedWifiNetwork> { it.ssid == currentSsid }
                .thenByDescending { it.is24Ghz }
                .thenByDescending { it.levelDbm }
        )

        if (rawResults.isNotEmpty()) {
            saveCachedInRangeWifiNetworks(context, sorted)
        }
        JarvisLiveBridge.updateAvailableWifiNetworks(sorted)
        return sorted
    }

    /**
     * Synchronously returns the current + cached in-range Wi-Fi networks AND triggers
     * an asynchronous OS Wi-Fi scan that updates [JarvisLiveBridge.availableWifiNetworksFlow]
     * as soon as `SCAN_RESULTS_AVAILABLE_ACTION` fires.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun scanAvailableWifiNetworks(
        context: Context,
        onAsyncUpdate: ((List<ScannedWifiNetwork>) -> Unit)? = null
    ): List<ScannedWifiNetwork> {
        return try {
            val appCtx = context.applicationContext
            val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return loadCachedInRangeWifiNetworks(appCtx)

            try {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        try {
                            appCtx.unregisterReceiver(this)
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                                t = e,
                                transportSource = TAG,
                                operationStage = "scanAvailableWifiNetworks_unregisterReceiver",
                                logToDiscoveryRecorder = true,
                                surfaceToUiBanner = true
                            )
                        }
                        val freshRaw = runCatching { wm.scanResults.orEmpty() }.getOrDefault(emptyList())
                        val updatedList = parseWifiManagerScanResults(appCtx, wm, freshRaw)
                        JarvisLiveBridge.setScanningWifiNetworks(false)
                        onAsyncUpdate?.invoke(updatedList)
                    }
                }
                val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    appCtx.registerReceiver(receiver, filter)
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = TAG,
                    operationStage = "scanAvailableWifiNetworks_registerReceiver",
                    logToDiscoveryRecorder = true,
                    surfaceToUiBanner = true
                )
            }

            try {
                wm.startScan()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = TAG,
                    operationStage = "scanAvailableWifiNetworks_startScan",
                    logToDiscoveryRecorder = true,
                    surfaceToUiBanner = true
                )
            }

            val immediateRaw = try {
                wm.scanResults ?: emptyList()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = TAG,
                    operationStage = "scanAvailableWifiNetworks_scanResults",
                    logToDiscoveryRecorder = true,
                    surfaceToUiBanner = true
                )
                emptyList()
            }
            parseWifiManagerScanResults(appCtx, wm, immediateRaw)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "scanAvailableWifiNetworks",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            Log.w(TAG, "Wi-Fi scan note: ${e.message}")
            loadCachedInRangeWifiNetworks(context)
        }
    }

    /**
     * Suspend version of [scanAvailableWifiNetworks] that waits up to [maxWaitMs] for Android's
     * `SCAN_RESULTS_AVAILABLE_ACTION` broadcast if the immediate scan list is empty.
     */
    suspend fun scanAvailableWifiNetworksSuspend(
        context: Context,
        maxWaitMs: Long = 1600L
    ): List<ScannedWifiNetwork> = withContext(Dispatchers.IO) {
        val initial = scanAvailableWifiNetworks(context)
        if (initial.size >= 2) return@withContext initial
        val fresh = withTimeoutOrNull(maxWaitMs) {
            suspendCancellableCoroutine<List<ScannedWifiNetwork>> { cont ->
                scanAvailableWifiNetworks(context) { updated ->
                    if (cont.isActive && updated.isNotEmpty()) {
                        cont.resume(updated)
                    }
                }
            }
        }
        fresh ?: JarvisLiveBridge.availableWifiNetworksFlow.value.ifEmpty { initial }
    }

    // -----------------------------------------------------------------------
    // STAGE 1: DISCOVERABLE BLE (0xFD50, 0xFEAF, 0xFFF6, 0xEC88) & SOFT-AP SCANNER
    // -----------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun discoverNearbyBleAndSoftApDevices(
        context: Context,
        scanDurationMs: Long = 1800L
    ): List<DiscoveredLanDevice> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val discovered = ConcurrentHashMap<String, DiscoveredLanDevice>()

        // 1. Scan Wi-Fi for live SoftAP Setup Hotspots (purging cached ghosts > 15s / 40s throttled)
        try {
            val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val scanTriggered = runCatching { wm?.startScan() ?: false }.getOrDefault(false)
            if (scanTriggered) {
                delay(450L)
            }
            val nowBootMicros = runCatching { SystemClock.elapsedRealtimeNanos() / 1_000L }
                .getOrElse { SystemClock.elapsedRealtime() * 1_000L }
            val maxApAgeMicros = (if (scanTriggered) 15L else 40L) * 1_000_000L
            val scanResults = wm?.scanResults.orEmpty()
            val seenApKeysInPass = HashSet<String>()
            val nonSmartPrefixes = listOf(
                "DIRECT-", "HP-Print", "HP-Setup", "EPSON", "Canon", "Brother",
                "GoPro", "DJI-", "SONY-", "iPhone", "Galaxy", "AndroidAP",
                "BTWi-fi", "Sky", "VirginMedia", "TalkTalk", "Vodafone", "EE-", "Plusnet"
            )
            for (sr in scanResults) {
                val ssid = sr.SSID?.trim()?.removePrefix("\"")?.removeSuffix("\"").orEmpty()
                val bssid = sr.BSSID?.trim().orEmpty()
                val apDedupeKey = "${ssid.lowercase(Locale.US)}::$bssid"
                if (!seenApKeysInPass.add(apDedupeKey)) continue

                if (ssid.isBlank()) continue
                val match = isAnySmartHomeSetupHotspot(ssid)
                if (match == null) {
                    val isBlacklistedPrefix = nonSmartPrefixes.any { ssid.startsWith(it, ignoreCase = true) }
                    if (isBlacklistedPrefix) {
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.FILTERED,
                            transportSource = "WIFI_SOFTAP_SCAN",
                            hardwareId = ssid,
                            macAddress = CrossEcosystemIdentityCorrelator.normalizeMac(bssid),
                            vendor = ssid,
                            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                            outcomeReason = "Excluded printer / Wi-Fi Direct / ISP router SSID ($ssid)",
                            wifiFrequencyMhz = sr.frequency,
                            rssiDbm = sr.level
                        )
                    }
                    continue
                }

                // Onboarding Section 1: Purge cached SmartLife-XXXX AP hotspot ghosts older than 15s
                val ageMicros = (nowBootMicros - sr.timestamp).coerceAtLeast(0L)
                if (ageMicros > maxApAgeMicros) {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FILTERED,
                        transportSource = "WIFI_SOFTAP_SCAN",
                        hardwareId = ssid,
                        macAddress = CrossEcosystemIdentityCorrelator.normalizeMac(bssid),
                        vendor = ssid,
                        qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                        outcomeReason = "Purged cached SoftAP hotspot '$ssid' (age=${ageMicros / 1_000_000L}s)",
                        wifiFrequencyMhz = sr.frequency,
                        rssiDbm = sr.level,
                        protocolResultCode = "STALE_AP_CACHED"
                    )
                    continue
                }

                DeviceRepository.unmarkDeviceDismissed(appCtx, bssid)
                val (tileType, brandOpt) = match
                val key = "ap_$ssid"
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.IDENTIFIED,
                    transportSource = "WIFI_SOFTAP_SCAN",
                    hardwareId = ssid,
                    macAddress = CrossEcosystemIdentityCorrelator.normalizeMac(bssid),
                    vendor = "${brandOpt.defaultTileTitle} ($ssid)",
                    confidenceScore = 0.94f,
                    qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                    outcomeReason = "Matched live SoftAP setup hotspot (age=${ageMicros / 1_000L}ms, ${sr.level} dBm)",
                    wifiFrequencyMhz = sr.frequency,
                    rssiDbm = sr.level,
                    protocolResultCode = "SOFTAP_LIVE"
                )
                discovered[key] = DiscoveredLanDevice(
                    ipAddress = ssid,
                    port = 0,
                    suggestedName = "${brandOpt.defaultTileTitle} ($ssid)",
                    suggestedType = tileType,
                    detail = "Wi-Fi Hotspot '$ssid' \u2022 Ready for AP Mode setup",
                    companionPackage = "",
                    actionType = DiscoveredDeviceActionType.START_PAIRING,
                    protocolBadge = "AP Hotspot Setup",
                    hardwareIdOrMac = bssid,
                    brandOption = brandOpt
                )
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "discoverNearbyBleAndSoftApDevices_wifi",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            Log.w(TAG, "SoftAP discovery note: ${e.message}")
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.FAILED,
                transportSource = "WIFI_SOFTAP_SCAN",
                vendor = "Android WifiManager",
                qualificationStage = "discoverNearbyBleAndSoftApDevices.wifi",
                outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "SoftAP Wi-Fi scan failed"}"
            )
        }

        // 2. Scan Tuya BLE Operator (only if ThingHomeSdk is initialized and an active pairing session isn't running)
        if (!isPairingActive && ClaraApplication.isTuyaSdkReady(appCtx)) {
            try {
                withContext(Dispatchers.Main) {
                    runCatching { ThingHomeSdk.getBleOperator().stopLeScan() }
                    val scanSetting = LeScanSetting.Builder()
                        .setTimeout(scanDurationMs)
                        .addScanType(ScanType.SINGLE)
                        .addScanType(ScanType.MESH)
                        .addScanType(ScanType.SIG_MESH)
                        .build()
                    ThingHomeSdk.getBleOperator().startLeScan(
                        scanSetting,
                        BleScanResponse { scanBean: ScanDeviceBean? ->
                            if (scanBean != null) {
                                val id = scanBean.id?.trim().orEmpty()
                                val uuid = scanBean.uuid?.trim().orEmpty()
                                val mac = scanBean.mac?.trim().orEmpty()
                                val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(mac)
                                val name = scanBean.name?.trim().orEmpty()
                                val key = uuid.ifBlank { mac.ifBlank { id.ifBlank { "tuya_ble_${discovered.size + 1}" } } }
                                val observedRssi = SmartHomeApiClient.getLastObservedBleRssi(normMac) ?: -65

                                // Onboarding Section 1: Reject already-paired Tuya BLE local-control beacons & combo MACs
                                val isBoundFlagSet = (scanBean.flag and 0x80) != 0
                                val isAlreadyComboOnboarded = normMac.isNotEmpty() && (
                                        ConnectedIdentityIndex.isAlreadyOnboarded("TUYA", normMac, normMac) ||
                                                !CrossEcosystemIdentityCorrelator.resolveCanonicalGuidForCandidate(rawMac = normMac).isNullOrBlank()
                                        )
                                if (isBoundFlagSet || isAlreadyComboOnboarded || observedRssi < -88) {
                                    DiscoveryFlightRecorder.record(
                                        eventType = DiscoveryEventType.FILTERED,
                                        transportSource = "TUYA_BLE",
                                        hardwareId = key,
                                        macAddress = normMac,
                                        vendor = name.ifBlank { "Tuya BLE" },
                                        qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                        outcomeReason = when {
                                            isBoundFlagSet -> "Ignored already-paired Tuya BleOperator beacon (flag 0x80=1)"
                                            isAlreadyComboOnboarded -> "Suppressed combo BLE plug matching onboarded Wi-Fi MAC"
                                            else -> "Dropped weak BLE setup beacon ($observedRssi dBm < -88 dBm floor)"
                                        },
                                        rssiDbm = observedRssi,
                                        protocolResultCode = if (isBoundFlagSet) "BLE_0xFD50_BOUND" else "BLE_FILTERED"
                                    )
                                    return@BleScanResponse
                                }

                                discoveredUnpairedBleBeans[key] = scanBean
                                if (mac.isNotBlank()) discoveredUnpairedBleBeans[mac] = scanBean
                                if (normMac.isNotBlank()) discoveredUnpairedBleBeans[normMac] = scanBean

                                val isLytmi = name.contains("lytmi", ignoreCase = true) || name.contains("neo", ignoreCase = true)
                                val isAppliance = name.contains("cosori", ignoreCase = true) ||
                                        name.contains("fryer", ignoreCase = true) ||
                                        name.contains("kettle", ignoreCase = true)
                                val labelPrefix = when {
                                    isLytmi -> "Lytmi Sync Box"
                                    isAppliance -> name.ifBlank { "Smart Kitchen Appliance" }
                                    name.isNotBlank() && !name.equals("TY", ignoreCase = true) -> name
                                    else -> "Smart Plug / Switch"
                                }
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.IDENTIFIED,
                                    transportSource = "TUYA_BLE",
                                    hardwareId = key,
                                    macAddress = normMac,
                                    vendor = labelPrefix,
                                    confidenceScore = 0.95f,
                                    qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                                    outcomeReason = "Verified unconfigured Tuya BLE setup beacon",
                                    rssiDbm = observedRssi,
                                    protocolResultCode = "TUYA_BLE_UNBOUND"
                                )
                                discovered["tuyable_${normMac.ifBlank { key }}"] = DiscoveredLanDevice(
                                    ipAddress = mac.ifBlank { uuid },
                                    port = 0,
                                    suggestedName = labelPrefix,
                                    suggestedType = when {
                                        isLytmi -> TileType.LYTMI_SYNC_BOX
                                        isAppliance -> TileType.SMARTLIFE_DEVICE
                                        else -> TileType.SMARTKNIGHT_PLUG
                                    },
                                    detail = "Ready for Wi-Fi setup \u2022 Strong Bluetooth signal",
                                    companionPackage = "",
                                    actionType = DiscoveredDeviceActionType.START_PAIRING,
                                    protocolBadge = "Bluetooth Setup",
                                    hardwareIdOrMac = mac.ifBlank { key },
                                    brandOption = if (isLytmi) BrandOption.LYTMI else BrandOption.SMARTKNIGHT
                                )
                            }
                        }
                    )
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                    t = t,
                    transportSource = TAG,
                    operationStage = "discoverNearbyBleAndSoftApDevices",
                    logToDiscoveryRecorder = true,
                    surfaceToUiBanner = true
                )
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FAILED,
                    transportSource = "TUYA_BLE",
                    vendor = "ThingHomeSdk BleOperator",
                    qualificationStage = "discoverNearbyBleAndSoftApDevices.tuyaBle",
                    outcomeReason = "${t.javaClass.simpleName}: ${t.message ?: "Tuya BLE scan failed"}"
                )
            }
        }

        // 3. Standard Android BluetoothLeScanner for 0xFEAF (Nest Weave), 0xFFF6 (Matter BLE),
        //    0xFD50 (Tuya BLE), 0xEC88 (Govee), and strict prefix-matched BLE devices
        var nativeBleScanner: android.bluetooth.le.BluetoothLeScanner? = null
        var nativeBleCallback: ScanCallback? = null
        val seenBleAddressesInPass = ConcurrentHashMap.newKeySet<String>()
        try {
            val btManager = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val btAdapter = btManager?.adapter
            if (btAdapter == null || !btAdapter.isEnabled) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FILTERED,
                    transportSource = "ANDROID_BLE",
                    vendor = "BluetoothAdapter",
                    qualificationStage = "discoverNearbyBleAndSoftApDevices.ble",
                    outcomeReason = "Bluetooth adapter unavailable or turned off; BLE scan skipped"
                )
            } else {
                nativeBleScanner = btAdapter.bluetoothLeScanner
                nativeBleCallback = object : ScanCallback() {
                    override fun onScanFailed(errorCode: Int) {
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.FAILED,
                            transportSource = "ANDROID_BLE",
                            vendor = "BluetoothLeScanner",
                            qualificationStage = "BluetoothLeScanner.onScanFailed",
                            outcomeReason = "Android BLE scan failed (errorCode=$errorCode)"
                        )
                    }

                    override fun onScanResult(callbackType: Int, result: ScanResult?) {
                        val r = result ?: return
                        val scanRec = r.scanRecord
                        val devName = scanRec?.deviceName?.trim()
                            ?: r.device?.name?.trim()
                            ?: ""
                        val mac = r.device?.address?.trim().orEmpty()
                        val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(mac)
                        val serviceUuids = scanRec?.serviceUuids.orEmpty()
                        val serviceData = scanRec?.serviceData.orEmpty()

                        val dedupeId = mac.ifBlank { devName }.ifBlank { "anon_${r.rssi}" }
                        val isFirstSightingInPass = seenBleAddressesInPass.add(dedupeId)
                        val uuidsSummary = serviceUuids.joinToString(",") { it.uuid.toString().take(8) }

                        if (isFirstSightingInPass) {
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                                transportSource = "ANDROID_BLE",
                                hardwareId = mac.ifBlank { devName },
                                macAddress = normMac,
                                vendor = devName.ifBlank { "BLE Peripheral" },
                                confidenceScore = 0.15f,
                                qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                                outcomeReason = "BLE advertisement received (${r.rssi} dBm)",
                                rawPayload = "name=$devName uuids=$uuidsSummary"
                            )
                        }

                        // Onboarding Section 1: Drop distant/neighbouring BLE setup beacons below -88 dBm floor
                        if (r.rssi < -88) {
                            if (isFirstSightingInPass) {
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.FILTERED,
                                    transportSource = "ANDROID_BLE",
                                    hardwareId = mac,
                                    macAddress = normMac,
                                    vendor = devName.ifBlank { "Distant BLE Beacon" },
                                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                    outcomeReason = "Dropped weak BLE beacon (${r.rssi} dBm < -88 dBm floor)",
                                    rssiDbm = r.rssi,
                                    protocolResultCode = "BLE_RSSI_BELOW_FLOOR"
                                )
                            }
                            return
                        }

                        // Ignore already-onboarded devices (including +1/-1/+2/-2 combo MAC offsets)
                        if (mac.isNotBlank() && (
                                    ConnectedIdentityIndex.isAlreadyOnboarded("TUYA", mac, mac) ||
                                            !CrossEcosystemIdentityCorrelator.resolveCanonicalGuidForCandidate(rawMac = normMac).isNullOrBlank()
                                    )
                        ) {
                            if (isFirstSightingInPass) {
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.FILTERED,
                                    transportSource = "ANDROID_BLE",
                                    hardwareId = mac,
                                    macAddress = normMac,
                                    vendor = devName.ifBlank { "Onboarded BLE Device" },
                                    qualificationStage = "STAGE_4_VALIDATED_DEVICE",
                                    outcomeReason = "SUPPRESSED_ALREADY_ONBOARDED_GUID (BLE MAC $mac)",
                                    rssiDbm = r.rssi,
                                    protocolResultCode = "COMBO_MAC_ONBOARDED"
                                )
                            }
                            return
                        }
                        val fd50Payload = serviceData[TUYA_FD50_UUID]
                            ?: serviceData.entries.firstOrNull {
                                it.key.uuid.toString().startsWith("0000fd50", ignoreCase = true)
                            }?.value
                        val tuyaMfgPayload = scanRec?.getManufacturerSpecificData(0x07D0)
                        val mfgBound = tuyaMfgPayload != null &&
                                tuyaMfgPayload.isNotEmpty() &&
                                (tuyaMfgPayload[0].toInt() and 0x80) != 0
                        if ((fd50Payload != null && !isUnboundTuyaFd50ServiceData(fd50Payload)) || mfgBound) {
                            if (isFirstSightingInPass) {
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.FILTERED,
                                    transportSource = "ANDROID_BLE",
                                    hardwareId = mac,
                                    macAddress = normMac,
                                    vendor = devName.ifBlank { "Tuya 0xFD50" },
                                    qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                    outcomeReason = "Filtered already-bound Tuya 0xFD50 / 0x07D0 local-control beacon (isBound=1)",
                                    rssiDbm = r.rssi,
                                    protocolResultCode = "BLE_0xFD50_BOUND"
                                )
                            }
                            return
                        }
                        val hasTuyaFd50Beacon = (fd50Payload != null && isUnboundTuyaFd50ServiceData(fd50Payload)) ||
                                serviceUuids.any {
                                    it == TUYA_FE07_UUID ||
                                            it.uuid.toString().startsWith("0000fe07", ignoreCase = true) ||
                                            it.uuid.toString().startsWith("0000a201", ignoreCase = true)
                                }

                        if (hasTuyaFd50Beacon && mac.isNotBlank()) {
                            DeviceRepository.unmarkDeviceDismissed(appCtx, mac)
                        }

                        val hasNestBleBeacon = serviceUuids.any {
                            it == NEST_WEAVE_BLE_UUID ||
                                    it.uuid.toString().startsWith("0000feaf", ignoreCase = true)
                        } || serviceData.keys.any {
                            it == NEST_WEAVE_BLE_UUID ||
                                    it.uuid.toString().startsWith("0000feaf", ignoreCase = true)
                        }

                        val hasMatterBleBeacon = serviceUuids.any {
                            it == MATTER_BLE_UUID ||
                                    it.uuid.toString().startsWith("0000fff6", ignoreCase = true)
                        } || serviceData.keys.any {
                            it == MATTER_BLE_UUID ||
                                    it.uuid.toString().startsWith("0000fff6", ignoreCase = true)
                        }

                        val hasGoveeBleUuid = serviceUuids.any {
                            it == GOVEE_BLE_UUID ||
                                    it.uuid.toString().startsWith("0000ec88", ignoreCase = true) ||
                                    it.uuid.toString().startsWith("0000880", ignoreCase = true)
                        }

                        val lower = devName.lowercase(Locale.US)
                        val isTuyaRadiatorHint = lower.startsWith("radiator") || lower.startsWith("trv_") || lower.startsWith("wk_")

                        // Stage 1 Fix: Require an explicit hardware prefix when no verified BLE service UUID is advertised
                        val matchedBrandAndType: Pair<TileType, BrandOption>? = when {
                            hasNestBleBeacon || lower.startsWith("nest-") || lower.startsWith("nest_") ||
                                    (hasMatterBleBeacon && lower.startsWith("nest")) ->
                                TileType.NEST_THERMOSTAT to BrandOption.NEST
                            hasTuyaFd50Beacon && isTuyaRadiatorHint ->
                                TileType.TUYA_RADIATOR to BrandOption.TUYA
                            lower.startsWith("lytmi") || lower.startsWith("neo_sync") ->
                                TileType.LYTMI_SYNC_BOX to BrandOption.LYTMI
                            hasGoveeBleUuid || lower.startsWith("govee_") || lower.startsWith("ihoment_") ||
                                    lower.startsWith("gbk_") || lower.startsWith("h60") || lower.startsWith("h61") || lower.startsWith("h70") ->
                                TileType.GOVEE_LIGHT to BrandOption.GOVEE
                            lower.startsWith("twinkly_") || lower.startsWith("twinkly-") || lower.startsWith("twk_") ->
                                TileType.TWINKLY_LIGHT to BrandOption.TWINKLY
                            lower.startsWith("cosori") || lower.startsWith("vesync") || lower.startsWith("caf-") ||
                                    lower.startsWith("cs1") || lower.startsWith("cs3") || lower.startsWith("airfryer") ||
                                    lower.startsWith("kettle") ->
                                TileType.SMARTLIFE_DEVICE to BrandOption.SMART_LIFE
                            hasTuyaFd50Beacon || lower == "ty" || lower.startsWith("ty_") || lower.startsWith("smartlife") ||
                                    lower.startsWith("tuya") || lower.startsWith("sl-") || lower.startsWith("smartknight") ||
                                    lower.startsWith("jisusu") || lower.startsWith("hyleton") ->
                                TileType.SMARTKNIGHT_PLUG to BrandOption.SMARTKNIGHT
                            lower.startsWith("tapo_") || lower.startsWith("kasa_") ->
                                TileType.TAPO_DEVICE to BrandOption.TAPO
                            hasMatterBleBeacon || lower.startsWith("matter_") || lower.startsWith("eve_") ||
                                    lower.startsWith("aqara_") || lower.startsWith("nanoleaf_") || lower.startsWith("switchbot_") ->
                                TileType.MATTER_DEVICE to BrandOption.MATTER
                            lower.startsWith("hue_") || lower.startsWith("wiz_") ->
                                TileType.HUE_LIGHT to BrandOption.HUE
                            lower.startsWith("shelly") ->
                                TileType.SHELLY_RELAY to BrandOption.SHELLY
                            lower.startsWith("sonoff_") || lower.startsWith("itead-") || lower.startsWith("ewelink_") ->
                                TileType.SONOFF_SWITCH to BrandOption.SONOFF
                            lower.startsWith("airrobo_") || lower.startsWith("roborock_") ->
                                TileType.AIRROBO_VACUUM to BrandOption.AIRROBO
                            else -> null
                        }

                        if (matchedBrandAndType != null) {
                            val (tType, bOpt) = matchedBrandAndType
                            val suffix = mac.takeLast(5).ifBlank { "BLE" }
                            val resolvedName = when {
                                hasNestBleBeacon -> "Nest Learning Thermostat [$suffix]"
                                hasMatterBleBeacon && tType == TileType.NEST_THERMOSTAT -> "Nest Thermostat [$suffix]"
                                devName.isNotBlank() && !devName.equals("TY", ignoreCase = true) -> "$devName [$suffix]"
                                hasTuyaFd50Beacon -> "Smart Wireless Device [$suffix]"
                                hasMatterBleBeacon -> "Matter Smart Device [$suffix]"
                                else -> "${bOpt.defaultTileTitle} [$suffix]"
                            }
                            val badge = when {
                                hasNestBleBeacon -> "BLE 0xFEAF"
                                hasMatterBleBeacon -> "Matter BLE 0xFFF6"
                                hasTuyaFd50Beacon -> "BLE 0xFD50"
                                hasGoveeBleUuid -> "BLE 0xEC88"
                                else -> "Bluetooth LE"
                            }
                            val detailStr = when {
                                hasNestBleBeacon -> "Thermostat Setup Beacon \u2022 Enter 7-Char Entry Key (${r.rssi} dBm)"
                                hasMatterBleBeacon -> "Commissioning Beacon \u2022 Enter 11-Digit Setup Code (${r.rssi} dBm)"
                                else -> "$badge (${r.rssi} dBm) \u2022 Ready to Connect"
                            }
                            val key = "ble_${mac.ifBlank { resolvedName }}"
                            if (discovered.putIfAbsent(
                                    key,
                                    DiscoveredLanDevice(
                                        ipAddress = mac,
                                        port = 0,
                                        suggestedName = resolvedName,
                                        suggestedType = tType,
                                        detail = detailStr,
                                        companionPackage = "",
                                        actionType = DiscoveredDeviceActionType.START_PAIRING,
                                        protocolBadge = badge,
                                        hardwareIdOrMac = mac,
                                        brandOption = bOpt
                                    )
                                ) == null
                            ) {
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.IDENTIFIED,
                                    transportSource = "ANDROID_BLE",
                                    hardwareId = mac,
                                    macAddress = normMac,
                                    vendor = resolvedName,
                                    confidenceScore = 0.90f,
                                    qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                                    outcomeReason = "Matched $badge setup beacon (${r.rssi} dBm)"
                                )
                            }
                        } else if (isFirstSightingInPass) {
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.FILTERED,
                                transportSource = "ANDROID_BLE",
                                hardwareId = mac.ifBlank { devName },
                                macAddress = normMac,
                                vendor = devName.ifBlank { "BLE Peripheral" },
                                qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                                outcomeReason = "Filtered non-smart-home BLE peripheral without setup UUID or prefix"
                            )
                        }
                    }
                }
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                nativeBleScanner?.startScan(null, settings, nativeBleCallback)
                delay(scanDurationMs)
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "discoverNearbyBleAndSoftApDevices_ble",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.FAILED,
                transportSource = "ANDROID_BLE",
                vendor = "BluetoothLeScanner",
                qualificationStage = "discoverNearbyBleAndSoftApDevices.ble",
                outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "BLE scan error"}"
            )
        } finally {
            try {
                if (nativeBleScanner != null && nativeBleCallback != null) {
                    nativeBleScanner.stopScan(nativeBleCallback)
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = TAG,
                    operationStage = "discoverNearbyBleAndSoftApDevices_stopScan",
                    logToDiscoveryRecorder = true,
                    surfaceToUiBanner = true
                )
            }
        }

        val finalList = discovered.values.sortedBy { it.suggestedName }
        JarvisLiveBridge.discoveredBleAndApDevicesFlow.value = finalList
        finalList
    }

    private fun matchesBleTargetBean(
        scanBean: ScanDeviceBean,
        targetHardware: String,
        targetBeaconName: String
    ): Boolean {
        val combinedHint = "$targetHardware$targetBeaconName".trim()
        if (combinedHint.isBlank()) return true

        val bracketSuffix = Regex("\\[([0-9A-Fa-f:]{4,17})]")
            .find(combinedHint)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
            .uppercase(Locale.US)
            .replace(":", "")
            .replace("-", "")
            .trim()

        val cleanTarget = targetHardware
            .substringBefore(" (")
            .substringBefore(" [")
            .uppercase(Locale.US)
            .replace(":", "")
            .replace("-", "")
            .trim()

        val effectiveSuffix = bracketSuffix.ifBlank {
            if (cleanTarget.length >= 4 && cleanTarget.all { it in '0'..'9' || it in 'A'..'F' }) {
                cleanTarget.takeLast(4)
            } else ""
        }

        val beanMac = (scanBean.mac ?: "").uppercase(Locale.US).replace(":", "").replace("-", "").trim()
        val beanUuid = (scanBean.uuid ?: "").uppercase(Locale.US).replace(":", "").replace("-", "").trim()
        val beanAddr = (scanBean.address ?: "").uppercase(Locale.US).replace(":", "").replace("-", "").trim()
        val beanId = (scanBean.id ?: "").uppercase(Locale.US).replace(":", "").replace("-", "").trim()

        if (cleanTarget.length >= 4 && (
                    beanMac == cleanTarget ||
                            beanUuid == cleanTarget ||
                            beanAddr == cleanTarget ||
                            beanId == cleanTarget
                    )
        ) {
            return true
        }
        if (effectiveSuffix.length >= 4 && (
                    beanMac.endsWith(effectiveSuffix) ||
                            beanUuid.endsWith(effectiveSuffix) ||
                            beanAddr.endsWith(effectiveSuffix) ||
                            beanId.endsWith(effectiveSuffix)
                    )
        ) {
            return true
        }
        return false
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun findAndConnectToOpenPlugHotspot(
        context: Context,
        preferredHotspotSsidOrMac: String = "",
        onHotspotBound: ((String) -> Unit)? = null
    ): String? {
        return try {
            val appCtx = context.applicationContext
            val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null

            if (isConnectedToDeviceHotspot(appCtx)) {
                val activeSsid = detectCurrentWifiSsid(appCtx)
                onHotspotBound?.invoke(activeSsid)
                return activeSsid
            }

            try {
                wm.startScan()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = TAG,
                    operationStage = "scanAvailableWifiNetworks_startScan",
                    logToDiscoveryRecorder = true,
                    surfaceToUiBanner = true
                )
            }

            val cleanHint = preferredHotspotSsidOrMac
                .substringBefore(" (")
                .trim()
                .removePrefix("\"")
                .removeSuffix("\"")

            val scanList = runCatching { wm.scanResults.orEmpty() }.getOrDefault(emptyList())
            val matchedScanResult = scanList
                .filter { sr ->
                    val ssid = sr.SSID?.trim()?.removePrefix("\"")?.removeSuffix("\"").orEmpty()
                    isPlugHotspotSsid(ssid) || isAnySmartHomeSetupHotspot(ssid) != null
                }
                .sortedWith(
                    compareByDescending<WifiScanResult> { sr ->
                        val ssid = sr.SSID?.trim()?.removePrefix("\"")?.removeSuffix("\"").orEmpty()
                        val bssid = sr.BSSID?.trim().orEmpty()
                        cleanHint.isNotBlank() && (
                                ssid.equals(cleanHint, ignoreCase = true) ||
                                        bssid.equals(cleanHint, ignoreCase = true)
                                )
                    }.thenByDescending { it.level }
                )
                .firstOrNull()

            val plugHotspot = matchedScanResult
                ?.SSID
                ?.trim()
                ?.removePrefix("\"")
                ?.removeSuffix("\"")
                ?: cleanHint.takeIf { isPlugHotspotSsid(it) || isAnySmartHomeSetupHotspot(it) != null }

            if (!plugHotspot.isNullOrBlank()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    if (cm != null) {
                        softApNetworkCallback?.let { oldCb ->
                            runCatching { cm.unregisterNetworkCallback(oldCb) }
                            softApNetworkCallback = null
                        }
                        val specBuilder = WifiNetworkSpecifier.Builder()
                            .setSsid(plugHotspot)
                        val matchedBssid = matchedScanResult?.BSSID?.trim().orEmpty()
                        if (matchedBssid.length == 17 && matchedBssid != "02:00:00:00:00:00") {
                            runCatching { specBuilder.setBssid(MacAddress.fromString(matchedBssid)) }
                        }
                        val request = NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .setNetworkSpecifier(specBuilder.build())
                            .build()
                        val cb = object : ConnectivityManager.NetworkCallback() {
                            override fun onAvailable(network: Network) {
                                runCatching { cm.bindProcessToNetwork(network) }
                                Log.d(TAG, "Bound process to SoftAP network: $plugHotspot")
                                JarvisLiveBridge.updatePairingTimeline(
                                    PairingTimelineStage.CONNECTING_WIFI,
                                    "Joined '$plugHotspot' hotspot! Delivering home Wi-Fi credentials..."
                                )
                                onHotspotBound?.invoke(plugHotspot)
                                // Keep SoftAP bound long enough for AP socket credential delivery (25s guard)
                                softApAutoUnbindJob?.cancel()
                                softApAutoUnbindJob = pairingScope.launch {
                                    delay(25_000L)
                                    releaseSoftApNetworkBinding(appCtx)
                                }
                            }

                            override fun onLost(network: Network) {
                                Log.d(TAG, "SoftAP hotspot '$plugHotspot' closed by rebooting device -> verifying Home Wi-Fi reconnect")
                                releaseSoftApNetworkBinding(appCtx, startDiscoveryAcceleration = false)
                                pairingScope.launch {
                                    awaitHomeWifiReconnect(appCtx, getSavedWifiSsid(appCtx), timeoutMs = 15_000L)
                                    startPostOnboardingDiscoveryAcceleration(appCtx)
                                    if (isPairingActive) {
                                        JarvisLiveBridge.updatePairingTimeline(
                                            PairingTimelineStage.REGISTERING_CLOUD,
                                            "Rejoined Home Wi-Fi! Registering device & binding security keys..."
                                        )
                                    }
                                }
                            }
                        }
                        softApNetworkCallback = cb
                        runCatching { cm.requestNetwork(request, cb) }
                    }
                } else {
                    val openConfig = WifiConfiguration().apply {
                        SSID = "\"$plugHotspot\""
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    }
                    val netId = wm.addNetwork(openConfig)
                    if (netId != -1) {
                        wm.disconnect()
                        wm.enableNetwork(netId, true)
                        wm.reconnect()
                        Log.d(TAG, "Auto-connecting tablet to open plug hotspot: $plugHotspot")
                        onHotspotBound?.invoke(plugHotspot)
                    }
                }
                plugHotspot
            } else {
                null
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "findAndConnectToOpenPlugHotspot",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            Log.w(TAG, "Auto-connect plug hotspot note: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun resolve24GhzBssidForSsid(context: Context, targetSsid: String): String {
        val cleanTarget = targetSsid.trim().removePrefix("\"").removeSuffix("\"")
        if (cleanTarget.isBlank()) return ""
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val rawResults = runCatching { wm?.scanResults.orEmpty() }.getOrDefault(emptyList())
        val liveBest = rawResults
            .filter { sr ->
                val s = sr.SSID?.trim()?.removePrefix("\"")?.removeSuffix("\"").orEmpty()
                s.equals(cleanTarget, ignoreCase = true) && sr.frequency in 2400..2500 && !sr.BSSID.isNullOrBlank()
            }
            .maxByOrNull { it.level }
            ?.BSSID
            ?.trim()
            .orEmpty()
        if (liveBest.isNotBlank()) return liveBest
        return availableWifiNetworksFlow.value
            .firstOrNull { it.ssid.equals(cleanTarget, ignoreCase = true) && it.bssid24Ghz.isNotBlank() }
            ?.bssid24Ghz
            .orEmpty()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun steerTabletTo24GhzRadioIfHybrid(
        context: Context,
        wifiSsid: String,
        wifiPassword: String
    ): String {
        if (!isTabletOn5GhzBand(context)) return ""
        val bssid24 = resolve24GhzBssidForSsid(context, wifiSsid)
        if (bssid24.isBlank()) return ""
        val appCtx = context.applicationContext
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return ""
                if (softApNetworkCallback == null) {
                    val specBuilder = WifiNetworkSpecifier.Builder()
                        .setSsid(wifiSsid.trim())
                        .setBssid(MacAddress.fromString(bssid24))
                    if (wifiPassword.isNotBlank()) {
                        specBuilder.setWpa2Passphrase(wifiPassword)
                    }
                    val req = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .setNetworkSpecifier(specBuilder.build())
                        .build()
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            runCatching { cm.bindProcessToNetwork(network) }
                            Log.d(TAG, "Pinned hybrid Wi-Fi '$wifiSsid' to 2.4GHz BSSID $bssid24 for pairing")
                        }

                        override fun onLost(network: Network) {
                            runCatching { cm.bindProcessToNetwork(null) }
                        }
                    }
                    softApNetworkCallback = cb
                    runCatching { cm.requestNetwork(req, cb) }
                }
                bssid24
            } else {
                val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return ""
                val cleanSsid = "\"${wifiSsid.trim()}\""
                val matchedCfg = wm.configuredNetworks?.firstOrNull { it.SSID == cleanSsid }
                if (matchedCfg != null) {
                    matchedCfg.BSSID = bssid24
                    wm.updateNetwork(matchedCfg)
                    wm.reassociate()
                    bssid24
                } else ""
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "steerTabletTo24GhzRadioIfHybrid",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            Log.w(TAG, "2.4GHz BSSID steering note: ${e.message}")
            ""
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun releaseSoftApNetworkBinding(
        context: Context?,
        startDiscoveryAcceleration: Boolean = true
    ) {
        val hadActiveSoftApBinding = softApNetworkCallback != null || softApAutoUnbindJob != null
        softApAutoUnbindJob?.cancel()
        softApAutoUnbindJob = null
        val ctx = resolveAppContext(context) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching {
                val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wm?.configuredNetworks?.forEach { cfg ->
                    if (!cfg.BSSID.isNullOrBlank() && cfg.BSSID != "any") {
                        cfg.BSSID = null
                        wm.updateNetwork(cfg)
                    }
                }
            }
        }
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.bindProcessToNetwork(null) }
        softApNetworkCallback?.let { cb ->
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
        softApNetworkCallback = null

        if (startDiscoveryAcceleration && (hadActiveSoftApBinding || isPairingActive)) {
            startPostOnboardingDiscoveryAcceleration(ctx)
        }
    }

    /**
     * Post-Onboarding Discovery Acceleration:
     * Opens a temporary, shared UDP 6666/6667 listener for 45 seconds after SoftAP unbind
     * to capture the device's first LAN broadcast, verify TCP port 6668, and close cleanly.
     */
    @SuppressLint("WifiManagerPotentialLeak")
    fun startPostOnboardingDiscoveryAcceleration(
        context: Context,
        expectedDeviceId: String = "",
        windowMs: Long = POST_ONBOARDING_UDP_WINDOW_MS
    ) {
        val appCtx = context.applicationContext
        postOnboardingUdpJob?.cancel()
        postOnboardingUdpJob = pairingScope.launch(Dispatchers.IO) {
            val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val tempMulticastLock = runCatching {
                wm?.createMulticastLock("Jarvis:PostOnboardingUdpAccel")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()

            val deadlineMs = System.currentTimeMillis() + windowMs
            val capturedNewLanDevice = java.util.concurrent.atomic.AtomicBoolean(false)
            Log.d(TAG, "Post-Onboarding Discovery Acceleration started for ${windowMs / 1000}s (UDP 6666/6667)")

            try {
                // Allow tablet Wi-Fi stack a brief moment to re-associate with home Wi-Fi after SoftAP unbind
                delay(800L)

                val listener6666 = launch {
                    listenBoundedTuyaUdpPort(
                        port = 6666,
                        encrypted = false,
                        deadlineMs = deadlineMs,
                        expectedDeviceId = expectedDeviceId,
                        capturedSignal = capturedNewLanDevice
                    )
                }
                val listener6667 = launch {
                    listenBoundedTuyaUdpPort(
                        port = 6667,
                        encrypted = true,
                        deadlineMs = deadlineMs,
                        expectedDeviceId = expectedDeviceId,
                        capturedSignal = capturedNewLanDevice
                    )
                }

                listener6666.join()
                listener6667.join()
            } finally {
                runCatching {
                    if (tempMulticastLock?.isHeld == true) {
                        tempMulticastLock.release()
                    }
                }
                Log.d(TAG, "Post-Onboarding Discovery Acceleration window closed cleanly")
            }
        }
    }

    private suspend fun CoroutineScope.listenBoundedTuyaUdpPort(
        port: Int,
        encrypted: Boolean,
        deadlineMs: Long,
        expectedDeviceId: String,
        capturedSignal: java.util.concurrent.atomic.AtomicBoolean
    ) {
        var socket: java.net.DatagramSocket? = null
        try {
            socket = java.net.DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 1_000
                bind(java.net.InetSocketAddress(port))
            }
            val buffer = ByteArray(1024)

            while (isActive && System.currentTimeMillis() < deadlineMs && !capturedSignal.get()) {
                val packet = java.net.DatagramPacket(buffer, buffer.size)
                val received = try {
                    socket.receive(packet)
                    true
                } catch (_: java.net.SocketTimeoutException) {
                    false
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    false
                }
                if (!received || packet.length <= 0) continue

                val senderIp = packet.address?.hostAddress?.trim().orEmpty()
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                    transportSource = "TUYA_ACCEL_UDP_$port",
                    ipAddress = senderIp,
                    port = port,
                    vendor = "Tuya Post-Onboarding UDP",
                    confidenceScore = 0.25f,
                    qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                    outcomeReason = "Post-onboarding UDP $port frame received (${packet.length} bytes)"
                )
                if (packet.length <= 28) {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FILTERED,
                        transportSource = "TUYA_ACCEL_UDP_$port",
                        ipAddress = senderIp,
                        port = port,
                        vendor = "UDP $port Host",
                        qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                        outcomeReason = "Filtered truncated post-onboarding UDP $port packet (${packet.length} bytes)"
                    )
                    continue
                }
                val parsed = parseTuyaUdpBroadcastFrame(
                    rawBytes = packet.data,
                    length = packet.length,
                    encrypted = encrypted,
                    fallbackIp = senderIp
                )
                if (parsed == null || parsed.first.isBlank() || parsed.second.isBlank()) {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.FILTERED,
                        transportSource = "TUYA_ACCEL_UDP_$port",
                        ipAddress = senderIp,
                        port = port,
                        vendor = "UDP $port Host",
                        qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
                        outcomeReason = "Rejected post-onboarding UDP $port frame without valid gwId/IP"
                    )
                    continue
                }

                val (gwId, resolvedIp) = parsed
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.IDENTIFIED,
                    transportSource = "TUYA_ACCEL_UDP_$port",
                    hardwareId = gwId,
                    ipAddress = resolvedIp,
                    port = 6668,
                    vendor = "Tuya Device $gwId",
                    confidenceScore = 0.95f,
                    qualificationStage = "STAGE_3_SMART_DEVICE_CANDIDATE",
                    outcomeReason = "Decoded post-onboarding Tuya LAN endpoint",
                    cloudId = gwId
                )

                TuyaApiClient.localIpByDevice[gwId] = resolvedIp
                val tcp6668Ready = verifyLocalTcp6668Reachable(resolvedIp)
                Log.d(
                    TAG,
                    "Post-Onboarding UDP $port hit: gwId=$gwId ip=$resolvedIp tcp6668=$tcp6668Ready"
                )

                val appCtx = resolveAppContext()
                if (appCtx != null) {
                    DeviceRepository.patchResolvedLanIpForDevice(
                        context = appCtx,
                        deviceId = gwId,
                        ipAddress = resolvedIp,
                        liveTiles = UniversalOnboardingEngine.activeDashboardTilesRef
                    )
                }

                val isTargetOrNew = if (expectedDeviceId.isNotBlank()) {
                    gwId == expectedDeviceId
                } else {
                    gwId !in cachedBaselineDeviceIds
                }

                if (isTargetOrNew && tcp6668Ready) {
                    DiscoveryFlightRecorder.record(
                        eventType = DiscoveryEventType.PRESENTED,
                        transportSource = "TUYA_ACCEL_UDP_$port",
                        hardwareId = gwId,
                        ipAddress = resolvedIp,
                        port = 6668,
                        vendor = "Device $gwId",
                        confidenceScore = 0.99f,
                        qualificationStage = "TCP_6668_VERIFIED",
                        outcomeReason = "Verified local AES socket at $resolvedIp:6668 (UDP $port)",
                        cloudId = gwId
                    )
                    if (isPairingActive) {
                        val msg = "Verified device $gwId on local LAN ($resolvedIp:6668)! Finalizing registration..."
                        JarvisLiveBridge.updatePairingTimeline(
                            PairingTimelineStage.REGISTERING_CLOUD,
                            msg
                        )
                        JarvisLiveBridge.tuyaPairingStatusFlow.value = msg
                    }
                    capturedSignal.set(true)
                    break
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "listenBoundedTuyaUdpPort",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            Log.w(TAG, "Bounded UDP $port listener note:${e.message}")
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun parseTuyaUdpBroadcastFrame(
        rawBytes: ByteArray,
        length: Int,
        encrypted: Boolean,
        fallbackIp: String
    ): Pair<String, String>? {
        return try {
            val md5Key = java.security.MessageDigest.getInstance("MD5")
                .digest("yGAdlopoPVldABfn".toByteArray(Charsets.UTF_8))
            val decodedText: String = (if (!encrypted) {
                val payloadLen = length - 28
                if (payloadLen <= 0) null else String(rawBytes.copyOfRange(20, 20 + payloadLen), Charsets.UTF_8)
            } else {
                // 1. Try AES-128-ECB (v3.1 / v3.2)
                val ecbDecoded = runCatching {
                    val tailSize = 8
                    val headerSize = if ((length - 20 - tailSize) % 16 != 0 && (length - 16 - tailSize) % 16 == 0) 16 else 20
                    val payloadLen = length - headerSize - tailSize
                    if (payloadLen <= 0) return@runCatching null
                    val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(md5Key, "AES"))
                    String(cipher.doFinal(rawBytes.copyOfRange(headerSize, headerSize + payloadLen)), Charsets.UTF_8)
                }.getOrNull()
                // 2. Fallback to AES-128-GCM (v3.3 / v3.4 / v3.5 on UDP 6667)
                ecbDecoded ?: runCatching {
                    if (length <= 48) return@runCatching null
                    val iv = rawBytes.copyOfRange(20, 32)
                    val cipherAndTag = rawBytes.copyOfRange(32, length - 4)
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        javax.crypto.Cipher.DECRYPT_MODE,
                        javax.crypto.spec.SecretKeySpec(md5Key, "AES"),
                        javax.crypto.spec.GCMParameterSpec(128, iv)
                    )
                    cipher.updateAAD(rawBytes, 4, 16)
                    String(cipher.doFinal(cipherAndTag), Charsets.UTF_8)
                }.getOrNull()
            }) ?: return null

            val jsonStart = decodedText.indexOf('{')
            val jsonEnd = decodedText.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd <= jsonStart) return null

            val json = JSONObject(decodedText.substring(jsonStart, jsonEnd + 1))
            val gwId = json.optString("gwId").trim()
            val ip = json.optString("ip").trim().ifBlank { fallbackIp }
            if (gwId.isBlank()) null else (gwId to ip)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "parseTuyaUdpBroadcastFrame",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            null
        }
    }

    private fun verifyLocalTcp6668Reachable(ipAddress: String, timeoutMs: Int = 650): Boolean {
        if (ipAddress.isBlank()) return false
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(ipAddress, 6668), timeoutMs)
                true
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "verifyLocalTcp6668Reachable",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            false
        }
    }

    private fun collectAllExistingDeviceIds(context: Context, initialHome: HomeBean?): Set<String> {
        val knownIds = HashSet<String>()
        runCatching {
            val existingTiles = DeviceRepository.loadTiles(context)
            for (t in existingTiles) {
                if (t.entityId.isNotBlank()) knownIds.add(t.entityId.trim())
                if (t.serialNumber.isNotBlank()) knownIds.add(t.serialNumber.trim())
            }
        }
        initialHome?.deviceList?.forEach { dev ->
            val id = dev.devId?.trim().orEmpty()
            if (id.isNotBlank()) {
                knownIds.add(id)
                cachedBaselineDeviceIds.add(id)
            }
        }
        knownIds.addAll(TuyaApiClient.getLanDiscoveredDeviceIds())
        return knownIds
    }

    fun prewarmActivatorToken(context: Context) {
        val appCtx = context.applicationContext
        scanAvailableWifiNetworks(appCtx)
        if (isConnectedToDeviceHotspot(appCtx)) return
        if (!ClaraApplication.isTuyaSdkReady(appCtx)) return
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        collectAllExistingDeviceIds(appCtx, null)
        ensureSilentSessionAndHome(
            onReady = { homeId ->
                cachedHomeId = homeId
                prefs.edit().putLong("cached_home_id", homeId).apply()
                ThingHomeSdk.newHomeInstance(homeId).getHomeDetail(object : IThingHomeResultCallback {
                    override fun onSuccess(homeBean: HomeBean?) {
                        collectAllExistingDeviceIds(appCtx, homeBean)
                    }
                    override fun onError(errorCode: String?, errorMsg: String?) {}
                })
                ThingHomeSdk.getActivatorInstance().getActivatorToken(
                    homeId,
                    object : IThingActivatorGetToken {
                        override fun onSuccess(token: String) {
                            if (token.isNotBlank()) {
                                cachedActivatorToken = token
                                cachedTokenTimestampMs = System.currentTimeMillis()
                                prefs.edit()
                                    .putString("cached_activator_token", token)
                                    .putLong("cached_token_time", cachedTokenTimestampMs)
                                    .apply()
                                Log.d(TAG, "Pre-warmed Tuya activator token for AP/EZ pairing (len=${token.length})")
                            }
                        }

                        override fun onFailure(errorCode: String?, errorMsg: String?) {
                            Log.w(TAG, "Pre-warm token note ($errorCode): $errorMsg")
                        }
                    }
                )
            },
            onError = {}
        )
    }

    @Suppress("DEPRECATION")
    private fun acquireRadioLocks(context: Context) {
        try {
            releaseRadioLocks()
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("ClaraTuyaPairingMulticastLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            wifiHighPerfLock = wm?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "ClaraTuyaPairingHighPerfLock"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "Acquired Wi-Fi HighPerfLock & MulticastLock for SmartKnight/Tuya pairing")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "acquireRadioLocks",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            Log.w(TAG, "Could not acquire Wi-Fi locks: ${e.message}")
        }
    }

    private fun releaseRadioLocks() {
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
            multicastLock = null
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "saveCachedInRangeWifiNetworks",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
        }
        try {
            wifiHighPerfLock?.let {
                if (it.isHeld) it.release()
            }
            wifiHighPerfLock = null
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "saveCachedInRangeWifiNetworks",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
        }
    }

    fun scanAndPairDeviceDirectly(
        context: Context,
        wifiSsid: String,
        wifiPassword: String,
        pairingMode: TuyaPairingMode = TuyaPairingMode.AUTO_COMBO_EZ,
        targetKey: String = "MODAL_PAIRING",
        targetHardware: String = "",
        targetBeaconName: String = "",
        hasPreDiscoveredTarget: Boolean = targetHardware.isNotBlank() || targetBeaconName.isNotBlank(),
        onStatusUpdate: (String) -> Unit,
        onDevicePaired: (DiscoveredTuyaDevice) -> Unit,
        onError: (String) -> Unit
    ) {
        val pairingStartMs = System.currentTimeMillis()
        val userOnStatusUpdate = onStatusUpdate
        val userOnDevicePaired = onDevicePaired
        val userOnError = onError

        val alreadyOnPlugHotspotInitial = isConnectedToDeviceHotspot(context.applicationContext)
        val initialEffectiveMode = if (alreadyOnPlugHotspotInitial || isPlugHotspotSsid(wifiSsid.trim())) {
            TuyaPairingMode.AP_HOTSPOT
        } else {
            pairingMode
        }
        val modeTransport = when (initialEffectiveMode) {
            TuyaPairingMode.BLE_DIRECT -> "TUYA_BLE_GATT"
            TuyaPairingMode.AP_HOTSPOT -> "TUYA_SOFTAP"
            TuyaPairingMode.PURE_EZ_BROADCAST -> "TUYA_EZ_UDP_24GHZ"
            TuyaPairingMode.AUTO_COMBO_EZ -> "TUYA_EZ_BLE_COMBO"
        }
        val modeDriver = when (initialEffectiveMode) {
            TuyaPairingMode.BLE_DIRECT -> "Tuya BLE 0xFD50 Provisioner"
            TuyaPairingMode.AP_HOTSPOT -> "Tuya SoftAP Provisioner"
            TuyaPairingMode.PURE_EZ_BROADCAST -> "Tuya 2.4GHz EZ Provisioner"
            TuyaPairingMode.AUTO_COMBO_EZ -> "Tuya EZ + BLE Provisioner"
        }
        val targetMacNorm = CrossEcosystemIdentityCorrelator.normalizeMac(targetHardware).ifEmpty {
            Regex("\\[([0-9A-Fa-f:]{12,17})]")
                .find("$targetHardware $targetBeaconName")
                ?.groupValues
                ?.getOrNull(1)
                ?.let { CrossEcosystemIdentityCorrelator.normalizeMac(it) }
                .orEmpty()
        }
        val preKnownGuid = when {
            targetKey.startsWith("guid::") && !targetKey.startsWith("guid::unresolved::") -> targetKey
            targetMacNorm.isNotEmpty() -> "guid::mac::$targetMacNorm"
            else -> ""
        }
        val recordedPairingStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        val recordedAuthSuccess = java.util.concurrent.atomic.AtomicBoolean(false)
        val recordedTerminalOutcome = java.util.concurrent.atomic.AtomicBoolean(false)

        val onStatusUpdate: (String) -> Unit = { msg ->
            if (preKnownGuid.isNotBlank() &&
                (msg.contains("Sending", ignoreCase = true) ||
                        msg.contains("Pushing", ignoreCase = true) ||
                        msg.contains("Delivering", ignoreCase = true) ||
                        msg.contains("Connected to BLE", ignoreCase = true)) &&
                recordedAuthSuccess.compareAndSet(false, true)
            ) {
                val elapsed = (System.currentTimeMillis() - pairingStartMs).coerceAtLeast(1L)
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = preKnownGuid,
                    event = MandatoryLifecycleTransition.AUTH_SUCCESS.eventName,
                    previousState = DeviceLifecycleState.CONNECTABLE.name,
                    newState = DeviceLifecycleState.AUTHENTICATED.name,
                    activeTransport = modeTransport,
                    vendorDriver = modeDriver,
                    latencyMs = elapsed,
                    protocolResultCode = "WIFI_CREDENTIALS_DISPATCHED",
                    macAddress = targetMacNorm
                )
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = preKnownGuid,
                    event = MandatoryLifecycleTransition.ACTIVATION_STARTED.eventName,
                    previousState = DeviceLifecycleState.AUTHENTICATED.name,
                    newState = DeviceLifecycleState.AUTHENTICATED.name,
                    activeTransport = modeTransport,
                    vendorDriver = modeDriver,
                    latencyMs = 0L,
                    protocolResultCode = "AWAITING_DEVICE_BIND",
                    macAddress = targetMacNorm
                )
            }
            userOnStatusUpdate(msg)
        }

        val onDevicePaired: (DiscoveredTuyaDevice) -> Unit = { dev ->
            if (recordedTerminalOutcome.compareAndSet(false, true)) {
                val elapsed = (System.currentTimeMillis() - pairingStartMs).coerceAtLeast(1L)
                val knownLanIp = TuyaApiClient.localIpByDevice[dev.deviceId].orEmpty()
                val finalGuid = preKnownGuid.ifBlank {
                    "guid::tuya::${dev.deviceId.trim().lowercase(Locale.US)}"
                }
                if (preKnownGuid.isBlank()) {
                    DeviceLifecycleRecorder.recordTransition(
                        deviceId = finalGuid,
                        event = MandatoryLifecycleTransition.DISCOVERED.eventName,
                        previousState = "NONE",
                        newState = DeviceLifecycleState.DISCOVERED.name,
                        activeTransport = modeTransport,
                        vendorDriver = modeDriver,
                        protocolResultCode = "PAIRING_BEACON_LOCKED",
                        cloudDeviceId = dev.deviceId
                    )
                    DeviceLifecycleRecorder.recordTransition(
                        deviceId = finalGuid,
                        event = MandatoryLifecycleTransition.VALIDATED.eventName,
                        previousState = DeviceLifecycleState.DISCOVERED.name,
                        newState = DeviceLifecycleState.VALIDATED.name,
                        activeTransport = modeTransport,
                        vendorDriver = modeDriver,
                        protocolResultCode = "TUYA_SDK_VALIDATED",
                        cloudDeviceId = dev.deviceId
                    )
                    DeviceLifecycleRecorder.recordTransition(
                        deviceId = finalGuid,
                        event = MandatoryLifecycleTransition.PAIRING_STARTED.eventName,
                        previousState = DeviceLifecycleState.VALIDATED.name,
                        newState = DeviceLifecycleState.CONNECTABLE.name,
                        activeTransport = modeTransport,
                        vendorDriver = modeDriver,
                        protocolResultCode = "MODE_${initialEffectiveMode.name}",
                        cloudDeviceId = dev.deviceId
                    )
                }
                if (recordedAuthSuccess.compareAndSet(false, true)) {
                    DeviceLifecycleRecorder.recordTransition(
                        deviceId = finalGuid,
                        event = MandatoryLifecycleTransition.AUTH_SUCCESS.eventName,
                        previousState = DeviceLifecycleState.CONNECTABLE.name,
                        newState = DeviceLifecycleState.AUTHENTICATED.name,
                        activeTransport = modeTransport,
                        vendorDriver = modeDriver,
                        latencyMs = elapsed,
                        protocolResultCode = "TOKEN_ACCEPTED",
                        macAddress = targetMacNorm,
                        cloudDeviceId = dev.deviceId
                    )
                    DeviceLifecycleRecorder.recordTransition(
                        deviceId = finalGuid,
                        event = MandatoryLifecycleTransition.ACTIVATION_STARTED.eventName,
                        previousState = DeviceLifecycleState.AUTHENTICATED.name,
                        newState = DeviceLifecycleState.AUTHENTICATED.name,
                        activeTransport = modeTransport,
                        vendorDriver = modeDriver,
                        protocolResultCode = "AWAITING_DEVICE_BIND",
                        macAddress = targetMacNorm,
                        cloudDeviceId = dev.deviceId
                    )
                }
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = finalGuid,
                    event = MandatoryLifecycleTransition.PAIRING_COMPLETED.eventName,
                    previousState = DeviceLifecycleState.AUTHENTICATED.name,
                    newState = DeviceLifecycleState.CONNECTABLE.name,
                    activeTransport = modeTransport,
                    vendorDriver = modeDriver,
                    latencyMs = elapsed,
                    protocolResultCode = "PAIRED_${dev.deviceId.takeLast(6)}",
                    macAddress = targetMacNorm,
                    cloudDeviceId = dev.deviceId
                )
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = finalGuid,
                    event = MandatoryLifecycleTransition.ACTIVATION_COMPLETED.eventName,
                    previousState = DeviceLifecycleState.CONNECTABLE.name,
                    newState = DeviceLifecycleState.ACTIVATED.name,
                    activeTransport = if (knownLanIp.isNotBlank()) ProviderId.TUYA_LAN.id else ProviderId.TUYA_CLOUD.id,
                    vendorDriver = modeDriver,
                    latencyMs = elapsed,
                    protocolResultCode = if (knownLanIp.isNotBlank()) "LAN_6668_$knownLanIp" else "CLOUD_BOUND",
                    macAddress = targetMacNorm,
                    cloudDeviceId = dev.deviceId
                )
            }
            userOnDevicePaired(dev)
        }

        val onError: (String) -> Unit = { errMsg ->
            if (preKnownGuid.isNotBlank() && recordedTerminalOutcome.compareAndSet(false, true)) {
                val elapsed = (System.currentTimeMillis() - pairingStartMs).coerceAtLeast(1L)
                if (recordedPairingStarted.compareAndSet(false, true)) {
                    DeviceLifecycleRecorder.recordTransition(
                        deviceId = preKnownGuid,
                        event = MandatoryLifecycleTransition.PAIRING_STARTED.eventName,
                        previousState = DeviceLifecycleState.VALIDATED.name,
                        newState = DeviceLifecycleState.CONNECTABLE.name,
                        activeTransport = modeTransport,
                        vendorDriver = modeDriver,
                        protocolResultCode = "MODE_${initialEffectiveMode.name}",
                        macAddress = targetMacNorm
                    )
                }
                val failCat = ErrorClassifier.classifyException(Exception(errMsg))
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = preKnownGuid,
                    event = MandatoryLifecycleTransition.AUTH_FAILED.eventName,
                    previousState = if (recordedAuthSuccess.get()) {
                        DeviceLifecycleState.AUTHENTICATED.name
                    } else {
                        DeviceLifecycleState.CONNECTABLE.name
                    },
                    newState = DeviceLifecycleState.QUARANTINED_ERROR.name,
                    activeTransport = modeTransport,
                    vendorDriver = modeDriver,
                    latencyMs = elapsed,
                    protocolResultCode = "PROVISIONING_FAILED",
                    failureCategory = failCat.name,
                    macAddress = targetMacNorm
                )
            }
            userOnError(errMsg)
        }

        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val scannedNetworks = scanAvailableWifiNetworks(appCtx)
        val rawSsid = wifiSsid.trim()

        val cleanSsid = if (rawSsid.isNotBlank() && !isPlugHotspotSsid(rawSsid)) {
            rawSsid
        } else {
            prefs.getString("wifi_ssid", "")?.takeIf { it.isNotBlank() && !isPlugHotspotSsid(it) }
                ?: scannedNetworks.firstOrNull { it.is24Ghz }?.ssid
                ?: detectCurrentWifiSsid(appCtx).takeIf { !isPlugHotspotSsid(it) }
                ?: scannedNetworks.firstOrNull()?.ssid.orEmpty()
        }
        val cleanPass = wifiPassword.trim()

        if (cleanSsid.isBlank()) {
            JarvisLiveBridge.isPairingTuyaFlow.value = false
            onError("Please select or enter your Home 2.4GHz Wi-Fi name (not the SmartLife hotspot name) so the device knows which home Wi-Fi to join.")
            return
        }

        val alreadyOnPlugHotspot = isConnectedToDeviceHotspot(appCtx)
        val effectiveMode = if (alreadyOnPlugHotspot || isPlugHotspotSsid(rawSsid)) {
            TuyaPairingMode.AP_HOTSPOT
        } else {
            pairingMode
        }

        // Change 6.1 + Sections 4 & 6: Mandatory pre-flight check (BSSID 2.4 GHz + BLE/Location + 5 GHz pure-EZ guard)
        val preflight = validateProvisioningPreflight(appCtx, cleanSsid, effectiveMode)
        if (!preflight.passed) {
            JarvisLiveBridge.isPairingTuyaFlow.value = false
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.FAILED,
                transportSource = modeTransport,
                hardwareId = targetHardware.ifBlank { "-" },
                macAddress = targetMacNorm,
                vendor = modeDriver,
                qualificationStage = "PREFLIGHT_CHECK",
                outcomeReason = "${preflight.failureTitle}: ${preflight.remediationMessage}",
                wifiFrequencyMhz = preflight.tabletFrequencyMhz,
                protocolResultCode = preflight.protocolResultCode
            )
            onError(preflight.remediationMessage)
            return
        }

        saveWifiCredentials(appCtx, cleanSsid, cleanPass)

        stopPairing()
        val baselineIds = collectAllExistingDeviceIds(appCtx, null)
        if (preKnownGuid.isNotBlank() && recordedPairingStarted.compareAndSet(false, true)) {
            DeviceLifecycleRecorder.recordTransition(
                deviceId = preKnownGuid,
                event = MandatoryLifecycleTransition.PAIRING_STARTED.eventName,
                previousState = DeviceLifecycleState.VALIDATED.name,
                newState = DeviceLifecycleState.CONNECTABLE.name,
                activeTransport = modeTransport,
                vendorDriver = modeDriver,
                latencyMs = 0L,
                protocolResultCode = "MODE_${effectiveMode.name}",
                macAddress = targetMacNorm
            )
        }
        isPairingActive = true
        JarvisLiveBridge.isPairingTuyaFlow.value = true
        val fallbackToken = cachedActivatorToken.ifBlank {
            prefs.getString("cached_activator_token", "").orEmpty()
        }
        val fallbackHomeId = if (cachedHomeId > 0L) {
            cachedHomeId
        } else {
            prefs.getLong("cached_home_id", -1L)
        }

        if (alreadyOnPlugHotspot && fallbackToken.isNotBlank() && fallbackHomeId > 0L) {
            val hotspotName = detectCurrentWifiSsid(appCtx)
            val msg = "Connected to device hotspot '$hotspotName'! Sending '$cleanSsid' Wi-Fi credentials..."
            JarvisLiveBridge.tuyaPairingStatusFlow.value = msg
            onStatusUpdate(msg)
            startHardwareDiscoveryAndPair(
                context = appCtx,
                homeId = fallbackHomeId,
                baselineDeviceIds = baselineIds,
                wifiSsid = cleanSsid,
                wifiPassword = cleanPass,
                token = fallbackToken,
                pairingMode = TuyaPairingMode.AP_HOTSPOT,
                targetHardware = targetHardware,
                targetBeaconName = targetBeaconName,
                hasPreDiscoveredTarget = true,
                onStatusUpdate = { status ->
                    JarvisLiveBridge.tuyaPairingStatusFlow.value = status
                    onStatusUpdate(status)
                },
                onDevicePaired = { bean ->
                    val discovered = TuyaApiClient.convertDeviceBeanToDiscovered(bean)
                    DeviceRepository.unmarkDeviceDismissed(appCtx, discovered.deviceId, discovered.name)
                    JarvisLiveBridge.isPairingTuyaFlow.value = false
                    onDevicePaired(discovered)
                },
                onError = { err ->
                    JarvisLiveBridge.isPairingTuyaFlow.value = false
                    onError(err)
                }
            )
            return
        }

        val on5G = isTabletOn5GhzBand(appCtx)
        val freqMhz = getCurrentWifiFrequencyMhz(appCtx)
        val prepMsg = when {
            effectiveMode == TuyaPairingMode.BLE_DIRECT ->
                "Opening direct Bluetooth LE GATT link to ${targetBeaconName.ifBlank { targetHardware }.ifBlank { "0xFD50 beacon" }}..."
            effectiveMode == TuyaPairingMode.AP_HOTSPOT ->
                "Preparing SoftAP hotspot bridge for ${targetBeaconName.ifBlank { targetHardware }.ifBlank { "AP device" }}..."
            on5G ->
                "5GHz Tablet Wi-Fi detected ($freqMhz MHz) \u2014 prioritizing Bluetooth LE + 2.4GHz combo pairing for '$cleanSsid'..."
            else ->
                "Preparing 2.4GHz Wi-Fi + BLE pairing for '$cleanSsid'..."
        }
        JarvisLiveBridge.tuyaPairingStatusFlow.value = prepMsg
        onStatusUpdate(prepMsg)

        ensureSilentSessionAndHome(
            onReady = { homeId ->
                cachedHomeId = homeId
                prefs.edit().putLong("cached_home_id", homeId).apply()

                ThingHomeSdk.newHomeInstance(homeId).getHomeDetail(object : IThingHomeResultCallback {
                    override fun onSuccess(initialHome: HomeBean?) {
                        val fullBaseline = collectAllExistingDeviceIds(appCtx, initialHome)
                        requestTokenAndBeginBroadcast(
                            context = appCtx,
                            homeId = homeId,
                            baselineDeviceIds = fullBaseline,
                            wifiSsid = cleanSsid,
                            wifiPassword = cleanPass,
                            pairingMode = effectiveMode,
                            targetHardware = targetHardware,
                            targetBeaconName = targetBeaconName,
                            hasPreDiscoveredTarget = hasPreDiscoveredTarget,
                            fallbackToken = fallbackToken,
                            onStatusUpdate = { status ->
                                JarvisLiveBridge.tuyaPairingStatusFlow.value = status
                                onStatusUpdate(status)
                            },
                            onDevicePaired = { dev ->
                                JarvisLiveBridge.isPairingTuyaFlow.value = false
                                onDevicePaired(dev)
                            },
                            onError = { err ->
                                JarvisLiveBridge.isPairingTuyaFlow.value = false
                                onError(err)
                            }
                        )
                    }

                    override fun onError(errorCode: String?, errorMsg: String?) {
                        val fullBaseline = collectAllExistingDeviceIds(appCtx, null)
                        requestTokenAndBeginBroadcast(
                            context = appCtx,
                            homeId = homeId,
                            baselineDeviceIds = fullBaseline,
                            wifiSsid = cleanSsid,
                            wifiPassword = cleanPass,
                            pairingMode = effectiveMode,
                            targetHardware = targetHardware,
                            targetBeaconName = targetBeaconName,
                            hasPreDiscoveredTarget = hasPreDiscoveredTarget,
                            fallbackToken = fallbackToken,
                            onStatusUpdate = { status ->
                                JarvisLiveBridge.tuyaPairingStatusFlow.value = status
                                onStatusUpdate(status)
                            },
                            onDevicePaired = { dev ->
                                JarvisLiveBridge.isPairingTuyaFlow.value = false
                                onDevicePaired(dev)
                            },
                            onError = { err ->
                                JarvisLiveBridge.isPairingTuyaFlow.value = false
                                onError(err)
                            }
                        )
                    }
                })
            },
            onError = { err ->
                if (ClaraApplication.isTuyaSdkReady(appCtx) && fallbackToken.isNotBlank() && fallbackHomeId > 0L) {
                    startHardwareDiscoveryAndPair(
                        context = appCtx,
                        homeId = fallbackHomeId,
                        baselineDeviceIds = baselineIds,
                        wifiSsid = cleanSsid,
                        wifiPassword = cleanPass,
                        token = fallbackToken,
                        pairingMode = effectiveMode,
                        targetHardware = targetHardware,
                        targetBeaconName = targetBeaconName,
                        hasPreDiscoveredTarget = hasPreDiscoveredTarget,
                        onStatusUpdate = { status ->
                            JarvisLiveBridge.tuyaPairingStatusFlow.value = status
                            onStatusUpdate(status)
                        },
                        onDevicePaired = { bean ->
                            val discovered = TuyaApiClient.convertDeviceBeanToDiscovered(bean)
                            DeviceRepository.unmarkDeviceDismissed(appCtx, discovered.deviceId, discovered.name)
                            JarvisLiveBridge.isPairingTuyaFlow.value = false
                            onDevicePaired(discovered)
                        },
                        onError = { e ->
                            JarvisLiveBridge.isPairingTuyaFlow.value = false
                            onError(e)
                        }
                    )
                } else {
                    isPairingActive = false
                    JarvisLiveBridge.isPairingTuyaFlow.value = false
                    onError(err)
                }
            }
        )
    }

    private fun requestTokenAndBeginBroadcast(
        context: Context,
        homeId: Long,
        baselineDeviceIds: Set<String>,
        wifiSsid: String,
        wifiPassword: String,
        pairingMode: TuyaPairingMode,
        targetHardware: String,
        targetBeaconName: String,
        hasPreDiscoveredTarget: Boolean,
        fallbackToken: String,
        onStatusUpdate: (String) -> Unit,
        onDevicePaired: (DiscoveredTuyaDevice) -> Unit,
        onError: (String) -> Unit
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val on5G = isTabletOn5GhzBand(context)
        val freqMhz = getCurrentWifiFrequencyMhz(context)

        fun launchWithToken(tokenToUse: String) {
            when {
                pairingMode == TuyaPairingMode.BLE_DIRECT -> {
                    val targetDesc = targetBeaconName.ifBlank { targetHardware }.ifBlank { "Bluetooth LE device" }
                    onStatusUpdate("Token ready! Connecting directly over Bluetooth LE to $targetDesc...")
                }
                pairingMode == TuyaPairingMode.AP_HOTSPOT && !isConnectedToDeviceHotspot(context) -> {
                    val targetDesc = targetBeaconName.ifBlank { targetHardware }.ifBlank { "device hotspot" }
                    onStatusUpdate("Token ready! Switching Wi-Fi to '$targetDesc' hotspot to deliver '$wifiSsid'...")
                }
                on5G -> {
                    onStatusUpdate("Tablet is on 5GHz ($freqMhz MHz): Prioritizing Bluetooth LE Combo pairing for '$wifiSsid'...")
                }
                else -> {
                    onStatusUpdate("Broadcasting 2.4GHz Wi-Fi + BLE credentials for '$wifiSsid' to wireless device...")
                }
            }
            startHardwareDiscoveryAndPair(
                context = context,
                homeId = homeId,
                baselineDeviceIds = baselineDeviceIds,
                wifiSsid = wifiSsid,
                wifiPassword = wifiPassword,
                token = tokenToUse,
                pairingMode = pairingMode,
                targetHardware = targetHardware,
                targetBeaconName = targetBeaconName,
                hasPreDiscoveredTarget = hasPreDiscoveredTarget,
                onStatusUpdate = onStatusUpdate,
                onDevicePaired = { bean ->
                    JarvisLiveBridge.updatePairingTimeline(
                        PairingTimelineStage.INITIALIZING_TILE,
                        "Initializing ${bean.name ?: "Smart Device"} & syncing capabilities..."
                    )
                    ThingHomeSdk.newHomeInstance(homeId).getHomeDetail(object : IThingHomeResultCallback {
                        override fun onSuccess(homeBean: HomeBean?) {
                            val freshBean = ThingHomeSdk.getDataInstance().getDeviceBean(bean.devId) ?: bean
                            val discovered = TuyaApiClient.convertDeviceBeanToDiscovered(freshBean)
                            DeviceRepository.unmarkDeviceDismissed(context, discovered.deviceId, discovered.name)
                            onDevicePaired(discovered)
                        }

                        override fun onError(errorCode: String?, errorMsg: String?) {
                            val discovered = TuyaApiClient.convertDeviceBeanToDiscovered(bean)
                            DeviceRepository.unmarkDeviceDismissed(context, discovered.deviceId, discovered.name)
                            onDevicePaired(discovered)
                        }
                    })
                },
                onError = onError
            )
        }

        ThingHomeSdk.getActivatorInstance().getActivatorToken(
            homeId,
            object : IThingActivatorGetToken {
                override fun onSuccess(token: String) {
                    cachedActivatorToken = token
                    cachedTokenTimestampMs = System.currentTimeMillis()
                    prefs.edit()
                        .putString("cached_activator_token", token)
                        .putLong("cached_token_time", cachedTokenTimestampMs)
                        .apply()
                    launchWithToken(token)
                }

                override fun onFailure(errorCode: String?, errorMsg: String?) {
                    if (fallbackToken.isNotBlank()) {
                        Log.d(TAG, "Using cached activator token for offline AP hotspot mode")
                        launchWithToken(fallbackToken)
                    } else {
                        isPairingActive = false
                        onError(
                            "Connect your tablet to your Home Wi-Fi first so Clara can prepare the pairing token."
                        )
                    }
                }
            }
        )
    }

    /**
     * Authenticates the household session strictly using the unique per-installation cryptographic
     * identity from `ClaraApplication.getOrCreateInstallationUid` and `getOrCreateInstallationSecret`
     * with zero static shared fallback credentials in source.
     */
    /**
     * Onboarding Section 5: Pre-fetches the Tuya cloud pairing token while the tablet is still
     * online on Home Wi-Fi BEFORE switching to an offline SmartLife-XXXX / SL-XXXX hotspot.
     */
    suspend fun prefetchCloudPairingTokenSuspend(
        context: Context,
        timeoutMs: Long = 8_000L
    ): Result<Pair<Long, String>> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        if (!ClaraApplication.isTuyaSdkReady(appCtx)) {
            return@withContext Result.failure(Exception("Tuya SDK is not initialized yet."))
        }
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Result<Pair<Long, String>>> { cont ->
                ensureSilentSessionAndHome(
                    onReady = { homeId ->
                        cachedHomeId = homeId
                        prefs.edit().putLong("cached_home_id", homeId).apply()
                        ThingHomeSdk.getActivatorInstance().getActivatorToken(
                            homeId,
                            object : IThingActivatorGetToken {
                                override fun onSuccess(token: String) {
                                    if (token.isNotBlank()) {
                                        cachedActivatorToken = token
                                        cachedTokenTimestampMs = System.currentTimeMillis()
                                        prefs.edit()
                                            .putString("cached_activator_token", token)
                                            .putLong("cached_token_time", cachedTokenTimestampMs)
                                            .apply()
                                        if (cont.isActive) cont.resume(Result.success(homeId to token))
                                    } else if (cont.isActive) {
                                        cont.resume(Result.failure(Exception("Received empty pairing token from cloud.")))
                                    }
                                }

                                override fun onFailure(errorCode: String?, errorMsg: String?) {
                                    if (cont.isActive) {
                                        cont.resume(
                                            Result.failure(
                                                Exception("Could not pre-fetch cloud pairing token ($errorCode): ${errorMsg.orEmpty()}")
                                            )
                                        )
                                    }
                                }
                            }
                        )
                    },
                    onError = { err ->
                        if (cont.isActive) cont.resume(Result.failure(Exception(err)))
                    }
                )
            }
        }
        result ?: Result.failure(Exception("Timed out pre-fetching cloud pairing token. Check internet connection."))
    }

    /**
     * Onboarding Section 5: Waits up to [timeoutMs] (15 seconds default) after releasing the
     * offline SoftAP socket for Android ConnectivityManager to rejoin validated Home Wi-Fi
     * before running Step 5 cloud registration & capability sync.
     */
    suspend fun awaitHomeWifiReconnect(
        context: Context,
        expectedHomeSsid: String = "",
        timeoutMs: Long = 15_000L
    ): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val startMs = SystemClock.elapsedRealtime()
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@withContext false

        fun isHomeLanReadyNow(): Boolean {
            if (isConnectedToDeviceHotspot(appCtx)) return false
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            val hasTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (!hasTransport || !hasInternet) return false
            val curSsid = detectCurrentWifiSsid(appCtx)
            return expectedHomeSsid.isBlank() ||
                    curSsid.isBlank() ||
                    curSsid.equals(expectedHomeSsid.trim(), ignoreCase = true)
        }

        if (isHomeLanReadyNow()) return@withContext true

        val reconnected = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val completed = java.util.concurrent.atomic.AtomicBoolean(false)
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (!isConnectedToDeviceHotspot(appCtx) && completed.compareAndSet(false, true)) {
                            runCatching { cm.unregisterNetworkCallback(this) }
                            if (cont.isActive) cont.resume(true)
                        }
                    }

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        val valid = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        if (valid && !isConnectedToDeviceHotspot(appCtx) && completed.compareAndSet(false, true)) {
                            runCatching { cm.unregisterNetworkCallback(this) }
                            if (cont.isActive) cont.resume(true)
                        }
                    }
                }
                runCatching { cm.registerNetworkCallback(req, cb) }
                cont.invokeOnCancellation {
                    runCatching { cm.unregisterNetworkCallback(cb) }
                }
            }
        } ?: isHomeLanReadyNow()

        val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
        DiscoveryFlightRecorder.record(
            eventType = if (reconnected) DiscoveryEventType.IDENTIFIED else DiscoveryEventType.TIMEOUT,
            transportSource = "HOME_WIFI_HANDOFF",
            hardwareId = expectedHomeSsid.ifBlank { "HOME_LAN" },
            vendor = "Android ConnectivityManager",
            confidenceScore = if (reconnected) 1.0f else 0.0f,
            qualificationStage = "SOFTAP_LAN_RECONNECT",
            outcomeReason = if (reconnected) {
                "Verified tablet rejoined Home Wi-Fi '${expectedHomeSsid}' in ${elapsed}ms"
            } else {
                "Home Wi-Fi reconnect timed out after ${elapsed}ms"
            },
            wifiFrequencyMhz = getCurrentWifiFrequencyMhz(appCtx),
            durationMs = elapsed,
            protocolResultCode = if (reconnected) "LAN_RECONNECT_OK" else "LAN_RECONNECT_TIMEOUT"
        )
        reconnected
    }

    /**
     * Onboarding Section 7: Immediately writes bound Tuya credentials & metadata into
     * ClaraSecretVault and ClaraDeviceRegistryStore BEFORE capability learning begins.
     */
    private fun persistBoundTuyaBeanImmediately(
        context: Context,
        bean: DeviceBean,
        fallbackMac: String = ""
    ) {
        val disc = TuyaApiClient.convertDeviceBeanToDiscovered(bean)
        if (disc.deviceId.isBlank()) return
        val knownIp = TuyaApiClient.localIpByDevice[disc.deviceId].orEmpty()
        val protoVer = TuyaApiClient.protocolVersionByDevice[disc.deviceId].orEmpty()
        val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(fallbackMac)
        ClaraDeviceRegistryStore.getInstance(context).persistBoundDeviceImmediately(
            deviceId = disc.deviceId,
            ipAddress = knownIp,
            port = if (knownIp.isNotBlank()) 6668 else 0,
            macAddress = normMac,
            localOrClientKey = disc.localKey,
            providerId = if (knownIp.isNotBlank()) ProviderId.TUYA_LAN else ProviderId.TUYA_CLOUD,
            suggestedTitle = disc.name.ifBlank { "Smart Plug" },
            suggestedTileType = disc.suggestedType,
            protocolVersion = protoVer
        )
    }

    fun ensureSilentSessionAndHome(
        countryCode: String = DEFAULT_COUNTRY_CODE,
        onReady: (homeId: Long) -> Unit,
        onError: (String) -> Unit
    ) {
        val appCtx = resolveAppContext()
        if (appCtx != null && !ClaraApplication.isTuyaSdkReady(appCtx)) {
            runCatching { ClaraApplication.ensureTuyaSdkInitialized(appCtx, true) }
        }
        if (!ClaraApplication.isTuyaSdkReady(appCtx)) {
            onError("Tuya SDK not initialized: awaiting Tuya credentials in Control Center.")
            return
        }
        try {
            val userInstance = ThingHomeSdk.getUserInstance()
            val selectedVaultHomeId = appCtx?.let { ClaraSecretVault.getTuyaSelectedHomeId(it) } ?: -1L
            val fetchOrCreateHome = {
                ThingHomeSdk.getHomeManagerInstance().queryHomeList(object : IThingGetHomeListCallback {
                    override fun onSuccess(homeBeans: MutableList<HomeBean>?) {
                        val existingHome = homeBeans?.firstOrNull { it.homeId == selectedVaultHomeId }
                            ?: homeBeans?.maxByOrNull { it.deviceList?.size ?: 0 }
                            ?: homeBeans?.firstOrNull()
                        if (existingHome != null) {
                            cachedHomeId = existingHome.homeId
                            if (appCtx != null) {
                                ClaraSecretVault.saveTuyaSelectedHome(
                                    appCtx,
                                    existingHome.homeId,
                                    existingHome.name.orEmpty()
                                )
                            }
                            onReady(existingHome.homeId)
                        } else {
                            ThingHomeSdk.getHomeManagerInstance().createHome(
                                "Clara Home",
                                0.0,
                                0.0,
                                "",
                                emptyList(),
                                object : IThingHomeResultCallback {
                                    override fun onSuccess(bean: HomeBean?) {
                                        if (bean != null) {
                                            cachedHomeId = bean.homeId
                                            if (appCtx != null) {
                                                ClaraSecretVault.saveTuyaSelectedHome(
                                                    appCtx,
                                                    bean.homeId,
                                                    bean.name.orEmpty()
                                                )
                                            }
                                            onReady(bean.homeId)
                                        } else {
                                            onError("Could not initialize your smart home profile.")
                                        }
                                    }

                                    override fun onError(errorCode: String?, errorMsg: String?) {
                                        onError("Could not initialize your smart home profile.")
                                    }
                                }
                            )
                        }
                    }

                    override fun onError(errorCode: String?, errorMsg: String?) {
                        if (cachedHomeId > 0L) {
                            onReady(cachedHomeId)
                        } else {
                            onError("Could not load your smart home devices right now.")
                        }
                    }
                })
            }

            if (userInstance.isLogin) {
                fetchOrCreateHome()
            } else if (appCtx != null) {
                val silentUid = ClaraApplication.getOrCreateInstallationUid(appCtx)
                val silentSecret = ClaraApplication.getOrCreateInstallationSecret(appCtx)
                Log.d(TAG, "Starting silent behind-the-scenes Tuya UID session")
                userInstance.loginOrRegisterWithUid(
                    countryCode.ifBlank { DEFAULT_COUNTRY_CODE },
                    silentUid,
                    silentSecret,
                    object : ILoginCallback {
                        override fun onSuccess(user: User?) {
                            Log.d(TAG, "Silent Tuya UID session active (uid=${user?.uid.orEmpty()})")
                            fetchOrCreateHome()
                        }

                        override fun onError(code: String?, error: String?) {
                            Log.w(TAG, "Silent Tuya UID login failed ($code): $error")
                            onError(
                                "Could not start silent Tuya pairing session (${code.orEmpty()}): ${error ?: "Verify Tuya AppKey, AppSecret, and SHA-256 certificate."}"
                            )
                        }
                    }
                )
            } else {
                onError("Smart home engine context is not ready yet.")
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            onError("Smart home engine is initializing. Please try again in a moment.")
        }
    }

    private fun startHardwareDiscoveryAndPair(
        context: Context,
        homeId: Long,
        baselineDeviceIds: Set<String>,
        wifiSsid: String,
        wifiPassword: String,
        token: String,
        pairingMode: TuyaPairingMode,
        targetHardware: String = "",
        targetBeaconName: String = "",
        hasPreDiscoveredTarget: Boolean = targetHardware.isNotBlank() || targetBeaconName.isNotBlank(),
        onStatusUpdate: (String) -> Unit,
        onDevicePaired: (DeviceBean) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!ClaraApplication.isTuyaSdkReady(context)) {
            isPairingActive = false
            JarvisLiveBridge.isPairingTuyaFlow.value = false
            onError("Tuya SDK not initialized. Please configure Tuya credentials in Control Center.")
            return
        }
        var hasPaired = false
        var bleComboInProgress = false
        var gattRetryAttempts = 0
        val maxGattRetries = 3
        val startEpochMs = System.currentTimeMillis()
        val on5G = isTabletOn5GhzBand(context)
        val freqMhz = getCurrentWifiFrequencyMhz(context)
        val displayTarget = targetBeaconName.ifBlank { targetHardware }.ifBlank { "wireless device" }
        val strictSingleBleTarget = pairingMode == TuyaPairingMode.BLE_DIRECT &&
                (targetHardware.isNotBlank() || targetBeaconName.isNotBlank())
        val normTargetMac = CrossEcosystemIdentityCorrelator.normalizeMac(targetHardware)

        if (on5G && (pairingMode == TuyaPairingMode.AUTO_COMBO_EZ || pairingMode == TuyaPairingMode.PURE_EZ_BROADCAST)) {
            steerTabletTo24GhzRadioIfHybrid(context, wifiSsid, wifiPassword)
        }

        acquireRadioLocks(context)

        if (hasPreDiscoveredTarget || JarvisLiveBridge.tuyaPairingStageFlow.value.stepIndex >= PairingTimelineStage.DEVICE_FOUND.stepIndex) {
            val step2Msg = when (pairingMode) {
                TuyaPairingMode.BLE_DIRECT ->
                    "Selected $displayTarget \u2014 authenticating over Bluetooth..."
                TuyaPairingMode.AP_HOTSPOT ->
                    "Connecting to '$displayTarget' hotspot to deliver '$wifiSsid'..."
                TuyaPairingMode.PURE_EZ_BROADCAST ->
                    "Broadcasting 2.4 GHz Wi-Fi credentials for '$wifiSsid'..."
                TuyaPairingMode.AUTO_COMBO_EZ ->
                    "Authenticating with $displayTarget & sending '$wifiSsid'..."
            }
            JarvisLiveBridge.updatePairingTimeline(PairingTimelineStage.DEVICE_FOUND, step2Msg)
        } else {
            JarvisLiveBridge.updatePairingTimeline(
                PairingTimelineStage.SCANNING_DEVICE,
                "Searching for blinking device on '$wifiSsid'..."
            )
        }

        pairingTimeoutWatchdogJob?.cancel()
        pairingTimeoutWatchdogJob = pairingScope.launch {
            delay(125_000L)
            if (!hasPaired && isPairingActive) {
                stopPairing()
            }
        }

        val triedBleUuids = HashSet<String>()
        var restartBleScanCallback: () -> Unit = {}

        fun completePairedDeviceWithImmediateVaultPersistence(deviceBean: DeviceBean) {
            if (hasPaired) return
            hasPaired = true
            discoveredUnpairedBleBeans.clear()
            // Onboarding Section 7: Immediately persist deviceId, IP, MAC, and localKey BEFORE capability sync
            persistBoundTuyaBeanImmediately(context, deviceBean, normTargetMac)
            JarvisLiveBridge.updatePairingTimeline(
                PairingTimelineStage.INITIALIZING_TILE,
                "Binding security keys & learning capabilities for ${deviceBean.name ?: "Smart Device"}..."
            )
            stopPairing(resetTimeline = false)
            onStatusUpdate("Connected ${deviceBean.name ?: "Smart Device"}!")
            onDevicePaired(deviceBean)
        }

        fun activateBleMultiModeBean(scanBean: ScanDeviceBean) {
            val key = scanBean.uuid?.ifBlank { scanBean.mac }.orEmpty()
            if (key.isNotBlank()) triedBleUuids.add(key)
            bleComboInProgress = true
            // Stop BLE scan prior to GATT connection to avoid Android status 133 collisions
            runCatching { ThingHomeSdk.getBleOperator().stopLeScan() }
            val macOrId = scanBean.mac?.ifBlank { scanBean.uuid }.orEmpty().ifBlank { displayTarget }
            val foundMsg = "Authenticated with $displayTarget! Sending '$wifiSsid' credentials..."
            JarvisLiveBridge.updatePairingTimeline(PairingTimelineStage.DEVICE_FOUND, foundMsg)
            JarvisLiveBridge.updatePairingTimeline(
                PairingTimelineStage.CONNECTING_WIFI,
                "Activating '$wifiSsid' on $displayTarget..."
            )
            onStatusUpdate(foundMsg)

            val multiBean = MultiModeActivatorBean(scanBean).apply {
                this.deviceType = scanBean.deviceType
                this.uuid = scanBean.uuid
                this.address = scanBean.address
                this.mac = scanBean.mac
                this.flag = scanBean.flag
                this.productId = scanBean.productId
                this.ssid = wifiSsid
                this.pwd = wifiPassword
                this.token = token
                this.homeId = homeId
                this.timeout = 120_000L
                this.phase1Timeout = 60_000L
            }

            pairingScope.launch {
                delay(4_200L)
                if (!hasPaired && bleComboInProgress) {
                    JarvisLiveBridge.updatePairingTimeline(
                        PairingTimelineStage.REGISTERING_CLOUD,
                        "Registering device & binding encryption keys..."
                    )
                }
            }

            val multiActivator = ThingHomeSdk.getActivator().newMultiModeActivator()
            multiActivator.startActivator(
                multiBean,
                object : IMultiModeActivatorListener {
                    override fun onSuccess(deviceBean: DeviceBean?) {
                        if (deviceBean != null) {
                            completePairedDeviceWithImmediateVaultPersistence(deviceBean)
                        }
                    }

                    override fun onFailure(code: Int, msg: String?, handle: Any?) {
                        bleComboInProgress = false
                        // Change 6.3: Explicitly close dangling GATT activator before backoff retry (fixes Android GATT 133)
                        if (!scanBean.uuid.isNullOrBlank()) {
                            runCatching { multiActivator.stopActivator(scanBean.uuid) }
                        }
                        runCatching { ThingHomeSdk.getBleOperator().stopLeScan() }
                        if (key.isNotBlank()) {
                            triedBleUuids.remove(key)
                            discoveredUnpairedBleBeans.remove(key)
                        }
                        gattRetryAttempts++
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.FAILED,
                            transportSource = "TUYA_BLE_GATT",
                            hardwareId = macOrId,
                            macAddress = CrossEcosystemIdentityCorrelator.normalizeMac(scanBean.mac ?: normTargetMac),
                            vendor = displayTarget,
                            qualificationStage = "BLE_GATT_ACTIVATOR",
                            outcomeReason = "BLE GATT activator attempt $gattRetryAttempts/$maxGattRetries failed ($code): ${msg.orEmpty()}",
                            wifiFrequencyMhz = getCurrentWifiFrequencyMhz(context),
                            protocolResultCode = "GATT_ERR_$code"
                        )
                        if (!hasPaired && isPairingActive) {
                            if (gattRetryAttempts <= maxGattRetries) {
                                val backoffMs = 1_200L * gattRetryAttempts
                                onStatusUpdate("Retrying Bluetooth connection ($gattRetryAttempts/$maxGattRetries)...")
                                pairingScope.launch {
                                    delay(backoffMs)
                                    if (!hasPaired && isPairingActive) {
                                        restartBleScanCallback()
                                    }
                                }
                            } else if (pairingMode == TuyaPairingMode.BLE_DIRECT) {
                                stopPairing(resetTimeline = false)
                                onError("Bluetooth setup could not stay connected to $displayTarget (code $code). Move the device closer or switch to Slow Blinking / AP Mode.")
                            }
                        }
                    }
                }
            )
        }

        fun startOrRestartBleScan() {
            if (hasPaired ||
                !isPairingActive ||
                pairingMode == TuyaPairingMode.AP_HOTSPOT ||
                pairingMode == TuyaPairingMode.PURE_EZ_BROADCAST
            ) return
            try {
                runCatching { ThingHomeSdk.getBleOperator().stopLeScan() }
                val scanSetting = LeScanSetting.Builder()
                    .setTimeout(60_000L)
                    .addScanType(ScanType.SINGLE)
                    .addScanType(ScanType.MESH)
                    .addScanType(ScanType.SIG_MESH)
                    .build()

                ThingHomeSdk.getBleOperator().startLeScan(
                    scanSetting,
                    BleScanResponse { scanBean: ScanDeviceBean? ->
                        if (scanBean != null && !hasPaired && !bleComboInProgress) {
                            if ((scanBean.flag and 0x80) != 0) return@BleScanResponse
                            val key = scanBean.uuid?.ifBlank { scanBean.mac }.orEmpty()
                            if (key.isNotBlank()) discoveredUnpairedBleBeans[key] = scanBean
                            if (!scanBean.mac.isNullOrBlank()) discoveredUnpairedBleBeans[scanBean.mac] = scanBean

                            // Onboarding Section 4: In BLE_DIRECT mode, NEVER fall back to an unselected device MAC!
                            val matchesTarget = if (strictSingleBleTarget) {
                                matchesBleTargetBean(scanBean, targetHardware, targetBeaconName)
                            } else {
                                matchesBleTargetBean(scanBean, targetHardware, targetBeaconName) ||
                                        (targetHardware.isBlank() && targetBeaconName.isBlank() && discoveredUnpairedBleBeans.size == 1)
                            }
                            if (matchesTarget && (key.isBlank() || key !in triedBleUuids)) {
                                activateBleMultiModeBean(scanBean)
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "BLE scan note: ${e.message}")
            }
        }
        restartBleScanCallback = { startOrRestartBleScan() }

        // SoftAP Hotspot Mode: Connect via WifiNetworkSpecifier and hold binding until handoff completes
        if (pairingMode == TuyaPairingMode.AP_HOTSPOT) {
            val hotspotHint = targetBeaconName.ifBlank { targetHardware }
            val connectedHotspot = findAndConnectToOpenPlugHotspot(
                context = context,
                preferredHotspotSsidOrMac = hotspotHint,
                onHotspotBound = { boundSsid ->
                    if (!hasPaired && isPairingActive) {
                        val hsMsg = "Joined '$boundSsid'! Delivering '$wifiSsid' credentials..."
                        JarvisLiveBridge.updatePairingTimeline(PairingTimelineStage.CONNECTING_WIFI, hsMsg)
                        onStatusUpdate(hsMsg)
                        startApHotspotActivatorInternal(
                            context = context,
                            wifiSsid = wifiSsid,
                            wifiPassword = wifiPassword,
                            token = token,
                            onStatusUpdate = onStatusUpdate,
                            onPaired = { devResp ->
                                completePairedDeviceWithImmediateVaultPersistence(devResp)
                            }
                        )
                    }
                }
            )
            if (isConnectedToDeviceHotspot(context) || connectedHotspot == null) {
                startApHotspotActivatorInternal(
                    context = context,
                    wifiSsid = wifiSsid,
                    wifiPassword = wifiPassword,
                    token = token,
                    onStatusUpdate = onStatusUpdate,
                    onPaired = { devResp ->
                        completePairedDeviceWithImmediateVaultPersistence(devResp)
                    }
                )
            }
        }

        pairingPollJob?.cancel()
        pairingPollJob = pairingScope.launch {
            while (isActive && !hasPaired) {
                delay(3000L)
                if (hasPaired) break

                if (pairingMode == TuyaPairingMode.AP_HOTSPOT &&
                    !isConnectedToDeviceHotspot(context) &&
                    !bleComboInProgress &&
                    softApNetworkCallback == null &&
                    activeApFallbackBroadcaster == null
                ) {
                    findAndConnectToOpenPlugHotspot(
                        context = context,
                        preferredHotspotSsidOrMac = targetBeaconName.ifBlank { targetHardware },
                        onHotspotBound = { _ ->
                            if (!hasPaired && isPairingActive) {
                                startApHotspotActivatorInternal(
                                    context = context,
                                    wifiSsid = wifiSsid,
                                    wifiPassword = wifiPassword,
                                    token = token,
                                    onStatusUpdate = onStatusUpdate,
                                    onPaired = { devResp ->
                                        completePairedDeviceWithImmediateVaultPersistence(devResp)
                                    }
                                )
                            }
                        }
                    )
                }

                if (!isConnectedToDeviceHotspot(context)) {
                    ThingHomeSdk.newHomeInstance(homeId).getHomeDetail(object : IThingHomeResultCallback {
                        override fun onSuccess(bean: HomeBean?) {
                            if (hasPaired) return
                            val devices = bean?.deviceList ?: return
                            val genuinelyNewDevice = devices.firstOrNull { dev ->
                                val id = dev.devId?.trim().orEmpty()
                                id.isNotBlank() && id !in baselineDeviceIds && id !in cachedBaselineDeviceIds
                            }
                            if (genuinelyNewDevice != null && !hasPaired) {
                                JarvisLiveBridge.updatePairingTimeline(
                                    PairingTimelineStage.REGISTERING_CLOUD,
                                    "Device registered on '$wifiSsid'! Binding keys..."
                                )
                                completePairedDeviceWithImmediateVaultPersistence(genuinelyNewDevice)
                            }
                        }

                        override fun onError(errorCode: String?, errorMsg: String?) {}
                    })
                }
            }
        }

        // 0. For BLE_DIRECT or AUTO_COMBO_EZ: Match strictly against selected target MAC first
        if (pairingMode == TuyaPairingMode.BLE_DIRECT || pairingMode == TuyaPairingMode.AUTO_COMBO_EZ) {
            val matchedCachedBean = discoveredUnpairedBleBeans.values.firstOrNull { bean ->
                val key = bean.uuid?.ifBlank { bean.mac }.orEmpty()
                key.isNotBlank() && key !in triedBleUuids &&
                        matchesBleTargetBean(bean, targetHardware, targetBeaconName)
            }
            val preCapturedBean = if (strictSingleBleTarget) {
                matchedCachedBean
            } else {
                matchedCachedBean ?: discoveredUnpairedBleBeans.values.firstOrNull { bean ->
                    val key = bean.uuid?.ifBlank { bean.mac }.orEmpty()
                    targetHardware.isBlank() && targetBeaconName.isBlank() && key.isNotBlank() && key !in triedBleUuids
                }
            }

            if (preCapturedBean != null) {
                activateBleMultiModeBean(preCapturedBean)
            } else {
                startOrRestartBleScan()
            }
        }

        // 2. Run Wi-Fi EZ Broadcaster ONLY for AUTO_COMBO_EZ or PURE_EZ_BROADCAST (never during BLE_DIRECT or AP_HOTSPOT)
        if (pairingMode == TuyaPairingMode.AUTO_COMBO_EZ || pairingMode == TuyaPairingMode.PURE_EZ_BROADCAST) {
            if (pairingMode == TuyaPairingMode.PURE_EZ_BROADCAST && isTabletOn5GhzBand(context)) {
                stopPairing(resetTimeline = false)
                onError(
                    "Your tablet is connected on 5 GHz ($freqMhz MHz). Switch to Slow Blinking / AP Mode so credentials deliver reliably to 2.4 GHz devices."
                )
                return
            }
            try {
                activeWifiBroadcaster?.stop()
                val activatorModel = if (isConnectedToDeviceHotspot(context)) {
                    ActivatorModelEnum.THING_AP
                } else {
                    ActivatorModelEnum.THING_EZ
                }

                activeWifiBroadcaster = ThingHomeSdk.getActivatorInstance().newActivator(
                    ActivatorBuilder()
                        .setContext(context)
                        .setSsid(wifiSsid)
                        .setPassword(wifiPassword)
                        .setActivatorModel(activatorModel)
                        .setTimeOut(120L)
                        .setToken(token)
                        .setListener(object : IThingSmartActivatorListener {
                            override fun onError(errorCode: String?, errorMsg: String?) {
                                if (!hasPaired && !bleComboInProgress) {
                                    val friendlyRecoveryMsg = if (isTabletOn5GhzBand(context)) {
                                        "Your tablet is on 5 GHz Wi-Fi ($freqMhz MHz). Switch to Slow Blinking / AP Mode to pair 2.4 GHz-only devices."
                                    } else {
                                        "We couldn't reach your device yet ($errorCode). Check the Wi-Fi password for '$wifiSsid' and ensure only one device is blinking rapidly."
                                    }
                                    JarvisLiveBridge.updatePairingTimeline(PairingTimelineStage.FAILED, friendlyRecoveryMsg)
                                    onError(friendlyRecoveryMsg)
                                }
                            }

                            override fun onActiveSuccess(devResp: DeviceBean?) {
                                if (devResp != null) {
                                    completePairedDeviceWithImmediateVaultPersistence(devResp)
                                }
                            }

                            override fun onStep(step: String?, data: Any?) {
                                val stepDesc = when (step) {
                                    "device_find" -> {
                                        val msg = "Found your device! Activating '$wifiSsid'..."
                                        JarvisLiveBridge.updatePairingTimeline(PairingTimelineStage.DEVICE_FOUND, msg)
                                        pairingScope.launch {
                                            delay(1_200L)
                                            if (!hasPaired) {
                                                JarvisLiveBridge.updatePairingTimeline(
                                                    PairingTimelineStage.CONNECTING_WIFI,
                                                    "Activating '$wifiSsid' Wi-Fi credentials on device..."
                                                )
                                            }
                                        }
                                        msg
                                    }
                                    "device_bind_success" -> {
                                        val msg = "Device joined '$wifiSsid'! Binding security keys..."
                                        JarvisLiveBridge.updatePairingTimeline(PairingTimelineStage.REGISTERING_CLOUD, msg)
                                        msg
                                    }
                                    else -> "Sending '$wifiSsid' setup frames..."
                                }
                                onStatusUpdate(stepDesc)
                            }
                        })
                )
                activeWifiBroadcaster?.start()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Wi-Fi activator note: ${e.message}")
            }
        }
    }

    /**
     * Change 6.3 + Onboarding Section 5:
     * Holds the SoftAP network binding continuously until credential delivery / router handoff
     * completes (or the plug closes its AP on reboot), then awaits a verified 15-second
     * ConnectivityManager reconnection onto Home Wi-Fi before completing cloud sync.
     */
    private fun startApHotspotActivatorInternal(
        context: Context,
        wifiSsid: String,
        wifiPassword: String,
        token: String,
        onStatusUpdate: (String) -> Unit,
        onPaired: (DeviceBean) -> Unit
    ) {
        try {
            activeApFallbackBroadcaster?.stop()
            val handoffReleased = java.util.concurrent.atomic.AtomicBoolean(false)

            fun releaseApAndVerifyHomeLanReconnect(reason: String) {
                if (!handoffReleased.compareAndSet(false, true)) return
                pairingScope.launch {
                    releaseSoftApNetworkBinding(context, startDiscoveryAcceleration = false)
                    onStatusUpdate("Credentials delivered ($reason)! Rejoining '$wifiSsid'...")
                    val rejoined = awaitHomeWifiReconnect(
                        context = context,
                        expectedHomeSsid = wifiSsid,
                        timeoutMs = 15_000L
                    )
                    startPostOnboardingDiscoveryAcceleration(context)
                    if (isPairingActive) {
                        val msg = if (rejoined) {
                            "Reconnected to '$wifiSsid'! Completing registration & key binding..."
                        } else {
                            "Waiting for tablet to finish reconnecting to '$wifiSsid'..."
                        }
                        JarvisLiveBridge.updatePairingTimeline(PairingTimelineStage.REGISTERING_CLOUD, msg)
                        onStatusUpdate(msg)
                    }
                }
            }

            activeApFallbackBroadcaster = ThingHomeSdk.getActivatorInstance().newActivator(
                ActivatorBuilder()
                    .setContext(context)
                    .setSsid(wifiSsid)
                    .setPassword(wifiPassword)
                    .setActivatorModel(ActivatorModelEnum.THING_AP)
                    .setTimeOut(120L)
                    .setToken(token)
                    .setListener(object : IThingSmartActivatorListener {
                        override fun onError(errorCode: String?, errorMsg: String?) {
                            releaseSoftApNetworkBinding(context)
                        }

                        override fun onActiveSuccess(devResp: DeviceBean?) {
                            releaseSoftApNetworkBinding(context, startDiscoveryAcceleration = false)
                            if (devResp != null) {
                                pairingScope.launch {
                                    awaitHomeWifiReconnect(context, wifiSsid, timeoutMs = 15_000L)
                                    startPostOnboardingDiscoveryAcceleration(context, expectedDeviceId = devResp.devId.orEmpty())
                                    onPaired(devResp)
                                }
                            }
                        }

                        override fun onStep(step: String?, data: Any?) {
                            when (step) {
                                "device_find" -> {
                                    // Hold SoftAP socket open so full SSID + password + token payload finishes transmitting!
                                    val msg = "Connected to device over AP socket \u2014 transmitting '$wifiSsid' credentials..."
                                    JarvisLiveBridge.updatePairingTimeline(PairingTimelineStage.CONNECTING_WIFI, msg)
                                    onStatusUpdate(msg)
                                    pairingScope.launch {
                                        // Only release fallback if the device hasn't already dropped its SoftAP on reboot
                                        delay(14_000L)
                                        if (isPairingActive && isConnectedToDeviceHotspot(context)) {
                                            releaseApAndVerifyHomeLanReconnect("ap_credential_flush")
                                        }
                                    }
                                }
                                "device_cfg_send", "device_bind_success" -> {
                                    releaseApAndVerifyHomeLanReconnect(step)
                                }
                                else -> onStatusUpdate("Transmitting '$wifiSsid' credentials over device hotspot...")
                            }
                        }
                    })
            )
            activeApFallbackBroadcaster?.start()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "SoftAP activator error: ${e.message}")
        }
    }

    fun stopPairing(resetTimeline: Boolean = false) {
        isPairingActive = false
        JarvisLiveBridge.isPairingTuyaFlow.value = false
        if (resetTimeline) {
            try {
                postOnboardingUdpJob?.cancel()
                postOnboardingUdpJob = null
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                    t = e,
                    transportSource = TAG,
                    operationStage = "stopPairing_postOnboardingUdpJob",
                    logToDiscoveryRecorder = true,
                    surfaceToUiBanner = true
                )
            }
            JarvisLiveBridge.updatePairingTimeline(PairingTimelineStage.IDLE)
        }
        try {
            pairingTimeoutWatchdogJob?.cancel()
            pairingTimeoutWatchdogJob = null
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "saveCachedInRangeWifiNetworks",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
        }
        try {
            pairingPollJob?.cancel()
            pairingPollJob = null
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = e,
                transportSource = TAG,
                operationStage = "saveCachedInRangeWifiNetworks",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
        }
        if (ClaraApplication.isTuyaSdkInitialized) {
            try {
                ThingHomeSdk.getBleOperator().stopLeScan()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                    t = t,
                    transportSource = TAG,
                    operationStage = "stopPairing_stopLeScan",
                    logToDiscoveryRecorder = true,
                    surfaceToUiBanner = true
                )
            }
        }
        try {
            activeWifiBroadcaster?.stop()
            activeWifiBroadcaster?.onDestroy()
            activeWifiBroadcaster = null
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = t,
                transportSource = TAG,
                operationStage = "stopPairing_activeWifiBroadcaster",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
        }
        try {
            activeApFallbackBroadcaster?.stop()
            activeApFallbackBroadcaster?.onDestroy()
            activeApFallbackBroadcaster = null
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            com.jarvis.smarthomelauncher.core.ErrorClassifier.recordHandledException(
                t = t,
                transportSource = TAG,
                operationStage = "stopPairing_activeApFallbackBroadcaster",
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
        }
        releaseSoftApNetworkBinding(null, startDiscoveryAcceleration = false)
        releaseRadioLocks()
    }
}
