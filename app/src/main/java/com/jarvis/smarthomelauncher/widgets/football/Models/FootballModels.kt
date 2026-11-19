package com.jarvis.smarthomelauncher.widgets.football.Models

import com.google.gson.annotations.SerializedName

/**
 * CWAS Rule 12: Discoverable manifest metadata for Football Hub v3.0.
 */
data class FootballWidgetManifestDescriptor(
    val id: String = "football-hub",
    val name: String = "Football Hub",
    val feature: String = "Football Hub Widget",
    val application: String = "Clara Smart Home",
    val version: String = "3.0",
    val description: String = "Provide a premium football dashboard experience that allows users to follow favourite clubs, competitions and matches from across a room while providing a deep football hub experience when opened.",
    val headerTitle: String = "Football",
    val headerIcon: String = "sports_soccer",
    val headerClickable: Boolean = true,
    val headerAction: String = "OpenFootballHub",
    val launchMode: String = "popup",
    val popupWidthFraction: Float = 0.95f,
    val popupHeightFraction: Float = 0.92f,
    val supportsPopup: Boolean = true,
    val supportsSettings: Boolean = true,
    val supportsNotifications: Boolean = true,
    val supportsAutomations: Boolean = true,
    val supportsVoiceAssistant: Boolean = true
)

/**
 * Supported competitions defined in Football Hub v3.0 specification.
 */
