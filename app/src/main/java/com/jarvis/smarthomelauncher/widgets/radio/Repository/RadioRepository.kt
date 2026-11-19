package com.jarvis.smarthomelauncher.widgets.radio.Repository

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.AudioManager
import com.jarvis.smarthomelauncher.core.ClaraInAppMusicPlayer
import com.jarvis.smarthomelauncher.core.DEFAULT_RADIO_STATIONS
import com.jarvis.smarthomelauncher.core.DeviceCapabilityDescriptor
import com.jarvis.smarthomelauncher.core.DeviceCapabilityKind
import com.jarvis.smarthomelauncher.core.DeviceClass
import com.jarvis.smarthomelauncher.core.DeviceRepository
import com.jarvis.smarthomelauncher.core.DeviceSubtype
import com.jarvis.smarthomelauncher.core.JarvisEventCategory
import com.jarvis.smarthomelauncher.core.JarvisPlatformEvent
import com.jarvis.smarthomelauncher.core.SecurityCenterMode
import com.jarvis.smarthomelauncher.core.ServiceClass
import com.jarvis.smarthomelauncher.core.SmartTile
import com.jarvis.smarthomelauncher.core.TileType
import com.jarvis.smarthomelauncher.integrations.GoogleCastLanClient
import com.jarvis.smarthomelauncher.integrations.JarvisLiveBridge
import com.jarvis.smarthomelauncher.widgets.radio.Models.DiscoverFeed
import com.jarvis.smarthomelauncher.widgets.radio.Models.HistoryTimeFilter
import com.jarvis.smarthomelauncher.widgets.radio.Models.NowPlayingMetadata
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioActivityStats
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioCategory
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioDashboardSnapshot
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioHistoryItem
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioProgrammeSlot
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioScheduleTriggerType
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioScheduledRoutine
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioStation
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioUiState
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioUserSettings
import com.jarvis.smarthomelauncher.widgets.radio.Models.StationSortOption
import com.jarvis.smarthomelauncher.widgets.radio.Services.LiveRadioService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class RadioDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_FAVOURITES (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                stream_url TEXT NOT NULL,
                genre TEXT NOT NULL,
                tags TEXT NOT NULL,
                country TEXT NOT NULL,
                country_code TEXT NOT NULL,
                favicon_url TEXT NOT NULL,
                homepage_url TEXT NOT NULL,
                bitrate_kbps INTEGER NOT NULL,
                codec TEXT NOT NULL,
                votes INTEGER NOT NULL,
                accent_hex INTEGER NOT NULL,
                badge_code TEXT NOT NULL,
                is_custom INTEGER NOT NULL,
                sort_index INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_HISTORY (
                id TEXT PRIMARY KEY,
                station_id TEXT NOT NULL,
                station_name TEXT NOT NULL,
                artist TEXT NOT NULL,
                track TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                accent_hex INTEGER NOT NULL,
                badge_code TEXT NOT NULL,
                favicon_url TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_DAILY_ACTIVITY (
                date_iso TEXT PRIMARY KEY,
                listening_ms INTEGER NOT NULL,
                played_station_ids TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_SETTINGS (
                setting_key TEXT PRIMARY KEY,
                setting_value TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_SCHEDULES (
                id TEXT PRIMARY KEY,
                station_id TEXT NOT NULL,
                station_name TEXT NOT NULL,
                stream_url TEXT NOT NULL,
                label TEXT NOT NULL,
                trigger_type TEXT NOT NULL,
                hour INTEGER NOT NULL,
                minute INTEGER NOT NULL,
                days_csv TEXT NOT NULL,
                is_enabled INTEGER NOT NULL,
                last_triggered_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    companion object {
        private const val DB_NAME = "clara_radio_widget_v1.db"
        private const val DB_VERSION = 1
        const val TABLE_FAVOURITES = "radio_favourites"
        const val TABLE_HISTORY = "radio_listening_history"
        const val TABLE_DAILY_ACTIVITY = "radio_daily_activity"
        const val TABLE_SETTINGS = "radio_settings"
        const val TABLE_SCHEDULES = "radio_schedules"
    }
}

class RadioRepository private constructor(
    context: Context,
    private val provider: LiveRadioService = LiveRadioService()
) {
    private val appContext = context.applicationContext
    private val dbHelper = RadioDatabaseHelper(appContext)
    private val dbLock = Any()

    // Application-scoped SupervisorJob ensures playback continues when RadioHubPopup closes
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow<RadioUiState>(RadioUiState.Loading)
    val uiState: StateFlow<RadioUiState> = _uiState.asStateFlow()

    private val _discoverFeed = MutableStateFlow(DiscoverFeed(isLoading = true))
    val discoverFeed: StateFlow<DiscoverFeed> = _discoverFeed.asStateFlow()

    private val liveMetadataByStationId = ConcurrentHashMap<String, NowPlayingMetadata>()

    @Volatile private var cachedCatalog: List<RadioStation> = emptyList()
    @Volatile private var cachedFavourites: List<RadioStation> = emptyList()
    @Volatile private var cachedSettings: RadioUserSettings = RadioUserSettings()
    @Volatile private var cachedSchedules: List<RadioScheduledRoutine> = emptyList()

    @Volatile private var activeSessionHistoryId: String? = null
    @Volatile private var activeSessionStationId: String? = null
    @Volatile private var activeSessionTrackSignature: String = ""
    @Volatile private var activeSessionElapsedMs: Long = 0L
    @Volatile private var unsavedDailyMillis: Long = 0L
    @Volatile private var lastObservedSecurityMode: SecurityCenterMode? = null

    init {
        ClaraInAppMusicPlayer.initContextIfNeeded(appContext)
        initializeRepository()
        observePlayerAndHouseholdFlows()
        startSessionTelemetryAndScheduleLoop()
    }

    private fun initializeRepository() {
        ioScope.launch {
            try {
                seedFavouritesFromBridgeIfEmpty()
                cachedSettings = readSettingsFromDb()
                cachedFavourites = readFavouritesFromDb()
                cachedSchedules = readSchedulesFromDb()
                val initialStations = provider.getBuiltInAndTopStations(
                    countryCode = cachedSettings.preferredCountryCode,
                    limit = 40
                )
                val favIds = cachedFavourites.map { it.id }.toSet()
                val favUrls = cachedFavourites.map { it.streamUrl.lowercase(Locale.UK) }.toSet()
                cachedCatalog = mergeWithFavourites(initialStations, cachedFavourites, favIds, favUrls)
                publishSnapshot()
                refreshLiveMetadataForVisibleStations()
                refreshDiscoverFeedInternal()
            } catch (e: Exception) {
                _uiState.value = RadioUiState.Error("Unable to retrieve radio stations")
            }
        }
    }

    private fun observePlayerAndHouseholdFlows() {
        ioScope.launch {
            ClaraInAppMusicPlayer.activeRadioStationFlow.collect { activeStream ->
                if (activeStream != null && cachedSettings.enableLiveTrackMetadata) {
                    val station = resolveStationFromStream(activeStream)
                    val meta = provider.fetchNowPlaying(station, allowStreamProbe = true)
                    liveMetadataByStationId[station.id] = meta
                    updateBridgeBannerIfTrackDetected(station, meta)
                }
                publishSnapshot()
            }
        }
        ioScope.launch {
            ClaraInAppMusicPlayer.isRadioPlayingFlow.collect { playing ->
                if (!playing) {
                    flushPendingDailyActivity()
                }
                publishSnapshot()
            }
        }
        ioScope.launch {
            JarvisLiveBridge.activeCastTargetNameFlow.collect {
                publishSnapshot()
            }
        }
        ioScope.launch {
            JarvisLiveBridge.securityCenterModeFlow.collect { newMode ->
                val prev = lastObservedSecurityMode
                lastObservedSecurityMode = newMode
                if (prev != null && prev != newMode) {
                    evaluateSecurityModeRoutines(newMode)
                }
            }
        }
    }

    private fun startSessionTelemetryAndScheduleLoop() {
        ioScope.launch {
            var tickSeconds = 0
            while (isActive) {
                delay(1_000L)
                tickSeconds++

                val activeStream = ClaraInAppMusicPlayer.activeRadioStationFlow.value
                val isPlaying = ClaraInAppMusicPlayer.isRadioPlayingFlow.value

                if (isPlaying && activeStream != null) {
                    if (activeSessionStationId != activeStream.id) {
                        flushPendingDailyActivity()
                        activeSessionStationId = activeStream.id
                        activeSessionElapsedMs = 0L
                        activeSessionHistoryId = null
                        activeSessionTrackSignature = ""
                    }
                    activeSessionElapsedMs += 1_000L
                    unsavedDailyMillis += 1_000L

                    val station = resolveStationFromStream(activeStream)
                    val currentMeta = liveMetadataByStationId[station.id]
                    recordOrUpdateListeningHistory(station, currentMeta, 1_000L)

                    if (unsavedDailyMillis >= 10_000L) {
                        flushPendingDailyActivity(station.id)
                    }

                    if (tickSeconds % 25 == 0 && cachedSettings.enableLiveTrackMetadata) {
                        val freshMeta = provider.fetchNowPlaying(station, allowStreamProbe = true)
                        liveMetadataByStationId[station.id] = freshMeta
                        updateBridgeBannerIfTrackDetected(station, freshMeta)
                    }

                    if (tickSeconds % 3 == 0) {
                        publishSnapshot()
                    }
                } else {
                    if (unsavedDailyMillis > 0L) {
                        flushPendingDailyActivity()
                    }
                }

                if (tickSeconds % 15 == 0) {
                    evaluateTimeScheduledAlarms()
                }
            }
        }
    }

    private fun updateBridgeBannerIfTrackDetected(station: RadioStation, meta: NowPlayingMetadata) {
        if (!ClaraInAppMusicPlayer.isRadioPlayingFlow.value) return
        if (ClaraInAppMusicPlayer.activeRadioStationFlow.value?.id != station.id) return
        if (meta.hasTrackInfo) {
            JarvisLiveBridge.activeTrackFlow.value = "${station.name} • ${meta.primaryDisplayLine}"
        }
    }

    fun playStation(station: RadioStation) {
        val stream = station.toRadioStationStream()
        val currentBridge = DeviceRepository.loadBridge(appContext)
        if (currentBridge.activeRadioStationId != station.id) {
            DeviceRepository.saveBridge(appContext, currentBridge.copy(activeRadioStationId = station.id))
        }
        ClaraInAppMusicPlayer.playRadioStation(stream, playbackScope)
        ioScope.launch {
            flushPendingDailyActivity(station.id)
            if (cachedSettings.enableLiveTrackMetadata) {
                val meta = provider.fetchNowPlaying(station, allowStreamProbe = true)
                liveMetadataByStationId[station.id] = meta
                updateBridgeBannerIfTrackDetected(station, meta)
            }
            publishSnapshot()
        }
    }

    fun togglePlayPause(station: RadioStation? = null) {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val activeStream = ClaraInAppMusicPlayer.activeRadioStationFlow.value
        val targetStation = station
            ?: activeStream?.let { resolveStationFromStream(it) }
            ?: cachedFavourites.firstOrNull()
            ?: cachedCatalog.firstOrNull()
            ?: return

        if (activeStream != null && activeStream.id == targetStation.id) {
            ClaraInAppMusicPlayer.toggleRadioStation(activeStream, audioManager, playbackScope)
        } else {
            playStation(targetStation)
        }
    }

    fun cycleStation(forward: Boolean) {
        val pool = cachedFavourites.ifEmpty { cachedCatalog }
        if (pool.isEmpty()) return
        ClaraInAppMusicPlayer.cycleRadioStation(
            stations = pool.map { it.toRadioStationStream() },
            forward = forward,
            scope = playbackScope
        )
    }

    fun selectTabletOutput() {
        ClaraInAppMusicPlayer.selectTabletSpeakerOutput(playbackScope)
    }

    fun selectCastOutput(targetName: String, endpoints: List<Pair<String, Int>>) {
        ClaraInAppMusicPlayer.selectCastSpeakerOutput(targetName, endpoints, playbackScope)
    }

    fun discoverCastSpeakers() {
        ioScope.launch {
            GoogleCastLanClient.discoverCastDevices(appContext, includeFullPortSweep = false)
        }
    }

    fun toggleFavourite(station: RadioStation, onBridgeUpdated: ((com.jarvis.smarthomelauncher.core.BridgeSettings) -> Unit)? = null) {
        ioScope.launch {
            val currentlyFav = isStationFavourite(station)
            synchronized(dbLock) {
                val db = dbHelper.writableDatabase
                if (currentlyFav) {
                    db.delete(
                        RadioDatabaseHelper.TABLE_FAVOURITES,
                        "id = ? OR LOWER(stream_url) = ?",
                        arrayOf(station.id, station.streamUrl.lowercase(Locale.UK))
                    )
                } else {
                    val nextIdx = cachedFavourites.size
                    db.insertWithOnConflict(
                        RadioDatabaseHelper.TABLE_FAVOURITES,
                        null,
                        stationToContentValues(station.copy(isFavourite = true), nextIdx),
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
            }
            cachedFavourites = readFavouritesFromDb()
            val favIds = cachedFavourites.map { it.id }.toSet()
            val favUrls = cachedFavourites.map { it.streamUrl.lowercase(Locale.UK) }.toSet()
            cachedCatalog = mergeWithFavourites(cachedCatalog, cachedFavourites, favIds, favUrls)

            val currentBridge = DeviceRepository.loadBridge(appContext)
            val updatedBridge = currentBridge.copy(
                customRadioStations = cachedFavourites.map { it.toRadioStationStream() }
            )
            DeviceRepository.saveBridge(appContext, updatedBridge)
            withContext(Dispatchers.Main) {
                onBridgeUpdated?.invoke(updatedBridge)
            }
            publishSnapshot()
            refreshDiscoverFeedInternal()
        }
    }

    fun addCustomStation(
        name: String,
        streamUrl: String,
        genre: String,
        badgeCode: String,
        onBridgeUpdated: ((com.jarvis.smarthomelauncher.core.BridgeSettings) -> Unit)? = null
    ) {
        val cleanName = name.trim()
        val cleanUrl = streamUrl.trim()
        if (cleanName.isBlank() || !cleanUrl.startsWith("http", ignoreCase = true)) return
        val custom = RadioStation(
            id = "custom_${cleanUrl.lowercase(Locale.UK).hashCode()}",
            name = cleanName,
            streamUrl = cleanUrl,
            genre = genre.trim().ifBlank { "Custom Live Radio" },
            tags = genre.lowercase(Locale.UK).split(",", " ").filter { it.length >= 2 },
            country = "Custom",
            countryCode = cachedSettings.preferredCountryCode,
            badgeCode = badgeCode.trim().take(4).uppercase(Locale.UK).ifBlank {
                cleanName.take(3).uppercase(Locale.UK)
            },
            isFavourite = true,
            isCustom = true
        )
        toggleFavourite(custom.copy(isFavourite = false), onBridgeUpdated)
    }

    fun updateSettings(newSettings: RadioUserSettings) {
        ioScope.launch {
            val prevCountry = cachedSettings.preferredCountryCode
            cachedSettings = newSettings
            writeSettingsToDb(newSettings)
            if (prevCountry != newSettings.preferredCountryCode) {
                val fresh = provider.getBuiltInAndTopStations(newSettings.preferredCountryCode, 40)
                val favIds = cachedFavourites.map { it.id }.toSet()
                val favUrls = cachedFavourites.map { it.streamUrl.lowercase(Locale.UK) }.toSet()
                cachedCatalog = mergeWithFavourites(fresh, cachedFavourites, favIds, favUrls)
                refreshDiscoverFeedInternal()
            }
            publishSnapshot()
        }
    }

    fun saveScheduledRoutine(routine: RadioScheduledRoutine) {
        ioScope.launch {
            synchronized(dbLock) {
                val db = dbHelper.writableDatabase
                val cv = ContentValues().apply {
                    put("id", routine.id)
                    put("station_id", routine.stationId)
                    put("station_name", routine.stationName)
                    put("stream_url", routine.streamUrl)
                    put("label", routine.label)
                    put("trigger_type", routine.triggerType.name)
                    put("hour", routine.hour.coerceIn(0, 23))
                    put("minute", routine.minute.coerceIn(0, 59))
                    put("days_csv", routine.daysOfWeek.sorted().joinToString(","))
                    put("is_enabled", if (routine.isEnabled) 1 else 0)
                    put("last_triggered_ms", routine.lastTriggeredEpochMs)
                }
                db.insertWithOnConflict(
                    RadioDatabaseHelper.TABLE_SCHEDULES,
                    null,
                    cv,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            cachedSchedules = readSchedulesFromDb()
            publishSnapshot()
        }
    }

    fun deleteScheduledRoutine(routineId: String) {
        ioScope.launch {
            synchronized(dbLock) {
                dbHelper.writableDatabase.delete(
                    RadioDatabaseHelper.TABLE_SCHEDULES,
                    "id = ?",
                    arrayOf(routineId)
                )
            }
            cachedSchedules = readSchedulesFromDb()
            publishSnapshot()
        }
    }

    fun addStationToDashboard(station: RadioStation, onResult: (Boolean) -> Unit = {}) {
        ioScope.launch {
            val added = runCatching {
                val tiles = DeviceRepository.loadTiles(appContext).toMutableList()
                val radioTile = SmartTile(
                    id = "radio_tile_${station.id}",
                    title = station.name,
                    roomOrSubtitle = station.genre.ifBlank { "Live Radio" },
                    type = TileType.MUSIC_PLAYER,
                    isActive = ClaraInAppMusicPlayer.activeRadioStationFlow.value?.id == station.id &&
                            ClaraInAppMusicPlayer.isRadioPlayingFlow.value,
                    isVisible = true,
                    isOnline = true,
                    volume = 60f,
                    serialNumber = station.id,
                    entityId = station.streamUrl,
                    sensorUnit = "RADIO_HERO",
                    deviceClassOverride = DeviceClass.MEDIA,
                    deviceSubtypeOverride = DeviceSubtype.RADIO,
                    serviceClassOverride = ServiceClass.RADIO,
                    capabilities = listOf(
                        DeviceCapabilityDescriptor(
                            key = "speaker_volume",
                            label = "Volume",
                            kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                            isWritable = true,
                            isPinnedToHero = true,
                            currentValue = "60",
                            minValue = 0f,
                            maxValue = 100f,
                            stepValue = 5f,
                            unit = "%"
                        )
                    )
                )
                DeviceRepository.upsertAndShowTilesOnDashboard(appContext, tiles, listOf(radioTile)) > 0
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                onResult(added)
            }
        }
    }

    fun clearListeningHistory() {
        ioScope.launch {
            synchronized(dbLock) {
                dbHelper.writableDatabase.delete(RadioDatabaseHelper.TABLE_HISTORY, null, null)
            }
            activeSessionHistoryId = null
            publishSnapshot()
        }
    }

    fun refreshAll() {
        ioScope.launch {
            if (cachedCatalog.isEmpty()) {
                _uiState.value = RadioUiState.Loading
            }
            runCatching {
                val fresh = provider.getBuiltInAndTopStations(cachedSettings.preferredCountryCode, 40)
                val favIds = cachedFavourites.map { it.id }.toSet()
                val favUrls = cachedFavourites.map { it.streamUrl.lowercase(Locale.UK) }.toSet()
                cachedCatalog = mergeWithFavourites(fresh, cachedFavourites, favIds, favUrls)
                publishSnapshot()
                refreshLiveMetadataForVisibleStations()
                refreshDiscoverFeedInternal()
            }.onFailure {
                if (cachedCatalog.isEmpty() && cachedFavourites.isEmpty()) {
                    _uiState.value = RadioUiState.Error("Unable to retrieve radio stations")
                }
            }
        }
    }

    suspend fun searchStations(query: String): List<RadioStation> {
        val results = provider.searchStations(query, null, 40)
        val favIds = cachedFavourites.map { it.id }.toSet()
        val favUrls = cachedFavourites.map { it.streamUrl.lowercase(Locale.UK) }.toSet()
        return decorateFavouriteFlags(results, favIds, favUrls)
    }

    suspend fun getStationsForCategory(category: RadioCategory): List<RadioStation> {
        val results = provider.getStationsByCategory(category, cachedSettings.preferredCountryCode, 32)
        val favIds = cachedFavourites.map { it.id }.toSet()
        val favUrls = cachedFavourites.map { it.streamUrl.lowercase(Locale.UK) }.toSet()
        return decorateFavouriteFlags(results, favIds, favUrls)
    }

    suspend fun fetchStationDetailsData(
        station: RadioStation
    ): Triple<NowPlayingMetadata, List<RadioProgrammeSlot>, List<RadioStation>> = coroutineScope {
        val nowDef = async { provider.fetchNowPlaying(station, allowStreamProbe = true) }
        val schedDef = async { provider.fetchStationSchedule(station) }
        val simDef = async {
            val primaryTag = station.tags.firstOrNull()
                ?: station.genre.split(",", "•", " ").firstOrNull { it.length >= 3 }
                ?: "pop"
            val matchedCat = RadioCategory.entries.firstOrNull {
                primaryTag.contains(it.searchTag, ignoreCase = true) ||
                        station.genre.contains(it.label, ignoreCase = true)
            } ?: RadioCategory.POP
            getStationsForCategory(matchedCat)
                .filterNot { it.id == station.id || it.streamUrl.equals(station.streamUrl, ignoreCase = true) }
                .take(8)
        }
        val meta = nowDef.await()
        liveMetadataByStationId[station.id] = meta
        Triple(meta, schedDef.await(), simDef.await())
    }

    fun getFilteredHistory(filter: HistoryTimeFilter): List<RadioHistoryItem> {
        val cutoffMs = when (filter) {
            HistoryTimeFilter.TODAY -> startOfDayEpochMs(0)
            HistoryTimeFilter.THIS_WEEK -> startOfDayEpochMs(-6)
            HistoryTimeFilter.THIS_MONTH -> startOfDayEpochMs(-29)
        }
        return readHistoryFromDb(sinceEpochMs = cutoffMs, limit = 150)
    }

    fun getStationHistory(stationId: String, limit: Int = 12): List<RadioHistoryItem> =
        readHistoryFromDb(sinceEpochMs = 0L, limit = 150)
            .filter { it.stationId == stationId }
            .take(limit)

    fun handleVoiceCommand(rawCommand: String): String? {
        val lower = rawCommand.lowercase(Locale.UK).trim()
        return when {
            lower in setOf("what's playing now", "whats playing now", "what song is playing", "what station is on") -> {
                val active = ClaraInAppMusicPlayer.activeRadioStationFlow.value
                if (active != null && ClaraInAppMusicPlayer.isRadioPlayingFlow.value) {
                    val meta = liveMetadataByStationId[active.id]
                    if (meta != null && meta.hasTrackInfo) {
                        "You are listening to ${active.name}, playing ${meta.primaryDisplayLine}."
                    } else {
                        "You are listening to ${active.name}."
                    }
                } else null
            }
            lower in setOf("play my favourite station", "play my favorite station", "start morning radio", "play radio") -> {
                val fav = cachedFavourites.firstOrNull() ?: cachedCatalog.firstOrNull()
                if (fav != null) {
                    playStation(fav)
                    "Playing ${fav.name}."
                } else null
            }
            lower.startsWith("play ") && (lower.contains("radio") || lower.contains("fm") || cachedFavourites.any {
                lower.contains(it.name.lowercase(Locale.UK))
            }) -> {
                val targetQuery = rawCommand.substring(5).trim()
                val matchedLocal = (cachedFavourites + cachedCatalog).firstOrNull {
                    it.name.contains(targetQuery, ignoreCase = true) ||
                            targetQuery.contains(it.name, ignoreCase = true)
                }
                if (matchedLocal != null) {
                    playStation(matchedLocal)
                    "Playing ${matchedLocal.name}."
                } else {
                    ioScope.launch {
                        searchStations(targetQuery).firstOrNull()?.let { playStation(it) }
                    }
                    "Searching live radio for $targetQuery."
                }
            }
            else -> null
        }
    }

    private suspend fun refreshLiveMetadataForVisibleStations() = coroutineScope {
        if (!cachedSettings.enableLiveTrackMetadata) return@coroutineScope
        val candidates = (cachedFavourites.take(6) + cachedCatalog.take(6))
            .distinctBy { it.id }
            .take(8)
        candidates.map { st ->
            async(Dispatchers.IO) {
                val isBbc = st.name.contains("BBC", ignoreCase = true)
                val meta = provider.fetchNowPlaying(st, allowStreamProbe = isBbc)
                if (meta.hasTrackInfo || meta.showOrPresenter.isNotBlank()) {
                    liveMetadataByStationId[st.id] = meta
                }
            }
        }
    }

    private suspend fun refreshDiscoverFeedInternal() = coroutineScope {
        val favIds = cachedFavourites.map { it.id }.toSet()
        val favUrls = cachedFavourites.map { it.streamUrl.lowercase(Locale.UK) }.toSet()

        val trendingDef = async { provider.getTrendingStations(20) }
        val areaDef = async { provider.getPopularInCountry(cachedSettings.preferredCountryCode, 20) }
        val newDef = async { provider.getNewStations(16) }

        val trending = decorateFavouriteFlags(trendingDef.await(), favIds, favUrls)
        val area = decorateFavouriteFlags(areaDef.await(), favIds, favUrls)
        val newest = decorateFavouriteFlags(newDef.await(), favIds, favUrls)

        val candidatePool = (cachedCatalog + area + trending).distinctBy { it.streamUrl.lowercase(Locale.UK) }
        val recommended = computeHabitRecommendations(candidatePool, maxItems = 12)

        _discoverFeed.value = DiscoverFeed(
            recommendedForYou = recommended,
            trendingStations = trending,
            popularInArea = area,
            newStations = newest,
            isLoading = false
        )
        publishSnapshot()
    }

    private fun computeHabitRecommendations(
        pool: List<RadioStation>,
        maxItems: Int
    ): List<RadioStation> {
        val history = readHistoryFromDb(sinceEpochMs = startOfDayEpochMs(-29), limit = 120)
        val playCountsByStation = history.groupingBy { it.stationId }.eachCount()
        val favTags = cachedFavourites
            .flatMap { it.tags + it.genre.lowercase(Locale.UK).split(",", " ", "•") }
            .map { it.trim() }
            .filter { it.length >= 3 }
            .groupingBy { it }
            .eachCount()

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeAffinityKeywords = when (hour) {
            in 5..10 -> setOf("news", "pop", "talk", "hits", "breakfast", "uk")
            in 11..17 -> setOf("pop", "rock", "indie", "dance", "80s", "90s")
            else -> setOf("chill", "chillout", "smooth", "classical", "jazz", "ambient", "soul")
        }

        val favIdSet = cachedFavourites.map { it.id }.toSet()

        return pool
            .sortedByDescending { candidate ->
                var score = 0
                if (candidate.id !in favIdSet) score += 25 // Prefer discovering non-pinned stations first
                score += (playCountsByStation[candidate.id] ?: 0) * 8
                val candidateTokens = (candidate.tags + candidate.genre.lowercase(Locale.UK).split(",", " ", "•"))
                    .map { it.trim() }
                for (tok in candidateTokens) {
                    score += (favTags[tok] ?: 0) * 10
                    if (tok in timeAffinityKeywords) score += 14
                }
                if (candidate.countryCode.equals(cachedSettings.preferredCountryCode, ignoreCase = true)) {
                    score += 12
                }
                score + (candidate.votes.coerceAtMost(10_000) / 1000)
            }
            .take(maxItems)
    }

    private fun evaluateTimeScheduledAlarms() {
        if (cachedSchedules.isEmpty()) return
        val cal = Calendar.getInstance()
        val nowMs = cal.timeInMillis
        val curHour = cal.get(Calendar.HOUR_OF_DAY)
        val curMin = cal.get(Calendar.MINUTE)
        val isoDow = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            else -> 7
        }

        for (routine in cachedSchedules) {
            if (!routine.isEnabled || routine.triggerType != RadioScheduleTriggerType.DAILY_ALARM) continue
            if (isoDow !in routine.daysOfWeek) continue
            if (routine.hour == curHour && routine.minute == curMin && (nowMs - routine.lastTriggeredEpochMs) > 65_000L) {
                triggerScheduledRadioRoutine(routine, nowMs)
            }
        }
    }

    private fun evaluateSecurityModeRoutines(newMode: SecurityCenterMode) {
        val matchingTrigger = when (newMode) {
            SecurityCenterMode.HOME -> RadioScheduleTriggerType.ARRIVING_HOME
            SecurityCenterMode.NIGHT -> RadioScheduleTriggerType.BEDTIME_ROUTINE
            SecurityCenterMode.AWAY -> RadioScheduleTriggerType.AWAY_ROUTINE
            else -> return
        }
        val nowMs = System.currentTimeMillis()
        cachedSchedules
            .filter { it.isEnabled && it.triggerType == matchingTrigger }
            .forEach { routine ->
                if ((nowMs - routine.lastTriggeredEpochMs) > 30_000L) {
                    triggerScheduledRadioRoutine(routine, nowMs)
                }
            }
    }

    private fun triggerScheduledRadioRoutine(routine: RadioScheduledRoutine, nowMs: Long) {
        val targetStation = (cachedFavourites + cachedCatalog).firstOrNull { it.id == routine.stationId }
            ?: RadioStation(
                id = routine.stationId,
                name = routine.stationName,
                streamUrl = routine.streamUrl
            )
        saveScheduledRoutine(routine.copy(lastTriggeredEpochMs = nowMs))
        playStation(targetStation)
        JarvisLiveBridge.emitPlatformEvent(
            JarvisPlatformEvent(
                category = JarvisEventCategory.MEDIA,
                sourceDeviceId = targetStation.id,
                sourceDeviceTitle = targetStation.name,
                eventType = "RADIO_ROUTINE_TRIGGERED",
                summary = "Started ${targetStation.name} (${routine.label})"
            )
        )
    }

    private fun publishSnapshot() {
        val bridge = DeviceRepository.loadBridge(appContext)
        val activeStream = ClaraInAppMusicPlayer.activeRadioStationFlow.value
        val isPlaying = ClaraInAppMusicPlayer.isRadioPlayingFlow.value
        val favs = cachedFavourites
        val all = cachedCatalog.ifEmpty { favs }

        if (all.isEmpty() && favs.isEmpty()) {
            _uiState.value = RadioUiState.Empty("No Stations Configured")
            return
        }

        val currentStation = when {
            activeStream != null -> resolveStationFromStream(activeStream)
            bridge.activeRadioStationId.isNotBlank() ->
                (favs + all).firstOrNull { it.id == bridge.activeRadioStationId } ?: favs.firstOrNull() ?: all.firstOrNull()
            else -> favs.firstOrNull() ?: all.firstOrNull()
        }

        val nowPlaying = currentStation?.let { st ->
            liveMetadataByStationId[st.id] ?: NowPlayingMetadata(
                stationId = st.id,
                stationName = st.name,
                showOrPresenter = st.genre
            )
        }

        val recent = readHistoryFromDb(sinceEpochMs = 0L, limit = 20)
        val stats = buildActivityStats(favCount = favs.size)
        val recs = computeHabitRecommendations(all, maxItems = 6)

        val summaryLine = when {
            isPlaying && currentStation != null ->
                "Listening to ${currentStation.name}"
            stats.todayListeningMs >= 60_000L && stats.mostPlayedStationThisWeek.isNotBlank() ->
                "${stats.todayListeningFormatted} listened today • Top: ${stats.mostPlayedStationThisWeek}"
            stats.mostPlayedStationThisWeek.isNotBlank() ->
                "Most played station this week: ${stats.mostPlayedStationThisWeek}"
            favs.isNotEmpty() ->
                "${favs.size} favourite ${if (favs.size == 1) "station" else "stations"} ready"
            else ->
                "Tap any station to start live broadcast"
        }

        _uiState.value = RadioUiState.Ready(
            RadioDashboardSnapshot(
                currentStation = currentStation,
                isPlaying = isPlaying,
                activeOutputName = JarvisLiveBridge.activeCastTargetNameFlow.value,
                nowPlaying = nowPlaying,
                sessionElapsedMs = if (isPlaying) activeSessionElapsedMs else 0L,
                favourites = favs,
                allStations = all,
                liveStationsNowPlaying = liveMetadataByStationId.toMap(),
                recentlyPlayed = recent,
                recommendedStations = recs,
                activity = stats,
                dashboardSummary = summaryLine,
                settings = cachedSettings,
                scheduledRoutines = cachedSchedules
            )
        )
    }

    private fun resolveStationFromStream(stream: com.jarvis.smarthomelauncher.core.RadioStationStream): RadioStation {
        val existing = (cachedFavourites + cachedCatalog).firstOrNull {
            it.id == stream.id || it.streamUrl.equals(stream.streamUrl, ignoreCase = true)
        }
        return existing ?: RadioStation.fromRadioStationStream(stream, isFavourite = isStreamFavourite(stream.streamUrl))
    }

    private fun isStationFavourite(station: RadioStation): Boolean =
        cachedFavourites.any {
            it.id == station.id || it.streamUrl.equals(station.streamUrl, ignoreCase = true)
        }

    private fun isStreamFavourite(url: String): Boolean =
        cachedFavourites.any { it.streamUrl.equals(url, ignoreCase = true) }

    private fun seedFavouritesFromBridgeIfEmpty() {
        synchronized(dbLock) {
            val db = dbHelper.readableDatabase
            val count = db.rawQuery("SELECT COUNT(*) FROM ${RadioDatabaseHelper.TABLE_FAVOURITES}", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
            if (count > 0) return
            val seedFlag = db.rawQuery(
                "SELECT setting_value FROM ${RadioDatabaseHelper.TABLE_SETTINGS} WHERE setting_key = 'seeded_v1'",
                null
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (seedFlag == "true") return

            val bridgeStations = DeviceRepository.loadBridge(appContext).customRadioStations
                .ifEmpty { DEFAULT_RADIO_STATIONS }
            val wdb = dbHelper.writableDatabase
            wdb.beginTransaction()
            try {
                bridgeStations.forEachIndexed { idx, stream ->
                    val st = RadioStation.fromRadioStationStream(stream, isFavourite = true)
                    wdb.insertWithOnConflict(
                        RadioDatabaseHelper.TABLE_FAVOURITES,
                        null,
                        stationToContentValues(st, idx),
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
                wdb.insertWithOnConflict(
                    RadioDatabaseHelper.TABLE_SETTINGS,
                    null,
                    ContentValues().apply {
                        put("setting_key", "seeded_v1")
                        put("setting_value", "true")
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                wdb.setTransactionSuccessful()
            } finally {
                wdb.endTransaction()
            }
        }
    }

    private fun stationToContentValues(st: RadioStation, sortIndex: Int): ContentValues =
        ContentValues().apply {
            put("id", st.id)
            put("name", st.name)
            put("stream_url", st.streamUrl)
            put("genre", st.genre)
            put("tags", st.tags.joinToString(","))
            put("country", st.country)
            put("country_code", st.countryCode)
            put("favicon_url", st.faviconUrl)
            put("homepage_url", st.homepageUrl)
            put("bitrate_kbps", st.bitrateKbps)
            put("codec", st.codec)
            put("votes", st.votes)
            put("accent_hex", st.accentColorHex)
            put("badge_code", st.badgeCode)
            put("is_custom", if (st.isCustom) 1 else 0)
            put("sort_index", sortIndex)
        }

    private fun readFavouritesFromDb(): List<RadioStation> = synchronized(dbLock) {
        val list = mutableListOf<RadioStation>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT id, name, stream_url, genre, tags, country, country_code, favicon_url, homepage_url, bitrate_kbps, codec, votes, accent_hex, badge_code, is_custom FROM ${RadioDatabaseHelper.TABLE_FAVOURITES} ORDER BY sort_index ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    RadioStation(
                        id = c.getString(0),
                        name = c.getString(1),
                        streamUrl = c.getString(2),
                        genre = c.getString(3),
                        tags = c.getString(4).split(",").filter { it.isNotBlank() },
                        country = c.getString(5),
                        countryCode = c.getString(6),
                        faviconUrl = c.getString(7),
                        homepageUrl = c.getString(8),
                        bitrateKbps = c.getInt(9),
                        codec = c.getString(10),
                        votes = c.getInt(11),
                        accentColorHex = c.getLong(12),
                        badgeCode = c.getString(13),
                        isFavourite = true,
                        isCustom = c.getInt(14) == 1
                    )
                )
            }
        }
        list
    }

    private fun recordOrUpdateListeningHistory(
        station: RadioStation,
        meta: NowPlayingMetadata?,
        deltaMs: Long
    ) {
        val track = meta?.track?.trim().orEmpty().ifBlank { station.genre.ifBlank { "Live Broadcast" } }
        val artist = meta?.artist?.trim().orEmpty().ifBlank { station.name }
        val sig = "${station.id}|$artist|$track"

        synchronized(dbLock) {
            val db = dbHelper.writableDatabase
            if (sig != activeSessionTrackSignature || activeSessionHistoryId == null) {
                val newId = "hist_${System.currentTimeMillis()}_${station.id.hashCode()}"
                activeSessionHistoryId = newId
                activeSessionTrackSignature = sig
                val cv = ContentValues().apply {
                    put("id", newId)
                    put("station_id", station.id)
                    put("station_name", station.name)
                    put("artist", artist)
                    put("track", track)
                    put("timestamp_ms", System.currentTimeMillis())
                    put("duration_ms", deltaMs)
                    put("accent_hex", station.accentColorHex)
                    put("badge_code", station.badgeCode)
                    put("favicon_url", station.faviconUrl)
                }
                db.insertWithOnConflict(
                    RadioDatabaseHelper.TABLE_HISTORY,
                    null,
                    cv,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                db.execSQL(
                    "DELETE FROM ${RadioDatabaseHelper.TABLE_HISTORY} WHERE id NOT IN (SELECT id FROM ${RadioDatabaseHelper.TABLE_HISTORY} ORDER BY timestamp_ms DESC LIMIT 250)"
                )
            } else {
                val histId = activeSessionHistoryId ?: return
                db.execSQL(
                    "UPDATE ${RadioDatabaseHelper.TABLE_HISTORY} SET duration_ms = duration_ms + ? WHERE id = ?",
                    arrayOf<Any>(deltaMs, histId)
                )
            }
        }
    }

    private fun flushPendingDailyActivity(stationId: String? = activeSessionStationId) {
        val delta = unsavedDailyMillis
        unsavedDailyMillis = 0L
        if (delta <= 0L && stationId.isNullOrBlank()) return

        val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date())
        synchronized(dbLock) {
            val db = dbHelper.writableDatabase
            var existingMs = 0L
            val stationSet = LinkedHashSet<String>()
            db.rawQuery(
                "SELECT listening_ms, played_station_ids FROM ${RadioDatabaseHelper.TABLE_DAILY_ACTIVITY} WHERE date_iso = ?",
                arrayOf(todayIso)
            ).use { c ->
                if (c.moveToFirst()) {
                    existingMs = c.getLong(0)
                    c.getString(1).split(",").filter { it.isNotBlank() }.forEach { stationSet.add(it) }
                }
            }
            if (!stationId.isNullOrBlank()) stationSet.add(stationId)
            val cv = ContentValues().apply {
                put("date_iso", todayIso)
                put("listening_ms", existingMs + delta)
                put("played_station_ids", stationSet.joinToString(","))
            }
            db.insertWithOnConflict(
                RadioDatabaseHelper.TABLE_DAILY_ACTIVITY,
                null,
                cv,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    private fun buildActivityStats(favCount: Int): RadioActivityStats = synchronized(dbLock) {
        val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date())
        val db = dbHelper.readableDatabase

        var todayMs = 0L
        var todayPlayedCount = 0
        db.rawQuery(
            "SELECT listening_ms, played_station_ids FROM ${RadioDatabaseHelper.TABLE_DAILY_ACTIVITY} WHERE date_iso = ?",
            arrayOf(todayIso)
        ).use { c ->
            if (c.moveToFirst()) {
                todayMs = c.getLong(0) + unsavedDailyMillis
                todayPlayedCount = c.getString(1).split(",").count { it.isNotBlank() }
            } else if (unsavedDailyMillis > 0L) {
                todayMs = unsavedDailyMillis
                todayPlayedCount = 1
            }
        }

        val weekCutoff = startOfDayEpochMs(-6)
        val weekStations = HashSet<String>()
        val durationByStationName = HashMap<String, Long>()
        db.rawQuery(
            "SELECT station_id, station_name, duration_ms FROM ${RadioDatabaseHelper.TABLE_HISTORY} WHERE timestamp_ms >= ?",
            arrayOf(weekCutoff.toString())
        ).use { c ->
            while (c.moveToNext()) {
                weekStations.add(c.getString(0))
                val name = c.getString(1)
                val dur = c.getLong(2)
                durationByStationName[name] = (durationByStationName[name] ?: 0L) + dur
            }
        }

        val mostPlayed = durationByStationName.maxByOrNull { it.value }?.key.orEmpty()
        RadioActivityStats(
            todayListeningMs = todayMs,
            favouriteCount = favCount,
            stationsPlayedToday = todayPlayedCount,
            stationsPlayedThisWeek = weekStations.size,
            mostPlayedStationThisWeek = mostPlayed
        )
    }

    private fun readHistoryFromDb(sinceEpochMs: Long, limit: Int): List<RadioHistoryItem> = synchronized(dbLock) {
        val list = mutableListOf<RadioHistoryItem>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT id, station_id, station_name, artist, track, timestamp_ms, duration_ms, accent_hex, badge_code, favicon_url FROM ${RadioDatabaseHelper.TABLE_HISTORY} WHERE timestamp_ms >= ? ORDER BY timestamp_ms DESC LIMIT ?",
            arrayOf(sinceEpochMs.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    RadioHistoryItem(
                        id = c.getString(0),
                        stationId = c.getString(1),
                        stationName = c.getString(2),
                        artist = c.getString(3),
                        track = c.getString(4),
                        timestampEpochMs = c.getLong(5),
                        durationMs = c.getLong(6),
                        accentColorHex = c.getLong(7),
                        badgeCode = c.getString(8),
                        faviconUrl = c.getString(9)
                    )
                )
            }
        }
        list
    }

    private fun readSettingsFromDb(): RadioUserSettings = synchronized(dbLock) {
        val map = HashMap<String, String>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT setting_key, setting_value FROM ${RadioDatabaseHelper.TABLE_SETTINGS}",
            null
        ).use { c ->
            while (c.moveToNext()) {
                map[c.getString(0)] = c.getString(1)
            }
        }
        val sortOpt = runCatching {
            StationSortOption.valueOf(map["default_sort"] ?: StationSortOption.FAVOURITES.name)
        }.getOrDefault(StationSortOption.FAVOURITES)
        RadioUserSettings(
            maxWidgetFavourites = (map["max_favs"]?.toIntOrNull() ?: 6).coerceIn(3, 6),
            preferredCountryCode = map["country_code"]?.ifBlank { "GB" } ?: "GB",
            enableLiveTrackMetadata = map["live_meta"]?.toBooleanStrictOrNull() ?: true,
            showRecommendationsOnWidget = map["show_recs"]?.toBooleanStrictOrNull() ?: true,
            defaultSortOption = sortOpt
        )
    }

    private fun writeSettingsToDb(settings: RadioUserSettings) = synchronized(dbLock) {
        val db = dbHelper.writableDatabase
        val entries = mapOf(
            "max_favs" to settings.maxWidgetFavourites.coerceIn(3, 6).toString(),
            "country_code" to settings.preferredCountryCode,
            "live_meta" to settings.enableLiveTrackMetadata.toString(),
            "show_recs" to settings.showRecommendationsOnWidget.toString(),
            "default_sort" to settings.defaultSortOption.name
        )
        db.beginTransaction()
        try {
            for ((k, v) in entries) {
                db.insertWithOnConflict(
                    RadioDatabaseHelper.TABLE_SETTINGS,
                    null,
                    ContentValues().apply {
                        put("setting_key", k)
                        put("setting_value", v)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun readSchedulesFromDb(): List<RadioScheduledRoutine> = synchronized(dbLock) {
        val list = mutableListOf<RadioScheduledRoutine>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT id, station_id, station_name, stream_url, label, trigger_type, hour, minute, days_csv, is_enabled, last_triggered_ms FROM ${RadioDatabaseHelper.TABLE_SCHEDULES}",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val trig = runCatching {
                    RadioScheduleTriggerType.valueOf(c.getString(5))
                }.getOrDefault(RadioScheduleTriggerType.DAILY_ALARM)
                val days = c.getString(8).split(",").mapNotNull { it.toIntOrNull() }.toSet()
                    .ifEmpty { setOf(1, 2, 3, 4, 5) }
                list.add(
                    RadioScheduledRoutine(
                        id = c.getString(0),
                        stationId = c.getString(1),
                        stationName = c.getString(2),
                        streamUrl = c.getString(3),
                        label = c.getString(4),
                        triggerType = trig,
                        hour = c.getInt(6),
                        minute = c.getInt(7),
                        daysOfWeek = days,
                        isEnabled = c.getInt(9) == 1,
                        lastTriggeredEpochMs = c.getLong(10)
                    )
                )
            }
        }
        list
    }

    private fun mergeWithFavourites(
        catalog: List<RadioStation>,
        favs: List<RadioStation>,
        favIds: Set<String>,
        favUrls: Set<String>
    ): List<RadioStation> {
        val decoratedCatalog = decorateFavouriteFlags(catalog, favIds, favUrls)
        val seenUrls = HashSet<String>()
        return buildList {
            for (f in favs) {
                if (seenUrls.add(f.streamUrl.lowercase(Locale.UK))) add(f.copy(isFavourite = true))
            }
            for (c in decoratedCatalog) {
                if (seenUrls.add(c.streamUrl.lowercase(Locale.UK))) add(c)
            }
        }
    }

    private fun decorateFavouriteFlags(
        stations: List<RadioStation>,
        favIds: Set<String>,
        favUrls: Set<String>
    ): List<RadioStation> = stations.map { st ->
        val isFav = st.id in favIds || st.streamUrl.lowercase(Locale.UK) in favUrls
        if (st.isFavourite == isFav) st else st.copy(isFavourite = isFav)
    }

    private fun startOfDayEpochMs(dayOffset: Int): Long {
        return Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    companion object {
        @Volatile
        private var instance: RadioRepository? = null

        fun getInstance(context: Context): RadioRepository {
            return instance ?: synchronized(this) {
                instance ?: RadioRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}