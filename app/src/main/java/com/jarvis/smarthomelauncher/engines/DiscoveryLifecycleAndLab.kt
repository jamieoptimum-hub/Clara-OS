package com.jarvis.smarthomelauncher.engines

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.smarthomelauncher.core.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// 1. DISCOVERY -> ONBOARDING -> PROVISIONING TRACE SUMMARY MODEL
// ---------------------------------------------------------------------------
enum class DiscoveryLifecycleState(val label: String, val isTerminal: Boolean = false) {
    DISCOVERED("Signal Detected"),
    IDENTIFIED("Device Fingerprint & Correlation"),
    CLASSIFIED("Semantic Classification"),
    PROVISIONING("Wi-Fi / GATT Provisioning Started"),
    AUTHENTICATING("Credentials & Token Sent"),
    VERIFIED("LAN Port 6668 / Endpoint Verified"),
    CAPABILITY_SYNC("Capability Discovery Complete"),
    ONBOARDED("Dashboard Tile Created"),
    HEALTHY("Healthy", isTerminal = true),
    FAILED("Failed", isTerminal = true)
}

data class DiscoveryLifecycleStepEntry(
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val state: DiscoveryLifecycleState,
    val title: String,
    val detail: String = "",
    val confidence: Float? = null
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date(timestampEpochMs))
}

