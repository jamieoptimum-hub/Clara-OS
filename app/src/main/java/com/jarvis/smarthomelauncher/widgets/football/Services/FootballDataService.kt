package com.jarvis.smarthomelauncher.widgets.football.Services

import android.util.Xml
import com.jarvis.smarthomelauncher.widgets.football.Models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * CWAS Rule 15: Provider abstraction decoupling FootballRepository & UI from specific upstream feeds.
 */
interface FootballDataProvider {
    val providerName: String

    suspend fun searchTeams(
        query: String,
        competitions: List<SupportedCompetition>
    ): List<FootballClubSummary>

    suspend fun fetchFixturesWindow(
        competitions: List<SupportedCompetition>,
        fromDate: LocalDate,
        toDate: LocalDate,
        seasonYear: Int
    ): List<FootballFixture>

    suspend fun fetchLiveMatches(
        competitions: List<SupportedCompetition>
    ): List<FootballFixture>

    suspend fun fetchStandings(
        competition: SupportedCompetition,
        seasonYear: Int
    ): List<LeagueStandingRow>

    suspend fun fetchCompetitionLeaders(
        competition: SupportedCompetition,
        seasonYear: Int,
        standingsFallback: List<LeagueStandingRow> = emptyList()
    ): Pair<List<FootballPlayerStatEntry>, List<FootballPlayerStatEntry>>

    suspend fun fetchTeamSquadAndIssues(
        team: FavouriteTeamEntry,
        seasonYear: Int
    ): Pair<List<FootballPlayerStatEntry>, List<PlayerAvailabilityIssue>>

    suspend fun fetchFixtureDetailEnrichment(
        fixture: FootballFixture,
        seasonYear: Int
    ): FootballFixtureEnrichment

    suspend fun fetchStadiumWeather(
        stadiumCity: String,
        latitude: Double?,
        longitude: Double?,
        kickoffEpochMs: Long
    ): StadiumWeatherSnapshot?

    suspend fun fetchNews(
        favouriteTeams: List<FavouriteTeamEntry>,
        competitions: List<SupportedCompetition>
    ): List<FootballNewsArticle>
}

data class FootballFixtureEnrichment(
    val homeLineup: TeamLineup?,
    val awayLineup: TeamLineup?,
    val lineupState: LineupAvailabilityState,
    val events: List<FootballMatchEvent>,
    val broadcast: BroadcastInfo,
    val headToHead: List<FootballFixture>,
    val injuries: List<PlayerAvailabilityIssue>,
    val suspensions: List<PlayerAvailabilityIssue>,
    val refereeName: String,
    val attendance: Int?,
    val stadiumWeather: StadiumWeatherSnapshot?
)

/**
 * Live API-Football v3 Retrofit definition (https://v3.football.api-sports.io/).
 */
interface ApiFootballV3Service {
    @GET("v3/teams")
    suspend fun searchTeams(
        @Header("x-apisports-key") apiKey: String,
        @Query("search") search: String
    ): Response<ApiFootballEnvelope<ApiFootballTeamSearchDto>>

    @GET("v3/fixtures")
    suspend fun getFixturesByRange(
        @Header("x-apisports-key") apiKey: String,
        @Query("league") leagueId: Int,
        @Query("season") season: Int,
        @Query("from") fromIsoDate: String,
        @Query("to") toIsoDate: String,
        @Query("timezone") timezone: String = ZoneId.systemDefault().id
    ): Response<ApiFootballEnvelope<ApiFootballFixtureDto>>

    @GET("v3/fixtures")
    suspend fun getLiveFixtures(
        @Header("x-apisports-key") apiKey: String,
        @Query("live") liveFilter: String = "all",
        @Query("timezone") timezone: String = ZoneId.systemDefault().id
    ): Response<ApiFootballEnvelope<ApiFootballFixtureDto>>

    @GET("v3/fixtures")
    suspend fun getFixtureById(
        @Header("x-apisports-key") apiKey: String,
        @Query("id") fixtureId: Int,
        @Query("timezone") timezone: String = ZoneId.systemDefault().id
    ): Response<ApiFootballEnvelope<ApiFootballFixtureDto>>

    @GET("v3/fixtures/headtohead")
    suspend fun getHeadToHead(
        @Header("x-apisports-key") apiKey: String,
        @Query("h2h") homeDashAwayIds: String,
        @Query("last") lastCount: Int = 10
    ): Response<ApiFootballEnvelope<ApiFootballFixtureDto>>

    @GET("v3/standings")
    suspend fun getStandings(
        @Header("x-apisports-key") apiKey: String,
        @Query("league") leagueId: Int,
        @Query("season") season: Int
    ): Response<ApiFootballEnvelope<ApiFootballStandingsWrapperDto>>

    @GET("v3/players/topscorers")
    suspend fun getTopScorers(
        @Header("x-apisports-key") apiKey: String,
        @Query("league") leagueId: Int,
        @Query("season") season: Int
    ): Response<ApiFootballEnvelope<ApiFootballPlayerStatsResponseDto>>

    @GET("v3/players/topassists")
    suspend fun getTopAssists(
        @Header("x-apisports-key") apiKey: String,
        @Query("league") leagueId: Int,
        @Query("season") season: Int
    ): Response<ApiFootballEnvelope<ApiFootballPlayerStatsResponseDto>>

    @GET("v3/players/squads")
    suspend fun getTeamSquad(
        @Header("x-apisports-key") apiKey: String,
        @Query("team") teamId: Int
    ): Response<ApiFootballEnvelope<ApiFootballSquadResponseDto>>

    @GET("v3/injuries")
    suspend fun getTeamInjuries(
        @Header("x-apisports-key") apiKey: String,
        @Query("team") teamId: Int,
        @Query("season") season: Int
    ): Response<ApiFootballEnvelope<ApiFootballInjuryDto>>
}

/**
 * Production Hybrid Live Provider:
 * - Uses API-Football v3 when an API key is configured in ClaraSecretVault.
 * - Uses ESPN Soccer Live Site API v2 for real-time fixtures, TV broadcast channels, standings,
 *   rosters, match events, lineups, and keyless multi-league coverage across all 17 supported competitions.
 * - Uses Open-Meteo Forecast & Geocoding APIs for real kickoff-hour stadium weather.
 * - Uses BBC Sport, Sky Sports, and Guardian Football live RSS feeds for real football news.
 */
