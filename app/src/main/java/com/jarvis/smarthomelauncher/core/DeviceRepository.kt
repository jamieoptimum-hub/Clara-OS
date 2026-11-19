package com.jarvis.smarthomelauncher.core

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.db.toEntity
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import com.jarvis.smarthomelauncher.core.ClaraSecretVault
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ConnectedIdentityIndex {
    private val ipv4LiteralRegex = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?$")
    private val knownGuidToTileIds = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    private val knownDeviceIdToTileIds = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    private val knownMacVariants = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    private fun appendTileId(
        map: java.util.concurrent.ConcurrentHashMap<String, Set<String>>,
        key: String,
        tileId: String
    ) {
        if (key.isBlank() || tileId.isBlank()) return
        map.compute(key) { _, existing ->
            if (existing == null) setOf(tileId) else existing + tileId
        }
    }

    fun isBareIpLiteral(raw: String?): Boolean {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        if (ipv4LiteralRegex.matches(trimmed)) return true
        if (trimmed.startsWith("[") && trimmed.contains("]")) return true
        return trimmed.count { it == ':' } >= 2 && CrossEcosystemIdentityCorrelator.normalizeMac(trimmed).isEmpty()
    }

    /**
     * Onboarding Section 1: Computes both last-octet and full 48-bit integer +1/-1/+2/-2
     * BLE-to-Wi-Fi combo MAC variants (handling carry across byte boundaries such as ..:FF -> ..:00)
     * so already-onboarded combo plugs stay hidden across BLE and LAN scans.
     */
    fun computeComboMacOffsetVariants(rawMac: String): Set<String> =
        CrossEcosystemIdentityCorrelator.computeMacOffsetVariants(rawMac)

    fun rebuildFromTiles(tiles: List<SmartTile>) {
        knownGuidToTileIds.clear()
        knownDeviceIdToTileIds.clear()
        knownMacVariants.clear()
        for (tile in tiles) {
            if (!tile.subtype.isHardwareSubtype) continue
            // Unbound tiles are discoverable again; active, offline, and hidden UNPAIRED tiles stay indexed
            if (tile.lifecycleState == DeviceLifecycleState.UNBOUND ||
                tile.lifecycleState == DeviceLifecycleState.DISCOVERABLE_AGAIN
            ) continue

            val resolvedGuid = tile.canonicalGuid.ifBlank { tile.resolvedCanonicalGuid() }
            if (resolvedGuid.isNotBlank() && !resolvedGuid.startsWith("guid::unresolved::")) {
                appendTileId(knownGuidToTileIds, resolvedGuid, tile.id)
            }
            val secondaryGuid = tile.resolvedCanonicalGuid()
            if (secondaryGuid.isNotBlank() && !secondaryGuid.startsWith("guid::unresolved::")) {
                appendTileId(knownGuidToTileIds, secondaryGuid, tile.id)
            }

            for (candidateId in listOf(tile.deviceId, tile.serialNumber, tile.entityId, tile.vehicleVin)) {
                val cleanId = candidateId.trim().lowercase(Locale.US)
                if (cleanId.isNotEmpty() && !cleanId.startsWith("lan_") && !isBareIpLiteral(cleanId)) {
                    appendTileId(knownDeviceIdToTileIds, cleanId, tile.id)
                }
            }

            val effectiveMac = CrossEcosystemIdentityCorrelator.normalizeMac(
                tile.macAddress.ifBlank { tile.serialNumber }
            )
            for (macVariant in computeComboMacOffsetVariants(effectiveMac)) {
                appendTileId(knownMacVariants, macVariant, tile.id)
            }
        }
    }

    fun isAlreadyOnboarded(
        providerId: String,
        rawDeviceId: String,
        macAddress: String = "",
        serialNumber: String = "",
        cloudDeviceId: String = ""
    ): Boolean {
        if (findMatchingTileIdsByMacOrId(
                rawDeviceId = rawDeviceId,
                macAddress = macAddress,
                providerId = providerId,
                serialNumber = serialNumber,
                cloudDeviceId = cloudDeviceId
            ).isNotEmpty()
        ) {
            return true
        }
        for (macVariant in computeComboMacOffsetVariants(macAddress)) {
            val persistedGuid = CrossEcosystemIdentityCorrelator.resolveCanonicalGuidForCandidate(rawMac = macVariant)
            if (!persistedGuid.isNullOrBlank() && !knownGuidToTileIds[persistedGuid].isNullOrEmpty()) {
                return true
            }
        }
        return false
    }

    fun findMatchingTileIdsByMacOrId(
        rawDeviceId: String,
        macAddress: String,
        providerId: String = "",
        serialNumber: String = "",
        cloudDeviceId: String = ""
    ): Set<String> {
        val matched = LinkedHashSet<String>()

        if (providerId.isNotBlank()) {
            val candidateGuid = CrossEcosystemIdentityCorrelator.resolveCanonicalGuid(
                activeProviderId = providerId,
                rawDeviceId = rawDeviceId.takeUnless { isBareIpLiteral(it) }.orEmpty(),
                macAddress = macAddress,
                hardwareSerial = serialNumber.takeUnless { isBareIpLiteral(it) }.orEmpty()
            )
            if (!candidateGuid.startsWith("guid::unresolved::")) {
                knownGuidToTileIds[candidateGuid]?.let { matched.addAll(it) }
            }
        }

        for (macVariant in computeComboMacOffsetVariants(macAddress)) {
            knownMacVariants[macVariant]?.let { matched.addAll(it) }
        }

        for (candidate in listOf(rawDeviceId, serialNumber, cloudDeviceId)) {
            val clean = candidate.trim().lowercase(Locale.US)
            if (clean.isNotEmpty() && !clean.startsWith("lan_") && !isBareIpLiteral(clean)) {
                knownDeviceIdToTileIds[clean]?.let { matched.addAll(it) }
            }
        }

        val correlatedGuid = CrossEcosystemIdentityCorrelator.resolveCanonicalGuidForCandidate(
            rawMac = macAddress,
            hardwareSerial = serialNumber.takeUnless { isBareIpLiteral(it) }
                ?: rawDeviceId.takeUnless { isBareIpLiteral(it) },
            tuyaGwId = cloudDeviceId.ifBlank { rawDeviceId }.takeUnless { isBareIpLiteral(it) },
            haEntityId = cloudDeviceId.ifBlank { rawDeviceId }.takeUnless { isBareIpLiteral(it) },
            cloudDeviceId = cloudDeviceId.ifBlank { rawDeviceId }.takeUnless { isBareIpLiteral(it) }
        )
        if (correlatedGuid != null) {
            knownGuidToTileIds[correlatedGuid]?.let { matched.addAll(it) }
        }

        return matched
    }

    fun findMatchingTileIdByMacOrId(
        rawDeviceId: String,
        macAddress: String,
        providerId: String = "",
        serialNumber: String = "",
        cloudDeviceId: String = ""
    ): String? = findMatchingTileIdsByMacOrId(
        rawDeviceId = rawDeviceId,
        macAddress = macAddress,
        providerId = providerId,
        serialNumber = serialNumber,
        cloudDeviceId = cloudDeviceId
    ).firstOrNull()
}

object CredentialVaultRepository {
    fun readSecret(context: Context, key: String, defaultValue: String = ""): String {
        val appCtx = ClaraApplication.requireAppContext(context)
        return com.jarvis.smarthomelauncher.core.ClaraSecretVault
            .getString(appCtx, key, defaultValue)
            .orEmpty()
    }

    fun writeSecret(context: Context, key: String, value: String) {
        val appCtx = ClaraApplication.requireAppContext(context)
        com.jarvis.smarthomelauncher.core.ClaraSecretVault.putString(appCtx, key, value)
    }
}

fun recordManualHoldForTile(tileId: String, holdMs: Long = 30 * 60 * 1000L) {
    // Tile holds are now tracked on the SmartTile object (manualHoldUntilEpochMs).
}

// DeviceStateRepository removed as per Phase 2.7

object DeviceRepository {
    private const val SNAPSHOT_SUFFIX = "__verified_backup_snapshot"



    fun <T> decodeJsonWithSnapshotFallback(
        prefs: android.content.SharedPreferences,
        key: String,
        parser: (String) -> List<T>
    ): List<T> {
        val primaryRaw = prefs.getString(key, null)
        if (primaryRaw.isNullOrBlank()) return emptyList()
        return try {
            val parsed = parser(primaryRaw)
            if (parsed.isNotEmpty()) {
                prefs.edit().putString("$key$SNAPSHOT_SUFFIX", primaryRaw).apply()
            }
            parsed
        } catch (primaryError: Exception) {
            val backupRaw = prefs.getString("$key$SNAPSHOT_SUFFIX", null)
            if (!backupRaw.isNullOrBlank()) {
                try {
                    val restored = parser(backupRaw)
                    prefs.edit().putString(key, backupRaw).apply()
                    JarvisLiveBridge.recordDeserializationCorruption(
                        storeKey = key,
                        errorMessage = primaryError.message ?: "Malformed JSON",
                        restoredFromSnapshot = true
                    )
                    return restored
                } catch (_: Exception) {
                }
            }
            JarvisLiveBridge.recordDeserializationCorruption(
                storeKey = key,
                errorMessage = primaryError.message ?: "Malformed JSON (no backup available)",
                restoredFromSnapshot = false
            )
            emptyList()
        }
    }

    private val pendingAckUntilByTileId = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun markTilePendingAck(tileId: String, windowMs: Long = 15_000L) {
        if (tileId.isBlank()) return
        pendingAckUntilByTileId[tileId] = System.currentTimeMillis() + windowMs
        // stateAuthorityByTileId logic is replaced by reading SmartTile's tier directly.
    }

    fun clearPendingAck(tileId: String) {
        if (tileId.isBlank()) return
        pendingAckUntilByTileId.remove(tileId)
    }

    fun confirmTileHardwareAck(tileId: String) {
        if (tileId.isBlank()) return
        pendingAckUntilByTileId.remove(tileId)
    }

    fun isTilePendingAckLocked(tileId: String): Boolean {
        val until = pendingAckUntilByTileId[tileId] ?: return false
        if (System.currentTimeMillis() < until) return true
        pendingAckUntilByTileId.remove(tileId)
        return false
    }

    fun recordManualHoldForTile(tileId: String, holdMs: Long = 30 * 60 * 1000L) {
        // Tile holds are now tracked on the SmartTile object (manualHoldUntilEpochMs).
    }

    fun isTileInManualHoldWindow(tileId: String): Boolean {
        // Look up the actual tile instead.
        val cached = cachedTileJsonById[tileId]?.first ?: return false
        return cached.manualHoldUntilEpochMs > System.currentTimeMillis()
    }

    fun canApplyPolledStateToTile(tileId: String, tier: StateAuthorityTier): Boolean {
        val tile = cachedTileJsonById[tileId]?.first ?: return true
        return tier.canOverwrite(tile.stateAuthorityTier, isTilePendingAckLocked(tileId))
    }

    fun healExistingTileIpIfMatched(
        context: Context,
        rawDeviceId: String,
        macAddress: String,
        newIpAddress: String,
        newPort: Int = 0
    ): Boolean {
        val cleanIp = newIpAddress.substringBefore(":").trim()
        if (cleanIp.isEmpty() || cleanIp == "0.0.0.0" || cleanIp == "127.0.0.1") return false
        val tiles = loadTiles(context)
        ConnectedIdentityIndex.rebuildFromTiles(tiles)

        val safeRawId = rawDeviceId.trim().takeUnless { ConnectedIdentityIndex.isBareIpLiteral(it) }.orEmpty()
        val matchedTileIds = ConnectedIdentityIndex.findMatchingTileIdsByMacOrId(
            rawDeviceId = safeRawId,
            macAddress = macAddress,
            serialNumber = safeRawId,
            cloudDeviceId = safeRawId
        )
        if (matchedTileIds.isEmpty()) return false

        val targetIndices = tiles.indices.filter { tiles[it].id in matchedTileIds }
        if (targetIndices.isEmpty()) return false

        val updatedGuids = HashSet<String>()
        val updatesByTileId = LinkedHashMap<String, Pair<String, String>>()
        var recordedTransition = false

        for (targetIdx in targetIndices) {
            val currentTile = tiles[targetIdx]
            val existingPort = EndpointSplitter.splitHostAndPort(currentTile.ipAddress).port
            val resolvedPort = if (newPort > 0) newPort else existingPort
            val formattedEndpoint = if (
                (currentTile.type == TileType.NEST_MINI || currentTile.type == TileType.SONOFF_SWITCH || currentTile.ipAddress.contains(":")) &&
                resolvedPort > 0
            ) {
                "$cleanIp:$resolvedPort"
            } else {
                cleanIp
            }
            val cleanMac = CrossEcosystemIdentityCorrelator.normalizeMac(macAddress).ifBlank { currentTile.macAddress }
            val canonGuid = currentTile.canonicalGuid.ifBlank { currentTile.resolvedCanonicalGuid() }

            if (updatedGuids.add(canonGuid)) {
                ClaraDeviceRegistryStore.getInstance(context).updateDeviceEndpointAndMac(
                    canonicalGuid = canonGuid,
                    newHostIp = cleanIp,
                    newPort = resolvedPort,
                    learnedMac = cleanMac
                )
            }

            if (currentTile.ipAddress != formattedEndpoint || currentTile.macAddress != cleanMac) {
                updatesByTileId[currentTile.id] = formattedEndpoint to cleanMac
                if (!recordedTransition && currentTile.ipAddress != formattedEndpoint) {
                    recordedTransition = true
                    val healedTile = currentTile.copy(ipAddress = formattedEndpoint, macAddress = cleanMac)
                    DeviceLifecycleRecorder.recordTileTransition(
                        tile = healedTile,
                        transition = MandatoryLifecycleTransition.REDISCOVERED,
                        previousState = currentTile.effectiveLifecycleState.name,
                        newState = healedTile.effectiveLifecycleState.name,
                        protocolResultCode = "DHCP_IP_HEALED_$cleanIp"
                    )
                }
            }
        }

        if (updatesByTileId.isEmpty()) return true

        val liveRef = UniversalOnboardingEngine.activeDashboardTilesRef
        if (liveRef != null) {
            repoScope.launch(Dispatchers.Main) {
                var anyLiveChanged = false
                for (liveIdx in liveRef.indices) {
                    val cur = liveRef[liveIdx]
                    val (ep, mac) = updatesByTileId[cur.id] ?: continue
                    liveRef[liveIdx] = cur.copy(ipAddress = ep, macAddress = mac)
                    anyLiveChanged = true
                }
                if (anyLiveChanged) {
                    saveTiles(context, liveRef)
                }
            }
        } else {
            val updatedTiles = tiles.toMutableList()
            for (idx in updatedTiles.indices) {
                val cur = updatedTiles[idx]
                val (ep, mac) = updatesByTileId[cur.id] ?: continue
                updatedTiles[idx] = cur.copy(ipAddress = ep, macAddress = mac)
            }
            saveTiles(context, updatedTiles)
        }
        return true
    }

