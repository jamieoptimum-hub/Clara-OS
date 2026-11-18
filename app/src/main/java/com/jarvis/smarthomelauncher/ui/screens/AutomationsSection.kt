package com.jarvis.smarthomelauncher.ui.screens

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private enum class AutomationSectionTab {
    ACTIVE_RULES,
    CREATE_OR_EDIT,
    EXECUTION_LOGS
}

private enum class DaysPresetOption(val label: String, val days: Set<Int>) {
    EVERY_DAY("Every Day (Mon\u2013Sun)", setOf(1, 2, 3, 4, 5, 6, 7)),
    WEEKDAYS("Weekdays Only (Mon\u2013Fri)", setOf(1, 2, 3, 4, 5)),
    WEEKENDS("Weekends Only (Sat\u2013Sun)", setOf(6, 7)),
    CUSTOM("Select Specific Days...", emptySet())
}

private data class SelectableDeviceTarget(
    val virtualId: String,
    val displayTitle: String,
    val subtitle: String,
    val baseTile: SmartTile,
    val socketSelection: Int = 0 // 0 = All/Default, 1 = Socket 1, 2 = Socket 2
)

private fun contextualActionOptionsForTile(
    tile: SmartTile?,
    socketSelection: Int
): List<AutomationActionType> {
    if (tile == null) {
        return listOf(
            AutomationActionType.TURN_ON,
            AutomationActionType.TURN_OFF,
            AutomationActionType.TOGGLE
        )
    }
    val effectiveCaps = tile.capabilities.ifEmpty { SmartHomeApiClient.buildDefaultCapabilitiesForTile(tile) }
    val renderers = effectiveCaps.map { CapabilityClassifier.inferRendererType(it) }.toSet()
    val hasTempDial = CapabilityRendererType.TEMPERATURE_DIAL in renderers || tile.isThermostatTile || tile.hasTemperatureSetpointCapability
    val hasLevelSlider = renderers.any {
        it in setOf(
            CapabilityRendererType.BRIGHTNESS_SLIDER,
            CapabilityRendererType.FAN_SPEED_STEPPER,
            CapabilityRendererType.COVER_POSITION_SLIDER,
            CapabilityRendererType.VOLUME_SLIDER,
            CapabilityRendererType.SLIDER,
            CapabilityRendererType.DIAL
        )
    } || tile.hasDimmingCapability || tile.isTowerFanTile

    return when {
        tile.isMultiGangSwitch && socketSelection == 1 -> listOf(
            AutomationActionType.SOCKET_1_ON,
            AutomationActionType.SOCKET_1_OFF,
            AutomationActionType.BOTH_SOCKETS_ON,
            AutomationActionType.BOTH_SOCKETS_OFF,
            AutomationActionType.TOGGLE
        )
        tile.isMultiGangSwitch && socketSelection == 2 -> listOf(
            AutomationActionType.SOCKET_2_ON,
            AutomationActionType.SOCKET_2_OFF,
            AutomationActionType.BOTH_SOCKETS_ON,
            AutomationActionType.BOTH_SOCKETS_OFF,
            AutomationActionType.TOGGLE
        )
        tile.isMultiGangSwitch -> listOf(
            AutomationActionType.BOTH_SOCKETS_ON,
            AutomationActionType.BOTH_SOCKETS_OFF,
            AutomationActionType.SOCKET_1_ON,
            AutomationActionType.SOCKET_1_OFF,
            AutomationActionType.SOCKET_2_ON,
            AutomationActionType.SOCKET_2_OFF,
            AutomationActionType.TOGGLE
        )
        tile.isVehicleTile || tile.deviceClass == DeviceClass.VEHICLE -> listOf(
            AutomationActionType.TURN_ON,
            AutomationActionType.TURN_OFF,
            AutomationActionType.SET_TEMPERATURE
        )
        tile.isThermostatTile -> listOf(
            AutomationActionType.SET_TEMPERATURE,
            AutomationActionType.SET_HVAC_MODE,
            AutomationActionType.TURN_ON,
            AutomationActionType.TURN_OFF
        )
        hasTempDial -> listOf(
            AutomationActionType.SET_TEMPERATURE,
            AutomationActionType.TURN_ON,
            AutomationActionType.TURN_OFF,
            AutomationActionType.TOGGLE
        )
        tile.isCameraTile || tile.deviceClass == DeviceClass.CAMERA -> listOf(
            AutomationActionType.OPEN_LIVE_CAMERA,
            AutomationActionType.CLOSE_LIVE_CAMERA
        )
        tile.deviceSubtype in setOf(DeviceSubtype.SPEAKER, DeviceSubtype.CAST_GROUP) -> listOf(
            AutomationActionType.SET_LEVEL_OR_VOLUME,
            AutomationActionType.RING_NEST_MINI_CHIME,
            AutomationActionType.SPEAK_ANNOUNCEMENT
        )
        tile.deviceSubtype == DeviceSubtype.TV -> listOf(
            AutomationActionType.TURN_ON,
            AutomationActionType.TURN_OFF,
            AutomationActionType.SET_LEVEL_OR_VOLUME
        )
        hasLevelSlider || tile.deviceClass == DeviceClass.LIGHT || tile.deviceSubtype in setOf(DeviceSubtype.BLIND, DeviceSubtype.GARAGE_DOOR, DeviceSubtype.TOWER_FAN) -> listOf(
            AutomationActionType.TURN_ON,
            AutomationActionType.TURN_OFF,
            AutomationActionType.TOGGLE,
            AutomationActionType.SET_LEVEL_OR_VOLUME
        )
        else -> listOf(
            AutomationActionType.TURN_ON,
            AutomationActionType.TURN_OFF,
            AutomationActionType.TOGGLE
        )
    }
}

private fun buildAutoGeneratedRuleName(
    triggerType: AutomationTriggerType,
    scheduleHour: Int,
    scheduleMinute: Int,
    triggerOnSunset: Boolean,
    triggerTileTitle: String,
    triggerDeviceState: DeviceTriggerTargetState,
    comparisonOp: ComparisonOperator,
    thresholdText: String,
    weatherKeyword: String,
    actions: List<AutomationAction>
): String {
    val firstAction = actions.firstOrNull()
    val rawTargetTitle = firstAction?.targetTileTitle?.trim().orEmpty()
    val cleanActionTarget = rawTargetTitle
        .substringBefore(" \u2014 ")
        .substringBefore(" (Socket")
        .trim()
    val customSubSocketLabel = rawTargetTitle
        .substringAfter(" \u2014 ", "")
        .substringBefore(" (")
        .trim()

    val actionPhrase = when (firstAction?.actionType) {
        null -> "Smart Routine"
        AutomationActionType.TURN_ON -> "Turn On ${cleanActionTarget.ifBlank { "Device" }}"
        AutomationActionType.TURN_OFF -> "Turn Off ${cleanActionTarget.ifBlank { "Device" }}"
        AutomationActionType.TOGGLE -> "Toggle ${cleanActionTarget.ifBlank { "Device" }}"
        AutomationActionType.SOCKET_1_ON -> {
            val s1 = customSubSocketLabel.takeIf { it.isNotBlank() && !it.startsWith("Both", true) } ?: "Socket 1"
            "Turn On ${cleanActionTarget.ifBlank { "Plug" }} ($s1)"
        }
        AutomationActionType.SOCKET_1_OFF -> {
            val s1 = customSubSocketLabel.takeIf { it.isNotBlank() && !it.startsWith("Both", true) } ?: "Socket 1"
            "Turn Off ${cleanActionTarget.ifBlank { "Plug" }} ($s1)"
        }
        AutomationActionType.SOCKET_2_ON -> {
            val s2 = customSubSocketLabel.takeIf { it.isNotBlank() && !it.startsWith("Both", true) } ?: "Socket 2"
            "Turn On ${cleanActionTarget.ifBlank { "Plug" }} ($s2)"
        }
        AutomationActionType.SOCKET_2_OFF -> {
            val s2 = customSubSocketLabel.takeIf { it.isNotBlank() && !it.startsWith("Both", true) } ?: "Socket 2"
            "Turn Off ${cleanActionTarget.ifBlank { "Plug" }} ($s2)"
        }
        AutomationActionType.BOTH_SOCKETS_ON -> "Turn On ${cleanActionTarget.ifBlank { "Smart Plug" }} (Both Sockets)"
        AutomationActionType.BOTH_SOCKETS_OFF -> "Turn Off ${cleanActionTarget.ifBlank { "Smart Plug" }} (Both Sockets)"
        AutomationActionType.SET_TEMPERATURE ->
            "Warm ${cleanActionTarget.ifBlank { "Heating" }} to ${"%.1f".format(firstAction.numericValue)}\u00B0C"
        AutomationActionType.SET_HVAC_MODE ->
            "Set ${cleanActionTarget.ifBlank { "Heating" }} to ${firstAction.textValue}"
        AutomationActionType.SET_LEVEL_OR_VOLUME ->
            "Set ${cleanActionTarget.ifBlank { "Device" }} to ${firstAction.numericValue.roundToInt()}%"
        AutomationActionType.RING_NEST_MINI_CHIME ->
            "Chime ${cleanActionTarget.ifBlank { "Speakers" }}"
        AutomationActionType.OPEN_LIVE_CAMERA ->
            "Show ${cleanActionTarget.ifBlank { "Camera" }}"
        AutomationActionType.CLOSE_LIVE_CAMERA ->
            "Close ${cleanActionTarget.ifBlank { "Camera" }}"
        AutomationActionType.WAIT_DELAY ->
            if (actions.size > 1) "Delayed ${actions[1].toSummaryText()}" else "Timed Wait Routine"
        AutomationActionType.EXECUTE_SCENE ->
            "Run ${firstAction.textValue}"
        AutomationActionType.SPEAK_ANNOUNCEMENT ->
            "Voice Announcement"
    }

    val extraStepsSuffix = if (actions.size > 1) " (+${actions.size - 1})" else ""
    val cleanTriggerDevice = triggerTileTitle
        .substringBefore(" \u2014 ")
        .substringBefore(" (Socket")
        .trim()
        .ifBlank { "Device" }
    val thresholdVal = thresholdText.trim().ifBlank { "19" }

    val triggerPhrase = when (triggerType) {
        AutomationTriggerType.TIME_SCHEDULE ->
            "at %02d:%02d".format(scheduleHour, scheduleMinute)
        AutomationTriggerType.SUNRISE_SUNSET ->
            if (triggerOnSunset) "at Sunset" else "at Sunrise"
        AutomationTriggerType.DEVICE_STATE_CHANGE ->
            "when $cleanTriggerDevice ${triggerDeviceState.label}"
        AutomationTriggerType.TEMPERATURE_THRESHOLD ->
            "when $cleanTriggerDevice ${comparisonOp.symbol} ${thresholdVal}\u00B0C"
        AutomationTriggerType.POWER_WATTS_THRESHOLD ->
            "when $cleanTriggerDevice ${comparisonOp.symbol} ${thresholdVal}W"
        AutomationTriggerType.DOORBELL_RING ->
            "on Doorbell Ring"
        AutomationTriggerType.WEATHER_CONDITION ->
            if (weatherKeyword.equals("TEMP", ignoreCase = true)) {
                "when Outdoor Temp ${comparisonOp.symbol} ${thresholdVal}\u00B0C"
            } else {
                "when Weather is $weatherKeyword"
            }
        AutomationTriggerType.SECURITY_MODE_IS ->
            "when Security Mode changes"
        AutomationTriggerType.TARIFF_WINDOW_IS ->
            "during Tariff Window"
        AutomationTriggerType.DAYLIGHT_IS ->
            "on Daylight State"
        AutomationTriggerType.CAPABILITY_VALUE_MATCH ->
            "when $cleanTriggerDevice capability matches"
    }

    return "$actionPhrase$extraStepsSuffix $triggerPhrase"
}