enum class SupportedCompetition(
    val id: String,
    val displayName: String,
    val shortName: String,
    val countryOrRegion: String,
    val apiFootballLeagueId: Int,
    val espnLeagueSlug: String,
    val isCupCompetition: Boolean,
    val championsLeagueSpots: Int = 0,
    val europeanSpots: Int = 0,
    val promotionSpots: Int = 0,
    val playoffSpots: Int = 0,
    val relegationSpots: Int = 0
) {
    PREMIER_LEAGUE(
        id = "premier-league",
        displayName = "Premier League",
        shortName = "EPL",
        countryOrRegion = "England",
        apiFootballLeagueId = 39,
        espnLeagueSlug = "eng.1",
        isCupCompetition = false,
        championsLeagueSpots = 4,
        europeanSpots = 6,
        relegationSpots = 3
    ),
    CHAMPIONSHIP(
        id = "championship",
        displayName = "Championship",
        shortName = "CHAMP",
        countryOrRegion = "England",
        apiFootballLeagueId = 40,
        espnLeagueSlug = "eng.2",
        isCupCompetition = false,
        promotionSpots = 2,
        playoffSpots = 6,
        relegationSpots = 3
    ),
    LEAGUE_ONE(
        id = "league-one",
        displayName = "League One",
        shortName = "L1",
        countryOrRegion = "England",
        apiFootballLeagueId = 41,
        espnLeagueSlug = "eng.3",
        isCupCompetition = false,
        promotionSpots = 2,
        playoffSpots = 6,
        relegationSpots = 4
    ),
    LEAGUE_TWO(
        id = "league-two",
        displayName = "League Two",
        shortName = "L2",
        countryOrRegion = "England",
        apiFootballLeagueId = 42,
        espnLeagueSlug = "eng.4",
        isCupCompetition = false,
        promotionSpots = 3,
        playoffSpots = 7,
        relegationSpots = 2
    ),
    FA_CUP(
        id = "fa-cup",
        displayName = "FA Cup",
        shortName = "FAC",
        countryOrRegion = "England",
        apiFootballLeagueId = 45,
        espnLeagueSlug = "eng.fa",
        isCupCompetition = true
    ),
    LEAGUE_CUP(
        id = "league-cup",
        displayName = "League Cup",
        shortName = "EFL",
        countryOrRegion = "England",
        apiFootballLeagueId = 48,
        espnLeagueSlug = "eng.league_cup",
        isCupCompetition = true
    ),
    CHAMPIONS_LEAGUE(
        id = "champions-league",
        displayName = "Champions League",
        shortName = "UCL",
        countryOrRegion = "Europe",
        apiFootballLeagueId = 2,
        espnLeagueSlug = "uefa.champions",
        isCupCompetition = true,
        championsLeagueSpots = 8,
        playoffSpots = 24,
        relegationSpots = 12
    ),
    EUROPA_LEAGUE(
        id = "europa-league",
        displayName = "Europa League",
        shortName = "UEL",
        countryOrRegion = "Europe",
        apiFootballLeagueId = 3,
        espnLeagueSlug = "uefa.europa",
        isCupCompetition = true,
        europeanSpots = 8,
        playoffSpots = 24,
        relegationSpots = 12
    ),
    CONFERENCE_LEAGUE(
        id = "conference-league",
        displayName = "Conference League",
        shortName = "UECL",
        countryOrRegion = "Europe",
        apiFootballLeagueId = 848,
        espnLeagueSlug = "uefa.europa.conf",
        isCupCompetition = true,
        europeanSpots = 8,
        playoffSpots = 24,
        relegationSpots = 12
    ),
    SCOTTISH_PREMIERSHIP(
        id = "scottish-premiership",
        displayName = "Scottish Premiership",
        shortName = "SPFL",
        countryOrRegion = "Scotland",
        apiFootballLeagueId = 179,
        espnLeagueSlug = "sco.1",
        isCupCompetition = false,
        championsLeagueSpots = 2,
        europeanSpots = 4,
        relegationSpots = 2
    ),
    LA_LIGA(
        id = "la-liga",
        displayName = "La Liga",
        shortName = "LALIGA",
        countryOrRegion = "Spain",
        apiFootballLeagueId = 140,
        espnLeagueSlug = "esp.1",
        isCupCompetition = false,
        championsLeagueSpots = 4,
        europeanSpots = 6,
        relegationSpots = 3
    ),
    BUNDESLIGA(
        id = "bundesliga",
        displayName = "Bundesliga",
        shortName = "BUN",
        countryOrRegion = "Germany",
        apiFootballLeagueId = 78,
        espnLeagueSlug = "ger.1",
        isCupCompetition = false,
        championsLeagueSpots = 4,
        europeanSpots = 6,
        relegationSpots = 3
    ),
    SERIE_A(
        id = "serie-a",
        displayName = "Serie A",
        shortName = "SERIEA",
        countryOrRegion = "Italy",
        apiFootballLeagueId = 135,
        espnLeagueSlug = "ita.1",
        isCupCompetition = false,
        championsLeagueSpots = 4,
        europeanSpots = 6,
        relegationSpots = 3
    ),
    LIGUE_1(
        id = "ligue-1",
        displayName = "Ligue 1",
        shortName = "L1FR",
        countryOrRegion = "France",
        apiFootballLeagueId = 61,
        espnLeagueSlug = "fra.1",
        isCupCompetition = false,
        championsLeagueSpots = 3,
        europeanSpots = 6,
        relegationSpots = 3
    ),
    MLS(
        id = "mls",
        displayName = "MLS",
        shortName = "MLS",
        countryOrRegion = "USA / Canada",
        apiFootballLeagueId = 253,
        espnLeagueSlug = "usa.1",
        isCupCompetition = false,
        playoffSpots = 9
    ),
    EUROPEAN_CHAMPIONSHIP(
        id = "european-championship",
        displayName = "European Championship",
        shortName = "EURO",
        countryOrRegion = "Europe",
        apiFootballLeagueId = 4,
        espnLeagueSlug = "uefa.euro",
        isCupCompetition = true
    ),
    WORLD_CUP(
        id = "world-cup",
        displayName = "World Cup",
        shortName = "WC",
        countryOrRegion = "International",
        apiFootballLeagueId = 1,
        espnLeagueSlug = "fifa.world",
        isCupCompetition = true
    );

    companion object {
        fun fromIdOrName(raw: String): SupportedCompetition? {
            val normalized = raw.trim().lowercase()
            return entries.firstOrNull {
                it.id == normalized ||
                        it.displayName.lowercase() == normalized ||
                        it.shortName.lowercase() == normalized ||
                        it.espnLeagueSlug.lowercase() == normalized
            }
        }

        fun fromApiFootballId(leagueId: Int): SupportedCompetition? {
            return entries.firstOrNull { it.apiFootballLeagueId == leagueId }
        }
    }
}

/**
 * Dashboard display modes per Football Hub v3.0 JSON specification.
 */
enum class FootballWidgetDisplayMode {
    NORMAL,
    MATCH_DAY,
    LIVE
}

/**
 * Lineup availability states for dashboard and fixture detail popups.
 */
enum class LineupAvailabilityState(
    val widgetLabel: String,
    val detailStatusMessage: String
) {
    NOT_AVAILABLE(
        widgetLabel = "Not Available",
        detailStatusMessage = "Lineups not yet announced"
    ),
    EXPECTED_SOON(
        widgetLabel = "Expected Soon",
        detailStatusMessage = "Expected approximately one hour before kickoff"
    ),
    AVAILABLE(
        widgetLabel = "Available",
        detailStatusMessage = "Lineups available"
    )
}