    fun updateDeviceEndpointAndMac(
        context: Context,
        canonicalGuid: String,
        newHostIp: String,
        newPort: Int,
        learnedMac: String
    ) {
        val appCtx = context.applicationContext
        repoScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            ClaraDeviceRegistryStore.getInstance(appCtx).updateDeviceEndpointAndMac(
                canonicalGuid = canonicalGuid,
                newHostIp = newHostIp,
                newPort = newPort,
                learnedMac = learnedMac
            )
        }
    }
    private const val TAG = "ClaraDeviceRepo"
    private const val PREFS_NAME = "google_home_jarvis_prefs_v10"
    private const val BACKUP_ENC_PREFIX = "CLARA_BACKUP_V1:"

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Synchronization locks for thread-safe write-through caching (CRIT-04 fix)
    private val dismissedLock = Any()
    private val telemetryLock = Any()
    private val auditLock = Any()
    private val selfHealLock = Any()
    private val dvrLock = Any()
    private val automationLogLock = Any()
    private val habitLock = Any()

    @Volatile
    private var graphRebuildJob: Job? = null

    @Volatile
    private var tilesSaveJob: Job? = null

    private val cachedTileJsonById = java.util.concurrent.ConcurrentHashMap<String, Pair<SmartTile, JSONObject>>()

    @Volatile
    private var cachedTilesSnapshot: List<SmartTile>? = null

    @Volatile
    private var cachedBridgeSnapshot: BridgeSettings? = null

    @Volatile
    private var cachedRulesSnapshot: List<AutomationRule>? = null

    val tilesFlow = kotlinx.coroutines.flow.MutableStateFlow<List<SmartTile>>(emptyList())
    val bridgeSettingsFlow = kotlinx.coroutines.flow.MutableStateFlow(BridgeSettings())
    val automationRulesFlow = kotlinx.coroutines.flow.MutableStateFlow<List<AutomationRule>>(emptyList())

    @Volatile
    private var cachedTelemetryPoints: List<TelemetryDataPoint>? = null

    @Volatile
    private var cachedAuditLogs: List<SecurityAuditLogEntry>? = null

    @Volatile
    private var cachedSelfHealingLogs: List<SelfHealingActionLog>? = null

    @Volatile
    private var cachedDvrSnapshots: List<DvrEventSnapshot>? = null

    @Volatile
    private var cachedAutomationLogs: List<AutomationLogEntry>? = null

    @Volatile
    private var cachedHabitEvents: List<HabitActuationEvent>? = null

    @Volatile
    private var graphSequenceVersion: Long = 1L


    private const val KEY_DISMISSED_DEVICE_KEYS = "dismissed_device_keys_json_v1"
    private const val KEY_AUTOMATIONS_JSON = "automations_json_list_v1"
    private const val KEY_CAST_GROUPS_JSON = "cast_speaker_groups_json_v1"
    private const val KEY_HOME_GRAPH_JSON = "digital_home_graph_snapshot_json_v1"
    private const val KEY_DISMISSED_HABIT_IDS_JSON = "dismissed_habit_suggestion_ids_v1"
    private const val KEY_SEEDED_DEFAULT_RADIO_V1 = "has_seeded_default_radio_stations_v1"

    private fun scheduleAsyncGraphRebuild(
        context: Context,
        tiles: List<SmartTile>? = cachedTilesSnapshot,
        bridge: BridgeSettings? = cachedBridgeSnapshot,
        rules: List<AutomationRule>? = cachedRulesSnapshot
    ) {
        val appCtx = context.applicationContext
        if (tiles != null) cachedTilesSnapshot = tiles
        if (bridge != null) cachedBridgeSnapshot = bridge
        if (rules != null) cachedRulesSnapshot = rules
        graphRebuildJob?.cancel()
        graphRebuildJob = repoScope.launch {
            delay(180L)
            rebuildDigitalHomeGraph(
                context = appCtx,
                tiles = cachedTilesSnapshot,
                bridge = cachedBridgeSnapshot,
                rules = cachedRulesSnapshot
            )
        }
    }

    fun mergeCapabilitiesWithSchemaEvolution(
        existing: SmartTile,
        incomingCapabilities: List<DeviceCapabilityDescriptor>
    ): Pair<List<DeviceCapabilityDescriptor>, String> {
        if (incomingCapabilities.isEmpty()) {
            val fallbackHash = existing.capabilitySchemaHash.ifBlank {
                CapabilitySchemaHasher.computeHash(existing.capabilities)
            }
            return existing.capabilities to fallbackHash
        }
        val incomingHash = CapabilitySchemaHasher.computeHash(incomingCapabilities)
        val existingHash = existing.capabilitySchemaHash.ifBlank {
            CapabilitySchemaHasher.computeHash(existing.capabilities)
        }
        val existingByKey = existing.capabilities.associateBy { it.key.lowercase(Locale.US) }
        val merged = incomingCapabilities.map { inc ->
            val prev = existingByKey[inc.key.lowercase(Locale.US)]
            if (prev != null) {
                inc.copy(
                    isPinnedToHero = prev.isPinnedToHero,
                    label = prev.label.ifBlank { inc.label },
                    rendererType = prev.rendererType,
                    presets = if (inc.presets.isEmpty()) prev.presets else inc.presets
                )
            } else {
                inc
            }
        }
        if (existingHash.isNotBlank() && incomingHash != existingHash) {
            if (existing.isConfiguredAndLinked && existing.serviceClass == null && existing.deviceSubtype.isHardwareSubtype) {
                DeviceLifecycleRecorder.recordTileTransition(
                    tile = existing.copy(
                        capabilities = merged,
                        capabilitySchemaHash = incomingHash
                    ),
                    transition = MandatoryLifecycleTransition.CAPABILITY_RECEIVED,
                    previousState = existing.effectiveLifecycleState.name,
                    newState = existing.effectiveLifecycleState.name,
                    protocolResultCode = "SCHEMA_EVOLVED_${incomingHash.take(8)}"
                )
            }
            JarvisLiveBridge.appendDeviceEvent(
                DeviceEvent(
                    deviceId = existing.id,
                    deviceTitle = existing.title,
                    roomName = existing.roomOrSubtitle,
                    eventType = DeviceEventType.CAPABILITY_EVOLVED,
                    source = existing.type.brandName,
                    actorName = "Capability Engine",
                    stateVersion = existing.stateVersion + 1L,
                    previousStateSummary = "${existing.capabilities.size} capabilities (${existingHash.take(8)})",
                    newStateSummary = "${merged.size} capabilities (${incomingHash.take(8)})"
                )
            )
        }
        return merged to incomingHash
    }

    /**
     * Stage 4 Outbound Intent Arbitration:
     * Determines whether an outbound command of [intentTier] is permitted to execute on [tile].
     * Suppresses background `AUTOMATION` and `SCHEDULED_TASK` rules when a 30-minute
     * `manualHoldUntilEpochMs` lockout window from `MANUAL_USER` or `PHYSICAL_DEVICE` is active.
     */
    fun canExecuteOutboundIntent(
        tile: SmartTile,
        intentTier: IntentAuthorityTier,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<Boolean, String> {
        val holdRemainingMs = (tile.manualHoldUntilEpochMs - nowMs).coerceAtLeast(0L)
        if (holdRemainingMs <= 0L) return true to ""

        return when (intentTier) {
            IntentAuthorityTier.SAFETY_CRITICAL -> true to ""
            IntentAuthorityTier.PHYSICAL_DEVICE -> {
                if (tile.lastIntentAuthorityTier == IntentAuthorityTier.SAFETY_CRITICAL) {
                    false to "Blocked by active Safety-Critical hold (${(holdRemainingMs / 60_000L) + 1}m remaining)."
                } else {
                    true to ""
                }
            }
            IntentAuthorityTier.MANUAL_USER -> {
                if (tile.lastIntentAuthorityTier == IntentAuthorityTier.SAFETY_CRITICAL) {
                    false to "Blocked by active Safety-Critical hold (${(holdRemainingMs / 60_000L) + 1}m remaining)."
                } else {
                    true to ""
                }
            }
            IntentAuthorityTier.RECOVERY -> {
                if (tile.lastIntentAuthorityTier.priorityRank <= IntentAuthorityTier.MANUAL_USER.priorityRank) {
                    false to "Recovery replay superseded by newer ${tile.lastIntentAuthorityTier.label} action."
                } else {
                    true to ""
                }
            }
            IntentAuthorityTier.AUTOMATION,
            IntentAuthorityTier.SCHEDULED_TASK -> {
                val minsLeft = ((holdRemainingMs / 60_000L) + 1L).coerceAtLeast(1L)
                false to "Suppressed by 30-minute manual hold (${tile.lastCausalActor.ifBlank { tile.lastIntentAuthorityTier.label }}, ${minsLeft}m remaining)."
            }
        }
    }

    /**
     * Stamps outbound intent authority, causal actor, and the capability-driven
     * `manualHoldUntilEpochMs` lockout window onto [tile] without inspecting DeviceClass.
     */
    fun stampOutboundIntentAuthority(
        tile: SmartTile,
        intentTier: IntentAuthorityTier,
        actorName: String,
        capabilityKey: String = "",
        nowMs: Long = System.currentTimeMillis()
    ): SmartTile {
        val matchedCapHoldMs = if (capabilityKey.isNotBlank()) {
            tile.capabilities.firstOrNull { it.key.equals(capabilityKey, ignoreCase = true) }?.holdDurationMs
        } else {
            tile.capabilities.maxOfOrNull { it.holdDurationMs }
        } ?: 0L
        val effectiveHoldWindowMs = if (intentTier.holdWindowMs > 0L) {
            maxOf(intentTier.holdWindowMs, matchedCapHoldMs)
        } else {
            0L
        }
        val nextHoldUntil = if (effectiveHoldWindowMs > 0L) {
            maxOf(tile.manualHoldUntilEpochMs, nowMs + effectiveHoldWindowMs)
        } else {
            tile.manualHoldUntilEpochMs
        }
        return tile.copy(
            lastUserActuationEpochMs = nowMs,
            manualHoldUntilEpochMs = nextHoldUntil,
            lastIntentAuthorityTier = intentTier,
            lastCausalActor = actorName.ifBlank { intentTier.label }
        )
    }

    private fun summarizeTileState(tile: SmartTile): String {
        if (!tile.isOnline) return "Offline"
        val hasThermostatCap = tile.isThermostatTile || tile.capabilities.any {
            SemanticClassifier.inferControlRole(it) == SemanticControlRole.THERMOSTAT_TEMPERATURE_DIAL
        }
        return when {
            hasThermostatCap -> "${if (tile.isActive) tile.hvacMode else "OFF"} (${"%.1f".format(Locale.US, tile.targetTemperature)}\u00B0C)"
            tile.isMultiGangSwitch -> "${tile.socket1Name}:${if (tile.isActive) "ON" else "OFF"}, ${tile.socket2Name}:${if (tile.isSocket2Active) "ON" else "OFF"}"
            tile.isVehicleTile -> if (tile.vehicleLocked) "Locked" else "Unlocked"
            else -> if (tile.isActive) "ON" else "OFF"
        }
    }

    fun buildObservedBehaviourProfile(deviceId: String, currentWatts: Float): ObservedBehaviourProfile? {
        val points = cachedTelemetryPoints?.filter {
            it.deviceId == deviceId && it.metricKey.equals("power_w", ignoreCase = true)
        }.orEmpty()
        if (points.size < 2 && currentWatts < 1200f) return null
        val peak = maxOf(currentWatts, points.maxOfOrNull { it.value } ?: 0f)
        if (peak < 1200f) return null
        val highLoadPoints = points.filter { it.value >= 1000f }
        val oldestHighMs = highLoadPoints.minOfOrNull { it.timestampEpochMs } ?: System.currentTimeMillis()
        val newestHighMs = highLoadPoints.maxOfOrNull { it.timestampEpochMs } ?: System.currentTimeMillis()
        val durationSec = ((newestHighMs - oldestHighMs) / 1000L).toInt().coerceAtLeast(90)
        val dropsToZero = currentWatts < 15f && peak >= 2150f
        val hasCycling = points.size >= 4 && points.zipWithNext().count { (a, b) ->
            kotlin.math.abs(a.value - b.value) >= 800f
        } >= 2
        return ObservedBehaviourProfile(
            peakWatts = peak,
            averageCycleDurationSeconds = durationSec,
            hasThermostaticCycling = hasCycling,
            dropsToZeroAfterBurst = dropsToZero || peak >= 2150f,
            confidence = if (points.size >= 3) 0.90f else 0.78f
        )
    }

    /**
     * Stage 4 Inbound State Authority Reconciliation:
     * - Enforces `StateAuthorityTier` priority (`DEVICE_HARDWARE_TRUTH` > `PRIMARY_MESH_AUTHORITY` > `SECONDARY_MESH_NODE` > `CACHED_UI_STATE`).
     * - Prevents stale background polls from overwriting a pending user actuation within [guardWindowMs].
     * - Prevents uninitialized 60% fallback volume from overwriting actual LG TV volume when standby/waking.
     * - Detects physical device actuations and activates a capability-driven automation lockout window.
     */
    fun reconcileRemoteTileUpdate(
        existing: SmartTile,
        incoming: SmartTile,
        guardWindowMs: Long = 6_000L,
        incomingAuthority: StateAuthorityTier = StateAuthorityTier.DEVICE_HARDWARE_TRUTH,
        sourceName: String = incoming.type.brandName
    ): SmartTile {
        val now = System.currentTimeMillis()

        // 1. Inbound Authority Check: Lower authority tiers never overwrite fresher higher-authority state
        if (incomingAuthority == StateAuthorityTier.CACHED_UI_STATE &&
            existing.stateAuthorityTier.priorityRank < StateAuthorityTier.CACHED_UI_STATE.priorityRank
        ) {
            return existing
        }
        if (incomingAuthority == StateAuthorityTier.CLOUD_SHADOW_STATE &&
            existing.stateAuthorityTier == StateAuthorityTier.DEVICE_HARDWARE_TRUTH &&
            (now - existing.lastConfirmedEpochMs) in 0L..15_000L
        ) {
            return existing
        }
        if (incomingAuthority == StateAuthorityTier.SECONDARY_MESH_NODE &&
            existing.stateAuthorityTier.priorityRank < StateAuthorityTier.SECONDARY_MESH_NODE.priorityRank &&
            incoming.stateOriginEpochMs < existing.stateOriginEpochMs
        ) {
            return existing
        }

        // 1B. LG TV & Speaker Standby Volume Protection:
        // Never allow an uninitialized 60% default on a standby/offline TV poll to overwrite the last known real TV volume.
        val resolvedIncomingVolume = if (
            (existing.type == TileType.LG_TV || existing.deviceSubtype == DeviceSubtype.TV) &&
            (!incoming.isActive || !incoming.isOnline || (incoming.volume == 60f && existing.volume != 60f))
        ) {
            existing.volume
        } else {
            incoming.volume
        }
        val sanitizedIncoming = (if (resolvedIncomingVolume != incoming.volume) {
            incoming.copy(volume = resolvedIncomingVolume).withUpdatedCapability("volume", resolvedIncomingVolume.toInt().toString())
        } else {
            incoming
        }).let { inc ->
            inc.copy(
                macAddress = inc.macAddress.ifBlank { existing.macAddress },
                vendorId = inc.vendorId.ifBlank { existing.vendorId },
                productId = inc.productId.ifBlank { existing.productId },
                canonicalGuid = inc.canonicalGuid.ifBlank { existing.canonicalGuid }
            )
        }

        // 2. Continuous Capability Schema Hashing & Evolution
        val (mergedCaps, mergedHash) = mergeCapabilitiesWithSchemaEvolution(existing, sanitizedIncoming.capabilities)

        val elapsedSinceUserTap = now - existing.lastUserActuationEpochMs
        val isWithinUserGuardWindow = existing.lastUserActuationEpochMs > 0L && elapsedSinceUserTap in 0L..guardWindowMs

        if (isWithinUserGuardWindow) {
            val stateMatchesDesired = existing.isActive == sanitizedIncoming.isActive &&
                    existing.isSocket2Active == sanitizedIncoming.isSocket2Active &&
                    (!existing.isThermostatTile || kotlin.math.abs(existing.targetTemperature - sanitizedIncoming.targetTemperature) <= 0.6f)
            return if (stateMatchesDesired) {
                val nextVer = maxOf(existing.stateVersion, sanitizedIncoming.stateVersion) + 1L
                val nextLifecycle = if (sanitizedIncoming.isOnline) DeviceLifecycleState.ONLINE else DeviceLifecycleState.OFFLINE
                val confirmedTile = sanitizedIncoming.copy(
                    capabilities = mergedCaps,
                    capabilitySchemaHash = mergedHash,
                    lifecycleState = nextLifecycle,
                    transactionState = CommandTransactionState.CONFIRMED,
                    stateVersion = nextVer,
                    stateOriginEpochMs = now,
                    stateAuthorityTier = incomingAuthority,
                    manualHoldUntilEpochMs = existing.manualHoldUntilEpochMs,
                    lastIntentAuthorityTier = existing.lastIntentAuthorityTier,
                    lastCausalActor = existing.lastCausalActor,
                    lastUserActuationEpochMs = existing.lastUserActuationEpochMs,
                    lastConfirmedEpochMs = now
                )
                if (existing.effectiveLifecycleState != nextLifecycle &&
                    confirmedTile.isConfiguredAndLinked &&
                    confirmedTile.serviceClass == null &&
                    confirmedTile.deviceSubtype.isHardwareSubtype
                ) {
                    DeviceLifecycleRecorder.recordTileTransition(
                        tile = confirmedTile,
                        transition = if (nextLifecycle == DeviceLifecycleState.ONLINE) {
                            MandatoryLifecycleTransition.ONLINE
                        } else {
                            MandatoryLifecycleTransition.OFFLINE
                        },
                        previousState = existing.effectiveLifecycleState.name,
                        newState = nextLifecycle.name,
                        protocolResultCode = "GUARD_RECONCILE_${nextLifecycle.name}",
                        failureCategory = if (nextLifecycle == DeviceLifecycleState.OFFLINE) FailureCategory.TRANSPORT_FAILURE else null
                    )
                }
                JarvisLiveBridge.updateDeviceLifecycleAndRecoveryStage(
                    tile = confirmedTile,
                    lifecycleState = nextLifecycle,
                    emitFlightRecord = false
                )
                confirmedTile
            } else {
                // Preserve pending user actuation target while accepting non-conflicting sensor telemetry
                sanitizedIncoming.copy(
                    isActive = existing.isActive,
                    isSocket2Active = existing.isSocket2Active,
                    targetTemperature = existing.targetTemperature,
                    hvacMode = existing.hvacMode,
                    volume = existing.volume,
                    vehicleLocked = existing.vehicleLocked,
                    fanSpeedLevel = existing.fanSpeedLevel,
                    fanMode = existing.fanMode,
                    fanOscillating = existing.fanOscillating,
                    lytmiHdmiInput = existing.lytmiHdmiInput,
                    lytmiSyncMode = existing.lytmiSyncMode,
                    lytmiColorArgb = existing.lytmiColorArgb,
                    capabilities = mergedCaps,
                    capabilitySchemaHash = mergedHash,
                    lifecycleState = existing.lifecycleState,
                    transactionState = existing.transactionState,
                    stateVersion = existing.stateVersion,
                    stateOriginEpochMs = existing.stateOriginEpochMs,
                    stateAuthorityTier = existing.stateAuthorityTier,
                    manualHoldUntilEpochMs = existing.manualHoldUntilEpochMs,
                    lastIntentAuthorityTier = existing.lastIntentAuthorityTier,
                    lastCausalActor = existing.lastCausalActor,
                    lastUserActuationEpochMs = existing.lastUserActuationEpochMs,
                    lastConfirmedEpochMs = existing.lastConfirmedEpochMs
                )
            }
        }

        // 2B. Live Authority-to-SemanticClassification Promotion (consumes Capabilities + Telemetry + Observed Behaviour)
        val isManualClass = existing.classificationSource == ClassificationSource.MANUAL_OVERRIDE || existing.isSubtypeConfirmed
        val isSplitSocket = existing.channelId.startsWith("SPLIT_SOCKET_")
        val isDedicatedMediaOrVehicle = existing.type in setOf(
            TileType.LG_TV,
            TileType.NEST_MINI,
            TileType.MUSIC_PLAYER,
            TileType.JAGUAR_VEHICLE,
            TileType.MERCEDES_VEHICLE
        )
        val devKey = sanitizedIncoming.serialNumber.ifBlank { sanitizedIncoming.entityId }.ifBlank { existing.id }
        val observedProfile = if (!isManualClass) {
            buildObservedBehaviourProfile(existing.id, sanitizedIncoming.powerWatts)
        } else {
            null
        }
        val liveResolution = if (!isManualClass && !isSplitSocket && !isDedicatedMediaOrVehicle && (
                    mergedHash != existing.capabilitySchemaHash ||
                            existing.classificationVersion < SemanticClassifier.CURRENT_VERSION ||
                            observedProfile != null ||
                            SemanticClassifier.isClimateHeatingDevice(mergedCaps, "", sanitizedIncoming.title.ifBlank { existing.title })
                    )
        ) {
            SemanticClassifier.resolve(
                capabilities = mergedCaps,
                categoryCode = "",
                multiGangSocketCount = sanitizedIncoming.multiGangSocketCount,
                hasPowerTelemetry = sanitizedIncoming.hasExplicitPowerTelemetry,
                schemaHash = mergedHash,
                titleHint = sanitizedIncoming.title.ifBlank { existing.title },
                observedBehaviour = observedProfile
            ).also { res ->
                SemanticClassifier.recordResolution(devKey, res)
            }
        } else {
            null
        }

        val combinedTitleLower = sanitizedIncoming.title.ifBlank { existing.title }.trim().lowercase(Locale.US)
        val isNonHeaterPlugOrAppliance = combinedTitleLower.contains("plug") ||
                combinedTitleLower.contains("socket") ||
                combinedTitleLower.contains("pond") ||
                combinedTitleLower.contains("pump") ||
                combinedTitleLower.contains("kettle") ||
                combinedTitleLower.contains("fryer") ||
                combinedTitleLower.contains("coffee") ||
                combinedTitleLower.contains("washer")

        val sanitizedMergedCaps = if (isNonHeaterPlugOrAppliance) {
            mergedCaps.filterNot { cap ->
                cap.dpId.isBlank() && cap.key.lowercase(Locale.US) in setOf(
                    "hvac_mode", "target_temperature", "ambient_temperature"
                )
            }
        } else {
            mergedCaps
        }

        val hasTempDialCap = !isNonHeaterPlugOrAppliance && (sanitizedMergedCaps.any {
            it.dpId.isNotBlank() &&
                    CapabilityClassifier.inferControlRole(it) == SemanticControlRole.THERMOSTAT_TEMPERATURE_DIAL
        } || existing.isThermostatTile || incoming.isThermostatTile)
        val hasFanStepperCap = sanitizedMergedCaps.any {
            CapabilityClassifier.inferControlRole(it) == SemanticControlRole.FAN_SPEED_STEPPER
        } || existing.isTowerFanTile
        val hasLevelOrCoverCap = sanitizedMergedCaps.any {
            CapabilityClassifier.inferControlRole(it) in setOf(
                SemanticControlRole.BRIGHTNESS_SLIDER,
                SemanticControlRole.COVER_POSITION_SLIDER
            )
        } || existing.hasDimmingCapability

        // 3. Detect Physical Hardware Actuation (Rank 2: Physical dial, lock, fan, cover, dimmer, or switch button)
        val powerChangedPhysically = existing.isActive != sanitizedIncoming.isActive ||
                (existing.isMultiGangSwitch && existing.isSocket2Active != sanitizedIncoming.isSocket2Active) ||
                (existing.isVehicleTile && existing.vehicleLocked != sanitizedIncoming.vehicleLocked)
        val setpointChangedPhysically = (hasTempDialCap &&
                kotlin.math.abs(existing.targetTemperature - sanitizedIncoming.targetTemperature) >= 0.5f) ||
                (hasFanStepperCap && existing.fanSpeedLevel != sanitizedIncoming.fanSpeedLevel) ||
                (hasLevelOrCoverCap && kotlin.math.abs(existing.volume - sanitizedIncoming.volume) >= 5f)

        val isPhysicalDeviceChange = incomingAuthority == StateAuthorityTier.DEVICE_HARDWARE_TRUTH &&
                existing.isOnline && sanitizedIncoming.isOnline &&
                existing.transactionState != CommandTransactionState.PENDING_ACK &&
                (powerChangedPhysically || setpointChangedPhysically)

        val nextVersion = if (powerChangedPhysically || setpointChangedPhysically || existing.isOnline != sanitizedIncoming.isOnline) {
            maxOf(existing.stateVersion, sanitizedIncoming.stateVersion) + 1L
        } else {
            maxOf(existing.stateVersion, sanitizedIncoming.stateVersion)
        }

        val capabilityHoldMs = sanitizedMergedCaps.maxOfOrNull { it.holdDurationMs } ?: IntentAuthorityTier.PHYSICAL_DEVICE.holdWindowMs
        val nextHoldUntil = if (isPhysicalDeviceChange) {
            maxOf(existing.manualHoldUntilEpochMs, now + maxOf(IntentAuthorityTier.PHYSICAL_DEVICE.holdWindowMs, capabilityHoldMs))
        } else {
            existing.manualHoldUntilEpochMs
        }

        val nextIntentTier = if (isPhysicalDeviceChange) {
            IntentAuthorityTier.PHYSICAL_DEVICE
        } else {
            existing.lastIntentAuthorityTier
        }

        val nextActor = if (isPhysicalDeviceChange) {
            "Physical ${existing.title}"
        } else {
            existing.lastCausalActor
        }

        val nextLifecycle = when {
            !sanitizedIncoming.isConfiguredAndLinked -> DeviceLifecycleState.DISCOVERED
            sanitizedIncoming.isOnline -> DeviceLifecycleState.ONLINE
            else -> DeviceLifecycleState.OFFLINE
        }

        val reconciledType = when {
            isNonHeaterPlugOrAppliance && sanitizedIncoming.type == TileType.TUYA_RADIATOR ->
                TileType.SMARTKNIGHT_PLUG
            !isNonHeaterPlugOrAppliance && liveResolution?.deviceClass == DeviceClass.CLIMATE && sanitizedIncoming.type.isSwitchType() ->
                TileType.TUYA_RADIATOR
            else ->
                sanitizedIncoming.type
        }
        val rawClassOverride = if (isManualClass) existing.deviceClassOverride else (liveResolution?.deviceClass ?: sanitizedIncoming.deviceClassOverride ?: existing.deviceClassOverride)
        val rawSubtypeOverride = if (isManualClass) existing.deviceSubtypeOverride else (liveResolution?.deviceSubtype ?: sanitizedIncoming.deviceSubtypeOverride ?: existing.deviceSubtypeOverride)
        val sanitizedClassOverride = if (isNonHeaterPlugOrAppliance && rawClassOverride == DeviceClass.CLIMATE) null else rawClassOverride
        val sanitizedSubtypeOverride = if (isNonHeaterPlugOrAppliance && rawSubtypeOverride in setOf(DeviceSubtype.THERMOSTAT, DeviceSubtype.RADIATOR, DeviceSubtype.TRV)) null else rawSubtypeOverride

        val reconciled = sanitizedIncoming.copy(
            type = reconciledType,
            capabilities = sanitizedMergedCaps,
            capabilitySchemaHash = CapabilitySchemaHasher.computeHash(sanitizedMergedCaps).ifBlank { mergedHash },
            deviceClassOverride = sanitizedClassOverride,
            deviceSubtypeOverride = sanitizedSubtypeOverride,
            classificationConfidence = liveResolution?.confidence ?: sanitizedIncoming.classificationConfidence,
            classificationSource = if (isManualClass && !isNonHeaterPlugOrAppliance) ClassificationSource.MANUAL_OVERRIDE else (liveResolution?.source ?: ClassificationSource.CAPABILITY_CLASSIFIER),
            classificationVersion = liveResolution?.version ?: SemanticClassifier.CURRENT_VERSION,
            classificationLastEvaluatedEpochMs = liveResolution?.evaluatedAtEpochMs ?: sanitizedIncoming.classificationLastEvaluatedEpochMs,
                    lifecycleState = nextLifecycle,
                    transactionState = if (sanitizedIncoming.isOnline && existing.transactionState == CommandTransactionState.PENDING_ACK) {
                        CommandTransactionState.CONFIRMED
                    } else {
                        CommandTransactionState.IDLE
                    },
                    stateVersion = nextVersion,
                    stateOriginEpochMs = now,
                    stateAuthorityTier = incomingAuthority,
                    manualHoldUntilEpochMs = nextHoldUntil,
                    lastIntentAuthorityTier = nextIntentTier,
                    lastCausalActor = nextActor,
                    lastUserActuationEpochMs = existing.lastUserActuationEpochMs,
                    lastConfirmedEpochMs = if (sanitizedIncoming.isOnline) now else existing.lastConfirmedEpochMs
                )

        if (isPhysicalDeviceChange) {
            JarvisLiveBridge.appendDeviceEvent(
                DeviceEvent(
                    deviceId = existing.id,
                    deviceTitle = existing.title,
                    roomName = existing.roomOrSubtitle,
                    eventType = DeviceEventType.STATE_CHANGED,
                    source = sourceName,
                    actorName = nextActor,
                    stateVersion = nextVersion,
                    authorityTierLabel = incomingAuthority.label,
                    previousStateSummary = summarizeTileState(existing),
                    newStateSummary = "${summarizeTileState(reconciled)} (30m Hold)"
                )
            )
        }
        if (existing.effectiveLifecycleState != nextLifecycle &&
            reconciled.isConfiguredAndLinked &&
            reconciled.serviceClass == null &&
            reconciled.deviceSubtype.isHardwareSubtype &&
            reconciled.type !in setOf(
                TileType.CLOCK_WEATHER,
                TileType.MUSIC_PLAYER,
                TileType.JARVIS_SCENES,
                TileType.PHOTO_SLIDESHOW,
                TileType.GROUP_CARD,
                TileType.ENERGY_MONITOR,
                TileType.MACRO_TILE,
                TileType.SECURITY_CENTER
            )
        ) {
            val pollTransition = when (nextLifecycle) {
                DeviceLifecycleState.ONLINE -> MandatoryLifecycleTransition.ONLINE
                DeviceLifecycleState.OFFLINE -> MandatoryLifecycleTransition.OFFLINE
                else -> null
            }
            if (pollTransition != null) {
                DeviceLifecycleRecorder.recordTileTransition(
                    tile = reconciled,
                    transition = pollTransition,
                    previousState = existing.effectiveLifecycleState.name,
                    newState = nextLifecycle.name,
                    protocolResultCode = "RECONCILE_${sourceName.take(16).uppercase(Locale.US).replace(' ', '_')}",
                    failureCategory = if (nextLifecycle == DeviceLifecycleState.OFFLINE) FailureCategory.TRANSPORT_FAILURE else null
                )
            }
        }
        JarvisLiveBridge.updateDeviceLifecycleAndRecoveryStage(
            tile = reconciled,
            lifecycleState = nextLifecycle,
            emitFlightRecord = false
        )
        return reconciled
    }

    private const val KEY_HA_URL = "ha_url"
    private const val KEY_HA_TOKEN = "ha_token"
    private const val KEY_IMOU_APP_ID = "imou_app_id"
    private const val KEY_IMOU_APP_SECRET = "imou_app_secret"
    private const val KEY_IMOU_API_HOST = "imou_api_host"
    private const val KEY_EZVIZ_APP_KEY = "ezviz_app_key"
    private const val KEY_EZVIZ_APP_SECRET = "ezviz_app_secret"
    private const val KEY_EZVIZ_API_HOST = "ezviz_api_host"

    private const val KEY_NEST_PROJECT_ID = "nest_project_id"
    private const val KEY_NEST_CLIENT_ID = "nest_client_id"
    private const val KEY_NEST_CLIENT_SECRET = "nest_client_secret"
    private const val KEY_NEST_REFRESH_TOKEN = "nest_refresh_token"
    private const val KEY_NEST_PUBSUB_SUB = "nest_pubsub_sub"

    private const val KEY_TUYA_ACCESS_ID = "tuya_access_id"
    private const val KEY_TUYA_ACCESS_SECRET = "tuya_access_secret"
    private const val KEY_TUYA_UID = "tuya_uid"
    private const val KEY_TUYA_API_HOST = "tuya_api_host"

    private const val KEY_TPLINK_EMAIL = "tplink_email"
    private const val KEY_TPLINK_PASSWORD = "tplink_password"
    private const val KEY_EWELINK_EMAIL = "ewelink_email"
    private const val KEY_EWELINK_PASSWORD = "ewelink_password"
    private const val KEY_MQTT_BROKER_HOST = "mqtt_broker_host"
    private const val KEY_MQTT_BROKER_PORT = "mqtt_broker_port"
    private const val KEY_MQTT_USERNAME = "mqtt_username"
    private const val KEY_MQTT_PASSWORD = "mqtt_password"

    private const val KEY_JAGUAR_EMAIL = "jaguar_email_v1"
    private const val KEY_JAGUAR_PASSWORD = "jaguar_password_v1"
    private const val KEY_JAGUAR_PIN = "jaguar_pin_v1"
    private const val KEY_JAGUAR_VIN = "jaguar_vin_v1"
    private const val KEY_MERCEDES_EMAIL = "mercedes_email_v1"
    private const val KEY_MERCEDES_PASSWORD = "mercedes_password_v1"
    private const val KEY_MERCEDES_PIN = "mercedes_pin_v1"
    private const val KEY_MERCEDES_VIN = "mercedes_vin_v1"
    private const val KEY_LYTMI_IP = "lytmi_ip_address_v1"
    private const val KEY_LYTMI_DEVICE_ID = "lytmi_device_id_v1"
    private const val KEY_LYTMI_LOCAL_KEY = "lytmi_local_key_v1"
    private const val KEY_GOVEE_API_KEY = "govee_api_key_v1"
    private const val KEY_GOVEE_IP = "govee_ip_address_v1"
    private const val KEY_TWINKLY_IP = "twinkly_ip_address_v1"
    private const val KEY_PLEX_SERVER_URL = "plex_server_url_v1"
    private const val KEY_PLEX_TOKEN = "plex_token_v1"

    private const val KEY_MUSIC_PROVIDER = "music_provider"
    private const val KEY_APP_THEME_MODE = "app_theme_mode"
    private const val KEY_ACCENT_COLOR_PRESET = "app_accent_color_preset_v1"
    private const val KEY_CUSTOM_ACCENT_COLOR_ARGB = "custom_accent_color_argb_v1"
    private const val KEY_CLOCK_FACE_STYLE = "clock_face_style_v1"
    private const val KEY_HERO_CARD_TIMEOUT_SEC = "hero_card_timeout_seconds_v1"
    private const val KEY_KEEP_SCREEN_AWAKE = "keep_screen_awake"
    private const val KEY_SLEEP_TIMEOUT_SEC = "sleep_timeout_seconds"
    private const val KEY_ENABLE_PRESENCE_WAKE = "enable_presence_wake"
    private const val KEY_ENABLE_VOICE_TTS = "enable_voice_tts"
    private const val KEY_CLOUD_AUTO_PAUSE_SEC = "cloud_stream_auto_pause_sec"
    private const val KEY_SHOW_ROOM_LABELS = "show_room_labels_on_tiles"
    private const val KEY_SHOW_TEMP_ON_TILES = "show_temperature_on_tiles_v1"
    private const val KEY_ENABLE_GROUP_CARDS = "enable_group_cards"
    private const val KEY_USER_CREATED_ROOMS_JSON = "user_created_rooms_json_v2"
    private const val KEY_DASHBOARD_GREETING_NAME = "dashboard_greeting_name"
    private const val KEY_ENABLE_VOICE_CONTROL = "enable_voice_control"
    private const val KEY_ENABLE_WAKE_WORD = "enable_wake_word_listening_v3"
    private const val KEY_VOICE_WAKE_WORD = "voice_wake_word_phrase"
    private const val KEY_ENABLE_NEST_DOORBELL_CHIME = "enable_nest_mini_doorbell_chime"
    private const val KEY_NEST_DOORBELL_VOLUME = "nest_mini_doorbell_volume"
    private const val KEY_DOORBELL_CHIME_MSG = "doorbell_chime_message"
    private const val KEY_DOORBELL_CHIME_IPS = "doorbell_chime_target_ips"
    private const val KEY_CUSTOM_DOORBELL_MP3_URI = "custom_doorbell_mp3_uri_v1"
    private const val KEY_CUSTOM_ALARM_MP3_URI = "custom_alarm_mp3_uri_v1"
    private const val KEY_ENABLE_SWIPE_SLIDESHOW = "enable_swipeable_slideshow_page"
    private const val KEY_SHOW_CLOCK_ON_SLIDESHOW = "show_clock_on_slideshow_page"
    private const val KEY_SLIDESHOW_SOURCE_TYPE = "slideshow_source_type"
    private const val KEY_SLIDESHOW_CLOUD_URL = "slideshow_cloud_url"
    private const val KEY_SLIDESHOW_LOCAL_URIS = "slideshow_local_uris_json"
    private const val KEY_SLIDESHOW_INTERVAL_SEC = "slideshow_interval_seconds"
    private const val KEY_SLIDESHOW_INCLUDE_SUBFOLDERS = "slideshow_include_subfolders"
    private const val KEY_DASHBOARD_CARD_ORDER_JSON = "dashboard_card_order_json_v1"
    private const val KEY_DASHBOARD_CARD_WIDTH_WEIGHTS_JSON = "dashboard_card_width_weights_json_v1"
    private const val KEY_DASHBOARD_CARD_HEIGHT_WEIGHTS_JSON = "dashboard_card_height_weights_json_v1"
    private const val KEY_DASHBOARD_CARD_ZOOM_SCALES_JSON = "dashboard_card_zoom_scales_json_v1"
    private const val KEY_DASHBOARD_SLOT_WIDGET_TYPES_JSON = "dashboard_slot_widget_types_json_v1"
    private const val KEY_DPI_SCALE_PRESET = "dpi_scale_preset_v1"
    private const val KEY_CUSTOM_DPI_SCALE_PCT = "custom_dpi_scale_pct_v1"
    private const val KEY_DASHBOARD_TEMPLATE = "dashboard_template_v1"
    private const val KEY_NAV_DOCK_POSITION = "nav_dock_position_v1"
    private const val KEY_INVERT_NAV_ORDER = "invert_nav_order_v1"
    private const val KEY_ACTIVE_USER_ROLE = "active_user_role_v1"
    private const val KEY_ROLE_PIN_CODE = "role_pin_code_v1"
    private const val KEY_REQUIRE_PIN_ADMIN_VEHICLES = "require_pin_admin_vehicles_v1"
    private const val KEY_USER_PROFILES_JSON = "user_profiles_list_json_v1"
    private const val KEY_ACTIVE_USER_PROFILE_ID = "active_user_profile_id_v1"
    private const val KEY_CUSTOM_RADIO_STATIONS_JSON = "custom_radio_stations_json_v1"
    private const val KEY_ACTIVE_RADIO_STATION_ID = "active_radio_station_id_v1"
    private const val KEY_TRACKED_MEDIA_SHOWS_JSON = "tracked_media_shows_json_v1"
    private const val KEY_SEEDED_DEFAULT_TV_SHOWS_V1 = "has_seeded_default_tv_shows_v1"
    private const val KEY_USER_CUSTOMIZED_TV_SHOWS_V1 = "has_user_customized_tracked_shows_v1"
    private const val KEY_CALENDAR_EVENTS_JSON = "household_calendar_events_json_v1"
    private const val KEY_COUNCIL_BINS_CONFIG_JSON = "uk_council_bins_config_json_v1"
    private const val KEY_FOLLOWED_FOOTBALL_TEAMS_JSON = "followed_football_teams_json_v1"
    private const val KEY_SEEDED_DEFAULT_FOOTBALL_TEAMS_V1 = "has_seeded_default_football_teams_v1"
    private const val KEY_USER_CUSTOMIZED_FOOTBALL_TEAMS_V1 = "has_user_customized_football_teams_v1"
    private const val KEY_IBO_CHANNEL_MAPPINGS_JSON = "ibo_channel_mappings_json_v1"
    private const val KEY_FOOTBALL_TV_PROVIDER_CONFIG_JSON = "football_tv_provider_config_json_v1"
    private const val KEY_GUEST_WIFI_CONFIG_JSON = "guest_wifi_commute_config_json_v1"
    private const val KEY_JAGUAR_REG_PLATE = "jaguar_reg_plate_v1"
    private const val KEY_MERCEDES_REG_PLATE = "mercedes_reg_plate_v1"
    private const val KEY_SEEDED_DEFAULT_ROOMS_V1 = "has_seeded_default_rooms_v1"
    private const val KEY_TARIFF_DAY_RATE_GBP = "electricity_tariff_day_rate_gbp_v1"
    private const val KEY_TARIFF_NIGHT_RATE_GBP = "electricity_tariff_night_rate_gbp_v1"
    private const val KEY_TARIFF_STANDING_CHARGE_GBP = "electricity_tariff_standing_charge_gbp_v1"
    private const val KEY_CURRENCY_SYMBOL = "currency_symbol_v1"
    private const val KEY_OFF_PEAK_START_HOUR = "off_peak_start_hour_v1"
    private const val KEY_OFF_PEAK_END_HOUR = "off_peak_end_hour_v1"
    private const val KEY_SECURITY_CENTER_MODE = "security_center_mode_v1"
    private const val KEY_SECURITY_HOME_TEMP_C = "security_home_temp_c_v1"
    private const val KEY_SECURITY_AWAY_TEMP_C = "security_away_temp_c_v1"
    private const val KEY_SECURITY_NIGHT_TEMP_C = "security_night_temp_c_v1"
    private const val KEY_SECURITY_VACATION_TEMP_C = "security_vacation_temp_c_v1"
    private const val KEY_LOCAL_LLM_ENDPOINT_URL = "local_llm_endpoint_url_v1"
    private const val KEY_SMARTTHINGS_TOKEN = "smartthings_pat_token_v1"
    private const val KEY_FRIGATE_HOST_URL = "frigate_nvr_host_url_v1"
    private const val KEY_TESLA_ACCESS_TOKEN = "tesla_access_token_v1"
    private const val KEY_TESLA_VIN = "tesla_vin_v1"
    private const val KEY_TESLA_WALL_CONNECTOR_IP = "tesla_wall_connector_ip_v1"

    private val SYNTHETIC_DUMMY_VINS = setOf(
        "SAJAA4BN8HAJAGUAR",
        "W1K20504MERCEDES"
    )

    fun sanitizeRealVin(rawVin: String): String {
        val clean = rawVin.trim().uppercase(Locale.US)
        return if (clean in SYNTHETIC_DUMMY_VINS) "" else clean
    }

    // -----------------------------------------------------------------------
    private const val PIN_HASH_PREFIX = "PIN_SHA256_V1:"

    fun hashPinCode(rawPin: String): String {
        val clean = rawPin.trim()
        if (clean.isBlank()) return ""
        if (clean.startsWith(PIN_HASH_PREFIX)) return clean
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$saltHex:$clean".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$PIN_HASH_PREFIX$saltHex:$digest"
    }

    fun verifyPinCode(candidatePin: String, storedPinOrHash: String): Boolean {
        val cleanCandidate = candidatePin.trim()
        val cleanStored = storedPinOrHash.trim()
        if (cleanCandidate.isBlank() || cleanStored.isBlank()) return false
        if (!cleanStored.startsWith(PIN_HASH_PREFIX)) {
            return MessageDigest.isEqual(
                cleanCandidate.toByteArray(Charsets.UTF_8),
                cleanStored.toByteArray(Charsets.UTF_8)
            )
        }
        val parts = cleanStored.removePrefix(PIN_HASH_PREFIX).split(":")
        if (parts.size != 2) return false
        val saltHex = parts[0]
        val expectedHex = parts[1]
        val computedHex = MessageDigest.getInstance("SHA-256")
            .digest("$saltHex:$cleanCandidate".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(
            computedHex.toByteArray(Charsets.UTF_8),
            expectedHex.toByteArray(Charsets.UTF_8)
        )
    }

    fun hasConfiguredOwnerPin(bridge: BridgeSettings): Boolean {
        if (bridge.rolePinCode.trim().isNotBlank()) return true
        return bridge.userProfiles.any {
            (it.role == UserRole.OWNER || it.role == UserRole.ADMIN) && it.customPinCode.trim().isNotBlank()
        }
    }

    private fun vaultBridgeKey(key: String): String = "${PREFS_NAME}::$key"

    private fun readBridgeSecret(
        context: Context,
        legacyPrefs: SharedPreferences,
        key: String
    ): String {
        val appCtx = context.applicationContext
        val vKey = vaultBridgeKey(key)
        val vaultRaw = ClaraSecretVault.getString(appCtx, vKey).orEmpty()
        if (vaultRaw.isNotBlank()) {
            val unwrapped = vaultRaw.trim()
            if (legacyPrefs.contains(key)) {
                legacyPrefs.edit().remove(key).apply()
            }
            return unwrapped
        }
        val legacyRaw = legacyPrefs.getString(key, "").orEmpty()
        if (legacyRaw.isBlank()) return ""
        val migrated = legacyRaw.trim()
        if (migrated.isNotBlank()) {
            ClaraSecretVault.putString(appCtx, vKey, migrated)
            legacyPrefs.edit().remove(key).apply()
        }
        return migrated
    }

    private fun writeBridgeSecret(
        context: Context,
        legacyEditor: SharedPreferences.Editor,
        key: String,
        plainValue: String
    ) {
        val appCtx = context.applicationContext
        val vKey = vaultBridgeKey(key)
        val clean = plainValue.trim()
        if (clean.isBlank()) {
            ClaraSecretVault.remove(appCtx, vKey)
        } else {
            ClaraSecretVault.putString(appCtx, vKey, clean)
        }
        legacyEditor.remove(key)
    }

    fun applyThemeMode(
        mode: AppThemeMode,
        accentPreset: AppAccentColorPreset = ClaraThemeManager.accentPreset,
        customAccentColorArgb: Long = ClaraThemeManager.customAccentColorArgb
    ) {
        ClaraThemeManager.isDarkMode = when (mode) {
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
            AppThemeMode.AUTO_DAYLIGHT -> !JarvisLiveBridge.weatherFlow.value.isDay
        }
        ClaraThemeManager.accentPreset = accentPreset
        ClaraThemeManager.customAccentColorArgb = customAccentColorArgb
    }

    // -----------------------------------------------------------------------
    // 2. DISMISSED DEVICE REGISTRY (Thread-Safe, Strictly Non-IP Hardware Keys)
    // -----------------------------------------------------------------------
    fun getDismissedDeviceKeys(context: Context): Set<String> {
        synchronized(dismissedLock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_DISMISSED_DEVICE_KEYS, null) ?: return emptySet()
            return try {
                val arr = JSONArray(raw)
                val set = LinkedHashSet<String>()
                var strippedBareIp = false
                for (i in 0 until arr.length()) {
                    val k = arr.optString(i, "").trim().lowercase(Locale.US)
                    if (k.isBlank()) continue
                    if (ConnectedIdentityIndex.isBareIpLiteral(k)) {
                        strippedBareIp = true
                        continue
                    }
                    set.add(k)
                }
                if (strippedBareIp) {
                    saveDismissedDeviceKeys(context, set)
                }
                set
            } catch (_: Exception) {
                emptySet()
            }
        }
    }

    private fun saveDismissedDeviceKeys(context: Context, keys: Set<String>) {
        synchronized(dismissedLock) {
            val arr = JSONArray()
            keys.forEach { k ->
                val clean = k.trim().lowercase(Locale.US)
                if (clean.isNotBlank() && !ConnectedIdentityIndex.isBareIpLiteral(clean)) {
                    arr.put(clean)
                }
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DISMISSED_DEVICE_KEYS, arr.toString())
                .apply()
        }
    }

    fun markDeviceDismissed(context: Context, tile: SmartTile) {
        synchronized(dismissedLock) {
            val current = getDismissedDeviceKeys(context).toMutableSet()
            val guid = tile.canonicalGuid.ifBlank { tile.resolvedCanonicalGuid() }.trim().lowercase(Locale.US)
            if (guid.isNotBlank() && !guid.startsWith("guid::unresolved::")) {
                current.add(guid)
            }
            saveDismissedDeviceKeys(context, current)
        }
    }

    fun unmarkDeviceDismissed(context: Context, vararg keys: String) {
        synchronized(dismissedLock) {
            val current = getDismissedDeviceKeys(context).toMutableSet()
            var changed = false
            for (k in keys) {
                val clean = k.trim().lowercase(Locale.US)
                if (clean.isNotBlank() && current.remove(clean)) {
                    changed = true
                }
                val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(k).lowercase(Locale.US)
                if (normMac.isNotBlank() && current.remove(normMac)) {
                    changed = true
                }
            }
            if (changed) {
                saveDismissedDeviceKeys(context, current)
            }
        }
    }

    fun isDeviceDismissed(context: Context, vararg candidateKeys: String): Boolean {
        val dismissed = getDismissedDeviceKeys(context)
        if (dismissed.isEmpty()) return false
        for (raw in candidateKeys) {
            val clean = raw.trim().lowercase(Locale.US)
            if (clean.isBlank() || ConnectedIdentityIndex.isBareIpLiteral(clean)) continue
            if (clean in dismissed) return true
            val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(raw).lowercase(Locale.US)
            if (normMac.isNotBlank() && normMac in dismissed) return true
        }
        return false
    }

    /**
     * Change 7.1: Unpair from Dashboard — hides the tile, retains vault keys & history,
     * suppresses it from New Device discovery by hardware GUID, and enables 1-tap restore.
     */
    fun unpairTileFromDashboard(
        context: Context,
        tile: SmartTile,
        tiles: MutableList<SmartTile>
    ) {
        val prevState = tile.effectiveLifecycleState.name
        val unpaired = tile.copy(
            isVisible = false,
            lifecycleState = DeviceLifecycleState.UNPAIRED,
            transactionState = CommandTransactionState.IDLE
        )
        val idx = tiles.indexOfFirst { it.id == tile.id }
        if (idx >= 0) {
            tiles[idx] = unpaired
        }
        markDeviceDismissed(context, unpaired)
        DeviceLifecycleRecorder.recordTileTransition(
            tile = unpaired,
            transition = MandatoryLifecycleTransition.UNPAIRED,
            previousState = prevState,
            newState = DeviceLifecycleState.UNPAIRED.name,
            protocolResultCode = "DASHBOARD_HIDDEN"
        )
        saveTiles(context, tiles)
    }

    fun restoreUnpairedTileToDashboard(
        context: Context,
        tile: SmartTile,
        tiles: MutableList<SmartTile>
    ) {
        val canonGuid = tile.canonicalGuid.ifBlank { tile.resolvedCanonicalGuid() }
        unmarkDeviceDismissed(context, canonGuid, tile.serialNumber, tile.entityId, tile.macAddress)
        val restoredState = if (tile.isOnline) DeviceLifecycleState.OPERATIONAL else DeviceLifecycleState.OFFLINE
        val restored = tile.copy(
            isVisible = true,
            lifecycleState = restoredState
        )
        val idx = tiles.indexOfFirst { it.id == tile.id }
        if (idx >= 0) {
            tiles[idx] = restored
        } else {
            tiles.add(restored)
        }
        DeviceLifecycleRecorder.recordTileTransition(
            tile = restored,
            transition = if (tile.isOnline) MandatoryLifecycleTransition.ONLINE else MandatoryLifecycleTransition.OFFLINE,
            previousState = DeviceLifecycleState.UNPAIRED.name,
            newState = restoredState.name,
            protocolResultCode = "DASHBOARD_RESTORED"
        )
        saveTiles(context, tiles)
    }

    /**
     * Change 7.1: Unbind & Forget — calls underlying ecosystem removal where supported,
     * purges vault keys/TLS pins, clears suppression flags, records UNBOUND, and deletes the tile.
     */
    suspend fun unbindAndForgetTile(
        context: Context,
        tile: SmartTile,
        bridge: BridgeSettings,
        tiles: MutableList<SmartTile>
    ) {
        val appCtx = context.applicationContext
        val devId = tile.serialNumber.ifBlank { tile.entityId }.trim()
        val cleanIp = tile.ipAddress.substringBefore(":").trim()
        val canonGuid = tile.canonicalGuid.ifBlank { tile.resolvedCanonicalGuid() }
        val providerKey = resolveEffectiveProviderKey(tile)

        if (providerKey.contains("TUYA") && devId.isNotBlank()) {
            runCatching { TuyaApiClient.deleteDevice(bridge, devId) }
        }
        if (tile.type == TileType.LG_TV || providerKey.contains("LG")) {
            LgThinQWebOsClient.clearPinnedTvCertForReverification(appCtx, cleanIp, tile.macAddress)
        }

        ClaraSecretVault.purgeDeviceSecretsOnUnbind(
            context = appCtx,
            deviceId = devId,
            ipAddress = cleanIp,
            macAddress = tile.macAddress
        )
        unmarkDeviceDismissed(appCtx, canonGuid, devId, tile.macAddress, tile.title)

        DeviceLifecycleRecorder.recordTileTransition(
            tile = tile,
            transition = MandatoryLifecycleTransition.UNBOUND,
            previousState = tile.effectiveLifecycleState.name,
            newState = DeviceLifecycleState.DISCOVERABLE_AGAIN.name,
            protocolResultCode = "VAULT_PURGED_UNBOUND"
        )
        JarvisLiveBridge.removeDeviceObservability(tile.id)
        withContext(Dispatchers.Main) {
            tiles.removeAll {
                it.id == tile.id ||
                        (canonGuid.isNotBlank() &&
                                !canonGuid.startsWith("guid::unresolved::") &&
                                (it.canonicalGuid == canonGuid || it.resolvedCanonicalGuid() == canonGuid))
            }
            saveTiles(appCtx, tiles)
        }
        ClaraDeviceRegistryStore.getInstance(appCtx).purgeUnboundDeviceRecord(canonGuid)
    }

    /**
     * Guarantees that newly paired or cloud-imported devices are unmarked from dismissed keys,
     * forced to `isVisible = true`, assigned capabilities, added/updated in `tiles`, and saved.
     */
    fun upsertAndShowTilesOnDashboard(
        context: Context,
        tiles: MutableList<SmartTile>,
        incomingTiles: List<SmartTile>
    ): Int {
        if (incomingTiles.isEmpty()) return 0
        var upsertedCount = 0
        val activeGroupIds = tiles.filter { it.type == TileType.GROUP_CARD }.map { it.id }.toSet()

        for (rawIncoming in incomingTiles) {
            unmarkDeviceDismissed(
                context,
                rawIncoming.entityId,
                rawIncoming.serialNumber,
                rawIncoming.ipAddress,
                rawIncoming.title
            )
            val caps = rawIncoming.capabilities.ifEmpty {
                SmartHomeApiClient.buildDefaultCapabilitiesForTile(rawIncoming)
            }
            val computedHash = CapabilitySchemaHasher.computeHash(caps)
            val devKey = rawIncoming.serialNumber.ifBlank { rawIncoming.entityId }.ifBlank { rawIncoming.id }
            val resolution = if (CapabilityClassifier.shouldReevaluateTile(rawIncoming, computedHash, context)) {
                CapabilityClassifier.resolve(
                    capabilities = caps,
                    categoryCode = "",
                    multiGangSocketCount = rawIncoming.multiGangSocketCount,
                    hasPowerTelemetry = rawIncoming.hasExplicitPowerTelemetry,
                    schemaHash = computedHash,
                    titleHint = rawIncoming.title
                ).also { res ->
                    CapabilityClassifier.recordResolution(devKey, res, context)
                }
            } else {
                CapabilityClassifier.getResolution(devKey, context)
            }
            val isManual = rawIncoming.classificationSource == ClassificationSource.MANUAL_OVERRIDE
            val incoming = rawIncoming.copy(
                isVisible = true,
                capabilities = caps,
                capabilitySchemaHash = computedHash,
                deviceClassOverride = if (isManual) rawIncoming.deviceClassOverride else (rawIncoming.deviceClassOverride ?: resolution?.deviceClass),
                deviceSubtypeOverride = if (isManual) rawIncoming.deviceSubtypeOverride else (rawIncoming.deviceSubtypeOverride ?: resolution?.deviceSubtype),
                classificationConfidence = resolution?.confidence ?: rawIncoming.classificationConfidence,
                classificationSource = if (isManual) ClassificationSource.MANUAL_OVERRIDE else (resolution?.source ?: rawIncoming.classificationSource),
                classificationVersion = resolution?.version ?: CapabilityClassifier.CURRENT_VERSION,
                classificationLastEvaluatedEpochMs = resolution?.evaluatedAtEpochMs ?: System.currentTimeMillis()
            )
            val existingIdx = tiles.indexOfFirst { t ->
                val sameSubEndpoint = t.subEndpointIndex == incoming.subEndpointIndex
                val sameEntity = sameSubEndpoint && incoming.entityId.isNotBlank() && t.entityId.equals(incoming.entityId, ignoreCase = true)
                val sameSerial = sameSubEndpoint && incoming.serialNumber.isNotBlank() && t.serialNumber.equals(incoming.serialNumber, ignoreCase = true)
                val bothUnlinkedSameTitle = sameSubEndpoint &&
                        t.type == incoming.type &&
                        t.title.equals(incoming.title, ignoreCase = true) &&
                        (t.entityId.isBlank() || incoming.entityId.isBlank()) &&
                        (t.serialNumber.isBlank() || incoming.serialNumber.isBlank())
                sameEntity || sameSerial || bothUnlinkedSameTitle
            }
            if (existingIdx >= 0) {
                val prev = tiles[existingIdx]
                val validGroupId = if (prev.groupCardId in activeGroupIds) prev.groupCardId else ""
                tiles[existingIdx] = incoming.copy(
                    id = prev.id,
                    roomOrSubtitle = incoming.roomOrSubtitle.ifBlank { prev.roomOrSubtitle },
                    colSpan = prev.colSpan,
                    rowSpan = prev.rowSpan,
                    gridCol = prev.gridCol,
                    gridRow = prev.gridRow,
                    groupCardId = validGroupId,
                    isVisible = true,
                    macAddress = incoming.macAddress.ifBlank { prev.macAddress },
                    vendorId = incoming.vendorId.ifBlank { prev.vendorId },
                    productId = incoming.productId.ifBlank { prev.productId },
                    canonicalGuid = incoming.canonicalGuid.ifBlank { prev.canonicalGuid }
                )
            } else {
                val hasDuplicateTitle = tiles.any {
                    it.type == incoming.type && it.title.equals(incoming.title, ignoreCase = true)
                }
                val disambiguatedTitle = if (hasDuplicateTitle) {
                    val suffix = incoming.roomOrSubtitle.takeIf {
                        it.isNotBlank() && !incoming.title.contains(it, ignoreCase = true)
                    } ?: incoming.serialNumber.takeLast(4).uppercase(Locale.US).ifBlank {
                        "${tiles.count { it.type == incoming.type } + 1}"
                    }
                    "${incoming.title} ($suffix)"
                } else {
                    incoming.title
                }
                tiles.add(
                    incoming.copy(
                        title = disambiguatedTitle,
                        isVisible = true,
                        groupCardId = "",
                        gridCol = -1,
                        gridRow = -1
                    )
                )
            }
            upsertedCount++
        }
        saveTiles(context, tiles)
        return upsertedCount
    }

    /**
     * Post-Onboarding Discovery Acceleration IP Patcher:
     * Immediately patches the resolved local LAN IP (and promotes activeProviderId to TUYA_LAN)
     * onto any matching dashboard tile(s) when UDP 6666/6667 resolves a few seconds after cloud pairing.
     */
    fun patchResolvedLanIpForDevice(
        context: Context,
        deviceId: String,
        ipAddress: String,
        liveTiles: MutableList<SmartTile>? = null
    ) {
        val cleanId = deviceId.trim()
        val cleanIp = ipAddress.substringBefore(":").trim()
        if (cleanId.isBlank() || cleanIp.isBlank()) return
        val appCtx = context.applicationContext

        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            val targetList = liveTiles ?: loadTiles(appCtx).toMutableList()
            var anyUpdated = false

            for (idx in targetList.indices) {
                val tile = targetList[idx]
                val matchesDevice = tile.serialNumber.equals(cleanId, ignoreCase = true) ||
                        tile.entityId.equals(cleanId, ignoreCase = true)
                if (matchesDevice && (tile.ipAddress != cleanIp || !tile.isOnline)) {
                    val knownKey = TuyaApiClient.resolveKnownLocalKey(cleanId, tile.macAddress)
                        .ifBlank { tile.secretCode }
                    TuyaApiClient.registerDeviceLanAddress(cleanId, cleanIp, knownKey)

                    val updatedTile = tile.copy(
                        ipAddress = cleanIp,
                        secretCode = tile.secretCode.ifBlank { knownKey },
                        isOnline = true,
                        lifecycleState = DeviceLifecycleState.ONLINE,
                        activeProviderId = ProviderId.TUYA_LAN.id
                    )
                    targetList[idx] = updatedTile
                    anyUpdated = true
                }
            }

            if (anyUpdated) {
                Log.d(TAG, "Patched post-onboarding LAN IP $cleanIp onto dashboard tile(s) for$cleanId")
                saveTiles(appCtx, targetList)
            }
        }
    }

    /**
     * Completely removes all connected devices, clears dismissed device blocks,
     * and resets protocol discovery caches so pairing can be tested from a clean slate.
     */
    fun removeAllDevicesAndClearCaches(
        context: Context,
        tiles: MutableList<SmartTile>
    ) {
        val appCtx = context.applicationContext
        CameraStreamSessionManager.muteAll()
        CameraStreamSessionManager.releaseAllExcept(emptySet())

        for (tile in tiles) {
            DeviceLifecycleRecorder.recordTransition(
                deviceId = tile.id,
                event = "Unbound",
                previousState = tile.effectiveLifecycleState.name,
                newState = "UNBOUND",
                activeTransport = "UI",
                vendorDriver = tile.activeProviderId,
                latencyMs = 0L,
                protocolResultCode = "SUCCESS"
            )
        }

        val starterOnly = defaultStarterTiles()
        tiles.clear()
        tiles.addAll(starterOnly)
        cachedTilesSnapshot = starterOnly
        cachedTileJsonById.clear()

        synchronized(dismissedLock) {
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_DISMISSED_DEVICE_KEYS)
                .remove(KEY_CAST_GROUPS_JSON)
                .remove(KEY_HOME_GRAPH_JSON)
                .apply()
        }

        TuyaApiClient.clearAllDeviceCaches()

        UniversalOnboardingEngine.resolvedIdentitiesFlow.value = emptyList()
        MultiProtocolLiveBridge.discoveredMatterDevicesFlow.value = emptyList()
        MultiProtocolLiveBridge.discoveredTapoKasaDevicesFlow.value = emptyList()
        MultiProtocolLiveBridge.discoveredSonoffDevicesFlow.value = emptyList()
        MultiProtocolLiveBridge.discoveredMqttEntitiesFlow.value = emptyList()

        JarvisLiveBridge.clearAllDeviceObservabilityAndCaches()
        val starterJsonList = starterOnly.map { serializeSingleTileToJson(it) }
        ClaraDeviceRegistryStore.getInstance(appCtx).clearAllForCleanSlate(starterJsonList)
        saveTiles(appCtx, starterOnly)
    }

    // -----------------------------------------------------------------------
    // 2B. USER PROFILES & CUSTOM RADIO SERIALIZATION HELPERS
    // -----------------------------------------------------------------------
    private fun serializeUserProfiles(profiles: List<UserProfile>): JSONArray {
        val arr = JSONArray()
        profiles.forEach { prof ->
            val hashedCustomPin = if (prof.customPinCode.isNotBlank()) hashPinCode(prof.customPinCode) else ""
            arr.put(
                JSONObject().apply {
                    put("id", prof.id)
                    put("name", prof.name)
                    put("role", prof.role.name)
                    put("avatarColorHex", prof.avatarColorHex)
                    put("isParentalLocked", prof.isParentalLocked)
                    put("requirePinToSwitchOut", prof.requirePinToSwitchOut)
                    put("customPinCode", hashedCustomPin)
                    put("avatarUriOrPreset", prof.avatarUriOrPreset)
                }
            )
        }
        return arr
    }

    private fun deserializeUserProfiles(rawJson: String?): List<UserProfile> {
        if (rawJson.isNullOrBlank()) return DEFAULT_USER_PROFILES
        return try {
            val arr = JSONArray(rawJson)
            val list = mutableListOf<UserProfile>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val role = runCatching {
                    UserRole.valueOf(o.optString("role", UserRole.OWNER.name))
                }.getOrDefault(UserRole.OWNER)
                val name = o.optString("name", role.label).trim().ifBlank { role.label }
                list.add(
                    UserProfile(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = name,
                        role = role,
                        avatarColorHex = o.optLong("avatarColorHex", 0xFFA3F0C1L),
                        isParentalLocked = o.optBoolean("isParentalLocked", false),
                        requirePinToSwitchOut = o.optBoolean("requirePinToSwitchOut", false),
                        customPinCode = o.optString("customPinCode", ""),
                        avatarUriOrPreset = o.optString("avatarUriOrPreset", "DEFAULT_${(i % 6) + 1}").ifBlank { "DEFAULT_1" }
                    )
                )
            }
            if (list.isEmpty()) DEFAULT_USER_PROFILES else list
        } catch (_: Exception) {
            DEFAULT_USER_PROFILES
        }
    }

    private fun serializeCustomRadioStations(stations: List<RadioStationStream>): JSONArray {
        val arr = JSONArray()
        stations.forEach { st ->
            arr.put(
                JSONObject().apply {
                    put("id", st.id)
                    put("name", st.name)
                    put("genreOrTagline", st.genreOrTagline)
                    put("streamUrl", st.streamUrl)
                    put("accentColorHex", st.accentColorHex)
                    put("badgeCode", st.badgeCode)
                    put("isCustom", st.isCustom)
                }
            )
        }
        return arr
    }

    private fun deserializeCustomRadioStations(rawJson: String?): List<RadioStationStream> {
        if (rawJson == null) return DEFAULT_RADIO_STATIONS
        if (rawJson.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            val list = mutableListOf<RadioStationStream>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").trim()
                val url = o.optString("streamUrl", "").trim()
                if (name.isBlank() || url.isBlank()) continue
                list.add(
                    RadioStationStream(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = name,
                        genreOrTagline = o.optString("genreOrTagline", "Custom Live Stream"),
                        streamUrl = url,
                        accentColorHex = o.optLong("accentColorHex", 0xFF00E5FFL),
                        badgeCode = o.optString("badgeCode", name.take(3).uppercase(Locale.US)),
                        isCustom = o.optBoolean("isCustom", true)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeTrackedMediaShows(shows: List<TrackedMediaShow>): JSONArray {
        val arr = JSONArray()
        shows.distinctBy { it.title.trim().lowercase(Locale.US) }.forEach { sh ->
            val appId = sh.appIdHint.ifBlank {
                NextEpisodeDirectoryClient.mapNetworkToLgTvAppId(sh.networkOrStreamer, sh.title).first
            }
            val normalizedStreamer = NextEpisodeDirectoryClient.normalizeStreamerName(sh.networkOrStreamer, appId)
            arr.put(
                JSONObject().apply {
                    put("id", sh.id)
                    put("externalId", sh.externalId)
                    put("title", sh.title)
                    put("kind", sh.kind.name)
                    put("networkOrStreamer", normalizedStreamer)
                    put("statusText", sh.statusText)
                    put("nextEpisodeCode", sh.nextEpisodeCode)
                    put("nextEpisodeTitle", sh.nextEpisodeTitle)
                    put("nextAirDateLabel", sh.nextAirDateLabel)
                    put("airTimestampEpochMs", sh.airTimestampEpochMs)
                    put("lastWatchedEpisodeCode", sh.lastWatchedEpisodeCode)
                    put("artworkUrl", sh.artworkUrl)
                    put("summarySnippet", sh.summarySnippet)
                    put("appIdHint", appId)
                    put("contentIdHint", sh.contentIdHint)
                    put("accentColorHex", sh.accentColorHex)
                    put("isFavourite", sh.isFavourite)
                }
            )
        }
        return arr
    }

    private fun deserializeTrackedMediaShows(rawJson: String?, hasUserCustomized: Boolean = false): List<TrackedMediaShow> {
        if (rawJson == null) return emptyList()
        if (rawJson.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            val list = mutableListOf<TrackedMediaShow>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title", "").trim()
                if (title.isBlank()) continue
                val kind = runCatching {
                    TrackedMediaKind.valueOf(o.optString("kind", TrackedMediaKind.TV_SHOW.name))
                }.getOrDefault(TrackedMediaKind.TV_SHOW)
                val rawNetwork = o.optString("networkOrStreamer", "TV Series")
                val appIdHint = o.optString("appIdHint", "bbc")
                val normalizedStreamer = NextEpisodeDirectoryClient.normalizeStreamerName(rawNetwork, appIdHint)
                list.add(
                    TrackedMediaShow(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        externalId = o.optString("externalId", ""),
                        title = title,
                        kind = kind,
                        networkOrStreamer = normalizedStreamer,
                        statusText = o.optString("statusText", "Running"),
                        nextEpisodeCode = o.optString("nextEpisodeCode", "Next Episode"),
                        nextEpisodeTitle = o.optString("nextEpisodeTitle", ""),
                        nextAirDateLabel = o.optString("nextAirDateLabel", "Upcoming"),
                        airTimestampEpochMs = o.optLong("airTimestampEpochMs", 0L),
                        lastWatchedEpisodeCode = o.optString("lastWatchedEpisodeCode", ""),
                        artworkUrl = o.optString("artworkUrl", ""),
                        summarySnippet = o.optString("summarySnippet", ""),
                        appIdHint = appIdHint,
                        contentIdHint = o.optString("contentIdHint", ""),
                        accentColorHex = o.optLong("accentColorHex", 0xFF00E5FFL),
                        isFavourite = o.optBoolean("isFavourite", true)
                    )
                )
            }
            list.distinctBy { it.title.trim().lowercase(Locale.US) }
        } catch (_: Exception) {
            if (hasUserCustomized) emptyList() else DEFAULT_TRACKED_MEDIA_SHOWS
        }
    }

    private fun serializeFootballTvProviderConfig(cfg: FootballTvProviderConfig): JSONObject =
        JSONObject().apply {
            put("providerOption", cfg.providerOption.name)
            put("customPlayerAppId", cfg.customPlayerAppId)
            put("xtreamServerUrl", cfg.xtreamServerUrl)
            put("xtreamUsername", cfg.xtreamUsername)
            put("m3uPlaylistUrl", cfg.m3uPlaylistUrl)
            put("autoMatchedChannels", serializeIboMappings(cfg.autoMatchedChannels))
        }

    private fun deserializeFootballTvProviderConfig(context: Context?, rawJson: String?): FootballTvProviderConfig {
        if (rawJson.isNullOrBlank()) return FootballTvProviderConfig()
        return try {
            val o = JSONObject(rawJson)
            val opt = runCatching {
                FootballTvProviderOption.valueOf(o.optString("providerOption", FootballTvProviderOption.IBO_PRO_PLAYER.name))
            }.getOrDefault(FootballTvProviderOption.IBO_PRO_PLAYER)
            val channelsObj = o.optJSONObject("autoMatchedChannels")
            val matchedMap = if (channelsObj != null) {
                deserializeIboMappings(channelsObj.toString())
            } else {
                DEFAULT_IBO_CHANNEL_MAPPINGS
            }
            
            var pass = o.optString("xtreamPassword", "")
            if (pass.isNotBlank() && context != null) {
                pass = unwrapV1(pass)
                if (!pass.startsWith("PIN_SHA256_V1:")) {
                    ClaraSecretVault.putString(context, "iptv::xtream_password", pass)
                }
            } else if (context != null) {
                pass = ClaraSecretVault.getString(context, "iptv::xtream_password") ?: ""
            }

            FootballTvProviderConfig(
                providerOption = opt,
                customPlayerAppId = o.optString("customPlayerAppId", opt.appId),
                xtreamServerUrl = o.optString("xtreamServerUrl", ""),
                xtreamUsername = o.optString("xtreamUsername", ""),
                xtreamPassword = pass,
                m3uPlaylistUrl = o.optString("m3uPlaylistUrl", ""),
                autoMatchedChannels = matchedMap
            )
        } catch (_: Exception) {
            FootballTvProviderConfig()
        }
    }

    private fun serializeCalendarEvents(events: List<HouseholdCalendarEvent>): JSONArray {
        val arr = JSONArray()
        events.forEach { ev ->
            arr.put(
                JSONObject().apply {
                    put("id", ev.id)
                    put("title", ev.title)
                    put("subtitle", ev.subtitle)
                    put("dateIso", ev.dateIso)
                    put("timeLabel", ev.timeLabel)
                    put("category", ev.category.name)
                    put("isRecurringYearly", ev.isRecurringYearly)
                    put("tvLaunchAppId", ev.tvLaunchAppId)
                    put("tvLaunchContentId", ev.tvLaunchContentId)
                    put("iboChannelNumber", ev.iboChannelNumber)
                }
            )
        }
        return arr
    }

    private fun deserializeCalendarEvents(rawJson: String?): List<HouseholdCalendarEvent> {
        if (rawJson.isNullOrBlank()) return DEFAULT_HOUSEHOLD_CALENDAR_EVENTS
        return try {
            val arr = JSONArray(rawJson)
            val list = mutableListOf<HouseholdCalendarEvent>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title", "").trim()
                if (title.isBlank()) continue
                val cat = runCatching {
                    CalendarEventCategory.valueOf(o.optString("category", CalendarEventCategory.REMINDER.name))
                }.getOrDefault(CalendarEventCategory.REMINDER)
                list.add(
                    HouseholdCalendarEvent(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        title = title,
                        subtitle = o.optString("subtitle", ""),
                        dateIso = o.optString("dateIso", ""),
                        timeLabel = o.optString("timeLabel", "All Day"),
                        category = cat,
                        isRecurringYearly = o.optBoolean("isRecurringYearly", cat == CalendarEventCategory.BIRTHDAY),
                        tvLaunchAppId = o.optString("tvLaunchAppId", ""),
                        tvLaunchContentId = o.optString("tvLaunchContentId", ""),
                        iboChannelNumber = o.optInt("iboChannelNumber", 0)
                    )
                )
            }
            list
        } catch (_: Exception) {
            DEFAULT_HOUSEHOLD_CALENDAR_EVENTS
        }
    }

    private fun serializeCouncilBinsConfig(cfg: UkCouncilBinsConfig): JSONObject =
        JSONObject().apply {
            put("collectionDayOfWeek", cfg.collectionDayOfWeek)
            put("anchorRecyclingDateIso", cfg.anchorRecyclingDateIso)
            put("alternateWeeklyRecyclingAndRefuse", cfg.alternateWeeklyRecyclingAndRefuse)
            put("includeFoodCaddyWeekly", cfg.includeFoodCaddyWeekly)
            put("includeGardenBinWithRecycling", cfg.includeGardenBinWithRecycling)
        }

    private fun deserializeCouncilBinsConfig(rawJson: String?): UkCouncilBinsConfig {
        if (rawJson.isNullOrBlank()) return UkCouncilBinsConfig()
        return try {
            val o = JSONObject(rawJson)
            UkCouncilBinsConfig(
                collectionDayOfWeek = o.optInt("collectionDayOfWeek", java.util.Calendar.WEDNESDAY).coerceIn(1, 7),
                anchorRecyclingDateIso = o.optString("anchorRecyclingDateIso", "2026-11-04"),
                alternateWeeklyRecyclingAndRefuse = o.optBoolean("alternateWeeklyRecyclingAndRefuse", true),
                includeFoodCaddyWeekly = o.optBoolean("includeFoodCaddyWeekly", true),
                includeGardenBinWithRecycling = o.optBoolean("includeGardenBinWithRecycling", false)
            )
        } catch (_: Exception) {
            UkCouncilBinsConfig()
        }
    }

    private fun serializeStringList(items: List<String>): JSONArray =
        JSONArray().apply { items.forEach { if (it.isNotBlank()) put(it.trim()) } }

    private fun deserializeStringList(rawJson: String?, defaultList: List<String>): List<String> {
        if (rawJson == null) return defaultList
        if (rawJson.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optString(i, "").trim().takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        } catch (_: Exception) {
            defaultList
        }
    }

    private fun serializeIboMappings(map: Map<String, Int>): JSONObject =
        JSONObject().apply {
            map.forEach { (k, v) -> if (k.isNotBlank() && v > 0) put(k, v) }
        }

    private fun deserializeIboMappings(rawJson: String?): Map<String, Int> {
        if (rawJson.isNullOrBlank()) return DEFAULT_IBO_CHANNEL_MAPPINGS
        return try {
            val o = JSONObject(rawJson)
            val result = mutableMapOf<String, Int>()
            result.putAll(DEFAULT_IBO_CHANNEL_MAPPINGS)
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = o.optInt(k, 0)
                if (k.isNotBlank() && v > 0) result[k] = v
            }
            result
        } catch (_: Exception) {
            DEFAULT_IBO_CHANNEL_MAPPINGS
        }
    }

    private fun serializeGuestWifiConfig(cfg: GuestWifiConfig): JSONObject =
        JSONObject().apply {
            put("ssid", cfg.ssid)
            put("securityType", cfg.securityType)
            put("commuteOriginName", cfg.commuteOriginName)
            put("commuteDestinationName", cfg.commuteDestinationName)
            put("preferredRailStationName", cfg.preferredRailStationName)
            put("preferredRailStationCrs", cfg.preferredRailStationCrs)
        }

    private fun deserializeGuestWifiConfig(context: Context?, rawJson: String?): GuestWifiConfig {
        if (rawJson.isNullOrBlank()) return GuestWifiConfig()
        return try {
            val o = JSONObject(rawJson)
            
            var pass = o.optString("password", "")
            if (pass.isNotBlank() && context != null) {
                pass = unwrapV1(pass)
                if (!pass.startsWith("PIN_SHA256_V1:")) {
                    ClaraSecretVault.putString(context, "wifi::guest_password", pass)
                }
            } else if (context != null) {
                pass = ClaraSecretVault.getString(context, "wifi::guest_password") ?: ""
            }
            
            GuestWifiConfig(
                ssid = o.optString("ssid", "Clara_Guest_WiFi"),
                password = pass,
                securityType = o.optString("securityType", "WPA"),
                commuteOriginName = o.optString("commuteOriginName", "Home"),
                commuteDestinationName = o.optString("commuteDestinationName", "Work"),
                preferredRailStationName = o.optString("preferredRailStationName", ""),
                preferredRailStationCrs = o.optString("preferredRailStationCrs", "")
            )
        } catch (_: Exception) {
            GuestWifiConfig()
        }
    }

    // -----------------------------------------------------------------------
    // 3. BRIDGE & APPLICATION SETTINGS PERSISTENCE
    // -----------------------------------------------------------------------
    fun loadBridge(context: Context): BridgeSettings {
        cachedBridgeSnapshot?.let { cached ->
            applyThemeMode(
                mode = cached.appThemeMode,
                accentPreset = cached.accentColorPreset,
                customAccentColorArgb = cached.customAccentColorArgb
            )
            return cached
        }
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedHaToken = readBridgeSecret(context, p, KEY_HA_TOKEN)
        val savedImouId = p.getString(KEY_IMOU_APP_ID, "") ?: ""
        val savedImouSecret = readBridgeSecret(context, p, KEY_IMOU_APP_SECRET)
        val savedImouHost = p.getString(KEY_IMOU_API_HOST, "") ?: ""

        val savedEzvizKey = p.getString(KEY_EZVIZ_APP_KEY, "") ?: ""
        val savedEzvizSecret = readBridgeSecret(context, p, KEY_EZVIZ_APP_SECRET)
        val savedEzvizHost = p.getString(KEY_EZVIZ_API_HOST, "") ?: ""

        val savedNestProject = p.getString(KEY_NEST_PROJECT_ID, "") ?: ""
        val savedNestClient = p.getString(KEY_NEST_CLIENT_ID, "") ?: ""
        val savedNestSecret = readBridgeSecret(context, p, KEY_NEST_CLIENT_SECRET)
        val savedNestRefresh = readBridgeSecret(context, p, KEY_NEST_REFRESH_TOKEN)
        val savedNestSub = p.getString(KEY_NEST_PUBSUB_SUB, "") ?: ""

        val savedTuyaId = p.getString(KEY_TUYA_ACCESS_ID, "") ?: ""
        val savedTuyaSecret = readBridgeSecret(context, p, KEY_TUYA_ACCESS_SECRET)
        val savedTuyaUid = p.getString(KEY_TUYA_UID, "") ?: ""
        val savedTuyaHost = p.getString(KEY_TUYA_API_HOST, "") ?: ""

        val savedTpLinkEmail = (p.getString(KEY_TPLINK_EMAIL, "") ?: "")
            .ifBlank { TpLinkTapoKasaClient.getSavedTpLinkEmail(context) }
        val savedTpLinkPass = readBridgeSecret(context, p, KEY_TPLINK_PASSWORD)
            .ifBlank { TpLinkTapoKasaClient.getSavedTpLinkPassword(context) }

        val savedEWeLinkEmail = (p.getString(KEY_EWELINK_EMAIL, "") ?: "")
            .ifBlank { EWeLinkSonoffClient.getSavedEWeLinkEmail(context) }
        val savedEWeLinkPass = readBridgeSecret(context, p, KEY_EWELINK_PASSWORD)
            .ifBlank { EWeLinkSonoffClient.getSavedEWeLinkPassword(context) }

        val savedMqttHost = (p.getString(KEY_MQTT_BROKER_HOST, "") ?: "")
            .ifBlank { MqttAndDiyClient.getMqttHost(context) }
        val savedMqttPort = p.getInt(KEY_MQTT_BROKER_PORT, MqttAndDiyClient.getMqttPort(context))
        val savedMqttUser = (p.getString(KEY_MQTT_USERNAME, "") ?: "")
            .ifBlank { MqttAndDiyClient.getMqttUsername(context) }
        val savedMqttPass = readBridgeSecret(context, p, KEY_MQTT_PASSWORD)
            .ifBlank { MqttAndDiyClient.getMqttPassword(context) }

        val savedJaguarEmail = p.getString(KEY_JAGUAR_EMAIL, "") ?: ""
        val savedJaguarPass = readBridgeSecret(context, p, KEY_JAGUAR_PASSWORD)
        val savedJaguarPin = readBridgeSecret(context, p, KEY_JAGUAR_PIN)
        val savedJaguarVin = sanitizeRealVin(p.getString(KEY_JAGUAR_VIN, "") ?: "")

        val savedMercedesEmail = p.getString(KEY_MERCEDES_EMAIL, "") ?: ""
        val savedMercedesPass = readBridgeSecret(context, p, KEY_MERCEDES_PASSWORD)
        val savedMercedesPin = readBridgeSecret(context, p, KEY_MERCEDES_PIN)
        val savedMercedesVin = sanitizeRealVin(p.getString(KEY_MERCEDES_VIN, "") ?: "")

        val savedLytmiIp = p.getString(KEY_LYTMI_IP, "") ?: ""
        val savedLytmiDevId = p.getString(KEY_LYTMI_DEVICE_ID, "") ?: ""
        val savedLytmiLocalKey = readBridgeSecret(context, p, KEY_LYTMI_LOCAL_KEY)

        val savedGoveeApiKey = readBridgeSecret(context, p, KEY_GOVEE_API_KEY)
        val savedGoveeIp = p.getString(KEY_GOVEE_IP, "") ?: ""
        val savedTwinklyIp = p.getString(KEY_TWINKLY_IP, "") ?: ""

        val savedPlexUrl = p.getString(KEY_PLEX_SERVER_URL, "") ?: ""
        val savedPlexToken = readBridgeSecret(context, p, KEY_PLEX_TOKEN)

        val savedMusicProvider = p.getString(KEY_MUSIC_PROVIDER, MusicProvider.SPOTIFY.name)
            ?: MusicProvider.SPOTIFY.name
        val parsedMusicProvider = runCatching {
            MusicProvider.valueOf(savedMusicProvider)
        }.getOrDefault(MusicProvider.SPOTIFY)

        val savedThemeName = p.getString(KEY_APP_THEME_MODE, AppThemeMode.DARK.name)
            ?: AppThemeMode.DARK.name
        val parsedThemeMode = runCatching {
            AppThemeMode.valueOf(savedThemeName)
        }.getOrDefault(AppThemeMode.DARK)

        val savedAccentName = p.getString(KEY_ACCENT_COLOR_PRESET, AppAccentColorPreset.MINT_GREEN.name)
            ?: AppAccentColorPreset.MINT_GREEN.name
        val parsedAccentPreset = runCatching {
            AppAccentColorPreset.valueOf(savedAccentName)
        }.getOrDefault(AppAccentColorPreset.MINT_GREEN)

        val defaultAccentArgb = (parsedAccentPreset.darkHex or 0xFF000000L)
        val savedCustomAccentArgb = p.getLong(KEY_CUSTOM_ACCENT_COLOR_ARGB, defaultAccentArgb)

        val savedClockStyleName = p.getString(KEY_CLOCK_FACE_STYLE, ClockFaceStyle.RADIAL_ORB.name)
            ?: ClockFaceStyle.RADIAL_ORB.name
        val parsedClockFaceStyle = runCatching {
            ClockFaceStyle.valueOf(savedClockStyleName)
        }.getOrDefault(ClockFaceStyle.RADIAL_ORB)

        val savedHeroTimeoutSec = p.getInt(KEY_HERO_CARD_TIMEOUT_SEC, 12).coerceIn(5, 300)

        val savedSlideshowSource = p.getString(KEY_SLIDESHOW_SOURCE_TYPE, SlideshowSourceType.LOCAL_DEVICE.name)
            ?: SlideshowSourceType.LOCAL_DEVICE.name
        val parsedSlideshowSource = runCatching {
            SlideshowSourceType.valueOf(savedSlideshowSource)
        }.getOrDefault(SlideshowSourceType.LOCAL_DEVICE)

        val savedSlideshowUrisRaw = p.getString(KEY_SLIDESHOW_LOCAL_URIS, null)
        val parsedSlideshowUris = mutableListOf<String>()
        if (!savedSlideshowUrisRaw.isNullOrBlank()) {
            try {
                val arr = JSONArray(savedSlideshowUrisRaw)
                for (i in 0 until arr.length()) {
                    arr.optString(i, "").takeIf { it.isNotBlank() }?.let { parsedSlideshowUris.add(it) }
                }
            } catch (_: Exception) {
            }
        }

        val hasSeededDefaultRooms = p.getBoolean(KEY_SEEDED_DEFAULT_ROOMS_V1, false)
        val savedCustomRoomsRaw = p.getString(KEY_USER_CREATED_ROOMS_JSON, null)
        val parsedCustomRooms = mutableListOf<String>()
        if (!hasSeededDefaultRooms && savedCustomRoomsRaw == null) {
            parsedCustomRooms.addAll(DEFAULT_ROOMS)
            val initialRoomsArr = JSONArray().apply { DEFAULT_ROOMS.forEach { put(it) } }
            p.edit()
                .putString(KEY_USER_CREATED_ROOMS_JSON, initialRoomsArr.toString())
                .putBoolean(KEY_SEEDED_DEFAULT_ROOMS_V1, true)
                .apply()
        } else {
            if (!hasSeededDefaultRooms) {
                p.edit().putBoolean(KEY_SEEDED_DEFAULT_ROOMS_V1, true).apply()
            }
            if (!savedCustomRoomsRaw.isNullOrBlank()) {
                try {
                    val arr = JSONArray(savedCustomRoomsRaw)
                    for (i in 0 until arr.length()) {
                        val r = arr.optString(i, "").trim()
                        if (r.isNotBlank() && r !in parsedCustomRooms) {
                            parsedCustomRooms.add(r)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        // Load Dashboard Card Order
        val savedCardOrderRaw = p.getString(KEY_DASHBOARD_CARD_ORDER_JSON, null)
        val parsedCardOrder = mutableListOf<String>()
        if (!savedCardOrderRaw.isNullOrBlank()) {
            try {
                val arr = JSONArray(savedCardOrderRaw)
                for (i in 0 until arr.length()) {
                    val key = arr.optString(i, "").trim()
                    if (DashboardCardSlot.fromKey(key) != null && key !in parsedCardOrder) {
                        parsedCardOrder.add(key)
                    }
                }
            } catch (_: Exception) {
            }
        }
        DashboardCardSlot.defaultOrderKeys.forEach { defaultKey ->
            if (defaultKey !in parsedCardOrder) {
                parsedCardOrder.add(defaultKey)
            }
        }

        // Load Dashboard Card Width Weights
        val parsedWidthWeights = DashboardCardSlot.defaultWidthMap.toMutableMap()
        val savedWidthWeightsRaw = p.getString(KEY_DASHBOARD_CARD_WIDTH_WEIGHTS_JSON, null)
        if (!savedWidthWeightsRaw.isNullOrBlank()) {
            try {
                val obj = JSONObject(savedWidthWeightsRaw)
                for (slot in DashboardCardSlot.entries) {
                    if (obj.has(slot.key)) {
                        val w = obj.optDouble(slot.key, slot.defaultWidthWeight.toDouble()).toFloat()
                        parsedWidthWeights[slot.key] = w.coerceIn(0.45f, 3.0f)
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Load Dashboard Card Height Weights
        val parsedHeightWeights = DashboardCardSlot.defaultHeightMap.toMutableMap()
        val savedHeightWeightsRaw = p.getString(KEY_DASHBOARD_CARD_HEIGHT_WEIGHTS_JSON, null)
        if (!savedHeightWeightsRaw.isNullOrBlank()) {
            try {
                val obj = JSONObject(savedHeightWeightsRaw)
                for (slot in DashboardCardSlot.entries) {
                    if (obj.has(slot.key)) {
                        val h = obj.optDouble(slot.key, slot.defaultHeightWeight.toDouble()).toFloat()
                        parsedHeightWeights[slot.key] = h.coerceIn(0.45f, 3.0f)
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Load Per-Card Zoom Scales (70% - 140%)
        val parsedZoomScales = DashboardCardSlot.defaultZoomMap.toMutableMap()
        val savedZoomScalesRaw = p.getString(KEY_DASHBOARD_CARD_ZOOM_SCALES_JSON, null)
        if (!savedZoomScalesRaw.isNullOrBlank()) {
            try {
                val obj = JSONObject(savedZoomScalesRaw)
                for (slot in DashboardCardSlot.entries) {
                    if (obj.has(slot.key)) {
                        val z = obj.optDouble(slot.key, 1.0).toFloat()
                        parsedZoomScales[slot.key] = z.coerceIn(0.70f, 1.40f)
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Load Per-Slot Widget Types
        val parsedSlotWidgets = DashboardCardSlot.defaultWidgetTypeMap.toMutableMap()
        val savedSlotWidgetsRaw = p.getString(KEY_DASHBOARD_SLOT_WIDGET_TYPES_JSON, null)
        if (!savedSlotWidgetsRaw.isNullOrBlank()) {
            try {
                val obj = JSONObject(savedSlotWidgetsRaw)
                for (slot in DashboardCardSlot.entries) {
                    val rawType = obj.optString(slot.key, "")
                    if (rawType.isNotBlank()) {
                        val wt = runCatching { DashboardWidgetType.valueOf(rawType) }
                            .getOrDefault(slot.defaultWidgetType)
                        parsedSlotWidgets[slot.key] = wt
                    }
                }
            } catch (_: Exception) {
            }
        }

        val parsedDpiPreset = runCatching {
            DpiScalePreset.valueOf(p.getString(KEY_DPI_SCALE_PRESET, DpiScalePreset.DEFAULT.name) ?: DpiScalePreset.DEFAULT.name)
        }.getOrDefault(DpiScalePreset.DEFAULT)
        val savedCustomDpiPct = p.getInt(KEY_CUSTOM_DPI_SCALE_PCT, 100).coerceIn(70, 150)
        val parsedGridSizePreset = runCatching {
            DashboardGridSizePreset.valueOf(p.getString("grid_size_preset_v1", DashboardGridSizePreset.COMPACT.name) ?: DashboardGridSizePreset.COMPACT.name)
        }.getOrDefault(DashboardGridSizePreset.COMPACT)

        val parsedTemplate = runCatching {
            DashboardTemplate.valueOf(
                p.getString(KEY_DASHBOARD_TEMPLATE, DashboardTemplate.STANDARD_QUAD.name)
                    ?: DashboardTemplate.STANDARD_QUAD.name
            )
        }.getOrDefault(DashboardTemplate.STANDARD_QUAD)

        val parsedNavDock = runCatching {
            NavDockPosition.valueOf(
                p.getString(KEY_NAV_DOCK_POSITION, NavDockPosition.LEFT.name) ?: NavDockPosition.LEFT.name
            )
        }.getOrDefault(NavDockPosition.LEFT)
        val savedInvertNavOrder = p.getBoolean(KEY_INVERT_NAV_ORDER, false)

        val savedRolePin = readBridgeSecret(context, p, KEY_ROLE_PIN_CODE).let {
            if (it.isNotBlank()) hashPinCode(it) else ""
        }
        val rawUserProfiles = deserializeUserProfiles(p.getString(KEY_USER_PROFILES_JSON, null))
        val hasOwnerPinOnLoad = savedRolePin.isNotBlank() || rawUserProfiles.any {
            (it.role == UserRole.OWNER || it.role == UserRole.ADMIN) && it.customPinCode.trim().isNotBlank()
        }
        val parsedUserProfiles = if (hasOwnerPinOnLoad) {
            rawUserProfiles
        } else {
            rawUserProfiles.map { prof ->
                if (prof.isParentalLocked || prof.requirePinToSwitchOut) {
                    prof.copy(
                        name = if (prof.name == "Kids (Locked)") "Kids" else prof.name,
                        isParentalLocked = false,
                        requirePinToSwitchOut = false
                    )
                } else {
                    prof
                }
            }
        }
        val savedActiveProfileId = p.getString(KEY_ACTIVE_USER_PROFILE_ID, parsedUserProfiles.first().id)
            ?: parsedUserProfiles.first().id
        val matchedActiveProfile = parsedUserProfiles.firstOrNull { it.id == savedActiveProfileId }
            ?: parsedUserProfiles.first()

        val parsedUserRole = runCatching {
            UserRole.valueOf(
                p.getString(KEY_ACTIVE_USER_ROLE, matchedActiveProfile.role.name) ?: matchedActiveProfile.role.name
            )
        }.getOrDefault(matchedActiveProfile.role)
        val savedRequirePin = hasOwnerPinOnLoad && p.getBoolean(KEY_REQUIRE_PIN_ADMIN_VEHICLES, false)

        // Seed default radio stations only once on initial installation so every station has a working remove button
        val hasSeededDefaultRadio = p.getBoolean(KEY_SEEDED_DEFAULT_RADIO_V1, false)
        val rawRadioJson = p.getString(KEY_CUSTOM_RADIO_STATIONS_JSON, null)
        val parsedCustomRadioStations = if (!hasSeededDefaultRadio && rawRadioJson == null) {
            val initialSeedJson = serializeCustomRadioStations(DEFAULT_RADIO_STATIONS).toString()
            p.edit()
                .putString(KEY_CUSTOM_RADIO_STATIONS_JSON, initialSeedJson)
                .putBoolean(KEY_SEEDED_DEFAULT_RADIO_V1, true)
                .apply()
            DEFAULT_RADIO_STATIONS
        } else {
            if (!hasSeededDefaultRadio) {
                p.edit().putBoolean(KEY_SEEDED_DEFAULT_RADIO_V1, true).apply()
            }
            deserializeCustomRadioStations(rawRadioJson ?: "")
        }
        val savedActiveRadioId = p.getString(KEY_ACTIVE_RADIO_STATION_ID, DEFAULT_RADIO_STATIONS.first().id)
            ?: DEFAULT_RADIO_STATIONS.first().id

        val hasUserCustomizedShows = p.getBoolean(KEY_USER_CUSTOMIZED_TV_SHOWS_V1, true)
        val parsedCalendarEvents = deserializeCalendarEvents(p.getString(KEY_CALENDAR_EVENTS_JSON, null))
        val parsedCouncilBinsConfig = deserializeCouncilBinsConfig(p.getString(KEY_COUNCIL_BINS_CONFIG_JSON, null))
        val hasSeededFootballTeams = p.getBoolean(KEY_SEEDED_DEFAULT_FOOTBALL_TEAMS_V1, false)
        val hasUserCustomizedFootball = p.getBoolean(KEY_USER_CUSTOMIZED_FOOTBALL_TEAMS_V1, false)
        val rawFootballTeamsJson = p.getString(KEY_FOLLOWED_FOOTBALL_TEAMS_JSON, null)
        val parsedFollowedFootballTeams = if (!hasSeededFootballTeams && !hasUserCustomizedFootball && rawFootballTeamsJson == null) {
            val initialTeamsJson = serializeStringList(DEFAULT_FOLLOWED_FOOTBALL_TEAMS).toString()
            p.edit()
                .putString(KEY_FOLLOWED_FOOTBALL_TEAMS_JSON, initialTeamsJson)
                .putBoolean(KEY_SEEDED_DEFAULT_FOOTBALL_TEAMS_V1, true)
                .apply()
            DEFAULT_FOLLOWED_FOOTBALL_TEAMS
        } else {
            if (!hasSeededFootballTeams) {
                p.edit().putBoolean(KEY_SEEDED_DEFAULT_FOOTBALL_TEAMS_V1, true).apply()
            }
            deserializeStringList(
                rawFootballTeamsJson ?: "",
                if (hasUserCustomizedFootball) emptyList() else DEFAULT_FOLLOWED_FOOTBALL_TEAMS
            )
        }
        val parsedIboMappings = deserializeIboMappings(p.getString(KEY_IBO_CHANNEL_MAPPINGS_JSON, null))
        val parsedFootballTvProviderConfig = deserializeFootballTvProviderConfig(
            context, p.getString(KEY_FOOTBALL_TV_PROVIDER_CONFIG_JSON, null)
        )
        val parsedGuestWifiConfig = deserializeGuestWifiConfig(context, p.getString(KEY_GUEST_WIFI_CONFIG_JSON, null))
        val savedJaguarReg = p.getString(KEY_JAGUAR_REG_PLATE, "") ?: ""
        val savedMercedesReg = p.getString(KEY_MERCEDES_REG_PLATE, "") ?: ""

        val parsedSecurityMode = runCatching {
            SecurityCenterMode.valueOf(
                p.getString(KEY_SECURITY_CENTER_MODE, SecurityCenterMode.HOME.name) ?: SecurityCenterMode.HOME.name
            )
        }.getOrDefault(SecurityCenterMode.HOME)

        applyThemeMode(parsedThemeMode, parsedAccentPreset, savedCustomAccentArgb)
        loadCastGroups(context)

        val savedChimeIpsRaw = p.getString(KEY_DOORBELL_CHIME_IPS, "") ?: ""
        val parsedChimeIps = savedChimeIpsRaw
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        return BridgeSettings(
            haUrl = p.getString(KEY_HA_URL, "http://homeassistant.local:8123") ?: "",
            haToken = savedHaToken.ifBlank { DEFAULT_HA_TOKEN },
            imouAppId = savedImouId.ifBlank { DEFAULT_IMOU_APP_ID },
            imouAppSecret = savedImouSecret.ifBlank { DEFAULT_IMOU_APP_SECRET },
            imouApiHost = savedImouHost.ifBlank { DEFAULT_IMOU_API_HOST },
            ezvizAppKey = savedEzvizKey,
            ezvizAppSecret = savedEzvizSecret,
            ezvizApiHost = savedEzvizHost.ifBlank { DEFAULT_EZVIZ_API_HOST },
            nestProjectId = savedNestProject.ifBlank { DEFAULT_NEST_PROJECT_ID },
            nestClientId = savedNestClient.ifBlank { DEFAULT_NEST_CLIENT_ID },
            nestClientSecret = savedNestSecret.ifBlank { DEFAULT_NEST_CLIENT_SECRET },
            nestRefreshToken = savedNestRefresh.ifBlank { DEFAULT_NEST_REFRESH_TOKEN },
            nestPubSubSubscription = savedNestSub.ifBlank { DEFAULT_NEST_PUBSUB_SUB },
            tuyaAccessId = savedTuyaId.ifBlank { DEFAULT_TUYA_ACCESS_ID },
            tuyaAccessSecret = savedTuyaSecret.ifBlank { DEFAULT_TUYA_ACCESS_SECRET },
            tuyaUid = savedTuyaUid.ifBlank { DEFAULT_TUYA_UID },
            tuyaApiHost = savedTuyaHost.ifBlank { DEFAULT_TUYA_API_HOST },
            tplinkEmail = savedTpLinkEmail,
            tplinkPassword = savedTpLinkPass,
            ewelinkEmail = savedEWeLinkEmail,
            ewelinkPassword = savedEWeLinkPass,
            mqttBrokerHost = savedMqttHost,
            mqttBrokerPort = savedMqttPort,
            mqttUsername = savedMqttUser,
            mqttPassword = savedMqttPass,
            jaguarEmail = savedJaguarEmail,
            jaguarPassword = savedJaguarPass,
            jaguarPin = savedJaguarPin,
            jaguarVin = savedJaguarVin,
            mercedesEmail = savedMercedesEmail,
            mercedesPassword = savedMercedesPass,
            mercedesPin = savedMercedesPin,
            mercedesVin = savedMercedesVin,
            lytmiIpAddress = savedLytmiIp,
            lytmiDeviceId = savedLytmiDevId,
            lytmiLocalKey = savedLytmiLocalKey,
            goveeApiKey = savedGoveeApiKey,
            goveeIpAddress = savedGoveeIp,
            twinklyIpAddress = savedTwinklyIp,
            plexServerUrl = savedPlexUrl,
            plexToken = savedPlexToken,
            musicProvider = parsedMusicProvider,
            appThemeMode = parsedThemeMode,
            accentColorPreset = parsedAccentPreset,
            customAccentColorArgb = savedCustomAccentArgb,
            clockFaceStyle = parsedClockFaceStyle,
            heroCardTimeoutSeconds = savedHeroTimeoutSec,
            keepScreenAwake = p.getBoolean(KEY_KEEP_SCREEN_AWAKE, false),
            sleepTimeoutSeconds = p.getInt(KEY_SLEEP_TIMEOUT_SEC, 90),
            enablePresenceWake = p.getBoolean(KEY_ENABLE_PRESENCE_WAKE, true),
            enableVoiceAnnouncements = p.getBoolean(KEY_ENABLE_VOICE_TTS, true),
            cloudStreamAutoPauseSeconds = p.getInt(KEY_CLOUD_AUTO_PAUSE_SEC, 120),
            showRoomLabelsOnTiles = p.getBoolean(KEY_SHOW_ROOM_LABELS, true),
            showTemperatureOnTiles = p.getBoolean(KEY_SHOW_TEMP_ON_TILES, false),
            enableGroupCards = p.getBoolean(KEY_ENABLE_GROUP_CARDS, true),
            customRooms = parsedCustomRooms,
            dashboardGreetingName = p.getString(KEY_DASHBOARD_GREETING_NAME, "Home") ?: "Home",
            enableVoiceControl = p.getBoolean(KEY_ENABLE_VOICE_CONTROL, true),
            enableWakeWordListening = p.getBoolean(KEY_ENABLE_WAKE_WORD, true),
            voiceWakeWord = p.getString(KEY_VOICE_WAKE_WORD, "Hey Clara") ?: "Hey Clara",
            enableNestMiniDoorbellChime = p.getBoolean(KEY_ENABLE_NEST_DOORBELL_CHIME, true),
            nestMiniDoorbellVolume = p.getInt(KEY_NEST_DOORBELL_VOLUME, 75),
            doorbellChimeMessage = p.getString(KEY_DOORBELL_CHIME_MSG, "Someone is at the front door")
                ?: "Someone is at the front door",
            doorbellChimeTargetIps = parsedChimeIps,
            customDoorbellMp3Uri = p.getString(KEY_CUSTOM_DOORBELL_MP3_URI, "") ?: "",
            customAlarmMp3Uri = p.getString(KEY_CUSTOM_ALARM_MP3_URI, "") ?: "",
            enableSwipeableSlideshowPage = p.getBoolean(KEY_ENABLE_SWIPE_SLIDESHOW, true),
            showClockOnSlideshowPage = p.getBoolean(KEY_SHOW_CLOCK_ON_SLIDESHOW, true),
            slideshowSourceType = parsedSlideshowSource,
            slideshowCloudUrl = p.getString(KEY_SLIDESHOW_CLOUD_URL, "") ?: "",
            slideshowLocalUris = parsedSlideshowUris,
            slideshowIntervalSeconds = p.getInt(KEY_SLIDESHOW_INTERVAL_SEC, 10).coerceAtLeast(3),
            slideshowIncludeSubfolders = p.getBoolean(KEY_SLIDESHOW_INCLUDE_SUBFOLDERS, true),
            dashboardCardOrder = parsedCardOrder,
            dashboardCardWidthWeights = parsedWidthWeights,
            dashboardCardHeightWeights = parsedHeightWeights,
            dashboardCardZoomScales = parsedZoomScales,
            dashboardSlotWidgetTypes = parsedSlotWidgets,
            dpiScalePreset = parsedDpiPreset,
            customDpiScalePct = savedCustomDpiPct,
            gridSizePreset = parsedGridSizePreset,
            dashboardTemplate = parsedTemplate,
            navDockPosition = parsedNavDock,
            invertNavOrder = savedInvertNavOrder,
            activeUserRole = parsedUserRole,
            rolePinCode = savedRolePin,
            requirePinForAdminAndVehicles = savedRequirePin,
            userProfiles = parsedUserProfiles,
            activeUserProfileId = matchedActiveProfile.id,
            customRadioStations = parsedCustomRadioStations,
            activeRadioStationId = savedActiveRadioId,
            hasUserCustomizedTrackedShows = hasUserCustomizedShows,
            calendarEvents = parsedCalendarEvents,
            councilBinsConfig = parsedCouncilBinsConfig,
            followedFootballTeams = parsedFollowedFootballTeams,
            hasUserCustomizedFootballTeams = hasUserCustomizedFootball,
            iboChannelMappings = parsedIboMappings,
            footballTvProviderConfig = parsedFootballTvProviderConfig,
            guestWifiConfig = parsedGuestWifiConfig,
            jaguarRegPlate = savedJaguarReg,
            mercedesRegPlate = savedMercedesReg,
            electricityTariffDayRateGbp = p.getFloat(KEY_TARIFF_DAY_RATE_GBP, 0.28f),
            electricityTariffNightRateGbp = p.getFloat(KEY_TARIFF_NIGHT_RATE_GBP, 0.09f),
            electricityStandingChargeGbp = p.getFloat(KEY_TARIFF_STANDING_CHARGE_GBP, 0.53f),
            currencySymbol = (p.getString(KEY_CURRENCY_SYMBOL, "\u00A3") ?: "\u00A3").ifBlank { "\u00A3" },
            offPeakStartHour = p.getInt(KEY_OFF_PEAK_START_HOUR, 0).coerceIn(0, 23),
            offPeakEndHour = p.getInt(KEY_OFF_PEAK_END_HOUR, 7).coerceIn(0, 23),
            securityCenterMode = parsedSecurityMode,
            securityHomeTempC = p.getFloat(KEY_SECURITY_HOME_TEMP_C, 20.5f).coerceIn(5f, 35f),
            securityAwayTempC = p.getFloat(KEY_SECURITY_AWAY_TEMP_C, 18.0f).coerceIn(5f, 35f),
            securityNightTempC = p.getFloat(KEY_SECURITY_NIGHT_TEMP_C, 18.5f).coerceIn(5f, 35f),
            securityVacationTempC = p.getFloat(KEY_SECURITY_VACATION_TEMP_C, 16.0f).coerceIn(5f, 35f),
            localLlmEndpointUrl = p.getString(KEY_LOCAL_LLM_ENDPOINT_URL, "") ?: "",
            smartThingsToken = readBridgeSecret(context, p, KEY_SMARTTHINGS_TOKEN),
            frigateHostUrl = p.getString(KEY_FRIGATE_HOST_URL, "") ?: "",
            teslaAccessToken = readBridgeSecret(context, p, KEY_TESLA_ACCESS_TOKEN),
            teslaVin = sanitizeRealVin(p.getString(KEY_TESLA_VIN, "") ?: ""),
            teslaWallConnectorIp = p.getString(KEY_TESLA_WALL_CONNECTOR_IP, "") ?: "",
            hubitatHostUrl = p.getString("hubitat_host_url_v1", "") ?: "",
            hubitatAppId = p.getString("hubitat_app_id_v1", "") ?: "",
            hubitatAccessToken = readBridgeSecret(context, p, "hubitat_access_token_v1"),
            homeyHostUrl = p.getString("homey_host_url_v1", "") ?: "",
            homeyBearerToken = readBridgeSecret(context, p, "homey_bearer_token_v1"),
            octopusApiKey = readBridgeSecret(context, p, "octopus_api_key_v1"),
            octopusMpan = p.getString("octopus_mpan_v1", "") ?: "",
            octopusSerialNumber = p.getString("octopus_serial_v1", "") ?: "",
            octopusTariffCode = p.getString("octopus_tariff_code_v1", "") ?: "",
            powerwallHostIp = p.getString("powerwall_host_ip_v1", "") ?: "",
            powerwallPassword = readBridgeSecret(context, p, "powerwall_password_v1"),
            givEnergyApiKey = readBridgeSecret(context, p, "givenergy_api_key_v1"),
            givEnergyInverterSerial = p.getString("givenergy_inverter_sn_v1", "") ?: "",
            solarEdgeApiKey = readBridgeSecret(context, p, "solaredge_api_key_v1"),
            solarEdgeSiteId = p.getString("solaredge_site_id_v1", "") ?: "",
            unifiControllerUrl = p.getString("unifi_controller_url_v1", "") ?: "",
            unifiUsername = p.getString("unifi_username_v1", "") ?: "",
            unifiPassword = readBridgeSecret(context, p, "unifi_password_v1")
        ).also { loaded ->
            cachedBridgeSnapshot = loaded
            bridgeSettingsFlow.value = loaded
        }
    }

            fun saveBridge(context: Context, settings: BridgeSettings) {
                val appCtx = context.applicationContext
                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val prevSnapshot = cachedBridgeSnapshot

                val defaultTeamsSet = DEFAULT_FOLLOWED_FOOTBALL_TEAMS.map { it.lowercase(Locale.US) }.toSet()
                val incomingTeamsSet = settings.followedFootballTeams.map { it.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }.toSet()
                val isTeamsDifferentFromDefault = incomingTeamsSet != defaultTeamsSet
                val wasTeamsAlreadyCustomized = prefs.getBoolean(KEY_USER_CUSTOMIZED_FOOTBALL_TEAMS_V1, false) ||
                        (prevSnapshot?.hasUserCustomizedFootballTeams == true)
                val effectiveTeamsCustomized = settings.hasUserCustomizedFootballTeams || isTeamsDifferentFromDefault || wasTeamsAlreadyCustomized
                val effectiveFootballTeams = if (
                    wasTeamsAlreadyCustomized &&
                    !settings.hasUserCustomizedFootballTeams &&
                    !isTeamsDifferentFromDefault &&
                    prevSnapshot != null &&
                    prevSnapshot.followedFootballTeams.map { it.lowercase(Locale.US) }.toSet() != defaultTeamsSet
                ) {
                    prevSnapshot.followedFootballTeams
                } else {
                    settings.followedFootballTeams
                }

                val sanitizedSettings = settings.copy(
                    followedFootballTeams = effectiveFootballTeams,
                    hasUserCustomizedFootballTeams = effectiveTeamsCustomized
                )
                cachedBridgeSnapshot = sanitizedSettings
        bridgeSettingsFlow.value = sanitizedSettings
        JarvisLiveBridge.securityCenterModeFlow.value = sanitizedSettings.securityCenterMode
                applyThemeMode(
                    mode = sanitizedSettings.appThemeMode,
                    accentPreset = sanitizedSettings.accentColorPreset,
                    customAccentColorArgb = sanitizedSettings.customAccentColorArgb
                )

                repoScope.launch {
                    val urisJsonArr = JSONArray()
                    sanitizedSettings.slideshowLocalUris.forEach { urisJsonArr.put(it) }

                    val customRoomsArr = JSONArray()
                    sanitizedSettings.customRooms.distinct().forEach { if (it.isNotBlank()) customRoomsArr.put(it.trim()) }

                    val cardOrderArr = JSONArray()
                    sanitizedSettings.dashboardCardOrder.forEach { cardOrderArr.put(it) }

                    val cardWidthObj = JSONObject()
                    sanitizedSettings.dashboardCardWidthWeights.forEach { (k, v) ->
                        cardWidthObj.put(k, v.coerceIn(0.45f, 3.0f).toDouble())
                    }

                    val cardHeightObj = JSONObject()
                    sanitizedSettings.dashboardCardHeightWeights.forEach { (k, v) ->
                        cardHeightObj.put(k, v.coerceIn(0.45f, 3.0f).toDouble())
                    }

                    val cardZoomObj = JSONObject()
                    sanitizedSettings.dashboardCardZoomScales.forEach { (k, v) ->
                        cardZoomObj.put(k, v.coerceIn(0.70f, 1.40f).toDouble())
                    }

                    val slotWidgetsObj = JSONObject()
                    sanitizedSettings.dashboardSlotWidgetTypes.forEach { (k, v) ->
                        slotWidgetsObj.put(k, v.name)
                    }

                    val userProfilesArr = serializeUserProfiles(sanitizedSettings.userProfiles)
                    val customRadioArr = serializeCustomRadioStations(sanitizedSettings.customRadioStations)
                    val calendarEventsArr = serializeCalendarEvents(sanitizedSettings.calendarEvents)
                    val councilBinsObj = serializeCouncilBinsConfig(sanitizedSettings.councilBinsConfig)
                    val footballTeamsArr = serializeStringList(sanitizedSettings.followedFootballTeams)
                    val iboMappingsObj = serializeIboMappings(sanitizedSettings.iboChannelMappings)
                    val footballProviderObj = serializeFootballTvProviderConfig(sanitizedSettings.footballTvProviderConfig)
                    val guestWifiObj = serializeGuestWifiConfig(sanitizedSettings.guestWifiConfig)

                    appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
                        putString(KEY_HA_URL, settings.haUrl)
                        writeBridgeSecret(appCtx, this, KEY_HA_TOKEN, settings.haToken)
                        putString(KEY_IMOU_APP_ID, settings.imouAppId)
                        writeBridgeSecret(appCtx, this, KEY_IMOU_APP_SECRET, settings.imouAppSecret)
                        putString(KEY_IMOU_API_HOST, settings.imouApiHost)
                        putString(KEY_EZVIZ_APP_KEY, settings.ezvizAppKey)
                        writeBridgeSecret(appCtx, this, KEY_EZVIZ_APP_SECRET, settings.ezvizAppSecret)
                        putString(KEY_EZVIZ_API_HOST, settings.ezvizApiHost)
                        putString(KEY_NEST_PROJECT_ID, settings.nestProjectId)
                        putString(KEY_NEST_CLIENT_ID, settings.nestClientId)
                        writeBridgeSecret(appCtx, this, KEY_NEST_CLIENT_SECRET, settings.nestClientSecret)
                        writeBridgeSecret(appCtx, this, KEY_NEST_REFRESH_TOKEN, settings.nestRefreshToken)
                        putString(KEY_NEST_PUBSUB_SUB, settings.nestPubSubSubscription)
                        putString(KEY_TUYA_ACCESS_ID, settings.tuyaAccessId)
                        writeBridgeSecret(appCtx, this, KEY_TUYA_ACCESS_SECRET, settings.tuyaAccessSecret)
                        putString(KEY_TUYA_UID, settings.tuyaUid)
                        putString(KEY_TUYA_API_HOST, settings.tuyaApiHost)
                        putString(KEY_TPLINK_EMAIL, settings.tplinkEmail)
                        writeBridgeSecret(appCtx, this, KEY_TPLINK_PASSWORD, settings.tplinkPassword)
                        putString(KEY_EWELINK_EMAIL, settings.ewelinkEmail)
                        writeBridgeSecret(appCtx, this, KEY_EWELINK_PASSWORD, settings.ewelinkPassword)
                        putString(KEY_MQTT_BROKER_HOST, settings.mqttBrokerHost)
                        putInt(KEY_MQTT_BROKER_PORT, settings.mqttBrokerPort)
                        putString(KEY_MQTT_USERNAME, settings.mqttUsername)
                        writeBridgeSecret(appCtx, this, KEY_MQTT_PASSWORD, settings.mqttPassword)
                        putString(KEY_JAGUAR_EMAIL, settings.jaguarEmail)
                        writeBridgeSecret(appCtx, this, KEY_JAGUAR_PASSWORD, settings.jaguarPassword)
                        writeBridgeSecret(appCtx, this, KEY_JAGUAR_PIN, settings.jaguarPin)
                        putString(KEY_JAGUAR_VIN, sanitizeRealVin(settings.jaguarVin))
                        putString(KEY_MERCEDES_EMAIL, settings.mercedesEmail)
                        writeBridgeSecret(appCtx, this, KEY_MERCEDES_PASSWORD, settings.mercedesPassword)
                        writeBridgeSecret(appCtx, this, KEY_MERCEDES_PIN, settings.mercedesPin)
                        putString(KEY_MERCEDES_VIN, sanitizeRealVin(settings.mercedesVin))
                        putString(KEY_LYTMI_IP, settings.lytmiIpAddress)
                        putString(KEY_LYTMI_DEVICE_ID, settings.lytmiDeviceId)
                        writeBridgeSecret(appCtx, this, KEY_LYTMI_LOCAL_KEY, settings.lytmiLocalKey)
                        writeBridgeSecret(appCtx, this, KEY_GOVEE_API_KEY, settings.goveeApiKey)
                        putString(KEY_GOVEE_IP, settings.goveeIpAddress)
                        putString(KEY_TWINKLY_IP, settings.twinklyIpAddress)
                        putString(KEY_PLEX_SERVER_URL, settings.plexServerUrl)
                        writeBridgeSecret(appCtx, this, KEY_PLEX_TOKEN, settings.plexToken)
                        putString(KEY_MUSIC_PROVIDER, settings.musicProvider.name)
                        putString(KEY_APP_THEME_MODE, settings.appThemeMode.name)
                        putString(KEY_ACCENT_COLOR_PRESET, settings.accentColorPreset.name)
                        putLong(KEY_CUSTOM_ACCENT_COLOR_ARGB, settings.customAccentColorArgb)
                        putString(KEY_CLOCK_FACE_STYLE, settings.clockFaceStyle.name)
                        putInt(KEY_HERO_CARD_TIMEOUT_SEC, settings.heroCardTimeoutSeconds.coerceIn(5, 300))
                        putBoolean(KEY_KEEP_SCREEN_AWAKE, settings.keepScreenAwake)
                        putInt(KEY_SLEEP_TIMEOUT_SEC, settings.sleepTimeoutSeconds)
                        putBoolean(KEY_ENABLE_PRESENCE_WAKE, settings.enablePresenceWake)
                        putBoolean(KEY_ENABLE_VOICE_TTS, settings.enableVoiceAnnouncements)
                        putInt(KEY_CLOUD_AUTO_PAUSE_SEC, settings.cloudStreamAutoPauseSeconds)
                        putBoolean(KEY_SHOW_ROOM_LABELS, settings.showRoomLabelsOnTiles)
                        putBoolean(KEY_SHOW_TEMP_ON_TILES, settings.showTemperatureOnTiles)
                        putBoolean(KEY_ENABLE_GROUP_CARDS, settings.enableGroupCards)
                        putString(KEY_USER_CREATED_ROOMS_JSON, customRoomsArr.toString())
                        putBoolean(KEY_SEEDED_DEFAULT_ROOMS_V1, true)
                        putString(KEY_DASHBOARD_GREETING_NAME, settings.dashboardGreetingName.ifBlank { "Home" })
                        putBoolean(KEY_ENABLE_VOICE_CONTROL, settings.enableVoiceControl)
                        putBoolean(KEY_ENABLE_WAKE_WORD, settings.enableWakeWordListening)
                        putString(KEY_VOICE_WAKE_WORD, settings.voiceWakeWord)
                        putBoolean(KEY_ENABLE_NEST_DOORBELL_CHIME, settings.enableNestMiniDoorbellChime)
                        putInt(KEY_NEST_DOORBELL_VOLUME, settings.nestMiniDoorbellVolume)
                        putString(KEY_DOORBELL_CHIME_MSG, settings.doorbellChimeMessage)
                        putString(KEY_DOORBELL_CHIME_IPS, settings.doorbellChimeTargetIps.joinToString(","))
                        putString(KEY_CUSTOM_DOORBELL_MP3_URI, settings.customDoorbellMp3Uri)
                        putString(KEY_CUSTOM_ALARM_MP3_URI, settings.customAlarmMp3Uri)
                        putBoolean(KEY_ENABLE_SWIPE_SLIDESHOW, settings.enableSwipeableSlideshowPage)
                        putBoolean(KEY_SHOW_CLOCK_ON_SLIDESHOW, settings.showClockOnSlideshowPage)
                        putString(KEY_SLIDESHOW_SOURCE_TYPE, settings.slideshowSourceType.name)
                        putString(KEY_SLIDESHOW_CLOUD_URL, settings.slideshowCloudUrl)
                        putString(KEY_SLIDESHOW_LOCAL_URIS, urisJsonArr.toString())
                        putInt(KEY_SLIDESHOW_INTERVAL_SEC, settings.slideshowIntervalSeconds.coerceAtLeast(3))
                        putBoolean(KEY_SLIDESHOW_INCLUDE_SUBFOLDERS, settings.slideshowIncludeSubfolders)
                        putString(KEY_DASHBOARD_CARD_ORDER_JSON, cardOrderArr.toString())
                        putString(KEY_DASHBOARD_CARD_WIDTH_WEIGHTS_JSON, cardWidthObj.toString())
                        putString(KEY_DASHBOARD_CARD_HEIGHT_WEIGHTS_JSON, cardHeightObj.toString())
                        putString(KEY_DASHBOARD_CARD_ZOOM_SCALES_JSON, cardZoomObj.toString())
                        putString(KEY_DASHBOARD_SLOT_WIDGET_TYPES_JSON, slotWidgetsObj.toString())
                        putString(KEY_DPI_SCALE_PRESET, settings.dpiScalePreset.name)
                        putInt(KEY_CUSTOM_DPI_SCALE_PCT, settings.customDpiScalePct.coerceIn(70, 150))
                        putString("grid_size_preset_v1", settings.gridSizePreset.name)
                        putString(KEY_DASHBOARD_TEMPLATE, settings.dashboardTemplate.name)
                        putString(KEY_NAV_DOCK_POSITION, settings.navDockPosition.name)
                        putBoolean(KEY_INVERT_NAV_ORDER, settings.invertNavOrder)
                        putString(KEY_ACTIVE_USER_ROLE, settings.activeUserRole.name)
                        val hashedRolePin = if (settings.rolePinCode.isNotBlank()) hashPinCode(settings.rolePinCode) else ""
                        writeBridgeSecret(appCtx, this, KEY_ROLE_PIN_CODE, hashedRolePin)
                        putBoolean(KEY_REQUIRE_PIN_ADMIN_VEHICLES, settings.requirePinForAdminAndVehicles && hashedRolePin.isNotBlank())
                        putString(KEY_USER_PROFILES_JSON, userProfilesArr.toString())
                        putString(KEY_ACTIVE_USER_PROFILE_ID, settings.activeUserProfileId)
                        putString(KEY_CUSTOM_RADIO_STATIONS_JSON, customRadioArr.toString())
                        putBoolean(KEY_SEEDED_DEFAULT_RADIO_V1, true)
                        putString(KEY_ACTIVE_RADIO_STATION_ID, settings.activeRadioStationId)
                        remove(KEY_TRACKED_MEDIA_SHOWS_JSON)
                        putBoolean(KEY_USER_CUSTOMIZED_TV_SHOWS_V1, sanitizedSettings.hasUserCustomizedTrackedShows)
                        putString(KEY_CALENDAR_EVENTS_JSON, calendarEventsArr.toString())
                        putString(KEY_COUNCIL_BINS_CONFIG_JSON, councilBinsObj.toString())
                        putString(KEY_FOLLOWED_FOOTBALL_TEAMS_JSON, footballTeamsArr.toString())
                        putBoolean(KEY_SEEDED_DEFAULT_FOOTBALL_TEAMS_V1, true)
                        putBoolean(KEY_USER_CUSTOMIZED_FOOTBALL_TEAMS_V1, sanitizedSettings.hasUserCustomizedFootballTeams)
                        putString(KEY_IBO_CHANNEL_MAPPINGS_JSON, iboMappingsObj.toString())
                        putString(KEY_FOOTBALL_TV_PROVIDER_CONFIG_JSON, footballProviderObj.toString())
                        putString(KEY_GUEST_WIFI_CONFIG_JSON, guestWifiObj.toString())
                        putString(KEY_JAGUAR_REG_PLATE, settings.jaguarRegPlate.trim().uppercase(Locale.UK))
                        putString(KEY_MERCEDES_REG_PLATE, settings.mercedesRegPlate.trim().uppercase(Locale.UK))
                        putFloat(KEY_TARIFF_DAY_RATE_GBP, settings.electricityTariffDayRateGbp)
                        putFloat(KEY_TARIFF_NIGHT_RATE_GBP, settings.electricityTariffNightRateGbp)
                        putFloat(KEY_TARIFF_STANDING_CHARGE_GBP, settings.electricityStandingChargeGbp)
                        putString(KEY_CURRENCY_SYMBOL, settings.currencySymbol.ifBlank { "\u00A3" })
                        putInt(KEY_OFF_PEAK_START_HOUR, settings.offPeakStartHour.coerceIn(0, 23))
                        putInt(KEY_OFF_PEAK_END_HOUR, settings.offPeakEndHour.coerceIn(0, 23))
                        putString(KEY_SECURITY_CENTER_MODE, settings.securityCenterMode.name)
                        putFloat(KEY_SECURITY_HOME_TEMP_C, settings.securityHomeTempC.coerceIn(5f, 35f))
                        putFloat(KEY_SECURITY_AWAY_TEMP_C, settings.securityAwayTempC.coerceIn(5f, 35f))
                        putFloat(KEY_SECURITY_NIGHT_TEMP_C, settings.securityNightTempC.coerceIn(5f, 35f))
                        putFloat(KEY_SECURITY_VACATION_TEMP_C, settings.securityVacationTempC.coerceIn(5f, 35f))
                        putString(KEY_LOCAL_LLM_ENDPOINT_URL, settings.localLlmEndpointUrl)
                        writeBridgeSecret(appCtx, this, KEY_SMARTTHINGS_TOKEN, settings.smartThingsToken)
                        putString(KEY_FRIGATE_HOST_URL, settings.frigateHostUrl)
                        writeBridgeSecret(appCtx, this, KEY_TESLA_ACCESS_TOKEN, settings.teslaAccessToken)
                        putString(KEY_TESLA_VIN, sanitizeRealVin(settings.teslaVin))
                        putString(KEY_TESLA_WALL_CONNECTOR_IP, settings.teslaWallConnectorIp)
                        putString("hubitat_host_url_v1", settings.hubitatHostUrl)
                        putString("hubitat_app_id_v1", settings.hubitatAppId)
                        writeBridgeSecret(appCtx, this, "hubitat_access_token_v1", settings.hubitatAccessToken)
                        putString("homey_host_url_v1", settings.homeyHostUrl)
                        writeBridgeSecret(appCtx, this, "homey_bearer_token_v1", settings.homeyBearerToken)
                        writeBridgeSecret(appCtx, this, "octopus_api_key_v1", settings.octopusApiKey)
                        putString("octopus_mpan_v1", settings.octopusMpan)
                        putString("octopus_serial_v1", settings.octopusSerialNumber)
                        putString("octopus_tariff_code_v1", settings.octopusTariffCode)
                        putString("powerwall_host_ip_v1", settings.powerwallHostIp)
                        writeBridgeSecret(appCtx, this, "powerwall_password_v1", settings.powerwallPassword)
                        writeBridgeSecret(appCtx, this, "givenergy_api_key_v1", settings.givEnergyApiKey)
                        putString("givenergy_inverter_sn_v1", settings.givEnergyInverterSerial)
                        writeBridgeSecret(appCtx, this, "solaredge_api_key_v1", settings.solarEdgeApiKey)
                        putString("solaredge_site_id_v1", settings.solarEdgeSiteId)
                        putString("unifi_controller_url_v1", settings.unifiControllerUrl)
                        putString("unifi_username_v1", settings.unifiUsername)
                        writeBridgeSecret(appCtx, this, "unifi_password_v1", settings.unifiPassword)
                        apply()
                    }
                    if (sanitizedSettings.tuyaAccessId.isNotBlank() && sanitizedSettings.tuyaAccessSecret.isNotBlank()) {
                        ClaraApplication.ensureTuyaSdkInitialized(appCtx, true)
                    }
                }
                scheduleAsyncGraphRebuild(context = appCtx, bridge = sanitizedSettings)
            }

    fun saveBridgeSettings(context: Context, settings: BridgeSettings) =
        saveBridge(context, settings)

    fun addCustomRoom(context: Context, newRoomName: String, currentBridge: BridgeSettings): BridgeSettings {
        val clean = newRoomName.trim()
        if (clean.isBlank()) return currentBridge
        val updatedRooms = currentBridge.customRooms.toMutableList()
        if (updatedRooms.none { it.equals(clean, ignoreCase = true) }) {
            updatedRooms.add(clean)
        }
        val updatedBridge = currentBridge.copy(customRooms = updatedRooms)
        saveBridge(context, updatedBridge)
        return updatedBridge
    }

    fun saveCastGroups(context: Context, groups: List<CastSpeakerGroup>) {
        val array = JSONArray()
        for (g in groups) {
            val ipsArr = JSONArray()
            g.memberIps.forEach { ipsArr.put(it) }
            val namesArr = JSONArray()
            g.memberNames.forEach { namesArr.put(it) }
            array.put(
                JSONObject().apply {
                    put("id", g.id)
                    put("name", g.name)
                    put("memberIps", ipsArr)
                    put("memberNames", namesArr)
                    put("volumePercent", g.volumePercent.toDouble())
                }
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CAST_GROUPS_JSON, array.toString())
            .apply()
        JarvisLiveBridge.castSpeakerGroupsFlow.value = groups.toList()
    }

    fun loadCastGroups(context: Context): List<CastSpeakerGroup> {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = p.getString(KEY_CAST_GROUPS_JSON, null)
        if (raw.isNullOrBlank()) {
            JarvisLiveBridge.castSpeakerGroupsFlow.value = emptyList()
            return emptyList()
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<CastSpeakerGroup>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ips = mutableListOf<String>()
                val ipsArr = o.optJSONArray("memberIps")
                if (ipsArr != null) {
                    for (j in 0 until ipsArr.length()) {
                        ipsArr.optString(j, "").takeIf { it.isNotBlank() }?.let { ips.add(it) }
                    }
                }
                val names = mutableListOf<String>()
                val namesArr = o.optJSONArray("memberNames")
                if (namesArr != null) {
                    for (j in 0 until namesArr.length()) {
                        namesArr.optString(j, "").takeIf { it.isNotBlank() }?.let { names.add(it) }
                    }
                }
                list.add(
                    CastSpeakerGroup(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", "All Speakers"),
                        memberIps = ips,
                        memberNames = names,
                        volumePercent = o.optDouble("volumePercent", 55.0).toFloat()
                    )
                )
            }
            JarvisLiveBridge.castSpeakerGroupsFlow.value = list
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    // -----------------------------------------------------------------------
    // 4. DASHBOARD TILES, CAPABILITY REGISTRY & GROUP CARDS PERSISTENCE
    // -----------------------------------------------------------------------
    

    

    private fun unwrapV1(encrypted: String): String {
        if (encrypted.startsWith("PIN_SHA256_V1:")) return encrypted
        if (!encrypted.startsWith("ENC_GCM_V1:")) return encrypted
        return try {
            val parts = encrypted.substringAfter("ENC_GCM_V1:").split(":")
            if (parts.size != 2) return encrypted
            val iv = android.util.Base64.decode(parts[0], android.util.Base64.DEFAULT)
            val cipherText = android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT)
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val secretKey = ks.getKey("clara_jarvis_secret_key", null) as? javax.crypto.SecretKey ?: return encrypted
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, javax.crypto.spec.GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            encrypted
        }
    }

    private fun serializeSingleTileToJson(t: SmartTile): JSONObject {
        val localUrisArr = JSONArray()
        t.slideshowLocalUris.forEach { localUrisArr.put(it) }
        val selectedFoldersArr = JSONArray()
        t.slideshowSelectedFolders.forEach { selectedFoldersArr.put(it) }
        val capabilitiesArr = org.json.JSONArray(CapabilitySnapshotCodec.encodeDescriptors(t.capabilities))
        val resolvedGroupIcon = t.groupCategoryIcon.ifBlank { GroupCardIconOption.LIGHTING.key }
        val cleanVehicleVin = if (t.isVehicleTile) sanitizeRealVin(t.vehicleVin) else t.vehicleVin
        val cleanSerial = if (t.isVehicleTile) sanitizeRealVin(t.serialNumber) else t.serialNumber
        val cleanSensorUnit = if (t.sensorUnit.equals("VOLUME_HERO", ignoreCase = true)) "\u00B0C" else t.sensorUnit

        return JSONObject().apply {
            put("id", t.id)
            put("title", t.title)
            put("roomOrSubtitle", t.roomOrSubtitle)
            put("type", t.type.name)
            put("colSpan", t.colSpan.coerceIn(1, 5))
            put("rowSpan", t.effectiveRowSpan)
            put("gridCol", t.gridCol)
            put("gridRow", t.gridRow)
            put("isTall", t.effectiveRowSpan > 1)
            put("isActive", t.isActive)
            put("isVisible", t.isVisible)
            put("isOnline", t.isOnline)
            put("temperature", t.temperature.toDouble())
            put("targetTemperature", t.targetTemperature.toDouble())
            put("humidity", t.humidity)
            put("hvacMode", t.hvacMode)
            put("hvacStatus", t.hvacStatus)
            put("volume", t.volume.toDouble())
            put("isSocket2Active", t.isSocket2Active)
            put("multiGangSocketCount", t.multiGangSocketCount.coerceAtLeast(1))
            put("socket1Name", t.socket1Name.ifBlank { "Socket 1" })
            put("socket2Name", t.socket2Name.ifBlank { "Socket 2" })
            put("groupCardId", t.groupCardId)
            put("groupCategoryIcon", resolvedGroupIcon)
            put("activeMediaSource", t.activeMediaSource)
            put("supportsPtz", t.supportsPtz)
            put("powerWatts", t.powerWatts.toDouble())
            put("voltageVolts", t.voltageVolts.toDouble())
            put("currentMilliamps", t.currentMilliamps.toDouble())
            put("totalEnergyKwh", t.totalEnergyKwh.toDouble())
            put("countdown1Seconds", t.countdown1Seconds)
            put("countdown2Seconds", t.countdown2Seconds)
            put("isLiveFeedActive", t.isLiveFeedActive)
            put("ipAddress", t.ipAddress)
            put("serialNumber", cleanSerial)
            put("channelId", t.channelId)
            put("useSubStream", t.useSubStream)
            put("customRtspUrl", t.customRtspUrl)
            put("entityId", t.entityId)
            put("companionAppPackage", t.companionAppPackage)
            put("sensorUnit", cleanSensorUnit)
            put("forceVolumeHeroMode", t.forceVolumeHeroMode)
            put("slideshowSourceType", t.slideshowSourceType.name)
            put("slideshowCloudUrl", t.slideshowCloudUrl)
            put("slideshowLocalUris", localUrisArr)
            put("slideshowIntervalSeconds", t.slideshowIntervalSeconds.coerceAtLeast(3))
            put("slideshowIncludeSubfolders", t.slideshowIncludeSubfolders)
            put("slideshowSelectedFolders", selectedFoldersArr)
            put("capabilities", capabilitiesArr)
            put("hasExplicitPowerTelemetry", t.hasExplicitPowerTelemetry)
            put("detectedApplianceCategory", t.detectedApplianceCategory.name)
            put("vehicleLocked", t.vehicleLocked)
            put("vehicleFuelOrBatteryPct", t.vehicleFuelOrBatteryPct)
            put("vehicleRangeMiles", t.vehicleRangeMiles)
            put("vehicleIsEv", t.vehicleIsEv)
            put("vehicleIsCharging", t.vehicleIsCharging)
            put("vehicleClimateActive", t.vehicleClimateActive)
            put("vehicleTargetClimateTempC", t.vehicleTargetClimateTempC.toDouble())
            put("vehicleOdometerMiles", t.vehicleOdometerMiles)
            put("vehicleLocationText", t.vehicleLocationText)
            put("vehicleVin", cleanVehicleVin)
            put("vehicleRegPlate", t.vehicleRegPlate)
            put("vehicleTaxStatus", t.vehicleTaxStatus)
            put("vehicleTaxDueDateIso", t.vehicleTaxDueDateIso)
            put("vehicleMotStatus", t.vehicleMotStatus)
            put("vehicleMotExpiryDateIso", t.vehicleMotExpiryDateIso)
            put("vehicleTpmsFrontLeftPsi", t.vehicleTpmsFrontLeftPsi.toDouble())
            put("vehicleTpmsFrontRightPsi", t.vehicleTpmsFrontRightPsi.toDouble())
            put("vehicleTpmsRearLeftPsi", t.vehicleTpmsRearLeftPsi.toDouble())
            put("vehicleTpmsRearRightPsi", t.vehicleTpmsRearRightPsi.toDouble())
            put("vehicle12vBatteryVolts", t.vehicle12vBatteryVolts.toDouble())
            put("vehicleDoorsWindowsAllClosed", t.vehicleDoorsWindowsAllClosed)
            put("vehicleNextServiceMiles", t.vehicleNextServiceMiles)
            put("lytmiHdmiInput", t.lytmiHdmiInput)
            put("lytmiSyncMode", t.lytmiSyncMode)
            put("lytmiSceneName", t.lytmiSceneName)
            put("lytmiDiffusionSpeed", t.lytmiDiffusionSpeed.toDouble())
            put("lytmiColorArgb", t.lytmiColorArgb)
            put("fanSpeedLevel", t.fanSpeedLevel.coerceIn(1, 6))
            put("fanMode", t.fanMode.ifBlank { "NORMAL" })
            put("fanOscillating", t.fanOscillating)
            put("fanIonizerActive", t.fanIonizerActive)
            put("fanTimerHours", t.fanTimerHours.coerceIn(0, 12))
            put("securityMode", t.securityMode.name)
            put("lastCameraAiEvent", t.lastCameraAiEvent.name)
            put("lastCameraEventTimestampMs", t.lastCameraEventTimestampMs)
            put("batteryPercent", t.batteryPercent)
            put("transactionState", t.transactionState.name)
            put("lastUserActuationEpochMs", t.lastUserActuationEpochMs)
            put("lastConfirmedEpochMs", t.lastConfirmedEpochMs)
            if (t.deviceClassOverride != null) {
                put("deviceClassOverride", t.deviceClassOverride.name)
            }
            if (t.deviceSubtypeOverride != null) {
                put("deviceSubtypeOverride", t.deviceSubtypeOverride.name)
            }
            if (t.serviceClassOverride != null) {
                put("serviceClassOverride", t.serviceClassOverride.name)
            }
            put("classificationConfidence", t.classificationConfidence.toDouble())
            put("classificationSource", t.classificationSource.name)
            put("classificationVersion", t.classificationVersion)
            put("classificationLastEvaluatedEpochMs", t.classificationLastEvaluatedEpochMs)
            put("lifecycleState", t.lifecycleState.name)
            put("capabilitySchemaHash", t.capabilitySchemaHash.ifBlank { CapabilitySchemaHasher.computeHash(t.capabilities) })
            put("stateVersion", t.stateVersion)
            put("stateOriginEpochMs", t.stateOriginEpochMs)
            put("stateAuthorityTier", t.stateAuthorityTier.name)
            put("manualHoldUntilEpochMs", t.manualHoldUntilEpochMs)
            put("lastIntentAuthorityTier", t.lastIntentAuthorityTier.name)
            put("lastCausalActor", t.lastCausalActor)
            put("activeProviderId", t.activeProviderId)
            val fbArr = JSONArray()
            t.fallbackProviderIds.forEach { if (it.isNotBlank()) fbArr.put(it) }
            put("fallbackProviderIds", fbArr)
            put("parentNetworkNodeId", t.parentNetworkNodeId)
            put("macAddress", CrossEcosystemIdentityCorrelator.normalizeMac(t.macAddress))
            put("vendorId", t.vendorId)
            put("productId", t.productId)
            put("canonicalGuid", t.canonicalGuid.ifBlank { t.resolvedCanonicalGuid() })
            put("subEndpointIndex", t.subEndpointIndex)
        }
    }

    fun saveTiles(context: Context, tiles: List<SmartTile>) {
        ConnectedIdentityIndex.rebuildFromTiles(tiles)
        val appCtx = context.applicationContext
        val snapshotCopy = tiles.toList()
        cachedTilesSnapshot = snapshotCopy
        tilesFlow.value = snapshotCopy

        tilesSaveJob?.cancel()
        tilesSaveJob = repoScope.launch {
            delay(80L)
            val latestSnapshot = cachedTilesSnapshot ?: snapshotCopy
            val activeIds = HashSet<String>(latestSnapshot.size)
            val array = JSONArray()
            val objectList = ArrayList<JSONObject>(latestSnapshot.size)
            for (t in latestSnapshot) {
                activeIds.add(t.id)
                val cachedPair = cachedTileJsonById[t.id]
                val obj = if (cachedPair != null && cachedPair.first == t) {
                    cachedPair.second
                } else {
                    serializeSingleTileToJson(t).also { serialized ->
                        cachedTileJsonById[t.id] = t to serialized
                    }
                }
                array.put(obj)
                objectList.add(obj)
            }
            cachedTileJsonById.keys.retainAll(activeIds)
            ClaraDeviceRegistryStore.getInstance(appCtx)
                .persistTileJsonObjectsTransactional(objectList, reason = "save_tiles")
            runCatching {
                val roomDb = com.jarvis.smarthomelauncher.db.ClaraRoomDatabase.getInstance(appCtx)
                val entities = latestSnapshot.map { it.toEntity() }
                roomDb.smartTileDao().insertOrUpdateTiles(entities)
            }
        }
        scheduleAsyncGraphRebuild(context = appCtx, tiles = snapshotCopy)
    }

    fun loadTiles(context: Context): List<SmartTile> {
        cachedTilesSnapshot?.let { return it }
        val registryStore = ClaraDeviceRegistryStore.getInstance(context)
        val healthyObjects = registryStore.loadHealthyTileJsonObjects()
        if (healthyObjects.isEmpty()) {
            val fallbackTiles = if (registryStore.isHouseholdInitialized()) {
                emptyList()
            } else {
                defaultStarterTiles()
            }
            cachedTilesSnapshot = fallbackTiles
            tilesFlow.value = fallbackTiles
            ConnectedIdentityIndex.rebuildFromTiles(fallbackTiles)
            scheduleAsyncGraphRebuild(context = context, tiles = fallbackTiles)
            return fallbackTiles
        }
        val list = mutableListOf<SmartTile>()
        for (o in healthyObjects) {
            try {
                val parsedType = runCatching {
                    TileType.valueOf(o.optString("type", TileType.IMOU_CAMERA.name))
                }.getOrDefault(TileType.SMARTLIFE_DEVICE)
                val isTallLoaded = o.optBoolean("isTall", false)
                val rowSpanLoaded = o.optInt("rowSpan", if (isTallLoaded) 2 else 1).coerceIn(1, 4)

                val parsedSlideshowSource = runCatching {
                    SlideshowSourceType.valueOf(
                        o.optString("slideshowSourceType", SlideshowSourceType.LOCAL_DEVICE.name)
                    )
                }.getOrDefault(SlideshowSourceType.LOCAL_DEVICE)

                val loadedLocalUris = mutableListOf<String>()
                val urisArr = o.optJSONArray("slideshowLocalUris")
                if (urisArr != null) {
                    for (uIdx in 0 until urisArr.length()) {
                        urisArr.optString(uIdx, "").takeIf { it.isNotBlank() }?.let { loadedLocalUris.add(it) }
                    }
                }
                val loadedSelectedFolders = mutableListOf<String>()
                val foldersArr = o.optJSONArray("slideshowSelectedFolders")
                if (foldersArr != null) {
                    for (fIdx in 0 until foldersArr.length()) {
                        foldersArr.optString(fIdx, "").takeIf { it.isNotBlank() }?.let { loadedSelectedFolders.add(it) }
                    }
                }

                val loadedCapabilities = CapabilitySnapshotCodec.decodeDescriptors(o.optJSONArray("capabilities")?.toString() ?: "")

                val loadedVolume = o.optDouble("volume", 60.0).toFloat()
                val loadedHvacStatus = o.optString("hvacStatus", "OFF")
                val loadedSocket2 = o.optBoolean("isSocket2Active", false)
                val loadedGangCount = o.optInt("multiGangSocketCount", 1).coerceAtLeast(1)
                val loadedSocket1Name = o.optString("socket1Name", "Socket 1").ifBlank { "Socket 1" }
                val loadedSocket2Name = o.optString("socket2Name", "Socket 2").ifBlank { "Socket 2" }
                val loadedGroupCardId = o.optString("groupCardId", "")
                val loadedGroupIconName = o.optString(
                    "groupCategoryIcon",
                    o.optString("groupIconName", GroupCardIconOption.LIGHTING.key)
                ).ifBlank { GroupCardIconOption.LIGHTING.key }
                val loadedMediaSource = o.optString(
                    "activeMediaSource",
                    if (parsedType == TileType.LG_TV && loadedHvacStatus != "OFF") loadedHvacStatus else ""
                )
                val loadedSupportsPtz = o.optBoolean(
                    "supportsPtz",
                    parsedType == TileType.IMOU_CAMERA
                )
                val parsedSecMode = runCatching {
                    SecurityCenterMode.valueOf(o.optString("securityMode", SecurityCenterMode.HOME.name))
                }.getOrDefault(SecurityCenterMode.HOME)
                val parsedAiEvent = runCatching {
                    CameraAiEventType.valueOf(o.optString("lastCameraAiEvent", CameraAiEventType.NONE.name))
                }.getOrDefault(CameraAiEventType.NONE)
                val parsedTxState = runCatching {
                    CommandTransactionState.valueOf(o.optString("transactionState", CommandTransactionState.IDLE.name))
                }.getOrDefault(CommandTransactionState.IDLE)
                val parsedClassOverride = o.optString("deviceClassOverride", "").takeIf { it.isNotBlank() }?.let { rawCls ->
                    runCatching { DeviceClass.valueOf(rawCls) }.getOrNull()
                }
                val parsedSubtypeOverride = o.optString("deviceSubtypeOverride", "").takeIf { it.isNotBlank() }?.let { rawSub ->
                    runCatching { DeviceSubtype.valueOf(rawSub) }.getOrNull()
                }
                val parsedServiceOverride = o.optString("serviceClassOverride", "").takeIf { it.isNotBlank() }?.let { rawSvc ->
                    runCatching { ServiceClass.valueOf(rawSvc) }.getOrNull()
                }
                val parsedClassificationConfidence = o.optDouble("classificationConfidence", 0.85).toFloat()
                val parsedClassificationSource = o.optString("classificationSource", "").takeIf { it.isNotBlank() }?.let { rawSrc ->
                    runCatching { ClassificationSource.valueOf(rawSrc) }.getOrNull()
                } ?: ClassificationSource.CAPABILITY_CLASSIFIER
                val parsedClassificationVersion = o.optInt("classificationVersion", 1)
                val parsedClassificationEvaluatedAt = o.optLong("classificationLastEvaluatedEpochMs", 0L)
                val parsedLifecycle = runCatching {
                    DeviceLifecycleState.valueOf(o.optString("lifecycleState", DeviceLifecycleState.ONLINE.name))
                }.getOrDefault(DeviceLifecycleState.ONLINE)
                val parsedStateAuthority = runCatching {
                    StateAuthorityTier.valueOf(o.optString("stateAuthorityTier", StateAuthorityTier.CACHED_UI_STATE.name))
                }.getOrDefault(StateAuthorityTier.CACHED_UI_STATE)
                val parsedIntentAuthority = runCatching {
                    IntentAuthorityTier.valueOf(o.optString("lastIntentAuthorityTier", IntentAuthorityTier.MANUAL_USER.name))
                }.getOrDefault(IntentAuthorityTier.MANUAL_USER)
                val loadedFallbackProviders = mutableListOf<String>()
                val fbArr = o.optJSONArray("fallbackProviderIds")
                if (fbArr != null) {
                    for (fbIdx in 0 until fbArr.length()) {
                        fbArr.optString(fbIdx, "").takeIf { it.isNotBlank() }?.let { loadedFallbackProviders.add(it) }
                    }
                }

                // Sanitize legacy synthetic dummy VINs & placeholder telemetry on Vehicle tiles
                val rawVin = o.optString("vehicleVin", "")
                val rawSn = o.optString("serialNumber", "")
                val rawEntityId = o.optString("entityId", "")
                val isVehicle = parsedType.isVehicleType()
                val wasDummyVehicle = isVehicle && (
                        rawVin.uppercase(Locale.US) in SYNTHETIC_DUMMY_VINS ||
                                rawSn.uppercase(Locale.US) in SYNTHETIC_DUMMY_VINS ||
                                rawEntityId.contains("hajaguar", ignoreCase = true) ||
                                rawEntityId.contains("4mercedes", ignoreCase = true)
                        )
                val cleanVin = if (isVehicle) sanitizeRealVin(rawVin) else rawVin
                val cleanSn = if (isVehicle) sanitizeRealVin(rawSn) else rawSn
                val cleanEntityId = if (wasDummyVehicle) "" else rawEntityId
                val loadedSoc = if (wasDummyVehicle) 0 else o.optInt("vehicleFuelOrBatteryPct", 0).coerceIn(0, 100)
                val loadedRange = if (wasDummyVehicle) 0 else o.optInt("vehicleRangeMiles", 0).coerceAtLeast(0)
                val loadedOdo = if (wasDummyVehicle) 0 else o.optInt("vehicleOdometerMiles", 0).coerceAtLeast(0)
                val loadedLoc = if (wasDummyVehicle) "" else o.optString("vehicleLocationText", "")
                val rawSensorUnit = o.optString("sensorUnit", "\u00B0C")
                val cleanSensorUnit = if (rawSensorUnit.equals("VOLUME_HERO", ignoreCase = true)) "\u00B0C" else rawSensorUnit
                val savedCapHash = o.optString("capabilitySchemaHash", "")
                val computedCapHash = CapabilitySchemaHasher.computeHash(loadedCapabilities)
                val loadedCapHash = computedCapHash.ifBlank { savedCapHash }
                val hasExplicitPower = o.optBoolean("hasExplicitPowerTelemetry", false)
                val tileIdLoaded = o.optString("id", UUID.randomUUID().toString())
                val devKey = cleanSn.ifBlank { cleanEntityId }.ifBlank { tileIdLoaded }

                val loadedTitle = o.optString("title", "Device")
                val lowerLoadedTitle = loadedTitle.trim().lowercase(Locale.US)
                val isPlugOrApplianceByTitle = lowerLoadedTitle.contains("plug") ||
                        lowerLoadedTitle.contains("socket") ||
                        lowerLoadedTitle.contains("pond") ||
                        lowerLoadedTitle.contains("pump") ||
                        lowerLoadedTitle.contains("kettle") ||
                        lowerLoadedTitle.contains("fryer") ||
                        lowerLoadedTitle.contains("coffee") ||
                        lowerLoadedTitle.contains("washer")
                val hasOnlySyntheticThermostatCaps = loadedCapabilities.isNotEmpty() &&
                        loadedCapabilities.any { it.dpId.isBlank() && it.key.lowercase(Locale.US) in setOf("hvac_mode", "target_temperature", "ambient_temperature") } &&
                        loadedCapabilities.none { it.dpId.isNotBlank() && CapabilityClassifier.inferControlRole(it) == SemanticControlRole.THERMOSTAT_TEMPERATURE_DIAL } &&
                        !lowerLoadedTitle.contains("heater") &&
                        !lowerLoadedTitle.contains("radiator") &&
                        !lowerLoadedTitle.contains("thermostat") &&
                        !lowerLoadedTitle.contains("trv")

                val shouldPurgeThermostatPoison = (isPlugOrApplianceByTitle || hasOnlySyntheticThermostatCaps) &&
                        parsedType != TileType.NEST_THERMOSTAT

                val sanitizedCapabilities = if (shouldPurgeThermostatPoison) {
                    val stripped = loadedCapabilities.filterNot { cap ->
                        cap.dpId.isBlank() && cap.key.lowercase(Locale.US) in setOf(
                            "hvac_mode", "target_temperature", "ambient_temperature"
                        )
                    }
                    stripped.ifEmpty {
                        listOf(
                            DeviceCapabilityDescriptor(
                                key = "switch_1",
                                label = "Power",
                                kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
                                isWritable = true,
                                isPinnedToHero = true,
                                currentValue = o.optBoolean("isActive", true).toString()
                            ),
                            DeviceCapabilityDescriptor(
                                key = "countdown_1",
                                label = "Countdown Timer",
                                kind = DeviceCapabilityKind.COUNTDOWN_TIMER,
                                isWritable = true,
                                isPinnedToHero = true,
                                currentValue = o.optInt("countdown1Seconds", 0).toString(),
                                minValue = 0f,
                                maxValue = 86400f,
                                stepValue = 60f,
                                unit = "s"
                            )
                        )
                    }
                } else {
                    loadedCapabilities
                }

                val sanitizedParsedType = if (shouldPurgeThermostatPoison && parsedType == TileType.TUYA_RADIATOR) {
                    TileType.SMARTKNIGHT_PLUG
                } else {
                    parsedType
                }
                val sanitizedParsedClassOverride = if (shouldPurgeThermostatPoison && parsedClassOverride == DeviceClass.CLIMATE) {
                    null
                } else {
                    parsedClassOverride
                }
                val sanitizedParsedSubtypeOverride = if (shouldPurgeThermostatPoison &&
                    parsedSubtypeOverride in setOf(DeviceSubtype.THERMOSTAT, DeviceSubtype.RADIATOR, DeviceSubtype.TRV)
                ) {
                    null
                } else {
                    parsedSubtypeOverride
                }
                val sanitizedClassificationSource = if (shouldPurgeThermostatPoison) {
                    ClassificationSource.CAPABILITY_CLASSIFIER
                } else {
                    parsedClassificationSource
                }

                val isDedicatedTvTile = sanitizedParsedType == TileType.LG_TV
                val needsAutoUpgrade = !isDedicatedTvTile &&
                        (shouldPurgeThermostatPoison || sanitizedClassificationSource != ClassificationSource.MANUAL_OVERRIDE) &&
                        (sanitizedCapabilities.isNotEmpty() || CapabilityClassifier.isClimateHeatingDevice(sanitizedCapabilities, "", loadedTitle)) &&
                        (shouldPurgeThermostatPoison ||
                                parsedClassificationVersion < CapabilityClassifier.CURRENT_VERSION ||
                                (savedCapHash.isNotBlank() && computedCapHash.isNotBlank() && savedCapHash != computedCapHash) ||
                                CapabilityClassifier.isClimateHeatingDevice(sanitizedCapabilities, "", loadedTitle))
                val upgradedResolution = if (needsAutoUpgrade) {
                    CapabilityClassifier.resolve(
                        capabilities = sanitizedCapabilities,
                        categoryCode = "",
                        multiGangSocketCount = loadedGangCount,
                        hasPowerTelemetry = hasExplicitPower,
                        schemaHash = CapabilitySchemaHasher.computeHash(sanitizedCapabilities),
                        titleHint = loadedTitle
                    ).also { res ->
                        CapabilityClassifier.recordResolution(devKey, res, context)
                    }
                } else {
                    null
                }
                val isLytmiOrNeoTile = sanitizedParsedType == TileType.LYTMI_SYNC_BOX ||
                        sanitizedParsedSubtypeOverride == DeviceSubtype.AMBIENT_SYNC ||
                        sanitizedCapabilities.any { it.key.lowercase(Locale.US) in setOf("sync_mode", "hdmi_input") }
                val effectiveTileType = if (isLytmiOrNeoTile) TileType.LYTMI_SYNC_BOX else sanitizedParsedType
                val effectiveClassOverride = when {
                    isDedicatedTvTile -> DeviceClass.MEDIA
                    isLytmiOrNeoTile -> DeviceClass.MEDIA
                    else -> upgradedResolution?.deviceClass ?: sanitizedParsedClassOverride
                }
                val effectiveSubtypeOverride = when {
                    isDedicatedTvTile -> DeviceSubtype.TV
                    isLytmiOrNeoTile -> DeviceSubtype.AMBIENT_SYNC
                    else -> upgradedResolution?.deviceSubtype ?: sanitizedParsedSubtypeOverride
                }
                val effectiveLoadedCapabilities = if (isLytmiOrNeoTile && sanitizedCapabilities.none {
                        it.key.lowercase(Locale.US) in setOf("work_mode", "sync_mode", "hdmi_input", "scene_select")
                    }
                ) {
                    TuyaApiClient.defaultLytmiCapabilities(
                        isActive = o.optBoolean("isActive", true),
                        hdmiInput = o.optInt("lytmiHdmiInput", 1).coerceIn(1, 4),
                        syncMode = o.optString("lytmiSyncMode", "VIDEO"),
                        sceneName = o.optString("lytmiSceneName", "Cyberpunk"),
                        diffusionSpeed = o.optDouble("lytmiDiffusionSpeed", 75.0).toFloat().coerceIn(0f, 100f)
                    )
                } else {
                    sanitizedCapabilities
                }

                val tileWithoutSecret = SmartTile(
                        id = tileIdLoaded,
                        title = o.optString("title", "Device"),
                        roomOrSubtitle = o.optString("roomOrSubtitle", ""),
                        type = effectiveTileType,
                        colSpan = o.optInt("colSpan", 1).coerceIn(1, 5),
                        rowSpan = rowSpanLoaded,
                        gridCol = o.optInt("gridCol", -1),
                        gridRow = o.optInt("gridRow", -1),
                        isTall = rowSpanLoaded > 1,
                        isActive = o.optBoolean("isActive", true),
                        isVisible = o.optBoolean("isVisible", true),
                        isOnline = o.optBoolean("isOnline", true),
                        temperature = o.optDouble("temperature", 20.5).toFloat(),
                        targetTemperature = o.optDouble("targetTemperature", 21.0).toFloat(),
                        humidity = o.optInt("humidity", 60),
                        hvacMode = o.optString("hvacMode", "OFF"),
                        hvacStatus = loadedHvacStatus,
                        volume = loadedVolume,
                        isSocket2Active = loadedSocket2,
                        multiGangSocketCount = loadedGangCount,
                        socket1Name = loadedSocket1Name,
                        socket2Name = loadedSocket2Name,
                        groupCardId = loadedGroupCardId,
                            groupCategoryIcon = loadedGroupIconName,
                        activeMediaSource = loadedMediaSource,
                        supportsPtz = loadedSupportsPtz,
                        powerWatts = o.optDouble("powerWatts", 0.0).toFloat(),
                        voltageVolts = o.optDouble("voltageVolts", 0.0).toFloat(),
                        currentMilliamps = o.optDouble("currentMilliamps", 0.0).toFloat(),
                        totalEnergyKwh = o.optDouble("totalEnergyKwh", 0.0).toFloat(),
                        countdown1Seconds = o.optInt("countdown1Seconds", 0),
                        countdown2Seconds = o.optInt("countdown2Seconds", 0),
                        isLiveFeedActive = false,
                        ipAddress = o.optString("ipAddress", ""),
                        secretCode = "",
                        serialNumber = cleanSn,
                        channelId = o.optString("channelId", "0"),
                        useSubStream = o.optBoolean("useSubStream", false),
                        customRtspUrl = o.optString("customRtspUrl", ""),
                        entityId = cleanEntityId,
                        companionAppPackage = o.optString("companionAppPackage", ""),
                        sensorUnit = cleanSensorUnit,
                        forceVolumeHeroMode = false,
                        slideshowSourceType = parsedSlideshowSource,
                        slideshowCloudUrl = o.optString("slideshowCloudUrl", ""),
                        slideshowLocalUris = loadedLocalUris,
                        slideshowIntervalSeconds = o.optInt("slideshowIntervalSeconds", 10).coerceAtLeast(3),
                        slideshowIncludeSubfolders = o.optBoolean("slideshowIncludeSubfolders", true),
                        slideshowSelectedFolders = loadedSelectedFolders,
                        capabilities = effectiveLoadedCapabilities,
                        hasExplicitPowerTelemetry = hasExplicitPower,
                        detectedApplianceCategory = runCatching {
                            ApplianceFingerprintCategory.valueOf(
                                o.optString("detectedApplianceCategory", ApplianceFingerprintCategory.UNKNOWN.name)
                            )
                        }.getOrDefault(ApplianceFingerprintCategory.UNKNOWN),
                        vehicleLocked = o.optBoolean("vehicleLocked", true),
                        vehicleFuelOrBatteryPct = loadedSoc,
                        vehicleRangeMiles = loadedRange,
                        vehicleIsEv = o.optBoolean("vehicleIsEv", parsedType == TileType.JAGUAR_VEHICLE),
                        vehicleIsCharging = o.optBoolean("vehicleIsCharging", false),
                        vehicleClimateActive = o.optBoolean("vehicleClimateActive", false),
                        vehicleTargetClimateTempC = o.optDouble("vehicleTargetClimateTempC", 21.0).toFloat(),
                        vehicleOdometerMiles = loadedOdo,
                        vehicleLocationText = loadedLoc,
                        vehicleVin = cleanVin,
                        vehicleRegPlate = o.optString("vehicleRegPlate", ""),
                        vehicleTaxStatus = o.optString("vehicleTaxStatus", "Not Configured"),
                        vehicleTaxDueDateIso = o.optString("vehicleTaxDueDateIso", ""),
                        vehicleMotStatus = o.optString("vehicleMotStatus", "Not Configured"),
                        vehicleMotExpiryDateIso = o.optString("vehicleMotExpiryDateIso", ""),
                        vehicleTpmsFrontLeftPsi = if (wasDummyVehicle) 0f else o.optDouble("vehicleTpmsFrontLeftPsi", 0.0).toFloat(),
                        vehicleTpmsFrontRightPsi = if (wasDummyVehicle) 0f else o.optDouble("vehicleTpmsFrontRightPsi", 0.0).toFloat(),
                        vehicleTpmsRearLeftPsi = if (wasDummyVehicle) 0f else o.optDouble("vehicleTpmsRearLeftPsi", 0.0).toFloat(),
                        vehicleTpmsRearRightPsi = if (wasDummyVehicle) 0f else o.optDouble("vehicleTpmsRearRightPsi", 0.0).toFloat(),
                        vehicle12vBatteryVolts = if (wasDummyVehicle) 0f else o.optDouble("vehicle12vBatteryVolts", 0.0).toFloat(),
                        vehicleDoorsWindowsAllClosed = o.optBoolean("vehicleDoorsWindowsAllClosed", true),
                        vehicleNextServiceMiles = if (wasDummyVehicle) 0 else o.optInt("vehicleNextServiceMiles", 0),
                        lytmiHdmiInput = o.optInt("lytmiHdmiInput", 1).coerceIn(1, 4),
                        lytmiSyncMode = o.optString("lytmiSyncMode", "VIDEO"),
                        lytmiSceneName = o.optString("lytmiSceneName", "Cyberpunk"),
                        lytmiDiffusionSpeed = o.optDouble("lytmiDiffusionSpeed", 75.0).toFloat().coerceIn(0f, 100f),
                        lytmiColorArgb = o.optLong("lytmiColorArgb", 0xFF00E5FFL),
                        fanSpeedLevel = o.optInt("fanSpeedLevel", 3).coerceIn(1, 6),
                        fanMode = o.optString("fanMode", "NORMAL").ifBlank { "NORMAL" },
                        fanOscillating = o.optBoolean("fanOscillating", false),
                        fanIonizerActive = o.optBoolean("fanIonizerActive", false),
                        fanTimerHours = o.optInt("fanTimerHours", 0).coerceIn(0, 12),
                        securityMode = parsedSecMode,
                        lastCameraAiEvent = parsedAiEvent,
                        lastCameraEventTimestampMs = o.optLong("lastCameraEventTimestampMs", 0L),
                        batteryPercent = o.optInt("batteryPercent", 100).coerceIn(0, 100),
                        transactionState = if (parsedTxState == CommandTransactionState.PENDING_ACK) CommandTransactionState.IDLE else parsedTxState,
                        lastUserActuationEpochMs = o.optLong("lastUserActuationEpochMs", 0L),
                        lastConfirmedEpochMs = o.optLong("lastConfirmedEpochMs", 0L),
                        deviceClassOverride = effectiveClassOverride,
                        deviceSubtypeOverride = effectiveSubtypeOverride,
                        serviceClassOverride = parsedServiceOverride,
                        classificationConfidence = upgradedResolution?.confidence ?: parsedClassificationConfidence,
                        classificationSource = upgradedResolution?.source ?: parsedClassificationSource,
                        classificationVersion = upgradedResolution?.version ?: parsedClassificationVersion,
                        classificationLastEvaluatedEpochMs = upgradedResolution?.evaluatedAtEpochMs ?: parsedClassificationEvaluatedAt,
                        lifecycleState = parsedLifecycle,
                        capabilitySchemaHash = loadedCapHash,
                        stateVersion = o.optLong("stateVersion", 1L),
                        stateOriginEpochMs = o.optLong("stateOriginEpochMs", System.currentTimeMillis()),
                        stateAuthorityTier = parsedStateAuthority,
                        manualHoldUntilEpochMs = o.optLong("manualHoldUntilEpochMs", 0L),
                        lastIntentAuthorityTier = parsedIntentAuthority,
                        lastCausalActor = o.optString("lastCausalActor", ""),
                        activeProviderId = o.optString("activeProviderId", ""),
                        fallbackProviderIds = loadedFallbackProviders,
                        parentNetworkNodeId = o.optString("parentNetworkNodeId", ""),
                        macAddress = CrossEcosystemIdentityCorrelator.normalizeMac(
                            o.optString("macAddress", "").ifBlank { cleanSn }
                        ),
                    vendorId = o.optString("vendorId", ""),
                    productId = o.optString("productId", ""),
                    canonicalGuid = o.optString("canonicalGuid", ""),
                    subEndpointIndex = o.optInt("subEndpointIndex", -1).takeIf { it >= 0 } ?: when {
                        o.optString("channelId", "").equals("SPLIT_SOCKET_1", ignoreCase = true) -> 1
                        o.optString("channelId", "").equals("SPLIT_SOCKET_2", ignoreCase = true) -> 2
                        o.optString("channelId", "").startsWith("SPLIT_SOCKET_", ignoreCase = true) ->
                            o.optString("channelId", "").substringAfter("SPLIT_SOCKET_").toIntOrNull() ?: 0
                        else -> 0
                    }
                )
                
                val canonGuid = tileWithoutSecret.canonicalGuid.ifBlank { tileWithoutSecret.resolvedCanonicalGuid() }
                val rawSecret = o.optString("secretCode", "")
                
                val finalSecretCode = if (rawSecret.isNotBlank()) {
                    val unwrapped = unwrapV1(rawSecret)
                    if (!unwrapped.startsWith("PIN_SHA256_V1:")) {
                        ClaraSecretVault.putString(context, "device::secret::$canonGuid", unwrapped)
                    }
                    unwrapped
                } else {
                    ClaraSecretVault.getString(context, "device::secret::$canonGuid") ?: ""
                }

                list.add(tileWithoutSecret.copy(secretCode = finalSecretCode))
            } catch (tileError: Exception) {
                registryStore.quarantineCorruptRecord(
                    sourceStore = "loadTiles:tileBind",
                    rawJson = o.toString(),
                    errorDetail = tileError.message ?: "Tile deserialization error"
                )
            }
        }
        val finalTiles = if (list.isNotEmpty() || registryStore.isHouseholdInitialized()) {
            list
        } else {
            defaultStarterTiles()
        }
        for (t in finalTiles) {
            if (t.isConfiguredAndLinked && t.serviceClass == null && t.deviceSubtype.isHardwareSubtype) {
                val canonGuid = DeviceLifecycleRecorder.registerTileAliases(t)
                DeviceLifecycleRecorder.seedKnownStateIfAbsent(canonGuid, t.effectiveLifecycleState.name)
                DeviceLifecycleRecorder.seedBaselineCapabilitySnapshotIfMissing(t)
            }
        }
        cachedTilesSnapshot = finalTiles
        tilesFlow.value = finalTiles
        ConnectedIdentityIndex.rebuildFromTiles(finalTiles)
        scheduleAsyncGraphRebuild(context = context, tiles = finalTiles)
        return finalTiles
    }

    // -----------------------------------------------------------------------
    // 5. DIGITAL HOME GRAPH & RAG-COMPACT LLM SERIALIZER
    // -----------------------------------------------------------------------
    fun rebuildDigitalHomeGraph(
        context: Context,
        tiles: List<SmartTile>? = null,
        bridge: BridgeSettings? = null,
        rules: List<AutomationRule>? = null
    ): DigitalHomeGraphSnapshot {
        return try {
            val effectiveBridge = bridge ?: cachedBridgeSnapshot
            ?: runCatching { loadBridgeSnapshotMinimal(context) }.getOrDefault(BridgeSettings())
            val effectiveTiles = tiles ?: cachedTilesSnapshot ?: loadTiles(context)
            val effectiveRulesCount = rules?.size ?: cachedRulesSnapshot?.size ?: runCatching {
                val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_AUTOMATIONS_JSON, null)
                if (raw.isNullOrBlank()) 0 else JSONArray(raw).length()
            }.getOrDefault(0)

            val controllableTiles = effectiveTiles.filter {
                it.type != TileType.CLOCK_WEATHER &&
                        it.type != TileType.GROUP_CARD &&
                        it.type != TileType.PHOTO_SLIDESHOW
            }

            val deviceNodes = controllableTiles.map { t ->
                val statusStr = when {
                    !t.isConfiguredAndLinked -> "Tap to Link"
                    !t.isOnline -> "Offline"
                    t.isVehicleTile -> "${if (t.vehicleLocked) "Locked" else "Unlocked"} \u2022 ${t.vehicleFuelOrBatteryPct}% (${t.vehicleRangeMiles} mi)"
                    t.isAmbientRgbTile -> "${if (t.isActive) "ON" else "OFF"} \u2022 ${t.lytmiSyncMode}"
                    t.isTowerFanTile -> if (t.isActive) "Speed ${t.fanSpeedLevel} \u2022 ${t.fanMode}${if (t.fanOscillating) " \u2022 Oscillating" else ""}" else "OFF"
                    t.isThermostatTile -> "${"%.1f".format(t.temperature)}\u00B0C \u2192 ${"%.1f".format(t.targetTemperature)}\u00B0C (${t.hvacMode})"
                    t.isMultiGangSwitch -> "${t.socket1Name}: ${if (t.isActive) "ON" else "OFF"}, ${t.socket2Name}: ${if (t.isSocket2Active) "ON" else "OFF"}"
                    t.isSwitchTile -> "${if (t.isActive) "ON" else "OFF"}${if (t.hasPowerTelemetryCapability && t.powerWatts > 0f) " (${"%.1f".format(t.powerWatts)}W)" else ""}"
                    t.isSpeakerTile -> "Volume ${t.volume.toInt()}%"
                    t.isCameraTile -> if (t.lastCameraAiEvent != CameraAiEventType.NONE) t.lastCameraAiEvent.label else "Armed"
                    else -> if (t.isActive) "Active" else "Standby"
                }
                DigitalHomeGraphDeviceNode(
                    tileId = t.id,
                    title = t.title,
                    roomName = t.roomOrSubtitle.ifBlank { "General" },
                    type = t.type,
                    deviceClass = t.deviceClass,
                    deviceSubtype = t.deviceSubtype,
                    serviceClass = t.serviceClass,
                    lifecycleState = t.effectiveLifecycleState,
                    isOnline = t.isOnline && t.isConfiguredAndLinked,
                    isActive = t.isActive || t.isSocket2Active || t.isLiveFeedActive,
                    capabilities = t.capabilities,
                    capabilitySchemaHash = t.capabilitySchemaHash.ifBlank { CapabilitySchemaHasher.computeHash(t.capabilities) },
                    statusSummary = statusStr,
                    transactionState = t.transactionState,
                    stateVersion = t.stateVersion,
                    stateAuthorityTier = t.stateAuthorityTier,
                    manualHoldUntilEpochMs = t.manualHoldUntilEpochMs,
                    lastCausalActor = t.lastCausalActor,
                    activeProviderId = t.activeProviderId.ifBlank { t.type.brandName },
                    lastUpdatedEpochMs = maxOf(t.lastUserActuationEpochMs, t.lastConfirmedEpochMs)
                )
            }

            val allRoomNames = (effectiveBridge.customRooms.ifEmpty { DEFAULT_ROOMS } + controllableTiles.map { it.roomOrSubtitle })
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.equals("All Rooms", ignoreCase = true) }
                .distinctBy { it.lowercase(Locale.US) }

            val roomNodes = allRoomNames.map { rName ->
                val roomTiles = controllableTiles.filter { it.roomOrSubtitle.equals(rName, ignoreCase = true) }
                val tempTiles = roomTiles.filter { (it.isThermostatTile || it.type == TileType.SMART_SENSOR) && it.isConfiguredAndLinked && it.isOnline }
                val avgTemp = if (tempTiles.isNotEmpty()) {
                    tempTiles.map { it.temperature }.average().toFloat()
                } else null
                val roomWatts = roomTiles.sumOf { it.powerWatts.toDouble() }.toFloat()
                val activeCount = roomTiles.count { it.isActive || it.isSocket2Active || it.isLiveFeedActive }
                DigitalHomeGraphRoomNode(
                    roomName = rName,
                    deviceIds = roomTiles.map { it.id },
                    activeDeviceCount = activeCount,
                    averageTempCelsius = avgTemp,
                    totalPowerWatts = roomWatts
                )
            }

            // Phase 6.1: Build Multi-Homed CanonicalDeviceNode list (merging LAN + Bridge endpoints)
            val canonicalNodes = controllableTiles
                .groupBy { t ->
                    val cleanSn = t.serialNumber.trim().lowercase(Locale.US)
                    val cleanIp = t.ipAddress.substringBefore(":").trim()
                    when {
                        cleanSn.isNotBlank() && !cleanSn.contains(".") -> "hw_$cleanSn"
                        cleanIp.isNotBlank() -> "ip_${cleanIp}_${t.deviceClass.name}"
                        else -> "tile_${t.id}"
                    }
                }
                .map { (canonKey, group) ->
                    val primary = group.first()
                    val allProviders = (group.map { it.resolvedProviderId } +
                            group.flatMap { it.fallbackProviderIds.map { fb -> ProviderId.fromRawKey(fb) } })
                        .filter { it != ProviderId.UNKNOWN }
                        .distinct()
                    val floorName = when {
                        primary.roomOrSubtitle.contains("bedroom", true) ||
                                primary.roomOrSubtitle.contains("bathroom", true) ||
                                primary.roomOrSubtitle.contains("upstairs", true) -> "First Floor"
                        primary.roomOrSubtitle.contains("garden", true) ||
                                primary.roomOrSubtitle.contains("driveway", true) ||
                                primary.roomOrSubtitle.contains("front door", true) -> "Exterior"
                        else -> "Ground Floor"
                    }
                    CanonicalDeviceNode(
                        canonicalDeviceId = canonKey,
                        primaryTileId = primary.id,
                        title = primary.title,
                        roomName = primary.roomOrSubtitle.ifBlank { "General" },
                        floorName = floorName,
                        deviceClass = primary.deviceClass,
                        deviceSubtype = primary.deviceSubtype,
                        activeProviderId = primary.resolvedProviderId,
                        multiHomedProviderIds = allProviders,
                        lanIpAddress = group.firstNotNullOfOrNull { it.ipAddress.takeIf { ip -> ip.isNotBlank() } }.orEmpty(),
                        hardwareMacOrSerial = group.firstNotNullOfOrNull { it.serialNumber.takeIf { sn -> sn.isNotBlank() } }.orEmpty(),
                        bridgeEntityId = group.firstNotNullOfOrNull { it.entityId.takeIf { en -> en.isNotBlank() } }.orEmpty(),
                        capabilities = primary.capabilities,
                        capabilitySchemaHash = primary.capabilitySchemaHash,
                        isOnline = group.any { it.isOnline },
                        isActive = group.any { it.isActive || it.isSocket2Active }
                    )
                }

            // Phase 7.1: Build 9-Primitive Knowledge Graph (Home, Floor, Place, Person, Device, Capability, Routine, Event, TariffSlot)
            val kgNodes = mutableListOf<KnowledgeGraphNode>()
            val kgEdges = mutableListOf<KnowledgeGraphEdge>()
            val homeNodeId = "home:root"
            val homeTitle = effectiveBridge.dashboardGreetingName.ifBlank { "Clara Home" }
            kgNodes.add(
                KnowledgeGraphNode(
                    nodeId = homeNodeId,
                    primitiveType = KnowledgeGraphPrimitiveType.HOME,
                    label = homeTitle,
                    attributes = mapOf("securityMode" to effectiveBridge.securityCenterMode.name)
                )
            )

            val floors = canonicalNodes.map { it.floorName }.distinct().ifEmpty { listOf("Ground Floor", "First Floor", "Exterior") }
            for (fl in floors) {
                val floorId = "floor:${fl.lowercase(Locale.US).replace(" ", "_")}"
                kgNodes.add(KnowledgeGraphNode(nodeId = floorId, primitiveType = KnowledgeGraphPrimitiveType.FLOOR, label = fl))
                kgEdges.add(KnowledgeGraphEdge(homeNodeId, floorId, KnowledgeGraphEdgeType.CONTAINS))
            }

            for (r in roomNodes) {
                val placeId = "place:${r.roomName.lowercase(Locale.US).replace(" ", "_")}"
                val matchedFloor = when {
                    r.roomName.contains("bedroom", true) || r.roomName.contains("bathroom", true) -> "floor:first_floor"
                    r.roomName.contains("garden", true) || r.roomName.contains("driveway", true) || r.roomName.contains("front door", true) -> "floor:exterior"
                    else -> "floor:ground_floor"
                }
                kgNodes.add(
                    KnowledgeGraphNode(
                        nodeId = placeId,
                        primitiveType = KnowledgeGraphPrimitiveType.PLACE,
                        label = r.roomName,
                        attributes = mapOf(
                            "activeDevices" to r.activeDeviceCount.toString(),
                            "totalWatts" to r.totalPowerWatts.toInt().toString(),
                            "avgTempC" to (r.averageTempCelsius?.toString() ?: "")
                        )
                    )
                )
                kgEdges.add(KnowledgeGraphEdge(matchedFloor, placeId, KnowledgeGraphEdgeType.CONTAINS))
            }

            val spatialPresence = SmartHomeApiClient.cachedSpatialPresenceList
            for (prof in effectiveBridge.userProfiles) {
                val personId = "person:${prof.id}"
                val matchedPresence = spatialPresence.firstOrNull { it.displayName.contains(prof.name, true) }
                kgNodes.add(
                    KnowledgeGraphNode(
                        nodeId = personId,
                        primitiveType = KnowledgeGraphPrimitiveType.PERSON,
                        label = prof.name,
                        attributes = mapOf(
                            "role" to prof.role.name,
                            "isHome" to (matchedPresence?.isHome ?: (effectiveBridge.securityCenterMode != SecurityCenterMode.AWAY)).toString()
                        )
                    )
                )
                val occupiedRoom = matchedPresence?.currentRoomOrApName?.takeIf { it.isNotBlank() }
                if (occupiedRoom != null) {
                    val placeId = "place:${occupiedRoom.lowercase(Locale.US).replace(" ", "_")}"
                    kgEdges.add(KnowledgeGraphEdge(personId, placeId, KnowledgeGraphEdgeType.OCCUPIES))
                }
            }

            for (cn in canonicalNodes) {
                val devNodeId = "device:${cn.canonicalDeviceId}"
                val placeId = "place:${cn.roomName.lowercase(Locale.US).replace(" ", "_")}"
                kgNodes.add(
                    KnowledgeGraphNode(
                        nodeId = devNodeId,
                        primitiveType = KnowledgeGraphPrimitiveType.DEVICE,
                        label = cn.title,
                        attributes = mapOf(
                            "deviceClass" to cn.deviceClass.name,
                            "subtype" to cn.deviceSubtype.name,
                            "provider" to cn.activeProviderId.id,
                            "multiHomedCount" to cn.multiHomedProviderIds.size.toString(),
                            "isOnline" to cn.isOnline.toString(),
                            "isActive" to cn.isActive.toString()
                        )
                    )
                )
                kgEdges.add(KnowledgeGraphEdge(devNodeId, placeId, KnowledgeGraphEdgeType.LOCATED_IN))
                for (cap in cn.capabilities.take(8)) {
                    val capNodeId = "cap:${cn.canonicalDeviceId}:${cap.key.lowercase(Locale.US)}"
                    kgNodes.add(
                        KnowledgeGraphNode(
                            nodeId = capNodeId,
                            primitiveType = KnowledgeGraphPrimitiveType.CAPABILITY,
                            label = "${cn.title} • ${cap.label}",
                            attributes = mapOf("key" to cap.key, "value" to cap.currentValue, "unit" to cap.unit)
                        )
                    )
                    kgEdges.add(KnowledgeGraphEdge(devNodeId, capNodeId, KnowledgeGraphEdgeType.EXPOSES))
                }
            }

            val activeRulesList = rules ?: cachedRulesSnapshot.orEmpty()
            for (rule in activeRulesList) {
                val routineId = "routine:${rule.id}"
                kgNodes.add(
                    KnowledgeGraphNode(
                        nodeId = routineId,
                        primitiveType = KnowledgeGraphPrimitiveType.ROUTINE,
                        label = rule.name,
                        attributes = mapOf("enabled" to rule.isEnabled.toString(), "trigger" to rule.triggerType.name)
                    )
                )
                for (act in rule.actions) {
                    val targetCanonical = canonicalNodes.firstOrNull { it.primaryTileId == act.targetTileId }
                    if (targetCanonical != null) {
                        kgEdges.add(KnowledgeGraphEdge(routineId, "device:${targetCanonical.canonicalDeviceId}", KnowledgeGraphEdgeType.GOVERNS))
                    }
                }
            }

            val tariffCap = SmartHomeApiClient.cachedOctopusTariffCapability
            if (tariffCap?.nextLowestSlot != null) {
                val slot = tariffCap.nextLowestSlot
                val slotNodeId = "tariff:next_lowest"
                kgNodes.add(
                    KnowledgeGraphNode(
                        nodeId = slotNodeId,
                        primitiveType = KnowledgeGraphPrimitiveType.TARIFF_SLOT,
                        label = "Octopus Lowest Slot (${"%.1f".format(Locale.US, slot.rateIncVatPence)}p/kWh)",
                        attributes = mapOf(
                            "ratePence" to slot.rateIncVatPence.toString(),
                            "validFrom" to slot.validFromIso,
                            "isPlunge" to slot.isPlungeOrNegative.toString()
                        )
                    )
                )
                kgEdges.add(KnowledgeGraphEdge(homeNodeId, slotNodeId, KnowledgeGraphEdgeType.OPTIMIZED_BY))
            }

            // Phase 7.2: Multi-Hop Contextual Inference Engine (Person <-> Place <-> Device <-> TariffSlot)
            val inferences = mutableListOf<MultiHopContextInference>()
            val occupiedRoomNames = spatialPresence.filter { it.isHome }.map { it.currentRoomOrApName.lowercase(Locale.US) }.toSet()

            for (r in roomNodes) {
                val roomLower = r.roomName.lowercase(Locale.US)
                val isRoomVacant = (effectiveBridge.securityCenterMode == SecurityCenterMode.AWAY) ||
                        (occupiedRoomNames.isNotEmpty() && roomLower !in occupiedRoomNames)
                if (isRoomVacant && r.activeDeviceCount > 0 && r.totalPowerWatts >= 40f) {
                    val highLoadTile = controllableTiles.firstOrNull {
                        it.roomOrSubtitle.equals(r.roomName, ignoreCase = true) && it.isActive && it.powerWatts >= 40f
                    }
                    if (highLoadTile != null && !highLoadTile.isManualHoldActive) {
                        inferences.add(
                            MultiHopContextInference(
                                inferenceId = "inf_vacant_load_${r.roomName.lowercase(Locale.US)}",
                                summary = "${r.roomName} is unoccupied while '${highLoadTile.title}' is drawing ${highLoadTile.powerWatts.toInt()}W",
                                pathNodes = listOf("place:$roomLower", "device:${highLoadTile.id}"),
                                confidence = 0.91f,
                                recommendedActionTileId = highLoadTile.id,
                                recommendedActionType = AutomationActionType.TURN_OFF
                            )
                        )
                    }
                }
            }

            if (tariffCap != null && tariffCap.currentRatePence <= 9.5f) {
                val unpluggedOrPausedEv = controllableTiles.firstOrNull {
                    (it.isVehicleTile || it.deviceSubtype == DeviceSubtype.EV_CHARGER) &&
                            it.isOnline && !it.vehicleIsCharging && it.vehicleFuelOrBatteryPct in 1..85
                }
                if (unpluggedOrPausedEv != null) {
                    inferences.add(
                        MultiHopContextInference(
                            inferenceId = "inf_agile_ev_${unpluggedOrPausedEv.id}",
                            summary = "Electricity tariff is at ${"%.1f".format(Locale.US, tariffCap.currentRatePence)}p/kWh while '${unpluggedOrPausedEv.title}' is at ${unpluggedOrPausedEv.vehicleFuelOrBatteryPct}% SOC",
                            pathNodes = listOf("tariff:next_lowest", "device:${unpluggedOrPausedEv.id}"),
                            confidence = 0.95f,
                            recommendedActionTileId = unpluggedOrPausedEv.id,
                            recommendedActionType = AutomationActionType.TURN_ON
                        )
                    )
                }
            }

            val totalLiveWatts = controllableTiles.sumOf { it.powerWatts.toDouble() }.toFloat()
            val primaryUid = JarvisLiveBridge.primaryAuthorityUidFlow.value.ifBlank {
                ClaraApplication.getOrCreateInstallationUid(context)
            }
            val isPrimary = JarvisLiveBridge.isPrimaryAutomationAuthorityFlow.value
            val snapshot = DigitalHomeGraphSnapshot(
                generatedAtEpochMs = System.currentTimeMillis(),
                graphVersion = ++graphSequenceVersion,
                primaryAuthorityInstallationUid = primaryUid,
                isPrimaryMeshAuthority = isPrimary,
                homeName = homeTitle,
                activeRole = effectiveBridge.activeUserRole,
                securityMode = effectiveBridge.securityCenterMode,
                rooms = roomNodes,
                devices = deviceNodes,
                canonicalDeviceNodes = canonicalNodes,
                knowledgeNodes = kgNodes,
                knowledgeEdges = kgEdges,
                activeInferences = inferences,
                automationCount = effectiveRulesCount,
                totalLiveWatts = totalLiveWatts,
                onlineDeviceCount = deviceNodes.count { it.isOnline },
                offlineDeviceCount = deviceNodes.count { !it.isOnline }
            )
            JarvisLiveBridge.digitalHomeGraphFlow.value = snapshot
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains(KEY_HOME_GRAPH_JSON)) {
                prefs.edit().remove(KEY_HOME_GRAPH_JSON).apply()
            }
            snapshot
        } catch (e: Exception) {
            Log.w(TAG, "rebuildDigitalHomeGraph note: ${e.message}")
            JarvisLiveBridge.digitalHomeGraphFlow.value
        }
    }

    private fun loadBridgeSnapshotMinimal(context: Context): BridgeSettings {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val role = runCatching {
            UserRole.valueOf(p.getString(KEY_ACTIVE_USER_ROLE, UserRole.OWNER.name) ?: UserRole.OWNER.name)
        }.getOrDefault(UserRole.OWNER)
        val secMode = runCatching {
            SecurityCenterMode.valueOf(p.getString(KEY_SECURITY_CENTER_MODE, SecurityCenterMode.HOME.name) ?: SecurityCenterMode.HOME.name)
        }.getOrDefault(SecurityCenterMode.HOME)
        return BridgeSettings(
            dashboardGreetingName = p.getString(KEY_DASHBOARD_GREETING_NAME, "Clara Home") ?: "Clara Home",
            activeUserRole = role,
            securityCenterMode = secMode
        )
    }

    fun serializeHomeGraphToJson(snapshot: DigitalHomeGraphSnapshot): JSONObject {
        val roomsArr = JSONArray()
        snapshot.rooms.forEach { r ->
            val devIdsArr = JSONArray()
            r.deviceIds.forEach { devIdsArr.put(it) }
            roomsArr.put(
                JSONObject().apply {
                    put("roomName", r.roomName)
                    put("deviceIds", devIdsArr)
                    put("activeDeviceCount", r.activeDeviceCount)
                    if (r.averageTempCelsius != null) put("averageTempCelsius", r.averageTempCelsius.toDouble())
                    put("totalPowerWatts", r.totalPowerWatts.toDouble())
                }
            )
        }
        val devsArr = JSONArray()
        snapshot.devices.forEach { d ->
            devsArr.put(
                JSONObject().apply {
                    put("tileId", d.tileId)
                    put("title", d.title)
                    put("roomName", d.roomName)
                    put("type", d.type.name)
                    put("deviceClass", d.deviceClass.name)
                    put("deviceSubtype", d.deviceSubtype.name)
                    if (d.serviceClass != null) {
                        put("serviceClass", d.serviceClass.name)
                    }
                    put("lifecycleState", d.lifecycleState.name)
                    put("isOnline", d.isOnline)
                    put("isActive", d.isActive)
                    put("capabilitySchemaHash", d.capabilitySchemaHash)
                    put("statusSummary", d.statusSummary)
                    put("transactionState", d.transactionState.name)
                    put("stateVersion", d.stateVersion)
                    put("stateAuthorityTier", d.stateAuthorityTier.name)
                    put("manualHoldUntilEpochMs", d.manualHoldUntilEpochMs)
                    put("lastCausalActor", d.lastCausalActor)
                    put("activeProviderId", d.activeProviderId)
                    put("lastUpdatedEpochMs", d.lastUpdatedEpochMs)
                    put("capabilities", org.json.JSONArray(CapabilitySnapshotCodec.encodeDescriptors(d.capabilities)))
                }
            )
        }
        return JSONObject().apply {
            put("generatedAtEpochMs", snapshot.generatedAtEpochMs)
            put("graphVersion", snapshot.graphVersion)
            put("primaryAuthorityInstallationUid", snapshot.primaryAuthorityInstallationUid)
            put("isPrimaryMeshAuthority", snapshot.isPrimaryMeshAuthority)
            put("homeName", snapshot.homeName)
            put("activeRole", snapshot.activeRole.name)
            put("securityMode", snapshot.securityMode.name)
            put("automationCount", snapshot.automationCount)
            put("totalLiveWatts", snapshot.totalLiveWatts.toDouble())
            put("onlineDeviceCount", snapshot.onlineDeviceCount)
            put("offlineDeviceCount", snapshot.offlineDeviceCount)
            put("rooms", roomsArr)
            put("devices", devsArr)
        }
    }

    /**
     * RAG-Compact Digital Home Graph Serializer:
     * Produces a token-efficient summary (<800 tokens even for 500–1,000 devices) by omitting
     * verbose capability schemas and prioritizing devices matching [queryHint] or active/offline states.
     */
    fun serializeCompactHomeGraphForLlm(
        snapshot: DigitalHomeGraphSnapshot,
        queryHint: String = ""
    ): String {
        val queryTokens = queryHint.lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }
            .toSet()

        val prioritizedDevices = snapshot.devices.sortedByDescending { d ->
            val text = "${d.roomName} ${d.title} ${d.deviceClass.label} ${d.type.brandName}".lowercase(Locale.US)
            val tokenHits = queryTokens.count { text.contains(it) }
            (tokenHits * 10) + (if (d.isActive) 2 else 0) + (if (!d.isOnline) 3 else 0)
        }.take(40)

        val compactDevs = JSONArray()
        prioritizedDevices.forEach { d ->
            compactDevs.put("${d.roomName} | ${d.title} [${d.deviceClass.name}]: ${d.statusSummary}")
        }
        val compactRooms = JSONArray()
        snapshot.rooms.filter { it.deviceIds.isNotEmpty() }.forEach { r ->
            val tempStr = if (r.averageTempCelsius != null) " ${"%.1f".format(Locale.US, r.averageTempCelsius)}C" else ""
            val wattStr = if (r.totalPowerWatts > 0f) " ${"%.0f".format(Locale.US, r.totalPowerWatts)}W" else ""
            compactRooms.put("${r.roomName}: ${r.activeDeviceCount}/${r.deviceIds.size} active$tempStr$wattStr")
        }
        return JSONObject().apply {
            put("home", snapshot.homeName)
            put("graphVersion", snapshot.graphVersion)
            put("securityMode", snapshot.securityMode.name)
            put("totalWatts", snapshot.totalLiveWatts.toInt())
            put("onlineCount", snapshot.onlineDeviceCount)
            put("offlineCount", snapshot.offlineDeviceCount)
            put("rooms", compactRooms)
            put("devices", compactDevs)
        }.toString()
    }

    // -----------------------------------------------------------------------
    // 6. THREAD-SAFE ON-DEVICE TIME-SERIES, AUDIT, SELF-HEALING & DVR STORE
    // -----------------------------------------------------------------------
    private const val MAX_TELEMETRY_POINTS_PER_DEVICE_METRIC = 120
    private const val MAX_TOTAL_TELEMETRY_POINTS = 2400

    fun recordTelemetryPoint(context: Context, point: TelemetryDataPoint) {
        val trimmed: List<TelemetryDataPoint>
        synchronized(telemetryLock) {
            val current = (cachedTelemetryPoints ?: loadTelemetryHistory(context)).toMutableList()
            current.add(0, point)
            val countsByBucket = HashMap<String, Int>()
            val perDeviceFiltered = ArrayList<TelemetryDataPoint>(minOf(current.size, MAX_TOTAL_TELEMETRY_POINTS))
            for (pt in current) {
                val bucketKey = "${pt.deviceId}|${pt.metricKey.lowercase(Locale.US)}"
                val count = countsByBucket.getOrDefault(bucketKey, 0)
                if (count < MAX_TELEMETRY_POINTS_PER_DEVICE_METRIC) {
                    countsByBucket[bucketKey] = count + 1
                    perDeviceFiltered.add(pt)
                    if (perDeviceFiltered.size >= MAX_TOTAL_TELEMETRY_POINTS) break
                }
            }
            trimmed = perDeviceFiltered
            cachedTelemetryPoints = trimmed
        }
        JarvisLiveBridge.telemetryHistoryFlow.value = trimmed
        val appCtx = context.applicationContext
        repoScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val db = TelemetryDatabaseHelper.getInstance(appCtx).writableDatabase
            db.beginTransaction()
            try {
                val cv = android.content.ContentValues().apply {
                    put("timestampEpochMs", point.timestampEpochMs)
                    put("deviceId", point.deviceId)
                    put("deviceTitle", point.deviceTitle)
                    put("metricKey", point.metricKey)
                    put("value", point.value)
                    put("unit", point.unit)
                }
                db.insert(TelemetryDatabaseHelper.TABLE_TELEMETRY, null, cv)
                db.execSQL(
                    "DELETE FROM ${TelemetryDatabaseHelper.TABLE_TELEMETRY} WHERE id NOT IN (SELECT id FROM ${TelemetryDatabaseHelper.TABLE_TELEMETRY} ORDER BY timestampEpochMs DESC LIMIT $MAX_TOTAL_TELEMETRY_POINTS)"
                )
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to insert telemetry point", e)
            } finally {
                db.endTransaction()
            }
        }
    }

    fun loadTelemetryHistory(
        context: Context,
        metricKey: String? = null,
        deviceId: String? = null
    ): List<TelemetryDataPoint> {
        val allPoints = synchronized(telemetryLock) {
            cachedTelemetryPoints ?: run {
                val db = TelemetryDatabaseHelper.getInstance(context.applicationContext).readableDatabase
                val list = mutableListOf<TelemetryDataPoint>()
                try {
                    db.rawQuery(
                        "SELECT timestampEpochMs, deviceId, deviceTitle, metricKey, value, unit FROM ${TelemetryDatabaseHelper.TABLE_TELEMETRY} ORDER BY timestampEpochMs DESC LIMIT $MAX_TOTAL_TELEMETRY_POINTS",
                        null
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            list.add(
                                TelemetryDataPoint(
                                    timestampEpochMs = cursor.getLong(0),
                                    deviceId = cursor.getString(1),
                                    deviceTitle = cursor.getString(2),
                                    metricKey = cursor.getString(3),
                                    value = cursor.getFloat(4),
                                    unit = cursor.getString(5)
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to load telemetry history", e)
                }
                val parsed = list.toList()
                cachedTelemetryPoints = parsed
                parsed
            }
        }
        JarvisLiveBridge.telemetryHistoryFlow.value = allPoints
        if (metricKey == null && deviceId == null) return allPoints
        return allPoints.filter { pt ->
            (metricKey == null || pt.metricKey.equals(metricKey, true)) &&
                    (deviceId == null || pt.deviceId == deviceId)
        }
    }

    fun logAuditEvent(
        context: Context,
        actorRole: UserRole = UserRole.OWNER,
        actionType: String,
        targetName: String,
        detail: String,
        succeeded: Boolean = true
    ) {
        val entry = SecurityAuditLogEntry(
            actorRole = actorRole,
            actionType = actionType,
            targetName = targetName,
            detail = detail,
            succeeded = succeeded
        )
        val trimmed: List<SecurityAuditLogEntry>
        synchronized(auditLock) {
            val current = (cachedAuditLogs ?: loadAuditLogs(context)).toMutableList()
            current.add(0, entry)
            trimmed = current.take(100)
            cachedAuditLogs = trimmed
        }
        JarvisLiveBridge.securityAuditLogsFlow.value = trimmed
        val appCtx = context.applicationContext
        repoScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val db = TelemetryDatabaseHelper.getInstance(appCtx).writableDatabase
            db.beginTransaction()
            try {
                val cv = android.content.ContentValues().apply {
                    put("id", entry.id)
                    put("timestampEpochMs", entry.timestampEpochMs)
                    put("actorRole", entry.actorRole.name)
                    put("actionType", entry.actionType)
                    put("targetName", entry.targetName)
                    put("detail", entry.detail)
                    put("succeeded", if (entry.succeeded) 1 else 0)
                }
                db.insert(TelemetryDatabaseHelper.TABLE_SECURITY_AUDIT, null, cv)
                db.execSQL(
                    "DELETE FROM ${TelemetryDatabaseHelper.TABLE_SECURITY_AUDIT} WHERE id NOT IN (SELECT id FROM ${TelemetryDatabaseHelper.TABLE_SECURITY_AUDIT} ORDER BY timestampEpochMs DESC LIMIT 100)"
                )
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to insert audit log", e)
            } finally {
                db.endTransaction()
            }
        }
    }

    fun loadAuditLogs(context: Context): List<SecurityAuditLogEntry> {
        synchronized(auditLock) {
            cachedAuditLogs?.let {
                JarvisLiveBridge.securityAuditLogsFlow.value = it
                return it
            }
            val db = TelemetryDatabaseHelper.getInstance(context.applicationContext).readableDatabase
            val list = mutableListOf<SecurityAuditLogEntry>()
            try {
                db.rawQuery(
                    "SELECT id, timestampEpochMs, actorRole, actionType, targetName, detail, succeeded FROM ${TelemetryDatabaseHelper.TABLE_SECURITY_AUDIT} ORDER BY timestampEpochMs DESC LIMIT 100",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val role = runCatching { UserRole.valueOf(cursor.getString(2)) }.getOrDefault(UserRole.OWNER)
                        list.add(
                            SecurityAuditLogEntry(
                                id = cursor.getString(0),
                                timestampEpochMs = cursor.getLong(1),
                                actorRole = role,
                                actionType = cursor.getString(3),
                                targetName = cursor.getString(4),
                                detail = cursor.getString(5),
                                succeeded = cursor.getInt(6) == 1
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to load audit logs", e)
            }
            cachedAuditLogs = list
            JarvisLiveBridge.securityAuditLogsFlow.value = list
            return list
        }
    }

    fun loadSecurityAuditLogs(context: Context): List<SecurityAuditLogEntry> =
        loadAuditLogs(context)

    fun clearAuditLogs(context: Context) {
        synchronized(auditLock) {
            cachedAuditLogs = emptyList()
        }
        val appCtx = context.applicationContext
        repoScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                TelemetryDatabaseHelper.getInstance(appCtx).writableDatabase.delete(TelemetryDatabaseHelper.TABLE_SECURITY_AUDIT, null, null)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to clear audit logs", e)
            }
        }
        JarvisLiveBridge.securityAuditLogsFlow.value = emptyList()
    }

    fun appendSelfHealingLog(context: Context, entry: SelfHealingActionLog) {
        val trimmed: List<SelfHealingActionLog>
        synchronized(selfHealLock) {
            val current = (cachedSelfHealingLogs ?: loadSelfHealingLogs(context)).toMutableList()
            current.add(0, entry)
            trimmed = current.take(80)
            cachedSelfHealingLogs = trimmed
        }
        JarvisLiveBridge.selfHealingLogsFlow.value = trimmed
        val appCtx = context.applicationContext
        repoScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val db = TelemetryDatabaseHelper.getInstance(appCtx).writableDatabase
            db.beginTransaction()
            try {
                val cv = android.content.ContentValues().apply {
                    put("id", entry.id)
                    put("timestampEpochMs", entry.timestampEpochMs)
                    put("deviceId", entry.deviceId)
                    put("deviceTitle", entry.deviceTitle)
                    put("integrationName", entry.integrationName)
                    put("stageReached", entry.stageReached)
                    put("stageLabel", entry.stageLabel)
                    put("succeeded", if (entry.succeeded) 1 else 0)
                    put("diagnosticDetail", entry.diagnosticDetail)
                }
                db.insert(TelemetryDatabaseHelper.TABLE_SELF_HEALING, null, cv)
                db.execSQL(
                    "DELETE FROM ${TelemetryDatabaseHelper.TABLE_SELF_HEALING} WHERE id NOT IN (SELECT id FROM ${TelemetryDatabaseHelper.TABLE_SELF_HEALING} ORDER BY timestampEpochMs DESC LIMIT 80)"
                )
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to insert self healing log", e)
            } finally {
                db.endTransaction()
            }
        }
    }

    fun loadSelfHealingLogs(context: Context): List<SelfHealingActionLog> {
        synchronized(selfHealLock) {
            cachedSelfHealingLogs?.let {
                JarvisLiveBridge.selfHealingLogsFlow.value = it
                return it
            }
            val db = TelemetryDatabaseHelper.getInstance(context.applicationContext).readableDatabase
            val list = mutableListOf<SelfHealingActionLog>()
            try {
                db.rawQuery(
                    "SELECT id, timestampEpochMs, deviceId, deviceTitle, integrationName, stageReached, stageLabel, succeeded, diagnosticDetail FROM ${TelemetryDatabaseHelper.TABLE_SELF_HEALING} ORDER BY timestampEpochMs DESC LIMIT 80",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        list.add(
                            SelfHealingActionLog(
                                id = cursor.getString(0),
                                timestampEpochMs = cursor.getLong(1),
                                deviceId = cursor.getString(2),
                                deviceTitle = cursor.getString(3),
                                integrationName = cursor.getString(4),
                                stageReached = cursor.getInt(5),
                                stageLabel = cursor.getString(6),
                                succeeded = cursor.getInt(7) == 1,
                                diagnosticDetail = cursor.getString(8)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to load self healing logs", e)
            }
            cachedSelfHealingLogs = list
            JarvisLiveBridge.selfHealingLogsFlow.value = list
            return list
        }
    }

    fun clearSelfHealingLogs(context: Context) {
        synchronized(selfHealLock) {
            cachedSelfHealingLogs = emptyList()
        }
        val appCtx = context.applicationContext
        repoScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                TelemetryDatabaseHelper.getInstance(appCtx).writableDatabase.delete(TelemetryDatabaseHelper.TABLE_SELF_HEALING, null, null)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to clear self healing logs", e)
            }
        }
        JarvisLiveBridge.selfHealingLogsFlow.value = emptyList()
    }

    fun appendDvrSnapshot(context: Context, snapshot: DvrEventSnapshot) {
        val trimmed: List<DvrEventSnapshot>
        synchronized(dvrLock) {
            val current = (cachedDvrSnapshots ?: loadDvrSnapshots(context)).toMutableList()
            current.add(0, snapshot)
            trimmed = current.take(50)
            cachedDvrSnapshots = trimmed
        }
        JarvisLiveBridge.dvrSnapshotsFlow.value = trimmed
    }

    fun loadDvrSnapshots(context: Context): List<DvrEventSnapshot> {
        synchronized(dvrLock) {
            val list = cachedDvrSnapshots ?: emptyList()
            cachedDvrSnapshots = list
            JarvisLiveBridge.dvrSnapshotsFlow.value = list
            return list
        }
    }

    fun clearDvrSnapshots(context: Context) {
        synchronized(dvrLock) {
            cachedDvrSnapshots = emptyList()
        }
        JarvisLiveBridge.dvrSnapshotsFlow.value = emptyList()
    }

    // -----------------------------------------------------------------------
    // 7. HOUSEHOLD SNAPSHOT ENGINE (100% STATE BACKUP & MESH REPLICATION)
    // -----------------------------------------------------------------------
    fun exportEncryptedBackupJson(context: Context, passphrase: String = ""): String {
        val appCtx = context.applicationContext
        val p = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tilesObjList = ClaraDeviceRegistryStore.getInstance(appCtx).loadHealthyTileJsonObjects()
        val tilesArr = JSONArray()
        for (obj in tilesObjList) {
            tilesArr.put(obj)
        }
        val tilesRaw = tilesArr.toString()
        val automationsRaw = p.getString(KEY_AUTOMATIONS_JSON, "[]") ?: "[]"
        val castGroupsRaw = p.getString(KEY_CAST_GROUPS_JSON, "[]") ?: "[]"
        val unifiedCardsRaw = DashboardLayoutStore.getUnifiedCardsJson(appCtx)

        val roomPhotosObj = JSONObject()
        for ((k, v) in DashboardLayoutStore.getAllRoomPhotos(appCtx)) {
            roomPhotosObj.put(k, v)
        }

        val vaultSecretsObj = JSONObject()
        val vault = ClaraSecretVault.getVault(appCtx)
        for ((k, v) in vault.all) {
            if (v is String) vaultSecretsObj.put(k, v)
        }

        val entertainmentFile = java.io.File(appCtx.filesDir, "clara_entertainment_store_v1.json")
        val entertainmentObj = if (entertainmentFile.exists()) {
            runCatching { JSONObject(entertainmentFile.readText()) }.getOrNull()
        } else null

        val bridge = loadBridge(appCtx)

        val customRoomsArr = JSONArray()
        bridge.customRooms.forEach { if (it.isNotBlank()) customRoomsArr.put(it) }

        val cardOrderArr = JSONArray()
        bridge.dashboardCardOrder.forEach { cardOrderArr.put(it) }

        val urisJsonArr = JSONArray()
        bridge.slideshowLocalUris.forEach { urisJsonArr.put(it) }

        val bridgeObj = JSONObject().apply {
            put("haUrl", bridge.haUrl)
            put("haToken", bridge.haToken)
            put("imouAppId", bridge.imouAppId)
            put("imouAppSecret", bridge.imouAppSecret)
            put("imouApiHost", bridge.imouApiHost)
            put("ezvizAppKey", bridge.ezvizAppKey)
            put("ezvizAppSecret", bridge.ezvizAppSecret)
            put("ezvizApiHost", bridge.ezvizApiHost)
            put("nestProjectId", bridge.nestProjectId)
            put("nestClientId", bridge.nestClientId)
            put("nestClientSecret", bridge.nestClientSecret)
            put("nestRefreshToken", bridge.nestRefreshToken)
            put("nestPubSubSubscription", bridge.nestPubSubSubscription)
            put("tuyaAccessId", bridge.tuyaAccessId)
            put("tuyaAccessSecret", bridge.tuyaAccessSecret)
            put("tuyaUid", bridge.tuyaUid)
            put("tuyaApiHost", bridge.tuyaApiHost)
            put("tplinkEmail", bridge.tplinkEmail)
            put("tplinkPassword", bridge.tplinkPassword)
            put("ewelinkEmail", bridge.ewelinkEmail)
            put("ewelinkPassword", bridge.ewelinkPassword)
            put("mqttBrokerHost", bridge.mqttBrokerHost)
            put("mqttBrokerPort", bridge.mqttBrokerPort)
            put("mqttUsername", bridge.mqttUsername)
            put("mqttPassword", bridge.mqttPassword)
            put("jaguarEmail", bridge.jaguarEmail)
            put("jaguarPassword", bridge.jaguarPassword)
            put("jaguarPin", bridge.jaguarPin)
            put("jaguarVin", bridge.jaguarVin)
            put("mercedesEmail", bridge.mercedesEmail)
            put("mercedesPassword", bridge.mercedesPassword)
            put("mercedesPin", bridge.mercedesPin)
            put("mercedesVin", bridge.mercedesVin)
            put("lytmiIpAddress", bridge.lytmiIpAddress)
            put("lytmiDeviceId", bridge.lytmiDeviceId)
            put("lytmiLocalKey", bridge.lytmiLocalKey)
            put("goveeApiKey", bridge.goveeApiKey)
            put("goveeIpAddress", bridge.goveeIpAddress)
            put("twinklyIpAddress", bridge.twinklyIpAddress)
            put("plexServerUrl", bridge.plexServerUrl)
            put("plexToken", bridge.plexToken)
            put("musicProvider", bridge.musicProvider.name)
            put("appThemeMode", bridge.appThemeMode.name)
            put("accentColorPreset", bridge.accentColorPreset.name)
            put("customAccentColorArgb", bridge.customAccentColorArgb)
            put("clockFaceStyle", bridge.clockFaceStyle.name)
            put("heroCardTimeoutSeconds", bridge.heroCardTimeoutSeconds)
            put("keepScreenAwake", bridge.keepScreenAwake)
            put("sleepTimeoutSeconds", bridge.sleepTimeoutSeconds)
            put("enablePresenceWake", bridge.enablePresenceWake)
            put("enableVoiceAnnouncements", bridge.enableVoiceAnnouncements)
            put("cloudStreamAutoPauseSeconds", bridge.cloudStreamAutoPauseSeconds)
            put("showRoomLabelsOnTiles", bridge.showRoomLabelsOnTiles)
            put("showTemperatureOnTiles", bridge.showTemperatureOnTiles)
            put("enableGroupCards", bridge.enableGroupCards)
            put("customRooms", customRoomsArr)
        }

        val bundleJson = JSONObject().apply {
            put("version", 5)
            put("exportedAtEpochMs", System.currentTimeMillis())
            put("tiles", runCatching { JSONArray(tilesRaw) }.getOrDefault(JSONArray()))
            put("automations", runCatching { JSONArray(automationsRaw) }.getOrDefault(JSONArray()))
            put("castGroups", runCatching { JSONArray(castGroupsRaw) }.getOrDefault(JSONArray()))
            put("unifiedGridCards", runCatching { JSONArray(unifiedCardsRaw) }.getOrDefault(JSONArray()))
            put("roomPhotoUris", roomPhotosObj)
            put("vaultSecrets", vaultSecretsObj)
            if (entertainmentObj != null) put("entertainmentStore", entertainmentObj)
            put("bridge", bridgeObj)
        }.toString()

        return try {
            val effectiveKey = passphrase.trim().ifBlank {
                ClaraApplication.getOrCreateInstallationSecret(appCtx)
            }
            val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = javax.crypto.spec.PBEKeySpec(effectiveKey.toCharArray(), salt, 600000, 256)
            val keyBytes = factory.generateSecret(spec).encoded
            val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(keyBytes, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(bundleJson.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(salt.size + iv.size + encrypted.size)
            System.arraycopy(salt, 0, combined, 0, salt.size)
            System.arraycopy(iv, 0, combined, salt.size, iv.size)
            System.arraycopy(encrypted, 0, combined, salt.size + iv.size, encrypted.size)
            logAuditEvent(
                context = appCtx,
                actorRole = bridge.activeUserRole,
                actionType = "EXPORT_SNAPSHOT",
                targetName = "Household Snapshot Engine",
                detail = "Exported full encrypted household snapshot (tiles, unified grid cards, room photos, radio, automations & credentials)",
                succeeded = true
            )
            "CLARA_BACKUP_V2:" + android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "exportEncryptedBackupJson failed closed: ${e.message}", e)
            throw java.security.GeneralSecurityException("Failed to encrypt household backup snapshot; refusing plaintext export.", e)
        }
    }

    fun importEncryptedBackupJson(
        context: Context,
        rawBackupPayload: String,
        passphrase: String = ""
    ): Result<Int> {
        val appCtx = context.applicationContext
        val cleanPayload = rawBackupPayload.trim()
        if (cleanPayload.isBlank()) {
            return Result.failure(Exception("Backup payload is empty."))
        }
        val isV2 = cleanPayload.startsWith("CLARA_BACKUP_V2:")
        val isV1 = cleanPayload.startsWith(BACKUP_ENC_PREFIX)
        if (!isV2 && !isV1) {
            return Result.failure(java.security.GeneralSecurityException("Rejected unencrypted backup payload; encrypted snapshot required."))
        }
        return try {
            val plainJsonStr = run {
                val effectiveKey = passphrase.trim().ifBlank {
                    ClaraApplication.getOrCreateInstallationSecret(appCtx)
                }
                if (isV2) {
                    val combined = android.util.Base64.decode(cleanPayload.removePrefix("CLARA_BACKUP_V2:"), android.util.Base64.NO_WRAP)
                    val salt = combined.copyOfRange(0, 16)
                    val iv = combined.copyOfRange(16, 28)
                    val cipherBytes = combined.copyOfRange(28, combined.size)
                    val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    val spec = javax.crypto.spec.PBEKeySpec(effectiveKey.toCharArray(), salt, 600000, 256)
                    val keyBytes = factory.generateSecret(spec).encoded
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(keyBytes, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
                    String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
                } else {
                    val keyBytes = java.security.MessageDigest.getInstance("SHA-256").digest(effectiveKey.toByteArray(Charsets.UTF_8))
                    val combined = android.util.Base64.decode(cleanPayload.removePrefix(BACKUP_ENC_PREFIX), android.util.Base64.NO_WRAP)
                    val iv = combined.copyOfRange(0, 12)
                    val cipherBytes = combined.copyOfRange(12, combined.size)
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(keyBytes, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
                    String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
                }
            }
            val root = JSONObject(plainJsonStr)
            val p = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val tilesArr = root.optJSONArray("tiles")
            val automationsArr = root.optJSONArray("automations")
            val castGroupsArr = root.optJSONArray("castGroups")
            val unifiedCardsArr = root.optJSONArray("unifiedGridCards")
            val roomPhotosObj = root.optJSONObject("roomPhotoUris")
            val vaultSecretsObj = root.optJSONObject("vaultSecrets")
            val entertainmentObj = root.optJSONObject("entertainmentStore")
            val entertainmentLegacyArr = root.optJSONArray("entertainmentStore")
            val bridgeObj = root.optJSONObject("bridge")

            p.edit().apply {
                if (automationsArr != null) putString(KEY_AUTOMATIONS_JSON, automationsArr.toString())
                if (castGroupsArr != null) putString(KEY_CAST_GROUPS_JSON, castGroupsArr.toString())
                putBoolean(KEY_SEEDED_DEFAULT_RADIO_V1, true)
                apply()
            }
            if (tilesArr != null) {
                val objList = mutableListOf<JSONObject>()
                for (i in 0 until tilesArr.length()) {
                    objList.add(tilesArr.getJSONObject(i))
                }
                ClaraDeviceRegistryStore.getInstance(appCtx)
                    .persistTileJsonObjectsTransactional(objList, reason = "restore_backup")
            }

            if (unifiedCardsArr != null) {
                DashboardLayoutStore.setUnifiedCardsJson(appCtx, unifiedCardsArr.toString())
            }

            if (roomPhotosObj != null) {
                val photosMap = mutableMapOf<String, String>()
                val keys = roomPhotosObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = roomPhotosObj.optString(k, "")
                    if (v.isNotBlank()) photosMap[k] = v
                }
                DashboardLayoutStore.setAllRoomPhotos(appCtx, photosMap)
            }

            if (vaultSecretsObj != null) {
                val keys = vaultSecretsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = vaultSecretsObj.optString(k, "")
                    if (v.isNotBlank()) ClaraSecretVault.putString(appCtx, k, v)
                }
            }

            val resolvedEntObj = entertainmentObj ?: entertainmentLegacyArr?.let { arr ->
                JSONObject().apply {
                    put("version", 1)
                    put("updatedAtEpochMs", System.currentTimeMillis())
                    put("items", arr)
                }
            }
            if (resolvedEntObj != null) {
                val f = java.io.File(appCtx.filesDir, "clara_entertainment_store_v1.json")
                f.writeText(resolvedEntObj.toString())
                repoScope.launch { ClaraEntertainmentStore.reloadFromDisk(appCtx) }
            }

            cachedTilesSnapshot = null
            cachedBridgeSnapshot = null
            cachedRulesSnapshot = null

            if (bridgeObj != null) {
                val current = loadBridge(appCtx)
                val restoredRooms = mutableListOf<String>()
                val roomsArr = bridgeObj.optJSONArray("customRooms")
                if (roomsArr != null) {
                    for (i in 0 until roomsArr.length()) {
                        roomsArr.optString(i, "").trim().takeIf { it.isNotBlank() }?.let { restoredRooms.add(it) }
                    }
                }
                val restoredProfiles = bridgeObj.optJSONArray("userProfiles")?.let {
                    deserializeUserProfiles(it.toString())
                } ?: current.userProfiles
                val restoredRadio = bridgeObj.optJSONArray("customRadioStations")?.let {
                    deserializeCustomRadioStations(it.toString())
                } ?: current.customRadioStations

                val restoredCardOrder = bridgeObj.optJSONArray("dashboardCardOrder")?.let { arr ->
                    buildList {
                        for (i in 0 until arr.length()) {
                            arr.optString(i, "").takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }.ifEmpty { current.dashboardCardOrder }
                } ?: current.dashboardCardOrder

                val restoredBridge = current.copy(
                    haUrl = bridgeObj.optString("haUrl", current.haUrl),
                    haToken = bridgeObj.optString("haToken", current.haToken),
                    imouAppId = bridgeObj.optString("imouAppId", current.imouAppId),
                    imouAppSecret = bridgeObj.optString("imouAppSecret", current.imouAppSecret),
                    imouApiHost = bridgeObj.optString("imouApiHost", current.imouApiHost),
                    ezvizAppKey = bridgeObj.optString("ezvizAppKey", current.ezvizAppKey),
                    ezvizAppSecret = bridgeObj.optString("ezvizAppSecret", current.ezvizAppSecret),
                    ezvizApiHost = bridgeObj.optString("ezvizApiHost", current.ezvizApiHost),
                    nestProjectId = bridgeObj.optString("nestProjectId", current.nestProjectId),
                    nestClientId = bridgeObj.optString("nestClientId", current.nestClientId),
                    nestClientSecret = bridgeObj.optString("nestClientSecret", current.nestClientSecret),
                    nestRefreshToken = bridgeObj.optString("nestRefreshToken", current.nestRefreshToken),
                    nestPubSubSubscription = bridgeObj.optString("nestPubSubSubscription", current.nestPubSubSubscription),
                    tuyaAccessId = bridgeObj.optString("tuyaAccessId", current.tuyaAccessId),
                    tuyaAccessSecret = bridgeObj.optString("tuyaAccessSecret", current.tuyaAccessSecret),
                    tuyaUid = bridgeObj.optString("tuyaUid", current.tuyaUid),
                    tuyaApiHost = bridgeObj.optString("tuyaApiHost", current.tuyaApiHost),
                    tplinkEmail = bridgeObj.optString("tplinkEmail", current.tplinkEmail),
                    tplinkPassword = bridgeObj.optString("tplinkPassword", current.tplinkPassword),
                    ewelinkEmail = bridgeObj.optString("ewelinkEmail", current.ewelinkEmail),
                    ewelinkPassword = bridgeObj.optString("ewelinkPassword", current.ewelinkPassword),
                    mqttBrokerHost = bridgeObj.optString("mqttBrokerHost", current.mqttBrokerHost),
                    mqttBrokerPort = bridgeObj.optInt("mqttBrokerPort", current.mqttBrokerPort),
                    mqttUsername = bridgeObj.optString("mqttUsername", current.mqttUsername),
                    mqttPassword = bridgeObj.optString("mqttPassword", current.mqttPassword),
                    jaguarEmail = bridgeObj.optString("jaguarEmail", current.jaguarEmail),
                    jaguarPassword = bridgeObj.optString("jaguarPassword", current.jaguarPassword),
                    jaguarPin = bridgeObj.optString("jaguarPin", current.jaguarPin),
                    jaguarVin = sanitizeRealVin(bridgeObj.optString("jaguarVin", current.jaguarVin)),
                    mercedesEmail = bridgeObj.optString("mercedesEmail", current.mercedesEmail),
                    mercedesPassword = bridgeObj.optString("mercedesPassword", current.mercedesPassword),
                    mercedesPin = bridgeObj.optString("mercedesPin", current.mercedesPin),
                    mercedesVin = sanitizeRealVin(bridgeObj.optString("mercedesVin", current.mercedesVin)),
                    lytmiIpAddress = bridgeObj.optString("lytmiIpAddress", current.lytmiIpAddress),
                    lytmiDeviceId = bridgeObj.optString("lytmiDeviceId", current.lytmiDeviceId),
                    lytmiLocalKey = bridgeObj.optString("lytmiLocalKey", current.lytmiLocalKey),
                    goveeApiKey = bridgeObj.optString("goveeApiKey", current.goveeApiKey),
                    goveeIpAddress = bridgeObj.optString("goveeIpAddress", current.goveeIpAddress),
                    twinklyIpAddress = bridgeObj.optString("twinklyIpAddress", current.twinklyIpAddress),
                    plexServerUrl = bridgeObj.optString("plexServerUrl", current.plexServerUrl),
                    plexToken = bridgeObj.optString("plexToken", current.plexToken),
                    musicProvider = runCatching { MusicProvider.valueOf(bridgeObj.optString("musicProvider", current.musicProvider.name)) }.getOrDefault(current.musicProvider),
                    appThemeMode = runCatching { AppThemeMode.valueOf(bridgeObj.optString("appThemeMode", current.appThemeMode.name)) }.getOrDefault(current.appThemeMode),
                    accentColorPreset = runCatching { AppAccentColorPreset.valueOf(bridgeObj.optString("accentColorPreset", current.accentColorPreset.name)) }.getOrDefault(current.accentColorPreset),
                    customAccentColorArgb = bridgeObj.optLong("customAccentColorArgb", current.customAccentColorArgb),
                    clockFaceStyle = runCatching { ClockFaceStyle.valueOf(bridgeObj.optString("clockFaceStyle", current.clockFaceStyle.name)) }.getOrDefault(current.clockFaceStyle),
                    heroCardTimeoutSeconds = bridgeObj.optInt("heroCardTimeoutSeconds", current.heroCardTimeoutSeconds),
                    keepScreenAwake = bridgeObj.optBoolean("keepScreenAwake", current.keepScreenAwake),
                    sleepTimeoutSeconds = bridgeObj.optInt("sleepTimeoutSeconds", current.sleepTimeoutSeconds),
                    enablePresenceWake = bridgeObj.optBoolean("enablePresenceWake", current.enablePresenceWake),
                    enableVoiceAnnouncements = bridgeObj.optBoolean("enableVoiceAnnouncements", current.enableVoiceAnnouncements),
                    cloudStreamAutoPauseSeconds = bridgeObj.optInt("cloudStreamAutoPauseSeconds", current.cloudStreamAutoPauseSeconds),
                    showRoomLabelsOnTiles = bridgeObj.optBoolean("showRoomLabelsOnTiles", current.showRoomLabelsOnTiles),
                    showTemperatureOnTiles = bridgeObj.optBoolean("showTemperatureOnTiles", current.showTemperatureOnTiles),
                    enableGroupCards = bridgeObj.optBoolean("enableGroupCards", current.enableGroupCards),
                    customRooms = restoredRooms.ifEmpty { current.customRooms },
                    userProfiles = restoredProfiles,
                    customRadioStations = restoredRadio,
                    dashboardCardOrder = restoredCardOrder
                )
                saveBridge(appCtx, restoredBridge)
            }
            val restoredTiles = loadTiles(appCtx)

            logAuditEvent(
                context = appCtx,
                actionType = "IMPORT_SNAPSHOT",
                targetName = "Household Snapshot Engine",
                detail = "Restored ${restoredTiles.size} tiles, unified grid layout, room photos & settings from household snapshot",
                succeeded = true
            )
            Result.success(restoredTiles.size)
        } catch (e: Exception) {
            Result.failure(Exception("Could not decrypt or parse backup bundle: ${e.message}"))
        }
    }
    private fun serializeTargetSelector(sel: AutomationTargetSelector): JSONObject = JSONObject().apply {
        put("scope", sel.scope.name)
        put("roomFilter", sel.roomFilter)
        if (sel.deviceClass != null) put("deviceClass", sel.deviceClass.name)
        if (sel.deviceSubtype != null) put("deviceSubtype", sel.deviceSubtype.name)
        put("requiredCapabilityKey", sel.requiredCapabilityKey)
    }

    private fun deserializeTargetSelector(obj: JSONObject?): AutomationTargetSelector {
        if (obj == null) return AutomationTargetSelector()
        val scope = runCatching {
            AutomationTargetScope.valueOf(obj.optString("scope", AutomationTargetScope.SINGLE_TILE.name))
        }.getOrDefault(AutomationTargetScope.SINGLE_TILE)
        val devClass = obj.optString("deviceClass", "").takeIf { it.isNotBlank() }?.let {
            runCatching { DeviceClass.valueOf(it) }.getOrNull()
        }
        val devSub = obj.optString("deviceSubtype", "").takeIf { it.isNotBlank() }?.let {
            runCatching { DeviceSubtype.valueOf(it) }.getOrNull()
        }
        return AutomationTargetSelector(
            scope = scope,
            roomFilter = obj.optString("roomFilter", ""),
            deviceClass = devClass,
            deviceSubtype = devSub,
            requiredCapabilityKey = obj.optString("requiredCapabilityKey", "")
        )
    }

    private fun serializeSecondaryCondition(sec: AutomationSecondaryCondition): JSONObject = JSONObject().apply {
        put("id", sec.id)
        put("conditionType", sec.conditionType.name)
        put("targetTileId", sec.targetTileId)
        put("targetTileTitle", sec.targetTileTitle)
        put("targetDeviceState", sec.targetDeviceState.name)
        put("comparisonOperator", sec.comparisonOperator.name)
        put("thresholdValue", sec.thresholdValue.toDouble())
        put("weatherKeyword", sec.weatherKeyword)
        put("targetSecurityMode", sec.targetSecurityMode.name)
        put("requireOffPeakTariff", sec.requireOffPeakTariff)
        put("requireNighttime", sec.requireNighttime)
        put("capabilityKey", sec.capabilityKey)
        put("capabilityExpectedText", sec.capabilityExpectedText)
        put("targetSelector", serializeTargetSelector(sec.targetSelector))
    }

    private fun deserializeSecondaryCondition(so: JSONObject): AutomationSecondaryCondition {
        val cType = runCatching {
            AutomationTriggerType.valueOf(so.optString("conditionType", AutomationTriggerType.DEVICE_STATE_CHANGE.name))
        }.getOrDefault(AutomationTriggerType.DEVICE_STATE_CHANGE)
        val tState = runCatching {
            DeviceTriggerTargetState.valueOf(so.optString("targetDeviceState", DeviceTriggerTargetState.TURNED_ON.name))
        }.getOrDefault(DeviceTriggerTargetState.TURNED_ON)
        val cOp = runCatching {
            ComparisonOperator.valueOf(so.optString("comparisonOperator", ComparisonOperator.BELOW.name))
        }.getOrDefault(ComparisonOperator.BELOW)
        val secMode = runCatching {
            SecurityCenterMode.valueOf(so.optString("targetSecurityMode", SecurityCenterMode.AWAY.name))
        }.getOrDefault(SecurityCenterMode.AWAY)
        return AutomationSecondaryCondition(
            id = so.optString("id", UUID.randomUUID().toString()),
            conditionType = cType,
            targetTileId = so.optString("targetTileId", ""),
            targetTileTitle = so.optString("targetTileTitle", ""),
            targetDeviceState = tState,
            comparisonOperator = cOp,
            thresholdValue = so.optDouble("thresholdValue", 19.0).toFloat(),
            weatherKeyword = so.optString("weatherKeyword", "Rain"),
            targetSecurityMode = secMode,
            requireOffPeakTariff = so.optBoolean("requireOffPeakTariff", true),
            requireNighttime = so.optBoolean("requireNighttime", true),
            capabilityKey = so.optString("capabilityKey", ""),
            capabilityExpectedText = so.optString("capabilityExpectedText", ""),
            targetSelector = deserializeTargetSelector(so.optJSONObject("targetSelector"))
        )
    }

    private fun serializeAutomationAction(act: AutomationAction): JSONObject = JSONObject().apply {
        put("id", act.id)
        put("targetTileId", act.targetTileId)
        put("targetTileTitle", act.targetTileTitle)
        put("actionType", act.actionType.name)
        put("numericValue", act.numericValue.toDouble())
        put("textValue", act.textValue)
        put("targetSelector", serializeTargetSelector(act.targetSelector))
    }

    private fun deserializeAutomationAction(ao: JSONObject): AutomationAction {
        val actType = runCatching {
            AutomationActionType.valueOf(ao.optString("actionType", AutomationActionType.TURN_ON.name))
        }.getOrDefault(AutomationActionType.TURN_ON)
        return AutomationAction(
            id = ao.optString("id", UUID.randomUUID().toString()),
            targetTileId = ao.optString("targetTileId", ""),
            targetTileTitle = ao.optString("targetTileTitle", ""),
            actionType = actType,
            numericValue = ao.optDouble("numericValue", 5.0).toFloat(),
            textValue = ao.optString("textValue", "MINUTES"),
            targetSelector = deserializeTargetSelector(ao.optJSONObject("targetSelector"))
        )
    }

    fun saveAutomations(context: Context, rules: List<AutomationRule>) {
        val appCtx = context.applicationContext
        val snapshotCopy = rules.toList()
        cachedRulesSnapshot = snapshotCopy
        automationRulesFlow.value = snapshotCopy
        repoScope.launch {
            val array = JSONArray()
            for (r in snapshotCopy) {
                val daysArray = JSONArray()
                r.daysOfWeek.sorted().forEach { daysArray.put(it) }

                val secondaryArray = JSONArray()
                r.secondaryConditions.forEach { secondaryArray.put(serializeSecondaryCondition(it)) }

                val actionsArray = JSONArray()
                r.actions.forEach { actionsArray.put(serializeAutomationAction(it)) }

                val branchesArray = JSONArray()
                for (branch in r.branches) {
                    val bConds = JSONArray()
                    branch.conditions.forEach { bConds.put(serializeSecondaryCondition(it)) }
                    val bActs = JSONArray()
                    branch.actions.forEach { bActs.put(serializeAutomationAction(it)) }
                    branchesArray.put(
                        JSONObject().apply {
                            put("id", branch.id)
                            put("label", branch.label)
                            put("combinator", branch.combinator.name)
                            put("conditions", bConds)
                            put("actions", bActs)
                        }
                    )
                }

                val otherwiseArray = JSONArray()
                r.otherwiseActions.forEach { otherwiseArray.put(serializeAutomationAction(it)) }

                val obj = JSONObject().apply {
                    put("id", r.id)
                    put("name", r.name)
                    put("isEnabled", r.isEnabled)
                    put("triggerType", r.triggerType.name)
                    put("scheduleHour", r.scheduleHour)
                    put("scheduleMinute", r.scheduleMinute)
                    put("daysOfWeek", daysArray)
                    put("triggerOnSunset", r.triggerOnSunset)
                    put("triggerTileId", r.triggerTileId)
                    put("triggerTileTitle", r.triggerTileTitle)
                    put("triggerDeviceState", r.triggerDeviceState.name)
                    put("comparisonOperator", r.comparisonOperator.name)
                    put("thresholdValue", r.thresholdValue.toDouble())
                    put("weatherKeyword", r.weatherKeyword)
                    put("restrictToTimeWindow", r.restrictToTimeWindow)
                    put("windowStartHour", r.windowStartHour)
                    put("windowStartMinute", r.windowStartMinute)
                    put("windowEndHour", r.windowEndHour)
                    put("windowEndMinute", r.windowEndMinute)
                    put("combinator", r.combinator.name)
                    put("secondaryConditions", secondaryArray)
                    put("actions", actionsArray)
                    put("branches", branchesArray)
                    put("otherwiseActions", otherwiseArray)
                    put("lastTriggeredEpochMs", r.lastTriggeredEpochMs)
                }
                array.put(obj)
            }
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_AUTOMATIONS_JSON, array.toString())
                .apply()
        }
        scheduleAsyncGraphRebuild(context = appCtx, rules = snapshotCopy)
    }

    fun loadAutomations(context: Context): List<AutomationRule> {
        cachedRulesSnapshot?.let { return it }
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = p.getString(KEY_AUTOMATIONS_JSON, null)
        if (raw.isNullOrBlank()) {
            cachedRulesSnapshot = emptyList()
            return emptyList()
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<AutomationRule>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val triggerType = runCatching {
                    AutomationTriggerType.valueOf(o.optString("triggerType", AutomationTriggerType.TIME_SCHEDULE.name))
                }.getOrDefault(AutomationTriggerType.TIME_SCHEDULE)
                val triggerDeviceState = runCatching {
                    DeviceTriggerTargetState.valueOf(o.optString("triggerDeviceState", DeviceTriggerTargetState.TURNED_ON.name))
                }.getOrDefault(DeviceTriggerTargetState.TURNED_ON)
                val comparisonOperator = runCatching {
                    ComparisonOperator.valueOf(o.optString("comparisonOperator", ComparisonOperator.BELOW.name))
                }.getOrDefault(ComparisonOperator.BELOW)
                val combinator = runCatching {
                    ConditionCombinator.valueOf(o.optString("combinator", ConditionCombinator.AND.name))
                }.getOrDefault(ConditionCombinator.AND)

                val daysSet = mutableSetOf<Int>()
                val daysArr = o.optJSONArray("daysOfWeek")
                if (daysArr != null) {
                    for (dIdx in 0 until daysArr.length()) {
                        val d = daysArr.optInt(dIdx, -1)
                        if (d in 1..7) daysSet.add(d)
                    }
                }
                if (daysSet.isEmpty()) daysSet.addAll(1..7)

                val secondaryList = mutableListOf<AutomationSecondaryCondition>()
                val secArr = o.optJSONArray("secondaryConditions")
                if (secArr != null) {
                    for (sIdx in 0 until secArr.length()) {
                        secArr.optJSONObject(sIdx)?.let { secondaryList.add(deserializeSecondaryCondition(it)) }
                    }
                }

                val actionsList = mutableListOf<AutomationAction>()
                val actsArr = o.optJSONArray("actions")
                if (actsArr != null) {
                    for (aIdx in 0 until actsArr.length()) {
                        actsArr.optJSONObject(aIdx)?.let { actionsList.add(deserializeAutomationAction(it)) }
                    }
                }

                val branchesList = mutableListOf<AutomationBranch>()
                val branchesArr = o.optJSONArray("branches")
                if (branchesArr != null) {
                    for (bIdx in 0 until branchesArr.length()) {
                        val bo = branchesArr.optJSONObject(bIdx) ?: continue
                        val bComb = runCatching {
                            ConditionCombinator.valueOf(bo.optString("combinator", ConditionCombinator.AND.name))
                        }.getOrDefault(ConditionCombinator.AND)
                        val bConds = mutableListOf<AutomationSecondaryCondition>()
                        val bCondsArr = bo.optJSONArray("conditions")
                        if (bCondsArr != null) {
                            for (cIdx in 0 until bCondsArr.length()) {
                                bCondsArr.optJSONObject(cIdx)?.let { bConds.add(deserializeSecondaryCondition(it)) }
                            }
                        }
                        val bActs = mutableListOf<AutomationAction>()
                        val bActsArr = bo.optJSONArray("actions")
                        if (bActsArr != null) {
                            for (aIdx in 0 until bActsArr.length()) {
                                bActsArr.optJSONObject(aIdx)?.let { bActs.add(deserializeAutomationAction(it)) }
                            }
                        }
                        branchesList.add(
                            AutomationBranch(
                                id = bo.optString("id", UUID.randomUUID().toString()),
                                label = bo.optString("label", "Branch #${bIdx + 1}"),
                                combinator = bComb,
                                conditions = bConds,
                                actions = bActs
                            )
                        )
                    }
                }

                val otherwiseList = mutableListOf<AutomationAction>()
                val otherwiseArr = o.optJSONArray("otherwiseActions")
                if (otherwiseArr != null) {
                    for (oIdx in 0 until otherwiseArr.length()) {
                        otherwiseArr.optJSONObject(oIdx)?.let { otherwiseList.add(deserializeAutomationAction(it)) }
                    }
                }

                list.add(
                    AutomationRule(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", "Automation Rule"),
                        isEnabled = o.optBoolean("isEnabled", true),
                        triggerType = triggerType,
                        scheduleHour = o.optInt("scheduleHour", 7).coerceIn(0, 23),
                        scheduleMinute = o.optInt("scheduleMinute", 30).coerceIn(0, 59),
                        daysOfWeek = daysSet,
                        triggerOnSunset = o.optBoolean("triggerOnSunset", true),
                        triggerTileId = o.optString("triggerTileId", ""),
                        triggerTileTitle = o.optString("triggerTileTitle", ""),
                        triggerDeviceState = triggerDeviceState,
                        comparisonOperator = comparisonOperator,
                        thresholdValue = o.optDouble("thresholdValue", 19.0).toFloat(),
                        weatherKeyword = o.optString("weatherKeyword", "Rain"),
                        restrictToTimeWindow = o.optBoolean("restrictToTimeWindow", false),
                        windowStartHour = o.optInt("windowStartHour", 18).coerceIn(0, 23),
                        windowStartMinute = o.optInt("windowStartMinute", 0).coerceIn(0, 59),
                        windowEndHour = o.optInt("windowEndHour", 7).coerceIn(0, 23),
                        windowEndMinute = o.optInt("windowEndMinute", 0).coerceIn(0, 59),
                        combinator = combinator,
                        secondaryConditions = secondaryList,
                        actions = actionsList,
                        branches = branchesList,
                        otherwiseActions = otherwiseList,
                        lastTriggeredEpochMs = o.optLong("lastTriggeredEpochMs", 0L)
                    )
                )
            }
            cachedRulesSnapshot = list
            automationRulesFlow.value = list
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun recordHabitActuationEvent(context: Context, event: HabitActuationEvent): List<HabitActuationEvent> {
        val trimmed: List<HabitActuationEvent>
        synchronized(habitLock) {
            val current = (cachedHabitEvents ?: loadHabitActuationEvents(context)).toMutableList()
            current.add(0, event)
            trimmed = current.take(150)
            cachedHabitEvents = trimmed
        }
        return trimmed
    }

    fun loadHabitActuationEvents(context: Context): List<HabitActuationEvent> {
        synchronized(habitLock) {
            val list = cachedHabitEvents ?: emptyList()
            cachedHabitEvents = list
            return list
        }
    }

    fun markHabitSuggestionDismissed(context: Context, suggestionKey: String) {
        synchronized(habitLock) {
            val current = getDismissedHabitSuggestionKeys(context).toMutableSet()
            if (current.add(suggestionKey.trim())) {
                val arr = JSONArray()
                current.forEach { arr.put(it) }
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_DISMISSED_HABIT_IDS_JSON, arr.toString())
                    .apply()
            }
        }
    }

    fun getDismissedHabitSuggestionKeys(context: Context): Set<String> {
        synchronized(habitLock) {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DISMISSED_HABIT_IDS_JSON, null) ?: return emptySet()
            return try {
                val arr = JSONArray(raw)
                buildSet {
                    for (i in 0 until arr.length()) {
                        arr.optString(i, "").trim().takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            } catch (_: Exception) {
                emptySet()
            }
        }
    }

    fun appendAutomationLog(context: Context, entry: AutomationLogEntry) {
        val trimmed: List<AutomationLogEntry>
        synchronized(automationLogLock) {
            val current = (cachedAutomationLogs ?: loadAutomationLogs(context)).toMutableList()
            current.add(0, entry)
            trimmed = current.take(60)
            cachedAutomationLogs = trimmed
        }
        JarvisLiveBridge.automationLogsFlow.value = trimmed
        val appCtx = context.applicationContext
        repoScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val stepsArr = JSONArray()
            for (step in entry.stepLogs) {
                stepsArr.put(
                    JSONObject().apply {
                        put("stepNumber", step.stepNumber)
                        put("stepSummary", step.stepSummary)
                        put("succeeded", step.succeeded)
                        put("reasonOrDetail", step.reasonOrDetail)
                        put("resolvedTargetCount", step.resolvedTargetCount)
                    }
                )
            }
            val db = TelemetryDatabaseHelper.getInstance(appCtx).writableDatabase
            db.beginTransaction()
            try {
                val cv = android.content.ContentValues().apply {
                    put("id", entry.id)
                    put("timestampEpochMs", entry.timestampEpochMs)
                    put("ruleId", entry.ruleId)
                    put("ruleName", entry.ruleName)
                    put("triggerSource", entry.triggerSource)
                    put("status", entry.status.name)
                    put("userFriendlySummary", entry.userFriendlySummary)
                    put("matchedBranchLabel", entry.matchedBranchLabel)
                    put("stepLogsJson", stepsArr.toString())
                }
                db.insert(TelemetryDatabaseHelper.TABLE_AUTOMATION_LOG, null, cv)
                db.execSQL(
                    "DELETE FROM ${TelemetryDatabaseHelper.TABLE_AUTOMATION_LOG} WHERE id NOT IN (SELECT id FROM ${TelemetryDatabaseHelper.TABLE_AUTOMATION_LOG} ORDER BY timestampEpochMs DESC LIMIT 60)"
                )
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to insert automation log", e)
            } finally {
                db.endTransaction()
            }
        }
    }

    fun clearAutomationLogs(context: Context) {
        synchronized(automationLogLock) {
            cachedAutomationLogs = emptyList()
        }
        val appCtx = context.applicationContext
        repoScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                TelemetryDatabaseHelper.getInstance(appCtx).writableDatabase.delete(TelemetryDatabaseHelper.TABLE_AUTOMATION_LOG, null, null)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to clear automation logs", e)
            }
        }
        JarvisLiveBridge.automationLogsFlow.value = emptyList()
    }

    fun loadAutomationLogs(context: Context): List<AutomationLogEntry> {
        synchronized(automationLogLock) {
            cachedAutomationLogs?.let {
                JarvisLiveBridge.automationLogsFlow.value = it
                return it
            }
            val db = TelemetryDatabaseHelper.getInstance(context.applicationContext).readableDatabase
            val list = mutableListOf<AutomationLogEntry>()
            try {
                db.rawQuery(
                    "SELECT id, timestampEpochMs, ruleId, ruleName, triggerSource, status, userFriendlySummary, matchedBranchLabel, stepLogsJson FROM ${TelemetryDatabaseHelper.TABLE_AUTOMATION_LOG} ORDER BY timestampEpochMs DESC LIMIT 60",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val status = runCatching { AutomationRunStatus.valueOf(cursor.getString(5)) }.getOrDefault(AutomationRunStatus.SUCCESS)
                        val steps = mutableListOf<AutomationStepLog>()
                        runCatching {
                            val stepsArr = JSONArray(cursor.getString(8))
                            for (sIdx in 0 until stepsArr.length()) {
                                val so = stepsArr.optJSONObject(sIdx) ?: continue
                                steps.add(
                                    AutomationStepLog(
                                        stepNumber = so.optInt("stepNumber", sIdx + 1),
                                        stepSummary = so.optString("stepSummary", ""),
                                        succeeded = so.optBoolean("succeeded", true),
                                        reasonOrDetail = so.optString("reasonOrDetail", ""),
                                        resolvedTargetCount = so.optInt("resolvedTargetCount", 1).coerceAtLeast(1)
                                    )
                                )
                            }
                        }
                        list.add(
                            AutomationLogEntry(
                                id = cursor.getString(0),
                                timestampEpochMs = cursor.getLong(1),
                                ruleId = cursor.getString(2),
                                ruleName = cursor.getString(3),
                                triggerSource = cursor.getString(4),
                                status = status,
                                userFriendlySummary = cursor.getString(6),
                                matchedBranchLabel = cursor.getString(7),
                                stepLogs = steps
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to load automation logs", e)
            }
            cachedAutomationLogs = list
            JarvisLiveBridge.automationLogsFlow.value = list
            return list
        }
    }

    private fun defaultStarterTiles(): List<SmartTile> = listOf(
        SmartTile(
            title = "Clock & Weather",
            type = TileType.CLOCK_WEATHER,
            colSpan = 1,
            isVisible = true
        ),
        SmartTile(
            title = "Media Player",
            roomOrSubtitle = "",
            type = TileType.MUSIC_PLAYER,
            colSpan = 1,
            isVisible = true,
            volume = 60f
        )
    )
}
