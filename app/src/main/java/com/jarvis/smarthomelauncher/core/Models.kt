package com.jarvis.smarthomelauncher.core

import android.app.PendingIntent
import android.net.Uri
import com.jarvis.smarthomelauncher.engines.ResolvedDeviceIdentity
import java.util.Locale
import java.util.UUID

data class ObservedBehaviourProfile(
    val peakWatts: Float = 0f,
    val averageCycleDurationSeconds: Int = 0,
    val hasThermostaticCycling: Boolean = false,
    val dropsToZeroAfterBurst: Boolean = false,
    val confidence: Float = 0f
)

data class ClassificationResolution(
    val deviceClass: DeviceClass,
    val deviceSubtype: DeviceSubtype,
    val suggestedTileType: TileType,
    val confidence: Float,
    val source: ClassificationSource = ClassificationSource.CAPABILITY_CLASSIFIER,
    val version: Int = SemanticClassifier.CURRENT_VERSION,
    val evaluatedAtEpochMs: Long = System.currentTimeMillis(),
    val schemaHash: String = ""
)

data class UserProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: UserRole = UserRole.OWNER,
    val avatarColorHex: Long = 0xFFA3F0C1L,
    val isParentalLocked: Boolean = false,
    val requirePinToSwitchOut: Boolean = false,
    val customPinCode: String = "",
    val avatarUriOrPreset: String = "DEFAULT_1"
) {
    val canEditLayout: Boolean
        get() = !isParentalLocked && role.canEditLayout

    val canManageDevices: Boolean
        get() = !isParentalLocked && role.canManageDevices

    val canControlVehiclesAndLocks: Boolean
        get() = !isParentalLocked && role.canControlVehiclesAndLocks

    val canAccessSettings: Boolean
        get() = !isParentalLocked && role.canAccessSettings
}

data class RadioStationStream(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val genreOrTagline: String,
    val streamUrl: String,
    val accentColorHex: Long = 0xFF00E5FFL,
    val badgeCode: String = "FM",
    val isCustom: Boolean = false
)

data class TrackedMediaShow(
    val id: String = UUID.randomUUID().toString(),
    val externalId: String = "",
    val title: String,
    val kind: TrackedMediaKind = TrackedMediaKind.TV_SHOW,
    val networkOrStreamer: String = "BBC iPlayer",
    val statusText: String = "Running",
    val nextEpisodeCode: String = "Next Episode",
    val nextEpisodeTitle: String = "",
    val nextAirDateLabel: String = "Airing Soon",
    val airTimestampEpochMs: Long = 0L,
    val lastWatchedEpisodeCode: String = "",
    val artworkUrl: String = "",
    val summarySnippet: String = "",
    val appIdHint: String = "bbc",
    val contentIdHint: String = "",
    val accentColorHex: Long = 0xFF00E5FFL,
    val isFavourite: Boolean = true
) {
    val canonicalStreamerBadge: String
        get() = NextEpisodeDirectoryClient.normalizeStreamerName(networkOrStreamer, appIdHint)
    val isAiringToday: Boolean
        get() {
            if (nextAirDateLabel.contains("Today", ignoreCase = true) ||
                nextAirDateLabel.contains("Tonight", ignoreCase = true)
            ) return true
            if (airTimestampEpochMs <= 0L) return false
            val calNow = java.util.Calendar.getInstance()
            val calAir = java.util.Calendar.getInstance().apply { timeInMillis = airTimestampEpochMs }
            return calNow.get(java.util.Calendar.YEAR) == calAir.get(java.util.Calendar.YEAR) &&
                    calNow.get(java.util.Calendar.DAY_OF_YEAR) == calAir.get(java.util.Calendar.DAY_OF_YEAR)
        }
}

data class HouseholdCalendarEvent(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val subtitle: String = "",
    val dateIso: String, // YYYY-MM-DD (or MM-DD matching for recurring birthdays)
    val timeLabel: String = "All Day",
    val category: CalendarEventCategory = CalendarEventCategory.REMINDER,
    val isRecurringYearly: Boolean = category == CalendarEventCategory.BIRTHDAY,
    val tvLaunchAppId: String = "",
    val tvLaunchContentId: String = "",
    val iboChannelNumber: Int = 0
)

data class UkCouncilBinsConfig(
    val collectionDayOfWeek: Int = java.util.Calendar.WEDNESDAY, // 1=Sun .. 7=Sat
    val anchorRecyclingDateIso: String = "2026-11-04",
    val alternateWeeklyRecyclingAndRefuse: Boolean = true,
    val includeFoodCaddyWeekly: Boolean = true,
    val includeGardenBinWithRecycling: Boolean = false
) {
    fun getBinsForDate(cal: java.util.Calendar): List<UkCouncilBinType> {
        if (cal.get(java.util.Calendar.DAY_OF_WEEK) != collectionDayOfWeek) return emptyList()
        val bins = mutableListOf<UkCouncilBinType>()
        if (!alternateWeeklyRecyclingAndRefuse) {
            bins.add(UkCouncilBinType.REFUSE_BLACK)
            bins.add(UkCouncilBinType.RECYCLING_BLUE)
        } else {
            val anchorMillis = runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(anchorRecyclingDateIso)?.time ?: 0L
            }.getOrDefault(0L)
            val diffWeeks = if (anchorMillis > 0L) {
                kotlin.math.abs((cal.timeInMillis - anchorMillis) / (7L * 24L * 3600L * 1000L)).toInt()
            } else {
                cal.get(java.util.Calendar.WEEK_OF_YEAR)
            }
            if (diffWeeks % 2 == 0) {
                bins.add(UkCouncilBinType.RECYCLING_BLUE)
                if (includeGardenBinWithRecycling) bins.add(UkCouncilBinType.GARDEN_GREEN)
            } else {
                bins.add(UkCouncilBinType.REFUSE_BLACK)
            }
        }
        if (includeFoodCaddyWeekly) bins.add(UkCouncilBinType.FOOD_CADDY)
        return bins
    }
}

data class FootballMatchFixture(
    val id: String,
    val homeTeam: String,
    val awayTeam: String,
    val competition: String,
    val dateIso: String, // YYYY-MM-DD
    val kickOffTimeUk: String, // HH:mm
    val statusLabel: String, // e.g. "19:45", "LIVE 64'", "FT 2-1"
    val scoreLabel: String = "vs",
    val ukTvBroadcaster: String = "Sky Sports Main Event",
    val tvAppId: String = "com.ibo.pro",
    val iboChannelNumber: Int = 401
)

data class FootballTvProviderConfig(
    val providerOption: FootballTvProviderOption = FootballTvProviderOption.IBO_PRO_PLAYER,
    val customPlayerAppId: String = "com.ibo.pro",
    val xtreamServerUrl: String = "",
    val xtreamUsername: String = "",
    val xtreamPassword: String = "",
    val m3uPlaylistUrl: String = "",
    val autoMatchedChannels: Map<String, Int> = DEFAULT_IBO_CHANNEL_MAPPINGS
) {
    val effectivePlayerAppId: String
        get() = customPlayerAppId.trim().ifBlank { providerOption.appId }
}

data class GuestWifiConfig(
    val ssid: String = "Clara_Guest_WiFi",
    val password: String = "",
    val securityType: String = "WPA",
    val commuteOriginName: String = "Home",
    val commuteDestinationName: String = "Work",
    val preferredRailStationName: String = "",
    val preferredRailStationCrs: String = ""
)

data class DiscoveredCastDevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val port: Int = 8009,
    val modelName: String = "Google Nest Mini",
    val volumePercent: Float = 55f,
    val isMuted: Boolean = false,
    val isCastGroup: Boolean = false,
    val statusText: String = "Idle"
)

data class CastSpeakerGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val memberIps: List<String>,
    val memberNames: List<String>,
    val volumePercent: Float = 55f
)

data class LgTvAppShortcut(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val appId: String,
    val isHdmiInput: Boolean = appId.contains("hdmi", ignoreCase = true),
    val contentId: String = "",
    val autoConfirmSelect: Boolean = contentId.isNotBlank(),
    val iboChannelNumber: Int = 0
) {
    val isIboChannelShortcut: Boolean
        get() = iboChannelNumber > 0 || appId.contains("ibo", ignoreCase = true)
}

data class DoorbellRingEvent(
    val sourcePackage: String,
    val title: String,
    val message: String,
    val contentIntent: PendingIntent?,
    val timestamp: Long = System.currentTimeMillis()
)

data class DailyWeatherForecast(
    val dayLabel: String,
    val dateSubtitle: String = "",
    val conditionText: String,
    val maxTempCelsius: Int,
    val minTempCelsius: Int,
    val precipProbabilityPct: Int = 0
)

data class WeatherInfo(
    val temperatureCelsius: Int = 15,
    val lowTemperatureCelsius: Int = 9,
    val conditionText: String = "Partly Cloudy",
    val isDay: Boolean = true,
    val dailyForecast: List<DailyWeatherForecast> = emptyList()
)

data class DigitalTwinShadowIntent(
    val id: String = UUID.randomUUID().toString(),
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val expiresAtEpochMs: Long = System.currentTimeMillis() + 30 * 60_000L,
    val tileId: String,
    val tileTitle: String,
    val desiredIsActive: Boolean,
    val desiredIsSocket2Active: Boolean = false,
    val desiredTargetTempC: Float? = null,
    val desiredVolumeOrLevel: Float? = null,
    val desiredMode: String = "",
    val capabilityKey: String = "",
    val desiredValue: String = "",
    val intentAuthorityTier: IntentAuthorityTier = IntentAuthorityTier.MANUAL_USER,
    val actorName: String = "Manual User",
    val coalesceKey: String = "${tileId}:${capabilityKey.ifBlank { "state" }}"
)

data class DeviceEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val deviceId: String,
    val deviceTitle: String,
    val roomName: String = "",
    val eventType: DeviceEventType = DeviceEventType.STATE_CHANGED,
    val source: String = "Local",
    val actorName: String = "Manual User",
    val stateVersion: Long = 1L,
    val authorityTierLabel: String = StateAuthorityTier.DEVICE_HARDWARE_TRUTH.label,
    val previousStateSummary: String = "",
    val newStateSummary: String = ""
)

data class DeviceObservabilityRecord(
    val deviceId: String,
    val deviceTitle: String,
    val deviceClass: DeviceClass = DeviceClass.SWITCH,
    val lifecycleState: DeviceLifecycleState = DeviceLifecycleState.ONLINE,
    val lastSeenEpochMs: Long = System.currentTimeMillis(),
    val lastSuccessfulCommandMs: Long = 0L,
    val lastFailedCommandMs: Long = 0L,
    val currentRecoveryStage: Int = -1,
    val activeProviderId: String = "LOCAL_LAN",
    val fallbackProviderIds: List<String> = emptyList(),
    val currentIp: String = "",
    val currentMac: String = "",
    val availabilityPct: Float = 100f,
    val commandSuccessRatePct: Float = 100f,
    val totalCommandsSent: Int = 0,
    val totalCommandsSucceeded: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val recoveryAttempts: Int = 0,
    val averageLatencyMs: Long = 12L,
    val lastFailureReason: String = "",
    val lastRecoveryDurationMs: Long = 0L,
    val canonicalGuid: String = "",
    val lastLifecycleSequence: Long = 0L
)

data class AccountHealthRecord(
    val accountKey: String,
    val displayName: String,
    val oauthStatus: AccountAuthStatus = AccountAuthStatus.NOT_LINKED,
    val tokenExpiresAtEpochMs: Long = 0L,
    val lastRefreshEpochMs: Long = 0L,
    val mfaRequired: Boolean = false,
    val permissionsRevoked: Boolean = false,
    val statusDetail: String = "Not configured"
) {
    val isHealthy: Boolean
        get() = oauthStatus == AccountAuthStatus.VALID || oauthStatus == AccountAuthStatus.EXPIRING_SOON
}

