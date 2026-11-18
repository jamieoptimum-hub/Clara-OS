package com.jarvis.smarthomelauncher.integrations

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.sin

enum class PairingTimelineStage(
    val stepIndex: Int,
    val title: String,
    val defaultSubtitle: String
) {
    IDLE(0, "Ready to Connect", "Select a setup mode to begin"),
    SCANNING_DEVICE(1, "Searching for Device", "Looking for your selected smart device"),
    DEVICE_FOUND(2, "Authenticating", "Verifying secure device handshake"),
    CONNECTING_WIFI(3, "Activating Connection", "Completing network & transport activation"),
    REGISTERING_CLOUD(4, "Binding Security Keys", "Registering device & saving encrypted local keys"),
    INITIALIZING_TILE(5, "Discovering Capabilities", "Reading verified hardware controls & initial state"),
    COMPLETED(6, "Device Connected", "Verified and added to your dashboard"),
    FAILED(-1, "Setup Needs Attention", "Review the guidance below to retry")
}

class CircuitBreakerOpenException(message: String) : Exception(message)

data class PairingTimelineEntry(
    val stage: PairingTimelineStage,
    val detail: String = stage.defaultSubtitle,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val title: String = stage.title
)

// ---------------------------------------------------------------------------
// CENTRAL PLATFORM EVENT BUS, HEALTH DIAGNOSTICS & LOCAL HUB SERVER (PORT 8089)
// ---------------------------------------------------------------------------
object JarvisLiveBridge {
    private const val TAG = "JarvisLiveBridge"
    const val LOCAL_HUB_PORT = 8089
    private const val MDNS_SERVICE_TYPE = "_clara._tcp."
    private const val MDNS_SERVICE_NAME_PREFIX = "ClaraSmartHomeHub"
    private const val MAX_CONCURRENT_HTTP_CLIENTS = 16

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClientSemaphore = Semaphore(MAX_CONCURRENT_HTTP_CLIENTS)
    private val recentEventsLock = Any()
    private val shadowQueueLock = Any()

    // Existing Live State Flows
    val doorbellRingFlow = MutableStateFlow<DoorbellRingEvent?>(null)
    val activeTrackFlow = MutableStateFlow("Not Playing")
    val isPlayingFlow = MutableStateFlow(false)
    val trackPositionMsFlow = MutableStateFlow(0L)
    val trackDurationMsFlow = MutableStateFlow(0L)

    // Live Music Library Sync Progress Bar State
    val isMusicSyncingFlow = MutableStateFlow(false)
    val musicSyncProgressFlow = MutableStateFlow(0f)
    val musicSyncStatusFlow = MutableStateFlow("")

    val weatherFlow = MutableStateFlow(WeatherInfo())
    val isVoiceListeningFlow = MutableStateFlow(false)
    val voiceStatusTextFlow = MutableStateFlow("")

    val discoveredCastDevicesFlow = MutableStateFlow<List<DiscoveredCastDevice>>(emptyList())
    val castSpeakerGroupsFlow = MutableStateFlow<List<CastSpeakerGroup>>(emptyList())
    val activeCastTargetNameFlow = MutableStateFlow("Tablet Speaker")

    // Live 2.4GHz Wi-Fi Network Scanning & Tuya Hardware Pairing State
    val availableWifiNetworksFlow = MutableStateFlow<List<ScannedWifiNetwork>>(emptyList())
    val isScanningWifiNetworksFlow = MutableStateFlow(false)
    val isPairingTuyaFlow = MutableStateFlow(false)
    val tuyaPairingStatusFlow = MutableStateFlow("")
    val tuyaPairingStageFlow = MutableStateFlow(PairingTimelineStage.IDLE)
    val activePairingStageTitleFlow = MutableStateFlow(PairingTimelineStage.IDLE.title)
    val tuyaPairingTimelineHistoryFlow = MutableStateFlow<List<PairingTimelineEntry>>(emptyList())
    val activeTimelineTargetKeyFlow = MutableStateFlow("")
    @Volatile
    private var timelineAutoHideJob: Job? = null

    fun inferFailureHeaderTitle(detailText: String): String {
        val lower = detailText.lowercase(Locale.US)
        return when {
            lower.contains("accept") && lower.contains("tv") -> "Confirmation Needed on TV"
            lower.contains("certificate") || lower.contains("tls") -> "TV Security Re-Verification Needed"
            lower.contains("5 ghz") || lower.contains("5ghz") || lower.contains("6 ghz") || lower.contains("2.4 ghz") || lower.contains("2.4ghz") ->
                "2.4 GHz Wi-Fi Required"
            lower.contains("permission") || lower.contains("location services") || lower.contains("turn on bluetooth") ->
                "Permission or Radio Required"
            lower.contains("sdk not") || lower.contains("sdk credentials") || lower.contains("uninitialized") ->
                "SDK Configuration Required"
            lower.contains("sign in") || lower.contains("account") || lower.contains("bound to another") || lower.contains("multi-admin") ->
                "Account or Ownership Action Required"
            lower.contains("password") || lower.contains("auth") || lower.contains("rejected") || lower.contains("401") || lower.contains("403") ->
                "Authentication Rejected"
            lower.contains("couldn't find") || lower.contains("timed out") || lower.contains("timeout") || lower.contains("did not respond") ->
                "Device Did Not Respond"
            else -> "Setup Could Not Complete"
        }
    }

    fun updatePairingTimeline(
        stage: PairingTimelineStage,
        customDetail: String = "",
        targetKey: String? = null,
        customTitle: String = ""
    ) {
        timelineAutoHideJob?.cancel()
        if (targetKey != null) {
            activeTimelineTargetKeyFlow.value = targetKey
        }
        val detailText = customDetail.ifBlank { stage.defaultSubtitle }
        val resolvedTitle = when {
            customTitle.isNotBlank() -> customTitle.trim()
            stage == PairingTimelineStage.FAILED -> inferFailureHeaderTitle(detailText)
            stage == PairingTimelineStage.CONNECTING_WIFI &&
                    (detailText.contains("Wi-Fi", ignoreCase = true) || detailText.contains("hotspot", ignoreCase = true)) ->
                "Connecting Device to Wi-Fi"
            else -> stage.title
        }
        tuyaPairingStageFlow.value = stage
        activePairingStageTitleFlow.value = resolvedTitle
        if (detailText.isNotBlank()) {
            tuyaPairingStatusFlow.value = detailText
        }
        UniversalOnboardingEngine.syncSessionFromPairingTimeline(
            timelineStage = stage,
            resolvedTitle = resolvedTitle,
            detailText = detailText
        )
        if (stage == PairingTimelineStage.IDLE) {
            activeTimelineTargetKeyFlow.value = ""
            activePairingStageTitleFlow.value = PairingTimelineStage.IDLE.title
            tuyaPairingTimelineHistoryFlow.value = emptyList()
            return
        }
        val currentList = tuyaPairingTimelineHistoryFlow.value.toMutableList()
        val existingIdx = currentList.indexOfFirst { it.stage == stage }
        val entry = PairingTimelineEntry(
            stage = stage,
            detail = detailText,
            timestampEpochMs = System.currentTimeMillis(),
            title = resolvedTitle
        )
        if (existingIdx >= 0) {
            currentList[existingIdx] = entry
        } else {
            currentList.add(entry)
        }
        tuyaPairingTimelineHistoryFlow.value = currentList

        if (stage == PairingTimelineStage.COMPLETED) {
            timelineAutoHideJob = bridgeScope.launch {
                kotlinx.coroutines.delay(1500L)
                tuyaPairingStageFlow.value = PairingTimelineStage.IDLE
                activePairingStageTitleFlow.value = PairingTimelineStage.IDLE.title
                activeTimelineTargetKeyFlow.value = ""
                tuyaPairingTimelineHistoryFlow.value = emptyList()
            }
        }
    }

    // Live All-Protocol BLE & Wi-Fi SoftAP Discoverable Devices State
    val discoveredBleAndApDevicesFlow = MutableStateFlow<List<DiscoveredLanDevice>>(emptyList())

    // Live Automation Execution & Error Diagnostics Log State
    val automationLogsFlow = MutableStateFlow<List<AutomationLogEntry>>(emptyList())

    // Digital Home Graph, Security Center, Telemetry, Self-Healing, DVR & Audit Flows
    val digitalHomeGraphFlow = MutableStateFlow(DigitalHomeGraphSnapshot())
    val securityCenterModeFlow = MutableStateFlow(SecurityCenterMode.HOME)
    val telemetryHistoryFlow = MutableStateFlow<List<TelemetryDataPoint>>(emptyList())
    val selfHealingLogsFlow = MutableStateFlow<List<SelfHealingActionLog>>(emptyList())
    val dvrSnapshotsFlow = MutableStateFlow<List<DvrEventSnapshot>>(emptyList())
    val securityAuditLogsFlow = MutableStateFlow<List<SecurityAuditLogEntry>>(emptyList())
    val suggestedAutomationsFlow = MutableStateFlow<List<SuggestedAutomationDraft>>(emptyList())

    // Stage 4 & 6 Front-Door Coalescing Intent Queue (Offline & Degraded Intent Replay)
    val shadowQueueFlow = MutableStateFlow<List<DigitalTwinShadowIntent>>(emptyList())

    // Stage 4 Append-Only Causal Event Sourcing Log
    private val deviceEventsLock = Any()
    val deviceEventsFlow = MutableStateFlow<List<DeviceEvent>>(emptyList())

    // Stage 5 & 6 Per-Device Observability, Provider Health, Account Health & Failover Policies
    private val deviceObservabilityMap = ConcurrentHashMap<String, DeviceObservabilityRecord>()
    val deviceObservabilityFlow = MutableStateFlow(deviceObservabilityMap.toMap())

    private val providerHealthMap = ConcurrentHashMap<String, ProviderHealthStatus>().apply {
        put("TUYA_LAN_6668", ProviderHealthStatus("TUYA_LAN_6668", "Tuya LAN (AES-6668)", 98.5f, 11L))
        put("TUYA_CLOUD", ProviderHealthStatus("TUYA_CLOUD", "Tuya Cloud OpenAPI", 95.0f, 64L))
        put("GOOGLE_CAST_TLS", ProviderHealthStatus("GOOGLE_CAST_TLS", "Google Cast (TLS 8009)", 99.2f, 14L))
        put("NEST_CLOUD", ProviderHealthStatus("NEST_CLOUD", "Google Nest (SDM / Web)", 96.4f, 48L))
        put("MATTER_FABRIC", ProviderHealthStatus("MATTER_FABRIC", "Matter Local Fabric", 98.8f, 12L))
        put("KASA_KLAP_LAN", ProviderHealthStatus("KASA_KLAP_LAN", "TP-Link Tapo / Kasa LAN", 98.2f, 15L))
        put("SONOFF_LAN_8081", ProviderHealthStatus("SONOFF_LAN_8081", "Sonoff / eWeLink LAN", 97.6f, 18L))
        put("SHELLY_TASMOTA_HTTP", ProviderHealthStatus("SHELLY_TASMOTA_HTTP", "Shelly / Tasmota HTTP", 99.0f, 10L))
        put("LG_WEBOS_SSAP", ProviderHealthStatus("LG_WEBOS_SSAP", "LG webOS SSAP (3001)", 98.0f, 13L))
        put("IMOU_RTSP_CLOUD", ProviderHealthStatus("IMOU_RTSP_CLOUD", "Imou RTSP / Cloud HLS", 96.8f, 28L))
        put("EZVIZ_RTSP_CLOUD", ProviderHealthStatus("EZVIZ_RTSP_CLOUD", "EZVIZ RTSP / Cloud HLS", 96.2f, 32L))
        put("HOME_ASSISTANT_BRIDGE", ProviderHealthStatus("HOME_ASSISTANT_BRIDGE", "Home Assistant Aggregator", 99.1f, 16L))
        put("MQTT_LOCAL", ProviderHealthStatus("MQTT_LOCAL", "MQTT / Zigbee2MQTT / Frigate", 99.0f, 9L))
        put("SMARTTHINGS_CLOUD", ProviderHealthStatus("SMARTTHINGS_CLOUD", "SmartThings Aggregator", 96.5f, 55L))
    }
    val providerHealthFlow = MutableStateFlow(providerHealthMap.toMap())