class HybridLiveFootballDataProvider(
    private val apiKeyProvider: () -> String?
) : FootballDataProvider {

    private val okHttpClient: OkHttpClient = com.jarvis.smarthomelauncher.core.NetworkModule.okHttpClient.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val apiFootballService: ApiFootballV3Service by lazy {
        Retrofit.Builder()
            .baseUrl("https://v3.football.api-sports.io/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiFootballV3Service::class.java)
    }

    private val geocodeCache = ConcurrentHashMap<String, Pair<Double, Double>>()

    override val providerName: String
        get() = if (!apiKeyProvider().isNullOrBlank()) {
            "API-Football v3 + ESPN Broadcast & Live Feeds"
        } else {
            "ESPN Live Soccer API v2 + Open-Meteo + UK Sports RSS"
        }

    companion object {
        fun currentFootballSeasonYear(referenceDate: LocalDate = LocalDate.now()): Int {
            return if (referenceDate.monthValue >= 7) referenceDate.year else referenceDate.year - 1
        }

        fun normalizeClubName(raw: String): String {
            return raw.lowercase(Locale.UK)
                .replace("fc", "")
                .replace("afc", "")
                .replace("&", "and")
                .replace(Regex("[^a-z0-9]"), "")
                .trim()
        }

        fun clubsMatch(a: String, b: String): Boolean {
            val na = normalizeClubName(a)
            val nb = normalizeClubName(b)
            if (na.isBlank() || nb.isBlank()) return false
            return na == nb || na.contains(nb) || nb.contains(na)
        }
    }

    override suspend fun searchTeams(
        query: String,
        competitions: List<SupportedCompetition>
    ): List<FootballClubSummary> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        val targetCompetitions = competitions.ifEmpty { SupportedCompetition.entries }
        val results = LinkedHashMap<String, FootballClubSummary>()

        val apiKey = apiKeyProvider()?.trim().orEmpty()
        if (apiKey.isNotBlank() && trimmed.length >= 2) {
            runCatching {
                val resp = apiFootballService.searchTeams(apiKey = apiKey, search = trimmed)
                if (resp.isSuccessful) {
                    resp.body()?.response.orEmpty().forEach { item ->
                        val dto = item.team ?: return@forEach
                        val name = dto.name?.trim().orEmpty()
                        if (name.isNotBlank()) {
                            val summary = FootballClubSummary(
                                id = "af_${dto.id}",
                                apiFootballId = dto.id,
                                espnId = null,
                                name = name,
                                shortName = name,
                                abbreviation = dto.code?.takeIf { it.isNotBlank() }
                                    ?: name.take(3).uppercase(Locale.UK),
                                badgeUrl = dto.logo.orEmpty()
                            )
                            results[name.lowercase(Locale.UK)] = summary
                        }
                    }
                }
            }
        }

        val searchLeagues = if (trimmed.isBlank()) {
            targetCompetitions.take(6)
        } else {
            targetCompetitions
        }

        coroutineScope {
            searchLeagues.map { comp ->
                async {
                    fetchEspnLeagueTeams(comp)
                }
            }.awaitAll().flatten().forEach { espnClub ->
                val matchesQuery = trimmed.isBlank() ||
                        espnClub.name.contains(trimmed, ignoreCase = true) ||
                        espnClub.shortName.contains(trimmed, ignoreCase = true) ||
                        espnClub.abbreviation.equals(trimmed, ignoreCase = true)
                if (matchesQuery) {
                    val key = espnClub.name.lowercase(Locale.UK)
                    val existing = results[key]
                    if (existing == null) {
                        results[key] = espnClub
                    } else if (existing.espnId == null) {
                        results[key] = existing.copy(
                            espnId = espnClub.espnId,
                            primaryColorHex = espnClub.primaryColorHex,
                            badgeUrl = existing.badgeUrl.ifBlank { espnClub.badgeUrl }
                        )
                    }
                }
            }
        }

        results.values.sortedBy { it.name }
    }

    override suspend fun fetchFixturesWindow(
        competitions: List<SupportedCompetition>,
        fromDate: LocalDate,
        toDate: LocalDate,
        seasonYear: Int
    ): List<FootballFixture> = withContext(Dispatchers.IO) {
        val activeCompetitions = competitions.distinct()
        val espnFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val espnRangeParam = "${fromDate.format(espnFormatter)}-${toDate.format(espnFormatter)}"

        val espnFixtures = coroutineScope {
            activeCompetitions.map { comp ->
                async {
                    fetchEspnScoreboardFixtures(comp, espnRangeParam)
                }
            }.awaitAll().flatten()
        }

        val apiKey = apiKeyProvider()?.trim().orEmpty()
        if (apiKey.isBlank()) {
            return@withContext espnFixtures.sortedBy { it.kickoffEpochMs }
        }

        val isoFrom = fromDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val isoTo = toDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val apiFootballFixtures = coroutineScope {
            activeCompetitions.map { comp ->
                async {
                    runCatching {
                        val resp = apiFootballService.getFixturesByRange(
                            apiKey = apiKey,
                            leagueId = comp.apiFootballLeagueId,
                            season = seasonYear,
                            fromIsoDate = isoFrom,
                            toIsoDate = isoTo
                        )
                        if (resp.isSuccessful) {
                            resp.body()?.response.orEmpty().mapNotNull { dto ->
                                mapApiFootballFixture(dto, comp)
                            }
                        } else {
                            emptyList()
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }

        if (apiFootballFixtures.isEmpty()) {
            return@withContext espnFixtures.sortedBy { it.kickoffEpochMs }
        }

        mergeFixtureFeeds(primary = apiFootballFixtures, broadcastSource = espnFixtures)
            .sortedBy { it.kickoffEpochMs }
    }

    override suspend fun fetchLiveMatches(
        competitions: List<SupportedCompetition>
    ): List<FootballFixture> = withContext(Dispatchers.IO) {
        val todayParam = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val espnToday = coroutineScope {
            competitions.distinct().map { comp ->
                async { fetchEspnScoreboardFixtures(comp, todayParam) }
            }.awaitAll().flatten()
        }.filter { it.status.isLive }

        val apiKey = apiKeyProvider()?.trim().orEmpty()
        if (apiKey.isBlank()) {
            return@withContext espnToday.sortedBy { it.kickoffEpochMs }
        }

        val allowedLeagueIds = competitions.map { it.apiFootballLeagueId }.toSet()
        val afLive = runCatching {
            val resp = apiFootballService.getLiveFixtures(apiKey = apiKey)
            if (resp.isSuccessful) {
                resp.body()?.response.orEmpty().mapNotNull { dto ->
                    val leagueId = dto.league?.id ?: return@mapNotNull null
                    if (leagueId !in allowedLeagueIds) return@mapNotNull null
                    val comp = SupportedCompetition.fromApiFootballId(leagueId) ?: return@mapNotNull null
                    mapApiFootballFixture(dto, comp)
                }
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())

        if (afLive.isEmpty()) espnToday else mergeFixtureFeeds(afLive, espnToday)
    }

    override suspend fun fetchStandings(
        competition: SupportedCompetition,
        seasonYear: Int
    ): List<LeagueStandingRow> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()?.trim().orEmpty()
        if (apiKey.isNotBlank()) {
            val afStandings = runCatching {
                val resp = apiFootballService.getStandings(
                    apiKey = apiKey,
                    leagueId = competition.apiFootballLeagueId,
                    season = seasonYear
                )
                if (resp.isSuccessful) {
                    val table = resp.body()?.response
                        ?.firstOrNull()
                        ?.league
                        ?.standings
                        ?.firstOrNull()
                        .orEmpty()
                    val totalTeams = table.size
                    table.mapNotNull { row -> mapApiFootballStanding(row, competition, totalTeams) }
                } else {
                    emptyList()
                }
            }.getOrDefault(emptyList())

            if (afStandings.isNotEmpty()) {
                return@withContext afStandings.sortedBy { it.position }
            }
        }

        fetchEspnStandings(competition)
    }

    override suspend fun fetchCompetitionLeaders(
        competition: SupportedCompetition,
        seasonYear: Int,
        standingsFallback: List<LeagueStandingRow>
    ): Pair<List<FootballPlayerStatEntry>, List<FootballPlayerStatEntry>> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()?.trim().orEmpty()
        if (apiKey.isNotBlank()) {
            val scorers = runCatching {
                val resp = apiFootballService.getTopScorers(
                    apiKey = apiKey,
                    leagueId = competition.apiFootballLeagueId,
                    season = seasonYear
                )
                if (resp.isSuccessful) {
                    resp.body()?.response.orEmpty().mapNotNull { mapApiFootballPlayerStat(it) }
                } else emptyList()
            }.getOrDefault(emptyList())

            val assists = runCatching {
                val resp = apiFootballService.getTopAssists(
                    apiKey = apiKey,
                    leagueId = competition.apiFootballLeagueId,
                    season = seasonYear
                )
                if (resp.isSuccessful) {
                    resp.body()?.response.orEmpty().mapNotNull { mapApiFootballPlayerStat(it) }
                } else emptyList()
            }.getOrDefault(emptyList())

            if (scorers.isNotEmpty() || assists.isNotEmpty()) {
                return@withContext Pair(scorers, assists)
            }
        }

        val sampleTeams = standingsFallback
            .sortedBy { it.position }
            .take(8)
            .mapNotNull { row ->
                val espnId = row.team.espnId ?: row.team.id.removePrefix("espn_").takeIf { it.all(Char::isDigit) }
                if (espnId != null) Triple(espnId, row.team.name, row.team.badgeUrl) else null
            }

        val aggregatedPlayers = coroutineScope {
            sampleTeams.map { (espnTeamId, teamName, badgeUrl) ->
                async {
                    fetchEspnTeamRosterAndIssues(
                        espnLeagueSlug = competition.espnLeagueSlug,
                        espnTeamId = espnTeamId,
                        teamName = teamName,
                        teamBadgeUrl = badgeUrl
                    ).first
                }
            }.awaitAll().flatten()
        }

        val topScorers = aggregatedPlayers
            .filter { it.goals > 0 }
            .sortedWith(compareByDescending<FootballPlayerStatEntry> { it.goals }.thenByDescending { it.assists })
            .take(20)

        val topAssists = aggregatedPlayers
            .filter { it.assists > 0 }
            .sortedWith(compareByDescending<FootballPlayerStatEntry> { it.assists }.thenByDescending { it.goals })
            .take(20)

        Pair(topScorers, topAssists)
    }

    override suspend fun fetchTeamSquadAndIssues(
        team: FavouriteTeamEntry,
        seasonYear: Int
    ): Pair<List<FootballPlayerStatEntry>, List<PlayerAvailabilityIssue>> = withContext(Dispatchers.IO) {
        val comp = SupportedCompetition.fromIdOrName(team.primaryCompetitionId)
            ?: SupportedCompetition.PREMIER_LEAGUE

        val resolvedEspnId = team.espnTeamId ?: resolveEspnTeamIdByName(comp, team.name)
        if (!resolvedEspnId.isNullOrBlank()) {
            val espnResult = fetchEspnTeamRosterAndIssues(
                espnLeagueSlug = comp.espnLeagueSlug,
                espnTeamId = resolvedEspnId,
                teamName = team.name,
                teamBadgeUrl = team.badgeUrl
            )
            if (espnResult.first.isNotEmpty() || espnResult.second.isNotEmpty()) {
                return@withContext espnResult
            }
        }

        val apiKey = apiKeyProvider()?.trim().orEmpty()
        val afTeamId = team.apiFootballTeamId
        if (apiKey.isNotBlank() && afTeamId != null) {
            val squad = runCatching {
                val resp = apiFootballService.getTeamSquad(apiKey = apiKey, teamId = afTeamId)
                if (resp.isSuccessful) {
                    resp.body()?.response?.firstOrNull()?.players.orEmpty().map { member ->
                        FootballPlayerStatEntry(
                            playerId = "af_p_${member.id}",
                            name = member.name.orEmpty(),
                            photoUrl = member.photo.orEmpty(),
                            teamId = team.teamId,
                            teamName = team.name,
                            teamBadgeUrl = team.badgeUrl,
                            position = member.position.orEmpty(),
                            shirtNumber = member.number,
                            age = member.age
                        )
                    }
                } else emptyList()
            }.getOrDefault(emptyList())

            val issues = runCatching {
                val resp = apiFootballService.getTeamInjuries(
                    apiKey = apiKey,
                    teamId = afTeamId,
                    season = seasonYear
                )
                if (resp.isSuccessful) {
                    resp.body()?.response.orEmpty().mapNotNull { dto ->
                        val p = dto.player ?: return@mapNotNull null
                        val reason = p.reason.orEmpty().ifBlank { p.type.orEmpty() }
                        val isSusp = reason.contains("suspen", ignoreCase = true) ||
                                reason.contains("red card", ignoreCase = true) ||
                                reason.contains("yellow", ignoreCase = true)
                        PlayerAvailabilityIssue(
                            id = "af_inj_${p.id}_${dto.fixture?.id ?: 0}",
                            playerId = "af_p_${p.id}",
                            playerName = p.name.orEmpty(),
                            playerPhotoUrl = p.photo.orEmpty(),
                            teamId = team.teamId,
                            teamName = dto.team?.name ?: team.name,
                            isSuspension = isSusp,
                            reason = reason.ifBlank { "Unavailable" },
                            statusLabel = p.type.orEmpty().ifBlank { if (isSusp) "Suspended" else "Injured" }
                        )
                    }.distinctBy { "${it.playerName}_${it.reason}" }
                } else emptyList()
            }.getOrDefault(emptyList())

            return@withContext Pair(squad, issues)
        }

        Pair(emptyList(), emptyList())
    }

    override suspend fun fetchFixtureDetailEnrichment(
        fixture: FootballFixture,
        seasonYear: Int
    ): FootballFixtureEnrichment = withContext(Dispatchers.IO) {
        val comp = SupportedCompetition.fromIdOrName(fixture.competitionId)
            ?: SupportedCompetition.PREMIER_LEAGUE

        var homeLineup: TeamLineup? = fixture.homeLineup
        var awayLineup: TeamLineup? = fixture.awayLineup
        var events: List<FootballMatchEvent> = fixture.events
        val broadcast: BroadcastInfo = fixture.broadcast
        var headToHead: List<FootballFixture> = emptyList()
        var refereeName: String = fixture.refereeName
        var attendance: Int? = fixture.attendance
        val allIssues = mutableListOf<PlayerAvailabilityIssue>()

        val espnEventId = fixture.espnEventId ?: fixture.id.removePrefix("espn_").takeIf { it.all(Char::isDigit) }
        if (!espnEventId.isNullOrBlank()) {
            val summaryJson = getJsonObject(
                "https://site.api.espn.com/apis/site/v2/sports/soccer/${comp.espnLeagueSlug}/summary?event=$espnEventId"
            )
            if (summaryJson != null) {
                val parsedRosters = parseEspnSummaryRosters(summaryJson, fixture)
                if (parsedRosters.first != null) homeLineup = parsedRosters.first
                if (parsedRosters.second != null) awayLineup = parsedRosters.second

                val parsedEvents = parseEspnSummaryKeyEvents(summaryJson, fixture)
                if (parsedEvents.isNotEmpty()) events = parsedEvents

                val parsedH2h = parseEspnHeadToHead(summaryJson, comp)
                if (parsedH2h.isNotEmpty()) headToHead = parsedH2h

                val gameInfo = summaryJson.optJSONObject("gameInfo")
                if (gameInfo != null) {
                    if (attendance == null && gameInfo.has("attendance")) {
                        attendance = gameInfo.optInt("attendance").takeIf { it > 0 }
                    }
                    val officials = gameInfo.optJSONArray("officials")
                    if (refereeName.isBlank() && officials != null && officials.length() > 0) {
                        refereeName = officials.optJSONObject(0)?.optString("displayName").orEmpty()
                    }
                }
            }
        }

        val apiKey = apiKeyProvider()?.trim().orEmpty()
        if (apiKey.isNotBlank() && fixture.apiFootballFixtureId != null) {
            runCatching {
                val detailResp = apiFootballService.getFixtureById(
                    apiKey = apiKey,
                    fixtureId = fixture.apiFootballFixtureId
                )
                if (detailResp.isSuccessful) {
                    val dto = detailResp.body()?.response?.firstOrNull()
                    if (dto != null) {
                        val lineups = dto.lineups.orEmpty()
                        if (lineups.size >= 2) {
                            homeLineup = mapApiFootballLineup(lineups[0])
                            awayLineup = mapApiFootballLineup(lineups[1])
                        }
                        val mappedEvents = mapApiFootballEvents(dto.events.orEmpty(), fixture.homeTeam.apiFootballId)
                        if (mappedEvents.isNotEmpty()) events = mappedEvents
                        if (refereeName.isBlank()) refereeName = dto.fixture?.referee.orEmpty()
                    }
                }
            }

            if (fixture.homeTeam.apiFootballId != null && fixture.awayTeam.apiFootballId != null) {
                runCatching {
                    val h2hResp = apiFootballService.getHeadToHead(
                        apiKey = apiKey,
                        homeDashAwayIds = "${fixture.homeTeam.apiFootballId}-${fixture.awayTeam.apiFootballId}",
                        lastCount = 10
                    )
                    if (h2hResp.isSuccessful) {
                        val mapped = h2hResp.body()?.response.orEmpty()
                            .mapNotNull { mapApiFootballFixture(it, comp) }
                            .sortedByDescending { it.kickoffEpochMs }
                        if (mapped.isNotEmpty()) headToHead = mapped
                    }
                }
            }
        }

        val homeEspnId = fixture.homeTeam.espnId
        val awayEspnId = fixture.awayTeam.espnId
        coroutineScope {
            val jobs = buildList {
                if (!homeEspnId.isNullOrBlank()) {
                    add(async {
                        fetchEspnTeamRosterAndIssues(
                            comp.espnLeagueSlug,
                            homeEspnId,
                            fixture.homeTeam.name,
                            fixture.homeTeam.badgeUrl
                        ).second
                    })
                }
                if (!awayEspnId.isNullOrBlank()) {
                    add(async {
                        fetchEspnTeamRosterAndIssues(
                            comp.espnLeagueSlug,
                            awayEspnId,
                            fixture.awayTeam.name,
                            fixture.awayTeam.badgeUrl
                        ).second
                    })
                }
            }
            jobs.awaitAll().flatten().let { allIssues.addAll(it) }
        }

        val resolvedLineupState = computeLineupState(
            kickoffEpochMs = fixture.kickoffEpochMs,
            status = fixture.status,
            homeLineup = homeLineup,
            awayLineup = awayLineup
        )

        val weather = fixture.stadiumWeather ?: fetchStadiumWeather(
            stadiumCity = fixture.stadiumCity.ifBlank { fixture.stadiumName },
            latitude = fixture.stadiumLatitude,
            longitude = fixture.stadiumLongitude,
            kickoffEpochMs = fixture.kickoffEpochMs
        )

        FootballFixtureEnrichment(
            homeLineup = homeLineup,
            awayLineup = awayLineup,
            lineupState = resolvedLineupState,
            events = events,
            broadcast = broadcast,
            headToHead = headToHead.take(10),
            injuries = allIssues.filterNot { it.isSuspension },
            suspensions = allIssues.filter { it.isSuspension },
            refereeName = refereeName,
            attendance = attendance,
            stadiumWeather = weather
        )
    }

    override suspend fun fetchStadiumWeather(
        stadiumCity: String,
        latitude: Double?,
        longitude: Double?,
        kickoffEpochMs: Long
    ): StadiumWeatherSnapshot? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val daysUntilKickoff = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(now),
            Instant.ofEpochMilli(kickoffEpochMs)
        )
        if (daysUntilKickoff < -1 || daysUntilKickoff > 14) return@withContext null

        val coords: Pair<Double, Double> = if (latitude != null && longitude != null) {
            Pair(latitude, longitude)
        } else {
            val queryName = stadiumCity
                .substringBefore(",")
                .replace("Stadium", "", ignoreCase = true)
                .replace("Park", "", ignoreCase = true)
                .trim()
            if (queryName.length < 2) return@withContext null
            geocodeCache[queryName.lowercase(Locale.UK)] ?: run {
                val encoded = URLEncoder.encode(queryName, "UTF-8")
                val geoObj = getJsonObject(
                    "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json"
                )
                val first = geoObj?.optJSONArray("results")?.optJSONObject(0) ?: return@withContext null
                val lat = first.optDouble("latitude", Double.NaN)
                val lon = first.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return@withContext null
                Pair(lat, lon).also { geocodeCache[queryName.lowercase(Locale.UK)] = it }
            }
        }

        val forecastObj = getJsonObject(
            "https://api.open-meteo.com/v1/forecast?latitude=${coords.first}&longitude=${coords.second}" +
                    "&hourly=temperature_2m,precipitation_probability,weathercode,windspeed_10m&timezone=UTC"
        ) ?: return@withContext null

        val hourly = forecastObj.optJSONObject("hourly") ?: return@withContext null
        val times = hourly.optJSONArray("time") ?: return@withContext null
        val temps = hourly.optJSONArray("temperature_2m") ?: return@withContext null
        val precips = hourly.optJSONArray("precipitation_probability")
        val codes = hourly.optJSONArray("weathercode")
        val winds = hourly.optJSONArray("windspeed_10m")

        val kickoffHourPrefix = Instant.ofEpochMilli(kickoffEpochMs)
            .atZone(ZoneId.of("UTC"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH"))

        var matchedIdx = 0
        for (i in 0 until times.length()) {
            if (times.optString(i).startsWith(kickoffHourPrefix)) {
                matchedIdx = i
                break
            }
        }

        val temp = temps.optDouble(matchedIdx, Double.NaN)
        if (temp.isNaN()) return@withContext null
        val precip = precips?.optInt(matchedIdx, 0) ?: 0
        val weatherCode = codes?.optInt(matchedIdx, 0) ?: 0
        val wind = winds?.optDouble(matchedIdx, 0.0) ?: 0.0

        StadiumWeatherSnapshot(
            temperatureCelsius = temp,
            conditionSummary = mapWmoWeatherCode(weatherCode),
            precipitationProbabilityPercent = precip,
            windSpeedKmh = wind
        )
    }

    override suspend fun fetchNews(
        favouriteTeams: List<FavouriteTeamEntry>,
        competitions: List<SupportedCompetition>
    ): List<FootballNewsArticle> = withContext(Dispatchers.IO) {
        val rssUrls = listOf(
            "BBC Sport" to "https://feeds.bbci.co.uk/sport/football/rss.xml",
            "Sky Sports" to "https://www.skysports.com/rss/12040",
            "The Guardian" to "https://www.theguardian.com/football/rss"
        )

        val favNames = favouriteTeams.flatMap {
            listOf(it.name, it.shortName).filter { s -> s.length >= 3 }
        }.distinct()

        val articles = coroutineScope {
            rssUrls.map { (sourceName, feedUrl) ->
                async {
                    fetchAndParseRssFeed(sourceName, feedUrl, favNames, competitions)
                }
            }.awaitAll().flatten()
        }

        articles
            .distinctBy { it.title.lowercase(Locale.UK).trim() }
            .sortedWith(
                compareByDescending<FootballNewsArticle> { it.involvesFavouriteTeam }
                    .thenByDescending { it.publishedEpochMs }
            )
            .take(60)
    }

    // ========================================================================
    // Internal Live ESPN / API-Football / RSS Parsing Helpers
    // ========================================================================

    private fun fetchEspnLeagueTeams(comp: SupportedCompetition): List<FootballClubSummary> {
        val root = getJsonObject(
            "https://site.api.espn.com/apis/site/v2/sports/soccer/${comp.espnLeagueSlug}/teams"
        ) ?: return emptyList()

        val teamsArr = root.optJSONArray("sports")
            ?.optJSONObject(0)
            ?.optJSONArray("leagues")
            ?.optJSONObject(0)
            ?.optJSONArray("teams")
            ?: return emptyList()

        val list = mutableListOf<FootballClubSummary>()
        for (i in 0 until teamsArr.length()) {
            val teamObj = teamsArr.optJSONObject(i)?.optJSONObject("team") ?: continue
            val espnId = teamObj.optString("id")
            val displayName = teamObj.optString("displayName").ifBlank { teamObj.optString("name") }
            if (espnId.isBlank() || displayName.isBlank()) continue
            val shortName = teamObj.optString("shortDisplayName").ifBlank { displayName }
            val abbr = teamObj.optString("abbreviation").ifBlank { shortName.take(3).uppercase(Locale.UK) }
            val rawColor = teamObj.optString("color").trim().removePrefix("#")
            val colorHex = if (rawColor.length == 6) "#$rawColor" else "#1E88E5"
            val logoUrl = extractEspnLogoUrl(teamObj)

            list.add(
                FootballClubSummary(
                    id = "espn_$espnId",
                    apiFootballId = null,
                    espnId = espnId,
                    name = displayName,
                    shortName = shortName,
                    abbreviation = abbr,
                    badgeUrl = logoUrl,
                    primaryColorHex = colorHex
                )
            )
        }
        return list
    }

    private fun resolveEspnTeamIdByName(comp: SupportedCompetition, teamName: String): String? {
        val teams = fetchEspnLeagueTeams(comp)
        val normalized = normalizeClubName(teamName)
        return teams.firstOrNull {
            normalizeClubName(it.name) == normalized ||
                    normalizeClubName(it.shortName) == normalized ||
                    it.name.contains(teamName, ignoreCase = true) ||
                    teamName.contains(it.shortName, ignoreCase = true)
        }?.espnId
    }

    private fun fetchEspnScoreboardFixtures(
        comp: SupportedCompetition,
        dateRangeParam: String
    ): List<FootballFixture> {
        val root = getJsonObject(
            "https://site.api.espn.com/apis/site/v2/sports/soccer/${comp.espnLeagueSlug}/scoreboard?dates=$dateRangeParam&limit=200"
        ) ?: return emptyList()

        val eventsArr = root.optJSONArray("events") ?: return emptyList()
        val fixtures = mutableListOf<FootballFixture>()

        for (i in 0 until eventsArr.length()) {
            val ev = eventsArr.optJSONObject(i) ?: continue
            val parsed = parseEspnEventToFixture(ev, comp) ?: continue
            fixtures.add(parsed)
        }
        return fixtures
    }

    private fun parseEspnEventToFixture(
        ev: JSONObject,
        comp: SupportedCompetition
    ): FootballFixture? {
        val eventId = ev.optString("id")
        if (eventId.isBlank()) return null
        val kickoffMs = parseIsoTimestampMs(ev.optString("date"))
        if (kickoffMs <= 0L) return null

        val compObj = ev.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val competitors = compObj.optJSONArray("competitors") ?: return null
        if (competitors.length() < 2) return null

        var homeObj: JSONObject? = null
        var awayObj: JSONObject? = null
        for (c in 0 until competitors.length()) {
            val item = competitors.optJSONObject(c) ?: continue
            if (item.optString("homeAway") == "home") homeObj = item else awayObj = item
        }
        val safeHome = homeObj ?: competitors.optJSONObject(0) ?: return null
        val safeAway = awayObj ?: competitors.optJSONObject(1) ?: return null

        val homeClub = parseEspnCompetitorClub(safeHome)
        val awayClub = parseEspnCompetitorClub(safeAway)

        val statusObj = compObj.optJSONObject("status") ?: ev.optJSONObject("status")
        val statusType = statusObj?.optJSONObject("type")
        val stateStr = statusType?.optString("state").orEmpty()
        val typeName = statusType?.optString("name").orEmpty()
        val shortDetail = statusType?.optString("shortDetail").orEmpty()
        val displayClock = statusObj?.optString("displayClock").orEmpty()

        val lifecycle = mapEspnLifecycle(stateStr, typeName, shortDetail)
        val minutePair = parseDisplayClockMinutes(displayClock)

        val showScore = lifecycle.isLive || lifecycle.isFinished
        val homeScore = if (showScore) safeHome.optString("score").toIntOrNull() else null
        val awayScore = if (showScore) safeAway.optString("score").toIntOrNull() else null

        val venueObj = compObj.optJSONObject("venue")
        val stadiumName = venueObj?.optString("fullName").orEmpty()
        val stadiumCity = venueObj?.optJSONObject("address")?.optString("city").orEmpty()
        val attendance = compObj.optInt("attendance", 0).takeIf { it > 0 }

        val broadcastInfo = parseEspnBroadcasts(compObj, comp)
        val matchEvents = parseEspnScoreboardDetails(compObj.optJSONArray("details"), homeClub.espnId)

        val lineupState = computeLineupState(
            kickoffEpochMs = kickoffMs,
            status = lifecycle,
            homeLineup = null,
            awayLineup = null
        )

        return FootballFixture(
            id = "espn_$eventId",
            apiFootballFixtureId = null,
            espnEventId = eventId,
            competitionId = comp.id,
            competitionName = comp.displayName,
            competitionRound = ev.optJSONObject("season")?.optString("slug").orEmpty(),
            kickoffEpochMs = kickoffMs,
            status = lifecycle,
            statusShortLabel = shortDetail.ifBlank { typeName },
            matchMinute = minutePair.first,
            stoppageMinute = minutePair.second,
            homeTeam = homeClub,
            awayTeam = awayClub,
            homeScore = homeScore,
            awayScore = awayScore,
            stadiumName = stadiumName,
            stadiumCity = stadiumCity,
            attendance = attendance,
            broadcast = broadcastInfo,
            lineupState = lineupState,
            events = matchEvents
        )
    }

    private fun parseEspnCompetitorClub(competitorObj: JSONObject): FootballClubSummary {
        val teamObj = competitorObj.optJSONObject("team") ?: JSONObject()
        val espnId = teamObj.optString("id").ifBlank { competitorObj.optString("id") }
        val name = teamObj.optString("displayName")
            .ifBlank { teamObj.optString("name") }
            .ifBlank { "Club" }
        val shortName = teamObj.optString("shortDisplayName").ifBlank { name }
        val abbr = teamObj.optString("abbreviation").ifBlank { shortName.take(3).uppercase(Locale.UK) }
        val rawColor = teamObj.optString("color").trim().removePrefix("#")
        val colorHex = if (rawColor.length == 6) "#$rawColor" else "#1E88E5"
        val logo = teamObj.optString("logo").ifBlank { extractEspnLogoUrl(teamObj) }

        return FootballClubSummary(
            id = "espn_$espnId",
            apiFootballId = null,
            espnId = espnId,
            name = name,
            shortName = shortName,
            abbreviation = abbr,
            badgeUrl = logo,
            primaryColorHex = colorHex
        )
    }

    private fun parseEspnBroadcasts(compObj: JSONObject, comp: SupportedCompetition): BroadcastInfo {
        val channels = LinkedHashSet<String>()
        val streaming = LinkedHashSet<String>()

        val geoArr = compObj.optJSONArray("geoBroadcasts")
        if (geoArr != null) {
            for (i in 0 until geoArr.length()) {
                val gb = geoArr.optJSONObject(i) ?: continue
                val mediaName = gb.optJSONObject("media")?.optString("shortName").orEmpty().trim()
                val mediaType = gb.optJSONObject("type")?.optString("shortName").orEmpty()
                if (mediaName.isNotBlank()) {
                    if (mediaType.contains("Stream", ignoreCase = true) ||
                        mediaName.contains("+", ignoreCase = true) ||
                        mediaName.contains("Prime", ignoreCase = true) ||
                        mediaName.contains("Discovery", ignoreCase = true) ||
                        mediaName.contains("NOW", ignoreCase = true) ||
                        mediaName.contains("iPlayer", ignoreCase = true) ||
                        mediaName.contains("ITVX", ignoreCase = true)
                    ) {
                        streaming.add(mediaName)
                    } else {
                        channels.add(mediaName)
                    }
                }
            }
        }

        val broadcastsArr = compObj.optJSONArray("broadcasts")
        if (broadcastsArr != null) {
            for (i in 0 until broadcastsArr.length()) {
                val bObj = broadcastsArr.optJSONObject(i) ?: continue
                val namesArr = bObj.optJSONArray("names") ?: continue
                for (n in 0 until namesArr.length()) {
                    val ch = namesArr.optString(n).trim()
                    if (ch.isNotBlank()) channels.add(ch)
                }
            }
        }

        val main = channels.firstOrNull()
            ?: streaming.firstOrNull()
            ?: defaultUkCompetitionBroadcaster(comp)
        val alternates = channels.drop(1)

        return BroadcastInfo(
            mainChannel = main,
            alternateChannels = alternates,
            streamingPlatforms = streaming.toList()
        )
    }

    private fun defaultUkCompetitionBroadcaster(comp: SupportedCompetition): String {
        return when (comp) {
            SupportedCompetition.PREMIER_LEAGUE -> "Sky Sports / TNT Sports"
            SupportedCompetition.CHAMPIONSHIP,
            SupportedCompetition.LEAGUE_ONE,
            SupportedCompetition.LEAGUE_TWO,
            SupportedCompetition.LEAGUE_CUP -> "Sky Sports+"
            SupportedCompetition.FA_CUP -> "BBC / ITV"
            SupportedCompetition.CHAMPIONS_LEAGUE,
            SupportedCompetition.EUROPA_LEAGUE,
            SupportedCompetition.CONFERENCE_LEAGUE -> "TNT Sports"
            SupportedCompetition.SCOTTISH_PREMIERSHIP -> "Sky Sports / Premier Sports"
            SupportedCompetition.LA_LIGA -> "Premier Sports / ITV"
            SupportedCompetition.BUNDESLIGA -> "Sky Sports"
            SupportedCompetition.SERIE_A -> "TNT Sports / OneFootball"
            SupportedCompetition.LIGUE_1 -> "Ligue 1 Pass"
            SupportedCompetition.MLS -> "MLS Season Pass (Apple TV)"
            SupportedCompetition.EUROPEAN_CHAMPIONSHIP,
            SupportedCompetition.WORLD_CUP -> "BBC / ITV"
        }
    }

    private fun parseEspnScoreboardDetails(
        detailsArr: JSONArray?,
        homeEspnTeamId: String?
    ): List<FootballMatchEvent> {
        if (detailsArr == null) return emptyList()
        val events = mutableListOf<FootballMatchEvent>()
        for (i in 0 until detailsArr.length()) {
            val d = detailsArr.optJSONObject(i) ?: continue
            val clockStr = d.optJSONObject("clock")?.optString("displayValue").orEmpty()
            val (min, extra) = parseDisplayClockMinutes(clockStr)
            val teamId = d.optJSONObject("team")?.optString("id").orEmpty()
            val isHome = !homeEspnTeamId.isNullOrBlank() && teamId == homeEspnTeamId
            val athletes = d.optJSONArray("athletesInvolved")
            val primaryPlayer = athletes?.optJSONObject(0)?.optString("displayName").orEmpty()
            val secondaryPlayer = athletes?.optJSONObject(1)?.optString("displayName")

            val isRed = d.optBoolean("redCard", false)
            val isYellow = d.optBoolean("yellowCard", false)
            val isOwnGoal = d.optBoolean("ownGoal", false)
            val isPenalty = d.optBoolean("penaltyKick", false)
            val scoreVal = d.optInt("scoreValue", 0)
            val typeText = d.optJSONObject("type")?.optString("text").orEmpty()

            val kind = when {
                isRed && isYellow -> MatchEventKind.SECOND_YELLOW_RED_CARD
                isRed -> MatchEventKind.RED_CARD
                isYellow -> MatchEventKind.YELLOW_CARD
                isOwnGoal -> MatchEventKind.OWN_GOAL
                isPenalty && scoreVal > 0 -> MatchEventKind.PENALTY_GOAL
                scoreVal > 0 || typeText.contains("Goal", ignoreCase = true) -> MatchEventKind.GOAL
                typeText.contains("Substitution", ignoreCase = true) -> MatchEventKind.SUBSTITUTION
                else -> continue
            }

            if (primaryPlayer.isNotBlank()) {
                events.add(
                    FootballMatchEvent(
                        id = "espn_ev_${i}_${min}_$primaryPlayer",
                        minute = min ?: 0,
                        extraMinute = extra,
                        teamId = "espn_$teamId",
                        isHomeTeam = isHome,
                        kind = kind,
                        primaryPlayerName = primaryPlayer,
                        secondaryPlayerName = secondaryPlayer,
                        detailLabel = typeText
                    )
                )
            }
        }
        return events.sortedWith(compareBy<FootballMatchEvent> { it.minute }.thenBy { it.extraMinute ?: 0 })
    }

    private fun parseEspnSummaryRosters(
        summaryJson: JSONObject,
        fixture: FootballFixture
    ): Pair<TeamLineup?, TeamLineup?> {
        val rostersArr = summaryJson.optJSONArray("rosters") ?: return Pair(null, null)
        var homeLineup: TeamLineup? = null
        var awayLineup: TeamLineup? = null

        for (i in 0 until rostersArr.length()) {
            val rObj = rostersArr.optJSONObject(i) ?: continue
            val homeAway = rObj.optString("homeAway")
            val formation = rObj.optString("formation").ifBlank { "Formation TBC" }
            val rosterEntries = rObj.optJSONArray("roster") ?: continue
            if (rosterEntries.length() == 0) continue

            val starters = mutableListOf<LineupPlayerEntry>()
            val subs = mutableListOf<LineupPlayerEntry>()

            for (p in 0 until rosterEntries.length()) {
                val pObj = rosterEntries.optJSONObject(p) ?: continue
                val athlete = pObj.optJSONObject("athlete") ?: continue
                val pName = athlete.optString("displayName").ifBlank { athlete.optString("fullName") }
                if (pName.isBlank()) continue
                val entry = LineupPlayerEntry(
                    playerId = "espn_p_${athlete.optString("id")}",
                    name = pName,
                    shirtNumber = pObj.optString("jersey").toIntOrNull(),
                    position = pObj.optJSONObject("position")?.optString("abbreviation").orEmpty(),
                    gridPosition = pObj.optString("formationPlace").takeIf { it.isNotBlank() },
                    isCaptain = pObj.optBoolean("captain", false)
                )
                if (pObj.optBoolean("starter", false)) {
                    starters.add(entry)
                } else {
                    subs.add(entry)
                }
            }

            if (starters.isNotEmpty()) {
                val isHome = homeAway == "home"
                val lineup = TeamLineup(
                    teamId = if (isHome) fixture.homeTeam.id else fixture.awayTeam.id,
                    teamName = if (isHome) fixture.homeTeam.name else fixture.awayTeam.name,
                    formation = formation,
                    startingEleven = starters,
                    substitutes = subs
                )
                if (isHome) homeLineup = lineup else awayLineup = lineup
            }
        }
        return Pair(homeLineup, awayLineup)
    }

    private fun parseEspnSummaryKeyEvents(
        summaryJson: JSONObject,
        fixture: FootballFixture
    ): List<FootballMatchEvent> {
        val keyEvents = summaryJson.optJSONArray("keyEvents") ?: return emptyList()
        val parsed = mutableListOf<FootballMatchEvent>()
        val homeEspnId = fixture.homeTeam.espnId

        for (i in 0 until keyEvents.length()) {
            val ev = keyEvents.optJSONObject(i) ?: continue
            val typeText = ev.optJSONObject("type")?.optString("text").orEmpty()
            val clockStr = ev.optJSONObject("clock")?.optString("displayValue").orEmpty()
            val (min, extra) = parseDisplayClockMinutes(clockStr)
            val teamEspnId = ev.optJSONObject("team")?.optString("id").orEmpty()
            val isHome = !homeEspnId.isNullOrBlank() && teamEspnId == homeEspnId

            val participants = ev.optJSONArray("participants")
            val p1 = participants?.optJSONObject(0)
                ?.optJSONObject("athlete")
                ?.optString("displayName")
                .orEmpty()
            val p2 = participants?.optJSONObject(1)
                ?.optJSONObject("athlete")
                ?.optString("displayName")

            val kind = when {
                typeText.contains("Own Goal", ignoreCase = true) -> MatchEventKind.OWN_GOAL
                typeText.contains("Penalty - Scored", ignoreCase = true) -> MatchEventKind.PENALTY_GOAL
                typeText.contains("Goal", ignoreCase = true) -> MatchEventKind.GOAL
                typeText.contains("Second Yellow", ignoreCase = true) -> MatchEventKind.SECOND_YELLOW_RED_CARD
                typeText.contains("Red Card", ignoreCase = true) -> MatchEventKind.RED_CARD
                typeText.contains("Yellow Card", ignoreCase = true) -> MatchEventKind.YELLOW_CARD
                typeText.contains("Substitution", ignoreCase = true) -> MatchEventKind.SUBSTITUTION
                else -> continue
            }

            if (p1.isNotBlank()) {
                parsed.add(
                    FootballMatchEvent(
                        id = "espn_kev_${ev.optString("id", i.toString())}",
                        minute = min ?: 0,
                        extraMinute = extra,
                        teamId = "espn_$teamEspnId",
                        isHomeTeam = isHome,
                        kind = kind,
                        primaryPlayerName = p1,
                        secondaryPlayerName = p2,
                        detailLabel = typeText
                    )
                )
            }
        }
        return parsed.sortedWith(compareBy<FootballMatchEvent> { it.minute }.thenBy { it.extraMinute ?: 0 })
    }

    private fun parseEspnHeadToHead(
        summaryJson: JSONObject,
        comp: SupportedCompetition
    ): List<FootballFixture> {
        val h2hGamesArr = summaryJson.optJSONArray("headToHeadGames")
            ?.optJSONObject(0)
            ?.optJSONArray("events")
            ?: return emptyList()

        val meetings = mutableListOf<FootballFixture>()
        for (i in 0 until h2hGamesArr.length()) {
            val g = h2hGamesArr.optJSONObject(i) ?: continue
            val gameDateMs = parseIsoTimestampMs(g.optString("gameDate"))
            if (gameDateMs <= 0L) continue
            val scoreStr = g.optString("score")
            val scores = scoreStr.split("-").map { it.trim().toIntOrNull() }
            val homeTeamObj = g.optJSONObject("homeTeam") ?: g.optJSONObject("team")
            val awayTeamObj = g.optJSONObject("awayTeam") ?: g.optJSONObject("opponent")
            if (homeTeamObj == null || awayTeamObj == null) continue

            val homeName = homeTeamObj.optString("displayName").ifBlank { homeTeamObj.optString("name", "Home") }
            val awayName = awayTeamObj.optString("displayName").ifBlank { awayTeamObj.optString("name", "Away") }

            meetings.add(
                FootballFixture(
                    id = "espn_h2h_${g.optString("id", i.toString())}",
                    espnEventId = g.optString("id"),
                    competitionId = comp.id,
                    competitionName = g.optJSONObject("league")?.optString("name").orEmpty().ifBlank { comp.displayName },
                    kickoffEpochMs = gameDateMs,
                    status = FootballMatchLifecycle.FINISHED,
                    statusShortLabel = "FT",
                    homeTeam = FootballClubSummary(
                        id = "espn_${homeTeamObj.optString("id")}",
                        espnId = homeTeamObj.optString("id"),
                        name = homeName,
                        shortName = homeName,
                        abbreviation = homeTeamObj.optString("abbreviation").ifBlank { homeName.take(3).uppercase(Locale.UK) },
                        badgeUrl = homeTeamObj.optString("logo")
                    ),
                    awayTeam = FootballClubSummary(
                        id = "espn_${awayTeamObj.optString("id")}",
                        espnId = awayTeamObj.optString("id"),
                        name = awayName,
                        shortName = awayName,
                        abbreviation = awayTeamObj.optString("abbreviation").ifBlank { awayName.take(3).uppercase(Locale.UK) },
                        badgeUrl = awayTeamObj.optString("logo")
                    ),
                    homeScore = g.optInt("homeTeamScore", -1).takeIf { it >= 0 } ?: scores.getOrNull(0),
                    awayScore = g.optInt("awayTeamScore", -1).takeIf { it >= 0 } ?: scores.getOrNull(1)
                )
            )
        }
        return meetings.sortedByDescending { it.kickoffEpochMs }.take(10)
    }

    private fun fetchEspnStandings(competition: SupportedCompetition): List<LeagueStandingRow> {
        val root = getJsonObject(
            "https://site.api.espn.com/apis/v2/sports/soccer/${competition.espnLeagueSlug}/standings"
        ) ?: return emptyList()

        val standingsObj = root.optJSONArray("children")
            ?.optJSONObject(0)
            ?.optJSONObject("standings")
            ?: root.optJSONObject("standings")
            ?: return emptyList()

        val entries = standingsObj.optJSONArray("entries") ?: return emptyList()
        val totalTeams = entries.length()
        val rows = mutableListOf<LeagueStandingRow>()

        for (i in 0 until totalTeams) {
            val entry = entries.optJSONObject(i) ?: continue
            val teamObj = entry.optJSONObject("team") ?: continue
            val espnId = teamObj.optString("id")
            val name = teamObj.optString("displayName").ifBlank { teamObj.optString("name") }
            val shortName = teamObj.optString("shortDisplayName").ifBlank { name }
            val abbr = teamObj.optString("abbreviation").ifBlank { shortName.take(3).uppercase(Locale.UK) }
            val logo = extractEspnLogoUrl(teamObj)
            val colorRaw = teamObj.optString("color").removePrefix("#")
            val colorHex = if (colorRaw.length == 6) "#$colorRaw" else "#1E88E5"

            val statsMap = HashMap<String, Int>()
            var formString = ""
            val statsArr = entry.optJSONArray("stats")
            if (statsArr != null) {
                for (s in 0 until statsArr.length()) {
                    val stat = statsArr.optJSONObject(s) ?: continue
                    val sName = stat.optString("name")
                    if (sName == "overall" || sName == "form") {
                        val disp = stat.optString("displayValue")
                        if (disp.any { it == 'W' || it == 'D' || it == 'L' }) formString = disp
                    }
                    if (stat.has("value")) {
                        statsMap[sName] = stat.optDouble("value", 0.0).toInt()
                    }
                }
            }

            val rank = statsMap["rank"]?.takeIf { it > 0 } ?: (i + 1)
            val played = statsMap["gamesPlayed"] ?: 0
            val won = statsMap["wins"] ?: 0
            val drawn = statsMap["ties"] ?: 0
            val lost = statsMap["losses"] ?: 0
            val gf = statsMap["pointsFor"] ?: 0
            val ga = statsMap["pointsAgainst"] ?: 0
            val gd = statsMap["pointDifferential"] ?: (gf - ga)
            val pts = statsMap["points"] ?: (won * 3 + drawn)

            val noteDesc = entry.optJSONObject("note")?.optString("description").orEmpty()
            val zone = resolveStandingZone(rank, totalTeams, competition, noteDesc)

            val formList = formString
                .filter { it == 'W' || it == 'D' || it == 'L' }
                .takeLast(5)
                .mapNotNull { FormResult.fromChar(it) }

            rows.add(
                LeagueStandingRow(
                    competitionId = competition.id,
                    competitionName = competition.displayName,
                    position = rank,
                    team = FootballClubSummary(
                        id = "espn_$espnId",
                        espnId = espnId,
                        name = name,
                        shortName = shortName,
                        abbreviation = abbr,
                        badgeUrl = logo,
                        primaryColorHex = colorHex
                    ),
                    played = played,
                    won = won,
                    drawn = drawn,
                    lost = lost,
                    goalsFor = gf,
                    goalsAgainst = ga,
                    goalDifference = gd,
                    points = pts,
                    recentForm = formList,
                    zone = zone,
                    zoneDescription = noteDesc.ifBlank { zone.label }
                )
            )
        }
        return rows.sortedBy { it.position }
    }

    private fun fetchEspnTeamRosterAndIssues(
        espnLeagueSlug: String,
        espnTeamId: String,
        teamName: String,
        teamBadgeUrl: String
    ): Pair<List<FootballPlayerStatEntry>, List<PlayerAvailabilityIssue>> {
        val root = getJsonObject(
            "https://site.api.espn.com/apis/site/v2/sports/soccer/$espnLeagueSlug/teams/$espnTeamId/roster"
        ) ?: return Pair(emptyList(), emptyList())

        val athletesArr = root.optJSONArray("athletes") ?: return Pair(emptyList(), emptyList())
        val players = mutableListOf<FootballPlayerStatEntry>()
        val issues = mutableListOf<PlayerAvailabilityIssue>()

        for (i in 0 until athletesArr.length()) {
            val a = athletesArr.optJSONObject(i) ?: continue
            val pid = a.optString("id")
            val name = a.optString("displayName").ifBlank { a.optString("fullName") }
            if (name.isBlank()) continue
            val headshot = a.optJSONObject("headshot")?.optString("href").orEmpty()
            val pos = a.optJSONObject("position")?.optString("displayName")
                .orEmpty()
                .ifBlank { a.optJSONObject("position")?.optString("abbreviation").orEmpty() }
            val jersey = a.optString("jersey").toIntOrNull()
            val age = a.optInt("age", 0).takeIf { it > 0 }
            val nationality = a.optString("citizenship")

            var apps = 0
            var goals = 0
            var assists = 0
            var yellows = 0
            var reds = 0

            val categories = a.optJSONObject("statistics")
                ?.optJSONObject("splits")
                ?.optJSONArray("categories")
            if (categories != null) {
                for (c in 0 until categories.length()) {
                    val stats = categories.optJSONObject(c)?.optJSONArray("stats") ?: continue
                    for (s in 0 until stats.length()) {
                        val st = stats.optJSONObject(s) ?: continue
                        val v = st.optDouble("value", 0.0).toInt()
                        when (st.optString("name")) {
                            "appearances" -> apps = v
                            "totalGoals" -> goals = v
                            "goalAssists" -> assists = v
                            "yellowCards" -> yellows = v
                            "redCards" -> reds = v
                        }
                    }
                }
            }

            players.add(
                FootballPlayerStatEntry(
                    playerId = "espn_p_$pid",
                    name = name,
                    photoUrl = headshot,
                    teamId = "espn_$espnTeamId",
                    teamName = teamName,
                    teamBadgeUrl = teamBadgeUrl,
                    position = pos,
                    shirtNumber = jersey,
                    age = age,
                    nationality = nationality,
                    appearances = apps,
                    goals = goals,
                    assists = assists,
                    yellowCards = yellows,
                    redCards = reds
                )
            )

            val injuriesArr = a.optJSONArray("injuries")
            if (injuriesArr != null) {
                for (k in 0 until injuriesArr.length()) {
                    val inj = injuriesArr.optJSONObject(k) ?: continue
                    val status = inj.optString("status").ifBlank { "Unavailable" }
                    val detailsObj = inj.optJSONObject("details")
                    val detailText = detailsObj?.optString("detail")
                        .orEmpty()
                        .ifBlank { detailsObj?.optString("type").orEmpty() }
                        .ifBlank { status }
                    val returnDate = detailsObj?.optString("returnDate").orEmpty()
                    val isSusp = status.contains("Suspen", ignoreCase = true) ||
                            detailText.contains("Suspen", ignoreCase = true) ||
                            detailText.contains("Card", ignoreCase = true)

                    issues.add(
                        PlayerAvailabilityIssue(
                            id = "espn_inj_${pid}_$k",
                            playerId = "espn_p_$pid",
                            playerName = name,
                            playerPhotoUrl = headshot,
                            teamId = "espn_$espnTeamId",
                            teamName = teamName,
                            isSuspension = isSusp,
                            reason = detailText,
                            statusLabel = status,
                            expectedReturn = returnDate
                        )
                    )
                }
            }
        }

        return Pair(players, issues)
    }

    private fun mapApiFootballFixture(
        dto: ApiFootballFixtureDto,
        comp: SupportedCompetition
    ): FootballFixture? {
        val meta = dto.fixture ?: return null
        val homeDto = dto.teams?.home ?: return null
        val awayDto = dto.teams?.away ?: return null
        val kickoffMs = meta.timestampSeconds * 1000L
        if (kickoffMs <= 0L) return null

        val shortCode = meta.status?.shortCode.orEmpty()
        val lifecycle = when (shortCode) {
            "1H" -> FootballMatchLifecycle.LIVE_FIRST_HALF
            "HT" -> FootballMatchLifecycle.HALF_TIME
            "2H" -> FootballMatchLifecycle.LIVE_SECOND_HALF
            "ET", "BT" -> FootballMatchLifecycle.EXTRA_TIME
            "P" -> FootballMatchLifecycle.PENALTIES
            "FT", "AET", "PEN" -> FootballMatchLifecycle.FINISHED
            "PST" -> FootballMatchLifecycle.POSTPONED
            "CANC", "ABD" -> FootballMatchLifecycle.CANCELLED
            "SUSP", "INT" -> FootballMatchLifecycle.SUSPENDED
            else -> FootballMatchLifecycle.SCHEDULED
        }

        val lineups = dto.lineups.orEmpty()
        val homeLineup = lineups.getOrNull(0)?.let { mapApiFootballLineup(it) }
        val awayLineup = lineups.getOrNull(1)?.let { mapApiFootballLineup(it) }
        val events = mapApiFootballEvents(dto.events.orEmpty(), homeDto.id)

        return FootballFixture(
            id = "af_${meta.id}",
            apiFootballFixtureId = meta.id,
            espnEventId = null,
            competitionId = comp.id,
            competitionName = dto.league?.name ?: comp.displayName,
            competitionRound = dto.league?.round.orEmpty(),
            kickoffEpochMs = kickoffMs,
            status = lifecycle,
            statusShortLabel = shortCode.ifBlank { meta.status?.longLabel.orEmpty() },
            matchMinute = meta.status?.elapsed,
            stoppageMinute = meta.status?.extra,
            homeTeam = FootballClubSummary(
                id = "af_${homeDto.id}",
                apiFootballId = homeDto.id,
                name = homeDto.name.orEmpty(),
                shortName = homeDto.name.orEmpty(),
                abbreviation = homeDto.code ?: homeDto.name.orEmpty().take(3).uppercase(Locale.UK),
                badgeUrl = homeDto.logo.orEmpty()
            ),
            awayTeam = FootballClubSummary(
                id = "af_${awayDto.id}",
                apiFootballId = awayDto.id,
                name = awayDto.name.orEmpty(),
                shortName = awayDto.name.orEmpty(),
                abbreviation = awayDto.code ?: awayDto.name.orEmpty().take(3).uppercase(Locale.UK),
                badgeUrl = awayDto.logo.orEmpty()
            ),
            homeScore = dto.goals?.home,
            awayScore = dto.goals?.away,
            stadiumName = meta.venue?.name.orEmpty(),
            stadiumCity = meta.venue?.city.orEmpty(),
            refereeName = meta.referee.orEmpty(),
            broadcast = BroadcastInfo(mainChannel = defaultUkCompetitionBroadcaster(comp)),
            lineupState = computeLineupState(kickoffMs, lifecycle, homeLineup, awayLineup),
            homeLineup = homeLineup,
            awayLineup = awayLineup,
            events = events
        )
    }

    private fun mapApiFootballLineup(dto: ApiFootballLineupDto): TeamLineup {
        val teamId = dto.team?.id ?: 0
        return TeamLineup(
            teamId = "af_$teamId",
            teamName = dto.team?.name.orEmpty(),
            formation = dto.formation.orEmpty().ifBlank { "Formation TBC" },
            coachName = dto.coach?.name.orEmpty(),
            startingEleven = dto.startXI.orEmpty().mapNotNull { slot ->
                val p = slot.player ?: return@mapNotNull null
                LineupPlayerEntry(
                    playerId = "af_p_${p.id ?: 0}",
                    name = p.name.orEmpty(),
                    shirtNumber = p.number,
                    position = p.pos.orEmpty(),
                    gridPosition = p.grid
                )
            },
            substitutes = dto.substitutes.orEmpty().mapNotNull { slot ->
                val p = slot.player ?: return@mapNotNull null
                LineupPlayerEntry(
                    playerId = "af_p_${p.id ?: 0}",
                    name = p.name.orEmpty(),
                    shirtNumber = p.number,
                    position = p.pos.orEmpty(),
                    gridPosition = p.grid
                )
            }
        )
    }

    private fun mapApiFootballEvents(
        events: List<ApiFootballEventDto>,
        homeApiTeamId: Int?
    ): List<FootballMatchEvent> {
        return events.mapIndexedNotNull { idx, ev ->
            val pName = ev.player?.name?.trim().orEmpty()
            if (pName.isBlank()) return@mapIndexedNotNull null
            val type = ev.type.orEmpty()
            val detail = ev.detail.orEmpty()
            val kind = when {
                type.equals("Goal", ignoreCase = true) && detail.contains("Own", ignoreCase = true) -> MatchEventKind.OWN_GOAL
                type.equals("Goal", ignoreCase = true) && detail.contains("Penalty", ignoreCase = true) -> MatchEventKind.PENALTY_GOAL
                type.equals("Goal", ignoreCase = true) && detail.contains("Missed", ignoreCase = true) -> MatchEventKind.MISSED_PENALTY
                type.equals("Goal", ignoreCase = true) -> MatchEventKind.GOAL
                type.equals("Card", ignoreCase = true) && detail.contains("Second Yellow", ignoreCase = true) -> MatchEventKind.SECOND_YELLOW_RED_CARD
                type.equals("Card", ignoreCase = true) && detail.contains("Red", ignoreCase = true) -> MatchEventKind.RED_CARD
                type.equals("Card", ignoreCase = true) -> MatchEventKind.YELLOW_CARD
                type.equals("subst", ignoreCase = true) -> MatchEventKind.SUBSTITUTION
                type.equals("Var", ignoreCase = true) -> MatchEventKind.VAR
                else -> return@mapIndexedNotNull null
            }
            FootballMatchEvent(
                id = "af_ev_${idx}_${ev.time?.elapsed ?: 0}",
                minute = ev.time?.elapsed ?: 0,
                extraMinute = ev.time?.extra,
                teamId = "af_${ev.team?.id ?: 0}",
                isHomeTeam = homeApiTeamId != null && ev.team?.id == homeApiTeamId,
                kind = kind,
                primaryPlayerName = pName,
                secondaryPlayerName = ev.assist?.name,
                detailLabel = detail
            )
        }
    }

    private fun mapApiFootballStanding(
        dto: ApiFootballStandingDto,
        competition: SupportedCompetition,
        totalTeams: Int
    ): LeagueStandingRow? {
        val team = dto.team ?: return null
        val desc = dto.description.orEmpty()
        val zone = resolveStandingZone(dto.rank, totalTeams, competition, desc)
        val formList = dto.form.orEmpty()
            .takeLast(5)
            .mapNotNull { FormResult.fromChar(it) }

        return LeagueStandingRow(
            competitionId = competition.id,
            competitionName = competition.displayName,
            position = dto.rank,
            team = FootballClubSummary(
                id = "af_${team.id}",
                apiFootballId = team.id,
                name = team.name.orEmpty(),
                shortName = team.name.orEmpty(),
                abbreviation = team.code ?: team.name.orEmpty().take(3).uppercase(Locale.UK),
                badgeUrl = team.logo.orEmpty()
            ),
            played = dto.all?.played ?: 0,
            won = dto.all?.win ?: 0,
            drawn = dto.all?.draw ?: 0,
            lost = dto.all?.lose ?: 0,
            goalsFor = dto.all?.goals?.goalsFor ?: 0,
            goalsAgainst = dto.all?.goals?.goalsAgainst ?: 0,
            goalDifference = dto.goalsDiff,
            points = dto.points,
            recentForm = formList,
            zone = zone,
            zoneDescription = desc.ifBlank { zone.label }
        )
    }

    private fun mapApiFootballPlayerStat(dto: ApiFootballPlayerStatsResponseDto): FootballPlayerStatEntry? {
        val bio = dto.player ?: return null
        val stat = dto.statistics?.firstOrNull()
        return FootballPlayerStatEntry(
            playerId = "af_p_${bio.id}",
            name = bio.name.orEmpty(),
            photoUrl = bio.photo.orEmpty(),
            teamId = "af_${stat?.team?.id ?: 0}",
            teamName = stat?.team?.name.orEmpty(),
            teamBadgeUrl = stat?.team?.logo.orEmpty(),
            position = stat?.games?.position.orEmpty(),
            shirtNumber = stat?.games?.number,
            age = bio.age,
            nationality = bio.nationality.orEmpty(),
            appearances = stat?.games?.appearances ?: 0,
            goals = stat?.goals?.total ?: 0,
            assists = stat?.goals?.assists ?: 0,
            yellowCards = stat?.cards?.yellow ?: 0,
            redCards = stat?.cards?.red ?: 0
        )
    }

    private fun mergeFixtureFeeds(
        primary: List<FootballFixture>,
        broadcastSource: List<FootballFixture>
    ): List<FootballFixture> {
        return primary.map { afFix ->
            val matchedEspn = broadcastSource.firstOrNull { espnFix ->
                espnFix.competitionId == afFix.competitionId &&
                        abs(espnFix.kickoffEpochMs - afFix.kickoffEpochMs) <= 6 * 3600_000L &&
                        clubsMatch(afFix.homeTeam.name, espnFix.homeTeam.name) &&
                        clubsMatch(afFix.awayTeam.name, espnFix.awayTeam.name)
            }
            if (matchedEspn != null) {
                afFix.copy(
                    espnEventId = matchedEspn.espnEventId,
                    broadcast = matchedEspn.broadcast,
                    homeTeam = afFix.homeTeam.copy(
                        espnId = matchedEspn.homeTeam.espnId,
                        primaryColorHex = matchedEspn.homeTeam.primaryColorHex
                    ),
                    awayTeam = afFix.awayTeam.copy(
                        espnId = matchedEspn.awayTeam.espnId,
                        primaryColorHex = matchedEspn.awayTeam.primaryColorHex
                    )
                )
            } else {
                afFix
            }
        }
    }

    private fun resolveStandingZone(
        rank: Int,
        totalTeams: Int,
        comp: SupportedCompetition,
        providerDescription: String
    ): StandingZone {
        val d = providerDescription.lowercase(Locale.UK)
        if (d.contains("relegation")) return StandingZone.RELEGATION
        if (d.contains("champions league")) return StandingZone.CHAMPIONS_LEAGUE
        if (d.contains("europa") || d.contains("conference")) return StandingZone.EUROPEAN
        if (d.contains("promotion") && !d.contains("play")) return StandingZone.PROMOTION
        if (d.contains("playoff") || d.contains("play-off")) return StandingZone.PLAYOFF

        if (comp.relegationSpots > 0 && totalTeams > comp.relegationSpots && rank > totalTeams - comp.relegationSpots) {
            return StandingZone.RELEGATION
        }
        if (comp.championsLeagueSpots > 0 && rank <= comp.championsLeagueSpots) {
            return StandingZone.CHAMPIONS_LEAGUE
        }
        if (comp.europeanSpots > 0 && rank <= comp.europeanSpots) {
            return StandingZone.EUROPEAN
        }
        if (comp.promotionSpots > 0 && rank <= comp.promotionSpots) {
            return StandingZone.PROMOTION
        }
        if (comp.playoffSpots > 0 && rank <= comp.playoffSpots) {
            return StandingZone.PLAYOFF
        }
        return StandingZone.NONE
    }

    private fun computeLineupState(
        kickoffEpochMs: Long,
        status: FootballMatchLifecycle,
        homeLineup: TeamLineup?,
        awayLineup: TeamLineup?
    ): LineupAvailabilityState {
        if ((homeLineup != null && homeLineup.startingEleven.isNotEmpty()) ||
            (awayLineup != null && awayLineup.startingEleven.isNotEmpty()) ||
            status.isLive ||
            status.isFinished
        ) {
            return LineupAvailabilityState.AVAILABLE
        }
        val minutesUntilKickoff = (kickoffEpochMs - System.currentTimeMillis()) / 60_000L
        return if (minutesUntilKickoff in 0..95) {
            LineupAvailabilityState.EXPECTED_SOON
        } else {
            LineupAvailabilityState.NOT_AVAILABLE
        }
    }

    private fun fetchAndParseRssFeed(
        sourceName: String,
        feedUrl: String,
        favouriteClubNames: List<String>,
        competitions: List<SupportedCompetition>
    ): List<FootballNewsArticle> {
        val xmlText = httpGetText(feedUrl) ?: return emptyList()
        val articles = mutableListOf<FootballNewsArticle>()

        runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xmlText))

            var insideItem = false
            var title = ""
            var description = ""
            var link = ""
            var pubDate = ""
            var imageUrl = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase(Locale.UK)
                        if (tag == "item") {
                            insideItem = true
                            title = ""
                            description = ""
                            link = ""
                            pubDate = ""
                            imageUrl = ""
                        } else if (insideItem) {
                            when (tag) {
                                "title" -> title = parser.nextText().trim()
                                "description" -> description = stripHtmlTags(parser.nextText()).trim()
                                "link" -> link = parser.nextText().trim()
                                "pubdate" -> pubDate = parser.nextText().trim()
                                "thumbnail", "content" -> {
                                    val attrUrl = parser.getAttributeValue(null, "url")
                                    if (!attrUrl.isNullOrBlank() && imageUrl.isBlank()) {
                                        imageUrl = attrUrl
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("item", ignoreCase = true) && insideItem) {
                            insideItem = false
                            if (title.isNotBlank() && link.isNotBlank()) {
                                val combined = "$title $description"
                                val matchedClubs = favouriteClubNames.filter { club ->
                                    combined.contains(club, ignoreCase = true)
                                }
                                val category = classifyNewsCategory(combined, matchedClubs.isNotEmpty(), competitions)
                                articles.add(
                                    FootballNewsArticle(
                                        id = "news_${link.hashCode()}",
                                        title = title,
                                        summary = description.take(240),
                                        sourceName = sourceName,
                                        articleUrl = link,
                                        imageUrl = imageUrl,
                                        publishedEpochMs = parseRfc822DateMs(pubDate),
                                        category = category,
                                        relatedTeamNames = matchedClubs,
                                        involvesFavouriteTeam = matchedClubs.isNotEmpty()
                                    )
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        }
        return articles
    }

    private fun classifyNewsCategory(
        combinedText: String,
        matchesClub: Boolean,
        competitions: List<SupportedCompetition>
    ): FootballNewsCategory {
        val lower = combinedText.lowercase(Locale.UK)
        return when {
            lower.contains("transfer") || lower.contains("sign") || lower.contains("bid") ||
                    lower.contains("contract") || lower.contains("loan") || lower.contains("deal") ->
                FootballNewsCategory.TRANSFER_NEWS
            lower.contains("injur") || lower.contains("hamstring") || lower.contains("fitness") ||
                    lower.contains("ruled out") || lower.contains("sidelined") || lower.contains("scan") ->
                FootballNewsCategory.INJURY_NEWS
            lower.contains("manager") || lower.contains("head coach") || lower.contains("sacked") ||
                    lower.contains("boss") || lower.contains("appointed") ->
                FootballNewsCategory.MANAGER_NEWS
            matchesClub -> FootballNewsCategory.CLUB_NEWS
            competitions.any { lower.contains(it.displayName.lowercase(Locale.UK)) } ->
                FootballNewsCategory.COMPETITION_NEWS
            else -> FootballNewsCategory.CLUB_NEWS
        }
    }

    private fun extractEspnLogoUrl(teamObj: JSONObject): String {
        val direct = teamObj.optString("logo")
        if (direct.isNotBlank()) return direct
        val logos = teamObj.optJSONArray("logos") ?: return ""
        return logos.optJSONObject(0)?.optString("href").orEmpty()
    }

    private fun mapEspnLifecycle(
        stateStr: String,
        typeName: String,
        shortDetail: String
    ): FootballMatchLifecycle {
        val uName = typeName.uppercase(Locale.UK)
        val uDetail = shortDetail.uppercase(Locale.UK)
        return when {
            uName.contains("HALFTIME") || uDetail == "HT" -> FootballMatchLifecycle.HALF_TIME
            uName.contains("POSTPONED") -> FootballMatchLifecycle.POSTPONED
            uName.contains("CANCELED") || uName.contains("CANCELLED") || uName.contains("ABANDONED") ->
                FootballMatchLifecycle.CANCELLED
            uName.contains("SUSPENDED") -> FootballMatchLifecycle.SUSPENDED
            (uName.contains("SHOOTOUT") || uDetail.contains("PEN")) && stateStr == "in" ->
                FootballMatchLifecycle.PENALTIES
            uName.contains("EXTRA") && stateStr == "in" -> FootballMatchLifecycle.EXTRA_TIME
            stateStr == "in" && uDetail.startsWith("1") -> FootballMatchLifecycle.LIVE_FIRST_HALF
            stateStr == "in" -> FootballMatchLifecycle.LIVE_SECOND_HALF
            stateStr == "post" -> FootballMatchLifecycle.FINISHED
            else -> FootballMatchLifecycle.SCHEDULED
        }
    }

    private fun parseDisplayClockMinutes(displayClock: String): Pair<Int?, Int?> {
        val cleaned = displayClock.replace("'", "").trim()
        if (cleaned.isBlank()) return Pair(null, null)
        return if (cleaned.contains("+")) {
            val parts = cleaned.split("+")
            Pair(parts.getOrNull(0)?.trim()?.toIntOrNull(), parts.getOrNull(1)?.trim()?.toIntOrNull())
        } else {
            Pair(cleaned.toIntOrNull(), null)
        }
    }

    private fun parseIsoTimestampMs(raw: String): Long {
        if (raw.isBlank()) return 0L
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrElse {
            runCatching {
                ZonedDateTime.parse(raw, DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli()
            }.getOrElse {
                runCatching {
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US)
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    sdf.parse(raw)?.time ?: 0L
                }.getOrDefault(0L)
            }
        }
    }

    private fun parseRfc822DateMs(raw: String): Long {
        if (raw.isBlank()) return System.currentTimeMillis()
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm zzz",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in formats) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.ENGLISH).parse(raw)?.time
            }.getOrNull()
            if (parsed != null && parsed > 0L) return parsed
        }
        return System.currentTimeMillis()
    }

    private fun stripHtmlTags(input: String): String {
        return input
            .replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace(Regex("\\s+"), " ")
    }

    private fun mapWmoWeatherCode(code: Int): String = when (code) {
        0 -> "Clear Sky"
        1, 2 -> "Partly Cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Light Drizzle"
        61, 63 -> "Rain"
        65, 80, 81, 82 -> "Heavy Rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Thunderstorm"
        else -> "Fair"
    }

    private fun getJsonObject(urlString: String): JSONObject? {
        val body = httpGetText(urlString) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun httpGetText(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 12_000
                setRequestProperty("User-Agent", "ClaraSmartHome/3.0 (Android; WallTablet)")
                setRequestProperty("Accept", "application/json, application/rss+xml, text/xml, */*")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}

/**
 * RulesEngine implementation per Football Hub v3.0 JSON specification ("provider": "RulesEngine").
 * Evaluates all 7 supported rules against live match, fixture, lineup, and table data.
 */
object FootballInsightRulesEngine {

    fun evaluateInsights(
        primaryTeam: FavouriteTeamEntry?,
        favouriteTeams: List<FavouriteTeamEntry>,
        liveMatches: List<FootballFixture>,
        upcomingFixtures: List<FootballFixture>,
        standingsByCompetition: Map<String, List<LeagueStandingRow>>,
        nowEpochMs: Long = System.currentTimeMillis()
    ): List<FootballInsight> {
        val insights = mutableListOf<FootballInsight>()
        val zoneId = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()

        // Rule 1: Live Match Status (Highest priority)
        val priorityLiveMatch = liveMatches.firstOrNull { it.involvesPrimaryFavourite }
            ?: liveMatches.firstOrNull { it.involvesFavouriteTeam }
            ?: liveMatches.firstOrNull()
        if (priorityLiveMatch != null) {
            val score = "${priorityLiveMatch.homeScore ?: 0}-${priorityLiveMatch.awayScore ?: 0}"
            insights.add(
                FootballInsight(
                    id = "rule_live_${priorityLiveMatch.id}",
                    ruleType = FootballInsightRuleType.LIVE_MATCH_STATUS,
                    headline = "${priorityLiveMatch.homeTeam.shortName} $score ${priorityLiveMatch.awayTeam.shortName} (${priorityLiveMatch.formattedLiveMinute}) on ${priorityLiveMatch.broadcast.displayChannel}",
                    supportingDetail = priorityLiveMatch.competitionName,
                    priority = 100,
                    fixtureId = priorityLiveMatch.id
                )
            )
        }

        val targetNext = upcomingFixtures.firstOrNull { it.involvesPrimaryFavourite }
            ?: upcomingFixtures.firstOrNull { it.involvesFavouriteTeam }
            ?: upcomingFixtures.firstOrNull()

        if (targetNext != null) {
            val minutesToKickoff = (targetNext.kickoffEpochMs - nowEpochMs) / 60_000L

            // Rule 2: Lineup Availability
            if (targetNext.lineupState == LineupAvailabilityState.AVAILABLE) {
                insights.add(
                    FootballInsight(
                        id = "rule_lineup_ready_${targetNext.id}",
                        ruleType = FootballInsightRuleType.LINEUP_AVAILABILITY,
                        headline = "Starting lineups confirmed for ${targetNext.homeTeam.shortName} vs ${targetNext.awayTeam.shortName}",
                        supportingDetail = "Kickoff on ${targetNext.broadcast.displayChannel}",
                        priority = 95,
                        fixtureId = targetNext.id
                    )
                )
            } else if (minutesToKickoff in 61..125) {
                val minsUntilLineup = (minutesToKickoff - 60L).coerceAtLeast(5L)
                insights.add(
                    FootballInsight(
                        id = "rule_lineup_soon_${targetNext.id}",
                        ruleType = FootballInsightRuleType.LINEUP_AVAILABILITY,
                        headline = "Lineups expected in $minsUntilLineup minutes",
                        supportingDetail = "${targetNext.homeTeam.shortName} vs ${targetNext.awayTeam.shortName}",
                        priority = 90,
                        fixtureId = targetNext.id
                    )
                )
            }

            // Rule 3: Upcoming Fixture
            val kickoffZdt = Instant.ofEpochMilli(targetNext.kickoffEpochMs).atZone(zoneId)
            val kickoffDate = kickoffZdt.toLocalDate()
            val timeStr = kickoffZdt.format(DateTimeFormatter.ofPattern("HH:mm"))
            val clubFocusName = when {
                primaryTeam != null && HybridLiveFootballDataProvider.clubsMatch(targetNext.homeTeam.name, primaryTeam.name) ->
                    primaryTeam.name
                primaryTeam != null && HybridLiveFootballDataProvider.clubsMatch(targetNext.awayTeam.name, primaryTeam.name) ->
                    primaryTeam.name
                else -> targetNext.homeTeam.shortName
            }
            val whenPhrase = when {
                kickoffDate == today -> "play today at $timeStr"
                kickoffDate == today.plusDays(1) -> "play tomorrow at $timeStr"
                else -> "play ${kickoffZdt.format(DateTimeFormatter.ofPattern("EEEE HH:mm"))}"
            }
            insights.add(
                FootballInsight(
                    id = "rule_upcoming_${targetNext.id}",
                    ruleType = FootballInsightRuleType.UPCOMING_FIXTURE,
                    headline = "$clubFocusName $whenPhrase on ${targetNext.broadcast.displayChannel}",
                    supportingDetail = targetNext.competitionName,
                    priority = 85,
                    fixtureId = targetNext.id
                )
            )
        }

        // Weekly match density check for primary club
        if (primaryTeam != null) {
            val endOfWeekMs = nowEpochMs + 7 * 24 * 3600_000L
            val matchesThisWeek = upcomingFixtures.count { fix ->
                fix.kickoffEpochMs in nowEpochMs..EditEndWindow(endOfWeekMs) &&
                        (HybridLiveFootballDataProvider.clubsMatch(fix.homeTeam.name, primaryTeam.name) ||
                                HybridLiveFootballDataProvider.clubsMatch(fix.awayTeam.name, primaryTeam.name))
            }
            if (matchesThisWeek >= 2) {
                insights.add(
                    FootballInsight(
                        id = "rule_week_count_${primaryTeam.teamId}",
                        ruleType = FootballInsightRuleType.UPCOMING_FIXTURE,
                        headline = "${numberWord(matchesThisWeek)} ${primaryTeam.name} matches this week",
                        supportingDetail = "Busy fixture schedule ahead",
                        priority = 72,
                        teamId = primaryTeam.teamId
                    )
                )
            }
        }

        // Standings-based rules: League Position, Qualification, Relegation Risk, Form Trend
        val focusClub = primaryTeam ?: favouriteTeams.firstOrNull()
        if (focusClub != null) {
            val allRows = standingsByCompetition.values.flatten()
            val clubRow = allRows.firstOrNull {
                HybridLiveFootballDataProvider.clubsMatch(it.team.name, focusClub.name)
            }
            if (clubRow != null) {
                val table = standingsByCompetition[clubRow.competitionId].orEmpty()

                // Rule 4: League Position
                val posHeadline = if (clubRow.position == 1) {
                    "${focusClub.name} currently top of the ${clubRow.competitionName} table"
                } else {
                    "${focusClub.name} sit ${ordinal(clubRow.position)} in ${clubRow.competitionName} (${clubRow.points} pts)"
                }
                insights.add(
                    FootballInsight(
                        id = "rule_pos_${focusClub.teamId}",
                        ruleType = FootballInsightRuleType.LEAGUE_POSITION,
                        headline = posHeadline,
                        supportingDetail = "Played ${clubRow.played} • GD ${if (clubRow.goalDifference >= 0) "+${clubRow.goalDifference}" else "${clubRow.goalDifference}"}",
                        priority = 80,
                        teamId = focusClub.teamId
                    )
                )

                // Rule 5: Competition Qualification
                if (clubRow.zone == StandingZone.CHAMPIONS_LEAGUE ||
                    clubRow.zone == StandingZone.EUROPEAN ||
                    clubRow.zone == StandingZone.PROMOTION ||
                    clubRow.zone == StandingZone.PLAYOFF
                ) {
                    insights.add(
                        FootballInsight(
                            id = "rule_qual_${focusClub.teamId}",
                            ruleType = FootballInsightRuleType.COMPETITION_QUALIFICATION,
                            headline = "${focusClub.name} occupying ${clubRow.zone.label} spot (${ordinal(clubRow.position)})",
                            supportingDetail = clubRow.competitionName,
                            priority = 76,
                            teamId = focusClub.teamId
                        )
                    )
                }

                // Rule 6: Relegation Risk
                val firstRelRow = table.firstOrNull { it.zone == StandingZone.RELEGATION }
                if (clubRow.zone == StandingZone.RELEGATION) {
                    val safetyRow = table.lastOrNull { it.zone != StandingZone.RELEGATION && it.position < clubRow.position }
                    val gap = ((safetyRow?.points ?: clubRow.points) - clubRow.points).coerceAtLeast(0)
                    insights.add(
                        FootballInsight(
                            id = "rule_rel_in_${focusClub.teamId}",
                            ruleType = FootballInsightRuleType.RELEGATION_RISK,
                            headline = "${focusClub.name} in relegation zone ($gap pts from safety)",
                            supportingDetail = clubRow.competitionName,
                            priority = 82,
                            teamId = focusClub.teamId
                        )
                    )
                } else if (firstRelRow != null && (clubRow.points - firstRelRow.points) in 0..4 && clubRow.played >= 6) {
                    val cushion = clubRow.points - firstRelRow.points
                    insights.add(
                        FootballInsight(
                            id = "rule_rel_near_${focusClub.teamId}",
                            ruleType = FootballInsightRuleType.RELEGATION_RISK,
                            headline = "${focusClub.name} only $cushion pts above relegation places",
                            supportingDetail = clubRow.competitionName,
                            priority = 78,
                            teamId = focusClub.teamId
                        )
                    )
                }

                // Rule 7: Form Trend
                if (clubRow.recentForm.size >= 4) {
                    val wins = clubRow.recentForm.count { it == FormResult.WIN }
                    val losses = clubRow.recentForm.count { it == FormResult.LOSS }
                    val draws = clubRow.recentForm.count { it == FormResult.DRAW }
                    if (losses == 0 && wins >= 3) {
                        insights.add(
                            FootballInsight(
                                id = "rule_form_hot_${focusClub.teamId}",
                                ruleType = FootballInsightRuleType.FORM_TREND,
                                headline = "${focusClub.name} unbeaten in last ${clubRow.recentForm.size} (${wins}W ${draws}D)",
                                supportingDetail = "Strong recent form trend",
                                priority = 74,
                                teamId = focusClub.teamId
                            )
                        )
                    } else if (wins == 0 && losses >= 3) {
                        insights.add(
                            FootballInsight(
                                id = "rule_form_cold_${focusClub.teamId}",
                                ruleType = FootballInsightRuleType.FORM_TREND,
                                headline = "${focusClub.name} winless in last ${clubRow.recentForm.size} league matches",
                                supportingDetail = "Searching for a turnaround",
                                priority = 70,
                                teamId = focusClub.teamId
                            )
                        )
                    }
                }
            }
        }

        return insights.sortedByDescending { it.priority }
    }

    private fun EditEndWindow(endEpochMs: Long): Long = endEpochMs

    fun ordinal(n: Int): String {
        if (n % 100 in 11..13) return "${n}th"
        return when (n % 10) {
            1 -> "${n}st"
            2 -> "${n}nd"
            3 -> "${n}rd"
            else -> "${n}th"
        }
    }

    private fun numberWord(n: Int): String = when (n) {
        2 -> "Two"
        3 -> "Three"
        4 -> "Four"
        else -> n.toString()
    }
}