data class ProviderHealthStatus(
    val providerId: String,
    val displayName: String,
    val rollingSuccessRatePct: Float = 100f,
    val averageLatencyMs: Long = 15L,
    val consecutiveFailures: Int = 0,
    val consecutiveAuthFailures: Int = 0,
    val circuitBreakerOpenUntilMs: Long = 0L,
    val lastUpdatedEpochMs: Long = System.currentTimeMillis()
) {
    val isHealthy: Boolean
        get() = System.currentTimeMillis() >= circuitBreakerOpenUntilMs &&
                consecutiveAuthFailures < 3 &&
                rollingSuccessRatePct >= 50f
}

data class ProviderFailoverPolicy(
    val deviceId: String,
    val primaryProviderId: String,
    val orderedFallbackProviderIds: List<String> = emptyList(),
    val activeProviderId: String = primaryProviderId,
    val lastFailoverEpochMs: Long = 0L,
    val parentNetworkNodeId: String = ""
)

data class ClaraReliabilityKpis(
    val discoverySuccessRatePct: Float = 98.5f,
    val onboardingSuccessRatePct: Float = 96.0f,
    val commandSuccessRatePct: Float = 99.4f,
    val autoRecoverySuccessRatePct: Float = 96.5f,
    val falseSuccessRatePct: Float = 0.0f,
    val averageUserDecisionsPerOnboarding: Float = 1.0f,
    val averageRecoveryTimeSeconds: Float = 4.2f,
    val totalDiscoveryRuns: Int = 1,
    val totalOnboardingsAttempted: Int = 0,
    val totalOnboardingsSucceeded: Int = 0,
    val totalCommandsDispatched: Int = 0,
    val totalCommandsConfirmed: Int = 0,
    val totalRecoveriesAttempted: Int = 0,
    val totalRecoveriesSucceeded: Int = 0
)

enum class CapabilityProvenance {
    VERIFIED_HARDWARE_SCHEMA,
    EXPLICIT_USER_OVERRIDE,
    SYNTHETIC_GUESS
}

data class CapabilityPreset(
    val value: Float,
    val label: String,
    val icon: String? = null
)

data class DeviceCapabilityDescriptor(
    val key: String,
    val dpId: String = "",
    val label: String,
    val kind: DeviceCapabilityKind = DeviceCapabilityKind.BOOLEAN_SWITCH,
    val isWritable: Boolean = true,
    val isPinnedToHero: Boolean = true,
    val currentValue: String = "",
    val enumOptions: List<String> = emptyList(),
    val minValue: Float = 0f,
    val maxValue: Float = 100f,
    val stepValue: Float = 1f,
    val unit: String = "",
    val rendererType: CapabilityRendererType = defaultRendererTypeForCapability(kind, key),
    val presets: List<CapabilityPreset> = emptyList(),
    val safetyTier: CapabilitySafetyTier = defaultSafetyTierForCapability(key),
    val holdDurationMs: Long = defaultHoldDurationMsForCapability(key),
    val provenance: CapabilityProvenance = CapabilityProvenance.VERIFIED_HARDWARE_SCHEMA,
    val schemaHash: String = ""
) {
    fun resolvedPresets(
        isSmartCover: Boolean = false,
        isGarageDoor: Boolean = false
    ): List<CapabilityPreset> {
        if (presets.isNotEmpty()) return presets
        return when (kind) {
            DeviceCapabilityKind.COUNTDOWN_TIMER -> listOf(
                CapabilityPreset(value = 900f, label = "15m Timer", icon = "TIMER"),
                CapabilityPreset(value = 1800f, label = "30m Timer", icon = "TIMER"),
                CapabilityPreset(value = 3600f, label = "1h Timer", icon = "TIMER")
            ).filter { it.value <= maxValue.coerceAtLeast(3600f) }

            DeviceCapabilityKind.NUMERIC_SLIDER -> {
                val span = (maxValue - minValue).coerceAtLeast(1f)
                val cleanKey = key.lowercase(Locale.US)
                when {
                    cleanKey in listOf("speaker_volume", "volume") -> listOf(
                        CapabilityPreset(minValue, "${minValue.toInt()}$unit", "VOLUME_MUTE"),
                        CapabilityPreset(minValue + span * 0.25f, "${(minValue + span * 0.25f).toInt()}$unit", "VOLUME_LOW"),
                        CapabilityPreset(minValue + span * 0.50f, "${(minValue + span * 0.50f).toInt()}$unit", "VOLUME_MED"),
                        CapabilityPreset(minValue + span * 0.75f, "${(minValue + span * 0.75f).toInt()}$unit", "VOLUME_HIGH"),
                        CapabilityPreset(maxValue, "${maxValue.toInt()}$unit", "VOLUME_MAX")
                    )
                    isGarageDoor -> listOf(
                        CapabilityPreset(maxValue, "Open (${maxValue.toInt()}$unit)", "BLINDS_OPEN"),
                        CapabilityPreset(minValue + span * 0.50f, "Half (${(minValue + span * 0.50f).toInt()}$unit)", "BLINDS_HALF"),
                        CapabilityPreset(minValue, "Close (${minValue.toInt()}$unit)", "BLINDS_CLOSED")
                    )
                    isSmartCover || cleanKey in listOf("position", "lift") -> listOf(
                        CapabilityPreset(maxValue, "Open (${maxValue.toInt()}$unit)", "BLINDS_OPEN"),
                        CapabilityPreset(minValue + span * 0.50f, "Half (${(minValue + span * 0.50f).toInt()}$unit)", "BLINDS_HALF"),
                        CapabilityPreset(minValue + span * 0.20f, "Vent (${(minValue + span * 0.20f).toInt()}$unit)", "BLINDS_VENT"),
                        CapabilityPreset(minValue, "Close (${minValue.toInt()}$unit)", "BLINDS_CLOSED")
                    )
                    else -> listOf(
                        CapabilityPreset(maxValue, "On (${maxValue.toInt()}$unit)", "SUNNY"),
                        CapabilityPreset(minValue + span * 0.75f, "Bright (${(minValue + span * 0.75f).toInt()}$unit)", "BRIGHT"),
                        CapabilityPreset(minValue + span * 0.25f, "Dim (${(minValue + span * 0.25f).toInt()}$unit)", "DIM"),
                        CapabilityPreset(0f, "Off", "POWER")
                    )
                }
            }
            else -> emptyList()
        }
    }
}

