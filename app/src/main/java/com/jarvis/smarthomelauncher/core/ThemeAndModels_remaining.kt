package com.jarvis.smarthomelauncher.core

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.app.PendingIntent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Blinds
import androidx.compose.material.icons.outlined.BlindsClosed
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BrightnessLow
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Doorbell
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.EvStation
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.LinearScale
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SettingsInputHdmi
import androidx.compose.material.icons.outlined.SettingsPower
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt



/**
 * Widget-pure control renderer types: defines HOW a capability control renders
 * on the Hero Card without leaking device subtypes into the UI layer.
 */

typealias CapabilityClassifier = SemanticClassifier

object SemanticClassifier {
    const val CURRENT_VERSION = 4
    private const val PREFS_NAME = "clara_capability_classification_v2"

    private val resolutionCache = mutableMapOf<String, ClassificationResolution>()

    private val kettleDpKeys = setOf(
        "boil", "temp_boiling", "keep_warm", "warm", "water_level", "tea_mode", "heating_quick", "diy", "warm_time", "temp_warm"
    )
    private val cookingDpKeys = setOf(
        "cook_time", "cook_temperature", "recipe", "appointment_time", "shake_remind", "preheat", "keep_warm_time"
    )
    private val hvacAndWaterHeaterCategories = setOf(
        "wk", "wkf", "qn", "rs", "rd"
    )
    private val kitchenApplianceCategories = setOf(
        "bh", "ymq", "kf", "kfj", "kz", "af", "mc", "dfb", "jc", "dk", "ysj", "mjj"
    )

    @Synchronized
    fun recordResolution(
        deviceOrTileKey: String,
        resolution: ClassificationResolution,
        context: android.content.Context? = null
    ) {
        val cleanKey = deviceOrTileKey.trim()
        if (cleanKey.isBlank()) return
        val existing = resolutionCache[cleanKey]
        if (existing?.source == ClassificationSource.MANUAL_OVERRIDE &&
            resolution.source != ClassificationSource.MANUAL_OVERRIDE
        ) {
            return
        }
        resolutionCache[cleanKey] = resolution
        val ctx = context ?: ClaraApplication.instance?.applicationContext
        if (ctx != null) {
            runCatching {
                val json = org.json.JSONObject().apply {
                    put("deviceClass", resolution.deviceClass.name)
                    put("deviceSubtype", resolution.deviceSubtype.name)
                    put("suggestedTileType", resolution.suggestedTileType.name)
                    put("confidence", resolution.confidence.toDouble())
                    put("source", resolution.source.name)
                    put("version", resolution.version)
                    put("evaluatedAtEpochMs", resolution.evaluatedAtEpochMs)
                    put("schemaHash", resolution.schemaHash)
                }
                ClaraDeviceRegistryStore.getInstance(ctx).saveSemanticResolution(cleanKey, json.toString())
            }
        }
    }

    @Synchronized
    fun getResolution(
        deviceOrTileKey: String,
        context: android.content.Context? = null
    ): ClassificationResolution? {
        val cleanKey = deviceOrTileKey.trim()
        if (cleanKey.isBlank()) return null
        resolutionCache[cleanKey]?.let { cached ->
            if (cached.source == ClassificationSource.MANUAL_OVERRIDE || cached.version >= CURRENT_VERSION) {
                return cached
            }
            resolutionCache.remove(cleanKey)
        }
        val ctx = context ?: ClaraApplication.appContextOrNull() ?: return null
        val store = ClaraDeviceRegistryStore.getInstance(ctx)
        val raw = store.loadSemanticResolution(cleanKey) ?: run {
            val legacyPrefs = ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val legacyVal = legacyPrefs.getString(cleanKey, null)
            if (!legacyVal.isNullOrBlank()) {
                store.saveSemanticResolution(cleanKey, legacyVal)
                legacyPrefs.edit().remove(cleanKey).apply()
                if (legacyPrefs.all.isEmpty()) {
                    runCatching { ctx.deleteSharedPreferences(PREFS_NAME) }
                }
            }
            legacyVal
        } ?: return null
        return runCatching {
            val o = org.json.JSONObject(raw)
            val savedVersion = o.optInt("version", 1)
            val src = runCatching {
                ClassificationSource.valueOf(o.optString("source", ClassificationSource.CAPABILITY_CLASSIFIER.name))
            }.getOrDefault(ClassificationSource.CAPABILITY_CLASSIFIER)
            if (src != ClassificationSource.MANUAL_OVERRIDE && savedVersion < CURRENT_VERSION) {
                return@runCatching null
            }
            val dClass = DeviceClass.valueOf(o.optString("deviceClass", DeviceClass.SWITCH.name))
            val dSub = runCatching {
                DeviceSubtype.valueOf(o.optString("deviceSubtype", ""))
            }.getOrDefault(DeviceSubtype.entries.first())
            val tType = runCatching {
                TileType.valueOf(o.optString("suggestedTileType", TileType.SMARTLIFE_DEVICE.name))
            }.getOrDefault(TileType.SMARTLIFE_DEVICE)
            ClassificationResolution(
                deviceClass = dClass,
                deviceSubtype = dSub,
                suggestedTileType = tType,
                confidence = o.optDouble("confidence", 0.85).toFloat(),
                source = src,
                version = savedVersion,
                evaluatedAtEpochMs = o.optLong("evaluatedAtEpochMs", 0L),
                schemaHash = o.optString("schemaHash", "")
            ).also {
                resolutionCache[cleanKey] = it
            }
        }.getOrNull()
    }

    fun shouldReevaluateTile(
        tile: SmartTile,
        newSchemaHash: String,
        context: android.content.Context? = null
    ): Boolean {
        val key = tile.serialNumber.ifBlank { tile.entityId }.ifBlank { tile.id }
        val existing = getResolution(key, context) ?: return true
        if (existing.source == ClassificationSource.MANUAL_OVERRIDE) return false
        return existing.version < CURRENT_VERSION ||
                (newSchemaHash.isNotBlank() && existing.schemaHash != newSchemaHash)
    }

    fun inferControlRole(cap: DeviceCapabilityDescriptor): SemanticControlRole {
        val code = cap.key.lowercase(Locale.US)
        val unit = cap.unit.lowercase(Locale.US)
        return when (cap.kind) {
            DeviceCapabilityKind.READ_ONLY_SENSOR -> {
                if (code in setOf("cur_power", "power", "wattage", "power_consumption", "add_ele", "energy") ||
                    unit in setOf("w", "kw", "kwh")
                ) {
                    SemanticControlRole.ENERGY_TELEMETRY_GRAPH
                } else {
                    SemanticControlRole.READ_ONLY_METRIC
                }
            }
            DeviceCapabilityKind.COUNTDOWN_TIMER -> SemanticControlRole.COUNTDOWN_TIMER
            DeviceCapabilityKind.ACTION_TRIGGER -> SemanticControlRole.MODE_SELECTOR
            DeviceCapabilityKind.ENUM_MODE -> {
                when {
                    code in setOf("hdmi_input", "input_source", "source", "hdmi") ->
                        SemanticControlRole.HDMI_INPUT_SELECTOR
                    code.contains("colour") || code.contains("color") ->
                        SemanticControlRole.COLOR_WHEEL
                    else ->
                        SemanticControlRole.MODE_SELECTOR
                }
            }
            DeviceCapabilityKind.BOOLEAN_SWITCH -> {
                when {
                    code in setOf("switch_2", "switch2") -> SemanticControlRole.SECONDARY_SOCKET_TOGGLE
                    code in setOf("switch", "switch_1", "switch1", "power", "switch_led", "power_go", "start", "boil") ->
                        SemanticControlRole.PRIMARY_POWER_TOGGLE
                    else -> SemanticControlRole.MODE_SELECTOR
                }
            }
            DeviceCapabilityKind.NUMERIC_SLIDER -> {
                if (code in setOf("hdmi_input", "input_source", "hdmi")) {
                    return SemanticControlRole.HDMI_INPUT_SELECTOR
                }
                val isNonTempTimerOrMetric = code.contains("countdown") ||
                        code.contains("timer") ||
                        code.contains("time") ||
                        code.contains("clean") ||
                        code.contains("area") ||
                        code.contains("battery") ||
                        code.contains("suction") ||
                        code.contains("volume") ||
                        code.contains("bright") ||
                        code in setOf("temp_value", "temp_value_v2", "temp_unit_convert", "temp_correction", "temp_calibration") ||
                        unit in setOf("s", "sec", "secs", "second", "seconds", "min", "mins", "h", "hr", "pct", "%", "m2", "\u33A1")
                val cleanUnit = unit.trim()
                val isExactTempUnit = cleanUnit in setOf("c", "f", "\u00B0c", "\u00B0f", "\u2103", "\u2109", "celsius", "fahrenheit")
                val isExplicitTempKey = code in setOf(
                    "temp_set", "set_temp", "target_temperature", "target_temp", "temp_boiling",
                    "cook_temperature", "warm_temp", "upper_temp", "temperature"
                ) || code.endsWith("_temp")
                val isTemp = !isNonTempTimerOrMetric && (isExplicitTempKey || isExactTempUnit)
                when {
                    isTemp && (cap.maxValue in 71f..260f || code in setOf("cook_temperature", "temp_boiling")) ->
                        SemanticControlRole.APPLIANCE_TEMPERATURE_DIAL
                    isTemp && cap.maxValue <= 70f ->
                        SemanticControlRole.THERMOSTAT_TEMPERATURE_DIAL
                    code in setOf("fan_speed", "fan_speed_percent", "windspeed", "speed", "level") && cap.maxValue <= 12f ->
                        SemanticControlRole.FAN_SPEED_STEPPER
                    code in setOf("position", "percent_control", "control_back", "lift") ->
                        SemanticControlRole.COVER_POSITION_SLIDER
                    code in setOf("volume", "vol", "sound_volume", "speaker_volume") ->
                        SemanticControlRole.VOLUME_SLIDER
                    else ->
                        SemanticControlRole.BRIGHTNESS_SLIDER
                }
            }
        }
    }

    /**
     * Canonical Layer 5 Capability -> Layer 11 [CapabilityRendererType] Lookup Table.
     */
    fun inferRendererType(cap: DeviceCapabilityDescriptor): CapabilityRendererType {
        return when (inferControlRole(cap)) {
            SemanticControlRole.PRIMARY_POWER_TOGGLE,
            SemanticControlRole.SECONDARY_SOCKET_TOGGLE -> CapabilityRendererType.TOGGLE
            SemanticControlRole.HDMI_INPUT_SELECTOR -> CapabilityRendererType.INPUT_SELECTOR
            SemanticControlRole.THERMOSTAT_TEMPERATURE_DIAL,
            SemanticControlRole.APPLIANCE_TEMPERATURE_DIAL -> CapabilityRendererType.TEMPERATURE_DIAL
            SemanticControlRole.FAN_SPEED_STEPPER -> CapabilityRendererType.FAN_SPEED_STEPPER
            SemanticControlRole.BRIGHTNESS_SLIDER -> CapabilityRendererType.BRIGHTNESS_SLIDER
            SemanticControlRole.COVER_POSITION_SLIDER -> CapabilityRendererType.COVER_POSITION_SLIDER
            SemanticControlRole.VOLUME_SLIDER -> CapabilityRendererType.VOLUME_SLIDER
            SemanticControlRole.COLOR_WHEEL -> CapabilityRendererType.COLOR_WHEEL
            SemanticControlRole.COUNTDOWN_TIMER,
            SemanticControlRole.MODE_SELECTOR -> CapabilityRendererType.CHIP_GROUP
            SemanticControlRole.ENERGY_TELEMETRY_GRAPH -> CapabilityRendererType.ENERGY_GRAPH
            SemanticControlRole.READ_ONLY_METRIC -> CapabilityRendererType.SENSOR_BADGE
        }
    }