/**
 * 5-match form outcomes mapped to JSON specification colours (green, orange, red).
 */
enum class FormResult(val code: String) {
    WIN("W"),
    DRAW("D"),
    LOSS("L");

    companion object {
        fun fromChar(ch: Char): FormResult? = when (ch.uppercaseChar()) {
            'W' -> WIN
            'D' -> DRAW
            'L' -> LOSS
            else -> null
        }
    }
}

/**
 * Match lifecycle status normalized across live providers.
 */
enum class FootballMatchLifecycle {
    SCHEDULED,
    LIVE_FIRST_HALF,
    HALF_TIME,
    LIVE_SECOND_HALF,
    EXTRA_TIME,
    PENALTIES,
    FINISHED,
    POSTPONED,
    CANCELLED,
    SUSPENDED;

    val isLive: Boolean
        get() = this == LIVE_FIRST_HALF ||
                this == HALF_TIME ||
                this == LIVE_SECOND_HALF ||
                this == EXTRA_TIME ||
                this == PENALTIES

    val isFinished: Boolean
        get() = this == FINISHED
}

/**
 * 8-tab navigation structure for Football Hub Popup (95% x 92%).
 */
enum class FootballHubTab(val title: String) {
    OVERVIEW("Overview"),
    FIXTURES("Fixtures"),
    LIVE("Live"),
    TABLES("Tables"),
    TEAMS("Teams"),
    COMPETITIONS("Competitions"),
    PLAYERS("Players"),
    NEWS("News")
}

/**
 * Sub-sections inside the Competitions tab.
 */
enum class CompetitionSubSection(val title: String) {
    TABLE("Table"),
    FIXTURES("Fixtures"),
    RESULTS("Results"),
    TOP_SCORERS("Top Scorers"),
    FORM_TABLE("Form Table")
}

/**
 * Sub-sections inside the Players tab.
 */
enum class PlayersSubSection(val title: String) {
    SQUAD("Squad"),
    TOP_SCORERS("Top Scorers"),
    TOP_ASSISTS("Top Assists"),
    INJURIES("Injuries"),
    SUSPENSIONS("Suspensions")
}

/**
 * News categories defined in Football Hub v3.0 JSON specification.
 */
enum class FootballNewsCategory(val title: String) {
    ALL("All News"),
    CLUB_NEWS("Club News"),
    TRANSFER_NEWS("Transfer News"),
    INJURY_NEWS("Injury News"),
    MANAGER_NEWS("Manager News"),
    COMPETITION_NEWS("Competition News")
}

/**
 * Chronological groupings for the Fixtures tab.
 */
enum class FixtureTimeGroup(val title: String) {
    TODAY("Today"),
    TOMORROW("Tomorrow"),
    THIS_WEEK("This Week"),
    NEXT_WEEK("Next Week"),
    THIS_MONTH("This Month")
}

/**
 * Table qualification / promotion / relegation zone classifications.
 */
enum class StandingZone(val label: String) {
    CHAMPIONS_LEAGUE("Champions League"),
    EUROPEAN("European Qualification"),
    PROMOTION("Automatic Promotion"),
    PLAYOFF("Play-Off Places"),
    NONE("Mid-Table"),
    RELEGATION("Relegation Zone")
}

/**
 * RulesEngine insight categories per Football Hub v3.0 JSON specification.
 */
enum class FootballInsightRuleType(val ruleName: String) {
    UPCOMING_FIXTURE("Upcoming Fixture"),
    LEAGUE_POSITION("League Position"),
    COMPETITION_QUALIFICATION("Competition Qualification"),
    RELEGATION_RISK("Relegation Risk"),
    FORM_TREND("Form Trend"),
    LINEUP_AVAILABILITY("Lineup Availability"),
    LIVE_MATCH_STATUS("Live Match Status")
}

/**
 * Notification event types supported by Football Hub v3.0.
 */
enum class FootballNotificationEventType(val displayName: String) {
    MATCH_REMINDER("Match Reminder"),
    LINEUPS_RELEASED("Lineups Released"),
    KICKOFF("Kickoff"),
    GOAL("Goal"),
    HALF_TIME("Half Time"),
    FULL_TIME("Full Time"),
    RED_CARD("Red Card"),
    MATCH_RESULT("Match Result"),
    FAVOURITE_TEAM_NEWS("Favourite Team News")
}