data class SmartTile(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val roomOrSubtitle: String = "",
    val type: TileType,
    val lifecycleState: DeviceLifecycleState = DeviceLifecycleState.DISCOVERED,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    val gridCol: Int = -1,
    val gridRow: Int = -1,
    val isTall: Boolean = false,
    val isActive: Boolean = true,
    val isVisible: Boolean = true,
    val isOnline: Boolean = true,
    val temperature: Float = 20.5f,
    val targetTemperature: Float = 20.0f,
    val humidity: Int = 0,
    val hvacMode: String = "HEAT",
    val hvacStatus: String = "OFF",
    val volume: Float = 60f,
    val isSocket2Active: Boolean = false,
    val multiGangSocketCount: Int = 1,
    val socket1Name: String = "Socket 1",
    val socket2Name: String = "Socket 2",
    val groupCardId: String = "",
    val groupCategoryIcon: String = GroupCardIconOption.LIGHTING.key,
    val activeMediaSource: String = if (type == TileType.LG_TV && hvacStatus != "OFF") hvacStatus else "",
    val supportsPtz: Boolean = type == TileType.IMOU_CAMERA,
    val powerWatts: Float = 0.0f,
    val voltageVolts: Float = 0.0f,
    val currentMilliamps: Float = 0.0f,
    val totalEnergyKwh: Float = 0.0f,
    val countdown1Seconds: Int = 0,
    val countdown2Seconds: Int = 0,
    val isLiveFeedActive: Boolean = false,
    val ipAddress: String = "",
    val secretCode: String = "",
    val serialNumber: String = "",
    val channelId: String = "0",
    val useSubStream: Boolean = false,
    val customRtspUrl: String = "",
    val entityId: String = "",
    val companionAppPackage: String = "",
    val sensorUnit: String = "\u00B0C",
    val forceVolumeHeroMode: Boolean = false,
    val slideshowSourceType: SlideshowSourceType = SlideshowSourceType.LOCAL_DEVICE,
    val slideshowCloudUrl: String = "",
    val slideshowLocalUris: List<String> = emptyList(),
    val slideshowIntervalSeconds: Int = 10,
    val slideshowIncludeSubfolders: Boolean = true,
    val slideshowSelectedFolders: List<String> = emptyList(),
    // Per-device discovered capabilities & sensors
    val capabilities: List<DeviceCapabilityDescriptor> = emptyList(),
    val hasExplicitPowerTelemetry: Boolean = false,
    // Smart Plug X-Ray Appliance Fingerprint
    val detectedApplianceCategory: ApplianceFingerprintCategory = ApplianceFingerprintCategory.UNKNOWN,
    // Jaguar & Mercedes-Benz live telemetry properties (0 until real vehicle sync completes)
    val vehicleLocked: Boolean = true,
    val vehicleFuelOrBatteryPct: Int = 0,
    val vehicleRangeMiles: Int = 0,
    val vehicleIsEv: Boolean = true,
    val vehicleIsCharging: Boolean = false,
    val vehicleClimateActive: Boolean = false,
    val vehicleTargetClimateTempC: Float = 21.0f,
    val vehicleOdometerMiles: Int = 0,
    val vehicleLocationText: String = "",
    val vehicleVin: String = "",
    val vehicleRegPlate: String = "",
    val vehicleTaxStatus: String = "Not Configured",
    val vehicleTaxDueDateIso: String = "",
    val vehicleMotStatus: String = "Not Configured",
    val vehicleMotExpiryDateIso: String = "",
    val vehicleMotAdvisories: List<String> = emptyList(),
    val vehicleRecallsCount: Int = 0,
    val vehicleUlezCompliant: Boolean = true,
    val vehicleTpmsFrontLeftPsi: Float = 0f,
    val vehicleTpmsFrontRightPsi: Float = 0f,
    val vehicleTpmsRearLeftPsi: Float = 0f,
    val vehicleTpmsRearRightPsi: Float = 0f,
    val vehicle12vBatteryVolts: Float = 0f,
    val vehicleDoorsWindowsAllClosed: Boolean = true,
    val vehicleNextServiceMiles: Int = 0,
    // Lytmi, Govee & Twinkly Ambient RGB / HDMI Sync Box properties
    val lytmiHdmiInput: Int = 1,
    val lytmiSyncMode: String = "VIDEO",
    val lytmiSceneName: String = "Cyberpunk",
    val lytmiDiffusionSpeed: Float = 75f,
    val lytmiColorArgb: Long = 0xFF00E5FFL,
    // Pro Breeze Smart Tower Fan properties
    val fanSpeedLevel: Int = 3,
    val fanMode: String = "NORMAL",
    val fanOscillating: Boolean = false,
    val fanIonizerActive: Boolean = false,
    val fanTimerHours: Int = 0,
    // Security Center & Camera AI properties
    val securityMode: SecurityCenterMode = SecurityCenterMode.HOME,
    val lastCameraAiEvent: CameraAiEventType = CameraAiEventType.NONE,
    val lastCameraEventTimestampMs: Long = 0L,
    val batteryPercent: Int = 100,// Closed-Loop Command Transaction, Lifecycle, Dual Authority & Capability Evolution
    val transactionState: CommandTransactionState = CommandTransactionState.IDLE,
    val lastUserActuationEpochMs: Long = 0L,
    val lastConfirmedEpochMs: Long = 0L,
    val deviceClassOverride: DeviceClass? = null,
    val deviceSubtypeOverride: DeviceSubtype? = null,
    val serviceClassOverride: ServiceClass? = null,
    val classificationConfidence: Float = 0.85f,
    val classificationSource: ClassificationSource = if (deviceClassOverride != null) {
        ClassificationSource.MANUAL_OVERRIDE
    } else {
        ClassificationSource.CAPABILITY_CLASSIFIER
    },
    val classificationVersion: Int = CapabilityClassifier.CURRENT_VERSION,
    val classificationLastEvaluatedEpochMs: Long = System.currentTimeMillis(),
    val capabilitySchemaHash: String = CapabilitySchemaHasher.computeHash(capabilities),
    val stateVersion: Long = 1L,
    val stateOriginEpochMs: Long = System.currentTimeMillis(),
    val stateAuthorityTier: StateAuthorityTier = StateAuthorityTier.CACHED_UI_STATE,
    val manualHoldUntilEpochMs: Long = 0L,
    val lastIntentAuthorityTier: IntentAuthorityTier = IntentAuthorityTier.MANUAL_USER,
    val lastCausalActor: String = "",
    val activeProviderId: String = "",
    val fallbackProviderIds: List<String> = emptyList(),
    val parentNetworkNodeId: String = "",
    val macAddress: String = "",
    val vendorId: String = "",
    val productId: String = "",
    val canonicalGuid: String = "",
    val subEndpointIndex: Int = when {
        channelId.equals("SPLIT_SOCKET_1", ignoreCase = true) -> 1
        channelId.equals("SPLIT_SOCKET_2", ignoreCase = true) -> 2
        channelId.startsWith("SPLIT_SOCKET_", ignoreCase = true) ->
            channelId.substringAfter("SPLIT_SOCKET_").toIntOrNull() ?: 0
        else -> 0
    }
) {
    private val isExplicitPlugOrApplianceByTitle: Boolean
        get() {
            val lower = title.trim().lowercase(Locale.US)
            return lower.contains("plug") ||
                    lower.contains("socket") ||
                    lower.contains("pond") ||
                    lower.contains("pump") ||
                    lower.contains("kettle") ||
                    lower.contains("fryer") ||
                    lower.contains("coffee") ||
                    lower.contains("washer")
        }

    private val isExplicitVacuumOrSocketOrMediaTile: Boolean
        get() = deviceSubtypeOverride in setOf(
            DeviceSubtype.VACUUM,
            DeviceSubtype.SMART_PLUG,
            DeviceSubtype.WALL_SOCKET,
            DeviceSubtype.RELAY,
            DeviceSubtype.POND_PUMP,
            DeviceSubtype.TOWER_FAN
        ) || channelId.startsWith("SPLIT_SOCKET_") ||
                multiGangSocketCount > 1 ||
                capabilities.any {
                    it.key.lowercase(Locale.US) in setOf("power_go", "switch_go", "clean_switch", "suction")
                }

    private val hasDynamicKettleOrApplianceTraits: Boolean
        get() {
            if (isExplicitVacuumOrSocketOrMediaTile) return false
            val lower = title.trim().lowercase(Locale.US)
            return deviceSubtypeOverride in setOf(
                DeviceSubtype.KETTLE,
                DeviceSubtype.AIR_FRYER,
                DeviceSubtype.COFFEE_MACHINE
            ) || lower.contains("kettle") ||
                    lower.contains("fryer") ||
                    lower.contains("coffee") ||
                    CapabilityClassifier.isKitchenApplianceByCapabilities(capabilities)
        }

    val isClimateHeatingDevice: Boolean
        get() {
            if (hasDynamicKettleOrApplianceTraits || isExplicitPlugOrApplianceByTitle || isExplicitVacuumOrSocketOrMediaTile) {
                return false
            }
            if (channelId.startsWith("SPLIT_SOCKET_") || multiGangSocketCount > 1) return false
            return deviceClassOverride == DeviceClass.CLIMATE ||
                    deviceSubtypeOverride in setOf(DeviceSubtype.THERMOSTAT, DeviceSubtype.RADIATOR, DeviceSubtype.TRV) ||
                    CapabilityClassifier.isClimateHeatingDevice(
                        capabilities = capabilities,
                        categoryCode = "",
                        titleHint = title
                    ) || type == TileType.NEST_THERMOSTAT || type == TileType.TUYA_RADIATOR
        }

    val hasLytmiOrSyncBoxTraits: Boolean
        get() = deviceSubtypeOverride == DeviceSubtype.AMBIENT_SYNC ||
                hasHdmiSwitchCapability ||
                type == TileType.LYTMI_SYNC_BOX

    val isSubtypeConfirmed: Boolean
        get() = classificationSource == ClassificationSource.MANUAL_OVERRIDE && deviceSubtypeOverride != null

    val resolvedProviderId: ProviderId
        get() {
            val fromActive = ProviderId.fromRawKey(activeProviderId)
            if (fromActive != ProviderId.UNKNOWN) return fromActive
            return when (type) {
                TileType.LG_TV -> ProviderId.LG_WEBOS
                TileType.NEST_MINI -> ProviderId.GOOGLE_CAST
                TileType.NEST_THERMOSTAT, TileType.NEST_DOORBELL -> ProviderId.GOOGLE_NEST
                TileType.GOVEE_LIGHT -> if (ipAddress.isNotBlank()) ProviderId.GOVEE_LAN else ProviderId.GOVEE_CLOUD
                TileType.TWINKLY_LIGHT -> ProviderId.TWINKLY_LAN
                TileType.TAPO_DEVICE -> ProviderId.TPLINK_KLAP
                TileType.SONOFF_SWITCH -> ProviderId.EWELINK_LAN
                TileType.SHELLY_RELAY -> ProviderId.SHELLY_HTTP
                TileType.HUE_LIGHT -> ProviderId.PHILIPS_HUE
                TileType.MATTER_DEVICE -> ProviderId.MATTER_FABRIC
                TileType.MQTT_DEVICE -> ProviderId.MQTT_BROKER
                TileType.IMOU_CAMERA -> if (ipAddress.isNotBlank() || customRtspUrl.isNotBlank()) ProviderId.RTSP_ONVIF_LAN else ProviderId.IMOU_CLOUD
                TileType.EZVIZ_DOORBELL -> if (ipAddress.isNotBlank() || customRtspUrl.isNotBlank()) ProviderId.RTSP_ONVIF_LAN else ProviderId.EZVIZ_CLOUD
                TileType.RING_CAMERA -> if (ipAddress.isNotBlank() || customRtspUrl.isNotBlank()) ProviderId.RTSP_ONVIF_LAN else ProviderId.HOME_ASSISTANT
                TileType.JAGUAR_VEHICLE -> ProviderId.JAGUAR_INCONTROL
                TileType.MERCEDES_VEHICLE -> ProviderId.MERCEDES_ME
                TileType.LYTMI_SYNC_BOX,
                TileType.TUYA_RADIATOR,
                TileType.SMARTKNIGHT_PLUG,
                TileType.SMARTLIFE_DEVICE,
                TileType.PROBREEZE_FAN,
                TileType.AIRROBO_VACUUM -> if (ipAddress.isNotBlank() && secretCode.isNotBlank()) {
                    ProviderId.TUYA_LAN
                } else {
                    ProviderId.TUYA_CLOUD
                }
                else -> if (entityId.isNotBlank()) ProviderId.HOME_ASSISTANT else ProviderId.SYSTEM_LOCAL
            }
        }

    val transportForRecorder: String
        get() = activeProviderId.trim().ifBlank { resolvedProviderId.id }

    val vendorDriverForRecorder: String
        get() = resolvedProviderId.displayName.ifBlank { type.brandName }

    fun capabilityValue(key: String, default: String = ""): String =
        capabilities.firstOrNull { it.key.equals(key, ignoreCase = true) }?.currentValue?.ifBlank { default } ?: default

    fun withUpdatedCapability(key: String, newValue: String): SmartTile {
        val exists = capabilities.any { it.key.equals(key, ignoreCase = true) }
        val updatedCaps = if (exists) {
            capabilities.map { cap ->
                if (cap.key.equals(key, ignoreCase = true)) cap.copy(currentValue = newValue) else cap
            }
        } else {
            capabilities
        }
        return copy(
            capabilities = updatedCaps,
            lytmiHdmiInput = if (key.equals("hdmi_input", true)) newValue.filter { it.isDigit() }.toIntOrNull() ?: lytmiHdmiInput else lytmiHdmiInput,
            lytmiSyncMode = if (key.equals("work_mode", true) || key.equals("sync_mode", true)) newValue else lytmiSyncMode,
            lytmiSceneName = if (key.equals("scene_select", true) || key.equals("scene_data", true)) newValue else lytmiSceneName,
            lytmiDiffusionSpeed = if (key.equals("diffusion_speed", true)) newValue.toFloatOrNull() ?: lytmiDiffusionSpeed else lytmiDiffusionSpeed,
            fanSpeedLevel = if (key.equals("fan_speed", true)) newValue.toIntOrNull() ?: fanSpeedLevel else fanSpeedLevel,
            fanMode = if (key.equals("fan_mode", true)) newValue else fanMode,
            isSocket2Active = if (key.equals("switch_2", true)) newValue.equals("true", true) || newValue == "1" else isSocket2Active
        )
    }

    val resolvedHdmiInput: Int
        get() = capabilityValue("hdmi_input", "").filter { it.isDigit() }.toIntOrNull() ?: lytmiHdmiInput.coerceIn(1, 4)

    val resolvedSyncMode: String
        get() = capabilityValue("work_mode", capabilityValue("sync_mode", lytmiSyncMode))

    val resolvedSceneName: String
        get() = capabilityValue("scene_select", capabilityValue("scene_data", lytmiSceneName))

    val resolvedDiffusionSpeed: Float
        get() = capabilityValue("diffusion_speed", "").toFloatOrNull() ?: lytmiDiffusionSpeed

    val resolvedFanSpeedLevel: Int
        get() = capabilityValue("fan_speed", capabilityValue("windspeed", "")).toIntOrNull() ?: fanSpeedLevel

    val resolvedFanMode: String
        get() = capabilityValue("fan_mode", capabilityValue("mode", fanMode))

    val resolvedVehicleLocked: Boolean
        get() = capabilityValue("vehicle_lock", capabilityValue("lock", "")).let { raw ->
            if (raw.isBlank()) vehicleLocked else raw.equals("true", true) || raw.equals("locked", true) || raw == "1"
        }

    val deviceClass: DeviceClass
        get() = when {
            classificationSource == ClassificationSource.MANUAL_OVERRIDE && deviceClassOverride != null ->
                deviceClassOverride
            hasLytmiOrSyncBoxTraits -> DeviceClass.MEDIA
            hasDynamicKettleOrApplianceTraits -> DeviceClass.APPLIANCE
            isExplicitPlugOrApplianceByTitle -> if (hasPowerTelemetryCapability) DeviceClass.ENERGY else DeviceClass.SWITCH
            isClimateHeatingDevice -> DeviceClass.CLIMATE
            deviceClassOverride != null -> deviceClassOverride
            classificationResolution != null -> classificationResolution!!.deviceClass
            capabilities.isNotEmpty() -> CapabilityClassifier.resolve(
                capabilities = capabilities,
                categoryCode = "",
                multiGangSocketCount = multiGangSocketCount,
                hasPowerTelemetry = hasPowerTelemetryCapability,
                titleHint = title
            ).deviceClass
            else -> type.toSemanticDeviceClass(hasPowerTelemetryCapability)
        }

    val serviceClass: ServiceClass?
        get() = serviceClassOverride ?: type.toDefaultServiceClass(sensorUnit)

    val deviceSubtype: DeviceSubtype
        get() = when {
            classificationSource == ClassificationSource.MANUAL_OVERRIDE && deviceSubtypeOverride != null ->
                deviceSubtypeOverride
            hasLytmiOrSyncBoxTraits -> DeviceSubtype.AMBIENT_SYNC
            hasDynamicKettleOrApplianceTraits -> when {
                title.contains("fryer", ignoreCase = true) -> DeviceSubtype.AIR_FRYER
                title.contains("coffee", ignoreCase = true) -> DeviceSubtype.COFFEE_MACHINE
                else -> DeviceSubtype.KETTLE
            }
            title.contains("pond", ignoreCase = true) || title.contains("pump", ignoreCase = true) ->
                DeviceSubtype.POND_PUMP
            isExplicitPlugOrApplianceByTitle -> DeviceSubtype.SMART_PLUG
            deviceSubtypeOverride != null -> deviceSubtypeOverride
            classificationResolution != null -> classificationResolution!!.deviceSubtype
            isClimateHeatingDevice || capabilities.isNotEmpty() ->
                CapabilityClassifier.resolve(
                    capabilities = capabilities,
                    categoryCode = "",
                    multiGangSocketCount = multiGangSocketCount,
                    hasPowerTelemetry = hasPowerTelemetryCapability,
                    titleHint = title
                ).deviceSubtype
            else -> type.toDefaultDeviceSubtype(
                titleHint = title,
                ipAddressHint = ipAddress,
                sensorUnitHint = sensorUnit,
                fingerprintCategory = inferredApplianceCategory
            )
        }

    val isManualHoldActive: Boolean
        get() = manualHoldUntilEpochMs > System.currentTimeMillis()

    val needsCapabilitySync: Boolean
        get() = isConfiguredAndLinked &&
                capabilities.isEmpty() &&
                lifecycleState in setOf(
            DeviceLifecycleState.AUTHENTICATED,
            DeviceLifecycleState.ACTIVATED,
            DeviceLifecycleState.CAPABILITY_LEARNING
        )

    val effectiveLifecycleState: DeviceLifecycleState
        get() = when {
            !isConfiguredAndLinked -> DeviceLifecycleState.DISCOVERED
            transactionState == CommandTransactionState.HEALING_IN_PROGRESS -> DeviceLifecycleState.RECOVERY
            transactionState == CommandTransactionState.FAILED_ROLLED_BACK -> DeviceLifecycleState.QUARANTINED_ERROR
            !isOnline && lifecycleState in setOf(DeviceLifecycleState.ONLINE, DeviceLifecycleState.OPERATIONAL) ->
                DeviceLifecycleState.OFFLINE
            else -> lifecycleState
        }

    val isVolumeHeroRequested: Boolean
        get() = forceVolumeHeroMode ||
                sensorUnit.equals("VOLUME_HERO", ignoreCase = true) ||
                deviceSubtypeOverride == DeviceSubtype.SPEAKER ||
                deviceSubtypeOverride == DeviceSubtype.CAST_GROUP

    val isRadioHeroRequested: Boolean
        get() = deviceSubtype == DeviceSubtype.RADIO ||
                serviceClass == ServiceClass.RADIO ||
                sensorUnit.equals("RADIO_HERO", ignoreCase = true)

    val effectiveRowSpan: Int
        get() = when {
            rowSpan > 1 -> rowSpan.coerceIn(1, 12)
            isTall -> 2
            else -> 1
        }

    val isGroupCardTile: Boolean
        get() = type == TileType.GROUP_CARD

    val isEnergyTile: Boolean
        get() = type == TileType.ENERGY_MONITOR

    val isMacroTile: Boolean
        get() = type == TileType.MACRO_TILE

    val isVehicleTile: Boolean
        get() = type == TileType.JAGUAR_VEHICLE ||
                type == TileType.MERCEDES_VEHICLE ||
                capabilities.any { it.key.startsWith("vehicle_", ignoreCase = true) }

    val isLytmiTile: Boolean
        get() = hasLytmiOrSyncBoxTraits

    val isAmbientRgbTile: Boolean
        get() = hasLytmiOrSyncBoxTraits ||
                type == TileType.GOVEE_LIGHT ||
                type == TileType.TWINKLY_LIGHT ||
                hasRgbColorCapability

    val isTowerFanTile: Boolean
        get() = type == TileType.PROBREEZE_FAN || hasFanSpeedCapability

    val isSpeakerTile: Boolean
        get() = type == TileType.NEST_MINI ||
                type == TileType.MUSIC_PLAYER ||
                capabilities.any { it.key == "speaker_volume" }

    val isSecurityCenterTile: Boolean
        get() = type == TileType.SECURITY_CENTER

    val isCameraTile: Boolean
        get() = type == TileType.IMOU_CAMERA ||
                type == TileType.EZVIZ_DOORBELL ||
                type == TileType.NEST_DOORBELL ||
                type == TileType.RING_CAMERA

    val isKitchenApplianceTile: Boolean
        get() = hasDynamicKettleOrApplianceTraits ||
                deviceClassOverride == DeviceClass.APPLIANCE ||
                deviceSubtype in setOf(DeviceSubtype.KETTLE, DeviceSubtype.COFFEE_MACHINE, DeviceSubtype.AIR_FRYER)

    val isThermostatTile: Boolean
        get() = !isKitchenApplianceTile && (
                isClimateHeatingDevice ||
                        type == TileType.NEST_THERMOSTAT ||
                        type == TileType.TUYA_RADIATOR ||
                        (deviceClass == DeviceClass.CLIMATE && !isTowerFanTile)
                )

    /**
     * Strictly true only when the climate device is powered on and targetTemperature > temperature + 0.05f
     * (or explicitly in BOOST mode), preventing standby thermostats from glowing orange.
     */
    val isActuallyHeating: Boolean
        get() = isThermostatTile &&
                isConfiguredAndLinked &&
                isOnline &&
                isActive &&
                !hvacMode.equals("OFF", ignoreCase = true) &&
                !hvacMode.equals("ECO", ignoreCase = true) &&
                (targetTemperature > temperature + 0.05f || hvacMode.equals("BOOST", ignoreCase = true))

    val isActivelyHeating: Boolean
        get() = isActuallyHeating

    val isSwitchTile: Boolean
        get() = !hasLytmiOrSyncBoxTraits && !isThermostatTile && type.isSwitchType()

    val isMultiGangSwitch: Boolean
        get() = multiGangSocketCount >= 2 ||
                capabilities.any { it.key.equals("switch_2", ignoreCase = true) || it.key.equals("switch2", ignoreCase = true) }

    // Capability-First Introspection Helpers
    val hasDimmingCapability: Boolean
        get() = type == TileType.HUE_LIGHT ||
                isAmbientRgbTile ||
                capabilities.any {
                    it.key.lowercase(Locale.US) in listOf("brightness", "brightness_pct", "bright_value", "bright_value_v2")
                }

    val hasRgbColorCapability: Boolean
        get() = capabilities.any {
            val k = it.key.lowercase(Locale.US)
            k in listOf("colour_data", "colour_data_v2", "color_data", "rgb_color") ||
                    (k == "work_mode" && it.enumOptions.any { opt -> opt.equals("COLOR", true) || opt.equals("VIDEO", true) })
        }

    val hasHdmiSwitchCapability: Boolean
        get() = capabilities.any { it.key.contains("hdmi", ignoreCase = true) }

    val hasFanSpeedCapability: Boolean
        get() = capabilities.any {
            it.key.lowercase(Locale.US) in listOf("fan_speed", "fan_speed_percent", "windspeed")
        }

    val isTvTrackerHeroRequested: Boolean
        get() = deviceSubtype == DeviceSubtype.TV_TRACKER ||
                serviceClass == ServiceClass.TV_TRACKER ||
                sensorUnit.equals("TV_TRACKER_HERO", ignoreCase = true)

    val isCalendarHeroRequested: Boolean
        get() = deviceSubtype == DeviceSubtype.CALENDAR_HUB ||
                serviceClass == ServiceClass.CALENDAR_HUB ||
                sensorUnit.equals("CALENDAR_HERO", ignoreCase = true)

    val isFootballHeroRequested: Boolean
        get() = deviceSubtype == DeviceSubtype.FOOTBALL_TRACKER ||
                serviceClass == ServiceClass.FOOTBALL_TRACKER ||
                sensorUnit.equals("FOOTBALL_HERO", ignoreCase = true)

    val isGuestWifiHeroRequested: Boolean
        get() = deviceSubtype == DeviceSubtype.GUEST_WIFI ||
                serviceClass == ServiceClass.GUEST_WIFI ||
                sensorUnit.equals("GUEST_WIFI_HERO", ignoreCase = true)

    val hasTemperatureSetpointCapability: Boolean
        get() = !isExplicitPlugOrApplianceByTitle &&
                !channelId.startsWith("SPLIT_SOCKET_") &&
                multiGangSocketCount <= 1 &&
                capabilities.any { cap ->
                    val hasHardwareBacking = cap.dpId.isNotBlank() || type == TileType.NEST_THERMOSTAT
                    val k = cap.key.lowercase(Locale.US)
                    val u = cap.unit.trim().lowercase(Locale.US)
                    val isTimerOrColorTempKey = k.contains("countdown") ||
                            k.contains("timer") ||
                            k.contains("time") ||
                            k.contains("clean") ||
                            k in setOf("temp_value", "temp_value_v2", "temp_unit_convert", "temp_correction", "temp_calibration")
                    val isExactTempUnit = u in setOf("c", "f", "\u00B0c", "\u00B0f", "\u2103", "\u2109", "celsius", "fahrenheit")
                    val isExplicitTempKey = k in setOf(
                        "target_temperature", "temp_set", "set_temp", "target_temp", "temp_boiling",
                        "cook_temperature", "upper_temp", "temperature", "warm_temp"
                    ) || k.endsWith("_temp")
                    hasHardwareBacking && !isTimerOrColorTempKey && cap.isWritable &&
                            cap.kind == DeviceCapabilityKind.NUMERIC_SLIDER &&
                            (isExplicitTempKey || isExactTempUnit)
                }

    val hasLockCapability: Boolean
        get() = type == TileType.SMART_LOCK ||
                isVehicleTile ||
                capabilities.any { it.key.lowercase(Locale.US) in listOf("lock", "vehicle_lock") }

    val inferredApplianceCategory: ApplianceFingerprintCategory
        get() = if (detectedApplianceCategory != ApplianceFingerprintCategory.UNKNOWN) {
            detectedApplianceCategory
        } else {
            AppliancePowerFingerprinter.inferApplianceFromTelemetry(powerWatts, title)
        }

    /**
     * Returns true only when this tile has genuine hardware/cloud/LAN identifiers
     * rather than being an unlinked placeholder or carrying synthetic dummy VINs.
     */
    val isConfiguredAndLinked: Boolean
        get() = when (type) {
            TileType.CLOCK_WEATHER,
            TileType.MUSIC_PLAYER,
            TileType.JARVIS_SCENES,
            TileType.PHOTO_SLIDESHOW,
            TileType.GROUP_CARD,
            TileType.ENERGY_MONITOR,
            TileType.MACRO_TILE,
            TileType.SECURITY_CENTER -> true
            TileType.JAGUAR_VEHICLE,
            TileType.MERCEDES_VEHICLE -> {
                val vin = vehicleVin.ifBlank { serialNumber }.trim()
                val hasRealVin = vin.isNotBlank() &&
                        !vin.equals("SAJAA4BN8HAJAGUAR", ignoreCase = true) &&
                        !vin.equals("W1K20504MERCEDES", ignoreCase = true)
                val hasHaEntity = entityId.startsWith("lock.", ignoreCase = true)
                (hasRealVin || hasHaEntity) && (vehicleFuelOrBatteryPct > 0 || vehicleRangeMiles > 0 || hasHaEntity)
            }
            TileType.IMOU_CAMERA,
            TileType.EZVIZ_DOORBELL,
            TileType.NEST_DOORBELL,
            TileType.RING_CAMERA ->
                serialNumber.isNotBlank() || ipAddress.isNotBlank() || customRtspUrl.isNotBlank() || entityId.isNotBlank()
            else ->
                serialNumber.isNotBlank() || ipAddress.isNotBlank() || entityId.isNotBlank()
        }

    val hasPowerTelemetryCapability: Boolean
        get() = hasExplicitPowerTelemetry ||
                powerWatts > 0f ||
                totalEnergyKwh > 0f ||
                capabilities.any { cap ->
                    cap.key in listOf("cur_power", "power", "cur_voltage", "voltage", "cur_current", "current", "add_ele", "energy") ||
                            cap.unit.equals("W", ignoreCase = true) ||
                            cap.unit.equals("kWh", ignoreCase = true) ||
                            cap.unit.equals("V", ignoreCase = true) ||
                            cap.unit.equals("mA", ignoreCase = true)
                }

    val supportsEnergyTelemetry: Boolean
        get() = hasPowerTelemetryCapability

    val supportsQualityToggle: Boolean
        get() = type == TileType.EZVIZ_DOORBELL || type == TileType.IMOU_CAMERA

    fun resolvedLocalRtspUrl(): String {
        if (customRtspUrl.isNotBlank()) return customRtspUrl.trim()
        val cleanIp = ipAddress.substringBefore(":").trim()
        val port = ipAddress.substringAfter(":", "554").toIntOrNull()?.takeIf { it > 0 } ?: 554
        val cleanPass = secretCode.trim()
        val encodedPass = Uri.encode(cleanPass)
        return when (type) {
            TileType.IMOU_CAMERA -> {
                val subType = if (useSubStream) 1 else 0
                val rawChan = channelId.trim().toIntOrNull() ?: 1
                val rtspChan = if (rawChan <= 0) 1 else rawChan
                if (cleanIp.isNotBlank() && cleanPass.isNotBlank()) {
                    "rtsp://admin:$encodedPass@$cleanIp:$port/cam/realmonitor?channel=$rtspChan&subtype=$subType"
                } else ""
            }
            TileType.EZVIZ_DOORBELL -> {
                val channelCode = if (useSubStream) "102" else "101"
                if (cleanIp.isNotBlank() && cleanPass.isNotBlank()) {
                    "rtsp://admin:$encodedPass@$cleanIp:$port/Streaming/Channels/$channelCode"
                } else ""
            }
            else -> customRtspUrl.trim()
        }
    }
}