    fun inferWidgetRenderer(cap: DeviceCapabilityDescriptor): CapabilityWidgetRenderer {
        return when (inferRendererType(cap)) {
            CapabilityRendererType.TOGGLE -> CapabilityWidgetRenderer.TOGGLE_CARD
            CapabilityRendererType.INPUT_SELECTOR -> CapabilityWidgetRenderer.INPUT_SELECTOR
            CapabilityRendererType.TEMPERATURE_DIAL,
            CapabilityRendererType.DIAL -> CapabilityWidgetRenderer.TEMPERATURE_DIAL
            CapabilityRendererType.FAN_SPEED_STEPPER,
            CapabilityRendererType.STEP_BAR -> CapabilityWidgetRenderer.FAN_SPEED_STEPPER
            CapabilityRendererType.BRIGHTNESS_SLIDER -> CapabilityWidgetRenderer.BRIGHTNESS_SLIDER
            CapabilityRendererType.COVER_POSITION_SLIDER -> CapabilityWidgetRenderer.COVER_POSITION_SLIDER
            CapabilityRendererType.VOLUME_SLIDER,
            CapabilityRendererType.SLIDER -> CapabilityWidgetRenderer.VOLUME_SLIDER
            CapabilityRendererType.COLOR_WHEEL -> CapabilityWidgetRenderer.COLOR_WHEEL_360
            CapabilityRendererType.CHIP_GROUP,
            CapabilityRendererType.MEDIA_TRANSPORT,
            CapabilityRendererType.D_PAD -> CapabilityWidgetRenderer.CHIP_GROUP
            CapabilityRendererType.ENERGY_GRAPH -> CapabilityWidgetRenderer.ENERGY_GRAPH
            CapabilityRendererType.SENSOR_BADGE -> CapabilityWidgetRenderer.METRIC_BADGE
        }
    }

    fun isKitchenApplianceByCapabilities(
        capabilities: List<DeviceCapabilityDescriptor>,
        categoryCode: String = ""
    ): Boolean {
        val cat = categoryCode.lowercase()
        if (cat in hvacAndWaterHeaterCategories) return false
        if (cat in kitchenApplianceCategories) return true
        val keys = capabilities.map { it.key.lowercase() }.toSet()
        if (keys.any { it in kettleDpKeys || it in cookingDpKeys }) return true
        val hasKettleEnumValues = capabilities.any { cap ->
            cap.currentValue.lowercase() in setOf("heating_quick", "diy", "boil", "keep_warm") ||
                    cap.enumOptions.any { opt -> opt.lowercase() in setOf("heating_quick", "diy", "boil", "tea", "coffee") }
        }
        if (hasKettleEnumValues) return true
        // Only treat high temperature as a kitchen appliance if an actual temperature slider has max in 71..260 C
        val maxTempSlider = capabilities
            .filter { inferControlRole(it) == SemanticControlRole.APPLIANCE_TEMPERATURE_DIAL }
            .maxOfOrNull { it.maxValue } ?: 0f
        return maxTempSlider in 71f..260f
    }

    /**
     * Strict 3-Tier Climate & Panel Heater Promotion Hierarchy:
     * Tier 1: Capability & DP Evidence First (writable temperature setpoint <= 70 C + ambient temp or climate mode DP)
     * Tier 2: Vendor Category Metadata Second (qn, wk, rs, wkf, rd)
     * Tier 3: Title Matching Last Resort ("panel heater", "space heater", "radiator", "thermostat", "trv", "heater")
     */
    fun isClimateHeatingDevice(
        capabilities: List<DeviceCapabilityDescriptor>,
        categoryCode: String = "",
        titleHint: String = ""
    ): Boolean {
        val cat = categoryCode.trim().lowercase(Locale.US)
        val lowerTitle = titleHint.trim().lowercase(Locale.US)

        if (cat in setOf("cz", "pc", "kg", "tdq") ||
            lowerTitle.contains("plug") ||
            lowerTitle.contains("socket") ||
            lowerTitle.contains("pond") ||
            lowerTitle.contains("pump") ||
            lowerTitle.contains("kettle") ||
            lowerTitle.contains("fryer") ||
            lowerTitle.contains("coffee") ||
            lowerTitle.contains("washer")
        ) {
            return false
        }
        if (isKitchenApplianceByCapabilities(capabilities, cat)) return false

        val keys = capabilities.map { it.key.lowercase(Locale.US) }.toSet()
        val hasVacuumOrMediaDps = keys.any {
            it in setOf("power_go", "switch_go", "clean_switch", "suction", "hdmi_input", "speaker_volume")
        }
        if (hasVacuumOrMediaDps) return false

        // Tier 1: Require non-blank hardware dpId so synthetic fallback capabilities never self-poison
        val hardwareCaps = capabilities.filter { it.dpId.isNotBlank() }
        val hasWritableSetpointDp = hardwareCaps.any { cap ->
            cap.isWritable && (
                    inferControlRole(cap) == SemanticControlRole.THERMOSTAT_TEMPERATURE_DIAL ||
                            (cap.kind == DeviceCapabilityKind.NUMERIC_SLIDER &&
                                    cap.maxValue <= 70f &&
                                    cap.key.lowercase(Locale.US) in setOf(
                                "temp_set", "set_temp", "target_temperature", "target_temp", "upper_temp", "temperature"
                            ))
                    )
        }
        val hasAmbientTempSensorDp = hardwareCaps.any { cap ->
            val k = cap.key.lowercase(Locale.US)
            k in setOf("temp_current", "current_temp", "ambient_temperature", "va_temperature", "room_temp", "temp_indoor") ||
                    (!cap.isWritable && k.contains("temp"))
        }
        if (hasWritableSetpointDp && (hasAmbientTempSensorDp || hardwareCaps.any { it.maxValue in 15f..55f })) {
            return true
        }

        // Tier 2: Vendor Climate Category Metadata Second
        if (cat in hvacAndWaterHeaterCategories) {
            return true
        }

        // Tier 3: Title Matching Last Resort
        if (lowerTitle.isNotBlank()) {
            if (lowerTitle.contains("panel heater") ||
                lowerTitle.contains("space heater") ||
                lowerTitle.contains("radiator") ||
                lowerTitle.contains("thermostat") ||
                lowerTitle.contains("trv") ||
                lowerTitle.contains("heater")
            ) {
                return true
            }
        }
        return false
    }

    fun allowedSubtypesForCapabilities(
        capabilities: List<DeviceCapabilityDescriptor>,
        hasPowerTelemetry: Boolean = false
    ): List<DeviceSubtype> {
        val keys = capabilities.map { it.key.lowercase(Locale.US) }.toSet()
        return when {
            keys.any { it in setOf("hdmi_input", "input_source", "hdmi") } ->
                listOf(DeviceSubtype.AMBIENT_SYNC, DeviceSubtype.TV, DeviceSubtype.LED_STRIP)
            keys.any { it in setOf("temp_set", "set_temp", "target_temperature", "target_temp") } ->
                listOf(DeviceSubtype.THERMOSTAT, DeviceSubtype.RADIATOR, DeviceSubtype.TRV, DeviceSubtype.AIR_FRYER, DeviceSubtype.KETTLE)
            hasPowerTelemetry || keys.any { it.startsWith("switch") || it == "power" || it == "cur_power" } ->
                listOf(
                    DeviceSubtype.SMART_PLUG,
                    DeviceSubtype.WALL_SOCKET,
                    DeviceSubtype.KETTLE,
                    DeviceSubtype.AIR_FRYER,
                    DeviceSubtype.COFFEE_MACHINE,
                    DeviceSubtype.WASHER,
                    DeviceSubtype.RADIATOR,
                    DeviceSubtype.TOWER_FAN,
                    DeviceSubtype.BULB,
                    DeviceSubtype.POND_PUMP,
                    DeviceSubtype.EV_CHARGER
                )
            else -> DeviceSubtype.hardwareEntries
        }
    }

    fun resolve(
        capabilities: List<DeviceCapabilityDescriptor>,
        categoryCode: String,
        multiGangSocketCount: Int,
        hasPowerTelemetry: Boolean,
        schemaHash: String = "",
        titleHint: String = "",
        observedBehaviour: ObservedBehaviourProfile? = null
    ): ClassificationResolution {
        val cat = categoryCode.lowercase()
        val keys = capabilities.map { it.key.lowercase() }.toSet()
        val roles = capabilities.map { inferControlRole(it) }.toSet()
        val hasCat = cat.isNotBlank()
        val resolvedSource = when {
            observedBehaviour != null && observedBehaviour.confidence >= 0.75f -> ClassificationSource.FINGERPRINT_ANALYZER
            capabilities.isNotEmpty() && hasCat -> ClassificationSource.HYBRID
            capabilities.isNotEmpty() -> ClassificationSource.CAPABILITY_CLASSIFIER
            hasCat -> ClassificationSource.CLOUD_METADATA
            else -> ClassificationSource.FINGERPRINT_ANALYZER
        }

        // 0. Observed Behaviour + Telemetry Pattern Inference (Kettle 2.8kW burst vs. Air Fryer 1.8kW cycling)
        if (observedBehaviour != null && observedBehaviour.confidence >= 0.75f) {
            if (observedBehaviour.peakWatts >= 2150f &&
                observedBehaviour.averageCycleDurationSeconds in 45..300 &&
                observedBehaviour.dropsToZeroAfterBurst
            ) {
                return ClassificationResolution(
                    deviceClass = DeviceClass.APPLIANCE,
                    deviceSubtype = DeviceSubtype.KETTLE,
                    suggestedTileType = TileType.SMARTKNIGHT_PLUG,
                    confidence = observedBehaviour.confidence,
                    source = ClassificationSource.FINGERPRINT_ANALYZER,
                    schemaHash = schemaHash
                )
            }
            if (observedBehaviour.peakWatts in 1200f..2149f &&
                observedBehaviour.averageCycleDurationSeconds in 300..3600 &&
                observedBehaviour.hasThermostaticCycling
            ) {
                return ClassificationResolution(
                    deviceClass = DeviceClass.APPLIANCE,
                    deviceSubtype = DeviceSubtype.AIR_FRYER,
                    suggestedTileType = TileType.SMARTKNIGHT_PLUG,
                    confidence = observedBehaviour.confidence,
                    source = ClassificationSource.FINGERPRINT_ANALYZER,
                    schemaHash = schemaHash
                )
            }
        }

        // 1. HDMI Sync Box / Ambient Media Backlight -> DeviceClass.MEDIA + DeviceSubtype.AMBIENT_SYNC
        val lowerTitleHint = titleHint.trim().lowercase(Locale.US)
        val isLytmiTitleHint = lowerTitleHint.contains("lytmi") ||
                lowerTitleHint.contains("neo3") ||
                lowerTitleHint.contains("neo 3") ||
                lowerTitleHint.contains("fantasy") ||
                lowerTitleHint.contains("sync box") ||
                lowerTitleHint.contains("ambilight")
        val hasHdmiCap = keys.any { it in setOf("hdmi_input", "input_source", "hdmi") }
        val hasSyncOrColourCap = keys.any {
            it in setOf("work_mode", "sync_mode", "scene_select", "diffusion_speed", "colour_data", "colour_data_v2")
        }
        if ((hasHdmiCap && hasSyncOrColourCap) || cat == "fwl" || isLytmiTitleHint) {
            return ClassificationResolution(
                deviceClass = DeviceClass.MEDIA,
                deviceSubtype = DeviceSubtype.AMBIENT_SYNC,
                suggestedTileType = TileType.LYTMI_SYNC_BOX,
                confidence = 0.98f,
                source = resolvedSource,
                schemaHash = schemaHash
            )
        }

        // 2. Robot Vacuum (resolved by navigation/dock/suction DPs)
        val hasVacuumDps = keys.any {
            it in setOf("power_go", "switch_go", "clean_switch", "switch_charge", "charge_switch", "seek", "suction", "clean_mode")
        }
        if (hasVacuumDps || cat == "sd") {
            return ClassificationResolution(
                deviceClass = DeviceClass.APPLIANCE,
                deviceSubtype = DeviceSubtype.VACUUM,
                suggestedTileType = TileType.AIRROBO_VACUUM,
                confidence = 0.98f,
                source = resolvedSource,
                schemaHash = schemaHash
            )
        }

        // 3. Kitchen Appliances (Kettle / Air Fryer / Coffee Maker)
        if (isKitchenApplianceByCapabilities(capabilities, cat)) {
            val maxTemp = capabilities.filter { it.kind == DeviceCapabilityKind.NUMERIC_SLIDER }.maxOfOrNull { it.maxValue } ?: 0f
            val subtype = when {
                keys.any { it in cookingDpKeys } || maxTemp > 115f || cat in setOf("kz", "af") ->
                    DeviceSubtype.AIR_FRYER
                keys.any { it.contains("coffee") || it.contains("brew") } || cat in setOf("kf", "kfj") ->
                    DeviceSubtype.COFFEE_MACHINE
                else ->
                    DeviceSubtype.KETTLE
            }
            return ClassificationResolution(
                deviceClass = DeviceClass.APPLIANCE,
                deviceSubtype = subtype,
                suggestedTileType = TileType.SMARTLIFE_DEVICE,
                confidence = 0.95f,
                source = resolvedSource,
                schemaHash = schemaHash
            )
        }

        // 4. Fan / Air Purifier (resolved by fan speed + oscillation/ionizer DPs without heating setpoint)
        val hasFanSpeed = keys.any { it in setOf("fan_speed", "fan_speed_percent", "windspeed") } ||
                SemanticControlRole.FAN_SPEED_STEPPER in roles
        val hasFanTraits = keys.any { it in setOf("switch_horizontal", "switch_vertical", "shake", "swing", "anion", "ionizer") }
        val hasTempSetpoint = SemanticControlRole.THERMOSTAT_TEMPERATURE_DIAL in roles
        if ((hasFanSpeed && hasFanTraits && !hasTempSetpoint) || cat in setOf("fs", "fskg", "fsd")) {
            return ClassificationResolution(
                deviceClass = DeviceClass.CLIMATE,
                deviceSubtype = DeviceSubtype.TOWER_FAN,
                suggestedTileType = TileType.PROBREEZE_FAN,
                confidence = 0.95f,
                source = resolvedSource,
                schemaHash = schemaHash
            )
        }

        // 5. Climate / Thermostat / Panel Heater / Radiator / TRV (Strict 3-Tier Evaluation)
        if (isClimateHeatingDevice(capabilities, cat, titleHint)) {
            val lowerTitle = titleHint.lowercase(Locale.US)
            val climateSubtype = when {
                cat == "wkf" || lowerTitle.contains("trv") || lowerTitle.contains("valve") -> DeviceSubtype.TRV
                cat == "wk" || lowerTitle.contains("thermostat") -> DeviceSubtype.THERMOSTAT
                else -> DeviceSubtype.RADIATOR
            }
            return ClassificationResolution(
                deviceClass = DeviceClass.CLIMATE,
                deviceSubtype = climateSubtype,
                suggestedTileType = TileType.TUYA_RADIATOR,
                confidence = if (hasTempSetpoint) 0.98f else 0.94f,
                source = resolvedSource,
                schemaHash = schemaHash
            )
        }

        // 6. Smart Covers / Blinds / Garage Doors
        if (SemanticControlRole.COVER_POSITION_SLIDER in roles || cat in setOf("cl", "clkg", "ckmkzq")) {
            val coverSubtype = if (cat == "ckmkzq") DeviceSubtype.GARAGE_DOOR else DeviceSubtype.BLIND
            return ClassificationResolution(
                deviceClass = DeviceClass.SECURITY,
                deviceSubtype = coverSubtype,
                suggestedTileType = TileType.SMART_COVER,
                confidence = 0.94f,
                source = resolvedSource,
                schemaHash = schemaHash
            )
        }

        // 7. Lighting (resolved by brightness, colour temperature, or RGB colour DPs)
        val hasLightCaps = keys.any {
            it in setOf("bright_value", "bright_value_v2", "brightness", "temp_value", "temp_value_v2", "colour_data", "colour_data_v2")
        }
        if (hasLightCaps || cat in setOf("dj", "dd", "fwd", "xdd", "dc")) {
            val lightSubtype = if (cat in setOf("dd", "dc")) DeviceSubtype.LED_STRIP else DeviceSubtype.BULB
            return ClassificationResolution(
                deviceClass = DeviceClass.LIGHT,
                deviceSubtype = lightSubtype,
                suggestedTileType = TileType.HUE_LIGHT,
                confidence = 0.94f,
                source = resolvedSource,
                schemaHash = schemaHash
            )
        }

        // 8. Sensors (read-only capabilities only)
        val hasAnyWritable = capabilities.any { it.isWritable }
        if ((!hasAnyWritable && capabilities.isNotEmpty()) || cat in setOf("wsdcg", "mcs", "pir")) {
            return ClassificationResolution(
                deviceClass = DeviceClass.SENSOR,
                deviceSubtype = DeviceSubtype.SENSOR,
                suggestedTileType = TileType.SMART_SENSOR,
                confidence = 0.92f,
                source = resolvedSource,
                schemaHash = schemaHash
            )
        }

        // 9. Smart Plugs & Multi-Gang Sockets (resolved by multi-gang DPs, energy telemetry, or socket category)
        if (multiGangSocketCount >= 2 || hasPowerTelemetry || cat in setOf("cz", "pc", "kg", "tdq")) {
            val switchSubtype = when {
                cat == "kg" -> DeviceSubtype.WALL_SOCKET
                multiGangSocketCount >= 2 || hasPowerTelemetry || cat in setOf("cz", "pc") -> DeviceSubtype.SMART_PLUG
                else -> DeviceSubtype.RELAY
            }
            return ClassificationResolution(
                deviceClass = if (hasPowerTelemetry) DeviceClass.ENERGY else DeviceClass.SWITCH,
                deviceSubtype = switchSubtype,
                suggestedTileType = TileType.SMARTKNIGHT_PLUG,
                confidence = 0.94f,
                source = resolvedSource,
                schemaHash = schemaHash
            )
        }

        return ClassificationResolution(
            deviceClass = DeviceClass.SWITCH,
            deviceSubtype = DeviceSubtype.SMART_PLUG,
            suggestedTileType = TileType.SMARTLIFE_DEVICE,
            confidence = 0.75f,
            source = resolvedSource,
            schemaHash = schemaHash
        )
    }
}