/**
 * Smart Home automation hooks exposed to Clara's Automation Engine & JarvisLiveBridge.
 */
enum class FootballAutomationHook(val displayName: String, val eventCode: String) {
    TURN_TV_ON_AT_KICKOFF("Turn TV on at kickoff", "FOOTBALL_KICKOFF_TV_ON"),
    OPEN_BROADCASTER_APP("Open broadcaster app", "FOOTBALL_OPEN_BROADCASTER_APP"),
    CHANGE_LIGHTS_TO_TEAM_COLOURS("Change lights to team colours", "FOOTBALL_TEAM_COLOURS_LIGHTS"),
    ANNOUNCE_LINEUP_RELEASE("Announce lineup release", "FOOTBALL_LINEUP_RELEASED_TTS"),
    PAUSE_MUSIC_AT_KICKOFF("Pause music when match starts", "FOOTBALL_PAUSE_MUSIC_KICKOFF"),
    SWITCH_DASHBOARD_LIVE_MODE("Switch dashboard to Live Match Mode", "FOOTBALL_DASHBOARD_LIVE_MODE")
}

/**
 * Quick actions inside Fixture Details Popup.
 */
enum class FixtureQuickAction(val label: String) {
    ADD_REMINDER("Add Reminder"),
    VIEW_COMPETITION("View Competition"),
    VIEW_TEAM("View Team"),
    WATCH_CHANNEL("Watch Channel")
}

/**
 * User-configured club entry stored in SQLite (maximum 10 teams, primary favourite prioritized).
 */
