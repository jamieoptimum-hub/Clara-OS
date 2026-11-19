package com.jarvis.smarthomelauncher.ui.modals

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.integrations.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class EntertainmentHubTab(val label: String, val icon: ImageVector) {
    OVERVIEW("Overview", Icons.Outlined.Dashboard),
    UPCOMING("Upcoming", Icons.Outlined.Event),
    MY_SHOWS("My Shows", Icons.Outlined.LiveTv),
    MOVIES("Movies", Icons.Outlined.Movie),
    DISCOVER("Discover", Icons.Outlined.Explore)
}

private enum class MyShowsSortOption(val label: String) {
    RECENTLY_WATCHED("Recently Watched"),
    NEXT_AIRING("Next Airing"),
    ALPHABETICAL("Alphabetical"),
    PROGRESS("Progress")
}

private enum class ShowDetailSection(val label: String) {
    OVERVIEW("Overview"),
    EPISODES("Episodes"),
    UPCOMING("Upcoming"),
    CAST("Cast"),
    RECOMMENDATIONS("Recommendations")
}

private object EntertainmentArtworkCache {
    private val cache = object : LruCache<String, ImageBitmap>(96) {}
    fun get(url: String): ImageBitmap? = synchronized(cache) { cache.get(url) }
    fun put(url: String, bmp: ImageBitmap) = synchronized(cache) { cache.put(url, bmp) }

    suspend fun fetchBitmap(imageUrl: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) return@withContext null
        get(imageUrl)?.let { return@withContext it }
        val loaded = runCatching {
            val conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            } else null
        }.getOrNull()
        if (loaded != null) {
            put(imageUrl, loaded)
        }
        loaded
    }
}

@Composable
private fun EntertainmentArtworkBox(
    imageUrl: String,
    fallbackIcon: ImageVector,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    contentScale: ContentScale = ContentScale.Crop
) {
    var bitmap by remember(imageUrl) {
        mutableStateOf(if (imageUrl.isNotBlank()) EntertainmentArtworkCache.get(imageUrl) else null)
    }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isBlank() || bitmap != null) return@LaunchedEffect
        bitmap = EntertainmentArtworkCache.fetchBitmap(imageUrl)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(GhTileSurfaceElevated)
            .border(1.dp, GhCardBorder, RoundedCornerShape(cornerRadius))
    ) {
        val currentBmp = bitmap
        if (currentBmp != null) {
            Image(
                bitmap = currentBmp,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = GhTextSecondary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
fun EntertainmentHubModal(
    allTiles: List<SmartTile>,
    bridgeSettings: BridgeSettings,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        ClaraEntertainmentStore.ensureInitialized(context)
    }

    val allItems by ClaraEntertainmentStore.items.collectAsState()
    var activeTab by remember { mutableStateOf(EntertainmentHubTab.OVERVIEW) }
    var selectedDetailItemId by remember { mutableStateOf<String?>(null) }
    var previewUnsavedItem by remember { mutableStateOf<EntertainmentMediaItem?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }

    var discoverFeed by remember { mutableStateOf(EntertainmentDiscoverFeed()) }
    var isLoadingDiscover by remember { mutableStateOf(false) }

    LaunchedEffect(allItems.size) {
        isLoadingDiscover = true
        discoverFeed = EntertainmentDirectoryClient.fetchDiscoverFeed(context, allItems)
        isLoadingDiscover = false
    }

    val snapshot = remember(allItems) {
        ClaraEntertainmentStore.buildDashboardSnapshot(allItems)
    }

    val activeTvTile = remember(allTiles) {
        allTiles.firstOrNull { it.type == TileType.LG_TV || it.deviceSubtype == DeviceSubtype.TV }
    }

    fun launchOnLgTv(title: String, streamer: String, appIdHint: String, contentIdHint: String) {
        val tv = activeTvTile ?: return
        val overrideWithIptv = ClaraEntertainmentStore.getOverrideWithIptv(context)
        val (fallbackApp, fallbackContent) = EntertainmentDirectoryClient.resolveLgTvAppTarget(streamer, title)
        val targetApp = if (overrideWithIptv) {
            bridgeSettings.footballTvProviderConfig.effectivePlayerAppId
        } else {
            appIdHint.ifBlank { fallbackApp }
        }
        LgThinQWebOsClient.launchAppShortcut(
            context = context,
            tile = tv,
            bridge = bridgeSettings,
            shortcut = LgTvAppShortcut(
                label = title,
                appId = targetApp,
                contentId = contentIdHint.ifBlank { fallbackContent },
                autoConfirmSelect = true
            ),
            scope = scope
        )
    }

    fun trackNewMediaItem(seed: EntertainmentMediaItem) {
        scope.launch {
            ClaraEntertainmentStore.upsertItem(context, seed.copy(isFavourite = true))
            val hydrated = EntertainmentDirectoryClient.hydrateItemDetails(context, seed.copy(isFavourite = true))
            ClaraEntertainmentStore.upsertItem(context, hydrated)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            color = GhBackground,
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.2.dp, GhCardBorder),
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .fillMaxHeight(0.90f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Top Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(GhAccentGreen.copy(alpha = 0.18f))
                                .border(1.dp, GhAccentGreen.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Movie,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Entertainment Hub",
                                color = GhTextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = GhFont
                            )
                            Text(
                                text = snapshot.aiSummaryLine,
                                color = GhAccentGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { activeTab = EntertainmentHubTab.DISCOVER },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = GhAccentGreen.copy(alpha = 0.18f),
                                contentColor = GhAccentGreen
                            )
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Find Shows & Movies", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        IconButton(
                            onClick = {
                                if (!isSyncing && allItems.isNotEmpty()) {
                                    scope.launch {
                                        isSyncing = true
                                        val refreshed = allItems.mapNotNull {
                                            runCatching { EntertainmentDirectoryClient.hydrateItemDetails(context, it) }.getOrNull()
                                        }
                                        ClaraEntertainmentStore.mergeRefreshedMetadata(context, refreshed)
                                        isSyncing = false
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Sync Schedules",
                                tint = if (isSyncing) GhAccentGreen else GhTextSecondary
                            )
                        }

                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "Hub Settings",
                                tint = GhTextSecondary
                            )
                        }

                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Entertainment Hub",
                                tint = GhTextPrimary
                            )
                        }
                    }
                }

                // 5-Tab Navigation Pill Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GhTileSurface)
                        .border(1.dp, GhCardBorder, RoundedCornerShape(16.dp))
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    EntertainmentHubTab.entries.forEach { tab ->
                        val selected = activeTab == tab
                        Surface(
                            color = if (selected) GhAccentGreen else Color.Transparent,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable { activeTab = tab }
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    tint = if (selected) Color(0xFF141518) else GhTextSecondary,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = tab.label,
                                    color = if (selected) Color(0xFF141518) else GhTextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }

                // Active Tab Body
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (activeTab) {
                        EntertainmentHubTab.OVERVIEW -> OverviewTabContent(
                            allItems = allItems,
                            snapshot = snapshot,
                            hasTv = activeTvTile != null,
                            onSelectItem = { selectedDetailItemId = it },
                            onGoToDiscover = { activeTab = EntertainmentHubTab.DISCOVER },
                            onLaunchTv = ::launchOnLgTv
                        )

                        EntertainmentHubTab.UPCOMING -> UpcomingTabContent(
                            allItems = allItems,
                            hasTv = activeTvTile != null,
                            onSelectItem = { selectedDetailItemId = it },
                            onGoToDiscover = { activeTab = EntertainmentHubTab.DISCOVER },
                            onLaunchTv = ::launchOnLgTv
                        )

                        EntertainmentHubTab.MY_SHOWS -> MyShowsTabContent(
                            allItems = allItems,
                            onSelectItem = { selectedDetailItemId = it },
                            onGoToDiscover = { activeTab = EntertainmentHubTab.DISCOVER }
                        )

                        EntertainmentHubTab.MOVIES -> MoviesTabContent(
                            allItems = allItems,
                            discoverFeed = discoverFeed,
                            hasTv = activeTvTile != null,
                            onSelectItem = { id, unsaved ->
                                if (unsaved != null) previewUnsavedItem = unsaved else selectedDetailItemId = id
                            },
                            onTrackItem = ::trackNewMediaItem,
                            onLaunchTv = ::launchOnLgTv
                        )

                        EntertainmentHubTab.DISCOVER -> DiscoverTabContent(
                            allItems = allItems,
                            discoverFeed = discoverFeed,
                            isLoadingFeed = isLoadingDiscover,
                            onOpenItemDetails = { candidate ->
                                val existing = allItems.firstOrNull {
                                    it.id == candidate.id ||
                                            (candidate.tmdbId > 0 && it.tmdbId == candidate.tmdbId && it.kind == candidate.kind) ||
                                            (candidate.tvmazeId > 0 && it.tvmazeId == candidate.tvmazeId && it.kind == candidate.kind)
                                }
                                if (existing != null) {
                                    selectedDetailItemId = existing.id
                                } else {
                                    previewUnsavedItem = candidate
                                }
                            },
                            onTrackItem = ::trackNewMediaItem
                        )
                    }
                }
            }
        }
    }

    // Show / Movie Details Popup (for tracked or previewed item)
    val trackedDetailTarget = remember(selectedDetailItemId, allItems) {
        selectedDetailItemId?.let { id -> allItems.firstOrNull { it.id == id } }
    }
    val activeDetailItem = trackedDetailTarget ?: previewUnsavedItem
    if (activeDetailItem != null) {
        EntertainmentItemDetailsDialog(
            item = activeDetailItem,
            isTracked = trackedDetailTarget != null,
            hasTv = activeTvTile != null,
            onTrackNow = {
                trackNewMediaItem(activeDetailItem)
                previewUnsavedItem = null
                selectedDetailItemId = activeDetailItem.id
            },
            onLaunchTv = {
                launchOnLgTv(
                    title = activeDetailItem.title,
                    streamer = activeDetailItem.primaryStreamer,
                    appIdHint = activeDetailItem.lgAppIdHint,
                    contentIdHint = activeDetailItem.lgContentIdHint
                )
            },
            onTrackRecommended = ::trackNewMediaItem,
            onDismiss = {
                selectedDetailItemId = null
                previewUnsavedItem = null
            }
        )
    }

    if (showSettingsDialog) {
        EntertainmentHubSettingsDialog(
            onSaved = {
                scope.launch {
                    isLoadingDiscover = true
                    discoverFeed = EntertainmentDirectoryClient.fetchDiscoverFeed(context, allItems)
                    isLoadingDiscover = false
                }
            },
            onDismiss = { showSettingsDialog = false }
        )
    }
}