val SmartTile.classificationResolution: ClassificationResolution?
    get() = CapabilityClassifier.getResolution(serialNumber.ifBlank { entityId }.ifBlank { id })


val DEFAULT_USER_PROFILES = listOf(
    UserProfile(
        id = "profile_owner",
        name = "Owner",
        role = UserRole.OWNER,
        avatarColorHex = 0xFFA3F0C1L,
        isParentalLocked = false,
        requirePinToSwitchOut = false
    ),
    UserProfile(
        id = "profile_family",
        name = "Family",
        role = UserRole.FAMILY,
        avatarColorHex = 0xFF00C6FFL,
        isParentalLocked = false,
        requirePinToSwitchOut = false
    ),
    UserProfile(
        id = "profile_kids",
        name = "Kids",
        role = UserRole.CHILD,
        avatarColorHex = 0xFFFF9E44L,
        isParentalLocked = false,
        requirePinToSwitchOut = false
    ),
    UserProfile(
        id = "profile_guest",
        name = "Guest",
        role = UserRole.GUEST,
        avatarColorHex = 0xFFC084FCL,
        isParentalLocked = false,
        requirePinToSwitchOut = false
    )
)

// ---------------------------------------------------------------------------
// LIVE RADIO STATIONS MODEL
// ---------------------------------------------------------------------------

val DEFAULT_RADIO_STATIONS = listOf(
    RadioStationStream(
        id = "radio_capital_uk",
        name = "Capital FM UK",
        genreOrTagline = "The UK's No.1 Hit Music Station",
        streamUrl = "https://media-ssl.musicradio.com/CapitalUK",
        accentColorHex = 0xFF00E5FFL,
        badgeCode = "CAP"
    ),
    RadioStationStream(
        id = "radio_kisstory",
        name = "KISSTORY",
        genreOrTagline = "Non-Stop Old Skool & Anthems",
        streamUrl = "https://edge-bauerall-01-gos2.sharp-stream.com/kisstory.aac",
        accentColorHex = 0xFFFF4081L,
        badgeCode = "KST"
    ),
    RadioStationStream(
        id = "radio_kiss_fm",
        name = "KISS FM UK",
        genreOrTagline = "The Biggest Dance & R&B Beats",
        streamUrl = "https://edge-bauerall-01-gos2.sharp-stream.com/kissnational.aac",
        accentColorHex = 0xFFE040FBL,
        badgeCode = "KISS"
    ),
    RadioStationStream(
        id = "radio_heart_uk",
        name = "Heart UK",
        genreOrTagline = "Turn Up the Feel Good!",
        streamUrl = "https://media-ssl.musicradio.com/HeartUK",
        accentColorHex = 0xFFFF5252L,
        badgeCode = "HRT"
    ),
    RadioStationStream(
        id = "radio_smooth_uk",
        name = "Smooth Radio",
        genreOrTagline = "Always the Best Music",
        streamUrl = "https://media-ssl.musicradio.com/SmoothUK",
        accentColorHex = 0xFFFFB300L,
        badgeCode = "SMTH"
    ),
    RadioStationStream(
        id = "radio_lbc_uk",
        name = "LBC London",
        genreOrTagline = "Leading Britain's Conversation",
        streamUrl = "https://media-ssl.musicradio.com/LBCUK",
        accentColorHex = 0xFF58A6FFL,
        badgeCode = "LBC"
    ),
    RadioStationStream(
        id = "radio_bbc_r1",
        name = "BBC Radio 1",
        genreOrTagline = "Best New Pop, Dance & Indie",
        streamUrl = "https://as-hls-ww-live.akamaized.net/pool_01505109/live/ww/bbc_radio_one/bbc_radio_one.isml/bbc_radio_one-audio%3d96000.norewind.m3u8",
        accentColorHex = 0xFF00E676L,
        badgeCode = "R1"
    ),
    RadioStationStream(
        id = "radio_bbc_r2",
        name = "BBC Radio 2",
        genreOrTagline = "Pop, Rock, Soul & Live Sessions",
        streamUrl = "https://as-hls-ww-live.akamaized.net/pool_74208725/live/ww/bbc_radio_two/bbc_radio_two.isml/bbc_radio_two-audio%3d96000.norewind.m3u8",
        accentColorHex = 0xFFFF8A00L,
        badgeCode = "R2"
    ),
    RadioStationStream(
        id = "radio_bbc_r4",
        name = "BBC Radio 4",
        genreOrTagline = "News, Drama, Comedy & Documentaries",
        streamUrl = "https://as-hls-ww-live.akamaized.net/pool_55057080/live/ww/bbc_radio_fourfm/bbc_radio_fourfm.isml/bbc_radio_fourfm-audio%3d96000.norewind.m3u8",
        accentColorHex = 0xFF2DD4BFL,
        badgeCode = "R4"
    ),
    RadioStationStream(
        id = "radio_classic_fm",
        name = "Classic FM",
        genreOrTagline = "The World's Greatest Classical Music",
        streamUrl = "https://media-ssl.musicradio.com/ClassicFM",
        accentColorHex = 0xFFC084FCL,
        badgeCode = "CFM"
    )
)

// ---------------------------------------------------------------------------
// NEXT EPISODE: NATIVE TV SHOW & MOVIE TRACKER MODELS & LIVE DIRECTORY CLIENT
// ---------------------------------------------------------------------------

val DEFAULT_TRACKED_MEDIA_SHOWS: List<TrackedMediaShow> = emptyList()

// ---------------------------------------------------------------------------
// HOUSEHOLD CALENDAR, BIRTHDAYS & UK COUNCIL BIN COLLECTION MODELS
// ---------------------------------------------------------------------------

val DEFAULT_HOUSEHOLD_CALENDAR_EVENTS = listOf(
    HouseholdCalendarEvent(
        id = "default_bday_partner",
        title = "Partner's Birthday \uD83C\uDF82",
        subtitle = "Yearly Household Reminder",
        dateIso = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date()),
        timeLabel = "All Day",
        category = CalendarEventCategory.BIRTHDAY,
        isRecurringYearly = true
    )
)

// ---------------------------------------------------------------------------
// FOOTBALL FIXTURES & UK TV BROADCASTER DIRECTORY MODELS
// ---------------------------------------------------------------------------

val DEFAULT_FOLLOWED_FOOTBALL_TEAMS = listOf("Arsenal", "Liverpool", "Manchester City", "Manchester United", "Chelsea", "England")

val DEFAULT_IBO_CHANNEL_MAPPINGS = mapOf(
    "Sky Sports Main Event" to 401,
    "Sky Sports Premier League" to 402,
    "Sky Sports Football" to 403,
    "TNT Sports 1" to 410,
    "TNT Sports 2" to 411,
    "BBC One" to 101,
    "ITV1" to 103
)


object FootballFixturesDirectoryClient {
    private val ukSportsCanonicalTargets = listOf(
        "Sky Sports Main Event" to listOf("sky sports main event", "sky sp main ev", "ss main event"),
        "Sky Sports Premier League" to listOf("sky sports premier league", "sky sp pl", "sky sports pl", "ss premier league"),
        "Sky Sports Football" to listOf("sky sports football", "sky sp football", "ss football"),
        "TNT Sports 1" to listOf("tnt sports 1", "tnt sport 1", "bt sport 1"),
        "TNT Sports 2" to listOf("tnt sports 2", "tnt sport 2", "bt sport 2"),
        "BBC One" to listOf("bbc one", "bbc 1"),
        "ITV1" to listOf("itv1", "itv 1", "itv hd")
    )

    /**
     * Fetches live streams from an Xtream Codes (`player_api.php?action=get_live_streams`)
     * or M3U playlist URL and automatically maps UK sports channels to their exact channel numbers.
     */
    suspend fun syncChannelsFromXtreamOrM3u(
        config: FootballTvProviderConfig
    ): Map<String, Int> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val resolved = LinkedHashMap<String, Int>(DEFAULT_IBO_CHANNEL_MAPPINGS)
        resolved.putAll(config.autoMatchedChannels)

