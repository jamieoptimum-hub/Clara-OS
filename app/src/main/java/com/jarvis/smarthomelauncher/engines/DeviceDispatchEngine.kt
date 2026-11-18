package com.jarvis.smarthomelauncher.engines

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// HARDWARE, VEHICLE, LYTMI / GOVEE / TWINKLY RGB, PRO BREEZE TOWER FAN,
// ROBOVAC & CAPABILITY DISPATCH ENGINE (WITH CLOSED-LOOP ACK, RBAC GUARD,
// DUAL AUTHORITY ARBITRATION, CAUSAL EVENT SOURCING, 9-STAGE SELF-HEALING &
// DIGITAL TWIN SHADOW QUEUING)
// ---------------------------------------------------------------------------

private fun resolveAppContext(explicitContext: Context? = null): Context? =
    ClaraApplication.appContextOrNull(explicitContext)

private fun isHardwareLifecycleTrackedTile(tile: SmartTile): Boolean =
    tile.isConfiguredAndLinked &&
            tile.serviceClass == null &&
            tile.deviceSubtype.isHardwareSubtype &&
            tile.type !in setOf(
        TileType.CLOCK_WEATHER,
        TileType.MUSIC_PLAYER,
        TileType.JARVIS_SCENES,
        TileType.PHOTO_SLIDESHOW,
        TileType.GROUP_CARD,
        TileType.ENERGY_MONITOR,
        TileType.MACRO_TILE,
        TileType.SECURITY_CENTER
    )

private fun recordDispatchCommandSent(
    tile: SmartTile,
    actionCode: String
) {
    if (!isHardwareLifecycleTrackedTile(tile)) return
    DeviceLifecycleRecorder.recordTileTransition(
        tile = tile,
        transition = MandatoryLifecycleTransition.COMMAND_SENT,
        previousState = tile.effectiveLifecycleState.name,
        newState = DeviceLifecycleState.OPERATIONAL.name,
        protocolResultCode = actionCode
    )
}

private fun recordDispatchCommandOutcome(
    beforeTile: SmartTile,
    afterTile: SmartTile,
    succeeded: Boolean,
    latencyMs: Long,
    actionCode: String,
    failureReason: String = ""
) {
    if (!isHardwareLifecycleTrackedTile(beforeTile)) return
    val failCat = if (succeeded) {
        null
    } else {
        ErrorClassifier.classifyException(Exception(failureReason.ifBlank { actionCode }))
    }
    DeviceLifecycleRecorder.recordTileTransition(
        tile = afterTile,
        transition = if (succeeded) {
            MandatoryLifecycleTransition.COMMAND_ACK
        } else {
            MandatoryLifecycleTransition.COMMAND_FAILED
        },
        previousState = DeviceLifecycleState.OPERATIONAL.name,
        newState = if (succeeded) {
            DeviceLifecycleState.OPERATIONAL.name
        } else {
            afterTile.effectiveLifecycleState.name
        },
        latencyMs = latencyMs,
        protocolResultCode = if (succeeded) "${actionCode}_ACK" else "${actionCode}_ERR",
        failureCategory = failCat
    )
}

private fun summarizeTileTargetState(tile: SmartTile, singleSocketNumber: Int? = null): String {
    // F-12 & F-13: Summarize multi-gang sockets dynamically from capability descriptors (socket_1..socket_N)
    val dynamicSockets = tile.multiGangSocketCapabilities()
    if (singleSocketNumber != null && singleSocketNumber >= 1 && (tile.isMultiGangSwitch || dynamicSockets.isNotEmpty())) {
        val cap = dynamicSockets.firstOrNull {
            it.key.endsWith("_$singleSocketNumber")
        }
        val label = cap?.label?.ifBlank { null } ?: when (singleSocketNumber) {
            1 -> tile.socket1Name.ifBlank { "Socket 1" }
            2 -> tile.socket2Name.ifBlank { "Socket 2" }
            else -> "Socket $singleSocketNumber"
        }
        val isOn = tile.isSocketActive(singleSocketNumber)
        return "$label: ${if (isOn) "ON" else "OFF"}"
    }
    if (dynamicSockets.size > 2) {
        return dynamicSockets.joinToString(", ") { cap ->
            val idx = cap.key.substringAfterLast("_").toIntOrNull() ?: 1
            "S$idx:${if (tile.isSocketActive(idx)) "ON" else "OFF"}"
        }
    }
    return when {
        tile.isVehicleTile -> if (tile.vehicleLocked) "Locked" else "Unlocked"
        tile.deviceSubtype == DeviceSubtype.LOCK -> if (tile.isActive) "Locked" else "Unlocked"
        tile.isThermostatTile -> if (tile.isActive && !tile.hvacMode.equals("OFF", true)) {
            val targetC = tile.hardwareTargetTempCelsiusOrNull() ?: tile.targetTemperature
            "${tile.hvacMode} (${"%.1f".format(Locale.US, targetC)}\u00B0C)"
        } else {
            "OFF"
        }
        tile.isTowerFanTile -> if (tile.isActive) "Speed ${tile.fanSpeedLevel} (${tile.fanMode})" else "OFF"
        tile.isAmbientRgbTile -> if (tile.isActive) "${tile.resolvedSyncMode} (${tile.volume.roundToInt()}%)" else "OFF"
        tile.isMultiGangSwitch ->
            "${tile.socket1Name.ifBlank { "S1" }}:${if (tile.isActive) "ON" else "OFF"}, ${tile.socket2Name.ifBlank { "S2" }}:${if (tile.isSocket2Active) "ON" else "OFF"}"
        tile.isSpeakerTile -> "Volume ${tile.volume.roundToInt()}%"
        tile.isCameraTile -> if (tile.isLiveFeedActive) "Live Stream ON" else "Live Stream OFF"
        else -> if (tile.isActive) "ON" else "OFF"
    }
}

/**
 * Dispatch-level RBAC & Parental Lock Guard (CRIT-02 / SEC-01 fix):
 * Blocks unauthorized unlocking of vehicles, unlocking of smart doors, or disarming of
 * the Security Center when a Parental-Locked profile (CHILD / GUEST) is active and
 * explicit PIN authorization was not granted.
 */
fun isProtectedActionBlockedByParentalLock(
    context: Context?,
    bridge: BridgeSettings,
    tile: SmartTile,
    pinAuthorized: Boolean = false
): Boolean {
    if (pinAuthorized) return false
    if (!bridge.isParentalLockActive) return false

    val isUnlockingVehicle = tile.isVehicleTile && !tile.vehicleLocked
    val isUnlockingDoor = (tile.deviceSubtype == DeviceSubtype.LOCK || tile.hasLockCapability) &&
            !tile.isVehicleTile && !tile.isActive
    val isDisarmingSecurity = tile.isSecurityCenterTile &&
            tile.securityMode == SecurityCenterMode.HOME &&
            bridge.securityCenterMode != SecurityCenterMode.HOME

    if (isUnlockingVehicle || isUnlockingDoor || isDisarmingSecurity) {
        if (context != null) {
            DeviceRepository.logAuditEvent(
                context = context,
                actorRole = bridge.activeUserRole,
                actionType = "PARENTAL_LOCK_BLOCKED_DISPATCH",
                targetName = tile.title,
                detail = "Blocked restricted unlock/disarm on '${tile.title}' under profile '${bridge.activeUserProfile.name}'",
                succeeded = false
            )
        }
        return true
    }
    return false
}

private suspend fun resolveOrHealTuyaDeviceId(
    bridge: BridgeSettings,
    tile: SmartTile
): String {
    val existing = tile.serialNumber.ifBlank { tile.entityId }.trim()
    if (existing.isNotBlank() &&
        !existing.startsWith("enterprises/") &&
        !existing.startsWith("matter_") &&
        !existing.startsWith("tasmota_") &&
        !existing.startsWith("esphome_") &&
        !existing.startsWith("mqtt_") &&
        !existing.contains(".")
    ) {
        return existing
    }
    return TuyaApiClient.resolveDeviceIdForTile(bridge, tile)
}

private suspend fun resolveOrHealHaEntityId(
    bridge: BridgeSettings,
    tile: SmartTile
): String {
    val existing = tile.entityId.trim()
    if (existing.contains(".")) return existing
    if (bridge.haUrl.isBlank() || bridge.haToken.isBlank()) return ""

    val res = SmartHomeApiClient.fetchHomeAssistantEntities(bridge.haUrl, bridge.haToken)
    if (res.isSuccess) {
        val list = res.getOrThrow()
        val matched = list.firstOrNull {
            it.friendlyName.equals(tile.title, ignoreCase = true) ||
                    (it.suggestedType == tile.type && tile.title.contains(it.friendlyName, ignoreCase = true))
        } ?: list.firstOrNull { it.suggestedType == tile.type }
        if (matched != null) {
            return matched.entityId
        }
    }
    return ""
}

private suspend fun resolveOrHealShellyIp(
    context: Context?,
    tile: SmartTile
): String = withContext(Dispatchers.IO) {
    val cleanExisting = tile.ipAddress.trim().substringBefore(":")
    if (cleanExisting.isNotBlank() && SmartHomeApiClient.isPortOpenInternal(cleanExisting, 80, 350)) {
        return@withContext cleanExisting
    }
    val ctx = resolveAppContext(context) ?: return@withContext cleanExisting
    val mdnsShelly = SmartHomeApiClient.discoverMdnsSmartHomeServices(ctx, timeoutMs = 1200L)
        .filter { it.suggestedType == TileType.SHELLY_RELAY }
    val matchedMdns = mdnsShelly.firstOrNull {
        it.suggestedName.equals(tile.title, ignoreCase = true) ||
                tile.title.contains(it.suggestedName, ignoreCase = true)
    } ?: mdnsShelly.firstOrNull()
    if (matchedMdns != null) {
        return@withContext matchedMdns.ipAddress.substringBefore(":")
    }
    val lanShelly = SmartHomeApiClient.discoverLanNetworkPass(
        context = ctx,
        includeFullPortSweep = false
    ).filter { it.suggestedType == TileType.SHELLY_RELAY }
    val matchedLan = lanShelly.firstOrNull {
        it.suggestedName.equals(tile.title, ignoreCase = true)
    } ?: lanShelly.firstOrNull()
    matchedLan?.ipAddress?.substringBefore(":") ?: cleanExisting
}

/**
 * Replays any pending Stage 7 Digital Twin Shadow Intents when a previously unreachable
 * device comes back online, respecting Stage 4 `IntentAuthorityTier.RECOVERY` arbitration.
 */
fun replayQueuedShadowIntentsForTile(
    context: Context,
    tile: SmartTile,
    bridge: BridgeSettings,
    audioManager: AudioManager,
    scope: CoroutineScope
) {
    val pendingIntents = JarvisLiveBridge.consumeShadowIntentsForDevice(tile.id)
    if (pendingIntents.isEmpty()) return

    val (canReplay, blockedReason) = DeviceRepository.canExecuteOutboundIntent(
        tile = tile,
        intentTier = IntentAuthorityTier.RECOVERY
    )
    if (!canReplay) {
        DeviceRepository.appendSelfHealingLog(
            context = context,
            entry = SelfHealingActionLog(
                deviceId = tile.id,
                deviceTitle = tile.title,
                integrationName = tile.type.brandName,
                stageReached = SelfHealingStage.STAGE_7_DIGITAL_TWIN_SHADOW.stageNumber,
                stageLabel = SelfHealingStage.STAGE_7_DIGITAL_TWIN_SHADOW.label,
                succeeded = true,
                diagnosticDetail = "Skipped queued shadow intent for '${tile.title}': $blockedReason"
            )
        )
        return
    }

    val latestIntent = pendingIntents.first()
    val shadowedTile = tile.copy(
        isActive = latestIntent.desiredIsActive,
        isSocket2Active = latestIntent.desiredIsSocket2Active,
        targetTemperature = latestIntent.desiredTargetTempC ?: tile.targetTemperature,
        volume = latestIntent.desiredVolumeOrLevel ?: tile.volume,
        hvacMode = latestIntent.desiredMode.ifBlank { tile.hvacMode },
        transactionState = CommandTransactionState.PENDING_ACK
    )

    DeviceRepository.appendSelfHealingLog(
        context = context,
        entry = SelfHealingActionLog(
            deviceId = tile.id,
            deviceTitle = tile.title,
            integrationName = tile.type.brandName,
            stageReached = SelfHealingStage.STAGE_7_DIGITAL_TWIN_SHADOW.stageNumber,
            stageLabel = SelfHealingStage.STAGE_7_DIGITAL_TWIN_SHADOW.label,
            succeeded = true,
            diagnosticDetail = "Replaying queued Digital Twin Shadow state for '${tile.title}' upon reconnect."
        )
    )

    dispatchDeviceUpdate(
        updated = shadowedTile,
        bridge = bridge,
        audioManager = audioManager,
        scope = scope,
        pinAuthorized = true,
        intentTier = IntentAuthorityTier.RECOVERY,
        actorName = "Shadow Queue Replay"
    )
}

/**
 * Typed Provider resolution backed by Layer 2 [ProviderId].
 */
internal fun resolveEffectiveProviderKey(tile: SmartTile): String {
    val explicit = tile.activeProviderId.trim().uppercase(Locale.US)
    if (explicit.isNotBlank()) return explicit
    return tile.resolvedProviderId.id
}

/**
 * Phase 2.2 Transport-Level Authority Separation:
 * Distinguishes between verified local-network hardware acknowledgments (`DEVICE_HARDWARE_TRUTH`)
 * and cloud relay HTTP 200 responses (`CLOUD_SHADOW_STATE`).
 */
internal fun resolveVerifiedTransportAuthorityTier(
    tile: SmartTile,
    usedLocalLanTransport: Boolean? = null
): StateAuthorityTier {
    if (usedLocalLanTransport == true) return StateAuthorityTier.DEVICE_HARDWARE_TRUTH
    if (usedLocalLanTransport == false) return StateAuthorityTier.CLOUD_SHADOW_STATE

    if (tile.isVehicleTile || tile.deviceClass == DeviceClass.VEHICLE) {
        return StateAuthorityTier.CLOUD_SHADOW_STATE
    }
    val providerKey = resolveEffectiveProviderKey(tile)
    val typedProvider = ProviderId.fromRawKey(providerKey)
    if (typedProvider != ProviderId.UNKNOWN && !typedProvider.isLocalTransport) {
        return StateAuthorityTier.CLOUD_SHADOW_STATE
    }
    if (providerKey.contains("CLOUD") ||
        providerKey.contains("SMARTTHINGS") ||
        providerKey.contains("NEST") ||
        providerKey.contains("OCTOPUS") ||
        providerKey.contains("SOLAREDGE") ||
        providerKey.contains("ALEXA") ||
        tile.entityId.startsWith("enterprises/")
    ) {
        return StateAuthorityTier.CLOUD_SHADOW_STATE
    }
    if (providerKey.contains("TUYA")) {
        val devId = tile.serialNumber.ifBlank { tile.entityId }.trim()
        val hasLanIp = tile.ipAddress.isNotBlank() || TuyaApiClient.resolveKnownLanIp(devId).isNotBlank()
        val hasLocalKey = tile.secretCode.length == 16 ||
                TuyaApiClient.resolveKnownLocalKey(devId, tile.macAddress).length == 16
        return if (hasLanIp && hasLocalKey) {
            StateAuthorityTier.DEVICE_HARDWARE_TRUTH
        } else {
            StateAuthorityTier.CLOUD_SHADOW_STATE
        }
    }
    return StateAuthorityTier.DEVICE_HARDWARE_TRUTH
}

suspend fun dispatchAmbientRgbTileUpdate(
    bridge: BridgeSettings,
    tile: SmartTile
): Boolean {
    val red = ((tile.lytmiColorArgb shr 16) and 0xFF).toInt().coerceIn(0, 255)
    val green = ((tile.lytmiColorArgb shr 8) and 0xFF).toInt().coerceIn(0, 255)
    val blue = (tile.lytmiColorArgb and 0xFF).toInt().coerceIn(0, 255)
    val effectiveHdmi = tile.resolvedHdmiInput
    val effectiveSyncMode = tile.resolvedSyncMode
    val effectiveSceneName = tile.resolvedSceneName
    val effectiveDiffusionSpeed = tile.resolvedDiffusionSpeed
    val provider = tile.resolvedProviderId
    val protocolOk = when (provider) {
        ProviderId.GOVEE_LAN, ProviderId.GOVEE_CLOUD -> {
            SmartHomeApiClient.sendGoveeCommand(
                bridge = bridge,
                tile = tile,
                turnOn = tile.isActive,
                brightnessPct = tile.volume,
                colorArgb = tile.lytmiColorArgb,
                syncMode = effectiveSyncMode,
                sceneName = effectiveSceneName
            )
        }
        ProviderId.TWINKLY_LAN -> {
            SmartHomeApiClient.sendTwinklyCommand(
                bridge = bridge,
                tile = tile,
                turnOn = tile.isActive,
                brightnessPct = tile.volume,
                colorArgb = tile.lytmiColorArgb,
                syncMode = effectiveSyncMode
            )
        }
        else -> {
            TuyaApiClient.sendLytmiSyncCommand(
                bridge = bridge,
                tile = tile,
                turnOn = tile.isActive,
                hdmiInput = effectiveHdmi,
                syncMode = effectiveSyncMode,
                sceneName = effectiveSceneName,
                diffusionSpeed = effectiveDiffusionSpeed,
                brightnessPct = tile.volume,
                colorArgb = tile.lytmiColorArgb
            )
        }
    }

    var haOk = false
    if (tile.entityId.startsWith("light.") && bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()) {
        val haPayload = JSONObject().apply {
            put("entity_id", tile.entityId)
            if (tile.isActive) {
                put("brightness_pct", tile.volume.roundToInt().coerceIn(1, 100))
                put("rgb_color", JSONArray().put(red).put(green).put(blue))
            }
        }
        haOk = SmartHomeApiClient.callHomeAssistantService(
            haBaseUrl = bridge.haUrl,
            haToken = bridge.haToken,
            domain = "light",
            service = if (tile.isActive) "turn_on" else "turn_off",
            payloadJson = haPayload
        )
    }

    return protocolOk || haOk
}

/**
 * Unified hardware dispatcher for Pro Breeze Smart Tower Fans (`TileType.PROBREEZE_FAN`).
 * Routes through the Persistent TCP 6668 LAN Socket Pool first, then Tuya SDK / Cloud, and Home Assistant (`fan.*`).
 */