    private val accountHealthMap = ConcurrentHashMap<String, AccountHealthRecord>().apply {
        put("TUYA", AccountHealthRecord("TUYA", "Tuya / Smart Life"))
        put("NEST", AccountHealthRecord("NEST", "Google Nest"))
        put("EZVIZ", AccountHealthRecord("EZVIZ", "EZVIZ Security"))
        put("IMOU", AccountHealthRecord("IMOU", "Imou Cloud"))
        put("TPLINK", AccountHealthRecord("TPLINK", "TP-Link Tapo / Kasa"))
        put("EWELINK", AccountHealthRecord("EWELINK", "eWeLink / Sonoff"))
        put("JAGUAR", AccountHealthRecord("JAGUAR", "Jaguar InControl"))
        put("MERCEDES", AccountHealthRecord("MERCEDES", "Mercedes me"))
        put("TESLA", AccountHealthRecord("TESLA", "Tesla Fleet / Wall Connector"))
        put("SMARTTHINGS", AccountHealthRecord("SMARTTHINGS", "Samsung SmartThings"))
        put("HOME_ASSISTANT", AccountHealthRecord("HOME_ASSISTANT", "Home Assistant Hub"))
        put("SPOTIFY", AccountHealthRecord("SPOTIFY", "Spotify Connect"))
    }
    val accountHealthFlow = MutableStateFlow(accountHealthMap.toMap())

    private val failoverPolicyMap = ConcurrentHashMap<String, ProviderFailoverPolicy>()
    val failoverPoliciesFlow = MutableStateFlow(failoverPolicyMap.toMap())

    private val kpiLock = Any()
    @Volatile
    private var falsePositiveContradictionCount: Int = 0
    val reliabilityKpisFlow = MutableStateFlow(ClaraReliabilityKpis())

    // Stage 4 Multi-Tablet Mesh Consensus (Lowest lexicographical installation UID = Primary Authority)
    val isPrimaryAutomationAuthorityFlow = MutableStateFlow(true)
    val primaryAuthorityUidFlow = MutableStateFlow("")
    private val meshPeerUidByEndpoint = ConcurrentHashMap<String, String>()

    // Standardized Platform Event Bus (replay = 0 to prevent stale edge-trigger replays; DROP_OLDEST for backpressure safety)
    private val _eventsFlow = MutableSharedFlow<JarvisPlatformEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val eventsFlow: SharedFlow<JarvisPlatformEvent> = _eventsFlow.asSharedFlow()
    val recentEventsFlow = MutableStateFlow<List<JarvisPlatformEvent>>(emptyList())

    // Integration Diagnostics & Latency Health Registry
    private val integrationHealthMap = ConcurrentHashMap<String, IntegrationHealthRecord>().apply {
        put(
            "TUYA_LAN",
            IntegrationHealthRecord(
                integrationKey = "TUYA_LAN",
                displayName = "Tuya / SmartKnight / Lytmi LAN",
                isOnline = true,
                tokenValid = true,
                averageLatencyMs = 14L,
                statusMessage = "Local AES-6668 & SDK Ready"
            )
        )
        put(
            "GOOGLE_NEST",
            IntegrationHealthRecord(
                integrationKey = "GOOGLE_NEST",
                displayName = "Google Nest SDM & Cast",
                isOnline = true,
                tokenValid = true,
                averageLatencyMs = 42L,
                statusMessage = "Cast TLS 8009 & SDM Ready"
            )
        )
        put(
            "VEHICLES",
            IntegrationHealthRecord(
                integrationKey = "VEHICLES",
                displayName = "Jaguar InControl & Mercedes me",
                isOnline = true,
                tokenValid = true,
                averageLatencyMs = 68L,
                statusMessage = "Vehicle Telemetry Active"
            )
        )
        put(
            "CAMERAS_RTSP",
            IntegrationHealthRecord(
                integrationKey = "CAMERAS_RTSP",
                displayName = "Imou / EZVIZ Cameras & DVR",
                isOnline = true,
                tokenValid = true,
                averageLatencyMs = 22L,
                statusMessage = "RTSP & Snapshot Engine Ready"
            )
        )
        put(
            "MULTI_PROTOCOL",
            IntegrationHealthRecord(
                integrationKey = "MULTI_PROTOCOL",
                displayName = "Matter / Tapo / Shelly / MQTT / HA",
                isOnline = true,
                tokenValid = true,
                averageLatencyMs = 19L,
                statusMessage = "Local Multi-Protocol Active"
            )
        )
    }
    val integrationHealthFlow = MutableStateFlow(integrationHealthMap.toMap())

    // Embedded Local Hub Server & mDNS Mesh State
    val isLocalHubServerRunningFlow = MutableStateFlow(false)
    val discoveredMeshPeersFlow = MutableStateFlow<List<String>>(emptyList())
    private val meshPeersSet = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var activeCustomMp3Uri: String = ""

    @Volatile
    private var serverJob: Job? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var lastRegisteredMdnsIp: String = ""

    @Volatile
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    @Volatile
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var remoteCommandHandler: ((String, JSONObject) -> Unit)? = null

    fun setScanningWifiNetworks(isScanning: Boolean) {
        isScanningWifiNetworksFlow.value = isScanning
    }

    fun updateAvailableWifiNetworks(networks: List<ScannedWifiNetwork>) {
        availableWifiNetworksFlow.value = networks
    }

    fun clearDoorbellEvent() {
        doorbellRingFlow.value = null
    }

    // -----------------------------------------------------------------------
    // 1. PLATFORM EVENT BUS, CAUSAL EVENT SOURCING & COALESCING INTENT QUEUE
    // -----------------------------------------------------------------------
    fun emitPlatformEvent(event: JarvisPlatformEvent) {
        synchronized(recentEventsLock) {
            val updatedRecent = ArrayList<JarvisPlatformEvent>(80).apply {
                add(event)
                addAll(recentEventsFlow.value.take(79))
            }
            recentEventsFlow.value = updatedRecent
        }
        _eventsFlow.tryEmit(event)
    }

    /**
     * Appends a causal [DeviceEvent] to the Event Sourcing log so every tile and the
     * Device Guardian Dashboard can explain exactly who/what changed a device and when.
     */
    fun appendDeviceEvent(event: DeviceEvent) {
        synchronized(deviceEventsLock) {
            val updated = ArrayList<DeviceEvent>(250).apply {
                add(event)
                addAll(deviceEventsFlow.value.take(249))
            }
            deviceEventsFlow.value = updated
        }

        if (event.deviceId.isNotBlank()) {
            val canonGuid = DeviceLifecycleRecorder.resolveCanonicalGuid(event.deviceId)
            DeviceBlackBoxRecorder.recordStateSync(
                deviceId = canonGuid,
                deviceName = event.deviceTitle,
                provider = event.source,
                observedState = event.newStateSummary,
                rawPayload = event.authorityTierLabel
            )
        }
    }

    fun latestEventForDevice(deviceId: String): DeviceEvent? {
        if (deviceId.isBlank()) return null
        return deviceEventsFlow.value.firstOrNull { it.deviceId == deviceId }
    }

    // -----------------------------------------------------------------------
    // PASS 07: SPLIT POLL HEALTH VS COMMAND LATENCY KPIS & CIRCUIT REJECTIONS
    // (Findings F-05, F-07, F-11, F-16)
    // -----------------------------------------------------------------------

    val pollAverageLatencyMsFlow = MutableStateFlow(0L)
    val pollSuccessRatePctFlow = MutableStateFlow(100f)
    val totalPollSamplesFlow = MutableStateFlow(0L)
    val circuitBreakerRejectionsFlow = MutableStateFlow(0L)
    val jsonCorruptionRecoveriesFlow = MutableStateFlow(0L)

    /**
     * Records background poll health strictly in its own KPI bucket (F-05)
     * so background HTTP/LAN polling never skews user command latency metrics.
     */
    fun recordPollHealth(
        providerKey: String,
        latencyMs: Long,
        succeeded: Boolean,
        statusDetail: String = ""
    ) {
        val clampedLatency = latencyMs.coerceAtLeast(1L)
        synchronized(kpiLock) {
            val prevSamples = totalPollSamplesFlow.value
            val newSamples = prevSamples + 1L
            totalPollSamplesFlow.value = newSamples
            pollAverageLatencyMsFlow.value = if (prevSamples <= 0L) {
                clampedLatency
            } else {
                ((pollAverageLatencyMsFlow.value * 4L) + clampedLatency) / 5L
            }
            val sampleSuccessPct = if (succeeded) 100f else 0f
            pollSuccessRatePctFlow.value = if (prevSamples <= 0L) {
                sampleSuccessPct
            } else {
                (((pollSuccessRatePctFlow.value * 9f) + sampleSuccessPct) / 10f).coerceIn(0f, 100f)
            }
        }
        val cleanProvider = providerKey.trim().uppercase()
        providerHealthMap[cleanProvider]?.let { existing ->
            val updatedRate = if (succeeded) {
                ((existing.rollingSuccessRatePct * 9f) + 100f) / 10f
            } else {
                (existing.rollingSuccessRatePct * 9f) / 10f
            }
            providerHealthMap[cleanProvider] = existing.copy(
                rollingSuccessRatePct = updatedRate.coerceIn(0f, 100f),
                averageLatencyMs = ((existing.averageLatencyMs * 3L) + clampedLatency) / 4L
            )
            providerHealthFlow.value = providerHealthMap.toMap()
        }
    }

    /**
     * Records when a provider circuit breaker rejects a poll or command (F-05)
     * instead of exiting silently.
     */
    fun recordCircuitBreakerRejection(
        providerKey: String,
        remainingOpenMs: Long,
        targetTitle: String = providerKey
    ) {
        synchronized(kpiLock) {
            circuitBreakerRejectionsFlow.value = circuitBreakerRejectionsFlow.value + 1L
        }
        emitPlatformEvent(
            JarvisPlatformEvent(
                category = JarvisEventCategory.SELF_HEALING,
                sourceDeviceTitle = targetTitle,
                eventType = "CIRCUIT_BREAKER_OPEN_REJECT",
                summary = "$providerKey circuit breaker open (${remainingOpenMs / 1000L}s remaining)"
            )
        )
    }