        fun matchCanonicalChannel(rawName: String): String? {
            val lower = rawName.lowercase(Locale.US)
            return ukSportsCanonicalTargets.firstOrNull { (_, keywords) ->
                keywords.any { kw -> lower.contains(kw) }
            }?.first
        }

        // 1. Xtream Codes API (`player_api.php?username=...&password=...&action=get_live_streams`)
        val cleanHost = config.xtreamServerUrl.trim().trimEnd('/')
        val cleanUser = config.xtreamUsername.trim()
        val cleanPass = config.xtreamPassword.trim()
        if (cleanHost.startsWith("http", ignoreCase = true) && cleanUser.isNotBlank() && cleanPass.isNotBlank()) {
            try {
                val uEnc = java.net.URLEncoder.encode(cleanUser, "UTF-8")
                val pEnc = java.net.URLEncoder.encode(cleanPass, "UTF-8")
                val url = "$cleanHost/player_api.php?username=$uEnc&password=$pEnc&action=get_live_streams"
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "IBOPlayer/1.0")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val arr = org.json.JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val name = item.optString("name", "").trim()
                        val chNum = item.optInt("num", item.optInt("stream_id", 0))
                        if (name.isNotBlank() && chNum > 0) {
                            val canonical = matchCanonicalChannel(name)
                            if (canonical != null) {
                                resolved[canonical] = chNum
                            }
                        }
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
        }

        // 2. M3U Playlist Parser (`#EXTINF:-1 tvg-chno="401",UK: Sky Sports Main Event FHD`)
        val cleanM3u = config.m3uPlaylistUrl.trim()
        if (cleanM3u.startsWith("http", ignoreCase = true)) {
            try {
                val conn = (java.net.URL(cleanM3u).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "IBOPlayer/1.0")
                }
                if (conn.responseCode in 200..299) {
                    var runningIndex = 0
                    conn.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val trimmed = line.trim()
                            if (trimmed.startsWith("#EXTINF", ignoreCase = true)) {
                                runningIndex++
                                val chNoAttr = Regex("""tvg-chno=["']?(\d+)["']?""", RegexOption.IGNORE_CASE)
                                    .find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                val titlePart = trimmed.substringAfterLast(",").trim()
                                val canonical = matchCanonicalChannel(titlePart)
                                if (canonical != null) {
                                    resolved[canonical] = chNoAttr ?: runningIndex
                                }
                            }
                        }
                    }
                    conn.disconnect()
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
        }
        resolved
    }

    fun inferUkBroadcasterAndChannel(
        competition: String,
        rawBroadcastHint: String,
        iboMappings: Map<String, Int> = DEFAULT_IBO_CHANNEL_MAPPINGS
    ): Triple<String, String, Int> {
        val lower = "$competition $rawBroadcastHint".lowercase(Locale.US)
        val channelName = when {
            lower.contains("tnt") || lower.contains("bt sport") || lower.contains("champions league") || lower.contains("europa") ->
                "TNT Sports 1"
            lower.contains("bbc") || lower.contains("fa cup") ->
                "BBC iPlayer"
            lower.contains("itv") ->
                "ITVX"
            lower.contains("prime") || lower.contains("amazon") ->
                "Prime Video"
            lower.contains("sky") && lower.contains("premier") ->
                "Sky Sports Premier League"
            else ->
                "Sky Sports Main Event"
        }
        val appId = when (channelName) {
            "BBC iPlayer" -> "bbc"
            "ITVX" -> "itv.hub"
            "Prime Video" -> "lovefilm.uk"
            else -> "com.ibo.pro"
        }
        val chNum = iboMappings[channelName] ?: DEFAULT_IBO_CHANNEL_MAPPINGS[channelName] ?: 0
        return Triple(channelName, appId, chNum)
    }

    suspend fun fetchUpcomingFixtures(
        followedTeams: List<String>,
        iboMappings: Map<String, Int> = DEFAULT_IBO_CHANNEL_MAPPINGS
    ): List<FootballMatchFixture> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val leagues = listOf(
            "eng.1" to "Premier League",
            "uefa.champions" to "UEFA Champions League",
            "eng.fa" to "FA Cup",
            "eng.league_cup" to "Carabao Cup"
        )
        val results = mutableListOf<FootballMatchFixture>()
        val sdfIn = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val sdfDateOut = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.UK)
        val sdfTimeOut = java.text.SimpleDateFormat("HH:mm", Locale.UK)

        for ((leagueCode, leagueTitle) in leagues) {
            try {
                val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/$leagueCode/scoreboard"
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3500
                    readTimeout = 3500
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val events = org.json.JSONObject(body).optJSONArray("events") ?: continue
                    for (i in 0 until events.length()) {
                        val ev = events.optJSONObject(i) ?: continue
                        val id = ev.optString("id", UUID.randomUUID().toString())
                        val rawDate = ev.optString("date", "")
                        val parsedDate = runCatching { sdfIn.parse(rawDate.take(16) + "Z") }.getOrNull()
                        val dateIso = if (parsedDate != null) sdfDateOut.format(parsedDate) else rawDate.take(10)
                        val kickOff = if (parsedDate != null) sdfTimeOut.format(parsedDate) else "15:00"

                        val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: continue
                        val competitors = comp.optJSONArray("competitors") ?: continue
                        var home = ""
                        var away = ""
                        var homeScore = ""
                        var awayScore = ""
                        for (c in 0 until competitors.length()) {
                            val teamEntry = competitors.optJSONObject(c) ?: continue
                            val teamName = teamEntry.optJSONObject("team")?.optString("shortDisplayName", "")
                                ?.ifBlank { teamEntry.optJSONObject("team")?.optString("displayName", "").orEmpty() }
                                .orEmpty()
                            val score = teamEntry.optString("score", "")
                            if (teamEntry.optString("homeAway") == "home") {
                                home = teamName
                                homeScore = score
                            } else {
                                away = teamName
                                awayScore = score
                            }
                        }
                        if (home.isBlank() || away.isBlank()) continue
                        val statusType = ev.optJSONObject("status")?.optJSONObject("type")
                        val state = statusType?.optString("state", "pre").orEmpty()
                        val shortDetail = statusType?.optString("shortDetail", kickOff).orEmpty()
                        val scoreLabel = if (state == "pre") "vs" else "$homeScore - $awayScore"

                        val broadcasts = comp.optJSONArray("broadcasts")
                        val rawBroadcast = broadcasts?.optJSONObject(0)?.optJSONArray("names")?.optString(0, "").orEmpty()
                        val (broadcaster, tvAppId, chNum) = inferUkBroadcasterAndChannel(leagueTitle, rawBroadcast, iboMappings)

                        results.add(
                            FootballMatchFixture(
                                id = "foot_$id",
                                homeTeam = home,
                                awayTeam = away,
                                competition = leagueTitle,
                                dateIso = dateIso,
                                kickOffTimeUk = kickOff,
                                statusLabel = if (state == "pre") kickOff else shortDetail,
                                scoreLabel = scoreLabel,
                                ukTvBroadcaster = broadcaster,
                                tvAppId = tvAppId,
                                iboChannelNumber = chNum
                            )
                        )
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
        }

        val cleanFollowed = followedTeams.map { it.trim() }.filter { it.isNotBlank() }
        val filteredByTeam = if (cleanFollowed.isEmpty()) {
            results
        } else {
            results.filter { m ->
                cleanFollowed.any { t ->
                    m.homeTeam.contains(t, ignoreCase = true) || m.awayTeam.contains(t, ignoreCase = true)
                }
            }
        }

        filteredByTeam.sortedWith(compareBy<FootballMatchFixture> { it.dateIso }.thenBy { it.kickOffTimeUk })
    }
}

object NextEpisodeDirectoryClient {
    private val accentColors = listOf(
        0xFF00E5FFL,
        0xFFFF006EL,
        0xFF00E676L,
        0xFFFFB300L,
        0xFFE50914L,
        0xFFC084FCL
    )

    fun mapNetworkToLgTvAppId(networkName: String, titleHint: String = ""): Pair<String, String> {
        val lower = "$networkName $titleHint".lowercase(Locale.US)
        val contentId = ""
        val appId = when {
            lower.contains("apple") || lower.contains("ted lasso") || lower.contains("slow horses") ||
                    lower.contains("severance") || lower.contains("silo") || lower.contains("shrinking") ||
                    lower.contains("foundation") || lower.contains("morning show") || lower.contains("bad sisters") ->
                "com.apple.appletv"
            lower.contains("bbc") || lower.contains("iplayer") || lower.contains("eastenders") ||
                    lower.contains("traitors") || lower.contains("doctor who") || lower.contains("line of duty") ||
                    lower.contains("peaky blinders") || lower.contains("strictly") ->
                "bbc"
            lower.contains("netflix") || lower.contains("stranger things") || lower.contains("black mirror") ||
                    lower.contains("squid game") || lower.contains("bridgerton") || lower.contains("wednesday") ||
                    lower.contains("night agent") || lower.contains("crown") || lower.contains("ozark") ->
                "netflix"
            lower.contains("itv") || lower.contains("coronation street") || lower.contains("emmerdale") ||
                    lower.contains("love island") || lower.contains("vera") || lower.contains("unforgotten") ->
                "itv.hub"
            lower.contains("channel 4") || lower.contains("all 4") || lower.contains("e4") ||
                    lower.contains("film4") || lower.contains("bake off") || lower.contains("gogglebox") ||
                    lower.contains("taskmaster") ->
                "all4"
            lower.contains("disney") || lower.contains("hulu") || lower.contains("fx") ||
                    lower.contains("mandalorian") || lower.contains("bear") || lower.contains("shogun") ||
                    lower.contains("andor") || lower.contains("loki") || lower.contains("only murders") ->
                "com.disney.disneyplus-prod"
            lower.contains("prime") || lower.contains("amazon") || lower.contains("reacher") ||
                    lower.contains("clarkson") || lower.contains("boys") || lower.contains("fallout") ||
                    lower.contains("rings of power") || lower.contains("jack ryan") || lower.contains("grand tour") ->
                "lovefilm.uk"
            lower.contains("sky") || lower.contains("hbo") || lower.contains("max") ||
                    lower.contains("house of the dragon") || lower.contains("last of us") ||
                    lower.contains("white lotus") || lower.contains("day of the jackal") ->
                "com.ibo.pro"
            lower.contains("youtube") ->
                "youtube.leanback.v4"
            else ->
                "com.webos.app.livetv"
        }
        return appId to contentId
    }

    fun normalizeStreamerName(networkOrStreamer: String, appIdHint: String = ""): String {
        val combinedLower = "${networkOrStreamer.trim()} ${appIdHint.trim()}".lowercase(Locale.US)
        return when {
            combinedLower.contains("bbc") || combinedLower.contains("iplayer") -> "BBC iPlayer"
            combinedLower.contains("apple") -> "Apple TV+"
            combinedLower.contains("netflix") -> "Netflix"
            combinedLower.contains("disney") || combinedLower.contains("hulu") || combinedLower.contains("fx") -> "Disney+"
            combinedLower.contains("prime") || combinedLower.contains("amazon") || combinedLower.contains("lovefilm") -> "Prime Video"
            combinedLower.contains("itv") -> "ITVX"
            combinedLower.contains("channel 4") || combinedLower.contains("all 4") || combinedLower.contains("all4") || combinedLower.contains("e4") -> "Channel 4"
            combinedLower.contains("sky") || combinedLower.contains("hbo") || combinedLower.contains("max") -> "Sky / NOW"
            combinedLower.contains("paramount") -> "Paramount+"
            combinedLower.contains("youtube") -> "YouTube"
            else -> networkOrStreamer.trim().ifBlank { "TV Series" }
        }
    }

    fun resolveStreamingBadgeLabel(appIdHint: String, networkOrStreamer: String): String =
        normalizeStreamerName(networkOrStreamer, appIdHint).take(15)

    private fun formatEpisodeCode(season: Int, number: Int): String {
        if (season <= 0 && number <= 0) return ""
        return "S%02dE%02d".format(Locale.US, season.coerceAtLeast(1), number.coerceAtLeast(1))
    }

