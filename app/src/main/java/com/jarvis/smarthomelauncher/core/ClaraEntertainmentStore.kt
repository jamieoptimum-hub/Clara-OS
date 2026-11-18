package com.jarvis.smarthomelauncher.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class EntertainmentMediaKind {
    TV_SHOW,
    MOVIE
}

enum class EntertainmentItemStatus {
    ACTIVE,
    ARCHIVED,
    HIDDEN
}

enum class HeatmapIntensity {
    NONE,
    SINGLE,
    MULTIPLE,
    BUSY
}

data class EntertainmentEpisode(
    val id: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val airDateIso: String,
    val airTimestampEpochMs: Long,
    val runtimeMinutes: Int = 0,
    val overview: String = ""
) {
    val episodeCode: String
        get() = String.format(Locale.UK, "S%02dE%02d", seasonNumber, episodeNumber)
}

data class EntertainmentCastMember(
    val name: String,
    val character: String,
    val profileUrl: String = ""
)

data class EntertainmentMediaItem(
    val id: String,
    val tmdbId: Int = 0,
    val tvmazeId: Int = 0,
    val itunesId: Long = 0L,
    val imdbId: String = "",
    val kind: EntertainmentMediaKind = EntertainmentMediaKind.TV_SHOW,
    val title: String,
    val overview: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val rating: Double = 0.0,
    val genres: List<String> = emptyList(),
    val streamingProviders: List<String> = emptyList(),
    val primaryStreamer: String = "",
    val lgAppIdHint: String = "",
    val lgContentIdHint: String = "",
    val accentColorHex: Long = 0xFF00E676L,
    val statusText: String = "",
    val lifecycleStatus: EntertainmentItemStatus = EntertainmentItemStatus.ACTIVE,
    val isFavourite: Boolean = true,
    val releaseDateIso: String = "",
    val nextAirTimestampEpochMs: Long = 0L,
    val nextEpisodeCode: String = "",
    val nextEpisodeTitle: String = "",
    val episodes: List<EntertainmentEpisode> = emptyList(),
    val watchedEpisodeIds: Set<String> = emptySet(),
    val isMovieWatched: Boolean = false,
    val lastWatchedEpochMs: Long = 0L,
    val cast: List<EntertainmentCastMember> = emptyList(),
    val lastSyncedEpochMs: Long = 0L
) {
    fun unwatchedAiredEpisodeCount(nowEpochMs: Long = System.currentTimeMillis()): Int {
        if (kind != EntertainmentMediaKind.TV_SHOW || lifecycleStatus != EntertainmentItemStatus.ACTIVE) return 0
        return episodes.count { ep ->
            ep.seasonNumber > 0 &&
                    ep.airTimestampEpochMs in 1..nowEpochMs &&
                    !watchedEpisodeIds.contains(ep.id)
        }
    }

    fun watchProgressFraction(): Float {
        if (kind == EntertainmentMediaKind.MOVIE) return if (isMovieWatched) 1f else 0f
        val regularEpisodes = episodes.filter { it.seasonNumber > 0 }
        if (regularEpisodes.isEmpty()) return 0f
        val watched = regularEpisodes.count { watchedEpisodeIds.contains(it.id) }
        return (watched.toFloat() / regularEpisodes.size.toFloat()).coerceIn(0f, 1f)
    }
}

data class EntertainmentScheduledRelease(
    val itemId: String,
    val title: String,
    val kind: EntertainmentMediaKind,
    val episodeCode: String,
    val episodeTitle: String,
    val releaseDateIso: String,
    val releaseEpochMs: Long,
    val countdownLabel: String,
    val primaryStreamer: String,
    val lgAppIdHint: String,
    val lgContentIdHint: String,
    val posterUrl: String,
    val accentColorHex: Long
)

data class EntertainmentHeatmapCell(
    val dayOffset: Int,
    val dateIso: String,
    val dayShort: String,
    val dayOfMonth: Int,
    val monthMarker: String,
    val releaseCount: Int,
    val intensity: HeatmapIntensity,
    val titles: List<String>
)