data class BridgeSettings(
    val haUrl: String = "http://homeassistant.local:8123",
    val haToken: String = DEFAULT_HA_TOKEN,
    val imouAppId: String = DEFAULT_IMOU_APP_ID,
    val imouAppSecret: String = DEFAULT_IMOU_APP_SECRET,
    val imouApiHost: String = DEFAULT_IMOU_API_HOST,
    val ezvizAppKey: String = "",
    val ezvizAppSecret: String = "",
    val ezvizApiHost: String = DEFAULT_EZVIZ_API_HOST,
    val nestProjectId: String = DEFAULT_NEST_PROJECT_ID,
    val nestClientId: String = DEFAULT_NEST_CLIENT_ID,
    val nestClientSecret: String = DEFAULT_NEST_CLIENT_SECRET,
    val nestRefreshToken: String = DEFAULT_NEST_REFRESH_TOKEN,
    val nestPubSubSubscription: String = DEFAULT_NEST_PUBSUB_SUB,
    val tuyaAccessId: String = DEFAULT_TUYA_ACCESS_ID,
    val tuyaAccessSecret: String = DEFAULT_TUYA_ACCESS_SECRET,
    val tuyaUid: String = DEFAULT_TUYA_UID,
    val tuyaApiHost: String = DEFAULT_TUYA_API_HOST,
    val tplinkEmail: String = "",
    val tplinkPassword: String = "",
    val ewelinkEmail: String = "",
    val ewelinkPassword: String = "",
    val mqttBrokerHost: String = "",
    val mqttBrokerPort: Int = 1883,
    val mqttUsername: String = "",
    val mqttPassword: String = "",
    // Jaguar InControl, Mercedes me, Lytmi, Govee, Twinkly & Plex credentials
    val jaguarEmail: String = "",
    val jaguarPassword: String = "",
    val jaguarPin: String = "",
    val jaguarVin: String = "",
    val mercedesEmail: String = "",
    val mercedesPassword: String = "",
    val mercedesPin: String = "",
    val mercedesVin: String = "",
    val lytmiIpAddress: String = "",
    val lytmiDeviceId: String = "",
    val lytmiLocalKey: String = "",
    val goveeApiKey: String = "",
    val goveeIpAddress: String = "",
    val twinklyIpAddress: String = "",
    val plexServerUrl: String = "",
    val plexToken: String = "",
    val musicProvider: MusicProvider = MusicProvider.SPOTIFY,
    val appThemeMode: AppThemeMode = AppThemeMode.DARK,
    val accentColorPreset: AppAccentColorPreset = AppAccentColorPreset.MINT_GREEN,
    val customAccentColorArgb: Long = DEFAULT_ACCENT_COLOR_ARGB,
    val clockFaceStyle: ClockFaceStyle = ClockFaceStyle.RADIAL_ORB,
    val heroCardTimeoutSeconds: Int = 12,
    val keepScreenAwake: Boolean = false,
    val sleepTimeoutSeconds: Int = 90,
    val enablePresenceWake: Boolean = true,
    val enableVoiceAnnouncements: Boolean = true,
    val cloudStreamAutoPauseSeconds: Int = 120,
    val showRoomLabelsOnTiles: Boolean = true,
    val showTemperatureOnTiles: Boolean = false,
    val enableGroupCards: Boolean = true,
    val customRooms: List<String> = emptyList(),
    val dashboardGreetingName: String = "Clara",
    val enableVoiceControl: Boolean = true,
    val enableWakeWordListening: Boolean = true,
    val voiceWakeWord: String = "Hey Clara",
    val enableNestMiniDoorbellChime: Boolean = true,
    val nestMiniDoorbellVolume: Int = 75,
    val doorbellChimeMessage: String = "Someone is at the front door",
    val doorbellChimeTargetIps: Set<String> = emptySet(),
    val customDoorbellMp3Uri: String = "",
    val customAlarmMp3Uri: String = "",
    val enableSwipeableSlideshowPage: Boolean = true,
    val showClockOnSlideshowPage: Boolean = true,
    val slideshowSourceType: SlideshowSourceType = SlideshowSourceType.LOCAL_DEVICE,
    val slideshowCloudUrl: String = "",
    val slideshowLocalUris: List<String> = emptyList(),
    val slideshowIntervalSeconds: Int = 10,
    val slideshowIncludeSubfolders: Boolean = true,
    val dashboardCardOrder: List<String> = DashboardCardSlot.defaultOrderKeys,
    val dashboardCardWidthWeights: Map<String, Float> = DashboardCardSlot.defaultWidthMap,
    val dashboardCardHeightWeights: Map<String, Float> = DashboardCardSlot.defaultHeightMap,
    // Per-Card Zoom Scales, Interchangeable Slot Widgets, DPI Scaling, Templates, Nav Docking & Roles
    val dashboardCardZoomScales: Map<String, Float> = DashboardCardSlot.defaultZoomMap,
    val dashboardSlotWidgetTypes: Map<String, DashboardWidgetType> = DashboardCardSlot.defaultWidgetTypeMap,
    val dpiScalePreset: DpiScalePreset = DpiScalePreset.DEFAULT,
    val customDpiScalePct: Int = 100,
    val gridSizePreset: DashboardGridSizePreset = DashboardGridSizePreset.COMPACT,
    val dashboardTemplate: DashboardTemplate = DashboardTemplate.STANDARD_QUAD,
    val navDockPosition: NavDockPosition = NavDockPosition.LEFT,
    val invertNavOrder: Boolean = false,
    val activeUserRole: UserRole = UserRole.OWNER,
    val rolePinCode: String = "",
    val requirePinForAdminAndVehicles: Boolean = false,
    val autoOnboardNewDevicesEnabled: Boolean = false,
    val userProfiles: List<UserProfile> = DEFAULT_USER_PROFILES,
    val activeUserProfileId: String = DEFAULT_USER_PROFILES.first().id,
    val customRadioStations: List<RadioStationStream> = DEFAULT_RADIO_STATIONS,
    val activeRadioStationId: String = DEFAULT_RADIO_STATIONS.first().id,
    val hasUserCustomizedTrackedShows: Boolean = true,
    val calendarEvents: List<HouseholdCalendarEvent> = DEFAULT_HOUSEHOLD_CALENDAR_EVENTS,
    val councilBinsConfig: UkCouncilBinsConfig = UkCouncilBinsConfig(),
    val followedFootballTeams: List<String> = DEFAULT_FOLLOWED_FOOTBALL_TEAMS,
    val hasUserCustomizedFootballTeams: Boolean = false,
    val iboChannelMappings: Map<String, Int> = DEFAULT_IBO_CHANNEL_MAPPINGS,
    val footballTvProviderConfig: FootballTvProviderConfig = FootballTvProviderConfig(),
    val guestWifiConfig: GuestWifiConfig = GuestWifiConfig(),
    val jaguarRegPlate: String = "",
    val mercedesRegPlate: String = "",
    val electricityTariffDayRateGbp: Float = 0.28f,
    val electricityTariffNightRateGbp: Float = 0.09f,
    val electricityStandingChargeGbp: Float = 0.53f,
    val currencySymbol: String = "\u00A3",
    val offPeakStartHour: Int = 0,
    val offPeakEndHour: Int = 7,
    val securityCenterMode: SecurityCenterMode = SecurityCenterMode.HOME,
    val securityHomeTempC: Float = 20.5f,
    val securityAwayTempC: Float = 18.0f,
    val securityNightTempC: Float = 18.5f,
    val securityVacationTempC: Float = 16.0f,
    val localLlmEndpointUrl: String = "",
    val smartThingsToken: String = "",
    val frigateHostUrl: String = "",
    val teslaAccessToken: String = "",
    val teslaVin: String = "",
    val teslaWallConnectorIp: String = "",
    val hubitatHostUrl: String = "",
    val hubitatAppId: String = "",
    val hubitatAccessToken: String = "",
    val homeyHostUrl: String = "",
    val homeyBearerToken: String = "",
    val octopusApiKey: String = "",
    val octopusMpan: String = "",
    val octopusSerialNumber: String = "",
    val octopusTariffCode: String = "",
    val powerwallHostIp: String = "",
    val powerwallPassword: String = "",
    val givEnergyApiKey: String = "",
    val givEnergyInverterSerial: String = "",
    val solarEdgeApiKey: String = "",
    val solarEdgeSiteId: String = "",
    val unifiControllerUrl: String = "",
    val unifiUsername: String = "",
    val unifiPassword: String = ""
) {
    val effectiveDpiScaleFactor: Float
        get() = when (dpiScalePreset) {
            DpiScalePreset.CUSTOM -> (customDpiScalePct.coerceIn(70, 150) / 100f)
            else -> dpiScalePreset.scaleFactor
        }

    val hasConfiguredOwnerPin: Boolean
        get() = rolePinCode.trim().length >= 4 ||
                userProfiles.any { (it.role == UserRole.OWNER || it.role == UserRole.ADMIN) && it.customPinCode.trim().length >= 4 }

    val activeUserProfile: UserProfile
        get() {
            val raw = userProfiles.firstOrNull { it.id == activeUserProfileId }
                ?: userProfiles.firstOrNull { it.role == activeUserRole }
                ?: UserProfile(
                    id = "profile_${activeUserRole.name.lowercase(Locale.US)}",
                    name = activeUserRole.label,
                    role = activeUserRole,
                    isParentalLocked = false,
                    requirePinToSwitchOut = false
                )
            return if (hasConfiguredOwnerPin) {
                raw
            } else {
                raw.copy(isParentalLocked = false, requirePinToSwitchOut = false)
            }
        }

    val isParentalLockActive: Boolean
        get() = hasConfiguredOwnerPin && (
                activeUserProfile.isParentalLocked ||
                        activeUserRole == UserRole.CHILD ||
                        activeUserRole == UserRole.GUEST
                )

    val hasConfiguredPin: Boolean
        get() = hasConfiguredOwnerPin || activeUserProfile.customPinCode.trim().length >= 4

    // Returns the user's saved Favourite Radio Stations (removable & customizable)
    val allRadioStations: List<RadioStationStream>
        get() = customRadioStations
}