    private fun parseAirDateLabel(airdate: String, airtime: String): Pair<String, Long> {
        if (airdate.isBlank()) return "TBA" to 0L
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val parsed = sdf.parse(airdate) ?: return airdate to 0L
            val calAir = java.util.Calendar.getInstance().apply { time = parsed }
            if (airtime.contains(":")) {
                val h = airtime.substringBefore(":").toIntOrNull() ?: 20
                val m = airtime.substringAfter(":").take(2).toIntOrNull() ?: 0
                calAir.set(java.util.Calendar.HOUR_OF_DAY, h)
                calAir.set(java.util.Calendar.MINUTE, m)
            }
            val calNow = java.util.Calendar.getInstance()
            val sameYear = calNow.get(java.util.Calendar.YEAR) == calAir.get(java.util.Calendar.YEAR)
            val dayDiff = calAir.get(java.util.Calendar.DAY_OF_YEAR) - calNow.get(java.util.Calendar.DAY_OF_YEAR)
            val timeSuffix = if (airtime.isNotBlank()) " $airtime" else ""
            val label = when {
                sameYear && dayDiff == 0 -> "Today$timeSuffix"
                sameYear && dayDiff == 1 -> "Tomorrow$timeSuffix"
                sameYear && dayDiff in 2..6 -> {
                    val dayFmt = java.text.SimpleDateFormat("EEE", Locale.getDefault())
                    "${dayFmt.format(parsed)}$timeSuffix"
                }
                else -> {
                    val outFmt = java.text.SimpleDateFormat("d MMM", Locale.getDefault())
                    "${outFmt.format(parsed)}$timeSuffix"
                }
            }
            label to calAir.timeInMillis
        } catch (_: Exception) {
            airdate to 0L
        }
    }

    private fun parseTvMazeShowObject(showObj: org.json.JSONObject, embeddedNextEp: org.json.JSONObject? = null): TrackedMediaShow? {
        val showId = showObj.optInt("id", 0)
        val name = showObj.optString("name", "").trim()
        if (showId <= 0 || name.isBlank()) return null

        val status = showObj.optString("status", "Running").trim()
        val networkObj = showObj.optJSONObject("network") ?: showObj.optJSONObject("webChannel")
        val networkName = networkObj?.optString("name", "")?.trim().orEmpty().ifBlank { "TV Series" }
        val scheduleObj = showObj.optJSONObject("schedule")
        val scheduleTime = scheduleObj?.optString("time", "").orEmpty()
        val daysArr = scheduleObj?.optJSONArray("days")
        val scheduleDays = buildList {
            if (daysArr != null) {
                for (i in 0 until daysArr.length()) {
                    daysArr.optString(i, "").take(3).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }.joinToString(",")

        val imageObj = showObj.optJSONObject("image")
        val artwork = imageObj?.optString("medium", imageObj.optString("original", ""))
            .orEmpty()
            .replace("http://", "https://")

        val rawSummary = showObj.optString("summary", "")
            .replace(Regex("<[^>]*>"), "")
            .trim()

        val embedded = showObj.optJSONObject("_embedded")
        val nextEp = embeddedNextEp
            ?: embedded?.optJSONObject("nextepisode")
            ?: embedded?.optJSONArray("nextepisode")?.optJSONObject(0)
        val prevEp = embedded?.optJSONObject("previousepisode")

        val epToUse = nextEp ?: prevEp
        val epCode = if (epToUse != null) {
            formatEpisodeCode(epToUse.optInt("season", 1), epToUse.optInt("number", 1))
        } else if (scheduleDays.isNotBlank()) {
            "$scheduleDays $scheduleTime".trim()
        } else {
            status
        }
        val epTitle = epToUse?.optString("name", "")?.trim().orEmpty()
        val (airLabel, airEpoch) = if (nextEp != null) {
            parseAirDateLabel(nextEp.optString("airdate", ""), nextEp.optString("airtime", scheduleTime))
        } else if (scheduleDays.isNotBlank() && status.equals("Running", ignoreCase = true)) {
            "$scheduleDays $scheduleTime".trim() to 0L
        } else if (prevEp != null) {
            "Last: ${parseAirDateLabel(prevEp.optString("airdate", ""), "").first}" to 0L
        } else {
            status to 0L
        }

        val (appIdHint, contentIdHint) = mapNetworkToLgTvAppId(networkName, name)
        val normalizedStreamer = normalizeStreamerName(networkName, appIdHint)
        return TrackedMediaShow(
            id = "tvmaze_$showId",
            externalId = showId.toString(),
            title = name,
            kind = TrackedMediaKind.TV_SHOW,
            networkOrStreamer = normalizedStreamer,
            statusText = status,
            nextEpisodeCode = epCode.ifBlank { "Series" },
            nextEpisodeTitle = epTitle,
            nextAirDateLabel = airLabel,
            airTimestampEpochMs = airEpoch,
            artworkUrl = artwork,
            summarySnippet = rawSummary,
            appIdHint = appIdHint,
            contentIdHint = contentIdHint,
            accentColorHex = accentColors[showId % accentColors.size]
        )
    }

    /**
     * Refreshes live next-episode air dates & episode codes (`SxxExx`) for the user's tracked TV shows.
     */
    suspend fun refreshTrackedShowsSchedule(shows: List<TrackedMediaShow>): List<TrackedMediaShow> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            shows.map { item ->
                if (item.kind != TrackedMediaKind.TV_SHOW || item.externalId.isBlank() || !item.externalId.all { it.isDigit() }) {
                    return@map item
                }
                try {
                    val url = "https://api.tvmaze.com/shows/${item.externalId}?embed[]=nextepisode&embed[]=previousepisode"
                    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 3500
                        readTimeout = 3500
                        setRequestProperty("Accept", "application/json")
                    }
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        conn.disconnect()
                        val parsed = parseTvMazeShowObject(org.json.JSONObject(body))
                        if (parsed != null) {
                            return@map parsed.copy(
                                id = item.id,
                                lastWatchedEpisodeCode = item.lastWatchedEpisodeCode,
                                appIdHint = item.appIdHint.ifBlank { parsed.appIdHint },
                                contentIdHint = item.contentIdHint.ifBlank { parsed.contentIdHint }
                            )
                        }
                    } else {
                        conn.disconnect()
                    }
                } catch (_: Exception) {
                }
                item
            }
        }

    /**
     * Fetches Today's UK TV Schedule (`https://api.tvmaze.com/schedule?country=GB`) so the user has
     * a live daily feed of episodes airing today.
     */
    suspend fun fetchUkDailySchedule(): List<TrackedMediaShow> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val list = mutableListOf<TrackedMediaShow>()
            try {
                val conn = (java.net.URL("https://api.tvmaze.com/schedule?country=GB").openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val arr = org.json.JSONArray(body)
                    val seenShowIds = HashSet<Int>()
                    for (i in 0 until arr.length()) {
                        val epObj = arr.optJSONObject(i) ?: continue
                        val showObj = epObj.optJSONObject("show") ?: continue
                        val showId = showObj.optInt("id", 0)
                        if (showId <= 0 || !seenShowIds.add(showId)) continue
                        parseTvMazeShowObject(showObj, embeddedNextEp = epObj)?.let {
                            list.add(it)
                        }
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
            (DEFAULT_TRACKED_MEDIA_SHOWS + list).distinctBy { it.title.lowercase(Locale.US) }.take(40)
        }

    /**
     * Searches both TV Series (via TVMaze API) and Movies (via iTunes Movie Search API) with zero API keys.
     */
    suspend fun searchShowsAndMovies(
        query: String,
        includeTv: Boolean = true,
        includeMovies: Boolean = true
    ): List<TrackedMediaShow> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) {
            val schedule = fetchUkDailySchedule()
            return@withContext schedule.filter {
                (includeTv && it.kind == TrackedMediaKind.TV_SHOW) ||
                        (includeMovies && it.kind == TrackedMediaKind.MOVIE)
            }
        }
        val encoded = java.net.URLEncoder.encode(clean, "UTF-8")
        val results = mutableListOf<TrackedMediaShow>()

        if (includeTv) {
            try {
                val conn = (java.net.URL("https://api.tvmaze.com/search/shows?q=$encoded").openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val arr = org.json.JSONArray(body)
                    for (i in 0 until arr.length().coerceAtMost(18)) {
                        val showObj = arr.optJSONObject(i)?.optJSONObject("show") ?: continue
                        parseTvMazeShowObject(showObj)?.let { results.add(it) }
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
        }

        if (includeMovies) {
            try {
                val movieUrl = "https://itunes.apple.com/search?term=$encoded&country=gb&media=movie&entity=movie&limit=14"
                val conn = (java.net.URL(movieUrl).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val arr = org.json.JSONObject(body).optJSONArray("results") ?: org.json.JSONArray()
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        val trackId = m.optLong("trackId", 0L)
                        val title = m.optString("trackName", "").trim()
                        if (title.isBlank()) continue
                        val genre = m.optString("primaryGenreName", "Movie").trim()
                        val director = m.optString("artistName", "").trim()
                        val releaseRaw = m.optString("releaseDate", "").substringBefore("T")
                        val year = releaseRaw.take(4)
                        val art = m.optString("artworkUrl100", "").replace("100x100bb", "400x400bb")
                        val desc = m.optString("longDescription", m.optString("shortDescription", "")).trim()
                        results.add(
                            TrackedMediaShow(
                                id = "movie_${trackId}_$i",
                                externalId = trackId.toString(),
                                title = title,
                                kind = TrackedMediaKind.MOVIE,
                                networkOrStreamer = if (director.isNotBlank()) "$genre \u2022 $director" else genre,
                                statusText = "Movie",
                                nextEpisodeCode = if (year.isNotBlank()) "Film ($year)" else "Feature Film",
                                nextEpisodeTitle = genre,
                                nextAirDateLabel = if (releaseRaw.isNotBlank()) "Released $year" else "Movie",
                                artworkUrl = art,
                                summarySnippet = desc,
                                appIdHint = "com.apple.appletv",
                                accentColorHex = 0xFFFFB300L
                            )
                        )
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
        }
        results
    }
}

// ---------------------------------------------------------------------------
// MOVABLE & RESIZABLE DASHBOARD CARD SLOTS
// ---------------------------------------------------------------------------

val DeviceCapabilityDescriptor.origin: CapabilityOrigin
    get() = if (dpId.isNotBlank() || !isWritable || currentValue.isNotBlank()) {
        CapabilityOrigin.HARDWARE_REPORTED
    } else {
        CapabilityOrigin.DISCOVERY_PROBED
    }

fun StateAuthorityTier.canOverwrite(
    existingTier: StateAuthorityTier,
    isPendingAckLocked: Boolean
): Boolean {
    if (isPendingAckLocked && this != StateAuthorityTier.DEVICE_HARDWARE_TRUTH) {
        return false
    }
    return this.ordinal <= existingTier.ordinal
}



val SmartTile.subtype: DeviceSubtype
    get() = deviceSubtype

val SmartTile.deviceId: String
    get() = serialNumber.ifBlank { entityId }

val SmartTile.hasDualSockets: Boolean
    get() = multiGangSocketCount >= 2

fun SmartTile.resolvedCanonicalGuid(): String {
    val rawId = serialNumber.ifBlank { entityId }.ifBlank { id }
    val prov = activeProviderId.ifBlank { resolvedProviderId.id }
    return CrossEcosystemIdentityCorrelator.resolveCanonicalGuid(
        activeProviderId = prov,
        rawDeviceId = rawId,
        macAddress = macAddress,
        hardwareSerial = serialNumber
    )
}

fun SmartTile.multiGangSocketCapabilities(): List<DeviceCapabilityDescriptor> {
    return capabilities
        .filter { cap ->
            cap.key.matches(Regex("^socket_\\d+$", RegexOption.IGNORE_CASE)) ||
                    cap.key.matches(Regex("^switch_\\d+$", RegexOption.IGNORE_CASE))
        }
        .sortedBy { cap ->
            cap.key.substringAfterLast("_").toIntOrNull() ?: 1
        }
}

fun SmartTile.socketCount(): Int {
    val dynamicCount = multiGangSocketCapabilities().size
    if (dynamicCount > 0) return dynamicCount
    return multiGangSocketCount.coerceAtLeast(1)
}

fun SmartTile.isSocketActive(socketIndexOneBased: Int): Boolean {
    val key = "socket_${socketIndexOneBased.coerceAtLeast(1)}"
    val descriptor = capabilities.firstOrNull { it.key.equals(key, ignoreCase = true) }
        ?: capabilities.firstOrNull { it.key.equals("switch_$socketIndexOneBased", ignoreCase = true) }
    if (descriptor != null) {
        return descriptor.currentValue.equals("true", ignoreCase = true) ||
                descriptor.currentValue.equals("on", ignoreCase = true) ||
                descriptor.currentValue == "1"
    }
    return when (socketIndexOneBased) {
        1 -> isActive
        2 -> isSocket2Active
        else -> false
    }
}

fun SmartTile.withUpdatedSocketState(
    socketIndexOneBased: Int,
    active: Boolean,
    @Suppress("UNUSED_PARAMETER") origin: CapabilityOrigin = CapabilityOrigin.HARDWARE_REPORTED,
    labelOverride: String? = null
): SmartTile {
    val idx = socketIndexOneBased.coerceAtLeast(1)
    val key = "socket_$idx"
    val updatedCaps = capabilities.toMutableList()
    val existingIdx = updatedCaps.indexOfFirst {
        it.key.equals(key, ignoreCase = true) || it.key.equals("switch_$idx", ignoreCase = true)
    }
    val defaultLabel = labelOverride ?: when (idx) {
        1 -> socket1Name.ifBlank { "Socket 1" }
        2 -> socket2Name.ifBlank { "Socket 2" }
        else -> "Socket $idx"
    }
    val updatedDescriptor = if (existingIdx >= 0) {
        updatedCaps[existingIdx].copy(
            key = key,
            label = labelOverride ?: updatedCaps[existingIdx].label.ifBlank { defaultLabel },
            currentValue = active.toString()
        )
    } else {
        DeviceCapabilityDescriptor(
            key = key,
            label = defaultLabel,
            kind = DeviceCapabilityKind.BOOLEAN_SWITCH,
            currentValue = active.toString()
        )
    }
    if (existingIdx >= 0) {
        updatedCaps[existingIdx] = updatedDescriptor
    } else {
        updatedCaps.add(updatedDescriptor)
    }

    val anySocketOn = updatedCaps
        .filter { it.key.startsWith("socket_") || it.key.startsWith("switch_") }
        .any { it.currentValue.equals("true", ignoreCase = true) || it.currentValue == "1" }
    val newSocketCount = maxOf(
        multiGangSocketCount,
        updatedCaps.count { it.key.startsWith("socket_") || it.key.startsWith("switch_") },
        idx
    )

    return copy(
        isActive = if (idx == 1) active else (anySocketOn || isActive),
        isSocket2Active = if (idx == 2) active else isSocket2Active,
        multiGangSocketCount = newSocketCount,
        capabilities = updatedCaps
    )
}

fun SmartTile.hardwareAmbientTempCelsiusOrNull(): Float? {
    val cap = capabilities.firstOrNull {
        it.key == "ambient_temperature" || it.key == "current_temperature" || it.key == "temperature" || it.key == "temp_current"
    } ?: return null
    return cap.currentValue.toFloatOrNull()
}

fun SmartTile.hardwareTargetTempCelsiusOrNull(): Float? {
    val cap = capabilities.firstOrNull {
        it.key == "target_temperature" || it.key == "setpoint_temperature" || it.key == "thermostat_setpoint" || it.key == "temp_set"
    } ?: return null
    return cap.currentValue.toFloatOrNull()
}

fun SmartTile.hardwareHumidityPctOrNull(): Int? {
    val cap = capabilities.firstOrNull {
        it.key == "humidity" || it.key == "ambient_humidity"
    } ?: return null
    return cap.currentValue.toFloatOrNull()?.toInt()
}

// TuyaApiClient provisioning extensions (F-26, F-27, F-32)
private val tuyaSequentialProvisioningMutex = kotlinx.coroutines.sync.Mutex()

val TuyaApiClient.AUTO_ONBOARD_RSSI_THRESHOLD_DBM: Int
    get() = -78

fun TuyaApiClient.isValid24GhzProvisioningSsid(ssid: String, frequencyMhz: Int = 2412): Boolean {
    val clean = ssid.trim().removeSurrounding("\"")
    if (clean.isEmpty() || clean.equals("<unknown ssid>", ignoreCase = true)) return false
    if (frequencyMhz in 4900..5900) return false
    val upper = clean.uppercase(Locale.US)
    return !upper.endsWith("_5G") && !upper.endsWith("-5G") && !upper.endsWith(" 5G") && !upper.contains("5GHZ")
}

fun TuyaApiClient.hardwareSuffix4Char(macOrDeviceId: String): String {
    val clean = macOrDeviceId.trim().uppercase(Locale.US)
        .replace(":", "")
        .replace("-", "")
        .filter { it.isLetterOrDigit() }
    return if (clean.length >= 4) clean.takeLast(4) else clean.padStart(4, '0')
}

suspend fun <T> TuyaApiClient.withSequentialProvisioningLock(block: suspend () -> T): T {
    tuyaSequentialProvisioningMutex.lock()
    return try {
        block()
    } finally {
        tuyaSequentialProvisioningMutex.unlock()
    }
}

// ---------------------------------------------------------------------------
// STAGE 5: FORMAL 10-STATE DEVICE LIFECYCLE STATE MACHINE
// ---------------------------------------------------------------------------

interface SmartHomeProviderAdapter {
    val providerId: String
    val typedProviderId: ProviderId
        get() = ProviderId.fromRawKey(providerId)
    val accountKey: String
        get() = providerId
    val displayName: String
    suspend fun ensureAuthenticated(context: android.content.Context, bridge: BridgeSettings): Boolean = true
    fun inspectAccountHealth(context: android.content.Context, bridge: BridgeSettings): AccountHealthRecord =
        AccountHealthRecord(accountKey = accountKey, displayName = displayName, oauthStatus = AccountAuthStatus.VALID)
    suspend fun discoverDevices(context: android.content.Context, bridge: BridgeSettings): List<SmartTile>
    suspend fun syncLiveStates(context: android.content.Context, bridge: BridgeSettings, tiles: MutableList<SmartTile>): Boolean
    suspend fun dispatchCommand(context: android.content.Context, bridge: BridgeSettings, tile: SmartTile): Pair<Boolean, String>
}


fun defaultSafetyTierForCapability(key: String): CapabilitySafetyTier {
    val cleanKey = key.lowercase(Locale.US)
    return when {
        cleanKey in setOf("lock", "lock_state", "child_lock", "vehicle_lock", "garage_door") ->
            CapabilitySafetyTier.SECURITY_CRITICAL
        cleanKey in setOf("boil", "temp_boiling", "cook_temperature", "start") ->
            CapabilitySafetyTier.HIGH_LOAD_APPLIANCE
        cleanKey in setOf("temp_set", "set_temp", "target_temperature", "target_temp", "hvac_mode") ->
            CapabilitySafetyTier.CLIMATE_HOLDABLE
        else ->
            CapabilitySafetyTier.STANDARD
    }
}

fun defaultHoldDurationMsForCapability(key: String): Long {
    return when (defaultSafetyTierForCapability(key)) {
        CapabilitySafetyTier.SECURITY_CRITICAL -> 60 * 60_000L
        CapabilitySafetyTier.CLIMATE_HOLDABLE,
        CapabilitySafetyTier.HIGH_LOAD_APPLIANCE -> 30 * 60_000L
        CapabilitySafetyTier.STANDARD -> 15 * 60_000L
    }
}

fun defaultRendererTypeForCapability(
    kind: DeviceCapabilityKind,
    key: String
): CapabilityRendererType {
    val cleanKey = key.lowercase(Locale.US)
    return when (kind) {
        DeviceCapabilityKind.BOOLEAN_SWITCH,
        DeviceCapabilityKind.ACTION_TRIGGER -> CapabilityRendererType.TOGGLE
        DeviceCapabilityKind.ENUM_MODE -> when {
            cleanKey in listOf("hdmi_input", "input_source", "source", "hdmi") ->
                CapabilityRendererType.INPUT_SELECTOR
            else ->
                CapabilityRendererType.CHIP_GROUP
        }
        DeviceCapabilityKind.COUNTDOWN_TIMER -> CapabilityRendererType.CHIP_GROUP
        DeviceCapabilityKind.READ_ONLY_SENSOR -> when {
            cleanKey in listOf("cur_power", "power", "wattage", "power_consumption", "add_ele", "energy") ->
                CapabilityRendererType.ENERGY_GRAPH
            else ->
                CapabilityRendererType.SENSOR_BADGE
        }
        DeviceCapabilityKind.NUMERIC_SLIDER -> when {
            cleanKey in listOf("hdmi_input", "input_source", "hdmi") ->
                CapabilityRendererType.INPUT_SELECTOR
            cleanKey in listOf("fan_speed", "fan_speed_percent", "windspeed", "speed", "level") ->
                CapabilityRendererType.FAN_SPEED_STEPPER
            cleanKey in listOf("colour_data", "colour_data_v2", "color_data", "rgb_color") ->
                CapabilityRendererType.COLOR_WHEEL
            cleanKey in listOf("target_temperature", "temp_set", "set_temp", "cook_temperature", "temp_boiling", "warm_temp", "upper_temp", "temperature") ||
                    (cleanKey.endsWith("_temp") && !cleanKey.contains("color") && !cleanKey.contains("colour")) ->
                CapabilityRendererType.TEMPERATURE_DIAL
            cleanKey in listOf("position", "lift", "percent_control", "control_back") ->
                CapabilityRendererType.COVER_POSITION_SLIDER
            cleanKey in listOf("speaker_volume", "volume", "vol", "sound_volume") ->
                CapabilityRendererType.VOLUME_SLIDER
            cleanKey in listOf("brightness", "brightness_pct", "bright_value", "bright_value_v2", "temp_value", "temp_value_v2") ->
                CapabilityRendererType.BRIGHTNESS_SLIDER
            else ->
                CapabilityRendererType.SLIDER
        }
    }
}




// ---------------------------------------------------------------------------
// SECURITY CENTER & CAMERA AI INTELLIGENCE ENUMS
// ---------------------------------------------------------------------------

object AppliancePowerFingerprinter {
    fun inferApplianceFromTelemetry(
        powerWatts: Float,
        titleHint: String = "",
        previous: ApplianceFingerprintCategory = ApplianceFingerprintCategory.UNKNOWN
    ): ApplianceFingerprintCategory {
        val lower = titleHint.lowercase(Locale.US)
        when {
            lower.contains("kettle") || lower.contains("boiler") -> return ApplianceFingerprintCategory.KETTLE
            lower.contains("heater") || lower.contains("panel") || lower.contains("radiator") || lower.contains("warmer") ->
                return ApplianceFingerprintCategory.SPACE_HEATER
            lower.contains("wash") || lower.contains("dryer") || lower.contains("laundry") ->
                return ApplianceFingerprintCategory.WASHING_MACHINE
            lower.contains("pond") || lower.contains("pump") || lower.contains("fountain") ->
                return ApplianceFingerprintCategory.POND_PUMP
            lower.contains("tv") || lower.contains("playstation") || lower.contains("xbox") ->
                return ApplianceFingerprintCategory.TV_CONSOLES
            lower.contains("lamp") || lower.contains("light") -> return ApplianceFingerprintCategory.LIGHTING
        }
        // Do not persist a previous WASHING_MACHINE guess if the title doesn't mention laundry/washer
        val sanitizedPrev = if (previous == ApplianceFingerprintCategory.WASHING_MACHINE) {
            ApplianceFingerprintCategory.UNKNOWN
        } else {
            previous
        }
        if (powerWatts < 2.5f) return sanitizedPrev
        return when {
            powerWatts >= 3100f -> ApplianceFingerprintCategory.EV_CHARGER
            powerWatts >= 2150f -> ApplianceFingerprintCategory.KETTLE
            powerWatts >= 280f -> ApplianceFingerprintCategory.SPACE_HEATER
            powerWatts >= 76f -> ApplianceFingerprintCategory.TV_CONSOLES
            powerWatts >= 39f -> ApplianceFingerprintCategory.POND_PUMP
            powerWatts >= 12f -> ApplianceFingerprintCategory.LIGHTING
            else -> ApplianceFingerprintCategory.PHONE_CHARGER
        }
    }
}

// ---------------------------------------------------------------------------
// AUTOMATIC DISCOVERY PROTOCOLS & DEVICE CAPABILITY METADATA
// ---------------------------------------------------------------------------

fun TileType.isSwitchType(): Boolean =
    this == TileType.SMARTKNIGHT_PLUG ||
            this == TileType.SMARTLIFE_DEVICE ||
            this == TileType.TAPO_DEVICE ||
            this == TileType.SHELLY_RELAY ||
            this == TileType.SONOFF_SWITCH ||
            this == TileType.MATTER_DEVICE ||
            this == TileType.MQTT_DEVICE

fun TileType.isTowerFanType(): Boolean =
    this == TileType.PROBREEZE_FAN

fun TileType.isVehicleType(): Boolean =
    this == TileType.JAGUAR_VEHICLE || this == TileType.MERCEDES_VEHICLE

fun TileType.isLytmiType(): Boolean =
    this == TileType.LYTMI_SYNC_BOX

fun TileType.isAmbientRgbType(): Boolean =
    this == TileType.LYTMI_SYNC_BOX ||
            this == TileType.GOVEE_LIGHT ||
            this == TileType.TWINKLY_LIGHT

fun TileType.isSpeakerType(): Boolean =
    this == TileType.NEST_MINI || this == TileType.MUSIC_PLAYER

fun TileType.toSemanticDeviceClass(hasPowerMeter: Boolean = false): DeviceClass = when (this) {
    TileType.NEST_THERMOSTAT,
    TileType.TUYA_RADIATOR,
    TileType.PROBREEZE_FAN -> DeviceClass.CLIMATE
    TileType.HUE_LIGHT,
    TileType.GOVEE_LIGHT,
    TileType.TWINKLY_LIGHT -> DeviceClass.LIGHT
    TileType.ENERGY_MONITOR -> DeviceClass.ENERGY
    TileType.SMARTKNIGHT_PLUG,
    TileType.SMARTLIFE_DEVICE,
    TileType.TAPO_DEVICE,
    TileType.SHELLY_RELAY,
    TileType.SONOFF_SWITCH,
    TileType.MATTER_DEVICE,
    TileType.MQTT_DEVICE -> if (hasPowerMeter) DeviceClass.ENERGY else DeviceClass.SWITCH
    TileType.LYTMI_SYNC_BOX,
    TileType.LG_TV,
    TileType.NEST_MINI,
    TileType.MUSIC_PLAYER -> DeviceClass.MEDIA
    TileType.IMOU_CAMERA,
    TileType.EZVIZ_DOORBELL,
    TileType.NEST_DOORBELL,
    TileType.RING_CAMERA -> DeviceClass.CAMERA
    TileType.SMART_LOCK,
    TileType.SMART_COVER,
    TileType.SECURITY_CENTER -> DeviceClass.SECURITY
    TileType.JAGUAR_VEHICLE,
    TileType.MERCEDES_VEHICLE -> DeviceClass.VEHICLE
    TileType.SMART_SENSOR -> DeviceClass.SENSOR
    TileType.AIRROBO_VACUUM -> DeviceClass.APPLIANCE
    TileType.GROUP_CARD,
    TileType.MACRO_TILE,
    TileType.JARVIS_SCENES,
    TileType.CLOCK_WEATHER,
    TileType.PHOTO_SLIDESHOW -> DeviceClass.NETWORK
}

fun TileType.toDefaultServiceClass(sensorUnitHint: String = ""): ServiceClass? = when {
    sensorUnitHint.equals("RADIO_HERO", ignoreCase = true) -> ServiceClass.RADIO
    this == TileType.MUSIC_PLAYER -> ServiceClass.MUSIC
    this == TileType.CLOCK_WEATHER -> ServiceClass.WEATHER
    else -> null
}

fun TileType.toDefaultDeviceSubtype(
    titleHint: String = "",
    ipAddressHint: String = "",
    sensorUnitHint: String = "",
    fingerprintCategory: ApplianceFingerprintCategory = ApplianceFingerprintCategory.UNKNOWN
): DeviceSubtype {
    if (sensorUnitHint.equals("RADIO_HERO", ignoreCase = true)) return DeviceSubtype.RADIO
    val lower = titleHint.lowercase(Locale.US)
    return when (this) {
        TileType.NEST_THERMOSTAT -> DeviceSubtype.THERMOSTAT
        TileType.TUYA_RADIATOR -> if (lower.contains("trv") || lower.contains("valve")) DeviceSubtype.TRV else DeviceSubtype.RADIATOR
        TileType.PROBREEZE_FAN -> DeviceSubtype.TOWER_FAN
        TileType.LYTMI_SYNC_BOX -> DeviceSubtype.AMBIENT_SYNC
        TileType.GOVEE_LIGHT,
        TileType.TWINKLY_LIGHT -> if (lower.contains("sync")) DeviceSubtype.AMBIENT_SYNC else DeviceSubtype.LED_STRIP
        TileType.HUE_LIGHT -> if (lower.contains("strip")) DeviceSubtype.LED_STRIP else DeviceSubtype.BULB
        TileType.LG_TV -> DeviceSubtype.TV
        TileType.NEST_MINI -> if (ipAddressHint.contains(",") || lower.contains("group") || lower.contains("speakers")) {
            DeviceSubtype.CAST_GROUP
        } else {
            DeviceSubtype.SPEAKER
        }
        TileType.MUSIC_PLAYER -> DeviceSubtype.MUSIC_PLAYER
        TileType.EZVIZ_DOORBELL,
        TileType.NEST_DOORBELL -> DeviceSubtype.DOORBELL
        TileType.IMOU_CAMERA,
        TileType.RING_CAMERA -> if (lower.contains("doorbell")) DeviceSubtype.DOORBELL else DeviceSubtype.CAMERA
        TileType.SMART_LOCK -> DeviceSubtype.LOCK
        TileType.SMART_COVER -> if (lower.contains("garage") || lower.contains("gate")) DeviceSubtype.GARAGE_DOOR else DeviceSubtype.BLIND
        TileType.SECURITY_CENTER -> DeviceSubtype.SECURITY_HUB
        TileType.JAGUAR_VEHICLE,
        TileType.MERCEDES_VEHICLE -> DeviceSubtype.VEHICLE
        TileType.AIRROBO_VACUUM -> DeviceSubtype.VACUUM
        TileType.SMART_SENSOR -> DeviceSubtype.SENSOR
        TileType.GROUP_CARD -> DeviceSubtype.GROUP
        TileType.SHELLY_RELAY -> DeviceSubtype.RELAY
        TileType.MATTER_DEVICE -> when {
            lower.contains("border router") || lower.contains("thread") -> DeviceSubtype.THREAD_BORDER_ROUTER
            lower.contains("bridge") -> DeviceSubtype.MATTER_BRIDGE
            else -> fingerprintCategory.toDeviceSubtype() ?: DeviceSubtype.SMART_PLUG
        }
        TileType.MQTT_DEVICE -> when {
            lower.contains("broker") -> DeviceSubtype.MQTT_BROKER
            else -> fingerprintCategory.toDeviceSubtype() ?: DeviceSubtype.RELAY
        }
        TileType.SMARTKNIGHT_PLUG,
        TileType.SMARTLIFE_DEVICE,
        TileType.TAPO_DEVICE,
        TileType.SONOFF_SWITCH -> {
            when {
                lower.contains("air fryer") || lower.contains("airfryer") -> DeviceSubtype.AIR_FRYER
                lower.contains("coffee") || lower.contains("espresso") -> DeviceSubtype.COFFEE_MACHINE
                lower.contains("kettle") -> DeviceSubtype.KETTLE
                lower.contains("washer") || lower.contains("washing") || lower.contains("dryer") -> DeviceSubtype.WASHER
                lower.contains("ev charger") || lower.contains("wallbox") -> DeviceSubtype.EV_CHARGER
                lower.contains("wall socket") || lower.contains("outlet") -> DeviceSubtype.WALL_SOCKET
                else -> fingerprintCategory.toDeviceSubtype() ?: DeviceSubtype.SMART_PLUG
            }
        }
        else -> DeviceSubtype.GENERIC
    }
}


object LiveRadioDirectoryClient {
    private val accentPalette = listOf(
        0xFF00E5FFL,
        0xFFFF4081L,
        0xFF00E676L,
        0xFFFFB300L,
        0xFFE040FBL,
        0xFF58A6FFL,
        0xFFFF8A00L,
        0xFF2DD4BFL
    )

    val builtInCatalog: List<RadioStationStream> = DEFAULT_RADIO_STATIONS + listOf(
        RadioStationStream(
            id = "dir_virgin_uk",
            name = "Virgin Radio UK",
            genreOrTagline = "Rock, Pop & Indie Anthems \u2022 UK",
            streamUrl = "https://radio.virginradio.co.uk/stream",
            accentColorHex = 0xFFFF5252L,
            badgeCode = "VRG"
        ),
        RadioStationStream(
            id = "dir_talksport_uk",
            name = "talkSPORT UK",
            genreOrTagline = "Live Premier League & Sports Talk \u2022 UK",
            streamUrl = "https://radio.talksport.com/stream",
            accentColorHex = 0xFFFFB300L,
            badgeCode = "TS"
        ),
        RadioStationStream(
            id = "dir_times_radio",
            name = "Times Radio",
            genreOrTagline = "Live News & Current Affairs \u2022 UK",
            streamUrl = "https://timesradio.wireless.radio/stream",
            accentColorHex = 0xFF58A6FFL,
            badgeCode = "TMS"
        ),
        RadioStationStream(
            id = "dir_capital_dance",
            name = "Capital Dance",
            genreOrTagline = "24/7 Club, House & Dance Anthems \u2022 UK",
            streamUrl = "https://media-ssl.musicradio.com/CapitalDance",
            accentColorHex = 0xFFE040FBL,
            badgeCode = "DNC"
        ),
        RadioStationStream(
            id = "dir_capital_xtra",
            name = "Capital XTRA",
            genreOrTagline = "Hip-Hop, RnB, Grime & Afrobeats \u2022 UK",
            streamUrl = "https://media-ssl.musicradio.com/CapitalXTRANational",
            accentColorHex = 0xFF00E676L,
            badgeCode = "XTR"
        ),
        RadioStationStream(
            id = "dir_heart_80s",
            name = "Heart 80s",
            genreOrTagline = "Non-Stop 80s Feel Good Classics \u2022 UK",
            streamUrl = "https://media-ssl.musicradio.com/Heart80s",
            accentColorHex = 0xFFFF4081L,
            badgeCode = "80S"
        ),
        RadioStationStream(
            id = "dir_heart_90s",
            name = "Heart 90s",
            genreOrTagline = "Non-Stop 90s Throwbacks \u2022 UK",
            streamUrl = "https://media-ssl.musicradio.com/Heart90s",
            accentColorHex = 0xFF00E5FFL,
            badgeCode = "90S"
        ),
        RadioStationStream(
            id = "dir_heart_dance",
            name = "Heart Dance",
            genreOrTagline = "Club Classics & Feel Good Dance \u2022 UK",
            streamUrl = "https://media-ssl.musicradio.com/HeartDance",
            accentColorHex = 0xFFC084FCL,
            badgeCode = "HDN"
        ),
        RadioStationStream(
            id = "dir_smooth_chill",
            name = "Smooth Chill",
            genreOrTagline = "Ambient, Chillout & Relaxing Beats \u2022 UK",
            streamUrl = "https://media-ssl.musicradio.com/SmoothChill",
            accentColorHex = 0xFF2DD4BFL,
            badgeCode = "CHL"
        ),
        RadioStationStream(
            id = "dir_radio_x",
            name = "Radio X UK",
            genreOrTagline = "Get Into The Music \u2022 Indie & Rock \u2022 UK",
            streamUrl = "https://media-ssl.musicradio.com/RadioXUK",
            accentColorHex = 0xFF00E676L,
            badgeCode = "RDX"
        ),
        RadioStationStream(
            id = "dir_gold_uk",
            name = "Gold Radio UK",
            genreOrTagline = "60s, 70s & 80s Greatest Hits \u2022 UK",
            streamUrl = "https://media-ssl.musicradio.com/Gold",
            accentColorHex = 0xFFFFB300L,
            badgeCode = "GLD"
        ),
        RadioStationStream(
            id = "dir_bbc_6music",
            name = "BBC Radio 6 Music",
            genreOrTagline = "Alternative, Indie, Soul & Electronica \u2022 UK",
            streamUrl = "https://as-hls-ww-live.akamaized.net/pool_81827798/live/ww/bbc_6music/bbc_6music.isml/bbc_6music-audio%3d96000.norewind.m3u8",
            accentColorHex = 0xFFFF8A00L,
            badgeCode = "6M"
        ),
        RadioStationStream(
            id = "dir_bbc_1xtra",
            name = "BBC Radio 1Xtra",
            genreOrTagline = "Black Music, Hip-Hop, RnB & Drum & Bass \u2022 UK",
            streamUrl = "https://as-hls-ww-live.akamaized.net/pool_92079267/live/ww/bbc_1xtra/bbc_1xtra.isml/bbc_1xtra-audio%3d96000.norewind.m3u8",
            accentColorHex = 0xFF00E5FFL,
            badgeCode = "1X"
        ),
        RadioStationStream(
            id = "dir_bbc_5live",
            name = "BBC Radio 5 Live",
            genreOrTagline = "Breaking News & Live Sport Commentary \u2022 UK",
            streamUrl = "https://as-hls-ww-live.akamaized.net/pool_89021708/live/ww/bbc_radio_five_live/bbc_radio_five_live.isml/bbc_radio_five_live-audio%3d96000.norewind.m3u8",
            accentColorHex = 0xFF2DD4BFL,
            badgeCode = "5LV"
        ),
        RadioStationStream(
            id = "dir_bbc_world",
            name = "BBC World Service",
            genreOrTagline = "Global News, Analysis & Documentaries",
            streamUrl = "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service",
            accentColorHex = 0xFFFF5252L,
            badgeCode = "WS"
        ),
        RadioStationStream(
            id = "dir_radio_paradise",
            name = "Radio Paradise (Main Mix)",
            genreOrTagline = "Eclectic Listener-Supported Rock, Indie & World",
            streamUrl = "https://stream.radioparadise.com/aac-320",
            accentColorHex = 0xFF00E676L,
            badgeCode = "RP"
        ),
        RadioStationStream(
            id = "dir_somafm_groovesalad",
            name = "SomaFM Groove Salad",
            genreOrTagline = "Nicely Chilled Ambient & Downtempo Beats",
            streamUrl = "https://ice2.somafm.com/groovesalad-256-mp3",
            accentColorHex = 0xFF2DD4BFL,
            badgeCode = "GS"
        ),
        RadioStationStream(
            id = "dir_jazz24",
            name = "Jazz24 (NPR Seattle)",
            genreOrTagline = "24/7 Classic & Contemporary Jazz",
            streamUrl = "https://live.wostreaming.net/direct/ppm-jazz24aac-ibc1",
            accentColorHex = 0xFFFFB300L,
            badgeCode = "J24"
        )
    )

    suspend fun searchStations(query: String): List<RadioStationStream> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val clean = query.trim()
            val localMatches = if (clean.isBlank()) {
                builtInCatalog
            } else {
                builtInCatalog.filter {
                    it.name.contains(clean, ignoreCase = true) ||
                            it.genreOrTagline.contains(clean, ignoreCase = true) ||
                            it.badgeCode.contains(clean, ignoreCase = true)
                }
            }
            if (clean.isBlank()) return@withContext localMatches

            val encoded = java.net.URLEncoder.encode(clean, "UTF-8")
            val mirrors = listOf(
                "https://de1.api.radio-browser.info",
                "https://fr1.api.radio-browser.info",
                "https://at1.api.radio-browser.info"
            )
            val remoteResults = mutableListOf<RadioStationStream>()
            for (host in mirrors) {
                try {
                    val endpoint = "$host/json/stations/search?name=$encoded&limit=35&hidebroken=true&order=clickcount&reverse=true"
                    val conn = (java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 4000
                        readTimeout = 4000
                        setRequestProperty("User-Agent", "ClaraSmartHome/2.0")
                        setRequestProperty("Accept", "application/json")
                    }
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        conn.disconnect()
                        val arr = org.json.JSONArray(body)
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val name = o.optString("name", "").trim()
                            val urlResolved = o.optString("url_resolved", o.optString("url", "")).trim()
                            if (name.isBlank() || !urlResolved.startsWith("http", ignoreCase = true)) continue
                            val uuid = o.optString("stationuuid", UUID.randomUUID().toString())
                            val country = o.optString("countrycode", "").trim()
                            val tags = o.optString("tags", "")
                                .split(",")
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .take(3)
                                .joinToString(", ")
                            val bitrate = o.optInt("bitrate", 0)
                            val sub = buildString {
                                if (tags.isNotBlank()) append(tags.replaceFirstChar { it.uppercase(Locale.US) })
                                else append("Live Radio")
                                if (country.isNotBlank()) append(" \u2022 $country")
                                if (bitrate > 0) append(" \u2022 ${bitrate}k")
                            }
                            val badge = name.replace(Regex("[^A-Za-z0-9]"), "").take(3).uppercase(Locale.US).ifBlank { "FM" }
                            remoteResults.add(
                                RadioStationStream(
                                    id = "rb_$uuid",
                                    name = name,
                                    genreOrTagline = sub,
                                    streamUrl = urlResolved,
                                    accentColorHex = accentPalette[i % accentPalette.size],
                                    badgeCode = badge,
                                    isCustom = true
                                )
                            )
                        }
                        break
                    } else {
                        conn.disconnect()
                    }
                } catch (_: Exception) {
                }
            }
            (localMatches + remoteResults).distinctBy { it.streamUrl.lowercase(Locale.US) }
        }
}

// ---------------------------------------------------------------------------
// DISCOVERY RESULT MODELS
// ---------------------------------------------------------------------------

interface VehicleProviderAdapter {
    val brand: ConnectedVehicleBrand
    suspend fun fetchTelemetry(context: android.content.Context, bridge: BridgeSettings, tile: SmartTile): SmartTile?
    suspend fun setLocked(context: android.content.Context, bridge: BridgeSettings, tile: SmartTile, lock: Boolean): Pair<Boolean, String>
    suspend fun setClimate(context: android.content.Context, bridge: BridgeSettings, tile: SmartTile, turnOn: Boolean): Pair<Boolean, String>
    suspend fun setCharging(context: android.content.Context, bridge: BridgeSettings, tile: SmartTile, startCharge: Boolean): Pair<Boolean, String>
    suspend fun triggerHonkAndFlash(context: android.content.Context, bridge: BridgeSettings, tile: SmartTile): Pair<Boolean, String>
}

// ---------------------------------------------------------------------------
// PHASE 6.1 & PHASE 7: CANONICAL DEVICE NODE, 9-PRIMITIVE KNOWLEDGE GRAPH
// & MULTI-HOP CONTEXTUAL INFERENCE MODELS
// ---------------------------------------------------------------------------

// Multi-colour spectrum gradient stops for the thermostat & ambient arc
internal val ThermostatSpectrumColors = listOf(
    Color(0xFF00E5A3), // Mint Teal
    Color(0xFF00C6FF), // Electric Cyan
    Color(0xFF7C4DFF), // Deep Violet
    Color(0xFFFF4081), // Rose Pink
    Color(0xFFFF6D00), // Warm Heating Orange
    Color(0xFF00E5A3)
)

internal val FullRgbWheelColors = listOf(
    Color(0xFFFF0000),
    Color(0xFFFFFF00),
    Color(0xFF00FF00),
    Color(0xFF00FFFF),
    Color(0xFF0000FF),
    Color(0xFFFF00FF),
    Color(0xFFFF0000)
)

internal fun iconForCapabilityDescriptor(cap: DeviceCapabilityDescriptor): ImageVector {
    val k = cap.key.lowercase(Locale.US)
    return when {
        k.contains("lock") || k.contains("child") -> Icons.Outlined.Lock
        k.contains("countdown") || k.contains("timer") -> Icons.Outlined.Timer
        k.contains("relay") || k.contains("restart") -> Icons.Outlined.SettingsPower
        k.contains("light") || k.contains("led") || k.contains("effect") -> Icons.Outlined.LightMode
        k.contains("horizontal") || k.contains("shake") || k.contains("swing") || k.contains("oscillat") -> Icons.Outlined.SyncAlt
        k.contains("anion") || k.contains("ion") || k.contains("fresh") -> Icons.Outlined.AutoAwesome
        k.contains("fan") || k.contains("speed") || k.contains("wind") -> Icons.Outlined.Air
        k.contains("clean") || k.contains("vacuum") -> Icons.Outlined.CleaningServices
        k.contains("dock") || k.contains("chargego") -> Icons.Outlined.Home
        k.contains("ev") || k.contains("charging") -> Icons.Outlined.EvStation
        k.contains("honk") || k.contains("flash") || k.contains("alarm") -> Icons.Outlined.Campaign
        k.contains("chime") || k.contains("doorbell") -> Icons.Outlined.Doorbell
        k.contains("position") || k.contains("blind") || k.contains("cover") -> Icons.Outlined.Blinds
        k.contains("mode") || k.contains("preset") || k.contains("scene") -> Icons.Outlined.AutoAwesome
        k.contains("temp") || k.contains("heat") || k.contains("climate") -> Icons.Outlined.Thermostat
        k.contains("hdmi") || k.contains("source") -> Icons.Outlined.SettingsInputHdmi
        cap.kind == DeviceCapabilityKind.COUNTDOWN_TIMER -> Icons.Outlined.Timer
        cap.kind == DeviceCapabilityKind.ENUM_MODE -> Icons.Outlined.Tune
        cap.kind == DeviceCapabilityKind.NUMERIC_SLIDER -> Icons.Outlined.LinearScale
        else -> Icons.Default.PowerSettingsNew
    }
}

internal fun iconForCapabilityPreset(
    iconKey: String?,
    fallbackIcon: ImageVector = Icons.Default.PowerSettingsNew
): ImageVector = when (iconKey?.uppercase(Locale.US)) {
    "SUNNY", "ON" -> Icons.Outlined.WbSunny
    "BRIGHT", "HIGH" -> Icons.Outlined.LightMode
    "DIM", "LOW" -> Icons.Outlined.BrightnessLow
    "POWER", "OFF" -> Icons.Default.PowerSettingsNew
    "BLINDS_OPEN", "BLINDS_HALF", "BLINDS_VENT" -> Icons.Outlined.Blinds
    "BLINDS_CLOSED", "CLOSE" -> Icons.Outlined.BlindsClosed
    "TIMER" -> Icons.Outlined.Timer
    "ECO" -> Icons.Outlined.Eco
    "HEAT" -> Icons.Outlined.LocalFireDepartment
    "BOOST" -> Icons.Outlined.Bolt
    "VOLUME_MUTE" -> Icons.Outlined.VolumeOff
    "VOLUME_LOW", "VOLUME_MED", "VOLUME_HIGH", "VOLUME_MAX" -> Icons.Outlined.VolumeUp
    else -> fallbackIcon
}

internal fun nextCapabilityValueOnTap(cap: DeviceCapabilityDescriptor): Pair<String, Boolean> {
    return when (cap.kind) {
        DeviceCapabilityKind.BOOLEAN_SWITCH -> {
            val currentlyOn = cap.currentValue.equals("true", ignoreCase = true) ||
                    cap.currentValue.equals("on", ignoreCase = true) ||
                    cap.currentValue.equals("locked", ignoreCase = true) ||
                    cap.currentValue == "1"
            val nextBool = !currentlyOn
            nextBool.toString() to nextBool
        }
        DeviceCapabilityKind.ENUM_MODE -> {
            val opts = cap.enumOptions.ifEmpty { listOf("off", "on", "last") }
            val curIdx = opts.indexOfFirst { it.equals(cap.currentValue, ignoreCase = true) }
            val nextOpt = opts[(curIdx + 1).coerceAtLeast(0) % opts.size]
            nextOpt to !nextOpt.equals("off", ignoreCase = true)
        }
        DeviceCapabilityKind.COUNTDOWN_TIMER -> {
            val curSec = cap.currentValue.toFloatOrNull()?.roundToInt() ?: 0
            val presets = listOf(0, 900, 1800, 3600, 7200)
            val curIdx = presets.indexOf(curSec)
            val nextSec = if (curIdx >= 0) presets[(curIdx + 1) % presets.size] else 1800
            nextSec.toString() to (nextSec > 0)
        }
        DeviceCapabilityKind.NUMERIC_SLIDER -> {
            val minV = cap.minValue
            val maxV = cap.maxValue.coerceAtLeast(minV + 1f)
            val span = maxV - minV
            val step = (span * 0.25f).coerceAtLeast(cap.stepValue.coerceAtLeast(1f))
            val curV = cap.currentValue.toFloatOrNull() ?: minV
            val nextV = if (curV + step > maxV) minV else (curV + step).coerceIn(minV, maxV)
            nextV.roundToInt().toString() to (nextV > minV)
        }
        else -> "TRIGGER" to true
    }
}

internal fun formatCapabilityButtonLabel(cap: DeviceCapabilityDescriptor): String {
    val shortName = cap.label
        .removePrefix("Socket 1 ")
        .removePrefix("Socket 2 ")
        .trim()
    return when (cap.kind) {
        DeviceCapabilityKind.BOOLEAN_SWITCH -> {
            val isOn = cap.currentValue.equals("true", ignoreCase = true) ||
                    cap.currentValue.equals("on", ignoreCase = true) ||
                    cap.currentValue == "1"
            "$shortName: ${if (isOn) "ON" else "OFF"}"
        }
        DeviceCapabilityKind.ENUM_MODE -> {
            val cur = cap.currentValue.ifBlank { cap.enumOptions.firstOrNull().orEmpty() }
            if (cur.isNotBlank()) "$shortName: ${cur.uppercase(Locale.US)}" else shortName
        }
        DeviceCapabilityKind.COUNTDOWN_TIMER -> {
            val secs = cap.currentValue.toFloatOrNull()?.roundToInt() ?: 0
            val mins = secs / 60
            if (mins > 0) "$shortName: ${mins}m" else "$shortName: Off"
        }
        DeviceCapabilityKind.NUMERIC_SLIDER -> {
            val v = cap.currentValue.ifBlank { cap.minValue.roundToInt().toString() }
            "$shortName: $v${cap.unit}"
        }
        else -> shortName
    }
}
