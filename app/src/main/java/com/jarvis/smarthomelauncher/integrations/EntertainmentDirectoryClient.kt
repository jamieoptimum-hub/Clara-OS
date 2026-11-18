package com.jarvis.smarthomelauncher.integrations

import android.content.Context
import com.jarvis.smarthomelauncher.core.ClaraEntertainmentStore
import com.jarvis.smarthomelauncher.core.EntertainmentCastMember
import com.jarvis.smarthomelauncher.core.EntertainmentEpisode
import com.jarvis.smarthomelauncher.core.EntertainmentMediaItem
import com.jarvis.smarthomelauncher.core.EntertainmentMediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class EntertainmentDiscoverFeed(
    val trending: List<EntertainmentMediaItem> = emptyList(),
    val upcomingMovies: List<EntertainmentMediaItem> = emptyList(),
    val becauseYouWatchedTitle: String = "",
    val becauseYouWatched: List<EntertainmentMediaItem> = emptyList()
)

object EntertainmentDirectoryClient {
    private const val TMDB_BASE = "https://api.themoviedb.org/3"
    private const val TMDB_IMG_POSTER = "https://image.tmdb.org/t/p/w500"
    private const val TMDB_IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"
    private const val TVMAZE_BASE = "https://api.tvmaze.com"

    suspend fun searchMedia(context: Context, rawQuery: String): List<EntertainmentMediaItem> = coroutineScope {
        val query = rawQuery.trim()
        if (query.length < 2) return@coroutineScope emptyList()

        val tmdbKey = ClaraEntertainmentStore.getTmdbApiKey(context)
        if (tmdbKey.isNotBlank()) {
            val tmdbResults = searchTmdbMulti(query, tmdbKey)
            if (tmdbResults.isNotEmpty()) return@coroutineScope tmdbResults
        }

        val tvDeferred = async { searchTvMazeShows(query) }
        val movieDeferred = async { searchItunesMoviesUk(query) }
        (tvDeferred.await() + movieDeferred.await()).take(24)
    }

    suspend fun hydrateItemDetails(
        context: Context,
        seed: EntertainmentMediaItem
    ): EntertainmentMediaItem = withContext(Dispatchers.IO) {
        val tmdbKey = ClaraEntertainmentStore.getTmdbApiKey(context)
        if (tmdbKey.isNotBlank() && seed.tmdbId > 0) {
            return@withContext if (seed.kind == EntertainmentMediaKind.MOVIE) {
                hydrateTmdbMovie(seed, tmdbKey)
            } else {
                hydrateTmdbTvShow(seed, tmdbKey)
            }
        }

        if (seed.kind == EntertainmentMediaKind.TV_SHOW && seed.tvmazeId > 0) {
            return@withContext hydrateTvMazeShow(seed)
        }
        if (seed.kind == EntertainmentMediaKind.TV_SHOW) {
            val matched = searchTvMazeShows(seed.title).firstOrNull()
            if (matched != null && matched.tvmazeId > 0) {
                return@withContext hydrateTvMazeShow(seed.copy(tvmazeId = matched.tvmazeId))
            }
        }
        seed.copy(lastSyncedEpochMs = System.currentTimeMillis())
    }