data class DiscoveredCamera(
    val deviceId: String,
    val name: String,
    val status: String,
    val channelId: String
)

data class DiscoveredNestDevice(
    val deviceName: String,
    val displayName: String,
    val roomName: String,
    val suggestedType: TileType,
    val ambientTempC: Float,
    val targetTempC: Float,
    val humidityPct: Int,
    val hvacMode: String,
    val hvacStatus: String,
    val isOnline: Boolean
)

data class DiscoveredTuyaDevice(
    val deviceId: String,
    val name: String,
    val category: String,
    val localKey: String,
    val isOnline: Boolean,
    val suggestedType: TileType,
    val isActive: Boolean = false,
    val isSocket2Active: Boolean = false,
    val multiGangSocketCount: Int = 1,
    val socket1Name: String = "Socket 1",
    val socket2Name: String = "Socket 2",
    val currentTempC: Float = 20.5f,
    val targetTempC: Float = 21.0f,
    val brightnessOrBatteryPct: Float = if (multiGangSocketCount >= 2) (if (isSocket2Active) 100f else 0f) else 80f,
    val powerWatts: Float = 0f,
    val voltageVolts: Float = 0f,
    val currentMilliamps: Float = 0f,
    val totalEnergyKwh: Float = 0f,
    val countdown1Seconds: Int = 0,
    val countdown2Seconds: Int = 0,
    val primarySwitchCode: String = "switch_1",
    val tempSetCode: String = "temp_set",
    val tempScaleDivisor: Float = 10f,
    val capabilities: List<DeviceCapabilityDescriptor> = emptyList(),
    val hasPowerTelemetry: Boolean = false,
    val lytmiHdmiInput: Int = 1,
    val lytmiSyncMode: String = "VIDEO",
    val lytmiSceneName: String = "Cyberpunk",
    val lytmiDiffusionSpeed: Float = 75f,
    val fanSpeedLevel: Int = 3,
    val fanMode: String = "NORMAL",
    val fanOscillating: Boolean = false,
    val fanIonizerActive: Boolean = false,
    val fanTimerHours: Int = 0,
    val deviceClass: DeviceClass = if (capabilities.isNotEmpty()) {
        SemanticClassifier.resolve(
            capabilities = capabilities,
            categoryCode = category,
            multiGangSocketCount = multiGangSocketCount,
            hasPowerTelemetry = hasPowerTelemetry,
            titleHint = name
        ).deviceClass
    } else {
        suggestedType.toSemanticDeviceClass(hasPowerTelemetry)
    },
    val deviceSubtype: DeviceSubtype = if (capabilities.isNotEmpty()) {
        SemanticClassifier.resolve(
            capabilities = capabilities,
            categoryCode = category,
            multiGangSocketCount = multiGangSocketCount,
            hasPowerTelemetry = hasPowerTelemetry,
            titleHint = name
        ).deviceSubtype
    } else {
        suggestedType.toDefaultDeviceSubtype(name)
    },
    val classificationConfidence: Float = 0.94f,
    val classificationSource: ClassificationSource = ClassificationSource.CAPABILITY_CLASSIFIER,
    val classificationVersion: Int = CapabilityClassifier.CURRENT_VERSION,
    val classificationLastEvaluatedEpochMs: Long = System.currentTimeMillis()
)