data class EntertainmentDashboardSnapshot(
    val trackedShowCount: Int = 0,
    val trackedMovieCount: Int = 0,
    val unwatchedEpisodeCount: Int = 0,
    val upcoming28DayCount: Int = 0,
    val todayEpisodeCount: Int = 0,
    val todayMovieCount: Int = 0,
    val todayReleases: List<EntertainmentScheduledRelease> = emptyList(),
    val nextRelease: EntertainmentScheduledRelease? = null,
    val heatmap28Days: List<EntertainmentHeatmapCell> = emptyList(),
    val aiSummaryLine: String = "Tap Entertainment to add your favourite shows & movies"
)

object ClaraEntertainmentStore {
    private const val STORE_FILE_NAME = "clara_entertainment_store_v1.json"
    private const val PREFS_NAME = "clara_entertainment_prefs_v1"
    private const val KEY_PURGED_LEGACY_SEEDS = "purged_legacy_seeds_v1"
    private const val KEY_TMDB_API_KEY = "tmdb_api_key_v3"
    private const val KEY_OVERRIDE_WITH_IPTV = "override_with_iptv_v1"
    private const val LEGACY_NEXT_EP_PREFS = "clara_next_episode_tv_prefs"

    private const val LEGACY_BRIDGE_PREFS = "google_home_jarvis_prefs_v10"
    private const val LEGACY_SHOWS_JSON_KEY = "tracked_media_shows_json_v1"
    private const val LEGACY_SEEDED_FLAG_KEY = "has_seeded_default_tv_shows_v1"
    private const val LEGACY_CUSTOMIZED_FLAG_KEY = "has_user_customized_tracked_shows_v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeMutex = Mutex()
    @Volatile private var isInitialized = false

    private val _items = MutableStateFlow<List<EntertainmentMediaItem>>(emptyList())
    val items: StateFlow<List<EntertainmentMediaItem>> = _items.asStateFlow()

    private suspend fun ensureLoadedLocked(appContext: Context) {
        if (isInitialized) return
        purgeLegacySeedShowsOnce(appContext)
        _items.value = readStoreFromDisk(appContext)
        isInitialized = true
    }

    fun ensureInitialized(context: Context) {
        if (isInitialized) return
        val appContext = context.applicationContext
        scope.launch {
            storeMutex.withLock {
                ensureLoadedLocked(appContext)
            }
        }
    }

    fun getTmdbApiKey(context: Context): String {
        return ClaraSecretVault.migrateStringKey(
            context,
            PREFS_NAME,
            KEY_TMDB_API_KEY,
            KEY_TMDB_API_KEY
        ).orEmpty()
    }

    fun setTmdbApiKey(context: Context, apiKey: String) {
        ClaraSecretVault.getVault(context).edit()
            .putString(KEY_TMDB_API_KEY, apiKey.trim())
            .apply()
    }