suspend fun dispatchTowerFanHardwareUpdate(
    bridge: BridgeSettings,
    updated: SmartTile
): Boolean = withContext(Dispatchers.IO) {
    var devId = updated.serialNumber.ifBlank { updated.entityId }.trim()
    if (devId.isBlank() || devId.contains(".")) {
        val healed = resolveOrHealTuyaDeviceId(bridge, updated)
        if (healed.isNotBlank()) devId = healed
    }

    var handled = false
    if (devId.isNotBlank() && !devId.contains(".")) {
        handled = TuyaApiClient.sendNativeTowerFanCommand(
            bridge = bridge,
            tile = updated.copy(
                serialNumber = updated.serialNumber.ifBlank { devId },
                entityId = updated.entityId.ifBlank { devId }
            )
        )
    }

    val haEntity = if (updated.entityId.startsWith("fan.") || updated.entityId.startsWith("switch.")) {
        updated.entityId
    } else if (!handled) {
        resolveOrHealHaEntityId(bridge, updated)
    } else ""

    if (haEntity.isNotBlank() && bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()) {
        val domain = haEntity.substringBefore(".")
        if (!updated.isActive) {
            handled = SmartHomeApiClient.callHomeAssistantService(
                haBaseUrl = bridge.haUrl,
                haToken = bridge.haToken,
                domain = domain,
                service = "turn_off",
                payloadJson = JSONObject().put("entity_id", haEntity)
            )
        } else {
            val pct = ((updated.fanSpeedLevel.coerceIn(1, 6) / 6.0) * 100.0).roundToInt().coerceIn(16, 100)
            handled = SmartHomeApiClient.callHomeAssistantService(
                haBaseUrl = bridge.haUrl,
                haToken = bridge.haToken,
                domain = domain,
                service = "turn_on",
                payloadJson = JSONObject().apply {
                    put("entity_id", haEntity)
                    if (domain == "fan") {
                        put("percentage", pct)
                        put("preset_mode", updated.fanMode.lowercase(Locale.US))
                    }
                }
            )
            if (domain == "fan") {
                SmartHomeApiClient.callHomeAssistantService(
                    haBaseUrl = bridge.haUrl,
                    haToken = bridge.haToken,
                    domain = "fan",
                    service = "oscillate",
                    payloadJson = JSONObject()
                        .put("entity_id", haEntity)
                        .put("oscillating", updated.fanOscillating)
                )
            }
        }
    }

    JarvisLiveBridge.emitPlatformEvent(
        JarvisPlatformEvent(
            category = JarvisEventCategory.CLIMATE,
            sourceDeviceId = updated.id,
            sourceDeviceTitle = updated.title,
            roomName = updated.roomOrSubtitle,
            eventType = if (updated.isActive) "FAN_ON_${updated.fanMode}" else "FAN_OFF",
            summary = if (updated.isActive) {
                "${updated.title} set to Speed ${updated.fanSpeedLevel} (${updated.fanMode}${if (updated.fanOscillating) ", Oscillating" else ""})"
            } else {
                "${updated.title} turned OFF"
            }
        )
    )

    handled
}

/**
 * Unified hardware dispatcher for Airrobo / Tuya / Home Assistant Robot Vacuums.
 * Dispatches real Tuya DPs (`switch_go`, `power_go`, `mode = smart/spiral/spot/chargego`,
 * `suction = strong/max/normal`, and `switch_charge`) for Auto (`CLEAN`), Spot (`SPOT`),
 * Turbo (`TURBO`), and Dock (`DOCK`).
 */
suspend fun dispatchRobovacHardwareUpdate(
    bridge: BridgeSettings,
    updated: SmartTile
): Boolean = withContext(Dispatchers.IO) {
    val actionMode = updated.hvacMode.uppercase(Locale.US)
    val wantsDock = actionMode == "DOCK" || !updated.isActive
    val wantsStart = updated.isActive && !wantsDock

    var devId = updated.serialNumber.ifBlank { updated.entityId }.trim()
    if (devId.isBlank() || devId.contains(".")) {
        val healed = resolveOrHealTuyaDeviceId(bridge, updated)
        if (healed.isNotBlank()) devId = healed
    }

    var handled = false
    if (devId.isNotBlank() && !devId.contains(".")) {
        if (updated.ipAddress.isNotBlank()) {
            TuyaApiClient.registerDeviceLanAddress(devId, updated.ipAddress, updated.secretCode)
        }
        // 1. Dispatch via Native Tuya LAN 6668 / ThingHomeSdk with full mode & suction support
        handled = TuyaApiClient.sendNativeVacuumCommand(
            deviceId = devId,
            startCleaning = wantsStart,
            returnToDock = wantsDock,
            vacuumActionMode = if (wantsDock) "DOCK" else actionMode
        )

        // 2. Fallback to Tuya Cloud OpenAPI DP commands if native LAN/SDK didn't confirm
        if (!handled) {
            val primaryGoCode = TuyaApiClient.switchDpCodeByDevice[devId]?.takeIf { it.isNotBlank() } ?: "power_go"
            val modeOpts = TuyaApiClient.findCapability(devId, "mode", "work_mode", "clean_mode")
                ?.enumOptions?.map { it.lowercase(Locale.US) }.orEmpty()
            val suctionOpts = TuyaApiClient.findCapability(devId, "suction", "fan_speed_enum", "fan_speed", "cistern", "clean_power")
                ?.enumOptions?.map { it.lowercase(Locale.US) }.orEmpty()
            val spotVal = modeOpts.firstOrNull { it in listOf("spiral", "spot", "part", "partial", "single") } ?: "spiral"
            val turboVal = suctionOpts.firstOrNull { it in listOf("strong", "max", "high", "turbo", "3") } ?: "strong"
            val normalVal = suctionOpts.firstOrNull { it in listOf("normal", "standard", "middle", "gentle", "1") } ?: "normal"

            val cmds = JSONArray()
            when {
                wantsDock -> {
                    cmds.put(JSONObject().put("code", primaryGoCode).put("value", false))
                    if (primaryGoCode != "switch_go") {
                        cmds.put(JSONObject().put("code", "switch_go").put("value", false))
                    }
                    cmds.put(JSONObject().put("code", "switch_charge").put("value", true))
                    cmds.put(JSONObject().put("code", "mode").put("value", "chargego"))
                }
                actionMode == "SPOT" -> {
                    cmds.put(JSONObject().put("code", "power").put("value", true))
                    cmds.put(JSONObject().put("code", primaryGoCode).put("value", true))
                    if (primaryGoCode != "switch_go") {
                        cmds.put(JSONObject().put("code", "switch_go").put("value", true))
                    }
                    cmds.put(JSONObject().put("code", "mode").put("value", spotVal))
                }
                actionMode == "TURBO" -> {
                    cmds.put(JSONObject().put("code", "power").put("value", true))
                    cmds.put(JSONObject().put("code", primaryGoCode).put("value", true))
                    if (primaryGoCode != "switch_go") {
                        cmds.put(JSONObject().put("code", "switch_go").put("value", true))
                    }
                    cmds.put(JSONObject().put("code", "mode").put("value", "smart"))
                    cmds.put(JSONObject().put("code", "suction").put("value", turboVal))
                }
                else -> {
                    cmds.put(JSONObject().put("code", "power").put("value", true))
                    cmds.put(JSONObject().put("code", primaryGoCode).put("value", true))
                    if (primaryGoCode != "switch_go") {
                        cmds.put(JSONObject().put("code", "switch_go").put("value", true))
                    }
                    cmds.put(JSONObject().put("code", "mode").put("value", "smart"))
                    cmds.put(JSONObject().put("code", "suction").put("value", normalVal))
                }
            }
            handled = TuyaApiClient.sendCommands(bridge, devId, cmds)
        }
    }

    // 3. Home Assistant `vacuum.*` service support if linked
    val haEntity = if (updated.entityId.startsWith("vacuum.")) {
        updated.entityId
    } else if (!handled) {
        resolveOrHealHaEntityId(bridge, updated)
    } else ""

    if (haEntity.startsWith("vacuum.") && bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()) {
        when {
            wantsDock -> {
                handled = SmartHomeApiClient.callHomeAssistantService(
                    haBaseUrl = bridge.haUrl,
                    haToken = bridge.haToken,
                    domain = "vacuum",
                    service = "return_to_base",
                    payloadJson = JSONObject().put("entity_id", haEntity)
                )
            }
            actionMode == "SPOT" -> {
                handled = SmartHomeApiClient.callHomeAssistantService(
                    haBaseUrl = bridge.haUrl,
                    haToken = bridge.haToken,
                    domain = "vacuum",
                    service = "clean_spot",
                    payloadJson = JSONObject().put("entity_id", haEntity)
                )
            }
            actionMode == "TURBO" -> {
                SmartHomeApiClient.callHomeAssistantService(
                    haBaseUrl = bridge.haUrl,
                    haToken = bridge.haToken,
                    domain = "vacuum",
                    service = "set_fan_speed",
                    payloadJson = JSONObject().put("entity_id", haEntity).put("fan_speed", "max")
                )
                handled = SmartHomeApiClient.callHomeAssistantService(
                    haBaseUrl = bridge.haUrl,
                    haToken = bridge.haToken,
                    domain = "vacuum",
                    service = "start",
                    payloadJson = JSONObject().put("entity_id", haEntity)
                )
            }
            else -> {
                handled = SmartHomeApiClient.callHomeAssistantService(
                    haBaseUrl = bridge.haUrl,
                    haToken = bridge.haToken,
                    domain = "vacuum",
                    service = "start",
                    payloadJson = JSONObject().put("entity_id", haEntity)
                )
            }
        }
    }

    handled
}

/**
 * Toggles all child tiles belonging to a Dashboard Group Card (`TileType.GROUP_CARD`)
 * and dispatches the hardware updates for each member device.
 */
fun dispatchGroupCardMasterToggle(
    groupTile: SmartTile,
    turnOn: Boolean,
    tiles: MutableList<SmartTile>,
    bridge: BridgeSettings,
    audioManager: AudioManager,
    scope: CoroutineScope,
    context: Context,
    intentTier: IntentAuthorityTier = IntentAuthorityTier.MANUAL_USER,
    actorName: String = "Group Card (${groupTile.title})"
) {
    var anyMutated = false
    val now = System.currentTimeMillis()
    for (i in tiles.indices) {
        val t = tiles[i]
        if (t.id == groupTile.id) {
            tiles[i] = t.copy(isActive = turnOn, lastUserActuationEpochMs = now)
            anyMutated = true
        } else if (t.groupCardId == groupTile.id) {
            val (canRun, _) = DeviceRepository.canExecuteOutboundIntent(t, intentTier, now)
            if (!canRun) continue

            val baseChild = when {
                t.isCameraTile -> t.copy(isLiveFeedActive = turnOn, lastUserActuationEpochMs = now)
                t.isThermostatTile -> t.copy(
                    isActive = turnOn,
                    hvacMode = if (turnOn) "HEAT" else "OFF",
                    hvacStatus = if (turnOn && t.targetTemperature > t.temperature) "HEATING" else "OFF",
                    transactionState = CommandTransactionState.PENDING_ACK,
                    lastUserActuationEpochMs = now
                )
                t.isMultiGangSwitch -> t.copy(
                    isActive = turnOn,
                    isSocket2Active = turnOn,
                    powerWatts = if (turnOn) t.powerWatts else 0f,
                    transactionState = CommandTransactionState.PENDING_ACK,
                    lastUserActuationEpochMs = now
                )
                t.isVehicleTile -> t.copy(
                    vehicleLocked = turnOn,
                    isActive = turnOn,
                    transactionState = CommandTransactionState.PENDING_ACK,
                    lastUserActuationEpochMs = now
                )
                else -> t.copy(
                    isActive = turnOn,
                    powerWatts = if (turnOn) t.powerWatts else 0f,
                    transactionState = CommandTransactionState.PENDING_ACK,
                    lastUserActuationEpochMs = now
                )
            }
            val updatedChild = DeviceRepository.stampOutboundIntentAuthority(
                tile = baseChild,
                intentTier = intentTier,
                actorName = actorName,
                nowMs = now
            )
            if (updatedChild != t) {
                tiles[i] = updatedChild
                anyMutated = true
                dispatchDeviceUpdate(
                    updated = updatedChild,
                    bridge = bridge,
                    audioManager = audioManager,
                    scope = scope,
                    intentTier = intentTier,
                    actorName = actorName
                )
            }
        }
    }
    if (anyMutated) {
        DeviceRepository.saveTiles(context, tiles)
    }
}

/**
 * Dynamic Capability Dispatcher:
 * Executes any discovered or custom hardware capability (`DeviceCapabilityDescriptor`) on `tile`
 * (Switches, Enum Modes, Sliders, Countdown Timers, and Instant Action Triggers), stamps
 * `IntentAuthorityTier`, updates the tile's state & capability list, and dispatches the protocol
 * command with observability and causal event sourcing.
 */
fun dispatchCapabilityCommand(
    context: Context,
    tile: SmartTile,
    capability: DeviceCapabilityDescriptor,
    newValue: String,
    bridge: BridgeSettings,
    audioManager: AudioManager,
    scope: CoroutineScope,
    intentTier: IntentAuthorityTier = IntentAuthorityTier.MANUAL_USER,
    actorName: String = "Manual User",
    onTileUpdated: (SmartTile) -> Unit = {}
) {
    val canonGuid = DeviceLifecycleRecorder.registerTileAliases(tile).ifBlank { tile.id }
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        DeviceBlackBoxRecorder.traceCommand(
            deviceId = canonGuid,
            deviceName = tile.title,
            provider = resolveEffectiveProviderKey(tile),
            endpointIp = tile.ipAddress,
            action = "Capability: ${capability.label} -> $newValue",
            expectedState = "Execute $newValue",
            payloadPreview = "capability=${capability.key}"
        ) {
            dispatchCapabilityCommandInternal(context, tile, capability, newValue, bridge, audioManager, scope, intentTier, actorName, onTileUpdated)
        }
    }
}

