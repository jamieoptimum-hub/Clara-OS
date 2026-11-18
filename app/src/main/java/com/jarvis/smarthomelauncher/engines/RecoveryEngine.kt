package com.jarvis.smarthomelauncher.engines

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------
// STAGE 6: DEDICATED 8-STAGE AUTONOMOUS RECOVERY, PROACTIVE SELF-HEALING &
//          PROVIDER FAILOVER ENGINE
// - Parent Network Node Awareness (heals degraded MQTT / HA / Cast bridges first)
// - Stages 0..7 Escalation Ladder with Causal Event Sourcing & KPI Tracking
// - Proactive Background Guardian Sweep (Reconnect -> Rediscover -> Re-Auth -> Re-Provision)
// - Automatic ProviderFailoverPolicy promotion and primary local failback
// ---------------------------------------------------------------------------
object RecoveryEngine {

    private val parentBridgeLocks = ConcurrentHashMap<String, Mutex>()
    private val lastParentHealEpochByNode = ConcurrentHashMap<String, Long>()
    private val nextProactiveHealEligibleMsByDevice = ConcurrentHashMap<String, Long>()
    private val proactiveBackoffStepByDevice = ConcurrentHashMap<String, Int>()

    private fun recordStageProgress(
        tile: SmartTile,
        stageNumber: Int,
        reason: String
    ) {
        JarvisLiveBridge.updateDeviceLifecycleAndRecoveryStage(
            tile = tile,
            lifecycleState = DeviceLifecycleState.RECOVERY,
            recoveryStage = stageNumber,
            diagnosticReason = reason,
            emitFlightRecord = false
        )
    }

