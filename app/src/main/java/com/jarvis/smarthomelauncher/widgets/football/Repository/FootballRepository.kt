package com.jarvis.smarthomelauncher.widgets.football.Repository

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
import com.jarvis.smarthomelauncher.core.ClaraInAppMusicPlayer
import com.jarvis.smarthomelauncher.core.ClaraSecretVault
import com.jarvis.smarthomelauncher.widgets.football.Models.*
import com.jarvis.smarthomelauncher.widgets.football.Services.FootballDataProvider
import com.jarvis.smarthomelauncher.widgets.football.Services.FootballInsightRulesEngine
import com.jarvis.smarthomelauncher.widgets.football.Services.HybridLiveFootballDataProvider
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Household timeline item exported by Football Hub for Clara's ecosystem (CWAS Rule 19).
 */
data class FootballCalendarTimelineEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val competitionName: String,
    val broadcastChannel: String,
    val kickoffEpochMs: Long,
    val isPrimaryFavourite: Boolean
)

/**
 * Emitted when a Football smart-home automation rule triggers at kickoff or lineup release.
 */
data class FootballAutomationDispatchEvent(
    val hook: FootballAutomationHook,
    val fixture: FootballFixture,
    val primaryColorHex: String,
    val spokenAnnouncement: String
)

/**
 * CWAS Rule 8: Dedicated SQLite persistence layer for Football Hub v3.0.
 * Stores favourite teams (max 10), user preferences, match reminders, notification deduplication logs,
 * offline fixture/table caches, and Clara household timeline entries.
 */
class FootballDatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_FAVOURITE_TEAMS (
                team_id TEXT PRIMARY KEY NOT NULL,
                api_football_id INTEGER,
                espn_team_id TEXT,
                name TEXT NOT NULL,
                short_name TEXT NOT NULL,
                badge_url TEXT NOT NULL,
                primary_competition_id TEXT NOT NULL,
                primary_color_hex TEXT NOT NULL,
                secondary_color_hex TEXT NOT NULL,
                is_primary_favourite INTEGER NOT NULL DEFAULT 0,
                sort_order INTEGER NOT NULL DEFAULT 0,
                added_at_ms INTEGER NOT NULL
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
            CREATE TABLE IF NOT EXISTS $TABLE_FIXTURE_CACHE (
                fixture_id TEXT PRIMARY KEY NOT NULL,
                competition_id TEXT NOT NULL,
                kickoff_epoch_ms INTEGER NOT NULL,
                payload_json TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_STANDINGS_CACHE (
                competition_id TEXT PRIMARY KEY NOT NULL,
                payload_json TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_REMINDERS (
                fixture_id TEXT PRIMARY KEY NOT NULL,
                home_team_name TEXT NOT NULL,
                away_team_name TEXT NOT NULL,
                competition_name TEXT NOT NULL,
                kickoff_epoch_ms INTEGER NOT NULL,
                channel_summary TEXT NOT NULL,
                remind_at_epoch_ms INTEGER NOT NULL,
                fired INTEGER NOT NULL DEFAULT 0
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

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TIMELINE_EXPORT (
                entry_id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                subtitle TEXT NOT NULL,
                competition_name TEXT NOT NULL,
                broadcast_channel TEXT NOT NULL,
                kickoff_epoch_ms INTEGER NOT NULL,
                is_primary INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    companion object {
        private const val DATABASE_NAME = "clara_football_hub_v3.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_FAVOURITE_TEAMS = "football_favourite_teams"
        const val TABLE_PREFERENCES = "football_preferences"
        const val TABLE_FIXTURE_CACHE = "football_fixture_cache"
        const val TABLE_STANDINGS_CACHE = "football_standings_cache"
        const val TABLE_REMINDERS = "football_reminders"
        const val TABLE_NOTIFICATION_LOG = "football_notification_log"
        const val TABLE_TIMELINE_EXPORT = "football_timeline_export"

        @Volatile
        private var instance: FootballDatabaseHelper? = null

        fun getInstance(context: Context): FootballDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: FootballDatabaseHelper(context).also { instance = it }
            }
        }
    }
}

/**
 * CWAS Rule 7: Single source of truth for Football Hub v3.0.
 * Widget ↓ FootballRepository ↓ FootballDatabaseHelper (SQLite) ↓ FootballDataProvider (Live APIs).
 */
class FootballRepository private constructor(context: Context) {

    private val appContext: Context = ClaraApplication.requireAppContext(context)
    private val dbHelper: FootballDatabaseHelper = FootballDatabaseHelper.getInstance(appContext)
    private val gson = Gson()
    private val refreshMutex = Mutex()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dataProvider: FootballDataProvider = HybridLiveFootballDataProvider(
        apiKeyProvider = { getApiFootballKey() }
    )

    private val _uiState = MutableStateFlow<FootballWidgetUiState>(FootballWidgetUiState.Loading)
    val uiState: StateFlow<FootballWidgetUiState> = _uiState.asStateFlow()

    private val _timelineEntries = MutableStateFlow<List<FootballCalendarTimelineEntry>>(emptyList())
    val timelineEntries: StateFlow<List<FootballCalendarTimelineEntry>> = _timelineEntries.asStateFlow()

    private val _automationEvents = MutableSharedFlow<FootballAutomationDispatchEvent>(extraBufferCapacity = 16)
    val automationEvents: SharedFlow<FootballAutomationDispatchEvent> = _automationEvents.asSharedFlow()

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
    // ClaraSecretVault API Key Management (Zero Hardcoded Keys)
    // ========================================================================

    fun getApiFootballKey(): String? {
        return ClaraSecretVault.getString(appContext, VAULT_KEY_API_FOOTBALL)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun setApiFootballKey(apiKey: String) {
        val cleaned = apiKey.trim()
        if (cleaned.isBlank()) {
            ClaraSecretVault.remove(appContext, VAULT_KEY_API_FOOTBALL)
        } else {
            ClaraSecretVault.putString(appContext, VAULT_KEY_API_FOOTBALL, cleaned)
        }
        triggerRefresh(forceRemote = true)
    }

    // ========================================================================
    // Autonomous Polling Loop (Faster Cadence During Live & Match-Day Modes)
    // ========================================================================

    private fun startAutonomousPollingLoop() {
        pollingJob?.cancel()
        pollingJob = repositoryScope.launch {
            while (isActive) {
                val currentMode = (_uiState.value as? FootballWidgetUiState.Ready)?.snapshot?.displayMode
                val intervalMs = when (currentMode) {
                    FootballWidgetDisplayMode.LIVE -> 45_000L
                    FootballWidgetDisplayMode.MATCH_DAY -> 180_000L
                    else -> 600_000L
                }
                delay(intervalMs)
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
        val teams = loadFavouriteTeamsFromDb()
        if (teams.isEmpty()) {
            _uiState.value = FootballWidgetUiState.Empty()
            _timelineEntries.value = emptyList()
            return
        }

        val cachedFixtures = loadCachedFixturesFromDb()
        val cachedStandings = loadCachedStandingsFromDb()
        if (cachedFixtures.isNotEmpty() || cachedStandings.isNotEmpty()) {
            val prefs = loadPreferencesFromDb()
            val cachedSnapshot = assembleSnapshot(
                favouriteTeams = teams,
                preferences = prefs,
                rawFixtures = cachedFixtures,
                standingsMap = cachedStandings,
                topScorersMap = emptyMap(),
                topAssistsMap = emptyMap(),
                squadMap = emptyMap(),
                issuesList = emptyList(),
                newsList = emptyList()
            )
            _uiState.value = FootballWidgetUiState.Ready(cachedSnapshot)
        } else {
            _uiState.value = FootballWidgetUiState.Loading
        }

        refreshDashboardSnapshot(forceRemote = true)
    }

    suspend fun refreshDashboardSnapshot(forceRemote: Boolean = true) = refreshMutex.withLock {
        val teams = loadFavouriteTeamsFromDb()
        val prefs = loadPreferencesFromDb()

        if (teams.isEmpty()) {
            _uiState.value = FootballWidgetUiState.Empty()
            clearTimelineExportInDb()
            _timelineEntries.value = emptyList()
            return@withLock
        }

        if (_uiState.value !is FootballWidgetUiState.Ready) {
            _uiState.value = FootballWidgetUiState.Loading
        }

        try {
            val activeCompetitions = resolveTrackedCompetitions(teams, prefs)
            val today = LocalDate.now()
            val seasonYear = HybridLiveFootballDataProvider.currentFootballSeasonYear(today)

            val fixtures = if (forceRemote) {
                val windowFixtures = dataProvider.fetchFixturesWindow(
                    competitions = activeCompetitions,
                    fromDate = today.minusDays(7),
                    toDate = today.plusDays(31),
                    seasonYear = seasonYear
                )
                val liveNow = dataProvider.fetchLiveMatches(activeCompetitions)
                val merged = mergeLiveIntoWindow(windowFixtures, liveNow)
                if (merged.isNotEmpty()) {
                    saveFixturesToCacheDb(merged)
                    merged
                } else {
                    loadCachedFixturesFromDb()
                }
            } else {
                loadCachedFixturesFromDb()
            }

            val standingsMap = LinkedHashMap<String, List<LeagueStandingRow>>()
            coroutineScope {
                activeCompetitions.map { comp ->
                    async {
                        val rows = if (forceRemote) {
                            dataProvider.fetchStandings(comp, seasonYear).also { fetched ->
                                if (fetched.isNotEmpty()) saveStandingsToCacheDb(comp.id, fetched)
                            }.ifEmpty { loadCachedStandingsForCompetition(comp.id) }
                        } else {
                            loadCachedStandingsForCompetition(comp.id)
                        }
                        comp.id to rows
                    }
                }.awaitAll().forEach { (compId, rows) ->
                    if (rows.isNotEmpty()) {
                        standingsMap[compId] = rows
                    }
                }
            }

            val topScorersMap = LinkedHashMap<String, List<FootballPlayerStatEntry>>()
            val topAssistsMap = LinkedHashMap<String, List<FootballPlayerStatEntry>>()
            val leaderCompetitions = activeCompetitions.take(4)
            coroutineScope {
                leaderCompetitions.map { comp ->
                    async {
                        val pair = dataProvider.fetchCompetitionLeaders(
                            competition = comp,
                            seasonYear = seasonYear,
                            standingsFallback = standingsMap[comp.id].orEmpty()
                        )
                        Triple(comp.id, pair.first, pair.second)
                    }
                }.awaitAll().forEach { (compId, scorers, assists) ->
                    if (scorers.isNotEmpty()) topScorersMap[compId] = scorers
                    if (assists.isNotEmpty()) topAssistsMap[compId] = assists
                }
            }

            val squadMap = LinkedHashMap<String, List<FootballPlayerStatEntry>>()
            val issuesList = mutableListOf<PlayerAvailabilityIssue>()
            coroutineScope {
                teams.map { favTeam ->
                    async {
                        val (squad, issues) = dataProvider.fetchTeamSquadAndIssues(favTeam, seasonYear)
                        Triple(favTeam.teamId, squad, issues)
                    }
                }.awaitAll().forEach { (teamId, squad, issues) ->
                    if (squad.isNotEmpty()) squadMap[teamId] = squad
                    issuesList.addAll(issues)
                }
            }

            val newsArticles = dataProvider.fetchNews(
                favouriteTeams = teams,
                competitions = activeCompetitions
            )

            var snapshot = assembleSnapshot(
                favouriteTeams = teams,
                preferences = prefs,
                rawFixtures = fixtures,
                standingsMap = standingsMap,
                topScorersMap = topScorersMap,
                topAssistsMap = topAssistsMap,
                squadMap = squadMap,
                issuesList = issuesList.distinctBy { "${it.playerName}_${it.reason}" },
                newsList = newsArticles
            )

            // Enrich hero fixture with live stadium weather & confirmed lineups when in Match-Day or Live mode
            val hero = snapshot.heroFixture
            if (hero != null &&
                (snapshot.displayMode == FootballWidgetDisplayMode.MATCH_DAY ||
                        snapshot.displayMode == FootballWidgetDisplayMode.LIVE)
            ) {
                val enrichment = dataProvider.fetchFixtureDetailEnrichment(hero, seasonYear)
                val enrichedHero = hero.copy(
                    homeLineup = enrichment.homeLineup ?: hero.homeLineup,
                    awayLineup = enrichment.awayLineup ?: hero.awayLineup,
                    lineupState = enrichment.lineupState,
                    events = enrichment.events.ifEmpty { hero.events },
                    stadiumWeather = enrichment.stadiumWeather ?: hero.stadiumWeather,
                    refereeName = enrichment.refereeName.ifBlank { hero.refereeName },
                    attendance = enrichment.attendance ?: hero.attendance
                )
                val updatedLive = snapshot.liveMatches.map { if (it.id == enrichedHero.id) enrichedHero else it }
                val updatedUpcoming = snapshot.upcomingFixtures.map { if (it.id == enrichedHero.id) enrichedHero else it }
                snapshot = snapshot.copy(
                    heroFixture = enrichedHero,
                    liveMatches = updatedLive,
                    upcomingFixtures = updatedUpcoming
                )
            }

            _uiState.value = FootballWidgetUiState.Ready(snapshot)
            exportTimelineEntries(snapshot)
            evaluateNotificationsAndAutomations(snapshot)
        } catch (e: Exception) {
            val cachedFixtures = loadCachedFixturesFromDb()
            val cachedStandings = loadCachedStandingsFromDb()
            if (cachedFixtures.isNotEmpty() || cachedStandings.isNotEmpty()) {
                val fallbackSnapshot = assembleSnapshot(
                    favouriteTeams = teams,
                    preferences = prefs,
                    rawFixtures = cachedFixtures,
                    standingsMap = cachedStandings,
                    topScorersMap = emptyMap(),
                    topAssistsMap = emptyMap(),
                    squadMap = emptyMap(),
                    issuesList = emptyList(),
                    newsList = emptyList()
                )
                _uiState.value = FootballWidgetUiState.Ready(fallbackSnapshot)
            } else {
                _uiState.value = FootballWidgetUiState.Error(
                    message = "Unable to retrieve data",
                    technicalDetail = e.message.orEmpty()
                )
            }
        }
    }

    // ========================================================================
    // Team & Settings Management (SQLite Persisted)
    // ========================================================================

    suspend fun searchClubsToFollow(query: String): List<FootballClubSummary> {
        val prefs = loadPreferencesFromDb()
        val favComps = prefs.favouriteCompetitionIds.mapNotNull { SupportedCompetition.fromIdOrName(it) }
            .ifEmpty { SupportedCompetition.entries }
        return dataProvider.searchTeams(query = query, competitions = favComps)
    }

    suspend fun addFavouriteTeam(
        club: FootballClubSummary,
        primaryCompetition: SupportedCompetition,
        makePrimaryFavourite: Boolean = false
    ): Result<List<FavouriteTeamEntry>> = withContext(Dispatchers.IO) {
        val prefs = loadPreferencesFromDb()
        val existing = loadFavouriteTeamsFromDb()

        if (!prefs.allowMultipleTeams && existing.isNotEmpty()) {
            return@withContext Result.failure(IllegalStateException("Multiple teams disabled in settings."))
        }
        if (existing.size >= prefs.maximumTeams && existing.none {
                HybridLiveFootballDataProvider.clubsMatch(it.name, club.name)
            }
        ) {
            return@withContext Result.failure(
                IllegalStateException("Maximum of ${prefs.maximumTeams} favourite teams reached.")
            )
        }

        val shouldBePrimary = makePrimaryFavourite || existing.isEmpty()
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            if (shouldBePrimary) {
                val resetValues = ContentValues().apply { put("is_primary_favourite", 0) }
                db.update(FootballDatabaseHelper.TABLE_FAVOURITE_TEAMS, resetValues, null, null)
            }

            val cv = ContentValues().apply {
                put("team_id", club.id)
                if (club.apiFootballId != null) put("api_football_id", club.apiFootballId)
                if (!club.espnId.isNullOrBlank()) put("espn_team_id", club.espnId)
                put("name", club.name)
                put("short_name", club.shortName.ifBlank { club.name })
                put("badge_url", club.badgeUrl)
                put("primary_competition_id", primaryCompetition.id)
                put("primary_color_hex", club.primaryColorHex)
                put("secondary_color_hex", "#FFFFFF")
                put("is_primary_favourite", if (shouldBePrimary) 1 else 0)
                put("sort_order", existing.size)
                put("added_at_ms", System.currentTimeMillis())
            }
            db.insertWithOnConflict(
                FootballDatabaseHelper.TABLE_FAVOURITE_TEAMS,
                null,
                cv,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        val updated = loadFavouriteTeamsFromDb()
        triggerRefresh(forceRemote = true)
        Result.success(updated)
    }

    suspend fun removeFavouriteTeam(teamId: String): List<FavouriteTeamEntry> = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete(FootballDatabaseHelper.TABLE_FAVOURITE_TEAMS, "team_id = ?", arrayOf(teamId))
        val remaining = loadFavouriteTeamsFromDb()
        if (remaining.isNotEmpty() && remaining.none { it.isPrimaryFavourite }) {
            setPrimaryFavouriteTeamInternal(remaining.first().teamId)
        }
        val finalTeams = loadFavouriteTeamsFromDb()
        triggerRefresh(forceRemote = true)
        finalTeams
    }

    suspend fun setPrimaryFavouriteTeam(teamId: String): List<FavouriteTeamEntry> = withContext(Dispatchers.IO) {
        setPrimaryFavouriteTeamInternal(teamId)
        val updated = loadFavouriteTeamsFromDb()
        triggerRefresh(forceRemote = false)
        updated
    }

    private fun setPrimaryFavouriteTeamInternal(teamId: String) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.update(
                FootballDatabaseHelper.TABLE_FAVOURITE_TEAMS,
                ContentValues().apply { put("is_primary_favourite", 0) },
                null,
                null
            )
            db.update(
                FootballDatabaseHelper.TABLE_FAVOURITE_TEAMS,
                ContentValues().apply { put("is_primary_favourite", 1) },
                "team_id = ?",
                arrayOf(teamId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun updatePreferences(updated: FootballHubPreferences) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("pref_key", KEY_PREFERENCES_JSON)
            put("pref_value", gson.toJson(updated))
        }
        db.insertWithOnConflict(
            FootballDatabaseHelper.TABLE_PREFERENCES,
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        triggerRefresh(forceRemote = false)
    }

    fun getPreferencesSync(): FootballHubPreferences = loadPreferencesFromDb()

    // ========================================================================
    // Fixture Detail Drill-Down & Match Reminders
    // ========================================================================

    suspend fun loadFixtureDetail(fixture: FootballFixture): FootballFixtureDetail = withContext(Dispatchers.IO) {
        val seasonYear = HybridLiveFootballDataProvider.currentFootballSeasonYear()
        val enrichment = dataProvider.fetchFixtureDetailEnrichment(fixture, seasonYear)
        val readyState = _uiState.value as? FootballWidgetUiState.Ready
        val fullTable = readyState?.snapshot?.standingsByCompetition?.get(fixture.competitionId)
            ?: loadCachedStandingsForCompetition(fixture.competitionId)

        val annotatedTable = fullTable.map { row ->
            val matchesMatchTeam = HybridLiveFootballDataProvider.clubsMatch(row.team.name, fixture.homeTeam.name) ||
                    HybridLiveFootballDataProvider.clubsMatch(row.team.name, fixture.awayTeam.name)
            row.copy(isFavouriteTeam = row.isFavouriteTeam || matchesMatchTeam)
        }

        val homeStanding = annotatedTable.firstOrNull {
            HybridLiveFootballDataProvider.clubsMatch(it.team.name, fixture.homeTeam.name)
        }
        val awayStanding = annotatedTable.firstOrNull {
            HybridLiveFootballDataProvider.clubsMatch(it.team.name, fixture.awayTeam.name)
        }

        val enrichedFixture = fixture.copy(
            homeLineup = enrichment.homeLineup ?: fixture.homeLineup,
            awayLineup = enrichment.awayLineup ?: fixture.awayLineup,
            lineupState = enrichment.lineupState,
            events = enrichment.events.ifEmpty { fixture.events },
            broadcast = enrichment.broadcast,
            refereeName = enrichment.refereeName.ifBlank { fixture.refereeName },
            attendance = enrichment.attendance ?: fixture.attendance,
            stadiumWeather = enrichment.stadiumWeather ?: fixture.stadiumWeather
        )

        val reminders = loadActiveReminderIdsFromDb()

        FootballFixtureDetail(
            fixture = enrichedFixture,
            broadcast = enrichment.broadcast,
            lineupState = enrichment.lineupState,
            homeLineup = enrichedFixture.homeLineup,
            awayLineup = enrichedFixture.awayLineup,
            contextLeagueTable = annotatedTable,
            homeRecentForm = homeStanding?.recentForm.orEmpty(),
            awayRecentForm = awayStanding?.recentForm.orEmpty(),
            headToHeadMeetings = enrichment.headToHead,
            injuries = enrichment.injuries,
            suspensions = enrichment.suspensions,
            hasReminderSet = fixture.id in reminders
        )
    }

    suspend fun toggleMatchReminder(fixture: FootballFixture): Boolean = withContext(Dispatchers.IO) {
        val existingIds = loadActiveReminderIdsFromDb()
        val db = dbHelper.writableDatabase
        val nowSet = if (fixture.id in existingIds) {
            db.delete(FootballDatabaseHelper.TABLE_REMINDERS, "fixture_id = ?", arrayOf(fixture.id))
            false
        } else {
            val prefs = loadPreferencesFromDb()
            val remindAt = (fixture.kickoffEpochMs - prefs.reminderLeadMinutes * 60_000L)
                .coerceAtLeast(System.currentTimeMillis() + 5_000L)
            val cv = ContentValues().apply {
                put("fixture_id", fixture.id)
                put("home_team_name", fixture.homeTeam.name)
                put("away_team_name", fixture.awayTeam.name)
                put("competition_name", fixture.competitionName)
                put("kickoff_epoch_ms", fixture.kickoffEpochMs)
                put("channel_summary", fixture.broadcast.displayChannel)
                put("remind_at_epoch_ms", remindAt)
                put("fired", 0)
            }
            db.insertWithOnConflict(
                FootballDatabaseHelper.TABLE_REMINDERS,
                null,
                cv,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            true
        }
        triggerRefresh(forceRemote = false)
        nowSet
    }

    // ========================================================================
    // Voice Assistant Handler (Supports All 6 JSON Voice Commands)
    // ========================================================================

    fun handleVoiceCommand(rawUtterance: String): FootballVoiceCommandResponse {
        val q = rawUtterance.trim().lowercase(Locale.UK)
        val snapshot = (_uiState.value as? FootballWidgetUiState.Ready)?.snapshot
            ?: return FootballVoiceCommandResponse(
                handled = true,
                spokenText = "Football Hub has no favourite teams configured yet. Open Football Hub to add your clubs.",
                targetTab = FootballHubTab.TEAMS
            )

        val zone = ZoneId.systemDefault()
        val timeFmt = DateTimeFormatter.ofPattern("EEEE 'at' HH:mm", Locale.UK)

        // 1. "Show live scores"
        if (q.contains("live score") || q.contains("live match")) {
            val live = snapshot.liveMatches
            val spoken = if (live.isEmpty()) {
                "There are no live football matches in your tracked competitions right now."
            } else {
                val summary = live.take(3).joinToString("; ") {
                    "${it.homeTeam.shortName} ${it.homeScore ?: 0}, ${it.awayTeam.shortName} ${it.awayScore ?: 0} (${it.formattedLiveMinute})"
                }
                "Live scores: $summary."
            }
            return FootballVoiceCommandResponse(
                handled = true,
                spokenText = spoken,
                targetTab = FootballHubTab.LIVE
            )
        }

        // 2. "Show today's matches"
        if (q.contains("today's match") || q.contains("todays match") || q.contains("matches today")) {
            val todayMatches = snapshot.allGroupedFixtures[FixtureTimeGroup.TODAY].orEmpty()
            val spoken = if (todayMatches.isEmpty()) {
                "There are no scheduled matches today across your tracked competitions."
            } else {
                val listText = todayMatches.take(4).joinToString(", ") {
                    val ko = Instant.ofEpochMilli(it.kickoffEpochMs).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
                    "${it.homeTeam.shortName} versus ${it.awayTeam.shortName} at $ko on ${it.broadcast.displayChannel}"
                }
                "Today's matches: $listText."
            }
            return FootballVoiceCommandResponse(
                handled = true,
                spokenText = spoken,
                targetTab = FootballHubTab.FIXTURES
            )
        }

        // 3. "Show [Competition] table" (e.g., "Show Premier League table")
        if (q.contains("table") || q.contains("standings")) {
            val matchedComp = SupportedCompetition.entries.firstOrNull {
                q.contains(it.displayName.lowercase(Locale.UK)) || q.contains(it.shortName.lowercase(Locale.UK))
            } ?: snapshot.primaryTeam?.let { SupportedCompetition.fromIdOrName(it.primaryCompetitionId) }
            ?: SupportedCompetition.PREMIER_LEAGUE

            val rows = snapshot.standingsByCompetition[matchedComp.id].orEmpty()
            val spoken = if (rows.isEmpty()) {
                "Opening the ${matchedComp.displayName} table."
            } else {
                val topThree = rows.take(3).joinToString(", ") { "${it.position}. ${it.team.shortName} on ${it.points} points" }
                "In the ${matchedComp.displayName}, the top three are: $topThree."
            }
            return FootballVoiceCommandResponse(
                handled = true,
                spokenText = spoken,
                targetTab = FootballHubTab.TABLES,
                targetCompetitionId = matchedComp.id
            )
        }

        // 4. "Has the lineup been announced"
        if (q.contains("lineup") || q.contains("team sheet") || q.contains("starting eleven")) {
            val hero = snapshot.heroFixture
            val spoken = if (hero == null) {
                "No upcoming fixture was found to check lineups."
            } else {
                when (hero.lineupState) {
                    LineupAvailabilityState.AVAILABLE ->
                        "Yes, lineups are available for ${hero.homeTeam.shortName} versus ${hero.awayTeam.shortName}."
                    LineupAvailabilityState.EXPECTED_SOON ->
                        "Lineups for ${hero.homeTeam.shortName} versus ${hero.awayTeam.shortName} are expected within the hour before kickoff."
                    LineupAvailabilityState.NOT_AVAILABLE ->
                        "Lineups for ${hero.homeTeam.shortName} versus ${hero.awayTeam.shortName} have not been announced yet."
                }
            }
            return FootballVoiceCommandResponse(
                handled = true,
                spokenText = spoken,
                targetTab = FootballHubTab.OVERVIEW
            )
        }

        // 5. "What channel is the [Team] match on"
        if (q.contains("what channel") || q.contains("which channel") || q.contains("broadcaster")) {
            val targetFixture = findFixtureMentionedInQuery(q, snapshot) ?: snapshot.heroFixture
            val spoken = if (targetFixture == null) {
                "I couldn't find an upcoming televised match."
            } else {
                "${targetFixture.homeTeam.name} versus ${targetFixture.awayTeam.name} is broadcast on ${targetFixture.broadcast.displayChannel}."
            }
            return FootballVoiceCommandResponse(
                handled = true,
                spokenText = spoken,
                targetTab = FootballHubTab.FIXTURES
            )
        }

        // 6. "When does [Team] play next"
        if (q.contains("play next") || q.contains("next match") || q.contains("next game")) {
            val targetFixture = findFixtureMentionedInQuery(q, snapshot) ?: snapshot.heroFixture
            val spoken = if (targetFixture == null) {
                "No upcoming scheduled fixture was found."
            } else {
                val koFormatted = Instant.ofEpochMilli(targetFixture.kickoffEpochMs).atZone(zone).format(timeFmt)
                "${targetFixture.homeTeam.name} play ${targetFixture.awayTeam.name} on $koFormatted in the ${targetFixture.competitionName}, live on ${targetFixture.broadcast.displayChannel}."
            }
            return FootballVoiceCommandResponse(
                handled = true,
                spokenText = spoken,
                targetTab = FootballHubTab.FIXTURES
            )
        }

        return FootballVoiceCommandResponse(handled = false, spokenText = "")
    }

    private fun findFixtureMentionedInQuery(
        lowerQuery: String,
        snapshot: FootballDashboardSnapshot
    ): FootballFixture? {
        val candidates = snapshot.liveMatches + snapshot.upcomingFixtures
        return candidates.firstOrNull { fix ->
            lowerQuery.contains(fix.homeTeam.shortName.lowercase(Locale.UK)) ||
                    lowerQuery.contains(fix.awayTeam.shortName.lowercase(Locale.UK)) ||
                    lowerQuery.contains(fix.homeTeam.name.lowercase(Locale.UK)) ||
                    lowerQuery.contains(fix.awayTeam.name.lowercase(Locale.UK))
        }
    }

    // ========================================================================
    // Snapshot Assembly, Mode Resolution & Fixture Grouping
    // ========================================================================

    private fun assembleSnapshot(
        favouriteTeams: List<FavouriteTeamEntry>,
        preferences: FootballHubPreferences,
        rawFixtures: List<FootballFixture>,
        standingsMap: Map<String, List<LeagueStandingRow>>,
        topScorersMap: Map<String, List<FootballPlayerStatEntry>>,
        topAssistsMap: Map<String, List<FootballPlayerStatEntry>>,
        squadMap: Map<String, List<FootballPlayerStatEntry>>,
        issuesList: List<PlayerAvailabilityIssue>,
        newsList: List<FootballNewsArticle>
    ): FootballDashboardSnapshot {
        val nowMs = System.currentTimeMillis()
        val orderedFavourites = favouriteTeams.sortedWith(
            compareByDescending<FavouriteTeamEntry> { preferences.prioritizeFavouriteTeam && it.isPrimaryFavourite }
                .thenBy { it.sortOrder }
        )
        val primaryTeam = orderedFavourites.firstOrNull { it.isPrimaryFavourite } ?: orderedFavourites.firstOrNull()

        val annotatedFixtures = rawFixtures.map { fix ->
            val matchesPrimary = primaryTeam != null && (
                    HybridLiveFootballDataProvider.clubsMatch(fix.homeTeam.name, primaryTeam.name) ||
                            HybridLiveFootballDataProvider.clubsMatch(fix.awayTeam.name, primaryTeam.name)
                    )
            val matchesAnyFav = orderedFavourites.any { fav ->
                HybridLiveFootballDataProvider.clubsMatch(fix.homeTeam.name, fav.name) ||
                        HybridLiveFootballDataProvider.clubsMatch(fix.awayTeam.name, fav.name)
            }
            fix.copy(
                involvesFavouriteTeam = matchesAnyFav,
                involvesPrimaryFavourite = matchesPrimary
            )
        }

        val annotatedStandings = standingsMap.mapValues { (_, rows) ->
            rows.map { row ->
                val isPrimary = primaryTeam != null &&
                        HybridLiveFootballDataProvider.clubsMatch(row.team.name, primaryTeam.name)
                val isFav = orderedFavourites.any { fav ->
                    HybridLiveFootballDataProvider.clubsMatch(row.team.name, fav.name)
                }
                row.copy(isFavouriteTeam = isFav, isPrimaryFavourite = isPrimary)
            }
        }

        val liveMatches = annotatedFixtures
            .filter { it.status.isLive }
            .sortedWith(
                compareByDescending<FootballFixture> { it.involvesPrimaryFavourite }
                    .thenByDescending { it.involvesFavouriteTeam }
                    .thenBy { it.kickoffEpochMs }
            )

        val futureScheduled = annotatedFixtures
            .filter { !it.status.isLive && !it.status.isFinished && it.kickoffEpochMs >= nowMs - 15 * 60_000L }
            .sortedBy { it.kickoffEpochMs }

        val favUpcoming = futureScheduled.filter { it.involvesFavouriteTeam }
        val prioritizedUpcoming = (favUpcoming + futureScheduled.filterNot { it.involvesFavouriteTeam }).distinctBy { it.id }

        val recentResults = annotatedFixtures
            .filter { it.status.isFinished }
            .sortedByDescending { it.kickoffEpochMs }

        val heroFixture: FootballFixture? = when {
            liveMatches.any { it.involvesPrimaryFavourite } -> liveMatches.first { it.involvesPrimaryFavourite }
            liveMatches.any { it.involvesFavouriteTeam } -> liveMatches.first { it.involvesFavouriteTeam }
            liveMatches.isNotEmpty() -> liveMatches.first()
            prioritizedUpcoming.any { it.involvesPrimaryFavourite } -> prioritizedUpcoming.first { it.involvesPrimaryFavourite }
            prioritizedUpcoming.isNotEmpty() -> prioritizedUpcoming.first()
            else -> recentResults.firstOrNull()
        }

        val displayMode = when {
            heroFixture != null && heroFixture.status.isLive -> FootballWidgetDisplayMode.LIVE
            heroFixture != null && !heroFixture.status.isFinished &&
                    (heroFixture.kickoffEpochMs - nowMs) in 0..(preferences.matchDayLeadHours * 3_600_000L) ->
                FootballWidgetDisplayMode.MATCH_DAY
            else -> FootballWidgetDisplayMode.NORMAL
        }

        val primaryLeagueSnapshot = primaryTeam?.let { pTeam ->
            val row = annotatedStandings.values.flatten().firstOrNull {
                HybridLiveFootballDataProvider.clubsMatch(it.team.name, pTeam.name)
            }
            if (row != null) {
                val totalTeams = annotatedStandings[row.competitionId]?.size ?: 20
                LeaguePositionSnapshot(
                    teamId = pTeam.teamId,
                    teamName = pTeam.name,
                    competitionId = row.competitionId,
                    competitionName = row.competitionName,
                    position = row.position,
                    points = row.points,
                    gamesPlayed = row.played,
                    goalDifference = row.goalDifference,
                    totalTeamsInTable = totalTeams,
                    zone = row.zone,
                    recentForm = row.recentForm.ifEmpty {
                        deriveRecentFormFromResults(pTeam.name, recentResults)
                    }
                )
            } else null
        }

        val teamCards = orderedFavourites.map { fav ->
            val standing = annotatedStandings.values.flatten().firstOrNull {
                HybridLiveFootballDataProvider.clubsMatch(it.team.name, fav.name)
            }
            val form = standing?.recentForm?.takeIf { it.isNotEmpty() }
                ?: deriveRecentFormFromResults(fav.name, recentResults)
            val nextFix = futureScheduled.firstOrNull {
                HybridLiveFootballDataProvider.clubsMatch(it.homeTeam.name, fav.name) ||
                        HybridLiveFootballDataProvider.clubsMatch(it.awayTeam.name, fav.name)
            }
            val liveFix = liveMatches.firstOrNull {
                HybridLiveFootballDataProvider.clubsMatch(it.homeTeam.name, fav.name) ||
                        HybridLiveFootballDataProvider.clubsMatch(it.awayTeam.name, fav.name)
            }
            TeamHubCardModel(
                favouriteEntry = fav,
                standing = standing,
                recentForm = form,
                nextFixture = nextFix,
                liveFixture = liveFix
            )
        }

        val groupedFixtures = groupFixturesChronologically(futureScheduled, nowMs)

        val insights = FootballInsightRulesEngine.evaluateInsights(
            primaryTeam = primaryTeam,
            favouriteTeams = orderedFavourites,
            liveMatches = liveMatches,
            upcomingFixtures = prioritizedUpcoming,
            standingsByCompetition = annotatedStandings,
            nowEpochMs = nowMs
        )

        return FootballDashboardSnapshot(
            displayMode = displayMode,
            favouriteTeams = orderedFavourites,
            primaryTeam = primaryTeam,
            heroFixture = heroFixture,
            liveMatches = liveMatches,
            upcomingFixtures = prioritizedUpcoming.filter { it.id != heroFixture?.id }.take(5),
            allGroupedFixtures = groupedFixtures,
            recentResults = recentResults,
            primaryLeagueSnapshot = primaryLeagueSnapshot,
            standingsByCompetition = annotatedStandings,
            teamCards = teamCards,
            insights = insights,
            topScorersByCompetition = topScorersMap,
            topAssistsByCompetition = topAssistsMap,
            squadByTeamId = squadMap,
            availabilityIssues = issuesList,
            newsArticles = newsList,
            activeReminders = loadActiveReminderIdsFromDb(),
            preferences = preferences,
            activeProviderLabel = dataProvider.providerName,
            lastSyncedEpochMs = nowMs
        )
    }

    private fun deriveRecentFormFromResults(
        clubName: String,
        finishedFixtures: List<FootballFixture>
    ): List<FormResult> {
        return finishedFixtures
            .filter {
                HybridLiveFootballDataProvider.clubsMatch(it.homeTeam.name, clubName) ||
                        HybridLiveFootballDataProvider.clubsMatch(it.awayTeam.name, clubName)
            }
            .sortedBy { it.kickoffEpochMs }
            .takeLast(5)
            .mapNotNull { fix ->
                val hs = fix.homeScore ?: return@mapNotNull null
                val ascore = fix.awayScore ?: return@mapNotNull null
                val isHome = HybridLiveFootballDataProvider.clubsMatch(fix.homeTeam.name, clubName)
                when {
                    hs == ascore -> FormResult.DRAW
                    (isHome && hs > ascore) || (!isHome && ascore > hs) -> FormResult.WIN
                    else -> FormResult.LOSS
                }
            }
    }

    private fun groupFixturesChronologically(
        upcoming: List<FootballFixture>,
        nowMs: Long
    ): Map<FixtureTimeGroup, List<FootballFixture>> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val tomorrow = today.plusDays(1)
        val endOfThisWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val endOfNextWeek = endOfThisWeek.plusDays(7)
        val endOfThisMonth = today.plusDays(31)

        val map = LinkedHashMap<FixtureTimeGroup, MutableList<FootballFixture>>()
        FixtureTimeGroup.entries.forEach { map[it] = mutableListOf() }

        for (fix in upcoming) {
            val d = Instant.ofEpochMilli(fix.kickoffEpochMs).atZone(zone).toLocalDate()
            val group = when {
                d == today -> FixtureTimeGroup.TODAY
                d == tomorrow -> FixtureTimeGroup.TOMORROW
                !d.isAfter(endOfThisWeek) -> FixtureTimeGroup.THIS_WEEK
                !d.isAfter(endOfNextWeek) -> FixtureTimeGroup.NEXT_WEEK
                !d.isAfter(endOfThisMonth) -> FixtureTimeGroup.THIS_MONTH
                else -> null
            }
            if (group != null) {
                map[group]?.add(fix)
            }
        }
        return map.filterValues { it.isNotEmpty() }
    }

    private fun resolveTrackedCompetitions(
        teams: List<FavouriteTeamEntry>,
        prefs: FootballHubPreferences
    ): List<SupportedCompetition> {
        val ids = LinkedHashSet<SupportedCompetition>()
        teams.mapNotNull { SupportedCompetition.fromIdOrName(it.primaryCompetitionId) }.forEach { ids.add(it) }
        prefs.favouriteCompetitionIds.mapNotNull { SupportedCompetition.fromIdOrName(it) }.forEach { ids.add(it) }
        // Include major cups in case domestic clubs have cup ties
        ids.add(SupportedCompetition.CHAMPIONS_LEAGUE)
        ids.add(SupportedCompetition.FA_CUP)
        ids.add(SupportedCompetition.LEAGUE_CUP)
        return ids.toList()
    }

    private fun mergeLiveIntoWindow(
        window: List<FootballFixture>,
        live: List<FootballFixture>
    ): List<FootballFixture> {
        if (live.isEmpty()) return window
        val map = LinkedHashMap<String, FootballFixture>()
        window.forEach { map[it.id] = it }
        live.forEach { map[it.id] = it }
        return map.values.toList()
    }

    // ========================================================================
    // Notifications, Smart Home Automations & Household Timeline Export
    // ========================================================================

    private fun exportTimelineEntries(snapshot: FootballDashboardSnapshot) {
        val entries = (snapshot.liveMatches + snapshot.upcomingFixtures + listOfNotNull(snapshot.heroFixture))
            .distinctBy { it.id }
            .filter { it.involvesFavouriteTeam }
            .sortedBy { it.kickoffEpochMs }
            .take(15)
            .map { fix ->
                FootballCalendarTimelineEntry(
                    id = "football_tl_${fix.id}",
                    title = "${fix.homeTeam.shortName} vs ${fix.awayTeam.shortName}",
                    subtitle = "${fix.competitionName} • ${fix.broadcast.displayChannel}",
                    competitionName = fix.competitionName,
                    broadcastChannel = fix.broadcast.displayChannel,
                    kickoffEpochMs = fix.kickoffEpochMs,
                    isPrimaryFavourite = fix.involvesPrimaryFavourite
                )
            }

        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(FootballDatabaseHelper.TABLE_TIMELINE_EXPORT, null, null)
            for (e in entries) {
                val cv = ContentValues().apply {
                    put("entry_id", e.id)
                    put("title", e.title)
                    put("subtitle", e.subtitle)
                    put("competition_name", e.competitionName)
                    put("broadcast_channel", e.broadcastChannel)
                    put("kickoff_epoch_ms", e.kickoffEpochMs)
                    put("is_primary", if (e.isPrimaryFavourite) 1 else 0)
                }
                db.insert(FootballDatabaseHelper.TABLE_TIMELINE_EXPORT, null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        _timelineEntries.value = entries
    }

    private fun clearTimelineExportInDb() {
        dbHelper.writableDatabase.delete(FootballDatabaseHelper.TABLE_TIMELINE_EXPORT, null, null)
    }

    private fun evaluateNotificationsAndAutomations(snapshot: FootballDashboardSnapshot) {
        val prefs = snapshot.preferences
        val nowMs = System.currentTimeMillis()

        // 1. Check persisted match reminders due now
        val dueReminders = loadDueRemindersFromDb(nowMs)
        for (rem in dueReminders) {
            markReminderFiredInDb(rem.fixtureId)
            if (prefs.notificationsEnabled &&
                FootballNotificationEventType.MATCH_REMINDER in prefs.enabledNotificationEvents
            ) {
                dispatchNotificationOnce(
                    dedupKey = "reminder_${rem.fixtureId}",
                    eventType = FootballNotificationEventType.MATCH_REMINDER,
                    title = "Upcoming Kickoff: ${rem.homeTeamName} vs ${rem.awayTeamName}",
                    body = "${rem.competitionName} • Watch on ${rem.channelSummary}"
                )
            }
        }

        // 2. Check favourite team fixtures for Lineups, Kickoff, Goals, Red Cards, Half Time, Full Time
        val trackedMatches = (snapshot.liveMatches + listOfNotNull(snapshot.heroFixture) + snapshot.recentResults.take(4))
            .distinctBy { it.id }
            .filter { it.involvesFavouriteTeam }

        for (match in trackedMatches) {
            val primaryColor = snapshot.primaryTeam?.primaryColorHex ?: match.homeTeam.primaryColorHex

            // Lineups Released
            if (match.lineupState == LineupAvailabilityState.AVAILABLE && !match.status.isFinished) {
                val lineupDedup = "lineup_${match.id}"
                if (!hasNotificationFired(lineupDedup)) {
                    if (prefs.notificationsEnabled &&
                        FootballNotificationEventType.LINEUPS_RELEASED in prefs.enabledNotificationEvents
                    ) {
                        dispatchNotificationOnce(
                            dedupKey = lineupDedup,
                            eventType = FootballNotificationEventType.LINEUPS_RELEASED,
                            title = "Lineups Confirmed",
                            body = "${match.homeTeam.shortName} vs ${match.awayTeam.shortName} starting XI announced"
                        )
                    }
                    if (prefs.automationsEnabled &&
                        FootballAutomationHook.ANNOUNCE_LINEUP_RELEASE in prefs.enabledAutomationHooks
                    ) {
                        recordDedupEvent(lineupDedup, FootballNotificationEventType.LINEUPS_RELEASED)
                        val announcement = "Starting lineups have been announced for ${match.homeTeam.name} versus ${match.awayTeam.name}."
                        speakTtsAnnouncement(announcement)
                        _automationEvents.tryEmit(
                            FootballAutomationDispatchEvent(
                                hook = FootballAutomationHook.ANNOUNCE_LINEUP_RELEASE,
                                fixture = match,
                                primaryColorHex = primaryColor,
                                spokenAnnouncement = announcement
                            )
                        )
                    }
                }
            }

            // Kickoff + Kickoff Automations
            if (match.status == FootballMatchLifecycle.LIVE_FIRST_HALF) {
                val koDedup = "kickoff_${match.id}"
                if (!hasNotificationFired(koDedup)) {
                    if (prefs.notificationsEnabled &&
                        FootballNotificationEventType.KICKOFF in prefs.enabledNotificationEvents
                    ) {
                        dispatchNotificationOnce(
                            dedupKey = koDedup,
                            eventType = FootballNotificationEventType.KICKOFF,
                            title = "Kickoff: ${match.homeTeam.shortName} vs ${match.awayTeam.shortName}",
                            body = "Live now on ${match.broadcast.displayChannel}"
                        )
                    } else {
                        recordDedupEvent(koDedup, FootballNotificationEventType.KICKOFF)
                    }

                    if (prefs.automationsEnabled) {
                        if (FootballAutomationHook.PAUSE_MUSIC_AT_KICKOFF in prefs.enabledAutomationHooks) {
                            ClaraInAppMusicPlayer.stopRadioPlayback(repositoryScope)
                            _automationEvents.tryEmit(
                                FootballAutomationDispatchEvent(
                                    hook = FootballAutomationHook.PAUSE_MUSIC_AT_KICKOFF,
                                    fixture = match,
                                    primaryColorHex = primaryColor,
                                    spokenAnnouncement = ""
                                )
                            )
                        }
                        for (hook in listOf(
                            FootballAutomationHook.TURN_TV_ON_AT_KICKOFF,
                            FootballAutomationHook.OPEN_BROADCASTER_APP,
                            FootballAutomationHook.CHANGE_LIGHTS_TO_TEAM_COLOURS,
                            FootballAutomationHook.SWITCH_DASHBOARD_LIVE_MODE
                        )) {
                            if (hook in prefs.enabledAutomationHooks) {
                                _automationEvents.tryEmit(
                                    FootballAutomationDispatchEvent(
                                        hook = hook,
                                        fixture = match,
                                        primaryColorHex = primaryColor,
                                        spokenAnnouncement = ""
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Half Time
            if (match.status == FootballMatchLifecycle.HALF_TIME &&
                prefs.notificationsEnabled &&
                FootballNotificationEventType.HALF_TIME in prefs.enabledNotificationEvents
            ) {
                dispatchNotificationOnce(
                    dedupKey = "ht_${match.id}",
                    eventType = FootballNotificationEventType.HALF_TIME,
                    title = "Half Time: ${match.homeTeam.shortName} ${match.homeScore ?: 0}-${match.awayScore ?: 0} ${match.awayTeam.shortName}",
                    body = match.competitionName
                )
            }

            // Goals & Red Cards
            if (match.status.isLive && prefs.notificationsEnabled) {
                for (goal in match.goalscorers) {
                    if (FootballNotificationEventType.GOAL in prefs.enabledNotificationEvents) {
                        dispatchNotificationOnce(
                            dedupKey = "goal_${match.id}_${goal.minute}_${goal.primaryPlayerName}",
                            eventType = FootballNotificationEventType.GOAL,
                            title = "GOAL! ${match.homeTeam.shortName} ${match.homeScore ?: 0}-${match.awayScore ?: 0} ${match.awayTeam.shortName}",
                            body = "${goal.primaryPlayerName} (${goal.formattedMinute})"
                        )
                    }
                }
                for (red in match.redCards) {
                    if (FootballNotificationEventType.RED_CARD in prefs.enabledNotificationEvents) {
                        dispatchNotificationOnce(
                            dedupKey = "red_${match.id}_${red.minute}_${red.primaryPlayerName}",
                            eventType = FootballNotificationEventType.RED_CARD,
                            title = "RED CARD • ${match.homeTeam.shortName} vs ${match.awayTeam.shortName}",
                            body = "${red.primaryPlayerName} sent off (${red.formattedMinute})"
                        )
                    }
                }
            }

            // Full Time / Match Result
            if (match.status == FootballMatchLifecycle.FINISHED &&
                (nowMs - match.kickoffEpochMs) <= 6 * 3_600_000L &&
                prefs.notificationsEnabled &&
                (FootballNotificationEventType.FULL_TIME in prefs.enabledNotificationEvents ||
                        FootballNotificationEventType.MATCH_RESULT in prefs.enabledNotificationEvents)
            ) {
                dispatchNotificationOnce(
                    dedupKey = "ft_${match.id}",
                    eventType = FootballNotificationEventType.FULL_TIME,
                    title = "Full Time: ${match.homeTeam.shortName} ${match.homeScore ?: 0}-${match.awayScore ?: 0} ${match.awayTeam.shortName}",
                    body = match.competitionName
                )
            }
        }

        // 3. Favourite Team Breaking News (published within last 2 hours)
        if (prefs.notificationsEnabled &&
            FootballNotificationEventType.FAVOURITE_TEAM_NEWS in prefs.enabledNotificationEvents
        ) {
            snapshot.newsArticles
                .firstOrNull { it.involvesFavouriteTeam && (nowMs - it.publishedEpochMs) in 0..(2 * 3_600_000L) }
                ?.let { article ->
                    dispatchNotificationOnce(
                        dedupKey = "news_${article.id}",
                        eventType = FootballNotificationEventType.FAVOURITE_TEAM_NEWS,
                        title = "${article.relatedTeamNames.firstOrNull() ?: "Club"} News • ${article.sourceName}",
                        body = article.title
                    )
                }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Clara Football Hub",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Live match alerts, lineup releases, goals, and kickoff reminders"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun dispatchNotificationOnce(
        dedupKey: String,
        eventType: FootballNotificationEventType,
        title: String,
        body: String
    ) {
        if (hasNotificationFired(dedupKey)) return
        recordDedupEvent(dedupKey, eventType)
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

    private fun speakTtsAnnouncement(text: String) {
        if (text.isBlank()) return
        repositoryScope.launch(Dispatchers.Main) {
            if (ttsEngine == null) {
                ttsEngine = TextToSpeech(appContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ttsEngine?.language = Locale.UK
                        ttsReady = true
                        ttsEngine?.speak(text, TextToSpeech.QUEUE_ADD, null, "football_tts_${System.currentTimeMillis()}")
                    }
                }
            } else if (ttsReady) {
                ttsEngine?.speak(text, TextToSpeech.QUEUE_ADD, null, "football_tts_${System.currentTimeMillis()}")
            }
        }
    }

    // ========================================================================
    // Low-Level SQLite Read / Write Operations
    // ========================================================================

    private fun loadFavouriteTeamsFromDb(): List<FavouriteTeamEntry> {
        val list = mutableListOf<FavouriteTeamEntry>()
        val db = dbHelper.readableDatabase
        db.query(
            FootballDatabaseHelper.TABLE_FAVOURITE_TEAMS,
            null,
            null,
            null,
            null,
            null,
            "is_primary_favourite DESC, sort_order ASC, added_at_ms ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val afIdx = cursor.getColumnIndexOrThrow("api_football_id")
                val espnIdx = cursor.getColumnIndexOrThrow("espn_team_id")
                list.add(
                    FavouriteTeamEntry(
                        teamId = cursor.getString(cursor.getColumnIndexOrThrow("team_id")),
                        apiFootballTeamId = if (cursor.isNull(afIdx)) null else cursor.getInt(afIdx),
                        espnTeamId = if (cursor.isNull(espnIdx)) null else cursor.getString(espnIdx),
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        shortName = cursor.getString(cursor.getColumnIndexOrThrow("short_name")),
                        badgeUrl = cursor.getString(cursor.getColumnIndexOrThrow("badge_url")),
                        primaryCompetitionId = cursor.getString(cursor.getColumnIndexOrThrow("primary_competition_id")),
                        primaryColorHex = cursor.getString(cursor.getColumnIndexOrThrow("primary_color_hex")),
                        secondaryColorHex = cursor.getString(cursor.getColumnIndexOrThrow("secondary_color_hex")),
                        isPrimaryFavourite = cursor.getInt(cursor.getColumnIndexOrThrow("is_primary_favourite")) == 1,
                        sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow("sort_order")),
                        addedAtEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("added_at_ms"))
                    )
                )
            }
        }
        return list
    }

    private fun loadPreferencesFromDb(): FootballHubPreferences {
        val db = dbHelper.readableDatabase
        db.query(
            FootballDatabaseHelper.TABLE_PREFERENCES,
            arrayOf("pref_value"),
            "pref_key = ?",
            arrayOf(KEY_PREFERENCES_JSON),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val raw = cursor.getString(0)
                return runCatching { gson.fromJson(raw, FootballHubPreferences::class.java) }
                    .getOrNull() ?: FootballHubPreferences()
            }
        }
        return FootballHubPreferences()
    }

    private fun saveFixturesToCacheDb(fixtures: List<FootballFixture>) {
        val now = System.currentTimeMillis()
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // Prune stale fixtures older than 14 days
            db.delete(
                FootballDatabaseHelper.TABLE_FIXTURE_CACHE,
                "kickoff_epoch_ms < ?",
                arrayOf((now - 14L * 24L * 3_600_000L).toString())
            )
            for (fix in fixtures) {
                val cv = ContentValues().apply {
                    put("fixture_id", fix.id)
                    put("competition_id", fix.competitionId)
                    put("kickoff_epoch_ms", fix.kickoffEpochMs)
                    put("payload_json", gson.toJson(fix))
                    put("updated_at_ms", now)
                }
                db.insertWithOnConflict(
                    FootballDatabaseHelper.TABLE_FIXTURE_CACHE,
                    null,
                    cv,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun loadCachedFixturesFromDb(): List<FootballFixture> {
        val list = mutableListOf<FootballFixture>()
        val db = dbHelper.readableDatabase
        db.query(
            FootballDatabaseHelper.TABLE_FIXTURE_CACHE,
            arrayOf("payload_json"),
            null,
            null,
            null,
            null,
            "kickoff_epoch_ms ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val raw = cursor.getString(0)
                runCatching { gson.fromJson(raw, FootballFixture::class.java) }
                    .getOrNull()
                    ?.let { list.add(it) }
            }
        }
        return list
    }

    private fun saveStandingsToCacheDb(competitionId: String, rows: List<LeagueStandingRow>) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("competition_id", competitionId)
            put("payload_json", gson.toJson(rows))
            put("updated_at_ms", System.currentTimeMillis())
        }
        db.insertWithOnConflict(
            FootballDatabaseHelper.TABLE_STANDINGS_CACHE,
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun loadCachedStandingsForCompetition(competitionId: String): List<LeagueStandingRow> {
        val db = dbHelper.readableDatabase
        val listType = object : TypeToken<List<LeagueStandingRow>>() {}.type
        db.query(
            FootballDatabaseHelper.TABLE_STANDINGS_CACHE,
            arrayOf("payload_json"),
            "competition_id = ?",
            arrayOf(competitionId),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val raw = cursor.getString(0)
                return runCatching { gson.fromJson<List<LeagueStandingRow>>(raw, listType) }
                    .getOrDefault(emptyList())
            }
        }
        return emptyList()
    }

    private fun loadCachedStandingsFromDb(): Map<String, List<LeagueStandingRow>> {
        val map = LinkedHashMap<String, List<LeagueStandingRow>>()
        val listType = object : TypeToken<List<LeagueStandingRow>>() {}.type
        val db = dbHelper.readableDatabase
        db.query(
            FootballDatabaseHelper.TABLE_STANDINGS_CACHE,
            arrayOf("competition_id", "payload_json"),
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val compId = cursor.getString(0)
                val raw = cursor.getString(1)
                val rows = runCatching { gson.fromJson<List<LeagueStandingRow>>(raw, listType) }
                    .getOrDefault(emptyList())
                if (rows.isNotEmpty()) map[compId] = rows
            }
        }
        return map
    }

    private fun loadActiveReminderIdsFromDb(): Set<String> {
        val set = LinkedHashSet<String>()
        val db = dbHelper.readableDatabase
        db.query(
            FootballDatabaseHelper.TABLE_REMINDERS,
            arrayOf("fixture_id"),
            "fired = 0",
            null,
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                set.add(cursor.getString(0))
            }
        }
        return set
    }

    private fun loadDueRemindersFromDb(nowMs: Long): List<FootballMatchReminder> {
        val list = mutableListOf<FootballMatchReminder>()
        val db = dbHelper.readableDatabase
        db.query(
            FootballDatabaseHelper.TABLE_REMINDERS,
            null,
            "fired = 0 AND remind_at_epoch_ms <= ?",
            arrayOf(nowMs.toString()),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    FootballMatchReminder(
                        fixtureId = cursor.getString(cursor.getColumnIndexOrThrow("fixture_id")),
                        homeTeamName = cursor.getString(cursor.getColumnIndexOrThrow("home_team_name")),
                        awayTeamName = cursor.getString(cursor.getColumnIndexOrThrow("away_team_name")),
                        competitionName = cursor.getString(cursor.getColumnIndexOrThrow("competition_name")),
                        kickoffEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("kickoff_epoch_ms")),
                        channelSummary = cursor.getString(cursor.getColumnIndexOrThrow("channel_summary")),
                        remindAtEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("remind_at_epoch_ms")),
                        fired = false
                    )
                )
            }
        }
        return list
    }

    private fun markReminderFiredInDb(fixtureId: String) {
        val cv = ContentValues().apply { put("fired", 1) }
        dbHelper.writableDatabase.update(
            FootballDatabaseHelper.TABLE_REMINDERS,
            cv,
            "fixture_id = ?",
            arrayOf(fixtureId)
        )
    }

    private fun hasNotificationFired(dedupKey: String): Boolean {
        dbHelper.readableDatabase.query(
            FootballDatabaseHelper.TABLE_NOTIFICATION_LOG,
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

    private fun recordDedupEvent(dedupKey: String, eventType: FootballNotificationEventType) {
        val cv = ContentValues().apply {
            put("dedup_key", dedupKey)
            put("event_type", eventType.name)
            put("fired_at_ms", System.currentTimeMillis())
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            FootballDatabaseHelper.TABLE_NOTIFICATION_LOG,
            null,
            cv,
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    companion object {
        private const val KEY_PREFERENCES_JSON = "football_hub_preferences_v3"
        private const val VAULT_KEY_API_FOOTBALL = "football_hub::api_football_v3_key"
        private const val NOTIFICATION_CHANNEL_ID = "clara_football_hub_v3"

        @Volatile
        private var instance: FootballRepository? = null

        fun getInstance(context: Context = ClaraApplication.appContext): FootballRepository {
            return instance ?: synchronized(this) {
                instance ?: FootballRepository(context).also { instance = it }
            }
        }
    }
}