fun dispatchCapabilityCommandInternal(
    context: Context,
    tile: SmartTile,
    capability: DeviceCapabilityDescriptor,
    newValue: String,
    bridge: BridgeSettings,
    audioManager: AudioManager,
    scope: CoroutineScope,
    intentTier: IntentAuthorityTier = IntentAuthorityTier.MANUAL_USER,
    actorName: String = "Manual User (${bridge.activeUserProfile.name})",
    onTileUpdated: (SmartTile) -> Unit = {}
) {
    val now = System.currentTimeMillis()
    val (canRun, blockedReason) = DeviceRepository.canExecuteOutboundIntent(tile, intentTier, now)
    if (!canRun) {
        JarvisLiveBridge.appendDeviceEvent(
            DeviceEvent(
                deviceId = tile.id,
                deviceTitle = tile.title,
                roomName = tile.roomOrSubtitle,
                eventType = DeviceEventType.MANUAL_HOLD_ACTIVATED,
                source = intentTier.label,
                actorName = actorName,
                stateVersion = tile.stateVersion,
                newStateSummary = blockedReason
            )
        )
        return
    }

    val cleanKey = capability.key.trim().lowercase(Locale.US)
    val cleanVal = newValue.trim()

    val updatedCaps = (tile.capabilities.ifEmpty { SmartHomeApiClient.buildDefaultCapabilitiesForTile(tile) })
        .map { cap ->
            if (cap.key.equals(capability.key, ignoreCase = true)) {
                cap.copy(currentValue = cleanVal)
            } else {
                cap
            }
        }

    var mutatedTile = DeviceRepository.stampOutboundIntentAuthority(
        tile = tile.copy(
            capabilities = updatedCaps,
            capabilitySchemaHash = CapabilitySchemaHasher.computeHash(updatedCaps),
            transactionState = CommandTransactionState.PENDING_ACK,
            lastUserActuationEpochMs = now
        ),
        intentTier = intentTier,
        actorName = actorName,
        capabilityKey = capability.key,
        nowMs = now
    )

    // Habit Shadowing: Record manual user capability actuation asynchronously off the UI thread
    if (intentTier == IntentAuthorityTier.MANUAL_USER) {
        scope.launch(Dispatchers.IO) {
            AutomationEngine.recordManualUserActuation(context, mutatedTile)
        }
    }

    val boolVal = cleanVal.equals("true", ignoreCase = true) ||
            cleanVal.equals("on", ignoreCase = true) ||
            cleanVal.equals("locked", ignoreCase = true) ||
            cleanVal.equals("charging", ignoreCase = true) ||
            cleanVal == "1"
    val floatVal = cleanVal.toFloatOrNull()

    // 1. Vehicle Capabilities (Jaguar InControl & Mercedes me)
    if (tile.isVehicleTile) {
        when (cleanKey) {
            "vehicle_lock" -> {
                if (!boolVal && isProtectedActionBlockedByParentalLock(context, bridge, tile.copy(vehicleLocked = false))) {
                    return
                }
                mutatedTile = mutatedTile.copy(vehicleLocked = boolVal, isActive = boolVal)
                onTileUpdated(mutatedTile)
                val cmdCode = if (boolVal) "VEHICLE_LOCK" else "VEHICLE_UNLOCK"
                recordDispatchCommandSent(mutatedTile, cmdCode)
                scope.launch {
                    val startMs = System.currentTimeMillis()
                    val (ok, detail) = VehicleIntegrationClient.setVehicleLocked(context, bridge, mutatedTile, boolVal)
                    val lat = (System.currentTimeMillis() - startMs).coerceAtLeast(15L)
                    JarvisLiveBridge.recordDeviceCommandResult(mutatedTile, "VEHICLES", lat, ok, if (ok) "" else detail)
                    val hasQueued = JarvisLiveBridge.hasQueuedIntentsForDevice(tile.id)
                    val resultTile = if (ok) {
                        mutatedTile.copy(
                            transactionState = CommandTransactionState.CONFIRMED,
                            lastConfirmedEpochMs = System.currentTimeMillis()
                        )
                    } else {
                        tile.copy(
                            transactionState = if (hasQueued) CommandTransactionState.SHADOW_QUEUED else CommandTransactionState.FAILED_ROLLED_BACK
                        )
                    }
                    recordDispatchCommandOutcome(mutatedTile, resultTile, ok, lat, cmdCode, if (ok) "" else detail)
                    onTileUpdated(resultTile)
                }
                return
            }
            "vehicle_climate" -> {
                mutatedTile = mutatedTile.copy(vehicleClimateActive = boolVal)
                onTileUpdated(mutatedTile)
                val cmdCode = if (boolVal) "VEHICLE_CLIMATE_ON" else "VEHICLE_CLIMATE_OFF"
                recordDispatchCommandSent(mutatedTile, cmdCode)
                scope.launch {
                    val startMs = System.currentTimeMillis()
                    val (ok, detail) = VehicleIntegrationClient.setVehicleClimate(
                        context = context,
                        bridge = bridge,
                        tile = mutatedTile,
                        active = boolVal,
                        targetTempC = mutatedTile.vehicleTargetClimateTempC
                    )
                    val lat = (System.currentTimeMillis() - startMs).coerceAtLeast(15L)
                    JarvisLiveBridge.recordDeviceCommandResult(mutatedTile, "VEHICLES", lat, ok, if (ok) "" else detail)
                    val hasQueued = JarvisLiveBridge.hasQueuedIntentsForDevice(tile.id)
                    val resultTile = if (ok) {
                        mutatedTile.copy(transactionState = CommandTransactionState.CONFIRMED)
                    } else {
                        tile.copy(
                            transactionState = if (hasQueued) CommandTransactionState.SHADOW_QUEUED else CommandTransactionState.FAILED_ROLLED_BACK
                        )
                    }
                    recordDispatchCommandOutcome(mutatedTile, resultTile, ok, lat, cmdCode, if (ok) "" else detail)
                    onTileUpdated(resultTile)
                }
                return
            }
            "vehicle_climate_temp" -> {
                val targetC = (floatVal ?: mutatedTile.vehicleTargetClimateTempC).coerceIn(16f, 28f)
                mutatedTile = mutatedTile.copy(
                    vehicleTargetClimateTempC = targetC,
                    vehicleClimateActive = true
                )
                onTileUpdated(mutatedTile)
                recordDispatchCommandSent(mutatedTile, "VEHICLE_CLIMATE_TEMP")
                scope.launch {
                    val startMs = System.currentTimeMillis()
                    val (ok, detail) = VehicleIntegrationClient.setVehicleClimate(
                        context = context,
                        bridge = bridge,
                        tile = mutatedTile,
                        active = true,
                        targetTempC = targetC
                    )
                    val lat = (System.currentTimeMillis() - startMs).coerceAtLeast(15L)
                    JarvisLiveBridge.recordDeviceCommandResult(mutatedTile, "VEHICLES", lat, ok, if (ok) "" else detail)
                    val hasQueued = JarvisLiveBridge.hasQueuedIntentsForDevice(tile.id)
                    val resultTile = if (ok) {
                        mutatedTile.copy(transactionState = CommandTransactionState.CONFIRMED)
                    } else {
                        tile.copy(
                            transactionState = if (hasQueued) CommandTransactionState.SHADOW_QUEUED else CommandTransactionState.FAILED_ROLLED_BACK
                        )
                    }
                    recordDispatchCommandOutcome(mutatedTile, resultTile, ok, lat, "VEHICLE_CLIMATE_TEMP", if (ok) "" else detail)
                    onTileUpdated(resultTile)
                }
                return
            }
            "vehicle_charging" -> {
                mutatedTile = mutatedTile.copy(vehicleIsCharging = boolVal)
                onTileUpdated(mutatedTile)
                val cmdCode = if (boolVal) "VEHICLE_CHARGE_START" else "VEHICLE_CHARGE_STOP"
                recordDispatchCommandSent(mutatedTile, cmdCode)
                scope.launch {
                    val startMs = System.currentTimeMillis()
                    val (ok, detail) = VehicleIntegrationClient.setVehicleCharging(context, bridge, mutatedTile, boolVal)
                    val lat = (System.currentTimeMillis() - startMs).coerceAtLeast(15L)
                    JarvisLiveBridge.recordDeviceCommandResult(mutatedTile, "VEHICLES", lat, ok, if (ok) "" else detail)
                    val hasQueued = JarvisLiveBridge.hasQueuedIntentsForDevice(tile.id)
                    val resultTile = if (ok) {
                        mutatedTile.copy(transactionState = CommandTransactionState.CONFIRMED)
                    } else {
                        tile.copy(
                            transactionState = if (hasQueued) CommandTransactionState.SHADOW_QUEUED else CommandTransactionState.FAILED_ROLLED_BACK
                        )
                    }
                    recordDispatchCommandOutcome(mutatedTile, resultTile, ok, lat, cmdCode, if (ok) "" else detail)
                    onTileUpdated(resultTile)
                }
                return
            }
            "vehicle_honk_flash" -> {
                onTileUpdated(mutatedTile)
                recordDispatchCommandSent(mutatedTile, "VEHICLE_HONK_FLASH")
                scope.launch {
                    val startMs = System.currentTimeMillis()
                    val (ok, detail) = VehicleIntegrationClient.triggerHonkAndFlash(context, bridge, mutatedTile)
                    val lat = (System.currentTimeMillis() - startMs).coerceAtLeast(15L)
                    JarvisLiveBridge.recordDeviceCommandResult(mutatedTile, "VEHICLES", lat, ok, if (ok) "" else detail)
                    val resultTile = mutatedTile.copy(
                        transactionState = if (ok) CommandTransactionState.CONFIRMED else CommandTransactionState.FAILED_ROLLED_BACK
                    )
                    recordDispatchCommandOutcome(mutatedTile, resultTile, ok, lat, "VEHICLE_HONK_FLASH", if (ok) "" else detail)
                    onTileUpdated(resultTile)
                }
                return
            }
        }
    }

    // 2. Capability-Driven Smart Tower Fan Capabilities
    if (tile.isTowerFanTile) {
        when (cleanKey) {
            "switch", "fan_switch", "power", "switch_fan", "ha_state" -> {
                mutatedTile = mutatedTile.copy(isActive = boolVal)
            }
            "mode", "fan_mode", "work_mode", "preset_mode" -> {
                mutatedTile = mutatedTile.copy(
                    isActive = true,
                    fanMode = cleanVal.uppercase(Locale.US)
                )
            }
            "fan_speed", "fan_speed_percent", "windspeed", "speed", "level" -> {
                val spd = (floatVal?.roundToInt() ?: cleanVal.filter { it.isDigit() }.toIntOrNull() ?: mutatedTile.fanSpeedLevel).coerceIn(1, 6)
                mutatedTile = mutatedTile.copy(
                    isActive = true,
                    fanSpeedLevel = spd,
                    volume = (spd / 6f) * 100f
                )
            }
            "switch_horizontal", "switch_vertical", "shake", "swing", "oscillate" -> {
                mutatedTile = mutatedTile.copy(
                    isActive = true,
                    fanOscillating = boolVal
                )
            }
            "anion", "ionizer", "fresh", "purifier" -> {
                mutatedTile = mutatedTile.copy(
                    isActive = true,
                    fanIonizerActive = boolVal
                )
            }
            "countdown_set", "countdown", "countdown_1", "timer" -> {
                val hrs = cleanVal.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 12) ?: 0
                mutatedTile = mutatedTile.copy(fanTimerHours = hrs)
            }
        }
        onTileUpdated(mutatedTile)
        val fanActionCode = "FAN_${cleanKey.uppercase(Locale.US)}"
        recordDispatchCommandSent(mutatedTile, fanActionCode)
        scope.launch {
            val startMs = System.currentTimeMillis()
            val ok = dispatchTowerFanHardwareUpdate(bridge, mutatedTile)
            val lat = (System.currentTimeMillis() - startMs).coerceAtLeast(6L)
            JarvisLiveBridge.recordDeviceCommandResult(mutatedTile, mutatedTile.activeProviderId.ifBlank { "TUYA_LAN_6668" }, lat, ok)
            val hasQueued = JarvisLiveBridge.hasQueuedIntentsForDevice(tile.id)
            val resultTile = if (ok) {
                mutatedTile.copy(
                    transactionState = CommandTransactionState.CONFIRMED,
                    lastConfirmedEpochMs = System.currentTimeMillis()
                )
            } else {
                tile.copy(
                    transactionState = if (hasQueued) CommandTransactionState.SHADOW_QUEUED else CommandTransactionState.FAILED_ROLLED_BACK
                )
            }
            recordDispatchCommandOutcome(
                beforeTile = mutatedTile,
                afterTile = resultTile,
                succeeded = ok,
                latencyMs = lat,
                actionCode = fanActionCode,
                failureReason = if (ok) "" else "Tower fan did not acknowledge $fanActionCode"
            )
            onTileUpdated(resultTile)
        }
        return
    }

    // 3. Capability-Driven HDMI Sync Box & Ambient RGB Capabilities
    if (tile.isAmbientRgbTile) {
        when (cleanKey) {
            "switch_led", "switch", "power" -> {
                mutatedTile = mutatedTile.copy(isActive = boolVal)
            }
            "work_mode", "mode", "sync_mode" -> {
                mutatedTile = mutatedTile.copy(
                    isActive = true,
                    lytmiSyncMode = cleanVal.uppercase(Locale.US)
                )
            }
            "hdmi_input", "input_source", "hdmi" -> {
                val parsedHdmi = cleanVal.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 4) ?: 1
                mutatedTile = mutatedTile.copy(
                    isActive = true,
                    lytmiHdmiInput = parsedHdmi
                )
            }
            "scene_select", "scene_data", "dreamlight_scene_mode" -> {
                mutatedTile = mutatedTile.copy(
                    isActive = true,
                    lytmiSyncMode = "SCENE",
                    lytmiSceneName = cleanVal
                )
            }
            "diffusion_speed", "speed", "sensitivity" -> {
                val spd = (floatVal ?: mutatedTile.lytmiDiffusionSpeed).coerceIn(1f, 100f)
                mutatedTile = mutatedTile.copy(lytmiDiffusionSpeed = spd)
            }
        }
        onTileUpdated(mutatedTile)
        val rgbActionCode = "RGB_${cleanKey.uppercase(Locale.US)}"
        recordDispatchCommandSent(mutatedTile, rgbActionCode)
        scope.launch {
            val startMs = System.currentTimeMillis()
            val ok = dispatchAmbientRgbTileUpdate(bridge, mutatedTile)
            val lat = (System.currentTimeMillis() - startMs).coerceAtLeast(6L)
            JarvisLiveBridge.recordDeviceCommandResult(mutatedTile, mutatedTile.activeProviderId.ifBlank { "GOVEE_TWINKLY_UDP" }, lat, ok)
            val hasQueued = JarvisLiveBridge.hasQueuedIntentsForDevice(tile.id)
            val resultTile = if (ok) {
                mutatedTile.copy(
                    transactionState = CommandTransactionState.CONFIRMED,
                    lastConfirmedEpochMs = System.currentTimeMillis()
                )
            } else {
                tile.copy(
                    transactionState = if (hasQueued) CommandTransactionState.SHADOW_QUEUED else CommandTransactionState.FAILED_ROLLED_BACK
                )
            }
            recordDispatchCommandOutcome(
                beforeTile = mutatedTile,
                afterTile = resultTile,
                succeeded = ok,
                latencyMs = lat,
                actionCode = rgbActionCode,
                failureReason = if (ok) "" else "Ambient RGB device did not acknowledge $rgbActionCode"
            )
            onTileUpdated(resultTile)
        }
        return
    }

    // 4. Speaker & Audio Capabilities (Keep PENDING_ACK until Cast TLS 8009 acknowledges)
    if (tile.isSpeakerTile) {
        if (cleanKey == "speaker_volume" && floatVal != null) {
            mutatedTile = mutatedTile.copy(
                volume = floatVal.coerceIn(0f, 100f),
                isActive = floatVal > 0.5f,
                transactionState = CommandTransactionState.PENDING_ACK
            )
            onTileUpdated(mutatedTile)
            dispatchDeviceUpdate(
                updated = mutatedTile,
                bridge = bridge,
                audioManager = audioManager,
                scope = scope,
                intentTier = intentTier,
                actorName = actorName
            ) { finalTile, _, _ ->
                onTileUpdated(finalTile)
            }
            return
        } else if (cleanKey == "speaker_chime") {
            onTileUpdated(mutatedTile.copy(transactionState = CommandTransactionState.PENDING_ACK))
            recordDispatchCommandSent(mutatedTile, "CAST_SPEAKER_CHIME")
            scope.launch(Dispatchers.IO) {
                val startMs = System.currentTimeMillis()
                val endpoints = GoogleCastLanClient.resolveEndpointsForTile(mutatedTile)
                val reachable = endpoints.any { (ip, port) ->
                    GoogleCastLanClient.queryCastDeviceStatus(ip, port) != null
                }
                if (reachable) {
                    GoogleCastLanClient.playDoorbellChimeOnNestMinis(
                        scope = scope,
                        bridge = bridge,
                        tiles = listOf(mutatedTile),
                        customAnnouncementText = bridge.doorbellChimeMessage
                    )
                }
                val lat = (System.currentTimeMillis() - startMs).coerceAtLeast(8L)
                val resultTile = if (reachable) {
                    mutatedTile.copy(
                        transactionState = CommandTransactionState.CONFIRMED,
                        stateAuthorityTier = StateAuthorityTier.DEVICE_HARDWARE_TRUTH,
                        lastConfirmedEpochMs = System.currentTimeMillis()
                    )
                } else {
                    tile.copy(transactionState = CommandTransactionState.FAILED_ROLLED_BACK)
                }
                recordDispatchCommandOutcome(
                    beforeTile = mutatedTile,
                    afterTile = resultTile,
                    succeeded = reachable,
                    latencyMs = lat,
                    actionCode = "CAST_SPEAKER_CHIME",
                    failureReason = if (reachable) "" else "Cast TLS 8009 speaker unreachable"
                )
                withContext(Dispatchers.Main) {
                    onTileUpdated(resultTile)
                }
            }
            return
        }
    }

    // 5. Standard Multi-Protocol & Tuya / Home Assistant Capabilities
    when {
        cleanKey in listOf("switch", "switch_1", "switch1", "power", "switch_led", "ha_state") -> {
            mutatedTile = mutatedTile.copy(isActive = boolVal)
            onTileUpdated(mutatedTile)
            dispatchDeviceUpdate(
                updated = mutatedTile,
                bridge = bridge,
                audioManager = audioManager,
                scope = scope,
                singleSocketNumber = if (mutatedTile.isMultiGangSwitch) 1 else null,
                intentTier = intentTier,
                actorName = actorName
            ) { finalTile, _, _ ->
                onTileUpdated(finalTile)
            }
        }
        cleanKey in listOf("switch_2", "switch2", "socket_2") -> {
            mutatedTile = mutatedTile.withUpdatedSocketState(2, boolVal)
            onTileUpdated(mutatedTile)
            dispatchDeviceUpdate(
                updated = mutatedTile,
                bridge = bridge,
                audioManager = audioManager,
                scope = scope,
                singleSocketNumber = 2,
                intentTier = intentTier,
                actorName = actorName
            ) { finalTile, _, _ ->
                onTileUpdated(finalTile)
            }
        }
        // F-13: Dynamic N-gang outlet dispatch for socket_3..socket_N / switch_3..switch_N
        cleanKey.matches(Regex("^(?:socket|switch)_([3-9]|\\d{2,})$")) -> {
            val outletNumber = cleanKey.substringAfterLast("_").toIntOrNull() ?: 3
            mutatedTile = mutatedTile.withUpdatedSocketState(outletNumber, boolVal)
            onTileUpdated(mutatedTile)
            dispatchDeviceUpdate(
                updated = mutatedTile,
                bridge = bridge,
                audioManager = audioManager,
                scope = scope,
                singleSocketNumber = outletNumber,
                intentTier = intentTier,
                actorName = actorName
            ) { finalTile, _, _ ->
                onTileUpdated(finalTile)
            }
        }
        cleanKey in listOf("countdown_1", "countdown", "countdown_2") -> {
            val isSocket2Timer = cleanKey == "countdown_2"
            val secs = (floatVal?.roundToInt() ?: 0).coerceIn(0, 86400)
            mutatedTile = if (isSocket2Timer) {
                mutatedTile.copy(
                    countdown2Seconds = secs,
                    transactionState = CommandTransactionState.PENDING_ACK
                )
            } else {
                mutatedTile.copy(
                    countdown1Seconds = secs,
                    transactionState = CommandTransactionState.PENDING_ACK
                )
            }
            onTileUpdated(mutatedTile)
            val timerActionCode = cleanKey.uppercase(Locale.US)
            recordDispatchCommandSent(mutatedTile, timerActionCode)
            val devId = mutatedTile.serialNumber.ifBlank { mutatedTile.entityId }.trim()
            scope.launch(Dispatchers.IO) {
                val startMs = System.currentTimeMillis()
                val lanOk = if (devId.isNotBlank() && !devId.contains(".")) {
                    if (capability.dpId.isNotBlank()) {
                        TuyaApiClient.sendLocalLanDpCommand(
                            deviceId = devId,
                            ipAddress = mutatedTile.ipAddress,
                            localKey = mutatedTile.secretCode,
                            dpsPayload = JSONObject().put(capability.dpId, secs)
                        )
                    } else {
                        TuyaApiClient.sendNativePlugCountdownCommand(
                            deviceId = devId,
                            socketNumber = if (isSocket2Timer) 2 else 1,
                            countdownSeconds = secs,
                            ipAddress = mutatedTile.ipAddress,
                            localKey = mutatedTile.secretCode
                        )
                    }
                } else false
                val cloudOk = if (!lanOk && devId.isNotBlank() && !devId.contains(".")) {
                    val codeKey = if (isSocket2Timer) "countdown_2" else "countdown_1"
                    val cmds = JSONArray().put(JSONObject().put("code", codeKey).put("value", secs))
                    TuyaApiClient.sendCommands(bridge, devId, cmds)
                } else false
                val sentOk = lanOk || cloudOk
                val lat = (System.currentTimeMillis() - startMs).coerceAtLeast(6L)
                val resolvedAuth = resolveVerifiedTransportAuthorityTier(mutatedTile, usedLocalLanTransport = lanOk)
                val resultTile = if (sentOk) {
                    mutatedTile.copy(
                        transactionState = CommandTransactionState.CONFIRMED,
                        stateAuthorityTier = resolvedAuth,
                        lastConfirmedEpochMs = System.currentTimeMillis()
                    )
                } else {
                    tile.copy(transactionState = CommandTransactionState.FAILED_ROLLED_BACK)
                }
                recordDispatchCommandOutcome(
                    beforeTile = mutatedTile,
                    afterTile = resultTile,
                    succeeded = sentOk,
                    latencyMs = lat,
                    actionCode = timerActionCode,
                    failureReason = if (sentOk) "" else "Countdown timer command timed out"
                )
                withContext(Dispatchers.Main) {
                    onTileUpdated(resultTile)
                }
            }
        }
        cleanKey in listOf("target_temperature", "temp_set", "set_temp") && floatVal != null -> {
            mutatedTile = mutatedTile.copy(
                targetTemperature = floatVal,
                isActive = true,
                hvacMode = "HEAT",
                hvacStatus = if (floatVal > mutatedTile.temperature) "HEATING" else "OFF"
            )
            onTileUpdated(mutatedTile)
            dispatchDeviceUpdate(mutatedTile, bridge, audioManager, scope, intentTier = intentTier, actorName = actorName) { finalTile, _, _ ->
                onTileUpdated(finalTile)
            }
        }
        cleanKey == "hvac_mode" -> {
            val modeUpper = cleanVal.uppercase(Locale.US)
            mutatedTile = mutatedTile.copy(
                hvacMode = modeUpper,
                isActive = modeUpper != "OFF",
                hvacStatus = if (modeUpper in listOf("HEAT", "BOOST") && mutatedTile.targetTemperature > mutatedTile.temperature) "HEATING" else "OFF"
            )
            onTileUpdated(mutatedTile)
            dispatchDeviceUpdate(mutatedTile, bridge, audioManager, scope, intentTier = intentTier, actorName = actorName) { finalTile, _, _ ->
                onTileUpdated(finalTile)
            }
        }
        cleanKey in listOf("brightness", "brightness_pct", "bright_value", "bright_value_v2", "position") && floatVal != null -> {
            mutatedTile = mutatedTile.copy(
                volume = floatVal.coerceIn(0f, 100f),
                isActive = floatVal > 0f
            )
            onTileUpdated(mutatedTile)
            dispatchDeviceUpdate(mutatedTile, bridge, audioManager, scope, intentTier = intentTier, actorName = actorName) { finalTile, _, _ ->
                onTileUpdated(finalTile)
            }
        }
        else -> {
            onTileUpdated(mutatedTile)
            val capActionCode = "CAP_${cleanKey.uppercase(Locale.US)}"
            recordDispatchCommandSent(mutatedTile, capActionCode)
            val devId = mutatedTile.serialNumber.ifBlank { mutatedTile.entityId }.trim()
            scope.launch(Dispatchers.IO) {
                val startMs = System.currentTimeMillis()
                var sentOk = false
                if (devId.isNotBlank() && !devId.contains(".")) {
                    if (mutatedTile.ipAddress.isNotBlank()) {
                        TuyaApiClient.registerDeviceLanAddress(devId, mutatedTile.ipAddress, mutatedTile.secretCode)
                    }
                    val typedValue: Any = when (capability.kind) {
                        DeviceCapabilityKind.BOOLEAN_SWITCH -> boolVal
                        DeviceCapabilityKind.NUMERIC_SLIDER,
                        DeviceCapabilityKind.COUNTDOWN_TIMER -> (floatVal ?: 0f).roundToInt()
                        else -> cleanVal
                    }
                    val dpKey = capability.dpId.ifBlank { capability.key }
                    val lanOk = if (capability.dpId.isNotBlank()) {
                        TuyaApiClient.sendLocalLanDpCommand(
                            deviceId = devId,
                            ipAddress = mutatedTile.ipAddress,
                            localKey = mutatedTile.secretCode,
                            dpsPayload = JSONObject().put(capability.dpId, typedValue)
                        )
                    } else false
                    sentOk = if (!lanOk) {
                        val cmds = JSONArray().put(
                            JSONObject().put("code", dpKey).put("value", typedValue)
                        )
                        TuyaApiClient.sendCommands(bridge, devId, cmds)
                    } else {
                        true
                    }
                } else if (mutatedTile.entityId.contains(".") && bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()) {
                    val domain = mutatedTile.entityId.substringBefore(".")
                    sentOk = when (cleanKey) {
                        "preset_mode" -> SmartHomeApiClient.callHomeAssistantService(
                            haBaseUrl = bridge.haUrl,
                            haToken = bridge.haToken,
                            domain = domain,
                            service = "set_preset_mode",
                            payloadJson = JSONObject().put("entity_id", mutatedTile.entityId).put("preset_mode", cleanVal)
                        )
                        "fan_mode" -> SmartHomeApiClient.callHomeAssistantService(
                            haBaseUrl = bridge.haUrl,
                            haToken = bridge.haToken,
                            domain = domain,
                            service = "set_fan_mode",
                            payloadJson = JSONObject().put("entity_id", mutatedTile.entityId).put("fan_mode", cleanVal)
                        )
                        "effect", "light_effect" -> SmartHomeApiClient.callHomeAssistantService(
                            haBaseUrl = bridge.haUrl,
                            haToken = bridge.haToken,
                            domain = "light",
                            service = "turn_on",
                            payloadJson = JSONObject().put("entity_id", mutatedTile.entityId).put("effect", cleanVal)
                        )
                        "source" -> SmartHomeApiClient.callHomeAssistantService(
                            haBaseUrl = bridge.haUrl,
                            haToken = bridge.haToken,
                            domain = "media_player",
                            service = "select_source",
                            payloadJson = JSONObject().put("entity_id", mutatedTile.entityId).put("source", cleanVal)
                        )
                        else -> false
                    }
                } else if (mutatedTile.activeProviderId.contains("HUBITAT", ignoreCase = true)) {
                    val cmd = when (capability.kind) {
                        DeviceCapabilityKind.BOOLEAN_SWITCH -> if (boolVal) "on" else "off"
                        DeviceCapabilityKind.NUMERIC_SLIDER -> "setLevel"
                        else -> cleanKey
                    }
                    val sec = if (capability.kind == DeviceCapabilityKind.NUMERIC_SLIDER) {
                        (floatVal ?: 0f).roundToInt().toString()
                    } else ""
                    sentOk = SmartHomeApiClient.sendHubitatCommand(bridge, mutatedTile, cmd, sec)
                } else if (mutatedTile.activeProviderId.contains("HOMEY", ignoreCase = true)) {
                    val homeyCap = when (cleanKey) {
                        "switch", "power" -> "onoff"
                        "brightness" -> "dim"
                        else -> cleanKey
                    }
                    val homeyVal: Any = when (homeyCap) {
                        "onoff" -> boolVal
                        "dim" -> ((floatVal ?: 100f).coerceIn(0f, 100f) / 100.0)
                        else -> floatVal ?: cleanVal
                    }
                    sentOk = SmartHomeApiClient.sendHomeyCommand(bridge, mutatedTile, homeyCap, homeyVal)
                }
                val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(8L)
                JarvisLiveBridge.recordCommandLatency(
                    integrationKey = "MULTI_PROTOCOL",
                    displayName = "Matter / Tapo / Shelly / MQTT / HA",
                    latencyMs = latency,
                    succeeded = sentOk
                )
                val failDetail = if (sentOk) "" else "Capability '${capability.label}' did not acknowledge"
                JarvisLiveBridge.recordDeviceCommandResult(
                    tile = mutatedTile,
                    providerId = mutatedTile.activeProviderId.ifBlank { mutatedTile.type.brandName },
                    latencyMs = latency,
                    succeeded = sentOk,
                    failureReason = failDetail
                )
                val resolvedCapAuth = resolveVerifiedTransportAuthorityTier(mutatedTile)
                val hasQueued = JarvisLiveBridge.hasQueuedIntentsForDevice(tile.id)
                val resultTile = if (sentOk) {
                    mutatedTile.copy(
                        transactionState = CommandTransactionState.CONFIRMED,
                        stateAuthorityTier = resolvedCapAuth,
                        lastConfirmedEpochMs = System.currentTimeMillis()
                    )
                } else {
                    tile.copy(
                        transactionState = if (hasQueued) CommandTransactionState.SHADOW_QUEUED else CommandTransactionState.FAILED_ROLLED_BACK
                    )
                }
                recordDispatchCommandOutcome(
                    beforeTile = mutatedTile,
                    afterTile = resultTile,
                    succeeded = sentOk,
                    latencyMs = latency,
                    actionCode = capActionCode,
                    failureReason = failDetail
                )
                withContext(Dispatchers.Main) {
                    onTileUpdated(resultTile)
                }
            }
        }
    }
}

