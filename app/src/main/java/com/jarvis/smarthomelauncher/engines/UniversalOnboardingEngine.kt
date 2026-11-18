package com.jarvis.smarthomelauncher.engines

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import kotlin.math.roundToInt
import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.UniversalOnboardingEngine.resolvedIdentitiesFlow
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume


// ---------------------------------------------------------------------------
// STAGE 1: STANDARDIZED MULTI-TRANSPORT SIGNAL FINGERPRINT
// ---------------------------------------------------------------------------
enum class TransportRadioType(val label: String, val basePriority: Int) {
    MATTER_FABRIC("Matter Local Fabric", 100),
    TUYA_LAN_6668("Local LAN AES-6668", 95),
    KASA_KLAP_LAN("Local LAN KLAP / 9999", 94),
    SONOFF_LAN_8081("Local LAN 8081 / mDNS", 93),
    SHELLY_TASMOTA_HTTP("Local HTTP / RPC", 92),
    MQTT_LOCAL("Local MQTT / ESPHome", 91),
    GOOGLE_CAST_TLS("Local Cast TLS 8009", 90),
    LG_WEBOS_SSAP("Local WebSocket 3001", 89),
    GOVEE_TWINKLY_UDP("Local UDP RGB Engine", 88),
    RTSP_ONVIF_LAN("Local RTSP 554 Video", 87),
    BLE_SETUP_BEACON("Bluetooth LE Setup Beacon", 85),
    WIFI_SOFTAP_BEACON("Wi-Fi Setup Hotspot", 84),
    HOME_ASSISTANT_BRIDGE("Home Assistant Bridge", 80),
    CLOUD_ACCOUNT_API("Authenticated Cloud Link", 75),
    RAW_TCP_PROBE("Unverified TCP Port", 35);

    fun toProviderId(suggestedType: TileType = TileType.SMARTLIFE_DEVICE): ProviderId = when (this) {
        MATTER_FABRIC -> ProviderId.MATTER_FABRIC
        TUYA_LAN_6668 -> ProviderId.TUYA_LAN
        KASA_KLAP_LAN -> ProviderId.TPLINK_KLAP
        SONOFF_LAN_8081 -> ProviderId.EWELINK_LAN
        SHELLY_TASMOTA_HTTP -> ProviderId.SHELLY_HTTP
        MQTT_LOCAL -> ProviderId.MQTT_BROKER
        GOOGLE_CAST_TLS -> ProviderId.GOOGLE_CAST
        LG_WEBOS_SSAP -> ProviderId.LG_WEBOS
        GOVEE_TWINKLY_UDP -> if (suggestedType == TileType.TWINKLY_LIGHT) ProviderId.TWINKLY_LAN else ProviderId.GOVEE_LAN

        // F-14: Route local RTSP 554 / ONVIF cameras to local RTSP/NVR provider instead of forcing cloud OAuth
        RTSP_ONVIF_LAN -> ProviderId.RTSP_ONVIF_LAN
        // F-28: Map BLE & SoftAP setup beacons to their target ecosystem ProviderId instead of UNKNOWN
        BLE_SETUP_BEACON, WIFI_SOFTAP_BEACON -> when (suggestedType) {
            TileType.NEST_THERMOSTAT, TileType.NEST_DOORBELL -> ProviderId.GOOGLE_NEST
            TileType.MATTER_DEVICE -> ProviderId.MATTER_FABRIC
            TileType.TAPO_DEVICE -> ProviderId.TPLINK_KLAP
            TileType.SONOFF_SWITCH -> ProviderId.EWELINK_LAN
            TileType.SHELLY_RELAY -> ProviderId.SHELLY_HTTP
            TileType.MQTT_DEVICE -> ProviderId.MQTT_BROKER
            TileType.GOVEE_LIGHT -> ProviderId.GOVEE_LAN
            TileType.TWINKLY_LIGHT -> ProviderId.TWINKLY_LAN
            else -> ProviderId.TUYA_LAN
        }
        HOME_ASSISTANT_BRIDGE -> ProviderId.HOME_ASSISTANT
        CLOUD_ACCOUNT_API -> when (suggestedType) {
            TileType.NEST_THERMOSTAT, TileType.NEST_DOORBELL -> ProviderId.GOOGLE_NEST
            TileType.JAGUAR_VEHICLE -> ProviderId.JAGUAR_INCONTROL
            TileType.MERCEDES_VEHICLE -> ProviderId.MERCEDES_ME
            TileType.GOVEE_LIGHT -> ProviderId.GOVEE_CLOUD
            TileType.IMOU_CAMERA -> ProviderId.IMOU_CLOUD
            TileType.EZVIZ_DOORBELL -> ProviderId.EZVIZ_CLOUD
            else -> ProviderId.TUYA_CLOUD
        }
        else -> ProviderId.UNKNOWN
    }
}

data class DeviceFingerprint(
    val id: String = UUID.randomUUID().toString(),
    val transport: TransportRadioType,
    val explicitProviderId: ProviderId? = null,
    val protocolBadge: String,
    val confidenceScore: Float,
    val displayName: String,
    val suggestedType: TileType,
    val ipAddress: String = "",
    val port: Int = 0,
    val macAddress: String = "",
    val hardwareSerialOrNodeId: String = "",
    val cloudDeviceId: String = "",
    val matterDiscriminator: Int = 0,
    val localKeyOrPin: String = "",
    val multiGangCount: Int = 1,
    val socket1Name: String = "Socket 1",
    val socket2Name: String = "Socket 2",
    val isActive: Boolean = false,
    val isSocket2Active: Boolean = false,
    val isOnline: Boolean = true,
    val temperatureC: Float = 20.5f,
    val targetTempC: Float = 21.0f,
    val humidityPct: Int = 50,
    val hvacMode: String = "HEAT",
    val hvacStatus: String = "OFF",
    val levelOrVolumePct: Float = 80f,
    val powerWatts: Float = 0f,
    val voltageVolts: Float = 0f,
    val currentMilliamps: Float = 0f,
    val totalEnergyKwh: Float = 0f,
    val hasPowerTelemetry: Boolean = false,
    val requiresProvisioning: Boolean = false,
    val requiresShortCode: Boolean = false,
    val requiresPhysicalAccept: Boolean = false,
    val requiresAccountAuth: Boolean = false,
    val accountWorkflowKey: String = "",
    val parentNetworkNodeId: String = "",
    val capabilities: List<DeviceCapabilityDescriptor> = emptyList(),
    val deviceClass: DeviceClass = if (capabilities.isNotEmpty()) {
        SemanticClassifier.resolve(
            capabilities = capabilities,
            categoryCode = "",
            multiGangSocketCount = multiGangCount,
            hasPowerTelemetry = hasPowerTelemetry,
            titleHint = displayName
        ).deviceClass
    } else {
        suggestedType.toSemanticDeviceClass(hasPowerTelemetry)
    },
    val deviceSubtype: DeviceSubtype = if (capabilities.isNotEmpty()) {
        SemanticClassifier.resolve(
            capabilities = capabilities,
            categoryCode = "",
            multiGangSocketCount = multiGangCount,
            hasPowerTelemetry = hasPowerTelemetry,
            titleHint = displayName
        ).deviceSubtype
    } else {
        suggestedType.toDefaultDeviceSubtype(displayName)
    },
    val discoveredAtEpochMs: Long = System.currentTimeMillis(),
    val rawAdvertisedName: String = displayName,
    val ouiManufacturer: String = "",
    val serviceUuids: List<String> = emptyList(),
    val manufacturerDataHex: String = "",
    val rssiDbm: Int? = null,
    val discoveryReasoning: List<String> = emptyList()
)

// ---------------------------------------------------------------------------
// STAGE 3: THE 5 BRAND-AGNOSTIC ONBOARDING REQUIREMENTS
// ---------------------------------------------------------------------------
sealed class OnboardingRequirement(
    val decisionCount: Int,
    val actionButtonLabel: String,
    val requirementSummary: String
) {
    /** 1 Decision — Tap Connect (Verified local LAN or pre-linked account) */
    data object ZeroTouchReady : OnboardingRequirement(
        decisionCount = 1,
        actionButtonLabel = "Connect",
        requirementSummary = "Ready to connect automatically"
    )

    /** 1 Decision — Tap Connect (Invisibly runs BLE 0xFD50 + 2.4GHz EZ + SoftAP bridge) */
    data class AutoProvisioningRequired(
        val beaconName: String = "",
        val macOrSsid: String = ""
    ) : OnboardingRequirement(
        decisionCount = 1,
        actionButtonLabel = "Connect to Wi-Fi",
        requirementSummary = "Wireless setup mode active \u2022 Tap to provision Wi-Fi automatically"
    )

    /** 1 Decision — Enter 6-8 char Entry Key or 11-21 digit / MT: setup code */
    data class ShortCodeRequired(
        val hintLabel: String = "Enter 7-character Entry Key or 11-digit setup code"
    ) : OnboardingRequirement(
        decisionCount = 1,
        actionButtonLabel = "Enter Setup Code",
        requirementSummary = "Requires setup code from device screen or label"
    )

    /** 1 Decision — Press Accept on the physical TV or appliance screen */
    data class PhysicalAcceptRequired(
        val promptMessage: String = "Press 'Accept' on your TV screen when prompted"
    ) : OnboardingRequirement(
        decisionCount = 1,
        actionButtonLabel = "Connect & Accept",
        requirementSummary = "Tap Connect, then press Accept on your screen"
    )

    /** 1 Decision — Sign in inside Clara's embedded modal when mandated by vendor */
    data class InAppAccountWorkflowRequired(
        val workflowKey: String,
        val workflowDisplayName: String
    ) : OnboardingRequirement(
        decisionCount = 1,
        actionButtonLabel = "Sign In to Link",
        requirementSummary = "Requires one-time sign-in inside Clara"
    )
}

// ---------------------------------------------------------------------------
// STAGE 2: RESOLVED DEVICE IDENTITY (DEDUPLICATED ACROSS ALL RADIOS)
// ---------------------------------------------------------------------------
data class ResolvedDeviceIdentity(
    val identityKey: String,
    val displayName: String,
    val suggestedRoom: String,
    val deviceClass: DeviceClass,
    val suggestedType: TileType,
    val combinedConfidence: Float,
    val deviceSubtype: DeviceSubtype = suggestedType.toDefaultDeviceSubtype(displayName),
    val primaryProviderId: String,
    val fallbackProviderIds: List<String>,
    val onboardingRequirement: OnboardingRequirement,
    val ipAddress: String = "",
    val port: Int = 0,
    val macAddress: String = "",
    val hardwareSerialOrNodeId: String = "",
    val cloudDeviceId: String = "",
    val localKeyOrPin: String = "",
    val multiGangCount: Int = 1,
    val socket1Name: String = "Socket 1",
    val socket2Name: String = "Socket 2",
    val isActive: Boolean = false,
    val isSocket2Active: Boolean = false,
    val isOnline: Boolean = true,
    val temperatureC: Float = 20.5f,
    val targetTempC: Float = 21.0f,
    val humidityPct: Int = 50,
    val hvacMode: String = "HEAT",
    val hvacStatus: String = "OFF",
    val levelOrVolumePct: Float = 80f,
    val powerWatts: Float = 0f,
    val voltageVolts: Float = 0f,
    val currentMilliamps: Float = 0f,
    val totalEnergyKwh: Float = 0f,
    val hasPowerTelemetry: Boolean = false,
    val parentNetworkNodeId: String = "",
    val capabilities: List<DeviceCapabilityDescriptor> = emptyList(),
    val capabilitySchemaHash: String = CapabilitySchemaHasher.computeHash(capabilities),
    val mergedTransports: List<TransportRadioType> = emptyList(),
    val protocolBadges: List<String> = emptyList(),
    val rawAdvertisedName: String = displayName,
    val ouiManufacturer: String = "",
    val serviceUuids: List<String> = emptyList(),
    val manufacturerDataHex: String = "",
    val rssiDbm: Int? = null,
    val firstSeenEpochMs: Long = System.currentTimeMillis(),
    val lastSeenEpochMs: Long = System.currentTimeMillis(),
    val seenCount: Int = 1,
    val discoveryReasoning: List<String> = emptyList(),
    val canonicalGuid: String = "",
    val consumerSubtitle: String = "",
    val softApSsid: String = ""
) {
    val resolvedProviderEnum: ProviderId
        get() = ProviderId.fromRawKey(primaryProviderId)

    val hasVerifiedVaultKey: Boolean
        get() = onboardingRequirement is OnboardingRequirement.ZeroTouchReady

    val requiresPhysicalTvAccept: Boolean
        get() = onboardingRequirement is OnboardingRequirement.PhysicalAcceptRequired

    val isWeakSignal: Boolean
        get() = rssiDbm != null && rssiDbm < -78

    val consumerSignalLabel: String
        get() {
            val rssi = rssiDbm ?: return ""
            return when {
                rssi >= -65 -> "Strong signal"
                rssi >= -78 -> "Good signal"
                else -> "Weak signal — move closer if setup stalls"
            }
        }

    val confidencePercent: Int
        get() = (combinedConfidence * 100f).roundToInt().coerceIn(1, 99)

    val transportSummaryText: String
        get() = protocolBadges.distinct().joinToString(" + ").ifBlank { deviceClass.label }

    val discoveryIntelligenceSummary: String
        get() = buildList {
            if (ouiManufacturer.isNotBlank()) add("Vendor: $ouiManufacturer")
            if (serviceUuids.isNotEmpty()) add("UUIDs: ${serviceUuids.joinToString(",")}")
            if (rssiDbm != null) add("RSSI: ${rssiDbm} dBm")
            if (seenCount > 1) add("Seen: ${seenCount}x")
            if (discoveryReasoning.isNotEmpty()) add(discoveryReasoning.joinToString(" + "))
        }.joinToString(" \u2022 ").ifBlank { transportSummaryText }
}

// ---------------------------------------------------------------------------
// STAGE 2: CROSS-TRANSPORT DEVICE IDENTITY RESOLVER & NOISY-OR ENGINE
// ---------------------------------------------------------------------------
enum class QualificationStage(val stageNumber: Int, val minScore: Float, val label: String) {
    STAGE_1_DISCOVERED_SERVICE(1, 0.10f, "Stage 1: Discovered Service"),
    STAGE_2_POTENTIAL_DEVICE(2, 0.30f, "Stage 2: Potential Device"),
    STAGE_3_SMART_DEVICE_CANDIDATE(3, 0.60f, "Stage 3: Smart Device Candidate"),
    STAGE_4_VALIDATED_DEVICE(4, 0.80f, "Stage 4: Validated Device"),
    STAGE_5_ONBOARDABLE_DEVICE(5, 0.90f, "Stage 5: Onboardable Device")
}



// ---------------------------------------------------------------------------
// STAGE 1 TO 3: UNIVERSAL ZERO-BRAND ONBOARDING & DISCOVERY ENGINE
// ---------------------------------------------------------------------------
object UniversalOnboardingEngine {
    private const val TAG = "ClaraUniversalOnboard"
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Onboarding Section 3: Single-session pairing mutex preventing concurrent multi-device onboarding
    private val pairingSessionMutex = Mutex()

    @Volatile
    var activeDashboardTilesRef: MutableList<SmartTile>? = null

    data class OnboardingSessionState(
        val pairingSessionId: String = "",
        val currentStage: UnifiedOnboardingStage = UnifiedOnboardingStage.SELECT_MODE,
        val isPairingActive: Boolean = false,
        val stageTitle: String = UnifiedOnboardingStage.SELECT_MODE.stepTitle,
        val statusDetail: String = UnifiedOnboardingStage.SELECT_MODE.consumerSubtitle,
        val failureTitle: String = "",
        val failureRemediation: String = ""
    )

    val resolvedIdentitiesFlow = MutableStateFlow<List<ResolvedDeviceIdentity>>(emptyList())
    val isScanningFlow = MutableStateFlow(false)

    private val _onboardingSessionStateFlow = MutableStateFlow(OnboardingSessionState())
    val onboardingSessionStateFlow: StateFlow<OnboardingSessionState> = _onboardingSessionStateFlow.asStateFlow()

    val activePairingSessionIdFlow = MutableStateFlow("")
    val activeUnifiedStageFlow = MutableStateFlow(UnifiedOnboardingStage.SELECT_MODE)

    private fun publishSessionStage(
        stage: UnifiedOnboardingStage,
        sessionId: String = activePairingSessionIdFlow.value,
        stageTitle: String = stage.stepTitle,
        statusDetail: String = stage.consumerSubtitle,
        failureTitle: String = "",
        failureRemediation: String = ""
    ) {
        activePairingSessionIdFlow.value = sessionId
        activeUnifiedStageFlow.value = stage
        _onboardingSessionStateFlow.value = OnboardingSessionState(
            pairingSessionId = sessionId,
            currentStage = stage,
            isPairingActive = sessionId.isNotBlank() && !stage.isTerminal &&
                    stage !in setOf(UnifiedOnboardingStage.SELECT_MODE, UnifiedOnboardingStage.SELECT_DEVICE, UnifiedOnboardingStage.CONFIGURE_WIFI),
            stageTitle = stageTitle,
            statusDetail = statusDetail,
            failureTitle = failureTitle,
            failureRemediation = failureRemediation
        )
    }

    fun syncSessionFromPairingTimeline(
        timelineStage: PairingTimelineStage,
        resolvedTitle: String,
        detailText: String
    ) {
        val curSessionId = activePairingSessionIdFlow.value
        if (curSessionId.isBlank()) return
        when (timelineStage) {
            PairingTimelineStage.FAILED -> publishSessionStage(
                stage = UnifiedOnboardingStage.FAILED,
                sessionId = curSessionId,
                stageTitle = resolvedTitle,
                statusDetail = detailText,
                failureTitle = resolvedTitle.ifBlank { "Setup Could Not Complete" },
                failureRemediation = detailText
            )
            PairingTimelineStage.COMPLETED -> publishSessionStage(
                stage = UnifiedOnboardingStage.COMPLETED,
                sessionId = curSessionId,
                stageTitle = resolvedTitle,
                statusDetail = detailText
            )
            PairingTimelineStage.IDLE -> Unit
            else -> _onboardingSessionStateFlow.update { cur ->
                cur.copy(
                    isPairingActive = true,
                    stageTitle = resolvedTitle.ifBlank { cur.stageTitle },
                    statusDetail = detailText.ifBlank { cur.statusDetail }
                )
            }
        }
    }

    fun beginSingleDevicePairingSession(entryMode: OnboardingEntryMode): String {
        val newSessionId = DiscoveryFlightRecorder.startPairingSession(entryMode.name)
        publishSessionStage(
            stage = UnifiedOnboardingStage.SCANNING_CANDIDATES,
            sessionId = newSessionId
        )
        return newSessionId
    }

    fun endSingleDevicePairingSession(expectedSessionId: String = "") {
        val current = activePairingSessionIdFlow.value
        if (expectedSessionId.isBlank() || current == expectedSessionId) {
            DiscoveryFlightRecorder.endPairingSession(current)
            publishSessionStage(
                stage = UnifiedOnboardingStage.SELECT_MODE,
                sessionId = ""
            )
        }
    }

    fun isSingleSessionLocked(): Boolean = pairingSessionMutex.isLocked
    private fun cleanConsumerDeviceName(rawName: String, fallbackType: TileType): String {
        val stripped = rawName
            .replace(Regex("\\[[0-9A-Fa-f:]{4,17}]"), "")
            .replace(Regex("\\(\\d{1,3}(?:\\.\\d{1,3}){3}\\)"), "")
            .replace("SSDP", "", ignoreCase = true)
            .replace("SSAP", "", ignoreCase = true)
            .replace("0xFD50", "", ignoreCase = true)
            .replace("0xFFF6", "", ignoreCase = true)
            .replace("0xFEAF", "", ignoreCase = true)
            .replace("0xEC88", "", ignoreCase = true)
            .trim()
        return stripped.ifBlank { fallbackType.brandName }
    }

    private fun cleanConsumerSubtitle(
        lan: DiscoveredLanDevice,
        entryMode: OnboardingEntryMode,
        hasVaultKey: Boolean
    ): String = when (entryMode) {
        OnboardingEntryMode.FAST_BLINKING -> "Ready for Wi-Fi setup"
        OnboardingEntryMode.SLOW_BLINKING_AP -> {
            val ssid = lan.ipAddress.takeIf { DiscoveryRadioCoordinator.isDeviceSetupHotspotSsid(it) }
                ?: lan.hardwareIdOrMac
            "Device Wi-Fi hotspot ($ssid) ready"
        }
        OnboardingEntryMode.ALREADY_ON_NETWORK -> when {
            lan.suggestedType == TileType.LG_TV && !hasVaultKey ->
                "On your network \u2022 Requires Accept on TV screen"
            lan.suggestedType == TileType.LG_TV ->
                "Paired & ready on your network"
            lan.actionType == DiscoveredDeviceActionType.OAUTH_SIGN_IN ->
                "On your network \u2022 Sign in to link account"
            else -> "Verified on your home network"
        }
        OnboardingEntryMode.MATTER_OR_CLOUD ->
            if (lan.detail.contains("Multi-Admin", ignoreCase = true)) {
                "Open Multi-Admin pairing window in existing home app"
            } else {
                "Ready for Matter setup code"
            }
    }