data class FavouriteTeamEntry(
    val teamId: String,
    val apiFootballTeamId: Int? = null,
    val espnTeamId: String? = null,
    val name: String,
    val shortName: String,
    val badgeUrl: String,
    val primaryCompetitionId: String,
    val primaryColorHex: String = "#1E88E5",
    val secondaryColorHex: String = "#FFFFFF",
    val isPrimaryFavourite: Boolean = false,
    val sortOrder: Int = 0,
    val addedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * User-configurable Football Hub settings persisted in SQLite.
 */
data class FootballHubPreferences(
    val allowMultipleTeams: Boolean = true,
    val maximumTeams: Int = 10,
    val prioritizeFavouriteTeam: Boolean = true,
    val matchDayLeadHours: Int = 8,
    val favouriteCompetitionIds: List<String> = listOf(
        SupportedCompetition.PREMIER_LEAGUE.id,
        SupportedCompetition.CHAMPIONS_LEAGUE.id,
        SupportedCompetition.FA_CUP.id
    ),
    val notificationsEnabled: Boolean = true,
    val enabledNotificationEvents: Set<FootballNotificationEventType> = FootballNotificationEventType.entries.toSet(),
    val automationsEnabled: Boolean = true,
    val enabledAutomationHooks: Set<FootballAutomationHook> = setOf(
        FootballAutomationHook.ANNOUNCE_LINEUP_RELEASE,
        FootballAutomationHook.SWITCH_DASHBOARD_LIVE_MODE
    ),
    val preferredTvDeviceTileId: String = "",
    val reminderLeadMinutes: Int = 30,
    val broadcastRegion: String = "UK"
)

/**
 * Persisted match reminder stored in SQLite.
 */
data class FootballMatchReminder(
    val fixtureId: String,
    val homeTeamName: String,
    val awayTeamName: String,
    val competitionName: String,
    val kickoffEpochMs: Long,
    val channelSummary: String,
    val remindAtEpochMs: Long,
    val fired: Boolean = false
)

/**
 * Team identity & badge summary inside fixtures and cards.
 */
data class FootballClubSummary(
    val id: String,
    val apiFootballId: Int? = null,
    val espnId: String? = null,
    val name: String,
    val shortName: String,
    val abbreviation: String,
    val badgeUrl: String,
    val primaryColorHex: String = "#1E88E5"
)

/**
 * Broadcast & streaming availability for a match.
 */
data class BroadcastInfo(
    val mainChannel: String,
    val alternateChannels: List<String> = emptyList(),
    val streamingPlatforms: List<String> = emptyList()
) {
    val displayChannel: String
        get() = mainChannel.ifBlank {
            streamingPlatforms.firstOrNull().orEmpty().ifBlank { "Broadcast TBC" }
        }
}

/**
 * Live stadium weather snapshot fetched via Open-Meteo for Match-Day Mode & Fixture Details.
 */
data class StadiumWeatherSnapshot(
    val temperatureCelsius: Double,
    val conditionSummary: String,
    val precipitationProbabilityPercent: Int,
    val windSpeedKmh: Double
) {
    val formattedSummary: String
        get() = "${temperatureCelsius.toInt()}°C • $conditionSummary"
}

/**
 * Match event (goal, yellow card, red card, substitution, VAR) for Live Mode & Live Match Centre.
 */
enum class MatchEventKind {
    GOAL,
    OWN_GOAL,
    PENALTY_GOAL,
    MISSED_PENALTY,
    YELLOW_CARD,
    SECOND_YELLOW_RED_CARD,
    RED_CARD,
    SUBSTITUTION,
    VAR
}

data class FootballMatchEvent(
    val id: String,
    val minute: Int,
    val extraMinute: Int? = null,
    val teamId: String,
    val isHomeTeam: Boolean,
    val kind: MatchEventKind,
    val primaryPlayerName: String,
    val secondaryPlayerName: String? = null,
    val detailLabel: String = ""
) {
    val formattedMinute: String
        get() = if (extraMinute != null && extraMinute > 0) "${minute}+${extraMinute}'" else "${minute}'"
}

/**
 * Player entry inside a match lineup.
 */
data class LineupPlayerEntry(
    val playerId: String,
    val name: String,
    val shirtNumber: Int? = null,
    val position: String = "",
    val gridPosition: String? = null,
    val isCaptain: Boolean = false
)

/**
 * Team lineup (formation, starting XI, substitutes, manager).
 */
data class TeamLineup(
    val teamId: String,
    val teamName: String,
    val formation: String,
    val coachName: String = "",
    val startingEleven: List<LineupPlayerEntry> = emptyList(),
    val substitutes: List<LineupPlayerEntry> = emptyList()
)

/**
 * Normalized Football Fixture model powering Dashboard Hero, Upcoming list, Fixtures tab, and Live Match Centre.
 */
data class FootballFixture(
    val id: String,
    val apiFootballFixtureId: Int? = null,
    val espnEventId: String? = null,
    val competitionId: String,
    val competitionName: String,
    val competitionRound: String = "",
    val kickoffEpochMs: Long,
    val status: FootballMatchLifecycle,
    val statusShortLabel: String,
    val matchMinute: Int? = null,
    val stoppageMinute: Int? = null,
    val homeTeam: FootballClubSummary,
    val awayTeam: FootballClubSummary,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val stadiumName: String = "",
    val stadiumCity: String = "",
    val stadiumLatitude: Double? = null,
    val stadiumLongitude: Double? = null,
    val refereeName: String = "",
    val attendance: Int? = null,
    val broadcast: BroadcastInfo = BroadcastInfo(mainChannel = "Broadcast TBC"),
    val lineupState: LineupAvailabilityState = LineupAvailabilityState.NOT_AVAILABLE,
    val homeLineup: TeamLineup? = null,
    val awayLineup: TeamLineup? = null,
    val events: List<FootballMatchEvent> = emptyList(),
    val stadiumWeather: StadiumWeatherSnapshot? = null,
    val involvesFavouriteTeam: Boolean = false,
    val involvesPrimaryFavourite: Boolean = false
) {
    val formattedLiveMinute: String
        get() = when (status) {
            FootballMatchLifecycle.HALF_TIME -> "HT"
            FootballMatchLifecycle.FINISHED -> "FT"
            FootballMatchLifecycle.PENALTIES -> "PEN"
            else -> {
                val min = matchMinute
                if (min == null) statusShortLabel
                else if (stoppageMinute != null && stoppageMinute > 0) "${min}+${stoppageMinute}'"
                else "${min}'"
            }
        }

    val goalscorers: List<FootballMatchEvent>
        get() = events.filter {
            it.kind == MatchEventKind.GOAL ||
                    it.kind == MatchEventKind.OWN_GOAL ||
                    it.kind == MatchEventKind.PENALTY_GOAL
        }

    val yellowCards: List<FootballMatchEvent>
        get() = events.filter { it.kind == MatchEventKind.YELLOW_CARD }

    val redCards: List<FootballMatchEvent>
        get() = events.filter {
            it.kind == MatchEventKind.RED_CARD || it.kind == MatchEventKind.SECOND_YELLOW_RED_CARD
        }

    val substitutions: List<FootballMatchEvent>
        get() = events.filter { it.kind == MatchEventKind.SUBSTITUTION }
}

/**
 * Full league table row with all 10 required columns + form + qualification/relegation zone.
 */
data class LeagueStandingRow(
    val competitionId: String,
    val competitionName: String,
    val position: Int,
    val team: FootballClubSummary,
    val played: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val goalDifference: Int,
    val points: Int,
    val recentForm: List<FormResult> = emptyList(),
    val zone: StandingZone = StandingZone.NONE,
    val zoneDescription: String = "",
    val isFavouriteTeam: Boolean = false,
    val isPrimaryFavourite: Boolean = false
)

/**
 * Compact League Snapshot for Dashboard Normal Mode & Overview tab.
 */
data class LeaguePositionSnapshot(
    val teamId: String,
    val teamName: String,
    val competitionId: String,
    val competitionName: String,
    val position: Int,
    val points: Int,
    val gamesPlayed: Int,
    val goalDifference: Int,
    val totalTeamsInTable: Int,
    val zone: StandingZone,
    val recentForm: List<FormResult>
)

/**
 * Adaptive grid Team Card model for Teams tab.
 */
data class TeamHubCardModel(
    val favouriteEntry: FavouriteTeamEntry,
    val standing: LeagueStandingRow?,
    val recentForm: List<FormResult>,
    val nextFixture: FootballFixture?,
    val liveFixture: FootballFixture?
)

/**
 * Player squad & leaderboard entry for Players & Competitions tabs.
 */
data class FootballPlayerStatEntry(
    val playerId: String,
    val name: String,
    val photoUrl: String = "",
    val teamId: String,
    val teamName: String,
    val teamBadgeUrl: String = "",
    val position: String = "",
    val shirtNumber: Int? = null,
    val age: Int? = null,
    val nationality: String = "",
    val appearances: Int = 0,
    val goals: Int = 0,
    val assists: Int = 0,
    val yellowCards: Int = 0,
    val redCards: Int = 0
)

/**
 * Player injury or suspension entry for Fixture Details & Players tab.
 */
data class PlayerAvailabilityIssue(
    val id: String,
    val playerId: String,
    val playerName: String,
    val playerPhotoUrl: String = "",
    val teamId: String,
    val teamName: String,
    val isSuspension: Boolean,
    val reason: String,
    val statusLabel: String,
    val expectedReturn: String = ""
)

/**
 * Live football news article classified by category and linked clubs/competitions.
 */
data class FootballNewsArticle(
    val id: String,
    val title: String,
    val summary: String,
    val sourceName: String,
    val articleUrl: String,
    val imageUrl: String = "",
    val publishedEpochMs: Long,
    val category: FootballNewsCategory,
    val relatedTeamNames: List<String> = emptyList(),
    val involvesFavouriteTeam: Boolean = false
)

/**
 * RulesEngine generated insight surfaced on Dashboard & Hub Overview.
 */
data class FootballInsight(
    val id: String,
    val ruleType: FootballInsightRuleType,
    val headline: String,
    val supportingDetail: String = "",
    val priority: Int,
    val teamId: String? = null,
    val fixtureId: String? = null,
    val generatedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Complete drill-down model for Fixture Details Popup.
 */
data class FootballFixtureDetail(
    val fixture: FootballFixture,
    val broadcast: BroadcastInfo,
    val lineupState: LineupAvailabilityState,
    val homeLineup: TeamLineup?,
    val awayLineup: TeamLineup?,
    val contextLeagueTable: List<LeagueStandingRow>,
    val homeRecentForm: List<FormResult>,
    val awayRecentForm: List<FormResult>,
    val headToHeadMeetings: List<FootballFixture>,
    val injuries: List<PlayerAvailabilityIssue>,
    val suspensions: List<PlayerAvailabilityIssue>,
    val hasReminderSet: Boolean
)

/**
 * Complete ready-state payload powering both the 2-4m Dashboard Widget and 8-tab Football Hub Popup.
 */
data class FootballDashboardSnapshot(
    val displayMode: FootballWidgetDisplayMode,
    val favouriteTeams: List<FavouriteTeamEntry>,
    val primaryTeam: FavouriteTeamEntry?,
    val heroFixture: FootballFixture?,
    val liveMatches: List<FootballFixture>,
    val upcomingFixtures: List<FootballFixture>,
    val allGroupedFixtures: Map<FixtureTimeGroup, List<FootballFixture>>,
    val recentResults: List<FootballFixture>,
    val primaryLeagueSnapshot: LeaguePositionSnapshot?,
    val standingsByCompetition: Map<String, List<LeagueStandingRow>>,
    val teamCards: List<TeamHubCardModel>,
    val insights: List<FootballInsight>,
    val topScorersByCompetition: Map<String, List<FootballPlayerStatEntry>>,
    val topAssistsByCompetition: Map<String, List<FootballPlayerStatEntry>>,
    val squadByTeamId: Map<String, List<FootballPlayerStatEntry>>,
    val availabilityIssues: List<PlayerAvailabilityIssue>,
    val newsArticles: List<FootballNewsArticle>,
    val activeReminders: Set<String>,
    val preferences: FootballHubPreferences,
    val activeProviderLabel: String,
    val lastSyncedEpochMs: Long
)

/**
 * CWAS Rule 11: Mandatory core states (Loading, Ready, Empty, Error).
 */
sealed interface FootballWidgetUiState {
    data object Loading : FootballWidgetUiState
    data class Empty(
        val title: String = "No Teams Selected",
        val message: String = "Tap Football to choose up to 10 favourite clubs and competitions."
    ) : FootballWidgetUiState

    data class Error(
        val message: String = "Unable to retrieve data",
        val technicalDetail: String = ""
    ) : FootballWidgetUiState

    data class Ready(val snapshot: FootballDashboardSnapshot) : FootballWidgetUiState
}

/**
 * Voice command response returned to ClaraVoiceAssistantManager.
 */
data class FootballVoiceCommandResponse(
    val handled: Boolean,
    val spokenText: String,
    val targetTab: FootballHubTab? = null,
    val targetCompetitionId: String? = null
)

// ============================================================================
// Live Production API Response DTOs (API-Football v3 & ESPN Soccer Site API v2)
// ============================================================================

data class ApiFootballEnvelope<T>(
    @SerializedName("response") val response: List<T> = emptyList(),
    @SerializedName("results") val results: Int = 0
)

data class ApiFootballFixtureDto(
    @SerializedName("fixture") val fixture: ApiFootballFixtureMetaDto? = null,
    @SerializedName("league") val league: ApiFootballLeagueDto? = null,
    @SerializedName("teams") val teams: ApiFootballTeamsPairDto? = null,
    @SerializedName("goals") val goals: ApiFootballGoalsDto? = null,
    @SerializedName("events") val events: List<ApiFootballEventDto>? = null,
    @SerializedName("lineups") val lineups: List<ApiFootballLineupDto>? = null
)

data class ApiFootballFixtureMetaDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("referee") val referee: String? = null,
    @SerializedName("timestamp") val timestampSeconds: Long = 0L,
    @SerializedName("venue") val venue: ApiFootballVenueDto? = null,
    @SerializedName("status") val status: ApiFootballStatusDto? = null
)

data class ApiFootballVenueDto(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("city") val city: String? = null
)

data class ApiFootballStatusDto(
    @SerializedName("long") val longLabel: String? = null,
    @SerializedName("short") val shortCode: String? = null,
    @SerializedName("elapsed") val elapsed: Int? = null,
    @SerializedName("extra") val extra: Int? = null
)

data class ApiFootballLeagueDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String? = null,
    @SerializedName("country") val country: String? = null,
    @SerializedName("logo") val logo: String? = null,
    @SerializedName("season") val season: Int? = null,
    @SerializedName("round") val round: String? = null,
    @SerializedName("standings") val standings: List<List<ApiFootballStandingDto>>? = null
)