/**
 * Closed-Loop Hardware Dispatcher (Criterion 11 — Zero False Success States):
 * - Enforces Parental Lock RBAC and Stage 4 Outbound Intent Authority (`canExecuteOutboundIntent`).
 * - Stamps the 30-minute manual hold window (`stampOutboundIntentAuthority`) for manual/voice actions.
 * - Executes protocol commands, verifies hardware acknowledgment, records per-device observability
 *   and causal `DeviceEvent` entries, and invokes Stage 0–7 Autonomous Self-Healing on failure.
 */
fun dispatchDeviceUpdate(
    updated: SmartTile,
    bridge: BridgeSettings,
    audioManager: AudioManager,
    scope: CoroutineScope,
    singleSocketNumber: Int? = null,
    pinAuthorized: Boolean = false,
    intentTier: IntentAuthorityTier = IntentAuthorityTier.MANUAL_USER,
    actorName: String = "Manual User (${bridge.activeUserProfile.name})",
    onTransactionResult: ((SmartTile, Boolean, String) -> Unit)? = null
) {
    val ctx = resolveAppContext()
    val nowMs = System.currentTimeMillis()

    // 1. Stage 4 Outbound Intent Authority Check (30-minute Manual/Physical Hold & 60-minute Safety Hold)
    val (canExecute, holdBlockedReason) = DeviceRepository.canExecuteOutboundIntent(
        tile = updated,
        intentTier = intentTier,
        nowMs = nowMs
    )
    if (!canExecute) {
        JarvisLiveBridge.appendDeviceEvent(
            DeviceEvent(
                deviceId = updated.id,
                deviceTitle = updated.title,
                roomName = updated.roomOrSubtitle,
                eventType = DeviceEventType.MANUAL_HOLD_ACTIVATED,
                source = intentTier.label,
                actorName = actorName,
                stateVersion = updated.stateVersion,
                newStateSummary = holdBlockedReason
            )
        )
        onTransactionResult?.invoke(updated, false, holdBlockedReason)
        return
    }

    // 2. Parental Lock & RBAC Shield
    if (isProtectedActionBlockedByParentalLock(ctx, bridge, updated, pinAuthorized)) {
        val blockedTile = updated.copy(
            vehicleLocked = if (updated.isVehicleTile) true else updated.vehicleLocked,
            isActive = if (updated.deviceSubtype == DeviceSubtype.LOCK || updated.hasLockCapability || updated.isVehicleTile) true else updated.isActive,
            transactionState = CommandTransactionState.FAILED_ROLLED_BACK
        )
        onTransactionResult?.invoke(blockedTile, false, "Blocked by Parental Lock PIN shield.")
        return
    }

    // F-04 & F-21: Mark 15-second PENDING_ACK lock; 30-minute manual hold is persisted on SmartTile.manualHoldUntilEpochMs
    DeviceRepository.markTilePendingAck(updated.id)

    // Stamp outbound intent authority & 30-minute manual hold window
    val stampedTile = DeviceRepository.stampOutboundIntentAuthority(
        tile = updated,
        intentTier = intentTier,
        actorName = actorName,
        nowMs = nowMs
    )

    // Immediate non-network audio/UI updates
    if (stampedTile.deviceSubtype == DeviceSubtype.MUSIC_PLAYER || stampedTile.type == TileType.MUSIC_PLAYER) {
        setSystemVolumePercent(audioManager, stampedTile.volume)
        ClaraInAppMusicPlayer.setVolumePercent(stampedTile.volume)
        val castTargets = ClaraInAppMusicPlayer.selectedCastEndpoints
        if (castTargets.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                castTargets.forEach { (ip, port) ->
                    GoogleCastLanClient.setCastDeviceVolume(ip, port, stampedTile.volume)
                }
            }
        }
        onTransactionResult?.invoke(
            stampedTile.copy(
                transactionState = CommandTransactionState.CONFIRMED,
                lastConfirmedEpochMs = nowMs
            ),
            true,
            "Updated media volume."
        )
        return
    }

    if (stampedTile.isSecurityCenterTile) {
        JarvisLiveBridge.securityCenterModeFlow.value = stampedTile.securityMode
        if (ctx != null) {
            DeviceRepository.saveBridge(ctx, bridge.copy(securityCenterMode = stampedTile.securityMode))
        }
        JarvisLiveBridge.emitPlatformEvent(
            JarvisPlatformEvent(
                category = JarvisEventCategory.SECURITY_MODE,
                sourceDeviceId = stampedTile.id,
                sourceDeviceTitle = stampedTile.title,
                eventType = stampedTile.securityMode.name,
                summary = "Security Center set to ${stampedTile.securityMode.label} mode"
            )
        )
        onTransactionResult?.invoke(
            stampedTile.copy(
                transactionState = CommandTransactionState.CONFIRMED,
                lastConfirmedEpochMs = nowMs
            ),
            true,
            "Security Center set to ${stampedTile.securityMode.label}."
        )
        return
    }

    // Record telemetry data point & Habit Shadowing actuation on background IO thread
    if (ctx != null) {
        scope.launch(Dispatchers.IO) {
            if (intentTier == IntentAuthorityTier.MANUAL_USER) {
                AutomationEngine.recordManualUserActuation(ctx, stampedTile)
            }
            if (stampedTile.hasPowerTelemetryCapability && stampedTile.powerWatts > 0f) {
                DeviceRepository.recordTelemetryPoint(
                    context = ctx,
                    point = TelemetryDataPoint(
                        deviceId = stampedTile.id,
                        deviceTitle = stampedTile.title,
                        metricKey = "power_w",
                        value = stampedTile.powerWatts,
                        unit = "W"
                    )
                )
            }
        }
    }

    val stampedProviderKey = resolveEffectiveProviderKey(stampedTile)
    val lowerStampedTitle = stampedTile.title.trim().lowercase(Locale.US)
    val isNonHeaterPlugOrAppliance = lowerStampedTitle.contains("plug") ||
            lowerStampedTitle.contains("socket") ||
            lowerStampedTitle.contains("pond") ||
            lowerStampedTitle.contains("pump") ||
            lowerStampedTitle.contains("kettle") ||
            lowerStampedTitle.contains("fryer") ||
            lowerStampedTitle.contains("coffee") ||
            lowerStampedTitle.contains("washer")

    // Debounced path for Google Nest SDM Thermostat sliders with strict ACK verification
    if (!isNonHeaterPlugOrAppliance && stampedTile.isThermostatTile && (stampedProviderKey.contains("NEST") || stampedTile.entityId.startsWith("enterprises/"))) {
        val nestCanonGuid = DeviceLifecycleRecorder.registerTileAliases(stampedTile).ifBlank { stampedTile.id }
        val nestTargetSummary = summarizeTileTargetState(stampedTile)
        val nestActionCode = "NEST_SETPOINT_${stampedTile.hvacMode.uppercase(Locale.US)}"
        recordDispatchCommandSent(stampedTile, nestActionCode)
        DeviceBlackBoxRecorder.logEvent(
            DeviceTraceEvent(
                deviceId = nestCanonGuid,
                deviceName = stampedTile.title,
                provider = stampedTile.activeProviderId.ifBlank { "GOOGLE_NEST" },
                category = DeviceTraceCategory.UI_ACTION,
                action = nestActionCode,
                outcome = TraceOutcome.SUCCESS,
                expectedState = nestTargetSummary,
                domain = BlackBoxDomain.COMMAND
            )
        )
        val startMs = System.currentTimeMillis()
        NestSdmApiClient.enqueueDebouncedThermostatUpdate(
            scope = scope,
            bridge = bridge,
            updated = stampedTile
        ) { ackOk ->
            val lat = (System.currentTimeMillis() - startMs).coerceAtLeast(15L)
            val providerId = stampedTile.activeProviderId.ifBlank { "NEST_CLOUD" }
            val failReason = if (ackOk) "" else "Nest Thermostat did not acknowledge setpoint"
            DeviceBlackBoxRecorder.logEvent(
                DeviceTraceEvent(
                    deviceId = nestCanonGuid,
                    deviceName = stampedTile.title,
                    provider = providerId,
                    category = if (ackOk) DeviceTraceCategory.COMMAND_TX else DeviceTraceCategory.TRANSPORT_RX,
                    action = nestActionCode,
                    outcome = if (ackOk) TraceOutcome.SUCCESS else TraceOutcome.FAILED,
                    latencyMs = lat,
                    expectedState = nestTargetSummary,
                    actualState = if (ackOk) nestTargetSummary else "SHADOW_QUEUED",
                    errorSummary = failReason,
                    domain = if (ackOk) BlackBoxDomain.COMMAND else BlackBoxDomain.FAILURE
                )
            )
            JarvisLiveBridge.recordDeviceCommandResult(
                tile = stampedTile,
                providerId = providerId,
                latencyMs = lat,
                succeeded = ackOk,
                failureReason = failReason
            )
            if (ackOk) {
                val nextVer = stampedTile.stateVersion + 1L
                JarvisLiveBridge.appendDeviceEvent(
                    DeviceEvent(
                        deviceId = stampedTile.id,
                        deviceTitle = stampedTile.title,
                        roomName = stampedTile.roomOrSubtitle,
                        eventType = DeviceEventType.INTENT_CONFIRMED,
                        source = "Google Nest",
                        actorName = actorName,
                        stateVersion = nextVer,
                        authorityTierLabel = StateAuthorityTier.CLOUD_SHADOW_STATE.label,
                        newStateSummary = summarizeTileTargetState(stampedTile)
                    )
                )
                val confirmedNestTile = stampedTile.copy(
                    isOnline = true,
                    lifecycleState = DeviceLifecycleState.ONLINE,
                    transactionState = CommandTransactionState.CONFIRMED,
                    stateVersion = nextVer,
                    stateOriginEpochMs = System.currentTimeMillis(),
                    stateAuthorityTier = StateAuthorityTier.CLOUD_SHADOW_STATE,
                    lastConfirmedEpochMs = System.currentTimeMillis()
                )
                recordDispatchCommandOutcome(stampedTile, confirmedNestTile, true, lat, nestActionCode)
                onTransactionResult?.invoke(
                    confirmedNestTile,
                    true,
                    "Updated ${stampedTile.title}."
                )
            } else {
                JarvisLiveBridge.enqueueShadowIntent(
                    DigitalTwinShadowIntent(
                        tileId = stampedTile.id,
                        tileTitle = stampedTile.title,
                        desiredIsActive = stampedTile.isActive,
                        desiredTargetTempC = stampedTile.targetTemperature,
                        desiredMode = stampedTile.hvacMode,
                        capabilityKey = "target_temperature",
                        intentAuthorityTier = intentTier,
                        actorName = actorName
                    )
                )
                JarvisLiveBridge.appendDeviceEvent(
                    DeviceEvent(
                        deviceId = stampedTile.id,
                        deviceTitle = stampedTile.title,
                        roomName = stampedTile.roomOrSubtitle,
                        eventType = DeviceEventType.INTENT_ROLLED_BACK,
                        source = "Google Nest",
                        actorName = actorName,
                        stateVersion = stampedTile.stateVersion,
                        newStateSummary = "Unconfirmed Nest setpoint \u2014 Queued for Wake"
                    )
                )
                val failedNestTile = stampedTile.copy(
                    lifecycleState = DeviceLifecycleState.QUARANTINED_ERROR,
                    transactionState = CommandTransactionState.SHADOW_QUEUED
                )
                recordDispatchCommandOutcome(stampedTile, failedNestTile, false, lat, nestActionCode, failReason)
                onTransactionResult?.invoke(
                    failedNestTile,
                    false,
                    "Could not confirm setpoint on ${stampedTile.title}."
                )
            }
        }
        JarvisLiveBridge.emitPlatformEvent(
            JarvisPlatformEvent(
                category = JarvisEventCategory.CLIMATE,
                sourceDeviceId = stampedTile.id,
                sourceDeviceTitle = stampedTile.title,
                roomName = stampedTile.roomOrSubtitle,
                eventType = stampedTile.hvacMode,
                summary = "${stampedTile.title} set to ${stampedTile.hvacMode} (${"%.1f".format(stampedTile.targetTemperature)}\u00B0C)"
            )
        )
        return
    }

    // Full closed-loop dispatch with automatic Stage 0–7 self-healing & shadow queuing
    if (ctx != null) {
        val dispatchActionCode = if (singleSocketNumber != null) {
            "SOCKET_${singleSocketNumber}_${if (stampedTile.isSocketActive(singleSocketNumber)) "ON" else "OFF"}"
        } else {
            "STATE_${if (stampedTile.isActive) "ON" else "OFF"}"
        }
        recordDispatchCommandSent(stampedTile, dispatchActionCode)
        scope.launch {
            val startMs = System.currentTimeMillis()
            val (ok, detail) = dispatchDeviceUpdateWithDiagnostic(
                context = ctx,
                updated = stampedTile,
                bridge = bridge,
                audioManager = audioManager,
                scope = scope,
                singleSocketNumber = singleSocketNumber,
                pinAuthorized = pinAuthorized,
                intentTier = intentTier,
                actorName = actorName
            )
            val latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(5L)
            val providerId = stampedTile.activeProviderId.ifBlank { stampedTile.type.brandName }

            JarvisLiveBridge.recordDeviceCommandResult(
                tile = stampedTile,
                providerId = providerId,
                latencyMs = latencyMs,
                succeeded = ok,
                failureReason = if (ok) "" else detail
            )

            val healedLiveTile = UniversalOnboardingEngine.activeDashboardTilesRef
                ?.firstOrNull { it.id == stampedTile.id }

            val confirmedTile = if (ok) {
                DeviceRepository.confirmTileHardwareAck(stampedTile.id)
                JarvisLiveBridge.consumeShadowIntentsForDevice(stampedTile.id)
                val nextVer = stampedTile.stateVersion + 1L
                val targetSummary = summarizeTileTargetState(stampedTile, singleSocketNumber)
                val verifiedAuthTier = resolveVerifiedTransportAuthorityTier(stampedTile)
                JarvisLiveBridge.appendDeviceEvent(
                    DeviceEvent(
                        deviceId = stampedTile.id,
                        deviceTitle = stampedTile.title,
                        roomName = stampedTile.roomOrSubtitle,
                        eventType = DeviceEventType.INTENT_CONFIRMED,
                        source = providerId,
                        actorName = actorName,
                        stateVersion = nextVer,
                        authorityTierLabel = verifiedAuthTier.label,
                        newStateSummary = targetSummary
                    )
                )
                stampedTile.copy(
                    ipAddress = healedLiveTile?.ipAddress?.ifBlank { stampedTile.ipAddress } ?: stampedTile.ipAddress,
                    activeProviderId = healedLiveTile?.activeProviderId?.ifBlank { stampedTile.activeProviderId } ?: stampedTile.activeProviderId,
                    macAddress = healedLiveTile?.macAddress?.ifBlank { stampedTile.macAddress } ?: stampedTile.macAddress,
                    isOnline = true,
                    lifecycleState = DeviceLifecycleState.ONLINE,
                    transactionState = CommandTransactionState.CONFIRMED,
                    stateVersion = nextVer,
                    stateOriginEpochMs = System.currentTimeMillis(),
                    stateAuthorityTier = verifiedAuthTier,
                    lastConfirmedEpochMs = System.currentTimeMillis()
                )
            } else {
                DeviceRepository.confirmTileHardwareAck(stampedTile.id)
                // Stage 7: Queue desired state in Coalescing Intent Queue for automatic replay on wake/reconnect
                JarvisLiveBridge.enqueueShadowIntent(
                    DigitalTwinShadowIntent(
                        tileId = stampedTile.id,
                        tileTitle = stampedTile.title,
                        desiredIsActive = stampedTile.isActive,
                        desiredIsSocket2Active = stampedTile.isSocket2Active,
                        desiredTargetTempC = if (stampedTile.isThermostatTile) stampedTile.targetTemperature else null,
                        desiredVolumeOrLevel = stampedTile.volume,
                        desiredMode = stampedTile.hvacMode,
                        capabilityKey = if (singleSocketNumber != null) "socket_$singleSocketNumber" else "state",
                        intentAuthorityTier = intentTier,
                        actorName = actorName
                    )
                )
                JarvisLiveBridge.appendDeviceEvent(
                    DeviceEvent(
                        deviceId = stampedTile.id,
                        deviceTitle = stampedTile.title,
                        roomName = stampedTile.roomOrSubtitle,
                        eventType = DeviceEventType.INTENT_ROLLED_BACK,
                        source = providerId,
                        actorName = actorName,
                        stateVersion = stampedTile.stateVersion,
                        newStateSummary = "Unconfirmed ($detail) \u2014 Rolled Back & Queued for Wake"
                    )
                )
                stampedTile.copy(
                    lifecycleState = DeviceLifecycleState.QUARANTINED_ERROR,
                    transactionState = CommandTransactionState.FAILED_ROLLED_BACK
                )
            }
            recordDispatchCommandOutcome(
                beforeTile = stampedTile,
                afterTile = confirmedTile,
                succeeded = ok,
                latencyMs = latencyMs,
                actionCode = dispatchActionCode,
                failureReason = if (ok) "" else detail
            )
            onTransactionResult?.invoke(confirmedTile, ok, detail)
        }
    }
}

