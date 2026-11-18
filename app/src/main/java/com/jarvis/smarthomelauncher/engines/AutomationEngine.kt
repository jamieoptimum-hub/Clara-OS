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
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

object AutomationEngine {
    private const val TAG = "ClaraIFTTT"
    private const val PREFS_PENDING_WAITS = "clara_pending_automation_waits_v1"
    private const val KEY_PENDING_WAITS_JSON = "pending_wait_continuations_json"

    private val lastFiredScheduleKeyByRule = ConcurrentHashMap<String, String>()
    private val previousConditionSatisfiedByRule = ConcurrentHashMap<String, Boolean>()
    private val pendingWaitLock = Any()

    @Volatile
    private var eventBusSubscriberJob: Job? = null

    @Volatile
    private var hasResumedPendingWaitsInSession: Boolean = false

    /**
     * Checks whether a spoken voice command attempting a high-security action (disarming Security Center,
     * unlocking a vehicle, or unlocking a smart door lock) must be blocked by the Parental Lock / PIN shield.
     */
    private fun isVoiceProtectedActionBlocked(
        context: Context,
        bridge: BridgeSettings,
        targetName: String,
        actionDescription: String,
        onSpeak: (String) -> Unit
    ): Boolean {
        val requiresPin = bridge.isParentalLockActive ||
                (bridge.requirePinForAdminAndVehicles && bridge.hasConfiguredPin)
        if (!requiresPin) return false

        DeviceRepository.logAuditEvent(
            context = context,
            actorRole = bridge.activeUserRole,
            actionType = "VOICE_RBAC_BLOCKED",
            targetName = targetName,
            detail = "Blocked voice command '$actionDescription' under profile '${bridge.activeUserProfile.name}' (PIN required)",
            succeeded = false
        )
        onSpeak("Security PIN required to $actionDescription. Please unlock on the dashboard screen.")
        return true
    }