    suspend fun scanCandidatesForEntryMode(
        context: Context,
        entryMode: OnboardingEntryMode,
        tiles: MutableList<SmartTile>,
        pairingSessionId: String = activePairingSessionIdFlow.value,
        bleScanDurationMs: Long = 8_000L
    ): List<ResolvedDeviceIdentity> = withContext(Dispatchers.IO) {
        activeDashboardTilesRef = tiles
        ConnectedIdentityIndex.rebuildFromTiles(tiles)
        val effectiveSessionId = pairingSessionId.ifBlank {
            beginSingleDevicePairingSession(entryMode)
        }
        DiscoveryFlightRecorder.attachActivePairingSession(effectiveSessionId)
        isScanningFlow.value = true
        publishSessionStage(UnifiedOnboardingStage.SCANNING_CANDIDATES, effectiveSessionId)
        val scanStartMs = SystemClock.elapsedRealtime()
        val bridgeSettings = DeviceRepository.loadBridge(context)

        try {
            val rawLanList = SmartHomeApiClient.discoverByRadioPass(
                context = context,
                radioPass = entryMode.primaryRadioPass,
                scanDurationMs = bleScanDurationMs,
                includeFullPortSweep = entryMode == OnboardingEntryMode.ALREADY_ON_NETWORK
            )
            val tabletFreq = RadioEnvironmentProbe.currentWifiFrequencyMhz(context)

            val candidates = rawLanList.mapNotNull { item ->
                val cleanIp = if (entryMode == OnboardingEntryMode.ALREADY_ON_NETWORK ||
                    entryMode == OnboardingEntryMode.MATTER_OR_CLOUD
                ) {
                    item.ipAddress.substringBefore(":").trim()
                } else ""
                val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(item.hardwareIdOrMac)
                val devId = item.hardwareIdOrMac.takeUnless { ConnectedIdentityIndex.isBareIpLiteral(it) }.orEmpty()

                val isLgTv = item.suggestedType == TileType.LG_TV
                val provider = when {
                    isLgTv -> ProviderId.LG_WEBOS
                    item.suggestedType == TileType.NEST_MINI -> ProviderId.GOOGLE_CAST
                    item.suggestedType == TileType.MATTER_DEVICE -> ProviderId.MATTER_FABRIC
                    item.suggestedType == TileType.SHELLY_RELAY -> ProviderId.SHELLY_HTTP
                    item.suggestedType == TileType.TAPO_DEVICE -> ProviderId.TPLINK_KLAP
                    item.suggestedType == TileType.SONOFF_SWITCH -> ProviderId.EWELINK_LAN
                    item.suggestedType == TileType.GOVEE_LIGHT -> ProviderId.GOVEE_LAN
                    item.suggestedType == TileType.TWINKLY_LIGHT -> ProviderId.TWINKLY_LAN
                    item.port == 554 -> ProviderId.RTSP_ONVIF_LAN
                    else -> ProviderId.TUYA_LAN
                }

                if (ConnectedIdentityIndex.isAlreadyOnboarded(
                        providerId = provider.id,
                        rawDeviceId = devId,
                        macAddress = normMac,
                        serialNumber = devId,
                        cloudDeviceId = devId
                    )
                ) {
                    if (cleanIp.isNotBlank()) {
                        DeviceRepository.healExistingTileIpIfMatched(
                            context = context,
                            rawDeviceId = devId,
                            macAddress = normMac,
                            newIpAddress = cleanIp,
                            newPort = item.port
                        )
                    }
                    return@mapNotNull null
                }

                val hasVaultKey = when {
                    isLgTv -> LgThinQWebOsClient.hasVerifiedVaultKey(context, cleanIp, normMac, devId)
                    item.port == 6668 -> ClaraSecretVault.hasVerifiedLocalControlKey(context, "TUYA", devId, cleanIp, normMac)
                    else -> item.actionType == DiscoveredDeviceActionType.ADD_DIRECT
                }
                val rssi = SmartHomeApiClient.getLastObservedBleRssi(normMac)
                val softApSsid = if (entryMode == OnboardingEntryMode.SLOW_BLINKING_AP) item.ipAddress.trim() else ""
                val candidateKey = listOf(entryMode.name, provider.id, normMac, devId, cleanIp, softApSsid)
                    .filter { it.isNotBlank() }
                    .joinToString(":")
                val cleanTitle = cleanConsumerDeviceName(item.suggestedName, item.suggestedType)
                val canonGuid = CrossEcosystemIdentityCorrelator.resolveCanonicalGuid(
                    rawDeviceId = devId.ifBlank { softApSsid },
                    macAddress = normMac,
                    hardwareSerial = devId,
                    cloudDeviceId = devId,
                    activeProviderId = provider.id,
                    fallbackIdentityKey = candidateKey
                )
                val requirement = when {
                    entryMode == OnboardingEntryMode.FAST_BLINKING ->
                        OnboardingRequirement.AutoProvisioningRequired(beaconName = cleanTitle, macOrSsid = normMac)
                    entryMode == OnboardingEntryMode.SLOW_BLINKING_AP ->
                        OnboardingRequirement.AutoProvisioningRequired(
                            beaconName = softApSsid.ifBlank { cleanTitle },
                            macOrSsid = softApSsid.ifBlank { normMac }
                        )
                    isLgTv && !hasVaultKey ->
                        OnboardingRequirement.PhysicalAcceptRequired("Press 'Accept' on your LG TV screen using your TV remote")
                    item.suggestedType == TileType.MATTER_DEVICE && !hasVaultKey ->
                        OnboardingRequirement.ShortCodeRequired()
                    !hasVaultKey && provider == ProviderId.TUYA_LAN ->
                        OnboardingRequirement.InAppAccountWorkflowRequired("TUYA", "SmartLife Account")
                    item.actionType == DiscoveredDeviceActionType.OAUTH_SIGN_IN && !hasVaultKey ->
                        OnboardingRequirement.InAppAccountWorkflowRequired(
                            workflowKey = item.brandOption?.name ?: provider.id,
                            workflowDisplayName = item.suggestedType.brandName
                        )
                    else -> OnboardingRequirement.ZeroTouchReady
                }
                val transports = when (entryMode) {
                    OnboardingEntryMode.FAST_BLINKING -> listOf(TransportRadioType.BLE_SETUP_BEACON)
                    OnboardingEntryMode.SLOW_BLINKING_AP -> listOf(TransportRadioType.WIFI_SOFTAP_BEACON)
                    OnboardingEntryMode.MATTER_OR_CLOUD -> listOf(TransportRadioType.MATTER_FABRIC)
                    OnboardingEntryMode.ALREADY_ON_NETWORK -> listOf(TransportRadioType.SHELLY_TASMOTA_HTTP)
                }

                ResolvedDeviceIdentity(
                    identityKey = candidateKey,
                    canonicalGuid = canonGuid,
                    displayName = cleanTitle,
                    consumerSubtitle = cleanConsumerSubtitle(item, entryMode, hasVaultKey),
                    suggestedRoom = inferRoomFromDeviceTitle(cleanTitle, bridgeSettings.customRooms),
                    deviceClass = item.suggestedType.toSemanticDeviceClass(),
                    deviceSubtype = item.suggestedType.toDefaultDeviceSubtype(cleanTitle),
                    suggestedType = item.suggestedType,
                    combinedConfidence = 0.95f,
                    primaryProviderId = provider.id,
                    fallbackProviderIds = emptyList(),
                    onboardingRequirement = requirement,
                    ipAddress = cleanIp,
                    port = item.port,
                    macAddress = normMac,
                    hardwareSerialOrNodeId = devId.ifBlank { normMac },
                    cloudDeviceId = devId,
                    softApSsid = softApSsid,
                    rssiDbm = rssi,
                    mergedTransports = transports,
                    protocolBadges = listOf(item.protocolBadge).filter { it.isNotBlank() }
                )
            }.distinctBy { it.identityKey }

            val nextStage = if (candidates.isNotEmpty()) {
                UnifiedOnboardingStage.SELECT_DEVICE
            } else {
                UnifiedOnboardingStage.TIMEOUT_NO_DEVICE
            }
            publishSessionStage(
                stage = nextStage,
                sessionId = effectiveSessionId
            )
            _onboardingSessionStateFlow.update {
                it.copy(
                    currentStage = nextStage
                )
            }

            val elapsed = (SystemClock.elapsedRealtime() - scanStartMs).coerceAtLeast(1L)
            DiscoveryFlightRecorder.record(
                eventType = if (candidates.isNotEmpty()) DiscoveryEventType.PRESENTED else DiscoveryEventType.TIMEOUT,
                transportSource = entryMode.primaryRadioPass.name,
                vendor = entryMode.title,
                confidenceScore = if (candidates.isNotEmpty()) 0.95f else 0.0f,
                qualificationStage = nextStage.name,
                outcomeReason = "Wizard scan completed (${candidates.size} candidate(s) in${elapsed}ms)",
                pairingSessionId = effectiveSessionId,
                wifiFrequencyMhz = tabletFreq,
                durationMs = elapsed,
                protocolResultCode = if (candidates.isNotEmpty()) "CANDIDATES_${candidates.size}" else "NO_CANDIDATES"
            )
            candidates
        } finally {
            isScanningFlow.value = false
        }
    }