/**
 * Dispatches a device update for a manual tap or an Automation Rule step with automatic background
 * identifier resolution, multipath failover, Stage 4 intent authority arbitration, and Stage 0–7
 * autonomous self-healing, returning `(succeeded, plainEnglishReason)`.
 */
suspend fun dispatchDeviceUpdateWithDiagnostic(
    context: Context,
    updated: SmartTile,
    bridge: BridgeSettings,
    audioManager: AudioManager,
    scope: CoroutineScope,
    singleSocketNumber: Int? = null,
    pinAuthorized: Boolean = false,
    intentTier: IntentAuthorityTier = IntentAuthorityTier.MANUAL_USER,
    actorName: String = "Manual User"
): Pair<Boolean, String> {
    val canonGuid = DeviceLifecycleRecorder.registerTileAliases(updated).ifBlank { updated.id }
    return DeviceBlackBoxRecorder.traceCommand(
        deviceId = canonGuid,
        deviceName = updated.title,
        provider = resolveEffectiveProviderKey(updated),
        endpointIp = updated.ipAddress,
        action = "Intent: ${if (singleSocketNumber != null) "Socket $singleSocketNumber" else "Main"}",
        expectedState = summarizeTileTargetState(updated, singleSocketNumber),
        payloadPreview = "isSwitch=${updated.isSwitchTile} isActive=${updated.isActive} vol=${updated.volume}"
    ) {
        dispatchDeviceUpdateWithDiagnosticInternal(
            context, updated, bridge, audioManager, scope, singleSocketNumber, pinAuthorized, intentTier, actorName
        )
    }.getOrElse {
        false to (it.message ?: "Unknown crash during execution")
    }
}