// ---------------------------------------------------------------------------
// TAB 1: OVERVIEW PAGE
// ---------------------------------------------------------------------------
@Composable
private fun OverviewTabContent(
    allItems: List<EntertainmentMediaItem>,
    snapshot: EntertainmentDashboardSnapshot,
    hasTv: Boolean,
    onSelectItem: (String) -> Unit,
    onGoToDiscover: () -> Unit,
    onLaunchTv: (String, String, String, String) -> Unit
) {
    val isoFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.UK) }
    val todayIso = remember { isoFmt.format(Date()) }
    val tomorrowIso = remember {
        isoFmt.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.time)
    }
    val fourteenDaysAgoMs = remember {
        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -14) }.timeInMillis
    }
    val nowMs = System.currentTimeMillis()
    var selectedCalendarIso by remember { mutableStateOf(todayIso) }

    val tomorrowReleases = remember(allItems) {
        collectScheduledReleases(allItems, minEpochMs = 0L)
            .filter { it.releaseDateIso == tomorrowIso }
    }

    val recentlyReleased = remember(allItems) {
        val list = mutableListOf<EntertainmentScheduledRelease>()
        for (item in allItems.filter { it.lifecycleStatus == EntertainmentItemStatus.ACTIVE && it.isFavourite }) {
            if (item.kind == EntertainmentMediaKind.TV_SHOW) {
                item.episodes
                    .filter { it.seasonNumber > 0 && it.airTimestampEpochMs in fourteenDaysAgoMs..nowMs }
                    .forEach { ep ->
                        list.add(
                            EntertainmentScheduledRelease(
                                itemId = item.id,
                                title = item.title,
                                kind = EntertainmentMediaKind.TV_SHOW,
                                episodeCode = ep.episodeCode,
                                episodeTitle = ep.title,
                                releaseDateIso = ep.airDateIso,
                                releaseEpochMs = ep.airTimestampEpochMs,
                                countdownLabel = "Released ${ep.airDateIso}",
                                primaryStreamer = item.primaryStreamer,
                                lgAppIdHint = item.lgAppIdHint,
                                lgContentIdHint = item.lgContentIdHint,
                                posterUrl = item.posterUrl,
                                accentColorHex = item.accentColorHex
                            )
                        )
                    }
            } else if (item.nextAirTimestampEpochMs in fourteenDaysAgoMs..nowMs) {
                list.add(
                    EntertainmentScheduledRelease(
                        itemId = item.id,
                        title = item.title,
                        kind = EntertainmentMediaKind.MOVIE,
                        episodeCode = "Movie",
                        episodeTitle = "",
                        releaseDateIso = item.releaseDateIso,
                        releaseEpochMs = item.nextAirTimestampEpochMs,
                        countdownLabel = "Released ${item.releaseDateIso}",
                        primaryStreamer = item.primaryStreamer,
                        lgAppIdHint = item.lgAppIdHint,
                        lgContentIdHint = item.lgContentIdHint,
                        posterUrl = item.posterUrl,
                        accentColorHex = item.accentColorHex
                    )
                )
            }
        }
        list.sortedByDescending { it.releaseEpochMs }.take(10)
    }

    if (allItems.isEmpty()) {
        EmptyEntertainmentPrompt(
            title = "Your Entertainment Hub Is Ready",
            subtitle = "No demo data is loaded. Search and favourite your TV shows and movies to populate your 28-day schedule.",
            onAction = onGoToDiscover
        )
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 4 Statistics Cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OverviewStatTile("Tracked Shows", snapshot.trackedShowCount.toString(), GhAccentGreen, Modifier.weight(1f))
                OverviewStatTile("Tracked Movies", snapshot.trackedMovieCount.toString(), Color(0xFF00E5FF), Modifier.weight(1f))
                OverviewStatTile("Upcoming Releases", snapshot.upcoming28DayCount.toString(), Color(0xFFFFAB40), Modifier.weight(1f))
                OverviewStatTile("Unwatched Episodes", snapshot.unwatchedEpisodeCount.toString(), Color(0xFFC084FC), Modifier.weight(1f))
            }
        }

        // Interactive 28-Day Calendar View
        if (snapshot.heatmap28Days.size == 28) {
            item {
                Surface(
                    color = GhTileSurface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, GhCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "28-Day Release Calendar",
                            color = GhTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            snapshot.heatmap28Days.take(7).forEach { cell ->
                                Text(
                                    text = cell.dayShort.uppercase(Locale.UK),
                                    color = GhTextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        for (row in 0 until 4) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (col in 0 until 7) {
                                    val cell = snapshot.heatmap28Days[row * 7 + col]
                                    val isSelected = cell.dateIso == selectedCalendarIso
                                    val bg = when (cell.intensity) {
                                        HeatmapIntensity.NONE -> GhTileSurfaceElevated
                                        HeatmapIntensity.SINGLE -> GhAccentGreen.copy(alpha = 0.30f)
                                        HeatmapIntensity.MULTIPLE -> Color(0xFFFF9F1C).copy(alpha = 0.35f)
                                        HeatmapIntensity.BUSY -> Color(0xFFFF4D6D).copy(alpha = 0.38f)
                                    }
                                    Surface(
                                        color = bg,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(
                                            if (isSelected) 1.6.dp else 1.dp,
                                            if (isSelected) Color.White else GhCardBorder
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp)
                                            .clickable { selectedCalendarIso = cell.dateIso }
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = if (cell.monthMarker.isNotBlank()) "${cell.monthMarker} ${cell.dayOfMonth}" else "${cell.dayOfMonth}",
                                                color = GhTextPrimary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (cell.releaseCount > 0) {
                                                Text(
                                                    text = "${cell.releaseCount} rel",
                                                    color = GhAccentGreen,
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val selectedCell = snapshot.heatmap28Days.firstOrNull { it.dateIso == selectedCalendarIso }
                        if (selectedCell != null && selectedCell.titles.isNotEmpty()) {
                            Text(
                                text = "${selectedCell.dateIso}: ${selectedCell.titles.joinToString(" • ")}",
                                color = GhAccentGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // Today & Tomorrow Side-by-Side Panels
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ReleaseSummaryPanel(
                    title = "Today",
                    releases = snapshot.todayReleases,
                    emptyText = "Nothing releasing today",
                    hasTv = hasTv,
                    onSelectItem = onSelectItem,
                    onLaunchTv = onLaunchTv,
                    modifier = Modifier.weight(1f)
                )
                ReleaseSummaryPanel(
                    title = "Tomorrow",
                    releases = tomorrowReleases,
                    emptyText = "Nothing releasing tomorrow",
                    hasTv = hasTv,
                    onSelectItem = onSelectItem,
                    onLaunchTv = onLaunchTv,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Recently Released Panel
        item {
            ReleaseSummaryPanel(
                title = "Recently Released (Last 14 Days)",
                releases = recentlyReleased,
                emptyText = "No recent episodes or movie drops in the past 14 days",
                hasTv = hasTv,
                onSelectItem = onSelectItem,
                onLaunchTv = onLaunchTv,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun OverviewStatTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = GhTileSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = value,
                color = GhTextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = GhFont
            )
            Text(
                text = label,
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ReleaseSummaryPanel(
    title: String,
    releases: List<EntertainmentScheduledRelease>,
    emptyText: String,
    hasTv: Boolean,
    onSelectItem: (String) -> Unit,
    onLaunchTv: (String, String, String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = GhTileSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, GhCardBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                color = GhAccentGreen,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold
            )
            if (releases.isEmpty()) {
                Text(
                    text = emptyText,
                    color = GhTextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                releases.take(5).forEach { rel ->
                    UpcomingReleaseCardRow(
                        release = rel,
                        hasTv = hasTv,
                        onClick = { onSelectItem(rel.itemId) },
                        onLaunchTv = {
                            onLaunchTv(rel.title, rel.primaryStreamer, rel.lgAppIdHint, rel.lgContentIdHint)
                        }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// TAB 2: UPCOMING PAGE
// ---------------------------------------------------------------------------
@Composable
private fun UpcomingTabContent(
    allItems: List<EntertainmentMediaItem>,
    hasTv: Boolean,
    onSelectItem: (String) -> Unit,
    onGoToDiscover: () -> Unit,
    onLaunchTv: (String, String, String, String) -> Unit
) {
    val startOfTodayCal = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
    val isoFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.UK) }
    val todayIso = remember { isoFmt.format(startOfTodayCal.time) }
    val tomorrowIso = remember {
        isoFmt.format((startOfTodayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.time)
    }
    val endOfThisWeekIso = remember {
        isoFmt.format((startOfTodayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 6) }.time)
    }
    val endOfNextWeekIso = remember {
        isoFmt.format((startOfTodayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 13) }.time)
    }

    val upcomingAll = remember(allItems) {
        collectScheduledReleases(allItems, minEpochMs = startOfTodayCal.timeInMillis)
    }

    if (upcomingAll.isEmpty()) {
        EmptyEntertainmentPrompt(
            title = "No Upcoming Releases Scheduled",
            subtitle = "Track ongoing TV series or upcoming movies to see Today, Tomorrow, This Week, and Next Week here.",
            onAction = onGoToDiscover
        )
        return
    }

    val sections = remember(upcomingAll) {
        listOf(
            "Today" to upcomingAll.filter { it.releaseDateIso == todayIso },
            "Tomorrow" to upcomingAll.filter { it.releaseDateIso == tomorrowIso },
            "This Week" to upcomingAll.filter { it.releaseDateIso > tomorrowIso && it.releaseDateIso <= endOfThisWeekIso },
            "Next Week" to upcomingAll.filter { it.releaseDateIso > endOfThisWeekIso && it.releaseDateIso <= endOfNextWeekIso },
            "Coming Later" to upcomingAll.filter { it.releaseDateIso > endOfNextWeekIso }
        ).filter { it.second.isNotEmpty() }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        sections.forEach { (sectionTitle, itemsInSection) ->
            item(key = "sec_$sectionTitle") {
                Text(
                    text = sectionTitle,
                    color = GhAccentGreen,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
            }
            items(itemsInSection, key = { "${sectionTitle}_${it.itemId}_${it.episodeCode}_${it.releaseDateIso}" }) { rel ->
                UpcomingReleaseCardRow(
                    release = rel,
                    hasTv = hasTv,
                    onClick = { onSelectItem(rel.itemId) },
                    onLaunchTv = {
                        onLaunchTv(rel.title, rel.primaryStreamer, rel.lgAppIdHint, rel.lgContentIdHint)
                    }
                )
            }
        }
    }
}

@Composable
private fun UpcomingReleaseCardRow(
    release: EntertainmentScheduledRelease,
    hasTv: Boolean,
    onClick: () -> Unit,
    onLaunchTv: () -> Unit
) {
    Surface(
        color = GhTileSurfaceElevated,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, GhCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                EntertainmentArtworkBox(
                    imageUrl = release.posterUrl,
                    fallbackIcon = if (release.kind == EntertainmentMediaKind.MOVIE) Icons.Outlined.Movie else Icons.Outlined.LiveTv,
                    cornerRadius = 8.dp,
                    modifier = Modifier
                        .width(44.dp)
                        .height(64.dp)
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = release.title,
                        color = GhTextPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val epLabel = if (release.kind == EntertainmentMediaKind.MOVIE) {
                        "Feature Film • ${release.releaseDateIso}"
                    } else {
                        val titlePart = if (release.episodeTitle.isNotBlank()) " \"${release.episodeTitle}\"" else ""
                        "${release.episodeCode}$titlePart • ${release.releaseDateIso}"
                    }
                    Text(
                        text = epLabel,
                        color = GhTextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = release.countdownLabel,
                        color = GhAccentGreen,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (release.primaryStreamer.isNotBlank()) {
                Surface(
                    color = GhAccentGreen.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.65f)),
                    modifier = Modifier.clickable { if (hasTv) onLaunchTv() else onClick() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tv,
                            contentDescription = null,
                            tint = GhAccentGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = release.primaryStreamer.take(14),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// TAB 3: MY SHOWS PAGE (ADAPTIVE GRID + SORTING + PROGRESS BARS)
// ---------------------------------------------------------------------------
@Composable
private fun MyShowsTabContent(
    allItems: List<EntertainmentMediaItem>,
    onSelectItem: (String) -> Unit,
    onGoToDiscover: () -> Unit
) {
    var sortOption by remember { mutableStateOf(MyShowsSortOption.NEXT_AIRING) }
    var statusFilter by remember { mutableStateOf(EntertainmentItemStatus.ACTIVE) }
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.UK) }

    val tvShows = remember(allItems, sortOption, statusFilter) {
        val filtered = allItems.filter {
            it.kind == EntertainmentMediaKind.TV_SHOW && it.lifecycleStatus == statusFilter
        }
        when (sortOption) {
            MyShowsSortOption.RECENTLY_WATCHED -> filtered.sortedByDescending { it.lastWatchedEpochMs }
            MyShowsSortOption.NEXT_AIRING -> filtered.sortedWith(
                compareBy<EntertainmentMediaItem> { if (it.nextAirTimestampEpochMs > 0L) 0 else 1 }
                    .thenBy { it.nextAirTimestampEpochMs }
            )
            MyShowsSortOption.ALPHABETICAL -> filtered.sortedBy { it.title.lowercase(Locale.UK) }
            MyShowsSortOption.PROGRESS -> filtered.sortedByDescending { it.watchProgressFraction() }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MyShowsSortOption.entries.forEach { opt ->
                    FilterChip(
                        selected = sortOption == opt,
                        onClick = { sortOption = opt },
                        label = { Text(opt.label, fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
                        shape = RoundedCornerShape(50)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EntertainmentItemStatus.entries.forEach { st ->
                    FilterChip(
                        selected = statusFilter == st,
                        onClick = { statusFilter = st },
                        label = { Text(st.name.lowercase(Locale.UK).replaceFirstChar { it.uppercase() }, fontSize = 11.sp) },
                        shape = RoundedCornerShape(50)
                    )
                }
            }
        }

        if (tvShows.isEmpty()) {
            EmptyEntertainmentPrompt(
                title = if (statusFilter == EntertainmentItemStatus.ACTIVE) "No TV Shows Tracked Yet" else "No ${statusFilter.name.lowercase(Locale.UK)} shows",
                subtitle = "Add TV series from Discover to track watch progress and upcoming episode air dates.",
                onAction = onGoToDiscover
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 270.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(tvShows, key = { it.id }) { show ->
                    val regularEps = show.episodes.filter { it.seasonNumber > 0 }
                    val watchedCount = regularEps.count { show.watchedEpisodeIds.contains(it.id) }
                    val progress = show.watchProgressFraction()
                    val lastWatchedLabel = if (show.lastWatchedEpochMs > 0L) {
                        "Last watched: ${dateFmt.format(Date(show.lastWatchedEpochMs))}"
                    } else {
                        "Not started yet"
                    }
                    val nextLabel = if (show.nextEpisodeCode.isNotBlank()) {
                        "Next: ${show.nextEpisodeCode} ${show.releaseDateIso}".trim()
                    } else {
                        show.statusText.ifBlank { "Caught up" }
                    }

                    Surface(
                        color = GhTileSurface,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, GhCardBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectItem(show.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            EntertainmentArtworkBox(
                                imageUrl = show.posterUrl,
                                fallbackIcon = Icons.Outlined.LiveTv,
                                cornerRadius = 10.dp,
                                modifier = Modifier
                                    .width(68.dp)
                                    .height(100.dp)
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = show.title,
                                    color = GhTextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "$watchedCount / ${regularEps.size} Episodes Watched",
                                    color = GhTextSecondary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                LinearProgressIndicator(
                                    progress = { progress },
                                    color = GhAccentGreen,
                                    trackColor = GhTileSurfaceElevated,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(50))
                                )
                                Text(
                                    text = nextLabel,
                                    color = GhAccentGreen,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = lastWatchedLabel,
                                    color = GhTextSecondary,
                                    fontSize = 10.5.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// TAB 4: MOVIES PAGE
// ---------------------------------------------------------------------------
@Composable
private fun MoviesTabContent(
    allItems: List<EntertainmentMediaItem>,
    discoverFeed: EntertainmentDiscoverFeed,
    hasTv: Boolean,
    onSelectItem: (String, EntertainmentMediaItem?) -> Unit,
    onTrackItem: (EntertainmentMediaItem) -> Unit,
    onLaunchTv: (String, String, String, String) -> Unit
) {
    val isoFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.UK) }
    val todayIso = remember { isoFmt.format(Date()) }
    val sixtyDaysAgoIso = remember {
        isoFmt.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -60) }.time)
    }

    val trackedMovies = remember(allItems) {
        allItems.filter { it.kind == EntertainmentMediaKind.MOVIE && it.lifecycleStatus == EntertainmentItemStatus.ACTIVE }
    }
    val comingSoon = remember(trackedMovies) {
        trackedMovies.filter { it.releaseDateIso > todayIso }.sortedBy { it.releaseDateIso }
    }
    val recentlyReleased = remember(trackedMovies) {
        trackedMovies.filter { it.releaseDateIso in sixtyDaysAgoIso..todayIso }.sortedByDescending { it.releaseDateIso }
    }
    val streamingNow = remember(trackedMovies) {
        trackedMovies.filter { it.releaseDateIso.isBlank() || it.releaseDateIso <= todayIso }
    }
    val recommendedMovies = remember(discoverFeed, trackedMovies) {
        discoverFeed.upcomingMovies.filter { cand ->
            trackedMovies.none { it.title.equals(cand.title, ignoreCase = true) }
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            MovieHorizontalSection(
                title = "Coming Soon",
                movies = comingSoon,
                emptyHint = "Track upcoming films in Discover to see release countdowns here.",
                isTrackedList = true,
                hasTv = hasTv,
                onSelect = { onSelectItem(it.id, null) },
                onTrack = onTrackItem,
                onLaunchTv = onLaunchTv
            )
        }
        item {
            MovieHorizontalSection(
                title = "Recently Released",
                movies = recentlyReleased,
                emptyHint = "No tracked movies released in the last 60 days.",
                isTrackedList = true,
                hasTv = hasTv,
                onSelect = { onSelectItem(it.id, null) },
                onTrack = onTrackItem,
                onLaunchTv = onLaunchTv
            )
        }
        item {
            MovieHorizontalSection(
                title = "Streaming Now",
                movies = streamingNow,
                emptyHint = "Saved movies available to stream will appear here.",
                isTrackedList = true,
                hasTv = hasTv,
                onSelect = { onSelectItem(it.id, null) },
                onTrack = onTrackItem,
                onLaunchTv = onLaunchTv
            )
        }
        item {
            MovieHorizontalSection(
                title = "Recommended",
                movies = recommendedMovies,
                emptyHint = "Loading movie recommendations...",
                isTrackedList = false,
                hasTv = hasTv,
                onSelect = { onSelectItem(it.id, it) },
                onTrack = onTrackItem,
                onLaunchTv = onLaunchTv
            )
        }
    }
}

@Composable
private fun MovieHorizontalSection(
    title: String,
    movies: List<EntertainmentMediaItem>,
    emptyHint: String,
    isTrackedList: Boolean,
    hasTv: Boolean,
    onSelect: (EntertainmentMediaItem) -> Unit,
    onTrack: (EntertainmentMediaItem) -> Unit,
    onLaunchTv: (String, String, String, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = GhAccentGreen,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold
        )
        if (movies.isEmpty()) {
            Text(text = emptyHint, color = GhTextSecondary, fontSize = 12.sp)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(movies, key = { it.id }) { movie ->
                    Surface(
                        color = GhTileSurface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, GhCardBorder),
                        modifier = Modifier
                            .width(175.dp)
                            .clickable { onSelect(movie) }
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            EntertainmentArtworkBox(
                                imageUrl = movie.posterUrl,
                                fallbackIcon = Icons.Outlined.Movie,
                                cornerRadius = 10.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(210.dp)
                            )
                            Text(
                                text = movie.title,
                                color = GhTextPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = buildString {
                                    if (movie.releaseDateIso.isNotBlank()) append(movie.releaseDateIso)
                                    if (movie.primaryStreamer.isNotBlank()) {
                                        if (isNotEmpty()) append(" • ")
                                        append(movie.primaryStreamer)
                                    }
                                }.ifBlank { "Feature Film" },
                                color = GhTextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isTrackedList && hasTv) {
                                OutlinedButton(
                                    onClick = {
                                        onLaunchTv(movie.title, movie.primaryStreamer, movie.lgAppIdHint, movie.lgContentIdHint)
                                    },
                                    shape = RoundedCornerShape(50),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.fillMaxWidth().height(30.dp)
                                ) {
                                    Text("Watch on TV", fontSize = 11.sp, color = GhAccentGreen)
                                }
                            } else if (!isTrackedList) {
                                Button(
                                    onClick = { onTrack(movie) },
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = GhAccentGreen,
                                        contentColor = Color(0xFF141518)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.fillMaxWidth().height(30.dp)
                                ) {
                                    Text("+ Track Movie", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// TAB 5: DISCOVER PAGE (LIVE SEARCH + RECOMMENDATION ENGINE)
// ---------------------------------------------------------------------------
@Composable
private fun DiscoverTabContent(
    allItems: List<EntertainmentMediaItem>,
    discoverFeed: EntertainmentDiscoverFeed,
    isLoadingFeed: Boolean,
    onOpenItemDetails: (EntertainmentMediaItem) -> Unit,
    onTrackItem: (EntertainmentMediaItem) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<EntertainmentMediaItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        if (q.length < 2) {
            searchResults = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        delay(320L)
        isSearching = true
        searchResults = EntertainmentDirectoryClient.searchMedia(context, q)
        isSearching = false
    }

    val similarGenresList = remember(allItems, discoverFeed) {
        val topGenres = allItems.flatMap { it.genres }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }.take(3).toSet()
        if (topGenres.isEmpty()) emptyList()
        else discoverFeed.trending.filter { item -> item.genres.any { it in topGenres } }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search TV shows or movies (e.g. Silo, Severance, Dune)...", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = GhAccentGreen) },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = GhTextSecondary)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = claraFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )

        if (searchQuery.trim().length >= 2) {
            if (isSearching) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = GhAccentGreen)
                }
            } else if (searchResults.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No matches found for \"$searchQuery\"", color = GhTextSecondary, fontSize = 14.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 175.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(searchResults, key = { it.id }) { candidate ->
                        DiscoverMediaCard(
                            item = candidate,
                            isAlreadyTracked = isItemTracked(allItems, candidate),
                            onOpen = { onOpenItemDetails(candidate) },
                            onTrack = { onTrackItem(candidate) }
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (discoverFeed.becauseYouWatched.isNotEmpty()) {
                    item {
                        DiscoverHorizontalCarousel(
                            title = if (discoverFeed.becauseYouWatchedTitle.isNotBlank()) {
                                "Because You Watched ${discoverFeed.becauseYouWatchedTitle}"
                            } else "Because You Watched",
                            items = discoverFeed.becauseYouWatched,
                            trackedItems = allItems,
                            onOpen = onOpenItemDetails,
                            onTrack = onTrackItem
                        )
                    }
                }
                if (similarGenresList.isNotEmpty()) {
                    item {
                        DiscoverHorizontalCarousel(
                            title = "Similar Genres",
                            items = similarGenresList,
                            trackedItems = allItems,
                            onOpen = onOpenItemDetails,
                            onTrack = onTrackItem
                        )
                    }
                }
                item {
                    DiscoverHorizontalCarousel(
                        title = if (isLoadingFeed) "Trending Now (Refreshing...)" else "Trending Now",
                        items = discoverFeed.trending,
                        trackedItems = allItems,
                        onOpen = onOpenItemDetails,
                        onTrack = onTrackItem
                    )
                }
                if (discoverFeed.upcomingMovies.isNotEmpty()) {
                    item {
                        DiscoverHorizontalCarousel(
                            title = "Popular & Upcoming Movies",
                            items = discoverFeed.upcomingMovies,
                            trackedItems = allItems,
                            onOpen = onOpenItemDetails,
                            onTrack = onTrackItem
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverHorizontalCarousel(
    title: String,
    items: List<EntertainmentMediaItem>,
    trackedItems: List<EntertainmentMediaItem>,
    onOpen: (EntertainmentMediaItem) -> Unit,
    onTrack: (EntertainmentMediaItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, color = GhAccentGreen, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { item ->
                DiscoverMediaCard(
                    item = item,
                    isAlreadyTracked = isItemTracked(trackedItems, item),
                    onOpen = { onOpen(item) },
                    onTrack = { onTrack(item) },
                    modifier = Modifier.width(170.dp)
                )
            }
        }
    }
}

@Composable
private fun DiscoverMediaCard(
    item: EntertainmentMediaItem,
    isAlreadyTracked: Boolean,
    onOpen: () -> Unit,
    onTrack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = GhTileSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, GhCardBorder),
        modifier = modifier.clickable { onOpen() }
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            EntertainmentArtworkBox(
                imageUrl = item.posterUrl,
                fallbackIcon = if (item.kind == EntertainmentMediaKind.MOVIE) Icons.Outlined.Movie else Icons.Outlined.LiveTv,
                cornerRadius = 10.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(205.dp)
            )
            Text(
                text = item.title,
                color = GhTextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${if (item.kind == EntertainmentMediaKind.MOVIE) "Movie" else "TV"} • ${item.primaryStreamer.ifBlank { item.releaseDateIso.take(4) }}",
                color = GhTextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Button(
                onClick = { if (!isAlreadyTracked) onTrack() else onOpen() },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAlreadyTracked) GhTileSurfaceElevated else GhAccentGreen,
                    contentColor = if (isAlreadyTracked) GhAccentGreen else Color(0xFF141518)
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.fillMaxWidth().height(30.dp)
            ) {
                Text(
                    text = if (isAlreadyTracked) "✓ Tracked" else "+ Track",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// SHOW & MOVIE DETAILS POPUP
// ---------------------------------------------------------------------------
@Composable
private fun EntertainmentItemDetailsDialog(
    item: EntertainmentMediaItem,
    isTracked: Boolean,
    hasTv: Boolean,
    onTrackNow: () -> Unit,
    onLaunchTv: () -> Unit,
    onTrackRecommended: (EntertainmentMediaItem) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hydratedPreview by remember(item.id) { mutableStateOf(item) }
    val displayItem = if (isTracked) item else hydratedPreview
    var activeSection by remember { mutableStateOf(ShowDetailSection.OVERVIEW) }
    var selectedSeason by remember(displayItem.id, displayItem.episodes.size) {
        val firstSeason = displayItem.episodes.filter { it.seasonNumber > 0 }.minOfOrNull { it.seasonNumber } ?: 1
        mutableIntStateOf(firstSeason)
    }
    var recommendations by remember(displayItem.id) { mutableStateOf<List<EntertainmentMediaItem>>(emptyList()) }

    LaunchedEffect(item.id, isTracked) {
        if (!isTracked && item.episodes.isEmpty()) {
            hydratedPreview = EntertainmentDirectoryClient.hydrateItemDetails(context, item)
        } else if (isTracked && item.episodes.isEmpty() && item.kind == EntertainmentMediaKind.TV_SHOW) {
            val fresh = EntertainmentDirectoryClient.hydrateItemDetails(context, item)
            ClaraEntertainmentStore.upsertItem(context, fresh)
        }
        val recFeed = EntertainmentDirectoryClient.fetchDiscoverFeed(context, listOf(displayItem))
        recommendations = recFeed.becauseYouWatched.ifEmpty { recFeed.trending.take(8) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhBackground,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.2.dp, GhCardBorder),
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .fillMaxHeight(0.86f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header with Backdrop + Poster + Rating + Genres + Providers
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                ) {
                    EntertainmentArtworkBox(
                        imageUrl = displayItem.backdropUrl.ifBlank { displayItem.posterUrl },
                        fallbackIcon = Icons.Outlined.Movie,
                        cornerRadius = 0.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        GhBackground.copy(alpha = 0.45f),
                                        GhBackground.copy(alpha = 0.96f)
                                    )
                                )
                            )
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        EntertainmentArtworkBox(
                            imageUrl = displayItem.posterUrl,
                            fallbackIcon = Icons.Outlined.LiveTv,
                            cornerRadius = 12.dp,
                            modifier = Modifier
                                .width(105.dp)
                                .height(155.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = displayItem.title,
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (displayItem.rating > 0.0) {
                                    Text(
                                        text = String.format(Locale.UK, "★ %.1f", displayItem.rating),
                                        color = Color(0xFFFFD54F),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                                if (displayItem.genres.isNotEmpty()) {
                                    Text(
                                        text = displayItem.genres.take(3).joinToString(" • "),
                                        color = GhTextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val providers = displayItem.streamingProviders.ifEmpty {
                                    listOfNotNull(displayItem.primaryStreamer.takeIf { it.isNotBlank() })
                                }
                                providers.take(3).forEach { prov ->
                                    Surface(
                                        color = GhAccentGreen.copy(alpha = 0.20f),
                                        shape = RoundedCornerShape(50),
                                        border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.6f))
                                    ) {
                                        Text(
                                            text = prov,
                                            color = GhAccentGreen,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Action Bar (Mark Watched / Mark Unwatched / Favourite / Archive / Hide / Remove)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isTracked) {
                        Button(
                            onClick = onTrackNow,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = GhAccentGreen, contentColor = Color(0xFF141518))
                        ) {
                            Text("+ Add to Favourites", fontWeight = FontWeight.ExtraBold)
                        }
                    } else {
                        val allRegularIds = displayItem.episodes.filter { it.seasonNumber > 0 }.map { it.id }
                        val isFullyWatched = if (displayItem.kind == EntertainmentMediaKind.MOVIE) {
                            displayItem.isMovieWatched
                        } else {
                            allRegularIds.isNotEmpty() && displayItem.watchedEpisodeIds.containsAll(allRegularIds)
                        }

                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    if (displayItem.kind == EntertainmentMediaKind.MOVIE) {
                                        ClaraEntertainmentStore.setMovieWatched(context, displayItem.id, !isFullyWatched)
                                    } else {
                                        val seasons = displayItem.episodes.map { it.seasonNumber }.distinct()
                                        seasons.forEach { s ->
                                            ClaraEntertainmentStore.setSeasonWatched(context, displayItem.id, s, !isFullyWatched)
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(if (isFullyWatched) "Mark Unwatched" else "Mark Watched", fontSize = 11.5.sp)
                        }

                        FilterChip(
                            selected = displayItem.isFavourite,
                            onClick = {
                                scope.launch {
                                    ClaraEntertainmentStore.setFavourite(context, displayItem.id, !displayItem.isFavourite)
                                }
                            },
                            label = { Text(if (displayItem.isFavourite) "★ Favourite" else "☆ Favourite", fontSize = 11.5.sp) },
                            shape = RoundedCornerShape(50)
                        )

                        FilterChip(
                            selected = displayItem.lifecycleStatus == EntertainmentItemStatus.ARCHIVED,
                            onClick = {
                                val next = if (displayItem.lifecycleStatus == EntertainmentItemStatus.ARCHIVED) {
                                    EntertainmentItemStatus.ACTIVE
                                } else EntertainmentItemStatus.ARCHIVED
                                scope.launch { ClaraEntertainmentStore.updateLifecycleStatus(context, displayItem.id, next) }
                            },
                            label = { Text("Archive", fontSize = 11.5.sp) },
                            shape = RoundedCornerShape(50)
                        )

                        FilterChip(
                            selected = displayItem.lifecycleStatus == EntertainmentItemStatus.HIDDEN,
                            onClick = {
                                val next = if (displayItem.lifecycleStatus == EntertainmentItemStatus.HIDDEN) {
                                    EntertainmentItemStatus.ACTIVE
                                } else EntertainmentItemStatus.HIDDEN
                                scope.launch { ClaraEntertainmentStore.updateLifecycleStatus(context, displayItem.id, next) }
                            },
                            label = { Text("Hide", fontSize = 11.5.sp) },
                            shape = RoundedCornerShape(50)
                        )

                        if (hasTv) {
                            OutlinedButton(onClick = onLaunchTv, shape = RoundedCornerShape(50)) {
                                Text("Watch on TV", color = GhAccentGreen, fontSize = 11.5.sp)
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        TextButton(
                            onClick = {
                                scope.launch {
                                    ClaraEntertainmentStore.deleteItemPermanently(context, displayItem.id)
                                    onDismiss()
                                }
                            }
                        ) {
                            Text("Remove", color = GhAccentRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Detail Sub-Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ShowDetailSection.entries.forEach { sec ->
                        if (sec == ShowDetailSection.EPISODES && displayItem.kind == EntertainmentMediaKind.MOVIE) return@forEach
                        FilterChip(
                            selected = activeSection == sec,
                            onClick = { activeSection = sec },
                            label = { Text(sec.label, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                            shape = RoundedCornerShape(50)
                        )
                    }
                }

                // Section Content
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    when (activeSection) {
                        ShowDetailSection.OVERVIEW -> {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = displayItem.overview.ifBlank { "No synopsis available yet." },
                                    color = GhTextPrimary,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                )
                                Text(
                                    text = "Status: ${displayItem.statusText.ifBlank { "Active" }} • Release: ${displayItem.releaseDateIso.ifBlank { "TBA" }}",
                                    color = GhAccentGreen,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        ShowDetailSection.EPISODES -> {
                            val seasons = remember(displayItem.episodes) {
                                displayItem.episodes.filter { it.seasonNumber > 0 }.map { it.seasonNumber }.distinct().sorted()
                            }
                            val seasonEpisodes = remember(displayItem.episodes, selectedSeason) {
                                displayItem.episodes.filter { it.seasonNumber == selectedSeason }.sortedBy { it.episodeNumber }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(seasons) { sNum ->
                                        FilterChip(
                                            selected = selectedSeason == sNum,
                                            onClick = { selectedSeason = sNum },
                                            label = { Text("Season $sNum", fontSize = 11.5.sp) },
                                            shape = RoundedCornerShape(50)
                                        )
                                    }
                                    if (isTracked && seasonEpisodes.isNotEmpty()) {
                                        item {
                                            val allSeasonWatched = seasonEpisodes.all { displayItem.watchedEpisodeIds.contains(it.id) }
                                            AssistChip(
                                                onClick = {
                                                    scope.launch {
                                                        ClaraEntertainmentStore.setSeasonWatched(
                                                            context,
                                                            displayItem.id,
                                                            selectedSeason,
                                                            !allSeasonWatched
                                                        )
                                                    }
                                                },
                                                label = {
                                                    Text(
                                                        if (allSeasonWatched) "Unmark Season" else "Mark Season Watched",
                                                        fontSize = 11.5.sp
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }

                                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                                    items(seasonEpisodes, key = { it.id }) { ep ->
                                        val watched = displayItem.watchedEpisodeIds.contains(ep.id)
                                        Surface(
                                            color = GhTileSurface,
                                            shape = RoundedCornerShape(12.dp),
                                            border = BorderStroke(1.dp, GhCardBorder),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(enabled = isTracked) {
                                                    scope.launch {
                                                        ClaraEntertainmentStore.setEpisodeWatched(context, displayItem.id, ep.id, !watched)
                                                    }
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "${ep.episodeCode} • ${ep.title}",
                                                        color = if (watched) GhAccentGreen else GhTextPrimary,
                                                        fontSize = 13.5.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = ep.airDateIso.ifBlank { "TBA" },
                                                        color = GhTextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                                if (isTracked) {
                                                    Checkbox(
                                                        checked = watched,
                                                        onCheckedChange = { checked ->
                                                            scope.launch {
                                                                ClaraEntertainmentStore.setEpisodeWatched(context, displayItem.id, ep.id, checked)
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        ShowDetailSection.UPCOMING -> {
                            val nowIso = remember { SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date()) }
                            val futureEps = remember(displayItem.episodes) {
                                displayItem.episodes.filter { it.seasonNumber > 0 && it.airDateIso >= nowIso }
                            }
                            if (displayItem.kind == EntertainmentMediaKind.MOVIE) {
                                Text(
                                    text = "Scheduled Movie Release Date: ${displayItem.releaseDateIso.ifBlank { "TBA" }}",
                                    color = GhTextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else if (futureEps.isEmpty()) {
                                Text("No upcoming episode dates announced yet.", color = GhTextSecondary, fontSize = 13.sp)
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(futureEps, key = { it.id }) { ep ->
                                        Text(
                                            text = "${ep.episodeCode} — ${ep.title} (${ep.airDateIso})",
                                            color = GhTextPrimary,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }

                        ShowDetailSection.CAST -> {
                            if (displayItem.cast.isEmpty()) {
                                Text("No cast credits available.", color = GhTextSecondary, fontSize = 13.sp)
                            } else {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(displayItem.cast) { member ->
                                        Surface(
                                            color = GhTileSurface,
                                            shape = RoundedCornerShape(14.dp),
                                            border = BorderStroke(1.dp, GhCardBorder),
                                            modifier = Modifier.width(135.dp)
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(10.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                EntertainmentArtworkBox(
                                                    imageUrl = member.profileUrl,
                                                    fallbackIcon = Icons.Outlined.Person,
                                                    cornerRadius = 50.dp,
                                                    modifier = Modifier.size(72.dp)
                                                )
                                                Text(
                                                    text = member.name,
                                                    color = GhTextPrimary,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = member.character,
                                                    color = GhTextSecondary,
                                                    fontSize = 10.5.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        ShowDetailSection.RECOMMENDATIONS -> {
                            if (recommendations.isEmpty()) {
                                Text("Loading similar recommendations...", color = GhTextSecondary, fontSize = 13.sp)
                            } else {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(recommendations, key = { it.id }) { rec ->
                                        DiscoverMediaCard(
                                            item = rec,
                                            isAlreadyTracked = false,
                                            onOpen = { onTrackRecommended(rec) },
                                            onTrack = { onTrackRecommended(rec) },
                                            modifier = Modifier.width(155.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// HUB SETTINGS DIALOG (OPTIONAL TMDB API KEY + IPTV LAUNCH OVERRIDE)
// ---------------------------------------------------------------------------
@Composable
private fun EntertainmentHubSettingsDialog(
    onSaved: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var tmdbKeyDraft by remember { mutableStateOf(ClaraEntertainmentStore.getTmdbApiKey(context)) }
    var overrideWithIptv by remember { mutableStateOf(ClaraEntertainmentStore.getOverrideWithIptv(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GhTileSurface,
        title = {
            Text("Entertainment Hub Settings", color = GhTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "TVMaze and UK iTunes work automatically with zero keys. Optionally add a free TMDB API v3 key (or Read Access Token) to unlock 1080p backdrops, UK watch providers, theatrical/VOD movie dates, and deep recommendations.",
                    color = GhTextSecondary,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = tmdbKeyDraft,
                    onValueChange = { tmdbKeyDraft = it },
                    label = { Text("TMDB API Key (Optional)", fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = claraFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Launch shows & movies in IPTV VOD player on LG TV", color = GhTextPrimary, fontSize = 12.5.sp)
                    Switch(checked = overrideWithIptv, onCheckedChange = { overrideWithIptv = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    ClaraEntertainmentStore.setTmdbApiKey(context, tmdbKeyDraft)
                    ClaraEntertainmentStore.setOverrideWithIptv(context, overrideWithIptv)
                    onSaved()
                    onDismiss()
                },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = GhAccentGreen, contentColor = Color(0xFF141518))
            ) {
                Text("Save Settings", fontWeight = FontWeight.ExtraBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = GhTextSecondary)
            }
        }
    )
}

@Composable
private fun EmptyEntertainmentPrompt(
    title: String,
    subtitle: String,
    onAction: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.widthIn(max = 460.dp)
        ) {
            Icon(Icons.Outlined.MovieFilter, contentDescription = null, tint = GhAccentGreen, modifier = Modifier.size(42.dp))
            Text(text = title, color = GhTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Text(text = subtitle, color = GhTextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = GhAccentGreen, contentColor = Color(0xFF141518))
            ) {
                Text("Explore & Add Favourites", fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

private fun isItemTracked(tracked: List<EntertainmentMediaItem>, candidate: EntertainmentMediaItem): Boolean {
    return tracked.any {
        it.id == candidate.id ||
                (candidate.tmdbId > 0 && it.tmdbId == candidate.tmdbId && it.kind == candidate.kind) ||
                (candidate.tvmazeId > 0 && it.tvmazeId == candidate.tvmazeId && it.kind == candidate.kind) ||
                (it.kind == candidate.kind && it.title.equals(candidate.title, ignoreCase = true))
    }
}

private fun collectScheduledReleases(
    allItems: List<EntertainmentMediaItem>,
    minEpochMs: Long
): List<EntertainmentScheduledRelease> {
    val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.UK)
    val todayIso = isoFmt.format(Date())
    val tomorrowIso = isoFmt.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.time)
    val dayFmt = SimpleDateFormat("EEE d MMM", Locale.UK)
    val timeFmt = SimpleDateFormat("HH:mm", Locale.UK)
    val list = mutableListOf<EntertainmentScheduledRelease>()

    fun buildCountdown(epochMs: Long, dateIso: String): String {
        val hasExactTime = if (epochMs > 0L) {
            val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
            cal.get(Calendar.HOUR_OF_DAY) != 0 || cal.get(Calendar.MINUTE) != 0
        } else false
        val timePart = if (hasExactTime) " ${timeFmt.format(Date(epochMs))}" else ""
        return when (dateIso) {
            todayIso -> "Today$timePart"
            tomorrowIso -> "Tomorrow$timePart"
            else -> if (epochMs > 0L) "${dayFmt.format(Date(epochMs))}$timePart" else dateIso
        }
    }

    for (item in allItems.filter { it.lifecycleStatus == EntertainmentItemStatus.ACTIVE && it.isFavourite }) {
        if (item.kind == EntertainmentMediaKind.TV_SHOW) {
            val eps = item.episodes.filter { it.seasonNumber > 0 && (it.airTimestampEpochMs >= minEpochMs || it.airDateIso >= todayIso) }
            if (eps.isNotEmpty()) {
                eps.forEach { ep ->
                    list.add(
                        EntertainmentScheduledRelease(
                            itemId = item.id,
                            title = item.title,
                            kind = EntertainmentMediaKind.TV_SHOW,
                            episodeCode = ep.episodeCode,
                            episodeTitle = ep.title,
                            releaseDateIso = ep.airDateIso,
                            releaseEpochMs = ep.airTimestampEpochMs,
                            countdownLabel = buildCountdown(ep.airTimestampEpochMs, ep.airDateIso),
                            primaryStreamer = item.primaryStreamer,
                            lgAppIdHint = item.lgAppIdHint,
                            lgContentIdHint = item.lgContentIdHint,
                            posterUrl = item.posterUrl,
                            accentColorHex = item.accentColorHex
                        )
                    )
                }
            } else if (item.releaseDateIso >= todayIso || item.nextAirTimestampEpochMs >= minEpochMs) {
                list.add(
                    EntertainmentScheduledRelease(
                        itemId = item.id,
                        title = item.title,
                        kind = EntertainmentMediaKind.TV_SHOW,
                        episodeCode = item.nextEpisodeCode.ifBlank { "Next" },
                        episodeTitle = item.nextEpisodeTitle,
                        releaseDateIso = item.releaseDateIso,
                        releaseEpochMs = item.nextAirTimestampEpochMs,
                        countdownLabel = buildCountdown(item.nextAirTimestampEpochMs, item.releaseDateIso),
                        primaryStreamer = item.primaryStreamer,
                        lgAppIdHint = item.lgAppIdHint,
                        lgContentIdHint = item.lgContentIdHint,
                        posterUrl = item.posterUrl,
                        accentColorHex = item.accentColorHex
                    )
                )
            }
        } else if (item.releaseDateIso >= todayIso || item.nextAirTimestampEpochMs >= minEpochMs) {
            list.add(
                EntertainmentScheduledRelease(
                    itemId = item.id,
                    title = item.title,
                    kind = EntertainmentMediaKind.MOVIE,
                    episodeCode = "Movie",
                    episodeTitle = "",
                    releaseDateIso = item.releaseDateIso,
                    releaseEpochMs = item.nextAirTimestampEpochMs,
                    countdownLabel = buildCountdown(item.nextAirTimestampEpochMs, item.releaseDateIso),
                    primaryStreamer = item.primaryStreamer,
                    lgAppIdHint = item.lgAppIdHint,
                    lgContentIdHint = item.lgContentIdHint,
                    posterUrl = item.posterUrl,
                    accentColorHex = item.accentColorHex
                )
            )
        }
    }
    return list.sortedWith(compareBy<EntertainmentScheduledRelease> { it.releaseDateIso }.thenBy { it.releaseEpochMs })
}