    /**
     * Logs JSON persistence deserialization corruption and tracks automatic snapshot recovery (F-16).
     */
    fun recordDeserializationCorruption(
        storeKey: String,
        errorMessage: String,
        restoredFromSnapshot: Boolean
    ) {
        synchronized(kpiLock) {
            jsonCorruptionRecoveriesFlow.value = jsonCorruptionRecoveriesFlow.value + 1L
        }
        emitPlatformEvent(
            JarvisPlatformEvent(
                category = JarvisEventCategory.SELF_HEALING,
                sourceDeviceTitle = "Persistence Guardian ($storeKey)",
                eventType = if (restoredFromSnapshot) "SNAPSHOT_RESTORED" else "JSON_CORRUPTION_DETECTED",
                summary = if (restoredFromSnapshot) {
                    "Recovered $storeKey from verified backup snapshot after error: $errorMessage"
                } else {
                    "Deserialization error in $storeKey: $errorMessage"
                }
            )
        )
    }

    /**
     * Drains non-expired queued shadow intents for a recovered device so RecoveryEngine (F-07, F-11)
     * can replay them deterministically.
     */
    fun drainReplayableShadowIntents(tileId: String? = null): List<DigitalTwinShadowIntent> {
        synchronized(shadowQueueLock) {
            val now = System.currentTimeMillis()
            val active = shadowQueueFlow.value.filter { it.expiresAtEpochMs > now }
            val (matching, remaining) = if (tileId.isNullOrBlank()) {
                active to emptyList()
            } else {
                active.partition { it.tileId == tileId }
            }
            shadowQueueFlow.value = remaining
            return matching
        }
    }

    /**
     * Front-Door Intent Queue with Rapid-Tap Coalescing & Outbound Authority Arbitration:
     * Coalesces rapid commands targeting the same `(tileId, capabilityKey)` unless an existing
     * queued intent has a higher-priority [IntentAuthorityTier].
     */
    fun enqueueShadowIntent(intent: DigitalTwinShadowIntent) {
        synchronized(shadowQueueLock) {
            val now = System.currentTimeMillis()
            val currentActive = shadowQueueFlow.value.filter { it.expiresAtEpochMs > now }
            val existingSameKey = currentActive.firstOrNull {
                it.coalesceKey == intent.coalesceKey || (it.tileId == intent.tileId && intent.capabilityKey.isBlank())
            }
            if (existingSameKey != null &&
                existingSameKey.intentAuthorityTier.priorityRank < intent.intentAuthorityTier.priorityRank
            ) {
                // Higher-priority intent already queued (e.g. SAFETY_CRITICAL or PHYSICAL_DEVICE)
                return
            }
            val filtered = currentActive.filterNot {
                it.coalesceKey == intent.coalesceKey || (it.tileId == intent.tileId && intent.capabilityKey.isBlank())
            }.toMutableList()
            filtered.add(0, intent)
            shadowQueueFlow.value = filtered.take(80)
        }
        appendDeviceEvent(
            DeviceEvent(
                deviceId = intent.tileId,
                deviceTitle = intent.tileTitle,
                eventType = DeviceEventType.INTENT_QUEUED,
                source = intent.intentAuthorityTier.label,
                actorName = intent.actorName,
                newStateSummary = "Queued (${intent.intentAuthorityTier.label}): ${if (intent.desiredIsActive) "ON" else "OFF"}"
            )
        )
    }

    fun hasQueuedIntentsForDevice(tileId: String): Boolean {
        if (tileId.isBlank()) return false
        val now = System.currentTimeMillis()
        return shadowQueueFlow.value.any { it.tileId == tileId && it.expiresAtEpochMs > now }
    }

    fun consumeShadowIntentsForDevice(tileId: String): List<DigitalTwinShadowIntent> {
        if (tileId.isBlank()) return emptyList()
        synchronized(shadowQueueLock) {
            val now = System.currentTimeMillis()
            val current = shadowQueueFlow.value.filter { it.expiresAtEpochMs > now }
            val matching = current.filter { it.tileId == tileId }
            if (matching.isNotEmpty()) {
                shadowQueueFlow.value = current.filterNot { it.tileId == tileId }
            }
            return matching
        }
    }

    // -----------------------------------------------------------------------
    // 1B. STAGE 5 OBSERVABILITY, PROVIDER HEALTH & RELIABILITY KPI ENGINE
    // -----------------------------------------------------------------------
    fun registerOrUpdateFailoverPolicy(policy: ProviderFailoverPolicy) {
        if (policy.deviceId.isBlank()) return
        failoverPolicyMap[policy.deviceId] = policy
        failoverPoliciesFlow.value = failoverPolicyMap.toMap()
    }

    fun getFailoverPolicyForDevice(deviceId: String): ProviderFailoverPolicy? {
        if (deviceId.isBlank()) return null
        return failoverPolicyMap[deviceId]
    }

    fun removeDeviceObservability(deviceId: String) {
        if (deviceId.isBlank()) return
        deviceObservabilityMap.remove(deviceId)
        deviceObservabilityFlow.value = deviceObservabilityMap.toMap()
        failoverPolicyMap.remove(deviceId)
        failoverPoliciesFlow.value = failoverPolicyMap.toMap()
    }

    fun clearAllDeviceObservabilityAndCaches() {
        synchronized(kpiLock) {
            falsePositiveContradictionCount = 0
        }
        deviceObservabilityMap.clear()
        deviceObservabilityFlow.value = emptyMap()
        failoverPolicyMap.clear()
        failoverPoliciesFlow.value = emptyMap()
        synchronized(shadowQueueLock) {
            shadowQueueFlow.value = emptyList()
        }
        synchronized(deviceEventsLock) {
            deviceEventsFlow.value = emptyList()
        }
        discoveredCastDevicesFlow.value = emptyList()
        castSpeakerGroupsFlow.value = emptyList()
        discoveredBleAndApDevicesFlow.value = emptyList()
        updatePairingTimeline(PairingTimelineStage.IDLE)
    }

    fun promoteHealthyFallbackProvider(
        tile: SmartTile,
        explicitFallbacks: List<String> = tile.fallbackProviderIds
    ): String? {
        val policy = failoverPolicyMap[tile.id]
        val primary = policy?.primaryProviderId?.ifBlank { tile.activeProviderId } ?: tile.activeProviderId
        val candidates = (policy?.orderedFallbackProviderIds.orEmpty() + explicitFallbacks + listOf("HOME_ASSISTANT_BRIDGE", "MQTT_LOCAL"))
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(tile.activeProviderId, ignoreCase = true) }
            .distinct()

        val chosen = candidates.firstOrNull { candidateId ->
            val health = providerHealthMap[candidateId.uppercase(Locale.US)]
            health == null || health.isHealthy
        } ?: candidates.firstOrNull() ?: return null

