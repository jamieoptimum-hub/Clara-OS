package com.jarvis.smarthomelauncher.widgets.commute.Models

/**
 * CWAS Rule 12: Discoverable manifest metadata for Commute Hub v2.0.
 */
data class CommuteWidgetManifestDescriptor(
    val id: String = "commute-hub",
    val name: String = "Commute Hub",
    val feature: String = "Commute Hub",
    val application: String = "Clara Smart Home",
    val version: String = "2.0",
    val description: String = "Provide a personalised transport and commute experience allowing users to quickly assess travel conditions, departure times, delays and disruptions before leaving home.",
    val headerTitle: String = "Commute",
    val headerIcon: String = "train",
    val headerClickable: Boolean = true,
    val headerAction: String = "OpenCommuteHub",
    val launchMode: String = "popup",
    val popupWidthFraction: Float = 0.90f,
    val popupHeightFraction: Float = 0.92f,
    val supportsPopup: Boolean = true,
    val supportsSettings: Boolean = true,
    val supportsNotifications: Boolean = true,
    val supportsAutomations: Boolean = true,
    val supportsVoiceAssistant: Boolean = true
)

/**
 * All 8 supported transport modes specified in Commute Hub v2.0 JSON.
 */
enum class SupportedTransportMode(
    val id: String,
    val displayName: String,
    val tflModeToken: String,
    val isPublicTransit: Boolean
) {
    RAIL("rail", "Rail", "national-rail,overground,elizabeth-line", true),
    UNDERGROUND("underground", "Underground", "tube,dlr", true),
    BUS("bus", "Bus", "bus", true),
    TRAM("tram", "Tram", "tram", true),
    FERRY("ferry", "Ferry", "river-bus", true),
    DRIVING("driving", "Driving", "driving", false),
    WALKING("walking", "Walking", "walking", false),
    CYCLING("cycling", "Cycling", "cycle", false);

    companion object {
        fun fromIdOrName(raw: String): SupportedTransportMode {
            val cleaned = raw.trim().lowercase()
            return entries.firstOrNull {
                it.id == cleaned ||
                        it.displayName.lowercase() == cleaned ||
                        it.tflModeToken.contains(cleaned)
            } ?: RAIL
        }
    }
}

/**
 * Dashboard display modes per Commute Hub v2.0 JSON specification.
 * Disruption mode overrides Normal mode automatically when active delays, cancellations,
 * or engineering works impact the primary commute route.
 */
enum class CommuteWidgetDisplayMode {
    NORMAL,
    DISRUPTION
}

/**
 * Commute direction / time-of-day profile used on the Overview page ("Morning Commute" & "Evening Commute").
 */
enum class CommuteTimeWindow(val label: String) {
    MORNING("Morning Commute"),
    EVENING("Evening Commute"),
    ALL_DAY("Anytime Route")
}

/**
 * Normalized service status across National Rail, TfL, Bus, Ferry, and Road traffic feeds.
 */
enum class ServiceStatusKind(val label: String) {
    ON_TIME("On Time"),
    DELAYED("Delayed"),
    CANCELLED("Cancelled"),
    PLATFORM_CHANGED("Platform Changed"),
    ENGINEERING_WORKS("Engineering Works"),
    SUSPENDED("Part / Full Suspension"),
    HEAVY_TRAFFIC("Heavy Traffic");

    val isDisrupted: Boolean
        get() = this != ON_TIME
}

/**
 * Live passenger crowding / seat occupancy level.
 */
enum class ServiceOccupancyLevel(val label: String) {
    LOW("Seats Available"),
    MODERATE("Moderate"),
    BUSY("Standing Room"),
    FULL("Very Busy"),
    UNKNOWN("Live")
}

/**
 * 6-tab navigation structure for Commute Hub Popup (90% x 92%).
 */
enum class CommuteHubTab(val title: String) {
    OVERVIEW("Overview"),
    ROUTES("Routes"),
    LIVE_DEPARTURES("Live Departures"),
    STATIONS("Stations"),
    DISRUPTIONS("Disruptions"),
    JOURNEY_PLANNER("Journey Planner")
}