data class DiscoveredLanDevice(
    val ipAddress: String,
    val port: Int,
    val suggestedName: String,
    val suggestedType: TileType,
    val detail: String,
    val companionPackage: String = "",
    val actionType: DiscoveredDeviceActionType = DiscoveredDeviceActionType.ADD_DIRECT,
    val protocolBadge: String = "Wi-Fi LAN",
    val hardwareIdOrMac: String = "",
    val brandOption: BrandOption? = null
)

data class DiscoveredHaEntity(
    val entityId: String,
    val friendlyName: String,
    val state: String,
    val suggestedType: TileType,
    val suggestedRoom: String = "",
    val temperature: Float = 20.5f,
    val targetTemperature: Float = 22.0f,
    val levelOrBrightness: Float = 100f,
    val unitOfMeasurement: String = "",
    val capabilities: List<DeviceCapabilityDescriptor> = emptyList(),
    val hasPowerTelemetry: Boolean = false
)

data class AutomationTargetSelector(
    val scope: AutomationTargetScope = AutomationTargetScope.SINGLE_TILE,
    val roomFilter: String = "",
    val deviceClass: DeviceClass? = null,
    val deviceSubtype: DeviceSubtype? = null,
    val requiredCapabilityKey: String = ""
) {
    val isDynamic: Boolean
        get() = scope == AutomationTargetScope.DYNAMIC_SELECTOR &&
                (deviceClass != null || deviceSubtype != null || requiredCapabilityKey.isNotBlank() || roomFilter.isNotBlank())

    fun toSummaryLabel(): String {
        val roomStr = if (roomFilter.isNotBlank() && !roomFilter.equals("All Rooms", true)) "$roomFilter " else "All "
        val targetStr = when {
            deviceSubtype != null && deviceSubtype != DeviceSubtype.GENERIC -> deviceSubtype.label
            deviceClass != null -> "${deviceClass.label} Devices"
            else -> "Matching Devices"
        }
        val capStr = if (requiredCapabilityKey.isNotBlank()) " [$requiredCapabilityKey]" else ""
        return "$roomStr$targetStr$capStr"
    }
}

data class AutomationSecondaryCondition(
    val id: String = UUID.randomUUID().toString(),
    val conditionType: AutomationTriggerType = AutomationTriggerType.DEVICE_STATE_CHANGE,
    val targetTileId: String = "",
    val targetTileTitle: String = "",
    val targetDeviceState: DeviceTriggerTargetState = DeviceTriggerTargetState.TURNED_ON,
    val comparisonOperator: ComparisonOperator = ComparisonOperator.BELOW,
    val thresholdValue: Float = 19.0f,
    val weatherKeyword: String = "Rain",
    val targetSecurityMode: SecurityCenterMode = SecurityCenterMode.AWAY,
    val requireOffPeakTariff: Boolean = true,
    val requireNighttime: Boolean = true,
    val capabilityKey: String = "",
    val capabilityExpectedText: String = "",
    val targetSelector: AutomationTargetSelector = AutomationTargetSelector()
) {
    fun toSummaryText(): String = when (conditionType) {
        AutomationTriggerType.DEVICE_STATE_CHANGE -> {
            val subject = if (targetSelector.isDynamic) targetSelector.toSummaryLabel() else targetTileTitle.ifBlank { "Device" }
            "$subject ${targetDeviceState.label}"
        }
        AutomationTriggerType.TEMPERATURE_THRESHOLD -> {
            val subject = if (targetSelector.isDynamic) targetSelector.toSummaryLabel() else targetTileTitle.ifBlank { "Thermostat" }
            "$subject ${comparisonOperator.symbol} ${"%.1f".format(Locale.US, thresholdValue)}\u00B0C"
        }
        AutomationTriggerType.POWER_WATTS_THRESHOLD -> {
            val subject = if (targetSelector.isDynamic) targetSelector.toSummaryLabel() else targetTileTitle.ifBlank { "Plug" }
            "$subject ${comparisonOperator.symbol} ${thresholdValue.toInt()}W"
        }
        AutomationTriggerType.WEATHER_CONDITION ->
            if (weatherKeyword.equals("TEMP", ignoreCase = true)) {
                "Outdoor Temp ${comparisonOperator.symbol} ${thresholdValue.toInt()}\u00B0C"
            } else {
                "Weather is '$weatherKeyword'"
            }
        AutomationTriggerType.SECURITY_MODE_IS ->
            "Security Mode is ${targetSecurityMode.label}"
        AutomationTriggerType.TARIFF_WINDOW_IS ->
            if (requireOffPeakTariff) "Tariff is Off-Peak Night Rate" else "Tariff is Peak Day Rate"
        AutomationTriggerType.DAYLIGHT_IS ->
            if (requireNighttime) "Outdoors is Nighttime" else "Outdoors is Daytime"
        AutomationTriggerType.CAPABILITY_VALUE_MATCH -> {
            val subject = if (targetSelector.isDynamic) targetSelector.toSummaryLabel() else targetTileTitle.ifBlank { "Device" }
            val capName = capabilityKey.ifBlank { "capability" }
            if (capabilityExpectedText.isNotBlank()) {
                "$subject [$capName == $capabilityExpectedText]"
            } else {
                "$subject [$capName ${comparisonOperator.symbol}${"%.1f".format(Locale.US, thresholdValue)}]"
            }
        }
        else -> conditionType.label
    }
}

data class AutomationAction(
    val id: String = UUID.randomUUID().toString(),
    val targetTileId: String = "",
    val targetTileTitle: String = "",
    val actionType: AutomationActionType = AutomationActionType.TURN_ON,
    val numericValue: Float = 5.0f,
    val textValue: String = "MINUTES",
    val targetSelector: AutomationTargetSelector = AutomationTargetSelector()
) {
    val effectiveTargetLabel: String
        get() = if (targetSelector.isDynamic) targetSelector.toSummaryLabel() else targetTileTitle.ifBlank { "Device" }

    fun toSummaryText(): String = when (actionType) {
        AutomationActionType.TURN_ON -> "Turn ON $effectiveTargetLabel"
        AutomationActionType.TURN_OFF -> "Turn OFF $effectiveTargetLabel"
        AutomationActionType.TOGGLE -> "Toggle $effectiveTargetLabel"
        AutomationActionType.SOCKET_1_ON -> "$effectiveTargetLabel: Socket 1 ON"
        AutomationActionType.SOCKET_1_OFF -> "$effectiveTargetLabel: Socket 1 OFF"
        AutomationActionType.SOCKET_2_ON -> "$effectiveTargetLabel: Socket 2 ON"
        AutomationActionType.SOCKET_2_OFF -> "$effectiveTargetLabel: Socket 2 OFF"
        AutomationActionType.BOTH_SOCKETS_ON -> "$effectiveTargetLabel: Both Sockets ON"
        AutomationActionType.BOTH_SOCKETS_OFF -> "$effectiveTargetLabel: Both Sockets OFF"
        AutomationActionType.SET_TEMPERATURE -> "Set $effectiveTargetLabel to${"%.1f".format(Locale.US, numericValue)}\u00B0C"
        AutomationActionType.SET_HVAC_MODE -> "Set $effectiveTargetLabel mode to$textValue"
        AutomationActionType.SET_LEVEL_OR_VOLUME -> "Set $effectiveTargetLabel to${numericValue.toInt()}%"
        AutomationActionType.RING_NEST_MINI_CHIME ->
            "Chime on ${targetTileTitle.ifBlank { "Nest Mini" }}: \"$textValue\""
        AutomationActionType.OPEN_LIVE_CAMERA -> "Open Live Feed on $effectiveTargetLabel"
        AutomationActionType.CLOSE_LIVE_CAMERA -> "Close Live Feed on $effectiveTargetLabel"
        AutomationActionType.WAIT_DELAY -> {
            val amount = numericValue.toInt().coerceAtLeast(1)
            val unitLabel = when (textValue.uppercase(Locale.US)) {
                "SECONDS" -> "sec"
                "HOURS" -> "hr"
                else -> "min"
            }
            "Wait $amount$unitLabel"
        }
        AutomationActionType.EXECUTE_SCENE -> "Run Routine '$textValue'"
        AutomationActionType.SPEAK_ANNOUNCEMENT -> "Speak \"$textValue\""
    }
}

data class AutomationBranch(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "Conditional Branch",
    val combinator: ConditionCombinator = ConditionCombinator.AND,
    val conditions: List<AutomationSecondaryCondition> = emptyList(),
    val actions: List<AutomationAction> = emptyList()
) {
    fun toSummaryText(): String {
        val condStr = if (conditions.isEmpty()) "Always" else conditions.joinToString(" ${combinator.name} ") { it.toSummaryText() }
        val actStr = actions.joinToString(" \u2192 ") { it.toSummaryText() }
        return "[$label: IF $condStr \u2192 $actStr]"
    }
}