        val updatedPolicy = (policy ?: ProviderFailoverPolicy(
            deviceId = tile.id,
            primaryProviderId = primary.ifBlank { tile.type.brandName },
            orderedFallbackProviderIds = candidates,
            parentNetworkNodeId = tile.parentNetworkNodeId
        )).copy(
            activeProviderId = chosen,
            lastFailoverEpochMs = System.currentTimeMillis()
        )
        failoverPolicyMap[tile.id] = updatedPolicy
        failoverPoliciesFlow.value = failoverPolicyMap.toMap()
        return chosen
    }

    fun restorePrimaryProviderIfHealthy(tile: SmartTile): String? {
        val policy = failoverPolicyMap[tile.id] ?: return null
        if (policy.primaryProviderId.isBlank() || policy.activeProviderId == policy.primaryProviderId) {
            return null
        }
        val primaryHealth = providerHealthMap[policy.primaryProviderId.uppercase(Locale.US)]
        if (primaryHealth != null && !primaryHealth.isHealthy) return null
        val restored = policy.copy(
            activeProviderId = policy.primaryProviderId,
            lastFailoverEpochMs = System.currentTimeMillis()
        )
        failoverPolicyMap[tile.id] = restored
        failoverPoliciesFlow.value = failoverPolicyMap.toMap()
        return restored.activeProviderId
    }

    fun seedDeviceObservabilityForTiles(tiles: List<SmartTile>) {
        val now = System.currentTimeMillis()
        var changed = false
        for (tile in tiles) {
            if (!tile.isConfiguredAndLinked ||
                tile.serviceClass != null ||
                !tile.deviceSubtype.isHardwareSubtype ||
                tile.type in setOf(TileType.CLOCK_WEATHER, TileType.GROUP_CARD, TileType.PHOTO_SLIDESHOW, TileType.MACRO_TILE, TileType.ENERGY_MONITOR, TileType.SECURITY_CENTER)
            ) continue
            val canonGuid = DeviceLifecycleRecorder.registerTileAliases(tile)
            DeviceLifecycleRecorder.seedKnownStateIfAbsent(canonGuid, tile.effectiveLifecycleState.name)
            val existing = deviceObservabilityMap[tile.id]
            if (existing == null) {
                deviceObservabilityMap[tile.id] = DeviceObservabilityRecord(
                    deviceId = tile.id,
                    deviceTitle = tile.title,
                    deviceClass = tile.deviceClass,
                    lifecycleState = tile.effectiveLifecycleState,
                    lastSeenEpochMs = if (tile.isOnline) now else (tile.lastConfirmedEpochMs.takeIf { it > 0L } ?: now),
                    activeProviderId = tile.activeProviderId.ifBlank { tile.type.brandName },
                    fallbackProviderIds = tile.fallbackProviderIds,
                    currentIp = tile.ipAddress.substringBefore(":"),
                    currentMac = tile.macAddress,
                    availabilityPct = if (tile.isOnline) 100f else 85f,
                    commandSuccessRatePct = 100f,
                    canonicalGuid = canonGuid
                )
                changed = true
            }
        }
        if (changed) {
            deviceObservabilityFlow.value = deviceObservabilityMap.toMap()
        }
    }

    fun incrementDeviceRecoveryAttempt(tile: SmartTile) {
        val canonGuid = DeviceLifecycleRecorder.registerTileAliases(tile)
        val existing = deviceObservabilityMap[tile.id] ?: DeviceObservabilityRecord(
            deviceId = tile.id,
            deviceTitle = tile.title,
            deviceClass = tile.deviceClass,
            activeProviderId = tile.activeProviderId.ifBlank { tile.type.brandName },
            canonicalGuid = canonGuid
        )
        deviceObservabilityMap[tile.id] = existing.copy(
            recoveryAttempts = existing.recoveryAttempts + 1,
            lifecycleState = DeviceLifecycleState.RECOVERY,
            canonicalGuid = existing.canonicalGuid.ifBlank { canonGuid }
        )
        deviceObservabilityFlow.value = deviceObservabilityMap.toMap()
    }

    fun updateAccountHealth(
        accountKey: String,
        displayName: String = accountKey,
        oauthStatus: AccountAuthStatus,
        tokenExpiresAtEpochMs: Long = 0L,
        lastRefreshEpochMs: Long = System.currentTimeMillis(),
        mfaRequired: Boolean = false,
        permissionsRevoked: Boolean = false,
        statusDetail: String = ""
    ) {
        val key = accountKey.uppercase(Locale.US)
        val existing = accountHealthMap[key]
        val updated = AccountHealthRecord(
            accountKey = key,
            displayName = existing?.displayName ?: displayName,
            oauthStatus = oauthStatus,
            tokenExpiresAtEpochMs = if (tokenExpiresAtEpochMs > 0L) tokenExpiresAtEpochMs else (existing?.tokenExpiresAtEpochMs ?: 0L),
            lastRefreshEpochMs = if (lastRefreshEpochMs > 0L) lastRefreshEpochMs else (existing?.lastRefreshEpochMs ?: 0L),
            mfaRequired = mfaRequired,
            permissionsRevoked = permissionsRevoked,
            statusDetail = statusDetail.ifBlank { oauthStatus.label }
        )
        accountHealthMap[key] = updated
        accountHealthFlow.value = accountHealthMap.toMap()
    }

    fun syncAccountHealthFromBridge(context: Context, bridge: BridgeSettings) {
        val now = System.currentTimeMillis()
        // 1. Tuya / Smart Life
        val tuyaLinked = bridge.tuyaAccessId.isNotBlank() && (bridge.tuyaAccessSecret.isNotBlank() || TuyaApiClient.hasInitializedNativeHomeInSession)
        val tuyaExp = TuyaApiClient.tokenExpiryMs
        updateAccountHealth(
            accountKey = "TUYA",
            displayName = "Tuya / Smart Life",
            oauthStatus = when {
                tuyaExp > now && (tuyaExp - now) < 10 * 60_000L -> AccountAuthStatus.EXPIRING_SOON
                tuyaExp > now || TuyaApiClient.hasInitializedNativeHomeInSession -> AccountAuthStatus.VALID
                tuyaLinked -> AccountAuthStatus.VALID
                else -> AccountAuthStatus.NOT_LINKED
            },
            tokenExpiresAtEpochMs = if (tuyaExp > 0L) tuyaExp else now + 7000_000L,
            statusDetail = if (TuyaApiClient.hasInitializedNativeHomeInSession) "Native Home & LAN 6668 Active" else "Cloud / LAN Ready"
        )

        // 2. Google Nest (Respect live provider & token health rather than assuming 100% valid)
        val nestLinked = NestSdmApiClient.isNestConfiguredAndLinked(bridge)
        val nestProviderHealth = providerHealthMap["NEST_CLOUD"]
        val nestIntegrationHealth = integrationHealthMap["GOOGLE_NEST"]
        val nestTokenInvalid = (nestProviderHealth?.consecutiveAuthFailures ?: 0) > 0 ||
                (nestIntegrationHealth?.tokenValid == false)
        val nestDegraded = (nestProviderHealth?.consecutiveFailures ?: 0) >= 2 ||
                (nestIntegrationHealth?.consecutiveFailures ?: 0) >= 2
        updateAccountHealth(
            accountKey = "NEST",
            displayName = "Google Nest",
            oauthStatus = when {
                !nestLinked -> AccountAuthStatus.NOT_LINKED
                nestTokenInvalid -> AccountAuthStatus.EXPIRED
                nestDegraded -> AccountAuthStatus.EXPIRING_SOON
                else -> AccountAuthStatus.VALID
            },
            tokenExpiresAtEpochMs = if (nestLinked && !nestTokenInvalid) now + 3000_000L else 0L,
            statusDetail = when {
                !nestLinked -> "Not linked"
                nestTokenInvalid -> "Session expired — background refresh required"
                nestDegraded -> "Connection degraded (${nestProviderHealth?.consecutiveFailures ?: 2} missed polls)"
                else -> "SDM / Web Session Verified"
            }
        )

        // 3. EZVIZ
        val ezLinked = EzvizCloudApiClient.hasActiveSessionOrCredentials(context, bridge)
        updateAccountHealth(
            accountKey = "EZVIZ",
            displayName = "EZVIZ Security",
            oauthStatus = if (ezLinked) AccountAuthStatus.VALID else AccountAuthStatus.NOT_LINKED,
            tokenExpiresAtEpochMs = if (ezLinked) now + 86400_000L else 0L,
            statusDetail = if (ezLinked) "Session & PIN Vault Active" else "Not linked"
        )

        // 4. Imou
        val imouLinked = bridge.imouAppId.isNotBlank() && bridge.imouAppSecret.isNotBlank()
        updateAccountHealth(
            accountKey = "IMOU",
            displayName = "Imou Cloud",
            oauthStatus = if (imouLinked) AccountAuthStatus.VALID else AccountAuthStatus.NOT_LINKED,
            tokenExpiresAtEpochMs = if (imouLinked) now + 86400_000L else 0L,
            statusDetail = if (imouLinked) "OpenAPI & Local RTSP Active" else "Local RTSP Only"
        )

        // 5. TP-Link Tapo / Kasa
        val tplinkLinked = bridge.tplinkEmail.isNotBlank() || TpLinkTapoKasaClient.hasSavedCredentials(context)
        updateAccountHealth(
            accountKey = "TPLINK",
            displayName = "TP-Link Tapo / Kasa",
            oauthStatus = if (tplinkLinked) AccountAuthStatus.VALID else AccountAuthStatus.NOT_LINKED,
            statusDetail = if (tplinkLinked) "KLAP v1/v2 & Port 9999 Ready" else "Kasa Port 9999 Only"
        )

        // 6. eWeLink / Sonoff
        val sonoffLinked = bridge.ewelinkEmail.isNotBlank() || EWeLinkSonoffClient.getSavedEWeLinkEmail(context).isNotBlank()
        updateAccountHealth(
            accountKey = "EWELINK",
            displayName = "eWeLink / Sonoff",
            oauthStatus = if (sonoffLinked) AccountAuthStatus.VALID else AccountAuthStatus.NOT_LINKED,
            statusDetail = if (sonoffLinked) "OAuth v2 & AES-8081 Ready" else "mDNS / DIY Mode Only"
        )

        // 7. Jaguar InControl
        val jagLinked = bridge.jaguarEmail.isNotBlank() && bridge.jaguarPassword.isNotBlank()
        updateAccountHealth(
            accountKey = "JAGUAR",
            displayName = "Jaguar InControl",
            oauthStatus = if (jagLinked) AccountAuthStatus.VALID else AccountAuthStatus.NOT_LINKED,
            statusDetail = if (jagLinked) "IF9 Telemetry Linked (${bridge.jaguarVin.ifBlank { "Auto VIN" }})" else "Not linked"
        )

        // 8. Mercedes me
        val mbLinked = bridge.mercedesEmail.isNotBlank() && bridge.mercedesPassword.isNotBlank()
        updateAccountHealth(
            accountKey = "MERCEDES",
            displayName = "Mercedes me",
            oauthStatus = if (mbLinked) AccountAuthStatus.VALID else AccountAuthStatus.NOT_LINKED,
            statusDetail = if (mbLinked) "BFF / OIDC Telemetry Linked" else "Not linked"
        )

        // 9. Tesla
        val teslaLinked = bridge.teslaAccessToken.isNotBlank() || bridge.teslaWallConnectorIp.isNotBlank()
        updateAccountHealth(
            accountKey = "TESLA",
            displayName = "Tesla Fleet / Wall Connector",
            oauthStatus = if (teslaLinked) AccountAuthStatus.VALID else AccountAuthStatus.NOT_LINKED,
            statusDetail = if (teslaLinked) "Vehicle / Wall Connector Active" else "Not linked"
        )

        // 10. SmartThings
        val stLinked = bridge.smartThingsToken.isNotBlank()
        updateAccountHealth(
            accountKey = "SMARTTHINGS",
            displayName = "Samsung SmartThings",
            oauthStatus = if (stLinked) AccountAuthStatus.VALID else AccountAuthStatus.NOT_LINKED,
            statusDetail = if (stLinked) "Cloud Aggregator Active" else "Not linked"
        )

        // 11. Home Assistant
        val haLinked = bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()
        updateAccountHealth(
            accountKey = "HOME_ASSISTANT",
            displayName = "Home Assistant Hub",
            oauthStatus = if (haLinked) AccountAuthStatus.VALID else AccountAuthStatus.NOT_LINKED,
            statusDetail = if (haLinked) "REST + WebSocket Push Active" else "Not linked"
        )

        // 12. Spotify
        val spLinked = NativeMusicCatalogClient.isSpotifySignedIn(context)
        updateAccountHealth(
            accountKey = "SPOTIFY",
            displayName = "Spotify Connect",
            oauthStatus = if (spLinked) AccountAuthStatus.VALID else AccountAuthStatus.NOT_LINKED,
            statusDetail = if (spLinked) "Web Playback & Catalog Synced" else "Not linked"
        )
    }

    fun recordDeviceCommandResult(
        tile: SmartTile,
        providerId: String,
        latencyMs: Long,
        succeeded: Boolean,
        failureReason: String = ""
    ) {
        val now = System.currentTimeMillis()
        val existing = deviceObservabilityMap[tile.id]
        val totalSent = (existing?.totalCommandsSent ?: 0) + 1
        val totalOk = (existing?.totalCommandsSucceeded ?: 0) + (if (succeeded) 1 else 0)
        val totalFail = (existing?.failureCount ?: 0) + (if (succeeded) 0 else 1)
        val consecFail = if (succeeded) 0 else (existing?.consecutiveFailures ?: 0) + 1
        val successPct = ((totalOk.toFloat() / totalSent.coerceAtLeast(1).toFloat()) * 100f).coerceIn(0f, 100f)
        val prevAvail = existing?.availabilityPct ?: 100f
        val nextAvail = if (succeeded) {
            ((prevAvail * 0.85f) + 15.0f).coerceIn(0f, 100f)
        } else {
            (prevAvail * 0.85f).coerceIn(0f, 100f)
        }
        val clampedLat = latencyMs.coerceIn(1L, 15_000L)
        val avgLat = if (existing != null && existing.averageLatencyMs > 0L) {
            ((existing.averageLatencyMs * 0.65) + (clampedLat * 0.35)).toLong()
        } else {
            clampedLat
        }
        val nextLifecycle = when {
            succeeded -> DeviceLifecycleState.ONLINE
            existing?.currentRecoveryStage in 0..6 -> DeviceLifecycleState.RECOVERY
            consecFail >= 3 -> DeviceLifecycleState.OFFLINE
            else -> DeviceLifecycleState.RECOVERY
        }
        val updatedObs = DeviceObservabilityRecord(
            deviceId = tile.id,
            deviceTitle = tile.title,
            deviceClass = tile.deviceClass,
            lifecycleState = nextLifecycle,
            lastSeenEpochMs = if (succeeded) now else (existing?.lastSeenEpochMs ?: now),
            lastSuccessfulCommandMs = if (succeeded) now else (existing?.lastSuccessfulCommandMs ?: 0L),
            lastFailedCommandMs = if (!succeeded) now else (existing?.lastFailedCommandMs ?: 0L),
            currentRecoveryStage = if (succeeded) -1 else (existing?.currentRecoveryStage ?: 0),
            activeProviderId = providerId.ifBlank { existing?.activeProviderId ?: tile.type.brandName },
            fallbackProviderIds = tile.fallbackProviderIds,
            currentIp = tile.ipAddress.substringBefore(":"),
            currentMac = tile.macAddress.ifBlank { existing?.currentMac.orEmpty() },
            availabilityPct = nextAvail,
            commandSuccessRatePct = successPct,
            totalCommandsSent = totalSent,
            totalCommandsSucceeded = totalOk,
            failureCount = totalFail,
            consecutiveFailures = consecFail,
            recoveryAttempts = existing?.recoveryAttempts ?: 0,
            averageLatencyMs = avgLat,
            lastFailureReason = if (succeeded) "" else failureReason.ifBlank { existing?.lastFailureReason.orEmpty() },
            lastRecoveryDurationMs = existing?.lastRecoveryDurationMs ?: 0L
        )
        deviceObservabilityMap[tile.id] = updatedObs
        deviceObservabilityFlow.value = deviceObservabilityMap.toMap()

        val isAuthErr = failureReason.contains("401") ||
                failureReason.contains("403") ||
                failureReason.contains("token", ignoreCase = true) ||
                failureReason.contains("revoked", ignoreCase = true)
        val isMfaErr = failureReason.contains("6002") ||
                failureReason.contains("1061") ||
                failureReason.contains("OTP", ignoreCase = true) ||
                failureReason.contains("MFA", ignoreCase = true)

        recordProviderHealthOutcome(
            providerId = providerId.ifBlank { tile.type.brandName },
            displayName = tile.type.brandName,
            latencyMs = clampedLat,
            succeeded = succeeded,
            isAuthError = isAuthErr || isMfaErr
        )

        synchronized(kpiLock) {
            val cur = reliabilityKpisFlow.value
            val nextTotalCmd = cur.totalCommandsDispatched + 1
            val nextConfirmedCmd = cur.totalCommandsConfirmed + (if (succeeded) 1 else 0)
            val cmdRate = ((nextConfirmedCmd.toFloat() / nextTotalCmd.toFloat()) * 100f).coerceIn(0f, 100f)
            val falseRate = ((falsePositiveContradictionCount.toFloat() / nextTotalCmd.coerceAtLeast(1).toFloat()) * 100f)
                .coerceIn(0f, 100f)
            reliabilityKpisFlow.value = cur.copy(
                commandSuccessRatePct = cmdRate,
                falseSuccessRatePct = falseRate,
                totalCommandsDispatched = nextTotalCmd,
                totalCommandsConfirmed = nextConfirmedCmd
            )
        }
    }

    fun recordFalseHealContradiction(tile: SmartTile, detail: String) {
        synchronized(kpiLock) {
            falsePositiveContradictionCount += 1
            val cur = reliabilityKpisFlow.value
            val denom = cur.totalCommandsDispatched.coerceAtLeast(1)
            val falseRate = ((falsePositiveContradictionCount.toFloat() / denom.toFloat()) * 100f)
                .coerceIn(0f, 100f)
            reliabilityKpisFlow.value = cur.copy(falseSuccessRatePct = falseRate)
        }
        emitPlatformEvent(
            JarvisPlatformEvent(
                category = JarvisEventCategory.SELF_HEALING,
                sourceDeviceId = tile.id,
                sourceDeviceTitle = tile.title,
                roomName = tile.roomOrSubtitle,
                eventType = "RECOVERY_RETRY_UNCONFIRMED",
                summary = detail
            )
        )
    }

    /**
     * Phase 2.3 Proactive Background Liveness Tracker:
     * Updates device observability, availability percentage, and provider health during background
     * polling so silent token expirations or dropped sockets immediately downgrade the tile's
     * health state before the user taps a button.
     */
    fun recordBackgroundPollOutcome(
        tile: SmartTile,
        providerId: String,
        succeeded: Boolean,
        latencyMs: Long = 35L,
        failureReason: String = "",
        isAuthError: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val canonGuid = DeviceLifecycleRecorder.registerTileAliases(tile)
        val existing = deviceObservabilityMap[tile.id]
        val prevLifecycle = existing?.lifecycleState ?: tile.effectiveLifecycleState
        val consecFail = if (succeeded) 0 else (existing?.consecutiveFailures ?: 0) + 1
        val prevAvail = existing?.availabilityPct ?: if (tile.isOnline) 100f else 60f
        val nextAvail = if (succeeded) {
            ((prevAvail * 0.80f) + 20.0f).coerceIn(0f, 100f)
        } else {
            (prevAvail * 0.75f).coerceIn(0f, 95f)
        }
        val nextLifecycle = when {
            succeeded -> DeviceLifecycleState.ONLINE
            isAuthError -> DeviceLifecycleState.QUARANTINED_ERROR
            consecFail >= 3 -> DeviceLifecycleState.OFFLINE
            else -> DeviceLifecycleState.RECOVERY
        }
        val effectiveTransport = providerId.ifBlank {
            existing?.activeProviderId ?: tile.transportForRecorder
        }
        val effectiveDriver = ProviderId.fromRawKey(effectiveTransport)
            .takeIf { it != ProviderId.UNKNOWN }
            ?.displayName ?: tile.vendorDriverForRecorder

        var latestSeq = existing?.lastLifecycleSequence ?: 0L
        if (prevLifecycle != nextLifecycle) {
            val pollTransition = when {
                nextLifecycle == DeviceLifecycleState.ONLINE &&
                        prevLifecycle != DeviceLifecycleState.OPERATIONAL ->
                    MandatoryLifecycleTransition.ONLINE
                nextLifecycle == DeviceLifecycleState.OFFLINE ->
                    MandatoryLifecycleTransition.OFFLINE
                nextLifecycle == DeviceLifecycleState.QUARANTINED_ERROR && isAuthError ->
                    MandatoryLifecycleTransition.AUTH_FAILED
                else -> null
            }
            if (pollTransition != null) {
                val failCat = when {
                    succeeded -> null
                    isAuthError -> FailureCategory.AUTHENTICATION_FAILURE
                    effectiveTransport.contains("CLOUD", ignoreCase = true) ||
                            effectiveTransport.contains("NEST", ignoreCase = true) -> FailureCategory.CLOUD_FAILURE
                    else -> FailureCategory.TRANSPORT_FAILURE
                }
                latestSeq = DeviceLifecycleRecorder.recordTileTransition(
                    tile = tile,
                    transition = pollTransition,
                    previousState = prevLifecycle.name,
                    newState = nextLifecycle.name,
                    activeTransport = effectiveTransport,
                    vendorDriver = effectiveDriver,
                    latencyMs = latencyMs,
                    protocolResultCode = if (succeeded) "POLL_OK" else "POLL_MISS_$consecFail",
                    failureCategory = failCat
                )
            }
        }

        val updatedObs = (existing ?: DeviceObservabilityRecord(
            deviceId = tile.id,
            deviceTitle = tile.title,
            deviceClass = tile.deviceClass
        )).copy(
            deviceTitle = tile.title,
            deviceClass = tile.deviceClass,
            lifecycleState = nextLifecycle,
            lastSeenEpochMs = if (succeeded) now else (existing?.lastSeenEpochMs ?: tile.lastConfirmedEpochMs),
            activeProviderId = effectiveTransport,
            fallbackProviderIds = tile.fallbackProviderIds,
            currentIp = tile.ipAddress.substringBefore(":"),
            currentMac = tile.macAddress.ifBlank { existing?.currentMac.orEmpty() },
            availabilityPct = nextAvail,
            consecutiveFailures = consecFail,
            lastFailureReason = if (succeeded) "" else failureReason.ifBlank { "Background heartbeat missed" },
            canonicalGuid = canonGuid,
            lastLifecycleSequence = latestSeq
        )
        deviceObservabilityMap[tile.id] = updatedObs
        deviceObservabilityFlow.value = deviceObservabilityMap.toMap()

        DeviceBlackBoxRecorder.logEvent(
            DeviceTraceEvent(
                deviceId = canonGuid,
                deviceName = tile.title,
                provider = effectiveTransport,
                endpointIp = tile.ipAddress,
                category = when {
                    isAuthError -> DeviceTraceCategory.AUTH_KEY
                    succeeded -> DeviceTraceCategory.STATE_POLL
                    else -> DeviceTraceCategory.REACHABILITY
                },
                action = if (succeeded) "BACKGROUND_POLL_OK" else "BACKGROUND_POLL_MISS_$consecFail",
                outcome = if (succeeded) TraceOutcome.SUCCESS else TraceOutcome.FAILED,
                latencyMs = latencyMs,
                actualState = nextLifecycle.name,
                errorSummary = if (succeeded) "" else failureReason.ifBlank { "Background heartbeat missed" },
                domain = when {
                    isAuthError -> BlackBoxDomain.AUTH
                    succeeded -> BlackBoxDomain.CONNECTIVITY
                    else -> BlackBoxDomain.FAILURE
                },
                isPinned = false
            )
        )

        recordProviderHealthOutcome(
            providerId = effectiveTransport,
            displayName = tile.type.brandName,
            latencyMs = latencyMs,
            succeeded = succeeded,
            isAuthError = isAuthError
        )
    }

    fun updateDeviceLifecycleAndRecoveryStage(
        tile: SmartTile,
        lifecycleState: DeviceLifecycleState,
        recoveryStage: Int = -1,
        activeProviderId: String = "",
        diagnosticReason: String = "",
        recoveryDurationMs: Long = 0L,
        emitFlightRecord: Boolean = true
    ) {
        val now = System.currentTimeMillis()
        val canonGuid = DeviceLifecycleRecorder.registerTileAliases(tile)
        val existing = deviceObservabilityMap[tile.id]
        val prevLifecycle = existing?.lifecycleState ?: tile.effectiveLifecycleState
        val isConnected = lifecycleState == DeviceLifecycleState.ONLINE ||
                lifecycleState == DeviceLifecycleState.OPERATIONAL
        val prevAvail = existing?.availabilityPct ?: 100f
        val nextAvail = if (isConnected) {
            ((prevAvail * 0.90f) + 10.0f).coerceIn(0f, 100f)
        } else if (lifecycleState in setOf(DeviceLifecycleState.OFFLINE, DeviceLifecycleState.QUARANTINED_ERROR)) {
            (prevAvail * 0.92f).coerceIn(0f, 100f)
        } else {
            prevAvail
        }
        val effectiveTransport = activeProviderId.ifBlank {
            existing?.activeProviderId ?: tile.transportForRecorder
        }
        val effectiveDriver = ProviderId.fromRawKey(effectiveTransport)
            .takeIf { it != ProviderId.UNKNOWN }
            ?.displayName ?: tile.vendorDriverForRecorder

        var latestSeq = existing?.lastLifecycleSequence ?: 0L
        if (emitFlightRecord && prevLifecycle != lifecycleState) {
            val mappedTransition = when (lifecycleState) {
                DeviceLifecycleState.DISCOVERED -> MandatoryLifecycleTransition.DISCOVERED
                DeviceLifecycleState.VALIDATED -> MandatoryLifecycleTransition.VALIDATED
                DeviceLifecycleState.CONNECTABLE -> MandatoryLifecycleTransition.PAIRING_STARTED
                DeviceLifecycleState.AUTHENTICATED -> MandatoryLifecycleTransition.AUTH_SUCCESS
                DeviceLifecycleState.ACTIVATED -> MandatoryLifecycleTransition.ACTIVATION_COMPLETED
                DeviceLifecycleState.CAPABILITY_LEARNING -> MandatoryLifecycleTransition.CAPABILITY_REQUESTED
                DeviceLifecycleState.CAPABILITIES_LOADED -> MandatoryLifecycleTransition.CAPABILITY_RECEIVED
                DeviceLifecycleState.ONLINE,
                DeviceLifecycleState.OPERATIONAL -> if (prevLifecycle == DeviceLifecycleState.RECOVERY) {
                    MandatoryLifecycleTransition.RECOVERY_COMPLETED
                } else {
                    MandatoryLifecycleTransition.ONLINE
                }
                DeviceLifecycleState.OFFLINE -> if (prevLifecycle == DeviceLifecycleState.RECOVERY) {
                    MandatoryLifecycleTransition.RECOVERY_FAILED
                } else {
                    MandatoryLifecycleTransition.OFFLINE
                }
                DeviceLifecycleState.RECOVERY -> MandatoryLifecycleTransition.RECOVERY_STARTED
                DeviceLifecycleState.UNPAIRED -> MandatoryLifecycleTransition.UNPAIRED
                DeviceLifecycleState.UNBOUND -> MandatoryLifecycleTransition.UNBOUND
                DeviceLifecycleState.DISCOVERABLE_AGAIN -> MandatoryLifecycleTransition.REDISCOVERED
                DeviceLifecycleState.QUARANTINED_ERROR -> MandatoryLifecycleTransition.AUTH_FAILED
                DeviceLifecycleState.SEEN -> null
            }
            if (mappedTransition != null) {
                val failCat = if (lifecycleState in setOf(DeviceLifecycleState.OFFLINE, DeviceLifecycleState.QUARANTINED_ERROR)) {
                    if (diagnosticReason.isNotBlank()) {
                        ErrorClassifier.classifyException(Exception(diagnosticReason))
                    } else if (lifecycleState == DeviceLifecycleState.QUARANTINED_ERROR) {
                        FailureCategory.AUTHENTICATION_FAILURE
                    } else {
                        FailureCategory.TRANSPORT_FAILURE
                    }
                } else null
                val resultCode = when {
                    recoveryStage >= 0 -> "STAGE_$recoveryStage"
                    failCat != null -> "STATE_${lifecycleState.name}"
                    else -> "OK"
                }
                latestSeq = DeviceLifecycleRecorder.recordTileTransition(
                    tile = tile,
                    transition = mappedTransition,
                    previousState = prevLifecycle.name,
                    newState = lifecycleState.name,
                    activeTransport = effectiveTransport,
                    vendorDriver = effectiveDriver,
                    latencyMs = recoveryDurationMs,
                    protocolResultCode = resultCode,
                    failureCategory = failCat
                )
            }
        }

        val updated = (existing ?: DeviceObservabilityRecord(
            deviceId = tile.id,
            deviceTitle = tile.title,
            deviceClass = tile.deviceClass
        )).copy(
            deviceTitle = tile.title,
            deviceClass = tile.deviceClass,
            lifecycleState = lifecycleState,
            lastSeenEpochMs = if (isConnected) now else (existing?.lastSeenEpochMs ?: now),
            currentRecoveryStage = recoveryStage,
            activeProviderId = effectiveTransport,
            currentIp = tile.ipAddress.substringBefore(":"),
            currentMac = tile.macAddress.ifBlank { existing?.currentMac.orEmpty() },
            availabilityPct = nextAvail,
            consecutiveFailures = if (isConnected) 0 else (existing?.consecutiveFailures ?: 0),
            lastFailureReason = if (isConnected) "" else diagnosticReason,
            lastRecoveryDurationMs = if (recoveryDurationMs > 0L) recoveryDurationMs else (existing?.lastRecoveryDurationMs ?: 0L),
            canonicalGuid = canonGuid,
            lastLifecycleSequence = latestSeq
        )
        deviceObservabilityMap[tile.id] = updated
        deviceObservabilityFlow.value = deviceObservabilityMap.toMap()
    }

    fun recordRecoveryOutcome(succeeded: Boolean, durationMs: Long) {
        synchronized(kpiLock) {
            val cur = reliabilityKpisFlow.value
            val nextAttempts = cur.totalRecoveriesAttempted + 1
            val nextSucceeded = cur.totalRecoveriesSucceeded + (if (succeeded) 1 else 0)
            val recRate = ((nextSucceeded.toFloat() / nextAttempts.toFloat()) * 100f).coerceIn(0f, 100f)
            val durSec = (durationMs.coerceAtLeast(100L) / 1000f).coerceAtMost(120f)
            val nextAvgSec = if (cur.totalRecoveriesSucceeded > 0 && succeeded) {
                ((cur.averageRecoveryTimeSeconds * 0.7f) + (durSec * 0.3f))
            } else if (succeeded) {
                durSec
            } else {
                cur.averageRecoveryTimeSeconds
            }
            reliabilityKpisFlow.value = cur.copy(
                autoRecoverySuccessRatePct = recRate,
                averageRecoveryTimeSeconds = nextAvgSec,
                totalRecoveriesAttempted = nextAttempts,
                totalRecoveriesSucceeded = nextSucceeded
            )
        }
    }

    fun recordOnboardingOutcome(succeeded: Boolean, userDecisionCount: Int = 1) {
        synchronized(kpiLock) {
            val cur = reliabilityKpisFlow.value
            val nextAttempts = cur.totalOnboardingsAttempted + 1
            val nextOk = cur.totalOnboardingsSucceeded + (if (succeeded) 1 else 0)
            val onbRate = ((nextOk.toFloat() / nextAttempts.toFloat()) * 100f).coerceIn(0f, 100f)
            val clampedDecisions = userDecisionCount.coerceIn(0, 10).toFloat()
            val nextAvgDecisions = (cur.averageUserDecisionsPerOnboarding * 0.75f) + (clampedDecisions * 0.25f)
            reliabilityKpisFlow.value = cur.copy(
                onboardingSuccessRatePct = onbRate,
                averageUserDecisionsPerOnboarding = nextAvgDecisions,
                totalOnboardingsAttempted = nextAttempts,
                totalOnboardingsSucceeded = nextOk
            )
        }
    }

    fun recordDiscoveryScanOutcome(rawSignalCount: Int, resolvedIdentityCount: Int) {
        if (rawSignalCount <= 0 && resolvedIdentityCount <= 0) return
        synchronized(kpiLock) {
            val cur = reliabilityKpisFlow.value
            val nextRuns = cur.totalDiscoveryRuns + 1
            val ratio = if (rawSignalCount > 0) {
                (resolvedIdentityCount.coerceAtLeast(0).toFloat() / rawSignalCount.coerceAtLeast(resolvedIdentityCount).toFloat())
                    .coerceIn(0.0f, 1.0f) * 100f
            } else cur.discoverySuccessRatePct
            val smoothed = ((cur.discoverySuccessRatePct * 0.8f) + (ratio * 0.2f)).coerceIn(0.0f, 100.0f)
            reliabilityKpisFlow.value = cur.copy(
                discoverySuccessRatePct = smoothed,
                totalDiscoveryRuns = nextRuns
            )
        }
    }

    fun isCircuitOpen(providerId: String): Boolean {
        val key = providerId.uppercase(Locale.US)
        val existing = providerHealthMap[key] ?: return false
        val nowMs = System.currentTimeMillis()
        if (nowMs < existing.circuitBreakerOpenUntilMs) {
            recordCircuitBreakerRejection(
                providerKey = key,
                remainingOpenMs = existing.circuitBreakerOpenUntilMs - nowMs
            )
            return true
        }
        return false
    }

    fun recordSuccess(providerId: String, latencyMs: Long = 100L) {
        recordProviderHealthOutcome(providerId, providerId, latencyMs, succeeded = true)
    }

    fun recordFailure(providerId: String, isAuthError: Boolean = false) {
        recordProviderHealthOutcome(providerId, providerId, 5000L, succeeded = false, isAuthError = isAuthError)
    }

    fun recordProviderHealthOutcome(
        providerId: String,
        displayName: String = providerId,
        latencyMs: Long,
        succeeded: Boolean,
        isAuthError: Boolean = false
    ) {
        val key = providerId.uppercase(Locale.US).ifBlank { "LOCAL_LAN" }
        val existing = providerHealthMap[key]
        val prevRate = existing?.rollingSuccessRatePct ?: 100f
        val nextRate = if (succeeded) {
            ((prevRate * 0.85f) + 15.0f).coerceIn(0f, 100f)
        } else {
            (prevRate * 0.82f).coerceIn(0f, 100f)
        }
        val nextFailures = if (succeeded) 0 else (existing?.consecutiveFailures ?: 0) + 1
        val nextAuthFailures = when {
            succeeded -> 0
            isAuthError -> (existing?.consecutiveAuthFailures ?: 0) + 1
            else -> existing?.consecutiveAuthFailures ?: 0
        }
        val cooldownUntil = if (nextFailures >= 4) {
            val backoffMultiplier = Math.pow(2.0, (nextFailures - 4).toDouble()).toLong()
            System.currentTimeMillis() + (25_000L * backoffMultiplier).coerceAtMost(300_000L)
        } else 0L
        val avgLat = if (existing != null && existing.averageLatencyMs > 0L) {
            ((existing.averageLatencyMs * 0.65) + (latencyMs.coerceIn(1L, 15_000L) * 0.35)).toLong()
        } else {
            latencyMs.coerceIn(1L, 15_000L)
        }
        val updated = ProviderHealthStatus(
            providerId = key,
            displayName = existing?.displayName ?: displayName,
            rollingSuccessRatePct = nextRate,
            averageLatencyMs = avgLat,
            consecutiveFailures = nextFailures,
            consecutiveAuthFailures = nextAuthFailures,
            circuitBreakerOpenUntilMs = cooldownUntil,
            lastUpdatedEpochMs = System.currentTimeMillis()
        )
        providerHealthMap[key] = updated
        providerHealthFlow.value = providerHealthMap.toMap()
    }

    // -----------------------------------------------------------------------
    // 2. INTEGRATION HEALTH & COMMAND LATENCY TRACKER
    // -----------------------------------------------------------------------
    fun recordCommandLatency(
        integrationKey: String,
        displayName: String = integrationKey,
        latencyMs: Long,
        succeeded: Boolean,
        tokenValid: Boolean = true,
        onlineDevices: Int? = null,
        offlineDevices: Int? = null,
        statusMessage: String = ""
    ) {
        val key = integrationKey.uppercase()
        val existing = integrationHealthMap[key]
        val clampedLatency = latencyMs.coerceIn(1L, 15_000L)
        val smoothedLatency = if (existing != null && existing.averageLatencyMs > 0L) {
            ((existing.averageLatencyMs * 0.65) + (clampedLatency * 0.35)).toLong()
        } else {
            clampedLatency
        }
        val nextFailures = if (succeeded) 0 else (existing?.consecutiveFailures ?: 0) + 1
        val resolvedStatus = when {
            statusMessage.isNotBlank() -> statusMessage
            !tokenValid -> "Token Refresh Needed"
            succeeded -> "Online (${smoothedLatency} ms avg)"
            nextFailures >= 3 -> "Degraded ($nextFailures failures) — Self-Healing Active"
            else -> "Transient Retry ($nextFailures)"
        }
        val updated = IntegrationHealthRecord(
            integrationKey = key,
            displayName = existing?.displayName ?: displayName,
            isOnline = succeeded || nextFailures < 3,
            tokenValid = tokenValid,
            lastSyncEpochMs = System.currentTimeMillis(),
            averageLatencyMs = smoothedLatency,
            consecutiveFailures = nextFailures,
            onlineDeviceCount = onlineDevices ?: existing?.onlineDeviceCount ?: 0,
            offlineDeviceCount = offlineDevices ?: existing?.offlineDeviceCount ?: 0,
            statusMessage = resolvedStatus
        )
        integrationHealthMap[key] = updated
        integrationHealthFlow.value = integrationHealthMap.toMap()
    }

    fun updateIntegrationDeviceCounts(
        integrationKey: String,
        displayName: String,
        onlineCount: Int,
        offlineCount: Int,
        tokenValid: Boolean = true
    ) {
        val key = integrationKey.uppercase()
        val existing = integrationHealthMap[key]
        val updated = (existing ?: IntegrationHealthRecord(
            integrationKey = key,
            displayName = displayName
        )).copy(
            isOnline = offlineCount == 0 || onlineCount > 0,
            tokenValid = tokenValid,
            lastSyncEpochMs = System.currentTimeMillis(),
            onlineDeviceCount = onlineCount,
            offlineDeviceCount = offlineCount
        )
        integrationHealthMap[key] = updated
        integrationHealthFlow.value = integrationHealthMap.toMap()
    }

    // -----------------------------------------------------------------------
    // 3. LOCAL LAN IP RESOLUTION & MP3 STREAM URL BUILDER
    // -----------------------------------------------------------------------
    fun resolveLocalTabletIp(context: Context? = null): String {
        val ctx = context ?: ClaraInAppMusicPlayer.currentAppContext()
        if (ctx != null) {
            try {
                val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val linkProps = cm?.getLinkProperties(cm.activeNetwork)
                if (linkProps != null) {
                    for (linkAddr in linkProps.linkAddresses) {
                        val addr = linkAddr.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress.orEmpty()
                            if (host.isNotBlank()) return host
                        }
                    }
                }
            } catch (_: Exception) {
            }
            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val ipInt = wm?.connectionInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    val b1 = ipInt and 0xff
                    val b2 = (ipInt shr 8) and 0xff
                    val b3 = (ipInt shr 16) and 0xff
                    val b4 = (ipInt shr 24) and 0xff
                    if (b1 != 0) return "$b1.$b2.$b3.$b4"
                }
            } catch (_: Exception) {
            }
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
                        val host = addr.hostAddress.orEmpty()
                        if (host.isNotBlank()) return host
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }

    private const val MEDIA_STREAM_URL_TTL_MS = 5 * 60_000L

    private fun computeMediaStreamSignature(
        context: Context?,
        path: String,
        textParam: String,
        expiresAtEpochMs: Long
    ): String {
        val ctx = context ?: ClaraApplication.instance ?: ClaraInAppMusicPlayer.currentAppContext()
        val hubSecret = if (ctx != null) {
            ClaraApplication.getOrCreateLocalHubToken(ctx)
        } else {
            ""
        }
        val payload = "$path|${textParam.trim()}|$expiresAtEpochMs|$hubSecret"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun isMediaStreamSignatureValid(
        context: Context,
        path: String,
        queryParams: Map<String, String>
    ): Boolean {
        val expMs = queryParams["exp"]?.toLongOrNull() ?: return false
        val providedSig = queryParams["sig"].orEmpty().trim()
        if (providedSig.isBlank() || System.currentTimeMillis() > expMs) return false
        val textParam = queryParams["text"].orEmpty()
        val expectedSig = computeMediaStreamSignature(context, path, textParam, expMs)
        return java.security.MessageDigest.isEqual(
            providedSig.toByteArray(Charsets.UTF_8),
            expectedSig.toByteArray(Charsets.UTF_8)
        )
    }

    fun buildLocalMediaStreamUrl(
        context: Context?,
        slot: String = "alert",
        customUriOverride: String = ""
    ): String {
        if (customUriOverride.isNotBlank()) {
            activeCustomMp3Uri = customUriOverride
        }
        val ip = resolveLocalTabletIp(context)
        val cleanSlot = slot.trim().lowercase().removeSuffix(".mp3").ifBlank { "alert" }
        val path = "/media/$cleanSlot.mp3"
        val expMs = System.currentTimeMillis() + MEDIA_STREAM_URL_TTL_MS
        val sig = computeMediaStreamSignature(context, path, "", expMs)
        return "http://$ip:$LOCAL_HUB_PORT$path?exp=$expMs&sig=$sig"
    }

    fun buildLocalTtsStreamUrl(
        context: Context?,
        spokenText: String
    ): String {
        val ip = resolveLocalTabletIp(context)
        val cleanText = spokenText.trim().ifBlank { "Visitor at the front door" }
        val encodedText = runCatching { URLEncoder.encode(cleanText, "UTF-8") }
            .getOrDefault("Visitor%20at%20the%20front%20door")
        val path = "/media/tts.wav"
        val expMs = System.currentTimeMillis() + MEDIA_STREAM_URL_TTL_MS
        val sig = computeMediaStreamSignature(context, path, cleanText, expMs)
        return "http://$ip:$LOCAL_HUB_PORT$path?text=$encodedText&exp=$expMs&sig=$sig"
    }

    // -----------------------------------------------------------------------
    // 4. EMBEDDED LOCAL REST, WEBHOOK, MP3 CAST & CLARA MESH SERVER (8089)
    // -----------------------------------------------------------------------
    @Synchronized
    fun ensureLocalHubServerRunning(
        context: Context,
        onRemoteCommand: ((String, JSONObject) -> Unit)? = null
    ) {
        val appCtx = context.applicationContext
        if (onRemoteCommand != null) {
            remoteCommandHandler = onRemoteCommand
        }
        val currentIp = resolveLocalTabletIp(appCtx)
        if (serverJob?.isActive == true && serverSocket?.isClosed == false) {
            if (currentIp != lastRegisteredMdnsIp && currentIp != "127.0.0.1") {
                registerAndDiscoverClaraMesh(appCtx)
            }
            return
        }
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        serverJob = bridgeScope.launch {
            try {
                val sock = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(LOCAL_HUB_PORT))
                }
                serverSocket = sock
                isLocalHubServerRunningFlow.value = true
                Log.d(TAG, "Clara Local Hub HTTP/Webhook/MP3 Server listening on port $LOCAL_HUB_PORT")
                registerAndDiscoverClaraMesh(appCtx)

                while (isActive && !sock.isClosed) {
                    val client = try {
                        sock.accept()
                    } catch (_: Exception) {
                        break
                    }
                    if (!httpClientSemaphore.tryAcquire()) {
                        runCatching { client.close() }
                        continue
                    }
                    bridgeScope.launch {
                        try {
                            handleIncomingHttpClient(appCtx, client)
                        } finally {
                            httpClientSemaphore.release()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Local Hub Server note: ${e.message}")
            } finally {
                isLocalHubServerRunningFlow.value = false
            }
        }
    }

    private fun handleIncomingHttpClient(context: Context, client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 6000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val out = BufferedOutputStream(socket.getOutputStream())

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0)?.uppercase() ?: "GET"
                val rawPath = parts.getOrNull(1) ?: "/"
                val path = rawPath.substringBefore("?")
                val queryStr = rawPath.substringAfter("?", "")
                val queryParams = parseQueryParams(queryStr)

                var contentLength = 0
                val headers = HashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0) {
                        val k = line.substring(0, colonIdx).trim().lowercase()
                        val v = line.substring(colonIdx + 1).trim()
                        headers[k] = v
                        if (k == "content-length") {
                            contentLength = v.toIntOrNull() ?: 0
                        }
                    }
                }

                val bodyStr = if (contentLength in 1..262_144) {
                    val chars = CharArray(contentLength)
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val n = reader.read(chars, totalRead, contentLength - totalRead)
                        if (n <= 0) break
                        totalRead += n
                    }
                    String(chars, 0, totalRead)
                } else ""

                val payloadJson = runCatching {
                    if (bodyStr.isNotBlank()) JSONObject(bodyStr) else JSONObject()
                }.getOrDefault(JSONObject())

                val expectedHubToken = ClaraApplication.getOrCreateLocalHubToken(context)
                val providedBearer = headers["authorization"]
                    ?.removePrefix("Bearer ")
                    ?.removePrefix("bearer ")
                    ?.trim()
                    .orEmpty()
                val providedToken = providedBearer
                    .ifBlank { headers["x-clara-token"].orEmpty().trim() }
                    .ifBlank { queryParams["token"].orEmpty().trim() }
                    .ifBlank { payloadJson.optString("token", "").trim() }
                val isLoopbackClient = socket.inetAddress?.isLoopbackAddress == true
                val isPrivateLanClient = socket.inetAddress?.isSiteLocalAddress == true || isLoopbackClient
                val isTokenAuthorized = isLoopbackClient ||
                        (expectedHubToken.isNotBlank() && providedToken == expectedHubToken)

                when {
                    // 1. Custom MP3, Synthesized Local TTS WAV, or Harmonic Chime Stream for Google Cast Speakers
                    path.startsWith("/media/") -> {
                        val isSignedMediaRequest = isPrivateLanClient &&
                                isMediaStreamSignatureValid(context, path, queryParams)
                        if (!isTokenAuthorized && !isSignedMediaRequest) {
                            writeJsonResponse(out, 401, """{"error":"Unauthorized: Signed media stream URL or Hub Token required"}""")
                            return
                        }
                        serveLocalMediaFileOrSynthesizedChime(context, path, queryParams, method, out)
                    }

                    // 2. Digital Home Graph Inspection Endpoint (Requires Hub Token)
                    path == "/api/v1/graph" -> {
                        if (!isTokenAuthorized) {
                            writeJsonResponse(out, 401, """{"error":"Unauthorized: Valid Bearer or ?token= required"}""")
                            return
                        }
                        val graphJson = DeviceRepository.serializeHomeGraphToJson(digitalHomeGraphFlow.value)
                        writeJsonResponse(out, 200, graphJson.toString())
                    }

                    // 3. Integration Health & Diagnostics Endpoint (Detailed topology requires Hub Token - SEC-03 fix)
                    path == "/api/v1/health" -> {
                        if (!isTokenAuthorized) {
                            val minimalResp = JSONObject().apply {
                                put("status", "ok")
                                put("authRequiredForDetails", true)
                            }
                            writeJsonResponse(out, 200, minimalResp.toString())
                            return
                        }
                        val healthArr = JSONArray()
                        integrationHealthFlow.value.values.forEach { rec ->
                            healthArr.put(
                                JSONObject().apply {
                                    put("integrationKey", rec.integrationKey)
                                    put("displayName", rec.displayName)
                                    put("isOnline", rec.isOnline)
                                    put("tokenValid", rec.tokenValid)
                                    put("averageLatencyMs", rec.averageLatencyMs)
                                    put("consecutiveFailures", rec.consecutiveFailures)
                                    put("statusMessage", rec.statusMessage)
                                }
                            )
                        }
                        val resp = JSONObject().apply {
                            put("status", "ok")
                            put("hubIp", resolveLocalTabletIp(context))
                            put("hubPort", LOCAL_HUB_PORT)
                            put("shadowQueueSize", shadowQueueFlow.value.size)
                            put("meshPeers", JSONArray(discoveredMeshPeersFlow.value))
                            put("integrations", healthArr)
                        }
                        writeJsonResponse(out, 200, resp.toString())
                    }

                    // 4. Local Webhook & Remote Command Endpoint (Requires Hub Token)
                    path == "/api/v1/command" || path == "/api/v1/webhook" -> {
                        if (!isTokenAuthorized) {
                            DeviceRepository.logAuditEvent(
                                context = context,
                                actionType = "WEBHOOK_REJECTED",
                                targetName = "Local Hub Port $LOCAL_HUB_PORT",
                                detail = "Blocked unauthenticated webhook from ${socket.inetAddress?.hostAddress.orEmpty()}",
                                succeeded = false
                            )
                            writeJsonResponse(out, 401, """{"error":"Unauthorized: Valid Bearer or ?token= required"}""")
                            return
                        }
                        val action = payloadJson.optString("action", payloadJson.optString("event", "webhook"))
                        val target = payloadJson.optString("target", payloadJson.optString("deviceId", ""))
                        emitPlatformEvent(
                            JarvisPlatformEvent(
                                category = JarvisEventCategory.SYSTEM,
                                sourceDeviceId = target,
                                sourceDeviceTitle = payloadJson.optString("title", "Local Webhook"),
                                eventType = action,
                                summary = "Received authorized local webhook '$action' from ${socket.inetAddress?.hostAddress.orEmpty()}"
                            )
                        )
                        remoteCommandHandler?.invoke(action, payloadJson)
                        val resp = JSONObject().apply {
                            put("success", true)
                            put("action", action)
                            put("timestamp", System.currentTimeMillis())
                        }
                        writeJsonResponse(out, 200, resp.toString())
                    }

                    // 5. Clara Peer-to-Peer Mesh Sync Endpoint (Requires Hub Token)
                    path == "/api/v1/mesh/sync" -> {
                        if (!isTokenAuthorized) {
                            DeviceRepository.logAuditEvent(
                                context = context,
                                actionType = "MESH_SYNC_REJECTED",
                                targetName = "Local Hub Port $LOCAL_HUB_PORT",
                                detail = "Blocked unauthenticated mesh sync request from ${socket.inetAddress?.hostAddress.orEmpty()}",
                                succeeded = false
                            )
                            writeJsonResponse(out, 401, """{"error":"Unauthorized: Valid Bearer or ?token= required"}""")
                            return
                        }
                        val customPassphrase = queryParams["passphrase"].orEmpty().trim()
                        val backupJson = DeviceRepository.exportEncryptedBackupJson(context, customPassphrase)
                        val resp = JSONObject().apply {
                            put("success", true)
                            put("deviceName", Build.MODEL ?: "Clara Tablet")
                            put("backupPayload", backupJson)
                        }
                        writeJsonResponse(out, 200, resp.toString())
                    }

                    else -> {
                        val resp = JSONObject().apply {
                            put("service", "Clara Smart Home Local Hub")
                            put("version", "2.2")
                            put("authRequired", true)
                            put(
                                "endpoints",
                                JSONArray()
                                    .put("/api/v1/graph")
                                    .put("/api/v1/health")
                                    .put("/api/v1/command")
                                    .put("/api/v1/webhook")
                                    .put("/api/v1/mesh/sync")
                                    .put("/media/alert.mp3")
                                    .put("/media/tts.wav")
                            )
                        }
                        writeJsonResponse(out, 200, resp.toString())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "HTTP client handler note: ${e.message}")
            }
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = runCatching { URLDecoder.decode(pair.substring(0, idx), "UTF-8") }.getOrDefault("")
                val v = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }.getOrDefault("")
                if (k.isNotBlank()) map[k] = v
            }
        }
        return map
    }

    private fun writeJsonResponse(out: BufferedOutputStream, statusCode: Int, jsonBody: String) {
        val bytes = jsonBody.toByteArray(Charsets.UTF_8)
        val statusText = when (statusCode) {
            200 -> "200 OK"
            401 -> "401 Unauthorized"
            else -> "$statusCode Error"
        }
        // SEC-04 Fix: Do not emit Access-Control-Allow-Origin: * on authenticated JSON API endpoints
        val header = "HTTP/1.1 $statusText\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun serveLocalMediaFileOrSynthesizedChime(
        context: Context,
        path: String,
        queryParams: Map<String, String>,
        method: String,
        out: BufferedOutputStream
    ) {
        var audioBytes: ByteArray? = null
        var contentType = "audio/mpeg"

        // On-device WAV synthesis for Google Nest Mini / Cast speakers (/media/tts.wav?text=...)
        if (path.endsWith("tts.wav", ignoreCase = true)) {
            val textToSynthesize = queryParams["text"].orEmpty().trim()
                .ifBlank { "Visitor at the front door" }
            val ttsFile = File(context.cacheDir, "clara_cast_tts_${textToSynthesize.hashCode()}.wav")
            val synthesizedOk = runBlocking(Dispatchers.IO) {
                ClaraVoiceAssistantManager.synthesizeTextToWavFile(context, textToSynthesize, ttsFile)
            }
            if (synthesizedOk && ttsFile.exists() && ttsFile.length() > 128L) {
                audioBytes = runCatching { ttsFile.readBytes() }.getOrNull()
                contentType = "audio/wav"
            }
            runCatching {
                if (ttsFile.exists()) ttsFile.delete()
            }
        }

        if (audioBytes == null || audioBytes.isEmpty()) {
            val bridge = runCatching { DeviceRepository.loadBridge(context) }.getOrNull()
            val chosenUriStr = when {
                path.contains("alarm", ignoreCase = true) && !bridge?.customAlarmMp3Uri.isNullOrBlank() ->
                    bridge?.customAlarmMp3Uri.orEmpty()
                path.contains("doorbell", ignoreCase = true) && !bridge?.customDoorbellMp3Uri.isNullOrBlank() ->
                    bridge?.customDoorbellMp3Uri.orEmpty()
                activeCustomMp3Uri.isNotBlank() -> activeCustomMp3Uri
                !bridge?.customDoorbellMp3Uri.isNullOrBlank() -> bridge?.customDoorbellMp3Uri.orEmpty()
                else -> ""
            }
            if (chosenUriStr.isNotBlank() && !path.endsWith("tts.wav", ignoreCase = true)) {
                try {
                    val uri = Uri.parse(chosenUriStr)
                    audioBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    Log.w(TAG, "Custom MP3 stream read fallback: ${e.message}")
                }
            }
        }

        if (audioBytes == null || audioBytes.isEmpty()) {
            audioBytes = synthesizeDoorbellChimeWavBytes()
            contentType = "audio/wav"
        }

        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${audioBytes.size}\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        if (method != "HEAD") {
            out.write(audioBytes)
        }
        out.flush()
    }

    /**
     * Generates a clean, pleasant two-tone "Ding-Dong" harmonic chime in standard 16-bit PCM WAV
     * format so Google Nest Mini speakers can play a local LAN chime even if no external MP3 is selected.
     */
    private fun synthesizeDoorbellChimeWavBytes(): ByteArray {
        val sampleRate = 22050
        val durationSeconds = 2
        val numSamples = sampleRate * durationSeconds
        val pcmData = ByteArray(numSamples * 2)
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            val freq = if (t < 0.75) 659.25 else 523.25 // E5 -> C5 classic chime
            val localT = if (t < 0.75) t else (t - 0.75)
            val envelope = kotlin.math.exp(-localT * 3.2)
            val sampleVal = (sin(2.0 * PI * freq * t) * 0.65 + sin(4.0 * PI * freq * t) * 0.20) * envelope
            val pcmShort = (sampleVal * 26000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            buffer.putShort(pcmShort)
        }

        val out = ByteArrayOutputStream(44 + pcmData.size)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcmData.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // AudioFormat = 1 (PCM)
        header.putShort(1) // Mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // ByteRate
        header.putShort(2) // BlockAlign
        header.putShort(16) // BitsPerSample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmData.size)

        out.write(header.array())
        out.write(pcmData)
        return out.toByteArray()
    }

    // -----------------------------------------------------------------------
    // 5. NSD / mDNS PEER-TO-PEER CLARA MESH DISCOVERY & PRIMARY AUTHORITY ELECTION
    // -----------------------------------------------------------------------
    /**
     * Stage 4 Multi-Tablet Mesh Consensus:
     * Elects the online Clara tablet with the lowest lexicographical installation UID
     * as Primary Automation Authority so scheduled rules execute once across the home.
     */
    fun recomputeMeshPrimaryAuthority(context: Context) {
        val localUid = ClaraApplication.getOrCreateInstallationUid(context)
        val allCandidateUids = (meshPeerUidByEndpoint.values + localUid)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .sorted()
        val electedPrimaryUid = allCandidateUids.firstOrNull().orEmpty().ifBlank { localUid }
        primaryAuthorityUidFlow.value = electedPrimaryUid
        isPrimaryAutomationAuthorityFlow.value = (electedPrimaryUid == localUid)
    }

    private fun registerAndDiscoverClaraMesh(context: Context) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val localIp = resolveLocalTabletIp(context)
        val localUid = ClaraApplication.getOrCreateInstallationUid(context)
        lastRegisteredMdnsIp = localIp
        recomputeMeshPrimaryAuthority(context)

        nsdRegistrationListener?.let { oldReg ->
            runCatching { nsdManager.unregisterService(oldReg) }
        }
        nsdDiscoveryListener?.let { oldDisc ->
            runCatching { nsdManager.stopServiceDiscovery(oldDisc) }
        }

        try {
            val shortUidSuffix = localUid.takeLast(8)
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "${MDNS_SERVICE_NAME_PREFIX}_${(Build.MODEL ?: "Tablet").replace(" ", "")}_$shortUidSuffix"
                serviceType = MDNS_SERVICE_TYPE
                port = LOCAL_HUB_PORT
                runCatching { setAttribute("uid", localUid) }
            }
            val regListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo?) {
                    Log.d(TAG, "Registered Clara Mesh mDNS service: ${info?.serviceName} ($localIp:$LOCAL_HUB_PORT, uid=$localUid)")
                }

                override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
                override fun onServiceUnregistered(info: NsdServiceInfo?) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
            }
            nsdRegistrationListener = regListener
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, regListener)
        } catch (_: Exception) {
        }

        try {
            val discListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String?) {}
                override fun onServiceFound(service: NsdServiceInfo?) {
                    val s = service ?: return
                    if (!s.serviceType.contains("_clara._tcp", ignoreCase = true)) return
                    try {
                        @Suppress("DEPRECATION")
                        nsdManager.resolveService(s, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                            override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
                                val r = resolvedInfo ?: return
                                val hostIp = r.host?.hostAddress ?: return
                                val port = if (r.port > 0) r.port else LOCAL_HUB_PORT
                                if (hostIp != localIp && hostIp != "127.0.0.1") {
                                    val endpoint = "$hostIp:$port"
                                    val peerUidAttr = r.attributes?.get("uid")?.let { String(it, Charsets.UTF_8) }?.trim().orEmpty()
                                    val fallbackUid = r.serviceName?.substringAfterLast("_", "")?.takeIf { it.isNotBlank() }
                                        ?.let { "clara_home_$it" }
                                        ?: "clara_peer_$hostIp"
                                    val resolvedPeerUid = peerUidAttr.ifBlank { fallbackUid }
                                    meshPeerUidByEndpoint[endpoint] = resolvedPeerUid
                                    val peerEntry = "${r.serviceName} ($endpoint)"
                                    if (meshPeersSet.add(peerEntry)) {
                                        discoveredMeshPeersFlow.value = meshPeersSet.toList()
                                    }
                                    recomputeMeshPrimaryAuthority(context)
                                }
                            }
                        })
                    } catch (_: Exception) {
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo?) {
                    val lostName = service?.serviceName.orEmpty()
                    if (lostName.isNotBlank()) {
                        val removed = meshPeersSet.removeIf { it.startsWith(lostName) }
                        if (removed) {
                            discoveredMeshPeersFlow.value = meshPeersSet.toList()
                            recomputeMeshPrimaryAuthority(context)
                        }
                    }
                }
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            }
            nsdDiscoveryListener = discListener
            nsdManager.discoverServices(MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discListener)
        } catch (_: Exception) {
        }
    }
}