suspend fun dispatchDeviceUpdateWithDiagnosticInternal(
    context: Context,
    updated: SmartTile,
    bridge: BridgeSettings,
    audioManager: AudioManager,
    scope: CoroutineScope,
    singleSocketNumber: Int? = null,
    pinAuthorized: Boolean = false,
    intentTier: IntentAuthorityTier = IntentAuthorityTier.MANUAL_USER,
    actorName: String = "Manual User",
    allowRecoveryRetry: Boolean = true
): Pair<Boolean, String> {
    val (canExecute, holdBlockedReason) = DeviceRepository.canExecuteOutboundIntent(
        tile = updated,
        intentTier = intentTier
    )
    if (!canExecute) {
        return false to holdBlockedReason
    }

    if (isProtectedActionBlockedByParentalLock(context, bridge, updated, pinAuthorized)) {
        return false to "Action blocked by Parental Lock PIN shield."
    }

    if (updated.isVehicleTile) {
        return when (updated.hvacMode.uppercase(Locale.US)) {
            "VEHICLE_CLIMATE" -> VehicleIntegrationClient.setVehicleClimate(
                context = context,
                bridge = bridge,
                tile = updated,
                active = updated.vehicleClimateActive,
                targetTempC = updated.vehicleTargetClimateTempC
            )
            "VEHICLE_CHARGE" -> VehicleIntegrationClient.setVehicleCharging(
                context = context,
                bridge = bridge,
                tile = updated,
                enableCharging = updated.vehicleIsCharging
            )
            else -> VehicleIntegrationClient.setVehicleLocked(
                context = context,
                bridge = bridge,
                tile = updated,
                locked = updated.vehicleLocked
            )
        }
    }

    val activeSubtype = updated.deviceSubtype
    val providerKey = resolveEffectiveProviderKey(updated)

    if (activeSubtype == DeviceSubtype.AMBIENT_SYNC || updated.isAmbientRgbTile) {
        val ok = dispatchAmbientRgbTileUpdate(bridge, updated)
        return ok to if (ok) {
            "Updated '${updated.title}' (${if (updated.isActive) "${updated.lytmiSyncMode} \u2022 ${updated.volume.roundToInt()}%" else "OFF"})."
        } else {
            "Could not reach ambient light '${updated.title}' on Wi-Fi or Cloud."
        }
    }

    if (activeSubtype == DeviceSubtype.TOWER_FAN || updated.isTowerFanTile) {
        val ok = dispatchTowerFanHardwareUpdate(bridge, updated)
        return ok to if (ok) {
            "Updated fan '${updated.title}' (${if (updated.isActive) "Speed ${updated.fanSpeedLevel} \u2022 ${updated.fanMode}" else "OFF"})."
        } else {
            "Could not reach fan '${updated.title}' on Wi-Fi."
        }
    }

    if (activeSubtype == DeviceSubtype.VACUUM) {
        val ok = dispatchRobovacHardwareUpdate(bridge, updated)
        return ok to if (ok) {
            "Sent '${updated.hvacMode}' command to '${updated.title}'."
        } else {
            "Could not reach robot vacuum '${updated.title}' on Wi-Fi."
        }
    }

    var devId = updated.serialNumber.ifBlank { updated.entityId }.trim()
    val isMatterNode = providerKey.contains("MATTER") || devId.startsWith("matter_")
    val isMqttOrDiyNode = providerKey.contains("MQTT") ||
            devId.startsWith("mqtt_") ||
            devId.startsWith("tasmota_") ||
            devId.startsWith("esphome_")

    if (devId.isBlank() && !isMatterNode && !isMqttOrDiyNode && providerKey.contains("TUYA")) {
        devId = resolveOrHealTuyaDeviceId(bridge, updated)
    }

    val hasTuyaDeviceId = devId.isNotBlank() &&
            !devId.startsWith("enterprises/") &&
            !isMatterNode &&
            !isMqttOrDiyNode &&
            !devId.contains(".")

    if (hasTuyaDeviceId && updated.ipAddress.isNotBlank()) {
        TuyaApiClient.registerDeviceLanAddress(devId, updated.ipAddress, updated.secretCode)
    }

    val haEntityId = when {
        updated.entityId.contains(".") -> updated.entityId.trim()
        !hasTuyaDeviceId && !isMatterNode && !isMqttOrDiyNode -> resolveOrHealHaEntityId(bridge, updated)
        else -> ""
    }

    val s1Label = updated.socket1Name.ifBlank { "Socket 1" }
    val s2Label = updated.socket2Name.ifBlank { "Socket 2" }

    // Dispatch routed by (activeProviderId, DeviceSubtype)
    val primaryOutcome: Pair<Boolean, String> = when {
        providerKey.contains("SMARTTHINGS") -> {
            val ok = SmartHomeApiClient.sendSmartThingsCommand(bridge, updated)
            if (ok) {
                true to "Updated SmartThings device '${updated.title}' (${if (updated.isActive) "ON" else "OFF"})."
            } else {
                false to "Could not reach SmartThings device '${updated.title}'."
            }
        }
        providerKey.contains("HUBITAT") || devId.startsWith("hubitat_") -> {
            val ok = when {
                updated.isThermostatTile -> SmartHomeApiClient.sendHubitatCommand(
                    bridge = bridge,
                    tile = updated,
                    command = "setHeatingSetpoint",
                    secondaryValue = updated.targetTemperature.roundToInt().toString()
                )
                updated.hasDimmingCapability && updated.isActive -> SmartHomeApiClient.sendHubitatCommand(
                    bridge = bridge,
                    tile = updated,
                    command = "setLevel",
                    secondaryValue = updated.volume.roundToInt().coerceIn(1, 100).toString()
                )
                else -> SmartHomeApiClient.sendHubitatCommand(
                    bridge = bridge,
                    tile = updated,
                    command = if (updated.isActive) "on" else "off"
                )
            }
            if (ok) {
                true to "Updated Hubitat device '${updated.title}' (${if (updated.isActive) "ON" else "OFF"})."
            } else {
                false to "Could not reach Hubitat Elevation hub for '${updated.title}'."
            }
        }
        providerKey.contains("HOMEY") || devId.startsWith("homey_") -> {
            val ok = when {
                updated.isThermostatTile -> SmartHomeApiClient.sendHomeyCommand(
                    bridge = bridge,
                    tile = updated,
                    capabilityId = "target_temperature",
                    value = updated.targetTemperature.toDouble()
                )
                updated.hasDimmingCapability && updated.isActive -> {
                    SmartHomeApiClient.sendHomeyCommand(bridge, updated, "onoff", true)
                    SmartHomeApiClient.sendHomeyCommand(
                        bridge = bridge,
                        tile = updated,
                        capabilityId = "dim",
                        value = (updated.volume.coerceIn(1f, 100f) / 100.0)
                    )
                }
                else -> SmartHomeApiClient.sendHomeyCommand(
                    bridge = bridge,
                    tile = updated,
                    capabilityId = "onoff",
                    value = updated.isActive
                )
            }
            if (ok) {
                true to "Updated Homey device '${updated.title}' (${if (updated.isActive) "ON" else "OFF"})."
            } else {
                false to "Could not reach Athom Homey bridge for '${updated.title}'."
            }
        }
        updated.entityId.startsWith("scene.", ignoreCase = true) -> {
            val ok = SmartHomeApiClient.triggerBridgeScene(
                bridge = bridge,
                scene = SceneTriggerCapability(
                    sceneId = updated.entityId,
                    sceneName = updated.title,
                    providerId = ProviderId.fromRawKey(providerKey).takeIf { it != ProviderId.UNKNOWN } ?: ProviderId.HOME_ASSISTANT,
                    roomHint = updated.roomOrSubtitle
                )
            )
            if (ok) {
                true to "Triggered scene '${updated.title}'."
            } else {
                false to "Could not trigger scene '${updated.title}'."
            }
        }
        isMatterNode -> {
            val startMs = System.currentTimeMillis()
            val ok = MatterProtocolClient.sendMatterCommand(context, updated, bridge)
            JarvisLiveBridge.recordCommandLatency(
                integrationKey = "MULTI_PROTOCOL",
                displayName = "Matter / Tapo / Shelly / MQTT / HA",
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(8L),
                succeeded = ok
            )
            if (ok) {
                true to "Turned Matter device '${updated.title}' ${if (updated.isActive) "ON" else "OFF"}."
            } else {
                false to "Could not reach Matter device '${updated.title}' on your local fabric."
            }
        }

        isMqttOrDiyNode -> {
            val startMs = System.currentTimeMillis()
            val mqttOk = MqttAndDiyClient.publishTileCommand(context, updated, singleSocketNumber)
            val httpOk = if (!mqttOk && updated.ipAddress.isNotBlank()) {
                if (updated.isMultiGangSwitch && singleSocketNumber == null) {
                    val ok1 = MqttAndDiyClient.sendTasmotaHttpCommand(updated.ipAddress, updated.isActive, relayNumber = 1)
                    val ok2 = MqttAndDiyClient.sendTasmotaHttpCommand(updated.ipAddress, updated.isSocket2Active, relayNumber = 2)
                    ok1 || ok2
                } else {
                    MqttAndDiyClient.sendTasmotaHttpCommand(
                        ipAddress = updated.ipAddress,
                        turnOn = if (singleSocketNumber == 2) updated.isSocket2Active else updated.isActive,
                        relayNumber = singleSocketNumber ?: 1
                    ) || MqttAndDiyClient.sendEspHomeHttpCommand(updated)
                }
            } else false
            val succeeded = mqttOk || httpOk
            JarvisLiveBridge.recordCommandLatency(
                integrationKey = "MULTI_PROTOCOL",
                displayName = "Matter / Tapo / Shelly / MQTT / HA",
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(8L),
                succeeded = succeeded
            )
            if (succeeded) {
                true to "Updated '${updated.title}' (${if (updated.isActive) "ON" else "OFF"})."
            } else {
                false to "Could not reach '${updated.title}' via MQTT Broker or local HTTP."
            }
        }

        !updated.title.lowercase(Locale.US).let {
            it.contains("plug") || it.contains("socket") || it.contains("pond") ||
                    it.contains("pump") || it.contains("kettle") || it.contains("fryer") ||
                    it.contains("coffee") || it.contains("washer")
        } && (updated.isThermostatTile || activeSubtype in setOf(DeviceSubtype.THERMOSTAT, DeviceSubtype.RADIATOR, DeviceSubtype.TRV)) -> {
            val shouldBeOn = updated.isActive && !updated.hvacMode.equals("OFF", ignoreCase = true)
            if (providerKey.contains("NEST") || updated.entityId.startsWith("enterprises/")) {
                val nestId = NestSdmApiClient.resolveOrAutoDiscoverNestEntityId(bridge, updated)
                if (!nestId.startsWith("enterprises/")) {
                    false to "Could not find '${updated.title}' in your Google Nest account."
                } else {
                    val ok = if (!shouldBeOn || updated.hvacMode.equals("ECO", ignoreCase = true)) {
                        NestSdmApiClient.setThermostatMode(bridge, nestId, updated.hvacMode)
                    } else {
                        NestSdmApiClient.setThermostatTemperature(bridge, nestId, updated.targetTemperature)
                    }
                    if (ok) {
                        true to "Updated '${updated.title}' to ${updated.hvacMode} (${"%.1f".format(updated.targetTemperature)}\u00B0C)."
                    } else {
                        false to "Could not update '${updated.title}'."
                    }
                }
            } else {
                val sentOk = when {
                    hasTuyaDeviceId -> {
                        val nativeOk = TuyaApiClient.sendNativeRadiatorCommand(
                            deviceId = devId,
                            shouldBeOn = shouldBeOn,
                            targetTempCelsius = updated.targetTemperature,
                            ipAddress = updated.ipAddress,
                            localKey = updated.secretCode,
                            hvacMode = updated.hvacMode
                        )
                        if (nativeOk) {
                            true
                        } else {
                            val dpCode = TuyaApiClient.tempSetDpCodeByDevice[devId] ?: "temp_set"
                            val scale = TuyaApiClient.tempScaleByDevice[devId] ?: 1f
                            val scaledValue = (updated.targetTemperature * scale).roundToInt()
                            val cmds = JSONArray().put(JSONObject().put("code", dpCode).put("value", scaledValue))
                            TuyaApiClient.sendCommands(bridge, devId, cmds)
                        }
                    }
                    haEntityId.isNotBlank() -> {
                        SmartHomeApiClient.callHomeAssistantService(
                            haBaseUrl = bridge.haUrl,
                            haToken = bridge.haToken,
                            domain = "climate",
                            service = "set_temperature",
                            payloadJson = JSONObject().apply {
                                put("entity_id", haEntityId)
                                put("temperature", updated.targetTemperature)
                            }
                        )
                    }
                    else -> false
                }
                if (sentOk) {
                    true to "Set '${updated.title}' to ${if (shouldBeOn) "${"%.1f".format(updated.targetTemperature)}\u00B0C" else "OFF"}."
                } else {
                    false to "Could not reach climate device '${updated.title}' on your network."
                }
            }
        }

        activeSubtype in setOf(DeviceSubtype.BULB, DeviceSubtype.LED_STRIP) -> {
            val sentOk = when {
                providerKey.contains("TPLINK") || providerKey.contains("KASA") || providerKey.contains("TAPO") -> {
                    val cleanIp = updated.ipAddress.substringBefore(":").trim()
                    TpLinkTapoKasaClient.setKasaRelayState(cleanIp, updated.isActive, updated.volume.roundToInt()) ||
                            TpLinkTapoKasaClient.setTapoDeviceState(context, cleanIp, updated.isActive, updated.volume.roundToInt())
                }
                hasTuyaDeviceId -> {
                    val dpCode = TuyaApiClient.switchDpCodeByDevice[devId] ?: "switch_led"
                    val cmds = JSONArray().apply {
                        put(JSONObject().put("code", dpCode).put("value", updated.isActive))
                        if (updated.isActive) {
                            put(
                                JSONObject()
                                    .put("code", "bright_value_v2")
                                    .put("value", (updated.volume * 10f).roundToInt().coerceIn(10, 1000))
                            )
                        }
                    }
                    TuyaApiClient.sendCommands(bridge, devId, cmds)
                }
                haEntityId.isNotBlank() -> {
                    SmartHomeApiClient.callHomeAssistantService(
                        haBaseUrl = bridge.haUrl,
                        haToken = bridge.haToken,
                        domain = "light",
                        service = if (updated.isActive) "turn_on" else "turn_off",
                        payloadJson = JSONObject().apply {
                            put("entity_id", haEntityId)
                            if (updated.isActive) {
                                put("brightness_pct", updated.volume.roundToInt())
                            }
                        }
                    )
                }
                else -> false
            }
            JarvisLiveBridge.emitPlatformEvent(
                JarvisPlatformEvent(
                    category = JarvisEventCategory.LIGHT,
                    sourceDeviceId = updated.id,
                    sourceDeviceTitle = updated.title,
                    roomName = updated.roomOrSubtitle,
                    eventType = if (updated.isActive) "LIGHT_ON" else "LIGHT_OFF",
                    summary = "${updated.title} ${if (updated.isActive) "set to ${updated.volume.roundToInt()}%" else "turned OFF"}"
                )
            )
            if (sentOk) {
                true to "Updated light '${updated.title}' (${if (updated.isActive) "${updated.volume.roundToInt()}%" else "OFF"})."
            } else {
                false to "Could not reach light '${updated.title}' on your network."
            }
        }

        activeSubtype == DeviceSubtype.LOCK -> {
            DeviceRepository.logAuditEvent(
                context = context,
                actorRole = bridge.activeUserRole,
                actionType = if (updated.isActive) "SMART_LOCK" else "SMART_UNLOCK",
                targetName = updated.title,
                detail = "${if (updated.isActive) "Locked" else "Unlocked"} ${updated.title} ($actorName)",
                succeeded = true
            )
            if (haEntityId.isBlank()) {
                false to "Could not find lock '${updated.title}' on your smart home hub."
            } else {
                val ok = SmartHomeApiClient.callHomeAssistantService(
                    haBaseUrl = bridge.haUrl,
                    haToken = bridge.haToken,
                    domain = "lock",
                    service = if (updated.isActive) "lock" else "unlock",
                    payloadJson = JSONObject().put("entity_id", haEntityId)
                )
                if (ok) {
                    true to "${if (updated.isActive) "Locked" else "Unlocked"} '${updated.title}'."
                } else {
                    false to "Could not reach '${updated.title}'."
                }
            }
        }

        activeSubtype in setOf(DeviceSubtype.BLIND, DeviceSubtype.GARAGE_DOOR) -> {
            if (haEntityId.isBlank()) {
                false to "Could not find '${updated.title}' on your smart home hub."
            } else {
                val pos = updated.volume.roundToInt().coerceIn(0, 100)
                val ok = SmartHomeApiClient.callHomeAssistantService(
                    haBaseUrl = bridge.haUrl,
                    haToken = bridge.haToken,
                    domain = "cover",
                    service = "set_cover_position",
                    payloadJson = JSONObject().apply {
                        put("entity_id", haEntityId)
                        put("position", pos)
                    }
                )
                if (ok) {
                    true to "Set '${updated.title}' to $pos%."
                } else {
                    false to "Could not reach '${updated.title}'."
                }
            }
        }

        activeSubtype == DeviceSubtype.TV -> {
            val resolvedTvIp = LgThinQWebOsClient.resolveOrAutoDiscoverTvIp(context, updated.ipAddress)
            var ssapOrWolOk = false
            if (resolvedTvIp.isNotBlank()) {
                if (updated.isActive) {
                    val effectiveMac = CrossEcosystemIdentityCorrelator.normalizeMac(
                        updated.macAddress
                            .ifBlank { updated.serialNumber }
                            .ifBlank { LgThinQWebOsClient.getSavedMacAddress(context, resolvedTvIp) }
                            .ifBlank { LgThinQWebOsClient.resolveMacFromArpTable(resolvedTvIp) }
                    )
                    val hasTvKey = LgThinQWebOsClient.hasVerifiedVaultKey(context, resolvedTvIp, effectiveMac, devId)
                    if (effectiveMac.isNotBlank() && hasTvKey) {
                        LgThinQWebOsClient.sendWakeOnLanMagicPacket(effectiveMac, resolvedTvIp)
                        ssapOrWolOk = true
                    }
                    val turnOnScreenOk = hasTvKey &&
                            LgThinQWebOsClient.sendSsapCommand(
                                context = context,
                                ipAddress = resolvedTvIp,
                                ssapUri = "ssap://com.webos.service.tvpower/power/turnOnScreen"
                            )
                    // Never send ssap://audio/setVolume during TV wake/power-on so the TV keeps its hardware volume
                    ssapOrWolOk = ssapOrWolOk || turnOnScreenOk
                } else {
                    ssapOrWolOk = LgThinQWebOsClient.sendSsapCommand(
                        context = context,
                        ipAddress = resolvedTvIp,
                        ssapUri = "ssap://system/turnOff"
                    )
                }
            }
            var haOk = false
            if (!ssapOrWolOk && updated.entityId.startsWith("media_player.") && bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()) {
                haOk = SmartHomeApiClient.callHomeAssistantService(
                    haBaseUrl = bridge.haUrl,
                    haToken = bridge.haToken,
                    domain = "media_player",
                    service = if (updated.isActive) "turn_on" else "turn_off",
                    payloadJson = JSONObject().put("entity_id", updated.entityId)
                )
            }
            if (ssapOrWolOk || haOk) {
                true to "Turned '${updated.title}' ${if (updated.isActive) "ON" else "OFF"}."
            } else {
                false to "Could not reach '${updated.title}' over LG webOS SSAP or Wake-on-LAN."
            }
        }

        activeSubtype in setOf(DeviceSubtype.SPEAKER, DeviceSubtype.CAST_GROUP) -> {
            var endpoints = GoogleCastLanClient.resolveEndpointsForTile(updated)
            if (endpoints.isEmpty()) {
                GoogleCastLanClient.discoverCastDevices(context, includeFullPortSweep = false)
                endpoints = GoogleCastLanClient.resolveEndpointsForTile(updated)
            }
            var castAckOk = false
            for ((ip, port) in endpoints) {
                if (GoogleCastLanClient.setCastDeviceVolume(ip, port, updated.volume)) {
                    castAckOk = true
                }
            }
            var haAckOk = false
            if (!castAckOk && updated.entityId.startsWith("media_player.") && bridge.haUrl.isNotBlank() && bridge.haToken.isNotBlank()) {
                haAckOk = SmartHomeApiClient.callHomeAssistantService(
                    haBaseUrl = bridge.haUrl,
                    haToken = bridge.haToken,
                    domain = "media_player",
                    service = "volume_set",
                    payloadJson = JSONObject().apply {
                        put("entity_id", updated.entityId)
                        put("volume_level", (updated.volume.coerceIn(0f, 100f) / 100.0))
                    }
                )
            }
            if (castAckOk || haAckOk) {
                true to "Set '${updated.title}' volume to ${updated.volume.roundToInt()}%."
            } else {
                false to "Speaker '${updated.title}' did not acknowledge Cast TLS 8009 volume update."
            }
        }

        activeSubtype in setOf(DeviceSubtype.CAMERA, DeviceSubtype.DOORBELL) -> {
            true to "${if (updated.isLiveFeedActive) "Opened" else "Closed"} live camera feed on '${updated.title}'."
        }

        activeSubtype in setOf(
            DeviceSubtype.SMART_PLUG,
            DeviceSubtype.WALL_SOCKET,
            DeviceSubtype.RELAY,
            DeviceSubtype.KETTLE,
            DeviceSubtype.AIR_FRYER,
            DeviceSubtype.COFFEE_MACHINE,
            DeviceSubtype.WASHER,
            DeviceSubtype.POND_PUMP,
            DeviceSubtype.EV_CHARGER
        ) || updated.isSwitchTile || updated.isKitchenApplianceTile || updated.title.lowercase(Locale.US).let {
            it.contains("plug") || it.contains("socket") || it.contains("pond") ||
                    it.contains("pump") || it.contains("kettle") || it.contains("fryer") ||
                    it.contains("coffee") || it.contains("washer")
        } -> {
            val turnOnTarget = if (singleSocketNumber == 2) updated.isSocket2Active else updated.isActive
            when {
                providerKey.contains("TPLINK") || providerKey.contains("KASA") || providerKey.contains("TAPO") -> {
                    val startMs = System.currentTimeMillis()
                    val cleanIp = updated.ipAddress.substringBefore(":").trim()
                    val kasaOk = if (cleanIp.isNotBlank()) {
                        if (updated.isMultiGangSwitch && singleSocketNumber == null) {
                            val ok1 = TpLinkTapoKasaClient.setKasaRelayState(cleanIp, updated.isActive, socketNumber = 1)
                            val ok2 = TpLinkTapoKasaClient.setKasaRelayState(cleanIp, updated.isSocket2Active, socketNumber = 2)
                            ok1 || ok2
                        } else {
                            TpLinkTapoKasaClient.setKasaRelayState(cleanIp, turnOnTarget, socketNumber = singleSocketNumber)
                        }
                    } else false
                    val tapoOk = if (!kasaOk && cleanIp.isNotBlank()) {
                        TpLinkTapoKasaClient.setTapoDeviceState(context, cleanIp, turnOnTarget, socketNumber = singleSocketNumber)
                    } else false
                    val haFallbackOk = if (!kasaOk && !tapoOk && haEntityId.isNotBlank()) {
                        SmartHomeApiClient.callHomeAssistantService(
                            haBaseUrl = bridge.haUrl,
                            haToken = bridge.haToken,
                            domain = "switch",
                            service = if (turnOnTarget) "turn_on" else "turn_off",
                            payloadJson = JSONObject().put("entity_id", haEntityId)
                        )
                    } else false
                    val succeeded = kasaOk || tapoOk || haFallbackOk
                    JarvisLiveBridge.recordCommandLatency(
                        integrationKey = "MULTI_PROTOCOL",
                        displayName = "Matter / Tapo / Shelly / MQTT / HA",
                        latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(6L),
                        succeeded = succeeded
                    )
                    if (succeeded) {
                        true to "Turned '${updated.title}' ${if (turnOnTarget) "ON" else "OFF"}."
                    } else {
                        false to "Could not reach '${updated.title}' on Wi-Fi."
                    }
                }

                providerKey.contains("SONOFF") || providerKey.contains("EWELINK") -> {
                    val startMs = System.currentTimeMillis()
                    val sonoffOk = if (updated.isMultiGangSwitch && singleSocketNumber == null) {
                        val ok1 = EWeLinkSonoffClient.setSonoffSwitchState(context, updated, updated.isActive, outletIndex = 0)
                        val ok2 = EWeLinkSonoffClient.setSonoffSwitchState(context, updated, updated.isSocket2Active, outletIndex = 1)
                        ok1 || ok2
                    } else {
                        EWeLinkSonoffClient.setSonoffSwitchState(
                            context = context,
                            tile = updated,
                            turnOn = turnOnTarget,
                            outletIndex = ((singleSocketNumber ?: 1) - 1).coerceAtLeast(0)
                        )
                    }
                    val haFallbackOk = if (!sonoffOk && haEntityId.isNotBlank()) {
                        SmartHomeApiClient.callHomeAssistantService(
                            haBaseUrl = bridge.haUrl,
                            haToken = bridge.haToken,
                            domain = "switch",
                            service = if (turnOnTarget) "turn_on" else "turn_off",
                            payloadJson = JSONObject().put("entity_id", haEntityId)
                        )
                    } else false
                    val succeeded = sonoffOk || haFallbackOk
                    JarvisLiveBridge.recordCommandLatency(
                        integrationKey = "MULTI_PROTOCOL",
                        displayName = "Matter / Tapo / Shelly / MQTT / HA",
                        latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(8L),
                        succeeded = succeeded
                    )
                    if (succeeded) {
                        true to "Turned '${updated.title}' ${if (turnOnTarget) "ON" else "OFF"}."
                    } else {
                        false to "Could not reach '${updated.title}' on Wi-Fi."
                    }
                }

                providerKey.contains("SHELLY") -> {
                    val shellyIp = resolveOrHealShellyIp(context, updated)
                    val relayIdx = ((singleSocketNumber ?: 1) - 1).coerceAtLeast(0)
                    val localOk = if (shellyIp.isNotBlank()) {
                        if (updated.isMultiGangSwitch && singleSocketNumber == null) {
                            val ok0 = SmartHomeApiClient.toggleLocalShellyRelay(shellyIp, updated.isActive, relayIndex = 0)
                            val ok1 = SmartHomeApiClient.toggleLocalShellyRelay(shellyIp, updated.isSocket2Active, relayIndex = 1)
                            if (ok0 || ok1) true else {
                                val t1 = MqttAndDiyClient.sendTasmotaHttpCommand(shellyIp, updated.isActive, relayNumber = 1)
                                val t2 = MqttAndDiyClient.sendTasmotaHttpCommand(shellyIp, updated.isSocket2Active, relayNumber = 2)
                                t1 || t2
                            }
                        } else {
                            SmartHomeApiClient.toggleLocalShellyRelay(shellyIp, turnOnTarget, relayIndex = relayIdx) ||
                                    MqttAndDiyClient.sendTasmotaHttpCommand(shellyIp, turnOnTarget, relayNumber = singleSocketNumber ?: 1) ||
                                    MqttAndDiyClient.sendEspHomeHttpCommand(updated.copy(ipAddress = shellyIp))
                        }
                    } else false
                    val haOk = if (!localOk && haEntityId.isNotBlank()) {
                        SmartHomeApiClient.callHomeAssistantService(
                            haBaseUrl = bridge.haUrl,
                            haToken = bridge.haToken,
                            domain = "switch",
                            service = if (turnOnTarget) "turn_on" else "turn_off",
                            payloadJson = JSONObject().put("entity_id", haEntityId)
                        )
                    } else false
                    if (localOk || haOk) {
                        true to "Turned '${updated.title}' ${if (turnOnTarget) "ON" else "OFF"}."
                    } else {
                        false to "Could not find '${updated.title}' on your Wi-Fi network."
                    }
                }

                else -> {
                    if (!hasTuyaDeviceId && haEntityId.isBlank()) {
                        false to "Could not reach '${updated.title}' because it hasn't been paired yet."
                    } else {
                        val s2On = updated.isSocket2Active
                        val sentOk = when {
                            singleSocketNumber == 1 || singleSocketNumber == 2 -> {
                                val turnOn = if (singleSocketNumber == 1) updated.isActive else s2On
                                TuyaApiClient.sendSingleSocketPlugCommand(
                                    bridge = bridge,
                                    deviceId = devId,
                                    socketNumber = singleSocketNumber,
                                    turnOn = turnOn,
                                    ipAddress = updated.ipAddress,
                                    localKey = updated.secretCode
                                )
                            }
                            updated.isMultiGangSwitch && hasTuyaDeviceId -> {
                                val nativeOk = TuyaApiClient.sendNativeDualPlugCommand(
                                    deviceId = devId,
                                    socket1On = updated.isActive,
                                    socket2On = s2On,
                                    ipAddress = updated.ipAddress,
                                    localKey = updated.secretCode,
                                    isDualHardware = true
                                )
                                if (nativeOk) {
                                    true
                                } else {
                                    val dp1Code = TuyaApiClient.switchDpCodeByDevice[devId] ?: "switch_1"
                                    val dp2Code = TuyaApiClient.switch2DpCodeByDevice[devId] ?: "switch_2"
                                    val cmds = JSONArray()
                                        .put(JSONObject().put("code", dp1Code).put("value", updated.isActive))
                                        .put(JSONObject().put("code", dp2Code).put("value", s2On))
                                    TuyaApiClient.sendCommands(bridge, devId, cmds)
                                }
                            }
                            hasTuyaDeviceId -> {
                                TuyaApiClient.sendSingleSocketPlugCommand(
                                    bridge = bridge,
                                    deviceId = devId,
                                    socketNumber = 1,
                                    turnOn = updated.isActive,
                                    ipAddress = updated.ipAddress,
                                    localKey = updated.secretCode
                                )
                            }
                            else -> false
                        }
                        val haOk = if (!sentOk && haEntityId.isNotBlank()) {
                            SmartHomeApiClient.callHomeAssistantService(
                                haBaseUrl = bridge.haUrl,
                                haToken = bridge.haToken,
                                domain = "switch",
                                service = if (updated.isActive) "turn_on" else "turn_off",
                                payloadJson = JSONObject().put("entity_id", haEntityId)
                            )
                        } else false

                        JarvisLiveBridge.emitPlatformEvent(
                            JarvisPlatformEvent(
                                category = JarvisEventCategory.PLUG_POWER,
                                sourceDeviceId = updated.id,
                                sourceDeviceTitle = updated.title,
                                roomName = updated.roomOrSubtitle,
                                eventType = if (updated.isActive) "TURNED_ON" else "TURNED_OFF",
                                summary = "${updated.title} turned ${if (updated.isActive) "ON" else "OFF"} ($actorName)"
                            )
                        )

                        if (sentOk || haOk) {
                            val msg = when {
                                singleSocketNumber == 1 -> "Turned $s1Label ${if (updated.isActive) "ON" else "OFF"} on '${updated.title}'."
                                singleSocketNumber == 2 -> "Turned $s2Label ${if (s2On) "ON" else "OFF"} on '${updated.title}'."
                                updated.isMultiGangSwitch -> "Updated $s1Label (${if (updated.isActive) "ON" else "OFF"}) & $s2Label (${if (s2On) "ON" else "OFF"}) on '${updated.title}'."
                                else -> "Turned '${updated.title}' ${if (updated.isActive) "ON" else "OFF"}."
                            }
                            true to msg
                        } else {
                            false to "'${updated.title}' did not respond on Wi-Fi or Cloud."
                        }
                    }
                }
            }
        }

        else -> {
            false to "Could not reach '${updated.title}' on your network."
        }
    }
    // If primary dispatch failed, invoke Stage 0..7 RecoveryEngine and retry the actual command once
    if (!primaryOutcome.first && allowRecoveryRetry) {
        val liveTilesRef = UniversalOnboardingEngine.activeDashboardTilesRef
        val (healed, healSummary) = RecoveryEngine.executeAutonomousRecovery(
            context = context,
            tile = updated,
            bridge = bridge,
            tiles = liveTilesRef
        )
        if (healed) {
            val healedSnapshot = liveTilesRef?.firstOrNull { it.id == updated.id }
                ?: runCatching { DeviceRepository.loadTiles(context).firstOrNull { it.id == updated.id } }.getOrNull()
            val retriedTile = updated.copy(
                ipAddress = healedSnapshot?.ipAddress?.ifBlank { updated.ipAddress } ?: updated.ipAddress,
                activeProviderId = healedSnapshot?.activeProviderId?.ifBlank { updated.activeProviderId } ?: updated.activeProviderId,
                macAddress = healedSnapshot?.macAddress?.ifBlank { updated.macAddress } ?: updated.macAddress
            )
            val retryOutcome = dispatchDeviceUpdateWithDiagnosticInternal(
                context = context,
                updated = retriedTile,
                bridge = bridge,
                audioManager = audioManager,
                scope = scope,
                singleSocketNumber = singleSocketNumber,
                pinAuthorized = pinAuthorized,
                intentTier = intentTier,
                actorName = actorName,
                allowRecoveryRetry = false
            )
            return if (retryOutcome.first) {
                true to "${retryOutcome.second} -> Auto-Healed ($healSummary)"
            } else {
                JarvisLiveBridge.recordFalseHealContradiction(
                    tile = updated,
                    detail = "Recovery reported ($healSummary) for '${updated.title}', but command retry failed: ${retryOutcome.second}"
                )
                false to "${retryOutcome.second} (Session recovery ran, but hardware did not acknowledge command retry)"
            }
        }
    }
    return primaryOutcome
}