data class AutomationRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val isEnabled: Boolean = true,
    val triggerType: AutomationTriggerType = AutomationTriggerType.TIME_SCHEDULE,
    val scheduleHour: Int = 7,
    val scheduleMinute: Int = 30,
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val triggerOnSunset: Boolean = true,
    val triggerTileId: String = "",
    val triggerTileTitle: String = "",
    val triggerDeviceState: DeviceTriggerTargetState = DeviceTriggerTargetState.TURNED_ON,
    val comparisonOperator: ComparisonOperator = ComparisonOperator.BELOW,
    val thresholdValue: Float = 19.0f,
    val weatherKeyword: String = "Rain",
    val restrictToTimeWindow: Boolean = false,
    val windowStartHour: Int = 18,
    val windowStartMinute: Int = 0,
    val windowEndHour: Int = 7,
    val windowEndMinute: Int = 0,
    val combinator: ConditionCombinator = ConditionCombinator.AND,
    val secondaryConditions: List<AutomationSecondaryCondition> = emptyList(),
    val actions: List<AutomationAction> = emptyList(),
    val branches: List<AutomationBranch> = emptyList(),
    val otherwiseActions: List<AutomationAction> = emptyList(),
    val lastTriggeredEpochMs: Long = 0L
) {
    fun formattedDaysSummary(): String {
        if (daysOfWeek.size >= 7) return "Every Day"
        if (daysOfWeek == setOf(1, 2, 3, 4, 5)) return "Weekdays (Mon\u2013Fri)"
        if (daysOfWeek == setOf(6, 7)) return "Weekends (Sat\u2013Sun)"
        val labels = mapOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")
        return daysOfWeek.sorted().mapNotNull { labels[it] }.joinToString(", ")
    }

    fun formattedIfCondition(): String {
        val baseIf = when (triggerType) {
            AutomationTriggerType.TIME_SCHEDULE ->
                "IF Time is %02d:%02d (%s)".format(scheduleHour, scheduleMinute, formattedDaysSummary())
            AutomationTriggerType.SUNRISE_SUNSET ->
                "IF Daylight changes to ${if (triggerOnSunset) "Sunset (Night)" else "Sunrise (Day)"} (${formattedDaysSummary()})"
            AutomationTriggerType.DEVICE_STATE_CHANGE ->
                "IF ${triggerTileTitle.ifBlank { "Device" }} ${triggerDeviceState.label}"
            AutomationTriggerType.TEMPERATURE_THRESHOLD ->
                "IF ${triggerTileTitle.ifBlank { "Thermostat" }} Temp ${comparisonOperator.symbol}${"%.1f".format(Locale.US, thresholdValue)}\u00B0C"
            AutomationTriggerType.POWER_WATTS_THRESHOLD ->
                "IF ${triggerTileTitle.ifBlank { "Smart Plug" }} Power ${comparisonOperator.symbol}${thresholdValue.toInt()}W"
            AutomationTriggerType.DOORBELL_RING ->
                "IF Any Doorbell Rings"
            AutomationTriggerType.WEATHER_CONDITION ->
                if (weatherKeyword.equals("TEMP", ignoreCase = true)) {
                    "IF Outdoor Temp ${comparisonOperator.symbol}${thresholdValue.toInt()}\u00B0C"
                } else {
                    "IF Outdoor Weather is '$weatherKeyword'"
                }
            else -> "IF ${triggerType.label}"
        }
        val extraConditionsSuffix = if (secondaryConditions.isNotEmpty()) {
            val joinWord = " ${combinator.name} "
            joinWord + secondaryConditions.joinToString(joinWord) { it.toSummaryText() }
        } else ""
        val windowSuffix = if (restrictToTimeWindow && triggerType != AutomationTriggerType.TIME_SCHEDULE) {
            " [Only %02d:%02d\u2013%02d:%02d]".format(windowStartHour, windowStartMinute, windowEndHour, windowEndMinute)
        } else ""
        return baseIf + extraConditionsSuffix + windowSuffix
    }

    fun formattedThenActions(): String {
        if (branches.isNotEmpty()) {
            val branchText = branches.joinToString(" | ") { it.toSummaryText() }
            val otherwiseSuffix = if (otherwiseActions.isNotEmpty()) {
                " | [Otherwise \u2192 ${otherwiseActions.joinToString(" \u2192 ") { it.toSummaryText() }}]"
            } else ""
            return "BRANCHES: $branchText$otherwiseSuffix"
        }
        if (actions.isEmpty()) return "THEN (No actions configured)"
        return "THEN " + actions.joinToString(" \u2192 ") { it.toSummaryText() }
    }
}

data class SimulatedDeviceMutation(
    val tileId: String,
    val tileTitle: String,
    val roomName: String,
    val deviceClassLabel: String,
    val currentStateSummary: String,
    val projectedStateSummary: String,
    val wouldBeBlockedByHold: Boolean = false,
    val blockOrOfflineReason: String = "",
    val estimatedWattsDelta: Float = 0f
)

data class SimulationReport(
    val scenarioTitle: String,
    val matchedBranchLabel: String = "Primary Actions",
    val mutations: List<SimulatedDeviceMutation> = emptyList(),
    val totalWattsSaved: Float = 0f,
    val estimatedHourlyCostDeltaGbp: Float = 0f
)

data class HabitActuationEvent(
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val tileId: String,
    val tileTitle: String,
    val actionType: AutomationActionType,
    val numericValue: Float = 0f,
    val textValue: String = "",
    val hourOfDay: Int,
    val minuteOfHour: Int,
    val isoDayOfWeek: Int
)

data class SuggestedAutomationDraft(
    val id: String = UUID.randomUUID().toString(),
    val confidenceOccurrences: Int,
    val reasonExplanation: String,
    val draftRule: AutomationRule
)

data class AutomationStepLog(
    val stepNumber: Int,
    val stepSummary: String,
    val succeeded: Boolean,
    val reasonOrDetail: String,
    val resolvedTargetCount: Int = 1
)

data class AutomationLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val ruleId: String,
    val ruleName: String,
    val triggerSource: String,
    val status: AutomationRunStatus,
    val userFriendlySummary: String,
    val matchedBranchLabel: String = "",
    val stepLogs: List<AutomationStepLog> = emptyList()
)

data class JarvisPlatformEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val category: JarvisEventCategory = JarvisEventCategory.SYSTEM,
    val sourceDeviceId: String = "",
    val sourceDeviceTitle: String = "",
    val roomName: String = "",
    val eventType: String = "",
    val summary: String = "",
    val cameraAiEvent: CameraAiEventType = CameraAiEventType.NONE,
    val metadata: Map<String, String> = emptyMap()
)

data class IntegrationHealthRecord(
    val integrationKey: String,
    val displayName: String,
    val isOnline: Boolean = true,
    val tokenValid: Boolean = true,
    val lastSyncEpochMs: Long = System.currentTimeMillis(),
    val averageLatencyMs: Long = 16L,
    val consecutiveFailures: Int = 0,
    val onlineDeviceCount: Int = 0,
    val offlineDeviceCount: Int = 0,
    val statusMessage: String = "Connected & Healthy"
)

data class SelfHealingActionLog(
    val id: String = UUID.randomUUID().toString(),
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val deviceId: String,
    val deviceTitle: String,
    val integrationName: String,
    val stageReached: Int = 1,
    val stageLabel: String = "Stage 1: LAN Re-Probe",
    val succeeded: Boolean = true,
    val diagnosticDetail: String = ""
)

data class TelemetryDataPoint(
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val deviceId: String,
    val deviceTitle: String,
    val metricKey: String,
    val value: Float,
    val unit: String = ""
)

data class DvrEventSnapshot(
    val id: String = UUID.randomUUID().toString(),
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val cameraTileId: String,
    val cameraTitle: String,
    val roomName: String = "",
    val aiEventType: CameraAiEventType = CameraAiEventType.PERSON,
    val localFilePathOrUri: String = "",
    val summaryText: String = ""
)

data class SecurityAuditLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val actorRole: UserRole = UserRole.OWNER,
    val actionType: String,
    val targetName: String,
    val detail: String,
    val succeeded: Boolean = true
)

data class DigitalHomeGraphDeviceNode(
    val tileId: String,
    val title: String,
    val roomName: String,
    val type: TileType,
    val deviceClass: DeviceClass = type.toSemanticDeviceClass(),
    val deviceSubtype: DeviceSubtype = type.toDefaultDeviceSubtype(),
    val serviceClass: ServiceClass? = type.toDefaultServiceClass(),
    val lifecycleState: DeviceLifecycleState = DeviceLifecycleState.ONLINE,
    val isOnline: Boolean = true,
    val isActive: Boolean = false,
    val capabilities: List<DeviceCapabilityDescriptor> = emptyList(),
    val capabilitySchemaHash: String = "",
    val statusSummary: String = "",
    val transactionState: CommandTransactionState = CommandTransactionState.IDLE,
    val stateVersion: Long = 1L,
    val stateAuthorityTier: StateAuthorityTier = StateAuthorityTier.DEVICE_HARDWARE_TRUTH,
    val manualHoldUntilEpochMs: Long = 0L,
    val lastCausalActor: String = "",
    val activeProviderId: String = "",
    val lastUpdatedEpochMs: Long = System.currentTimeMillis()
)

data class DigitalHomeGraphRoomNode(
    val roomName: String,
    val deviceIds: List<String> = emptyList(),
    val activeDeviceCount: Int = 0,
    val averageTempCelsius: Float? = null,
    val totalPowerWatts: Float = 0f
)

data class EnergyTariffHalfHourSlot(
    val validFromIso: String,
    val validToIso: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val rateIncVatPence: Float,
    val isPlungeOrNegative: Boolean = rateIncVatPence <= 0f,
    val isOffPeakDispatchSlot: Boolean = rateIncVatPence <= 12.0f
)

data class EnergyTariffCapability(
    val providerId: ProviderId = ProviderId.OCTOPUS_ENERGY,
    val tariffCode: String = "",
    val currentRatePence: Float = 28.0f,
    val nextLowestSlot: EnergyTariffHalfHourSlot? = null,
    val upcomingSlots: List<EnergyTariffHalfHourSlot> = emptyList(),
    val lastSyncedEpochMs: Long = System.currentTimeMillis()
)

data class EnergyStorageCapability(
    val providerId: ProviderId = ProviderId.TESLA_ENERGY,
    val deviceId: String,
    val batterySocPct: Int = 0,
    val solarGenerationWatts: Float = 0f,
    val batteryFlowWatts: Float = 0f, // positive = charging, negative = discharging
    val homeLoadWatts: Float = 0f,
    val gridFlowWatts: Float = 0f, // positive = importing, negative = exporting
    val reservePercent: Int = 20,
    val operatingMode: String = "SELF_CONSUMPTION"
)

data class SceneTriggerCapability(
    val sceneId: String,
    val sceneName: String,
    val providerId: ProviderId,
    val roomHint: String = ""
)

data class ObjectDetectionCapability(
    val cameraId: String,
    val cameraName: String,
    val detectedLabel: CameraAiEventType = CameraAiEventType.NONE,
    val confidenceScore: Float = 0f,
    val zoneNames: List<String> = emptyList(),
    val snapshotUrl: String = "",
    val eventTimestampEpochMs: Long = System.currentTimeMillis()
)

data class PresenceCapability(
    val personOrDeviceId: String,
    val displayName: String,
    val isHome: Boolean = true,
    val currentRoomOrApName: String = "",
    val rssiDbm: Int = -60,
    val sourceProtocol: String = "UNIFI_BLE_FUSION",
    val lastSeenEpochMs: Long = System.currentTimeMillis()
)

data class CanonicalDeviceNode(
    val canonicalDeviceId: String,
    val primaryTileId: String,
    val title: String,
    val roomName: String,
    val floorName: String = "Ground Floor",
    val deviceClass: DeviceClass,
    val deviceSubtype: DeviceSubtype,
    val activeProviderId: ProviderId,
    val multiHomedProviderIds: List<ProviderId> = emptyList(),
    val lanIpAddress: String = "",
    val hardwareMacOrSerial: String = "",
    val bridgeEntityId: String = "",
    val capabilities: List<DeviceCapabilityDescriptor> = emptyList(),
    val capabilitySchemaHash: String = "",
    val isOnline: Boolean = true,
    val isActive: Boolean = false
)