data class ApiFootballTeamsPairDto(
    @SerializedName("home") val home: ApiFootballTeamInfoDto? = null,
    @SerializedName("away") val away: ApiFootballTeamInfoDto? = null
)

data class ApiFootballTeamInfoDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String? = null,
    @SerializedName("code") val code: String? = null,
    @SerializedName("logo") val logo: String? = null,
    @SerializedName("winner") val winner: Boolean? = null
)

data class ApiFootballGoalsDto(
    @SerializedName("home") val home: Int? = null,
    @SerializedName("away") val away: Int? = null
)

data class ApiFootballEventDto(
    @SerializedName("time") val time: ApiFootballEventTimeDto? = null,
    @SerializedName("team") val team: ApiFootballTeamInfoDto? = null,
    @SerializedName("player") val player: ApiFootballPersonRefDto? = null,
    @SerializedName("assist") val assist: ApiFootballPersonRefDto? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("detail") val detail: String? = null
)

data class ApiFootballEventTimeDto(
    @SerializedName("elapsed") val elapsed: Int? = null,
    @SerializedName("extra") val extra: Int? = null
)

data class ApiFootballPersonRefDto(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("name") val name: String? = null
)

data class ApiFootballLineupDto(
    @SerializedName("team") val team: ApiFootballTeamInfoDto? = null,
    @SerializedName("coach") val coach: ApiFootballPersonRefDto? = null,
    @SerializedName("formation") val formation: String? = null,
    @SerializedName("startXI") val startXI: List<ApiFootballLineupSlotDto>? = null,
    @SerializedName("substitutes") val substitutes: List<ApiFootballLineupSlotDto>? = null
)