    private fun savePendingWaitContinuation(
        context: Context,
        continuationId: String,
        ruleId: String,
        resumeFromActionIndex: Int,
        executeAtEpochMs: Long,
        triggerSource: String
    ) {
        synchronized(pendingWaitLock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_PENDING_WAITS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_PENDING_WAITS_JSON, "[]") ?: "[]"
            val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
            val nextArr = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id") != continuationId) {
                    nextArr.put(o)
                }
            }
            nextArr.put(
                JSONObject().apply {
                    put("id", continuationId)
                    put("ruleId", ruleId)
                    put("resumeFromActionIndex", resumeFromActionIndex)
                    put("executeAtEpochMs", executeAtEpochMs)
                    put("triggerSource", triggerSource)
                }
            )
            prefs.edit().putString(KEY_PENDING_WAITS_JSON, nextArr.toString()).apply()
        }
    }

    private fun removePendingWaitContinuation(context: Context, continuationId: String) {
        synchronized(pendingWaitLock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_PENDING_WAITS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_PENDING_WAITS_JSON, "[]") ?: "[]"
            val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
            val nextArr = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id") != continuationId) {
                    nextArr.put(o)
                }
            }
            prefs.edit().putString(KEY_PENDING_WAITS_JSON, nextArr.toString()).apply()
        }
    }

    private fun resumePersistedWaitContinuationsIfNeeded(
        context: Context,
        rules: MutableList<AutomationRule>,
        tiles: MutableList<SmartTile>,
        bridgeProvider: () -> BridgeSettings,
        audioManager: AudioManager,
        scope: CoroutineScope,
        onSpeak: (String) -> Unit
    ) {
        if (hasResumedPendingWaitsInSession) return
        hasResumedPendingWaitsInSession = true

        val pendingItems = synchronized(pendingWaitLock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_PENDING_WAITS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_PENDING_WAITS_JSON, "[]") ?: "[]"
            val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
            val list = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { list.add(it) }
            }
            list
        }
        if (pendingItems.isEmpty()) return

        for (item in pendingItems) {
            val contId = item.optString("id", "")
            val ruleId = item.optString("ruleId", "")
            val resumeIdx = item.optInt("resumeFromActionIndex", 0)
            val executeAt = item.optLong("executeAtEpochMs", 0L)
            val triggerSource = item.optString("triggerSource", "Resumed Timer")
            val matchedRule = rules.firstOrNull { it.id == ruleId && it.isEnabled }
            if (matchedRule == null || resumeIdx !in matchedRule.actions.indices) {
                removePendingWaitContinuation(context, contId)
                continue
            }
            scope.launch(Dispatchers.Main) {
                val remainingMs = (executeAt - System.currentTimeMillis()).coerceAtLeast(0L)
                if (remainingMs > 0L) {
                    delay(remainingMs)
                }
                removePendingWaitContinuation(context, contId)
                val resumedRule = matchedRule.copy(
                    actions = matchedRule.actions.drop(resumeIdx)
                )
                executeRuleActions(
                    rule = resumedRule,
                    tiles = tiles,
                    bridge = bridgeProvider(),
                    audioManager = audioManager,
                    scope = scope,
                    context = context,
                    onSpeak = onSpeak,
                    triggerSource = "$triggerSource (Resumed after timer)"
                )
            }
        }
    }

    private fun currentIsoDayOfWeek(cal: Calendar): Int = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        Calendar.SUNDAY -> 7
        else -> 1
    }

    private fun isWithinAllowedDayAndWindow(cal: Calendar, rule: AutomationRule): Boolean {
        val isoDay = currentIsoDayOfWeek(cal)
        if (isoDay !in rule.daysOfWeek) return false
        if (!rule.restrictToTimeWindow || rule.triggerType == AutomationTriggerType.TIME_SCHEDULE) {
            return true
        }
        val nowMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMins = rule.windowStartHour * 60 + rule.windowStartMinute
        val endMins = rule.windowEndHour * 60 + rule.windowEndMinute
        return if (startMins <= endMins) {
            nowMins in startMins..endMins
        } else {
            nowMins >= startMins || nowMins <= endMins
        }
    }

    private fun compareThreshold(liveVal: Float, op: ComparisonOperator, target: Float): Boolean = when (op) {
        ComparisonOperator.BELOW -> liveVal < target
        ComparisonOperator.ABOVE -> liveVal > target
        ComparisonOperator.EQUALS -> abs(liveVal - target) <= 0.4f
    }

    private fun cleanBaseTileId(rawId: String): String =
        if (rawId.startsWith("DYNAMIC::")) rawId else rawId.substringBefore("::")

    private fun extractSubSocketTarget(rawId: String, tile: SmartTile? = null): String {
        val explicit = rawId.substringAfter("::", "")
        if (explicit == "SOCKET_1" || explicit == "SOCKET_2") return explicit
        return when (tile?.channelId) {
            "SPLIT_SOCKET_1" -> "SOCKET_1"
            "SPLIT_SOCKET_2" -> "SOCKET_2"
            else -> explicit
        }
    }

    private fun resolveEffectiveSelector(
        rawTileId: String,
        selector: AutomationTargetSelector
    ): AutomationTargetSelector {
        if (selector.isDynamic) return selector
        if (rawTileId.startsWith("DYNAMIC::", ignoreCase = true)) {
            val clsName = rawTileId.substringAfter("DYNAMIC::").trim().uppercase(Locale.US)
            val parsedClass = runCatching { DeviceClass.valueOf(clsName) }.getOrNull()
            if (parsedClass != null) {
                return selector.copy(
                    scope = AutomationTargetScope.DYNAMIC_SELECTOR,
                    deviceClass = parsedClass
                )
            }
        }
        return selector
    }

    private fun fuzzyTokenOverlapScore(query: String, candidate: String): Int {
        val qTokens = query.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()
        val cTokens = candidate.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()
        if (qTokens.isEmpty() || cTokens.isEmpty()) return 0
        return qTokens.intersect(cTokens).size
    }

    private fun resolveTileForRule(tiles: List<SmartTile>, tileId: String, tileTitle: String): SmartTile? {
        val baseId = cleanBaseTileId(tileId)
        if (baseId.isNotBlank()) {
            tiles.firstOrNull { it.id == baseId }?.let { return it }
        }
        val baseTitle = tileTitle
            .substringBefore(" \u2014 ")
            .substringBefore(" \u2022 Socket")
            .substringBefore(" (Socket")
            .trim()
        if (baseTitle.isNotBlank()) {
            tiles.firstOrNull {
                it.title.equals(baseTitle, ignoreCase = true) ||
                        it.socket1Name.equals(baseTitle, ignoreCase = true) ||
                        it.socket2Name.equals(baseTitle, ignoreCase = true)
            }?.let { return it }
            tiles.maxByOrNull { t ->
                fuzzyTokenOverlapScore(baseTitle, "${t.roomOrSubtitle} ${t.title} ${t.socket1Name} ${t.socket2Name}")
            }?.takeIf {
                fuzzyTokenOverlapScore(baseTitle, "${it.roomOrSubtitle} ${it.title} ${it.socket1Name} ${it.socket2Name}") > 0
            }?.let { return it }
        }
        return null
    }

    fun resolveTilesBySelector(
        tiles: List<SmartTile>,
        selector: AutomationTargetSelector
    ): List<SmartTile> {
        val cleanRoom = selector.roomFilter.trim()
        val filterByRoom = cleanRoom.isNotBlank() && !cleanRoom.equals("All Rooms", ignoreCase = true)
        val cleanCap = selector.requiredCapabilityKey.trim().lowercase(Locale.US)

        return tiles.filter { t ->
            if (!t.isConfiguredAndLinked ||
                t.type in setOf(TileType.CLOCK_WEATHER, TileType.GROUP_CARD, TileType.PHOTO_SLIDESHOW, TileType.MACRO_TILE, TileType.ENERGY_MONITOR)
            ) {
                return@filter false
            }
            if (filterByRoom && !t.roomOrSubtitle.equals(cleanRoom, ignoreCase = true)) {
                return@filter false
            }
            if (selector.deviceClass != null && t.deviceClass != selector.deviceClass) {
                return@filter false
            }
            if (selector.deviceSubtype != null &&
                selector.deviceSubtype != DeviceSubtype.GENERIC &&
                t.deviceSubtype != selector.deviceSubtype
            ) {
                return@filter false
            }
            if (cleanCap.isNotBlank()) {
                val effectiveCaps = t.capabilities.ifEmpty { SmartHomeApiClient.buildDefaultCapabilitiesForTile(t) }
                val hasCap = effectiveCaps.any { c ->
                    c.key.lowercase(Locale.US) == cleanCap || c.key.lowercase(Locale.US).contains(cleanCap)
                } || when (cleanCap) {
                    "target_temperature", "temp_set" -> t.isThermostatTile || t.hasTemperatureSetpointCapability
                    "cur_power", "power" -> t.hasPowerTelemetryCapability
                    "brightness", "switch_led" -> t.hasDimmingCapability
                    "fan_speed" -> t.isTowerFanTile || t.hasFanSpeedCapability
                    "lock", "vehicle_lock" -> t.hasLockCapability
                    else -> false
                }
                if (!hasCap) return@filter false
            }
            true
        }
    }

    private fun resolveTargetIndicesForAction(
        tiles: List<SmartTile>,
        action: AutomationAction
    ): List<Int> {
        val effectiveSelector = resolveEffectiveSelector(action.targetTileId, action.targetSelector)
        if (effectiveSelector.isDynamic) {
            val matchedIds = resolveTilesBySelector(tiles, effectiveSelector).map { it.id }.toSet()
            return tiles.indices.filter { tiles[it].id in matchedIds }
        }
        val baseId = cleanBaseTileId(action.targetTileId)
        val cleanTitle = action.targetTileTitle
            .substringBefore(" \u2014 ")
            .substringBefore(" \u2022 Socket")
            .substringBefore(" (Socket")
            .trim()
        val idx = tiles.indexOfFirst {
            (baseId.isNotBlank() && it.id == baseId) ||
                    (cleanTitle.isNotBlank() && it.title.equals(cleanTitle, ignoreCase = true))
        }
        return if (idx in tiles.indices) listOf(idx) else emptyList()
    }

    fun isOffPeakNightTariffNow(
        cal: Calendar = Calendar.getInstance(),
        bridge: BridgeSettings = BridgeSettings()
    ): Boolean {
        val liveOctopus = SmartHomeApiClient.cachedOctopusTariffCapability
        if (liveOctopus != null) {
            val nowMs = cal.timeInMillis
            val activeSlot = liveOctopus.upcomingSlots.firstOrNull { nowMs in it.startEpochMs..it.endEpochMs }
            val thresholdPence = (bridge.electricityTariffNightRateGbp * 100f).coerceAtLeast(10.0f)
            if (activeSlot != null) {
                if (activeSlot.isPlungeOrNegative || activeSlot.isOffPeakDispatchSlot || activeSlot.rateIncVatPence <= thresholdPence) {
                    return true
                }
            } else if (liveOctopus.currentRatePence <= thresholdPence) {
                return true
            }
        }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val start = bridge.offPeakStartHour.coerceIn(0, 23)
        val end = bridge.offPeakEndHour.coerceIn(0, 23)
        return if (start <= end) {
            hour in start until end
        } else {
            hour >= start || hour < end
        }
    }

    private fun evaluateSingleSecondaryCondition(
        cond: AutomationSecondaryCondition,
        tiles: List<SmartTile>,
        weather: WeatherInfo,
        bridge: BridgeSettings = BridgeSettings()
    ): Boolean {
        val effectiveSelector = resolveEffectiveSelector(cond.targetTileId, cond.targetSelector)
        val candidateTiles: List<SmartTile> = if (effectiveSelector.isDynamic) {
            resolveTilesBySelector(tiles, effectiveSelector)
        } else {
            listOfNotNull(resolveTileForRule(tiles, cond.targetTileId, cond.targetTileTitle))
        }

        return when (cond.conditionType) {
            AutomationTriggerType.DEVICE_STATE_CHANGE -> {
                if (candidateTiles.isEmpty()) return false
                candidateTiles.any { target ->
                    when (cond.targetDeviceState) {
                        DeviceTriggerTargetState.TURNED_ON, DeviceTriggerTargetState.SOCKET_1_ON -> target.isActive
                        DeviceTriggerTargetState.TURNED_OFF, DeviceTriggerTargetState.SOCKET_1_OFF -> !target.isActive
                        DeviceTriggerTargetState.SOCKET_2_ON -> target.isSocket2Active
                        DeviceTriggerTargetState.SOCKET_2_OFF -> !target.isSocket2Active
                    }
                }
            }
            AutomationTriggerType.TEMPERATURE_THRESHOLD -> {
                if (candidateTiles.isEmpty()) return false
                // F-03: Require online hardware-reported temperature telemetry; never trigger on offline 20.5°C defaults
                candidateTiles.any { target ->
                    if (!target.isOnline) return@any false
                    val trustedTemp = target.hardwareAmbientTempCelsiusOrNull()
                        ?: target.temperature.takeIf { it > 0f && it != 20.5f }
                        ?: return@any false
                    compareThreshold(trustedTemp, cond.comparisonOperator, cond.thresholdValue)
                }
            }
            AutomationTriggerType.POWER_WATTS_THRESHOLD -> {
                if (candidateTiles.isEmpty()) return false
                candidateTiles.any { target ->
                    compareThreshold(target.powerWatts, cond.comparisonOperator, cond.thresholdValue)
                }
            }
            AutomationTriggerType.WEATHER_CONDITION -> {
                if (cond.weatherKeyword.equals("TEMP", ignoreCase = true)) {
                    compareThreshold(weather.temperatureCelsius.toFloat(), cond.comparisonOperator, cond.thresholdValue)
                } else {
                    weather.conditionText.contains(cond.weatherKeyword.trim(), ignoreCase = true)
                }
            }
            AutomationTriggerType.SECURITY_MODE_IS -> {
                val activeMode = JarvisLiveBridge.securityCenterModeFlow.value
                activeMode == cond.targetSecurityMode || bridge.securityCenterMode == cond.targetSecurityMode
            }
            AutomationTriggerType.TARIFF_WINDOW_IS -> {
                val isNightTariff = isOffPeakNightTariffNow( bridge = bridge)
                if (cond.requireOffPeakTariff) isNightTariff else !isNightTariff
            }
            AutomationTriggerType.DAYLIGHT_IS -> {
                val isNightNow = !weather.isDay
                if (cond.requireNighttime) isNightNow else weather.isDay
            }
            AutomationTriggerType.CAPABILITY_VALUE_MATCH -> {
                if (candidateTiles.isEmpty()) return false
                val reqKey = cond.capabilityKey.trim().lowercase(Locale.US)
                val expectedText = cond.capabilityExpectedText.trim()
                candidateTiles.any { tile ->
                    val caps = tile.capabilities.ifEmpty { SmartHomeApiClient.buildDefaultCapabilitiesForTile(tile) }
                    val matchedCap = caps.firstOrNull {
                        it.key.lowercase(Locale.US) == reqKey || it.key.lowercase(Locale.US).contains(reqKey)
                    }
                    val rawVal = matchedCap?.currentValue ?: when (reqKey) {
                        "humidity" -> tile.humidity.toString()
                        "battery", "battery_percentage" -> tile.batteryPercent.toString()
                        "cur_power", "power" -> tile.powerWatts.toString()
                        "temperature", "temp_current" -> tile.temperature.toString()
                        else -> ""
                    }
                    if (expectedText.isNotBlank()) {
                        rawVal.equals(expectedText, ignoreCase = true)
                    } else {
                        val num = rawVal.toFloatOrNull() ?: return@any false
                        compareThreshold(num, cond.comparisonOperator, cond.thresholdValue)
                    }
                }
            }
            else -> true
        }
    }

    private fun areConditionListSatisfied(
        combinator: ConditionCombinator,
        conditions: List<AutomationSecondaryCondition>,
        tiles: List<SmartTile>,
        weather: WeatherInfo,
        bridge: BridgeSettings
    ): Boolean {
        if (conditions.isEmpty()) return true
        return when (combinator) {
            ConditionCombinator.AND -> conditions.all { evaluateSingleSecondaryCondition(it, tiles, weather, bridge) }
            ConditionCombinator.OR -> conditions.any { evaluateSingleSecondaryCondition(it, tiles, weather, bridge) }
        }
    }

    private fun areSecondaryConditionsSatisfied(
        rule: AutomationRule,
        tiles: List<SmartTile>,
        weather: WeatherInfo,
        bridge: BridgeSettings = BridgeSettings()
    ): Boolean = areConditionListSatisfied(rule.combinator, rule.secondaryConditions, tiles, weather, bridge)

    /**
     * Evaluates `rule.branches` in order (`when`-style decision routing).
     * Returns `(matchedBranchLabel, actionsToExecute)`.
     */
    fun resolveMatchingBranchAndActions(
        rule: AutomationRule,
        tiles: List<SmartTile>,
        weather: WeatherInfo = JarvisLiveBridge.weatherFlow.value,
        bridge: BridgeSettings = BridgeSettings()
    ): Pair<String, List<AutomationAction>> {
        if (rule.branches.isEmpty()) {
            return "Primary Actions" to rule.actions
        }
        for (branch in rule.branches) {
            if (areConditionListSatisfied(branch.combinator, branch.conditions, tiles, weather, bridge)) {
                return branch.label.ifBlank { "Matched Branch" } to branch.actions
            }
        }
        if (rule.otherwiseActions.isNotEmpty()) {
            return "Otherwise (Default Branch)" to rule.otherwiseActions
        }
        return "No Branch Matched" to emptyList()
    }

    private fun summarizeTileStateForSimulation(t: SmartTile): String = when {
        t.isVehicleTile -> "${if (t.vehicleLocked) "Locked" else "Unlocked"}${if (t.vehicleClimateActive) " \u2022 Climate ON" else ""}"
        t.deviceSubtype == DeviceSubtype.LOCK -> if (t.isActive) "Locked" else "Unlocked"
        t.isThermostatTile -> if (t.isActive && !t.hvacMode.equals("OFF", true)) {
            "${t.hvacMode} (${"%.1f".format(Locale.US, t.targetTemperature)}\u00B0C)"
        } else "OFF"
        t.isTowerFanTile -> if (t.isActive) "Speed ${t.fanSpeedLevel} (${t.fanMode})" else "OFF"
        t.isMultiGangSwitch -> "${t.socket1Name}:${if (t.isActive) "ON" else "OFF"}, ${t.socket2Name}:${if (t.isSocket2Active) "ON" else "OFF"}"
        t.hasDimmingCapability -> if (t.isActive) "ON (${t.volume.roundToInt()}%)" else "OFF"
        else -> if (t.isActive) "ON" else "OFF"
    }

    /**
     * Side-Effect-Free Dry-Run Simulator for any `AutomationRule`.
     * Computes which `AutomationBranch` wins, which devices will mutate, which devices are
     * protected by a Stage 4 30-minute manual hold, and the estimated wattage and £/hr delta.
     */
    fun simulateRule(
        rule: AutomationRule,
        tiles: List<SmartTile>,
        bridge: BridgeSettings
    ): SimulationReport {
        val weather = JarvisLiveBridge.weatherFlow.value
        val (branchLabel, actionsToSimulate) = resolveMatchingBranchAndActions(rule, tiles, weather, bridge)
        val mutations = mutableListOf<SimulatedDeviceMutation>()
        var totalWattsSaved = 0f

        for (action in actionsToSimulate) {
            if (!action.actionType.requiresTargetTile) continue
            val targetIndices = resolveTargetIndicesForAction(tiles, action)
            for (idx in targetIndices) {
                val current = tiles[idx]
                val (canExecute, holdReason) = DeviceRepository.canExecuteOutboundIntent(
                    tile = current,
                    intentTier = IntentAuthorityTier.AUTOMATION
                )
                val beforeSummary = summarizeTileStateForSimulation(current)
                val projectedTile = when (action.actionType) {
                    AutomationActionType.TURN_ON,
                    AutomationActionType.SOCKET_1_ON,
                    AutomationActionType.BOTH_SOCKETS_ON -> current.copy(
                        isActive = true,
                        isSocket2Active = if (action.actionType == AutomationActionType.BOTH_SOCKETS_ON) true else current.isSocket2Active,
                        hvacMode = if (current.isThermostatTile) "HEAT" else current.hvacMode
                    )
                    AutomationActionType.TURN_OFF,
                    AutomationActionType.SOCKET_1_OFF,
                    AutomationActionType.BOTH_SOCKETS_OFF -> current.copy(
                        isActive = false,
                        isSocket2Active = if (action.actionType == AutomationActionType.BOTH_SOCKETS_OFF) false else current.isSocket2Active,
                        hvacMode = if (current.isThermostatTile) "OFF" else current.hvacMode
                    )
                    AutomationActionType.SOCKET_2_ON -> current.copy(isSocket2Active = true)
                    AutomationActionType.SOCKET_2_OFF -> current.copy(isSocket2Active = false)
                    AutomationActionType.TOGGLE -> current.copy(isActive = !current.isActive)
                    AutomationActionType.SET_TEMPERATURE -> current.copy(
                        isActive = true,
                        hvacMode = "HEAT",
                        targetTemperature = action.numericValue.coerceIn(5f, 37f)
                    )
                    AutomationActionType.SET_HVAC_MODE -> current.copy(
                        hvacMode = action.textValue.uppercase(Locale.US),
                        isActive = !action.textValue.equals("OFF", true)
                    )
                    AutomationActionType.SET_LEVEL_OR_VOLUME -> current.copy(
                        isActive = action.numericValue > 0f,
                        volume = action.numericValue.coerceIn(0f, 100f)
                    )
                    else -> current
                }
                val afterSummary = summarizeTileStateForSimulation(projectedTile)
                val wattsDelta = when {
                    current.isActive && !projectedTile.isActive -> {
                        if (current.powerWatts > 1f) current.powerWatts
                        else if (current.isThermostatTile) 850f
                        else if (current.deviceClass == DeviceClass.LIGHT) 12f
                        else 45f
                    }
                    current.isThermostatTile && projectedTile.targetTemperature < current.targetTemperature ->
                        (current.targetTemperature - projectedTile.targetTemperature) * 140f
                    else -> 0f
                }
                if (canExecute && current.isOnline) {
                    totalWattsSaved += wattsDelta
                }
                mutations.add(
                    SimulatedDeviceMutation(
                        tileId = current.id,
                        tileTitle = current.title,
                        roomName = current.roomOrSubtitle.ifBlank { "Home" },
                        deviceClassLabel = current.deviceClass.label,
                        currentStateSummary = beforeSummary,
                        projectedStateSummary = afterSummary,
                        wouldBeBlockedByHold = !canExecute || !current.isOnline,
                        blockOrOfflineReason = when {
                            !current.isOnline -> "Device is currently offline (will queue in Shadow Twin)"
                            !canExecute -> holdReason
                            else -> ""
                        },
                        estimatedWattsDelta = wattsDelta
                    )
                )
            }
        }

        val activeTariff = if (isOffPeakNightTariffNow(bridge = bridge)) {
            bridge.electricityTariffNightRateGbp
        } else {
            bridge.electricityTariffDayRateGbp
        }
        val hourlySavingsGbp = (totalWattsSaved / 1000f) * activeTariff

        return SimulationReport(
            scenarioTitle = rule.name,
            matchedBranchLabel = branchLabel,
            mutations = mutations,
            totalWattsSaved = totalWattsSaved,
            estimatedHourlyCostDeltaGbp = hourlySavingsGbp
        )
    }

    /**
     * Side-Effect-Free Dry-Run Simulator for Household Security & Occupancy Scenarios
     * (e.g. "Show me what would happen if I left home now").
     */
    fun simulateSecurityModeScenario(
        mode: SecurityCenterMode,
        tiles: List<SmartTile>,
        bridge: BridgeSettings
    ): SimulationReport {
        val mutations = mutableListOf<SimulatedDeviceMutation>()
        var totalWattsSaved = 0f

        for (t in tiles) {
            if (!t.isConfiguredAndLinked ||
                t.type in setOf(TileType.CLOCK_WEATHER, TileType.GROUP_CARD, TileType.PHOTO_SLIDESHOW, TileType.MACRO_TILE, TileType.ENERGY_MONITOR)
            ) continue
            val beforeSummary = summarizeTileStateForSimulation(t)
            val projected = when (mode) {
                SecurityCenterMode.HOME -> {
                    if (t.isThermostatTile && t.hvacMode.equals("ECO", true)) {
                        t.copy(hvacMode = "HEAT", isActive = true, targetTemperature = bridge.securityHomeTempC)
                    } else t
                }
                SecurityCenterMode.AWAY -> when {
                    t.isVehicleTile -> t.copy(vehicleLocked = true, isActive = true)
                    t.deviceSubtype == DeviceSubtype.LOCK -> t.copy(isActive = true)
                    t.isThermostatTile -> t.copy(hvacMode = "ECO", targetTemperature = bridge.securityAwayTempC)
                    t.deviceClass == DeviceClass.LIGHT || t.deviceSubtype == DeviceSubtype.TV || t.isLytmiTile || t.isTowerFanTile ->
                        t.copy(isActive = false)
                    else -> t
                }
                SecurityCenterMode.NIGHT -> when {
                    t.isVehicleTile -> t.copy(vehicleLocked = true, isActive = true)
                    t.deviceSubtype == DeviceSubtype.LOCK -> t.copy(isActive = true)
                    t.deviceSubtype in setOf(DeviceSubtype.BLIND, DeviceSubtype.GARAGE_DOOR) ->
                        t.copy(isActive = false, volume = 0f)
                    t.isThermostatTile -> t.copy(targetTemperature = bridge.securityNightTempC)
                    t.isTowerFanTile && t.isActive -> t.copy(fanMode = "SLEEP", fanSpeedLevel = 1)
                    t.deviceSubtype == DeviceSubtype.TV || t.isLytmiTile -> t.copy(isActive = false)
                    else -> t
                }
                SecurityCenterMode.VACATION -> when {
                    t.isVehicleTile -> t.copy(vehicleLocked = true, isActive = true)
                    t.deviceSubtype == DeviceSubtype.LOCK -> t.copy(isActive = true)
                    t.deviceSubtype in setOf(DeviceSubtype.BLIND, DeviceSubtype.GARAGE_DOOR) ->
                        t.copy(isActive = false, volume = 0f)
                    t.isThermostatTile -> t.copy(hvacMode = "ECO", targetTemperature = bridge.securityVacationTempC, isActive = false)
                    t.isSwitchTile || t.deviceClass == DeviceClass.LIGHT || t.deviceSubtype == DeviceSubtype.TV || t.isLytmiTile || t.isTowerFanTile ->
                        t.copy(isActive = false, isSocket2Active = false)
                    else -> t
                }
            }
            if (projected != t) {
                val afterSummary = summarizeTileStateForSimulation(projected)
                val wattsSaved = when {
                    t.isActive && !projected.isActive -> {
                        if (t.powerWatts > 1f) t.powerWatts
                        else if (t.isThermostatTile) 900f
                        else if (t.deviceClass == DeviceClass.LIGHT) 14f
                        else 55f
                    }
                    t.isThermostatTile && projected.targetTemperature < t.targetTemperature ->
                        (t.targetTemperature - projected.targetTemperature) * 140f
                    else -> 0f
                }
                totalWattsSaved += wattsSaved
                mutations.add(
                    SimulatedDeviceMutation(
                        tileId = t.id,
                        tileTitle = t.title,
                        roomName = t.roomOrSubtitle.ifBlank { "Home" },
                        deviceClassLabel = t.deviceClass.label,
                        currentStateSummary = beforeSummary,
                        projectedStateSummary = afterSummary,
                        wouldBeBlockedByHold = !t.isOnline,
                        blockOrOfflineReason = if (!t.isOnline) "Offline (will queue in Shadow Twin)" else "",
                        estimatedWattsDelta = wattsSaved
                    )
                )
            }
        }

        val activeTariff = if (isOffPeakNightTariffNow(bridge = bridge)) {
            bridge.electricityTariffNightRateGbp
        } else {
            bridge.electricityTariffDayRateGbp
        }
        return SimulationReport(
            scenarioTitle = "Scenario Simulation: ${mode.label} Mode (${mode.subtitle})",
            matchedBranchLabel = "Security Center \u2192 ${mode.label}",
            mutations = mutations,
            totalWattsSaved = totalWattsSaved,
            estimatedHourlyCostDeltaGbp = (totalWattsSaved / 1000f) * activeTariff
        )
    }

    /**
     * Behavioral Routine Synthesis Engine ("Habit Shadowing"):
     * Records manual user device actuations and mines temporal patterns. When the user performs
     * the same action on the same device within a +/-20 minute window on 3+ occasions, automatically
     * synthesizes a 1-tap `SuggestedAutomationDraft` in `JarvisLiveBridge.suggestedAutomationsFlow`.
     */
    fun recordManualUserActuation(context: Context, tile: SmartTile) {
        if (tile.type == TileType.CLOCK_WEATHER ||
            tile.type == TileType.GROUP_CARD ||
            tile.type == TileType.MACRO_TILE ||
            tile.type == TileType.PHOTO_SLIDESHOW ||
            tile.type == TileType.ENERGY_MONITOR
        ) {
            return
        }
        val cal = Calendar.getInstance()
        val actionType = when {
            tile.isThermostatTile && tile.isActive -> AutomationActionType.SET_TEMPERATURE
            tile.isActive -> AutomationActionType.TURN_ON
            else -> AutomationActionType.TURN_OFF
        }
        val event = HabitActuationEvent(
            tileId = tile.id,
            tileTitle = tile.title,
            actionType = actionType,
            numericValue = if (tile.isThermostatTile) tile.targetTemperature else tile.volume,
            textValue = if (tile.isThermostatTile) tile.hvacMode else "",
            hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
            minuteOfHour = cal.get(Calendar.MINUTE),
            isoDayOfWeek = currentIsoDayOfWeek(cal)
        )
        val allEvents = DeviceRepository.recordHabitActuationEvent(context, event)
        val existingRules = DeviceRepository.loadAutomations(context)
        analyzeAndSynthesizeHabitRules(context, allEvents, existingRules)
    }

    fun analyzeAndSynthesizeHabitRules(
        context: Context,
        events: List<HabitActuationEvent>,
        existingRules: List<AutomationRule>
    ) {
        if (events.size < 3) return
        val dismissedKeys = DeviceRepository.getDismissedHabitSuggestionKeys(context)
        val groupedByDeviceAndAction = events.groupBy { "${it.tileId}::${it.actionType.name}" }
        val suggestions = mutableListOf<SuggestedAutomationDraft>()

        for ((key, group) in groupedByDeviceAndAction) {
            if (group.size < 3) continue
            val latest = group.first()
            val latestMins = latest.hourOfDay * 60 + latest.minuteOfHour
            val matchingCluster = group.filter { ev ->
                val mins = ev.hourOfDay * 60 + ev.minuteOfHour
                val diff = abs(mins - latestMins)
                minOf(diff, 1440 - diff) <= 20
            }
            if (matchingCluster.size < 3) continue

            val avgMins = matchingCluster.map { it.hourOfDay * 60 + it.minuteOfHour }.average().roundToInt().coerceIn(0, 1439)
            val schedH = avgMins / 60
            val schedM = (avgMins % 60 / 5) * 5
            val suggestionKey = "habit_${key}_${schedH}_${schedM}"
            if (suggestionKey in dismissedKeys) continue

            val alreadyAutomated = existingRules.any { r ->
                r.triggerType == AutomationTriggerType.TIME_SCHEDULE &&
                        abs((r.scheduleHour * 60 + r.scheduleMinute) - avgMins) <= 25 &&
                        r.actions.any { it.targetTileId == latest.tileId && it.actionType == latest.actionType }
            }
            if (alreadyAutomated) continue

            val action = AutomationAction(
                targetTileId = latest.tileId,
                targetTileTitle = latest.tileTitle,
                actionType = latest.actionType,
                numericValue = latest.numericValue,
                textValue = latest.textValue
            )
            val ruleName = "${action.toSummaryText()} at %02d:%02d".format(schedH, schedM)
            val draftRule = AutomationRule(
                id = suggestionKey,
                name = ruleName,
                isEnabled = true,
                triggerType = AutomationTriggerType.TIME_SCHEDULE,
                scheduleHour = schedH,
                scheduleMinute = schedM,
                daysOfWeek = matchingCluster.map { it.isoDayOfWeek }.toSet().ifEmpty { setOf(1, 2, 3, 4, 5, 6, 7) },
                actions = listOf(action)
            )
            suggestions.add(
                SuggestedAutomationDraft(
                    id = suggestionKey,
                    confidenceOccurrences = matchingCluster.size,
                    reasonExplanation = "You manually ${action.toSummaryText().lowercase(Locale.US)} around %02d:%02d (${matchingCluster.size} times recently).".format(schedH, schedM),
                    draftRule = draftRule
                )
            )
        }
        JarvisLiveBridge.suggestedAutomationsFlow.value = suggestions.take(4)
    }

    private suspend fun dispatchSingleSocketCommandForTile(
        context: Context,
        tile: SmartTile,
        bridge: BridgeSettings,
        socketNumber: Int,
        turnOn: Boolean
    ): Boolean {
        val providerKey = resolveEffectiveProviderKey(tile)
        return when {
            providerKey.contains("SONOFF") || providerKey.contains("EWELINK") -> {
                EWeLinkSonoffClient.setSonoffSwitchState(
                    context = context,
                    tile = tile,
                    turnOn = turnOn,
                    outletIndex = (socketNumber - 1).coerceAtLeast(0)
                )
            }
            providerKey.contains("SHELLY") || providerKey.contains("MQTT") -> {
                if (tile.ipAddress.isNotBlank() &&
                    MqttAndDiyClient.sendTasmotaHttpCommand(tile.ipAddress, turnOn, relayNumber = socketNumber)
                ) {
                    true
                } else {
                    MqttAndDiyClient.publishTileCommand(context, tile, socketNumber = socketNumber)
                }
            }
            providerKey.contains("TPLINK") || providerKey.contains("TAPO") || providerKey.contains("KASA") -> {
                val cleanIp = tile.ipAddress.substringBefore(":").trim()
                if (cleanIp.isNotBlank()) {
                    TpLinkTapoKasaClient.setKasaRelayState(cleanIp, turnOn, socketNumber = socketNumber) ||
                            TpLinkTapoKasaClient.setTapoDeviceState(context, cleanIp, turnOn)
                } else {
                    TuyaApiClient.sendSingleSocketPlugCommand(
                        bridge = bridge,
                        deviceId = tile.serialNumber.ifBlank { tile.entityId },
                        socketNumber = socketNumber,
                        turnOn = turnOn,
                        ipAddress = tile.ipAddress,
                        localKey = tile.secretCode
                    )
                }
            }
            providerKey.contains("MATTER") -> {
                MatterProtocolClient.sendMatterCommand(context, tile, bridge)
            }
            else -> {
                TuyaApiClient.sendSingleSocketPlugCommand(
                    bridge = bridge,
                    deviceId = tile.serialNumber.ifBlank { tile.entityId },
                    socketNumber = socketNumber,
                    turnOn = turnOn,
                    ipAddress = tile.ipAddress,
                    localKey = tile.secretCode
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // 1. REAL-TIME EVENT BUS SUBSCRIBER & SECURITY CENTER ORCHESTRATOR
    // -----------------------------------------------------------------------
    fun ensureEventBusSubscriberRunning(
        context: Context,
        rules: MutableList<AutomationRule>,
        tiles: MutableList<SmartTile>,
        bridgeProvider: () -> BridgeSettings,
        audioManager: AudioManager,
        scope: CoroutineScope,
        onSpeak: (String) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val savedHabitEvents = DeviceRepository.loadHabitActuationEvents(context)
            analyzeAndSynthesizeHabitRules(context, savedHabitEvents, rules)
        }
        resumePersistedWaitContinuationsIfNeeded(
            context = context,
            rules = rules,
            tiles = tiles,
            bridgeProvider = bridgeProvider,
            audioManager = audioManager,
            scope = scope,
            onSpeak = onSpeak
        )
        if (eventBusSubscriberJob?.isActive == true) return
        eventBusSubscriberJob = scope.launch(Dispatchers.Default) {
            JarvisLiveBridge.eventsFlow.collect { evt ->
                val currentBridge = bridgeProvider()
                when (evt.category) {
                    JarvisEventCategory.CAMERA_AI -> {
                        if (evt.cameraAiEvent != CameraAiEventType.NONE) {
                            withContext(Dispatchers.Main) {
                                val camIdx = tiles.indexOfFirst { it.id == evt.sourceDeviceId }
                                if (camIdx in tiles.indices) {
                                    tiles[camIdx] = tiles[camIdx].copy(
                                        lastCameraAiEvent = evt.cameraAiEvent,
                                        lastCameraEventTimestampMs = evt.timestampEpochMs
                                    )
                                }
                                if (currentBridge.securityCenterMode != SecurityCenterMode.HOME) {
                                    val alertText = "${evt.cameraAiEvent.label} at${evt.sourceDeviceTitle.ifBlank { "Camera" }}"
                                    if (currentBridge.enableVoiceAnnouncements) {
                                        onSpeak(alertText)
                                    }
                                    if (currentBridge.enableNestMiniDoorbellChime &&
                                        currentBridge.securityCenterMode in listOf(SecurityCenterMode.AWAY, SecurityCenterMode.VACATION)
                                    ) {
                                        GoogleCastLanClient.playDoorbellChimeOnNestMinis(
                                            scope = scope,
                                            bridge = currentBridge,
                                            tiles = tiles,
                                            customAnnouncementText = alertText
                                        )
                                    }
                                }
                            }
                        }
                    }
                    JarvisEventCategory.DOORBELL,
                    JarvisEventCategory.PLUG_POWER,
                    JarvisEventCategory.CLIMATE,
                    JarvisEventCategory.LIGHT -> {
                        evaluateAllRules(
                            context = context,
                            rules = rules,
                            tiles = tiles,
                            bridge = currentBridge,
                            audioManager = audioManager,
                            scope = scope,
                            onSpeak = onSpeak
                        )
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Orchestrates Clara Security Center modes (`HOME`, `AWAY`, `NIGHT`, `VACATION`):
     * - Updates BridgeSettings & Security Center tiles
     * - Automatically locks Jaguar / Mercedes vehicles & smart locks in Away / Night / Vacation
     * - Adjusts climate setpoints, tower fans, and non-essential plugs/lighting
     */
    fun applySecurityCenterMode(
        mode: SecurityCenterMode,
        context: Context,
        tiles: MutableList<SmartTile>,
        bridge: BridgeSettings,
        audioManager: AudioManager,
        scope: CoroutineScope,
        onSpeak: (String) -> Unit
    ): BridgeSettings {
        val updatedBridge = bridge.copy(securityCenterMode = mode)
        DeviceRepository.saveBridge(context, updatedBridge)
        JarvisLiveBridge.securityCenterModeFlow.value = mode
        val now = System.currentTimeMillis()

        for (i in tiles.indices) {
            val t = tiles[i]
            val updated = when {
                t.isSecurityCenterTile -> t.copy(
                    securityMode = mode,
                    isActive = mode != SecurityCenterMode.HOME,
                    lastUserActuationEpochMs = now
                )
                mode == SecurityCenterMode.HOME -> {
                    if (t.isThermostatTile && t.hvacMode.equals("ECO", ignoreCase = true)) {
                        t.copy(
                            hvacMode = "HEAT",
                            isActive = true,
                            targetTemperature = bridge.securityHomeTempC,
                            transactionState = CommandTransactionState.PENDING_ACK,
                            lastUserActuationEpochMs = now
                        )
                    } else {
                        t
                    }
                }
                mode == SecurityCenterMode.AWAY -> {
                    when {
                        t.isVehicleTile -> t.copy(
                            vehicleLocked = true,
                            isActive = true,
                            transactionState = CommandTransactionState.PENDING_ACK,
                            lastUserActuationEpochMs = now
                        )
                        t.deviceSubtype == DeviceSubtype.LOCK -> t.copy(
                            isActive = true,
                            transactionState = CommandTransactionState.PENDING_ACK,
                            lastUserActuationEpochMs = now
                        )
                        t.isThermostatTile -> t.copy(
                            hvacMode = "ECO",
                            targetTemperature = bridge.securityAwayTempC,
                            transactionState = CommandTransactionState.PENDING_ACK,
                            lastUserActuationEpochMs = now
                        )
                        t.deviceClass == DeviceClass.LIGHT || t.deviceSubtype == DeviceSubtype.TV || t.isLytmiTile || t.isTowerFanTile ->
                            t.copy(
                                isActive = false,
                                transactionState = CommandTransactionState.PENDING_ACK,
                                lastUserActuationEpochMs = now
                            )
                        else -> t
                    }
                }
                mode == SecurityCenterMode.NIGHT -> {
                    when {
                        t.isVehicleTile -> t.copy(
                            vehicleLocked = true,
                            isActive = true,
                            transactionState = CommandTransactionState.PENDING_ACK,
                            lastUserActuationEpochMs = now
                        )
                        t.deviceSubtype == DeviceSubtype.LOCK -> t.copy(
                            isActive = true,
                            transactionState = CommandTransactionState.PENDING_ACK,
                            lastUserActuationEpochMs = now
                        )
                        t.deviceSubtype in setOf(DeviceSubtype.BLIND, DeviceSubtype.GARAGE_DOOR) -> t.copy(
                            isActive = false,
                            volume = 0f,
                            transactionState = CommandTransactionState.PENDING_ACK,
                            lastUserActuationEpochMs = now
                        )
                        t.isThermostatTile -> t.copy(
                            targetTemperature = bridge.securityNightTempC,
                            transactionState = CommandTransactionState.PENDING_ACK,
                            lastUserActuationEpochMs = now
                        )
                        t.isTowerFanTile && t.isActive -> t.copy(
                            fanMode = "SLEEP",
                            fanSpeedLevel = 1,
                            volume = 17f,
                            transactionState = CommandTransactionState.PENDING_ACK,
                            lastUserActuationEpochMs = now
                        )
                        t.deviceSubtype == DeviceSubtype.TV || t.isLytmiTile -> t.copy(
                            isActive = false,
                            transactionState = CommandTransactionState.PENDING_ACK,
                            lastUserActuationEpochMs = now
                        )
                        else -> t
                    }
                }
                mode == SecurityCenterMode.VACATION -> {
                    when {
                        t.isVehicleTile -> t.copy(
                            vehicleLocked = true,
                            isActive = true,
                            transactionState = CommandTransactionState.PENDING_ACK,
                            lastUserActuationEpochMs = now
                        )
                        t.deviceSubtype == DeviceSubtype.LOCK -> t.copy(
                            isActive = true,
                            transactionState = CommandTransactionState.PENDING_ACK,
                            lastUserActuationEpochMs = now
                        )
                        t.deviceSubtype in setOf(DeviceSubtype.BLIND, DeviceSubtype.GARAGE_DOOR) -> t.copy(
                            isActive = false,
                            volume = 0f,
                            transactionState = CommandTransactionState.PENDING_ACK,
                            lastUserActuationEpochMs = now
                        )
                        t.isThermostatTile -> t.copy(
                            hvacMode = "ECO",
                            targetTemperature = bridge.securityVacationTempC,
                            isActive = false,
                            transactionState = CommandTransactionState.PENDING_ACK,
                            lastUserActuationEpochMs = now
                        )
                        t.isSwitchTile || t.deviceClass == DeviceClass.LIGHT || t.deviceSubtype == DeviceSubtype.TV || t.isLytmiTile || t.isTowerFanTile ->
                            t.copy(
                                isActive = false,
                                isSocket2Active = false,
                                powerWatts = 0f,
                                transactionState = CommandTransactionState.PENDING_ACK,
                                lastUserActuationEpochMs = now
                            )
                        else -> t
                    }
                }
                else -> t
            }
            if (updated != t) {
                tiles[i] = updated
                if (!updated.isSecurityCenterTile && !updated.isGroupCardTile) {
                    dispatchDeviceUpdate(
                        updated = updated,
                        bridge = updatedBridge,
                        audioManager = audioManager,
                        scope = scope,
                        pinAuthorized = true
                    )
                }
            }
        }
        DeviceRepository.saveTiles(context, tiles)
        DeviceRepository.logAuditEvent(
            context = context,
            actorRole = updatedBridge.activeUserRole,
            actionType = "SECURITY_MODE_${mode.name}",
            targetName = "Clara Security Center",
            detail = "Switched Security Center to ${mode.label} (${mode.subtitle})",
            succeeded = true
        )
        JarvisLiveBridge.emitPlatformEvent(
            JarvisPlatformEvent(
                category = JarvisEventCategory.SECURITY_MODE,
                sourceDeviceTitle = "Security Center",
                eventType = mode.name,
                summary = "Security Center armed in ${mode.label} mode"
            )
        )
        onSpeak("Security Center set to ${mode.label} mode. ${mode.subtitle}.")
        return updatedBridge
    }

    // -----------------------------------------------------------------------
    // 2. RULE EVALUATION ENGINE (Gated by Multi-Tablet Primary Mesh Authority)
    // -----------------------------------------------------------------------
    fun buildFrostGuardEvRuleTemplate(vehicleTile: SmartTile): AutomationRule {
        return AutomationRule(
            id = "frost_guard_${vehicleTile.id}",
            name = "Frost Guard EV Pre-Conditioning (${vehicleTile.title})",
            isEnabled = false,
            triggerType = AutomationTriggerType.WEATHER_CONDITION,
            weatherKeyword = "TEMP",
            comparisonOperator = ComparisonOperator.BELOW,
            thresholdValue = 2.5f,
            restrictToTimeWindow = true,
            windowStartHour = 6,
            windowStartMinute = 0,
            windowEndHour = 8,
            windowEndMinute = 30,
            daysOfWeek = setOf(1, 2, 3, 4, 5),
            actions = listOf(
                AutomationAction(
                    targetTileId = vehicleTile.id,
                    targetTileTitle = vehicleTile.title,
                    actionType = AutomationActionType.TURN_ON
                )
            )
        )
    }

    fun evaluateAllRules(
        context: Context,
        rules: MutableList<AutomationRule>,
        tiles: MutableList<SmartTile>,
        bridge: BridgeSettings,
        audioManager: AudioManager,
        scope: CoroutineScope,
        onSpeak: (String) -> Unit
    ) {
        val rulesSnapshot = runCatching { rules.toList() }.getOrDefault(emptyList())
        if (rulesSnapshot.isEmpty()) return
        // Stage 4 Multi-Tablet Mesh Consensus: Only the elected primary authority executes automatic rules
        if (!JarvisLiveBridge.isPrimaryAutomationAuthorityFlow.value) return
        val tilesSnapshot = runCatching { tiles.toList() }.getOrDefault(emptyList())
        val cal = Calendar.getInstance()
        val nowMs = System.currentTimeMillis()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val year = cal.get(Calendar.YEAR)
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val weather = JarvisLiveBridge.weatherFlow.value

        val triggeredRuleIds = mutableSetOf<String>()

        for (rule in rulesSnapshot) {
            if (!rule.isEnabled) {
                previousConditionSatisfiedByRule.remove(rule.id)
                continue
            }
            if (!isWithinAllowedDayAndWindow(cal, rule) || !areSecondaryConditionsSatisfied(rule, tilesSnapshot, weather, bridge)) {
                previousConditionSatisfiedByRule[rule.id] = false
                continue
            }

            when (rule.triggerType) {
                AutomationTriggerType.TIME_SCHEDULE -> {
                    if (hour == rule.scheduleHour && minute == rule.scheduleMinute) {
                        val minuteKey = "$year-$dayOfYear-$hour:$minute"
                        if (lastFiredScheduleKeyByRule[rule.id] != minuteKey) {
                            lastFiredScheduleKeyByRule[rule.id] = minuteKey
                            executeRuleActions(
                                rule = rule,
                                tiles = tiles,
                                bridge = bridge,
                                audioManager = audioManager,
                                scope = scope,
                                context = context,
                                onSpeak = onSpeak,
                                triggerSource = "Time Schedule (%02d:%02d)".format(hour, minute)
                            )
                            triggeredRuleIds.add(rule.id)
                        }
                    }
                }

                AutomationTriggerType.SUNRISE_SUNSET -> {
                    val isCurrentlySunsetNight = !weather.isDay
                    val conditionMet = if (rule.triggerOnSunset) isCurrentlySunsetNight else weather.isDay
                    val prev = previousConditionSatisfiedByRule.put(rule.id, conditionMet)
                    if (prev != null && !prev && conditionMet && (nowMs - rule.lastTriggeredEpochMs) > 60_000L) {
                        executeRuleActions(
                            rule, tiles, bridge, audioManager, scope, context, onSpeak,
                            triggerSource = if (rule.triggerOnSunset) "Sunset Transition" else "Sunrise Transition"
                        )
                        triggeredRuleIds.add(rule.id)
                    }
                }

                AutomationTriggerType.DEVICE_STATE_CHANGE -> {
                    val triggerTile = resolveTileForRule(tilesSnapshot, rule.triggerTileId, rule.triggerTileTitle)
                    if (triggerTile != null) {
                        val subSocket = extractSubSocketTarget(rule.triggerTileId)
                        val isSocket2On = triggerTile.isSocket2Active
                        val conditionMet = when {
                            subSocket == "SOCKET_1" && rule.triggerDeviceState == DeviceTriggerTargetState.TURNED_ON -> triggerTile.isActive
                            subSocket == "SOCKET_1" && rule.triggerDeviceState == DeviceTriggerTargetState.TURNED_OFF -> !triggerTile.isActive
                            subSocket == "SOCKET_2" && rule.triggerDeviceState == DeviceTriggerTargetState.TURNED_ON -> isSocket2On
                            subSocket == "SOCKET_2" && rule.triggerDeviceState == DeviceTriggerTargetState.TURNED_OFF -> !isSocket2On
                            rule.triggerDeviceState == DeviceTriggerTargetState.TURNED_ON -> triggerTile.isActive
                            rule.triggerDeviceState == DeviceTriggerTargetState.TURNED_OFF -> !triggerTile.isActive
                            rule.triggerDeviceState == DeviceTriggerTargetState.SOCKET_1_ON -> triggerTile.isActive
                            rule.triggerDeviceState == DeviceTriggerTargetState.SOCKET_1_OFF -> !triggerTile.isActive
                            rule.triggerDeviceState == DeviceTriggerTargetState.SOCKET_2_ON -> isSocket2On
                            rule.triggerDeviceState == DeviceTriggerTargetState.SOCKET_2_OFF -> !isSocket2On
                            else -> false
                        }
                        val prev = previousConditionSatisfiedByRule.put(rule.id, conditionMet)
                        if (prev != null && !prev && conditionMet && (nowMs - rule.lastTriggeredEpochMs) > 15_000L) {
                            executeRuleActions(
                                rule, tiles, bridge, audioManager, scope, context, onSpeak,
                                triggerSource = "Device State (${rule.triggerTileTitle})"
                            )
                            triggeredRuleIds.add(rule.id)
                        }
                    }
                }

                AutomationTriggerType.TEMPERATURE_THRESHOLD -> {
                    val triggerTile = resolveTileForRule(tilesSnapshot, rule.triggerTileId, rule.triggerTileTitle)
                    // F-03: Only evaluate temperature threshold rules when the sensor/thermostat is online with real telemetry
                    val trustedLiveTemp = if (triggerTile != null && triggerTile.isOnline) {
                        triggerTile.hardwareAmbientTempCelsiusOrNull()
                            ?: triggerTile.temperature.takeIf { it > 0f && it != 20.5f }
                    } else null
                    if (triggerTile != null && trustedLiveTemp != null) {
                        val conditionMet = compareThreshold(
                            liveVal = trustedLiveTemp,
                            op = rule.comparisonOperator,
                            target = rule.thresholdValue
                        )
                        val prev = previousConditionSatisfiedByRule.put(rule.id, conditionMet)
                        if ((prev == false || (prev == null && rule.lastTriggeredEpochMs == 0L)) &&
                            conditionMet && (nowMs - rule.lastTriggeredEpochMs) > 60_000L
                        ) {
                            executeRuleActions(
                                rule, tiles, bridge, audioManager, scope, context, onSpeak,
                                triggerSource = "Temperature (${"%.1f".format(triggerTile.temperature)}\u00B0C)"
                            )
                            triggeredRuleIds.add(rule.id)
                        }
                    }
                }

                AutomationTriggerType.POWER_WATTS_THRESHOLD -> {
                    val triggerTile = resolveTileForRule(tilesSnapshot, rule.triggerTileId, rule.triggerTileTitle)
                    if (triggerTile != null) {
                        val conditionMet = compareThreshold(
                            liveVal = triggerTile.powerWatts,
                            op = rule.comparisonOperator,
                            target = rule.thresholdValue
                        )
                        val prev = previousConditionSatisfiedByRule.put(rule.id, conditionMet)
                        if ((prev == false || (prev == null && rule.lastTriggeredEpochMs == 0L)) &&
                            conditionMet && (nowMs - rule.lastTriggeredEpochMs) > 30_000L
                        ) {
                            executeRuleActions(
                                rule, tiles, bridge, audioManager, scope, context, onSpeak,
                                triggerSource = "Power Draw (${"%.1f".format(triggerTile.powerWatts)}W)"
                            )
                            triggeredRuleIds.add(rule.id)
                        }
                    }
                }

                AutomationTriggerType.DOORBELL_RING -> {
                    val ringEvt = JarvisLiveBridge.doorbellRingFlow.value
                    val conditionMet = ringEvt != null
                    val prev = previousConditionSatisfiedByRule.put(rule.id, conditionMet)
                    if (prev != true && conditionMet && (nowMs - rule.lastTriggeredEpochMs) > 15_000L) {
                        executeRuleActions(
                            rule, tiles, bridge, audioManager, scope, context, onSpeak,
                            triggerSource = "Doorbell Ring"
                        )
                        triggeredRuleIds.add(rule.id)
                    }
                }

                AutomationTriggerType.WEATHER_CONDITION -> {
                    val conditionNow = if (rule.weatherKeyword.equals("TEMP", ignoreCase = true)) {
                        compareThreshold(weather.temperatureCelsius.toFloat(), rule.comparisonOperator, rule.thresholdValue)
                    } else {
                        weather.conditionText.contains(rule.weatherKeyword.trim(), ignoreCase = true)
                    }
                    val wasSatisfied = previousConditionSatisfiedByRule.put(rule.id, conditionNow) ?: false
                    if (conditionNow && !wasSatisfied && (nowMs - rule.lastTriggeredEpochMs) > 300_000L) {
                        executeRuleActions(
                            rule = rule,
                            tiles = tiles,
                            bridge = bridge,
                            audioManager = audioManager,
                            scope = scope,
                            context = context,
                            onSpeak = onSpeak,
                            triggerSource = "Weather Trigger (${rule.weatherKeyword})"
                        )
                        triggeredRuleIds.add(rule.id)
                    }
                }

                AutomationTriggerType.SECURITY_MODE_IS,
                AutomationTriggerType.TARIFF_WINDOW_IS,
                AutomationTriggerType.DAYLIGHT_IS,
                AutomationTriggerType.CAPABILITY_VALUE_MATCH -> {
                    val primaryContextMet = when (rule.triggerType) {
                        AutomationTriggerType.SECURITY_MODE_IS -> {
                            val expectedMode = runCatching {
                                SecurityCenterMode.valueOf(rule.weatherKeyword.trim().uppercase(Locale.US))
                            }.getOrDefault(SecurityCenterMode.AWAY)
                            val activeMode = JarvisLiveBridge.securityCenterModeFlow.value
                            activeMode == expectedMode || bridge.securityCenterMode == expectedMode
                        }
                        AutomationTriggerType.TARIFF_WINDOW_IS -> {
                            val wantsPeakDay = rule.weatherKeyword.equals("PEAK_DAY", ignoreCase = true)
                            if (wantsPeakDay) !isOffPeakNightTariffNow(cal, bridge) else isOffPeakNightTariffNow(cal, bridge)
                        }
                        AutomationTriggerType.DAYLIGHT_IS -> {
                            if (rule.triggerOnSunset) !weather.isDay else weather.isDay
                        }
                        AutomationTriggerType.CAPABILITY_VALUE_MATCH -> {
                            val syntheticCond = AutomationSecondaryCondition(
                                conditionType = AutomationTriggerType.CAPABILITY_VALUE_MATCH,
                                targetTileId = rule.triggerTileId,
                                targetTileTitle = rule.triggerTileTitle,
                                capabilityKey = rule.weatherKeyword.ifBlank { "cur_power" },
                                comparisonOperator = rule.comparisonOperator,
                                thresholdValue = rule.thresholdValue
                            )
                            evaluateSingleSecondaryCondition(syntheticCond, tilesSnapshot, weather, bridge)
                        }
                        else -> true
                    }
                    val conditionNow = primaryContextMet && areSecondaryConditionsSatisfied(rule, tilesSnapshot, weather, bridge)
                    val wasSatisfied = previousConditionSatisfiedByRule.put(rule.id, conditionNow) ?: false
                    if (conditionNow && !wasSatisfied && (nowMs - rule.lastTriggeredEpochMs) > 60_000L) {
                        executeRuleActions(
                            rule = rule,
                            tiles = tiles,
                            bridge = bridge,
                            audioManager = audioManager,
                            scope = scope,
                            context = context,
                            onSpeak = onSpeak,
                            triggerSource = "Context Trigger (${rule.triggerType.label})"
                        )
                        triggeredRuleIds.add(rule.id)
                    }
                }
            }
        }

        evaluateMultiHopKnowledgeGraphInferences(
            context = context,
            rulesSnapshot = rulesSnapshot,
            tilesSnapshot = tilesSnapshot,
            bridge = bridge
        )

        if (triggeredRuleIds.isNotEmpty()) {
            scope.launch(Dispatchers.Main) {
                var changed = false
                for (idx in rules.indices) {
                    val r = rules[idx]
                    if (r.id in triggeredRuleIds) {
                        rules[idx] = r.copy(lastTriggeredEpochMs = nowMs)
                        changed = true
                    }
                }
                if (changed) {
                    DeviceRepository.saveAutomations(context, rules)
                }
            }
        }
    }

    /**
     * Phase 7.2 Multi-Hop Knowledge Graph Contextual Inference Engine:
     * Evaluates multi-hop graph paths (`Person <-> Place <-> Device <-> TariffSlot`) from the
     * 9-primitive Knowledge Graph and surfaces actionable 1-tap `SuggestedAutomationDraft` cards
     * without overwriting user-dismissed suggestions.
     */
    private fun evaluateMultiHopKnowledgeGraphInferences(
        context: Context,
        rulesSnapshot: List<AutomationRule>,
        tilesSnapshot: List<SmartTile>,
        bridge: BridgeSettings
    ) {
        if (tilesSnapshot.isEmpty()) return
        val graph = JarvisLiveBridge.digitalHomeGraphFlow.value
        val activeInferences = graph.activeInferences
        if (activeInferences.isEmpty()) return

        val dismissedKeys = DeviceRepository.getDismissedHabitSuggestionKeys(context)
        val existingSuggestions = JarvisLiveBridge.suggestedAutomationsFlow.value.toMutableList()
        var changed = false

        for (inf in activeInferences) {
            if (inf.inferenceId in dismissedKeys) continue
            if (existingSuggestions.any { it.id == inf.inferenceId }) continue
            val targetTile = tilesSnapshot.firstOrNull { it.id == inf.recommendedActionTileId } ?: continue
            val alreadyCovered = rulesSnapshot.any { r ->
                r.actions.any { a -> a.targetTileId == targetTile.id && a.actionType == inf.recommendedActionType }
            }
            if (alreadyCovered) continue

            val cal = Calendar.getInstance()
            val action = AutomationAction(
                targetTileId = targetTile.id,
                targetTileTitle = targetTile.title,
                actionType = inf.recommendedActionType,
                numericValue = inf.recommendedNumericValue
            )
            val draftRule = AutomationRule(
                id = inf.inferenceId,
                name = "Context Graph: ${action.toSummaryText()}",
                isEnabled = true,
                triggerType = if (inf.inferenceId.contains("agile")) {
                    AutomationTriggerType.TARIFF_WINDOW_IS
                } else {
                    AutomationTriggerType.TIME_SCHEDULE
                },
                scheduleHour = cal.get(Calendar.HOUR_OF_DAY),
                scheduleMinute = (cal.get(Calendar.MINUTE) / 5) * 5,
                actions = listOf(action)
            )
            existingSuggestions.add(
                0,
                SuggestedAutomationDraft(
                    id = inf.inferenceId,
                    confidenceOccurrences = (inf.confidence * 10f).roundToInt().coerceAtLeast(3),
                    reasonExplanation = "Knowledge Graph Inference (${inf.pathNodes.joinToString(" \u2192 ")}): ${inf.summary}.",
                    draftRule = draftRule
                )
            )
            changed = true
        }

        if (changed) {
            JarvisLiveBridge.suggestedAutomationsFlow.value = existingSuggestions.take(5)
        }
    }

    /**
     * Executes all "THEN THAT" steps sequentially, persists any `WAIT_DELAY` steps for crash-safe
     * resumption across restarts, verifies whether each hardware/cloud call succeeded, and writes
     * a plain-English entry to the Automation Execution & Failure Log.
     */
    fun executeRuleActions(
        rule: AutomationRule,
        tiles: MutableList<SmartTile>,
        bridge: BridgeSettings,
        audioManager: AudioManager,
        scope: CoroutineScope,
        context: Context,
        onSpeak: (String) -> Unit,
        triggerSource: String = "Automatic Trigger"
    ) {
        scope.launch(Dispatchers.Main) {
            val (matchedBranchLabel, activeActions) = resolveMatchingBranchAndActions(
                rule = rule,
                tiles = tiles,
                weather = JarvisLiveBridge.weatherFlow.value,
                bridge = bridge
            )
            if (activeActions.isEmpty()) {
                val emptyMsg = if (rule.branches.isNotEmpty()) {
                    "Skipped: No conditional branch matched ($matchedBranchLabel) and no 'Otherwise' fallback actions are configured."
                } else {
                    "Failed: This automation has no actions configured. Edit the rule and add at least one action."
                }
                DeviceRepository.appendAutomationLog(
                    context = context,
                    entry = AutomationLogEntry(
                        ruleId = rule.id,
                        ruleName = rule.name,
                        triggerSource = triggerSource,
                        status = if (rule.branches.isNotEmpty()) AutomationRunStatus.SUCCESS else AutomationRunStatus.FAILED,
                        userFriendlySummary = emptyMsg,
                        matchedBranchLabel = matchedBranchLabel,
                        stepLogs = emptyList()
                    )
                )
                return@launch
            }

            val isManualOrVoiceRun = triggerSource.startsWith("Voice", ignoreCase = true) ||
                    triggerSource.startsWith("Manual", ignoreCase = true) ||
                    triggerSource.startsWith("Dashboard Macro", ignoreCase = true)
            val ruleIntentTier = when {
                isManualOrVoiceRun -> IntentAuthorityTier.MANUAL_USER
                rule.triggerType == AutomationTriggerType.TIME_SCHEDULE ||
                        rule.triggerType == AutomationTriggerType.SUNRISE_SUNSET -> IntentAuthorityTier.SCHEDULED_TASK
                else -> IntentAuthorityTier.AUTOMATION
            }
            val ruleActorName = if (isManualOrVoiceRun) triggerSource else "Rule: ${rule.name}"

            val stepLogs = mutableListOf<AutomationStepLog>()

            for ((index, action) in activeActions.withIndex()) {
                val stepNum = index + 1
                when (action.actionType) {
                    AutomationActionType.WAIT_DELAY -> {
                        val rawAmount = action.numericValue.coerceAtLeast(1f)
                        val delayMs = when (action.textValue.uppercase(Locale.getDefault())) {
                            "SECONDS" -> (rawAmount * 1000L).toLong()
                            "HOURS" -> (rawAmount * 3600_000L).toLong()
                            else -> (rawAmount * 60_000L).toLong()
                        }
                        val continuationId = "${rule.id}_step_${index + 1}"
                        if (index + 1 < activeActions.size) {
                            savePendingWaitContinuation(
                                context = context,
                                continuationId = continuationId,
                                ruleId = rule.id,
                                resumeFromActionIndex = index + 1,
                                executeAtEpochMs = System.currentTimeMillis() + delayMs,
                                triggerSource = triggerSource
                            )
                        }
                        Log.d(TAG, "Rule '${rule.name}': Waiting${delayMs / 1000}s before next step...")
                        delay(delayMs)
                        removePendingWaitContinuation(context, continuationId)
                        stepLogs.add(
                            AutomationStepLog(
                                stepNumber = stepNum,
                                stepSummary = action.toSummaryText(),
                                succeeded = true,
                                reasonOrDetail = "Completed wait timer (${delayMs / 1000}s)."
                            )
                        )
                    }
                    AutomationActionType.EXECUTE_SCENE -> {
                        val controllable = tiles.filter {
                            it.isConfiguredAndLinked &&
                                    it.type != TileType.CLOCK_WEATHER &&
                                    it.type != TileType.GROUP_CARD &&
                                    it.type != TileType.PHOTO_SLIDESHOW &&
                                    it.type != TileType.MACRO_TILE
                        }
                        val offlineTargets = controllable.filter { !it.isOnline }
                        executeHomeRoutine(
                            sceneName = action.textValue,
                            tiles = tiles,
                            bridge = bridge,
                            audioManager = audioManager,
                            scope = scope,
                            context = context,
                            onSpeak = onSpeak,
                            intentTier = ruleIntentTier,
                            actorName = ruleActorName
                        )
                        val sceneOk = controllable.isNotEmpty() && offlineTargets.isEmpty()
                        val sceneDetail = when {
                            controllable.isEmpty() ->
                                "Failed: No linked devices available for scene '${action.textValue}'."
                            offlineTargets.isNotEmpty() ->
                                "Scene '${action.textValue}' ran with ${offlineTargets.size} offline device(s):${offlineTargets.joinToString { it.title }}."
                            else ->
                                "Executed routine scene '${action.textValue}' across${controllable.size} online device(s)."
                        }
                        stepLogs.add(
                            AutomationStepLog(
                                stepNumber = stepNum,
                                stepSummary = action.toSummaryText(),
                                succeeded = sceneOk,
                                reasonOrDetail = sceneDetail
                            )
                        )
                    }
                    AutomationActionType.SPEAK_ANNOUNCEMENT -> {
                        if (!bridge.enableVoiceAnnouncements) {
                            stepLogs.add(
                                AutomationStepLog(
                                    stepNumber = stepNum,
                                    stepSummary = action.toSummaryText(),
                                    succeeded = false,
                                    reasonOrDetail = "Failed: 'Spoken Voice Replies (TTS)' is turned OFF in Application Settings."
                                )
                            )
                        } else if (action.textValue.isBlank()) {
                            stepLogs.add(
                                AutomationStepLog(
                                    stepNumber = stepNum,
                                    stepSummary = action.toSummaryText(),
                                    succeeded = false,
                                    reasonOrDetail = "Failed: Spoken announcement text is empty."
                                )
                            )
                        } else {
                            onSpeak(action.textValue)
                            stepLogs.add(
                                AutomationStepLog(
                                    stepNumber = stepNum,
                                    stepSummary = action.toSummaryText(),
                                    succeeded = true,
                                    reasonOrDetail = "Spoke announcement on tablet speaker."
                                )
                            )
                        }
                    }
                    AutomationActionType.RING_NEST_MINI_CHIME -> {
                        val baseId = cleanBaseTileId(action.targetTileId)
                        val targetTile = tiles.firstOrNull {
                            (baseId.isNotBlank() && it.id == baseId) ||
                                    (action.targetTileTitle.isNotBlank() && it.title.equals(action.targetTileTitle, ignoreCase = true))
                        }
                        val discoveredCast = JarvisLiveBridge.discoveredCastDevicesFlow.value
                        if (targetTile == null && discoveredCast.isEmpty() && bridge.doorbellChimeTargetIps.isEmpty()) {
                            stepLogs.add(
                                AutomationStepLog(
                                    stepNumber = stepNum,
                                    stepSummary = action.toSummaryText(),
                                    succeeded = false,
                                    reasonOrDetail = "Failed: No Google Nest Mini speakers were found on Wi-Fi. Scan for Nest Minis in Control Center -> Add & Link Devices."
                                )
                            )
                        } else {
                            val targetTiles = if (targetTile != null) listOf(targetTile) else tiles.toList()
                            GoogleCastLanClient.playDoorbellChimeOnNestMinis(
                                scope = scope,
                                bridge = bridge,
                                tiles = targetTiles,
                                customAnnouncementText = action.textValue.ifBlank { bridge.doorbellChimeMessage }
                            )
                            stepLogs.add(
                                AutomationStepLog(
                                    stepNumber = stepNum,
                                    stepSummary = action.toSummaryText(),
                                    succeeded = true,
                                    reasonOrDetail = "Sent chime & announcement to Google Nest Mini speaker(s)."
                                )
                            )
                        }
                    }
                    else -> {
                        val targetIndices = resolveTargetIndicesForAction(tiles, action)
                        if (targetIndices.isEmpty()) {
                            stepLogs.add(
                                AutomationStepLog(
                                    stepNumber = stepNum,
                                    stepSummary = action.toSummaryText(),
                                    succeeded = false,
                                    reasonOrDetail = "Failed: No linked device matched '${action.effectiveTargetLabel}'.",
                                    resolvedTargetCount = 0
                                )
                            )
                            continue
                        }
                        for (idx in targetIndices) {
                            val current = tiles[idx]
                            val subSocketTarget = extractSubSocketTarget(action.targetTileId, current)
                            // F-04 & F-21: Enforce 30-minute manual hold window in DeviceRepository before mutating tile
                            val isLockedByManualHold = !isManualOrVoiceRun && DeviceRepository.isTileInManualHoldWindow(current.id)
                            val (canExecuteFromTile, rawHoldReason) = DeviceRepository.canExecuteOutboundIntent(
                                tile = current,
                                intentTier = ruleIntentTier
                            )
                            val canExecuteStep = !isLockedByManualHold && canExecuteFromTile
                            val holdBlockedReason = if (isLockedByManualHold) {
                                "Protected by active 30-minute manual user hold on '${current.title}'."
                            } else {
                                rawHoldReason
                            }
                            if (!canExecuteStep) {
                                JarvisLiveBridge.appendDeviceEvent(
                                    DeviceEvent(
                                        deviceId = current.id,
                                        deviceTitle = current.title,
                                        roomName = current.roomOrSubtitle,
                                        eventType = DeviceEventType.MANUAL_HOLD_ACTIVATED,
                                        source = ruleIntentTier.label,
                                        actorName = ruleActorName,
                                        stateVersion = current.stateVersion,
                                        newStateSummary = holdBlockedReason
                                    )
                                )
                                stepLogs.add(
                                    AutomationStepLog(
                                        stepNumber = stepNum,
                                        stepSummary = action.toSummaryText(),
                                        succeeded = true,
                                        reasonOrDetail = "Skipped: $holdBlockedReason"
                                    )
                                )
                                continue
                            }
                            val currentSocket2On = current.isSocket2Active
                            val effectiveActionType = when {
                                current.isMultiGangSwitch && subSocketTarget == "SOCKET_1" -> when (action.actionType) {
                                AutomationActionType.TURN_ON -> AutomationActionType.SOCKET_1_ON
                                AutomationActionType.TURN_OFF -> AutomationActionType.SOCKET_1_OFF
                                AutomationActionType.TOGGLE -> if (!current.isActive) AutomationActionType.SOCKET_1_ON else AutomationActionType.SOCKET_1_OFF
                                else -> action.actionType
                            }
                            current.isMultiGangSwitch && subSocketTarget == "SOCKET_2" -> when (action.actionType) {
                                AutomationActionType.TURN_ON -> AutomationActionType.SOCKET_2_ON
                                AutomationActionType.TURN_OFF -> AutomationActionType.SOCKET_2_OFF
                                AutomationActionType.TOGGLE -> if (!currentSocket2On) AutomationActionType.SOCKET_2_ON else AutomationActionType.SOCKET_2_OFF
                                else -> action.actionType
                            }
                            else -> action.actionType
                        }
                        val nowActuationMs = System.currentTimeMillis()
                        val baseUpdated = when (effectiveActionType) {
                            AutomationActionType.TURN_ON -> {
                                when {
                                    current.isThermostatTile -> current.copy(
                                        isActive = true,
                                        hvacMode = "HEAT",
                                        hvacStatus = if (current.targetTemperature > current.temperature) "HEATING" else "OFF"
                                    )
                                    current.isVehicleTile -> current.copy(vehicleLocked = true, isActive = true)
                                    current.isMultiGangSwitch -> current.copy(isActive = true, isSocket2Active = true)
                                    else -> current.copy(isActive = true)
                                }
                            }
                            AutomationActionType.TURN_OFF -> {
                                when {
                                    current.isThermostatTile -> current.copy(isActive = false, hvacMode = "OFF", hvacStatus = "OFF")
                                    current.isVehicleTile -> current.copy(vehicleLocked = false, isActive = false)
                                    current.isMultiGangSwitch -> current.copy(isActive = false, isSocket2Active = false)
                                    else -> current.copy(isActive = false)
                                }
                            }
                            AutomationActionType.TOGGLE -> {
                                val nextOn = !current.isActive
                                when {
                                    current.isThermostatTile -> current.copy(isActive = nextOn, hvacMode = if (nextOn) "HEAT" else "OFF")
                                    current.isVehicleTile -> current.copy(vehicleLocked = !current.vehicleLocked, isActive = !current.vehicleLocked)
                                    current.isMultiGangSwitch -> current.copy(isActive = nextOn, isSocket2Active = nextOn)
                                    else -> current.copy(isActive = nextOn)
                                }
                            }
                            AutomationActionType.SOCKET_1_ON -> current.copy(isActive = true)
                            AutomationActionType.SOCKET_1_OFF -> current.copy(isActive = false)
                            AutomationActionType.SOCKET_2_ON -> current.copy(isSocket2Active = true)
                            AutomationActionType.SOCKET_2_OFF -> current.copy(isSocket2Active = false)
                            AutomationActionType.BOTH_SOCKETS_ON -> current.copy(isActive = true, isSocket2Active = true)
                            AutomationActionType.BOTH_SOCKETS_OFF -> current.copy(isActive = false, isSocket2Active = false)
                            AutomationActionType.SET_TEMPERATURE -> {
                                val tempCap = current.capabilities.firstOrNull {
                                    it.rendererType == CapabilityRendererType.TEMPERATURE_DIAL
                                }
                                val minBound = tempCap?.minValue ?: 5f
                                val maxBound = tempCap?.maxValue ?: if (current.deviceSubtype == DeviceSubtype.THERMOSTAT) 32f else 37f
                                val targetC = action.numericValue.coerceIn(minBound, maxBound)
                                current.copy(
                                    isActive = true,
                                    hvacMode = "HEAT",
                                    targetTemperature = targetC,
                                    hvacStatus = if (targetC > current.temperature) "HEATING" else "OFF"
                                )
                            }
                            AutomationActionType.SET_HVAC_MODE -> {
                                val mode = action.textValue.uppercase(Locale.US)
                                current.copy(
                                    hvacMode = mode,
                                    isActive = mode == "HEAT",
                                    hvacStatus = if (mode == "HEAT" && current.targetTemperature > current.temperature) "HEATING" else "OFF"
                                )
                            }
                            AutomationActionType.SET_LEVEL_OR_VOLUME -> {
                                if (current.isTowerFanTile) {
                                    val rawVal = action.numericValue
                                    val spd = if (rawVal in 1f..6f) rawVal.roundToInt() else ((rawVal.coerceIn(0f, 100f) / 100f) * 6f).roundToInt().coerceIn(1, 6)
                                    current.copy(
                                        isActive = rawVal > 0f,
                                        fanSpeedLevel = spd,
                                        volume = (spd / 6f) * 100f
                                    )
                                } else {
                                    val pct = action.numericValue.coerceIn(0f, 100f)
                                    current.copy(volume = pct, isActive = pct > 0f)
                                }
                            }
                            AutomationActionType.OPEN_LIVE_CAMERA -> current.copy(isLiveFeedActive = true)
                            AutomationActionType.CLOSE_LIVE_CAMERA -> current.copy(isLiveFeedActive = false)
                            else -> current
                        }.copy(
                            transactionState = CommandTransactionState.PENDING_ACK,
                            lastUserActuationEpochMs = nowActuationMs
                        )
                            val updated = DeviceRepository.stampOutboundIntentAuthority(
                                tile = baseUpdated,
                                intentTier = ruleIntentTier,
                                actorName = ruleActorName,
                                nowMs = nowActuationMs
                            )
                            tiles[idx] = updated
                            DeviceRepository.saveTiles(context, tiles)
                            val singleSocketNumber: Int? = when (effectiveActionType) {
                                AutomationActionType.SOCKET_1_ON, AutomationActionType.SOCKET_1_OFF -> 1
                                AutomationActionType.SOCKET_2_ON, AutomationActionType.SOCKET_2_OFF -> 2
                                else -> null
                            }
                            val updatedProviderKey = resolveEffectiveProviderKey(updated)
                            val (ok, detailMsg) = if (singleSocketNumber != null && !updatedProviderKey.contains("TUYA")) {
                            val turnOnSocket = if (singleSocketNumber == 1) updated.isActive else updated.isSocket2Active
                            val sentOk = dispatchSingleSocketCommandForTile(
                                context = context,
                                tile = updated,
                                bridge = bridge,
                                socketNumber = singleSocketNumber,
                                turnOn = turnOnSocket
                            )
                            val socketDisplay = if (singleSocketNumber == 1) {
                                updated.socket1Name.ifBlank { "Socket 1" }
                            } else {
                                updated.socket2Name.ifBlank { "Socket 2" }
                            }
                            if (sentOk) {
                                true to "Turned $socketDisplay ${if (turnOnSocket) "ON" else "OFF"} on '${updated.title}'."
                            } else {
                                false to "'${updated.title}' ($socketDisplay) did not respond on Wi-Fi."
                            }
                        } else {
                            dispatchDeviceUpdateWithDiagnostic(
                                context = context,
                                updated = updated,
                                bridge = bridge,
                                audioManager = audioManager,
                                scope = scope,
                                singleSocketNumber = singleSocketNumber,
                                pinAuthorized = true,
                                intentTier = ruleIntentTier,
                                actorName = ruleActorName
                            )
                        }
                            if (idx in tiles.indices && tiles[idx].id == updated.id) {
                                tiles[idx] = if (ok) {
                                    val nextVer = tiles[idx].stateVersion + 1L
                                    val verifiedTier = resolveVerifiedTransportAuthorityTier(updated, null)
                                    JarvisLiveBridge.appendDeviceEvent(
                                        DeviceEvent(
                                            deviceId = updated.id,
                                            deviceTitle = updated.title,
                                            roomName = updated.roomOrSubtitle,
                                            eventType = DeviceEventType.INTENT_CONFIRMED,
                                            source = updated.activeProviderId.ifBlank { updated.type.brandName },
                                            actorName = ruleActorName,
                                            stateVersion = nextVer,
                                            authorityTierLabel = verifiedTier.label,
                                            newStateSummary = detailMsg
                                        )
                                    )
                                    tiles[idx].copy(
                                        isOnline = true,
                                        lifecycleState = DeviceLifecycleState.ONLINE,
                                        transactionState = CommandTransactionState.CONFIRMED,
                                        stateVersion = nextVer,
                                        stateOriginEpochMs = System.currentTimeMillis(),
                                        stateAuthorityTier = verifiedTier,
                                        lastConfirmedEpochMs = System.currentTimeMillis()
                                    )
                                } else {
                                val hasQueued = JarvisLiveBridge.hasQueuedIntentsForDevice(current.id)
                                current.copy(
                                    lifecycleState = DeviceLifecycleState.QUARANTINED_ERROR,
                                    transactionState = if (hasQueued) {
                                        CommandTransactionState.SHADOW_QUEUED
                                    } else {
                                        CommandTransactionState.FAILED_ROLLED_BACK
                                    }
                                )
                            }
                            DeviceRepository.saveTiles(context, tiles)
                        }
                            stepLogs.add(
                                AutomationStepLog(
                                    stepNumber = stepNum,
                                    stepSummary = if (targetIndices.size > 1) "${action.toSummaryText()} (${current.title})" else action.toSummaryText(),
                                    succeeded = ok,
                                    reasonOrDetail = detailMsg,
                                    resolvedTargetCount = targetIndices.size
                                )
                            )
                        }
                    }
                }
            }

            val failedSteps = stepLogs.filter { !it.succeeded }
            val overallStatus = when {
                failedSteps.isEmpty() -> AutomationRunStatus.SUCCESS
                failedSteps.size == stepLogs.size -> AutomationRunStatus.FAILED
                else -> AutomationRunStatus.PARTIAL_FAILURE
            }

            val branchPrefix = if (rule.branches.isNotEmpty()) "[$matchedBranchLabel] " else ""
            val summaryText = when (overallStatus) {
                AutomationRunStatus.SUCCESS ->
                    "${branchPrefix}All ${stepLogs.size} step(s) completed successfully."
                AutomationRunStatus.PARTIAL_FAILURE ->
                    "${branchPrefix}${failedSteps.size} of ${stepLogs.size} step(s) failed: ${failedSteps.first().reasonOrDetail}"
                AutomationRunStatus.FAILED ->
                    "$branchPrefix${failedSteps.firstOrNull()?.reasonOrDetail ?: "Automation failed to execute."}"
            }

            DeviceRepository.appendAutomationLog(
                context = context,
                entry = AutomationLogEntry(
                    ruleId = rule.id,
                    ruleName = rule.name,
                    triggerSource = triggerSource,
                    status = overallStatus,
                    userFriendlySummary = summaryText,
                    matchedBranchLabel = matchedBranchLabel,
                    stepLogs = stepLogs
                )
            )
        }
    }

    private fun parseSpokenScheduleTime(cmd: String): Pair<Int, Int>? {
        if (cmd.contains("midnight")) return 0 to 0
        if (cmd.contains("noon") || cmd.contains("midday")) return 12 to 0

        val timeRegex = Regex("""(?:at|for)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?""")
        val match = timeRegex.find(cmd) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: 0
        val meridiem = match.groupValues[3].replace(".", "").lowercase(Locale.getDefault())

        when (meridiem) {
            "pm" -> if (hour in 1..11) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        if (hour !in 0..23) return null
        return hour to minute
    }

    /**
     * Goal-Driven Automation AI Synthesizer:
     * Compiles natural-language household goals (e.g. "I leave for work at 8am. Make my home energy efficient",
     * "Charge my EV only on the cheap night rate", "Bedtime routine at 11pm") into multi-action,
     * capability-scoped `AutomationRule` instances with `AutomationBranch` decision logic.
     */
    fun synthesizeAutomationFromGoal(
        rawGoalText: String,
        context: Context,
        rules: MutableList<AutomationRule>,
        tiles: List<SmartTile>,
        @Suppress("UNUSED_PARAMETER") bridge: BridgeSettings,
        onSpeak: (String) -> Unit
    ): Boolean {
        val cmd = rawGoalText.trim().lowercase(Locale.getDefault())
        if (cmd.length < 8) return false

        val parsedTime = parseSpokenScheduleTime(cmd)
        val isLeaveWorkOrEnergyGoal = cmd.contains("leave for work") ||
                cmd.contains("leave home") ||
                cmd.contains("energy efficient") ||
                cmd.contains("save energy") ||
                cmd.contains("eco mode") ||
                cmd.contains("bedtime") ||
                cmd.contains("goodnight") ||
                cmd.contains("sleep routine")
        val isEvNightTariffGoal = (cmd.contains("charge") || cmd.contains("ev") || cmd.contains("car") || cmd.contains("washer") || cmd.contains("appliance")) &&
                (cmd.contains("night") || cmd.contains("off-peak") || cmd.contains("cheap") || cmd.contains("tariff"))
        val isMultiBranchDoorbellGoal = (cmd.contains("doorbell") && (cmd.contains("away") || cmd.contains("night"))) ||
                cmd.contains("security routine")
        if (!isLeaveWorkOrEnergyGoal && !isEvNightTariffGoal && !isMultiBranchDoorbellGoal) {
            return false
        }

        val days = if (cmd.contains("work") || cmd.contains("weekday")) {
            setOf(1, 2, 3, 4, 5)
        } else {
            setOf(1, 2, 3, 4, 5, 6, 7)
        }

        val synthesizedRule: AutomationRule = when {
            isLeaveWorkOrEnergyGoal -> {
                val schedH = parsedTime?.first ?: 8
                val schedM = parsedTime?.second ?: 0
                val actions = buildList {
                    add(
                        AutomationAction(
                            targetTileTitle = "All Climate Devices",
                            actionType = AutomationActionType.SET_TEMPERATURE,
                            numericValue = 18.0f,
                            textValue = "ECO",
                            targetSelector = AutomationTargetSelector(
                                scope = AutomationTargetScope.DYNAMIC_SELECTOR,
                                deviceClass = DeviceClass.CLIMATE,
                                requiredCapabilityKey = "target_temperature"
                            )
                        )
                    )
                    add(
                        AutomationAction(
                            targetTileTitle = "All Lighting Devices",
                            actionType = AutomationActionType.TURN_OFF,
                            targetSelector = AutomationTargetSelector(
                                scope = AutomationTargetScope.DYNAMIC_SELECTOR,
                                deviceClass = DeviceClass.LIGHT
                            )
                        )
                    )
                    add(
                        AutomationAction(
                            targetTileTitle = "All Smart Plugs",
                            actionType = AutomationActionType.TURN_OFF,
                            targetSelector = AutomationTargetSelector(
                                scope = AutomationTargetScope.DYNAMIC_SELECTOR,
                                deviceClass = DeviceClass.SWITCH,
                                deviceSubtype = DeviceSubtype.SMART_PLUG
                            )
                        )
                    )
                    if (tiles.any { it.hasLockCapability }) {
                        add(
                            AutomationAction(
                                targetTileTitle = "All Locks & Vehicles",
                                actionType = AutomationActionType.TURN_ON,
                                targetSelector = AutomationTargetSelector(
                                    scope = AutomationTargetScope.DYNAMIC_SELECTOR,
                                    requiredCapabilityKey = "lock"
                                )
                            )
                        )
                    }
                }
                AutomationRule(
                    id = UUID.randomUUID().toString(),
                    name = "AI Energy-Efficient Departure (%02d:%02d)".format(schedH, schedM),
                    isEnabled = true,
                    triggerType = AutomationTriggerType.TIME_SCHEDULE,
                    scheduleHour = schedH,
                    scheduleMinute = schedM,
                    daysOfWeek = days,
                    actions = actions
                )
            }

            isEvNightTariffGoal -> {
                val schedH = parsedTime?.first ?: 0
                val schedM = parsedTime?.second ?: 30
                AutomationRule(
                    id = UUID.randomUUID().toString(),
                    name = "AI Off-Peak Night EV & Appliance Charge (%02d:%02d)".format(schedH, schedM),
                    isEnabled = true,
                    triggerType = AutomationTriggerType.TIME_SCHEDULE,
                    scheduleHour = schedH,
                    scheduleMinute = schedM,
                    daysOfWeek = setOf(1, 2, 3, 4, 5, 6, 7),
                    secondaryConditions = listOf(
                        AutomationSecondaryCondition(
                            conditionType = AutomationTriggerType.TARIFF_WINDOW_IS,
                            requireOffPeakTariff = true
                        )
                    ),
                    actions = listOf(
                        AutomationAction(
                            targetTileTitle = "All Vehicles & EV Chargers",
                            actionType = AutomationActionType.TURN_ON,
                            targetSelector = AutomationTargetSelector(
                                scope = AutomationTargetScope.DYNAMIC_SELECTOR,
                                deviceClass = DeviceClass.VEHICLE
                            )
                        )
                    )
                )
            }

            else -> {
                val speaker = tiles.firstOrNull { it.deviceSubtype in setOf(DeviceSubtype.SPEAKER, DeviceSubtype.CAST_GROUP) }
                val cam = tiles.firstOrNull { it.isCameraTile }
                AutomationRule(
                    id = UUID.randomUUID().toString(),
                    name = "AI Context-Aware Doorbell Response",
                    isEnabled = true,
                    triggerType = AutomationTriggerType.DOORBELL_RING,
                    branches = listOf(
                        AutomationBranch(
                            label = "When Away Mode",
                            conditions = listOf(
                                AutomationSecondaryCondition(
                                    conditionType = AutomationTriggerType.SECURITY_MODE_IS,
                                    targetSecurityMode = SecurityCenterMode.AWAY
                                )
                            ),
                            actions = listOf(
                                AutomationAction(
                                    targetTileId = cam?.id.orEmpty(),
                                    targetTileTitle = cam?.title ?: "All Cameras",
                                    actionType = AutomationActionType.OPEN_LIVE_CAMERA,
                                    targetSelector = AutomationTargetSelector(
                                        scope = AutomationTargetScope.DYNAMIC_SELECTOR,
                                        deviceClass = DeviceClass.CAMERA
                                    )
                                ),
                                AutomationAction(
                                    targetTileTitle = "All Lights",
                                    actionType = AutomationActionType.TURN_ON,
                                    targetSelector = AutomationTargetSelector(
                                        scope = AutomationTargetScope.DYNAMIC_SELECTOR,
                                        deviceClass = DeviceClass.LIGHT
                                    )
                                )
                            )
                        ),
                        AutomationBranch(
                            label = "When Night Mode",
                            conditions = listOf(
                                AutomationSecondaryCondition(
                                    conditionType = AutomationTriggerType.SECURITY_MODE_IS,
                                    targetSecurityMode = SecurityCenterMode.NIGHT
                                )
                            ),
                            actions = listOf(
                                AutomationAction(
                                    targetTileTitle = "Hallway & Porch Lights",
                                    actionType = AutomationActionType.SET_LEVEL_OR_VOLUME,
                                    numericValue = 30f,
                                    targetSelector = AutomationTargetSelector(
                                        scope = AutomationTargetScope.DYNAMIC_SELECTOR,
                                        deviceClass = DeviceClass.LIGHT
                                    )
                                )
                            )
                        )
                    ),
                    otherwiseActions = listOf(
                        AutomationAction(
                            targetTileId = speaker?.id.orEmpty(),
                            targetTileTitle = speaker?.title ?: "Nest Mini",
                            actionType = AutomationActionType.RING_NEST_MINI_CHIME,
                            textValue = "Someone is at the front door"
                        )
                    )
                )
            }
        }

        rules.add(0, synthesizedRule)
        DeviceRepository.saveAutomations(context, rules)
        onSpeak("Created AI routine: ${synthesizedRule.name}")
        return true
    }

    private fun tryCreateRoutineFromVoiceCommand(
        cmd: String,
        context: Context,
        rules: MutableList<AutomationRule>,
        tiles: List<SmartTile>,
        bridge: BridgeSettings,
        onSpeak: (String) -> Unit
    ): Boolean {
        val parsedTime = parseSpokenScheduleTime(cmd)
        val hasSunsetTrigger = cmd.contains("at sunset") || cmd.contains("when it gets dark")
        val hasSunriseTrigger = cmd.contains("at sunrise") || cmd.contains("at daybreak")
        val hasDoorbellTrigger = cmd.contains("when doorbell rings") || cmd.contains("when the doorbell rings") || cmd.contains("on doorbell ring")
        val isBedtimeOutcome = cmd.contains("bedtime routine") || cmd.contains("goodnight routine") || cmd.contains("night routine")
        val isMorningOutcome = cmd.contains("morning routine") || cmd.contains("wake up routine")

        val hasExplicitRoutinePrefix = cmd.startsWith("schedule ") ||
                cmd.startsWith("create a routine") ||
                cmd.startsWith("create routine") ||
                cmd.startsWith("create a schedule") ||
                cmd.startsWith("add a schedule") ||
                cmd.startsWith("add a routine") ||
                cmd.startsWith("set up a routine") ||
                cmd.startsWith("every day ") ||
                cmd.startsWith("every night ") ||
                cmd.startsWith("every morning ") ||
                isBedtimeOutcome ||
                isMorningOutcome

        if (parsedTime == null && !hasSunsetTrigger && !hasSunriseTrigger && !hasDoorbellTrigger && !hasExplicitRoutinePrefix) {
            return false
        }

        val daysOfWeek = when {
            cmd.contains("weekday") -> setOf(1, 2, 3, 4, 5)
            cmd.contains("weekend") -> setOf(6, 7)
            else -> setOf(1, 2, 3, 4, 5, 6, 7)
        }

        val wantsOff = cmd.contains("turn off") || cmd.contains("switch off") || cmd.contains(" off ")
        val numberRegex = Regex("""(\d+(?:\.\d+)?)\s*(?:degree|degrees|\u00B0c|c\b)""")
        val tempMatch = numberRegex.find(cmd)?.groupValues?.getOrNull(1)?.toFloatOrNull()

        val plugTile = tiles.firstOrNull { it.isMultiGangSwitch }
            ?: tiles.firstOrNull { it.isSwitchTile }
        val thermostatTile = tiles.firstOrNull { t ->
            t.isThermostatTile && (cmd.contains(t.title.lowercase(Locale.getDefault())) ||
                    (t.roomOrSubtitle.isNotBlank() && cmd.contains(t.roomOrSubtitle.lowercase(Locale.getDefault()))))
        } ?: tiles.firstOrNull { it.isThermostatTile }
        val speakerTile = tiles.firstOrNull { it.deviceSubtype in setOf(DeviceSubtype.SPEAKER, DeviceSubtype.CAST_GROUP) }
        val cameraTile = tiles.firstOrNull { it.isCameraTile }
        val customSocket1Plug = tiles.firstOrNull { t ->
            t.isMultiGangSwitch && t.socket1Name.isNotBlank() && cmd.contains(t.socket1Name.lowercase(Locale.getDefault()))
        }
        val customSocket2Plug = tiles.firstOrNull { t ->
            t.isMultiGangSwitch && t.socket2Name.isNotBlank() && cmd.contains(t.socket2Name.lowercase(Locale.getDefault()))
        }
        val matchedTile = tiles.firstOrNull { t ->
            t.title.isNotBlank() && cmd.contains(t.title.lowercase(Locale.getDefault()))
        }

        val action: AutomationAction = when {
            cmd.contains("eco night") || cmd.contains("bedtime") || cmd.contains("goodnight") -> {
                AutomationAction(
                    actionType = AutomationActionType.EXECUTE_SCENE,
                    textValue = "ECO NIGHT"
                )
            }
            cmd.contains("boost heat") || isMorningOutcome -> {
                if (thermostatTile != null) {
                    AutomationAction(
                        targetTileId = thermostatTile.id,
                        targetTileTitle = thermostatTile.title,
                        actionType = AutomationActionType.SET_TEMPERATURE,
                        numericValue = tempMatch?.coerceIn(5f, 35f) ?: 21.0f,
                        textValue = "HEAT"
                    )
                } else {
                    AutomationAction(
                        actionType = AutomationActionType.EXECUTE_SCENE,
                        textValue = "BOOST HEAT"
                    )
                }
            }
            hasDoorbellTrigger || cmd.contains("chime") -> {
                AutomationAction(
                    targetTileId = speakerTile?.id ?: "",
                    targetTileTitle = speakerTile?.title ?: "Nest Mini",
                    actionType = AutomationActionType.RING_NEST_MINI_CHIME,
                    textValue = bridge.doorbellChimeMessage
                )
            }
            tempMatch != null && thermostatTile != null -> {
                AutomationAction(
                    targetTileId = thermostatTile.id,
                    targetTileTitle = thermostatTile.title,
                    actionType = AutomationActionType.SET_TEMPERATURE,
                    numericValue = tempMatch.coerceIn(5f, 35f),
                    textValue = "HEAT"
                )
            }
            customSocket1Plug != null -> {
                val s1Name = customSocket1Plug.socket1Name.ifBlank { "Socket 1" }
                AutomationAction(
                    targetTileId = "${customSocket1Plug.id}::SOCKET_1",
                    targetTileTitle = "${customSocket1Plug.title} \u2014 $s1Name",
                    actionType = if (wantsOff) AutomationActionType.SOCKET_1_OFF else AutomationActionType.SOCKET_1_ON
                )
            }
            customSocket2Plug != null -> {
                val s2Name = customSocket2Plug.socket2Name.ifBlank { "Socket 2" }
                AutomationAction(
                    targetTileId = "${customSocket2Plug.id}::SOCKET_2",
                    targetTileTitle = "${customSocket2Plug.title} \u2014 $s2Name",
                    actionType = if (wantsOff) AutomationActionType.SOCKET_2_OFF else AutomationActionType.SOCKET_2_ON
                )
            }
            (cmd.contains("socket 1") || cmd.contains("socket one")) && plugTile != null -> {
                val s1Name = plugTile.socket1Name.ifBlank { "Socket 1" }
                AutomationAction(
                    targetTileId = "${plugTile.id}::SOCKET_1",
                    targetTileTitle = "${plugTile.title} \u2014 $s1Name",
                    actionType = if (wantsOff) AutomationActionType.SOCKET_1_OFF else AutomationActionType.SOCKET_1_ON
                )
            }
            (cmd.contains("socket 2") || cmd.contains("socket two")) && plugTile != null -> {
                val s2Name = plugTile.socket2Name.ifBlank { "Socket 2" }
                AutomationAction(
                    targetTileId = "${plugTile.id}::SOCKET_2",
                    targetTileTitle = "${plugTile.title} \u2014 $s2Name",
                    actionType = if (wantsOff) AutomationActionType.SOCKET_2_OFF else AutomationActionType.SOCKET_2_ON
                )
            }
            matchedTile != null -> {
                val actType = when {
                    matchedTile.isMultiGangSwitch ->
                        if (wantsOff) AutomationActionType.BOTH_SOCKETS_OFF else AutomationActionType.BOTH_SOCKETS_ON
                    matchedTile.isCameraTile ->
                        if (wantsOff) AutomationActionType.CLOSE_LIVE_CAMERA else AutomationActionType.OPEN_LIVE_CAMERA
                    else ->
                        if (wantsOff) AutomationActionType.TURN_OFF else AutomationActionType.TURN_ON
                }
                AutomationAction(
                    targetTileId = matchedTile.id,
                    targetTileTitle = matchedTile.title,
                    actionType = actType
                )
            }
            plugTile != null && (cmd.contains("plug") || cmd.contains("socket") || cmd.contains("smartknight")) -> {
                AutomationAction(
                    targetTileId = if (plugTile.isMultiGangSwitch) "${plugTile.id}::BOTH" else plugTile.id,
                    targetTileTitle = plugTile.title,
                    actionType = if (plugTile.isMultiGangSwitch) {
                        if (wantsOff) AutomationActionType.BOTH_SOCKETS_OFF else AutomationActionType.BOTH_SOCKETS_ON
                    } else {
                        if (wantsOff) AutomationActionType.TURN_OFF else AutomationActionType.TURN_ON
                    }
                )
            }
            thermostatTile != null && (cmd.contains("heat") || cmd.contains("thermostat") || cmd.contains("radiator")) -> {
                AutomationAction(
                    targetTileId = thermostatTile.id,
                    targetTileTitle = thermostatTile.title,
                    actionType = if (wantsOff) AutomationActionType.TURN_OFF else AutomationActionType.SET_TEMPERATURE,
                    numericValue = 21.0f,
                    textValue = if (wantsOff) "OFF" else "HEAT"
                )
            }
            cameraTile != null && cmd.contains("camera") -> {
                AutomationAction(
                    targetTileId = cameraTile.id,
                    targetTileTitle = cameraTile.title,
                    actionType = if (wantsOff) AutomationActionType.CLOSE_LIVE_CAMERA else AutomationActionType.OPEN_LIVE_CAMERA
                )
            }
            else -> return false
        }

        val triggerType = when {
            hasDoorbellTrigger -> AutomationTriggerType.DOORBELL_RING
            hasSunsetTrigger || hasSunriseTrigger -> AutomationTriggerType.SUNRISE_SUNSET
            else -> AutomationTriggerType.TIME_SCHEDULE
        }

        val defaultHour = if (isMorningOutcome || cmd.contains("every morning")) 7 else 23
        val schedHour = parsedTime?.first ?: defaultHour
        val schedMin = parsedTime?.second ?: 0

        val triggerDesc = when (triggerType) {
            AutomationTriggerType.TIME_SCHEDULE -> "at %02d:%02d".format(schedHour, schedMin)
            AutomationTriggerType.SUNRISE_SUNSET -> if (hasSunsetTrigger) "at Sunset" else "at Sunrise"
            else -> "on Doorbell Ring"
        }

        val generatedName = "${action.toSummaryText()} $triggerDesc"

        val newRule = AutomationRule(
            id = UUID.randomUUID().toString(),
            name = generatedName,
            isEnabled = true,
            triggerType = triggerType,
            scheduleHour = schedHour,
            scheduleMinute = schedMin,
            daysOfWeek = daysOfWeek,
            triggerOnSunset = hasSunsetTrigger,
            actions = listOf(action)
        )

        rules.add(0, newRule)
        DeviceRepository.saveAutomations(context, rules)
        onSpeak("Created schedule: $generatedName")
        return true
    }

    // -----------------------------------------------------------------------
    // 3. OPTIONAL LOCAL LAN LLM FALLBACK (Grounded in RAG-Compact Home Graph)
    // -----------------------------------------------------------------------
    private suspend fun queryLocalLlmFallback(
        endpointUrl: String,
        spokenQuery: String,
        graphSnapshot: DigitalHomeGraphSnapshot
    ): String? = withContext(Dispatchers.IO) {
        val cleanUrl = endpointUrl.trim().trimEnd('/')
        if (!cleanUrl.startsWith("http")) return@withContext null
        return@withContext try {
            val compactGraphSummary = DeviceRepository.serializeCompactHomeGraphForLlm(
                snapshot = graphSnapshot,
                queryHint = spokenQuery
            )
            val systemPrompt = "You are Clara, an offline-first smart home voice assistant. Answer concisely in 1-2 sentences using this Digital Home Graph JSON: $compactGraphSummary"
            val targetUrl = if (cleanUrl.endsWith("/chat/completions") || cleanUrl.endsWith("/api/generate")) {
                cleanUrl
            } else {
                "$cleanUrl/v1/chat/completions"
            }
            val payload = JSONObject().apply {
                put("model", "llama3")
                put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", spokenQuery))
                )
                put("stream", false)
            }.toString()

            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4500
                readTimeout = 6000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(payload); it.flush() }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@withContext null
            }
            val respText = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(respText)
            val openAiReply = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()
            val ollamaReply = json.optString("response", "").trim()
            (openAiReply?.takeIf { it.isNotBlank() } ?: ollamaReply.takeIf { it.isNotBlank() })
        } catch (_: Exception) {
            null
        }
    }

    // -----------------------------------------------------------------------
    // 4. SPOKEN VOICE COMMAND & DIGITAL HOME GRAPH RESOLVER (WITH RBAC SHIELD)
    // -----------------------------------------------------------------------
    fun processSpokenVoiceCommand(
        rawSpokenText: String,
        context: Context,
        rules: MutableList<AutomationRule>,
        tiles: MutableList<SmartTile>,
        bridge: BridgeSettings,
        audioManager: AudioManager,
        scope: CoroutineScope,
        onSpeak: (String) -> Unit
    ) {
        val cmd = rawSpokenText.trim().lowercase(Locale.getDefault())
        if (cmd.isBlank()) return

        JarvisLiveBridge.voiceStatusTextFlow.value = "\"$rawSpokenText\""
        val nowMs = System.currentTimeMillis()

        // 1. Multi-Room Speaker Broadcast / Announcement Intents
        val broadcastPrefixes = listOf(
            "play an announcement on my speakers ",
            "play announcement on my speakers ",
            "play an announcement on speakers ",
            "play an announcement ",
            "make an announcement ",
            "announce that ",
            "announce ",
            "broadcast that ",
            "broadcast ",
            "tell everyone that ",
            "tell everyone ",
            "say on speakers ",
            "say on my speakers ",
            "say on nest mini "
        )
        val matchedBroadcastPrefix = broadcastPrefixes.firstOrNull { cmd.startsWith(it) }
        val isGenericAnnouncementIntent = cmd == "play an announcement on my speakers" ||
                cmd == "play an announcement" ||
                cmd == "make an announcement" ||
                cmd == "chime speakers" ||
                cmd == "ring doorbell chime"

        if (matchedBroadcastPrefix != null || isGenericAnnouncementIntent) {
            val messageToAnnounce = if (matchedBroadcastPrefix != null) {
                rawSpokenText.trim().substring(matchedBroadcastPrefix.length)
                    .removePrefix("that ")
                    .removePrefix("saying ")
                    .trim()
                    .ifBlank { bridge.doorbellChimeMessage }
            } else {
                bridge.doorbellChimeMessage
            }
            if (messageToAnnounce.isNotBlank()) {
                GoogleCastLanClient.playDoorbellChimeOnNestMinis(
                    scope = scope,
                    bridge = bridge,
                    tiles = tiles,
                    customAnnouncementText = messageToAnnounce
                )
                onSpeak("Broadcasting: $messageToAnnounce")
                return
            }
        }

        // 2. Security Center Mode Voice Intents (Home / Away / Night / Vacation)
        when {
            cmd.contains("away mode") || cmd.contains("arm away") || cmd == "set security to away" -> {
                applySecurityCenterMode(SecurityCenterMode.AWAY, context, tiles, bridge, audioManager, scope, onSpeak)
                return
            }
            cmd.contains("night mode") || cmd.contains("arm night") || cmd == "set security to night" -> {
                applySecurityCenterMode(SecurityCenterMode.NIGHT, context, tiles, bridge, audioManager, scope, onSpeak)
                return
            }
            cmd.contains("vacation mode") || cmd.contains("holiday mode") || cmd == "set security to vacation" -> {
                applySecurityCenterMode(SecurityCenterMode.VACATION, context, tiles, bridge, audioManager, scope, onSpeak)
                return
            }
            cmd.contains("home mode") || cmd.contains("disarm security") || cmd == "set security to home" -> {
                if (isVoiceProtectedActionBlocked(context, bridge, "Security Center", "disarm Security Center", onSpeak)) {
                    return
                }
                applySecurityCenterMode(SecurityCenterMode.HOME, context, tiles, bridge, audioManager, scope, onSpeak)
                return
            }
        }

        // 3. Capability-Driven Vehicle Status & Control Intents
        val mentionsGenericCar = cmd.contains("my car") || cmd.contains("the car") || cmd.contains("vehicles") || cmd.contains("vehicle") || cmd.contains("my ev")
        val matchedVehicleIdx = tiles.indexOfFirst { t ->
            (t.deviceClass == DeviceClass.VEHICLE || t.isVehicleTile) && (
                    (t.title.isNotBlank() && cmd.contains(t.title.lowercase(Locale.getDefault()))) ||
                            (t.vehicleRegPlate.isNotBlank() && cmd.contains(t.vehicleRegPlate.lowercase(Locale.getDefault()))) ||
                            fuzzyTokenOverlapScore(cmd, "${t.title} ${t.roomOrSubtitle}") >= 1 ||
                            mentionsGenericCar
                    )
        }
        if (matchedVehicleIdx in tiles.indices) {
            val vehicleIdx = matchedVehicleIdx
            val carTile = tiles[vehicleIdx]
            val isStatusQuestion = cmd.startsWith("is ") ||
                    cmd.startsWith("what") ||
                    cmd.startsWith("how ") ||
                    cmd.contains("status") ||
                    cmd.contains("range") ||
                    cmd.contains("battery") ||
                    cmd.contains("fuel")

            if (isStatusQuestion && !cmd.contains("lock my") && !cmd.contains("unlock")) {
                val lockStr = if (carTile.vehicleLocked) "locked" else "unlocked"
                val energyType = if (carTile.vehicleIsEv) "battery" else "fuel"
                val climateStr = if (carTile.vehicleClimateActive) ", cabin pre-conditioning is active" else ""
                val extraDetails = buildList {
                    if (carTile.vehicleMotExpiryDateIso.isNotBlank()) {
                        add("MOT is ${carTile.vehicleMotStatus} until ${carTile.vehicleMotExpiryDateIso}")
                    }
                    if (carTile.vehicleTaxDueDateIso.isNotBlank()) {
                        add("tax is ${carTile.vehicleTaxStatus}")
                    }
                    if (carTile.vehicleTpmsFrontLeftPsi > 0f && carTile.vehicleTpmsRearLeftPsi > 0f) {
                        add("tyre pressures are ${carTile.vehicleTpmsFrontLeftPsi.roundToInt()} PSI front and ${carTile.vehicleTpmsRearLeftPsi.roundToInt()} PSI rear")
                    }
                    if (carTile.vehicle12vBatteryVolts > 0f) {
                        add("the 12 volt battery is at ${"%.1f".format(Locale.US, carTile.vehicle12vBatteryVolts)} volts")
                    }
                }.joinToString(", ").let { if (it.isNotBlank()) " ${it.replaceFirstChar { c -> c.uppercase(Locale.US) }}." else "" }
                val evCostStr = if (carTile.vehicleIsEv && carTile.vehicleFuelOrBatteryPct in 1..99) {
                    val kwhNeeded = ((100 - carTile.vehicleFuelOrBatteryPct).coerceAtLeast(0) * 0.90f)
                    val nightCostGbp = kwhNeeded * bridge.electricityTariffNightRateGbp
                    val dayCostGbp = kwhNeeded * bridge.electricityTariffDayRateGbp
                    " A full charge costs ${bridge.currencySymbol}${"%.2f".format(Locale.US, nightCostGbp)} on your off-peak night tariff or ${bridge.currencySymbol}${"%.2f".format(Locale.US, dayCostGbp)} on the day rate."
                } else ""
                onSpeak("${carTile.title} is $lockStr with ${carTile.vehicleFuelOrBatteryPct} percent $energyType and ${carTile.vehicleRangeMiles} miles of range$climateStr.$extraDetails$evCostStr")
                return
            }

            if (cmd.contains("climate") || cmd.contains("precondition") || cmd.contains("pre-condition") || cmd.contains("warm up") || cmd.contains("defrost")) {
                val turnOnClimate = !cmd.contains("stop") && !cmd.contains("off")
                val updated = carTile.copy(
                    vehicleClimateActive = turnOnClimate,
                    transactionState = CommandTransactionState.PENDING_ACK,
                    lastUserActuationEpochMs = nowMs
                )
                tiles[vehicleIdx] = updated
                DeviceRepository.saveTiles(context, tiles)
                scope.launch {
                    val (ok, msg) = VehicleIntegrationClient.setVehicleClimate(context, bridge, updated, turnOnClimate)
                    if (vehicleIdx in tiles.indices && tiles[vehicleIdx].id == updated.id) {
                        val hasQueued = JarvisLiveBridge.hasQueuedIntentsForDevice(carTile.id)
                        tiles[vehicleIdx] = if (ok) {
                            tiles[vehicleIdx].copy(transactionState = CommandTransactionState.CONFIRMED)
                        } else {
                            carTile.copy(
                                transactionState = if (hasQueued) CommandTransactionState.SHADOW_QUEUED else CommandTransactionState.FAILED_ROLLED_BACK
                            )
                        }
                        DeviceRepository.saveTiles(context, tiles)
                    }
                    onSpeak(msg)
                }
                return
            }

            if (cmd.contains("flash") || cmd.contains("honk") || cmd.contains("beep") || cmd.contains("find my")) {
                scope.launch {
                    val (_, msg) = VehicleIntegrationClient.triggerHonkAndFlash(context, bridge, carTile)
                    onSpeak(msg)
                }
                return
            }

            if (cmd.contains("charge") || cmd.contains("charging")) {
                val startCharge = !cmd.contains("stop") && !cmd.contains("pause")
                val updated = carTile.copy(
                    vehicleIsCharging = startCharge,
                    transactionState = CommandTransactionState.PENDING_ACK,
                    lastUserActuationEpochMs = nowMs
                )
                tiles[vehicleIdx] = updated
                DeviceRepository.saveTiles(context, tiles)
                scope.launch {
                    val (ok, msg) = VehicleIntegrationClient.setVehicleCharging(context, bridge, updated, startCharge)
                    if (vehicleIdx in tiles.indices && tiles[vehicleIdx].id == updated.id) {
                        val hasQueued = JarvisLiveBridge.hasQueuedIntentsForDevice(carTile.id)
                        tiles[vehicleIdx] = if (ok) {
                            tiles[vehicleIdx].copy(transactionState = CommandTransactionState.CONFIRMED)
                        } else {
                            carTile.copy(
                                transactionState = if (hasQueued) CommandTransactionState.SHADOW_QUEUED else CommandTransactionState.FAILED_ROLLED_BACK
                            )
                        }
                        DeviceRepository.saveTiles(context, tiles)
                    }
                    onSpeak(msg)
                }
                return
            }

            if (cmd.contains("unlock") || cmd.contains("lock")) {
                val shouldLock = !cmd.contains("unlock")
                if (!shouldLock && isVoiceProtectedActionBlocked(context, bridge, carTile.title, "unlock ${carTile.title}", onSpeak)) {
                    return
                }
                val updated = DeviceRepository.stampOutboundIntentAuthority(
                    tile = carTile.copy(
                        vehicleLocked = shouldLock,
                        isActive = shouldLock,
                        transactionState = CommandTransactionState.PENDING_ACK,
                        lastUserActuationEpochMs = nowMs
                    ),
                    intentTier = IntentAuthorityTier.MANUAL_USER,
                    actorName = "Voice Command",
                    nowMs = nowMs
                )
                tiles[vehicleIdx] = updated
                DeviceRepository.saveTiles(context, tiles)
                scope.launch {
                    val startMs = System.currentTimeMillis()
                    val (ok, msg) = VehicleIntegrationClient.setVehicleLocked(context, bridge, updated, shouldLock)
                    val lat = (System.currentTimeMillis() - startMs).coerceAtLeast(15L)
                    JarvisLiveBridge.recordDeviceCommandResult(updated, "VEHICLES", lat, ok, if (ok) "" else msg)
                    if (vehicleIdx in tiles.indices && tiles[vehicleIdx].id == updated.id) {
                        val hasQueued = JarvisLiveBridge.hasQueuedIntentsForDevice(carTile.id)
                        tiles[vehicleIdx] = if (ok) {
                            val verifiedTier = resolveVerifiedTransportAuthorityTier(updated, false)
                            tiles[vehicleIdx].copy(
                                transactionState = CommandTransactionState.CONFIRMED,
                                stateVersion = tiles[vehicleIdx].stateVersion + 1L,
                                stateAuthorityTier = verifiedTier
                            )
                        } else {
                            carTile.copy(
                                transactionState = if (hasQueued) CommandTransactionState.SHADOW_QUEUED else CommandTransactionState.FAILED_ROLLED_BACK
                            )
                        }
                        DeviceRepository.saveTiles(context, tiles)
                    }
                    onSpeak(msg)
                }
                return
            }
        }

        // 4. Capability-Driven Fan Voice Intents (Power, Speed 1-6, Oscillation, Wind Modes, Ionizer)
        val hasFanVerbOrKeyword = cmd.contains("fan") || cmd.contains("oscillat") || cmd.contains("ionizer") || cmd.contains("wind mode")
        val matchedFanIdx = tiles.indexOfFirst { t ->
            (t.isTowerFanTile || t.hasFanSpeedCapability || t.deviceSubtype == DeviceSubtype.TOWER_FAN) &&
                    (hasFanVerbOrKeyword ||
                            (t.title.isNotBlank() && cmd.contains(t.title.lowercase(Locale.getDefault()))) ||
                            (t.roomOrSubtitle.isNotBlank() && cmd.contains(t.roomOrSubtitle.lowercase(Locale.getDefault())) && cmd.contains("fan")))
        }
        if (matchedFanIdx in tiles.indices) {
            val fanIdx = matchedFanIdx
            val fanTile = tiles[fanIdx]
            val speedMatch = Regex("""(?:speed|level)\s*([1-6])""").find(cmd)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""\b([1-6])\b""").find(cmd)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val wantsOff = cmd.contains("turn off") || cmd.contains("switch off") || cmd.endsWith(" off")
            val nextMode = when {
                cmd.contains("natural") || cmd.contains("nature") -> "NATURAL"
                cmd.contains("sleep") || cmd.contains("night") -> "SLEEP"
                cmd.contains("auto") || cmd.contains("smart") -> "AUTO"
                cmd.contains("normal") -> "NORMAL"
                else -> fanTile.fanMode
            }
            val togglesOscillation = cmd.contains("oscillat") || cmd.contains("swing") || cmd.contains("rotate")
            val nextOsc = if (togglesOscillation) {
                !cmd.contains("stop") && !cmd.contains("off")
            } else {
                fanTile.fanOscillating
            }
            val nextIonizer = if (cmd.contains("ionizer") || cmd.contains("anion") || cmd.contains("purifier")) {
                !cmd.contains("off")
            } else {
                fanTile.fanIonizerActive
            }
            val nextSpeed = (speedMatch ?: fanTile.fanSpeedLevel).coerceIn(1, 6)
            val updated = fanTile.copy(
                isActive = !wantsOff,
                fanSpeedLevel = nextSpeed,
                volume = (nextSpeed / 6f) * 100f,
                fanMode = nextMode,
                fanOscillating = nextOsc,
                fanIonizerActive = nextIonizer,
                transactionState = CommandTransactionState.PENDING_ACK,
                lastUserActuationEpochMs = nowMs
            )
            val stampedFan = DeviceRepository.stampOutboundIntentAuthority(
                tile = updated,
                intentTier = IntentAuthorityTier.MANUAL_USER,
                actorName = "Voice Command"
            )
            tiles[fanIdx] = stampedFan
            DeviceRepository.saveTiles(context, tiles)
            scope.launch {
                val ok = dispatchTowerFanHardwareUpdate(bridge, stampedFan)
                if (fanIdx in tiles.indices && tiles[fanIdx].id == stampedFan.id) {
                    val hasQueued = JarvisLiveBridge.hasQueuedIntentsForDevice(fanTile.id)
                    tiles[fanIdx] = if (ok) {
                        tiles[fanIdx].copy(transactionState = CommandTransactionState.CONFIRMED)
                    } else {
                        fanTile.copy(
                            transactionState = if (hasQueued) CommandTransactionState.SHADOW_QUEUED else CommandTransactionState.FAILED_ROLLED_BACK
                        )
                    }
                    DeviceRepository.saveTiles(context, tiles)
                }
                if (ok) {
                    onSpeak(
                        if (wantsOff) "Turned off ${fanTile.title}."
                        else "Set ${fanTile.title} to speed $nextSpeed in ${nextMode.lowercase(Locale.US)} mode${if (nextOsc) " with oscillation on" else ""}."
                    )
                } else {
                    onSpeak("Could not reach ${fanTile.title} on Wi-Fi.")
                }
            }
            return
        }

        // 5. Capability-Driven HDMI Sync Box & Ambient Backlight Voice Intents
        val hasSyncBoxKeyword = cmd.contains("sync box") || cmd.contains("backlight") || cmd.contains("ambilight") || cmd.contains("hdmi")
        val matchedSyncIdx = tiles.indexOfFirst { t ->
            (t.isLytmiTile || t.deviceSubtype == DeviceSubtype.AMBIENT_SYNC) &&
                    (hasSyncBoxKeyword || (t.title.isNotBlank() && cmd.contains(t.title.lowercase(Locale.getDefault()))))
        }
        if (matchedSyncIdx in tiles.indices) {
            val lytmiIdx = matchedSyncIdx
            val lytmiTile = tiles[lytmiIdx]
            val hdmiMatch = Regex("""hdmi\s*([1-4])""").find(cmd)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val nextMode = when {
                cmd.contains("game") -> "GAME"
                cmd.contains("music") -> "MUSIC"
                cmd.contains("movie") || cmd.contains("cinema") -> "MOVIE"
                cmd.contains("scene") || cmd.contains("cyberpunk") || cmd.contains("aurora") -> "SCENE"
                else -> lytmiTile.lytmiSyncMode
            }
            val wantsOff = cmd.contains("turn off") || cmd.contains("switch off") || cmd.endsWith(" off")
            val updated = DeviceRepository.stampOutboundIntentAuthority(
                tile = lytmiTile.copy(
                    isActive = !wantsOff,
                    lytmiHdmiInput = hdmiMatch ?: lytmiTile.lytmiHdmiInput,
                    lytmiSyncMode = nextMode,
                    transactionState = CommandTransactionState.PENDING_ACK,
                    lastUserActuationEpochMs = nowMs
                ),
                intentTier = IntentAuthorityTier.MANUAL_USER,
                actorName = "Voice Command"
            )
            tiles[lytmiIdx] = updated
            DeviceRepository.saveTiles(context, tiles)
            scope.launch {
                val ok = TuyaApiClient.sendLytmiSyncCommand(
                    bridge = bridge,
                    tile = updated,
                    turnOn = updated.isActive,
                    hdmiInput = updated.lytmiHdmiInput,
                    syncMode = updated.lytmiSyncMode,
                    sceneName = updated.lytmiSceneName,
                    diffusionSpeed = updated.lytmiDiffusionSpeed,
                    brightnessPct = updated.volume
                )
                if (lytmiIdx in tiles.indices && tiles[lytmiIdx].id == updated.id) {
                    val hasQueued = JarvisLiveBridge.hasQueuedIntentsForDevice(lytmiTile.id)
                    tiles[lytmiIdx] = if (ok) {
                        tiles[lytmiIdx].copy(transactionState = CommandTransactionState.CONFIRMED)
                    } else {
                        lytmiTile.copy(
                            transactionState = if (hasQueued) CommandTransactionState.SHADOW_QUEUED else CommandTransactionState.FAILED_ROLLED_BACK
                        )
                    }
                    DeviceRepository.saveTiles(context, tiles)
                }
                if (ok) {
                    onSpeak(
                        if (wantsOff) "Turned off ${lytmiTile.title}."
                        else "Set ${lytmiTile.title} to HDMI ${updated.lytmiHdmiInput} in ${updated.lytmiSyncMode.lowercase(Locale.US)} mode."
                    )
                } else {
                    onSpeak("Could not reach ${lytmiTile.title} on Wi-Fi.")
                }
            }
            return
        }

        // 6. AI Troubleshooting, Routine Failure Explanation & Home Health Diagnostics
        if (cmd.contains("why did") ||
            cmd.contains("routine fail") ||
            cmd.contains("automation fail") ||
            cmd.contains("home health") ||
            cmd.contains("system health") ||
            cmd.contains("diagnostic") ||
            cmd.contains("any offline")
        ) {
            val logs = JarvisLiveBridge.automationLogsFlow.value
            val latestFailure = logs.firstOrNull { it.status != AutomationRunStatus.SUCCESS }
            val healLogs = JarvisLiveBridge.selfHealingLogsFlow.value
            val healthRecords = JarvisLiveBridge.integrationHealthFlow.value.values
            val diagnosticReply = when {
                (cmd.contains("routine") || cmd.contains("automation") || cmd.contains("why did")) && latestFailure != null ->
                    "Your routine '${latestFailure.ruleName}' reported: ${latestFailure.userFriendlySummary}"
                healLogs.isNotEmpty() && (cmd.contains("heal") || cmd.contains("offline")) ->
                    "Self-healing engine note: ${healLogs.first().diagnosticDetail}"
                else -> {
                    val avgLatency = healthRecords.map { it.averageLatencyMs }.average().roundToInt()
                    val degraded = healthRecords.filter { !it.isOnline || !it.tokenValid }
                    if (degraded.isEmpty()) {
                        "All ${healthRecords.size} smart home integrations are online and healthy with an average local response time of $avgLatency milliseconds."
                    } else {
                        "Attention needed on ${degraded.joinToString { it.displayName }}: ${degraded.first().statusMessage}."
                    }
                }
            }
            onSpeak(diagnosticReply)
            return
        }

        // 7. Hands-Free Outcome-Based Device Setup & Discovery Intents
        val isOutcomeDeviceSetupIntent = cmd.contains("scan for devices") ||
                cmd.contains("discover devices") ||
                cmd.contains("find new devices") ||
                cmd.contains("search for devices") ||
                cmd.contains("sync devices") ||
                cmd == "add device" ||
                cmd == "pair device" ||
                cmd.startsWith("add my ") ||
                cmd.startsWith("connect my ") ||
                cmd.startsWith("pair my ") ||
                cmd.startsWith("link my ") ||
                cmd.startsWith("set up my ") ||
                cmd.startsWith("setup my ") ||
                cmd.startsWith("connect a ") ||
                (cmd.startsWith("add a ") && !cmd.startsWith("add a schedule") && !cmd.startsWith("add a routine"))

        if (isOutcomeDeviceSetupIntent) {
            val targetGoal = cmd
                .removePrefix("add my ")
                .removePrefix("connect my ")
                .removePrefix("pair my ")
                .removePrefix("link my ")
                .removePrefix("set up my ")
                .removePrefix("setup my ")
                .removePrefix("connect a ")
                .removePrefix("add a ")
                .trim()
            if (targetGoal.isNotBlank() && targetGoal != cmd) {
                onSpeak("Searching your Wi-Fi and linked accounts to connect your $targetGoal.")
            } else {
                onSpeak("Scanning your Wi-Fi and cloud accounts for smart devices.")
            }
            scope.launch(Dispatchers.IO) {
                val beforeCount = tiles.size
                runAutomaticEnvironmentDiscoveryAndSelfHealing(
                    context = context,
                    bridgeSettings = bridge,
                    tiles = tiles,
                    scope = scope,
                    includeFullLanScan = true
                )
                withContext(Dispatchers.Main) {
                    val added = (tiles.size - beforeCount).coerceAtLeast(0)
                    if (added > 0) {
                        onSpeak("Discovery complete. Added $added new device${if (added == 1) "" else "s"} to your dashboard.")
                    } else {
                        onSpeak("Device scan complete. All ${tiles.size} dashboard devices are connected and up to date.")
                    }
                }
            }
            return
        }

        // 8A. Voice-Driven Dry-Run Scenario Simulator ("show me what would happen if I left home now")
        if (cmd.contains("what would happen") ||
            cmd.startsWith("simulate ") ||
            cmd.contains("dry run")
        ) {
            val targetMode = when {
                cmd.contains("night") || cmd.contains("bed") || cmd.contains("sleep") -> SecurityCenterMode.NIGHT
                cmd.contains("vacation") || cmd.contains("holiday") -> SecurityCenterMode.VACATION
                cmd.contains("home") -> SecurityCenterMode.HOME
                else -> SecurityCenterMode.AWAY
            }
            val report = simulateSecurityModeScenario(targetMode, tiles, bridge)
            val previewItems = report.mutations.take(4).joinToString(", ") {
                "${it.tileTitle} to ${it.projectedStateSummary}"
            }
            val savingsStr = if (report.totalWattsSaved >= 5f) {
                " saving approximately ${report.totalWattsSaved.roundToInt()} watts"
            } else ""
            onSpeak(
                if (report.mutations.isEmpty()) {
                    "Simulating ${targetMode.label} mode: all devices are already in their target state."
                } else {
                    "If you switch to ${targetMode.label} mode now, Clara will update ${report.mutations.size} devices$savingsStr: $previewItems."
                }
            )
            return
        }

        // 8B. Goal-Driven Multi-Device AI Routine Synthesis ("I leave for work at 8am, make my home energy efficient")
        if (synthesizeAutomationFromGoal(rawSpokenText, context, rules, tiles, bridge, onSpeak)) {
            return
        }

        // 8C. Voice-Driven Single-Device Schedule & Routine Creation Intents
        if (tryCreateRoutineFromVoiceCommand(cmd, context, rules, tiles, bridge, onSpeak)) {
            return
        }

        // 9. Execute Existing Saved Automation Rules by Voice
        val matchedRuleIdx = rules.indexOfFirst { r ->
            val rName = r.name.lowercase(Locale.getDefault())
            cmd.contains(rName) ||
                    (cmd.startsWith("run ") && rName.contains(cmd.removePrefix("run ").removePrefix("automation ").trim())) ||
                    (cmd.startsWith("execute ") && rName.contains(cmd.removePrefix("execute ").removePrefix("automation ").trim()))
        }
        if (matchedRuleIdx >= 0) {
            val rule = rules[matchedRuleIdx]
            executeRuleActions(
                rule = rule,
                tiles = tiles,
                bridge = bridge,
                audioManager = audioManager,
                scope = scope,
                context = context,
                onSpeak = onSpeak,
                triggerSource = "Voice Command (\"$rawSpokenText\")"
            )
            rules[matchedRuleIdx] = rule.copy(lastTriggeredEpochMs = System.currentTimeMillis())
            DeviceRepository.saveAutomations(context, rules)
            onSpeak("Running ${rule.name}")
            return
        }

        // 9B. Morning Briefing, Council Bins, Birthdays, TV Shows & Football Summary
        if (cmd.contains("morning briefing") ||
            cmd.contains("daily briefing") ||
            cmd.contains("what's on today") ||
            cmd.contains("what is on today") ||
            cmd.contains("bin collection") ||
            cmd.contains("which bin")
        ) {
            val w = JarvisLiveBridge.weatherFlow.value
            val todayIso = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(java.util.Date())
            val todayMonthDay = todayIso.substring(5)
            val todayCal = Calendar.getInstance()
            val tomorrowCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
            val binsToday = bridge.councilBinsConfig.getBinsForDate(todayCal)
            val binsTomorrow = bridge.councilBinsConfig.getBinsForDate(tomorrowCal)
            val binSpeech = when {
                binsToday.isNotEmpty() -> "Today is council collection day for: ${binsToday.joinToString(" and ") { it.label }}."
                binsTomorrow.isNotEmpty() -> "Put out your ${binsTomorrow.joinToString(" and ") { it.label }} tonight for collection tomorrow morning."
                else -> "No council bin collection due today or tomorrow."
            }
            val birthdaysToday = bridge.calendarEvents.filter {
                it.dateIso == todayIso || (it.isRecurringYearly && it.dateIso.endsWith(todayMonthDay))
            }
            val bdaySpeech = if (birthdaysToday.isNotEmpty()) {
                " Today's reminders: ${birthdaysToday.joinToString(", ") { it.title }}."
            } else ""
            ClaraEntertainmentStore.ensureInitialized(context)
            val entSnapshot = ClaraEntertainmentStore.buildDashboardSnapshot(ClaraEntertainmentStore.items.value)
            val tvSpeech = if (entSnapshot.todayReleases.isNotEmpty()) {
                val joined = entSnapshot.todayReleases.joinToString(", ") { rel ->
                    if (rel.primaryStreamer.isNotBlank()) "${rel.title} on ${rel.primaryStreamer}" else rel.title
                }
                " Releasing today: $joined."
            } else ""
            onSpeak("Good morning. Outdoor weather is ${w.temperatureCelsius} degrees and ${w.conditionText}. $binSpeech$bdaySpeech$tvSpeech")
            return
        }

        // 9C. Entertainment Hub Voice Queries (Today's Releases & Next Upcoming Countdown)
        if (cmd.contains("upcoming release") ||
            cmd.contains("any new episode") ||
            cmd.contains("what's airing") ||
            cmd.contains("what is airing") ||
            cmd.contains("entertainment schedule")
        ) {
            ClaraEntertainmentStore.ensureInitialized(context)
            val entSnapshot = ClaraEntertainmentStore.buildDashboardSnapshot(ClaraEntertainmentStore.items.value)
            val reply = when {
                entSnapshot.trackedShowCount == 0 && entSnapshot.trackedMovieCount == 0 ->
                    "You have no shows or movies tracked in Entertainment Hub yet."
                entSnapshot.todayReleases.isNotEmpty() -> {
                    val todayNames = entSnapshot.todayReleases.joinToString(", ") { "${it.title} (${it.episodeCode})" }
                    "Releasing today: $todayNames. You have ${entSnapshot.upcoming28DayCount} releases scheduled over the next 28 days."
                }
                entSnapshot.nextRelease != null -> {
                    val next = entSnapshot.nextRelease
                    "Nothing releases today. Next up is ${next.title} (${next.episodeCode}), releasing ${next.countdownLabel}."
                }
                else -> "You are all caught up with no upcoming release dates announced yet."
            }
            onSpeak(reply)
            return
        }

        // 10. Weather, Indoor Temperature, Octopus Agile Tariff, Solar/Battery Storage & Knowledge Graph Queries
        if (cmd.contains("what is the weather") || cmd.contains("what's the weather") || cmd.contains("outside temperature")) {
            val w = JarvisLiveBridge.weatherFlow.value
            onSpeak("It is currently ${w.temperatureCelsius} degrees and ${w.conditionText} outside.")
            return
        }
        if (cmd.contains("what is the temperature") || cmd.contains("what's the temperature") || cmd.contains("inside temperature")) {
            val therm = tiles.firstOrNull { it.isThermostatTile }
            if (therm != null) {
                onSpeak("${therm.title} is currently ${"%.1f".format(therm.temperature)} degrees, set to ${"%.1f".format(therm.targetTemperature)} degrees.")
                return
            }
        }
        if (cmd.contains("tariff") || cmd.contains("octopus") || cmd.contains("cheapest slot") || cmd.contains("electricity rate")) {
            val tariff = SmartHomeApiClient.cachedOctopusTariffCapability
            if (tariff != null) {
                val lowest = tariff.nextLowestSlot
                val lowestSuffix = if (lowest != null) {
                    " The next lowest half-hour slot is ${"%.1f".format(Locale.US, lowest.rateIncVatPence)} pence per kilowatt hour at ${lowest.validFromIso.substringAfter("T").take(5)}."
                } else ""
                onSpeak("Your current electricity rate is ${"%.1f".format(Locale.US, tariff.currentRatePence)} pence per kilowatt hour.$lowestSuffix")
            } else {
                val activeRatePence = (if (isOffPeakNightTariffNow(bridge = bridge)) bridge.electricityTariffNightRateGbp else bridge.electricityTariffDayRateGbp) * 100f
                onSpeak("Your current electricity tariff rate is ${"%.1f".format(Locale.US, activeRatePence)} pence per kilowatt hour.")
            }
            return
        }
        if (cmd.contains("solar") || cmd.contains("powerwall") || cmd.contains("home battery") || cmd.contains("inverter")) {
            val storage = SmartHomeApiClient.cachedEnergyStorageCapabilities.firstOrNull()
            if (storage != null) {
                onSpeak("${storage.providerId.displayName} battery is at ${storage.batterySocPct} percent, solar is generating ${storage.solarGenerationWatts.roundToInt()} watts, and home load is ${storage.homeLoadWatts.roundToInt()} watts.")
                return
            }
        }
        if (cmd.contains("knowledge graph") || cmd.contains("context insight") || cmd.contains("unoccupied room")) {
            val snapshot = DeviceRepository.rebuildDigitalHomeGraph(context, tiles, bridge, rules)
            val topInference = snapshot.activeInferences.firstOrNull()
            if (topInference != null) {
                onSpeak("Context Graph insight: ${topInference.summary}.")
            } else {
                onSpeak("Your Knowledge Graph is tracking ${snapshot.knowledgeNodes.size} nodes and ${snapshot.knowledgeEdges.size} relationships across ${snapshot.rooms.size} rooms with no anomalies.")
            }
            return
        }
        if (cmd.contains("how much power") || cmd.contains("electricity usage") || cmd.contains("live watts")) {
            val totalWatts = tiles.sumOf { it.powerWatts.toDouble() }.toFloat()
            onSpeak("Your home is currently drawing ${"%.1f".format(totalWatts)} watts across all monitored smart plugs.")
            return
        }

        // 11. LG Smart TV App / Show Deep-Link Launch & Mute Voice Intents
        val tvTile = tiles.firstOrNull { it.deviceSubtype == DeviceSubtype.TV }
        if (tvTile != null) {
            val matchedTvApp = LgThinQWebOsClient.loadTvApps(context).firstOrNull { app ->
                val fullLower = app.label.lowercase(Locale.getDefault())
                val baseLower = fullLower.substringBefore(" (").trim()
                cmd.contains(fullLower) || (baseLower.length >= 3 && cmd.contains(baseLower))
            }
            val hasTvLaunchVerb = cmd.contains("open ") ||
                    cmd.contains("launch ") ||
                    cmd.contains("watch ") ||
                    cmd.contains("start ") ||
                    cmd.contains("put on ")
            val mentionsTvOrShow = cmd.contains(" on tv") ||
                    cmd.contains("lg tv") ||
                    cmd.contains("television") ||
                    matchedTvApp?.contentId?.isNotBlank() == true
            if (matchedTvApp != null && hasTvLaunchVerb && mentionsTvOrShow) {
                LgThinQWebOsClient.launchAppShortcut(context, tvTile, bridge, matchedTvApp, scope)
                onSpeak("Launching ${matchedTvApp.label} on ${tvTile.title}")
                return
            }
            if ((cmd.contains(" on tv") || cmd.contains("lg tv") || cmd.contains("television")) && cmd.contains("mute")) {
                val unmute = cmd.contains("unmute")
                LgThinQWebOsClient.toggleMute(context, tvTile, mute = !unmute, scope = scope)
                onSpeak(if (unmute) "Unmuted ${tvTile.title}" else "Muted ${tvTile.title}")
                return
            }
        }

        // 12. Music Transport & Catalog Search Intents
        if (cmd == "pause" || cmd == "pause music" || cmd == "stop music") {
            ClaraInAppMusicPlayer.togglePlayPause(audioManager, scope)
            onSpeak("Music paused")
            return
        }
        if (cmd == "next song" || cmd == "skip song" || cmd == "next track") {
            ClaraInAppMusicPlayer.skipToNext(audioManager, scope)
            return
        }
        if (cmd.startsWith("play ")) {
            val musicQuery = cmd.removePrefix("play ").trim()
            if (musicQuery.isNotBlank()) {
                scope.launch {
                    val savedPlaylists = NativeMusicCatalogClient.loadLibraryAndPlaylists(context, bridge.musicProvider)
                    val matchedPlaylist = savedPlaylists.firstOrNull {
                        it.title.lowercase(Locale.getDefault()).contains(musicQuery)
                    }
                    if (matchedPlaylist != null) {
                        val tracks = NativeMusicCatalogClient.fetchTracksForPlaylist(context, matchedPlaylist, bridge)
                        if (tracks.isNotEmpty()) {
                            ClaraInAppMusicPlayer.playTrackQueue(tracks, 0, scope)
                            onSpeak("Playing ${matchedPlaylist.title}")
                            return@launch
                        }
                    }
                    val searchRes = NativeMusicCatalogClient.searchMusicCatalog(context, bridge.musicProvider, musicQuery)
                    val foundTracks = searchRes.getOrNull().orEmpty()
                    if (foundTracks.isNotEmpty()) {
                        ClaraInAppMusicPlayer.playTrackQueue(foundTracks, 0, scope)
                        onSpeak("Playing ${foundTracks.first().title}")
                    }
                }
                return
            }
        }

        // 13. Individual Socket 1 / Socket 2 / Custom Sub-Socket Name / Both Sockets Voice Control
        for (idx in tiles.indices) {
            val plug = tiles[idx]
            if (!plug.isMultiGangSwitch) continue
            val s1Name = plug.socket1Name.ifBlank { "Socket 1" }
            val s2Name = plug.socket2Name.ifBlank { "Socket 2" }
            val s1Lower = s1Name.lowercase(Locale.getDefault())
            val s2Lower = s2Name.lowercase(Locale.getDefault())

            val wantsOn = cmd.contains("turn on") || cmd.contains("switch on") || cmd.endsWith(" on")
            val wantsOff = cmd.contains("turn off") || cmd.contains("switch off") || cmd.endsWith(" off")

            val matchesSocket1 = (s1Lower.isNotBlank() && cmd.contains(s1Lower)) ||
                    cmd.contains("socket 1") || cmd.contains("socket one")
            val matchesSocket2 = (s2Lower.isNotBlank() && cmd.contains(s2Lower)) ||
                    cmd.contains("socket 2") || cmd.contains("socket two")

            if (matchesSocket1 && !matchesSocket2) {
                val nextOn = if (wantsOff) false else if (wantsOn) true else !plug.isActive
                val updated = plug.copy(
                    isActive = nextOn,
                    transactionState = CommandTransactionState.PENDING_ACK,
                    lastUserActuationEpochMs = nowMs
                )
                tiles[idx] = updated
                DeviceRepository.saveTiles(context, tiles)
                dispatchDeviceUpdate(
                    updated = updated,
                    bridge = bridge,
                    audioManager = audioManager,
                    scope = scope,
                    singleSocketNumber = 1,
                    actorName = "Voice Command"
                ) { finalTile, ok, detail ->
                    if (idx in tiles.indices && tiles[idx].id == plug.id) {
                        tiles[idx] = if (ok) finalTile else plug.copy(isOnline = false, transactionState = CommandTransactionState.FAILED_ROLLED_BACK)
                        DeviceRepository.saveTiles(context, tiles)
                    }
                    if (ok) onSpeak("$s1Name turned ${if (nextOn) "on" else "off"}")
                    else onSpeak(detail.ifBlank { "Could not reach $s1Name." })
                }
                return
            }
            if (matchesSocket2 && !matchesSocket1) {
                val currentS2 = plug.isSocket2Active
                val nextOn = if (wantsOff) false else if (wantsOn) true else !currentS2
                val updated = plug.copy(
                    isSocket2Active = nextOn,
                    transactionState = CommandTransactionState.PENDING_ACK,
                    lastUserActuationEpochMs = nowMs
                )
                tiles[idx] = updated
                DeviceRepository.saveTiles(context, tiles)
                dispatchDeviceUpdate(
                    updated = updated,
                    bridge = bridge,
                    audioManager = audioManager,
                    scope = scope,
                    singleSocketNumber = 2,
                    actorName = "Voice Command"
                ) { finalTile, ok, detail ->
                    if (idx in tiles.indices && tiles[idx].id == plug.id) {
                        tiles[idx] = if (ok) finalTile else plug.copy(isOnline = false, transactionState = CommandTransactionState.FAILED_ROLLED_BACK)
                        DeviceRepository.saveTiles(context, tiles)
                    }
                    if (ok) onSpeak("$s2Name turned ${if (nextOn) "on" else "off"}")
                    else onSpeak(detail.ifBlank { "Could not reach $s2Name." })
                }
                return
            }
            if (cmd.contains("both sockets") || cmd.contains("all sockets")) {
                val nextOn = !wantsOff
                val updated = plug.copy(
                    isActive = nextOn,
                    isSocket2Active = nextOn,
                    transactionState = CommandTransactionState.PENDING_ACK,
                    lastUserActuationEpochMs = nowMs
                )
                tiles[idx] = updated
                DeviceRepository.saveTiles(context, tiles)
                dispatchDeviceUpdate(updated, bridge, audioManager, scope, actorName = "Voice Command") { finalTile, ok, detail ->
                    if (idx in tiles.indices && tiles[idx].id == plug.id) {
                        tiles[idx] = if (ok) finalTile else plug.copy(isOnline = false, transactionState = CommandTransactionState.FAILED_ROLLED_BACK)
                        DeviceRepository.saveTiles(context, tiles)
                    }
                    if (ok) onSpeak("Both sockets turned ${if (nextOn) "on" else "off"}")
                    else onSpeak(detail.ifBlank { "Could not reach ${plug.title}." })
                }
                return
            }
        }
        val numberRegex = Regex("(\\d+(?:\\.\\d+)?)")
        val numberMatch = numberRegex.find(cmd)?.value?.toFloatOrNull()
        // 14. Speaker Volume Control (Awaits Cast TLS 8009 ACK)
        if (numberMatch != null && (cmd.contains("volume") || cmd.contains("speaker") || cmd.contains("nest mini"))) {
            val miniIdx = tiles.indexOfFirst { t ->
                t.deviceSubtype in setOf(DeviceSubtype.SPEAKER, DeviceSubtype.CAST_GROUP) &&
                        (cmd.contains(t.title.lowercase(Locale.getDefault())) ||
                                (t.roomOrSubtitle.isNotBlank() && cmd.contains(t.roomOrSubtitle.lowercase(Locale.getDefault()))))
            }.let { found ->
                if (found >= 0) found else tiles.indexOfFirst {
                    it.deviceSubtype in setOf(DeviceSubtype.SPEAKER, DeviceSubtype.CAST_GROUP)
                }
            }
            if (miniIdx >= 0) {
                val t = tiles[miniIdx]
                val clampedVol = numberMatch.coerceIn(0f, 100f)
                val updated = t.copy(
                    volume = clampedVol,
                    transactionState = CommandTransactionState.PENDING_ACK,
                    lastUserActuationEpochMs = nowMs
                )
                tiles[miniIdx] = updated
                DeviceRepository.saveTiles(context, tiles)
                dispatchDeviceUpdate(updated, bridge, audioManager, scope, actorName = "Voice Command") { finalTile, ok, detail ->
                    if (miniIdx in tiles.indices && tiles[miniIdx].id == t.id) {
                        tiles[miniIdx] = if (ok) finalTile else t.copy(isOnline = false, transactionState = CommandTransactionState.FAILED_ROLLED_BACK)
                        DeviceRepository.saveTiles(context, tiles)
                    }
                    if (ok) onSpeak("Set ${t.title} volume to ${clampedVol.roundToInt()} percent")
                    else onSpeak(detail.ifBlank { "Could not reach ${t.title}." })
                }
                return
            }
        }
        // 15. Thermostat / Heater Temperature Control (Awaits Hardware / SDM ACK)
        if (numberMatch != null && (cmd.contains("degree") || cmd.contains("temp") || cmd.contains("set ") || cmd.contains("heat"))) {
            val thermIdx = tiles.indexOfFirst { t ->
                t.isThermostatTile && (cmd.contains(t.title.lowercase(Locale.getDefault())) ||
                        (t.roomOrSubtitle.isNotBlank() && cmd.contains(t.roomOrSubtitle.lowercase(Locale.getDefault()))) ||
                        (cmd.contains("heater") && t.deviceSubtype == DeviceSubtype.RADIATOR) ||
                        (cmd.contains("downstairs") && t.title.contains("downstairs", true)) ||
                        (cmd.contains("upstairs") && t.title.contains("upstairs", true)))
            }.let { found -> if (found >= 0) found else tiles.indexOfFirst { it.isThermostatTile } }
            if (thermIdx >= 0) {
                val t = tiles[thermIdx]
                val tempCap = t.capabilities.firstOrNull { it.rendererType == CapabilityRendererType.TEMPERATURE_DIAL }
                val minLimit = tempCap?.minValue ?: 5f
                val maxLimit = tempCap?.maxValue ?: if (t.deviceSubtype == DeviceSubtype.THERMOSTAT) 32f else 35f
                val clampedTemp = numberMatch.coerceIn(minLimit, maxLimit)
                val updated = t.copy(
                    targetTemperature = clampedTemp,
                    isActive = true,
                    hvacMode = "HEAT",
                    hvacStatus = if (clampedTemp > t.temperature) "HEATING" else "OFF",
                    transactionState = CommandTransactionState.PENDING_ACK,
                    lastUserActuationEpochMs = nowMs
                )
                tiles[thermIdx] = updated
                DeviceRepository.saveTiles(context, tiles)
                dispatchDeviceUpdate(updated, bridge, audioManager, scope, actorName = "Voice Command") { finalTile, ok, detail ->
                    if (thermIdx in tiles.indices && tiles[thermIdx].id == t.id) {
                        tiles[thermIdx] = if (ok) finalTile else t.copy(isOnline = false, transactionState = CommandTransactionState.FAILED_ROLLED_BACK)
                        DeviceRepository.saveTiles(context, tiles)
                    }
                    if (ok) onSpeak("Set ${t.title} to ${clampedTemp.roundToInt()} degrees")
                    else onSpeak(detail.ifBlank { "Could not reach ${t.title}." })
                }
                return
            }
        }
        // 15B. Semantic DeviceClass Batch & Room Queries ("turn off all lights in the living room", "how many switches are on")
        val targetDeviceClass: DeviceClass? = when {
            cmd.contains("light") || cmd.contains("lamp") || cmd.contains("bulb") -> DeviceClass.LIGHT
            cmd.contains("plug") || cmd.contains("socket") || cmd.contains("switch") -> DeviceClass.SWITCH
            cmd.contains("climate") || cmd.contains("heating") || cmd.contains("heater") || cmd.contains("thermostat") -> DeviceClass.CLIMATE
            cmd.contains("camera") || cmd.contains("cameras") -> DeviceClass.CAMERA
            else -> null
        }
        val matchedRoomFilter = bridge.customRooms.ifEmpty { DEFAULT_ROOMS }.firstOrNull { r ->
            r.isNotBlank() && cmd.contains(r.lowercase(Locale.getDefault()))
        }
        if (targetDeviceClass != null && (cmd.contains("all ") || matchedRoomFilter != null || cmd.startsWith("how many "))) {
            val classMatches = tiles.withIndex().filter { (_, t) ->
                t.isConfiguredAndLinked &&
                        (t.deviceClass == targetDeviceClass || (targetDeviceClass == DeviceClass.SWITCH && t.isSwitchTile)) &&
                        (matchedRoomFilter == null || t.roomOrSubtitle.equals(matchedRoomFilter, ignoreCase = true))
            }
            if (cmd.startsWith("how many ") || cmd.startsWith("are any ")) {
                val activeCount = classMatches.count { it.value.isActive || it.value.isSocket2Active }
                val roomSuffix = if (matchedRoomFilter != null) " in $matchedRoomFilter" else ""
                onSpeak("$activeCount of ${classMatches.size} ${targetDeviceClass.label.lowercase(Locale.US)} devices$roomSuffix are currently active.")
                return
            }
            if (classMatches.isNotEmpty() && (cmd.contains("turn on") || cmd.contains("turn off") || cmd.contains("switch on") || cmd.contains("switch off"))) {
                val wantsOnBatch = cmd.contains("turn on") || cmd.contains("switch on")
                for ((idx, tile) in classMatches) {
                    val updated = DeviceRepository.stampOutboundIntentAuthority(
                        tile = tile.copy(
                            isActive = wantsOnBatch,
                            isSocket2Active = if (tile.isMultiGangSwitch) wantsOnBatch else tile.isSocket2Active,
                            hvacMode = if (tile.isThermostatTile) (if (wantsOnBatch) "HEAT" else "OFF") else tile.hvacMode,
                            transactionState = CommandTransactionState.PENDING_ACK,
                            lastUserActuationEpochMs = nowMs
                        ),
                        intentTier = IntentAuthorityTier.MANUAL_USER,
                        actorName = "Voice Command"
                    )
                    tiles[idx] = updated
                    dispatchDeviceUpdate(updated, bridge, audioManager, scope, actorName = "Voice Command") { finalTile, ok, _ ->
                        if (idx in tiles.indices && tiles[idx].id == tile.id) {
                            tiles[idx] = if (ok) finalTile else tile.copy(isOnline = false, transactionState = CommandTransactionState.FAILED_ROLLED_BACK)
                            DeviceRepository.saveTiles(context, tiles)
                        }
                    }
                }
                DeviceRepository.saveTiles(context, tiles)
                val roomSuffix = if (matchedRoomFilter != null) " in $matchedRoomFilter" else ""
                onSpeak("Turning ${if (wantsOnBatch) "on" else "off"} ${classMatches.size} ${targetDeviceClass.label.lowercase(Locale.US)} device${if (classMatches.size == 1) "" else "s"}$roomSuffix.")
                return
            }
        }

        // 16. Generic Device Turn On / Off by Title or Fuzzy Semantic Room+Title Match
        val exactMatchIdx = tiles.indexOfFirst { t ->
            val titleLower = t.title.lowercase(Locale.getDefault())
            titleLower.isNotBlank() && cmd.contains(titleLower)
        }
        val targetDeviceIdx = if (exactMatchIdx >= 0) {
            exactMatchIdx
        } else {
            tiles.indices
                .filter { idx ->
                    val t = tiles[idx]
                    t.type != TileType.CLOCK_WEATHER && t.type != TileType.PHOTO_SLIDESHOW
                }
                .maxByOrNull { idx ->
                    val t = tiles[idx]
                    fuzzyTokenOverlapScore(cmd, "${t.roomOrSubtitle} ${t.title}")
                }
                ?.takeIf { idx ->
                    val t = tiles[idx]
                    fuzzyTokenOverlapScore(cmd, "${t.roomOrSubtitle} ${t.title}") >= 2
                } ?: -1
        }

        if (targetDeviceIdx in tiles.indices) {
            val i = targetDeviceIdx
            val t = tiles[i]
            if (t.title.isNotBlank()) {
                val wantsOff = cmd.contains("off") || cmd.contains("close") || cmd.contains("stop") || cmd.contains("unlock")
                val wantsOn = cmd.contains("on") || cmd.contains("open") || cmd.contains("start") || cmd.contains("show") || cmd.contains("lock")
                val nextActive = if (wantsOff) false else if (wantsOn) true else !t.isActive

                // SEC-01 Fix: Block voice unlocking of Smart Locks or Vehicles when Parental Lock / PIN Shield is active
                if (!nextActive && (t.deviceSubtype == DeviceSubtype.LOCK || t.hasLockCapability || t.isVehicleTile)) {
                    if (isVoiceProtectedActionBlocked(context, bridge, t.title, "unlock ${t.title}", onSpeak)) {
                        return
                    }
                }

                val baseUpdated = when {
                    t.isCameraTile -> t.copy(isLiveFeedActive = nextActive)
                    t.isThermostatTile -> t.copy(
                        isActive = nextActive,
                        hvacMode = if (nextActive) "HEAT" else "OFF",
                        hvacStatus = if (nextActive && t.targetTemperature > t.temperature) "HEATING" else "OFF"
                    )
                    t.isVehicleTile -> t.copy(
                        vehicleLocked = nextActive,
                        isActive = nextActive
                    )
                    t.isMultiGangSwitch -> t.copy(
                        isActive = nextActive,
                        isSocket2Active = nextActive
                    )
                    else -> t.copy(isActive = nextActive)
                }.copy(
                    transactionState = CommandTransactionState.PENDING_ACK,
                    lastUserActuationEpochMs = nowMs
                )
                // F-21: Activate 15-second PENDING_ACK lock; 30-minute manual hold is stamped onto SmartTile below
                DeviceRepository.markTilePendingAck(t.id)
                val updated = DeviceRepository.stampOutboundIntentAuthority(
                    tile = baseUpdated,
                    intentTier = IntentAuthorityTier.MANUAL_USER,
                    actorName = "Voice Command",
                    nowMs = nowMs
                )

                tiles[i] = updated
                DeviceRepository.saveTiles(context, tiles)

                dispatchDeviceUpdate(
                    updated = updated,
                    bridge = bridge,
                    audioManager = audioManager,
                    scope = scope,
                    intentTier = IntentAuthorityTier.MANUAL_USER,
                    actorName = "Voice Command"
                ) { finalTile, ok, detail ->
                    val curIdx = tiles.indexOfFirst { it.id == t.id }
                    if (curIdx in tiles.indices) {
                        tiles[curIdx] = if (ok) {
                            finalTile
                        } else {
                            t.copy(
                                isOnline = false,
                                transactionState = CommandTransactionState.FAILED_ROLLED_BACK,
                                lastUserActuationEpochMs = 0L
                            )
                        }
                        DeviceRepository.saveTiles(context, tiles)
                    }
                    val statusVerb = if (t.isVehicleTile || t.deviceSubtype == DeviceSubtype.LOCK) {
                        if (nextActive) "locked" else "unlocked"
                    } else {
                        if (nextActive) "turned on" else "turned off"
                    }
                    if (ok) {
                        onSpeak("${t.title} $statusVerb")
                    } else {
                        onSpeak(detail.ifBlank { "Could not reach ${t.title}." })
                    }
                }
                return
            }
        }

        // 17. Quick Home Scenes, Theme Mode Switching & Optional Local LAN LLM Fallback
        when {
            cmd.contains("eco night") || cmd.contains("goodnight") -> executeHomeRoutine("ECO NIGHT", tiles, bridge, audioManager, scope, context, onSpeak)
            cmd.contains("boost heat") -> executeHomeRoutine("BOOST HEAT", tiles, bridge, audioManager, scope, context, onSpeak)
            cmd.contains("live cam") || cmd.contains("all camera") -> executeHomeRoutine("ALL LIVE FEEDS", tiles, bridge, audioManager, scope, context, onSpeak)
            cmd.contains("light mode") -> {
                DeviceRepository.saveBridge(context, bridge.copy(appThemeMode = AppThemeMode.LIGHT))
                onSpeak("Switched to light mode")
            }
            cmd.contains("dark mode") -> {
                DeviceRepository.saveBridge(context, bridge.copy(appThemeMode = AppThemeMode.DARK))
                onSpeak("Switched to dark mode")
            }
            bridge.localLlmEndpointUrl.isNotBlank() -> {
                scope.launch {
                    val graph = DeviceRepository.rebuildDigitalHomeGraph(context, tiles, bridge, rules)
                    val llmAnswer = queryLocalLlmFallback(bridge.localLlmEndpointUrl, rawSpokenText, graph)
                    if (!llmAnswer.isNullOrBlank()) {
                        onSpeak(llmAnswer)
                    }
                }
            }
        }
    }
}