/**
 * RulesEngine insight categories per Commute Hub v2.0 JSON specification.
 */
enum class CommuteInsightRuleType(val ruleName: String) {
    DELAY_ALERT("Delay Alert"),
    PLATFORM_CHANGE("Platform Change"),
    ENGINEERING_WORKS("Engineering Works"),
    HEAVY_TRAFFIC("Heavy Traffic"),
    ALTERNATIVE_ROUTE_AVAILABLE("Alternative Route Available"),
    LEAVE_HOME_REMINDER("Leave Home Reminder")
}

/**
 * Notification event types supported by Commute Hub v2.0.
 */
enum class CommuteNotificationEventType(val displayName: String) {
    LEAVE_HOME_REMINDER("Leave Home Reminder"),
    TRAIN_DELAYED("Train Delayed"),
    TRAIN_CANCELLED("Train Cancelled"),
    PLATFORM_CHANGED("Platform Changed"),
    ENGINEERING_WORKS_ALERT("Engineering Works Alert"),
    ALTERNATIVE_ROUTE_AVAILABLE("Alternative Route Available")
}

/**
 * Smart Home automation hooks exposed by Commute Hub v2.0.
 */
enum class CommuteAutomationHook(val displayName: String, val eventCode: String) {
    NOTIFY_DELAY_OVER_10_MIN("Notify if train delayed more than 10 minutes", "COMMUTE_DELAY_OVER_10M"),
    ANNOUNCE_PLATFORM_CHANGES("Announce platform changes", "COMMUTE_PLATFORM_CHANGED_TTS"),
    OPEN_WEEKDAY_MORNING_DASHBOARD("Open commute dashboard every weekday morning", "COMMUTE_WEEKDAY_MORNING_OPEN"),
    DISPLAY_DISRUPTIONS_ON_DASHBOARD("Display disruptions on main dashboard", "COMMUTE_DISRUPTION_OVERRIDE"),
    ADJUST_ALARM_FOR_DELAYS("Adjust alarm / morning routine based on train delays", "COMMUTE_ADJUST_MORNING_ALARM")
}

/**
 * Station / Stop / Location endpoint resolved from live UK National Rail CRS, TfL StopPoint, or Geocoding APIs.
 */
data class TransportStopEndpoint(
    val id: String,
    val name: String,
    val crsOrStopCode: String = "",
    val primaryMode: SupportedTransportMode = SupportedTransportMode.RAIL,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val zoneOrRegion: String = ""
) {
    val compactDisplayName: String
        get() = if (crsOrStopCode.length == 3 && primaryMode == SupportedTransportMode.RAIL) {
            "$name ($crsOrStopCode)"
        } else {
            name
        }
}

/**
 * User-configured Saved Route persisted in SQLite (e.g., "Home to Work", "Home to London Bridge").
 */