data class KnowledgeGraphNode(
    val nodeId: String,
    val primitiveType: KnowledgeGraphPrimitiveType,
    val label: String,
    val attributes: Map<String, String> = emptyMap(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class KnowledgeGraphEdge(
    val sourceNodeId: String,
    val targetNodeId: String,
    val edgeType: KnowledgeGraphEdgeType,
    val weight: Float = 1.0f
)

data class MultiHopContextInference(
    val inferenceId: String,
    val summary: String,
    val pathNodes: List<String> = emptyList(),
    val confidence: Float = 0.90f,
    val recommendedActionTileId: String = "",
    val recommendedActionType: AutomationActionType = AutomationActionType.TURN_ON,
    val recommendedNumericValue: Float = 0f,
    val evaluatedAtEpochMs: Long = System.currentTimeMillis()
)

data class DigitalHomeGraphSnapshot(
    val generatedAtEpochMs: Long = System.currentTimeMillis(),
    val graphVersion: Long = 1L,
    val primaryAuthorityInstallationUid: String = "",
    val isPrimaryMeshAuthority: Boolean = true,
    val homeName: String = "Clara Home",
    val activeRole: UserRole = UserRole.OWNER,
    val securityMode: SecurityCenterMode = SecurityCenterMode.HOME,
    val rooms: List<DigitalHomeGraphRoomNode> = emptyList(),
    val devices: List<DigitalHomeGraphDeviceNode> = emptyList(),
    val canonicalDeviceNodes: List<CanonicalDeviceNode> = emptyList(),
    val knowledgeNodes: List<KnowledgeGraphNode> = emptyList(),
    val knowledgeEdges: List<KnowledgeGraphEdge> = emptyList(),
    val activeInferences: List<MultiHopContextInference> = emptyList(),
    val automationCount: Int = 0,
    val totalLiveWatts: Float = 0f,
    val onlineDeviceCount: Int = 0,
    val offlineDeviceCount: Int = 0
)

data class ScannedWifiNetworkOption(
    val ssid: String,
    val bssidCount: Int = 1,
    val frequenciesMhz: List<Int> = emptyList(),
    val strongestRssiDbm: Int = -60,
    val requiresPassword: Boolean = true
) {
    val has24GhzBand: Boolean
        get() = frequenciesMhz.any { it in 2400..2499 }

    val has5GhzBand: Boolean
        get() = frequenciesMhz.any { it in 4900..5899 }

    val has6GhzBand: Boolean
        get() = frequenciesMhz.any { it >= 5925 }

    val bandValidationStatus: WifiBandValidationStatus
        get() = when {
            frequenciesMhz.isEmpty() -> WifiBandValidationStatus.UNKNOWN_UNSCANNED
            has24GhzBand && (has5GhzBand || has6GhzBand) -> WifiBandValidationStatus.VALID_DUAL_BAND_MESH
            has24GhzBand -> WifiBandValidationStatus.VALID_24GHZ_ONLY
            else -> WifiBandValidationStatus.BLOCKED_5GHZ_OR_6GHZ_ONLY
        }

    val inlineBandGuidance: String
        get() = when (bandValidationStatus) {
            WifiBandValidationStatus.VALID_24GHZ_ONLY ->
                "2.4 GHz network verified."
            WifiBandValidationStatus.VALID_DUAL_BAND_MESH ->
                "Dual-band mesh verified (2.4 GHz radio detected across $bssidCount access point${if (bssidCount == 1) "" else "s"})."
            WifiBandValidationStatus.BLOCKED_5GHZ_OR_6GHZ_ONLY ->
                "This network only broadcasts on ${if (has6GhzBand) "5/6 GHz" else "5 GHz"}. Smart plugs and sensors contain 2.4 GHz-only radios — enable 2.4 GHz on your router or select a 2.4 GHz SSID."
            WifiBandValidationStatus.UNKNOWN_UNSCANNED ->
                "Wi-Fi band not yet scanned — ensure this network supports 2.4 GHz."
        }
}

data class OnboardingRfTelemetryContext(
    val pairingSessionId: String = "",
    val tabletWifiFrequencyMhz: Int = 0,
    val targetRssiDbm: Int = -127,
    val permissionStateSummary: String = "",
    val stageDurationMs: Long = 0L,
    val protocolResultCode: String = ""
)

data class SingleDeviceWizardSessionState(
    val pairingSessionId: String = "",
    val entryMode: OnboardingEntryMode? = null,
    val currentStage: UnifiedOnboardingStage = UnifiedOnboardingStage.SELECT_MODE,
    val stepIndex: Int = 1,
    val isScanning: Boolean = false,
    val isPureEzInvertedFlow: Boolean = false,
    val tabletOn5GhzWarning: Boolean = false,
    val tabletActiveFrequencyMhz: Int = 0,
    val candidates: List<ResolvedDeviceIdentity> = emptyList(),
    val selectedCandidateId: String? = null,
    val availableWifiNetworks: List<ScannedWifiNetworkOption> = emptyList(),
    val selectedWifiSsid: String = "",
    val wifiPassword: String = "",
    val isWifiPasswordVisible: Boolean = false,
    val preFetchedCloudToken: String = "",
    val assignedDeviceName: String = "",
    val assignedRoomName: String = "Living Room",
    val statusDetail: String = "",
    val failureTitle: String = "",
    val failureRemediation: String = "",
    val failureCategoryName: String = "",
    val boundCanonicalGuid: String = "",
    val needsCapabilitySyncRetryOnly: Boolean = false
) {
    val selectedCandidate: ResolvedDeviceIdentity?
        get() = candidates.firstOrNull { it.identityKey == selectedCandidateId }

    val selectedWifiOption: ScannedWifiNetworkOption?
        get() = availableWifiNetworks.firstOrNull { it.ssid == selectedWifiSsid }
}

object CapabilitySchemaHasher {
    fun computeHash(capabilities: List<DeviceCapabilityDescriptor>): String {
        if (capabilities.isEmpty()) return ""
        val canonical = capabilities
            .sortedWith(compareBy({ it.key.lowercase(Locale.US) }, { it.dpId }))
            .joinToString("|") { cap ->
                val opts = cap.enumOptions.map { it.lowercase(Locale.US) }.sorted().joinToString(",")
                listOf(
                    cap.key.trim().lowercase(Locale.US),
                    cap.dpId.trim(),
                    cap.kind.name,
                    cap.isWritable.toString(),
                    cap.minValue.toString(),
                    cap.maxValue.toString(),
                    cap.stepValue.toString(),
                    cap.unit,
                    opts
                ).joinToString(":")
            }
        return try {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            canonical.hashCode().toString()
        }
    }
}

object CapabilitySnapshotCodec {
    fun encodeDescriptors(capabilities: List<DeviceCapabilityDescriptor>): String {
        val arr = org.json.JSONArray()
        for (cap in capabilities.sortedWith(compareBy({ it.key.lowercase(Locale.US) }, { it.dpId }))) {
            val optsArr = org.json.JSONArray()
            cap.enumOptions.forEach { optsArr.put(it) }
            val presetsArr = org.json.JSONArray()
            cap.presets.forEach { preset ->
                presetsArr.put(
                    org.json.JSONObject().apply {
                        put("value", preset.value.toDouble())
                        put("label", preset.label)
                        if (!preset.icon.isNullOrBlank()) {
                            put("icon", preset.icon)
                        }
                    }
                )
            }
            arr.put(
                org.json.JSONObject().apply {
                    put("key", cap.key)
                    put("dpId", cap.dpId)
                    put("label", cap.label)
                    put("kind", cap.kind.name)
                    put("rendererType", cap.rendererType.name)
                    put("isWritable", cap.isWritable)
                    put("isPinnedToHero", cap.isPinnedToHero)
                    put("currentValue", cap.currentValue)
                    put("enumOptions", optsArr)
                    put("presets", presetsArr)
                    put("minValue", cap.minValue.toDouble())
                    put("maxValue", cap.maxValue.toDouble())
                    put("stepValue", cap.stepValue.toDouble())
                    put("unit", cap.unit)
                    put("safetyTier", cap.safetyTier.name)
                    put("holdDurationMs", cap.holdDurationMs)
                    put("provenance", cap.provenance.name)
                    put("schemaHash", cap.schemaHash)
                }
            )
        }
        return arr.toString()
    }

    fun decodeDescriptors(rawJson: String): List<DeviceCapabilityDescriptor> {
        if (rawJson.isBlank()) return emptyList()
        val arr = runCatching { org.json.JSONArray(rawJson) }.getOrNull() ?: return emptyList()
        val list = mutableListOf<DeviceCapabilityDescriptor>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("key", "").trim()
            if (key.isBlank()) continue
            val kind = runCatching {
                DeviceCapabilityKind.valueOf(o.optString("kind", DeviceCapabilityKind.BOOLEAN_SWITCH.name))
            }.getOrDefault(DeviceCapabilityKind.BOOLEAN_SWITCH)
            val rendererType = o.optString("rendererType", "").takeIf { it.isNotBlank() }?.let { rawRenderer ->
                runCatching { CapabilityRendererType.valueOf(rawRenderer) }.getOrNull()
            } ?: defaultRendererTypeForCapability(kind, key)
            val safetyTier = o.optString("safetyTier", "").takeIf { it.isNotBlank() }?.let { rawSafety ->
                runCatching { CapabilitySafetyTier.valueOf(rawSafety) }.getOrNull()
            } ?: defaultSafetyTierForCapability(key)
            val provenance = o.optString("provenance", "").takeIf { it.isNotBlank() }?.let { rawProv ->
                runCatching { CapabilityProvenance.valueOf(rawProv) }.getOrNull()
            } ?: CapabilityProvenance.VERIFIED_HARDWARE_SCHEMA
            val holdDurationMs = o.optLong("holdDurationMs", defaultHoldDurationMsForCapability(key))
            val opts = mutableListOf<String>()
            val optsArr = o.optJSONArray("enumOptions")
            if (optsArr != null) {
                for (j in 0 until optsArr.length()) {
                    optsArr.optString(j, "").takeIf { it.isNotBlank() }?.let { opts.add(it) }
                }
            }
            val presetsList = mutableListOf<CapabilityPreset>()
            val presetsArr = o.optJSONArray("presets")
            if (presetsArr != null) {
                for (pIdx in 0 until presetsArr.length()) {
                    val po = presetsArr.optJSONObject(pIdx) ?: continue
                    val pLabel = po.optString("label", "").trim()
                    if (pLabel.isNotBlank()) {
                        presetsList.add(
                            CapabilityPreset(
                                value = po.optDouble("value", 0.0).toFloat(),
                                label = pLabel,
                                icon = po.optString("icon", "").takeIf { it.isNotBlank() }
                            )
                        )
                    }
                }
            }
            list.add(
                DeviceCapabilityDescriptor(
                    key = key,
                    dpId = o.optString("dpId", ""),
                    label = o.optString("label", key),
                    kind = kind,
                    isWritable = o.optBoolean("isWritable", true),
                    isPinnedToHero = o.optBoolean("isPinnedToHero", true),
                    currentValue = o.optString("currentValue", ""),
                    enumOptions = opts,
                    minValue = o.optDouble("minValue", 0.0).toFloat(),
                    maxValue = o.optDouble("maxValue", 100.0).toFloat(),
                    stepValue = o.optDouble("stepValue", 1.0).toFloat(),
                    unit = o.optString("unit", ""),
                    rendererType = rendererType,
                    presets = presetsList,
                    safetyTier = safetyTier,
                    holdDurationMs = holdDurationMs,
                    provenance = provenance,
                    schemaHash = o.optString("schemaHash", "")
                )
            )
        }
        return list
    }
}