    private fun isShellyRpcOrStatusReachable(ipAddress: String): Boolean {
        val cleanIp = ipAddress.substringBefore(":").trim()
        if (cleanIp.isBlank()) return false
        for (path in listOf("/rpc/Shelly.GetDeviceInfo", "/shelly")) {
            try {
                val resp = SharedHttpClient.requestText(
                    url = "http://$cleanIp$path",
                    connectTimeoutMs = 700,
                    readTimeoutMs = 700
                )
                if (resp.isSuccessful && (
                            resp.body.contains("mac", ignoreCase = true) ||
                                    resp.body.contains("shelly", ignoreCase = true) ||
                                    resp.body.contains("gen", ignoreCase = true)
                            )
                ) {
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private suspend fun verifyAuthenticatedSessionReady(
        context: Context,
        tile: SmartTile,
        candidateEndpoint: String,
        bridge: BridgeSettings
    ): Boolean {
        val providerKey = resolveEffectiveProviderKey(tile)
        val parsed = EndpointSplitter.splitHostAndPort(candidateEndpoint.ifBlank { tile.ipAddress })
        val cleanIp = parsed.hostIp
        val devId = tile.serialNumber.ifBlank { tile.entityId }.trim()

        return when {
            tile.deviceSubtype == DeviceSubtype.TV || providerKey.contains("LG") -> {
                cleanIp.isNotBlank() &&
                        LgThinQWebOsClient.hasVerifiedVaultKey(context, cleanIp, tile.macAddress, devId) &&
                        LgThinQWebOsClient.sendSsapCommandWithResponse(context, cleanIp, "ssap://audio/getVolume") != null
            }
            tile.deviceSubtype in setOf(DeviceSubtype.SPEAKER, DeviceSubtype.CAST_GROUP) || providerKey.contains("CAST") -> {
                cleanIp.isNotBlank() &&
                        GoogleCastLanClient.queryCastDeviceStatus(cleanIp, if (parsed.port > 0) parsed.port else 8009) != null
            }
            providerKey.contains("SHELLY") || tile.deviceSubtype == DeviceSubtype.RELAY -> {
                isShellyRpcOrStatusReachable(cleanIp)
            }
            providerKey.contains("TWINKLY") -> {
                cleanIp.isNotBlank() && SmartHomeApiClient.probeTwinklyGestaltOnPort80(cleanIp) != null
            }
            providerKey.contains("TPLINK") || providerKey.contains("TAPO") || providerKey.contains("KASA") -> {
                cleanIp.isNotBlank() && (
                        TpLinkTapoKasaClient.queryTapoLiveTelemetry(context, cleanIp) != null ||
                                SmartHomeApiClient.isPortOpenInternal(cleanIp, 9999, 500)
                        )
            }
            providerKey.contains("SONOFF") || providerKey.contains("EWELINK") -> {
                cleanIp.isNotBlank() && (
                        tile.secretCode.isNotBlank() ||
                                ClaraSecretVault.hasVerifiedLocalControlKey(context, "EWELINK", devId, cleanIp, tile.macAddress) ||
                                SmartHomeApiClient.isPortOpenInternal(cleanIp, if (parsed.port > 0) parsed.port else 8081, 500)
                        )
            }
            providerKey.contains("MATTER") -> {
                cleanIp.isNotBlank() && MatterProtocolClient.discoverMatterDevices(context, timeoutMs = 900L)
                    .any { it.isCommissionedOperational && (it.nodeId == devId || it.ipAddress == cleanIp) }
            }
            providerKey.contains("TUYA") -> {
                val hasLocalKey = tile.secretCode.length == 16 ||
                        TuyaApiClient.resolveKnownLocalKey(devId, tile.macAddress).length == 16 ||
                        ClaraSecretVault.hasVerifiedLocalControlKey(context, "TUYA", devId, cleanIp, tile.macAddress)
                cleanIp.isNotBlank() && devId.isNotBlank() && hasLocalKey &&
                        SmartHomeApiClient.isPortOpenInternal(cleanIp, 6668, 600)
            }
            providerKey.contains("GOVEE") -> {
                cleanIp.isNotBlank() && SmartHomeApiClient.discoverGoveeDevices(bridge, timeoutMs = 800L)
                    .any { it.ipAddress == cleanIp }
            }
            else -> false
        }
    }

    /**
     * Checks if [tile] depends on a parent `DeviceClass.NETWORK` bridge (`ha_bridge`, `mqtt_broker`,
     * or a parent tile in [tiles]). If the parent bridge is degraded, heals the parent once
     * so 20 child devices do not trigger 20 redundant recovery loops.
     */
    private suspend fun healParentNetworkBridgeIfNeeded(
        context: Context,
        tile: SmartTile,
        bridge: BridgeSettings,
        tiles: MutableList<SmartTile>?
    ): Boolean {
        val providerKey = resolveEffectiveProviderKey(tile)
        val parentId = tile.parentNetworkNodeId.trim().ifBlank {
            when {
                tile.entityId.contains(".") && tile.ipAddress.isBlank() -> "ha_bridge"
                providerKey.contains("MQTT") && tile.ipAddress.isBlank() -> "mqtt_broker"
                else -> ""
            }
        }
        if (parentId.isBlank()) return false

        val mutex = parentBridgeLocks.getOrPut(parentId) { Mutex() }
        return mutex.withLock {
            val now = System.currentTimeMillis()
            val lastHealed = lastParentHealEpochByNode[parentId] ?: 0L
            if (now - lastHealed < 4_000L) {
                return@withLock true
            }

            val childKey = tile.entityId.ifBlank { tile.serialNumber }.trim()
            val healedParent = when (parentId.lowercase(Locale.US)) {
                "ha_bridge" -> {
                    if (bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()) {
                        val res = SmartHomeApiClient.fetchHomeAssistantEntities(
                            haBaseUrl = bridge.haUrl,
                            haToken = bridge.haToken,
                            bypassCircuitBreaker = true
                        )
                        if (res.isSuccess && tiles != null) {
                            SmartHomeApiClient.syncLiveTileStatesFromHa(bridge, tiles)
                        }
                        res.getOrNull()?.any { entity ->
                            entity.entityId.equals(childKey, ignoreCase = true) &&
                                    !entity.state.equals("unavailable", ignoreCase = true) &&
                                    !entity.state.equals("unknown", ignoreCase = true)
                        } == true
                    } else false
                }
                "mqtt_broker" -> {
                    val discovered = MqttAndDiyClient.connectAndDiscoverMqttEntities(context, bridge, tiles)
                    discovered.any { mq ->
                        childKey.isNotBlank() && mq.uniqueId.equals(childKey, ignoreCase = true)
                    }
                }
                else -> false
            }

            if (healedParent) {
                lastParentHealEpochByNode[parentId] = System.currentTimeMillis()
                JarvisLiveBridge.appendDeviceEvent(
                    DeviceEvent(
                        deviceId = tile.id,
                        deviceTitle = tile.title,
                        roomName = tile.roomOrSubtitle,
                        eventType = DeviceEventType.RECOVERY_STAGE,
                        source = "RecoveryEngine",
                        actorName = "Network Bridge Healer",
                        stateVersion = tile.stateVersion + 1L,
                        newStateSummary = "Healed parent network bridge ($parentId) for '${tile.title}'"
                    )
                )
            }
            healedParent
        }
    }

    suspend fun executeAutonomousRecovery(
        context: Context,
        tile: SmartTile,
        bridge: BridgeSettings,
        tiles: MutableList<SmartTile>? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val providerKey = resolveEffectiveProviderKey(tile)
        val integrationName = tile.activeProviderId.ifBlank { tile.deviceSubtype.label }
        val initialLifecycleName = tile.effectiveLifecycleState.name
        JarvisLiveBridge.incrementDeviceRecoveryAttempt(tile)

        DeviceLifecycleRecorder.recordTileTransition(
            tile = tile,
            transition = MandatoryLifecycleTransition.RECOVERY_STARTED,
            previousState = initialLifecycleName,
            newState = DeviceLifecycleState.RECOVERY.name,
            protocolResultCode = "STAGE_0_START"
        )

        // 0A. Parent DeviceClass.NETWORK Dependency Check
        if (healParentNetworkBridgeIfNeeded(context, tile, bridge, tiles)) {
            val durMs = (System.currentTimeMillis() - startMs).coerceAtLeast(10L)
            JarvisLiveBridge.recordRecoveryOutcome(succeeded = true, durationMs = durMs)
            DeviceLifecycleRecorder.recordTileTransition(
                tile = tile,
                transition = MandatoryLifecycleTransition.RECOVERY_COMPLETED,
                previousState = DeviceLifecycleState.RECOVERY.name,
                newState = DeviceLifecycleState.OPERATIONAL.name,
                latencyMs = durMs,
                protocolResultCode = "STAGE_0_PARENT_BRIDGE_OK"
            )
            JarvisLiveBridge.updateDeviceLifecycleAndRecoveryStage(
                tile = tile,
                lifecycleState = DeviceLifecycleState.ONLINE,
                recoveryStage = -1,
                recoveryDurationMs = durMs,
                emitFlightRecord = false
            )
            val msg = "Parent network bridge (${tile.parentNetworkNodeId.ifBlank { "Hub" }}) restored for '${tile.title}' (${durMs}ms)."
            DeviceRepository.appendSelfHealingLog(
                context = context,
                entry = SelfHealingActionLog(
                    deviceId = tile.id,
                    deviceTitle = tile.title,
                    integrationName = integrationName,
                    stageReached = 0,
                    stageLabel = "Network Bridge Recovery",
                    succeeded = true,
                    diagnosticDetail = msg
                )
            )
            return@withContext true to msg
        }

        // =====================================================================
        // STAGE 0: STATE & NETWORK VALIDATION
        // Verify tablet Wi-Fi link & check if a recent push event already restored state
        // =====================================================================
        recordStageProgress(tile, 0, "Stage 0: Validating tablet Wi-Fi & recent push state...")
        val localTabletIp = JarvisLiveBridge.resolveLocalTabletIp(context)
        if (localTabletIp.isBlank() || localTabletIp == "127.0.0.1") {
            val durMs = (System.currentTimeMillis() - startMs).coerceAtLeast(5L)
            val stage0Msg = "${SelfHealingStage.STAGE_0_NETWORK_INTEGRITY.label}: Tablet Wi-Fi is disconnected; holding intent in SHADOW_QUEUED."
            JarvisLiveBridge.enqueueShadowIntent(
                DigitalTwinShadowIntent(
                    tileId = tile.id,
                    tileTitle = tile.title,
                    desiredIsActive = tile.isActive,
                    desiredIsSocket2Active = tile.isSocket2Active,
                    desiredTargetTempC = if (tile.isThermostatTile) tile.targetTemperature else null,
                    desiredVolumeOrLevel = tile.volume,
                    desiredMode = tile.hvacMode,
                    intentAuthorityTier = tile.lastIntentAuthorityTier,
                    actorName = tile.lastCausalActor.ifBlank { "RecoveryEngine" }
                )
            )
            JarvisLiveBridge.recordRecoveryOutcome(succeeded = false, durationMs = durMs)
            DeviceLifecycleRecorder.recordTileTransition(
                tile = tile,
                transition = MandatoryLifecycleTransition.RECOVERY_FAILED,
                previousState = DeviceLifecycleState.RECOVERY.name,
                newState = DeviceLifecycleState.OFFLINE.name,
                latencyMs = durMs,
                protocolResultCode = "STAGE_0_WIFI_DISCONNECTED",
                failureCategory = FailureCategory.TRANSPORT_FAILURE
            )
            DeviceRepository.appendSelfHealingLog(
                context = context,
                entry = SelfHealingActionLog(
                    deviceId = tile.id,
                    deviceTitle = tile.title,
                    integrationName = integrationName,
                    stageReached = 0,
                    stageLabel = "Stage 0: State Validation",
                    succeeded = false,
                    diagnosticDetail = stage0Msg
                )
            )
            return@withContext false to stage0Msg
        }

        val devKey = tile.serialNumber.ifBlank { tile.entityId }.trim()

        // =====================================================================
        // STAGE 1: PERSISTED MAC & NEIGHBOR IP REFRESH (WITH AUTH SESSION GATE)
        // =====================================================================
        recordStageProgress(tile, 1, "Stage 1: Inspecting persisted MAC & neighbor tables for DHCP IP renewal...")
        var healedIp = tile.ipAddress.substringBefore(":").trim()
        val (udpNeighbors, mdnsNeighbors) = coroutineScope {
            val udpDeferred = async(Dispatchers.IO) {
                SmartHomeApiClient.listenForTuyaUdpBroadcasts(timeoutMs = 6500L)
            }
            val mdnsDeferred = async(Dispatchers.IO) {
                SmartHomeApiClient.discoverMdnsSmartHomeServices(context, timeoutMs = 6500L)
            }
            udpDeferred.await() to mdnsDeferred.await()
        }
        val arpNeighbors = (udpNeighbors + mdnsNeighbors).associate { it.ipAddress to it.hardwareIdOrMac }
        val knownMac = CrossEcosystemIdentityCorrelator.normalizeMac(
            tile.macAddress
                .ifBlank { JarvisLiveBridge.deviceObservabilityFlow.value[tile.id]?.currentMac.orEmpty() }
                .ifBlank { tile.serialNumber }
        )
        if (knownMac.isNotEmpty()) {
            val arpMatchedIp = arpNeighbors.entries.firstOrNull { (_, mac) ->
                CrossEcosystemIdentityCorrelator.areMacsCorrelated(mac, knownMac)
            }?.key
            if (!arpMatchedIp.isNullOrBlank() && arpMatchedIp != healedIp) {
                healedIp = arpMatchedIp
                if (verifyAuthenticatedSessionReady(context, tile, healedIp, bridge)) {
                    completeSuccessfulRecovery(
                        context = context,
                        tile = tile,
                        tiles = tiles,
                        healedIp = healedIp,
                        stageNumber = 1,
                        stageLabel = "Stage 1: IP Refresh",
                        detail = "Stage 1: Healed DHCP IP renewal ($healedIp) and verified session for '${tile.title}'.",
                        startMs = startMs
                    )
                    return@withContext true to "Stage 1: Healed DHCP IP ($healedIp) for '${tile.title}'."
                }
            }
        }

        // =====================================================================
        // STAGE 2: SUB-SECOND SERVICE REDISCOVERY
        // Dispatch targeted mDNS, SSDP, and UDP broadcast probes
        // =====================================================================
        recordStageProgress(tile, 2, "Stage 2: Running targeted mDNS, SSDP & UDP service rediscovery...")
        val stage2Recovered = when {
            tile.deviceSubtype == DeviceSubtype.TV || providerKey.contains("LG") -> {
                val foundTvIp = LgThinQWebOsClient.resolveOrAutoDiscoverTvIp(context, healedIp)
                if (foundTvIp.isNotBlank() && verifyAuthenticatedSessionReady(context, tile, foundTvIp, bridge)) {
                    healedIp = foundTvIp
                    true
                } else false
            }
            tile.deviceSubtype in setOf(DeviceSubtype.SPEAKER, DeviceSubtype.CAST_GROUP) || providerKey.contains("CAST") -> {
                val castList = GoogleCastLanClient.discoverCastDevices(context, includeFullPortSweep = false)
                val matched = castList.firstOrNull {
                    (devKey.isNotBlank() && it.id.equals(devKey, ignoreCase = true)) ||
                            it.name.equals(tile.title, ignoreCase = true)
                }
                if (matched != null) {
                    val endpoint = "${matched.ipAddress}:${matched.port}"
                    if (verifyAuthenticatedSessionReady(context, tile, endpoint, bridge)) {
                        healedIp = endpoint
                        true
                    } else false
                } else false
            }
            providerKey.contains("GOVEE") -> {
                val goveeFound = SmartHomeApiClient.discoverGoveeDevices(bridge, timeoutMs = 1000L)
                val matched = goveeFound.firstOrNull {
                    (devKey.isNotBlank() && it.hardwareIdOrMac.contains(devKey, ignoreCase = true)) ||
                            CrossEcosystemIdentityCorrelator.areMacsCorrelated(it.hardwareIdOrMac, knownMac) ||
                            it.suggestedName.equals(tile.title, ignoreCase = true)
                }
                if (matched != null && matched.ipAddress.isNotBlank()) {
                    healedIp = matched.ipAddress
                    true
                } else false
            }
            providerKey.contains("TWINKLY") -> {
                val twinklyFound = SmartHomeApiClient.discoverTwinklyDevices(context, timeoutMs = 1000L)
                val matched = twinklyFound.firstOrNull {
                    (devKey.isNotBlank() && it.hardwareIdOrMac.equals(devKey, ignoreCase = true)) ||
                            CrossEcosystemIdentityCorrelator.areMacsCorrelated(it.hardwareIdOrMac, knownMac) ||
                            it.suggestedName.equals(tile.title, ignoreCase = true)
                }
                if (matched != null && matched.ipAddress.isNotBlank() &&
                    verifyAuthenticatedSessionReady(context, tile, matched.ipAddress, bridge)
                ) {
                    healedIp = matched.ipAddress
                    true
                } else false
            }
            providerKey.contains("SHELLY") || tile.deviceSubtype == DeviceSubtype.RELAY -> {
                val mdns = SmartHomeApiClient.discoverMdnsSmartHomeServices(context, timeoutMs = 1000L)
                    .firstOrNull {
                        it.brandOption == BrandOption.SHELLY && (
                                CrossEcosystemIdentityCorrelator.areMacsCorrelated(it.hardwareIdOrMac, knownMac) ||
                                        it.suggestedName.equals(tile.title, ignoreCase = true)
                                )
                    }
                val candidateIp = mdns?.ipAddress ?: healedIp
                if (candidateIp.isNotBlank() && verifyAuthenticatedSessionReady(context, tile, candidateIp, bridge)) {
                    healedIp = candidateIp
                    true
                } else false
            }
            providerKey.contains("TPLINK") || providerKey.contains("TAPO") || providerKey.contains("KASA") -> {
                val foundTapo = TpLinkTapoKasaClient.discoverLocalKasaAndTapoDevices(context)
                    .firstOrNull {
                        (devKey.isNotBlank() && it.deviceId.equals(devKey, ignoreCase = true)) ||
                                CrossEcosystemIdentityCorrelator.areMacsCorrelated(it.macAddress, knownMac) ||
                                it.name.equals(tile.title, ignoreCase = true)
                    }
                if (foundTapo != null && foundTapo.ipAddress.isNotBlank() && !foundTapo.requiresAuth) {
                    healedIp = foundTapo.ipAddress
                    true
                } else false
            }
            providerKey.contains("SONOFF") || providerKey.contains("EWELINK") -> {
                val foundSonoff = EWeLinkSonoffClient.discoverLocalSonoffMdns(context, timeoutMs = 1000L)
                    .firstOrNull {
                        (devKey.isNotBlank() && it.deviceId.equals(devKey, ignoreCase = true)) ||
                                it.name.equals(tile.title, ignoreCase = true)
                    }
                if (foundSonoff != null && foundSonoff.ipAddress.isNotBlank() && !foundSonoff.requiresAuth) {
                    healedIp = "${foundSonoff.ipAddress}:${foundSonoff.port}"
                    true
                } else false
            }
            providerKey.contains("MATTER") -> {
                val resolvedMatterIp = MatterProtocolClient.resolveOrHealMatterIp(context, tile, bridge)
                if (resolvedMatterIp.isNotBlank() && verifyAuthenticatedSessionReady(context, tile, resolvedMatterIp, bridge)) {
                    healedIp = resolvedMatterIp
                    true
                } else false
            }
            providerKey.contains("TUYA") -> {
                val udpFound = SmartHomeApiClient.listenForTuyaUdpBroadcasts(timeoutMs = 1400L)
                val matched = udpFound.firstOrNull {
                    devKey.isNotBlank() && it.hardwareIdOrMac.equals(devKey, ignoreCase = true)
                }
                if (matched != null && matched.ipAddress.isNotBlank() &&
                    verifyAuthenticatedSessionReady(context, tile, matched.ipAddress, bridge)
                ) {
                    healedIp = matched.ipAddress
                    true
                } else false
            }
            else -> false
        }

        if (stage2Recovered && healedIp.isNotBlank()) {
            // Automatic failback to primary local provider when local fabric recovers
            val restoredPrimary = JarvisLiveBridge.restorePrimaryProviderIfHealthy(tile)
            val detail = buildString {
                append("Stage 2: Service Rediscovery healed verified endpoint $healedIp for '${tile.title}'")
                if (!restoredPrimary.isNullOrBlank()) {
                    append(" and failed back to primary provider ($restoredPrimary)")
                }
                append(".")
            }
            completeSuccessfulRecovery(
                context = context,
                tile = if (!restoredPrimary.isNullOrBlank()) tile.copy(activeProviderId = restoredPrimary) else tile,
                tiles = tiles,
                healedIp = healedIp,
                stageNumber = 2,
                stageLabel = "Stage 2: Service Rediscovery",
                detail = detail,
                startMs = startMs
            )
            return@withContext true to detail
        }

        // =====================================================================
        // STAGE 3: SILENT OAUTH & SESSION TOKEN RENEWAL (SCOPED BY ECOSYSTEM)
        // =====================================================================
        recordStageProgress(tile, 3, "Stage 3: Refreshing cloud/session authentication tokens...")
        val stage3Recovered = when {
            providerKey.contains("NEST") -> {
                NestSdmApiClient.refreshWebSessionTokenSync(context) || NestSdmApiClient.getAccessToken(bridge).isSuccess
            }
            providerKey.contains("IMOU") -> {
                bridge.imouAppId.isNotBlank() &&
                        ImouCloudApiClient.getAccessToken(bridge.imouApiHost, bridge.imouAppId, bridge.imouAppSecret, forceRefresh = true).isSuccess
            }
            providerKey.contains("EZVIZ") -> {
                bridge.ezvizAppKey.isNotBlank() &&
                        EzvizCloudApiClient.getAccessToken(bridge.ezvizApiHost, bridge.ezvizAppKey, bridge.ezvizAppSecret).isSuccess
            }
            tile.isVehicleTile -> {
                VehicleIntegrationClient.syncVehicleStates(context, bridge, tiles ?: mutableListOf(tile))
            }
            providerKey.contains("TUYA") && bridge.tuyaAccessId.isNotBlank() -> {
                TuyaApiClient.getAccessToken(bridge).isSuccess &&
                        TuyaApiClient.discoverAllTuyaDevices(bridge).getOrNull()?.any {
                            it.deviceId.equals(devKey, ignoreCase = true) && it.isOnline
                        } == true
            }
            else -> false
        }
        if (stage3Recovered) {
            val detail = "Stage 3: Token Renewal refreshed verified cloud session for '${tile.title}'."
            completeSuccessfulRecovery(
                context = context,
                tile = tile,
                tiles = tiles,
                healedIp = healedIp,
                stageNumber = 3,
                stageLabel = "Stage 3: Token Renewal",
                detail = detail,
                startMs = startMs
            )
            return@withContext true to detail
        }

        // =====================================================================
        // STAGE 4: SOCKET EVICTION, CONNECTION REBUILD & VERIFIED WAKE PACKETS
        // =====================================================================
        recordStageProgress(tile, 4, "Stage 4: Rebuilding socket connections & sending wake signals...")
        val stage4Recovered = try {
            when {
                tile.deviceSubtype == DeviceSubtype.TV || providerKey.contains("LG") -> {
                    val targetIp = healedIp.ifBlank { tile.ipAddress.substringBefore(":").trim() }
                    val mac = CrossEcosystemIdentityCorrelator.normalizeMac(
                        tile.macAddress.ifBlank { tile.serialNumber }.ifBlank {
                            LgThinQWebOsClient.getSavedMacAddress(context, targetIp)
                        }
                    )
                    if (mac.isNotBlank() && targetIp.isNotBlank() && LgThinQWebOsClient.isTvPaired(context, targetIp)) {
                        LgThinQWebOsClient.sendWakeOnLanMagicPacket(mac, targetIp)
                        kotlinx.coroutines.delay(1500L)
                        LgThinQWebOsClient.sendSsapCommandWithResponse(context, targetIp, "ssap://audio/getVolume") != null
                    } else false
                }
                providerKey.contains("EZVIZ") || tile.deviceSubtype == DeviceSubtype.DOORBELL -> {
                    EzvizCloudApiClient.wakeDoorbellIfNeeded(context, tile.serialNumber)
                    CameraStreamSessionManager.getOrCreate(context, tile.id).forceRetry()
                    healedIp.isNotBlank() && SmartHomeApiClient.isPortOpenInternal(healedIp, 554, 900)
                }
                tile.isCameraTile -> {
                    ImouCloudApiClient.clearStreamCache()
                    EzvizCloudApiClient.clearStreamCache()
                    CameraStreamSessionManager.getOrCreate(context, tile.id).forceRetry()
                    healedIp.isNotBlank() && SmartHomeApiClient.isPortOpenInternal(healedIp, 554, 900)
                }
                providerKey.contains("TUYA") && ClaraApplication.isTuyaSdkReady(context) -> {
                    TuyaApiClient.discoverNativeSdkDevices(forceRefresh = true).isSuccess &&
                            TuyaApiClient.discoverAllTuyaDevices(bridge).getOrNull()?.any {
                                it.deviceId.equals(devKey, ignoreCase = true) && it.isOnline
                            } == true
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
        if (stage4Recovered) {
            val detail = "Stage 4: Connection Rebuild reset transport session & wake packet for '${tile.title}'."
            completeSuccessfulRecovery(
                context = context,
                tile = tile,
                tiles = tiles,
                healedIp = healedIp,
                stageNumber = 4,
                stageLabel = "Stage 4: Connection Rebuild",
                detail = detail,
                startMs = startMs
            )
            return@withContext true to detail
        }

        // =====================================================================
        // STAGE 5: DETECT FACTORY-RESET BEACONS & LIFT SUPPRESSION (NO SILENT EZ BROADCAST)
        // =====================================================================
        recordStageProgress(tile, 5, "Stage 5: Checking for factory-reset setup state...")
        val nearbyBleOrAp = JarvisLiveBridge.discoveredBleAndApDevicesFlow.value.firstOrNull {
            (knownMac.isNotEmpty() && CrossEcosystemIdentityCorrelator.areMacsCorrelated(it.hardwareIdOrMac, knownMac)) ||
                    it.suggestedName.contains(tile.title, ignoreCase = true)
        }
        if (nearbyBleOrAp != null) {
            // Change 7.2 + Section 1: Lift suppression for reset hardware without broadcasting Wi-Fi in background
            val canonGuid = tile.canonicalGuid.ifBlank { tile.resolvedCanonicalGuid() }
            DeviceRepository.unmarkDeviceDismissed(context, canonGuid, devKey, knownMac, nearbyBleOrAp.hardwareIdOrMac)
            DeviceLifecycleRecorder.recordTileTransition(
                tile = tile,
                transition = MandatoryLifecycleTransition.REDISCOVERED,
                previousState = DeviceLifecycleState.RECOVERY.name,
                newState = DeviceLifecycleState.DISCOVERABLE_AGAIN.name,
                protocolResultCode = "FACTORY_RESET_BEACON_SEEN"
            )
        }

        // =====================================================================
        // STAGE 6: PROVIDER FAILOVER (PROMOTE NEXT HEALTHY FALLBACK PROVIDER)
        // =====================================================================
        recordStageProgress(tile, 6, "Stage 6: Promoting fallback provider in ProviderFailoverPolicy...")
        val promotedProvider = JarvisLiveBridge.promoteHealthyFallbackProvider(tile)
        var stage6Recovered = false

        if (bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()) {
            val haEntities = SmartHomeApiClient.fetchHomeAssistantEntities(
                haBaseUrl = bridge.haUrl,
                haToken = bridge.haToken,
                bypassCircuitBreaker = true
            ).getOrNull().orEmpty()
            val matchedHa = haEntities.firstOrNull {
                it.friendlyName.equals(tile.title, ignoreCase = true) ||
                        it.entityId.equals(tile.entityId, ignoreCase = true)
            }
            if (matchedHa != null && matchedHa.entityId.contains(".")) {
                val domain = matchedHa.entityId.substringBefore(".")
                stage6Recovered = SmartHomeApiClient.callHomeAssistantService(
                    haBaseUrl = bridge.haUrl,
                    haToken = bridge.haToken,
                    domain = domain,
                    service = if (tile.isActive) "turn_on" else "turn_off",
                    payloadJson = JSONObject().put("entity_id", matchedHa.entityId)
                )
            }
        }
        if (!stage6Recovered && MqttAndDiyClient.isConfigured(context)) {
            stage6Recovered = MqttAndDiyClient.publishTileCommand(context, tile)
        }

        if (stage6Recovered) {
            val effectiveFallbackName = promotedProvider ?: "FAILOVER_BRIDGE"
            val detail = "Stage 6: Provider Failover promoted '$effectiveFallbackName' to complete command for '${tile.title}'."
            JarvisLiveBridge.appendDeviceEvent(
                DeviceEvent(
                    deviceId = tile.id,
                    deviceTitle = tile.title,
                    roomName = tile.roomOrSubtitle,
                    eventType = DeviceEventType.PROVIDER_FAILOVER,
                    source = "RecoveryEngine",
                    actorName = "Stage 6 Failover",
                    stateVersion = tile.stateVersion + 1L,
                    newStateSummary = detail
                )
            )
            completeSuccessfulRecovery(
                context = context,
                tile = tile.copy(activeProviderId = effectiveFallbackName),
                tiles = tiles,
                healedIp = healedIp,
                stageNumber = 6,
                stageLabel = "Stage 6: Provider Failover",
                detail = detail,
                startMs = startMs
            )
            return@withContext true to detail
        }

        // =====================================================================
        // STAGE 7: USER NOTIFICATION & SHADOW_QUEUED INTENT RETENTION
        // =====================================================================
        JarvisLiveBridge.enqueueShadowIntent(
            DigitalTwinShadowIntent(
                tileId = tile.id,
                tileTitle = tile.title,
                desiredIsActive = tile.isActive,
                desiredIsSocket2Active = tile.isSocket2Active,
                desiredTargetTempC = if (tile.isThermostatTile) tile.targetTemperature else null,
                desiredVolumeOrLevel = tile.volume,
                desiredMode = tile.hvacMode,
                intentAuthorityTier = tile.lastIntentAuthorityTier,
                actorName = tile.lastCausalActor.ifBlank { "Manual User" }
            )
        )
        // =====================================================================
        // STAGE 8: EXPLICIT NEEDS_REAUTH ESCALATION vs SHADOW_QUEUED RETENTION (F-07, F-30)
        // =====================================================================
        val accountStatus = JarvisLiveBridge.accountHealthFlow.value.values.firstOrNull { acc ->
            providerKey.contains(acc.accountKey, ignoreCase = true)
        }?.oauthStatus
        val lgMissingVaultKey = providerKey.contains("LG") &&
                !LgThinQWebOsClient.hasVerifiedVaultKey(context, tile.ipAddress, tile.macAddress, devKey)
        if (lgMissingVaultKey && tile.ipAddress.isNotBlank()) {
            LgThinQWebOsClient.clearPinnedTvCertForReverification(context, tile.ipAddress, tile.macAddress)
        }
        val requiresExplicitReauth = accountStatus in setOf(
            AccountAuthStatus.EXPIRED,
            AccountAuthStatus.PERMISSIONS_REVOKED
        ) || lgMissingVaultKey

        val terminalLifecycleState = DeviceLifecycleState.QUARANTINED_ERROR
        val finalStageNumber = if (requiresExplicitReauth) 8 else 7

        val durMs = (System.currentTimeMillis() - startMs).coerceAtLeast(10L)
        JarvisLiveBridge.recordRecoveryOutcome(succeeded = false, durationMs = durMs)
        DeviceLifecycleRecorder.recordTileTransition(
            tile = tile,
            transition = MandatoryLifecycleTransition.RECOVERY_FAILED,
            previousState = DeviceLifecycleState.RECOVERY.name,
            newState = terminalLifecycleState.name,
            latencyMs = durMs,
            protocolResultCode = "STAGE_${finalStageNumber}_EXHAUSTED",
            failureCategory = if (requiresExplicitReauth) {
                FailureCategory.AUTHENTICATION_FAILURE
            } else {
                FailureCategory.TRANSPORT_FAILURE
            }
        )
        JarvisLiveBridge.updateDeviceLifecycleAndRecoveryStage(
            tile = tile,
            lifecycleState = terminalLifecycleState,
            recoveryStage = finalStageNumber,
            diagnosticReason = if (requiresExplicitReauth) {
                "Stage 8: Authentication expired or revoked (NEEDS_REAUTH)."
            } else {
                "Stage 7: Unreachable across primary and fallback providers; intent held in SHADOW_QUEUED."
            },
            recoveryDurationMs = durMs,
            emitFlightRecord = false
        )

        val devId = tile.serialNumber.ifBlank { tile.entityId }
        val stillReachableLocally = TuyaApiClient.isDeviceReachableOrOnline(
            deviceId = devId,
            ipAddress = tile.ipAddress,
            reportedCloudOnline = false
        )
        if (!stillReachableLocally && tiles != null) {
            withContext(Dispatchers.Main) {
                val idx = tiles.indexOfFirst { it.id == tile.id }
                if (idx >= 0) {
                    tiles[idx] = tiles[idx].copy(
                        isOnline = false,
                        lifecycleState = terminalLifecycleState,
                        transactionState = CommandTransactionState.SHADOW_QUEUED
                    )
                    DeviceRepository.saveTiles(context, tiles)
                }
            }
        }

        val alertMsg = "Stage 7: User Notification \u2014 '${tile.title}' is unreachable across all providers. Desired state held in SHADOW_QUEUED for automatic replay on wake."
        JarvisLiveBridge.appendDeviceEvent(
            DeviceEvent(
                deviceId = tile.id,
                deviceTitle = tile.title,
                roomName = tile.roomOrSubtitle,
                eventType = DeviceEventType.RECOVERY_STAGE,
                source = "RecoveryEngine",
                actorName = "Stage 7 Alert",
                stateVersion = tile.stateVersion,
                newStateSummary = alertMsg
            )
        )
        DeviceRepository.appendSelfHealingLog(
            context = context,
            entry = SelfHealingActionLog(
                deviceId = tile.id,
                deviceTitle = tile.title,
                integrationName = integrationName,
                stageReached = 7,
                stageLabel = "Stage 7: User Notification",
                succeeded = false,
                diagnosticDetail = alertMsg
            )
        )
        JarvisLiveBridge.emitPlatformEvent(
            JarvisPlatformEvent(
                category = JarvisEventCategory.DEVICE_OFFLINE,
                sourceDeviceId = tile.id,
                sourceDeviceTitle = tile.title,
                roomName = tile.roomOrSubtitle,
                eventType = "DEVICE_REQUIRES_ATTENTION",
                summary = alertMsg
            )
        )
        false to alertMsg
    }

    private suspend fun completeSuccessfulRecovery(
        context: Context,
        tile: SmartTile,
        tiles: MutableList<SmartTile>?,
        healedIp: String,
        stageNumber: Int,
        stageLabel: String,
        detail: String,
        startMs: Long
    ) {
        val devId = tile.serialNumber.ifBlank { tile.entityId }
        if (devId.isNotBlank() && healedIp.isNotBlank()) {
            TuyaApiClient.registerDeviceLanAddress(devId, healedIp, tile.secretCode)
        }
        val finalIp = healedIp.ifBlank { tile.ipAddress }
        val parsedEndpoint = EndpointSplitter.splitHostAndPort(finalIp)
        val canonGuid = tile.canonicalGuid.ifBlank { tile.resolvedCanonicalGuid() }
        if (parsedEndpoint.hostIp.isNotBlank()) {
            ClaraDeviceRegistryStore.getInstance(context).updateDeviceEndpointAndMac(
                canonicalGuid = canonGuid,
                newHostIp = parsedEndpoint.hostIp,
                newPort = parsedEndpoint.port,
                learnedMac = tile.macAddress
            )
        }
        if (tiles != null) {
            withContext(Dispatchers.Main) {
                val idx = tiles.indexOfFirst { it.id == tile.id }
                if (idx >= 0) {
                    val existingTx = tiles[idx].transactionState
                    tiles[idx] = tiles[idx].copy(
                        ipAddress = finalIp,
                        activeProviderId = tile.activeProviderId,
                        isOnline = true,
                        lifecycleState = DeviceLifecycleState.ONLINE,
                        transactionState = if (existingTx == CommandTransactionState.PENDING_ACK) {
                            CommandTransactionState.PENDING_ACK
                        } else {
                            CommandTransactionState.IDLE
                        }
                    )
                    DeviceRepository.saveTiles(context, tiles)
                }
            }
        }
        val durMs = (System.currentTimeMillis() - startMs).coerceAtLeast(8L)
        val recoveredTile = tile.copy(ipAddress = finalIp)
        JarvisLiveBridge.recordRecoveryOutcome(succeeded = true, durationMs = durMs)
        DeviceLifecycleRecorder.recordTileTransition(
            tile = recoveredTile,
            transition = MandatoryLifecycleTransition.RECOVERY_COMPLETED,
            previousState = DeviceLifecycleState.RECOVERY.name,
            newState = DeviceLifecycleState.OPERATIONAL.name,
            latencyMs = durMs,
            protocolResultCode = "STAGE_${stageNumber}_OK"
        )
        JarvisLiveBridge.updateDeviceLifecycleAndRecoveryStage(
            tile = recoveredTile,
            lifecycleState = DeviceLifecycleState.ONLINE,
            recoveryStage = -1,
            activeProviderId = tile.activeProviderId,
            recoveryDurationMs = durMs,
            emitFlightRecord = false
        )
        JarvisLiveBridge.appendDeviceEvent(
            DeviceEvent(
                deviceId = tile.id,
                deviceTitle = tile.title,
                roomName = tile.roomOrSubtitle,
                eventType = DeviceEventType.RECOVERY_STAGE,
                source = "RecoveryEngine",
                actorName = stageLabel,
                stateVersion = tile.stateVersion + 1L,
                newStateSummary = detail
            )
        )
        proactiveBackoffStepByDevice.remove(tile.id)
        nextProactiveHealEligibleMsByDevice.remove(tile.id)
        DeviceRepository.appendSelfHealingLog(
            context = context,
            entry = SelfHealingActionLog(
                deviceId = tile.id,
                deviceTitle = tile.title,
                integrationName = tile.activeProviderId.ifBlank { tile.deviceSubtype.label },
                stageReached = stageNumber,
                stageLabel = stageLabel,
                succeeded = true,
                diagnosticDetail = detail
            )
        )
        JarvisLiveBridge.emitPlatformEvent(
            JarvisPlatformEvent(
                category = JarvisEventCategory.SELF_HEALING,
                sourceDeviceId = tile.id,
                sourceDeviceTitle = tile.title,
                roomName = tile.roomOrSubtitle,
                eventType = "SELF_HEALED_STAGE_$stageNumber",
                summary = detail
            )
        )
    }

    /**
     * Proactive Background Self-Healing & Account Token Guardian Sweep:
     * 1. Seeds per-device observability and syncs Account Health across all integrations.
     * 2. Proactively refreshes any cloud OAuth token expiring within 10 minutes (unless MFA is required).
     * 3. Automatically runs `Reconnect -> Rediscover -> Re-Auth -> Re-Provision` on offline or
     *    degraded devices with exponential backoff (30s -> 60s -> 180s -> 300s) and replays
     *    queued Digital Twin Shadow intents upon recovery.
     */
    suspend fun runProactiveGuardianSweep(
        context: Context,
        tiles: MutableList<SmartTile>,
        bridge: BridgeSettings,
        scope: CoroutineScope
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        JarvisLiveBridge.seedDeviceObservabilityForTiles(tiles)
        JarvisLiveBridge.syncAccountHealthFromBridge(context, bridge)

        // 1. Proactive OAuth Token Renewal (10-minute pre-expiry window)
        val accounts = JarvisLiveBridge.accountHealthFlow.value.values
        for (acc in accounts) {
            if (acc.mfaRequired || acc.permissionsRevoked || acc.oauthStatus == AccountAuthStatus.NOT_LINKED) continue
            val isExpiringSoon = acc.tokenExpiresAtEpochMs in 1..(now + 10 * 60_000L) ||
                    acc.oauthStatus in setOf(AccountAuthStatus.EXPIRING_SOON, AccountAuthStatus.EXPIRED)
            if (!isExpiringSoon) continue

            val refreshedOk = when (acc.accountKey) {
                "TUYA" -> TuyaApiClient.getAccessToken(bridge).isSuccess
                "NEST" -> NestSdmApiClient.refreshWebSessionTokenSync(context) || NestSdmApiClient.getAccessToken(bridge).isSuccess
                "IMOU" -> ImouCloudApiClient.getAccessToken(bridge.imouApiHost, bridge.imouAppId, bridge.imouAppSecret, forceRefresh = true).isSuccess
                "EZVIZ" -> EzvizCloudApiClient.getAccessToken(bridge.ezvizApiHost, bridge.ezvizAppKey, bridge.ezvizAppSecret).isSuccess
                "JAGUAR", "MERCEDES" -> VehicleIntegrationClient.syncVehicleStates(context, bridge, tiles)
                "SPOTIFY" -> !NativeMusicCatalogClient.forceRefreshSpotifyTokenSync(context).isNullOrBlank()
                else -> true
            }
            JarvisLiveBridge.updateAccountHealth(
                accountKey = acc.accountKey,
                displayName = acc.displayName,
                oauthStatus = if (refreshedOk) AccountAuthStatus.VALID else AccountAuthStatus.EXPIRED,
                tokenExpiresAtEpochMs = if (refreshedOk) now + 3600_000L else acc.tokenExpiresAtEpochMs,
                lastRefreshEpochMs = now,
                statusDetail = if (refreshedOk) "Token auto-refreshed proactively" else "Auto-refresh failed; awaiting retry"
            )
        }

        // 2. Proactive Offline / Degraded Device Recovery (Max 3 devices per sweep to protect LAN bandwidth)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val candidates = tiles.filter { t ->
            t.isConfiguredAndLinked &&
                    t.type !in setOf(TileType.CLOCK_WEATHER, TileType.GROUP_CARD, TileType.PHOTO_SLIDESHOW, TileType.MACRO_TILE, TileType.ENERGY_MONITOR) &&
                    (!t.isOnline || t.effectiveLifecycleState in setOf(
                        DeviceLifecycleState.OFFLINE,
                        DeviceLifecycleState.RECOVERY,
                        DeviceLifecycleState.QUARANTINED_ERROR
                    )) &&
                    now >= (nextProactiveHealEligibleMsByDevice[t.id] ?: 0L)
        }.take(3)

        for (candidate in candidates) {
            val (healed, _) = executeAutonomousRecovery(
                context = context,
                tile = candidate,
                bridge = bridge,
                tiles = tiles
            )
            if (healed) {
                proactiveBackoffStepByDevice.remove(candidate.id)
                nextProactiveHealEligibleMsByDevice.remove(candidate.id)
                if (audioManager != null) {
                    val refreshedTile = tiles.firstOrNull { it.id == candidate.id } ?: candidate.copy(isOnline = true)
                    replayQueuedShadowIntentsForTile(
                        context = context,
                        tile = refreshedTile,
                        bridge = bridge,
                        audioManager = audioManager,
                        scope = scope
                    )
                }
            } else {
                val step = (proactiveBackoffStepByDevice[candidate.id] ?: 0) + 1
                proactiveBackoffStepByDevice[candidate.id] = step
                val delayMs = when (step) {
                    1 -> 30_000L
                    2 -> 60_000L
                    3 -> 180_000L
                    else -> 300_000L
                }
                nextProactiveHealEligibleMsByDevice[candidate.id] = System.currentTimeMillis() + delayMs
            }
        }
    }
}