    /**
     * Collects raw [DeviceFingerprint] signals across all local and cloud transports,
     * runs [DeviceIdentityResolver] to deduplicate and heal existing tiles, and updates
     * [resolvedIdentitiesFlow] with confidence-ranked new devices.
     */
    private fun recordScannerExceptionIfNotCancelled(
        transportSource: String,
        qualificationStage: String,
        t: Throwable
    ) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        val isTimeout = t is java.net.SocketTimeoutException ||
                t.message?.contains("timeout", ignoreCase = true) == true
        DiscoveryFlightRecorder.record(
            eventType = if (isTimeout) DiscoveryEventType.TIMEOUT else DiscoveryEventType.FAILED,
            transportSource = transportSource,
            vendor = transportSource,
            qualificationStage = qualificationStage,
            outcomeReason = "${t.javaClass.simpleName}: ${t.message ?: "Scanner error"}",
            rawPayload = t.stackTraceToString().lineSequence().take(4).joinToString(" | ")
        )
    }

    private const val MIN_ONBOARDABLE_CONFIDENCE = 0.90f

    private fun checkNegativeExclusionReason(
        cluster: List<DeviceFingerprint>,
        bestTitle: String,
        manufacturer: String
    ): String? {
        val combined = buildString {
            append(bestTitle).append(' ')
            append(manufacturer).append(' ')
            cluster.forEach { fp ->
                append(fp.rawAdvertisedName).append(' ')
                append(fp.protocolBadge).append(' ')
            }
        }.lowercase(Locale.US)

        return when {
            combined.contains("hp-print") || combined.contains("laserjet") || combined.contains("officejet") ||
                    combined.contains("deskjet") || combined.contains("pixma") || combined.contains("jetdirect") ||
                    combined.contains("_ipp") || combined.contains("_printer") ->
                "Excluded printer peripheral ($bestTitle)"
            combined.contains("internetgatewaydevice") || combined.contains("wandevice") ||
                    combined.contains("sky hub") || combined.contains("sky q") || combined.contains("sky stream") ||
                    combined.contains("bt hub") || combined.contains("smarthub") || combined.contains("fritz!box") ->
                "Excluded ISP router / gateway / set-top box ($bestTitle)"
            combined.contains("synology") || combined.contains("diskstation") || combined.contains("qnap") ||
                    combined.contains("truenas") || combined.contains("plex media server") ||
                    combined.contains("airtunes") || combined.contains("airplay") ->
                "Excluded NAS / AirPlay / media server ($bestTitle)"
            combined.contains("playstation") || combined.contains("xbox") ->
                "Excluded game console ($bestTitle)"
            combined.contains("_hap._tcp") || combined.contains("philips hue") ->
                "Excluded unsupported local ecosystem ($bestTitle)"
            cluster.all { it.transport == TransportRadioType.RAW_TCP_PROBE } ->
                "Excluded unverified Stage 1 raw TCP port probe ($bestTitle)"
            else -> null
        }
    }

    private fun classifyQualificationStage(score: Float): QualificationStage = when {
        score >= QualificationStage.STAGE_5_ONBOARDABLE_DEVICE.minScore -> QualificationStage.STAGE_5_ONBOARDABLE_DEVICE
        score >= QualificationStage.STAGE_4_VALIDATED_DEVICE.minScore -> QualificationStage.STAGE_4_VALIDATED_DEVICE
        score >= QualificationStage.STAGE_3_SMART_DEVICE_CANDIDATE.minScore -> QualificationStage.STAGE_3_SMART_DEVICE_CANDIDATE
        score >= QualificationStage.STAGE_2_POTENTIAL_DEVICE.minScore -> QualificationStage.STAGE_2_POTENTIAL_DEVICE
        else -> QualificationStage.STAGE_1_DISCOVERED_SERVICE
    }

    private data class IdentitySightingHistory(
        val firstSeenEpochMs: Long,
        val lastSeenEpochMs: Long,
        val seenCount: Int
    )

    private val sightingHistoryByIdentityKey = ConcurrentHashMap<String, IdentitySightingHistory>()

    private val IEEE_OUI_VENDOR_MAP = mapOf(
        "D81F12" to "Tuya Smart",
        "10D561" to "Tuya Smart",
        "508A06" to "Tuya Smart",
        "7CF666" to "Tuya Smart",
        "84E342" to "Tuya Smart",
        "A09208" to "Tuya Smart",
        "EC71DB" to "Reolink",
        "3CEF8C" to "Dahua / Imou",
        "4C11BF" to "Dahua / Imou",
        "9002A9" to "Dahua / Imou",
        "A0BD1D" to "Dahua / Imou",
        "38AF29" to "Dahua / Imou",
        "C056E3" to "Hikvision / EZVIZ",
        "4C62DF" to "Hikvision / EZVIZ",
        "1868CB" to "Hikvision / EZVIZ",
        "2857BE" to "Hikvision / EZVIZ",
        "4419B6" to "Hikvision / EZVIZ",
        "BCAD28" to "Hikvision / EZVIZ",
        "E0508B" to "Hikvision / EZVIZ",
        "50C7BF" to "TP-Link",
        "6032B1" to "TP-Link",
        "B0BE76" to "TP-Link",
        "98DAC4" to "TP-Link",
        "E848B8" to "TP-Link",
        "001788" to "Philips Hue (Signify)",
        "ECB5FA" to "Philips Hue (Signify)",
        "34EA34" to "BroadLink / HangZhou",
        "E8DB84" to "Shelly (Allterco)",
        "C45BBE" to "Shelly (Allterco)",
        "A4CF12" to "Espressif (ESPHome / Tasmota / Sonoff)",
        "84F3EB" to "Espressif (ESPHome / Tasmota / Sonoff)",
        "DC4F22" to "Espressif (ESPHome / Tasmota / Sonoff)",
        "18FE34" to "Espressif (ESPHome / Tasmota / Sonoff)",
        "64B708" to "LG Electronics",
        "CC2D83" to "LG Electronics",
        "A823FE" to "LG Electronics",
        "F8042E" to "Google Nest",
        "546009" to "Google Nest",
        "18B430" to "Google Nest",
        "D4F547" to "Google Nest"
    )

    fun resolveManufacturerFromMacOrHints(
        macAddress: String,
        serviceUuids: List<String>,
        protocolBadges: List<String>,
        fallbackBrand: String
    ): String {
        val cleanMac = CrossEcosystemIdentityCorrelator.normalizeMac(macAddress)
        if (cleanMac.length >= 6) {
            IEEE_OUI_VENDOR_MAP[cleanMac.take(6)]?.let { return it }
        }
        val uuidsUpper = serviceUuids.map { it.uppercase(Locale.US) }
        val badgesUpper = protocolBadges.joinToString(" ").uppercase(Locale.US)
        return when {
            uuidsUpper.any { it.contains("FD50") } || badgesUpper.contains("0XFD50") -> "Tuya Smart"
            uuidsUpper.any { it.contains("FEAF") } || badgesUpper.contains("0XFEAF") -> "Google Nest"
            uuidsUpper.any { it.contains("FFF6") } || badgesUpper.contains("MATTER") -> "Matter CSA Fabric"
            uuidsUpper.any { it.contains("EC88") } || badgesUpper.contains("GOVEE") -> "Govee"
            badgesUpper.contains("TWINKLY") || badgesUpper.contains("XLED") -> "Twinkly"
            badgesUpper.contains("WEBOS") || badgesUpper.contains("LG") -> "LG Electronics"
            badgesUpper.contains("KLAP") || badgesUpper.contains("TAPO") || badgesUpper.contains("KASA") -> "TP-Link"
            badgesUpper.contains("SONOFF") || badgesUpper.contains("EWELINK") || badgesUpper.contains("8081") -> "Sonoff / CoolKit"
            badgesUpper.contains("SHELLY") -> "Shelly (Allterco)"
            else -> fallbackBrand
        }
    }

    private fun normalizeIp(rawIp: String): String =
        rawIp.substringBefore(":").trim().takeIf { it.contains(".") && it != "0.0.0.0" }.orEmpty()

    fun computeNoisyOrConfidence(scores: List<Float>): Float {
        if (scores.isEmpty()) return 0f
        var failureProduct = 1.0
        for (s in scores) {
            val clamped = s.coerceIn(0.05f, 0.99f).toDouble()
            failureProduct *= (1.0 - clamped)
        }
        return kotlin.math.min(0.99f, (1.0 - failureProduct).toFloat())
    }

    private fun sharesIdentityCorrelation(
        a: DeviceFingerprint,
        b: DeviceFingerprint,
        arpMacByIp: Map<String, String>
    ): Boolean {
        val ipA = normalizeIp(a.ipAddress)
        val ipB = normalizeIp(b.ipAddress)
        val macA = CrossEcosystemIdentityCorrelator.normalizeMac(a.macAddress.ifBlank { if (ipA.isNotBlank()) arpMacByIp[ipA].orEmpty() else "" })
        val macB = CrossEcosystemIdentityCorrelator.normalizeMac(b.macAddress.ifBlank { if (ipB.isNotBlank()) arpMacByIp[ipB].orEmpty() else "" })

        if (CrossEcosystemIdentityCorrelator.areMacsCorrelated(macA, macB)) return true

        val snA = a.hardwareSerialOrNodeId.trim().lowercase(Locale.US).takeUnless { ConnectedIdentityIndex.isBareIpLiteral(it) }.orEmpty()
        val snB = b.hardwareSerialOrNodeId.trim().lowercase(Locale.US).takeUnless { ConnectedIdentityIndex.isBareIpLiteral(it) }.orEmpty()
        if (snA.length >= 5 && snA == snB) return true

        val cloudA = a.cloudDeviceId.trim().lowercase(Locale.US).takeUnless { ConnectedIdentityIndex.isBareIpLiteral(it) }.orEmpty()
        val cloudB = b.cloudDeviceId.trim().lowercase(Locale.US).takeUnless { ConnectedIdentityIndex.isBareIpLiteral(it) }.orEmpty()
        if (cloudA.length >= 5 && cloudA == cloudB) return true

        if (a.matterDiscriminator > 0 && a.matterDiscriminator == b.matterDiscriminator) return true

        val hasConflictingIds = (snA.length >= 5 && snB.length >= 5 && snA != snB) ||
                (cloudA.length >= 5 && cloudB.length >= 5 && cloudA != cloudB) ||
                (macA.isNotBlank() && macB.isNotBlank() && !CrossEcosystemIdentityCorrelator.areMacsCorrelated(macA, macB))
        if (!hasConflictingIds &&
            a.parentNetworkNodeId.isBlank() &&
            b.parentNetworkNodeId.isBlank() &&
            ipA.isNotBlank() &&
            ipA == ipB &&
            a.deviceClass == b.deviceClass
        ) {
            return true
        }

        return false
    }

    fun resolveOnboardingRequirement(cluster: List<DeviceFingerprint>): OnboardingRequirement {
        val best = cluster.maxByOrNull { it.confidenceScore * 100 + it.transport.basePriority } ?: cluster.first()
        val appCtx = ClaraApplication.appContextOrNull()

        val lgFp = cluster.firstOrNull {
            it.transport == TransportRadioType.LG_WEBOS_SSAP || it.suggestedType == TileType.LG_TV
        }
        if (lgFp != null) {
            val lgIp = normalizeIp(lgFp.ipAddress)
            val lgMac = CrossEcosystemIdentityCorrelator.normalizeMac(lgFp.macAddress)
            val lgDevId = lgFp.hardwareSerialOrNodeId.ifBlank { lgFp.cloudDeviceId }
            val hasVerifiedLgKey = appCtx != null && (
                    LgThinQWebOsClient.hasVerifiedVaultKey(appCtx, lgIp, lgMac, lgDevId) ||
                            ClaraSecretVault.hasVerifiedLocalControlKey(appCtx, "LG_WEBOS", lgDevId, lgIp, lgMac)
                    )
            return if (hasVerifiedLgKey) {
                OnboardingRequirement.ZeroTouchReady
            } else {
                OnboardingRequirement.PhysicalAcceptRequired(
                    promptMessage = "Press 'Accept' on your LG TV screen using your TV remote"
                )
            }
        }

        val hasMissingRequiredVaultKey = cluster.all { fp ->
            val devId = fp.hardwareSerialOrNodeId.ifBlank { fp.cloudDeviceId }
            when (fp.transport) {
                TransportRadioType.TUYA_LAN_6668 ->
                    fp.localKeyOrPin.length != 16 &&
                            (appCtx == null || !ClaraSecretVault
                                .hasVerifiedLocalControlKey(appCtx, "TUYA", devId, fp.ipAddress, fp.macAddress))
                TransportRadioType.KASA_KLAP_LAN ->
                    fp.port == 80 && fp.requiresAccountAuth &&
                            (appCtx == null || !ClaraSecretVault
                                .hasVerifiedLocalControlKey(appCtx, "TPLINK", devId, fp.ipAddress, fp.macAddress))
                TransportRadioType.SONOFF_LAN_8081 ->
                    fp.requiresAccountAuth && fp.localKeyOrPin.isBlank() &&
                            (appCtx == null || !ClaraSecretVault
                                .hasVerifiedLocalControlKey(appCtx, "EWELINK", devId, fp.ipAddress, fp.macAddress))
                TransportRadioType.MATTER_FABRIC ->
                    appCtx == null || !ClaraSecretVault
                        .hasVerifiedLocalControlKey(appCtx, "MATTER", devId, fp.ipAddress, fp.macAddress)
                else -> false
            }
        }
        val hasOperationalLocal = !hasMissingRequiredVaultKey && cluster.any {
            !it.requiresProvisioning && !it.requiresShortCode && !it.requiresPhysicalAccept && !it.requiresAccountAuth &&
                    it.confidenceScore >= 0.85f
        }
        if (hasOperationalLocal) {
            return OnboardingRequirement.ZeroTouchReady
        }
        if (hasMissingRequiredVaultKey) {
            return when (best.transport) {
                TransportRadioType.MATTER_FABRIC -> OnboardingRequirement.ShortCodeRequired(
                    hintLabel = if (best.requiresShortCode) {
                        "Enter 11-digit Matter setup code or scan QR"
                    } else {
                        "Already paired to another home — open Multi-Admin Pairing Window in Google/Apple Home & enter dynamic code"
                    }
                )
                TransportRadioType.TUYA_LAN_6668 -> OnboardingRequirement.InAppAccountWorkflowRequired(
                    workflowKey = "TUYA",
                    workflowDisplayName = "Sign in to SmartLife (or hold reset button 5s to re-pair)"
                )
                TransportRadioType.KASA_KLAP_LAN -> OnboardingRequirement.InAppAccountWorkflowRequired(
                    workflowKey = "TPLINK",
                    workflowDisplayName = "Sign in to TP-Link Tapo"
                )
                TransportRadioType.SONOFF_LAN_8081 -> OnboardingRequirement.InAppAccountWorkflowRequired(
                    workflowKey = "EWELINK",
                    workflowDisplayName = "Sign in to eWeLink"
                )
                else -> OnboardingRequirement.ZeroTouchReady
            }
        }
        return when {
            best.requiresPhysicalAccept -> OnboardingRequirement.PhysicalAcceptRequired()
            best.requiresShortCode -> OnboardingRequirement.ShortCodeRequired()
            best.requiresProvisioning -> OnboardingRequirement.AutoProvisioningRequired(
                beaconName = best.displayName,
                macOrSsid = best.macAddress.ifBlank { best.ipAddress }
            )
            best.requiresAccountAuth -> OnboardingRequirement.InAppAccountWorkflowRequired(
                workflowKey = best.accountWorkflowKey.ifBlank { best.suggestedType.name },
                workflowDisplayName = best.suggestedType.brandName
            )
            else -> OnboardingRequirement.ZeroTouchReady
        }
    }

    suspend fun resolveAndHealExistingTiles(
        context: Context,
        rawFingerprints: List<DeviceFingerprint>,
        tiles: MutableList<SmartTile>,
        bridgeSettings: BridgeSettings,
        scope: CoroutineScope
    ): List<ResolvedDeviceIdentity> {
        if (rawFingerprints.isEmpty()) return emptyList()
        ConnectedIdentityIndex.rebuildFromTiles(tiles)
        val arpTable = CrossEcosystemIdentityCorrelator.getObservedIpToMacMap()

        val verifiedFingerprints = rawFingerprints.filterNot { fp ->
            if (fp.transport != TransportRadioType.BLE_SETUP_BEACON) return@filterNot false
            val rawLower = fp.rawAdvertisedName.trim().lowercase(Locale.US)
            val isAmbientBlChip = rawLower.startsWith("bl50") ||
                    rawLower.startsWith("bl60") ||
                    rawLower.startsWith("bk72") ||
                    rawLower.contains("ble_mouse") ||
                    rawLower.contains("keyboard")
            val hasVerifiedSetupUuid = fp.serviceUuids.any { uuid ->
                val u = uuid.uppercase(Locale.US)
                u.contains("FD50") || u.contains("FEAF") || u.contains("FFF6") || u.contains("EC88")
            } || fp.protocolBadge.contains("0x", ignoreCase = true)
            val shouldFilter = isAmbientBlChip && !hasVerifiedSetupUuid
            if (shouldFilter) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FILTERED,
                    transportSource = fp.transport.name,
                    hardwareId = fp.hardwareSerialOrNodeId.ifBlank { fp.macAddress },
                    macAddress = fp.macAddress,
                    ipAddress = fp.ipAddress,
                    port = fp.port,
                    vendor = fp.ouiManufacturer.ifBlank { fp.deviceClass.name },
                    confidenceScore = fp.confidenceScore,
                    qualificationStage = QualificationStage.STAGE_2_POTENTIAL_DEVICE.name,
                    outcomeReason = "Ambient BLE chip filtered: $rawLower",
                    rawPayload = fp.rawAdvertisedName,
                    cloudId = fp.cloudDeviceId
                )
            }
            shouldFilter
        }
        if (verifiedFingerprints.isEmpty()) return emptyList()

        val clusters = mutableListOf<MutableList<DeviceFingerprint>>()
        for (fp in verifiedFingerprints) {
            val matchingCluster = clusters.firstOrNull { cluster ->
                cluster.any { existing -> sharesIdentityCorrelation(existing, fp, arpTable) }
            }
            if (matchingCluster != null) {
                matchingCluster.add(fp)
            } else {
                clusters.add(mutableListOf(fp))
            }
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var anyExistingTileHealed = false
        val newIdentities = mutableListOf<ResolvedDeviceIdentity>()

        for (cluster in clusters) {
            val rawConfidence = computeNoisyOrConfidence(cluster.map { it.confidenceScore })
            val combinedConfidence = if (cluster.all { it.transport == TransportRadioType.RAW_TCP_PROBE }) {
                rawConfidence.coerceAtMost(0.25f)
            } else {
                rawConfidence
            }

            val sortedByPriority = cluster.sortedWith(
                compareByDescending<DeviceFingerprint> { !it.requiresProvisioning && !it.requiresAccountAuth }
                    .thenByDescending { it.transport.basePriority }
                    .thenByDescending { it.confidenceScore }
            )
            val primary = sortedByPriority.first()
            val mergedIp = sortedByPriority.firstNotNullOfOrNull { normalizeIp(it.ipAddress).takeIf { ip -> ip.isNotBlank() } }.orEmpty()
            val mergedPort = sortedByPriority.firstNotNullOfOrNull { it.port.takeIf { p -> p > 0 } } ?: 0
            val mergedMac = sortedByPriority.firstNotNullOfOrNull {
                CrossEcosystemIdentityCorrelator.normalizeMac(it.macAddress).takeIf { m -> m.isNotBlank() }
            } ?: (if (mergedIp.isNotBlank()) CrossEcosystemIdentityCorrelator.normalizeMac(arpTable[mergedIp].orEmpty()) else "")
            val mergedSn = sortedByPriority.firstNotNullOfOrNull { it.hardwareSerialOrNodeId.trim().takeIf { s -> s.isNotBlank() } }.orEmpty()
            val mergedCloudId = sortedByPriority.firstNotNullOfOrNull { it.cloudDeviceId.trim().takeIf { c -> c.isNotBlank() } }.orEmpty()
            val mergedKey = sortedByPriority.firstNotNullOfOrNull { it.localKeyOrPin.trim().takeIf { k -> k.isNotBlank() } }.orEmpty()
            val mergedCaps = sortedByPriority.firstNotNullOfOrNull { it.capabilities.takeIf { c -> c.isNotEmpty() } }.orEmpty()
            val hasPower = cluster.any { it.hasPowerTelemetry || it.powerWatts > 0f || it.totalEnergyKwh > 0f }
            val maxGangs = cluster.maxOf { it.multiGangCount.coerceAtLeast(1) }

            val bestTitle = sortedByPriority.firstNotNullOfOrNull { fp ->
                fp.displayName.trim().takeIf { name ->
                    name.isNotBlank() && !name.startsWith("Smart Device (") && !name.contains("Setup Beacon")
                }
            } ?: primary.displayName.trim().ifBlank { primary.deviceClass.label }

            val providerChain = sortedByPriority
                .map { fp -> (fp.explicitProviderId ?: fp.transport.toProviderId(fp.suggestedType)).id }
                .filter { it.isNotBlank() && it != ProviderId.UNKNOWN.id }
                .distinct()
            val primaryProvider = providerChain.firstOrNull()
                ?: (primary.explicitProviderId ?: primary.transport.toProviderId(primary.suggestedType)).id
            val fallbackProviders = providerChain.drop(1)

            val identityKey = when {
                mergedSn.isNotBlank() -> "id_sn_${mergedSn.lowercase(Locale.US)}"
                mergedCloudId.isNotBlank() -> "id_cloud_${mergedCloudId.lowercase(Locale.US)}"
                mergedMac.isNotBlank() -> "id_mac_${mergedMac.lowercase(Locale.US)}"
                mergedIp.isNotBlank() -> "id_ip_${mergedIp}_${primary.deviceClass.name}"
                else -> "id_${bestTitle.lowercase(Locale.US)}_${primary.deviceClass.name}"
            }

            val nonIpSn = mergedSn.takeUnless { ConnectedIdentityIndex.isBareIpLiteral(it) }.orEmpty()
            val nonIpCloudId = mergedCloudId.takeUnless { ConnectedIdentityIndex.isBareIpLiteral(it) }.orEmpty()
            val matchedPersistedGuid = CrossEcosystemIdentityCorrelator.resolveCanonicalGuidForCandidate(
                context = context,
                rawMac = mergedMac,
                hardwareSerial = nonIpSn,
                tuyaGwId = nonIpCloudId.ifBlank { nonIpSn },
                haEntityId = nonIpCloudId,
                matterNodeId = nonIpSn,
                cloudDeviceId = nonIpCloudId,
                observedEndpoint = if (mergedPort > 0 && mergedIp.isNotBlank()) "$mergedIp:$mergedPort" else mergedIp
            )
            val matchedIndexTileIds = ConnectedIdentityIndex.findMatchingTileIdsByMacOrId(
                rawDeviceId = nonIpSn.ifBlank { nonIpCloudId },
                macAddress = mergedMac,
                providerId = primaryProvider,
                serialNumber = nonIpSn,
                cloudDeviceId = nonIpCloudId
            )

            val candidateGuid = CrossEcosystemIdentityCorrelator.resolveCanonicalGuid(
                rawDeviceId = nonIpSn.ifBlank { nonIpCloudId },
                macAddress = mergedMac,
                hardwareSerial = nonIpSn,
                cloudDeviceId = nonIpCloudId,
                activeProviderId = primaryProvider,
                fallbackIdentityKey = identityKey
            )

            val matchingTileIndices = tiles.indices.filter { idx ->
                val t = tiles[idx]
                val tSn = t.serialNumber.trim().takeUnless { ConnectedIdentityIndex.isBareIpLiteral(it) }.orEmpty()
                val tEntity = t.entityId.trim().takeUnless { ConnectedIdentityIndex.isBareIpLiteral(it) }.orEmpty()
                val tGuid = t.canonicalGuid.ifBlank { t.resolvedCanonicalGuid() }
                (t.id in matchedIndexTileIds) ||
                        (matchedPersistedGuid != null && (tGuid == matchedPersistedGuid || t.resolvedCanonicalGuid() == matchedPersistedGuid)) ||
                        (tGuid == candidateGuid) ||
                        (nonIpSn.isNotBlank() && (tSn.equals(nonIpSn, true) || tEntity.equals(nonIpSn, true))) ||
                        (nonIpCloudId.isNotBlank() && (tSn.equals(nonIpCloudId, true) || tEntity.equals(nonIpCloudId, true))) ||
                        CrossEcosystemIdentityCorrelator.areMacsCorrelated(t.macAddress.ifBlank { tSn }, mergedMac)
            }

            if (matchingTileIndices.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    val registryUpdatedGuids = HashSet<String>()
                    for (existingTileIdx in matchingTileIndices) {
                        if (existingTileIdx !in tiles.indices) continue
                        val current = tiles[existingTileIdx]
                        val wasOffline = !current.isOnline
                        val endpointIp = if (
                            (current.type == TileType.NEST_MINI || current.type == TileType.SONOFF_SWITCH || current.ipAddress.contains(":")) &&
                            mergedPort > 0 &&
                            mergedIp.isNotBlank()
                        ) {
                            "$mergedIp:$mergedPort"
                        } else {
                            mergedIp.ifBlank { current.ipAddress }
                        }
                        val resolvedGuid = current.canonicalGuid.ifBlank { candidateGuid }
                        val ipDrifted = mergedIp.isNotBlank() && normalizeIp(current.ipAddress) != mergedIp
                        if (ipDrifted && registryUpdatedGuids.add(resolvedGuid)) {
                            ClaraDeviceRegistryStore.getInstance(context).updateDeviceEndpointAndMac(
                                canonicalGuid = resolvedGuid,
                                newHostIp = mergedIp,
                                newPort = mergedPort,
                                learnedMac = mergedMac.ifBlank { current.macAddress }
                            )
                        }
                        if ((wasOffline && primary.isOnline) || ipDrifted) {
                            val rediscoveredDriver = ProviderId.fromRawKey(primaryProvider)
                                .takeIf { it != ProviderId.UNKNOWN }
                                ?.displayName ?: current.vendorDriverForRecorder
                            DeviceLifecycleRecorder.recordTileTransition(
                                tile = current.copy(canonicalGuid = resolvedGuid),
                                transition = MandatoryLifecycleTransition.REDISCOVERED,
                                previousState = current.effectiveLifecycleState.name,
                                newState = if (primary.isOnline) DeviceLifecycleState.ONLINE.name else current.effectiveLifecycleState.name,
                                activeTransport = primaryProvider,
                                vendorDriver = rediscoveredDriver,
                                latencyMs = (System.currentTimeMillis() - primary.discoveredAtEpochMs).coerceAtLeast(0L),
                                protocolResultCode = if (ipDrifted) "DHCP_IP_HEALED" else "SIGNAL_REDISCOVERED"
                            )
                        }
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.FILTERED,
                            transportSource = primaryProvider,
                            hardwareId = mergedSn.ifBlank { current.serialNumber.ifBlank { current.entityId } },
                            macAddress = mergedMac.ifBlank { current.macAddress },
                            ipAddress = mergedIp.ifBlank { normalizeIp(current.ipAddress) },
                            port = mergedPort,
                            vendor = current.title.ifBlank { bestTitle },
                            confidenceScore = combinedConfidence,
                            qualificationStage = QualificationStage.STAGE_4_VALIDATED_DEVICE.name,
                            outcomeReason = if (ipDrifted) {
                                "HEALED_DHCP_IP_FOR_ONBOARDED_DEVICE (${current.ipAddress.ifBlank { "none" }} -> $endpointIp)"
                            } else {
                                "SUPPRESSED_ALREADY_ONBOARDED_GUID ($resolvedGuid)"
                            },
                            rawPayload = sortedByPriority.joinToString("+") { it.protocolBadge },
                            cloudId = mergedCloudId.ifBlank { current.entityId }
                        )
                        if (mergedSn.isNotBlank() && mergedIp.isNotBlank()) {
                            TuyaApiClient.registerDeviceLanAddress(mergedSn, mergedIp, mergedKey.ifBlank { current.secretCode })
                        }
                        val (evolvedCaps, evolvedHash) = DeviceRepository.mergeCapabilitiesWithSchemaEvolution(
                            existing = current,
                            incomingCapabilities = mergedCaps
                        )
                        val candidate = current.copy(
                            ipAddress = endpointIp,
                            macAddress = current.macAddress.ifBlank { mergedMac },
                            serialNumber = current.serialNumber.ifBlank { mergedSn.ifBlank { mergedCloudId } },
                            entityId = current.entityId.ifBlank { mergedCloudId.ifBlank { mergedSn } },
                            secretCode = current.secretCode.ifBlank { mergedKey },
                            isOnline = primary.isOnline,
                            isActive = primary.isActive,
                            isSocket2Active = if (maxGangs >= 2) primary.isSocket2Active else current.isSocket2Active,
                            multiGangSocketCount = maxOf(current.multiGangSocketCount, maxGangs),
                            temperature = if (current.isThermostatTile || current.type == TileType.SMART_SENSOR) primary.temperatureC else current.temperature,
                            targetTemperature = if (current.isThermostatTile && primary.targetTempC > 5f) primary.targetTempC else current.targetTemperature,
                            humidity = if (primary.humidityPct > 0) primary.humidityPct else current.humidity,
                            hvacMode = if (current.isThermostatTile && primary.hvacMode.isNotBlank()) primary.hvacMode else current.hvacMode,
                            hvacStatus = if (current.isThermostatTile && primary.hvacStatus.isNotBlank()) primary.hvacStatus else current.hvacStatus,
                            volume = if (current.isSpeakerTile || current.hasDimmingCapability) primary.levelOrVolumePct else current.volume,
                            powerWatts = if (primary.powerWatts > 0f || !primary.isActive) primary.powerWatts else current.powerWatts,
                            voltageVolts = if (primary.voltageVolts > 0f) primary.voltageVolts else current.voltageVolts,
                            currentMilliamps = if (primary.currentMilliamps > 0f) primary.currentMilliamps else current.currentMilliamps,
                            totalEnergyKwh = if (primary.totalEnergyKwh > 0f) primary.totalEnergyKwh else current.totalEnergyKwh,
                            hasExplicitPowerTelemetry = current.hasExplicitPowerTelemetry || hasPower,
                            capabilities = evolvedCaps,
                            capabilitySchemaHash = evolvedHash,
                            activeProviderId = current.activeProviderId.ifBlank { primaryProvider },
                            fallbackProviderIds = if (current.fallbackProviderIds.isEmpty()) fallbackProviders else current.fallbackProviderIds,
                            parentNetworkNodeId = current.parentNetworkNodeId.ifBlank { primary.parentNetworkNodeId }
                        )
                        val hasLocalTransportSignal = mergedIp.isNotBlank() ||
                                sortedByPriority.any { it.transport != TransportRadioType.CLOUD_ACCOUNT_API }
                        val resolvedAuthority = if (hasLocalTransportSignal) {
                            StateAuthorityTier.DEVICE_HARDWARE_TRUTH
                        } else {
                            StateAuthorityTier.CLOUD_SHADOW_STATE
                        }
                        val reconciled = DeviceRepository.reconcileRemoteTileUpdate(
                            existing = current,
                            incoming = candidate,
                            incomingAuthority = resolvedAuthority,
                            sourceName = primary.protocolBadge
                        )
                        if (reconciled != current) {
                            tiles[existingTileIdx] = reconciled
                            anyExistingTileHealed = true
                        }
                        JarvisLiveBridge.registerOrUpdateFailoverPolicy(
                            ProviderFailoverPolicy(
                                deviceId = current.id,
                                primaryProviderId = primaryProvider,
                                orderedFallbackProviderIds = fallbackProviders,
                                activeProviderId = current.activeProviderId.ifBlank { primaryProvider },
                                parentNetworkNodeId = current.parentNetworkNodeId.ifBlank { primary.parentNetworkNodeId }
                            )
                        )
                        if (wasOffline && reconciled.isOnline && audioManager != null) {
                            replayQueuedShadowIntentsForTile(
                                context = context,
                                tile = tiles[existingTileIdx],
                                bridge = bridgeSettings,
                                audioManager = audioManager,
                                scope = scope
                            )
                        }
                    }
                }
                continue
            }

            val clusterBadgesPayload = sortedByPriority.map { it.protocolBadge }.filter { it.isNotBlank() }.distinct().joinToString("+")

            val exclusionReason = checkNegativeExclusionReason(cluster, bestTitle, primary.ouiManufacturer)
            if (exclusionReason != null) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FILTERED,
                    transportSource = primaryProvider,
                    hardwareId = mergedSn.ifBlank { identityKey },
                    macAddress = mergedMac,
                    ipAddress = mergedIp,
                    port = mergedPort,
                    vendor = bestTitle,
                    confidenceScore = combinedConfidence,
                    qualificationStage = QualificationStage.STAGE_2_POTENTIAL_DEVICE.name,
                    outcomeReason = exclusionReason,
                    rawPayload = clusterBadgesPayload,
                    cloudId = mergedCloudId
                )
                continue
            }

            if (ConnectedIdentityIndex.isAlreadyOnboarded(
                    providerId = primaryProvider,
                    rawDeviceId = nonIpSn.ifBlank { nonIpCloudId },
                    macAddress = mergedMac,
                    serialNumber = nonIpSn,
                    cloudDeviceId = nonIpCloudId
                )
            ) {
                if (mergedIp.isNotBlank()) {
                    DeviceRepository.healExistingTileIpIfMatched(
                        context = context,
                        rawDeviceId = nonIpSn.ifBlank { nonIpCloudId },
                        macAddress = mergedMac,
                        newIpAddress = mergedIp,
                        newPort = mergedPort
                    )
                }
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FILTERED,
                    transportSource = primaryProvider,
                    hardwareId = mergedSn.ifBlank { identityKey },
                    macAddress = mergedMac,
                    ipAddress = mergedIp,
                    port = mergedPort,
                    vendor = bestTitle,
                    confidenceScore = combinedConfidence,
                    qualificationStage = QualificationStage.STAGE_4_VALIDATED_DEVICE.name,
                    outcomeReason = "SUPPRESSED_ALREADY_ONBOARDED_GUID (${matchedPersistedGuid ?: candidateGuid})",
                    rawPayload = clusterBadgesPayload,
                    cloudId = mergedCloudId
                )
                continue
            }

            val requirement = resolveOnboardingRequirement(cluster)
            if (DeviceRepository.isDeviceDismissed(context, mergedSn, mergedCloudId, bestTitle, identityKey)) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FILTERED,
                    transportSource = primaryProvider,
                    hardwareId = mergedSn.ifBlank { identityKey },
                    macAddress = mergedMac,
                    ipAddress = mergedIp,
                    port = mergedPort,
                    vendor = bestTitle,
                    confidenceScore = combinedConfidence,
                    qualificationStage = QualificationStage.STAGE_4_VALIDATED_DEVICE.name,
                    outcomeReason = "SUPPRESSED_USER_DISMISSED",
                    rawPayload = clusterBadgesPayload,
                    cloudId = mergedCloudId
                )
                continue
            }

            val qualificationStage = classifyQualificationStage(combinedConfidence)
            if (combinedConfidence < MIN_ONBOARDABLE_CONFIDENCE) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FILTERED,
                    transportSource = primaryProvider,
                    hardwareId = mergedSn.ifBlank { identityKey },
                    macAddress = mergedMac,
                    ipAddress = mergedIp,
                    port = mergedPort,
                    vendor = bestTitle,
                    confidenceScore = combinedConfidence,
                    qualificationStage = qualificationStage.name,
                    outcomeReason = "Held below Stage 5 UI gate (${(combinedConfidence * 100).roundToInt()}% < 90%)",
                    rawPayload = clusterBadgesPayload,
                    cloudId = mergedCloudId
                )
                continue
            }

            val inferredRoom = inferRoomFromDeviceTitle(bestTitle, bridgeSettings.customRooms)
            val devLookupKey = mergedSn.ifBlank { mergedCloudId }.ifBlank { mergedIp }.ifBlank { identityKey }
            val existingSavedResolution = SemanticClassifier.getResolution(devLookupKey, context)
            val semanticResolution = if (existingSavedResolution?.source == ClassificationSource.MANUAL_OVERRIDE) {
                existingSavedResolution
            } else if (mergedCaps.isNotEmpty() || primary.suggestedType == TileType.LYTMI_SYNC_BOX) {
                SemanticClassifier.resolve(
                    capabilities = mergedCaps,
                    categoryCode = "",
                    multiGangSocketCount = maxGangs,
                    hasPowerTelemetry = hasPower,
                    schemaHash = CapabilitySchemaHasher.computeHash(mergedCaps),
                    titleHint = bestTitle
                )
            } else {
                null
            }
            val resolvedClass = when {
                semanticResolution != null -> semanticResolution.deviceClass
                hasPower && primary.deviceClass == DeviceClass.SWITCH -> DeviceClass.ENERGY
                else -> primary.deviceClass
            }
            val inferredFingerprint = AppliancePowerFingerprinter.inferApplianceFromTelemetry(
                powerWatts = primary.powerWatts,
                titleHint = bestTitle
            )
            val resolvedSubtype = semanticResolution?.deviceSubtype ?: primary.suggestedType.toDefaultDeviceSubtype(
                titleHint = bestTitle,
                ipAddressHint = mergedIp,
                fingerprintCategory = inferredFingerprint
            )

            val nowMs = System.currentTimeMillis()
            val prevSighting = sightingHistoryByIdentityKey[identityKey]
            val updatedSighting = if (prevSighting != null) {
                IdentitySightingHistory(
                    firstSeenEpochMs = prevSighting.firstSeenEpochMs,
                    lastSeenEpochMs = nowMs,
                    seenCount = prevSighting.seenCount + 1
                )
            } else {
                val earliestFpMs = cluster.minOfOrNull { it.discoveredAtEpochMs } ?: nowMs
                IdentitySightingHistory(
                    firstSeenEpochMs = earliestFpMs,
                    lastSeenEpochMs = nowMs,
                    seenCount = 1
                )
            }
            sightingHistoryByIdentityKey[identityKey] = updatedSighting

            val mergedRawAdvertisedName = sortedByPriority.firstNotNullOfOrNull {
                it.rawAdvertisedName.trim().takeIf { n -> n.isNotBlank() }
            } ?: bestTitle
            val mergedServiceUuids = sortedByPriority.flatMap { it.serviceUuids }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val mergedManufacturerDataHex = sortedByPriority.firstNotNullOfOrNull {
                it.manufacturerDataHex.trim().takeIf { m -> m.isNotBlank() }
            }.orEmpty()
            val bestRssiDbm = cluster.mapNotNull { it.rssiDbm }.maxOrNull()
            val distinctBadges = sortedByPriority.map { it.protocolBadge }.filter { it.isNotBlank() }.distinct()
            val resolvedManufacturer = sortedByPriority.firstNotNullOfOrNull {
                it.ouiManufacturer.trim().takeIf { v -> v.isNotBlank() }
            } ?: resolveManufacturerFromMacOrHints(
                macAddress = mergedMac,
                serviceUuids = mergedServiceUuids,
                protocolBadges = distinctBadges,
                fallbackBrand = primary.suggestedType.brandName
            )

            val reasoningChain = buildList {
                addAll(sortedByPriority.flatMap { it.discoveryReasoning })
                if (mergedServiceUuids.isNotEmpty()) {
                    add("BLE UUID ${mergedServiceUuids.joinToString("/")}")
                }
                if (mergedMac.length >= 6) {
                    add("OUI ${mergedMac.take(6)} ($resolvedManufacturer)")
                } else if (resolvedManufacturer.isNotBlank()) {
                    add("Vendor: $resolvedManufacturer")
                }
                if (bestRssiDbm != null) {
                    add("Signal $bestRssiDbm dBm")
                }
                if (mergedCaps.isNotEmpty()) {
                    add("${mergedCaps.size} hardware DPs (${mergedCaps.take(3).joinToString(",") { it.key }})")
                }
                if (semanticResolution != null) {
                    add("Classifier -> ${resolvedClass.label}/${resolvedSubtype.name} (${(semanticResolution.confidence * 100).roundToInt()}%)")
                } else {
                    add("Transport ${ distinctBadges.joinToString("+") } -> ${resolvedClass.label}")
                }
            }.distinct()

            val candidateDriver = ProviderId.fromRawKey(primaryProvider)
                .takeIf { it != ProviderId.UNKNOWN }
                ?.displayName ?: resolvedManufacturer.ifBlank { primary.suggestedType.brandName }
            val priorLifecycleHistory = DeviceLifecycleRecorder.getDeviceHistoryOrdered(candidateGuid)
            val lastRecordedState = priorLifecycleHistory.lastOrNull()?.newState.orEmpty()
            val discoveryLatencyMs = (nowMs - (cluster.minOfOrNull { it.discoveredAtEpochMs } ?: nowMs)).coerceAtLeast(0L)

            if (lastRecordedState in setOf(DeviceLifecycleState.UNBOUND.name, DeviceLifecycleState.UNPAIRED.name)) {
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = candidateGuid,
                    event = MandatoryLifecycleTransition.REDISCOVERED.eventName,
                    previousState = lastRecordedState,
                    newState = DeviceLifecycleState.DISCOVERABLE_AGAIN.name,
                    activeTransport = primaryProvider,
                    vendorDriver = candidateDriver,
                    latencyMs = discoveryLatencyMs,
                    protocolResultCode = distinctBadges.joinToString("+").ifBlank { "REDISCOVERED" },
                    macAddress = mergedMac,
                    hardwareSerial = nonIpSn,
                    cloudDeviceId = nonIpCloudId
                )
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = candidateGuid,
                    event = MandatoryLifecycleTransition.VALIDATED.eventName,
                    previousState = DeviceLifecycleState.DISCOVERABLE_AGAIN.name,
                    newState = DeviceLifecycleState.VALIDATED.name,
                    activeTransport = primaryProvider,
                    vendorDriver = candidateDriver,
                    latencyMs = 0L,
                    protocolResultCode = "STAGE_5_${(combinedConfidence * 100).roundToInt()}PCT",
                    macAddress = mergedMac,
                    hardwareSerial = nonIpSn,
                    cloudDeviceId = nonIpCloudId
                )
            } else if (priorLifecycleHistory.isEmpty()) {
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = candidateGuid,
                    event = MandatoryLifecycleTransition.DISCOVERED.eventName,
                    previousState = "NONE",
                    newState = DeviceLifecycleState.DISCOVERED.name,
                    activeTransport = primaryProvider,
                    vendorDriver = candidateDriver,
                    latencyMs = discoveryLatencyMs,
                    protocolResultCode = distinctBadges.joinToString("+").ifBlank { "SIGNAL_SEEN" },
                    macAddress = mergedMac,
                    hardwareSerial = nonIpSn,
                    cloudDeviceId = nonIpCloudId
                )
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = candidateGuid,
                    event = MandatoryLifecycleTransition.VALIDATED.eventName,
                    previousState = DeviceLifecycleState.DISCOVERED.name,
                    newState = DeviceLifecycleState.VALIDATED.name,
                    activeTransport = primaryProvider,
                    vendorDriver = candidateDriver,
                    latencyMs = 0L,
                    protocolResultCode = "STAGE_5_${(combinedConfidence * 100).roundToInt()}PCT",
                    macAddress = mergedMac,
                    hardwareSerial = nonIpSn,
                    cloudDeviceId = nonIpCloudId
                )
            }

            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.IDENTIFIED,
                transportSource = primaryProvider,
                hardwareId = mergedSn.ifBlank { identityKey },
                macAddress = mergedMac,
                ipAddress = mergedIp,
                port = mergedPort,
                vendor = "$bestTitle ($resolvedManufacturer)",
                confidenceScore = combinedConfidence,
                qualificationStage = QualificationStage.STAGE_4_VALIDATED_DEVICE.name,
                outcomeReason = reasoningChain.joinToString(" • "),
                rawPayload = "signals=${cluster.size} badges=${distinctBadges.joinToString("+")}",
                cloudId = mergedCloudId
            )
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.PRESENTED,
                transportSource = primaryProvider,
                hardwareId = mergedSn.ifBlank { identityKey },
                macAddress = mergedMac,
                ipAddress = mergedIp,
                port = mergedPort,
                vendor = "$bestTitle ($resolvedManufacturer)",
                confidenceScore = combinedConfidence,
                qualificationStage = QualificationStage.STAGE_5_ONBOARDABLE_DEVICE.name,
                outcomeReason = "${resolvedClass.name} -> ${resolvedSubtype.name} • ${requirement.actionButtonLabel}",
                rawPayload = "badges=${distinctBadges.joinToString("+")} req=${requirement::class.simpleName}",
                cloudId = mergedCloudId
            )

            newIdentities.add(
                ResolvedDeviceIdentity(
                    identityKey = identityKey,
                    displayName = bestTitle,
                    suggestedRoom = inferredRoom,
                    deviceClass = resolvedClass,
                    suggestedType = primary.suggestedType,
                    combinedConfidence = combinedConfidence,
                    deviceSubtype = resolvedSubtype,
                    primaryProviderId = primaryProvider,
                    fallbackProviderIds = fallbackProviders,
                    onboardingRequirement = requirement,
                    ipAddress = mergedIp,
                    port = mergedPort,
                    macAddress = mergedMac,
                    hardwareSerialOrNodeId = mergedSn,
                    cloudDeviceId = mergedCloudId,
                    localKeyOrPin = mergedKey,
                    multiGangCount = maxGangs,
                    socket1Name = primary.socket1Name,
                    socket2Name = primary.socket2Name,
                    isActive = primary.isActive,
                    isSocket2Active = primary.isSocket2Active,
                    isOnline = primary.isOnline,
                    temperatureC = primary.temperatureC,
                    targetTempC = primary.targetTempC,
                    humidityPct = primary.humidityPct,
                    hvacMode = primary.hvacMode,
                    hvacStatus = primary.hvacStatus,
                    levelOrVolumePct = primary.levelOrVolumePct,
                    powerWatts = primary.powerWatts,
                    voltageVolts = primary.voltageVolts,
                    currentMilliamps = primary.currentMilliamps,
                    totalEnergyKwh = primary.totalEnergyKwh,
                    hasPowerTelemetry = hasPower,
                    parentNetworkNodeId = primary.parentNetworkNodeId,
                    capabilities = mergedCaps,
                    mergedTransports = sortedByPriority.map { it.transport }.distinct(),
                    protocolBadges = distinctBadges,
                    rawAdvertisedName = mergedRawAdvertisedName,
                    ouiManufacturer = resolvedManufacturer,
                    serviceUuids = mergedServiceUuids,
                    manufacturerDataHex = mergedManufacturerDataHex,
                    rssiDbm = bestRssiDbm,
                    firstSeenEpochMs = updatedSighting.firstSeenEpochMs,
                    lastSeenEpochMs = updatedSighting.lastSeenEpochMs,
                    seenCount = updatedSighting.seenCount,
                    discoveryReasoning = reasoningChain
                )
            )
        }

        if (anyExistingTileHealed) {
            withContext(Dispatchers.Main) {
                DeviceRepository.saveTiles(context, tiles)
            }
        }

        return newIdentities.sortedWith(
            compareByDescending<ResolvedDeviceIdentity> { it.combinedConfidence }
                .thenBy { it.onboardingRequirement.decisionCount }
                .thenBy { it.displayName }
        )
    }

    suspend fun runUnifiedDiscoveryPass(
        context: Context,
        bridgeSettings: BridgeSettings,
        tiles: MutableList<SmartTile>,
        scope: CoroutineScope,
        includeFullLanScan: Boolean = true
    ): List<ResolvedDeviceIdentity> = withContext(Dispatchers.IO) {
        activeDashboardTilesRef = tiles
        isScanningFlow.value = true
        DiscoveryFlightRecorder.startSession(
            if (includeFullLanScan) "unified_lan_scan" else "unified_quick_scan"
        )

        val fingerprints = ConcurrentHashMap.newKeySet<DeviceFingerprint>()

        try {
            coroutineScope {
                // 1. Google Cast Speakers & Video Receivers (mDNS + TLS 8009)
                val castJob = async(Dispatchers.IO) {
                    val casts = runCatching {
                        GoogleCastLanClient.discoverCastDevices(context, includeFullPortSweep = includeFullLanScan)
                    }.onFailure { recordScannerExceptionIfNotCancelled("GOOGLE_CAST_TLS", "discoverCastDevices", it) }
                        .getOrDefault(emptyList())
                    for (c in casts) {
                        if (c.isCastGroup) {
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.FILTERED,
                                transportSource = "GOOGLE_CAST_TLS",
                                hardwareId = c.id,
                                ipAddress = c.ipAddress,
                                port = c.port,
                                vendor = c.name,
                                confidenceScore = 0.50f,
                                qualificationStage = QualificationStage.STAGE_2_POTENTIAL_DEVICE.name,
                                outcomeReason = "Filtered virtual Google Cast speaker group"
                            )
                            continue
                        }
                        val isVideoCast = GoogleCastLanClient.isVideoCastDevice(c)
                        fingerprints.add(
                            DeviceFingerprint(
                                transport = TransportRadioType.GOOGLE_CAST_TLS,
                                explicitProviderId = ProviderId.GOOGLE_CAST,
                                protocolBadge = if (isVideoCast) "Cast Video" else "Cast Audio",
                                confidenceScore = 0.96f,
                                displayName = c.name,
                                suggestedType = TileType.NEST_MINI,
                                deviceClass = DeviceClass.MEDIA,
                                deviceSubtype = if (isVideoCast) DeviceSubtype.TV else DeviceSubtype.SPEAKER,
                                ipAddress = c.ipAddress,
                                port = c.port,
                                hardwareSerialOrNodeId = c.id,
                                levelOrVolumePct = c.volumePercent,
                                isActive = !c.isMuted && c.volumePercent > 0.5f,
                                isOnline = true
                            )
                        )
                    }
                }

                // 2. Matter Operational & Uncommissioned Nodes (_matter._tcp., _matterc._udp., port 5580)
                val matterJob = async(Dispatchers.IO) {
                    val matterNodes = runCatching {
                        MatterProtocolClient.discoverMatterDevices(context, timeoutMs = 1300L)
                    }.onFailure { recordScannerExceptionIfNotCancelled("MATTER_FABRIC", "discoverMatterDevices", it) }
                        .getOrDefault(emptyList())
                    for (m in matterNodes) {
                        val matterScore = when {
                            m.isCommissionedOperational -> 0.96f
                            m.discriminator > 0 -> 0.92f
                            else -> 0.78f
                        }
                        fingerprints.add(
                            DeviceFingerprint(
                                transport = TransportRadioType.MATTER_FABRIC,
                                explicitProviderId = ProviderId.MATTER_FABRIC,
                                protocolBadge = if (m.isCommissionedOperational) "Matter Fabric" else "Matter Setup",
                                confidenceScore = matterScore,
                                displayName = m.displayName,
                                suggestedType = m.suggestedType,
                                deviceClass = m.suggestedType.toSemanticDeviceClass(m.powerWatts > 0f),
                                ipAddress = m.ipAddress,
                                port = m.port,
                                hardwareSerialOrNodeId = m.nodeId,
                                matterDiscriminator = m.discriminator,
                                isActive = m.isActive,
                                levelOrVolumePct = m.levelPct,
                                temperatureC = m.temperatureCelsius,
                                targetTempC = m.targetTempCelsius,
                                powerWatts = m.powerWatts,
                                hasPowerTelemetry = m.powerWatts > 0f,
                                requiresShortCode = !m.isCommissionedOperational
                            )
                        )
                    }
                }

                // 3. Tuya UDP 6666/6667 + Native SDK + Cloud (Never drop unkeyed UDP 6666/6667 into Shelly!)
                val tuyaJob = async(Dispatchers.IO) {
                    val udpList = runCatching {
                        SmartHomeApiClient.listenForTuyaUdpBroadcasts(timeoutMs = 950L)
                    }.onFailure { recordScannerExceptionIfNotCancelled("TUYA_LAN_6668", "listenForTuyaUdpBroadcasts", it) }
                        .getOrDefault(emptyList())
                    for (u in udpList) {
                        val gwId = u.hardwareIdOrMac.trim()
                        if (gwId.isBlank()) {
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.FILTERED,
                                transportSource = "TUYA_LAN_6668",
                                ipAddress = u.ipAddress,
                                port = 6668,
                                vendor = u.suggestedName,
                                qualificationStage = QualificationStage.STAGE_1_DISCOVERED_SERVICE.name,
                                outcomeReason = "Filtered Tuya UDP broadcast missing gwId"
                            )
                            continue
                        }
                        val knownKey = TuyaApiClient.resolveKnownLocalKey(gwId)
                        val hasVerifiedKey = knownKey.length == 16 ||
                                ClaraSecretVault.hasVerifiedLocalControlKey(context, "TUYA", gwId, u.ipAddress, "")
                        fingerprints.add(
                            DeviceFingerprint(
                                transport = TransportRadioType.TUYA_LAN_6668,
                                explicitProviderId = ProviderId.TUYA_LAN,
                                protocolBadge = if (hasVerifiedKey) "LAN 6668" else "Tuya Key Sync",
                                confidenceScore = 0.95f,
                                displayName = u.suggestedName,
                                suggestedType = u.suggestedType,
                                deviceClass = u.suggestedType.toSemanticDeviceClass(),
                                ipAddress = u.ipAddress,
                                port = 6668,
                                hardwareSerialOrNodeId = gwId,
                                cloudDeviceId = gwId,
                                localKeyOrPin = knownKey,
                                requiresProvisioning = false,
                                requiresAccountAuth = !hasVerifiedKey,
                                accountWorkflowKey = if (!hasVerifiedKey) "TUYA" else ""
                            )
                        )
                    }
                    val hasUserLinkedTuyaCloud = bridgeSettings.tuyaUid.trim().isNotBlank() ||
                            !ClaraSecretVault.getTuyaConsumerUid(context).isNullOrBlank()
                    val tuyaRes = runCatching { TuyaApiClient.discoverAllTuyaDevices(bridgeSettings) }
                        .onFailure { recordScannerExceptionIfNotCancelled("TUYA_CLOUD", "discoverAllTuyaDevices", it) }
                        .getOrNull()
                    if (tuyaRes != null && tuyaRes.isSuccess) {
                        for (dev in tuyaRes.getOrThrow()) {
                            val knownLanIp = TuyaApiClient.resolveKnownLanIp(dev.deviceId)
                            val isAlreadyOnDashboard = ConnectedIdentityIndex.isAlreadyOnboarded(
                                providerId = ProviderId.TUYA_CLOUD.id,
                                rawDeviceId = dev.deviceId,
                                serialNumber = dev.deviceId,
                                cloudDeviceId = dev.deviceId
                            ) || tiles.any {
                                it.serialNumber.equals(dev.deviceId, ignoreCase = true) ||
                                        it.entityId.equals(dev.deviceId, ignoreCase = true)
                            }
                            // Only surface Cloud Sync devices in Discovery if they are on the LAN, already on the dashboard
                            // (for state healing), or explicitly from a user-linked Smart Life account and online.
                            if (knownLanIp.isBlank() && !isAlreadyOnDashboard && (!hasUserLinkedTuyaCloud || !dev.isOnline)) {
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.FILTERED,
                                    transportSource = "TUYA_CLOUD",
                                    hardwareId = dev.deviceId,
                                    vendor = dev.name,
                                    confidenceScore = 0.45f,
                                    qualificationStage = QualificationStage.STAGE_2_POTENTIAL_DEVICE.name,
                                    outcomeReason = "Filtered offline or unlinked cloud-only Tuya device without LAN presence",
                                    cloudId = dev.deviceId
                                )
                                continue
                            }
                            fingerprints.add(
                                DeviceFingerprint(
                                    transport = if (knownLanIp.isNotBlank()) TransportRadioType.TUYA_LAN_6668 else TransportRadioType.CLOUD_ACCOUNT_API,
                                    explicitProviderId = if (knownLanIp.isNotBlank()) ProviderId.TUYA_LAN else ProviderId.TUYA_CLOUD,
                                    protocolBadge = if (knownLanIp.isNotBlank()) "LAN 6668" else "Cloud Sync",
                                    confidenceScore = 0.96f,
                                    displayName = dev.name,
                                    suggestedType = dev.suggestedType,
                                    deviceClass = dev.suggestedType.toSemanticDeviceClass(dev.hasPowerTelemetry),
                                    ipAddress = knownLanIp,
                                    port = if (knownLanIp.isNotBlank()) 6668 else 0,
                                    hardwareSerialOrNodeId = dev.deviceId,
                                    cloudDeviceId = dev.deviceId,
                                    localKeyOrPin = dev.localKey,
                                    multiGangCount = dev.multiGangSocketCount.coerceAtLeast(1),
                                    socket1Name = dev.socket1Name,
                                    socket2Name = dev.socket2Name,
                                    isActive = dev.isActive,
                                    isSocket2Active = dev.isSocket2Active,
                                    isOnline = dev.isOnline,
                                    temperatureC = dev.currentTempC,
                                    targetTempC = dev.targetTempC,
                                    levelOrVolumePct = dev.brightnessOrBatteryPct,
                                    powerWatts = dev.powerWatts,
                                    voltageVolts = dev.voltageVolts,
                                    currentMilliamps = dev.currentMilliamps,
                                    totalEnergyKwh = dev.totalEnergyKwh,
                                    hasPowerTelemetry = dev.hasPowerTelemetry,
                                    capabilities = dev.capabilities
                                )
                            )
                        }
                    }
                }

                // 4. Google Nest Thermostats & Doorbells
                val nestJob = async(Dispatchers.IO) {
                    if (NestSdmApiClient.isNestConfiguredAndLinked(bridgeSettings)) {
                        val nestRes = runCatching { NestSdmApiClient.discoverNestDevices(bridgeSettings) }.getOrNull()
                        if (nestRes != null && nestRes.isSuccess) {
                            for (nd in nestRes.getOrThrow()) {
                                fingerprints.add(
                                    DeviceFingerprint(
                                        transport = TransportRadioType.CLOUD_ACCOUNT_API,
                                        explicitProviderId = ProviderId.GOOGLE_NEST,
                                        protocolBadge = "Nest Climate",
                                        confidenceScore = 0.96f,
                                        displayName = nd.displayName,
                                        suggestedType = nd.suggestedType,
                                        deviceClass = nd.suggestedType.toSemanticDeviceClass(),
                                        cloudDeviceId = nd.deviceName,
                                        hardwareSerialOrNodeId = nd.deviceName,
                                        isActive = nd.hvacMode.equals("HEAT", true) || nd.hvacStatus.equals("HEATING", true),
                                        isOnline = nd.isOnline,
                                        temperatureC = nd.ambientTempC,
                                        targetTempC = nd.targetTempC,
                                        humidityPct = nd.humidityPct,
                                        hvacMode = nd.hvacMode,
                                        hvacStatus = nd.hvacStatus
                                    )
                                )
                            }
                        }
                    }
                }

                // 5. TP-Link Tapo (KLAP) & Kasa (Port 9999)
                val tapoKasaJob = async(Dispatchers.IO) {
                    val tkList = runCatching { TpLinkTapoKasaClient.loginAndDiscoverTpLinkDevices(context).getOrNull() }.getOrNull()
                        ?: runCatching { TpLinkTapoKasaClient.discoverLocalKasaAndTapoDevices(context) }.getOrDefault(emptyList())
                    for (tk in tkList) {
                        val hasPower = tk.powerWatts > 0f || tk.totalEnergyKwh > 0f
                        fingerprints.add(
                            DeviceFingerprint(
                                transport = TransportRadioType.KASA_KLAP_LAN,
                                explicitProviderId = ProviderId.TPLINK_KLAP,
                                protocolBadge = if (tk.isKasaProtocol) "LAN 9999" else "KLAP LAN",
                                confidenceScore = 0.95f,
                                displayName = tk.name,
                                suggestedType = tk.suggestedType,
                                deviceClass = tk.suggestedType.toSemanticDeviceClass(hasPower),
                                ipAddress = tk.ipAddress,
                                port = if (tk.isKasaProtocol) 9999 else 80,
                                macAddress = tk.macAddress,
                                hardwareSerialOrNodeId = tk.deviceId,
                                multiGangCount = tk.multiGangCount.coerceAtLeast(1),
                                socket1Name = tk.socket1Name,
                                socket2Name = tk.socket2Name,
                                isActive = tk.isActive,
                                isSocket2Active = tk.isSocket2Active,
                                powerWatts = tk.powerWatts,
                                voltageVolts = tk.voltageVolts,
                                currentMilliamps = tk.currentMilliamps,
                                totalEnergyKwh = tk.totalEnergyKwh,
                                hasPowerTelemetry = hasPower,
                                requiresAccountAuth = tk.requiresAuth,
                                accountWorkflowKey = if (tk.requiresAuth) "TPLINK" else ""
                            )
                        )
                    }
                }

                // 6. Sonoff / eWeLink Local mDNS & Cloud v2
                val sonoffJob = async(Dispatchers.IO) {
                    val snList = runCatching { EWeLinkSonoffClient.loginAndDiscoverEWeLinkCloud(context).getOrNull() }.getOrNull()
                        ?: runCatching { EWeLinkSonoffClient.discoverLocalSonoffMdns(context, timeoutMs = 1100L) }.getOrDefault(emptyList())
                    for (sn in snList) {
                        val hasPower = sn.powerWatts > 0f
                        fingerprints.add(
                            DeviceFingerprint(
                                transport = TransportRadioType.SONOFF_LAN_8081,
                                explicitProviderId = ProviderId.EWELINK_LAN,
                                protocolBadge = "LAN 8081",
                                confidenceScore = 0.95f,
                                displayName = sn.name,
                                suggestedType = sn.suggestedType,
                                deviceClass = sn.suggestedType.toSemanticDeviceClass(hasPower),
                                ipAddress = sn.ipAddress,
                                port = sn.port,
                                hardwareSerialOrNodeId = sn.deviceId,
                                localKeyOrPin = sn.deviceKey,
                                multiGangCount = sn.multiGangCount.coerceAtLeast(1),
                                isActive = sn.isActive,
                                isSocket2Active = sn.isSocket2Active,
                                powerWatts = sn.powerWatts,
                                voltageVolts = sn.voltageVolts,
                                currentMilliamps = sn.currentMilliamps,
                                hasPowerTelemetry = hasPower,
                                requiresAccountAuth = sn.requiresAuth,
                                accountWorkflowKey = if (sn.requiresAuth) "EWELINK" else ""
                            )
                        )
                    }
                }

                // 7. Govee UDP/Cloud & Twinkly XLED
                val rgbJob = async(Dispatchers.IO) {
                    val govee = runCatching { SmartHomeApiClient.discoverGoveeDevices(bridgeSettings, timeoutMs = 1000L) }.getOrDefault(emptyList())
                    for (gv in govee) {
                        fingerprints.add(
                            DeviceFingerprint(
                                transport = TransportRadioType.GOVEE_TWINKLY_UDP,
                                explicitProviderId = if (gv.ipAddress.isNotBlank()) ProviderId.GOVEE_LAN else ProviderId.GOVEE_CLOUD,
                                protocolBadge = "UDP 4003",
                                confidenceScore = 0.95f,
                                displayName = gv.suggestedName,
                                suggestedType = TileType.GOVEE_LIGHT,
                                deviceClass = DeviceClass.LIGHT,
                                ipAddress = gv.ipAddress,
                                port = 4003,
                                hardwareSerialOrNodeId = gv.hardwareIdOrMac,
                                localKeyOrPin = bridgeSettings.goveeApiKey,
                                isActive = true
                            )
                        )
                    }
                    val twinkly = runCatching { SmartHomeApiClient.discoverTwinklyDevices(context, timeoutMs = 1000L) }.getOrDefault(emptyList())
                    for (tw in twinkly) {
                        fingerprints.add(
                            DeviceFingerprint(
                                transport = TransportRadioType.GOVEE_TWINKLY_UDP,
                                explicitProviderId = ProviderId.TWINKLY_LAN,
                                protocolBadge = "XLED REST",
                                confidenceScore = 0.95f,
                                displayName = tw.suggestedName,
                                suggestedType = TileType.TWINKLY_LIGHT,
                                deviceClass = DeviceClass.LIGHT,
                                ipAddress = tw.ipAddress,
                                port = 80,
                                hardwareSerialOrNodeId = tw.hardwareIdOrMac,
                                isActive = true
                            )
                        )
                    }
                }

                // 8. MQTT Auto-Discovery, Tasmota & ESPHome
                val mqttJob = async(Dispatchers.IO) {
                    val esp = runCatching { MqttAndDiyClient.discoverEspHomeMdns(context, timeoutMs = 950L) }.getOrDefault(emptyList())
                    val mqtt = runCatching { MqttAndDiyClient.connectAndDiscoverMqttEntities(context, bridgeSettings, tiles) }.getOrDefault(emptyList())
                    for (mq in (mqtt + esp).distinctBy { it.uniqueId }) {
                        val hasPower = mq.powerWatts > 0f || mq.totalEnergyKwh > 0f
                        fingerprints.add(
                            DeviceFingerprint(
                                transport = TransportRadioType.MQTT_LOCAL,
                                explicitProviderId = ProviderId.MQTT_BROKER,
                                protocolBadge = if (mq.isEspHomeNative) "ESPHome" else if (mq.isTasmotaHttp) "Tasmota HTTP" else "MQTT",
                                confidenceScore = 0.95f,
                                displayName = mq.name,
                                suggestedType = mq.suggestedType,
                                deviceClass = mq.suggestedType.toSemanticDeviceClass(hasPower),
                                ipAddress = mq.ipAddress,
                                hardwareSerialOrNodeId = mq.uniqueId,
                                multiGangCount = mq.multiGangCount.coerceAtLeast(1),
                                socket1Name = mq.socket1Name,
                                socket2Name = mq.socket2Name,
                                isActive = mq.isActive,
                                isSocket2Active = mq.isSocket2Active,
                                powerWatts = mq.powerWatts,
                                voltageVolts = mq.voltageVolts,
                                currentMilliamps = mq.currentMilliamps,
                                totalEnergyKwh = mq.totalEnergyKwh,
                                hasPowerTelemetry = hasPower,
                                parentNetworkNodeId = if (!mq.isEspHomeNative && !mq.isTasmotaHttp) "mqtt_broker" else ""
                            )
                        )
                    }
                }

                // 9. Imou & EZVIZ Cloud Cameras
                val cameraCloudJob = async(Dispatchers.IO) {
                    if (bridgeSettings.imouAppId.isNotBlank() && bridgeSettings.imouAppSecret.isNotBlank()) {
                        val imouRes = runCatching { ImouCloudApiClient.discoverBoundCameras(bridgeSettings) }.getOrNull()
                        if (imouRes != null && imouRes.isSuccess) {
                            for (cam in imouRes.getOrThrow()) {
                                fingerprints.add(
                                    DeviceFingerprint(
                                        transport = TransportRadioType.CLOUD_ACCOUNT_API,
                                        explicitProviderId = ProviderId.IMOU_CLOUD,
                                        protocolBadge = "Camera Stream",
                                        confidenceScore = 0.96f,
                                        displayName = cam.name,
                                        suggestedType = TileType.IMOU_CAMERA,
                                        deviceClass = DeviceClass.CAMERA,
                                        hardwareSerialOrNodeId = cam.deviceId,
                                        cloudDeviceId = cam.deviceId,
                                        localKeyOrPin = ImouCloudApiClient.getSavedDeviceSafetyCode(cam.deviceId),
                                        isOnline = cam.status.equals("online", true)
                                    )
                                )
                            }
                        }
                    }
                    val ezEmail = EzvizCloudApiClient.getSavedEzvizEmail(context)
                    val ezPass = EzvizCloudApiClient.getSavedEzvizPassword(context)
                    if (ezEmail.isNotBlank() && ezPass.isNotBlank()) {
                        val ezRes = runCatching {
                            EzvizCloudApiClient.loginAndDiscoverWithConsumerAccount(context, ezEmail, ezPass)
                        }.getOrNull()
                        if (ezRes != null && ezRes.isSuccess) {
                            for (ez in ezRes.getOrThrow()) {
                                fingerprints.add(
                                    DeviceFingerprint(
                                        transport = TransportRadioType.CLOUD_ACCOUNT_API,
                                        explicitProviderId = ProviderId.EZVIZ_CLOUD,
                                        protocolBadge = "Doorbell Stream",
                                        confidenceScore = 0.96f,
                                        displayName = ez.name,
                                        suggestedType = TileType.EZVIZ_DOORBELL,
                                        deviceClass = DeviceClass.CAMERA,
                                        ipAddress = ez.localIp,
                                        port = ez.localRtspPort,
                                        hardwareSerialOrNodeId = ez.serial,
                                        cloudDeviceId = ez.serial,
                                        localKeyOrPin = ez.verificationCode,
                                        isOnline = ez.isOnline
                                    )
                                )
                            }
                        }
                    }
                }

                // 10. Isolated LAN / Ethernet Scan Only (Never turns on BLE or SoftAP in background/unified passes)
                val bleAndLanJob = async(Dispatchers.IO) {
                    if (includeFullLanScan) {
                        val lanDevices = runCatching {
                            SmartHomeApiClient.discoverLanNetworkPass(context, includeFullPortSweep = true)
                        }.getOrDefault(emptyList())
                        for (lan in lanDevices) {
                            val badgeUpper = lan.protocolBadge.uppercase(Locale.US)
                            val isMatterBle = badgeUpper.contains("MATTER PAIRING")
                            val isLgTv = lan.suggestedType == TileType.LG_TV
                            val isTvPaired = isLgTv && LgThinQWebOsClient.hasVerifiedVaultKey(
                                context = context,
                                ipAddress = lan.ipAddress.substringBefore(":"),
                                macAddress = lan.hardwareIdOrMac
                            )
                            val score = when {
                                lan.port in listOf(3001, 8009, 8123, 9999, 8081, 32400) -> 0.95f
                                lan.port == 80 -> 0.92f
                                lan.port == 554 -> 0.88f
                                else -> 0.75f
                            }
                            val preservedDisplayName = lan.suggestedName.substringBefore(" (").trim().ifBlank { lan.suggestedName }
                            val extractedUuids = buildList {
                                if (badgeUpper.contains("0XFD50") || lan.detail.contains("0xFD50", true)) add("0xFD50")
                                if (badgeUpper.contains("0XFEAF") || lan.detail.contains("0xFEAF", true)) add("0xFEAF")
                                if (badgeUpper.contains("0XFFF6") || lan.detail.contains("0xFFF6", true)) add("0xFFF6")
                                if (badgeUpper.contains("0XEC88") || lan.detail.contains("0xEC88", true)) add("0xEC88")
                            }
                            val mappedTransport = when {
                                isLgTv -> TransportRadioType.LG_WEBOS_SSAP
                                lan.port == 6668 || lan.brandOption in setOf(BrandOption.SMARTKNIGHT, BrandOption.LYTMI) ->
                                    TransportRadioType.TUYA_LAN_6668
                                lan.port == 9999 || lan.brandOption == BrandOption.TAPO ->
                                    TransportRadioType.KASA_KLAP_LAN
                                lan.port == 8081 || lan.brandOption == BrandOption.SONOFF ->
                                    TransportRadioType.SONOFF_LAN_8081
                                lan.port == 8009 || badgeUpper.contains("CAST") ->
                                    TransportRadioType.GOOGLE_CAST_TLS
                                lan.port == 4003 || lan.brandOption in setOf(BrandOption.GOVEE, BrandOption.TWINKLY) ->
                                    TransportRadioType.GOVEE_TWINKLY_UDP
                                lan.suggestedType == TileType.MATTER_DEVICE || badgeUpper.contains("MATTER") ->
                                    TransportRadioType.MATTER_FABRIC
                                lan.suggestedType == TileType.MQTT_DEVICE || lan.brandOption == BrandOption.MQTT_TASMOTA_ESPHOME ->
                                    TransportRadioType.MQTT_LOCAL
                                lan.port == 554 -> TransportRadioType.RTSP_ONVIF_LAN
                                lan.port in listOf(80, 8123) && (lan.suggestedType == TileType.SHELLY_RELAY || lan.port == 8123) ->
                                    TransportRadioType.SHELLY_TASMOTA_HTTP
                                else -> TransportRadioType.RAW_TCP_PROBE
                            }
                            val isTuyaLan6668 = mappedTransport == TransportRadioType.TUYA_LAN_6668
                            val hasTuyaKey = isTuyaLan6668 && (
                                    TuyaApiClient.resolveKnownLocalKey(lan.hardwareIdOrMac).length == 16 ||
                                            ClaraSecretVault.hasVerifiedLocalControlKey(
                                                context,
                                                "TUYA",
                                                lan.hardwareIdOrMac,
                                                lan.ipAddress,
                                                ""
                                            )
                                    )
                            val protocolConfidenceScore = when {
                                mappedTransport == TransportRadioType.RAW_TCP_PROBE -> 0.20f
                                extractedUuids.isNotEmpty() -> 0.93f
                                else -> score
                            }
                            val extractedRssi = Regex("(-\\d{2,3})\\s*dBm", RegexOption.IGNORE_CASE)
                                .find("${lan.detail} ${lan.protocolBadge}")
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()
                            fingerprints.add(
                                DeviceFingerprint(
                                    transport = mappedTransport,
                                    explicitProviderId = if (lan.port == 8123) {
                                        ProviderId.HOME_ASSISTANT
                                    } else {
                                        mappedTransport.toProviderId(lan.suggestedType).takeIf { it != ProviderId.UNKNOWN }
                                    },
                                    protocolBadge = if (isTuyaLan6668 && !hasTuyaKey) "Tuya Key Sync" else lan.protocolBadge,
                                    confidenceScore = protocolConfidenceScore,
                                    displayName = preservedDisplayName,
                                    suggestedType = lan.suggestedType,
                                    deviceClass = if (lan.port == 8123) DeviceClass.NETWORK else lan.suggestedType.toSemanticDeviceClass(),
                                    deviceSubtype = if (badgeUpper.contains("CAST VIDEO")) {
                                        DeviceSubtype.TV
                                    } else {
                                        lan.suggestedType.toDefaultDeviceSubtype(preservedDisplayName)
                                    },
                                    ipAddress = lan.ipAddress.substringBefore(":"),
                                    port = lan.port,
                                    macAddress = if (lan.hardwareIdOrMac.contains(":")) lan.hardwareIdOrMac else "",
                                    hardwareSerialOrNodeId = lan.hardwareIdOrMac,
                                    cloudDeviceId = if (isTuyaLan6668) lan.hardwareIdOrMac else "",
                                    requiresProvisioning = false,
                                    requiresShortCode = isMatterBle,
                                    requiresPhysicalAccept = isLgTv && !isTvPaired,
                                    requiresAccountAuth = lan.actionType == DiscoveredDeviceActionType.OAUTH_SIGN_IN ||
                                            (isTuyaLan6668 && !hasTuyaKey),
                                    accountWorkflowKey = if (isTuyaLan6668 && !hasTuyaKey) {
                                        "TUYA"
                                    } else {
                                        lan.brandOption?.name.orEmpty()
                                    },
                                    rawAdvertisedName = lan.suggestedName.trim(),
                                    serviceUuids = extractedUuids,
                                    rssiDbm = extractedRssi
                                )
                            )
                        }
                    }
                }

                // 11. Home Assistant Bridge Entities
                val haJob = async(Dispatchers.IO) {
                    if (bridgeSettings.haUrl.isNotBlank() && bridgeSettings.haToken.isNotBlank()) {
                        val haRes = runCatching {
                            SmartHomeApiClient.fetchHomeAssistantEntities(bridgeSettings.haUrl, bridgeSettings.haToken)
                        }.getOrNull()
                        if (haRes != null && haRes.isSuccess) {
                            for (entity in haRes.getOrThrow()) {
                                fingerprints.add(
                                    DeviceFingerprint(
                                        transport = TransportRadioType.HOME_ASSISTANT_BRIDGE,
                                        explicitProviderId = ProviderId.HOME_ASSISTANT,
                                        protocolBadge = "Home Bridge",
                                        confidenceScore = 0.95f,
                                        displayName = entity.friendlyName,
                                        suggestedType = entity.suggestedType,
                                        deviceClass = entity.suggestedType.toSemanticDeviceClass(entity.hasPowerTelemetry),
                                        hardwareSerialOrNodeId = entity.entityId,
                                        cloudDeviceId = entity.entityId,
                                        isOnline = !entity.state.equals("unavailable", true) && !entity.state.equals("unknown", true),
                                        isActive = entity.state.equals("on", true) || entity.state.equals("playing", true) || entity.state.equals("cleaning", true),
                                        temperatureC = entity.temperature,
                                        targetTempC = entity.targetTemperature,
                                        levelOrVolumePct = entity.levelOrBrightness,
                                        hasPowerTelemetry = entity.hasPowerTelemetry,
                                        parentNetworkNodeId = "ha_bridge",
                                        capabilities = entity.capabilities
                                    )
                                )
                            }
                        }
                    }
                }

                // 12. Phase 6 Ecosystem Bridges (SmartThings, Hubitat, Homey), Energy (Octopus, Powerwall, GivEnergy, SolarEdge, Tesla WC) & Vision (Frigate NVR, ONVIF)
                val extraBridgesJob = async(Dispatchers.IO) {
                    if (bridgeSettings.smartThingsToken.isNotBlank()) {
                        val stDevices = runCatching { SmartHomeApiClient.fetchSmartThingsDevices(bridgeSettings) }.getOrDefault(emptyList())
                        for (st in stDevices) {
                            fingerprints.add(
                                DeviceFingerprint(
                                    transport = TransportRadioType.CLOUD_ACCOUNT_API,
                                    explicitProviderId = ProviderId.SMARTTHINGS_CLOUD,
                                    protocolBadge = "SmartThings Cloud",
                                    confidenceScore = 0.96f,
                                    displayName = st.title,
                                    suggestedType = st.type,
                                    deviceClass = st.deviceClass,
                                    deviceSubtype = st.deviceSubtype,
                                    hardwareSerialOrNodeId = st.serialNumber,
                                    cloudDeviceId = st.entityId,
                                    isOnline = st.isOnline,
                                    isActive = st.isActive,
                                    hasPowerTelemetry = st.hasExplicitPowerTelemetry,
                                    capabilities = st.capabilities
                                )
                            )
                        }
                    }
                    if (bridgeSettings.hubitatHostUrl.isNotBlank() && bridgeSettings.hubitatAccessToken.isNotBlank()) {
                        val hubitatList = runCatching { SmartHomeApiClient.fetchHubitatDevices(bridgeSettings) }.getOrDefault(emptyList())
                        for (hb in hubitatList) {
                            fingerprints.add(
                                DeviceFingerprint(
                                    transport = TransportRadioType.SHELLY_TASMOTA_HTTP,
                                    explicitProviderId = ProviderId.HUBITAT_LOCAL,
                                    protocolBadge = "Hubitat Maker API",
                                    confidenceScore = 0.96f,
                                    displayName = hb.title,
                                    suggestedType = hb.type,
                                    deviceClass = hb.deviceClass,
                                    deviceSubtype = hb.deviceSubtype,
                                    ipAddress = hb.ipAddress,
                                    port = 80,
                                    hardwareSerialOrNodeId = hb.serialNumber,
                                    cloudDeviceId = hb.entityId,
                                    isOnline = hb.isOnline,
                                    isActive = hb.isActive,
                                    levelOrVolumePct = hb.volume,
                                    temperatureC = hb.temperature,
                                    powerWatts = hb.powerWatts,
                                    hasPowerTelemetry = hb.hasExplicitPowerTelemetry,
                                    parentNetworkNodeId = "hubitat_bridge",
                                    capabilities = hb.capabilities
                                )
                            )
                        }
                    }
                    if (bridgeSettings.homeyHostUrl.isNotBlank() && bridgeSettings.homeyBearerToken.isNotBlank()) {
                        val homeyList = runCatching { SmartHomeApiClient.fetchHomeyDevices(bridgeSettings) }.getOrDefault(emptyList())
                        for (hm in homeyList) {
                            fingerprints.add(
                                DeviceFingerprint(
                                    transport = TransportRadioType.SHELLY_TASMOTA_HTTP,
                                    explicitProviderId = ProviderId.HOMEY_BRIDGE,
                                    protocolBadge = "Homey Bridge",
                                    confidenceScore = 0.96f,
                                    displayName = hm.title,
                                    suggestedType = hm.type,
                                    deviceClass = hm.deviceClass,
                                    deviceSubtype = hm.deviceSubtype,
                                    ipAddress = hm.ipAddress,
                                    port = 80,
                                    hardwareSerialOrNodeId = hm.serialNumber,
                                    cloudDeviceId = hm.entityId,
                                    isOnline = hm.isOnline,
                                    isActive = hm.isActive,
                                    levelOrVolumePct = hm.volume,
                                    temperatureC = hm.temperature,
                                    targetTempC = hm.targetTemperature,
                                    powerWatts = hm.powerWatts,
                                    hasPowerTelemetry = hm.hasExplicitPowerTelemetry,
                                    parentNetworkNodeId = "homey_bridge",
                                    capabilities = hm.capabilities
                                )
                            )
                        }
                    }
                    if (bridgeSettings.octopusTariffCode.isNotBlank() || bridgeSettings.octopusApiKey.isNotBlank()) {
                        runCatching { SmartHomeApiClient.fetchOctopusAgileTariffCapability(bridgeSettings) }
                    }
                    val storageTiles = runCatching { SmartHomeApiClient.discoverEnergyStorageTiles(bridgeSettings) }.getOrDefault(emptyList())
                    for (stTile in storageTiles) {
                        val resolvedStorageProvider = ProviderId.fromRawKey(stTile.activeProviderId)
                            .takeIf { it != ProviderId.UNKNOWN } ?: ProviderId.TESLA_ENERGY
                        fingerprints.add(
                            DeviceFingerprint(
                                transport = if (resolvedStorageProvider.isLocalTransport) {
                                    TransportRadioType.SHELLY_TASMOTA_HTTP
                                } else {
                                    TransportRadioType.CLOUD_ACCOUNT_API
                                },
                                explicitProviderId = resolvedStorageProvider,
                                protocolBadge = stTile.activeProviderId.ifBlank { resolvedStorageProvider.displayName },
                                confidenceScore = 0.97f,
                                displayName = stTile.title,
                                suggestedType = stTile.type,
                                deviceClass = DeviceClass.ENERGY,
                                hardwareSerialOrNodeId = stTile.serialNumber,
                                cloudDeviceId = stTile.entityId,
                                isOnline = true,
                                isActive = stTile.isActive,
                                powerWatts = stTile.powerWatts,
                                hasPowerTelemetry = true,
                                capabilities = stTile.capabilities
                            )
                        )
                    }
                    if (bridgeSettings.teslaWallConnectorIp.isNotBlank()) {
                        val wc = runCatching {
                            SmartHomeApiClient.probeTeslaWallConnectorVitals(bridgeSettings.teslaWallConnectorIp)
                        }.getOrNull()
                        if (wc != null) {
                            fingerprints.add(
                                DeviceFingerprint(
                                    transport = TransportRadioType.SHELLY_TASMOTA_HTTP,
                                    explicitProviderId = ProviderId.TESLA_ENERGY,
                                    protocolBadge = "Tesla Gen3 LAN",
                                    confidenceScore = 0.98f,
                                    displayName = wc.title,
                                    suggestedType = wc.type,
                                    deviceClass = DeviceClass.ENERGY,
                                    deviceSubtype = DeviceSubtype.EV_CHARGER,
                                    ipAddress = wc.ipAddress,
                                    port = 80,
                                    hardwareSerialOrNodeId = wc.serialNumber,
                                    isOnline = true,
                                    isActive = wc.isActive,
                                    powerWatts = wc.powerWatts,
                                    voltageVolts = wc.voltageVolts,
                                    currentMilliamps = wc.currentMilliamps,
                                    totalEnergyKwh = wc.totalEnergyKwh,
                                    hasPowerTelemetry = true
                                )
                            )
                        }
                    }
                    if (bridgeSettings.frigateHostUrl.isNotBlank()) {
                        val frigateCams = runCatching {
                            SmartHomeApiClient.fetchFrigateNvrCamerasAndEvents(bridgeSettings)
                        }.getOrDefault(emptyList())
                        for (fCam in frigateCams) {
                            fingerprints.add(
                                DeviceFingerprint(
                                    transport = TransportRadioType.RTSP_ONVIF_LAN,
                                    explicitProviderId = ProviderId.FRIGATE_NVR,
                                    protocolBadge = "Frigate AI NVR",
                                    confidenceScore = 0.97f,
                                    displayName = fCam.title,
                                    suggestedType = fCam.type,
                                    deviceClass = DeviceClass.CAMERA,
                                    deviceSubtype = DeviceSubtype.CAMERA,
                                    ipAddress = fCam.ipAddress,
                                    port = 8554,
                                    hardwareSerialOrNodeId = fCam.serialNumber,
                                    cloudDeviceId = fCam.entityId,
                                    isOnline = true
                                )
                            )
                        }
                        runCatching { SmartHomeApiClient.pollFrigateObjectDetectionEvents(bridgeSettings, tiles) }
                    }
                    if (includeFullLanScan) {
                        val onvifCams = runCatching {
                            SmartHomeApiClient.discoverOnvifCamerasViaWsDiscovery(timeoutMs = 950L)
                        }.getOrDefault(emptyList())
                        for (cam in onvifCams) {
                            fingerprints.add(
                                DeviceFingerprint(
                                    transport = TransportRadioType.RTSP_ONVIF_LAN,
                                    explicitProviderId = ProviderId.RTSP_ONVIF_LAN,
                                    protocolBadge = cam.protocolBadge,
                                    confidenceScore = 0.94f,
                                    displayName = cam.suggestedName,
                                    suggestedType = cam.suggestedType,
                                    deviceClass = DeviceClass.CAMERA,
                                    ipAddress = cam.ipAddress.substringBefore(":"),
                                    port = 554,
                                    isOnline = true
                                )
                            )
                        }
                    }
                    runCatching { SmartHomeApiClient.pollUnifiAndBleSpatialPresence(context, bridgeSettings) }
                }

                listOf(
                    castJob,
                    matterJob,
                    tuyaJob,
                    nestJob,
                    tapoKasaJob,
                    sonoffJob,
                    rgbJob,
                    mqttJob,
                    cameraCloudJob,
                    bleAndLanJob,
                    haJob,
                    extraBridgesJob
                ).awaitAll()
            }

            val rawList = fingerprints.toList()
            val resolved = resolveAndHealExistingTiles(
                context = context,
                rawFingerprints = rawList,
                tiles = tiles,
                bridgeSettings = bridgeSettings,
                scope = scope
            )
            resolvedIdentitiesFlow.value = resolved
            JarvisLiveBridge.recordDiscoveryScanOutcome(
                rawSignalCount = rawList.size,
                resolvedIdentityCount = resolved.size + tiles.count { it.isConfiguredAndLinked }
            )

            resolved
        } finally {
            isScanningFlow.value = false
        }
    }

    private fun emitUnifiedStageRecord(
        deviceId: String,
        stage: UnifiedOnboardingStage,
        activeTransport: String,
        vendorDriver: String,
        pairingSessionId: String,
        durationMs: Long = 0L,
        protocolResultCode: String = stage.name,
        macAddress: String = "",
        rssiDbm: Int = -127
    ) {
        val previous = activeUnifiedStageFlow.value.name
        publishSessionStage(stage = stage, sessionId = pairingSessionId)
        DeviceLifecycleRecorder.recordTransition(
            deviceId = deviceId,
            event = stage.stepTitle,
            previousState = previous,
            newState = stage.name,
            activeTransport = activeTransport,
            vendorDriver = vendorDriver,
            latencyMs = durationMs,
            durationMs = durationMs,
            protocolResultCode = protocolResultCode,
            pairingSessionId = pairingSessionId,
            macAddress = macAddress,
            rssiDbm = rssiDbm
        )
    }

    suspend fun onboardResolvedIdentity(
        context: Context,
        identity: ResolvedDeviceIdentity,
        tiles: MutableList<SmartTile>,
        bridgeSettings: BridgeSettings,
        scope: CoroutineScope,
        customRoomOverride: String = "",
        shortCodeInput: String = "",
        wifiSsid: String = "",
        wifiPassword: String = "",
        splitMultiGangIntoTwoTiles: Boolean = false,
        explicitPairingModeOverride: TuyaPairingMode? = null,
        onOpenAccountModal: ((String) -> Unit)? = null,
        onStatusUpdate: (String) -> Unit = {}
    ): Result<SmartTile> = withContext(Dispatchers.IO) {
        // Section 3: Enforce single-session pairing mutex so Clara never onboards multiple devices concurrently
        pairingSessionMutex.withLock {
            activeDashboardTilesRef = tiles
            val sessionId = activePairingSessionIdFlow.value.ifBlank {
                beginSingleDevicePairingSession(OnboardingEntryMode.ALREADY_ON_NETWORK)
            }
            DiscoveryFlightRecorder.attachActivePairingSession(sessionId)

            val targetRoom = customRoomOverride.trim()
                .ifBlank { identity.suggestedRoom }
                .ifBlank { inferRoomFromDeviceTitle(identity.displayName, bridgeSettings.customRooms) }
            val candidateGuid = CrossEcosystemIdentityCorrelator.resolveCanonicalGuid(
                rawDeviceId = identity.hardwareSerialOrNodeId,
                macAddress = identity.macAddress,
                hardwareSerial = identity.hardwareSerialOrNodeId,
                cloudDeviceId = identity.cloudDeviceId,
                activeProviderId = identity.primaryProviderId,
                fallbackIdentityKey = identity.identityKey
            )
            val activeTransport = ProviderId.fromRawKey(identity.primaryProviderId)
                .takeIf { it != ProviderId.UNKNOWN }?.id ?: identity.primaryProviderId.ifBlank { "LOCAL_LAN" }
            val vendorDriver = ProviderId.fromRawKey(identity.primaryProviderId)
                .takeIf { it != ProviderId.UNKNOWN }?.displayName
                ?: identity.ouiManufacturer.ifBlank { identity.suggestedType.brandName }
            val observedRssi = identity.rssiDbm ?: -127

            // Section 2: Double-check LG webOS vault key before allowing ZeroTouchReady execution
            val effectiveRequirement = if (
                identity.suggestedType == TileType.LG_TV &&
                !LgThinQWebOsClient.hasVerifiedVaultKey(
                    context = context,
                    ipAddress = identity.ipAddress,
                    macAddress = identity.macAddress,
                    deviceUuid = identity.hardwareSerialOrNodeId
                )
            ) {
                OnboardingRequirement.PhysicalAcceptRequired(
                    promptMessage = "Press 'Accept' on your LG TV screen using your TV remote"
                )
            } else {
                identity.onboardingRequirement
            }

            when (val req = effectiveRequirement) {
                is OnboardingRequirement.InAppAccountWorkflowRequired -> {
                    DeviceLifecycleRecorder.recordTransition(
                        deviceId = candidateGuid,
                        event = MandatoryLifecycleTransition.PAIRING_STARTED.eventName,
                        previousState = DeviceLifecycleState.VALIDATED.name,
                        newState = DeviceLifecycleState.CONNECTABLE.name,
                        activeTransport = activeTransport,
                        vendorDriver = vendorDriver,
                        protocolResultCode = "ACCOUNT_WORKFLOW_${req.workflowKey}",
                        macAddress = identity.macAddress,
                        hardwareSerial = identity.hardwareSerialOrNodeId,
                        cloudDeviceId = identity.cloudDeviceId,
                        pairingSessionId = sessionId,
                        rssiDbm = observedRssi
                    )
                    withContext(Dispatchers.Main) {
                        onOpenAccountModal?.invoke(req.workflowKey)
                    }
                    return@withLock Result.failure(Exception("IN_APP_ACCOUNT_WORKFLOW:${req.workflowKey}"))
                }

                is OnboardingRequirement.ShortCodeRequired -> {
                    val cleanCode = shortCodeInput.trim()
                    if (cleanCode.isBlank()) {
                        return@withLock Result.failure(Exception("SHORT_CODE_REQUIRED"))
                    }
                    return@withLock onboardByUniversalShortCode(
                        context = context,
                        rawCode = cleanCode,
                        customTitle = identity.displayName,
                        targetRoom = targetRoom,
                        tiles = tiles,
                        bridgeSettings = bridgeSettings,
                        canonicalGuidHint = candidateGuid,
                        onStatusUpdate = onStatusUpdate
                    )
                }

                is OnboardingRequirement.PhysicalAcceptRequired -> {
                    val cleanIp = identity.ipAddress.substringBefore(":").trim()
                    val key = identity.identityKey
                    JarvisLiveBridge.isPairingTuyaFlow.value = true
                    emitUnifiedStageRecord(
                        deviceId = candidateGuid,
                        stage = UnifiedOnboardingStage.AUTHENTICATING,
                        activeTransport = ProviderId.LG_WEBOS.id,
                        vendorDriver = ProviderId.LG_WEBOS.displayName,
                        pairingSessionId = sessionId,
                        protocolResultCode = "SSAP_PROMPT_WAIT",
                        macAddress = identity.macAddress
                    )
                    JarvisLiveBridge.updatePairingTimeline(
                        stage = PairingTimelineStage.DEVICE_FOUND,
                        customDetail = "Look at your ${identity.displayName} and press 'Accept' using your TV remote...",
                        targetKey = key,
                        customTitle = "Accept Prompt on TV"
                    )
                    onStatusUpdate("Please press 'Accept' on your ${identity.displayName} screen...")

                    val pairStartMs = SystemClock.elapsedRealtime()
                    val pairRes = LgThinQWebOsClient.pairAndFetchTvDetails(context, cleanIp)
                    val pairDurationMs = (SystemClock.elapsedRealtime() - pairStartMs).coerceAtLeast(1L)

                    if (pairRes.isFailure) {
                        val err = pairRes.exceptionOrNull()
                            ?: Exception("Please press 'Accept' on your LG TV screen to allow Clara to connect.")
                        activeUnifiedStageFlow.value = UnifiedOnboardingStage.FAILED
                        JarvisLiveBridge.isPairingTuyaFlow.value = false
                        // Section 2: Keep TV retryable (never lock into QUARANTINED_ERROR) and show failure-specific title
                        JarvisLiveBridge.updatePairingTimeline(
                            stage = PairingTimelineStage.FAILED,
                            customDetail = err.message ?: "TV prompt was not accepted in time.",
                            targetKey = key,
                            customTitle = "Confirmation Needed on TV"
                        )
                        JarvisLiveBridge.recordOnboardingOutcome(succeeded = false, userDecisionCount = 1)
                        return@withLock Result.failure(err)
                    }

                    val (learnedMac, syncedShortcuts) = pairRes.getOrThrow()
                    val mac = learnedMac.ifBlank { identity.macAddress }
                    emitUnifiedStageRecord(
                        deviceId = candidateGuid,
                        stage = UnifiedOnboardingStage.ACTIVATING,
                        activeTransport = ProviderId.LG_WEBOS.id,
                        vendorDriver = ProviderId.LG_WEBOS.displayName,
                        pairingSessionId = sessionId,
                        durationMs = pairDurationMs,
                        protocolResultCode = "WEBOS_SESSION_ACTIVE",
                        macAddress = mac
                    )
                    JarvisLiveBridge.updatePairingTimeline(
                        stage = PairingTimelineStage.CONNECTING_WIFI,
                        customDetail = "TV session activated on local network ($cleanIp)",
                        targetKey = key,
                        customTitle = "Activating TV Session"
                    )
                    emitUnifiedStageRecord(
                        deviceId = candidateGuid,
                        stage = UnifiedOnboardingStage.BINDING_KEYS,
                        activeTransport = ProviderId.LG_WEBOS.id,
                        vendorDriver = ProviderId.LG_WEBOS.displayName,
                        pairingSessionId = sessionId,
                        protocolResultCode = "SSAP_CLIENT_KEY_BOUND",
                        macAddress = mac
                    )
                    JarvisLiveBridge.updatePairingTimeline(
                        stage = PairingTimelineStage.REGISTERING_CLOUD,
                        customDetail = "Saved encrypted TV pairing key & MAC address",
                        targetKey = key,
                        customTitle = "Binding Security Keys"
                    )
                    emitUnifiedStageRecord(
                        deviceId = candidateGuid,
                        stage = UnifiedOnboardingStage.DISCOVERING_CAPABILITIES,
                        activeTransport = ProviderId.LG_WEBOS.id,
                        vendorDriver = ProviderId.LG_WEBOS.displayName,
                        pairingSessionId = sessionId,
                        protocolResultCode = "APPS_${syncedShortcuts.size}",
                        macAddress = mac
                    )
                    JarvisLiveBridge.updatePairingTimeline(
                        stage = PairingTimelineStage.INITIALIZING_TILE,
                        customDetail = "Synced ${syncedShortcuts.size} TV inputs & apps",
                        targetKey = key,
                        customTitle = "Discovering Capabilities"
                    )

                    val tvCaps = listOf(
                        DeviceCapabilityDescriptor(
                            key = "power",
                            label = "TV Power",
                            kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                            isWritable = true,
                            isPinnedToHero = true,
                            currentValue = "true",
                            provenance = CapabilityProvenance.VERIFIED_HARDWARE_SCHEMA
                        ),
                        DeviceCapabilityDescriptor(
                            key = "volume",
                            label = "TV Volume",
                            kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                            isWritable = true,
                            isPinnedToHero = true,
                            currentValue = (LgThinQWebOsClient.lastKnownTvVolumeByIp[cleanIp] ?: 25).toString(),
                            minValue = 0f,
                            maxValue = 100f,
                            stepValue = 1f,
                            unit = "%",
                            rendererType = CapabilityRendererType.VOLUME_SLIDER,
                            provenance = CapabilityProvenance.VERIFIED_HARDWARE_SCHEMA
                        )
                    )
                    val tile = withContext(Dispatchers.Main) {
                        buildAndCommitOnboardedTile(
                            context = context,
                            identity = identity.copy(
                                macAddress = mac,
                                capabilities = identity.capabilities.ifEmpty { tvCaps }
                            ),
                            targetRoom = targetRoom.ifBlank { "Living Room" },
                            tiles = tiles,
                            canonicalGuidOverride = candidateGuid
                        )
                    }
                    emitUnifiedStageRecord(
                        deviceId = candidateGuid,
                        stage = UnifiedOnboardingStage.INITIAL_SYNC,
                        activeTransport = ProviderId.LG_WEBOS.id,
                        vendorDriver = ProviderId.LG_WEBOS.displayName,
                        pairingSessionId = sessionId,
                        protocolResultCode = "TV_SYNC_OK",
                        macAddress = mac
                    )
                    activeUnifiedStageFlow.value = UnifiedOnboardingStage.COMPLETED
                    JarvisLiveBridge.isPairingTuyaFlow.value = false
                    JarvisLiveBridge.updatePairingTimeline(
                        stage = PairingTimelineStage.COMPLETED,
                        customDetail = "Connected '${tile.title}'!",
                        targetKey = key
                    )
                    JarvisLiveBridge.recordOnboardingOutcome(succeeded = true, userDecisionCount = 1)
                    return@withLock Result.success(tile)
                }

                is OnboardingRequirement.AutoProvisioningRequired -> {
                    val resolvedSsid = wifiSsid.trim().ifBlank {
                        TuyaDirectPairingManager.getSavedWifiSsid(context).ifBlank {
                            TuyaDirectPairingManager.detectCurrentWifiSsid(context)
                        }
                    }
                    val resolvedPass = wifiPassword.ifBlank {
                        TuyaDirectPairingManager.getSavedWifiPassword(context, resolvedSsid)
                    }
                    if (resolvedSsid.isBlank()) {
                        return@withLock Result.failure(Exception("WIFI_CREDENTIALS_REQUIRED"))
                    }
                    DiagnosticsSanitizer.registerTransientWifiSecretForRedaction(resolvedPass)

                    val cleanBeaconName = req.beaconName.ifBlank { identity.displayName }.trim()
                    val rawTargetId = req.macOrSsid.ifBlank { identity.macAddress }
                        .ifBlank { identity.hardwareSerialOrNodeId }
                        .ifBlank { cleanBeaconName }
                        .trim()
                    val isSoftApDevice = explicitPairingModeOverride == TuyaPairingMode.AP_HOTSPOT ||
                            identity.mergedTransports.contains(TransportRadioType.WIFI_SOFTAP_BEACON) ||
                            identity.protocolBadges.any { it.contains("AP", ignoreCase = true) || it.contains("Hotspot", ignoreCase = true) } ||
                            TuyaDirectPairingManager.isPlugHotspotSsid(cleanBeaconName.substringBefore(" (")) ||
                            TuyaDirectPairingManager.isPlugHotspotSsid(rawTargetId.substringBefore(" ("))
                    val isBleDevice = !isSoftApDevice && explicitPairingModeOverride != TuyaPairingMode.PURE_EZ_BROADCAST && (
                            explicitPairingModeOverride == TuyaPairingMode.BLE_DIRECT ||
                                    identity.mergedTransports.contains(TransportRadioType.BLE_SETUP_BEACON) ||
                                    identity.protocolBadges.any { it.contains("BLE", ignoreCase = true) || it.contains("Bluetooth", ignoreCase = true) }
                            )
                    val selectedMode = explicitPairingModeOverride ?: when {
                        isSoftApDevice -> TuyaPairingMode.AP_HOTSPOT
                        isBleDevice -> TuyaPairingMode.BLE_DIRECT
                        else -> TuyaPairingMode.PURE_EZ_BROADCAST
                    }

                    // Section 5: Pre-fetch cloud pairing token while online BEFORE joining offline SoftAP hotspot
                    if (selectedMode == TuyaPairingMode.AP_HOTSPOT && !TuyaDirectPairingManager.isConnectedToDeviceHotspot(context)) {
                        onStatusUpdate("Pre-fetching secure pairing token while online...")
                        TuyaDirectPairingManager.prefetchCloudPairingTokenSuspend(context)
                    }

                    val targetHardware = if (isSoftApDevice) {
                        rawTargetId.takeIf { DiscoveryRadioCoordinator.isDeviceSetupHotspotSsid(it) }
                            ?: cleanBeaconName.substringBefore(" (").trim().ifBlank { rawTargetId }
                    } else {
                        identity.macAddress.ifBlank { req.macOrSsid }.ifBlank { cleanBeaconName }
                    }
                    val statusMsg = when (selectedMode) {
                        TuyaPairingMode.AP_HOTSPOT ->
                            "Connecting to '$targetHardware' hotspot to deliver '$resolvedSsid'..."
                        TuyaPairingMode.BLE_DIRECT ->
                            "Sending '$resolvedSsid' directly over Bluetooth to '${identity.displayName}'..."
                        TuyaPairingMode.PURE_EZ_BROADCAST ->
                            "Broadcasting '$resolvedSsid' on 2.4 GHz Wi-Fi (blink one device only)..."
                        TuyaPairingMode.AUTO_COMBO_EZ ->
                            "Sending '$resolvedSsid' credentials to '${identity.displayName}'..."
                    }
                    publishSessionStage(
                        stage = UnifiedOnboardingStage.AUTHENTICATING,
                        sessionId = sessionId,
                        statusDetail = statusMsg
                    )
                    onStatusUpdate(statusMsg)

                    val provisioningOutcome = try {
                        withTimeout(90_000L) {
                            suspendCancellableCoroutine<Result<SmartTile>> { cont ->
                                val completed = AtomicBoolean(false)
                                cont.invokeOnCancellation {
                                    if (completed.compareAndSet(false, true)) {
                                        TuyaDirectPairingManager.stopDirectPairing()
                                    }
                                }
                                engineScope.launch(Dispatchers.Main) {
                                    TuyaDirectPairingManager.startDirectPairing(
                                        context = context,
                                        ssid = resolvedSsid,
                                        password = resolvedPass,
                                        mode = selectedMode,
                                        scope = scope,
                                        isSilentBackground = false,
                                        targetKey = candidateGuid,
                                        targetHardware = targetHardware,
                                        targetBeaconName = cleanBeaconName,
                                        onError = { errMsg ->
                                            if (completed.compareAndSet(false, true) && cont.isActive) {
                                                publishSessionStage(
                                                    stage = UnifiedOnboardingStage.FAILED,
                                                    sessionId = sessionId,
                                                    stageTitle = JarvisLiveBridge.inferFailureHeaderTitle(errMsg),
                                                    statusDetail = errMsg,
                                                    failureTitle = JarvisLiveBridge.inferFailureHeaderTitle(errMsg),
                                                    failureRemediation = errMsg
                                                )
                                                JarvisLiveBridge.recordOnboardingOutcome(succeeded = false, userDecisionCount = 1)
                                                onStatusUpdate(errMsg)
                                                cont.resume(Result.failure(Exception(errMsg)))
                                            }
                                        },
                                        onDevicePaired = { packet ->
                                            if (!completed.compareAndSet(false, true)) return@startDirectPairing
                                            emitUnifiedStageRecord(
                                                deviceId = candidateGuid,
                                                stage = UnifiedOnboardingStage.BINDING_KEYS,
                                                activeTransport = if (packet.ipAddress.isNotBlank()) ProviderId.TUYA_LAN.id else ProviderId.TUYA_CLOUD.id,
                                                vendorDriver = "Tuya Provisioner",
                                                pairingSessionId = sessionId,
                                                protocolResultCode = "KEY_SHA256_${ClaraSecretVault.fingerprintSecret(packet.localKey)}",
                                                macAddress = identity.macAddress,
                                                rssiDbm = observedRssi
                                            )
                                            ClaraDeviceRegistryStore.getInstance(context).persistBoundDeviceImmediately(
                                                deviceId = packet.gwId,
                                                ipAddress = packet.ipAddress,
                                                port = if (packet.ipAddress.isNotBlank()) 6668 else 0,
                                                macAddress = identity.macAddress,
                                                localOrClientKey = packet.localKey,
                                                providerId = if (packet.ipAddress.isNotBlank()) ProviderId.TUYA_LAN else ProviderId.TUYA_CLOUD,
                                                suggestedTitle = packet.name.ifBlank { identity.displayName },
                                                suggestedRoom = targetRoom,
                                                suggestedTileType = packet.suggestedType,
                                                protocolVersion = TuyaApiClient.resolveProtocolVersion(packet.gwId)
                                            )

                                            emitUnifiedStageRecord(
                                                deviceId = candidateGuid,
                                                stage = UnifiedOnboardingStage.DISCOVERING_CAPABILITIES,
                                                activeTransport = if (packet.ipAddress.isNotBlank()) ProviderId.TUYA_LAN.id else ProviderId.TUYA_CLOUD.id,
                                                vendorDriver = "Tuya Provisioner",
                                                pairingSessionId = sessionId,
                                                macAddress = identity.macAddress
                                            )
                                            val acquiredCaps = TuyaApiClient.capabilitiesByDevice[packet.gwId].orEmpty()
                                            val hasPower = TuyaApiClient.hasPowerTelemetryByDevice[packet.gwId] ?: false
                                            val resolvedTitle = packet.name.ifBlank { identity.displayName }
                                            val semanticRes = if (acquiredCaps.isNotEmpty()) {
                                                SemanticClassifier.resolve(
                                                    capabilities = acquiredCaps,
                                                    categoryCode = "",
                                                    multiGangSocketCount = packet.multiGangSocketCount.coerceAtLeast(1),
                                                    hasPowerTelemetry = hasPower,
                                                    titleHint = resolvedTitle
                                                )
                                            } else null
                                            val pairedIdentity = identity.copy(
                                                displayName = resolvedTitle,
                                                suggestedType = semanticRes?.suggestedTileType ?: packet.suggestedType,
                                                deviceClass = semanticRes?.deviceClass ?: packet.suggestedType.toSemanticDeviceClass(hasPower),
                                                deviceSubtype = semanticRes?.deviceSubtype ?: packet.suggestedType.toDefaultDeviceSubtype(resolvedTitle),
                                                ipAddress = packet.ipAddress,
                                                hardwareSerialOrNodeId = packet.gwId,
                                                cloudDeviceId = packet.gwId,
                                                localKeyOrPin = packet.localKey,
                                                multiGangCount = packet.multiGangSocketCount,
                                                capabilities = acquiredCaps,
                                                hasPowerTelemetry = hasPower
                                            )
                                            val committedTile = buildAndCommitOnboardedTile(
                                                context = context,
                                                identity = pairedIdentity,
                                                targetRoom = targetRoom,
                                                tiles = tiles,
                                                splitMultiGangIntoTwoTiles = splitMultiGangIntoTwoTiles,
                                                canonicalGuidOverride = candidateGuid
                                            )
                                            if (packet.ipAddress.isBlank() && packet.gwId.isNotBlank()) {
                                                TuyaDirectPairingManager.startPostOnboardingDiscoveryAcceleration(
                                                    context = context,
                                                    expectedDeviceId = packet.gwId
                                                )
                                            }
                                            emitUnifiedStageRecord(
                                                deviceId = candidateGuid,
                                                stage = UnifiedOnboardingStage.INITIAL_SYNC,
                                                activeTransport = if (packet.ipAddress.isNotBlank()) ProviderId.TUYA_LAN.id else ProviderId.TUYA_CLOUD.id,
                                                vendorDriver = "Tuya Provisioner",
                                                pairingSessionId = sessionId,
                                                protocolResultCode = if (acquiredCaps.isNotEmpty()) "SYNC_OK" else "BOUND_NEEDS_CAP_SYNC",
                                                macAddress = identity.macAddress
                                            )
                                            publishSessionStage(
                                                stage = UnifiedOnboardingStage.COMPLETED,
                                                sessionId = sessionId,
                                                statusDetail = "Connected '${pairedIdentity.displayName}'!"
                                            )
                                            JarvisLiveBridge.recordOnboardingOutcome(succeeded = true, userDecisionCount = 1)
                                            onStatusUpdate("Connected '${pairedIdentity.displayName}'!")
                                            if (cont.isActive) {
                                                cont.resume(Result.success(committedTile))
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    } catch (_: TimeoutCancellationException) {
                        TuyaDirectPairingManager.stopDirectPairing()
                        val timeoutMsg = "Provisioning timed out after 90 seconds for '${identity.displayName}'. Check your 2.4 GHz Wi-Fi password and move closer to the device."
                        publishSessionStage(
                            stage = UnifiedOnboardingStage.FAILED,
                            sessionId = sessionId,
                            stageTitle = "Device Did Not Respond",
                            statusDetail = timeoutMsg,
                            failureTitle = "Device Did Not Respond",
                            failureRemediation = timeoutMsg
                        )
                        JarvisLiveBridge.updatePairingTimeline(
                            stage = PairingTimelineStage.FAILED,
                            customDetail = timeoutMsg,
                            targetKey = candidateGuid
                        )
                        JarvisLiveBridge.recordOnboardingOutcome(succeeded = false, userDecisionCount = 1)
                        onStatusUpdate(timeoutMsg)
                        Result.failure(Exception(timeoutMsg))
                    }
                    return@withLock provisioningOutcome
                }

                is OnboardingRequirement.ZeroTouchReady -> {
                    val key = identity.identityKey
                    val devId = identity.hardwareSerialOrNodeId.ifBlank { identity.cloudDeviceId }
                    JarvisLiveBridge.isPairingTuyaFlow.value = true

                    // Unified Stage 1: Authenticate
                    emitUnifiedStageRecord(
                        deviceId = candidateGuid,
                        stage = UnifiedOnboardingStage.AUTHENTICATING,
                        activeTransport = activeTransport,
                        vendorDriver = vendorDriver,
                        pairingSessionId = sessionId,
                        protocolResultCode = "AUTH_HANDSHAKE",
                        macAddress = identity.macAddress,
                        rssiDbm = observedRssi
                    )
                    JarvisLiveBridge.updatePairingTimeline(
                        stage = PairingTimelineStage.DEVICE_FOUND,
                        customDetail = "Authenticating with ${identity.displayName}...",
                        targetKey = key,
                        customTitle = "Authenticating"
                    )

                    // Unified Stage 2: Activate & Verify Control Transport
                    emitUnifiedStageRecord(
                        deviceId = candidateGuid,
                        stage = UnifiedOnboardingStage.ACTIVATING,
                        activeTransport = activeTransport,
                        vendorDriver = vendorDriver,
                        pairingSessionId = sessionId,
                        protocolResultCode = "VERIFYING_CONTROL",
                        macAddress = identity.macAddress
                    )
                    JarvisLiveBridge.updatePairingTimeline(
                        stage = PairingTimelineStage.CONNECTING_WIFI,
                        customDetail = "Verifying live session on ${identity.ipAddress.ifBlank { "network" }}...",
                        targetKey = key,
                        customTitle = "Activating Connection"
                    )

                    val probeCandidate = SmartTile(
                        title = identity.displayName,
                        type = identity.suggestedType,
                        ipAddress = identity.ipAddress,
                        macAddress = identity.macAddress,
                        serialNumber = identity.hardwareSerialOrNodeId.ifBlank { identity.macAddress },
                        entityId = identity.cloudDeviceId.ifBlank { identity.hardwareSerialOrNodeId },
                        secretCode = identity.localKeyOrPin,
                        activeProviderId = identity.primaryProviderId,
                        lifecycleState = DeviceLifecycleState.CAPABILITY_LEARNING,
                        canonicalGuid = candidateGuid
                    )
                    val verifyStartMs = SystemClock.elapsedRealtime()
                    val resolvedPrimaryProvider = ProviderId.fromRawKey(identity.primaryProviderId)
                    val isCloudPrimary = identity.primaryProviderId == TransportRadioType.CLOUD_ACCOUNT_API.name ||
                            (resolvedPrimaryProvider != ProviderId.UNKNOWN && !resolvedPrimaryProvider.isLocalTransport) ||
                            (identity.ipAddress.isBlank() && identity.mergedTransports.contains(TransportRadioType.CLOUD_ACCOUNT_API))
                    val isCloudPreVerified = isCloudPrimary && identity.isOnline
                    var controlVerified = isCloudPreVerified || verifyCandidateReachability(
                        context = context,
                        bridgeSettings = bridgeSettings,
                        candidateTile = probeCandidate
                    )
                    var resolvedIdentityForCommit = identity

                    // Section 2: If an LG TV had a stale/rotated TLS pin or rejected key, auto-clear pin and re-prompt on TV!
                    if (!controlVerified && identity.suggestedType == TileType.LG_TV) {
                        val tvIp = identity.ipAddress.substringBefore(":").trim()
                        JarvisLiveBridge.updatePairingTimeline(
                            stage = PairingTimelineStage.DEVICE_FOUND,
                            customDetail = "TV security key changed — press 'Accept' on your LG TV screen...",
                            targetKey = key,
                            customTitle = "Accept Prompt on TV"
                        )
                        val rePairRes = LgThinQWebOsClient.pairAndFetchTvDetails(
                            context = context,
                            ipAddress = tvIp,
                            forceClearStaleTlsPin = true
                        )
                        if (rePairRes.isSuccess) {
                            controlVerified = true
                            val newMac = rePairRes.getOrThrow().first.ifBlank { identity.macAddress }
                            resolvedIdentityForCommit = identity.copy(macAddress = newMac)
                        }
                    }

                    val verifyLatencyMs = (SystemClock.elapsedRealtime() - verifyStartMs).coerceAtLeast(1L)
                    if (!controlVerified) {
                        activeUnifiedStageFlow.value = UnifiedOnboardingStage.FAILED
                        DeviceLifecycleRecorder.recordTransition(
                            deviceId = candidateGuid,
                            event = MandatoryLifecycleTransition.AUTH_FAILED.eventName,
                            previousState = DeviceLifecycleState.AUTHENTICATED.name,
                            newState = DeviceLifecycleState.CONNECTABLE.name,
                            activeTransport = activeTransport,
                            vendorDriver = vendorDriver,
                            latencyMs = verifyLatencyMs,
                            durationMs = verifyLatencyMs,
                            protocolResultCode = "PREFLIGHT_CONTROL_FAILED",
                            failureCategory = FailureCategory.ACTIVATION_FAILURE.name,
                            macAddress = identity.macAddress,
                            hardwareSerial = identity.hardwareSerialOrNodeId,
                            cloudDeviceId = identity.cloudDeviceId,
                            pairingSessionId = sessionId
                        )
                        JarvisLiveBridge.isPairingTuyaFlow.value = false
                        val failMsg = if (identity.suggestedType == TileType.LG_TV) {
                            "Please switch on '${identity.displayName}' and press 'Accept' on the TV screen when prompted."
                        } else {
                            "Could not verify live control of '${identity.displayName}'. Check that it is powered on and connected to your home network."
                        }
                        JarvisLiveBridge.updatePairingTimeline(
                            stage = PairingTimelineStage.FAILED,
                            customDetail = failMsg,
                            targetKey = key
                        )
                        onStatusUpdate(failMsg)
                        return@withLock Result.failure(Exception(failMsg))
                    }

                    // Unified Stage 4: Bind Keys Immediately Before Capability Sync
                    emitUnifiedStageRecord(
                        deviceId = candidateGuid,
                        stage = UnifiedOnboardingStage.BINDING_KEYS,
                        activeTransport = activeTransport,
                        vendorDriver = vendorDriver,
                        pairingSessionId = sessionId,
                        durationMs = verifyLatencyMs,
                        protocolResultCode = "KEYS_BOUND",
                        macAddress = resolvedIdentityForCommit.macAddress
                    )
                    ClaraDeviceRegistryStore.getInstance(context).persistBoundDeviceImmediately(
                        deviceId = devId.ifBlank { resolvedIdentityForCommit.macAddress.ifBlank { identity.ipAddress } },
                        ipAddress = resolvedIdentityForCommit.ipAddress,
                        port = resolvedIdentityForCommit.port,
                        macAddress = resolvedIdentityForCommit.macAddress,
                        localOrClientKey = resolvedIdentityForCommit.localKeyOrPin,
                        providerId = ProviderId.fromRawKey(activeTransport),
                        suggestedTitle = resolvedIdentityForCommit.displayName,
                        suggestedRoom = targetRoom,
                        suggestedTileType = resolvedIdentityForCommit.suggestedType
                    )
                    JarvisLiveBridge.updatePairingTimeline(
                        stage = PairingTimelineStage.REGISTERING_CLOUD,
                        customDetail = "Saved verified device identity & security keys",
                        targetKey = key,
                        customTitle = "Binding Security Keys"
                    )

                    // Unified Stage 5 & 6: Discover Capabilities -> Initial Sync
                    emitUnifiedStageRecord(
                        deviceId = candidateGuid,
                        stage = UnifiedOnboardingStage.DISCOVERING_CAPABILITIES,
                        activeTransport = activeTransport,
                        vendorDriver = vendorDriver,
                        pairingSessionId = sessionId,
                        macAddress = resolvedIdentityForCommit.macAddress
                    )
                    JarvisLiveBridge.updatePairingTimeline(
                        stage = PairingTimelineStage.INITIALIZING_TILE,
                        customDetail = "Reading verified hardware capabilities & state...",
                        targetKey = key,
                        customTitle = "Discovering Capabilities"
                    )

                    val created = withContext(Dispatchers.Main) {
                        buildAndCommitOnboardedTile(
                            context = context,
                            identity = resolvedIdentityForCommit,
                            targetRoom = targetRoom,
                            tiles = tiles,
                            splitMultiGangIntoTwoTiles = splitMultiGangIntoTwoTiles,
                            canonicalGuidOverride = candidateGuid
                        )
                    }
                    emitUnifiedStageRecord(
                        deviceId = candidateGuid,
                        stage = UnifiedOnboardingStage.INITIAL_SYNC,
                        activeTransport = activeTransport,
                        vendorDriver = vendorDriver,
                        pairingSessionId = sessionId,
                        protocolResultCode = "INITIAL_SYNC_COMPLETE",
                        macAddress = resolvedIdentityForCommit.macAddress
                    )
                    activeUnifiedStageFlow.value = UnifiedOnboardingStage.COMPLETED
                    JarvisLiveBridge.isPairingTuyaFlow.value = false
                    JarvisLiveBridge.updatePairingTimeline(
                        stage = PairingTimelineStage.COMPLETED,
                        customDetail = "Connected '${created.title}'!",
                        targetKey = key
                    )
                    JarvisLiveBridge.recordOnboardingOutcome(succeeded = true, userDecisionCount = 1)
                    onStatusUpdate("Added '${created.title}' to your dashboard!")
                    return@withLock Result.success(created)
                }
            }
        }
    }

    /**
     * Stage 3 Universal Short-Code Router:
     * - 6-to-8 character alphanumeric code -> Routes automatically to Google Nest Entry Key claim provider.
     * - 11-to-21 digit numeric or `MT:` payload -> Routes automatically to Matter commissioner.
     */
    suspend fun onboardByUniversalShortCode(
        context: Context,
        rawCode: String,
        customTitle: String = "",
        targetRoom: String = "Living Room",
        tiles: MutableList<SmartTile>,
        bridgeSettings: BridgeSettings,
        canonicalGuidHint: String = "",
        onStatusUpdate: (String) -> Unit = {}
    ): Result<SmartTile> = withContext(Dispatchers.IO) {
        val clean = rawCode.trim()
        val alphanumeric = clean.replace("-", "").replace(" ", "").trim()
        val digitsOnly = clean.filter { it.isDigit() }

        // Route 1: 11-to-21 digit numeric code or MT: QR payload -> Matter Commissioner
        if (clean.startsWith("MT:", ignoreCase = true) || digitsOnly.length in 11..21) {
            onStatusUpdate("Commissioning device over local fabric...")
            if (canonicalGuidHint.isNotBlank()) {
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = canonicalGuidHint,
                    event = MandatoryLifecycleTransition.PAIRING_STARTED.eventName,
                    previousState = DeviceLifecycleState.VALIDATED.name,
                    newState = DeviceLifecycleState.CONNECTABLE.name,
                    activeTransport = ProviderId.MATTER_FABRIC.id,
                    vendorDriver = ProviderId.MATTER_FABRIC.displayName,
                    latencyMs = 0L,
                    protocolResultCode = "MATTER_CODE_COMMISSION"
                )
            }
            val commStartMs = System.currentTimeMillis()
            val res = MatterProtocolClient.commissionMatterDeviceWithCode(
                context = context,
                setupPayloadOrPin = clean,
                customTitle = customTitle.ifBlank { "$targetRoom Smart Device".trim() },
                explicitIp = ""
            )
            val commLatencyMs = (System.currentTimeMillis() - commStartMs).coerceAtLeast(1L)
            if (res.isSuccess) {
                val node = res.getOrThrow()
                val effectiveMatterGuid = canonicalGuidHint.ifBlank {
                    "guid::matter::${node.nodeId.lowercase(Locale.US)}"
                }
                if (canonicalGuidHint.isBlank()) {
                    DeviceLifecycleRecorder.recordTransition(
                        deviceId = effectiveMatterGuid,
                        event = MandatoryLifecycleTransition.DISCOVERED.eventName,
                        previousState = "NONE",
                        newState = DeviceLifecycleState.DISCOVERED.name,
                        activeTransport = ProviderId.MATTER_FABRIC.id,
                        vendorDriver = ProviderId.MATTER_FABRIC.displayName,
                        protocolResultCode = "MATTER_SHORT_CODE"
                    )
                    DeviceLifecycleRecorder.recordTransition(
                        deviceId = effectiveMatterGuid,
                        event = MandatoryLifecycleTransition.VALIDATED.eventName,
                        previousState = DeviceLifecycleState.DISCOVERED.name,
                        newState = DeviceLifecycleState.VALIDATED.name,
                        activeTransport = ProviderId.MATTER_FABRIC.id,
                        vendorDriver = ProviderId.MATTER_FABRIC.displayName,
                        protocolResultCode = "MATTER_PAYLOAD_VALID"
                    )
                    DeviceLifecycleRecorder.recordTransition(
                        deviceId = effectiveMatterGuid,
                        event = MandatoryLifecycleTransition.PAIRING_STARTED.eventName,
                        previousState = DeviceLifecycleState.VALIDATED.name,
                        newState = DeviceLifecycleState.CONNECTABLE.name,
                        activeTransport = ProviderId.MATTER_FABRIC.id,
                        vendorDriver = ProviderId.MATTER_FABRIC.displayName,
                        protocolResultCode = "MATTER_CODE_COMMISSION"
                    )
                }
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = effectiveMatterGuid,
                    event = MandatoryLifecycleTransition.PAIRING_COMPLETED.eventName,
                    previousState = DeviceLifecycleState.CONNECTABLE.name,
                    newState = DeviceLifecycleState.CONNECTABLE.name,
                    activeTransport = ProviderId.MATTER_FABRIC.id,
                    vendorDriver = ProviderId.MATTER_FABRIC.displayName,
                    latencyMs = commLatencyMs,
                    protocolResultCode = "PASE_CASE_OK"
                )
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = effectiveMatterGuid,
                    event = MandatoryLifecycleTransition.AUTH_SUCCESS.eventName,
                    previousState = DeviceLifecycleState.CONNECTABLE.name,
                    newState = DeviceLifecycleState.AUTHENTICATED.name,
                    activeTransport = ProviderId.MATTER_FABRIC.id,
                    vendorDriver = ProviderId.MATTER_FABRIC.displayName,
                    protocolResultCode = "NOC_INSTALLED"
                )
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = effectiveMatterGuid,
                    event = MandatoryLifecycleTransition.ACTIVATION_STARTED.eventName,
                    previousState = DeviceLifecycleState.AUTHENTICATED.name,
                    newState = DeviceLifecycleState.AUTHENTICATED.name,
                    activeTransport = ProviderId.MATTER_FABRIC.id,
                    vendorDriver = ProviderId.MATTER_FABRIC.displayName,
                    protocolResultCode = "FABRIC_BIND"
                )
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = effectiveMatterGuid,
                    event = MandatoryLifecycleTransition.ACTIVATION_COMPLETED.eventName,
                    previousState = DeviceLifecycleState.AUTHENTICATED.name,
                    newState = DeviceLifecycleState.ACTIVATED.name,
                    activeTransport = ProviderId.MATTER_FABRIC.id,
                    vendorDriver = ProviderId.MATTER_FABRIC.displayName,
                    protocolResultCode = node.nodeId
                )
                val isNestThermostat = customTitle.contains("nest", true) ||
                        customTitle.contains("thermostat", true) ||
                        node.vendorName.contains("Nest", true)
                val resolvedType = if (isNestThermostat) TileType.NEST_THERMOSTAT else node.suggestedType
                val identity = ResolvedDeviceIdentity(
                    identityKey = "matter_${node.nodeId}",
                    displayName = customTitle.ifBlank { node.displayName },
                    suggestedRoom = targetRoom,
                    deviceClass = resolvedType.toSemanticDeviceClass(),
                    suggestedType = resolvedType,
                    combinedConfidence = 0.99f,
                    primaryProviderId = TransportRadioType.MATTER_FABRIC.name,
                    fallbackProviderIds = listOf(TransportRadioType.HOME_ASSISTANT_BRIDGE.name),
                    onboardingRequirement = OnboardingRequirement.ZeroTouchReady,
                    ipAddress = node.ipAddress,
                    port = node.port,
                    hardwareSerialOrNodeId = node.nodeId,
                    isActive = true,
                    temperatureC = node.temperatureCelsius,
                    targetTempC = node.targetTempCelsius
                )
                val tile = withContext(Dispatchers.Main) {
                    buildAndCommitOnboardedTile(
                        context = context,
                        identity = identity,
                        targetRoom = targetRoom,
                        tiles = tiles,
                        canonicalGuidOverride = effectiveMatterGuid
                    )
                }
                JarvisLiveBridge.recordOnboardingOutcome(succeeded = true, userDecisionCount = 1)
                return@withContext Result.success(tile)
            }
            val failErr = res.exceptionOrNull() ?: Exception("Could not pair device with code '$clean'.")
            if (canonicalGuidHint.isNotBlank()) {
                val failCat = ErrorClassifier.classifyException(failErr)
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = canonicalGuidHint,
                    event = MandatoryLifecycleTransition.AUTH_FAILED.eventName,
                    previousState = DeviceLifecycleState.CONNECTABLE.name,
                    newState = DeviceLifecycleState.QUARANTINED_ERROR.name,
                    activeTransport = ProviderId.MATTER_FABRIC.id,
                    vendorDriver = ProviderId.MATTER_FABRIC.displayName,
                    latencyMs = commLatencyMs,
                    protocolResultCode = "MATTER_COMMISSION_FAILED",
                    failureCategory = failCat.name
                )
            }
            JarvisLiveBridge.recordOnboardingOutcome(succeeded = false, userDecisionCount = 1)
            return@withContext Result.failure(failErr)
        }

        // Route 2: 6-to-8 character alphanumeric Entry Key -> Google Nest Entry Key Claim Provider
        if (alphanumeric.length in 6..8 && alphanumeric.all { it.isLetterOrDigit() }) {
            val upperKey = alphanumeric.uppercase(Locale.US)
            onStatusUpdate("Claiming Thermostat with Entry Key '$upperKey'...")
            if (canonicalGuidHint.isNotBlank()) {
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = canonicalGuidHint,
                    event = MandatoryLifecycleTransition.PAIRING_STARTED.eventName,
                    previousState = DeviceLifecycleState.VALIDATED.name,
                    newState = DeviceLifecycleState.CONNECTABLE.name,
                    activeTransport = ProviderId.GOOGLE_NEST.id,
                    vendorDriver = ProviderId.GOOGLE_NEST.displayName,
                    protocolResultCode = "NEST_ENTRY_KEY_CLAIM"
                )
            }
            val claimStartMs = System.currentTimeMillis()
            val claimRes = NestSdmApiClient.claimThermostatByEntryKey(
                context = context,
                entryKey = upperKey,
                roomName = targetRoom
            )
            val claimLatencyMs = (System.currentTimeMillis() - claimStartMs).coerceAtLeast(1L)
            if (claimRes.isSuccess) {
                val nd = claimRes.getOrThrow()
                val effectiveNestGuid = canonicalGuidHint.ifBlank { "guid::nest::${nd.deviceName}" }
                if (canonicalGuidHint.isBlank()) {
                    DeviceLifecycleRecorder.recordTransition(
                        deviceId = effectiveNestGuid,
                        event = MandatoryLifecycleTransition.DISCOVERED.eventName,
                        previousState = "NONE",
                        newState = DeviceLifecycleState.DISCOVERED.name,
                        activeTransport = ProviderId.GOOGLE_NEST.id,
                        vendorDriver = ProviderId.GOOGLE_NEST.displayName,
                        protocolResultCode = "NEST_ENTRY_KEY"
                    )
                    DeviceLifecycleRecorder.recordTransition(
                        deviceId = effectiveNestGuid,
                        event = MandatoryLifecycleTransition.VALIDATED.eventName,
                        previousState = DeviceLifecycleState.DISCOVERED.name,
                        newState = DeviceLifecycleState.VALIDATED.name,
                        activeTransport = ProviderId.GOOGLE_NEST.id,
                        vendorDriver = ProviderId.GOOGLE_NEST.displayName,
                        protocolResultCode = "ENTRY_KEY_FORMAT_OK"
                    )
                    DeviceLifecycleRecorder.recordTransition(
                        deviceId = effectiveNestGuid,
                        event = MandatoryLifecycleTransition.PAIRING_STARTED.eventName,
                        previousState = DeviceLifecycleState.VALIDATED.name,
                        newState = DeviceLifecycleState.CONNECTABLE.name,
                        activeTransport = ProviderId.GOOGLE_NEST.id,
                        vendorDriver = ProviderId.GOOGLE_NEST.displayName,
                        protocolResultCode = "NEST_ENTRY_KEY_CLAIM"
                    )
                }
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = effectiveNestGuid,
                    event = MandatoryLifecycleTransition.PAIRING_COMPLETED.eventName,
                    previousState = DeviceLifecycleState.CONNECTABLE.name,
                    newState = DeviceLifecycleState.CONNECTABLE.name,
                    activeTransport = ProviderId.GOOGLE_NEST.id,
                    vendorDriver = ProviderId.GOOGLE_NEST.displayName,
                    latencyMs = claimLatencyMs,
                    protocolResultCode = "ENTRY_KEY_CLAIMED"
                )
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = effectiveNestGuid,
                    event = MandatoryLifecycleTransition.AUTH_SUCCESS.eventName,
                    previousState = DeviceLifecycleState.CONNECTABLE.name,
                    newState = DeviceLifecycleState.AUTHENTICATED.name,
                    activeTransport = ProviderId.GOOGLE_NEST.id,
                    vendorDriver = ProviderId.GOOGLE_NEST.displayName,
                    protocolResultCode = "NEST_SESSION_OK"
                )
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = effectiveNestGuid,
                    event = MandatoryLifecycleTransition.ACTIVATION_STARTED.eventName,
                    previousState = DeviceLifecycleState.AUTHENTICATED.name,
                    newState = DeviceLifecycleState.AUTHENTICATED.name,
                    activeTransport = ProviderId.GOOGLE_NEST.id,
                    vendorDriver = ProviderId.GOOGLE_NEST.displayName,
                    protocolResultCode = "NEST_STRUCTURE_BIND"
                )
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = effectiveNestGuid,
                    event = MandatoryLifecycleTransition.ACTIVATION_COMPLETED.eventName,
                    previousState = DeviceLifecycleState.AUTHENTICATED.name,
                    newState = DeviceLifecycleState.ACTIVATED.name,
                    activeTransport = ProviderId.GOOGLE_NEST.id,
                    vendorDriver = ProviderId.GOOGLE_NEST.displayName,
                    protocolResultCode = nd.deviceName.substringAfterLast("/")
                )
                val identity = ResolvedDeviceIdentity(
                    identityKey = "nest_${nd.deviceName}",
                    displayName = customTitle.ifBlank { nd.displayName },
                    suggestedRoom = nd.roomName.ifBlank { targetRoom },
                    deviceClass = DeviceClass.CLIMATE,
                    suggestedType = TileType.NEST_THERMOSTAT,
                    combinedConfidence = 0.99f,
                    primaryProviderId = TransportRadioType.CLOUD_ACCOUNT_API.name,
                    fallbackProviderIds = listOf(TransportRadioType.HOME_ASSISTANT_BRIDGE.name),
                    onboardingRequirement = OnboardingRequirement.ZeroTouchReady,
                    cloudDeviceId = nd.deviceName,
                    hardwareSerialOrNodeId = nd.deviceName,
                    isActive = true,
                    isOnline = nd.isOnline,
                    temperatureC = nd.ambientTempC,
                    targetTempC = nd.targetTempC,
                    humidityPct = nd.humidityPct,
                    hvacMode = nd.hvacMode,
                    hvacStatus = nd.hvacStatus
                )
                val tile = withContext(Dispatchers.Main) {
                    buildAndCommitOnboardedTile(
                        context = context,
                        identity = identity,
                        targetRoom = targetRoom,
                        tiles = tiles,
                        canonicalGuidOverride = effectiveNestGuid
                    )
                }
                JarvisLiveBridge.recordOnboardingOutcome(succeeded = true, userDecisionCount = 1)
                return@withContext Result.success(tile)
            }
            val claimErr = claimRes.exceptionOrNull()
                ?: Exception("NEEDS_NEST_SIGN_IN: Sign in with your Google Account to claim Entry Key '$upperKey'.")
            if (canonicalGuidHint.isNotBlank()) {
                val failCat = ErrorClassifier.classifyException(claimErr)
                DeviceLifecycleRecorder.recordTransition(
                    deviceId = canonicalGuidHint,
                    event = MandatoryLifecycleTransition.AUTH_FAILED.eventName,
                    previousState = DeviceLifecycleState.CONNECTABLE.name,
                    newState = DeviceLifecycleState.QUARANTINED_ERROR.name,
                    activeTransport = ProviderId.GOOGLE_NEST.id,
                    vendorDriver = ProviderId.GOOGLE_NEST.displayName,
                    latencyMs = claimLatencyMs,
                    protocolResultCode = "NEST_CLAIM_FAILED",
                    failureCategory = failCat.name
                )
            }
            return@withContext Result.failure(claimErr)
        }

        Result.failure(
            Exception("Enter a 7-character Thermostat Entry Key (e.g. 123ABCD) or an 11-to-21 digit setup code.")
        )
    }

    fun splitOrMergeTwoGangTile(
        context: Context,
        tile: SmartTile,
        tiles: MutableList<SmartTile>,
        splitIntoTwo: Boolean
    ) {
        val devKey = tile.serialNumber.ifBlank { tile.entityId }.trim()
        val hardwareGuid = tile.canonicalGuid.ifBlank { tile.resolvedCanonicalGuid() }
        val baseTitle = tile.title
            .substringBefore(" \u2014 ")
            .trim()
            .ifBlank { tile.title }
        val s1Name = tile.socket1Name.ifBlank { "Socket 1" }
        val s2Name = tile.socket2Name.ifBlank { "Socket 2" }

        fun matchesPhysicalPlug(candidate: SmartTile): Boolean {
            if (candidate.id == tile.id) return true
            if (hardwareGuid.isNotBlank() && !hardwareGuid.startsWith("guid::unresolved::")) {
                val candGuid = candidate.canonicalGuid.ifBlank { candidate.resolvedCanonicalGuid() }
                if (candGuid == hardwareGuid) return true
            }
            return devKey.isNotBlank() &&
                    (candidate.serialNumber.equals(devKey, true) || candidate.entityId.equals(devKey, true))
        }

        if (splitIntoTwo) {
            tiles.removeAll { matchesPhysicalPlug(it) }
            val tile1 = tile.copy(
                id = UUID.randomUUID().toString(),
                title = "$baseTitle \u2014 $s1Name",
                channelId = "SPLIT_SOCKET_1",
                subEndpointIndex = 1,
                canonicalGuid = hardwareGuid,
                multiGangSocketCount = 2,
                gridCol = -1,
                gridRow = -1,
                isVisible = true
            )
            val tile2 = tile.copy(
                id = UUID.randomUUID().toString(),
                title = "$baseTitle \u2014 $s2Name",
                channelId = "SPLIT_SOCKET_2",
                subEndpointIndex = 2,
                canonicalGuid = hardwareGuid,
                multiGangSocketCount = 2,
                gridCol = -1,
                gridRow = -1,
                isVisible = true
            )
            tiles.add(tile1)
            tiles.add(tile2)
        } else {
            tiles.removeAll { matchesPhysicalPlug(it) }
            val combined = tile.copy(
                id = UUID.randomUUID().toString(),
                title = baseTitle,
                channelId = "0",
                subEndpointIndex = 0,
                canonicalGuid = hardwareGuid,
                multiGangSocketCount = 2,
                gridCol = -1,
                gridRow = -1,
                isVisible = true
            )
            tiles.add(combined)
        }
        DeviceRepository.saveTiles(context, tiles)
    }

    private fun buildAndCommitOnboardedTile(
        context: Context,
        identity: ResolvedDeviceIdentity,
        targetRoom: String,
        tiles: MutableList<SmartTile>,
        splitMultiGangIntoTwoTiles: Boolean = false,
        canonicalGuidOverride: String = ""
    ): SmartTile {
        val endpointIp = if (
            (identity.suggestedType == TileType.NEST_MINI || identity.suggestedType == TileType.SONOFF_SWITCH) &&
            identity.port > 0 && identity.ipAddress.isNotBlank() && !identity.ipAddress.contains(":")
        ) {
            "${identity.ipAddress}:${identity.port}"
        } else {
            identity.ipAddress
        }

        val devId = identity.hardwareSerialOrNodeId.ifBlank { identity.cloudDeviceId }
        if (devId.isNotBlank() && identity.ipAddress.isNotBlank()) {
            TuyaApiClient.registerDeviceLanAddress(devId, identity.ipAddress, identity.localKeyOrPin)
        }
        DeviceRepository.unmarkDeviceDismissed(context, devId, identity.ipAddress, identity.displayName)

        val inferredFingerprint = AppliancePowerFingerprinter.inferApplianceFromTelemetry(
            powerWatts = identity.powerWatts,
            titleHint = identity.displayName
        )
        val lookupKey = devId.ifBlank { identity.ipAddress.substringBefore(":") }.ifBlank { identity.identityKey }
        val savedResolution = SemanticClassifier.getResolution(lookupKey, context)
        val isConfirmedOverride = savedResolution?.source == ClassificationSource.MANUAL_OVERRIDE
        val effectiveClass = if (isConfirmedOverride) savedResolution!!.deviceClass else identity.deviceClass
        val effectiveSubtype = if (isConfirmedOverride) savedResolution!!.deviceSubtype else identity.deviceSubtype
        val initialVolume = if (identity.suggestedType == TileType.LG_TV || effectiveSubtype == DeviceSubtype.TV) {
            // Avoid seeding LG TV tiles with the 80% fingerprint default before live SSAP telemetry arrives
            identity.levelOrVolumePct.takeIf { it != 80f } ?: 25f
        } else {
            identity.levelOrVolumePct
        }
        val canonicalProviderId = ProviderId.fromRawKey(identity.primaryProviderId)
            .takeIf { it != ProviderId.UNKNOWN }
            ?.id ?: identity.primaryProviderId
        val resolvedCanonicalGuid = canonicalGuidOverride.trim()
            .takeIf { it.isNotBlank() && !it.startsWith("guid::unresolved::") }
            ?: CrossEcosystemIdentityCorrelator.resolveCanonicalGuid(
                rawDeviceId = identity.hardwareSerialOrNodeId,
                macAddress = identity.macAddress,
                hardwareSerial = identity.hardwareSerialOrNodeId,
                cloudDeviceId = identity.cloudDeviceId,
                activeProviderId = identity.primaryProviderId,
                fallbackIdentityKey = identity.identityKey
            )

        val baseTile = SmartTile(
            title = identity.displayName,
            roomOrSubtitle = targetRoom,
            type = identity.suggestedType,
            colSpan = if (effectiveClass == DeviceClass.CAMERA) 2 else 1,
            rowSpan = 1,
            isVisible = true,
            isOnline = identity.isOnline,
            isActive = identity.isActive,
            isSocket2Active = identity.isSocket2Active,
            multiGangSocketCount = identity.multiGangCount.coerceAtLeast(1),
            socket1Name = identity.socket1Name.ifBlank { "Socket 1" },
            socket2Name = identity.socket2Name.ifBlank { "Socket 2" },
            temperature = identity.temperatureC,
            targetTemperature = identity.targetTempC,
            humidity = identity.humidityPct,
            hvacMode = identity.hvacMode,
            hvacStatus = identity.hvacStatus,
            volume = initialVolume,
            powerWatts = identity.powerWatts,
            voltageVolts = identity.voltageVolts,
            currentMilliamps = identity.currentMilliamps,
            totalEnergyKwh = identity.totalEnergyKwh,
            ipAddress = endpointIp,
            secretCode = identity.localKeyOrPin,
            serialNumber = devId.ifBlank { identity.macAddress },
            entityId = identity.cloudDeviceId.ifBlank { devId },
            hasExplicitPowerTelemetry = identity.hasPowerTelemetry,
            detectedApplianceCategory = inferredFingerprint,
            deviceClassOverride = effectiveClass,
            deviceSubtypeOverride = effectiveSubtype,
            classificationSource = if (isConfirmedOverride) ClassificationSource.MANUAL_OVERRIDE else ClassificationSource.CAPABILITY_CLASSIFIER,
            classificationConfidence = if (isConfirmedOverride) 1.0f else identity.combinedConfidence,
            lifecycleState = when {
                !identity.isOnline -> DeviceLifecycleState.OFFLINE
                identity.capabilities.isEmpty() &&
                        identity.suggestedType in setOf(
                    TileType.SMARTKNIGHT_PLUG,
                    TileType.SMARTLIFE_DEVICE,
                    TileType.TUYA_RADIATOR,
                    TileType.MATTER_DEVICE
                ) -> DeviceLifecycleState.ACTIVATED
                else -> DeviceLifecycleState.OPERATIONAL
            },
            stateVersion = 1L,
            stateOriginEpochMs = System.currentTimeMillis(),
            stateAuthorityTier = if (endpointIp.isNotBlank() ||
                ProviderId.fromRawKey(canonicalProviderId).let { it != ProviderId.UNKNOWN && it.isLocalTransport }
            ) {
                StateAuthorityTier.DEVICE_HARDWARE_TRUTH
            } else {
                StateAuthorityTier.CLOUD_SHADOW_STATE
            },
            activeProviderId = canonicalProviderId,
            fallbackProviderIds = identity.fallbackProviderIds,
            parentNetworkNodeId = identity.parentNetworkNodeId,
            macAddress = CrossEcosystemIdentityCorrelator.normalizeMac(identity.macAddress),
            canonicalGuid = resolvedCanonicalGuid
        )

        val resolvedLifecycle = baseTile.lifecycleState
        val resolvedAuthority = baseTile.stateAuthorityTier

        val hardwareSchemaHash = CapabilitySchemaHasher.computeHash(identity.capabilities)
        val acquiredCaps = identity.capabilities.map {
            it.copy(
                provenance = CapabilityProvenance.VERIFIED_HARDWARE_SCHEMA,
                schemaHash = hardwareSchemaHash
            )
        }
        val capHash = CapabilitySchemaHasher.computeHash(acquiredCaps)
        val populatedTile = baseTile.copy(
            capabilities = acquiredCaps,
            capabilitySchemaHash = capHash
        )

        val safeDevId = devId.takeUnless { ConnectedIdentityIndex.isBareIpLiteral(it) }.orEmpty()
        val matchedTileIds = ConnectedIdentityIndex.findMatchingTileIdsByMacOrId(
            rawDeviceId = safeDevId,
            macAddress = identity.macAddress,
            providerId = canonicalProviderId,
            serialNumber = safeDevId,
            cloudDeviceId = identity.cloudDeviceId
        )
        val isSingletonSystemTile = populatedTile.type in setOf(
            TileType.PHOTO_SLIDESHOW,
            TileType.SECURITY_CENTER,
            TileType.CLOCK_WEATHER,
            TileType.ENERGY_MONITOR
        )
        val existingMatchingIndices = tiles.indices.filter { idx ->
            val t = tiles[idx]
            (t.id in matchedTileIds) ||
                    (safeDevId.isNotBlank() && (t.serialNumber.equals(safeDevId, true) || t.entityId.equals(safeDevId, true))) ||
                    CrossEcosystemIdentityCorrelator.areMacsCorrelated(t.macAddress, identity.macAddress) ||
                    (isSingletonSystemTile && t.type == populatedTile.type)
        }
        val finalTile = if (splitMultiGangIntoTwoTiles && populatedTile.multiGangSocketCount >= 2) {
            splitOrMergeTwoGangTile(
                context = context,
                tile = populatedTile,
                tiles = tiles,
                splitIntoTwo = true
            )
            tiles.lastOrNull() ?: populatedTile
        } else if (existingMatchingIndices.size >= 2 && existingMatchingIndices.all { tiles[it].subEndpointIndex > 0 }) {
            var lastMerged = populatedTile
            for (idx in existingMatchingIndices) {
                val existingSplit = tiles[idx]
                val preservedGuid = existingSplit.canonicalGuid
                    .takeIf { it.isNotBlank() && !it.startsWith("guid::unresolved::") }
                    ?: populatedTile.canonicalGuid
                val updatedSplit = populatedTile.copy(
                    id = existingSplit.id,
                    title = existingSplit.title,
                    roomOrSubtitle = existingSplit.roomOrSubtitle.ifBlank { populatedTile.roomOrSubtitle },
                    channelId = existingSplit.channelId,
                    subEndpointIndex = existingSplit.subEndpointIndex,
                    gridCol = existingSplit.gridCol,
                    gridRow = existingSplit.gridRow,
                    colSpan = existingSplit.colSpan,
                    rowSpan = existingSplit.rowSpan,
                    groupCardId = existingSplit.groupCardId,
                    canonicalGuid = preservedGuid
                )
                tiles[idx] = updatedSplit
                lastMerged = updatedSplit
            }
            lastMerged
        } else if (existingMatchingIndices.isNotEmpty()) {
            val existingIdx = existingMatchingIndices.first()
            val existing = tiles[existingIdx]
            val preservedGuid = existing.canonicalGuid
                .takeIf { it.isNotBlank() && !it.startsWith("guid::unresolved::") }
                ?: populatedTile.canonicalGuid
            val merged = populatedTile.copy(
                id = existing.id,
                channelId = existing.channelId,
                subEndpointIndex = existing.subEndpointIndex,
                gridCol = existing.gridCol,
                gridRow = existing.gridRow,
                colSpan = existing.colSpan,
                rowSpan = existing.rowSpan,
                groupCardId = existing.groupCardId,
                canonicalGuid = preservedGuid
            )
            tiles[existingIdx] = merged
            merged
        } else {
            tiles.add(populatedTile)
            populatedTile
        }
        DeviceLifecycleRecorder.registerTileAliases(finalTile)
        DeviceLifecycleRecorder.recordTileTransition(
            tile = finalTile,
            transition = MandatoryLifecycleTransition.CAPABILITY_REQUESTED,
            previousState = DeviceLifecycleState.ACTIVATED.name,
            newState = DeviceLifecycleState.CAPABILITY_LEARNING.name,
            protocolResultCode = "SCHEMA_INTROSPECTION"
        )
        DeviceLifecycleRecorder.recordTileTransition(
            tile = finalTile,
            transition = MandatoryLifecycleTransition.CAPABILITY_RECEIVED,
            previousState = DeviceLifecycleState.CAPABILITY_LEARNING.name,
            newState = DeviceLifecycleState.CAPABILITIES_LOADED.name,
            protocolResultCode = "CAPS_${acquiredCaps.size}_${capHash.take(8)}"
        )
        DeviceLifecycleRecorder.recordTileTransition(
            tile = finalTile,
            transition = if (finalTile.isOnline) {
                MandatoryLifecycleTransition.ONLINE
            } else {
                MandatoryLifecycleTransition.OFFLINE
            },
            previousState = DeviceLifecycleState.CAPABILITIES_LOADED.name,
            newState = resolvedLifecycle.name,
            protocolResultCode = if (finalTile.isOnline) "TILE_ONLINE" else "TILE_OFFLINE",
            failureCategory = if (finalTile.isOnline) null else FailureCategory.TRANSPORT_FAILURE
        )
        DeviceRepository.saveTiles(context, tiles)

        JarvisLiveBridge.registerOrUpdateFailoverPolicy(
            ProviderFailoverPolicy(
                deviceId = finalTile.id,
                primaryProviderId = identity.primaryProviderId,
                orderedFallbackProviderIds = identity.fallbackProviderIds,
                activeProviderId = identity.primaryProviderId,
                parentNetworkNodeId = identity.parentNetworkNodeId
            )
        )
        JarvisLiveBridge.updateDeviceLifecycleAndRecoveryStage(
            tile = finalTile,
            lifecycleState = resolvedLifecycle,
            activeProviderId = identity.primaryProviderId,
            emitFlightRecord = false
        )
        JarvisLiveBridge.appendDeviceEvent(
            DeviceEvent(
                deviceId = finalTile.id,
                deviceTitle = finalTile.title,
                roomName = finalTile.roomOrSubtitle,
                eventType = DeviceEventType.ONBOARDED,
                source = identity.transportSummaryText,
                actorName = "Universal Onboarding",
                stateVersion = 1L,
                authorityTierLabel = resolvedAuthority.label,
                newStateSummary = "Onboarded as ${finalTile.deviceClass.label} (${acquiredCaps.size} capabilities)"
            )
        )

        JarvisLiveBridge.seedDeviceObservabilityForTiles(listOf(finalTile))
        resolvedIdentitiesFlow.value = resolvedIdentitiesFlow.value.filterNot {
            it.identityKey == identity.identityKey
        }
        return finalTile
    }

    fun mapTileToResolvedIdentity(
        candidateTile: SmartTile,
        sourceLabel: String = "Universal Onboarding"
    ): ResolvedDeviceIdentity {
        val parsedEp = EndpointSplitter.splitHostAndPort(candidateTile.ipAddress)
        val acquiredCaps = candidateTile.capabilities.ifEmpty {
            SmartHomeApiClient.buildDefaultCapabilitiesForTile(candidateTile)
        }
        val provider = ProviderId.fromRawKey(candidateTile.activeProviderId)
            .takeIf { it != ProviderId.UNKNOWN } ?: candidateTile.resolvedProviderId
        val devId = candidateTile.serialNumber.ifBlank { candidateTile.entityId }
            .ifBlank { candidateTile.vehicleVin }
        val canonGuid = candidateTile.canonicalGuid.ifBlank { candidateTile.resolvedCanonicalGuid() }
        return ResolvedDeviceIdentity(
            identityKey = canonGuid.ifBlank { "tile_${candidateTile.id}" },
            canonicalGuid = canonGuid,
            displayName = candidateTile.title,
            suggestedRoom = candidateTile.roomOrSubtitle,
            deviceClass = candidateTile.deviceClassOverride ?: candidateTile.deviceClass,
            deviceSubtype = candidateTile.deviceSubtypeOverride ?: candidateTile.deviceSubtype,
            suggestedType = candidateTile.type,
            combinedConfidence = candidateTile.classificationConfidence.coerceAtLeast(0.95f),
            primaryProviderId = provider.id,
            fallbackProviderIds = candidateTile.fallbackProviderIds,
            onboardingRequirement = OnboardingRequirement.ZeroTouchReady,
            ipAddress = parsedEp.hostIp,
            port = parsedEp.port,
            macAddress = candidateTile.macAddress,
            hardwareSerialOrNodeId = devId,
            cloudDeviceId = candidateTile.entityId.ifBlank { devId },
            localKeyOrPin = candidateTile.secretCode,
            multiGangCount = candidateTile.multiGangSocketCount.coerceAtLeast(1),
            socket1Name = candidateTile.socket1Name,
            socket2Name = candidateTile.socket2Name,
            isActive = candidateTile.isActive,
            isSocket2Active = candidateTile.isSocket2Active,
            isOnline = candidateTile.isOnline,
            temperatureC = candidateTile.temperature,
            targetTempC = candidateTile.targetTemperature,
            humidityPct = candidateTile.humidity,
            hvacMode = candidateTile.hvacMode,
            hvacStatus = candidateTile.hvacStatus,
            levelOrVolumePct = candidateTile.volume,
            powerWatts = candidateTile.powerWatts,
            voltageVolts = candidateTile.voltageVolts,
            currentMilliamps = candidateTile.currentMilliamps,
            totalEnergyKwh = candidateTile.totalEnergyKwh,
            hasPowerTelemetry = candidateTile.hasExplicitPowerTelemetry,
            parentNetworkNodeId = candidateTile.parentNetworkNodeId,
            capabilities = acquiredCaps,
            protocolBadges = listOf(sourceLabel)
        )
    }

    suspend fun onboardLegacyCandidateTiles(
        context: Context,
        candidateTiles: List<SmartTile>,
        tiles: MutableList<SmartTile>,
        bridgeSettings: BridgeSettings,
        scope: CoroutineScope,
        sourceLabel: String = "Universal Onboarding"
    ): Int {
        var count = 0
        for (candidate in candidateTiles) {
            val identity = mapTileToResolvedIdentity(candidate, sourceLabel)
            val res = onboardResolvedIdentity(
                context = context,
                identity = identity,
                tiles = tiles,
                bridgeSettings = bridgeSettings,
                scope = scope,
                customRoomOverride = candidate.roomOrSubtitle
            )
            val committed = res.getOrNull() ?: buildAndCommitOnboardedTile(
                context = context,
                identity = identity,
                targetRoom = candidate.roomOrSubtitle,
                tiles = tiles,
                canonicalGuidOverride = identity.canonicalGuid
            )
            if (candidate.isVehicleTile || candidate.customRtspUrl.isNotBlank() || candidate.colSpan > 1 || candidate.rowSpan > 1) {
                withContext(Dispatchers.Main) {
                    val idx = tiles.indexOfFirst { it.id == committed.id }
                    if (idx >= 0) {
                        tiles[idx] = tiles[idx].copy(
                            colSpan = maxOf(tiles[idx].colSpan, candidate.colSpan),
                            rowSpan = maxOf(tiles[idx].rowSpan, candidate.rowSpan),
                            isTall = tiles[idx].isTall || candidate.isTall,
                            customRtspUrl = candidate.customRtspUrl.ifBlank { tiles[idx].customRtspUrl },
                            channelId = if (candidate.channelId != "0") candidate.channelId else tiles[idx].channelId,
                            vehicleVin = candidate.vehicleVin.ifBlank { tiles[idx].vehicleVin },
                            vehicleLocked = candidate.vehicleLocked,
                            vehicleFuelOrBatteryPct = candidate.vehicleFuelOrBatteryPct,
                            vehicleRangeMiles = candidate.vehicleRangeMiles,
                            vehicleIsEv = candidate.vehicleIsEv,
                            vehicleIsCharging = candidate.vehicleIsCharging,
                            vehicleClimateActive = candidate.vehicleClimateActive,
                            vehicleOdometerMiles = candidate.vehicleOdometerMiles,
                            vehicleLocationText = candidate.vehicleLocationText,
                            securityMode = candidate.securityMode
                        )
                        DeviceRepository.saveTiles(context, tiles)
                    }
                }
            }
            count++
        }
        return count
    }

    fun onboardCompatibilityTiles(
        context: Context,
        candidateTiles: List<SmartTile>,
        tiles: MutableList<SmartTile>,
        sourceLabel: String = "Compatibility Onboarding"
    ) {
        candidateTiles.forEach {
            onboardCompatibilityTile(context, it, tiles, sourceLabel)
        }
    }

    fun onboardCompatibilityTile(
        context: Context,
        candidateTile: SmartTile,
        tiles: MutableList<SmartTile>,
        sourceLabel: String = "Compatibility Onboarding"
    ): SmartTile {
        val identity = mapTileToResolvedIdentity(candidateTile, sourceLabel)
        return buildAndCommitOnboardedTile(
            context = context,
            identity = identity,
            targetRoom = candidateTile.roomOrSubtitle,
            tiles = tiles,
            canonicalGuidOverride = identity.canonicalGuid
        )
    }

    suspend fun verifyCandidateReachability(
        context: Context,
        bridgeSettings: BridgeSettings,
        candidateTile: SmartTile
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanIp = candidateTile.ipAddress.substringBefore(":").trim()
        val devId = candidateTile.serialNumber.ifBlank { candidateTile.entityId }.trim()
        when {
            candidateTile.type == TileType.LG_TV -> {
                cleanIp.isNotBlank() &&
                        LgThinQWebOsClient.hasVerifiedVaultKey(context, cleanIp, candidateTile.macAddress, devId) &&
                        LgThinQWebOsClient.probeVerifiedWebOsSsapBanner(cleanIp, 1500, upnpAlreadyVerifiedLg = true)
            }
            cleanIp.isNotBlank() -> {
                TuyaApiClient.isDeviceReachableOrOnline(
                    deviceId = devId,
                    ipAddress = cleanIp,
                    reportedCloudOnline = false
                ) || SmartHomeApiClient.isPortOpenInternal(cleanIp, 80, 500) ||
                        SmartHomeApiClient.isPortOpenInternal(cleanIp, 554, 500) ||
                        SmartHomeApiClient.isPortOpenInternal(cleanIp, 4003, 500) ||
                        SmartHomeApiClient.isPortOpenInternal(cleanIp, 6668, 500) ||
                        SmartHomeApiClient.isPortOpenInternal(cleanIp, 8009, 500) ||
                        SmartHomeApiClient.isPortOpenInternal(cleanIp, 8081, 500) ||
                        SmartHomeApiClient.isPortOpenInternal(cleanIp, 9999, 500)
            }
            devId.contains(".") && bridgeSettings.haUrl.isNotBlank() && bridgeSettings.haToken.isNotBlank() -> {
                SmartHomeApiClient.fetchHomeAssistantEntities(bridgeSettings.haUrl, bridgeSettings.haToken)
                    .getOrNull()
                    ?.any { it.entityId.equals(devId, ignoreCase = true) && !it.state.equals("unavailable", true) } == true
            }
            devId.isNotBlank() -> {
                TuyaApiClient.isDeviceReachableOrOnline(
                    deviceId = devId,
                    ipAddress = "",
                    reportedCloudOnline = false
                )
            }
            else -> false
        }
    }
}
