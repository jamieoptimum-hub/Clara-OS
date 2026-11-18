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
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// ZERO-CONFIGURATION BACKGROUND ENVIRONMENT DISCOVERY & TOPOLOGY SELF-HEALING
// - Automatically discovers and links devices across Google Cast, Tuya (UDP
//   6666/6667 + Native SDK + Cloud), Pro Breeze Tower Fans, Google Nest SDM,
//   Imou, EZVIZ, Govee, Twinkly, TP-Link (Tapo KLAP & Kasa 9999), eWeLink/Sonoff,
//   Matter (_matter._tcp. / _matterc._udp. / port 5580), MQTT/Tasmota/ESPHome,
//   LG webOS SSDP, and Home Assistant.
// - Routes all multi-transport signals through UniversalOnboardingEngine and
//   DeviceIdentityResolver for cross-radio deduplication, Noisy-OR confidence
//   scoring, and silent existing-device IP/state healing.
// - Uses TuyaApiClient.isDeviceReachableOrOnline so transient cloud MQTT drops
//   never mark locally reachable LAN devices offline.
// - Reconciles all state updates via DeviceRepository.reconcileRemoteTileUpdate
//   with continuous SHA-256 capabilitySchemaHash evolution and replays queued
//   Digital Twin Shadow intents upon device reconnect.
// ---------------------------------------------------------------------------
private val SEMANTIC_ROOM_SYNONYMS: List<Pair<String, List<String>>> = listOf(
    "Living Room" to listOf(
        "living", "lounge", "sitting", "family room", "snug", "den", "cinema",
        "sofa", "couch", "mantel", "fireplace", "sync box", "lytmi", "ambilight", "tv"
    ),
    "Kitchen" to listOf(
        "kitchen", "scullery", "pantry", "diner", "dining", "breakfast", "island",
        "kettle", "toaster", "espresso", "coffee", "dishwasher", "fridge", "freezer", "oven", "microwave"
    ),
    "Bedroom" to listOf(
        "bedroom", "master", "nursery", "guest room", "kids room", "child", "bunk",
        "bedside", "nightstand", "wardrobe", "dresser", "cot"
    ),
    "Bathroom" to listOf(
        "bathroom", "ensuite", "en-suite", "wc", "cloakroom", "lavatory", "washroom",
        "shower", "bath", "towel rail", "mirror", "extractor"
    ),
    "Hallway" to listOf(
        "hallway", "hall", "corridor", "landing", "stairs", "staircase", "foyer", "vestibule", "boot room"
    ),
    "Front Door" to listOf(
        "front door", "porch", "entrance", "entryway", "doorbell", "chime", "gate", "intercom"
    ),
    "Garden" to listOf(
        "garden", "patio", "deck", "decking", "lawn", "yard", "backyard", "pond",
        "fountain", "shed", "greenhouse", "pergola", "gazebo", "outdoor", "floodlight", "festoon"
    ),
    "Office" to listOf(
        "office", "study", "desk", "workstation", "library", "studio", "pc", "monitor", "printer"
    ),
    "Driveway" to listOf(
        "driveway", "drive", "garage", "carport", "ev charger", "wallbox", "jaguar", "mercedes", "car"
    )
)

internal fun inferRoomFromDeviceTitle(
    rawTitle: String,
    customRooms: List<String> = emptyList()
): String {
    val clean = rawTitle.trim()
    if (clean.isBlank()) return ""

    val activeRooms = customRooms.map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { DEFAULT_ROOMS }

    // 1. Check active user-configured rooms first
    activeRooms.firstOrNull { room ->
        clean.contains(room, ignoreCase = true)
    }?.let { return it }

    // 2. Semantic synonym & appliance-context matching (restricted to active rooms)
    val lower = clean.lowercase()
    for ((canonicalRoom, keywords) in SEMANTIC_ROOM_SYNONYMS) {
        val matchingActiveRoom = activeRooms.firstOrNull { it.equals(canonicalRoom, ignoreCase = true) }
        if (matchingActiveRoom != null && keywords.any { kw -> lower.contains(kw) }) {
            return matchingActiveRoom
        }
    }
    return ""
}