data class DeviceDiscoveryLifecycleTrace(
    val traceKey: String,
    val deviceName: String,
    val currentState: DiscoveryLifecycleState,
    val previousState: DiscoveryLifecycleState? = null,
    val lastSuccessfulStage: DiscoveryLifecycleState? = null,
    val failureReason: String = "",
    val steps: List<DiscoveryLifecycleStepEntry> = emptyList(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    fun formatStructuredReport(): String = buildString {
        appendLine("Device: $deviceName")
        appendLine()
        for (step in steps) {
            appendLine(step.formattedTime)
            appendLine(step.title)
            if (step.detail.isNotBlank()) appendLine(step.detail)
            if (step.confidence != null) {
                appendLine("Confidence: ${"%.2f".format(Locale.US, step.confidence)}")
            }
            appendLine()
        }
        appendLine("Status:")
        if (currentState == DiscoveryLifecycleState.FAILED) {
            appendLine("FAILED (Last OK: ${lastSuccessfulStage?.label ?: "None"} - $failureReason)")
        } else {
            appendLine(currentState.label)
        }
    }
}

object DiscoveryLifecycleLogger {
    private const val TAG = "ClaraLifecycleLog"
    private val tracesByKey = ConcurrentHashMap<String, DeviceDiscoveryLifecycleTrace>()
    val tracesFlow = MutableStateFlow<List<DeviceDiscoveryLifecycleTrace>>(emptyList())

    fun recordTransition(
        traceKey: String,
        deviceName: String,
        newState: DiscoveryLifecycleState,
        stepTitle: String = newState.label,
        detail: String = "",
        confidence: Float? = null,
        failureReason: String = ""
    ) {
        val cleanKey = traceKey.trim().ifBlank { deviceName.lowercase(Locale.US) }
        val existing = tracesByKey[cleanKey]
        val step = DiscoveryLifecycleStepEntry(
            state = newState,
            title = stepTitle,
            detail = detail,
            confidence = confidence
        )
        val updatedSteps = (existing?.steps.orEmpty() + step).takeLast(25)
        val prevState = existing?.currentState
        val lastOk = when {
            newState != DiscoveryLifecycleState.FAILED -> newState
            else -> existing?.lastSuccessfulStage ?: prevState
        }

        val updatedTrace = DeviceDiscoveryLifecycleTrace(
            traceKey = cleanKey,
            deviceName = deviceName.ifBlank { existing?.deviceName ?: "Wireless Device" },
            currentState = newState,
            previousState = prevState,
            lastSuccessfulStage = lastOk,
            failureReason = if (newState == DiscoveryLifecycleState.FAILED) failureReason.ifBlank { detail } else "",
            steps = updatedSteps
        )
        tracesByKey[cleanKey] = updatedTrace
        tracesFlow.value = tracesByKey.values.sortedByDescending { it.updatedAtEpochMs }
        Log.d(TAG, "[${updatedTrace.deviceName}] ${newState.name}: $stepTitle ${if (detail.isNotBlank()) "($detail)" else ""}")
    }

    fun clearAllTraces() {
        tracesByKey.clear()
        tracesFlow.value = emptyList()
    }
}

// ---------------------------------------------------------------------------
// 2. CHANGE 1.5: PURE DETERMINISTIC EVENT-SOURCED TIMELINE REPLAY ENGINE
// ---------------------------------------------------------------------------
enum class AuthReplayStatus(val label: String, val badgeColorHex: Long) {
    UNAUTHENTICATED("Unauthenticated", 0xFF94A3B8L),
    PAIRING_IN_PROGRESS("Pairing In Progress", 0xFF38BDF8L),
    AUTHENTICATED("Authenticated", 0xFF10B981L),
    AUTH_REJECTED("Auth Rejected / Failed", 0xFFEF4444L),
    AUTH_EXPIRED_OR_REVOKED("Session Expired / Needs Re-Auth", 0xFFF59E0BL),
    DASHBOARD_UNPAIRED("Unpaired (Hidden)", 0xFF94A3B8L),
    UNBOUND_CREDENTIALS_WIPED("Unbound (Credentials Wiped)", 0xFFEF4444L)
}

data class EndpointSightingRecord(
    val endpoint: String,
    val macAddress: String,
    val transportSource: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val sequenceNumber: Long,
    val isDhcpDrift: Boolean
)

data class ConfidenceStepPoint(
    val timestampMs: Long,
    val transportSource: String,
    val eventType: String,
    val qualificationStage: String,
    val confidenceScore: Float,
    val outcomeReason: String
)

data class CommandOutcomeRecord(
    val sequenceNumber: Long,
    val timestampMs: Long,
    val actionCode: String,
    val outcome: String,
    val transport: String,
    val latencyMs: Long,
    val observedState: String,
    val failureDetail: String
)

data class CapabilitySchemaDiff(
    val fromVersion: Int,
    val toVersion: Int,
    val addedKeys: List<String>,
    val removedKeys: List<String>,
    val changedKeys: List<String>
) {
    val hasChanges: Boolean
        get() = addedKeys.isNotEmpty() || removedKeys.isNotEmpty() || changedKeys.isNotEmpty()
}

data class ReplayRootCauseFinding(
    val timestampMs: Long,
    val sequenceNumber: Long,
    val category: String,
    val source: String,
    val explanation: String
)

data class ReplayableDeviceSummary(
    val canonicalGuid: String,
    val displayName: String,
    val maxSequenceNumber: Long,
    val latestState: String,
    val lastUpdatedMs: Long
)

data class ProjectedDeviceReplayState(
    val canonicalGuid: String,
    val displayName: String,
    val selectedSequenceNumber: Long,
    val maxSequenceNumber: Long,
    val selectedEvent: DeviceLifecycleEvent,
    val lifecycleState: String,
    val previousLifecycleState: String,
    val activeTransport: String,
    val vendorDriver: String,
    val authStatus: AuthReplayStatus,
    val authDetail: String,
    val activeEndpoint: String,
    val learnedMacAddress: String,
    val endpointHistory: List<EndpointSightingRecord>,
    val latestConfidenceScore: Float,
    val peakConfidenceScore: Float,
    val confidenceProgression: List<ConfidenceStepPoint>,
    val commandsSentCount: Int,
    val commandsAckedCount: Int,
    val commandsFailedCount: Int,
    val stateBounceCount: Int,
    val lastCommandOutcomeSummary: String,
    val commandOutcomes: List<CommandOutcomeRecord>,
    val activeCapabilitySnapshot: DeviceCapabilitySnapshot?,
    val capabilitySchemaDiff: CapabilitySchemaDiff?,
    val allCapabilitySnapshotsUpToSeq: List<DeviceCapabilitySnapshot>,
    val rootCauseFindings: List<ReplayRootCauseFinding>,
    val foldedLifecycleEvents: List<DeviceLifecycleEvent>
)

object DiscoveryReplayEngine {
    private val ipv4ExtractRegex = Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?\b""")
    private val stagePctRegex = Regex("""STAGE_\d_(\d{1,3})PCT""")

    private fun formatReplayTimestamp(ms: Long): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.UK).format(Date(ms))

    private fun extractEndpointFromText(raw: String): String {
        if (raw.isBlank() || raw == "-" || raw.equals("no-ip", ignoreCase = true)) return ""
        val m = ipv4ExtractRegex.find(raw) ?: return ""
        val host = m.groupValues[1]
        if (host == "0.0.0.0" || host == "127.0.0.1") return ""
        val port = m.groupValues.getOrNull(2).orEmpty()
        return if (port.isNotBlank() && port != "0") "$host:$port" else host
    }

    /**
     * Pure, side-effect-free event-sourced projector.
     * Folds recorded discovery events, monotonic lifecycle transitions (1..targetSequenceNumber),
     * command/black-box traces, and versioned capability snapshots into an exact historical state.
     */
    fun projectDeviceStateAtSequence(
        canonicalGuid: String,
        displayName: String,
        targetSequenceNumber: Long,
        lifecycleEvents: List<DeviceLifecycleEvent>,
        discoveryEvents: List<DiscoveryFlightEvent>,
        blackBoxEvents: List<DeviceTraceEvent>,
        capabilitySnapshots: List<DeviceCapabilitySnapshot>
    ): ProjectedDeviceReplayState? {
        if (lifecycleEvents.isEmpty()) return null
        val orderedLifecycle = lifecycleEvents.sortedBy { it.sequenceNumber }
        val maxSeq = orderedLifecycle.last().sequenceNumber
        val clampedSeq = targetSequenceNumber.coerceIn(orderedLifecycle.first().sequenceNumber, maxSeq)

        val foldedLifecycle = orderedLifecycle.filter { it.sequenceNumber <= clampedSeq }
        if (foldedLifecycle.isEmpty()) return null
        val selectedEvent = foldedLifecycle.last()
        val nextLifecycleEvent = orderedLifecycle.firstOrNull { it.sequenceNumber > clampedSeq }

        // Deterministic temporal cutoff bounded between selectedEvent and nextLifecycleEvent
        fun isWithinCutoff(tsMs: Long, monoNs: Long): Boolean {
            if (nextLifecycleEvent == null) {
                return tsMs <= selectedEvent.timestampMs + 1_000L
            }
            if (nextLifecycleEvent.timestampMs > selectedEvent.timestampMs) {
                return tsMs < nextLifecycleEvent.timestampMs && tsMs <= selectedEvent.timestampMs + 250L
            }
            if (monoNs > 0L && nextLifecycleEvent.monotonicTimestampNs > 0L) {
                return tsMs <= selectedEvent.timestampMs && monoNs < nextLifecycleEvent.monotonicTimestampNs
            }
            return tsMs <= selectedEvent.timestampMs
        }

        val includedDiscovery = discoveryEvents
            .filter { isWithinCutoff(it.timestampMs, it.monotonicTimestampNs) }
            .sortedWith(compareBy<DiscoveryFlightEvent> { it.timestampMs }.thenBy { it.monotonicTimestampNs })

        val includedBlackBox = blackBoxEvents
            .filter { isWithinCutoff(it.timestampMs, it.monotonicTimestampNs) }
            .sortedWith(compareBy<DeviceTraceEvent> { it.timestampMs }.thenBy { it.monotonicTimestampNs })

        // 1. Fold Authentication & Ownership State across sequence 1..clampedSeq
        var authStatus = AuthReplayStatus.UNAUTHENTICATED
        var authDetail = "No authentication handshake completed yet"
        var lastUnboundSeq = -1L

        for (lc in foldedLifecycle) {
            when (MandatoryLifecycleTransition.fromRawEvent(lc.event)) {
                MandatoryLifecycleTransition.PAIRING_STARTED -> {
                    authStatus = AuthReplayStatus.PAIRING_IN_PROGRESS
                    authDetail = "Pairing initiated via ${lc.activeTransport} (${lc.protocolResultCode})"
                }
                MandatoryLifecycleTransition.PAIRING_COMPLETED -> {
                    if (authStatus != AuthReplayStatus.AUTHENTICATED) {
                        authStatus = AuthReplayStatus.PAIRING_IN_PROGRESS
                    }
                    authDetail = "Pairing transport linked (${lc.protocolResultCode})"
                }
                MandatoryLifecycleTransition.AUTH_SUCCESS -> {
                    authStatus = AuthReplayStatus.AUTHENTICATED
                    authDetail = "Authenticated via ${lc.activeTransport} (${lc.protocolResultCode})"
                }
                MandatoryLifecycleTransition.ACTIVATION_COMPLETED -> {
                    authStatus = AuthReplayStatus.AUTHENTICATED
                    authDetail = "Activated & bound via ${lc.activeTransport} (${lc.protocolResultCode})"
                }
                MandatoryLifecycleTransition.AUTH_FAILED -> {
                    authStatus = AuthReplayStatus.AUTH_REJECTED
                    authDetail = "Rejected on ${lc.activeTransport}: ${lc.protocolResultCode} (${lc.failureCategory.ifBlank { "AUTHENTICATION_FAILURE" }})"
                }
                MandatoryLifecycleTransition.RECOVERY_COMPLETED -> {
                    if (authStatus in setOf(AuthReplayStatus.AUTH_EXPIRED_OR_REVOKED, AuthReplayStatus.AUTH_REJECTED)) {
                        authStatus = AuthReplayStatus.AUTHENTICATED
                        authDetail = "Session restored during recovery (${lc.protocolResultCode})"
                    }
                }
                MandatoryLifecycleTransition.RECOVERY_FAILED -> {
                    if (lc.failureCategory == FailureCategory.AUTHENTICATION_FAILURE.name) {
                        authStatus = AuthReplayStatus.AUTH_EXPIRED_OR_REVOKED
                        authDetail = "Recovery exhausted due to expired/revoked credentials (${lc.protocolResultCode})"
                    }
                }
                MandatoryLifecycleTransition.UNPAIRED -> {
                    authStatus = AuthReplayStatus.DASHBOARD_UNPAIRED
                    authDetail = "Hidden/unpaired from dashboard; vault credentials retained"
                }
                MandatoryLifecycleTransition.UNBOUND -> {
                    authStatus = AuthReplayStatus.UNBOUND_CREDENTIALS_WIPED
                    authDetail = "Formally unbound & local vault credentials wiped (${lc.protocolResultCode})"
                    lastUnboundSeq = lc.sequenceNumber
                }
                MandatoryLifecycleTransition.ONLINE -> {
                    if (authStatus == AuthReplayStatus.UNAUTHENTICATED || authStatus == AuthReplayStatus.DASHBOARD_UNPAIRED) {
                        authStatus = AuthReplayStatus.AUTHENTICATED
                        authDetail = "Operational session active on ${lc.activeTransport}"
                    }
                }
                else -> Unit
            }
        }

        // 2. Fold Real Endpoint & MAC History (Zero synthetic IP generation)
        data class RawEndpointObservation(
            val tsMs: Long,
            val seq: Long,
            val endpoint: String,
            val mac: String,
            val source: String
        )

        val rawObservations = mutableListOf<RawEndpointObservation>()
        for (d in includedDiscovery) {
            val ep = if (d.ipAddress.isNotBlank() && d.ipAddress != "-" && d.ipAddress != "0.0.0.0" && !d.ipAddress.equals("no-ip", true)) {
                if (d.port > 0 && !d.ipAddress.contains(":")) "${d.ipAddress}:${d.port}" else d.ipAddress
            } else ""
            val cleanMac = CrossEcosystemIdentityCorrelator.normalizeMac(d.macAddress)
            if (ep.isNotBlank() || cleanMac.isNotBlank()) {
                rawObservations.add(
                    RawEndpointObservation(
                        tsMs = d.timestampMs,
                        seq = foldedLifecycle.lastOrNull { it.timestampMs <= d.timestampMs }?.sequenceNumber ?: 1L,
                        endpoint = ep,
                        mac = cleanMac,
                        source = d.transportSource
                    )
                )
            }
        }
        for (lc in foldedLifecycle) {
            val epFromCode = extractEndpointFromText(lc.protocolResultCode)
            if (epFromCode.isNotBlank()) {
                rawObservations.add(
                    RawEndpointObservation(
                        tsMs = lc.timestampMs,
                        seq = lc.sequenceNumber,
                        endpoint = epFromCode,
                        mac = "",
                        source = lc.activeTransport
                    )
                )
            }
        }
        for (bb in includedBlackBox) {
            val ep = extractEndpointFromText(bb.endpointIp)
            if (ep.isNotBlank()) {
                rawObservations.add(
                    RawEndpointObservation(
                        tsMs = bb.timestampMs,
                        seq = foldedLifecycle.lastOrNull { it.timestampMs <= bb.timestampMs }?.sequenceNumber ?: clampedSeq,
                        endpoint = ep,
                        mac = "",
                        source = bb.provider
                    )
                )
            }
        }
        rawObservations.sortWith(compareBy<RawEndpointObservation> { it.tsMs }.thenBy { it.seq })

        var learnedMac = if (canonicalGuid.startsWith("guid::mac::")) {
            CrossEcosystemIdentityCorrelator.normalizeMac(canonicalGuid.removePrefix("guid::mac::"))
        } else ""
        val endpointHistory = mutableListOf<EndpointSightingRecord>()
        var lastHostIp = ""

        for (obs in rawObservations) {
            if (obs.mac.isNotBlank()) {
                learnedMac = obs.mac
            }
            if (obs.endpoint.isBlank()) continue
            val hostIp = obs.endpoint.substringBefore(":")
            val lastEntry = endpointHistory.lastOrNull()
            if (lastEntry != null && lastEntry.endpoint == obs.endpoint) {
                endpointHistory[endpointHistory.lastIndex] = lastEntry.copy(
                    lastSeenMs = maxOf(lastEntry.lastSeenMs, obs.tsMs),
                    macAddress = lastEntry.macAddress.ifBlank { learnedMac }
                )
            } else {
                val drifted = lastHostIp.isNotBlank() && hostIp != lastHostIp
                lastHostIp = hostIp
                endpointHistory.add(
                    EndpointSightingRecord(
                        endpoint = obs.endpoint,
                        macAddress = learnedMac,
                        transportSource = obs.source,
                        firstSeenMs = obs.tsMs,
                        lastSeenMs = obs.tsMs,
                        sequenceNumber = obs.seq,
                        isDhcpDrift = drifted
                    )
                )
            }
        }
        val activeEndpoint = if (selectedEvent.newState == DeviceLifecycleState.UNBOUND.name) {
            ""
        } else {
            endpointHistory.lastOrNull()?.endpoint.orEmpty()
        }

        // 3. Fold Discovery Confidence Progression
        val confidenceSteps = mutableListOf<ConfidenceStepPoint>()
        for (d in includedDiscovery) {
            if (d.confidenceScore > 0f || d.eventType in setOf(DiscoveryEventType.FILTERED, DiscoveryEventType.FAILED, DiscoveryEventType.TIMEOUT)) {
                confidenceSteps.add(
                    ConfidenceStepPoint(
                        timestampMs = d.timestampMs,
                        transportSource = d.transportSource,
                        eventType = d.eventType.name,
                        qualificationStage = d.qualificationStage.ifBlank { d.eventType.name },
                        confidenceScore = d.confidenceScore,
                        outcomeReason = d.outcomeReason
                    )
                )
            }
        }
        for (lc in foldedLifecycle) {
            if (MandatoryLifecycleTransition.fromRawEvent(lc.event) == MandatoryLifecycleTransition.VALIDATED) {
                val pct = stagePctRegex.find(lc.protocolResultCode)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (pct != null && confidenceSteps.none { kotlin.math.abs(it.timestampMs - lc.timestampMs) <= 250L && (it.confidenceScore * 100).roundToInt() == pct }) {
                    confidenceSteps.add(
                        ConfidenceStepPoint(
                            timestampMs = lc.timestampMs,
                            transportSource = lc.activeTransport,
                            eventType = "VALIDATED",
                            qualificationStage = "STAGE_5_ONBOARDABLE_DEVICE",
                            confidenceScore = (pct / 100f).coerceIn(0f, 1f),
                            outcomeReason = "Lifecycle validated at seq #${lc.sequenceNumber} (${lc.protocolResultCode})"
                        )
                    )
                }
            }
        }
        confidenceSteps.sortBy { it.timestampMs }
        val latestConf = confidenceSteps.lastOrNull { it.confidenceScore > 0f }?.confidenceScore ?: 0f
        val peakConf = confidenceSteps.maxOfOrNull { it.confidenceScore } ?: 0f

        // 4. Fold Command Outcomes & State Bounce Counts
        var sentCount = 0
        var ackCount = 0
        var failCount = 0
        val bounceEvents = includedBlackBox.filter { it.category == DeviceTraceCategory.STATE_BOUNCE_WARN }
        val commandOutcomes = mutableListOf<CommandOutcomeRecord>()

        val commandTraces = includedBlackBox.filter {
            it.category in setOf(DeviceTraceCategory.COMMAND_TX, DeviceTraceCategory.TRANSPORT_RX, DeviceTraceCategory.STATE_BOUNCE_WARN)
        }

        for (lc in foldedLifecycle) {
            when (MandatoryLifecycleTransition.fromRawEvent(lc.event)) {
                MandatoryLifecycleTransition.COMMAND_SENT -> {
                    sentCount++
                    val hasFollowupAtOrBeforeCutoff = foldedLifecycle.any {
                        it.sequenceNumber > lc.sequenceNumber &&
                                MandatoryLifecycleTransition.fromRawEvent(it.event) in setOf(
                            MandatoryLifecycleTransition.COMMAND_ACK,
                            MandatoryLifecycleTransition.COMMAND_FAILED
                        )
                    }
                    if (!hasFollowupAtOrBeforeCutoff) {
                        commandOutcomes.add(
                            CommandOutcomeRecord(
                                sequenceNumber = lc.sequenceNumber,
                                timestampMs = lc.timestampMs,
                                actionCode = lc.protocolResultCode,
                                outcome = "SENT_PENDING_ACK",
                                transport = lc.activeTransport,
                                latencyMs = lc.latencyMs,
                                observedState = lc.newState,
                                failureDetail = ""
                            )
                        )
                    }
                }
                MandatoryLifecycleTransition.COMMAND_ACK -> {
                    ackCount++
                    val matchedTrace = commandTraces.lastOrNull {
                        it.outcome == TraceOutcome.SUCCESS && kotlin.math.abs(it.timestampMs - lc.timestampMs) <= 2_500L
                    }
                    commandOutcomes.add(
                        CommandOutcomeRecord(
                            sequenceNumber = lc.sequenceNumber,
                            timestampMs = lc.timestampMs,
                            actionCode = lc.protocolResultCode.removeSuffix("_ACK"),
                            outcome = "ACK_CONFIRMED",
                            transport = lc.activeTransport,
                            latencyMs = lc.latencyMs,
                            observedState = matchedTrace?.actualState?.ifBlank { lc.newState } ?: lc.newState,
                            failureDetail = ""
                        )
                    )
                }
                MandatoryLifecycleTransition.COMMAND_FAILED -> {
                    failCount++
                    val matchedTrace = commandTraces.lastOrNull {
                        it.outcome != TraceOutcome.SUCCESS && kotlin.math.abs(it.timestampMs - lc.timestampMs) <= 2_500L
                    }
                    commandOutcomes.add(
                        CommandOutcomeRecord(
                            sequenceNumber = lc.sequenceNumber,
                            timestampMs = lc.timestampMs,
                            actionCode = lc.protocolResultCode.removeSuffix("_ERR"),
                            outcome = "FAILED",
                            transport = lc.activeTransport,
                            latencyMs = lc.latencyMs,
                            observedState = matchedTrace?.actualState?.ifBlank { lc.newState } ?: lc.newState,
                            failureDetail = matchedTrace?.errorSummary?.ifBlank { lc.failureCategory } ?: lc.failureCategory
                        )
                    )
                }
                else -> Unit
            }
        }
        for (b in bounceEvents) {
            commandOutcomes.add(
                CommandOutcomeRecord(
                    sequenceNumber = foldedLifecycle.lastOrNull { it.timestampMs <= b.timestampMs }?.sequenceNumber ?: clampedSeq,
                    timestampMs = b.timestampMs,
                    actionCode = b.action,
                    outcome = "STATE_BOUNCED",
                    transport = b.provider,
                    latencyMs = b.latencyMs,
                    observedState = b.actualState,
                    failureDetail = b.errorSummary
                )
            )
        }
        commandOutcomes.sortWith(compareBy<CommandOutcomeRecord> { it.timestampMs }.thenBy { it.sequenceNumber })
        val lastCmdSummary = commandOutcomes.lastOrNull()?.let { last ->
            "${last.actionCode} -> ${last.outcome}${if (last.latencyMs > 0) " (${last.latencyMs}ms)" else ""}"
        } ?: "No commands dispatched through seq #$clampedSeq"

        // 5. Resolve Versioned Capability Schema Snapshot active at clampedSeq
        val inlineSnapshots = foldedLifecycle.mapNotNull { lc ->
            if (lc.capabilitySnapshotJson.isNotBlank() && lc.capabilitySchemaVersion > 0) {
                val decoded = CapabilitySnapshotCodec.decodeDescriptors(lc.capabilitySnapshotJson)
                DeviceCapabilitySnapshot(
                    id = "inline_${lc.id}",
                    deviceId = canonicalGuid,
                    schemaVersion = lc.capabilitySchemaVersion,
                    lifecycleSequenceNumber = lc.sequenceNumber,
                    timestampMs = lc.timestampMs,
                    monotonicTimestampNs = lc.monotonicTimestampNs,
                    schemaHash = lc.capabilitySchemaHash,
                    capabilityCount = decoded.size,
                    capabilitiesJson = lc.capabilitySnapshotJson,
                    capabilities = decoded
                )
            } else null
        }

        val mergedSnapshots = (capabilitySnapshots + inlineSnapshots)
            .distinctBy { it.schemaVersion }
            .sortedBy { it.schemaVersion }

        val eligibleSnapshots = mergedSnapshots.filter { snap ->
            snap.lifecycleSequenceNumber > lastUnboundSeq && (
                    snap.lifecycleSequenceNumber <= clampedSeq ||
                            (snap.lifecycleSequenceNumber == 0L && foldedLifecycle.any {
                                it.newState in setOf(
                                    DeviceLifecycleState.CAPABILITIES_LOADED.name,
                                    DeviceLifecycleState.ONLINE.name,
                                    DeviceLifecycleState.OPERATIONAL.name
                                )
                            })
                    )
        }

        val activeSnapshot = eligibleSnapshots.lastOrNull()
        val previousSnapshot = if (eligibleSnapshots.size >= 2) {
            eligibleSnapshots[eligibleSnapshots.size - 2]
        } else null

        val schemaDiff = if (activeSnapshot != null && previousSnapshot != null) {
            val prevMap = previousSnapshot.capabilities.associateBy { it.key }
            val currMap = activeSnapshot.capabilities.associateBy { it.key }
            val added = (currMap.keys - prevMap.keys).sorted()
            val removed = (prevMap.keys - currMap.keys).sorted()
            val changed = currMap.keys.intersect(prevMap.keys).filter { k ->
                val a = prevMap[k]!!
                val b = currMap[k]!!
                a.kind != b.kind || a.minValue != b.minValue || a.maxValue != b.maxValue ||
                        a.unit != b.unit || a.enumOptions != b.enumOptions || a.dpId != b.dpId
            }.sorted()
            CapabilitySchemaDiff(
                fromVersion = previousSnapshot.schemaVersion,
                toVersion = activeSnapshot.schemaVersion,
                addedKeys = added,
                removedKeys = removed,
                changedKeys = changed
            )
        } else null

        // 6. Fold Deterministic Root-Cause Failure Findings up to clampedSeq
        val findings = mutableListOf<ReplayRootCauseFinding>()
        for (d in includedDiscovery) {
            if (d.eventType in setOf(DiscoveryEventType.FAILED, DiscoveryEventType.TIMEOUT)) {
                findings.add(
                    ReplayRootCauseFinding(
                        timestampMs = d.timestampMs,
                        sequenceNumber = foldedLifecycle.lastOrNull { it.timestampMs <= d.timestampMs }?.sequenceNumber ?: 1L,
                        category = d.eventType.name,
                        source = d.transportSource,
                        explanation = d.outcomeReason
                    )
                )
            }
        }
        for (lc in foldedLifecycle) {
            if (lc.failureCategory.isNotBlank()) {
                findings.add(
                    ReplayRootCauseFinding(
                        timestampMs = lc.timestampMs,
                        sequenceNumber = lc.sequenceNumber,
                        category = lc.failureCategory,
                        source = "${lc.activeTransport} (${lc.vendorDriver})",
                        explanation = "Seq #${lc.sequenceNumber} ${lc.event}: ${lc.previousState} -> ${lc.newState} [code=${lc.protocolResultCode}]"
                    )
                )
            }
        }
        for (bb in includedBlackBox) {
            if (bb.outcome in setOf(TraceOutcome.FAILED, TraceOutcome.TIMEOUT, TraceOutcome.BOUNCED, TraceOutcome.UNHANDLED) &&
                bb.category != DeviceTraceCategory.LIFECYCLE_STEP
            ) {
                findings.add(
                    ReplayRootCauseFinding(
                        timestampMs = bb.timestampMs,
                        sequenceNumber = foldedLifecycle.lastOrNull { it.timestampMs <= bb.timestampMs }?.sequenceNumber ?: clampedSeq,
                        category = "${bb.domain.name}/${bb.outcome.name}",
                        source = bb.provider,
                        explanation = "${bb.action}: ${bb.errorSummary.ifBlank { "Unacknowledged or reverted state" }}"
                    )
                )
            }
        }
        findings.sortWith(compareByDescending<ReplayRootCauseFinding> { it.sequenceNumber }.thenByDescending { it.timestampMs })

        return ProjectedDeviceReplayState(
            canonicalGuid = canonicalGuid,
            displayName = displayName.ifBlank { canonicalGuid.removePrefix("guid::") },
            selectedSequenceNumber = clampedSeq,
            maxSequenceNumber = maxSeq,
            selectedEvent = selectedEvent,
            lifecycleState = selectedEvent.newState,
            previousLifecycleState = selectedEvent.previousState,
            activeTransport = selectedEvent.activeTransport,
            vendorDriver = selectedEvent.vendorDriver,
            authStatus = authStatus,
            authDetail = authDetail,
            activeEndpoint = activeEndpoint,
            learnedMacAddress = learnedMac,
            endpointHistory = endpointHistory,
            latestConfidenceScore = latestConf,
            peakConfidenceScore = peakConf,
            confidenceProgression = confidenceSteps,
            commandsSentCount = sentCount,
            commandsAckedCount = ackCount,
            commandsFailedCount = failCount,
            stateBounceCount = bounceEvents.size,
            lastCommandOutcomeSummary = lastCmdSummary,
            commandOutcomes = commandOutcomes,
            activeCapabilitySnapshot = activeSnapshot,
            capabilitySchemaDiff = schemaDiff,
            allCapabilitySnapshotsUpToSeq = eligibleSnapshots,
            rootCauseFindings = findings,
            foldedLifecycleEvents = foldedLifecycle
        )
    }

    fun listReplayableDevices(context: Context? = null): List<ReplayableDeviceSummary> {
        val tilesByGuid = if (context != null) {
            runCatching {
                DeviceRepository.loadTiles(context).associateBy { tile ->
                    tile.canonicalGuid.ifBlank { tile.resolvedCanonicalGuid() }
                }
            }.getOrDefault(emptyMap())
        } else emptyMap()

        val allGuids = DeviceLifecycleRecorder.getAllRecordedDeviceGuids()
        val summaries = mutableListOf<ReplayableDeviceSummary>()
        for (guid in allGuids) {
            val history = DeviceLifecycleRecorder.getDeviceHistoryOrdered(guid)
            if (history.isEmpty()) continue
            val latest = history.last()
            val tileTitle = tilesByGuid[guid]?.title.orEmpty()
            val resolvedName = DeviceBlackBoxRecorder.resolveDisplayName(
                canonicalGuid = guid,
                fallbackName = tileTitle.ifBlank { latest.vendorDriver }
            )
            summaries.add(
                ReplayableDeviceSummary(
                    canonicalGuid = guid,
                    displayName = resolvedName,
                    maxSequenceNumber = latest.sequenceNumber,
                    latestState = latest.newState,
                    lastUpdatedMs = latest.timestampMs
                )
            )
        }
        return summaries.sortedByDescending { it.lastUpdatedMs }
    }

    fun loadAndProjectDevice(
        context: Context?,
        deviceIdOrGuid: String,
        targetSequenceNumber: Long? = null
    ): ProjectedDeviceReplayState? {
        val canonicalGuid = DeviceLifecycleRecorder.resolveCanonicalGuid(deviceIdOrGuid)
        val lifecycleHistory = DeviceLifecycleRecorder.getDeviceHistoryOrdered(canonicalGuid)
        if (lifecycleHistory.isEmpty()) return null

        val matchedTile = if (context != null) {
            runCatching {
                DeviceRepository.loadTiles(context).firstOrNull { t ->
                    (t.canonicalGuid.ifBlank { t.resolvedCanonicalGuid() }) == canonicalGuid
                }
            }.getOrNull()
        } else null

        if (matchedTile != null) {
            DeviceLifecycleRecorder.seedBaselineCapabilitySnapshotIfMissing(matchedTile)
        }

        val aliases = DeviceLifecycleRecorder.aliasesForCanonicalGuid(canonicalGuid)
        val macHints = buildSet {
            if (matchedTile != null && matchedTile.macAddress.isNotBlank()) add(matchedTile.macAddress)
            aliases.forEach { if (CrossEcosystemIdentityCorrelator.normalizeMac(it).isNotEmpty()) add(it) }
        }
        val idHints = buildSet {
            addAll(aliases)
            if (matchedTile != null) {
                if (matchedTile.serialNumber.isNotBlank()) add(matchedTile.serialNumber)
                if (matchedTile.entityId.isNotBlank()) add(matchedTile.entityId)
                if (matchedTile.vehicleVin.isNotBlank()) add(matchedTile.vehicleVin)
            }
        }

        val discoveryHistory = DiscoveryFlightRecorder.getCorrelatedDiscoveryEvents(
            canonicalGuid = canonicalGuid,
            macHints = macHints,
            idHints = idHints
        )
        val blackBoxHistory = DeviceBlackBoxRecorder.getDeviceEvents(canonicalGuid)
        val capSnapshots = DeviceLifecycleRecorder.getCapabilitySnapshotsForDevice(canonicalGuid)
        val displayName = DeviceBlackBoxRecorder.resolveDisplayName(
            canonicalGuid = canonicalGuid,
            fallbackName = matchedTile?.title.orEmpty()
        )
        val seqToProject = targetSequenceNumber ?: lifecycleHistory.maxOf { it.sequenceNumber }

        return projectDeviceStateAtSequence(
            canonicalGuid = canonicalGuid,
            displayName = displayName,
            targetSequenceNumber = seqToProject,
            lifecycleEvents = lifecycleHistory,
            discoveryEvents = discoveryHistory,
            blackBoxEvents = blackBoxHistory,
            capabilitySnapshots = capSnapshots
        )
    }

    fun exportProjectedTimelineReport(
        context: Context,
        state: ProjectedDeviceReplayState,
        asJson: Boolean = false,
        launchShareSheet: Boolean = false
    ): String {
        val content = if (asJson) {
            JSONObject().apply {
                put("canonicalGuid", state.canonicalGuid)
                put("displayName", state.displayName)
                put("selectedSequenceNumber", state.selectedSequenceNumber)
                put("maxSequenceNumber", state.maxSequenceNumber)
                put("timestampMs", state.selectedEvent.timestampMs)
                put("event", state.selectedEvent.event)
                put("previousLifecycleState", state.previousLifecycleState)
                put("lifecycleState", state.lifecycleState)
                put("activeTransport", state.activeTransport)
                put("vendorDriver", state.vendorDriver)
                put("authStatus", state.authStatus.name)
                put("authDetail", state.authDetail)
                put("activeEndpoint", state.activeEndpoint)
                put("learnedMacAddress", state.learnedMacAddress)
                put("latestConfidencePct", (state.latestConfidenceScore * 100).roundToInt())
                put("peakConfidencePct", (state.peakConfidenceScore * 100).roundToInt())
                put("commandsSentCount", state.commandsSentCount)
                put("commandsAckedCount", state.commandsAckedCount)
                put("commandsFailedCount", state.commandsFailedCount)
                put("stateBounceCount", state.stateBounceCount)
                put("capabilitySchemaVersion", state.activeCapabilitySnapshot?.schemaVersion ?: 0)
                put("capabilitySchemaHash", state.activeCapabilitySnapshot?.schemaHash.orEmpty())
                put(
                    "endpointHistory",
                    JSONArray().apply {
                        state.endpointHistory.forEach { ep ->
                            put(
                                JSONObject()
                                    .put("endpoint", ep.endpoint)
                                    .put("macAddress", ep.macAddress)
                                    .put("transportSource", ep.transportSource)
                                    .put("sequenceNumber", ep.sequenceNumber)
                                    .put("isDhcpDrift", ep.isDhcpDrift)
                                    .put("firstSeenMs", ep.firstSeenMs)
                                    .put("lastSeenMs", ep.lastSeenMs)
                            )
                        }
                    }
                )
                put(
                    "rootCauseFindings",
                    JSONArray().apply {
                        state.rootCauseFindings.forEach { f ->
                            put(
                                JSONObject()
                                    .put("sequenceNumber", f.sequenceNumber)
                                    .put("timestampMs", f.timestampMs)
                                    .put("category", f.category)
                                    .put("source", f.source)
                                    .put("explanation", f.explanation)
                            )
                        }
                    }
                )
            }.toString(2)
        } else {
            buildString {
                appendLine("# Clara Deterministic Timeline Replay Report")
                appendLine("- **Device:** ${state.displayName} (`${state.canonicalGuid}`)")
                appendLine("- **Scrubbed Position:** Sequence `#${state.selectedSequenceNumber}` of `#${state.maxSequenceNumber}` (${formatReplayTimestamp(state.selectedEvent.timestampMs)})")
                appendLine("- **Lifecycle Transition:** `${state.selectedEvent.event}` (`${state.previousLifecycleState}` -> `${state.lifecycleState}`)")
                appendLine("- **Active Transport / Driver:** `${state.activeTransport}` (${state.vendorDriver})")
                appendLine("- **Auth State:** `${state.authStatus.label}` — ${state.authDetail}")
                appendLine("- **Active Endpoint:** `${state.activeEndpoint.ifBlank { "None / Cloud-Only" }}` • MAC: `${state.learnedMacAddress.ifBlank { "Unlearned" }}`")
                appendLine("- **Discovery Confidence:** Latest ${(state.latestConfidenceScore * 100).roundToInt()}% (Peak ${(state.peakConfidenceScore * 100).roundToInt()}%)")
                appendLine("- **Commands (Seq 1..#${state.selectedSequenceNumber}):** Sent=${state.commandsSentCount}, ACK=${state.commandsAckedCount}, Failed=${state.commandsFailedCount}, Bounces=${state.stateBounceCount}")
                state.activeCapabilitySnapshot?.let { snap ->
                    appendLine("- **Capability Schema:** v${snap.schemaVersion} (${snap.capabilityCount} DPs • Hash `${snap.schemaHash.take(12)}`)")
                }
                appendLine()
                appendLine("## Endpoint History (Up to Seq #${state.selectedSequenceNumber})")
                if (state.endpointHistory.isEmpty()) {
                    appendLine("- _No IPv4/port endpoints recorded up to this sequence._")
                } else {
                    state.endpointHistory.forEach { ep ->
                        val driftTag = if (ep.isDhcpDrift) " [DHCP DRIFT]" else ""
                        appendLine("- `${ep.endpoint}` via `${ep.transportSource}` (Seq #${ep.sequenceNumber}, ${formatReplayTimestamp(ep.firstSeenMs)})$driftTag")
                    }
                }
                appendLine()
                appendLine("## Root-Cause Findings (Up to Seq #${state.selectedSequenceNumber})")
                if (state.rootCauseFindings.isEmpty()) {
                    appendLine("- Zero failures recorded through sequence #${state.selectedSequenceNumber}.")
                } else {
                    state.rootCauseFindings.forEach { f ->
                        appendLine("- **[Seq #${f.sequenceNumber} | ${f.category}]** `${f.source}`: ${f.explanation}")
                    }
                }
            }
        }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("ClaraTimelineReplay", content))

        val outFile = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            if (asJson) "clara_timeline_replay.json" else "clara_timeline_replay.md"
        )
        runCatching { outFile.writeText(content) }

        if (launchShareSheet) {
            runCatching {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Clara Timeline Replay (${state.displayName})")
                    putExtra(Intent.EXTRA_TEXT, content)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(
                    Intent.createChooser(share, "Export Timeline Replay").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
        return "Exported seq #${state.selectedSequenceNumber}/${state.maxSequenceNumber} replay to ${outFile.name} & clipboard"
    }
}

// ---------------------------------------------------------------------------
// 3. INTERACTIVE DETERMINISTIC TIMELINE REPLAY SCRUBBER UI
// ---------------------------------------------------------------------------
@Composable
fun DeterministicTimelineReplayPanel(
    initialDeviceGuid: String? = null,
    onStatusMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val liveLifecycleEvents by DeviceLifecycleRecorder.events.collectAsState()
    val liveBlackBoxEvents by DeviceBlackBoxRecorder.events.collectAsState()
    val liveDiscoveryEvents by DiscoveryFlightRecorder.events.collectAsState()
    val liveCapSnapshots by DeviceLifecycleRecorder.capabilitySnapshots.collectAsState()

    val replayableDevices = remember(liveLifecycleEvents, liveBlackBoxEvents) {
        DiscoveryReplayEngine.listReplayableDevices(context)
    }

    val resolvedInitialGuid = remember(initialDeviceGuid, replayableDevices) {
        val candidate = initialDeviceGuid?.takeIf { it.isNotBlank() }
            ?.let { DeviceLifecycleRecorder.resolveCanonicalGuid(it) }
        when {
            candidate != null && replayableDevices.any { it.canonicalGuid == candidate } -> candidate
            else -> replayableDevices.firstOrNull()?.canonicalGuid
        }
    }

    var selectedGuid by remember(resolvedInitialGuid) { mutableStateOf(resolvedInitialGuid) }
    val selectedSummary = remember(selectedGuid, replayableDevices) {
        replayableDevices.firstOrNull { it.canonicalGuid == selectedGuid } ?: replayableDevices.firstOrNull()
    }
    val effectiveGuid = selectedSummary?.canonicalGuid

    var scrubbedSeq by remember(effectiveGuid) {
        mutableLongStateOf(selectedSummary?.maxSequenceNumber ?: 1L)
    }

    LaunchedEffect(effectiveGuid, selectedSummary?.maxSequenceNumber) {
        val maxNow = selectedSummary?.maxSequenceNumber ?: 1L
        if (scrubbedSeq > maxNow || scrubbedSeq < 1L) {
            scrubbedSeq = maxNow
        }
    }

    val projectedState = remember(
        effectiveGuid,
        scrubbedSeq,
        liveLifecycleEvents,
        liveBlackBoxEvents,
        liveDiscoveryEvents,
        liveCapSnapshots
    ) {
        if (effectiveGuid.isNullOrBlank()) {
            null
        } else {
            DiscoveryReplayEngine.loadAndProjectDevice(
                context = context,
                deviceIdOrGuid = effectiveGuid,
                targetSequenceNumber = scrubbedSeq
            )
        }
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.UK) }

    Column(modifier = modifier.fillMaxSize()) {
        if (replayableDevices.isEmpty() || projectedState == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF181B23))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No recorded device lifecycle sequences found yet. Run a real discovery scan, onboard a device, or toggle any hardware control to populate deterministic replay history.",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp
                )
            }
            return@Column
        }

        // 1. Real Household Device Selector Row
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(replayableDevices, key = { it.canonicalGuid }) { dev ->
                val isSelected = dev.canonicalGuid == projectedState.canonicalGuid
                val shortId = dev.canonicalGuid.removePrefix("guid::").takeLast(12)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isSelected) Color(0xFF065F46) else Color(0xFF191C24),
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) Color(0xFF34D399) else Color(0xFF2A2F3D)
                    ),
                    modifier = Modifier.clickable {
                        selectedGuid = dev.canonicalGuid
                        scrubbedSeq = dev.maxSequenceNumber
                    }
                ) {
                    Text(
                        text = "${dev.displayName} [$shortId • #${dev.maxSequenceNumber}]",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 2. Interactive Sequence Scrubber Control Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF181B23),
            border = BorderStroke(1.dp, Color(0xFF2D3342)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sequence #${projectedState.selectedSequenceNumber} of #${projectedState.maxSequenceNumber}  •  ${projectedState.selectedEvent.event}",
                            color = Color(0xFF34D399),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${timeFmt.format(Date(projectedState.selectedEvent.timestampMs))} • ${projectedState.canonicalGuid} • Code: ${projectedState.selectedEvent.protocolResultCode}",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { scrubbedSeq = 1L },
                            enabled = projectedState.selectedSequenceNumber > 1L,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("|\u25C0 #1", color = Color.White, fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { scrubbedSeq = (projectedState.selectedSequenceNumber - 1L).coerceAtLeast(1L) },
                            enabled = projectedState.selectedSequenceNumber > 1L,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("\u25C0 Prev", color = Color.White, fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                scrubbedSeq = (projectedState.selectedSequenceNumber + 1L)
                                    .coerceAtMost(projectedState.maxSequenceNumber)
                            },
                            enabled = projectedState.selectedSequenceNumber < projectedState.maxSequenceNumber,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("Next \u25B6", color = Color.White, fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { scrubbedSeq = projectedState.maxSequenceNumber },
                            enabled = projectedState.selectedSequenceNumber < projectedState.maxSequenceNumber,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("Latest #${projectedState.maxSequenceNumber} \u25B6|", color = Color(0xFF34D399), fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                val msg = DiscoveryReplayEngine.exportProjectedTimelineReport(
                                    context = context,
                                    state = projectedState,
                                    asJson = true,
                                    launchShareSheet = false
                                )
                                onStatusMessage(msg)
                            },
                            border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("Export JSON", color = Color(0xFF38BDF8), fontSize = 11.sp)
                        }
                    }
                }

                if (projectedState.maxSequenceNumber > 1L) {
                    Slider(
                        value = projectedState.selectedSequenceNumber.toFloat(),
                        onValueChange = { next ->
                            scrubbedSeq = next.roundToInt().toLong()
                                .coerceIn(1L, projectedState.maxSequenceNumber)
                        },
                        valueRange = 1f..projectedState.maxSequenceNumber.toFloat(),
                        steps = (projectedState.maxSequenceNumber.toInt() - 2).coerceIn(0, 200),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF34D399),
                            activeTrackColor = Color(0xFF34D399),
                            inactiveTrackColor = Color(0xFF334155)
                        ),
                        modifier = Modifier.fillMaxWidth().height(24.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 3. Reconstructed State Details at Scrubbed Sequence
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Card A: Operational State, Auth Status & Transport
            item {
                val authColor = Color(projectedState.authStatus.badgeColorHex or 0xFF000000L)
                ReplaySectionCard(title = "RECONSTRUCTED STATE & AUTHENTICATION AT SEQ #${projectedState.selectedSequenceNumber}") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${projectedState.previousLifecycleState}  ->  ${projectedState.lifecycleState}",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = authColor.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, authColor.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = projectedState.authStatus.label,
                                color = authColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Text(
                        text = "Transport: ${projectedState.activeTransport} (${projectedState.vendorDriver}) • Latency: ${projectedState.selectedEvent.latencyMs}ms",
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp
                    )
                    Text(
                        text = projectedState.authDetail,
                        color = Color(0xFF94A3B8),
                        fontSize = 11.5.sp
                    )
                }
            }

            // Card B: Endpoint & DHCP History
            item {
                ReplaySectionCard(title = "ENDPOINT & MAC HISTORY (SEQ 1..#${projectedState.selectedSequenceNumber})") {
                    Text(
                        text = "Active Endpoint: ${projectedState.activeEndpoint.ifBlank { "None / Cloud-Only" }}  •  MAC: ${projectedState.learnedMacAddress.ifBlank { "Unlearned" }}",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                    if (projectedState.endpointHistory.isEmpty()) {
                        Text(
                            text = "No local IPv4/port endpoint observed up to sequence #${projectedState.selectedSequenceNumber}.",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.5.sp
                        )
                    } else {
                        projectedState.endpointHistory.forEach { ep ->
                            val driftLabel = if (ep.isDhcpDrift) "  [DHCP IP DRIFT]" else ""
                            Text(
                                text = "• [Seq #${ep.sequenceNumber} @ ${timeFmt.format(Date(ep.firstSeenMs))}] ${ep.endpoint} via ${ep.transportSource}$driftLabel",
                                color = if (ep.isDhcpDrift) Color(0xFFFBBF24) else Color(0xFFE2E8F0),
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Card C: Command Outcomes & State Bounces
            item {
                ReplaySectionCard(title = "COMMAND OUTCOMES (SENT=${projectedState.commandsSentCount} • ACK=${projectedState.commandsAckedCount} • FAILED=${projectedState.commandsFailedCount} • BOUNCED=${projectedState.stateBounceCount})") {
                    Text(
                        text = "Latest Command: ${projectedState.lastCommandOutcomeSummary}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    projectedState.commandOutcomes.takeLast(8).reversed().forEach { cmd ->
                        val cColor = when (cmd.outcome) {
                            "ACK_CONFIRMED" -> Color(0xFF10B981)
                            "FAILED" -> Color(0xFFEF4444)
                            "STATE_BOUNCED" -> Color(0xFFF59E0B)
                            else -> Color(0xFF38BDF8)
                        }
                        Text(
                            text = "• [Seq #${cmd.sequenceNumber} | ${timeFmt.format(Date(cmd.timestampMs))}] ${cmd.actionCode} -> ${cmd.outcome} (${cmd.latencyMs}ms, state=${cmd.observedState}) ${cmd.failureDetail}".trim(),
                            color = cColor,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Card D: Versioned Capability Schema Snapshot
            item {
                val snap = projectedState.activeCapabilitySnapshot
                val diff = projectedState.capabilitySchemaDiff
                ReplaySectionCard(
                    title = if (snap != null) {
                        "CAPABILITY SCHEMA SNAPSHOT v${snap.schemaVersion} (${snap.capabilityCount} DPs • HASH ${snap.schemaHash.take(10)})"
                    } else {
                        "CAPABILITY SCHEMA SNAPSHOT (NONE LOADED AT SEQ #${projectedState.selectedSequenceNumber})"
                    }
                ) {
                    if (snap == null) {
                        Text(
                            text = "Capabilities had not been introspected yet (or were cleared by formal Unbind) at sequence #${projectedState.selectedSequenceNumber}.",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.5.sp
                        )
                    } else {
                        if (diff != null && diff.hasChanges) {
                            Text(
                                text = "Schema evolved v${diff.fromVersion} -> v${diff.toVersion}: +${diff.addedKeys.size} added (${diff.addedKeys.joinToString()}), ~${diff.changedKeys.size} modified, -${diff.removedKeys.size} removed",
                                color = Color(0xFFFBBF24),
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(snap.capabilities, key = { "${it.key}_${it.dpId}" }) { cap ->
                                val valSuffix = if (cap.currentValue.isNotBlank()) "=${cap.currentValue}${cap.unit}" else ""
                                val dpPrefix = if (cap.dpId.isNotBlank()) "DP${cap.dpId}:" else ""
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF232836),
                                    border = BorderStroke(1.dp, Color(0xFF334155))
                                ) {
                                    Text(
                                        text = "$dpPrefix${cap.key}$valSuffix",
                                        color = Color(0xFFE2E8F0),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Card E: Discovery Confidence Progression
            item {
                val latestPct = (projectedState.latestConfidenceScore * 100f).roundToInt()
                val peakPct = (projectedState.peakConfidenceScore * 100f).roundToInt()
                ReplaySectionCard(title = "DISCOVERY CONFIDENCE PROGRESSION (LATEST ${latestPct}% • PEAK ${peakPct}%)") {
                    if (projectedState.confidenceProgression.isEmpty()) {
                        Text(
                            text = "No correlated raw discovery packets retained in telemetry window prior to seq #${projectedState.selectedSequenceNumber}.",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.5.sp
                        )
                    } else {
                        projectedState.confidenceProgression.takeLast(8).forEach { pt ->
                            val pct = (pt.confidenceScore * 100f).roundToInt()
                            Text(
                                text = "• [${timeFmt.format(Date(pt.timestampMs))}] ${pt.transportSource} | ${pt.qualificationStage} (${pct}%) — ${pt.outcomeReason}",
                                color = Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Card F: Deterministic Failure Root-Cause Ledger
            item {
                ReplaySectionCard(title = "FAILURE ROOT CAUSES THROUGH SEQ #${projectedState.selectedSequenceNumber} (${projectedState.rootCauseFindings.size})") {
                    if (projectedState.rootCauseFindings.isEmpty()) {
                        Text(
                            text = "Zero failures or state bounces recorded from sequence #1 through #${projectedState.selectedSequenceNumber}.",
                            color = Color(0xFF34D399),
                            fontSize = 12.sp
                        )
                    } else {
                        projectedState.rootCauseFindings.take(12).forEach { f ->
                            Text(
                                text = "• [Seq #${f.sequenceNumber} @ ${timeFmt.format(Date(f.timestampMs))}] [${f.category}] ${f.source}: ${f.explanation}",
                                color = Color(0xFFFCA5A5),
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplaySectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF181B23))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = title,
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
fun DeterministicTimelineReplayModal(
    initialDeviceGuid: String? = null,
    onDismiss: () -> Unit
) {
    var bannerText by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.86f),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF12141A),
            border = BorderStroke(1.dp, Color(0xFF2B303C))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Deterministic Event-Sourced Timeline Replay",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Scrub any real device's sequence history to reconstruct exact IP, auth, capability schema & command truth",
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF262A34))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("\u2715", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }

                bannerText?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = msg,
                        color = Color(0xFF34D399),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF064E3B).copy(alpha = 0.45f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                DeterministicTimelineReplayPanel(
                    initialDeviceGuid = initialDeviceGuid,
                    onStatusMessage = { bannerText = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}