data class SavedCommuteRoute(
    val routeId: String,
    val routeName: String,
    val mode: SupportedTransportMode,
    val origin: TransportStopEndpoint,
    val destination: TransportStopEndpoint,
    val walkToStationMinutes: Int = 8,
    val targetArrivalTime24h: String = "08:45",
    val timeWindow: CommuteTimeWindow = CommuteTimeWindow.MORNING,
    val isPrimaryRoute: Boolean = false,
    val sortOrder: Int = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Database-backed Commute Hub user preferences.
 */
data class CommuteHubPreferences(
    val autoDisruptionModeEnabled: Boolean = true,
    val delayAlertThresholdMinutes: Int = 10,
    val leaveHomeBufferMinutes: Int = 10,
    val weekdayMorningPopupHour: Int = 7,
    val weekdayMorningPopupMinute: Int = 30,
    val notificationsEnabled: Boolean = true,
    val enabledNotificationEvents: Set<CommuteNotificationEventType> = CommuteNotificationEventType.entries.toSet(),
    val automationsEnabled: Boolean = true,
    val enabledAutomationHooks: Set<CommuteAutomationHook> = setOf(
        CommuteAutomationHook.NOTIFY_DELAY_OVER_10_MIN,
        CommuteAutomationHook.ANNOUNCE_PLATFORM_CHANGES,
        CommuteAutomationHook.DISPLAY_DISRUPTIONS_ON_DASHBOARD
    )
)

/**
 * Individual calling point on a live rail / underground / bus service.
 */
data class ServiceCallingPoint(
    val stationName: String,
    val crsOrCode: String = "",
    val scheduledTime: String,
    val expectedTime: String,
    val isCancelled: Boolean = false
)

/**
 * Normalized live departure or active journey leg powering Widget Hero, Upcoming Services, and Live Departures tab.
 */
data class CommuteDepartureService(
    val serviceId: String,
    val routeId: String = "",
    val mode: SupportedTransportMode,
    val operatorOrLineName: String,
    val originName: String,
    val destinationName: String,
    val platform: String = "",
    val previousPlatform: String = "",
    val platformChanged: Boolean = false,
    val scheduledDepartureTime: String,
    val expectedDepartureTime: String,
    val scheduledDepartureEpochMs: Long,
    val expectedDepartureEpochMs: Long,
    val scheduledArrivalTime: String = "",
    val journeyDurationMinutes: Int = 0,
    val delayMinutes: Int = 0,
    val statusKind: ServiceStatusKind = ServiceStatusKind.ON_TIME,
    val statusDetailReason: String = "",
    val occupancy: ServiceOccupancyLevel = ServiceOccupancyLevel.UNKNOWN,
    val callingPoints: List<ServiceCallingPoint> = emptyList(),
    val alternativeRouteSuggestion: String = ""
) {
    val isCancelled: Boolean
        get() = statusKind == ServiceStatusKind.CANCELLED

    val displayPlatform: String
        get() = platform.ifBlank {
            when (mode) {
                SupportedTransportMode.BUS -> "Stop"
                SupportedTransportMode.DRIVING,
                SupportedTransportMode.WALKING,
                SupportedTransportMode.CYCLING -> mode.displayName
                else -> "TBC"
            }
        }
}

/**
 * Live arrival entry for the Station Details page.
 */
data class StationArrivalEntry(
    val serviceId: String,
    val operatorOrLineName: String,
    val originName: String,
    val platform: String,
    val scheduledArrivalTime: String,
    val expectedArrivalTime: String,
    val statusLabel: String
)

/**
 * Station Details model showing liveDepartures, liveArrivals, parking, taxiAvailability, facilities, accessibility.
 */
data class StationFacilityDetails(
    val station: TransportStopEndpoint,
    val liveDepartures: List<CommuteDepartureService>,
    val liveArrivals: List<StationArrivalEntry>,
    val parkingSummary: String,
    val taxiAvailability: String,
    val facilities: List<String>,
    val accessibilitySummary: String
)

/**
 * Active disruption record for Disruption Mode & Disruption Centre tab.
 */
enum class DisruptionCategory(val label: String) {
    DELAYS("Delays"),
    CANCELLATIONS("Cancellations"),
    ENGINEERING_WORKS("Engineering Works"),
    SERVICE_SUSPENSIONS("Service Suspensions"),
    ROAD_TRAFFIC("Heavy Traffic")
}

data class CommuteDisruptionItem(
    val id: String,
    val category: DisruptionCategory,
    val lineOrRouteName: String,
    val mode: SupportedTransportMode,
    val severityLabel: String,
    val summary: String,
    val alternativeRouteAdvice: String = "",
    val affectsSavedRoute: Boolean = false,
    val reportedEpochMs: Long = System.currentTimeMillis()
)

/**
 * Journey Planner option classification per Commute Hub v2.0 JSON:
 * fastestRoute, cheapestRoute, fewestChanges, earliestArrival.
 */
enum class JourneyOptionBadge(val label: String) {
    FASTEST_ROUTE("Fastest Route"),
    CHEAPEST_ROUTE("Cheapest Route"),
    FEWEST_CHANGES("Fewest Changes"),
    EARLIEST_ARRIVAL("Earliest Arrival")
}

data class JourneyLegStep(
    val mode: SupportedTransportMode,
    val lineOrInstruction: String,
    val fromStopName: String,
    val toStopName: String,
    val departureTime: String,
    val arrivalTime: String,
    val durationMinutes: Int,
    val platformOrStop: String = ""
)

data class PlannedJourneyOption(
    val id: String,
    val badges: List<JourneyOptionBadge>,
    val departureTime: String,
    val arrivalTime: String,
    val arrivalEpochMs: Long,
    val totalDurationMinutes: Int,
    val changesCount: Int,
    val estimatedFareGbp: Double?,
    val legs: List<JourneyLegStep>,
    val disruptionNote: String = ""
)

/**
 * Route summary card model for Overview & Routes tabs.
 */
data class RouteLiveSummaryCard(
    val route: SavedCommuteRoute,
    val nextService: CommuteDepartureService?,
    val upcomingServices: List<CommuteDepartureService>,
    val overallStatus: ServiceStatusKind,
    val maxDelayMinutes: Int,
    val leaveHomeInMinutes: Int?,
    val leaveHomeAtFormatted: String,
    val activeDisruptions: List<CommuteDisruptionItem>,
    val alternativeRouteSummary: String
)

/**
 * RulesEngine insight surfaced on Dashboard & Hub Overview.
 */
data class CommuteInsight(
    val id: String,
    val ruleType: CommuteInsightRuleType,
    val headline: String,
    val supportingDetail: String = "",
    val priority: Int,
    val routeId: String? = null
)

/**
 * Leave-By calculation exported to Clara's household ecosystem (CWAS Rule 19).
 */
data class CommuteLeaveByExport(
    val routeId: String,
    val routeName: String,
    val destinationName: String,
    val leaveByEpochMs: Long,
    val leaveByFormatted: String,
    val nextDepartureTime: String,
    val platform: String,
    val statusLabel: String,
    val delayMinutes: Int
)

/**
 * Complete Ready-state snapshot powering both the 2–4m Dashboard Widget and 90% x 92% Commute Hub Popup.
 */
data class CommuteDashboardSnapshot(
    val displayMode: CommuteWidgetDisplayMode,
    val savedRoutes: List<SavedCommuteRoute>,
    val primaryRouteCard: RouteLiveSummaryCard?,
    val morningRouteCards: List<RouteLiveSummaryCard>,
    val eveningRouteCards: List<RouteLiveSummaryCard>,
    val allRouteCards: List<RouteLiveSummaryCard>,
    val heroService: CommuteDepartureService?,
    val upcomingServices: List<CommuteDepartureService>,
    val disruptions: List<CommuteDisruptionItem>,
    val stationDetailsByStopId: Map<String, StationFacilityDetails>,
    val insights: List<CommuteInsight>,
    val preferences: CommuteHubPreferences,
    val providerLabel: String,
    val lastSyncedEpochMs: Long
)

/**
 * CWAS Rule 11: Mandatory core states (Loading, Ready, Empty, Error).
 */
sealed interface CommuteWidgetUiState {
    data object Loading : CommuteWidgetUiState

    data class Empty(
        val title: String = "No Saved Routes",
        val message: String = "Tap Commute to configure your Home to Work, station, or road commute routes."
    ) : CommuteWidgetUiState

    data class Error(
        val message: String = "Unable to retrieve data",
        val technicalDetail: String = ""
    ) : CommuteWidgetUiState

    data class Ready(val snapshot: CommuteDashboardSnapshot) : CommuteWidgetUiState
}

/**
 * Natural-language voice command response for ClaraVoiceAssistantManager.
 */
data class CommuteVoiceCommandResponse(
    val handled: Boolean,
    val spokenText: String,
    val targetTab: CommuteHubTab? = null
)