data class ApiFootballLineupSlotDto(
    @SerializedName("player") val player: ApiFootballLineupPlayerDto? = null
)

data class ApiFootballLineupPlayerDto(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("number") val number: Int? = null,
    @SerializedName("pos") val pos: String? = null,
    @SerializedName("grid") val grid: String? = null
)

data class ApiFootballStandingsWrapperDto(
    @SerializedName("league") val league: ApiFootballLeagueDto? = null
)

data class ApiFootballStandingDto(
    @SerializedName("rank") val rank: Int = 0,
    @SerializedName("team") val team: ApiFootballTeamInfoDto? = null,
    @SerializedName("points") val points: Int = 0,
    @SerializedName("goalsDiff") val goalsDiff: Int = 0,
    @SerializedName("form") val form: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("all") val all: ApiFootballRecordStatsDto? = null
)

data class ApiFootballRecordStatsDto(
    @SerializedName("played") val played: Int = 0,
    @SerializedName("win") val win: Int = 0,
    @SerializedName("draw") val draw: Int = 0,
    @SerializedName("lose") val lose: Int = 0,
    @SerializedName("goals") val goals: ApiFootballGoalsForAgainstDto? = null
)

data class ApiFootballGoalsForAgainstDto(
    @SerializedName("for") val goalsFor: Int = 0,
    @SerializedName("against") val goalsAgainst: Int = 0
)