    fun getOverrideWithIptv(context: Context): Boolean {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_OVERRIDE_WITH_IPTV)) {
            return prefs.getBoolean(KEY_OVERRIDE_WITH_IPTV, false)
        }
        val legacyPrefs = appCtx.getSharedPreferences(LEGACY_NEXT_EP_PREFS, Context.MODE_PRIVATE)
        val migrated = legacyPrefs.getBoolean("override_with_iptv", false)
        prefs.edit().putBoolean(KEY_OVERRIDE_WITH_IPTV, migrated).apply()
        runCatching { appCtx.deleteSharedPreferences(LEGACY_NEXT_EP_PREFS) }
        return migrated
    }

    fun setOverrideWithIptv(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OVERRIDE_WITH_IPTV, enabled)
            .apply()
        runCatching { appCtx.deleteSharedPreferences(LEGACY_NEXT_EP_PREFS) }
    }

    suspend fun reloadFromDisk(context: Context) {
        val appContext = context.applicationContext
        storeMutex.withLock {
            _items.value = readStoreFromDisk(appContext)
            isInitialized = true
        }
    }

    private fun purgeLegacySeedShowsOnce(context: Context) {
        val entPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (entPrefs.getBoolean(KEY_PURGED_LEGACY_SEEDS, false)) return

        val legacyPrefs = context.getSharedPreferences(LEGACY_BRIDGE_PREFS, Context.MODE_PRIVATE)
        legacyPrefs.edit()
            .putString(LEGACY_SHOWS_JSON_KEY, "[]")
            .putBoolean(LEGACY_SEEDED_FLAG_KEY, true)
            .putBoolean(LEGACY_CUSTOMIZED_FLAG_KEY, true)
            .apply()

        entPrefs.edit().putBoolean(KEY_PURGED_LEGACY_SEEDS, true).apply()
    }

    suspend fun upsertItem(context: Context, item: EntertainmentMediaItem) {
        val appContext = context.applicationContext
        storeMutex.withLock {
            ensureLoadedLocked(appContext)
            val current = _items.value.toMutableList()
            val index = current.indexOfFirst {
                it.id == item.id ||
                        (item.tmdbId > 0 && it.tmdbId == item.tmdbId && it.kind == item.kind) ||
                        (item.tvmazeId > 0 && it.tvmazeId == item.tvmazeId && it.kind == item.kind)
            }
            if (index >= 0) {
                val existing = current[index]
                current[index] = item.copy(
                    id = existing.id,
                    watchedEpisodeIds = if (item.watchedEpisodeIds.isEmpty()) existing.watchedEpisodeIds else item.watchedEpisodeIds,
                    isMovieWatched = item.isMovieWatched || existing.isMovieWatched,
                    lastWatchedEpochMs = maxOf(existing.lastWatchedEpochMs, item.lastWatchedEpochMs),
                    lifecycleStatus = existing.lifecycleStatus
                )
            } else {
                current.add(item)
            }
            _items.value = current
            writeStoreToDisk(appContext, current)
        }
    }

    suspend fun deleteItemPermanently(context: Context, itemId: String) {
        val appContext = context.applicationContext
        storeMutex.withLock {
            ensureLoadedLocked(appContext)
            val updated = _items.value.filterNot { it.id == itemId }
            _items.value = updated
            writeStoreToDisk(appContext, updated)
        }
    }

    suspend fun updateLifecycleStatus(context: Context, itemId: String, status: EntertainmentItemStatus) {
        mutateItem(context, itemId) { it.copy(lifecycleStatus = status) }
    }

    suspend fun setFavourite(context: Context, itemId: String, favourite: Boolean) {
        mutateItem(context, itemId) { it.copy(isFavourite = favourite) }
    }

    suspend fun setEpisodeWatched(context: Context, itemId: String, episodeId: String, watched: Boolean) {
        val now = System.currentTimeMillis()
        mutateItem(context, itemId) { item ->
            val updatedSet = item.watchedEpisodeIds.toMutableSet().apply {
                if (watched) add(episodeId) else remove(episodeId)
            }
            item.copy(
                watchedEpisodeIds = updatedSet,
                lastWatchedEpochMs = if (watched) now else item.lastWatchedEpochMs
            )
        }
    }

    suspend fun setSeasonWatched(context: Context, itemId: String, seasonNumber: Int, watched: Boolean) {
        val now = System.currentTimeMillis()
        mutateItem(context, itemId) { item ->
            val seasonIds = item.episodes.filter { it.seasonNumber == seasonNumber }.map { it.id }.toSet()
            val updatedSet = item.watchedEpisodeIds.toMutableSet().apply {
                if (watched) addAll(seasonIds) else removeAll(seasonIds)
            }
            item.copy(
                watchedEpisodeIds = updatedSet,
                lastWatchedEpochMs = if (watched) now else item.lastWatchedEpochMs
            )
        }
    }

    suspend fun setMovieWatched(context: Context, itemId: String, watched: Boolean) {
        val now = System.currentTimeMillis()
        mutateItem(context, itemId) { item ->
            item.copy(
                isMovieWatched = watched,
                lastWatchedEpochMs = if (watched) now else item.lastWatchedEpochMs
            )
        }
    }

    suspend fun mergeRefreshedMetadata(context: Context, refreshedItems: List<EntertainmentMediaItem>) {
        if (refreshedItems.isEmpty()) return
        val appContext = context.applicationContext
        storeMutex.withLock {
            ensureLoadedLocked(appContext)
            val byId = refreshedItems.associateBy { it.id }
            var changed = false
            val merged = _items.value.map { existing ->
                val fresh = byId[existing.id] ?: return@map existing
                val updated = existing.copy(
                    tmdbId = if (fresh.tmdbId > 0) fresh.tmdbId else existing.tmdbId,
                    tvmazeId = if (fresh.tvmazeId > 0) fresh.tvmazeId else existing.tvmazeId,
                    imdbId = fresh.imdbId.ifBlank { existing.imdbId },
                    overview = fresh.overview.ifBlank { existing.overview },
                    posterUrl = fresh.posterUrl.ifBlank { existing.posterUrl },
                    backdropUrl = fresh.backdropUrl.ifBlank { existing.backdropUrl },
                    rating = if (fresh.rating > 0.0) fresh.rating else existing.rating,
                    genres = fresh.genres.ifEmpty { existing.genres },
                    streamingProviders = fresh.streamingProviders.ifEmpty { existing.streamingProviders },
                    primaryStreamer = fresh.primaryStreamer.ifBlank { existing.primaryStreamer },
                    lgAppIdHint = fresh.lgAppIdHint.ifBlank { existing.lgAppIdHint },
                    lgContentIdHint = fresh.lgContentIdHint.ifBlank { existing.lgContentIdHint },
                    statusText = fresh.statusText.ifBlank { existing.statusText },
                    releaseDateIso = fresh.releaseDateIso.ifBlank { existing.releaseDateIso },
                    nextAirTimestampEpochMs = fresh.nextAirTimestampEpochMs,
                    nextEpisodeCode = fresh.nextEpisodeCode,
                    nextEpisodeTitle = fresh.nextEpisodeTitle,
                    episodes = fresh.episodes.ifEmpty { existing.episodes },
                    cast = fresh.cast.ifEmpty { existing.cast },
                    lastSyncedEpochMs = fresh.lastSyncedEpochMs
                )
                if (updated != existing) changed = true
                updated
            }
            if (changed) {
                _items.value = merged
                writeStoreToDisk(appContext, merged)
            }
        }
    }

    private suspend fun mutateItem(
        context: Context,
        itemId: String,
        transform: (EntertainmentMediaItem) -> EntertainmentMediaItem
    ) {
        val appContext = context.applicationContext
        storeMutex.withLock {
            ensureLoadedLocked(appContext)
            var changed = false
            val updated = _items.value.map { item ->
                if (item.id == itemId) {
                    changed = true
                    transform(item)
                } else {
                    item
                }
            }
            if (changed) {
                _items.value = updated
                writeStoreToDisk(appContext, updated)
            }
        }
    }

    fun buildDashboardSnapshot(
        allItems: List<EntertainmentMediaItem>,
        nowEpochMs: Long = System.currentTimeMillis()
    ): EntertainmentDashboardSnapshot {
        val activeItems = allItems.filter { it.lifecycleStatus == EntertainmentItemStatus.ACTIVE }
        val trackedShowCount = activeItems.count { it.kind == EntertainmentMediaKind.TV_SHOW }
        val trackedMovieCount = activeItems.count { it.kind == EntertainmentMediaKind.MOVIE }
        val unwatchedCount = activeItems.sumOf { it.unwatchedAiredEpisodeCount(nowEpochMs) }

        val favourited = activeItems.filter { it.isFavourite }
        val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.UK)
        val dayShortFmt = SimpleDateFormat("EEE", Locale.UK)
        val monthShortFmt = SimpleDateFormat("MMM", Locale.UK)

        val startOfTodayCal = Calendar.getInstance().apply {
            timeInMillis = nowEpochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfTodayMs = startOfTodayCal.timeInMillis
        val endOf28DaysMs = (startOfTodayCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 28)
        }.timeInMillis

        val todayIso = isoFmt.format(Date(startOfTodayMs))
        val tomorrowIso = isoFmt.format(
            (startOfTodayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.time
        )

        val releasesInWindow = mutableListOf<EntertainmentScheduledRelease>()
        val futureCandidates = mutableListOf<EntertainmentScheduledRelease>()

        for (item in favourited) {
            if (item.kind == EntertainmentMediaKind.TV_SHOW) {
                val upcomingEpisodes = item.episodes.filter {
                    it.seasonNumber > 0 && (it.airTimestampEpochMs >= startOfTodayMs || it.airDateIso >= todayIso)
                }
                if (upcomingEpisodes.isNotEmpty()) {
                    for (ep in upcomingEpisodes) {
                        val epDateIso = ep.airDateIso.ifBlank {
                            if (ep.airTimestampEpochMs > 0L) isoFmt.format(Date(ep.airTimestampEpochMs)) else ""
                        }
                        if (epDateIso.isBlank()) continue
                        val effectiveMs = if (ep.airTimestampEpochMs > 0L) {
                            ep.airTimestampEpochMs
                        } else {
                            parseIsoToLocalNoonMs(epDateIso)
                        }
                        val scheduled = EntertainmentScheduledRelease(
                            itemId = item.id,
                            title = item.title,
                            kind = EntertainmentMediaKind.TV_SHOW,
                            episodeCode = ep.episodeCode,
                            episodeTitle = ep.title,
                            releaseDateIso = epDateIso,
                            releaseEpochMs = effectiveMs,
                            countdownLabel = formatCountdownLabel(effectiveMs, epDateIso, todayIso, tomorrowIso),
                            primaryStreamer = item.primaryStreamer,
                            lgAppIdHint = item.lgAppIdHint,
                            lgContentIdHint = item.lgContentIdHint,
                            posterUrl = item.posterUrl,
                            accentColorHex = item.accentColorHex
                        )
                        if (effectiveMs in startOfTodayMs until endOf28DaysMs) {
                            releasesInWindow.add(scheduled)
                        }
                        if (effectiveMs >= startOfTodayMs && !item.watchedEpisodeIds.contains(ep.id)) {
                            futureCandidates.add(scheduled)
                        }
                    }
                } else if (item.nextAirTimestampEpochMs >= startOfTodayMs || item.releaseDateIso >= todayIso) {
                    val dateIso = item.releaseDateIso.ifBlank { isoFmt.format(Date(item.nextAirTimestampEpochMs)) }
                    val effectiveMs = if (item.nextAirTimestampEpochMs > 0L) {
                        item.nextAirTimestampEpochMs
                    } else {
                        parseIsoToLocalNoonMs(dateIso)
                    }
                    val scheduled = EntertainmentScheduledRelease(
                        itemId = item.id,
                        title = item.title,
                        kind = EntertainmentMediaKind.TV_SHOW,
                        episodeCode = item.nextEpisodeCode.ifBlank { "Next Ep" },
                        episodeTitle = item.nextEpisodeTitle,
                        releaseDateIso = dateIso,
                        releaseEpochMs = effectiveMs,
                        countdownLabel = formatCountdownLabel(effectiveMs, dateIso, todayIso, tomorrowIso),
                        primaryStreamer = item.primaryStreamer,
                        lgAppIdHint = item.lgAppIdHint,
                        lgContentIdHint = item.lgContentIdHint,
                        posterUrl = item.posterUrl,
                        accentColorHex = item.accentColorHex
                    )
                    if (effectiveMs in startOfTodayMs until endOf28DaysMs) releasesInWindow.add(scheduled)
                    futureCandidates.add(scheduled)
                }
            } else {
                val movieIso = item.releaseDateIso.ifBlank {
                    if (item.nextAirTimestampEpochMs > 0L) isoFmt.format(Date(item.nextAirTimestampEpochMs)) else ""
                }
                if (movieIso.isNotBlank() && movieIso >= todayIso) {
                    val effectiveMs = if (item.nextAirTimestampEpochMs > 0L) {
                        item.nextAirTimestampEpochMs
                    } else {
                        parseIsoToLocalNoonMs(movieIso)
                    }
                    val scheduled = EntertainmentScheduledRelease(
                        itemId = item.id,
                        title = item.title,
                        kind = EntertainmentMediaKind.MOVIE,
                        episodeCode = "Movie",
                        episodeTitle = "",
                        releaseDateIso = movieIso,
                        releaseEpochMs = effectiveMs,
                        countdownLabel = formatCountdownLabel(effectiveMs, movieIso, todayIso, tomorrowIso),
                        primaryStreamer = item.primaryStreamer,
                        lgAppIdHint = item.lgAppIdHint,
                        lgContentIdHint = item.lgContentIdHint,
                        posterUrl = item.posterUrl,
                        accentColorHex = item.accentColorHex
                    )
                    if (effectiveMs in startOfTodayMs until endOf28DaysMs) releasesInWindow.add(scheduled)
                    if (!item.isMovieWatched) futureCandidates.add(scheduled)
                }
            }
        }

        val sortedWindow = releasesInWindow.sortedBy { it.releaseEpochMs }
        val byDate = sortedWindow.groupBy { it.releaseDateIso }
        val todayReleases = byDate[todayIso].orEmpty()
        val tomorrowReleases = byDate[tomorrowIso].orEmpty()

        val nextRelease = futureCandidates
            .filter { it.releaseDateIso > todayIso || it.releaseEpochMs >= nowEpochMs }
            .minByOrNull { it.releaseEpochMs }

        val heatmapCells = (0 until 28).map { offset ->
            val cellCal = (startOfTodayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
            val cellDate = cellCal.time
            val dateIso = isoFmt.format(cellDate)
            val dayOfMonth = cellCal.get(Calendar.DAY_OF_MONTH)
            val dayReleases = byDate[dateIso].orEmpty()
            val count = dayReleases.size
            val intensity = when {
                count <= 0 -> HeatmapIntensity.NONE
                count == 1 -> HeatmapIntensity.SINGLE
                count == 2 -> HeatmapIntensity.MULTIPLE
                else -> HeatmapIntensity.BUSY
            }
            val monthMarker = if (offset == 0 || dayOfMonth == 1) {
                monthShortFmt.format(cellDate).uppercase(Locale.UK)
            } else {
                ""
            }
            EntertainmentHeatmapCell(
                dayOffset = offset,
                dateIso = dateIso,
                dayShort = dayShortFmt.format(cellDate),
                dayOfMonth = dayOfMonth,
                monthMarker = monthMarker,
                releaseCount = count,
                intensity = intensity,
                titles = dayReleases.map { it.title }
            )
        }

        val next7Count = heatmapCells.take(7).sumOf { it.releaseCount }
        val weekendCount = (0 until 7).sumOf { offset ->
            val c = (startOfTodayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
            val dow = c.get(Calendar.DAY_OF_WEEK)
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
                heatmapCells[offset].releaseCount
            } else 0
        }

        val summaryLine = when {
            favourited.isEmpty() -> "Tap Entertainment to add TV shows & movies"
            todayReleases.isNotEmpty() -> {
                val label = if (todayReleases.size == 1) "1 release today" else "${todayReleases.size} releases today"
                "$label • ${sortedWindow.size} scheduled in 28d"
            }
            tomorrowReleases.isNotEmpty() -> {
                if (tomorrowReleases.size == 1) "1 item releases tomorrow" else "${tomorrowReleases.size} items release tomorrow"
            }
            next7Count >= 5 -> "Busy week ahead • $next7Count releases in 7 days"
            next7Count > 0 && weekendCount == 0 -> "$next7Count this week • No releases this weekend"
            sortedWindow.isNotEmpty() -> "${sortedWindow.size} releases scheduled in 28 days"
            else -> "No upcoming air dates announced yet"
        }

        return EntertainmentDashboardSnapshot(
            trackedShowCount = trackedShowCount,
            trackedMovieCount = trackedMovieCount,
            unwatchedEpisodeCount = unwatchedCount,
            upcoming28DayCount = sortedWindow.size,
            todayEpisodeCount = todayReleases.count { it.kind == EntertainmentMediaKind.TV_SHOW },
            todayMovieCount = todayReleases.count { it.kind == EntertainmentMediaKind.MOVIE },
            todayReleases = todayReleases,
            nextRelease = nextRelease,
            heatmap28Days = heatmapCells,
            aiSummaryLine = summaryLine
        )
    }

    private fun parseIsoToLocalNoonMs(dateIso: String): Long {
        return runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.UK)
            val parsed = fmt.parse(dateIso) ?: return 0L
            Calendar.getInstance().apply {
                time = parsed
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
            }.timeInMillis
        }.getOrDefault(0L)
    }

    private fun formatCountdownLabel(
        epochMs: Long,
        dateIso: String,
        todayIso: String,
        tomorrowIso: String
    ): String {
        val timeFmt = SimpleDateFormat("HH:mm", Locale.UK)
        val dayDateFmt = SimpleDateFormat("EEE d MMM", Locale.UK)
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        val hasExactTime = cal.get(Calendar.HOUR_OF_DAY) != 0 || cal.get(Calendar.MINUTE) != 0
        val timeSuffix = if (hasExactTime) " ${timeFmt.format(Date(epochMs))}" else ""

        return when (dateIso) {
            todayIso -> "Today$timeSuffix"
            tomorrowIso -> "Tomorrow$timeSuffix"
            else -> "${dayDateFmt.format(Date(epochMs))}$timeSuffix"
        }
    }

    private suspend fun readStoreFromDisk(context: Context): List<EntertainmentMediaItem> = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, STORE_FILE_NAME)
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val raw = file.readText()
            if (raw.isBlank()) return@runCatching emptyList()
            val root = JSONObject(raw)
            val arr = root.optJSONArray("items") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val title = o.optString("title", "").trim()
                    if (title.isBlank()) continue
                    add(deserializeItem(o, title))
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun writeStoreToDisk(context: Context, items: List<EntertainmentMediaItem>) = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray()
            items.forEach { arr.put(serializeItem(it)) }
            val root = JSONObject().apply {
                put("version", 1)
                put("updatedAtEpochMs", System.currentTimeMillis())
                put("items", arr)
            }
            val target = File(context.filesDir, STORE_FILE_NAME)
            val tmp = File(context.filesDir, "$STORE_FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(root.toString())
                tmp.delete()
            }
        }
    }

    private fun serializeItem(item: EntertainmentMediaItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("tmdbId", item.tmdbId)
        put("tvmazeId", item.tvmazeId)
        put("itunesId", item.itunesId)
        put("imdbId", item.imdbId)
        put("kind", item.kind.name)
        put("title", item.title)
        put("overview", item.overview)
        put("posterUrl", item.posterUrl)
        put("backdropUrl", item.backdropUrl)
        put("rating", item.rating)
        put("genres", JSONArray(item.genres))
        put("streamingProviders", JSONArray(item.streamingProviders))
        put("primaryStreamer", item.primaryStreamer)
        put("lgAppIdHint", item.lgAppIdHint)
        put("lgContentIdHint", item.lgContentIdHint)
        put("accentColorHex", item.accentColorHex)
        put("statusText", item.statusText)
        put("lifecycleStatus", item.lifecycleStatus.name)
        put("isFavourite", item.isFavourite)
        put("releaseDateIso", item.releaseDateIso)
        put("nextAirTimestampEpochMs", item.nextAirTimestampEpochMs)
        put("nextEpisodeCode", item.nextEpisodeCode)
        put("nextEpisodeTitle", item.nextEpisodeTitle)
        put("watchedEpisodeIds", JSONArray(item.watchedEpisodeIds.toList()))
        put("isMovieWatched", item.isMovieWatched)
        put("lastWatchedEpochMs", item.lastWatchedEpochMs)
        put("lastSyncedEpochMs", item.lastSyncedEpochMs)
        put("episodes", JSONArray().apply {
            item.episodes.forEach { ep ->
                put(JSONObject().apply {
                    put("id", ep.id)
                    put("seasonNumber", ep.seasonNumber)
                    put("episodeNumber", ep.episodeNumber)
                    put("title", ep.title)
                    put("airDateIso", ep.airDateIso)
                    put("airTimestampEpochMs", ep.airTimestampEpochMs)
                    put("runtimeMinutes", ep.runtimeMinutes)
                    put("overview", ep.overview)
                })
            }
        })
        put("cast", JSONArray().apply {
            item.cast.forEach { c ->
                put(JSONObject().apply {
                    put("name", c.name)
                    put("character", c.character)
                    put("profileUrl", c.profileUrl)
                })
            }
        })
    }

    private fun deserializeItem(o: JSONObject, title: String): EntertainmentMediaItem {
        val kind = runCatching {
            EntertainmentMediaKind.valueOf(o.optString("kind", EntertainmentMediaKind.TV_SHOW.name))
        }.getOrDefault(EntertainmentMediaKind.TV_SHOW)
        val status = runCatching {
            EntertainmentItemStatus.valueOf(o.optString("lifecycleStatus", EntertainmentItemStatus.ACTIVE.name))
        }.getOrDefault(EntertainmentItemStatus.ACTIVE)

        val episodesArr = o.optJSONArray("episodes") ?: JSONArray()
        val episodes = buildList {
            for (i in 0 until episodesArr.length()) {
                val e = episodesArr.optJSONObject(i) ?: continue
                add(
                    EntertainmentEpisode(
                        id = e.optString("id", "ep_$i"),
                        seasonNumber = e.optInt("seasonNumber", 1),
                        episodeNumber = e.optInt("episodeNumber", i + 1),
                        title = e.optString("title", ""),
                        airDateIso = e.optString("airDateIso", ""),
                        airTimestampEpochMs = e.optLong("airTimestampEpochMs", 0L),
                        runtimeMinutes = e.optInt("runtimeMinutes", 0),
                        overview = e.optString("overview", "")
                    )
                )
            }
        }

        val castArr = o.optJSONArray("cast") ?: JSONArray()
        val cast = buildList {
            for (i in 0 until castArr.length()) {
                val c = castArr.optJSONObject(i) ?: continue
                val name = c.optString("name", "").trim()
                if (name.isNotBlank()) {
                    add(
                        EntertainmentCastMember(
                            name = name,
                            character = c.optString("character", ""),
                            profileUrl = c.optString("profileUrl", "")
                        )
                    )
                }
            }
        }

        return EntertainmentMediaItem(
            id = o.optString("id", "item_${title.lowercase(Locale.UK).hashCode()}"),
            tmdbId = o.optInt("tmdbId", 0),
            tvmazeId = o.optInt("tvmazeId", 0),
            itunesId = o.optLong("itunesId", 0L),
            imdbId = o.optString("imdbId", ""),
            kind = kind,
            title = title,
            overview = o.optString("overview", ""),
            posterUrl = o.optString("posterUrl", ""),
            backdropUrl = o.optString("backdropUrl", ""),
            rating = o.optDouble("rating", 0.0),
            genres = jsonArrayToStrings(o.optJSONArray("genres")),
            streamingProviders = jsonArrayToStrings(o.optJSONArray("streamingProviders")),
            primaryStreamer = o.optString("primaryStreamer", ""),
            lgAppIdHint = o.optString("lgAppIdHint", ""),
            lgContentIdHint = o.optString("lgContentIdHint", ""),
            accentColorHex = o.optLong("accentColorHex", 0xFF00E676L),
            statusText = o.optString("statusText", ""),
            lifecycleStatus = status,
            isFavourite = o.optBoolean("isFavourite", true),
            releaseDateIso = o.optString("releaseDateIso", ""),
            nextAirTimestampEpochMs = o.optLong("nextAirTimestampEpochMs", 0L),
            nextEpisodeCode = o.optString("nextEpisodeCode", ""),
            nextEpisodeTitle = o.optString("nextEpisodeTitle", ""),
            episodes = episodes,
            watchedEpisodeIds = jsonArrayToStrings(o.optJSONArray("watchedEpisodeIds")).toSet(),
            isMovieWatched = o.optBoolean("isMovieWatched", false),
            lastWatchedEpochMs = o.optLong("lastWatchedEpochMs", 0L),
            cast = cast,
            lastSyncedEpochMs = o.optLong("lastSyncedEpochMs", 0L)
        )
    }

    private fun jsonArrayToStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim()
                if (v.isNotBlank()) add(v)
            }
        }
    }
}