fun setSystemVolumePercent(audioManager: AudioManager, percent: Float) {
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val target = ((percent / 100f) * max).roundToInt().coerceIn(0, max)
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
}

fun dispatchMediaKey(audioManager: AudioManager, keyCode: Int) {
    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
}

fun openExternalApp(context: Context, packageName: String) {
    val cleanPkg = packageName.trim()
    if (cleanPkg.isBlank()) return
    try {
        val directIntent = context.packageManager.getLaunchIntentForPackage(cleanPkg)
        if (directIntent != null) {
            directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(directIntent)
        } else {
            Toast.makeText(
                context,
                "Device is managed directly inside Clara without external vendor apps.",
                Toast.LENGTH_SHORT
            ).show()
        }
    } catch (_: Exception) {
    }
}

fun executeHomeRoutine(
    sceneName: String,
    tiles: MutableList<SmartTile>,
    bridge: BridgeSettings,
    audioManager: AudioManager,
    scope: CoroutineScope,
    context: Context,
    onSpeak: (String) -> Unit,
    intentTier: IntentAuthorityTier = IntentAuthorityTier.MANUAL_USER,
    actorName: String = "Home Scene ($sceneName)"
) {
    val now = System.currentTimeMillis()
    val effectiveTier = if (sceneName == "PERIMETER LOCK") IntentAuthorityTier.SAFETY_CRITICAL else intentTier
    when (sceneName) {
        "ALL LIVE FEEDS" -> {
            val anyCameraOff = tiles.any { it.isCameraTile && !it.isLiveFeedActive }
            onSpeak(if (anyCameraOff) "Opening all live camera feeds." else "Closing live camera feeds.")
            tiles.replaceAll { t ->
                if (t.isCameraTile) {
                    DeviceRepository.stampOutboundIntentAuthority(
                        tile = t.copy(isLiveFeedActive = anyCameraOff, lastUserActuationEpochMs = now),
                        intentTier = effectiveTier,
                        actorName = actorName,
                        nowMs = now
                    )
                } else t
            }
        }
        "ECO NIGHT" -> {
            onSpeak("Goodnight routine active. Locking vehicles and doors, setting heating to Eco mode, and turning off plugs.")
            setSystemVolumePercent(audioManager, 20f)
            tiles.replaceAll { t ->
                val (canRun, _) = DeviceRepository.canExecuteOutboundIntent(t, effectiveTier, now)
                if (!canRun) return@replaceAll t

                val providerKey = resolveEffectiveProviderKey(t)
                val ecoNightTemp = bridge.securityNightTempC.coerceIn(5f, 35f)
                val baseUpdated = when {
                    t.isThermostatTile && (providerKey.contains("NEST") || t.deviceSubtype == DeviceSubtype.THERMOSTAT) -> t.copy(
                        hvacMode = "ECO",
                        isActive = false,
                        targetTemperature = ecoNightTemp,
                        lastUserActuationEpochMs = now
                    )
                    t.isThermostatTile -> t.copy(targetTemperature = ecoNightTemp, lastUserActuationEpochMs = now)
                    t.isTowerFanTile -> t.copy(
                        fanMode = "SLEEP",
                        fanSpeedLevel = 1,
                        volume = 17f,
                        lastUserActuationEpochMs = now
                    )
                    t.deviceSubtype in setOf(DeviceSubtype.SPEAKER, DeviceSubtype.CAST_GROUP) ->
                        t.copy(volume = 20f, lastUserActuationEpochMs = now)
                    t.deviceSubtype == DeviceSubtype.TV -> {
                        LgThinQWebOsClient.setPower(context, t, bridge, turnOn = false, scope = scope)
                        t.copy(isActive = false, lastUserActuationEpochMs = now)
                    }
                    t.isVehicleTile -> t.copy(vehicleLocked = true, isActive = true, lastUserActuationEpochMs = now)
                    t.deviceSubtype == DeviceSubtype.LOCK -> t.copy(isActive = true, lastUserActuationEpochMs = now)
                    t.deviceSubtype in setOf(DeviceSubtype.BLIND, DeviceSubtype.GARAGE_DOOR) ->
                        t.copy(isActive = false, volume = 0f, lastUserActuationEpochMs = now)
                    t.isAmbientRgbTile || t.deviceClass == DeviceClass.LIGHT ->
                        t.copy(isActive = false, volume = 0f, lastUserActuationEpochMs = now)
                    t.deviceClass == DeviceClass.SWITCH || t.deviceSubtype in setOf(DeviceSubtype.SMART_PLUG, DeviceSubtype.WALL_SOCKET, DeviceSubtype.RELAY) -> t.copy(
                        isActive = false,
                        isSocket2Active = false,
                        powerWatts = 0f,
                        volume = 0f,
                        lastUserActuationEpochMs = now
                    )
                    t.isGroupCardTile -> t.copy(isActive = false, lastUserActuationEpochMs = now)
                    else -> t
                }
                if (baseUpdated != t) {
                    val stamped = DeviceRepository.stampOutboundIntentAuthority(
                        tile = baseUpdated,
                        intentTier = effectiveTier,
                        actorName = actorName,
                        nowMs = now
                    )
                    if (!t.isGroupCardTile) {
                        dispatchDeviceUpdate(stamped, bridge, audioManager, scope, pinAuthorized = true, intentTier = effectiveTier, actorName = actorName)
                    }
                    stamped
                } else t
            }
        }
        "BOOST HEAT" -> {
            val boostTargetC = (bridge.securityHomeTempC + 2.0f).coerceIn(18.0f, 30.0f)
            onSpeak("Boosting heating to ${"%.1f".format(Locale.US, boostTargetC)} degrees.")
            tiles.replaceAll { t ->
                val (canRun, _) = DeviceRepository.canExecuteOutboundIntent(t, effectiveTier, now)
                if (t.isThermostatTile && canRun) {
                    val updated = DeviceRepository.stampOutboundIntentAuthority(
                        tile = t.copy(
                            hvacMode = "HEAT",
                            isActive = true,
                            targetTemperature = boostTargetC,
                            lastUserActuationEpochMs = now
                        ),
                        intentTier = effectiveTier,
                        actorName = actorName,
                        nowMs = now
                    )
                    dispatchDeviceUpdate(updated, bridge, audioManager, scope, intentTier = effectiveTier, actorName = actorName)
                    updated
                } else t
            }
        }
        "PERIMETER LOCK" -> {
            onSpeak("Securing perimeter locks, locking vehicles, and turning on outdoor smart plugs.")
            tiles.replaceAll { t ->
                when {
                    t.isVehicleTile -> {
                        val updated = DeviceRepository.stampOutboundIntentAuthority(
                            tile = t.copy(vehicleLocked = true, isActive = true, lastUserActuationEpochMs = now),
                            intentTier = effectiveTier,
                            actorName = actorName,
                            nowMs = now
                        )
                        dispatchDeviceUpdate(updated, bridge, audioManager, scope, pinAuthorized = true, intentTier = effectiveTier, actorName = actorName)
                        updated
                    }
                    t.deviceSubtype == DeviceSubtype.LOCK -> {
                        val updated = DeviceRepository.stampOutboundIntentAuthority(
                            tile = t.copy(isActive = true, lastUserActuationEpochMs = now),
                            intentTier = effectiveTier,
                            actorName = actorName,
                            nowMs = now
                        )
                        dispatchDeviceUpdate(updated, bridge, audioManager, scope, pinAuthorized = true, intentTier = effectiveTier, actorName = actorName)
                        updated
                    }
                    t.deviceSubtype in setOf(DeviceSubtype.SMART_PLUG, DeviceSubtype.WALL_SOCKET) && !t.isThermostatTile -> {
                        val updated = DeviceRepository.stampOutboundIntentAuthority(
                            tile = t.copy(
                                isActive = true,
                                isSocket2Active = if (t.isMultiGangSwitch) true else t.isSocket2Active,
                                volume = 100f,
                                lastUserActuationEpochMs = now
                            ),
                            intentTier = effectiveTier,
                            actorName = actorName,
                            nowMs = now
                        )
                        dispatchDeviceUpdate(updated, bridge, audioManager, scope, pinAuthorized = true, intentTier = effectiveTier, actorName = actorName)
                        updated
                    }
                    else -> t
                }
            }
        }
    }
    DeviceRepository.saveTiles(context, tiles)
}

private fun queryShellyLiveTelemetryBlocking(ipAddress: String, fallbackTile: SmartTile): SmartTile? {
    val cleanIp = ipAddress.substringBefore(":").trim()
    if (cleanIp.isBlank()) return null

    // 1. Shelly Gen2 / Gen3 / Plus / Pro RPC status
    try {
        val conn = (java.net.URL("http://$cleanIp/rpc/Shelly.GetStatus").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 1500
            readTimeout = 1500
        }
        if (conn.responseCode in 200..299) {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val root = JSONObject(body)
            val sw0 = root.optJSONObject("switch:0")
            val sw1 = root.optJSONObject("switch:1")
            if (sw0 != null) {
                val isOn0 = sw0.optBoolean("output", fallbackTile.isActive)
                val isOn1 = sw1?.optBoolean("output", fallbackTile.isSocket2Active) ?: fallbackTile.isSocket2Active
                val watts = (sw0.optDouble("apower", 0.0) + (sw1?.optDouble("apower", 0.0) ?: 0.0)).toFloat()
                val volts = sw0.optDouble("voltage", fallbackTile.voltageVolts.toDouble()).toFloat()
                val amps = (sw0.optDouble("current", 0.0) + (sw1?.optDouble("current", 0.0) ?: 0.0)).toFloat()
                val whTotal = (sw0.optJSONObject("aenergy")?.optDouble("total", 0.0) ?: 0.0) +
                        (sw1?.optJSONObject("aenergy")?.optDouble("total", 0.0) ?: 0.0)
                val hasMeter = sw0.has("apower") || watts > 0f || whTotal > 0.0
                return fallbackTile.copy(
                    ipAddress = cleanIp,
                    isOnline = true,
                    isActive = isOn0,
                    isSocket2Active = isOn1,
                    multiGangSocketCount = if (sw1 != null) maxOf(fallbackTile.multiGangSocketCount, 2) else fallbackTile.multiGangSocketCount,
                    powerWatts = watts,
                    voltageVolts = volts,
                    currentMilliamps = amps * 1000f,
                    totalEnergyKwh = if (whTotal > 0.0) (whTotal / 1000.0).toFloat() else fallbackTile.totalEnergyKwh,
                    hasExplicitPowerTelemetry = fallbackTile.hasExplicitPowerTelemetry || hasMeter
                )
            }
        } else {
            conn.disconnect()
        }
    } catch (_: Exception) {
    }

    // 2. Shelly Gen1 HTTP /status
    try {
        val conn = (java.net.URL("http://$cleanIp/status").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 1500
            readTimeout = 1500
        }
        if (conn.responseCode in 200..299) {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val root = JSONObject(body)
            val relays = root.optJSONArray("relays")
            val meters = root.optJSONArray("meters")
            if (relays != null && relays.length() > 0) {
                val isOn0 = relays.optJSONObject(0)?.optBoolean("ison", fallbackTile.isActive) ?: fallbackTile.isActive
                val isOn1 = relays.optJSONObject(1)?.optBoolean("ison", fallbackTile.isSocket2Active) ?: fallbackTile.isSocket2Active
                val watts = meters?.optJSONObject(0)?.optDouble("power", fallbackTile.powerWatts.toDouble())?.toFloat()
                    ?: fallbackTile.powerWatts
                // Shelly Gen1 reports energy in Watt-minutes
                val wattMin = meters?.optJSONObject(0)?.optDouble("total", 0.0) ?: 0.0
                return fallbackTile.copy(
                    ipAddress = cleanIp,
                    isOnline = true,
                    isActive = isOn0,
                    isSocket2Active = isOn1,
                    multiGangSocketCount = maxOf(fallbackTile.multiGangSocketCount, relays.length()),
                    powerWatts = watts,
                    totalEnergyKwh = if (wattMin > 0.0) (wattMin / 60_000.0).toFloat() else fallbackTile.totalEnergyKwh,
                    hasExplicitPowerTelemetry = fallbackTile.hasExplicitPowerTelemetry || meters != null
                )
            }
        } else {
            conn.disconnect()
        }
    } catch (_: Exception) {
    }
    return null
}

/**
 * Change 3.2: Unified 5-second-bounded single-device state & telemetry refresh routed to the
 * active ecosystem provider (Tapo/Kasa, Shelly, Sonoff, Matter, MQTT/Tasmota, Home Assistant,
 * Hubitat, Homey, SmartThings, and Tuya LAN/Cloud), persisting live state into the Device Registry.
 */