data class ApiFootballTeamSearchDto(
    @SerializedName("team") val team: ApiFootballTeamInfoDto? = null,
    @SerializedName("venue") val venue: ApiFootballVenueDto? = null
)

data class ApiFootballPlayerStatsResponseDto(
    @SerializedName("player") val player: ApiFootballPlayerBioDto? = null,
    @SerializedName("statistics") val statistics: List<ApiFootballPlayerCompetitionStatDto>? = null
)

data class ApiFootballPlayerBioDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String? = null,
    @SerializedName("age") val age: Int? = null,
    @SerializedName("nationality") val nationality: String? = null,
    @SerializedName("photo") val photo: String? = null
)

data class ApiFootballPlayerCompetitionStatDto(
    @SerializedName("team") val team: ApiFootballTeamInfoDto? = null,
    @SerializedName("games") val games: ApiFootballPlayerGamesDto? = null,
    @SerializedName("goals") val goals: ApiFootballPlayerGoalsDto? = null,
    @SerializedName("cards") val cards: ApiFootballPlayerCardsDto? = null
)

data class ApiFootballPlayerGamesDto(
    @SerializedName("appearences") val appearances: Int? = null,
    @SerializedName("number") val number: Int? = null,
    @SerializedName("position") val position: String? = null
)

data class ApiFootballPlayerGoalsDto(
    @SerializedName("total") val total: Int? = null,
    @SerializedName("assists") val assists: Int? = null
)

data class ApiFootballPlayerCardsDto(
    @SerializedName("yellow") val yellow: Int? = null,
    @SerializedName("red") val red: Int? = null
)

data class ApiFootballSquadResponseDto(
    @SerializedName("team") val team: ApiFootballTeamInfoDto? = null,
    @SerializedName("players") val players: List<ApiFootballSquadMemberDto>? = null
)

data class ApiFootballSquadMemberDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String? = null,
    @SerializedName("age") val age: Int? = null,
    @SerializedName("number") val number: Int? = null,
    @SerializedName("position") val position: String? = null,
    @SerializedName("photo") val photo: String? = null
)

data class ApiFootballInjuryDto(
    @SerializedName("player") val player: ApiFootballInjuryPlayerDto? = null,
    @SerializedName("team") val team: ApiFootballTeamInfoDto? = null,
    @SerializedName("fixture") val fixture: ApiFootballFixtureMetaDto? = null
)

data class ApiFootballInjuryPlayerDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String? = null,
    @SerializedName("photo") val photo: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("reason") val reason: String? = null
)