@Composable
fun AutomationsAndSchedulesSection(
    context: Context,
    tiles: MutableList<SmartTile>,
    automationRules: MutableList<AutomationRule>,
    bridgeSettings: BridgeSettings,
    audioManager: AudioManager,
    compactInputTextStyle: TextStyle,
    onSpeak: (String) -> Unit,
    onStatusBanner: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val automationLogs by JarvisLiveBridge.automationLogsFlow.collectAsState()
    val suggestedAutomations by JarvisLiveBridge.suggestedAutomationsFlow.collectAsState()

    LaunchedEffect(Unit) {
        DeviceRepository.loadAutomationLogs(context)
    }

    val controllableTiles = remember(tiles.toList()) {
        tiles.filter {
            it.type != TileType.CLOCK_WEATHER &&
                    it.type != TileType.JARVIS_SCENES &&
                    it.type != TileType.PHOTO_SLIDESHOW &&
                    it.type != TileType.GROUP_CARD &&
                    it.type != TileType.ENERGY_MONITOR &&
                    it.type != TileType.MACRO_TILE
        }
    }

    // Expand Dynamic Capability/Class Selectors + Multi-Gang Plugs into selectable targets
    val expandedDeviceTargets = remember(controllableTiles) {
        buildList {
            val dynamicClasses = listOf(
                Triple("DYNAMIC::LIGHT", "All Lighting Devices (Dynamic Group)", DeviceClass.LIGHT),
                Triple("DYNAMIC::SWITCH", "All Smart Plugs & Switches (Dynamic Group)", DeviceClass.SWITCH),
                Triple("DYNAMIC::CLIMATE", "All Climate & Heating Devices (Dynamic Group)", DeviceClass.CLIMATE),
                Triple("DYNAMIC::VEHICLE", "All Vehicles & EV Chargers (Dynamic Group)", DeviceClass.VEHICLE),
                Triple("DYNAMIC::CAMERA", "All Security Cameras & Doorbells (Dynamic Group)", DeviceClass.CAMERA)
            )
            for ((dynId, dynTitle, dynClass) in dynamicClasses) {
                add(
                    SelectableDeviceTarget(
                        virtualId = dynId,
                        displayTitle = dynTitle,
                        subtitle = "Resolves all ${dynClass.label} devices dynamically at runtime",
                        baseTile = SmartTile(
                            id = dynId,
                            title = dynTitle,
                            type = when (dynClass) {
                                DeviceClass.LIGHT -> TileType.HUE_LIGHT
                                DeviceClass.CLIMATE -> TileType.TUYA_RADIATOR
                                DeviceClass.VEHICLE -> TileType.JAGUAR_VEHICLE
                                DeviceClass.CAMERA -> TileType.IMOU_CAMERA
                                else -> TileType.SMARTKNIGHT_PLUG
                            },
                            deviceClassOverride = dynClass
                        ),
                        socketSelection = 0
                    )
                )
            }
            for (tile in controllableTiles) {
                if (tile.isMultiGangSwitch) {
                    val s1Name = tile.socket1Name.ifBlank { "Socket 1" }
                    val s2Name = tile.socket2Name.ifBlank { "Socket 2" }
                    add(
                        SelectableDeviceTarget(
                            virtualId = "${tile.id}::SOCKET_1",
                            displayTitle = "${tile.title} \u2014 $s1Name",
                            subtitle = "Controls only $s1Name on ${tile.title}",
                            baseTile = tile,
                            socketSelection = 1
                        )
                    )
                    add(
                        SelectableDeviceTarget(
                            virtualId = "${tile.id}::SOCKET_2",
                            displayTitle = "${tile.title} \u2014 $s2Name",
                            subtitle = "Controls only $s2Name on ${tile.title}",
                            baseTile = tile,
                            socketSelection = 2
                        )
                    )
                    add(
                        SelectableDeviceTarget(
                            virtualId = "${tile.id}::BOTH",
                            displayTitle = "${tile.title} \u2014 Both Sockets ($s1Name & $s2Name)",
                            subtitle = "Controls both $s1Name & $s2Name together",
                            baseTile = tile,
                            socketSelection = 0
                        )
                    )
                } else {
                    add(
                        SelectableDeviceTarget(
                            virtualId = tile.id,
                            displayTitle = tile.title,
                            subtitle = tile.type.brandName,
                            baseTile = tile,
                            socketSelection = 0
                        )
                    )
                }
            }
        }
    }

    var activeSubTab by remember {
        mutableStateOf(AutomationSectionTab.ACTIVE_RULES)
    }
    var editingRuleId by remember { mutableStateOf<String?>(null) }
    var showOnlyFailedLogs by remember { mutableStateOf(false) }
    var aiGoalInputText by remember { mutableStateOf("") }
    var activeSimulationReport by remember { mutableStateOf<SimulationReport?>(null) }
    val draftBranches = remember { mutableStateListOf<AutomationBranch>() }
    val draftOtherwiseActions = remember { mutableStateListOf<AutomationAction>() }

    var draftName by remember { mutableStateOf("") }
    var hasCustomName by remember { mutableStateOf(false) }
    var showRenameField by remember { mutableStateOf(false) }
    var draftPinAsMacroTile by remember { mutableStateOf(false) }

    var draftTriggerType by remember { mutableStateOf(AutomationTriggerType.TIME_SCHEDULE) }
    var draftHour by remember { mutableIntStateOf(23) }
    var draftMinute by remember { mutableIntStateOf(0) }
    var draftDays by remember { mutableStateOf(setOf(1, 2, 3, 4, 5, 6, 7)) }
    var draftDaysPreset by remember { mutableStateOf(DaysPresetOption.EVERY_DAY) }
    var draftTriggerOnSunset by remember { mutableStateOf(true) }

    fun buildSelectorForTarget(target: SelectableDeviceTarget?): AutomationTargetSelector {
        val vId = target?.virtualId.orEmpty()
        return if (vId.startsWith("DYNAMIC::")) {
            val cls = runCatching { DeviceClass.valueOf(vId.removePrefix("DYNAMIC::")) }.getOrNull()
            AutomationTargetSelector(
                scope = AutomationTargetScope.DYNAMIC_SELECTOR,
                deviceClass = cls
            )
        } else {
            AutomationTargetSelector()
        }
    }

    val defaultTriggerTarget = expandedDeviceTargets.firstOrNull { !it.virtualId.startsWith("DYNAMIC::") }
        ?: expandedDeviceTargets.firstOrNull()
    var draftTriggerTileId by remember(defaultTriggerTarget) {
        mutableStateOf(defaultTriggerTarget?.virtualId ?: "")
    }
    var draftTriggerTileTitle by remember(defaultTriggerTarget) {
        mutableStateOf(defaultTriggerTarget?.displayTitle ?: "")
    }
    var draftDeviceTriggerState by remember { mutableStateOf(DeviceTriggerTargetState.TURNED_ON) }
    var draftComparisonOp by remember { mutableStateOf(ComparisonOperator.BELOW) }
    var draftThresholdText by remember { mutableStateOf("19.0") }
    var draftWeatherKeyword by remember { mutableStateOf("Rain") }
    var draftRestrictWindow by remember { mutableStateOf(false) }
    var draftWindowStartHour by remember { mutableIntStateOf(18) }
    var draftWindowStartMinute by remember { mutableIntStateOf(0) }
    var draftWindowEndHour by remember { mutableIntStateOf(7) }
    var draftWindowEndMinute by remember { mutableIntStateOf(0) }
    var draftCombinator by remember { mutableStateOf(ConditionCombinator.AND) }
    val draftSecondaryConditions = remember { mutableStateListOf<AutomationSecondaryCondition>() }

    val draftActions = remember {
        mutableStateListOf<AutomationAction>().apply {
            val firstTarget = defaultTriggerTarget
            if (firstTarget != null) {
                add(
                    AutomationAction(
                        targetTileId = firstTarget.virtualId,
                        targetTileTitle = firstTarget.displayTitle,
                        actionType = when (firstTarget.socketSelection) {
                            1 -> AutomationActionType.SOCKET_1_OFF
                            2 -> AutomationActionType.SOCKET_2_OFF
                            else -> AutomationActionType.TURN_OFF
                        },
                        numericValue = 21.0f,
                        textValue = "HEAT",
                        targetSelector = buildSelectorForTarget(firstTarget)
                    )
                )
            }
        }
    }

    val autoGeneratedName = remember(
        draftTriggerType,
        draftHour,
        draftMinute,
        draftTriggerOnSunset,
        draftTriggerTileTitle,
        draftDeviceTriggerState,
        draftComparisonOp,
        draftThresholdText,
        draftWeatherKeyword,
        draftActions.toList()
    ) {
        buildAutoGeneratedRuleName(
            triggerType = draftTriggerType,
            scheduleHour = draftHour,
            scheduleMinute = draftMinute,
            triggerOnSunset = draftTriggerOnSunset,
            triggerTileTitle = draftTriggerTileTitle,
            triggerDeviceState = draftDeviceTriggerState,
            comparisonOp = draftComparisonOp,
            thresholdText = draftThresholdText,
            weatherKeyword = draftWeatherKeyword,
            actions = draftActions.toList()
        )
    }

    // Automatically keep rule name updated unless the user explicitly typed a custom name
    LaunchedEffect(autoGeneratedName, hasCustomName, editingRuleId) {
        if (!hasCustomName && editingRuleId == null) {
            draftName = autoGeneratedName
        }
    }

    fun isRulePinnedAsTile(rule: AutomationRule): Boolean {
        return tiles.any {
            it.type == TileType.MACRO_TILE &&
                    (it.entityId == rule.id || it.serialNumber == rule.id || it.title.equals(rule.name, ignoreCase = true))
        }
    }

    fun pinOrUnpinRuleAsDashboardTile(rule: AutomationRule, shouldPin: Boolean) {
        val existingIdx = tiles.indexOfFirst {
            it.type == TileType.MACRO_TILE &&
                    (it.entityId == rule.id || it.serialNumber == rule.id || it.title.equals(rule.name, ignoreCase = true))
        }
        if (shouldPin) {
            if (existingIdx >= 0) {
                tiles[existingIdx] = tiles[existingIdx].copy(
                    title = rule.name,
                    entityId = rule.id,
                    serialNumber = rule.id,
                    isVisible = true
                )
            } else {
                tiles.add(
                    SmartTile(
                        title = rule.name,
                        roomOrSubtitle = "",
                        type = TileType.MACRO_TILE,
                        colSpan = 1,
                        rowSpan = 1,
                        isVisible = true,
                        isActive = false,
                        entityId = rule.id,
                        serialNumber = rule.id
                    )
                )
            }
            DeviceRepository.saveTiles(context, tiles)
            onStatusBanner("Pinned '${rule.name}' to your dashboard as a single-tap Macro Tile!")
        } else {
            if (existingIdx >= 0) {
                tiles.removeAt(existingIdx)
                DeviceRepository.saveTiles(context, tiles)
                onStatusBanner("Removed Macro Tile '${rule.name}' from dashboard.")
            }
        }
    }

    fun resetDraftForNewRule() {
        editingRuleId = null
        hasCustomName = false
        showRenameField = false
        draftPinAsMacroTile = false
        draftTriggerType = AutomationTriggerType.TIME_SCHEDULE
        draftHour = 23
        draftMinute = 0
        draftDays = setOf(1, 2, 3, 4, 5, 6, 7)
        draftDaysPreset = DaysPresetOption.EVERY_DAY
        draftTriggerOnSunset = true
        draftTriggerTileId = defaultTriggerTarget?.virtualId ?: ""
        draftTriggerTileTitle = defaultTriggerTarget?.displayTitle ?: ""
        draftDeviceTriggerState = DeviceTriggerTargetState.TURNED_ON
        draftComparisonOp = ComparisonOperator.BELOW
        draftThresholdText = "19.0"
        draftWeatherKeyword = "Rain"
        draftRestrictWindow = false
        draftCombinator = ConditionCombinator.AND
        draftSecondaryConditions.clear()
        draftBranches.clear()
        draftOtherwiseActions.clear()
        draftActions.clear()
        val firstTarget = expandedDeviceTargets.firstOrNull()
        if (firstTarget != null) {
            draftActions.add(
                AutomationAction(
                    targetTileId = firstTarget.virtualId,
                    targetTileTitle = firstTarget.displayTitle,
                    actionType = when (firstTarget.socketSelection) {
                        1 -> AutomationActionType.SOCKET_1_OFF
                        2 -> AutomationActionType.SOCKET_2_OFF
                        else -> AutomationActionType.TURN_OFF
                    },
                    numericValue = 21.0f,
                    textValue = "HEAT",
                    targetSelector = buildSelectorForTarget(firstTarget)
                )
            )
        }
        draftName = buildAutoGeneratedRuleName(
            triggerType = draftTriggerType,
            scheduleHour = draftHour,
            scheduleMinute = draftMinute,
            triggerOnSunset = draftTriggerOnSunset,
            triggerTileTitle = draftTriggerTileTitle,
            triggerDeviceState = draftDeviceTriggerState,
            comparisonOp = draftComparisonOp,
            thresholdText = draftThresholdText,
            weatherKeyword = draftWeatherKeyword,
            actions = draftActions.toList()
        )
    }

    fun loadRuleIntoBuilder(rule: AutomationRule) {
        editingRuleId = rule.id
        draftName = rule.name
        hasCustomName = true
        showRenameField = false
        draftPinAsMacroTile = isRulePinnedAsTile(rule)
        draftTriggerType = rule.triggerType
        draftHour = rule.scheduleHour
        draftMinute = rule.scheduleMinute
        draftDays = rule.daysOfWeek
        draftDaysPreset = when (rule.daysOfWeek) {
            setOf(1, 2, 3, 4, 5, 6, 7) -> DaysPresetOption.EVERY_DAY
            setOf(1, 2, 3, 4, 5) -> DaysPresetOption.WEEKDAYS
            setOf(6, 7) -> DaysPresetOption.WEEKENDS
            else -> DaysPresetOption.CUSTOM
        }
        draftTriggerOnSunset = rule.triggerOnSunset
        draftTriggerTileId = rule.triggerTileId
        draftTriggerTileTitle = rule.triggerTileTitle
        draftDeviceTriggerState = rule.triggerDeviceState
        draftComparisonOp = rule.comparisonOperator
        draftThresholdText = rule.thresholdValue.toString()
        draftWeatherKeyword = rule.weatherKeyword
        draftRestrictWindow = rule.restrictToTimeWindow
        draftWindowStartHour = rule.windowStartHour
        draftWindowStartMinute = rule.windowStartMinute
        draftWindowEndHour = rule.windowEndHour
        draftWindowEndMinute = rule.windowEndMinute
        draftCombinator = rule.combinator
        draftSecondaryConditions.clear()
        draftSecondaryConditions.addAll(rule.secondaryConditions)
        draftBranches.clear()
        draftBranches.addAll(rule.branches)
        draftOtherwiseActions.clear()
        draftOtherwiseActions.addAll(rule.otherwiseActions)
        draftActions.clear()
        if (rule.actions.isNotEmpty()) {
            draftActions.addAll(rule.actions)
        }
        activeSubTab = AutomationSectionTab.CREATE_OR_EDIT
    }

    val dayLabels = listOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")
    val waitUnitOptions = listOf("SECONDS" to "Seconds", "MINUTES" to "Minutes", "HOURS" to "Hours")
    val failedLogsCount = automationLogs.count { it.status != AutomationRunStatus.SUCCESS }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompactMobile = maxWidth < 600.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // =================================================================
            // TOP 3-TAB BAR: 1. Active Rules | 2. Create / Edit | 3. Activity Log
            // =================================================================
            Surface(
                color = GhTileSurface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, GhTileSurfaceElevated),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isActiveTab = activeSubTab == AutomationSectionTab.ACTIVE_RULES
                    Surface(
                        color = if (isActiveTab) GhTileBlueActive else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                        border = if (isActiveTab) BorderStroke(1.dp, GhAccentBlue.copy(alpha = 0.5f)) else null,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { activeSubTab = AutomationSectionTab.ACTIVE_RULES }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Schedule,
                                contentDescription = null,
                                tint = if (isActiveTab) GhAccentBlue else GhTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Active Rules (${automationRules.size})",
                                color = if (isActiveTab) GhTextPrimary else GhTextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (isActiveTab) FontWeight.Bold else FontWeight.Medium,
                                fontFamily = GhFont,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    val isCreateTab = activeSubTab == AutomationSectionTab.CREATE_OR_EDIT
                    Surface(
                        color = if (isCreateTab) GhTileBlueActive else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                        border = if (isCreateTab) BorderStroke(1.dp, GhAccentBlue.copy(alpha = 0.5f)) else null,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                if (!isCreateTab && editingRuleId == null) {
                                    resetDraftForNewRule()
                                }
                                activeSubTab = AutomationSectionTab.CREATE_OR_EDIT
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (editingRuleId != null) Icons.Outlined.Edit else Icons.Default.Add,
                                contentDescription = null,
                                tint = if (isCreateTab) GhAccentGreen else GhTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (editingRuleId != null) "Editing Rule" else "Create New Rule",
                                color = if (isCreateTab) GhTextPrimary else GhTextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (isCreateTab) FontWeight.Bold else FontWeight.Medium,
                                fontFamily = GhFont,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    val isLogTab = activeSubTab == AutomationSectionTab.EXECUTION_LOGS
                    Surface(
                        color = when {
                            isLogTab -> GhTileBlueActive
                            failedLogsCount > 0 -> GhTileRedActive.copy(alpha = 0.55f)
                            else -> Color.Transparent
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = when {
                            isLogTab -> BorderStroke(1.dp, GhAccentBlue.copy(alpha = 0.5f))
                            failedLogsCount > 0 -> BorderStroke(1.dp, GhAccentRed.copy(alpha = 0.5f))
                            else -> null
                        },
                        modifier = Modifier
                            .weight(1.1f)
                            .clickable { activeSubTab = AutomationSectionTab.EXECUTION_LOGS }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (failedLogsCount > 0) Icons.Outlined.ReportProblem else Icons.Outlined.History,
                                contentDescription = null,
                                tint = when {
                                    failedLogsCount > 0 -> GhAccentRed
                                    isLogTab -> GhAccentBlue
                                    else -> GhTextSecondary
                                },
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (failedLogsCount > 0) "Activity Log ($failedLogsCount Alert)" else "Activity Log (${automationLogs.size})",
                                color = if (isLogTab || failedLogsCount > 0) GhTextPrimary else GhTextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (isLogTab || failedLogsCount > 0) FontWeight.Bold else FontWeight.Medium,
                                fontFamily = GhFont,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // =================================================================
            // TAB 1: ACTIVE AUTOMATIONS & MACRO TILES
            // =================================================================
            when (activeSubTab) {
                AutomationSectionTab.ACTIVE_RULES -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Goal-Driven AI Routine Synthesizer & Dry-Run Scenario Simulator Card
                        item {
                            Surface(
                                color = GhTileSurface,
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.AutoAwesome,
                                                contentDescription = null,
                                                tint = Color(0xFF00E5FF),
                                                modifier = Modifier.size(19.dp)
                                            )
                                            Text(
                                                text = "AI Goal Builder & Dry-Run Scenario Simulator",
                                                color = GhTextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = GhFont
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = aiGoalInputText,
                                            onValueChange = { aiGoalInputText = it },
                                            label = {
                                                Text(
                                                    "Describe a goal (e.g. 'I leave for work at 8am, make my home energy efficient')",
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
                                            textStyle = compactInputTextStyle,
                                            shape = RoundedCornerShape(14.dp),
                                            colors = claraFieldColors(),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(
                                            onClick = {
                                                val cleanGoal = aiGoalInputText.trim()
                                                if (cleanGoal.isNotBlank()) {
                                                    val created = AutomationEngine.synthesizeAutomationFromGoal(
                                                        rawGoalText = cleanGoal,
                                                        context = context,
                                                        rules = automationRules,
                                                        tiles = tiles,
                                                        bridge = bridgeSettings,
                                                        onSpeak = onSpeak
                                                    )
                                                    if (created) {
                                                        aiGoalInputText = ""
                                                        onStatusBanner("SUCCESS: Clara AI built a multi-device capability routine from your goal!")
                                                    } else {
                                                        AutomationEngine.processSpokenVoiceCommand(
                                                            rawSpokenText = cleanGoal,
                                                            context = context,
                                                            rules = automationRules,
                                                            tiles = tiles,
                                                            bridge = bridgeSettings,
                                                            audioManager = audioManager,
                                                            scope = scope,
                                                            onSpeak = onSpeak
                                                        )
                                                        aiGoalInputText = ""
                                                    }
                                                }
                                            },
                                            enabled = aiGoalInputText.trim().length >= 5,
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = GhAccentGreen,
                                                contentColor = Color(0xFF0F1114)
                                            )
                                        ) {
                                            Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text("Build Routine", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        item {
                                            Text(
                                                text = "What-If Simulator:",
                                                color = GhTextSecondary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        items(SecurityCenterMode.entries) { simMode ->
                                            AssistChip(
                                                onClick = {
                                                    activeSimulationReport = AutomationEngine.simulateSecurityModeScenario(
                                                        mode = simMode,
                                                        tiles = tiles,
                                                        bridge = bridgeSettings
                                                    )
                                                },
                                                shape = RoundedCornerShape(50),
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Science,
                                                        contentDescription = null,
                                                        tint = Color(0xFF00E5FF),
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                },
                                                label = {
                                                    Text(
                                                        text = "Simulate ${simMode.label} Mode",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (suggestedAutomations.isNotEmpty()) {
                            item {
                                Surface(
                                    color = GhAccentGreen.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(18.dp),
                                    border = BorderStroke(1.2.dp, GhAccentGreen.copy(alpha = 0.65f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.AutoAwesome,
                                                contentDescription = null,
                                                tint = GhAccentGreen,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = "Suggested for You (Learned from Your Habits)",
                                                color = GhTextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = GhFont
                                            )
                                        }
                                        suggestedAutomations.forEach { suggestion ->
                                            Surface(
                                                color = GhTileSurface,
                                                shape = RoundedCornerShape(14.dp),
                                                border = BorderStroke(1.dp, GhTileSurfaceElevated),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                ResponsiveSplitRow(
                                                    isStacked = isCompactMobile,
                                                    firstContent = { mod ->
                                                        Column(
                                                            modifier = mod.padding(12.dp),
                                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            Text(
                                                                text = suggestion.draftRule.name,
                                                                color = GhTextPrimary,
                                                                fontSize = 14.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                            Text(
                                                                text = suggestion.reasonExplanation,
                                                                color = GhAccentGreen,
                                                                fontSize = 11.5.sp,
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                        }
                                                    },
                                                    secondContent = {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            TextButton(
                                                                onClick = {
                                                                    DeviceRepository.markHabitSuggestionDismissed(context, suggestion.id)
                                                                    JarvisLiveBridge.suggestedAutomationsFlow.value =
                                                                        suggestedAutomations.filterNot { it.id == suggestion.id }
                                                                }
                                                            ) {
                                                                Text("Dismiss", fontSize = 11.sp, color = GhTextSecondary)
                                                            }
                                                            Button(
                                                                onClick = {
                                                                    val createdRule = suggestion.draftRule.copy(
                                                                        id = UUID.randomUUID().toString(),
                                                                        isEnabled = true
                                                                    )
                                                                    automationRules.add(0, createdRule)
                                                                    DeviceRepository.saveAutomations(context, automationRules)
                                                                    DeviceRepository.markHabitSuggestionDismissed(context, suggestion.id)
                                                                    JarvisLiveBridge.suggestedAutomationsFlow.value =
                                                                        suggestedAutomations.filterNot { it.id == suggestion.id }
                                                                    onStatusBanner("Enabled learned routine '${createdRule.name}'!")
                                                                },
                                                                shape = RoundedCornerShape(50),
                                                                colors = ButtonDefaults.buttonColors(
                                                                    containerColor = GhAccentGreen,
                                                                    contentColor = Color(0xFF0F1114)
                                                                )
                                                            ) {
                                                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                                                                Spacer(modifier = Modifier.width(4.dp))
                                                                Text("Enable Routine", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (automationRules.isEmpty()) {
                            item {
                                Surface(
                                    color = GhTileSurface,
                                    shape = RoundedCornerShape(18.dp),
                                    border = BorderStroke(1.dp, GhTileSurfaceElevated),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(28.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Schedule,
                                            contentDescription = null,
                                            tint = GhAccentBlue,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Text(
                                            text = "No Active Schedules or Macros Yet",
                                            color = GhTextPrimary,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Tap 'Build Custom Schedule' below or use the 'Create New Rule' tab above to automate your devices or create a single-tap Macro Tile.",
                                            color = GhTextSecondary,
                                            fontSize = 12.sp
                                        )
                                        Button(
                                            onClick = {
                                                resetDraftForNewRule()
                                                activeSubTab = AutomationSectionTab.CREATE_OR_EDIT
                                            },
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = GhAccentGreen,
                                                contentColor = Color(0xFF0F1114)
                                            )
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Build Custom Schedule / Macro", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        } else {
                            itemsIndexed(automationRules, key = { _, r -> r.id }) { idx, rule ->
                                val latestLogForRule = automationLogs.firstOrNull { it.ruleId == rule.id }
                                val isPinnedOnDashboard = isRulePinnedAsTile(rule)

                                Surface(
                                    color = if (rule.isEnabled) GhTileSurface else GhTileSurface.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        when {
                                            latestLogForRule != null && latestLogForRule.status != AutomationRunStatus.SUCCESS ->
                                                GhAccentRed.copy(alpha = 0.6f)
                                            rule.isEnabled -> GhAccentGreen.copy(alpha = 0.45f)
                                            else -> GhTileSurfaceElevated
                                        }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { loadRuleIntoBuilder(rule) }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        ResponsiveSplitRow(
                                            isStacked = isCompactMobile,
                                            firstContent = { mod ->
                                                Column(
                                                    modifier = mod,
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Text(
                                                            text = rule.name,
                                                            color = GhTextPrimary,
                                                            fontSize = 15.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        if (isPinnedOnDashboard) {
                                                            Surface(
                                                                color = Color(0xFF00E5FF).copy(alpha = 0.18f),
                                                                shape = RoundedCornerShape(50),
                                                                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f))
                                                            ) {
                                                                Text(
                                                                    text = "Pinned Tile \u2713",
                                                                    color = Color(0xFF00E5FF),
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    Text(
                                                        text = rule.formattedIfCondition(),
                                                        color = GhAccentWarm,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = rule.formattedThenActions(),
                                                        color = GhAccentBlue,
                                                        fontSize = 12.sp
                                                    )
                                                }
                                            },
                                            secondContent = {
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    // Turn Macro into a Single-Run Dashboard Tile Button
                                                    FilledTonalButton(
                                                        onClick = {
                                                            pinOrUnpinRuleAsDashboardTile(rule, !isPinnedOnDashboard)
                                                        },
                                                        shape = RoundedCornerShape(50),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                        colors = ButtonDefaults.filledTonalButtonColors(
                                                            containerColor = if (isPinnedOnDashboard) Color(0xFF00E5FF).copy(alpha = 0.22f) else GhTileSurfaceElevated,
                                                            contentColor = if (isPinnedOnDashboard) Color(0xFF00E5FF) else GhAccentGreen
                                                        )
                                                    ) {
                                                        Icon(
                                                            imageVector = if (isPinnedOnDashboard) Icons.Default.Check else Icons.Outlined.AddToHomeScreen,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = if (isPinnedOnDashboard) "Tile Added" else "Pin as Tile",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }

                                                    FilledTonalButton(
                                                        onClick = {
                                                            activeSimulationReport = AutomationEngine.simulateRule(
                                                                rule = rule,
                                                                tiles = tiles,
                                                                bridge = bridgeSettings
                                                            )
                                                        },
                                                        shape = RoundedCornerShape(50),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                        colors = ButtonDefaults.filledTonalButtonColors(
                                                            containerColor = GhTileSurfaceElevated,
                                                            contentColor = Color(0xFF00E5FF)
                                                        )
                                                    ) {
                                                        Icon(Icons.Outlined.Science, contentDescription = null, modifier = Modifier.size(14.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Simulate", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                                    }

                                                    FilledTonalButton(
                                                        onClick = {
                                                            AutomationEngine.executeRuleActions(
                                                                rule = rule,
                                                                tiles = tiles,
                                                                bridge = bridgeSettings,
                                                                audioManager = audioManager,
                                                                scope = scope,
                                                                context = context,
                                                                onSpeak = onSpeak,
                                                                triggerSource = "Manual Test Button"
                                                            )
                                                            automationRules[idx] = rule.copy(lastTriggeredEpochMs = System.currentTimeMillis())
                                                            DeviceRepository.saveAutomations(context, automationRules)
                                                            onStatusBanner("Tested '${rule.name}' \u2014 view step-by-step results in the Activity Log tab!")
                                                        },
                                                        shape = RoundedCornerShape(50),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                        colors = ButtonDefaults.filledTonalButtonColors(
                                                            containerColor = GhTileSurfaceElevated,
                                                            contentColor = GhAccentGreen
                                                        )
                                                    ) {
                                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Run", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                                    }

                                                    FilledTonalButton(
                                                        onClick = { loadRuleIntoBuilder(rule) },
                                                        shape = RoundedCornerShape(50),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                        colors = ButtonDefaults.filledTonalButtonColors(
                                                            containerColor = GhTileSurfaceElevated,
                                                            contentColor = GhAccentBlue
                                                        )
                                                    ) {
                                                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Edit", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                                    }

                                                    Switch(
                                                        checked = rule.isEnabled,
                                                        onCheckedChange = { enabled ->
                                                            automationRules[idx] = rule.copy(isEnabled = enabled)
                                                            DeviceRepository.saveAutomations(context, automationRules)
                                                        },
                                                        colors = SwitchDefaults.colors(
                                                            checkedThumbColor = Color(0xFF0F1114),
                                                            checkedTrackColor = GhAccentGreen
                                                        )
                                                    )

                                                    IconButton(
                                                        onClick = {
                                                            pinOrUnpinRuleAsDashboardTile(rule, false)
                                                            automationRules.removeAt(idx)
                                                            DeviceRepository.saveAutomations(context, automationRules)
                                                            onStatusBanner("Removed '${rule.name}'.")
                                                        },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.DeleteOutline,
                                                            contentDescription = "Delete Rule",
                                                            tint = GhAccentRed,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        )

                                        // Inline Latest Run Status Badge on every Rule Card
                                        if (latestLogForRule != null) {
                                            val fmt = remember { SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()) }
                                            val isErr = latestLogForRule.status != AutomationRunStatus.SUCCESS
                                            Surface(
                                                color = if (isErr) GhTileRedActive.copy(alpha = 0.65f) else GhTileGreenActive.copy(alpha = 0.5f),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { activeSubTab = AutomationSectionTab.EXECUTION_LOGS }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                        Icon(
                                                            imageVector = if (isErr) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                                                            contentDescription = null,
                                                            tint = if (isErr) GhAccentRed else GhAccentGreen,
                                                            modifier = Modifier.size(15.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = "${fmt.format(Date(latestLogForRule.timestampEpochMs))}: ${latestLogForRule.userFriendlySummary}",
                                                            color = if (isErr) GhAccentRed else GhAccentGreen,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Text(
                                                        text = "Details ->",
                                                        color = GhTextSecondary,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // =============================================================
                // TAB 2: CREATE / EDIT RULE (Guided Builder + Pin as Macro Tile)
                // =============================================================
                AutomationSectionTab.CREATE_OR_EDIT -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            Surface(
                                color = GhTileSurface,
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, GhTileSurfaceElevated),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    // Auto-Generated Rule Name Header with Optional Rename Toggle
                                    Surface(
                                        color = GhTileSurfaceElevated.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = if (hasCustomName) "Routine / Macro Name" else "Auto-Generated Routine Name",
                                                        color = GhTextSecondary,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = draftName.ifBlank { autoGeneratedName },
                                                        color = GhTextPrimary,
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    if (hasCustomName) {
                                                        TextButton(
                                                            onClick = {
                                                                hasCustomName = false
                                                                draftName = autoGeneratedName
                                                                showRenameField = false
                                                            }
                                                        ) {
                                                            Text("Use Auto Name", fontSize = 11.sp, color = GhAccentGreen)
                                                        }
                                                    }
                                                    FilledTonalButton(
                                                        onClick = { showRenameField = !showRenameField },
                                                        shape = RoundedCornerShape(50),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                        modifier = Modifier.height(30.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = if (showRenameField) Icons.Default.Check else Icons.Outlined.Edit,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(13.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = if (showRenameField) "Done" else "Rename",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                            }

                                            AnimatedVisibility(visible = showRenameField) {
                                                OutlinedTextField(
                                                    value = draftName,
                                                    onValueChange = {
                                                        draftName = it
                                                        hasCustomName = it.isNotBlank() && it != autoGeneratedName
                                                    },
                                                    label = { Text("Custom Routine / Macro Tile Name", fontSize = 11.sp) },
                                                    textStyle = compactInputTextStyle,
                                                    shape = RoundedCornerShape(14.dp),
                                                    colors = claraFieldColors(),
                                                    singleLine = true,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }

                                    // Pin Macro as Single-Run Dashboard Tile Toggle
                                    Surface(
                                        color = if (draftPinAsMacroTile) Color(0xFF00E5FF).copy(alpha = 0.14f) else GhTileSurfaceElevated.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (draftPinAsMacroTile) Color(0xFF00E5FF).copy(alpha = 0.6f) else GhCardBorder
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { draftPinAsMacroTile = !draftPinAsMacroTile }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.PlayCircle,
                                                    contentDescription = null,
                                                    tint = if (draftPinAsMacroTile) Color(0xFF00E5FF) else GhTextSecondary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Column {
                                                    Text(
                                                        text = "Pin Macro as a Single-Tap Dashboard Tile",
                                                        color = GhTextPrimary,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "Adds a button tile on your home screen so you can run all actions below with a single click.",
                                                        color = GhTextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }
                                            Switch(
                                                checked = draftPinAsMacroTile,
                                                onCheckedChange = { draftPinAsMacroTile = it },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color(0xFF0F1114),
                                                    checkedTrackColor = Color(0xFF00E5FF)
                                                )
                                            )
                                        }
                                    }

                                    HorizontalDivider(color = GhTileSurfaceElevated)

                                    Text(
                                        text = "1. When This Happens",
                                        color = GhAccentWarm,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "When to Run",
                                                selectedText = draftTriggerType.label,
                                                options = AutomationTriggerType.entries,
                                                optionLabel = { it.label },
                                                optionSubtitle = { it.subtitle },
                                                onOptionSelected = { nextType ->
                                                    draftTriggerType = nextType
                                                    if (nextType == AutomationTriggerType.WEATHER_CONDITION &&
                                                        draftWeatherKeyword.equals("TEMP", ignoreCase = true) &&
                                                        draftThresholdText == "19.0"
                                                    ) {
                                                        draftThresholdText = "10.0"
                                                    }
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Active Days",
                                                selectedText = draftDaysPreset.label,
                                                options = DaysPresetOption.entries,
                                                optionLabel = { it.label },
                                                onOptionSelected = { preset ->
                                                    draftDaysPreset = preset
                                                    if (preset != DaysPresetOption.CUSTOM) {
                                                        draftDays = preset.days
                                                    }
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        }
                                    )

                                    if (draftDaysPreset == DaysPresetOption.CUSTOM) {
                                        LazyRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            items(dayLabels) { (dayNum, label) ->
                                                val isSelected = dayNum in draftDays
                                                FilterChip(
                                                    selected = isSelected,
                                                    onClick = {
                                                        val next = draftDays.toMutableSet()
                                                        if (!next.add(dayNum) && next.size > 1) {
                                                            next.remove(dayNum)
                                                        }
                                                        draftDays = next
                                                    },
                                                    shape = RoundedCornerShape(50),
                                                    label = { Text(label, fontSize = 11.sp) }
                                                )
                                            }
                                        }
                                    }

                                    when (draftTriggerType) {
                                        AutomationTriggerType.TIME_SCHEDULE -> {
                                            ScrollableClockWheelPicker(
                                                title = "Scheduled Time (24-Hour Clock)",
                                                selectedHour = draftHour,
                                                onHourChange = { draftHour = it },
                                                selectedMinute = draftMinute,
                                                onMinuteChange = { draftMinute = it }
                                            )
                                        }
                                        AutomationTriggerType.SUNRISE_SUNSET -> {
                                            val sunOptions = listOf(
                                                true to "At Sunset (When Night Begins)",
                                                false to "At Sunrise (When Daylight Begins)"
                                            )
                                            CleanDropdownSelector(
                                                label = "Time of Day",
                                                selectedText = if (draftTriggerOnSunset) "At Sunset (When Night Begins)" else "At Sunrise (When Daylight Begins)",
                                                options = sunOptions,
                                                optionLabel = { it.second },
                                                onOptionSelected = { draftTriggerOnSunset = it.first },
                                                textStyle = compactInputTextStyle,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        AutomationTriggerType.DEVICE_STATE_CHANGE -> {
                                            ResponsiveSplitRow(
                                                isStacked = isCompactMobile,
                                                firstContent = { mod ->
                                                    CleanDropdownSelector(
                                                        label = "Select Device or Plug Socket to Watch",
                                                        selectedText = draftTriggerTileTitle.ifBlank { "Choose Device / Socket..." },
                                                        options = expandedDeviceTargets,
                                                        optionLabel = { it.displayTitle },
                                                        optionSubtitle = { it.subtitle },
                                                        onOptionSelected = { chosen ->
                                                            draftTriggerTileId = chosen.virtualId
                                                            draftTriggerTileTitle = chosen.displayTitle
                                                            if (chosen.socketSelection == 1) {
                                                                draftDeviceTriggerState = DeviceTriggerTargetState.SOCKET_1_ON
                                                            } else if (chosen.socketSelection == 2) {
                                                                draftDeviceTriggerState = DeviceTriggerTargetState.SOCKET_2_ON
                                                            }
                                                        },
                                                        textStyle = compactInputTextStyle,
                                                        modifier = mod
                                                    )
                                                },
                                                secondContent = { mod ->
                                                    CleanDropdownSelector(
                                                        label = "When It Is",
                                                        selectedText = draftDeviceTriggerState.label,
                                                        options = DeviceTriggerTargetState.entries,
                                                        optionLabel = { it.label },
                                                        onOptionSelected = { draftDeviceTriggerState = it },
                                                        textStyle = compactInputTextStyle,
                                                        modifier = mod
                                                    )
                                                }
                                            )
                                        }
                                        AutomationTriggerType.TEMPERATURE_THRESHOLD -> {
                                            val tempTiles = controllableTiles.filter {
                                                it.isThermostatTile || it.hasTemperatureSetpointCapability || it.deviceClass == DeviceClass.SENSOR
                                            }.ifEmpty { controllableTiles }
                                            ResponsiveSplitRow(
                                                isStacked = isCompactMobile,
                                                firstContent = { mod ->
                                                    CleanDropdownSelector(
                                                        label = "Thermostat or Heater",
                                                        selectedText = draftTriggerTileTitle.ifBlank { "Choose Thermostat..." },
                                                        options = tempTiles,
                                                        optionLabel = { "${it.title} (${"%.1f".format(it.temperature)}\u00B0C)" },
                                                        onOptionSelected = {
                                                            draftTriggerTileId = it.id
                                                            draftTriggerTileTitle = it.title
                                                        },
                                                        textStyle = compactInputTextStyle,
                                                        modifier = mod
                                                    )
                                                },
                                                secondContent = { mod ->
                                                    CleanDropdownSelector(
                                                        label = "When Temperature Is",
                                                        selectedText = draftComparisonOp.label,
                                                        options = ComparisonOperator.entries,
                                                        optionLabel = { it.label },
                                                        onOptionSelected = { draftComparisonOp = it },
                                                        textStyle = compactInputTextStyle,
                                                        modifier = mod
                                                    )
                                                }
                                            )
                                            OutlinedTextField(
                                                value = draftThresholdText,
                                                onValueChange = { draftThresholdText = it },
                                                label = { Text("Temperature (\u00B0C)", fontSize = 11.sp) },
                                                textStyle = compactInputTextStyle,
                                                shape = RoundedCornerShape(14.dp),
                                                colors = claraFieldColors(),
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        AutomationTriggerType.POWER_WATTS_THRESHOLD -> {
                                            val plugTiles = controllableTiles.filter { it.isSwitchTile }.ifEmpty { controllableTiles }
                                            ResponsiveSplitRow(
                                                isStacked = isCompactMobile,
                                                firstContent = { mod ->
                                                    CleanDropdownSelector(
                                                        label = "Smart Plug",
                                                        selectedText = draftTriggerTileTitle.ifBlank { "Choose Plug..." },
                                                        options = plugTiles,
                                                        optionLabel = { "${it.title} (${"%.1f".format(it.powerWatts)}W)" },
                                                        onOptionSelected = {
                                                            draftTriggerTileId = it.id
                                                            draftTriggerTileTitle = it.title
                                                        },
                                                        textStyle = compactInputTextStyle,
                                                        modifier = mod
                                                    )
                                                },
                                                secondContent = { mod ->
                                                    CleanDropdownSelector(
                                                        label = "When Power Usage Is",
                                                        selectedText = draftComparisonOp.label,
                                                        options = ComparisonOperator.entries,
                                                        optionLabel = { it.label },
                                                        onOptionSelected = { draftComparisonOp = it },
                                                        textStyle = compactInputTextStyle,
                                                        modifier = mod
                                                    )
                                                }
                                            )
                                            OutlinedTextField(
                                                value = draftThresholdText,
                                                onValueChange = { draftThresholdText = it },
                                                label = { Text("Power Usage (Watts)", fontSize = 11.sp) },
                                                textStyle = compactInputTextStyle,
                                                shape = RoundedCornerShape(14.dp),
                                                colors = claraFieldColors(),
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        AutomationTriggerType.DOORBELL_RING -> {
                                            Text(
                                                text = "Runs automatically whenever someone rings your doorbell.",
                                                color = GhAccentGreen,
                                                fontSize = 12.sp
                                            )
                                        }

                                        AutomationTriggerType.SECURITY_MODE_IS -> {
                                            CleanDropdownSelector(
                                                label = "When Security Center Mode Enters",
                                                selectedText = SecurityCenterMode.entries.firstOrNull {
                                                    it.name.equals(draftWeatherKeyword, ignoreCase = true)
                                                }?.label ?: SecurityCenterMode.AWAY.label,
                                                options = SecurityCenterMode.entries,
                                                optionLabel = { "${it.label} (${it.subtitle})" },
                                                onOptionSelected = { mode ->
                                                    draftWeatherKeyword = mode.name
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        AutomationTriggerType.TARIFF_WINDOW_IS -> {
                                            val tariffOptions = listOf(
                                                "OFF_PEAK" to "Off-Peak Night Rate Window (00:00\u201307:00)",
                                                "PEAK_DAY" to "Standard Day Rate Window (07:00\u201324:00)"
                                            )
                                            CleanDropdownSelector(
                                                label = "Electricity Tariff Window",
                                                selectedText = tariffOptions.firstOrNull {
                                                    it.first.equals(draftWeatherKeyword, ignoreCase = true)
                                                }?.second ?: tariffOptions.first().second,
                                                options = tariffOptions,
                                                optionLabel = { it.second },
                                                onOptionSelected = { (code, _) ->
                                                    draftWeatherKeyword = code
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        AutomationTriggerType.DAYLIGHT_IS -> {
                                            val daylightOptions = listOf(
                                                true to "When Nighttime Outdoors (After Sunset)",
                                                false to "When Daytime Outdoors (After Sunrise)"
                                            )
                                            CleanDropdownSelector(
                                                label = "Outdoor Daylight State",
                                                selectedText = if (draftTriggerOnSunset) daylightOptions[0].second else daylightOptions[1].second,
                                                options = daylightOptions,
                                                optionLabel = { it.second },
                                                onOptionSelected = { (isNight, _) ->
                                                    draftTriggerOnSunset = isNight
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        AutomationTriggerType.CAPABILITY_VALUE_MATCH -> {
                                            ResponsiveSplitRow(
                                                isStacked = isCompactMobile,
                                                firstContent = { mod ->
                                                    CleanDropdownSelector(
                                                        label = "Select Device to Watch",
                                                        selectedText = draftTriggerTileTitle.ifBlank { "Choose Device..." },
                                                        options = expandedDeviceTargets,
                                                        optionLabel = { it.displayTitle },
                                                        optionSubtitle = { it.subtitle },
                                                        onOptionSelected = { chosen ->
                                                            draftTriggerTileId = chosen.virtualId
                                                            draftTriggerTileTitle = chosen.displayTitle
                                                        },
                                                        textStyle = compactInputTextStyle,
                                                        modifier = mod
                                                    )
                                                },
                                                secondContent = { mod ->
                                                    OutlinedTextField(
                                                        value = draftWeatherKeyword,
                                                        onValueChange = { draftWeatherKeyword = it },
                                                        label = { Text("Capability Key (e.g. humidity, battery, cur_power)", fontSize = 11.sp) },
                                                        textStyle = compactInputTextStyle,
                                                        shape = RoundedCornerShape(14.dp),
                                                        colors = claraFieldColors(),
                                                        singleLine = true,
                                                        modifier = mod
                                                    )
                                                }
                                            )
                                            ResponsiveSplitRow(
                                                isStacked = isCompactMobile,
                                                firstContent = { mod ->
                                                    CleanDropdownSelector(
                                                        label = "Comparison Operator",
                                                        selectedText = draftComparisonOp.label,
                                                        options = ComparisonOperator.entries,
                                                        optionLabel = { it.label },
                                                        onOptionSelected = { draftComparisonOp = it },
                                                        textStyle = compactInputTextStyle,
                                                        modifier = mod
                                                    )
                                                },
                                                secondContent = { mod ->
                                                    OutlinedTextField(
                                                        value = draftThresholdText,
                                                        onValueChange = { draftThresholdText = it },
                                                        label = { Text("Target Numeric Threshold", fontSize = 11.sp) },
                                                        textStyle = compactInputTextStyle,
                                                        shape = RoundedCornerShape(14.dp),
                                                        colors = claraFieldColors(),
                                                        singleLine = true,
                                                        modifier = mod
                                                    )
                                                }
                                            )
                                        }
                                        AutomationTriggerType.WEATHER_CONDITION -> {
                                            val weatherOptions = listOf(
                                                "Rain" to "Outdoor Weather is Rain",
                                                "Showers" to "Outdoor Weather is Showers",
                                                "Snow" to "Outdoor Weather is Snow",
                                                "Clear" to "Outdoor Weather is Clear / Sunny",
                                                "Overcast" to "Outdoor Weather is Overcast / Cloudy",
                                                "TEMP" to "Outdoor Temperature (\u00B0C) Threshold"
                                            )
                                            CleanDropdownSelector(
                                                label = "Outdoor Weather Condition",
                                                selectedText = weatherOptions.firstOrNull { it.first.equals(draftWeatherKeyword, true) }?.second
                                                    ?: "Weather: $draftWeatherKeyword",
                                                options = weatherOptions,
                                                optionLabel = { it.second },
                                                onOptionSelected = { chosenKey ->
                                                    draftWeatherKeyword = chosenKey.first
                                                    if (chosenKey.first.equals("TEMP", ignoreCase = true) &&
                                                        (draftThresholdText.toFloatOrNull() == null || draftThresholdText == "19.0")
                                                    ) {
                                                        draftThresholdText = "10.0"
                                                        draftComparisonOp = ComparisonOperator.BELOW
                                                    }
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = Modifier.fillMaxWidth()
                                            )

                                            AnimatedVisibility(visible = draftWeatherKeyword.equals("TEMP", ignoreCase = true)) {
                                                val parsedOutdoorTemp = (draftThresholdText.toFloatOrNull() ?: 10.0f).coerceIn(-10f, 40f)
                                                val liveWeather = JarvisLiveBridge.weatherFlow.collectAsState().value

                                                Surface(
                                                    color = GhTileSurfaceElevated.copy(alpha = 0.45f),
                                                    shape = RoundedCornerShape(14.dp),
                                                    border = BorderStroke(1.dp, GhAccentBlue.copy(alpha = 0.35f)),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 8.dp)
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(14.dp),
                                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = "Outdoor Temperature Trigger (${draftComparisonOp.symbol} ${"%.1f".format(parsedOutdoorTemp)}\u00B0C)",
                                                                color = GhAccentBlue,
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                            Text(
                                                                text = "Current Outdoor: ${liveWeather.temperatureCelsius}\u00B0C",
                                                                color = GhTextSecondary,
                                                                fontSize = 11.sp
                                                            )
                                                        }

                                                        ResponsiveSplitRow(
                                                            isStacked = isCompactMobile,
                                                            firstContent = { mod ->
                                                                CleanDropdownSelector(
                                                                    label = "When Outdoor Temperature",
                                                                    selectedText = draftComparisonOp.label,
                                                                    options = ComparisonOperator.entries,
                                                                    optionLabel = { it.label },
                                                                    onOptionSelected = { draftComparisonOp = it },
                                                                    textStyle = compactInputTextStyle,
                                                                    modifier = mod
                                                                )
                                                            },
                                                            secondContent = { mod ->
                                                                OutlinedTextField(
                                                                    value = draftThresholdText,
                                                                    onValueChange = { draftThresholdText = it },
                                                                    label = { Text("Outdoor Temperature (\u00B0C)", fontSize = 11.sp) },
                                                                    textStyle = compactInputTextStyle,
                                                                    shape = RoundedCornerShape(14.dp),
                                                                    colors = claraFieldColors(),
                                                                    singleLine = true,
                                                                    modifier = mod
                                                                )
                                                            }
                                                        )

                                                        Slider(
                                                            value = parsedOutdoorTemp,
                                                            onValueChange = { v ->
                                                                val roundedHalf = (v * 2f).roundToInt() / 2f
                                                                draftThresholdText = "%.1f".format(Locale.US, roundedHalf)
                                                            },
                                                            valueRange = -5f..35f,
                                                            colors = SliderDefaults.colors(
                                                                thumbColor = GhAccentBlue,
                                                                activeTrackColor = GhAccentBlue,
                                                                inactiveTrackColor = GhTileSurface
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Optional Time Window Restriction for Event-Based Triggers
                                    if (draftTriggerType != AutomationTriggerType.TIME_SCHEDULE) {
                                        Surface(
                                            color = GhTileSurfaceElevated.copy(alpha = 0.4f),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = "Only Run Between Specific Hours",
                                                            color = GhTextPrimary,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                        Text(
                                                            text = if (draftRestrictWindow) {
                                                                "Active from %02d:%02d to %02d:%02d".format(
                                                                    draftWindowStartHour,
                                                                    draftWindowStartMinute,
                                                                    draftWindowEndHour,
                                                                    draftWindowEndMinute
                                                                )
                                                            } else {
                                                                "Runs at any time of day"
                                                            },
                                                            color = GhTextSecondary,
                                                            fontSize = 11.sp
                                                        )
                                                    }
                                                    Switch(
                                                        checked = draftRestrictWindow,
                                                        onCheckedChange = { draftRestrictWindow = it },
                                                        colors = SwitchDefaults.colors(
                                                            checkedThumbColor = Color(0xFF0F1114),
                                                            checkedTrackColor = GhAccentGreen
                                                        )
                                                    )
                                                }

                                                AnimatedVisibility(visible = draftRestrictWindow) {
                                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                        ScrollableClockWheelPicker(
                                                            title = "Start Time",
                                                            selectedHour = draftWindowStartHour,
                                                            onHourChange = { draftWindowStartHour = it },
                                                            selectedMinute = draftWindowStartMinute,
                                                            onMinuteChange = { draftWindowStartMinute = it }
                                                        )
                                                        ScrollableClockWheelPicker(
                                                            title = "End Time",
                                                            selectedHour = draftWindowEndHour,
                                                            onHourChange = { draftWindowEndHour = it },
                                                            selectedMinute = draftWindowEndMinute,
                                                            onMinuteChange = { draftWindowEndMinute = it }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Optional Multi-Condition (AND / OR) Secondary Conditions Builder
                                    Surface(
                                        color = GhTileSurfaceElevated.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, GhCardBorder),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Additional Conditions (AND / OR)",
                                                        color = GhTextPrimary,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = if (draftSecondaryConditions.isEmpty()) {
                                                            "Optional: Require another device state, temperature, or power draw before running"
                                                        } else {
                                                            "${draftSecondaryConditions.size} secondary condition(s) active (${draftCombinator.label})"
                                                        },
                                                        color = GhTextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                                FilledTonalButton(
                                                    onClick = {
                                                        val firstDev = expandedDeviceTargets.firstOrNull()
                                                        draftSecondaryConditions.add(
                                                            AutomationSecondaryCondition(
                                                                conditionType = AutomationTriggerType.DEVICE_STATE_CHANGE,
                                                                targetTileId = firstDev?.virtualId.orEmpty(),
                                                                targetTileTitle = firstDev?.displayTitle.orEmpty(),
                                                                targetDeviceState = DeviceTriggerTargetState.TURNED_ON
                                                            )
                                                        )
                                                    },
                                                    shape = RoundedCornerShape(50),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(30.dp)
                                                ) {
                                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Add Condition", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }

                                            if (draftSecondaryConditions.isNotEmpty()) {
                                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    items(ConditionCombinator.entries) { comb ->
                                                        FilterChip(
                                                            selected = draftCombinator == comb,
                                                            onClick = { draftCombinator = comb },
                                                            shape = RoundedCornerShape(50),
                                                            label = { Text(comb.label, fontSize = 11.sp) }
                                                        )
                                                    }
                                                }

                                                val secondaryAllowedTypes = listOf(
                                                    AutomationTriggerType.SECURITY_MODE_IS,
                                                    AutomationTriggerType.TARIFF_WINDOW_IS,
                                                    AutomationTriggerType.DAYLIGHT_IS,
                                                    AutomationTriggerType.CAPABILITY_VALUE_MATCH,
                                                    AutomationTriggerType.DEVICE_STATE_CHANGE,
                                                    AutomationTriggerType.TEMPERATURE_THRESHOLD,
                                                    AutomationTriggerType.POWER_WATTS_THRESHOLD,
                                                    AutomationTriggerType.WEATHER_CONDITION
                                                )

                                                draftSecondaryConditions.forEachIndexed { secIdx, secCond ->
                                                    Surface(
                                                        color = GhTileSurface,
                                                        shape = RoundedCornerShape(12.dp),
                                                        border = BorderStroke(1.dp, GhTileSurfaceElevated),
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Column(
                                                            modifier = Modifier.padding(10.dp),
                                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Text(
                                                                    text = "${draftCombinator.name} Condition #${secIdx + 1}: ${secCond.toSummaryText()}",
                                                                    color = GhAccentWarm,
                                                                    fontSize = 11.5.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                                IconButton(
                                                                    onClick = { draftSecondaryConditions.removeAt(secIdx) },
                                                                    modifier = Modifier.size(22.dp)
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.Close,
                                                                        contentDescription = "Remove Condition",
                                                                        tint = GhAccentRed,
                                                                        modifier = Modifier.size(15.dp)
                                                                    )
                                                                }
                                                            }

                                                            ResponsiveSplitRow(
                                                                isStacked = isCompactMobile,
                                                                firstContent = { mod ->
                                                                    CleanDropdownSelector(
                                                                        label = "Condition Type",
                                                                        selectedText = secCond.conditionType.label,
                                                                        options = secondaryAllowedTypes,
                                                                        optionLabel = { it.label },
                                                                        onOptionSelected = { nextType ->
                                                                            draftSecondaryConditions[secIdx] = secCond.copy(conditionType = nextType)
                                                                        },
                                                                        textStyle = compactInputTextStyle,
                                                                        modifier = mod
                                                                    )
                                                                },
                                                                secondContent = { mod ->
                                                                    when (secCond.conditionType) {
                                                                        AutomationTriggerType.SECURITY_MODE_IS -> {
                                                                            CleanDropdownSelector(
                                                                                label = "Required Security Mode",
                                                                                selectedText = secCond.targetSecurityMode.label,
                                                                                options = SecurityCenterMode.entries,
                                                                                optionLabel = { it.label },
                                                                                optionSubtitle = { it.subtitle },
                                                                                onOptionSelected = { mode ->
                                                                                    draftSecondaryConditions[secIdx] = secCond.copy(targetSecurityMode = mode)
                                                                                },
                                                                                textStyle = compactInputTextStyle,
                                                                                modifier = mod
                                                                            )
                                                                        }
                                                                        AutomationTriggerType.TARIFF_WINDOW_IS -> {
                                                                            val tariffOpts = listOf(
                                                                                true to "Off-Peak Night Rate (00:00\u201307:00)",
                                                                                false to "Peak Day Rate (07:00\u201324:00)"
                                                                            )
                                                                            CleanDropdownSelector(
                                                                                label = "Required Tariff Window",
                                                                                selectedText = if (secCond.requireOffPeakTariff) "Off-Peak Night Rate (00:00\u201307:00)" else "Peak Day Rate (07:00\u201324:00)",
                                                                                options = tariffOpts,
                                                                                optionLabel = { it.second },
                                                                                onOptionSelected = { (offPeak, _) ->
                                                                                    draftSecondaryConditions[secIdx] = secCond.copy(requireOffPeakTariff = offPeak)
                                                                                },
                                                                                textStyle = compactInputTextStyle,
                                                                                modifier = mod
                                                                            )
                                                                        }
                                                                        AutomationTriggerType.DAYLIGHT_IS -> {
                                                                            val dayOpts = listOf(
                                                                                true to "Nighttime (After Sunset)",
                                                                                false to "Daytime (After Sunrise)"
                                                                            )
                                                                            CleanDropdownSelector(
                                                                                label = "Outdoor Daylight State",
                                                                                selectedText = if (secCond.requireNighttime) "Nighttime (After Sunset)" else "Daytime (After Sunrise)",
                                                                                options = dayOpts,
                                                                                optionLabel = { it.second },
                                                                                onOptionSelected = { (night, _) ->
                                                                                    draftSecondaryConditions[secIdx] = secCond.copy(requireNighttime = night)
                                                                                },
                                                                                textStyle = compactInputTextStyle,
                                                                                modifier = mod
                                                                            )
                                                                        }
                                                                        AutomationTriggerType.WEATHER_CONDITION -> {
                                                                            CleanDropdownSelector(
                                                                                label = "Weather Keyword",
                                                                                selectedText = secCond.weatherKeyword,
                                                                                options = listOf("Rain", "Showers", "Snow", "Clear", "Overcast"),
                                                                                optionLabel = { it },
                                                                                onOptionSelected = { kw ->
                                                                                    draftSecondaryConditions[secIdx] = secCond.copy(weatherKeyword = kw)
                                                                                },
                                                                                textStyle = compactInputTextStyle,
                                                                                modifier = mod
                                                                            )
                                                                        }
                                                                        else -> {
                                                                            CleanDropdownSelector(
                                                                                label = "Target Device",
                                                                                selectedText = secCond.targetTileTitle.ifBlank { "Choose Device..." },
                                                                                options = expandedDeviceTargets,
                                                                                optionLabel = { it.displayTitle },
                                                                                onOptionSelected = { chosen ->
                                                                                    draftSecondaryConditions[secIdx] = secCond.copy(
                                                                                        targetTileId = chosen.virtualId,
                                                                                        targetTileTitle = chosen.displayTitle
                                                                                    )
                                                                                },
                                                                                textStyle = compactInputTextStyle,
                                                                                modifier = mod
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            )

                                                            if (secCond.conditionType == AutomationTriggerType.DEVICE_STATE_CHANGE) {
                                                                CleanDropdownSelector(
                                                                    label = "Required State",
                                                                    selectedText = secCond.targetDeviceState.label,
                                                                    options = DeviceTriggerTargetState.entries,
                                                                    optionLabel = { it.label },
                                                                    onOptionSelected = { st ->
                                                                        draftSecondaryConditions[secIdx] = secCond.copy(targetDeviceState = st)
                                                                    },
                                                                    textStyle = compactInputTextStyle,
                                                                    modifier = Modifier.fillMaxWidth()
                                                                )
                                                            } else if (secCond.conditionType in listOf(
                                                                    AutomationTriggerType.TEMPERATURE_THRESHOLD,
                                                                    AutomationTriggerType.POWER_WATTS_THRESHOLD
                                                                )
                                                            ) {
                                                                ResponsiveSplitRow(
                                                                    isStacked = isCompactMobile,
                                                                    firstContent = { mod ->
                                                                        CleanDropdownSelector(
                                                                            label = "Comparison",
                                                                            selectedText = secCond.comparisonOperator.label,
                                                                            options = ComparisonOperator.entries,
                                                                            optionLabel = { it.label },
                                                                            onOptionSelected = { op ->
                                                                                draftSecondaryConditions[secIdx] = secCond.copy(comparisonOperator = op)
                                                                            },
                                                                            textStyle = compactInputTextStyle,
                                                                            modifier = mod
                                                                        )
                                                                    },
                                                                    secondContent = { mod ->
                                                                        OutlinedTextField(
                                                                            value = secCond.thresholdValue.toString(),
                                                                            onValueChange = { v ->
                                                                                val parsed = v.toFloatOrNull() ?: secCond.thresholdValue
                                                                                draftSecondaryConditions[secIdx] = secCond.copy(thresholdValue = parsed)
                                                                            },
                                                                            label = {
                                                                                Text(
                                                                                    if (secCond.conditionType == AutomationTriggerType.POWER_WATTS_THRESHOLD)
                                                                                        "Threshold (Watts)"
                                                                                    else
                                                                                        "Threshold (\u00B0C)",
                                                                                    fontSize = 11.sp
                                                                                )
                                                                            },
                                                                            textStyle = compactInputTextStyle,
                                                                            shape = RoundedCornerShape(14.dp),
                                                                            colors = claraFieldColors(),
                                                                            singleLine = true,
                                                                            modifier = mod
                                                                        )
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    HorizontalDivider(color = GhTileSurfaceElevated)

                                    Text(
                                        text = "2. Do These Actions",
                                        color = GhAccentGreen,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        draftActions.forEachIndexed { actionIdx, actionItem ->
                                            val isWaitStep = actionItem.actionType == AutomationActionType.WAIT_DELAY
                                            val matchedTarget = expandedDeviceTargets.firstOrNull {
                                                it.virtualId == actionItem.targetTileId
                                            }
                                            val matchedBaseTile = matchedTarget?.baseTile
                                                ?: controllableTiles.firstOrNull {
                                                    it.id == actionItem.targetTileId.substringBefore("::") ||
                                                            actionItem.targetTileTitle.startsWith(it.title, ignoreCase = true)
                                                }
                                            val isDualSocketPlug = matchedBaseTile?.isMultiGangSwitch == true
                                            val contextualActions = contextualActionOptionsForTile(
                                                tile = matchedBaseTile,
                                                socketSelection = matchedTarget?.socketSelection ?: 0
                                            )

                                            Surface(
                                                color = if (isWaitStep) GhTileWarmActive.copy(alpha = 0.55f) else GhTileSurfaceElevated.copy(alpha = 0.45f),
                                                shape = RoundedCornerShape(16.dp),
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (isWaitStep) GhAccentWarm.copy(alpha = 0.5f) else GhTextSecondary.copy(alpha = 0.16f)
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(14.dp),
                                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = "Step #${actionIdx + 1}: ${actionItem.toSummaryText()}",
                                                            color = if (isWaitStep) GhAccentWarm else GhAccentGreen,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        if (draftActions.size > 1) {
                                                            IconButton(
                                                                onClick = { draftActions.removeAt(actionIdx) },
                                                                modifier = Modifier.size(24.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Close,
                                                                    contentDescription = "Remove Step",
                                                                    tint = GhAccentRed,
                                                                    modifier = Modifier.size(16.dp)
                                                                )
                                                            }
                                                        }
                                                    }

                                                    if (isWaitStep) {
                                                        ResponsiveSplitRow(
                                                            isStacked = isCompactMobile,
                                                            firstContent = { mod ->
                                                                OutlinedTextField(
                                                                    value = actionItem.numericValue.toInt().coerceAtLeast(1).toString(),
                                                                    onValueChange = { v ->
                                                                        val parsed = (v.toFloatOrNull() ?: 1f).coerceAtLeast(1f)
                                                                        draftActions[actionIdx] = actionItem.copy(numericValue = parsed)
                                                                    },
                                                                    label = { Text("Wait Duration", fontSize = 11.sp) },
                                                                    textStyle = compactInputTextStyle,
                                                                    shape = RoundedCornerShape(14.dp),
                                                                    colors = claraFieldColors(),
                                                                    singleLine = true,
                                                                    modifier = mod
                                                                )
                                                            },
                                                            secondContent = { mod ->
                                                                CleanDropdownSelector(
                                                                    label = "Time Unit",
                                                                    selectedText = waitUnitOptions.firstOrNull {
                                                                        it.first.equals(actionItem.textValue, true)
                                                                    }?.second ?: "Minutes",
                                                                    options = waitUnitOptions,
                                                                    optionLabel = { it.second },
                                                                    onOptionSelected = { (unitCode, _) ->
                                                                        draftActions[actionIdx] = actionItem.copy(textValue = unitCode)
                                                                    },
                                                                    textStyle = compactInputTextStyle,
                                                                    modifier = mod
                                                                )
                                                            }
                                                        )
                                                    } else {
                                                        ResponsiveSplitRow(
                                                            isStacked = isCompactMobile,
                                                            firstContent = { mod ->
                                                                if (actionItem.actionType.requiresTargetTile) {
                                                                    CleanDropdownSelector(
                                                                        label = "Select Device or Plug Socket",
                                                                        selectedText = actionItem.targetTileTitle.ifBlank {
                                                                            expandedDeviceTargets.firstOrNull()?.displayTitle ?: "Select Device..."
                                                                        },
                                                                        options = expandedDeviceTargets,
                                                                        optionLabel = { it.displayTitle },
                                                                        optionSubtitle = { it.subtitle },
                                                                        onOptionSelected = { chosenTarget ->
                                                                            val currentWantsOff = actionItem.actionType in listOf(
                                                                                AutomationActionType.TURN_OFF,
                                                                                AutomationActionType.SOCKET_1_OFF,
                                                                                AutomationActionType.SOCKET_2_OFF,
                                                                                AutomationActionType.BOTH_SOCKETS_OFF
                                                                            )
                                                                            val nextActionType = when {
                                                                                chosenTarget.socketSelection == 1 ->
                                                                                    if (currentWantsOff) AutomationActionType.SOCKET_1_OFF else AutomationActionType.SOCKET_1_ON
                                                                                chosenTarget.socketSelection == 2 ->
                                                                                    if (currentWantsOff) AutomationActionType.SOCKET_2_OFF else AutomationActionType.SOCKET_2_ON
                                                                                chosenTarget.baseTile.isMultiGangSwitch ->
                                                                                    if (currentWantsOff) AutomationActionType.BOTH_SOCKETS_OFF else AutomationActionType.BOTH_SOCKETS_ON
                                                                                chosenTarget.baseTile.isThermostatTile || chosenTarget.baseTile.hasTemperatureSetpointCapability ->
                                                                                    AutomationActionType.SET_TEMPERATURE
                                                                                chosenTarget.baseTile.isCameraTile || chosenTarget.baseTile.deviceClass == DeviceClass.CAMERA ->
                                                                                    AutomationActionType.OPEN_LIVE_CAMERA
                                                                                chosenTarget.baseTile.deviceSubtype in setOf(DeviceSubtype.SPEAKER, DeviceSubtype.CAST_GROUP) ->
                                                                                    AutomationActionType.SET_LEVEL_OR_VOLUME
                                                                                else ->
                                                                                    if (currentWantsOff) AutomationActionType.TURN_OFF else AutomationActionType.TURN_ON
                                                                            }
                                                                            draftActions[actionIdx] = actionItem.copy(
                                                                                targetTileId = chosenTarget.virtualId,
                                                                                targetTileTitle = chosenTarget.displayTitle,
                                                                                actionType = nextActionType,
                                                                                targetSelector = buildSelectorForTarget(chosenTarget)
                                                                            )
                                                                        },
                                                                        textStyle = compactInputTextStyle,
                                                                        modifier = mod
                                                                    )
                                                                } else {
                                                                    Spacer(modifier = mod)
                                                                }
                                                            },
                                                            secondContent = { mod ->
                                                                CleanDropdownSelector(
                                                                    label = "Action to Perform",
                                                                    selectedText = actionItem.actionType.label,
                                                                    options = contextualActions,
                                                                    optionLabel = { it.label },
                                                                    onOptionSelected = { newType ->
                                                                        val defaultText = when (newType) {
                                                                            AutomationActionType.WAIT_DELAY -> "MINUTES"
                                                                            AutomationActionType.EXECUTE_SCENE -> "ECO NIGHT"
                                                                            AutomationActionType.SET_HVAC_MODE -> "HEAT"
                                                                            AutomationActionType.RING_NEST_MINI_CHIME -> bridgeSettings.doorbellChimeMessage
                                                                            AutomationActionType.SPEAK_ANNOUNCEMENT -> "Scheduled routine active."
                                                                            else -> actionItem.textValue
                                                                        }
                                                                        val defaultNum = when (newType) {
                                                                            AutomationActionType.WAIT_DELAY -> 5f
                                                                            AutomationActionType.SET_TEMPERATURE -> 21.5f
                                                                            else -> actionItem.numericValue
                                                                        }
                                                                        draftActions[actionIdx] = actionItem.copy(
                                                                            actionType = newType,
                                                                            numericValue = defaultNum,
                                                                            textValue = defaultText
                                                                        )
                                                                    },
                                                                    textStyle = compactInputTextStyle,
                                                                    modifier = mod
                                                                )
                                                            }
                                                        )

                                                        // Dedicated Individual Socket Quick-Selector using custom socket1Name and socket2Name
                                                        if (isDualSocketPlug && matchedBaseTile != null) {
                                                            val s1Label = matchedBaseTile.socket1Name.ifBlank { "Socket 1" }
                                                            val s2Label = matchedBaseTile.socket2Name.ifBlank { "Socket 2" }
                                                            val isS1On = actionItem.actionType == AutomationActionType.SOCKET_1_ON
                                                            val isS1Off = actionItem.actionType == AutomationActionType.SOCKET_1_OFF
                                                            val isS2On = actionItem.actionType == AutomationActionType.SOCKET_2_ON
                                                            val isS2Off = actionItem.actionType == AutomationActionType.SOCKET_2_OFF

                                                            LazyRow(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                item {
                                                                    Text(
                                                                        text = "Quick Socket Pick:",
                                                                        color = GhTextSecondary,
                                                                        fontSize = 11.sp,
                                                                        fontWeight = FontWeight.SemiBold
                                                                    )
                                                                }
                                                                item {
                                                                    FilterChip(
                                                                        selected = isS1On,
                                                                        onClick = {
                                                                            draftActions[actionIdx] = actionItem.copy(
                                                                                targetTileId = "${matchedBaseTile.id}::SOCKET_1",
                                                                                targetTileTitle = "${matchedBaseTile.title} \u2014 $s1Label",
                                                                                actionType = AutomationActionType.SOCKET_1_ON
                                                                            )
                                                                        },
                                                                        shape = RoundedCornerShape(50),
                                                                        label = { Text("$s1Label ON", fontSize = 11.sp) }
                                                                    )
                                                                }
                                                                item {
                                                                    FilterChip(
                                                                        selected = isS1Off,
                                                                        onClick = {
                                                                            draftActions[actionIdx] = actionItem.copy(
                                                                                targetTileId = "${matchedBaseTile.id}::SOCKET_1",
                                                                                targetTileTitle = "${matchedBaseTile.title} \u2014 $s1Label",
                                                                                actionType = AutomationActionType.SOCKET_1_OFF
                                                                            )
                                                                        },
                                                                        shape = RoundedCornerShape(50),
                                                                        label = { Text("$s1Label OFF", fontSize = 11.sp) }
                                                                    )
                                                                }
                                                                item {
                                                                    FilterChip(
                                                                        selected = isS2On,
                                                                        onClick = {
                                                                            draftActions[actionIdx] = actionItem.copy(
                                                                                targetTileId = "${matchedBaseTile.id}::SOCKET_2",
                                                                                targetTileTitle = "${matchedBaseTile.title} \u2014 $s2Label",
                                                                                actionType = AutomationActionType.SOCKET_2_ON
                                                                            )
                                                                        },
                                                                        shape = RoundedCornerShape(50),
                                                                        label = { Text("$s2Label ON", fontSize = 11.sp) }
                                                                    )
                                                                }
                                                                item {
                                                                    FilterChip(
                                                                        selected = isS2Off,
                                                                        onClick = {
                                                                            draftActions[actionIdx] = actionItem.copy(
                                                                                targetTileId = "${matchedBaseTile.id}::SOCKET_2",
                                                                                targetTileTitle = "${matchedBaseTile.title} \u2014 $s2Label",
                                                                                actionType = AutomationActionType.SOCKET_2_OFF
                                                                            )
                                                                        },
                                                                        shape = RoundedCornerShape(50),
                                                                        label = { Text("$s2Label OFF", fontSize = 11.sp) }
                                                                    )
                                                                }
                                                            }
                                                        }

                                                        when (actionItem.actionType) {
                                                            AutomationActionType.SET_TEMPERATURE -> {
                                                                OutlinedTextField(
                                                                    value = actionItem.numericValue.toString(),
                                                                    onValueChange = { v ->
                                                                        val parsed = v.toFloatOrNull() ?: actionItem.numericValue
                                                                        draftActions[actionIdx] = actionItem.copy(numericValue = parsed)
                                                                    },
                                                                    label = { Text("Target Temperature (\u00B0C)", fontSize = 11.sp) },
                                                                    textStyle = compactInputTextStyle,
                                                                    shape = RoundedCornerShape(14.dp),
                                                                    colors = claraFieldColors(),
                                                                    singleLine = true,
                                                                    modifier = Modifier.fillMaxWidth()
                                                                )
                                                            }
                                                            AutomationActionType.SET_LEVEL_OR_VOLUME -> {
                                                                OutlinedTextField(
                                                                    value = actionItem.numericValue.toInt().toString(),
                                                                    onValueChange = { v ->
                                                                        val parsed = v.toFloatOrNull() ?: actionItem.numericValue
                                                                        draftActions[actionIdx] = actionItem.copy(numericValue = parsed)
                                                                    },
                                                                    label = { Text("Brightness or Volume (%)", fontSize = 11.sp) },
                                                                    textStyle = compactInputTextStyle,
                                                                    shape = RoundedCornerShape(14.dp),
                                                                    colors = claraFieldColors(),
                                                                    singleLine = true,
                                                                    modifier = Modifier.fillMaxWidth()
                                                                )
                                                            }
                                                            AutomationActionType.SET_HVAC_MODE -> {
                                                                CleanDropdownSelector(
                                                                    label = "Heating Mode",
                                                                    selectedText = actionItem.textValue,
                                                                    options = listOf("HEAT", "ECO", "OFF"),
                                                                    optionLabel = { "Mode: $it" },
                                                                    onOptionSelected = { m ->
                                                                        draftActions[actionIdx] = actionItem.copy(textValue = m)
                                                                    },
                                                                    textStyle = compactInputTextStyle,
                                                                    modifier = Modifier.fillMaxWidth()
                                                                )
                                                            }
                                                            AutomationActionType.EXECUTE_SCENE -> {
                                                                CleanDropdownSelector(
                                                                    label = "Home Routine",
                                                                    selectedText = actionItem.textValue,
                                                                    options = listOf("ECO NIGHT", "BOOST HEAT", "PERIMETER LOCK", "ALL LIVE FEEDS"),
                                                                    optionLabel = { it },
                                                                    onOptionSelected = { sc ->
                                                                        draftActions[actionIdx] = actionItem.copy(textValue = sc)
                                                                    },
                                                                    textStyle = compactInputTextStyle,
                                                                    modifier = Modifier.fillMaxWidth()
                                                                )
                                                            }
                                                            AutomationActionType.RING_NEST_MINI_CHIME,
                                                            AutomationActionType.SPEAK_ANNOUNCEMENT -> {
                                                                OutlinedTextField(
                                                                    value = actionItem.textValue,
                                                                    onValueChange = { txt ->
                                                                        draftActions[actionIdx] = actionItem.copy(textValue = txt)
                                                                    },
                                                                    label = { Text("Spoken Message", fontSize = 11.sp) },
                                                                    textStyle = compactInputTextStyle,
                                                                    shape = RoundedCornerShape(14.dp),
                                                                    colors = claraFieldColors(),
                                                                    singleLine = true,
                                                                    modifier = Modifier.fillMaxWidth()
                                                                )
                                                            }
                                                            else -> {}
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            Row(
                                                modifier = mod,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Button(
                                                    onClick = {
                                                        val firstTarget = expandedDeviceTargets.firstOrNull()
                                                        draftActions.add(
                                                            AutomationAction(
                                                                id = UUID.randomUUID().toString(),
                                                                targetTileId = firstTarget?.virtualId ?: "",
                                                                targetTileTitle = firstTarget?.displayTitle ?: "",
                                                                actionType = when (firstTarget?.socketSelection) {
                                                                    1 -> AutomationActionType.SOCKET_1_ON
                                                                    2 -> AutomationActionType.SOCKET_2_ON
                                                                    else -> AutomationActionType.TURN_ON
                                                                },
                                                                numericValue = 21.0f,
                                                                textValue = "HEAT",
                                                                targetSelector = buildSelectorForTarget(firstTarget)
                                                            )
                                                        )
                                                    },
                                                    shape = RoundedCornerShape(50),
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = GhAccentGreen.copy(alpha = 0.22f),
                                                        contentColor = GhAccentGreen
                                                    ),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Add Device Action", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }

                                                Button(
                                                    onClick = {
                                                        draftActions.add(
                                                            AutomationAction(
                                                                id = UUID.randomUUID().toString(),
                                                                actionType = AutomationActionType.WAIT_DELAY,
                                                                numericValue = 5.0f,
                                                                textValue = "MINUTES"
                                                            )
                                                        )
                                                    },
                                                    shape = RoundedCornerShape(50),
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = GhAccentWarm.copy(alpha = 0.22f),
                                                        contentColor = GhAccentWarm
                                                    ),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Add Wait Timer", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        },
                                        secondContent = { mod ->
                                            Button(
                                                onClick = {
                                                    val nestMiniTile = controllableTiles.firstOrNull {
                                                        it.deviceSubtype in setOf(DeviceSubtype.SPEAKER, DeviceSubtype.CAST_GROUP)
                                                    }
                                                    draftActions.add(
                                                        AutomationAction(
                                                            id = UUID.randomUUID().toString(),
                                                            targetTileId = nestMiniTile?.id ?: "",
                                                            targetTileTitle = nestMiniTile?.title ?: "Nest Mini",
                                                            actionType = if (nestMiniTile != null)
                                                                AutomationActionType.RING_NEST_MINI_CHIME
                                                            else
                                                                AutomationActionType.SPEAK_ANNOUNCEMENT,
                                                            numericValue = 1.0f,
                                                            textValue = "Routine complete."
                                                        )
                                                    )
                                                },
                                                shape = RoundedCornerShape(50),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = GhAccentBlue.copy(alpha = 0.22f),
                                                    contentColor = GhAccentBlue
                                                ),
                                                modifier = mod
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Add Voice Alert", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    )

                                    // Conditional Decision Branches (`When / Otherwise`) Inspector & Builder
                                    if (draftBranches.isNotEmpty()) {
                                        Surface(
                                            color = GhTileSurfaceElevated.copy(alpha = 0.45f),
                                            shape = RoundedCornerShape(14.dp),
                                            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.45f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "Conditional Decision Branches (${draftBranches.size})",
                                                        color = Color(0xFF00E5FF),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    TextButton(onClick = { draftBranches.clear(); draftOtherwiseActions.clear() }) {
                                                        Text("Clear Branches", fontSize = 11.sp, color = GhAccentRed)
                                                    }
                                                }
                                                draftBranches.forEachIndexed { bIdx, branch ->
                                                    Text(
                                                        text = "Branch #${bIdx + 1}: ${branch.toSummaryText()}",
                                                        color = GhTextPrimary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                                if (draftOtherwiseActions.isNotEmpty()) {
                                                    Text(
                                                        text = "Otherwise Fallback: ${draftOtherwiseActions.joinToString(" \u2192 ") { it.toSummaryText() }}",
                                                        color = GhAccentWarm,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    HorizontalDivider(color = GhTileSurfaceElevated)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                editingRuleId = null
                                                activeSubTab = AutomationSectionTab.ACTIVE_RULES
                                            },
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text("Cancel", fontSize = 12.sp, color = GhTextSecondary)
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Button(
                                            onClick = {
                                                if (draftActions.isEmpty() && draftBranches.isEmpty()) {
                                                    onStatusBanner("Please add at least one action before saving.")
                                                    return@Button
                                                }
                                                val resolvedName = if (hasCustomName && draftName.trim().isNotBlank()) {
                                                    draftName.trim()
                                                } else {
                                                    autoGeneratedName
                                                }
                                                val defaultThreshold = if (draftTriggerType == AutomationTriggerType.WEATHER_CONDITION &&
                                                    draftWeatherKeyword.equals("TEMP", ignoreCase = true)
                                                ) 10.0f else 19.0f

                                                val newRule = AutomationRule(
                                                    id = editingRuleId ?: UUID.randomUUID().toString(),
                                                    name = resolvedName,
                                                    isEnabled = true,
                                                    triggerType = draftTriggerType,
                                                    scheduleHour = draftHour,
                                                    scheduleMinute = draftMinute,
                                                    daysOfWeek = draftDays,
                                                    triggerOnSunset = draftTriggerOnSunset,
                                                    triggerTileId = draftTriggerTileId,
                                                    triggerTileTitle = draftTriggerTileTitle,
                                                    triggerDeviceState = draftDeviceTriggerState,
                                                    comparisonOperator = draftComparisonOp,
                                                    thresholdValue = draftThresholdText.toFloatOrNull() ?: defaultThreshold,
                                                    weatherKeyword = draftWeatherKeyword.trim().ifBlank { "Rain" },
                                                    restrictToTimeWindow = draftRestrictWindow,
                                                    windowStartHour = draftWindowStartHour,
                                                    windowStartMinute = draftWindowStartMinute,
                                                    windowEndHour = draftWindowEndHour,
                                                    windowEndMinute = draftWindowEndMinute,
                                                    combinator = draftCombinator,
                                                    secondaryConditions = draftSecondaryConditions.toList(),
                                                    actions = draftActions.toList(),
                                                    branches = draftBranches.toList(),
                                                    otherwiseActions = draftOtherwiseActions.toList()
                                                )
                                                val existingIdx = automationRules.indexOfFirst { it.id == newRule.id }
                                                if (existingIdx >= 0) {
                                                    automationRules[existingIdx] = newRule
                                                } else {
                                                    automationRules.add(0, newRule)
                                                }
                                                DeviceRepository.saveAutomations(context, automationRules)
                                                if (draftPinAsMacroTile || isRulePinnedAsTile(newRule)) {
                                                    pinOrUnpinRuleAsDashboardTile(newRule, draftPinAsMacroTile)
                                                }
                                                onStatusBanner(
                                                    if (draftPinAsMacroTile) {
                                                        "SUCCESS: Saved '${newRule.name}' & pinned as a Macro Tile on your dashboard!"
                                                    } else {
                                                        "SUCCESS: Saved '${newRule.name}'!"
                                                    }
                                                )
                                                editingRuleId = null
                                                activeSubTab = AutomationSectionTab.ACTIVE_RULES
                                            },
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = GhAccentGreen,
                                                contentColor = Color(0xFF0F1114)
                                            )
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (editingRuleId != null) "Save Changes" else "Save Schedule",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // =============================================================
                // TAB 3: HUMAN-READABLE AUTOMATION ACTIVITY LOG
                // =============================================================
                AutomationSectionTab.EXECUTION_LOGS -> {
                    val filteredLogs = remember(automationLogs, showOnlyFailedLogs) {
                        if (showOnlyFailedLogs) {
                            automationLogs.filter { it.status != AutomationRunStatus.SUCCESS }
                        } else {
                            automationLogs
                        }
                    }
                    val fmt = remember { SimpleDateFormat("EEE dd MMM, HH:mm:ss", Locale.getDefault()) }

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            color = GhTileSurface,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, GhTileSurfaceElevated),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                ResponsiveSplitRow(
                                    isStacked = isCompactMobile,
                                    firstContent = { mod ->
                                        Column(modifier = mod) {
                                            Text(
                                                text = "Automation Activity History",
                                                color = GhTextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "Shows every scheduled or manual run and any helpful status notes.",
                                                color = GhTextSecondary,
                                                fontSize = 11.sp
                                            )
                                        }
                                    },
                                    secondContent = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilterChip(
                                                selected = showOnlyFailedLogs,
                                                onClick = { showOnlyFailedLogs = !showOnlyFailedLogs },
                                                shape = RoundedCornerShape(50),
                                                label = { Text("Alerts Only ($failedLogsCount)", fontSize = 11.sp) }
                                            )
                                            if (automationLogs.isNotEmpty()) {
                                                OutlinedButton(
                                                    onClick = {
                                                        DeviceRepository.clearAutomationLogs(context)
                                                        onStatusBanner("Cleared activity history.")
                                                    },
                                                    shape = RoundedCornerShape(50),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                                ) {
                                                    Text("Clear History", fontSize = 11.sp, color = GhAccentRed)
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        if (filteredLogs.isEmpty()) {
                            Surface(
                                color = GhTileSurface,
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, GhTileSurfaceElevated),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (showOnlyFailedLogs) "All recent schedules ran smoothly with no alerts!" else "No schedules have run yet. Tap 'Run' on any rule to try it out.",
                                        color = GhTextSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filteredLogs, key = { it.id }) { logEntry ->
                                    val isSuccess = logEntry.status == AutomationRunStatus.SUCCESS
                                    val isPartial = logEntry.status == AutomationRunStatus.PARTIAL_FAILURE
                                    val accentColor = when {
                                        isSuccess -> GhAccentGreen
                                        isPartial -> GhAccentWarm
                                        else -> GhAccentRed
                                    }

                                    Surface(
                                        color = GhTileSurface,
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                    Surface(
                                                        color = accentColor.copy(alpha = 0.2f),
                                                        shape = CircleShape,
                                                        modifier = Modifier.size(30.dp)
                                                    ) {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Icon(
                                                                imageVector = if (isSuccess) Icons.Default.Check else Icons.Outlined.ReportProblem,
                                                                contentDescription = null,
                                                                tint = accentColor,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column {
                                                        Text(
                                                            text = logEntry.ruleName,
                                                            color = GhTextPrimary,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(
                                                            text = "${fmt.format(Date(logEntry.timestampEpochMs))} \u2022 ${logEntry.triggerSource}",
                                                            color = GhTextSecondary,
                                                            fontSize = 11.sp
                                                        )
                                                    }
                                                }

                                                Surface(
                                                    color = accentColor.copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(50)
                                                ) {
                                                    Text(
                                                        text = logEntry.status.label,
                                                        color = accentColor,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                    )
                                                }
                                            }

                                            Text(
                                                text = logEntry.userFriendlySummary,
                                                color = accentColor,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )

                                            if (logEntry.stepLogs.isNotEmpty()) {
                                                HorizontalDivider(color = GhTileSurfaceElevated)
                                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    for (step in logEntry.stepLogs) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            verticalAlignment = Alignment.Top
                                                        ) {
                                                            Icon(
                                                                imageVector = if (step.succeeded) Icons.Default.CheckCircleOutline else Icons.Default.ErrorOutline,
                                                                contentDescription = null,
                                                                tint = if (step.succeeded) GhAccentGreen else GhAccentRed,
                                                                modifier = Modifier
                                                                    .size(15.dp)
                                                                    .padding(top = 2.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Column {
                                                                val targetSuffix = if (step.resolvedTargetCount > 1) " (${step.resolvedTargetCount} devices)" else ""
                                                                Text(
                                                                    text = "Step ${step.stepNumber}: ${step.stepSummary}$targetSuffix",
                                                                    color = GhTextPrimary,
                                                                    fontSize = 11.sp,
                                                                    fontWeight = FontWeight.SemiBold
                                                                )
                                                                Text(
                                                                    text = step.reasonOrDetail,
                                                                    color = if (step.succeeded) GhTextSecondary else GhAccentRed,
                                                                    fontSize = 11.sp
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    activeSimulationReport?.let { report ->
        AlertDialog(
            onDismissRequest = { activeSimulationReport = null },
            containerColor = GhTileSurface,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Science,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = report.scenarioTitle,
                            color = GhTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont
                        )
                        Text(
                            text = "Branch: ${report.matchedBranchLabel} \u2022 Est. Savings: ${report.totalWattsSaved.roundToInt()}W (\u00A3${"%.2f".format(Locale.US, report.estimatedHourlyCostDeltaGbp)}/hr)",
                            color = GhAccentGreen,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = GhFont
                        )
                    }
                }
            },
            text = {
                if (report.mutations.isEmpty()) {
                    Text(
                        text = "No device state changes would occur right now (all matching devices are already in their target state).",
                        color = GhTextSecondary,
                        fontSize = 12.5.sp
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        items(report.mutations, key = { it.tileId }) { mut ->
                            Surface(
                                color = GhTileSurfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (mut.wouldBeBlockedByHold) GhAccentWarm else GhAccentGreen.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${mut.tileTitle} (${mut.roomName})",
                                            color = GhTextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${mut.currentStateSummary} \u2192 ${mut.projectedStateSummary}",
                                            color = if (mut.wouldBeBlockedByHold) GhAccentWarm else GhAccentGreen,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    if (mut.wouldBeBlockedByHold && mut.blockOrOfflineReason.isNotBlank()) {
                                        Text(
                                            text = mut.blockOrOfflineReason,
                                            color = GhAccentWarm,
                                            fontSize = 10.5.sp
                                        )
                                    } else if (mut.estimatedWattsDelta >= 1f) {
                                        Text(
                                            text = "Estimated load reduction: -${mut.estimatedWattsDelta.roundToInt()} W",
                                            color = Color(0xFF00E5FF),
                                            fontSize = 10.5.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { activeSimulationReport = null },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GhAccentGreen,
                        contentColor = Color(0xFF0F1114)
                    )
                ) {
                    Text("Done", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}
