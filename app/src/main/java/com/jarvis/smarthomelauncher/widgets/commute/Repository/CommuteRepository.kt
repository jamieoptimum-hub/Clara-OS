package com.jarvis.smarthomelauncher.widgets.commute.Repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.smarthomelauncher.core.ClaraApplication
import com.jarvis.smarthomelauncher.core.ClaraSecretVault
import com.jarvis.smarthomelauncher.widgets.commute.Models.*
import com.jarvis.smarthomelauncher.widgets.commute.Services.CommuteDataProvider
import com.jarvis.smarthomelauncher.widgets.commute.Services.CommuteInsightRulesEngine
import com.jarvis.smarthomelauncher.widgets.commute.Services.LiveUkMultiModalCommuteProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Emitted when a Commute smart-home automation rule triggers (weekday morning popup,
 * >10m delay alert, platform change TTS, or alarm adjustment).
 */
data class CommuteAutomationDispatchEvent(
    val hook: CommuteAutomationHook,
    val route: SavedCommuteRoute?,
    val service: CommuteDepartureService?,
    val delayMinutes: Int = 0,
    val summaryMessage: String
)

/**
 * CWAS Rule 8: Dedicated SQLite database helper for Commute Hub v2.0.
 * Persists user-configured routes, preferences, departure caches, platform history for real-time
 * platform-change detection, notification deduplication logs, and Clara Leave-By calculations.
 */
class CommuteDatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SAVED_ROUTES (
                route_id TEXT PRIMARY KEY NOT NULL,
                route_name TEXT NOT NULL,
                mode_id TEXT NOT NULL,
                origin_json TEXT NOT NULL,
                destination_json TEXT NOT NULL,
                walk_minutes INTEGER NOT NULL DEFAULT 8,
                target_arrival_24h TEXT NOT NULL DEFAULT '08:45',
                time_window TEXT NOT NULL DEFAULT 'MORNING',
                is_primary INTEGER NOT NULL DEFAULT 0,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PREFERENCES (
                pref_key TEXT PRIMARY KEY NOT NULL,
                pref_value TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DEPARTURES_CACHE (
                route_id TEXT PRIMARY KEY NOT NULL,
                payload_json TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLATFORM_HISTORY (
                service_id TEXT PRIMARY KEY NOT NULL,
                last_platform TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_LEAVE_BY_EXPORT (
                route_id TEXT PRIMARY KEY NOT NULL,
                route_name TEXT NOT NULL,
                destination_name TEXT NOT NULL,
                leave_by_epoch_ms INTEGER NOT NULL,
                leave_by_formatted TEXT NOT NULL,
                next_departure_time TEXT NOT NULL,
                platform TEXT NOT NULL,
                status_label TEXT NOT NULL,
                delay_minutes INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_NOTIFICATION_LOG (
                dedup_key TEXT PRIMARY KEY NOT NULL,
                event_type TEXT NOT NULL,
                fired_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    companion object {
        private const val DATABASE_NAME = "clara_commute_hub_v2.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_SAVED_ROUTES = "commute_saved_routes"
        const val TABLE_PREFERENCES = "commute_preferences"
        const val TABLE_DEPARTURES_CACHE = "commute_departures_cache"
        const val TABLE_PLATFORM_HISTORY = "commute_platform_history"
        const val TABLE_LEAVE_BY_EXPORT = "commute_leave_by_export"
        const val TABLE_NOTIFICATION_LOG = "commute_notification_log"

        @Volatile
        private var instance: CommuteDatabaseHelper? = null

        fun getInstance(context: Context): CommuteDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: CommuteDatabaseHelper(context).also { instance = it }
            }
        }
    }
}

/**
 * CWAS Rule 7: Repository-first single source of truth for Commute Hub v2.0.
 * Widget ↓ CommuteRepository ↓ CommuteDatabaseHelper (SQLite) + ClaraSecretVault ↓ CommuteDataProvider.
 */
class CommuteRepository private constructor(context: Context) {

    private val appContext: Context = ClaraApplication.requireAppContext(context)
    private val dbHelper: CommuteDatabaseHelper = CommuteDatabaseHelper.getInstance(appContext)
    private val gson = Gson()
    private val refreshMutex = Mutex()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dataProvider: CommuteDataProvider = LiveUkMultiModalCommuteProvider(
        tflApiKeyProvider = { getTflApiKey() },
        darwinGatewayUrlProvider = { getDarwinGatewayUrl() }
    )

    private val _uiState = MutableStateFlow<CommuteWidgetUiState>(CommuteWidgetUiState.Loading)
    val uiState: StateFlow<CommuteWidgetUiState> = _uiState.asStateFlow()

    private val _leaveByExports = MutableStateFlow<List<CommuteLeaveByExport>>(emptyList())
    val leaveByExports: StateFlow<List<CommuteLeaveByExport>> = _leaveByExports.asStateFlow()

    private val _automationEvents = MutableSharedFlow<CommuteAutomationDispatchEvent>(extraBufferCapacity = 16)
    val automationEvents: SharedFlow<CommuteAutomationDispatchEvent> = _automationEvents.asSharedFlow()

    private var pollingJob: Job? = null
    private var ttsEngine: TextToSpeech? = null

    @Volatile
    private var ttsReady: Boolean = false

    init {
        ensureNotificationChannel()
        repositoryScope.launch {
            bootstrapFromDatabaseAndSync()
        }
        startAutonomousPollingLoop()
    }

    // ========================================================================
    // ClaraSecretVault & Gateway Configuration
    // ========================================================================

    fun getTflApiKey(): String? {
        return ClaraSecretVault.getString(appContext, VAULT_KEY_TFL_APP_KEY)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun setTflApiKey(apiKey: String) {
        val cleaned = apiKey.trim()
        if (cleaned.isBlank()) {
            ClaraSecretVault.remove(appContext, VAULT_KEY_TFL_APP_KEY)
        } else {
            ClaraSecretVault.putString(appContext, VAULT_KEY_TFL_APP_KEY, cleaned)
        }
        triggerRefresh(forceRemote = true)
    }

    fun getDarwinGatewayUrl(): String {
        dbHelper.readableDatabase.query(
            CommuteDatabaseHelper.TABLE_PREFERENCES,
            arrayOf("pref_value"),
            "pref_key = ?",
            arrayOf(KEY_DARWIN_GATEWAY_URL),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val url = cursor.getString(0)?.trim().orEmpty()
                if (url.startsWith("http", ignoreCase = true)) return url
            }
        }
        return LiveUkMultiModalCommuteProvider.DEFAULT_DARWIN_GATEWAY_URL
    }

    fun setDarwinGatewayUrl(gatewayUrl: String) {
        val cleaned = gatewayUrl.trim().trimEnd('/')
        val db = dbHelper.writableDatabase
        if (cleaned.isBlank() || cleaned == LiveUkMultiModalCommuteProvider.DEFAULT_DARWIN_GATEWAY_URL) {
            db.delete(CommuteDatabaseHelper.TABLE_PREFERENCES, "pref_key = ?", arrayOf(KEY_DARWIN_GATEWAY_URL))
        } else {
            val cv = ContentValues().apply {
                put("pref_key", KEY_DARWIN_GATEWAY_URL)
                put("pref_value", cleaned)
            }
            db.insertWithOnConflict(
                CommuteDatabaseHelper.TABLE_PREFERENCES,
                null,
                cv,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
        triggerRefresh(forceRemote = true)
    }

    // ========================================================================
    // Autonomous Polling Loop (Peak-Hour & Disruption-Aware Cadence)
    // ========================================================================

    private fun startAutonomousPollingLoop() {
        pollingJob?.cancel()
        pollingJob = repositoryScope.launch {
            while (isActive) {
                val nowZdt = ZonedDateTime.now()
                val hour = nowZdt.hour
                val isPeakWindow = hour in 6..9 || hour in 16..19
                val isDisrupted = (_uiState.value as? CommuteWidgetUiState.Ready)?.snapshot?.displayMode ==
                        CommuteWidgetDisplayMode.DISRUPTION
                val delayMs = if (isPeakWindow || isDisrupted) 60_000L else 180_000L
                delay(delayMs)
                refreshDashboardSnapshot(forceRemote = true)
            }
        }
    }

    fun triggerRefresh(forceRemote: Boolean = true) {
        repositoryScope.launch {
            refreshDashboardSnapshot(forceRemote = forceRemote)
        }
    }

    private suspend fun bootstrapFromDatabaseAndSync() {
        val routes = loadSavedRoutesFromDb()
        if (routes.isEmpty()) {
            _uiState.value = CommuteWidgetUiState.Empty()
            _leaveByExports.value = emptyList()
            return
        }

        val cachedDepartures = loadCachedDeparturesByRouteFromDb()
        if (cachedDepartures.isNotEmpty()) {
            val prefs = loadPreferencesFromDb()
            val cachedSnapshot = assembleSnapshot(
                routes = routes,
                preferences = prefs,
                departuresByRouteId = cachedDepartures,
                disruptions = emptyList(),
                stationDetailsMap = emptyMap()
            )
            _uiState.value = CommuteWidgetUiState.Ready(cachedSnapshot)
        } else {
            _uiState.value = CommuteWidgetUiState.Loading
        }

        refreshDashboardSnapshot(forceRemote = true)
    }

    suspend fun refreshDashboardSnapshot(forceRemote: Boolean = true) = refreshMutex.withLock {
        val routes = loadSavedRoutesFromDb()
        val prefs = loadPreferencesFromDb()

        if (routes.isEmpty()) {
            _uiState.value = CommuteWidgetUiState.Empty()
            clearLeaveByExportInDb()
            _leaveByExports.value = emptyList()
            return@withLock
        }

        if (_uiState.value !is CommuteWidgetUiState.Ready) {
            _uiState.value = CommuteWidgetUiState.Loading
        }

        try {
            val previousPlatforms = loadPlatformHistoryFromDb()
            val departuresByRouteId = LinkedHashMap<String, List<CommuteDepartureService>>()

            if (forceRemote) {
                coroutineScope {
                    routes.map { route ->
                        async {
                            val liveList = dataProvider.fetchRouteDepartures(
                                route = route,
                                previousPlatformsByServiceId = previousPlatforms
                            )
                            val effective = if (liveList.isNotEmpty()) {
                                saveDeparturesCacheForRoute(route.routeId, liveList)
                                recordObservedPlatformsInDb(liveList)
                                liveList
                            } else {
                                loadCachedDeparturesForRoute(route.routeId)
                            }
                            route.routeId to effective
                        }
                    }.awaitAll().forEach { (routeId, services) ->
                        departuresByRouteId[routeId] = services
                    }
                }
            } else {
                routes.forEach { r ->
                    departuresByRouteId[r.routeId] = loadCachedDeparturesForRoute(r.routeId)
                }
            }

            val allDepartures = departuresByRouteId.values.flatten()
            val disruptions = if (forceRemote) {
                dataProvider.fetchActiveDisruptions(
                    savedRoutes = routes,
                    routeDepartures = allDepartures
                )
            } else {
                emptyList()
            }

            // Preload Station Details for the primary route's origin & destination stations
            val stationDetailsMap = LinkedHashMap<String, StationFacilityDetails>()
            val primaryRoute = resolveActiveFocusRoute(routes)
            if (forceRemote && primaryRoute != null && primaryRoute.mode.isPublicTransit) {
                val uniqueStations = listOf(primaryRoute.origin, primaryRoute.destination)
                    .distinctBy { it.id }
                coroutineScope {
                    uniqueStations.map { st ->
                        async {
                            st.id to dataProvider.fetchStationFacilityDetails(st, previousPlatforms)
                        }
                    }.awaitAll().forEach { (id, details) ->
                        stationDetailsMap[id] = details
                    }
                }
            }

            val snapshot = assembleSnapshot(
                routes = routes,
                preferences = prefs,
                departuresByRouteId = departuresByRouteId,
                disruptions = disruptions,
                stationDetailsMap = stationDetailsMap
            )

            _uiState.value = CommuteWidgetUiState.Ready(snapshot)
            exportLeaveByCalculations(snapshot)
            evaluateNotificationsAndAutomations(snapshot)
        } catch (e: Exception) {
            val cached = loadCachedDeparturesByRouteFromDb()
            if (cached.isNotEmpty()) {
                val fallback = assembleSnapshot(
                    routes = routes,
                    preferences = prefs,
                    departuresByRouteId = cached,
                    disruptions = emptyList(),
                    stationDetailsMap = emptyMap()
                )
                _uiState.value = CommuteWidgetUiState.Ready(fallback)
            } else {
                _uiState.value = CommuteWidgetUiState.Error(
                    message = "Unable to retrieve data",
                    technicalDetail = e.message.orEmpty()
                )
            }
        }
    }

    // ========================================================================
    // Saved Routes, Station Lookup & Multi-Option Journey Planner
    // ========================================================================

    suspend fun searchStopsAndLocations(
        query: String,
        mode: SupportedTransportMode
    ): List<TransportStopEndpoint> {
        return dataProvider.searchStopsAndLocations(query = query, mode = mode)
    }

    suspend fun saveCommuteRoute(route: SavedCommuteRoute): List<SavedCommuteRoute> = withContext(Dispatchers.IO) {
        val existing = loadSavedRoutesFromDb()
        val shouldBePrimary = route.isPrimaryRoute || existing.isEmpty()

        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            if (shouldBePrimary) {
                db.update(
                    CommuteDatabaseHelper.TABLE_SAVED_ROUTES,
                    ContentValues().apply { put("is_primary", 0) },
                    null,
                    null
                )
            }
            val cv = ContentValues().apply {
                put("route_id", route.routeId)
                put("route_name", route.routeName.trim())
                put("mode_id", route.mode.id)
                put("origin_json", gson.toJson(route.origin))
                put("destination_json", gson.toJson(route.destination))
                put("walk_minutes", route.walkToStationMinutes.coerceIn(0, 90))
                put("target_arrival_24h", route.targetArrivalTime24h.trim().ifBlank { "08:45" })
                put("time_window", route.timeWindow.name)
                put("is_primary", if (shouldBePrimary) 1 else 0)
                put("sort_order", route.sortOrder)
                put("created_at_ms", route.createdAtEpochMs)
            }
            db.insertWithOnConflict(
                CommuteDatabaseHelper.TABLE_SAVED_ROUTES,
                null,
                cv,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        val updated = loadSavedRoutesFromDb()
        triggerRefresh(forceRemote = true)
        updated
    }

    suspend fun deleteCommuteRoute(routeId: String): List<SavedCommuteRoute> = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete(CommuteDatabaseHelper.TABLE_SAVED_ROUTES, "route_id = ?", arrayOf(routeId))
        db.delete(CommuteDatabaseHelper.TABLE_DEPARTURES_CACHE, "route_id = ?", arrayOf(routeId))

        val remaining = loadSavedRoutesFromDb()
        if (remaining.isNotEmpty() && remaining.none { it.isPrimaryRoute }) {
            setPrimaryRouteInternal(remaining.first().routeId)
        }
        val finalRoutes = loadSavedRoutesFromDb()
        triggerRefresh(forceRemote = true)
        finalRoutes
    }

    suspend fun setPrimaryCommuteRoute(routeId: String): List<SavedCommuteRoute> = withContext(Dispatchers.IO) {
        setPrimaryRouteInternal(routeId)
        val updated = loadSavedRoutesFromDb()
        triggerRefresh(forceRemote = false)
        updated
    }

    private fun setPrimaryRouteInternal(routeId: String) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.update(
                CommuteDatabaseHelper.TABLE_SAVED_ROUTES,
                ContentValues().apply { put("is_primary", 0) },
                null,
                null
            )
            db.update(
                CommuteDatabaseHelper.TABLE_SAVED_ROUTES,
                ContentValues().apply { put("is_primary", 1) },
                "route_id = ?",
                arrayOf(routeId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getSavedRoutesSync(): List<SavedCommuteRoute> = loadSavedRoutesFromDb()

    fun getPreferencesSync(): CommuteHubPreferences = loadPreferencesFromDb()

    suspend fun updatePreferences(updated: CommuteHubPreferences) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("pref_key", KEY_PREFERENCES_JSON)
            put("pref_value", gson.toJson(updated))
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            CommuteDatabaseHelper.TABLE_PREFERENCES,
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        triggerRefresh(forceRemote = false)
    }

    suspend fun loadStationFacilityDetails(station: TransportStopEndpoint): StationFacilityDetails {
        val prevPlatforms = withContext(Dispatchers.IO) { loadPlatformHistoryFromDb() }
        return dataProvider.fetchStationFacilityDetails(station, prevPlatforms)
    }

    suspend fun planJourneys(
        origin: TransportStopEndpoint,
        destination: TransportStopEndpoint,
        preferredMode: SupportedTransportMode
    ): List<PlannedJourneyOption> {
        return dataProvider.planMultiOptionJourneys(
            origin = origin,
            destination = destination,
            preferredMode = preferredMode
        )
    }

    // ========================================================================
    // Voice Assistant Handler (Supports All 5 JSON Voice Commands)
    // ========================================================================

    fun handleVoiceCommand(rawUtterance: String): CommuteVoiceCommandResponse {
        val q = rawUtterance.trim().lowercase(Locale.UK)
        val snapshot = (_uiState.value as? CommuteWidgetUiState.Ready)?.snapshot
            ?: return CommuteVoiceCommandResponse(
                handled = true,
                spokenText = "Commute Hub has no saved routes yet. Open Commute Hub to add your Home to Work route.",
                targetTab = CommuteHubTab.ROUTES
            )

        val hero = snapshot.heroService
        val primaryCard = snapshot.primaryRouteCard

        // 1. "Show live departures"
        if (q.contains("live departure") || q.contains("departure board")) {
            val upcoming = snapshot.upcomingServices
            val spoken = if (hero == null && upcoming.isEmpty()) {
                "No live departures were found for your saved route right now."
            } else {
                val services = listOfNotNull(hero) + upcoming.take(2)
                val summary = services.joinToString("; ") {
                    "${it.scheduledDepartureTime} to ${it.destinationName} from platform ${it.displayPlatform} (${it.statusKind.label})"
                }
                "Upcoming departures: $summary."
            }
            return CommuteVoiceCommandResponse(
                handled = true,
                spokenText = spoken,
                targetTab = CommuteHubTab.LIVE_DEPARTURES
            )
        }

        // 2. "What platform is my train departing from"
        if (q.contains("what platform") || q.contains("which platform") || q.contains("platform is my")) {
            val spoken = if (hero == null) {
                "There is no upcoming train departure to check a platform for."
            } else if (hero.platform.isBlank()) {
                "The platform for the ${hero.scheduledDepartureTime} service to ${hero.destinationName} has not been announced yet."
            } else {
                val changeNote = if (hero.platformChanged) " — note that this recently changed from platform ${hero.previousPlatform}" else ""
                "Your ${hero.scheduledDepartureTime} train to ${hero.destinationName} departs from platform ${hero.platform}$changeNote."
            }
            return CommuteVoiceCommandResponse(
                handled = true,
                spokenText = spoken,
                targetTab = CommuteHubTab.LIVE_DEPARTURES
            )
        }

        // 3. "Any delays on my route"
        if (q.contains("any delay") || q.contains("delays on my") || q.contains("any disruption")) {
            val routeDisruptions = snapshot.disruptions.filter { it.affectsSavedRoute }
            val spoken = when {
                hero?.isCancelled == true ->
                    "Alert: your ${hero.scheduledDepartureTime} service to ${hero.destinationName} is cancelled. ${primaryCard?.alternativeRouteSummary.orEmpty()}"
                hero != null && hero.delayMinutes > 0 ->
                    "Your ${hero.scheduledDepartureTime} service to ${hero.destinationName} is delayed by ${hero.delayMinutes} minutes, expected at ${hero.expectedDepartureTime}."
                routeDisruptions.isNotEmpty() ->
                    "Disruption reported on ${routeDisruptions.first().lineOrRouteName}: ${routeDisruptions.first().severityLabel}."
                else ->
                    "Good news — your saved commute routes are currently running on time with no reported delays."
            }
            return CommuteVoiceCommandResponse(
                handled = true,
                spokenText = spoken,
                targetTab = if (routeDisruptions.isNotEmpty() || hero?.statusKind?.isDisrupted == true) {
                    CommuteHubTab.DISRUPTIONS
                } else {
                    CommuteHubTab.OVERVIEW
                }
            )
        }

        // 4. "When is my next train"
        if (q.contains("next train") || q.contains("next bus") || q.contains("next departure")) {
            val spoken = if (hero == null) {
                "No upcoming departure was found on your primary route."
            } else {
                val leavePart = primaryCard?.leaveHomeInMinutes?.takeIf { it >= 0 }?.let {
                    " Leave home in $it minutes."
                }.orEmpty()
                "Your next service from ${hero.originName} to ${hero.destinationName} is the ${hero.scheduledDepartureTime}, expected at ${hero.expectedDepartureTime} from platform ${hero.displayPlatform}.$leavePart"
            }
            return CommuteVoiceCommandResponse(
                handled = true,
                spokenText = spoken,
                targetTab = CommuteHubTab.LIVE_DEPARTURES
            )
        }

        // 5. "How is my commute today"
        if (q.contains("how is my commute") || q.contains("commute today") || q.contains("travel condition")) {
            val spoken = if (primaryCard == null || hero == null) {
                "Your commute routes are saved, but no live departures are currently scheduled."
            } else {
                val statusPhrase = when {
                    hero.isCancelled -> "cancelled"
                    hero.delayMinutes > 0 -> "delayed by ${hero.delayMinutes} minutes"
                    else -> "running on time"
                }
                "On ${primaryCard.route.routeName}, the ${hero.scheduledDepartureTime} to ${hero.destinationName} is $statusPhrase on platform ${hero.displayPlatform}."
            }
            return CommuteVoiceCommandResponse(
                handled = true,
                spokenText = spoken,
                targetTab = CommuteHubTab.OVERVIEW
            )
        }

        return CommuteVoiceCommandResponse(handled = false, spokenText = "")
    }

    // ========================================================================
    // Snapshot Assembly, Disruption Override & Leave-By Calculation
    // ========================================================================

    private fun resolveActiveFocusRoute(routes: List<SavedCommuteRoute>): SavedCommuteRoute? {
        if (routes.isEmpty()) return null
        val currentHour = ZonedDateTime.now().hour
        val windowMatch = if (currentHour < 13) {
            routes.firstOrNull { it.isPrimaryRoute && it.timeWindow == CommuteTimeWindow.MORNING }
                ?: routes.firstOrNull { it.timeWindow == CommuteTimeWindow.MORNING }
        } else {
            routes.firstOrNull { it.isPrimaryRoute && it.timeWindow == CommuteTimeWindow.EVENING }
                ?: routes.firstOrNull { it.timeWindow == CommuteTimeWindow.EVENING }
        }
        return routes.firstOrNull { it.isPrimaryRoute } ?: windowMatch ?: routes.first()
    }

    private fun assembleSnapshot(
        routes: List<SavedCommuteRoute>,
        preferences: CommuteHubPreferences,
        departuresByRouteId: Map<String, List<CommuteDepartureService>>,
        disruptions: List<CommuteDisruptionItem>,
        stationDetailsMap: Map<String, StationFacilityDetails>
    ): CommuteDashboardSnapshot {
        val nowMs = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val hhMmFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

        val focusRoute = resolveActiveFocusRoute(routes)

        val allRouteCards = routes.map { route ->
            val services = departuresByRouteId[route.routeId].orEmpty()
                .filter { it.expectedDepartureEpochMs >= nowMs - 5 * 60_000L || it.isCancelled }
                .sortedBy { it.scheduledDepartureEpochMs }

            val nextLiveCatchable = services.firstOrNull { !it.isCancelled } ?: services.firstOrNull()
            val firstScheduled = services.firstOrNull()

            val routeDisruptions = disruptions.filter { dis ->
                dis.affectsSavedRoute && (
                        dis.summary.contains(route.origin.name, ignoreCase = true) ||
                                dis.summary.contains(route.destination.name, ignoreCase = true) ||
                                services.any { svc -> svc.operatorOrLineName.equals(dis.lineOrRouteName, ignoreCase = true) }
                        )
            }

            val overallStatus = when {
                firstScheduled?.isCancelled == true -> ServiceStatusKind.CANCELLED
                nextLiveCatchable?.platformChanged == true -> ServiceStatusKind.PLATFORM_CHANGED
                routeDisruptions.any { it.category == DisruptionCategory.ENGINEERING_WORKS } ->
                    ServiceStatusKind.ENGINEERING_WORKS
                routeDisruptions.any { it.category == DisruptionCategory.SERVICE_SUSPENSIONS } ->
                    ServiceStatusKind.SUSPENDED
                (nextLiveCatchable?.delayMinutes ?: 0) > 0 -> ServiceStatusKind.DELAYED
                routeDisruptions.any { it.category == DisruptionCategory.ROAD_TRAFFIC } ->
                    ServiceStatusKind.HEAVY_TRAFFIC
                else -> ServiceStatusKind.ON_TIME
            }

            val maxDelay = services.take(3).maxOfOrNull { it.delayMinutes } ?: 0

            val leaveInMins: Int?
            val leaveFormatted: String
            if (nextLiveCatchable != null && !nextLiveCatchable.isCancelled) {
                val leaveEpochMs = nextLiveCatchable.expectedDepartureEpochMs -
                        (route.walkToStationMinutes * 60_000L)
                leaveInMins = ((leaveEpochMs - nowMs) / 60_000L).toInt()
                leaveFormatted = Instant.ofEpochMilli(leaveEpochMs).atZone(zone).format(hhMmFormatter)
            } else {
                leaveInMins = null
                leaveFormatted = "--:--"
            }

            val altRouteText = when {
                firstScheduled?.isCancelled == true && nextLiveCatchable != null && !nextLiveCatchable.isCancelled ->
                    "Next available service: ${nextLiveCatchable.expectedDepartureTime} (${nextLiveCatchable.operatorOrLineName}) from Platform ${nextLiveCatchable.displayPlatform}"
                nextLiveCatchable?.alternativeRouteSuggestion?.isNotBlank() == true ->
                    nextLiveCatchable.alternativeRouteSuggestion
                routeDisruptions.firstOrNull()?.alternativeRouteAdvice?.isNotBlank() == true ->
                    routeDisruptions.first().alternativeRouteAdvice
                else -> ""
            }

            RouteLiveSummaryCard(
                route = route,
                nextService = firstScheduled ?: nextLiveCatchable,
                upcomingServices = services.take(6),
                overallStatus = overallStatus,
                maxDelayMinutes = maxDelay,
                leaveHomeInMinutes = leaveInMins,
                leaveHomeAtFormatted = leaveFormatted,
                activeDisruptions = routeDisruptions,
                alternativeRouteSummary = altRouteText
            )
        }

        val primaryCard = allRouteCards.firstOrNull { it.route.routeId == focusRoute?.routeId }
            ?: allRouteCards.firstOrNull()

        val heroService = primaryCard?.nextService
        val upcomingServices = primaryCard?.upcomingServices
            ?.filter { it.serviceId != heroService?.serviceId }
            ?.take(5)
            .orEmpty()

        val shouldShowDisruptionMode = preferences.autoDisruptionModeEnabled &&
                (!preferences.automationsEnabled ||
                        CommuteAutomationHook.DISPLAY_DISRUPTIONS_ON_DASHBOARD in preferences.enabledAutomationHooks) &&
                (primaryCard?.overallStatus?.isDisrupted == true ||
                        disruptions.any { it.affectsSavedRoute })

        val displayMode = if (shouldShowDisruptionMode) {
            CommuteWidgetDisplayMode.DISRUPTION
        } else {
            CommuteWidgetDisplayMode.NORMAL
        }

        val insights = CommuteInsightRulesEngine.evaluateInsights(
            primaryCard = primaryCard,
            allCards = allRouteCards,
            disruptions = disruptions,
            preferences = preferences
        )

        return CommuteDashboardSnapshot(
            displayMode = displayMode,
            savedRoutes = routes,
            primaryRouteCard = primaryCard,
            morningRouteCards = allRouteCards.filter {
                it.route.timeWindow == CommuteTimeWindow.MORNING || it.route.timeWindow == CommuteTimeWindow.ALL_DAY
            },
            eveningRouteCards = allRouteCards.filter {
                it.route.timeWindow == CommuteTimeWindow.EVENING || it.route.timeWindow == CommuteTimeWindow.ALL_DAY
            },
            allRouteCards = allRouteCards,
            heroService = heroService,
            upcomingServices = upcomingServices,
            disruptions = disruptions,
            stationDetailsByStopId = stationDetailsMap,
            insights = insights,
            preferences = preferences,
            providerLabel = dataProvider.providerName,
            lastSyncedEpochMs = nowMs
        )
    }

    // ========================================================================
    // Ecosystem Leave-By Export, Notifications & Smart-Home Automations
    // ========================================================================

    private fun exportLeaveByCalculations(snapshot: CommuteDashboardSnapshot) {
        val exports = snapshot.allRouteCards.mapNotNull { card ->
            val svc = card.upcomingServices.firstOrNull { !it.isCancelled } ?: return@mapNotNull null
            val leaveEpochMs = svc.expectedDepartureEpochMs - (card.route.walkToStationMinutes * 60_000L)
            CommuteLeaveByExport(
                routeId = card.route.routeId,
                routeName = card.route.routeName,
                destinationName = svc.destinationName,
                leaveByEpochMs = leaveEpochMs,
                leaveByFormatted = card.leaveHomeAtFormatted,
                nextDepartureTime = svc.expectedDepartureTime,
                platform = svc.displayPlatform,
                statusLabel = card.overallStatus.label,
                delayMinutes = svc.delayMinutes
            )
        }

        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(CommuteDatabaseHelper.TABLE_LEAVE_BY_EXPORT, null, null)
            for (item in exports) {
                val cv = ContentValues().apply {
                    put("route_id", item.routeId)
                    put("route_name", item.routeName)
                    put("destination_name", item.destinationName)
                    put("leave_by_epoch_ms", item.leaveByEpochMs)
                    put("leave_by_formatted", item.leaveByFormatted)
                    put("next_departure_time", item.nextDepartureTime)
                    put("platform", item.platform)
                    put("status_label", item.statusLabel)
                    put("delay_minutes", item.delayMinutes)
                }
                db.insert(CommuteDatabaseHelper.TABLE_LEAVE_BY_EXPORT, null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        _leaveByExports.value = exports
    }

    private fun clearLeaveByExportInDb() {
        dbHelper.writableDatabase.delete(CommuteDatabaseHelper.TABLE_LEAVE_BY_EXPORT, null, null)
    }

    private fun evaluateNotificationsAndAutomations(snapshot: CommuteDashboardSnapshot) {
        val prefs = snapshot.preferences
        val nowZdt = ZonedDateTime.now()
        val todayKey = nowZdt.toLocalDate().toString()

        // 1. Weekday Morning Auto-Open Commute Dashboard Automation
        val isWeekday = nowZdt.dayOfWeek != DayOfWeek.SATURDAY && nowZdt.dayOfWeek != DayOfWeek.SUNDAY
        if (isWeekday &&
            prefs.automationsEnabled &&
            CommuteAutomationHook.OPEN_WEEKDAY_MORNING_DASHBOARD in prefs.enabledAutomationHooks &&
            nowZdt.hour == prefs.weekdayMorningPopupHour &&
            nowZdt.minute in prefs.weekdayMorningPopupMinute..(prefs.weekdayMorningPopupMinute + 4)
        ) {
            val morningKey = "morning_open_$todayKey"
            if (!hasEventFired(morningKey)) {
                recordFiredEvent(morningKey, "WEEKDAY_MORNING_OPEN")
                _automationEvents.tryEmit(
                    CommuteAutomationDispatchEvent(
                        hook = CommuteAutomationHook.OPEN_WEEKDAY_MORNING_DASHBOARD,
                        route = snapshot.primaryRouteCard?.route,
                        service = snapshot.heroService,
                        summaryMessage = "Opening weekday morning Commute Hub"
                    )
                )
            }
        }

        for (card in snapshot.allRouteCards) {
            val next = card.nextService ?: continue

            // 2. Leave Home Reminder Notification
            val leaveMins = card.leaveHomeInMinutes
            if (leaveMins != null &&
                !next.isCancelled &&
                leaveMins in 1..prefs.leaveHomeBufferMinutes &&
                prefs.notificationsEnabled &&
                CommuteNotificationEventType.LEAVE_HOME_REMINDER in prefs.enabledNotificationEvents
            ) {
                dispatchNotificationOnce(
                    dedupKey = "leave_${next.serviceId}",
                    eventType = CommuteNotificationEventType.LEAVE_HOME_REMINDER,
                    title = "Leave Home in $leaveMins mins (${card.route.routeName})",
                    body = "${next.expectedDepartureTime} to ${next.destinationName} • Platform ${next.displayPlatform}"
                )
            }

            // 3. Platform Changed Notification + Voice TTS Automation
            if (next.platformChanged) {
                val platKey = "plat_${next.serviceId}_${next.platform}"
                if (!hasEventFired(platKey)) {
                    if (prefs.notificationsEnabled &&
                        CommuteNotificationEventType.PLATFORM_CHANGED in prefs.enabledNotificationEvents
                    ) {
                        dispatchNotificationOnce(
                            dedupKey = platKey,
                            eventType = CommuteNotificationEventType.PLATFORM_CHANGED,
                            title = "Platform Changed • Platform ${next.platform}",
                            body = "${next.scheduledDepartureTime} to ${next.destinationName} moved from Platform ${next.previousPlatform} to ${next.platform}."
                        )
                    } else {
                        recordFiredEvent(platKey, CommuteNotificationEventType.PLATFORM_CHANGED.name)
                    }

                    if (prefs.automationsEnabled &&
                        CommuteAutomationHook.ANNOUNCE_PLATFORM_CHANGES in prefs.enabledAutomationHooks
                    ) {
                        val spoken = "Platform change alert: the ${next.scheduledDepartureTime} service to ${next.destinationName} now departs from platform ${next.platform}."
                        speakTtsAnnouncement(spoken)
                        _automationEvents.tryEmit(
                            CommuteAutomationDispatchEvent(
                                hook = CommuteAutomationHook.ANNOUNCE_PLATFORM_CHANGES,
                                route = card.route,
                                service = next,
                                summaryMessage = spoken
                            )
                        )
                    }
                }
            }

            // 4. Train Cancelled + Alternative Route Available Notification
            if (next.isCancelled) {
                if (prefs.notificationsEnabled &&
                    CommuteNotificationEventType.TRAIN_CANCELLED in prefs.enabledNotificationEvents
                ) {
                    dispatchNotificationOnce(
                        dedupKey = "cancel_${next.serviceId}",
                        eventType = CommuteNotificationEventType.TRAIN_CANCELLED,
                        title = "Service Cancelled: ${next.scheduledDepartureTime} to ${next.destinationName}",
                        body = next.statusDetailReason.ifBlank { card.alternativeRouteSummary.ifBlank { card.route.routeName } }
                    )
                }
                if (card.alternativeRouteSummary.isNotBlank() &&
                    prefs.notificationsEnabled &&
                    CommuteNotificationEventType.ALTERNATIVE_ROUTE_AVAILABLE in prefs.enabledNotificationEvents
                ) {
                    dispatchNotificationOnce(
                        dedupKey = "alt_${next.serviceId}",
                        eventType = CommuteNotificationEventType.ALTERNATIVE_ROUTE_AVAILABLE,
                        title = "Alternative Route Available (${card.route.routeName})",
                        body = card.alternativeRouteSummary
                    )
                }
            }

            // 5. Train Delayed Notification + >10m Delay & Alarm Adjustment Automations
            if (!next.isCancelled && next.delayMinutes >= prefs.delayAlertThresholdMinutes) {
                val delayBucketKey = "delay_${next.serviceId}_${next.delayMinutes / 5}"
                if (prefs.notificationsEnabled &&
                    CommuteNotificationEventType.TRAIN_DELAYED in prefs.enabledNotificationEvents
                ) {
                    dispatchNotificationOnce(
                        dedupKey = delayBucketKey,
                        eventType = CommuteNotificationEventType.TRAIN_DELAYED,
                        title = "${card.route.routeName} Delayed (+${next.delayMinutes}m)",
                        body = "${next.scheduledDepartureTime} to ${next.destinationName} now expected at ${next.expectedDepartureTime}."
                    )
                }

                if (prefs.automationsEnabled && next.delayMinutes > 10) {
                    val autoKey = "auto_delay10_${next.serviceId}"
                    if (!hasEventFired(autoKey)) {
                        recordFiredEvent(autoKey, "DELAY_OVER_10M")
                        if (CommuteAutomationHook.NOTIFY_DELAY_OVER_10_MIN in prefs.enabledAutomationHooks) {
                            val msg = "Commute alert: your ${next.scheduledDepartureTime} service to ${next.destinationName} is delayed by ${next.delayMinutes} minutes."
                            speakTtsAnnouncement(msg)
                            _automationEvents.tryEmit(
                                CommuteAutomationDispatchEvent(
                                    hook = CommuteAutomationHook.NOTIFY_DELAY_OVER_10_MIN,
                                    route = card.route,
                                    service = next,
                                    delayMinutes = next.delayMinutes,
                                    summaryMessage = msg
                                )
                            )
                        }
                        if (CommuteAutomationHook.ADJUST_ALARM_FOR_DELAYS in prefs.enabledAutomationHooks) {
                            _automationEvents.tryEmit(
                                CommuteAutomationDispatchEvent(
                                    hook = CommuteAutomationHook.ADJUST_ALARM_FOR_DELAYS,
                                    route = card.route,
                                    service = next,
                                    delayMinutes = next.delayMinutes,
                                    summaryMessage = "Adjusted morning routine schedule for +${next.delayMinutes}m delay"
                                )
                            )
                        }
                    }
                }
            }
        }

        // 6. Engineering Works Alert Notification
        if (prefs.notificationsEnabled &&
            CommuteNotificationEventType.ENGINEERING_WORKS_ALERT in prefs.enabledNotificationEvents
        ) {
            snapshot.disruptions
                .firstOrNull { it.category == DisruptionCategory.ENGINEERING_WORKS && it.affectsSavedRoute }
                ?.let { eng ->
                    dispatchNotificationOnce(
                        dedupKey = "eng_${eng.id}_$todayKey",
                        eventType = CommuteNotificationEventType.ENGINEERING_WORKS_ALERT,
                        title = "Engineering Works: ${eng.lineOrRouteName}",
                        body = eng.summary.take(140)
                    )
                }
        }
    }

    // ========================================================================
    // Low-Level SQLite Persistence Operations
    // ========================================================================

    private fun loadSavedRoutesFromDb(): List<SavedCommuteRoute> {
        val list = mutableListOf<SavedCommuteRoute>()
        dbHelper.readableDatabase.query(
            CommuteDatabaseHelper.TABLE_SAVED_ROUTES,
            null,
            null,
            null,
            null,
            null,
            "is_primary DESC, sort_order ASC, created_at_ms ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val originJson = cursor.getString(cursor.getColumnIndexOrThrow("origin_json"))
                val destJson = cursor.getString(cursor.getColumnIndexOrThrow("destination_json"))
                val origin = runCatching { gson.fromJson(originJson, TransportStopEndpoint::class.java) }.getOrNull()
                    ?: continue
                val dest = runCatching { gson.fromJson(destJson, TransportStopEndpoint::class.java) }.getOrNull()
                    ?: continue
                val mode = SupportedTransportMode.fromIdOrName(
                    cursor.getString(cursor.getColumnIndexOrThrow("mode_id"))
                )
                val window = runCatching {
                    CommuteTimeWindow.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("time_window")))
                }.getOrDefault(CommuteTimeWindow.MORNING)

                list.add(
                    SavedCommuteRoute(
                        routeId = cursor.getString(cursor.getColumnIndexOrThrow("route_id")),
                        routeName = cursor.getString(cursor.getColumnIndexOrThrow("route_name")),
                        mode = mode,
                        origin = origin,
                        destination = dest,
                        walkToStationMinutes = cursor.getInt(cursor.getColumnIndexOrThrow("walk_minutes")),
                        targetArrivalTime24h = cursor.getString(cursor.getColumnIndexOrThrow("target_arrival_24h")),
                        timeWindow = window,
                        isPrimaryRoute = cursor.getInt(cursor.getColumnIndexOrThrow("is_primary")) == 1,
                        sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow("sort_order")),
                        createdAtEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_ms"))
                    )
                )
            }
        }
        return list
    }

    private fun loadPreferencesFromDb(): CommuteHubPreferences {
        dbHelper.readableDatabase.query(
            CommuteDatabaseHelper.TABLE_PREFERENCES,
            arrayOf("pref_value"),
            "pref_key = ?",
            arrayOf(KEY_PREFERENCES_JSON),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val raw = cursor.getString(0)
                return runCatching { gson.fromJson(raw, CommuteHubPreferences::class.java) }
                    .getOrNull() ?: CommuteHubPreferences()
            }
        }
        return CommuteHubPreferences()
    }

    private fun saveDeparturesCacheForRoute(routeId: String, services: List<CommuteDepartureService>) {
        val cv = ContentValues().apply {
            put("route_id", routeId)
            put("payload_json", gson.toJson(services))
            put("updated_at_ms", System.currentTimeMillis())
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            CommuteDatabaseHelper.TABLE_DEPARTURES_CACHE,
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun loadCachedDeparturesForRoute(routeId: String): List<CommuteDepartureService> {
        val listType = object : TypeToken<List<CommuteDepartureService>>() {}.type
        dbHelper.readableDatabase.query(
            CommuteDatabaseHelper.TABLE_DEPARTURES_CACHE,
            arrayOf("payload_json"),
            "route_id = ?",
            arrayOf(routeId),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val raw = cursor.getString(0)
                return runCatching { gson.fromJson<List<CommuteDepartureService>>(raw, listType) }
                    .getOrDefault(emptyList())
            }
        }
        return emptyList()
    }

    private fun loadCachedDeparturesByRouteFromDb(): Map<String, List<CommuteDepartureService>> {
        val map = LinkedHashMap<String, List<CommuteDepartureService>>()
        val listType = object : TypeToken<List<CommuteDepartureService>>() {}.type
        dbHelper.readableDatabase.query(
            CommuteDatabaseHelper.TABLE_DEPARTURES_CACHE,
            arrayOf("route_id", "payload_json"),
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val rId = cursor.getString(0)
                val raw = cursor.getString(1)
                val parsed = runCatching { gson.fromJson<List<CommuteDepartureService>>(raw, listType) }
                    .getOrDefault(emptyList())
                if (parsed.isNotEmpty()) map[rId] = parsed
            }
        }
        return map
    }

    private fun loadPlatformHistoryFromDb(): Map<String, String> {
        val map = HashMap<String, String>()
        val cutoff = System.currentTimeMillis() - 18L * 3_600_000L
        dbHelper.readableDatabase.query(
            CommuteDatabaseHelper.TABLE_PLATFORM_HISTORY,
            arrayOf("service_id", "last_platform"),
            "updated_at_ms >= ?",
            arrayOf(cutoff.toString()),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                map[cursor.getString(0)] = cursor.getString(1)
            }
        }
        return map
    }

    private fun recordObservedPlatformsInDb(services: List<CommuteDepartureService>) {
        val nowMs = System.currentTimeMillis()
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                CommuteDatabaseHelper.TABLE_PLATFORM_HISTORY,
                "updated_at_ms < ?",
                arrayOf((nowMs - 24L * 3_600_000L).toString())
            )
            for (svc in services) {
                if (svc.platform.isNotBlank()) {
                    val cv = ContentValues().apply {
                        put("service_id", svc.serviceId)
                        put("last_platform", svc.platform)
                        put("updated_at_ms", nowMs)
                    }
                    db.insertWithOnConflict(
                        CommuteDatabaseHelper.TABLE_PLATFORM_HISTORY,
                        null,
                        cv,
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Clara Commute Hub",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Leave-home reminders, train delays, cancellations, platform changes & engineering works"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun dispatchNotificationOnce(
        dedupKey: String,
        eventType: CommuteNotificationEventType,
        title: String,
        body: String
    ) {
        if (hasEventFired(dedupKey)) return
        recordFiredEvent(dedupKey, eventType.name)
        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val notif = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            nm.notify(dedupKey.hashCode(), notif)
        }
    }

    private fun hasEventFired(dedupKey: String): Boolean {
        dbHelper.readableDatabase.query(
            CommuteDatabaseHelper.TABLE_NOTIFICATION_LOG,
            arrayOf("dedup_key"),
            "dedup_key = ?",
            arrayOf(dedupKey),
            null,
            null,
            null
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun recordFiredEvent(dedupKey: String, eventName: String) {
        val cv = ContentValues().apply {
            put("dedup_key", dedupKey)
            put("event_type", eventName)
            put("fired_at_ms", System.currentTimeMillis())
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            CommuteDatabaseHelper.TABLE_NOTIFICATION_LOG,
            null,
            cv,
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun speakTtsAnnouncement(text: String) {
        if (text.isBlank()) return
        repositoryScope.launch(Dispatchers.Main) {
            if (ttsEngine == null) {
                ttsEngine = TextToSpeech(appContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ttsEngine?.language = Locale.UK
                        ttsReady = true
                        ttsEngine?.speak(text, TextToSpeech.QUEUE_ADD, null, "commute_tts_${System.currentTimeMillis()}")
                    }
                }
            } else if (ttsReady) {
                ttsEngine?.speak(text, TextToSpeech.QUEUE_ADD, null, "commute_tts_${System.currentTimeMillis()}")
            }
        }
    }

    companion object {
        private const val KEY_PREFERENCES_JSON = "commute_hub_preferences_v2"
        private const val KEY_DARWIN_GATEWAY_URL = "commute_hub_darwin_gateway_url_v2"
        private const val VAULT_KEY_TFL_APP_KEY = "commute_hub::tfl_app_key_v2"
        private const val NOTIFICATION_CHANNEL_ID = "clara_commute_hub_v2"

        @Volatile
        private var instance: CommuteRepository? = null

        fun getInstance(context: Context = ClaraApplication.appContext): CommuteRepository {
            return instance ?: synchronized(this) {
                instance ?: CommuteRepository(context).also { instance = it }
            }
        }
    }
}