suspend fun runAutomaticEnvironmentDiscoveryAndSelfHealing(
    context: Context,
    bridgeSettings: BridgeSettings,
    tiles: MutableList<SmartTile>,
    scope: CoroutineScope,
    includeFullLanScan: Boolean = true
) {
    var anyTileMutated = false
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun reconcileAndApplyTileUpdate(
        index: Int,
        current: SmartTile,
        candidate: SmartTile,
        sourceName: String = candidate.type.brandName,
        incomingAuthority: StateAuthorityTier = StateAuthorityTier.DEVICE_HARDWARE_TRUTH
    ) {
        val wasOffline = !current.isOnline
        val reconciled = DeviceRepository.reconcileRemoteTileUpdate(
            existing = current,
            incoming = candidate,
            incomingAuthority = incomingAuthority,
            sourceName = sourceName
        )
        if (reconciled != current) {
            tiles[index] = reconciled
            anyTileMutated = true
        }
        if (wasOffline && reconciled.isOnline && audioManager != null) {
            replayQueuedShadowIntentsForTile(
                context = context,
                tile = tiles[index],
                bridge = bridgeSettings,
                audioManager = audioManager,
                scope = scope
            )
        }
    }

    // 1. Prune any phantom generic "Google Nest Mini" tile that is offline or duplicates a named speaker IP
    withContext(Dispatchers.Main) {
        val namedCastIps = tiles.filter {
            it.type == TileType.NEST_MINI && !it.title.equals("Google Nest Mini", ignoreCase = true)
        }.map { it.ipAddress.substringBefore(":").trim() }.filter { it.isNotBlank() }.toSet()

        val phantomTiles = tiles.filter { t ->
            t.type == TileType.NEST_MINI &&
                    t.title.equals("Google Nest Mini", ignoreCase = true) &&
                    (!t.isOnline || t.ipAddress.substringBefore(":").trim() in namedCastIps || t.entityId.contains("."))
        }
        if (phantomTiles.isNotEmpty()) {
            for (phantom in phantomTiles) {
                tiles.remove(phantom)
                JarvisLiveBridge.removeDeviceObservability(phantom.id)
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FILTERED,
                    transportSource = "GOOGLE_CAST_TLS",
                    hardwareId = phantom.serialNumber.ifBlank { phantom.id },
                    macAddress = phantom.macAddress,
                    ipAddress = phantom.ipAddress.substringBefore(":").trim(),
                    vendor = phantom.title,
                    qualificationStage = "EnvironmentDiscoveryEngine.prunePhantomCast",
                    outcomeReason = "Pruned offline or duplicate generic Google Nest Mini tile",
                    cloudId = phantom.entityId
                )
            }
            anyTileMutated = true
        }
    }

    // 2. Single Canonical Discovery -> Identity -> Classification -> Capabilities -> Existing-Tile Healing Pass
    // F-26 & F-27: Rebuild ConnectedIdentityIndex first and gate silent EZ broadcast on user's auto-add setting
    ConnectedIdentityIndex.rebuildFromTiles(tiles)
    runCatching {
        UniversalOnboardingEngine.runUnifiedDiscoveryPass(
            context = context,
            bridgeSettings = bridgeSettings,
            tiles = tiles,
            scope = scope,
            includeFullLanScan = includeFullLanScan
        )
    }.onFailure { e ->
        if (e is kotlinx.coroutines.CancellationException) throw e
        DiscoveryFlightRecorder.record(
            eventType = DiscoveryEventType.FAILED,
            transportSource = "ENVIRONMENT_DISCOVERY",
            vendor = "EnvironmentDiscoveryEngine",
            qualificationStage = "runAutomaticEnvironmentDiscoveryAndSelfHealing",
            outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "Environment discovery pass failed"}"
        )
    }

    // 3. Reconcile any matching existing dashboard tiles against the unified discovery identities
    //    (including AppliancePowerFingerprinter and Shadow Queue replay on reconnect)
    val resolvedIdentities = UniversalOnboardingEngine.resolvedIdentitiesFlow.value
    var effectiveBridge = bridgeSettings
    if (resolvedIdentities.isNotEmpty()) {
        val discoveredHaHub = resolvedIdentities.firstOrNull { it.port == 8123 && it.ipAddress.isNotBlank() }
        if (discoveredHaHub != null) {
            val cleanHaIp = discoveredHaHub.ipAddress.substringBefore(":")
            val discoveredHaUrl = "http://$cleanHaIp:8123"
            if (effectiveBridge.haUrl.isBlank() || effectiveBridge.haUrl.contains("homeassistant.local")) {
                effectiveBridge = effectiveBridge.copy(haUrl = discoveredHaUrl)
                withContext(Dispatchers.Main) {
                    DeviceRepository.saveBridge(context, effectiveBridge)
                }
            }
        }

        withContext(Dispatchers.Main) {
            for (identity in resolvedIdentities) {
                val devId = identity.hardwareSerialOrNodeId.ifBlank { identity.cloudDeviceId }.trim()
                val cleanIp = identity.ipAddress.substringBefore(":").trim()
                val existingIdx = tiles.indexOfFirst { t ->
                    (devId.isNotBlank() && (t.serialNumber.equals(devId, true) || t.entityId.equals(devId, true))) ||
                            (cleanIp.isNotBlank() && t.ipAddress.substringBefore(":").equals(cleanIp, true)) ||
                            (t.type == identity.suggestedType && t.title.equals(identity.displayName, true))
                }
                if (existingIdx >= 0) {
                    val current = tiles[existingIdx]
                    val (mergedCaps, mergedHash) = DeviceRepository.mergeCapabilitiesWithSchemaEvolution(
                        existing = current,
                        incomingCapabilities = identity.capabilities
                    )
                    val resolvedIp = identity.ipAddress.ifBlank { current.ipAddress }
                    val resolvedKey = identity.localKeyOrPin.ifBlank { current.secretCode }
                    if (devId.isNotBlank() && resolvedIp.isNotBlank()) {
                        TuyaApiClient.registerDeviceLanAddress(devId, resolvedIp, resolvedKey)
                    }
                    val resolvedOnline = TuyaApiClient.isDeviceReachableOrOnline(
                        deviceId = devId.ifBlank { current.id },
                        ipAddress = resolvedIp,
                        reportedCloudOnline = identity.isOnline
                    )
                    val inferredAppliance = AppliancePowerFingerprinter.inferApplianceFromTelemetry(
                        powerWatts = identity.powerWatts,
                        titleHint = current.title.ifBlank { identity.displayName },
                        previous = current.detectedApplianceCategory
                    )
                    val safeSocketCount = identity.multiGangCount.coerceAtLeast(current.multiGangSocketCount.coerceAtLeast(1))
                    val autoSocket1 = if (
                        current.socket1Name == "Socket 1" &&
                        inferredAppliance != ApplianceFingerprintCategory.UNKNOWN &&
                        inferredAppliance.suggestedSocketName.isNotBlank() &&
                        identity.isActive &&
                        (!identity.isSocket2Active || safeSocketCount == 1)
                    ) {
                        inferredAppliance.suggestedSocketName
                    } else {
                        current.socket1Name
                    }
                    val updated = current.copy(
                        title = current.title.ifBlank { identity.displayName },
                        roomOrSubtitle = current.roomOrSubtitle.ifBlank {
                            identity.suggestedRoom.ifBlank {
                                inferRoomFromDeviceTitle(identity.displayName, effectiveBridge.customRooms)
                            }
                        },
                        isOnline = resolvedOnline,
                        isActive = identity.isActive,
                        isSocket2Active = identity.isSocket2Active,
                        multiGangSocketCount = safeSocketCount,
                        socket1Name = autoSocket1,
                        temperature = if (identity.temperatureC > 0f) identity.temperatureC else current.temperature,
                        targetTemperature = if (identity.targetTempC > 0f) identity.targetTempC else current.targetTemperature,
                        humidity = if (identity.humidityPct > 0) identity.humidityPct else current.humidity,
                        hvacMode = identity.hvacMode.ifBlank { current.hvacMode },
                        hvacStatus = identity.hvacStatus.ifBlank { current.hvacStatus },
                        volume = if (identity.levelOrVolumePct > 0f) identity.levelOrVolumePct else current.volume,
                        powerWatts = identity.powerWatts,
                        voltageVolts = identity.voltageVolts,
                        currentMilliamps = identity.currentMilliamps,
                        totalEnergyKwh = identity.totalEnergyKwh,
                        ipAddress = resolvedIp,
                        secretCode = resolvedKey,
                        serialNumber = devId.ifBlank { current.serialNumber },
                        entityId = identity.cloudDeviceId.ifBlank { devId.ifBlank { current.entityId } },
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash,
                        hasExplicitPowerTelemetry = current.hasExplicitPowerTelemetry || identity.hasPowerTelemetry,
                        detectedApplianceCategory = inferredAppliance
                    )
                    val isLocalAuthority = resolvedIp.isNotBlank() &&
                            !identity.primaryProviderId.contains("CLOUD", ignoreCase = true) &&
                            !identity.primaryProviderId.contains("NEST", ignoreCase = true)
                    val incomingAuthority = if (isLocalAuthority) {
                        StateAuthorityTier.DEVICE_HARDWARE_TRUTH
                    } else {
                        StateAuthorityTier.CLOUD_SHADOW_STATE
                    }
                    reconcileAndApplyTileUpdate(
                        existingIdx,
                        current,
                        updated,
                        identity.primaryProviderId.ifBlank { current.type.brandName },
                        incomingAuthority
                    )
                }
            }
        }
    }

    // 4. Lightweight Live State Syncs for Onboarded Devices (Zero Duplicate Discovery Scans)
    runCatching { TuyaApiClient.syncLiveTuyaStates(effectiveBridge, tiles) }
    runCatching { GoogleCastLanClient.syncLiveNestMiniVolumes(context, tiles) }
    runCatching { TpLinkTapoKasaClient.syncLiveTapoKasaStates(context, tiles) }
    runCatching { EWeLinkSonoffClient.syncLiveSonoffStates(context, tiles) }
    runCatching { MatterProtocolClient.syncLiveMatterStates(context, effectiveBridge, tiles) }
    runCatching { MqttAndDiyClient.syncLiveMqttAndTasmotaStates(context, tiles) }
    if (effectiveBridge.haUrl.isNotBlank() && effectiveBridge.haToken.isNotBlank()) {
        runCatching { SmartHomeApiClient.syncLiveTileStatesFromHa(effectiveBridge, tiles) }
    }
    if (effectiveBridge.teslaWallConnectorIp.isNotBlank()) {
        val wcTile = runCatching {
            SmartHomeApiClient.probeTeslaWallConnectorVitals(effectiveBridge.teslaWallConnectorIp)
        }.getOrNull()
        if (wcTile != null) {
            withContext(Dispatchers.Main) {
                val existingWcIdx = tiles.indexOfFirst {
                    it.ipAddress.substringBefore(":") == effectiveBridge.teslaWallConnectorIp.substringBefore(":") ||
                            it.entityId.equals(wcTile.entityId, ignoreCase = true)
                }
                if (existingWcIdx >= 0) {
                    reconcileAndApplyTileUpdate(existingWcIdx, tiles[existingWcIdx], wcTile, "Tesla Wall Connector")
                }
            }
        }
    }

    if (anyTileMutated) {
        withContext(Dispatchers.Main) {
            DeviceRepository.saveTiles(context, tiles)
        }
    }
}