suspend fun refreshSingleDeviceTelemetrySnapshot(
    context: Context,
    tile: SmartTile,
    bridge: BridgeSettings
): Result<Pair<SmartTile, String>> = withContext(Dispatchers.IO) {
    val providerKey = resolveEffectiveProviderKey(tile)
    val devId = tile.serialNumber.ifBlank { tile.entityId }.trim()
    val cleanIp = tile.ipAddress.substringBefore(":").trim()
    val refreshStartMs = System.currentTimeMillis()
    if (isHardwareLifecycleTrackedTile(tile)) {
        DeviceLifecycleRecorder.recordTileTransition(
            tile = tile,
            transition = MandatoryLifecycleTransition.CAPABILITY_REQUESTED,
            previousState = tile.effectiveLifecycleState.name,
            newState = DeviceLifecycleState.CAPABILITY_LEARNING.name,
            protocolResultCode = "TELEMETRY_REFRESH_REQ"
        )
    }

    if ((tile.type == TileType.LG_TV || tile.deviceSubtype == DeviceSubtype.TV || providerKey.contains("LG")) &&
        !LgThinQWebOsClient.hasVerifiedVaultKey(context, cleanIp, tile.macAddress, devId)
    ) {
        return@withContext Result.failure(
            Exception("Pairing required for '${tile.title}' — open Full Remote, tap Pair TV, and press Accept on your TV screen.")
        )
    }

    val outcome = kotlinx.coroutines.withTimeoutOrNull(5_000L) {
        when {
            tile.type == TileType.LG_TV || tile.deviceSubtype == DeviceSubtype.TV || providerKey.contains("LG") -> {
                val tvIp = LgThinQWebOsClient.resolveOrAutoDiscoverTvIp(context, cleanIp)
                if (tvIp.isNotBlank() && LgThinQWebOsClient.hasVerifiedVaultKey(context, tvIp, tile.macAddress, devId)) {
                    val volResp = LgThinQWebOsClient.sendSsapCommandWithResponse(context, tvIp, "ssap://audio/getVolume")
                    val fgResp = LgThinQWebOsClient.sendSsapCommandWithResponse(
                        context,
                        tvIp,
                        "ssap://com.webos.applicationManager/getForegroundAppInfo"
                    )
                    if (volResp != null || fgResp != null) {
                        runCatching { LgThinQWebOsClient.syncInstalledAppsFromTv(context, tvIp) }
                        val parsedVol = volResp?.optJSONObject("payload")?.optInt("volume", -1)
                            ?.takeIf { it in 0..100 }
                            ?: volResp?.optInt("volume", -1)?.takeIf { it in 0..100 }
                            ?: LgThinQWebOsClient.lastKnownTvVolumeByIp[tvIp]
                            ?: tile.volume.roundToInt()
                        val fgAppId = fgResp?.optJSONObject("payload")?.optString("appId").orEmpty()
                            .ifBlank { fgResp?.optString("appId").orEmpty() }
                        val matchedLabel = if (fgAppId.isNotBlank()) {
                            LgThinQWebOsClient.loadTvApps(context)
                                .firstOrNull { it.appId.equals(fgAppId, ignoreCase = true) }?.label
                                ?: tile.activeMediaSource
                        } else tile.activeMediaSource
                        val learnedMac = CrossEcosystemIdentityCorrelator.normalizeMac(
                            LgThinQWebOsClient.getSavedMacAddress(context, tvIp)
                                .ifBlank { LgThinQWebOsClient.resolveMacFromArpTable(tvIp) }
                        ).ifBlank { tile.macAddress }
                        tile.copy(
                            ipAddress = tvIp,
                            macAddress = learnedMac,
                            serialNumber = tile.serialNumber.ifBlank { learnedMac },
                            isOnline = true,
                            isActive = true,
                            volume = parsedVol.toFloat(),
                            activeMediaSource = matchedLabel
                        ) to "Synced LG webOS TV (Vol $parsedVol%${if (matchedLabel.isNotBlank()) " \u2022 $matchedLabel" else ""})"
                    } else null
                } else null
            }

            tile.deviceSubtype in setOf(DeviceSubtype.SPEAKER, DeviceSubtype.CAST_GROUP) || providerKey.contains("CAST") -> {
                val castList = GoogleCastLanClient.discoverCastDevices(context, includeFullPortSweep = false)
                val matched = castList.firstOrNull {
                    (devId.isNotBlank() && it.id.equals(devId, ignoreCase = true)) ||
                            (cleanIp.isNotBlank() && it.ipAddress == cleanIp) ||
                            it.name.equals(tile.title, ignoreCase = true)
                }
                if (matched != null) {
                    tile.copy(
                        ipAddress = "${matched.ipAddress}:${matched.port}",
                        isOnline = true,
                        isActive = !matched.isMuted && matched.volumePercent > 0.5f,
                        volume = matched.volumePercent
                    ) to "Synced Cast speaker (${matched.volumePercent.roundToInt()}%)"
                } else null
            }

            providerKey.contains("TWINKLY") -> {
                val gestalt = if (cleanIp.isNotBlank()) SmartHomeApiClient.probeTwinklyGestaltOnPort80(cleanIp) else null
                val found = gestalt ?: SmartHomeApiClient.discoverTwinklyDevices(context, timeoutMs = 1200L)
                    .firstOrNull { it.hardwareIdOrMac.equals(devId, true) || it.suggestedName.equals(tile.title, true) }
                if (found != null) {
                    tile.copy(
                        ipAddress = found.ipAddress.ifBlank { tile.ipAddress },
                        isOnline = true
                    ) to "Synced Twinkly LAN controller (${found.ipAddress})"
                } else null
            }

            providerKey.contains("GOVEE") -> {
                val found = SmartHomeApiClient.discoverGoveeDevices(bridge, timeoutMs = 1200L)
                    .firstOrNull {
                        (cleanIp.isNotBlank() && it.ipAddress == cleanIp) ||
                                (devId.isNotBlank() && it.hardwareIdOrMac.equals(devId, true)) ||
                                it.suggestedName.equals(tile.title, true)
                    }
                if (found != null) {
                    tile.copy(
                        ipAddress = found.ipAddress.ifBlank { tile.ipAddress },
                        isOnline = true
                    ) to "Synced Govee LAN light (${found.ipAddress})"
                } else null
            }

            providerKey.contains("TPLINK") || providerKey.contains("TAPO") || providerKey.contains("KASA") -> {
                val tapoLive = if (cleanIp.isNotBlank()) {
                    TpLinkTapoKasaClient.queryTapoLiveTelemetry(context, cleanIp)
                } else null
                if (tapoLive != null) {
                    tile.copy(
                        isOnline = true,
                        isActive = tapoLive.isActive,
                        isSocket2Active = tapoLive.isSocket2Active,
                        powerWatts = tapoLive.powerWatts,
                        totalEnergyKwh = tapoLive.totalEnergyKwh
                    ) to "Synced TP-Link Tapo telemetry (${"%.1f".format(Locale.US, tapoLive.powerWatts)} W)"
                } else {
                    val discovered = TpLinkTapoKasaClient.discoverLocalKasaAndTapoDevices(context).firstOrNull {
                        (devId.isNotBlank() && it.deviceId.equals(devId, ignoreCase = true)) ||
                                (cleanIp.isNotBlank() && it.ipAddress == cleanIp) ||
                                it.name.equals(tile.title, ignoreCase = true)
                    }
                    if (discovered != null) {
                        tile.copy(
                            ipAddress = discovered.ipAddress.ifBlank { tile.ipAddress },
                            macAddress = CrossEcosystemIdentityCorrelator.normalizeMac(discovered.macAddress).ifBlank { tile.macAddress },
                            isOnline = true,
                            isActive = discovered.isActive,
                            isSocket2Active = discovered.isSocket2Active,
                            powerWatts = discovered.powerWatts,
                            voltageVolts = discovered.voltageVolts,
                            currentMilliamps = discovered.currentMilliamps,
                            totalEnergyKwh = discovered.totalEnergyKwh
                        ) to "Synced TP-Link LAN state (${if (discovered.isActive) "ON" else "OFF"})"
                    } else null
                }
            }

            providerKey.contains("SHELLY") -> {
                val shellyIp = resolveOrHealShellyIp(context, tile)
                val shellyUpdated = queryShellyLiveTelemetryBlocking(shellyIp, tile)
                if (shellyUpdated != null) {
                    shellyUpdated to "Synced Shelly HTTP telemetry (${"%.1f".format(Locale.US, shellyUpdated.powerWatts)} W)"
                } else null
            }

            providerKey.contains("SONOFF") || providerKey.contains("EWELINK") -> {
                val localList = EWeLinkSonoffClient.discoverLocalSonoffMdns(context, timeoutMs = 1200L)
                val cloudList = EWeLinkSonoffClient.loginAndDiscoverEWeLinkCloud(context).getOrNull().orEmpty()
                val matched = (localList + cloudList).firstOrNull {
                    (devId.isNotBlank() && it.deviceId.equals(devId, ignoreCase = true)) ||
                            (cleanIp.isNotBlank() && it.ipAddress == cleanIp) ||
                            it.name.equals(tile.title, ignoreCase = true)
                }
                if (matched != null) {
                    tile.copy(
                        ipAddress = if (matched.ipAddress.isNotBlank()) "${matched.ipAddress}:${matched.port}" else tile.ipAddress,
                        isOnline = true,
                        isActive = matched.isActive,
                        isSocket2Active = matched.isSocket2Active,
                        powerWatts = matched.powerWatts,
                        voltageVolts = matched.voltageVolts,
                        currentMilliamps = matched.currentMilliamps
                    ) to "Synced Sonoff state (${if (matched.isActive) "ON" else "OFF"})"
                } else null
            }

            providerKey.contains("MATTER") || devId.startsWith("matter_") -> {
                val matched = MatterProtocolClient.discoverMatterDevices(context, timeoutMs = 1500L).firstOrNull {
                    (devId.isNotBlank() && it.nodeId.equals(devId, ignoreCase = true)) ||
                            (cleanIp.isNotBlank() && it.ipAddress == cleanIp)
                }
                if (matched != null) {
                    tile.copy(
                        ipAddress = matched.ipAddress.ifBlank { tile.ipAddress },
                        isOnline = true,
                        isActive = matched.isActive,
                        volume = matched.levelPct,
                        temperature = matched.temperatureCelsius,
                        targetTemperature = matched.targetTempCelsius,
                        powerWatts = matched.powerWatts
                    ) to "Synced Matter fabric node (${if (matched.isActive) "ON" else "OFF"})"
                } else null
            }

            providerKey.contains("MQTT") || devId.startsWith("mqtt_") || devId.startsWith("tasmota_") || devId.startsWith("esphome_") -> {
                val tasmota = if (cleanIp.isNotBlank()) MqttAndDiyClient.probeTasmotaOnPort80(cleanIp) else null
                val mqttAll = if (tasmota == null) {
                    MqttAndDiyClient.connectAndDiscoverMqttEntities(context, bridge, UniversalOnboardingEngine.activeDashboardTilesRef)
                } else emptyList()
                val matched = tasmota ?: mqttAll.firstOrNull {
                    (devId.isNotBlank() && it.uniqueId.equals(devId, ignoreCase = true)) ||
                            (cleanIp.isNotBlank() && it.ipAddress == cleanIp)
                }
                if (matched != null) {
                    tile.copy(
                        ipAddress = matched.ipAddress.ifBlank { tile.ipAddress },
                        isOnline = true,
                        isActive = matched.isActive,
                        isSocket2Active = matched.isSocket2Active,
                        powerWatts = matched.powerWatts,
                        voltageVolts = matched.voltageVolts,
                        currentMilliamps = matched.currentMilliamps,
                        totalEnergyKwh = matched.totalEnergyKwh
                    ) to "Synced MQTT / Tasmota telemetry (${if (matched.isActive) "ON" else "OFF"})"
                } else null
            }

            providerKey.contains("HUBITAT") || devId.startsWith("hubitat_") -> {
                val matched = SmartHomeApiClient.fetchHubitatDevices(bridge).firstOrNull {
                    it.serialNumber.equals(devId, ignoreCase = true) || it.title.equals(tile.title, ignoreCase = true)
                }
                if (matched != null) {
                    matched.copy(id = tile.id, roomOrSubtitle = tile.roomOrSubtitle) to "Synced Hubitat Maker API state"
                } else null
            }

            providerKey.contains("HOMEY") || devId.startsWith("homey_") -> {
                val matched = SmartHomeApiClient.fetchHomeyDevices(bridge).firstOrNull {
                    it.serialNumber.equals(devId, ignoreCase = true) || it.title.equals(tile.title, ignoreCase = true)
                }
                if (matched != null) {
                    matched.copy(id = tile.id, roomOrSubtitle = tile.roomOrSubtitle) to "Synced Homey Bridge state"
                } else null
            }

            providerKey.contains("SMARTTHINGS") -> {
                val matched = SmartHomeApiClient.fetchSmartThingsDevices(bridge).firstOrNull {
                    it.serialNumber.equals(devId, ignoreCase = true) || it.entityId.equals(tile.entityId, ignoreCase = true)
                }
                if (matched != null) {
                    matched.copy(id = tile.id, roomOrSubtitle = tile.roomOrSubtitle) to "Synced SmartThings Cloud state"
                } else null
            }

            providerKey.contains("HOME_ASSISTANT") || tile.entityId.contains(".") -> {
                val entities = SmartHomeApiClient.fetchHomeAssistantEntities(
                    haBaseUrl = bridge.haUrl,
                    haToken = bridge.haToken,
                    bypassCircuitBreaker = true
                ).getOrNull().orEmpty()
                val live = entities.firstOrNull {
                    it.entityId.equals(tile.entityId, ignoreCase = true) ||
                            (tile.entityId.isBlank() && it.friendlyName.equals(tile.title, ignoreCase = true))
                }
                if (live != null && !live.state.equals("unavailable", ignoreCase = true) && !live.state.equals("unknown", ignoreCase = true)) {
                    val (mergedCaps, mergedHash) = DeviceRepository.mergeCapabilitiesWithSchemaEvolution(tile, live.capabilities)
                    tile.copy(
                        entityId = tile.entityId.ifBlank { live.entityId },
                        isOnline = true,
                        isActive = live.state.equals("on", ignoreCase = true) || live.state.equals("playing", ignoreCase = true) || live.state.equals("locked", ignoreCase = true),
                        temperature = if (live.temperature != 0f) live.temperature else tile.temperature,
                        targetTemperature = if (live.targetTemperature != 0f) live.targetTemperature else tile.targetTemperature,
                        volume = live.levelOrBrightness,
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash
                    ) to "Synced Home Assistant entity (${live.state.uppercase(Locale.US)})"
                } else null
            }

            else -> {
                if (ClaraApplication.isTuyaSdkReady(context)) {
                    runCatching { TuyaApiClient.discoverNativeSdkDevices(forceRefresh = true) }
                }
                val udpFound = SmartHomeApiClient.listenForTuyaUdpBroadcasts(timeoutMs = 1100L)
                val udpMatch = udpFound.firstOrNull {
                    devId.isNotBlank() && it.hardwareIdOrMac.equals(devId, ignoreCase = true)
                }
                val resolvedLanIp = udpMatch?.ipAddress?.ifBlank { null }
                    ?: TuyaApiClient.resolveKnownLanIp(devId).ifBlank { null }
                    ?: cleanIp
                val allTuya = TuyaApiClient.discoverAllTuyaDevices(bridge).getOrNull().orEmpty()
                val dev = allTuya.firstOrNull {
                    (devId.isNotBlank() && it.deviceId.equals(devId, ignoreCase = true)) ||
                            it.name.equals(tile.title, ignoreCase = true)
                }
                if (dev != null && (dev.isOnline || udpMatch != null ||
                            (resolvedLanIp.isNotBlank() && SmartHomeApiClient.isPortOpenInternal(resolvedLanIp, 6668, 550)))
                ) {
                    val (mergedCaps, mergedHash) = DeviceRepository.mergeCapabilitiesWithSchemaEvolution(tile, dev.capabilities)
                    tile.copy(
                        ipAddress = resolvedLanIp.ifBlank { tile.ipAddress },
                        isOnline = true,
                        isActive = dev.isActive,
                        isSocket2Active = dev.isSocket2Active,
                        multiGangSocketCount = maxOf(tile.multiGangSocketCount, dev.multiGangSocketCount),
                        powerWatts = dev.powerWatts,
                        voltageVolts = dev.voltageVolts,
                        currentMilliamps = dev.currentMilliamps,
                        totalEnergyKwh = dev.totalEnergyKwh,
                        countdown1Seconds = dev.countdown1Seconds,
                        countdown2Seconds = dev.countdown2Seconds,
                        hasExplicitPowerTelemetry = tile.hasExplicitPowerTelemetry || dev.hasPowerTelemetry,
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash
                    ) to "Synced Tuya telemetry (${if (dev.isActive) "ON" else "OFF"}${if (dev.hasPowerTelemetry) ", ${"%.1f".format(Locale.US, dev.powerWatts)} W" else ""})"
                } else if (resolvedLanIp.isNotBlank() &&
                    (tile.secretCode.length == 16 ||
                            TuyaApiClient.resolveKnownLocalKey(devId, tile.macAddress).length == 16 ||
                            ClaraSecretVault.hasVerifiedLocalControlKey(context, "TUYA", devId, resolvedLanIp, tile.macAddress)) &&
                    TuyaApiClient.isDeviceReachableOrOnline(devId, resolvedLanIp, reportedCloudOnline = false)
                ) {
                    val cachedCaps = TuyaApiClient.capabilitiesByDevice[devId].orEmpty()
                    val (mergedCaps, mergedHash) = DeviceRepository.mergeCapabilitiesWithSchemaEvolution(tile, cachedCaps)
                    tile.copy(
                        ipAddress = resolvedLanIp,
                        isOnline = true,
                        capabilities = mergedCaps,
                        capabilitySchemaHash = mergedHash
                    ) to "Verified local LAN 6668 connection ($resolvedLanIp)"
                } else null
            }
        }
    }

    val refreshElapsedMs = (System.currentTimeMillis() - refreshStartMs).coerceAtLeast(1L)
    if (outcome == null) {
        val timeoutErr = Exception("'${tile.title}' did not respond within 5 seconds on $providerKey. Check device power, Wi-Fi, or credentials.")
        if (isHardwareLifecycleTrackedTile(tile)) {
            DeviceLifecycleRecorder.recordTileTransition(
                tile = tile,
                transition = MandatoryLifecycleTransition.OFFLINE,
                previousState = DeviceLifecycleState.CAPABILITY_LEARNING.name,
                newState = DeviceLifecycleState.OFFLINE.name,
                latencyMs = refreshElapsedMs,
                protocolResultCode = "TELEMETRY_TIMEOUT_5S",
                failureCategory = FailureCategory.TRANSPORT_FAILURE
            )
        }
        return@withContext Result.failure(timeoutErr)
    }

    val (rawRefreshed, summaryText) = outcome
    val syncedCaps = rawRefreshed.capabilities.map { cap ->
        when (cap.key.lowercase(Locale.US)) {
            "switch", "switch_1", "switch1", "power", "ha_state" -> cap.copy(currentValue = rawRefreshed.isActive.toString())
            "switch_2", "switch2", "socket_2" -> cap.copy(currentValue = rawRefreshed.isSocket2Active.toString())
            "cur_power", "power_w" -> cap.copy(currentValue = "%.1f".format(Locale.US, rawRefreshed.powerWatts))
            else -> cap
        }
    }
    val parsedFinalEndpoint = EndpointSplitter.splitHostAndPort(rawRefreshed.ipAddress)
    if (parsedFinalEndpoint.hostIp.isNotBlank()) {
        ClaraDeviceRegistryStore.getInstance(context).updateDeviceEndpointAndMac(
            canonicalGuid = rawRefreshed.canonicalGuid.ifBlank { rawRefreshed.resolvedCanonicalGuid() },
            newHostIp = parsedFinalEndpoint.hostIp,
            newPort = parsedFinalEndpoint.port,
            learnedMac = rawRefreshed.macAddress
        )
    }
    val finalized = rawRefreshed.copy(
        capabilities = syncedCaps,
        capabilitySchemaHash = CapabilitySchemaHasher.computeHash(syncedCaps).ifBlank { rawRefreshed.capabilitySchemaHash },
        lifecycleState = DeviceLifecycleState.OPERATIONAL,
        transactionState = CommandTransactionState.CONFIRMED,
        stateAuthorityTier = resolveVerifiedTransportAuthorityTier(rawRefreshed),
        lastConfirmedEpochMs = System.currentTimeMillis()
    )
    if (isHardwareLifecycleTrackedTile(finalized)) {
        DeviceLifecycleRecorder.recordTileTransition(
            tile = finalized,
            transition = MandatoryLifecycleTransition.CAPABILITY_RECEIVED,
            previousState = DeviceLifecycleState.CAPABILITY_LEARNING.name,
            newState = DeviceLifecycleState.CAPABILITIES_LOADED.name,
            latencyMs = refreshElapsedMs,
            protocolResultCode = "CAPS_${syncedCaps.size}_OK"
        )
        DeviceLifecycleRecorder.recordTileTransition(
            tile = finalized,
            transition = MandatoryLifecycleTransition.ONLINE,
            previousState = DeviceLifecycleState.CAPABILITIES_LOADED.name,
            newState = DeviceLifecycleState.ONLINE.name,
            latencyMs = 0L,
            protocolResultCode = "TELEMETRY_SYNC_OK"
        )
    }

    withContext(Dispatchers.Main) {
        val liveTiles = UniversalOnboardingEngine.activeDashboardTilesRef
        if (liveTiles != null) {
            val idx = liveTiles.indexOfFirst { it.id == tile.id }
            if (idx >= 0) {
                liveTiles[idx] = DeviceRepository.reconcileRemoteTileUpdate(
                    existing = liveTiles[idx],
                    incoming = finalized,
                    guardWindowMs = 0L,
                    incomingAuthority = finalized.stateAuthorityTier,
                    sourceName = "Manual Modal Refresh"
                )
                DeviceRepository.saveTiles(context, liveTiles)
            }
        } else {
            val diskTiles = DeviceRepository.loadTiles(context).toMutableList()
            val idx = diskTiles.indexOfFirst { it.id == tile.id }
            if (idx >= 0) {
                diskTiles[idx] = finalized
                DeviceRepository.saveTiles(context, diskTiles)
            }
        }
    }

    Result.success(finalized to summaryText)
}