    suspend fun refreshTrackedSchedule(
        context: Context,
        items: List<EntertainmentMediaItem>
    ): List<EntertainmentMediaItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val staleThresholdMs = 3 * 60 * 60 * 1000L
        items.mapNotNull { item ->
            if (now - item.lastSyncedEpochMs < staleThresholdMs && item.episodes.isNotEmpty()) {
                null
            } else {
                runCatching { hydrateItemDetails(context, item) }.getOrNull()
            }
        }
    }

    suspend fun fetchDiscoverFeed(
        context: Context,
        trackedItems: List<EntertainmentMediaItem>
    ): EntertainmentDiscoverFeed = coroutineScope {
        val tmdbKey = ClaraEntertainmentStore.getTmdbApiKey(context)
        if (tmdbKey.isNotBlank()) {
            val trendingDef = async { fetchTmdbList("$TMDB_BASE/trending/all/week?language=en-GB", tmdbKey) }
            val moviesDef = async { fetchTmdbList("$TMDB_BASE/movie/upcoming?language=en-GB&region=GB", tmdbKey, EntertainmentMediaKind.MOVIE) }
            val anchor = trackedItems.filter { it.tmdbId > 0 }.maxByOrNull { it.lastWatchedEpochMs }
                ?: trackedItems.firstOrNull { it.tmdbId > 0 }
            val recsDef = async {
                if (anchor != null) {
                    val path = if (anchor.kind == EntertainmentMediaKind.MOVIE) "movie" else "tv"
                    fetchTmdbList("$TMDB_BASE/$path/${anchor.tmdbId}/recommendations?language=en-GB", tmdbKey, anchor.kind)
                } else emptyList()
            }
            return@coroutineScope EntertainmentDiscoverFeed(
                trending = trendingDef.await(),
                upcomingMovies = moviesDef.await(),
                becauseYouWatchedTitle = anchor?.title.orEmpty(),
                becauseYouWatched = recsDef.await()
            )
        }

        val ukScheduleDef = async { fetchTvMazeUkTrending() }
        val ukMoviesDef = async { fetchItunesTopUkMovies() }
        val topGenres = trackedItems.flatMap { it.genres }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }.take(2).toSet()
        val trending = ukScheduleDef.await()
        val genreMatches = if (topGenres.isNotEmpty()) {
            trending.filter { candidate ->
                trackedItems.none { it.title.equals(candidate.title, ignoreCase = true) } &&
                        candidate.genres.any { it in topGenres }
            }
        } else emptyList()

        EntertainmentDiscoverFeed(
            trending = trending.take(16),
            upcomingMovies = ukMoviesDef.await(),
            becauseYouWatchedTitle = trackedItems.firstOrNull()?.title.orEmpty(),
            becauseYouWatched = genreMatches.take(12)
        )
    }

    fun resolveLgTvAppTarget(streamerName: String, titleHint: String): Pair<String, String> {
        val lower = streamerName.lowercase(Locale.UK)
        val appId = when {
            "netflix" in lower -> "netflix"
            "apple" in lower -> "com.apple.appletv"
            "prime" in lower || "amazon" in lower -> "amazon"
            "disney" in lower -> "com.disney.disneyplus-prod"
            "bbc" in lower || "iplayer" in lower -> "bbc"
            "itv" in lower -> "uk.co.itv.itvplayer"
            "channel 4" in lower || "all 4" in lower -> "com.channel4.all4"
            "now" in lower || "sky" in lower || "hbo" in lower -> "com.sky.nowtv"
            "paramount" in lower -> "com.paramount.paramountplus"
            "youtube" in lower -> "youtube.leanback.v4"
            else -> "netflix"
        }
        return appId to titleHint.trim()
    }

    private suspend fun searchTmdbMulti(query: String, apiKey: String): List<EntertainmentMediaItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = executeGetJson("$TMDB_BASE/search/multi?query=$encoded&language=en-GB&region=GB&include_adult=false", apiKey)
            ?: return@withContext emptyList()
        parseTmdbResultsArray(json.optJSONArray("results"), forcedKind = null)
    }

    private suspend fun fetchTmdbList(
        url: String,
        apiKey: String,
        forcedKind: EntertainmentMediaKind? = null
    ): List<EntertainmentMediaItem> = withContext(Dispatchers.IO) {
        val json = executeGetJson(url, apiKey) ?: return@withContext emptyList()
        parseTmdbResultsArray(json.optJSONArray("results"), forcedKind)
    }

    private fun parseTmdbResultsArray(
        arr: JSONArray?,
        forcedKind: EntertainmentMediaKind?
    ): List<EntertainmentMediaItem> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val mediaType = o.optString("media_type", "")
                val kind = forcedKind ?: when (mediaType) {
                    "movie" -> EntertainmentMediaKind.MOVIE
                    "tv" -> EntertainmentMediaKind.TV_SHOW
                    else -> continue
                }
                val tmdbId = o.optInt("id", 0)
                if (tmdbId <= 0) continue
                val title = (if (kind == EntertainmentMediaKind.MOVIE) o.optString("title") else o.optString("name")).trim()
                if (title.isBlank()) continue
                val poster = o.optString("poster_path", "").takeIf { it.isNotBlank() && it != "null" }?.let { "$TMDB_IMG_POSTER$it" }.orEmpty()
                val backdrop = o.optString("backdrop_path", "").takeIf { it.isNotBlank() && it != "null" }?.let { "$TMDB_IMG_BACKDROP$it" }.orEmpty()
                val releaseIso = if (kind == EntertainmentMediaKind.MOVIE) o.optString("release_date", "") else o.optString("first_air_date", "")
                add(
                    EntertainmentMediaItem(
                        id = "${kind.name.lowercase(Locale.UK)}_tmdb_$tmdbId",
                        tmdbId = tmdbId,
                        kind = kind,
                        title = title,
                        overview = stripHtml(o.optString("overview", "")),
                        posterUrl = poster,
                        backdropUrl = backdrop,
                        rating = o.optDouble("vote_average", 0.0),
                        releaseDateIso = releaseIso,
                        accentColorHex = accentForTitle(title)
                    )
                )
            }
        }
    }

    private fun hydrateTmdbMovie(seed: EntertainmentMediaItem, apiKey: String): EntertainmentMediaItem {
        val url = "$TMDB_BASE/movie/${seed.tmdbId}?language=en-GB&append_to_response=release_dates,watch/providers,credits"
        val o = executeGetJson(url, apiKey) ?: return seed
        val providers = extractUkWatchProviders(o.optJSONObject("watch/providers"))
        val primaryStreamer = providers.firstOrNull().orEmpty().ifBlank { "Cinema / VOD" }
        val ukReleaseIso = extractUkMovieReleaseDate(o.optJSONObject("release_dates")).ifBlank {
            o.optString("release_date", seed.releaseDateIso)
        }
        val (lgApp, lgContent) = resolveLgTvAppTarget(primaryStreamer, seed.title)
        val genres = extractNameList(o.optJSONArray("genres"))
        val cast = extractTmdbCast(o.optJSONObject("credits"))
        val poster = o.optString("poster_path", "").takeIf { it.isNotBlank() && it != "null" }?.let { "$TMDB_IMG_POSTER$it" } ?: seed.posterUrl
        val backdrop = o.optString("backdrop_path", "").takeIf { it.isNotBlank() && it != "null" }?.let { "$TMDB_IMG_BACKDROP$it" } ?: seed.backdropUrl

        return seed.copy(
            imdbId = o.optString("imdb_id", seed.imdbId),
            overview = o.optString("overview", seed.overview),
            posterUrl = poster,
            backdropUrl = backdrop,
            rating = o.optDouble("vote_average", seed.rating),
            genres = genres,
            streamingProviders = providers,
            primaryStreamer = primaryStreamer,
            lgAppIdHint = lgApp,
            lgContentIdHint = lgContent,
            statusText = o.optString("status", "Released"),
            releaseDateIso = ukReleaseIso,
            nextAirTimestampEpochMs = parseIsoDateMs(ukReleaseIso),
            cast = cast,
            lastSyncedEpochMs = System.currentTimeMillis()
        )
    }

    private fun hydrateTmdbTvShow(seed: EntertainmentMediaItem, apiKey: String): EntertainmentMediaItem {
        val url = "$TMDB_BASE/tv/${seed.tmdbId}?language=en-GB&append_to_response=external_ids,watch/providers,credits"
        val o = executeGetJson(url, apiKey) ?: return seed
        val imdbId = o.optJSONObject("external_ids")?.optString("imdb_id", "").orEmpty().ifBlank { seed.imdbId }
        val providers = extractUkWatchProviders(o.optJSONObject("watch/providers"))
        val networkFallback = o.optJSONArray("networks")?.optJSONObject(0)?.optString("name", "").orEmpty()
        val primaryStreamer = providers.firstOrNull().orEmpty().ifBlank { networkFallback.ifBlank { "TV Series" } }
        val (lgApp, lgContent) = resolveLgTvAppTarget(primaryStreamer, seed.title)
        val genres = extractNameList(o.optJSONArray("genres"))
        val cast = extractTmdbCast(o.optJSONObject("credits"))
        val poster = o.optString("poster_path", "").takeIf { it.isNotBlank() && it != "null" }?.let { "$TMDB_IMG_POSTER$it" } ?: seed.posterUrl
        val backdrop = o.optString("backdrop_path", "").takeIf { it.isNotBlank() && it != "null" }?.let { "$TMDB_IMG_BACKDROP$it" } ?: seed.backdropUrl

        val nextEpObj = o.optJSONObject("next_episode_to_air")
        var nextIso = nextEpObj?.optString("air_date", "").orEmpty()
        var nextCode = if (nextEpObj != null) {
            String.format(Locale.UK, "S%02dE%02d", nextEpObj.optInt("season_number", 1), nextEpObj.optInt("episode_number", 1))
        } else ""
        var nextTitle = nextEpObj?.optString("name", "").orEmpty()
        var nextEpoch = parseIsoDateMs(nextIso)
        var tvmazeId = seed.tvmazeId
        var episodes = seed.episodes

        val resolvedMazeId = when {
            tvmazeId > 0 -> tvmazeId
            imdbId.isNotBlank() -> lookupTvMazeIdByImdb(imdbId)
            else -> 0
        }
        if (resolvedMazeId > 0) {
            tvmazeId = resolvedMazeId
            val mazeHydrated = hydrateTvMazeShow(seed.copy(tvmazeId = resolvedMazeId))
            if (mazeHydrated.episodes.isNotEmpty()) {
                episodes = mazeHydrated.episodes
                if (mazeHydrated.nextAirTimestampEpochMs > 0L) {
                    nextEpoch = mazeHydrated.nextAirTimestampEpochMs
                    nextIso = mazeHydrated.releaseDateIso.ifBlank { nextIso }
                    nextCode = mazeHydrated.nextEpisodeCode.ifBlank { nextCode }
                    nextTitle = mazeHydrated.nextEpisodeTitle.ifBlank { nextTitle }
                }
            }
        }

        return seed.copy(
            tvmazeId = tvmazeId,
            imdbId = imdbId,
            overview = o.optString("overview", seed.overview),
            posterUrl = poster,
            backdropUrl = backdrop,
            rating = o.optDouble("vote_average", seed.rating),
            genres = genres,
            streamingProviders = providers,
            primaryStreamer = primaryStreamer,
            lgAppIdHint = lgApp,
            lgContentIdHint = lgContent,
            statusText = o.optString("status", "Returning Series"),
            releaseDateIso = nextIso,
            nextAirTimestampEpochMs = nextEpoch,
            nextEpisodeCode = nextCode,
            nextEpisodeTitle = nextTitle,
            episodes = episodes,
            cast = cast,
            lastSyncedEpochMs = System.currentTimeMillis()
        )
    }

    private suspend fun searchTvMazeShows(query: String): List<EntertainmentMediaItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val arr = executeGetJsonArray("$TVMAZE_BASE/search/shows?q=$encoded") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val show = arr.optJSONObject(i)?.optJSONObject("show") ?: continue
                parseTvMazeShowSummary(show)?.let { add(it) }
            }
        }
    }

    private suspend fun fetchTvMazeUkTrending(): List<EntertainmentMediaItem> = withContext(Dispatchers.IO) {
        val ukArr = executeGetJsonArray("$TVMAZE_BASE/schedule?country=GB") ?: JSONArray()
        val webArr = executeGetJsonArray("$TVMAZE_BASE/schedule/web") ?: JSONArray()
        val seenIds = HashSet<Int>()
        val combined = mutableListOf<EntertainmentMediaItem>()

        fun collectFromSchedule(arr: JSONArray, embeddedKey: String) {
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val show = entry.optJSONObject("show")
                    ?: entry.optJSONObject("_embedded")?.optJSONObject(embeddedKey)
                    ?: continue
                val mazeId = show.optInt("id", 0)
                if (mazeId <= 0 || !seenIds.add(mazeId)) continue
                parseTvMazeShowSummary(show)?.let { combined.add(it) }
            }
        }

        collectFromSchedule(ukArr, "show")
        collectFromSchedule(webArr, "show")
        combined.sortedByDescending { it.rating }
    }

    private fun parseTvMazeShowSummary(show: JSONObject): EntertainmentMediaItem? {
        val mazeId = show.optInt("id", 0)
        val title = show.optString("name", "").trim()
        if (mazeId <= 0 || title.isBlank()) return null
        val streamer = show.optJSONObject("webChannel")?.optString("name", "")
            ?.takeIf { it.isNotBlank() }
            ?: show.optJSONObject("network")?.optString("name", "")
            ?: "TV Series"
        val imageObj = show.optJSONObject("image")
        val poster = imageObj?.optString("original", "")
            ?.ifBlank { imageObj.optString("medium", "") }
            ?.replace("http://", "https://")
            .orEmpty()
        val genresArr = show.optJSONArray("genres") ?: JSONArray()
        val genres = (0 until genresArr.length()).mapNotNull { genresArr.optString(it).takeIf { g -> g.isNotBlank() } }
        val rating = show.optJSONObject("rating")?.optDouble("average", 0.0) ?: 0.0
        val imdbId = show.optJSONObject("externals")?.optString("imdb", "").orEmpty()
        val (lgApp, lgContent) = resolveLgTvAppTarget(streamer, title)

        return EntertainmentMediaItem(
            id = "tv_show_tvmaze_$mazeId",
            tvmazeId = mazeId,
            imdbId = imdbId,
            kind = EntertainmentMediaKind.TV_SHOW,
            title = title,
            overview = stripHtml(show.optString("summary", "")),
            posterUrl = poster,
            backdropUrl = poster,
            rating = if (rating.isNaN()) 0.0 else rating,
            genres = genres,
            streamingProviders = if (streamer.isNotBlank()) listOf(streamer) else emptyList(),
            primaryStreamer = streamer,
            lgAppIdHint = lgApp,
            lgContentIdHint = lgContent,
            accentColorHex = accentForTitle(title),
            statusText = show.optString("status", "Running")
        )
    }

    private fun hydrateTvMazeShow(seed: EntertainmentMediaItem): EntertainmentMediaItem {
        val url = "$TVMAZE_BASE/shows/${seed.tvmazeId}?embed[]=episodes&embed[]=cast"
        val show = executeGetJson(url, apiKey = "") ?: return seed
        val base = parseTvMazeShowSummary(show) ?: seed
        val embedded = show.optJSONObject("_embedded")
        val epArr = embedded?.optJSONArray("episodes") ?: JSONArray()
        val nowMs = System.currentTimeMillis()
        val startOfTodayMs = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val parsedEpisodes = buildList {
            for (i in 0 until epArr.length()) {
                val e = epArr.optJSONObject(i) ?: continue
                val season = e.optInt("season", 0)
                val number = e.optInt("number", 0)
                if (season <= 0 || number <= 0) continue
                val airdate = e.optString("airdate", "")
                val airstamp = e.optString("airstamp", "")
                val epochMs = parseRfc3339OrIsoMs(airstamp, airdate)
                add(
                    EntertainmentEpisode(
                        id = "tvmaze_ep_${e.optInt("id", i)}",
                        seasonNumber = season,
                        episodeNumber = number,
                        title = e.optString("name", "Episode $number"),
                        airDateIso = airdate,
                        airTimestampEpochMs = epochMs,
                        runtimeMinutes = e.optInt("runtime", 0),
                        overview = stripHtml(e.optString("summary", ""))
                    )
                )
            }
        }

        val nextUpcoming = parsedEpisodes
            .filter { it.airTimestampEpochMs >= startOfTodayMs }
            .minByOrNull { if (it.airTimestampEpochMs >= nowMs) it.airTimestampEpochMs else it.airTimestampEpochMs + 1L }

        val castArr = embedded?.optJSONArray("cast") ?: JSONArray()
        val cast = buildList {
            for (i in 0 until minOf(castArr.length(), 14)) {
                val entry = castArr.optJSONObject(i) ?: continue
                val personName = entry.optJSONObject("person")?.optString("name", "").orEmpty().trim()
                val charName = entry.optJSONObject("character")?.optString("name", "").orEmpty().trim()
                val img = entry.optJSONObject("person")?.optJSONObject("image")?.optString("medium", "")
                    ?.replace("http://", "https://").orEmpty()
                if (personName.isNotBlank()) {
                    add(EntertainmentCastMember(name = personName, character = charName, profileUrl = img))
                }
            }
        }

        return seed.copy(
            tvmazeId = base.tvmazeId,
            imdbId = base.imdbId.ifBlank { seed.imdbId },
            overview = base.overview.ifBlank { seed.overview },
            posterUrl = base.posterUrl.ifBlank { seed.posterUrl },
            backdropUrl = seed.backdropUrl.ifBlank { base.backdropUrl },
            rating = if (base.rating > 0.0) base.rating else seed.rating,
            genres = base.genres.ifEmpty { seed.genres },
            streamingProviders = seed.streamingProviders.ifEmpty { base.streamingProviders },
            primaryStreamer = seed.primaryStreamer.ifBlank { base.primaryStreamer },
            lgAppIdHint = seed.lgAppIdHint.ifBlank { base.lgAppIdHint },
            lgContentIdHint = seed.lgContentIdHint.ifBlank { base.lgContentIdHint },
            statusText = base.statusText.ifBlank { seed.statusText },
            releaseDateIso = nextUpcoming?.airDateIso.orEmpty(),
            nextAirTimestampEpochMs = nextUpcoming?.airTimestampEpochMs ?: 0L,
            nextEpisodeCode = nextUpcoming?.episodeCode.orEmpty(),
            nextEpisodeTitle = nextUpcoming?.title.orEmpty(),
            episodes = parsedEpisodes,
            cast = cast.ifEmpty { seed.cast },
            lastSyncedEpochMs = System.currentTimeMillis()
        )
    }

    private suspend fun searchItunesMoviesUk(query: String): List<EntertainmentMediaItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://itunes.apple.com/search?term=$encoded&country=GB&media=movie&entity=movie&limit=10"
        val root = executeGetJson(url, apiKey = "") ?: return@withContext emptyList()
        val arr = root.optJSONArray("results") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val trackId = o.optLong("trackId", 0L)
                val title = o.optString("trackName", "").trim()
                if (trackId <= 0L || title.isBlank()) continue
                val artwork = o.optString("artworkUrl100", "").replace("100x100bb", "600x600bb")
                val releaseIso = o.optString("releaseDate", "").take(10)
                val genre = o.optString("primaryGenreName", "").takeIf { it.isNotBlank() }
                val (lgApp, lgContent) = resolveLgTvAppTarget("Apple TV", title)
                add(
                    EntertainmentMediaItem(
                        id = "movie_itunes_$trackId",
                        itunesId = trackId,
                        kind = EntertainmentMediaKind.MOVIE,
                        title = title,
                        overview = o.optString("longDescription", o.optString("shortDescription", "")),
                        posterUrl = artwork,
                        backdropUrl = artwork,
                        genres = if (genre != null) listOf(genre) else emptyList(),
                        streamingProviders = listOf("Apple TV"),
                        primaryStreamer = "Apple TV",
                        lgAppIdHint = lgApp,
                        lgContentIdHint = lgContent,
                        accentColorHex = accentForTitle(title),
                        statusText = "Movie",
                        releaseDateIso = releaseIso,
                        nextAirTimestampEpochMs = parseIsoDateMs(releaseIso)
                    )
                )
            }
        }
    }

    private suspend fun fetchItunesTopUkMovies(): List<EntertainmentMediaItem> = withContext(Dispatchers.IO) {
        val root = executeGetJson("https://rss.applemarketingtools.com/api/v2/gb/movies/top/16/movies.json", "")
            ?: return@withContext emptyList()
        val arr = root.optJSONObject("feed")?.optJSONArray("results") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val idStr = o.optString("id", "")
                val title = o.optString("name", "").trim()
                if (idStr.isBlank() || title.isBlank()) continue
                val artwork = o.optString("artworkUrl100", "").replace("100x100bb", "600x600bb")
                val releaseIso = o.optString("releaseDate", "").take(10)
                val (lgApp, lgContent) = resolveLgTvAppTarget("Apple TV", title)
                add(
                    EntertainmentMediaItem(
                        id = "movie_itunes_$idStr",
                        itunesId = idStr.toLongOrNull() ?: 0L,
                        kind = EntertainmentMediaKind.MOVIE,
                        title = title,
                        posterUrl = artwork,
                        backdropUrl = artwork,
                        streamingProviders = listOf("Apple TV"),
                        primaryStreamer = "Apple TV",
                        lgAppIdHint = lgApp,
                        lgContentIdHint = lgContent,
                        accentColorHex = accentForTitle(title),
                        statusText = "Movie",
                        releaseDateIso = releaseIso,
                        nextAirTimestampEpochMs = parseIsoDateMs(releaseIso)
                    )
                )
            }
        }
    }

    private fun lookupTvMazeIdByImdb(imdbId: String): Int {
        val show = executeGetJson("$TVMAZE_BASE/lookup/shows?imdb=$imdbId", apiKey = "") ?: return 0
        return show.optInt("id", 0)
    }

    private fun extractUkWatchProviders(watchObj: JSONObject?): List<String> {
        val gb = watchObj?.optJSONObject("results")?.optJSONObject("GB") ?: return emptyList()
        val buckets = listOf("flatrate", "free", "ads", "rent", "buy")
        val names = LinkedHashSet<String>()
        for (bucket in buckets) {
            val arr = gb.optJSONArray(bucket) ?: continue
            for (i in 0 until arr.length()) {
                val name = arr.optJSONObject(i)?.optString("provider_name", "")?.trim().orEmpty()
                if (name.isNotBlank()) names.add(name)
            }
        }
        return names.take(5)
    }

    private fun extractUkMovieReleaseDate(relObj: JSONObject?): String {
        val results = relObj?.optJSONArray("results") ?: return ""
        for (i in 0 until results.length()) {
            val country = results.optJSONObject(i) ?: continue
            if (country.optString("iso_3166_1") != "GB") continue
            val dates = country.optJSONArray("release_dates") ?: continue
            var digitalDate = ""
            var theatricalDate = ""
            for (j in 0 until dates.length()) {
                val d = dates.optJSONObject(j) ?: continue
                val iso = d.optString("release_date", "").take(10)
                val type = d.optInt("type", 0)
                if (type == 4 && iso.isNotBlank()) digitalDate = iso
                if ((type == 3 || type == 2) && iso.isNotBlank() && theatricalDate.isBlank()) theatricalDate = iso
            }
            return digitalDate.ifBlank { theatricalDate }
        }
        return ""
    }

    private fun extractNameList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name", "")?.takeIf { n -> n.isNotBlank() } }
    }

    private fun extractTmdbCast(creditsObj: JSONObject?): List<EntertainmentCastMember> {
        val castArr = creditsObj?.optJSONArray("cast") ?: return emptyList()
        return buildList {
            for (i in 0 until minOf(castArr.length(), 14)) {
                val c = castArr.optJSONObject(i) ?: continue
                val name = c.optString("name", "").trim()
                if (name.isBlank()) continue
                val profile = c.optString("profile_path", "").takeIf { it.isNotBlank() && it != "null" }?.let { "$TMDB_IMG_POSTER$it" }.orEmpty()
                add(EntertainmentCastMember(name = name, character = c.optString("character", ""), profileUrl = profile))
            }
        }
    }

    private fun executeGetJson(rawUrl: String, apiKey: String): JSONObject? {
        val body = executeHttpGet(rawUrl, apiKey) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun executeGetJsonArray(rawUrl: String): JSONArray? {
        val body = executeHttpGet(rawUrl, "") ?: return null
        return runCatching { JSONArray(body) }.getOrNull()
    }

    private fun executeHttpGet(rawUrl: String, apiKey: String): String? {
        return runCatching {
            val isBearer = apiKey.length > 45
            val finalUrl = if (apiKey.isNotBlank() && !isBearer && rawUrl.startsWith(TMDB_BASE)) {
                val sep = if ("?" in rawUrl) "&" else "?"
                "$rawUrl${sep}api_key=$apiKey"
            } else {
                rawUrl
            }
            val conn = (URL(finalUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                if (isBearer && rawUrl.startsWith(TMDB_BASE)) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        }.getOrNull()
    }

    private fun stripHtml(raw: String): String =
        raw.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"").trim()

    private fun parseRfc3339OrIsoMs(airstamp: String, airdate: String): Long {
        if (airstamp.isNotBlank()) {
            runCatching {
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                fmt.parse(airstamp)?.time
            }.getOrNull()?.let { return it }
        }
        return parseIsoDateMs(airdate)
    }

    private fun parseIsoDateMs(dateIso: String): Long {
        if (dateIso.isBlank()) return 0L
        return runCatching {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.UK).parse(dateIso.take(10)) ?: return 0L
            Calendar.getInstance().apply {
                time = parsed
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }.getOrDefault(0L)
    }

    private fun accentForTitle(title: String): Long {
        val palette = longArrayOf(0xFF00E676L, 0xFF00E5FFL, 0xFFFFAB40L, 0xFF7C4DFFL, 0xFF40C4FFL)
        val idx = (title.hashCode() and 0x7FFFFFFF) % palette.size
        return palette[idx]
    }
}