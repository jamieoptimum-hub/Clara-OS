package com.jarvis.smarthomelauncher.widgets.radio.Popup

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.widgets.radio.Models.*
import com.jarvis.smarthomelauncher.widgets.radio.Repository.RadioRepository
import com.jarvis.smarthomelauncher.widgets.radio.Services.RadioImageMemoryCache
import com.jarvis.smarthomelauncher.widgets.radio.Settings.RadioSettingsPopup
import com.jarvis.smarthomelauncher.widgets.radio.Widget.RadioStationBadgeAvatar
import com.jarvis.smarthomelauncher.ui.widgets.WidgetContext
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RadioHubPopup(
    widgetContext: WidgetContext? = null,
    onDismiss: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    val repository = remember(appContext) { RadioRepository.getInstance(appContext) }
    val uiState by repository.uiState.collectAsState()
    val discoverFeed by repository.discoverFeed.collectAsState()

    var selectedTab by remember { mutableStateOf(RadioHubTab.OVERVIEW) }
    var selectedSort by remember { mutableStateOf(StationSortOption.FAVOURITES) }
    var selectedHistoryFilter by remember { mutableStateOf(HistoryTimeFilter.TODAY) }
    var selectedCategory by remember { mutableStateOf<RadioCategory?>(null) }
    var categoryStations by remember { mutableStateOf<List<RadioStation>>(emptyList()) }
    var isCategoryLoading by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<RadioStation>?>(null) }
    var isSearching by remember { mutableStateOf(false) }

    var detailStation by remember { mutableStateOf<RadioStation?>(null) }
    var showSettingsModal by remember { mutableStateOf(false) }
    var showCastModal by remember { mutableStateOf(false) }
    var statusBannerText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState) {
        val ready = uiState as? RadioUiState.Ready ?: return@LaunchedEffect
        selectedSort = ready.snapshot.settings.defaultSortOption
    }

    LaunchedEffect(statusBannerText) {
        if (statusBannerText != null) {
            delay(3000L)
            statusBannerText = null
        }
    }

    LaunchedEffect(searchQuery) {
        val clean = searchQuery.trim()
        if (clean.length < 2) {
            searchResults = null
            isSearching = false
        } else {
            isSearching = true
            delay(280L)
            searchResults = repository.searchStations(clean)
            isSearching = false
        }
    }

    LaunchedEffect(selectedCategory) {
        val cat = selectedCategory ?: return@LaunchedEffect
        isCategoryLoading = true
        categoryStations = repository.getStationsForCategory(cat)
        isCategoryLoading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .fillMaxHeight(0.90f),
            shape = RoundedCornerShape(26.dp),
            color = GhBackground,
            border = BorderStroke(1.5.dp, GhCardBorder)
        ) {
            when (val state = uiState) {
                is RadioUiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = GhAccentGreen)
                    }
                }

                is RadioUiState.Empty, is RadioUiState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (state is RadioUiState.Error) state.message else "No stations available",
                                color = GhTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { repository.refreshAll() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GhAccentGreen,
                                    contentColor = Color(0xFF141518)
                                )
                            ) {
                                Text("Retry", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                is RadioUiState.Ready -> {
                    val snap = state.snapshot
                    val favIds = remember(snap.favourites) { snap.favourites.map { it.id }.toSet() }
                    val favUrls = remember(snap.favourites) {
                        snap.favourites.map { it.streamUrl.lowercase(Locale.UK) }.toSet()
                    }

                    fun isFav(st: RadioStation): Boolean =
                        st.id in favIds || st.streamUrl.lowercase(Locale.UK) in favUrls

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 22.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 1. TOP HEADER BAR + SEARCH + CAST + SETTINGS + CLOSE
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
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Radio,
                                        contentDescription = "Radio Hub",
                                        tint = GhAccentGreen,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Radio Hub",
                                        color = GhTextPrimary,
                                        fontSize = 21.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = GhFont
                                    )
                                    Text(
                                        text = "${snap.allStations.size} live stations • Output: ${snap.activeOutputName}",
                                        color = GhTextSecondary,
                                        fontSize = 11.5.sp,
                                        fontFamily = GhFont
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = {
                                        Text("Search stations or genres...", fontSize = 12.sp, color = GhTextSecondary)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Search, contentDescription = null, tint = GhTextSecondary, modifier = Modifier.size(18.dp))
                                    },
                                    trailingIcon = {
                                        if (searchQuery.isNotBlank()) {
                                            IconButton(onClick = { searchQuery = "" }) {
                                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = GhTextSecondary, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .widthIn(min = 210.dp, max = 300.dp)
                                        .height(48.dp)
                                )

                                FilledTonalIconButton(
                                    onClick = {
                                        repository.discoverCastSpeakers()
                                        showCastModal = true
                                    },
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = if (snap.activeOutputName != "Tablet Speaker") {
                                            GhAccentGreen.copy(alpha = 0.22f)
                                        } else {
                                            GhTileSurfaceElevated
                                        },
                                        contentColor = if (snap.activeOutputName != "Tablet Speaker") GhAccentGreen else GhTextPrimary
                                    )
                                ) {
                                    Icon(Icons.Outlined.Cast, contentDescription = "Speaker Output", modifier = Modifier.size(19.dp))
                                }

                                FilledTonalIconButton(
                                    onClick = { showSettingsModal = true },
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = GhTileSurfaceElevated,
                                        contentColor = GhTextPrimary
                                    )
                                ) {
                                    Icon(Icons.Outlined.Settings, contentDescription = "Settings", modifier = Modifier.size(19.dp))
                                }

                                FilledTonalIconButton(
                                    onClick = onDismiss,
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = GhTileSurfaceElevated,
                                        contentColor = GhTextPrimary
                                    )
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(19.dp))
                                }
                            }
                        }

                        // 2. PERSISTENT NOW PLAYING TRANSPORT BAR
                        snap.currentStation?.let { current ->
                            HubPersistentNowPlayingBar(
                                station = current.copy(isFavourite = isFav(current)),
                                nowPlaying = snap.nowPlaying,
                                isPlaying = snap.isPlaying,
                                outputName = snap.activeOutputName,
                                onTogglePlayPause = { repository.togglePlayPause(current) },
                                onPrevious = { repository.cycleStation(forward = false) },
                                onNext = { repository.cycleStation(forward = true) },
                                onToggleFavourite = {
                                    repository.toggleFavourite(current, widgetContext?.onUpdateBridgeSettings)
                                },
                                onOpenDetails = { detailStation = current.copy(isFavourite = isFav(current)) },
                                onOpenCast = {
                                    repository.discoverCastSpeakers()
                                    showCastModal = true
                                }
                            )
                        }

                        statusBannerText?.let { banner ->
                            Surface(
                                color = GhAccentGreen.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, GhAccentGreen),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = banner,
                                    color = GhAccentGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }

                        // 3. SIX NAVIGATION TABS
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(RadioHubTab.entries) { tab ->
                                val selected = selectedTab == tab && searchQuery.trim().length < 2
                                Surface(
                                    color = if (selected) GhAccentGreen.copy(alpha = 0.22f) else GhTileSurface,
                                    shape = RoundedCornerShape(11.dp),
                                    border = BorderStroke(
                                        width = if (selected) 1.3.dp else 1.dp,
                                        color = if (selected) GhAccentGreen else GhCardBorder
                                    ),
                                    modifier = Modifier.clickable {
                                        searchQuery = ""
                                        selectedTab = tab
                                    }
                                ) {
                                    Text(
                                        text = tab.label,
                                        color = if (selected) GhAccentGreen else GhTextSecondary,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        fontFamily = GhFont,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = GhCardBorder)

                        // 4. MAIN PAGE CONTENT OR LIVE SEARCH OVERLAY
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            if (searchQuery.trim().length >= 2) {
                                SearchStationsResultsView(
                                    query = searchQuery,
                                    isSearching = isSearching,
                                    results = searchResults.orEmpty().map { it.copy(isFavourite = isFav(it)) },
                                    currentStationId = snap.currentStation?.id,
                                    isPlaying = snap.isPlaying,
                                    nowPlayingMap = snap.liveStationsNowPlaying,
                                    onPlayStation = { repository.playStation(it) },
                                    onToggleFavourite = {
                                        repository.toggleFavourite(it, widgetContext?.onUpdateBridgeSettings)
                                    },
                                    onOpenDetails = { detailStation = it }
                                )
                            } else {
                                when (selectedTab) {
                                    RadioHubTab.OVERVIEW -> OverviewTabContent(
                                        snapshot = snap,
                                        isFav = ::isFav,
                                        onPlayStation = { repository.playStation(it) },
                                        onToggleFavourite = {
                                            repository.toggleFavourite(it, widgetContext?.onUpdateBridgeSettings)
                                        },
                                        onOpenDetails = { detailStation = it.copy(isFavourite = isFav(it)) }
                                    )

                                    RadioHubTab.LIVE -> LiveTabContent(
                                        stations = snap.allStations.map { it.copy(isFavourite = isFav(it)) },
                                        currentStationId = snap.currentStation?.id,
                                        isPlaying = snap.isPlaying,
                                        nowPlayingMap = snap.liveStationsNowPlaying,
                                        onPlayStation = { repository.playStation(it) },
                                        onToggleFavourite = {
                                            repository.toggleFavourite(it, widgetContext?.onUpdateBridgeSettings)
                                        },
                                        onOpenDetails = { detailStation = it }
                                    )

                                    RadioHubTab.STATIONS -> StationsGridTabContent(
                                        snapshot = snap,
                                        selectedSort = selectedSort,
                                        onSelectSort = { selectedSort = it },
                                        isFav = ::isFav,
                                        onPlayStation = { repository.playStation(it) },
                                        onToggleFavourite = {
                                            repository.toggleFavourite(it, widgetContext?.onUpdateBridgeSettings)
                                        },
                                        onOpenDetails = { detailStation = it }
                                    )

                                    RadioHubTab.CATEGORIES -> CategoriesTabContent(
                                        selectedCategory = selectedCategory,
                                        isLoading = isCategoryLoading,
                                        categoryStations = categoryStations.map { it.copy(isFavourite = isFav(it)) },
                                        currentStationId = snap.currentStation?.id,
                                        isPlaying = snap.isPlaying,
                                        nowPlayingMap = snap.liveStationsNowPlaying,
                                        onSelectCategory = { selectedCategory = it },
                                        onClearCategory = { selectedCategory = null },
                                        onPlayStation = { repository.playStation(it) },
                                        onToggleFavourite = {
                                            repository.toggleFavourite(it, widgetContext?.onUpdateBridgeSettings)
                                        },
                                        onOpenDetails = { detailStation = it }
                                    )

                                    RadioHubTab.DISCOVER -> DiscoverTabContent(
                                        feed = discoverFeed,
                                        currentStationId = snap.currentStation?.id,
                                        isPlaying = snap.isPlaying,
                                        isFav = ::isFav,
                                        onPlayStation = { repository.playStation(it) },
                                        onToggleFavourite = {
                                            repository.toggleFavourite(it, widgetContext?.onUpdateBridgeSettings)
                                        },
                                        onOpenDetails = { detailStation = it.copy(isFavourite = isFav(it)) }
                                    )

                                    RadioHubTab.HISTORY -> HistoryTabContent(
                                        selectedFilter = selectedHistoryFilter,
                                        onSelectFilter = { selectedHistoryFilter = it },
                                        historyItems = remember(snap.recentlyPlayed, selectedHistoryFilter) {
                                            repository.getFilteredHistory(selectedHistoryFilter)
                                        },
                                        onReplayStation = { item ->
                                            val matched = snap.allStations.firstOrNull { it.id == item.stationId }
                                            if (matched != null) repository.playStation(matched)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // STATION DETAILS SUB-POPUP
                    detailStation?.let { target ->
                        val refreshedTarget = target.copy(isFavourite = isFav(target))
                        StationDetailsPopup(
                            station = refreshedTarget,
                            isCurrentPlaying = snap.currentStation?.id == refreshedTarget.id && snap.isPlaying,
                            repository = repository,
                            onTogglePlayPause = { repository.togglePlayPause(refreshedTarget) },
                            onPlayStation = { st ->
                                repository.playStation(st)
                                detailStation = st.copy(isFavourite = isFav(st))
                            },
                            onToggleFavourite = {
                                repository.toggleFavourite(refreshedTarget, widgetContext?.onUpdateBridgeSettings)
                            },
                            onOpenCast = {
                                repository.discoverCastSpeakers()
                                showCastModal = true
                            },
                            onSaveRoutine = { routine ->
                                repository.saveScheduledRoutine(routine)
                                statusBannerText = "Saved routine: ${routine.stationName} (${routine.formattedSummary()})"
                            },
                            onAddToDashboard = {
                                repository.addStationToDashboard(refreshedTarget) { added ->
                                    statusBannerText = if (added) {
                                        "Added ${refreshedTarget.name} tile to Dashboard"
                                    } else {
                                        "${refreshedTarget.name} is already on your Dashboard"
                                    }
                                }
                            },
                            onDismiss = { detailStation = null }
                        )
                    }

                    // SETTINGS SUB-POPUP
                    if (showSettingsModal) {
                        RadioSettingsPopup(
                            settings = snap.settings,
                            scheduledRoutines = snap.scheduledRoutines,
                            onUpdateSettings = { repository.updateSettings(it) },
                            onAddCustomStation = { name, url, genre, badge ->
                                repository.addCustomStation(
                                    name = name,
                                    streamUrl = url,
                                    genre = genre,
                                    badgeCode = badge,
                                    onBridgeUpdated = widgetContext?.onUpdateBridgeSettings
                                )
                                statusBannerText = "Added custom station: $name"
                            },
                            onDeleteRoutine = { repository.deleteScheduledRoutine(it) },
                            onClearHistory = {
                                repository.clearListeningHistory()
                                statusBannerText = "Cleared listening history"
                            },
                            onDismiss = { showSettingsModal = false }
                        )
                    }

                    // CAST SPEAKER ROUTING DIALOG
                    if (showCastModal) {
                        CastOutputSelectorDialog(
                            activeOutputName = snap.activeOutputName,
                            speakerTiles = widgetContext?.allTiles.orEmpty().filter {
                                it.type == TileType.NEST_MINI && it.ipAddress.isNotBlank()
                            },
                            onSelectTablet = {
                                repository.selectTabletOutput()
                                showCastModal = false
                            },
                            onSelectSpeaker = { name, endpoints ->
                                repository.selectCastOutput(name, endpoints)
                                showCastModal = false
                            },
                            onDismiss = { showCastModal = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HubPersistentNowPlayingBar(
    station: RadioStation,
    nowPlaying: NowPlayingMetadata?,
    isPlaying: Boolean,
    outputName: String,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleFavourite: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenCast: () -> Unit
) {
    val trackTitle = nowPlaying?.track?.takeIf { it.isNotBlank() } ?: station.genre
    val artistSub = nowPlaying?.artist?.takeIf { it.isNotBlank() && !it.equals(station.name, true) }
        ?: nowPlaying?.showOrPresenter?.takeIf { it.isNotBlank() }
        ?: "Live Stream"

    Surface(
        color = if (isPlaying) GhTileGreenActive else GhTileSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (isPlaying) 1.3.dp else 1.dp,
            color = if (isPlaying) GhAccentGreen.copy(alpha = 0.65f) else GhCardBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDetails() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                RadioStationBadgeAvatar(
                    faviconUrl = station.faviconUrl,
                    badgeCode = station.badgeCode,
                    accentColorHex = station.accentColorHex,
                    size = 46.dp,
                    cornerRadius = 12.dp,
                    fontSize = 13.sp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = station.name,
                            color = GhTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = GhFont,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            color = if (isPlaying) GhAccentGreen.copy(alpha = 0.22f) else GhTileSurfaceElevated,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (isPlaying) "ON AIR" else "PAUSED",
                                color = if (isPlaying) GhAccentGreen else GhTextSecondary,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "$trackTitle • $artistSub",
                        color = if (isPlaying) GhAccentGreen else GhTextSecondary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Output: $outputName • Tap for Station Details, Schedule & Alarms",
                        color = GhTextSecondary,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(onClick = onToggleFavourite) {
                    Icon(
                        imageVector = if (station.isFavourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Favourite",
                        tint = if (station.isFavourite) GhAccentRed else GhTextSecondary
                    )
                }
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Station", tint = GhTextPrimary)
                }
                FilledIconButton(
                    onClick = onTogglePlayPause,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = GhAccentGreen,
                        contentColor = Color(0xFF141518)
                    ),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next Station", tint = GhTextPrimary)
                }
                IconButton(onClick = onOpenCast) {
                    Icon(Icons.Outlined.SpeakerGroup, contentDescription = "Cast Output", tint = GhTextPrimary)
                }
            }
        }
    }
}

@Composable
private fun OverviewTabContent(
    snapshot: RadioDashboardSnapshot,
    isFav: (RadioStation) -> Boolean,
    onPlayStation: (RadioStation) -> Unit,
    onToggleFavourite: (RadioStation) -> Unit,
    onOpenDetails: (RadioStation) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 4 OVERVIEW STATISTICS CARDS
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OverviewStatCard(
                    title = "Favourite Stations",
                    value = "${snapshot.activity.favouriteCount}",
                    accent = GhAccentGreen,
                    modifier = Modifier.weight(1f)
                )
                OverviewStatCard(
                    title = "Listening Hours Today",
                    value = snapshot.activity.todayListeningFormatted,
                    accent = Color(0xFF00E5FF),
                    modifier = Modifier.weight(1f)
                )
                OverviewStatCard(
                    title = "Most Played Station",
                    value = snapshot.activity.mostPlayedStationThisWeek.ifBlank {
                        snapshot.currentStation?.name ?: "—"
                    },
                    accent = GhAccentWarm,
                    modifier = Modifier.weight(1.3f)
                )
                OverviewStatCard(
                    title = "Played This Week",
                    value = "${snapshot.activity.stationsPlayedThisWeek}",
                    accent = GhAccentPurple,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // FAVOURITE / LIVE QUICK LAUNCH ROW
        if (snapshot.favourites.isNotEmpty()) {
            item {
                SectionHeaderTitle("Your Favourite Stations")
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(snapshot.favourites, key = { it.id }) { st ->
                        CompactStationTileCard(
                            station = st.copy(isFavourite = true),
                            isPlaying = snapshot.currentStation?.id == st.id && snapshot.isPlaying,
                            nowPlaying = snapshot.liveStationsNowPlaying[st.id],
                            onPlay = { onPlayStation(st) },
                            onToggleFav = { onToggleFavourite(st) },
                            onOpenDetails = { onOpenDetails(st) }
                        )
                    }
                }
            }
        }

        // RECOMMENDATIONS ROW
        if (snapshot.recommendedStations.isNotEmpty()) {
            item {
                SectionHeaderTitle("Recommended For Your Listening Habits")
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(snapshot.recommendedStations, key = { it.id }) { st ->
                        val decorated = st.copy(isFavourite = isFav(st))
                        CompactStationTileCard(
                            station = decorated,
                            isPlaying = snapshot.currentStation?.id == st.id && snapshot.isPlaying,
                            nowPlaying = snapshot.liveStationsNowPlaying[st.id],
                            onPlay = { onPlayStation(decorated) },
                            onToggleFav = { onToggleFavourite(decorated) },
                            onOpenDetails = { onOpenDetails(decorated) }
                        )
                    }
                }
            }
        }

        // LIVE STATIONS + RECENTLY PLAYED TWO-COLUMN SPLIT
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1.2f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SectionHeaderTitle("Live Stations On Air")
                    snapshot.allStations.take(6).forEach { st ->
                        val decorated = st.copy(isFavourite = isFav(st))
                        LiveStationListRow(
                            station = decorated,
                            nowPlaying = snapshot.liveStationsNowPlaying[st.id],
                            isPlaying = snapshot.currentStation?.id == st.id && snapshot.isPlaying,
                            onPlay = { onPlayStation(decorated) },
                            onToggleFav = { onToggleFavourite(decorated) },
                            onOpenDetails = { onOpenDetails(decorated) }
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SectionHeaderTitle("Recently Played")
                    if (snapshot.recentlyPlayed.isEmpty()) {
                        Surface(
                            color = GhTileSurface,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, GhCardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Start listening to any station to build your track & station history.",
                                color = GhTextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        snapshot.recentlyPlayed.take(6).forEach { hist ->
                            HistoryRowCard(
                                item = hist,
                                onClick = {
                                    snapshot.allStations.firstOrNull { it.id == hist.stationId }
                                        ?.let(onPlayStation)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTabContent(
    stations: List<RadioStation>,
    currentStationId: String?,
    isPlaying: Boolean,
    nowPlayingMap: Map<String, NowPlayingMetadata>,
    onPlayStation: (RadioStation) -> Unit,
    onToggleFavourite: (RadioStation) -> Unit,
    onOpenDetails: (RadioStation) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(stations, key = { it.id }) { st ->
            LiveStationListRow(
                station = st,
                nowPlaying = nowPlayingMap[st.id],
                isPlaying = currentStationId == st.id && isPlaying,
                onPlay = { onPlayStation(st) },
                onToggleFav = { onToggleFavourite(st) },
                onOpenDetails = { onOpenDetails(st) }
            )
        }
    }
}

@Composable
private fun StationsGridTabContent(
    snapshot: RadioDashboardSnapshot,
    selectedSort: StationSortOption,
    onSelectSort: (StationSortOption) -> Unit,
    isFav: (RadioStation) -> Boolean,
    onPlayStation: (RadioStation) -> Unit,
    onToggleFavourite: (RadioStation) -> Unit,
    onOpenDetails: (RadioStation) -> Unit
) {
    val recentOrder = remember(snapshot.recentlyPlayed) {
        snapshot.recentlyPlayed.mapIndexed { idx, h -> h.stationId to idx }.toMap()
    }

    val sortedStations = remember(snapshot.allStations, snapshot.favourites, selectedSort, recentOrder) {
        val decorated = snapshot.allStations.map { it.copy(isFavourite = isFav(it)) }
        when (selectedSort) {
            StationSortOption.FAVOURITES ->
                decorated.sortedWith(compareByDescending<RadioStation> { it.isFavourite }.thenBy { it.name })
            StationSortOption.RECENTLY_PLAYED ->
                decorated.sortedWith(compareBy<RadioStation> { recentOrder[it.id] ?: Int.MAX_VALUE }.thenBy { it.name })
            StationSortOption.MOST_POPULAR ->
                decorated.sortedByDescending { it.votes }
            StationSortOption.ALPHABETICAL ->
                decorated.sortedBy { it.name.lowercase(Locale.UK) }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Sort by:", color = GhTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            StationSortOption.entries.forEach { opt ->
                val active = selectedSort == opt
                Surface(
                    color = if (active) GhAccentGreen.copy(alpha = 0.20f) else GhTileSurface,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (active) GhAccentGreen else GhCardBorder),
                    modifier = Modifier.clickable { onSelectSort(opt) }
                ) {
                    Text(
                        text = opt.label,
                        color = if (active) GhAccentGreen else GhTextSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 225.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(sortedStations, key = { it.id }) { st ->
                AdaptiveStationGridCard(
                    station = st,
                    nowPlaying = snapshot.liveStationsNowPlaying[st.id],
                    isPlaying = snapshot.currentStation?.id == st.id && snapshot.isPlaying,
                    onPlay = { onPlayStation(st) },
                    onToggleFav = { onToggleFavourite(st) },
                    onOpenDetails = { onOpenDetails(st) }
                )
            }
        }
    }
}

@Composable
private fun CategoriesTabContent(
    selectedCategory: RadioCategory?,
    isLoading: Boolean,
    categoryStations: List<RadioStation>,
    currentStationId: String?,
    isPlaying: Boolean,
    nowPlayingMap: Map<String, NowPlayingMetadata>,
    onSelectCategory: (RadioCategory) -> Unit,
    onClearCategory: () -> Unit,
    onPlayStation: (RadioStation) -> Unit,
    onToggleFavourite: (RadioStation) -> Unit,
    onOpenDetails: (RadioStation) -> Unit
) {
    if (selectedCategory == null) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 195.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(RadioCategory.entries) { cat ->
                val accent = Color(cat.accentHex or 0xFF000000L)
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.2.dp, accent.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .height(104.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    accent.copy(alpha = 0.26f),
                                    GhTileSurface
                                )
                            )
                        )
                        .clickable { onSelectCategory(cat) }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = cat.label,
                            color = GhTextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = GhFont
                        )
                        Text(
                            text = cat.subtitle,
                            color = GhTextSecondary,
                            fontSize = 11.5.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onClearCategory,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("All Categories")
                }
                Text(
                    text = "${selectedCategory.label} Stations",
                    color = GhTextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = GhAccentGreen)
                }
            } else {
                LiveTabContent(
                    stations = categoryStations,
                    currentStationId = currentStationId,
                    isPlaying = isPlaying,
                    nowPlayingMap = nowPlayingMap,
                    onPlayStation = onPlayStation,
                    onToggleFavourite = onToggleFavourite,
                    onOpenDetails = onOpenDetails
                )
            }
        }
    }
}

@Composable
private fun DiscoverTabContent(
    feed: DiscoverFeed,
    currentStationId: String?,
    isPlaying: Boolean,
    isFav: (RadioStation) -> Boolean,
    onPlayStation: (RadioStation) -> Unit,
    onToggleFavourite: (RadioStation) -> Unit,
    onOpenDetails: (RadioStation) -> Unit
) {
    if (feed.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = GhAccentGreen)
        }
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        val sections = listOf(
            "Recommended For You" to feed.recommendedForYou,
            "Trending Stations" to feed.trendingStations,
            "Popular In Your Area" to feed.popularInArea,
            "New Stations" to feed.newStations
        )

        sections.forEach { (title, list) ->
            if (list.isNotEmpty()) {
                item(key = title) {
                    SectionHeaderTitle(title)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list, key = { "${title}_${it.id}" }) { st ->
                            val decorated = st.copy(isFavourite = isFav(st))
                            CompactStationTileCard(
                                station = decorated,
                                isPlaying = currentStationId == st.id && isPlaying,
                                nowPlaying = null,
                                onPlay = { onPlayStation(decorated) },
                                onToggleFav = { onToggleFavourite(decorated) },
                                onOpenDetails = { onOpenDetails(decorated) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryTabContent(
    selectedFilter: HistoryTimeFilter,
    onSelectFilter: (HistoryTimeFilter) -> Unit,
    historyItems: List<RadioHistoryItem>,
    onReplayStation: (RadioHistoryItem) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HistoryTimeFilter.entries.forEach { filter ->
                val active = selectedFilter == filter
                Surface(
                    color = if (active) GhAccentGreen.copy(alpha = 0.22f) else GhTileSurface,
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, if (active) GhAccentGreen else GhCardBorder),
                    modifier = Modifier.clickable { onSelectFilter(filter) }
                ) {
                    Text(
                        text = filter.label,
                        color = if (active) GhAccentGreen else GhTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }

        if (historyItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No radio listening history recorded for ${selectedFilter.label.lowercase(Locale.UK)}.",
                    color = GhTextSecondary,
                    fontSize = 13.5.sp
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(historyItems, key = { it.id }) { item ->
                    HistoryRowCard(item = item, onClick = { onReplayStation(item) })
                }
            }
        }
    }
}

@Composable
private fun SearchStationsResultsView(
    query: String,
    isSearching: Boolean,
    results: List<RadioStation>,
    currentStationId: String?,
    isPlaying: Boolean,
    nowPlayingMap: Map<String, NowPlayingMetadata>,
    onPlayStation: (RadioStation) -> Unit,
    onToggleFavourite: (RadioStation) -> Unit,
    onOpenDetails: (RadioStation) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Search Results for \"$query\" (${results.size})",
            color = GhTextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        if (isSearching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GhAccentGreen)
            }
        } else if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matching live stations found.", color = GhTextSecondary)
            }
        } else {
            LiveTabContent(
                stations = results,
                currentStationId = currentStationId,
                isPlaying = isPlaying,
                nowPlayingMap = nowPlayingMap,
                onPlayStation = onPlayStation,
                onToggleFavourite = onToggleFavourite,
                onOpenDetails = onOpenDetails
            )
        }
    }
}

// ---------------------------------------------------------------------------
// STATION DETAILS POPUP (7 ACTIONS + 4 SECTIONS)
// ---------------------------------------------------------------------------
@Composable
private fun StationDetailsPopup(
    station: RadioStation,
    isCurrentPlaying: Boolean,
    repository: RadioRepository,
    onTogglePlayPause: () -> Unit,
    onPlayStation: (RadioStation) -> Unit,
    onToggleFavourite: () -> Unit,
    onOpenCast: () -> Unit,
    onSaveRoutine: (RadioScheduledRoutine) -> Unit,
    onAddToDashboard: () -> Unit,
    onDismiss: () -> Unit
) {
    var activeSection by remember { mutableStateOf(StationDetailSection.NOW_PLAYING) }
    var nowPlaying by remember(station.id) { mutableStateOf<NowPlayingMetadata?>(null) }
    var scheduleSlots by remember(station.id) { mutableStateOf<List<RadioProgrammeSlot>>(emptyList()) }
    var similarStations by remember(station.id) { mutableStateOf<List<RadioStation>>(emptyList()) }
    var isLoadingDetails by remember(station.id) { mutableStateOf(true) }
    var albumArtBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    var showRoutineDialogMode by remember { mutableStateOf<RadioScheduleTriggerType?>(null) }

    val stationHistory = remember(station.id, isCurrentPlaying) {
        repository.getStationHistory(station.id, limit = 15)
    }

    LaunchedEffect(station.id) {
        isLoadingDetails = true
        val (meta, sched, similar) = repository.fetchStationDetailsData(station)
        nowPlaying = meta
        scheduleSlots = sched
        similarStations = similar
        isLoadingDetails = false
        if (meta.albumArtUrl.isNotBlank()) {
            albumArtBitmap = RadioImageMemoryCache.loadImage(meta.albumArtUrl)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhTileSurface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.5.dp, GhCardBorder),
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth(0.86f)
                .fillMaxHeight(0.84f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // HEADER: Logo, Name, Genre, Country, Live Indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        RadioStationBadgeAvatar(
                            faviconUrl = station.faviconUrl,
                            badgeCode = station.badgeCode,
                            accentColorHex = station.accentColorHex,
                            size = 54.dp,
                            cornerRadius = 14.dp,
                            fontSize = 15.sp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = station.name,
                                    color = GhTextPrimary,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Surface(
                                    color = GhAccentGreen.copy(alpha = 0.20f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "LIVE",
                                        color = GhAccentGreen,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "${station.genre} • ${station.country} • ${station.bitrateKbps}kbps ${station.codec}",
                                color = GhTextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    FilledTonalIconButton(
                        onClick = onDismiss,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = GhTileSurfaceElevated,
                            contentColor = GhTextPrimary
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                // ACTION BAR: Play/Pause, Favourite, Cast, Create Automation, Set Alarm, Add To Dashboard
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        Button(
                            onClick = onTogglePlayPause,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GhAccentGreen,
                                contentColor = Color(0xFF141518)
                            )
                        ) {
                            Icon(
                                imageVector = if (isCurrentPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (isCurrentPlaying) "Pause" else "Play", fontWeight = FontWeight.Bold)
                        }
                    }
                    item {
                        DetailActionChip(
                            label = if (station.isFavourite) "Favourited" else "Favourite",
                            icon = if (station.isFavourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            highlighted = station.isFavourite,
                            onClick = onToggleFavourite
                        )
                    }
                    item {
                        DetailActionChip(
                            label = "Cast",
                            icon = Icons.Outlined.Cast,
                            onClick = onOpenCast
                        )
                    }
                    item {
                        DetailActionChip(
                            label = "Set Alarm",
                            icon = Icons.Outlined.Alarm,
                            onClick = { showRoutineDialogMode = RadioScheduleTriggerType.DAILY_ALARM }
                        )
                    }
                    item {
                        DetailActionChip(
                            label = "Create Automation",
                            icon = Icons.Outlined.AutoAwesome,
                            onClick = { showRoutineDialogMode = RadioScheduleTriggerType.ARRIVING_HOME }
                        )
                    }
                    item {
                        DetailActionChip(
                            label = "Add To Dashboard",
                            icon = Icons.Outlined.DashboardCustomize,
                            onClick = onAddToDashboard
                        )
                    }
                }

                // SECTION TABS: Now Playing, Recently Played, Schedule, Similar Stations
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StationDetailSection.entries.forEach { sec ->
                        val active = activeSection == sec
                        Surface(
                            color = if (active) GhAccentGreen.copy(alpha = 0.20f) else GhTileSurfaceElevated,
                            shape = RoundedCornerShape(9.dp),
                            border = BorderStroke(1.dp, if (active) GhAccentGreen else GhCardBorder),
                            modifier = Modifier.clickable { activeSection = sec }
                        ) {
                            Text(
                                text = sec.label,
                                color = if (active) GhAccentGreen else GhTextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = GhCardBorder)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (isLoadingDetails && activeSection != StationDetailSection.RECENTLY_PLAYED) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = GhAccentGreen)
                        }
                    } else {
                        when (activeSection) {
                            StationDetailSection.NOW_PLAYING -> {
                                Surface(
                                    color = GhTileSurfaceElevated,
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, GhCardBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(18.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val bmp = albumArtBitmap
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp,
                                                contentDescription = "Track Art",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(92.dp)
                                                    .clip(RoundedCornerShape(14.dp))
                                            )
                                        } else {
                                            RadioStationBadgeAvatar(
                                                faviconUrl = station.faviconUrl,
                                                badgeCode = station.badgeCode,
                                                accentColorHex = station.accentColorHex,
                                                size = 92.dp,
                                                cornerRadius = 14.dp,
                                                fontSize = 22.sp
                                            )
                                        }

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = nowPlaying?.track?.ifBlank { station.name } ?: station.name,
                                                color = GhTextPrimary,
                                                fontSize = 19.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                            Text(
                                                text = nowPlaying?.artist?.ifBlank { station.genre } ?: station.genre,
                                                color = GhAccentGreen,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            nowPlaying?.showOrPresenter?.takeIf { it.isNotBlank() }?.let { show ->
                                                Text(
                                                    text = show,
                                                    color = GhTextSecondary,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            val pct = (nowPlaying?.progressPercent ?: if (isCurrentPlaying) 100 else 0) / 100f
                                            LinearProgressIndicator(
                                                progress = { pct.coerceIn(0f, 1f) },
                                                color = GhAccentGreen,
                                                trackColor = GhTileSurface,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(5.dp)
                                                    .clip(RoundedCornerShape(50))
                                            )
                                        }
                                    }
                                }
                            }

                            StationDetailSection.RECENTLY_PLAYED -> {
                                if (stationHistory.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("No tracks logged yet for ${station.name}.", color = GhTextSecondary)
                                    }
                                } else {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(stationHistory, key = { it.id }) { h ->
                                            HistoryRowCard(item = h, onClick = { onTogglePlayPause() })
                                        }
                                    }
                                }
                            }

                            StationDetailSection.SCHEDULE -> {
                                if (scheduleSlots.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "24/7 continuous live broadcast (${station.genre}).",
                                            color = GhTextSecondary,
                                            fontSize = 13.sp
                                        )
                                    }
                                } else {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(scheduleSlots, key = { it.id }) { slot ->
                                            Surface(
                                                color = if (slot.isLiveNow) GhTileGreenActive else GhTileSurfaceElevated,
                                                shape = RoundedCornerShape(12.dp),
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (slot.isLiveNow) GhAccentGreen else GhCardBorder
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(14.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = slot.title,
                                                            color = GhTextPrimary,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        if (slot.presenterOrSubtitle.isNotBlank()) {
                                                            Text(
                                                                text = slot.presenterOrSubtitle,
                                                                color = GhTextSecondary,
                                                                fontSize = 12.sp
                                                            )
                                                        }
                                                    }
                                                    Text(
                                                        text = "${slot.startTimeLabel} – ${slot.endTimeLabel}",
                                                        color = if (slot.isLiveNow) GhAccentGreen else GhTextSecondary,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            StationDetailSection.SIMILAR -> {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(similarStations, key = { it.id }) { sim ->
                                        LiveStationListRow(
                                            station = sim,
                                            nowPlaying = null,
                                            isPlaying = false,
                                            onPlay = { onPlayStation(sim) },
                                            onToggleFav = { repository.toggleFavourite(sim) },
                                            onOpenDetails = { onPlayStation(sim) }
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

    showRoutineDialogMode?.let { initialMode ->
        CreateRadioRoutineDialog(
            station = station,
            initialTrigger = initialMode,
            onSave = { routine ->
                onSaveRoutine(routine)
                showRoutineDialogMode = null
            },
            onDismiss = { showRoutineDialogMode = null }
        )
    }
}

// ---------------------------------------------------------------------------
// ALARM & AUTOMATION ROUTINE CREATOR DIALOG
// ---------------------------------------------------------------------------
@Composable
private fun CreateRadioRoutineDialog(
    station: RadioStation,
    initialTrigger: RadioScheduleTriggerType,
    onSave: (RadioScheduledRoutine) -> Unit,
    onDismiss: () -> Unit
) {
    var triggerType by remember { mutableStateOf(initialTrigger) }
    var hour by remember { mutableIntStateOf(7) }
    var minute by remember { mutableIntStateOf(0) }
    var days by remember { mutableStateOf(setOf(1, 2, 3, 4, 5)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GhTileSurface,
        title = {
            Text(
                text = "Automate ${station.name}",
                color = GhTextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RadioScheduleTriggerType.entries.forEach { opt ->
                    val selected = triggerType == opt
                    Surface(
                        color = if (selected) GhAccentGreen.copy(alpha = 0.18f) else GhTileSurfaceElevated,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (selected) GhAccentGreen else GhCardBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { triggerType = opt }
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(opt.label, color = GhTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(opt.description, color = GhTextSecondary, fontSize = 11.sp)
                        }
                    }
                }

                if (triggerType == RadioScheduleTriggerType.DAILY_ALARM) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { hour = (hour + 23) % 24 }) {
                                Icon(Icons.Default.Remove, contentDescription = null, tint = GhTextPrimary)
                            }
                            Text(String.format(Locale.UK, "%02d", hour), color = GhAccentGreen, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            IconButton(onClick = { hour = (hour + 1) % 24 }) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = GhTextPrimary)
                            }
                        }
                        Text(":", color = GhTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { minute = (minute + 55) % 60 }) {
                                Icon(Icons.Default.Remove, contentDescription = null, tint = GhTextPrimary)
                            }
                            Text(String.format(Locale.UK, "%02d", minute), color = GhAccentGreen, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            IconButton(onClick = { minute = (minute + 5) % 60 }) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = GhTextPrimary)
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S").forEach { (d, lbl) ->
                            val active = d in days
                            Surface(
                                color = if (active) GhAccentGreen else GhTileSurfaceElevated,
                                shape = CircleShape,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable {
                                        days = if (active && days.size > 1) days - d else days + d
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = lbl,
                                        color = if (active) Color(0xFF141518) else GhTextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        RadioScheduledRoutine(
                            stationId = station.id,
                            stationName = station.name,
                            streamUrl = station.streamUrl,
                            label = triggerType.label,
                            triggerType = triggerType,
                            hour = hour,
                            minute = minute,
                            daysOfWeek = days
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = GhAccentGreen, contentColor = Color(0xFF141518))
            ) {
                Text("Save Routine", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = GhTextSecondary)
            }
        }
    )
}

// ---------------------------------------------------------------------------
// CAST SPEAKER OUTPUT SELECTOR DIALOG
// ---------------------------------------------------------------------------
@Composable
private fun CastOutputSelectorDialog(
    activeOutputName: String,
    speakerTiles: List<SmartTile>,
    onSelectTablet: () -> Unit,
    onSelectSpeaker: (String, List<Pair<String, Int>>) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GhTileSurface,
        title = {
            Text("Audio Output Target", color = GhTextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val tabletSelected = activeOutputName == "Tablet Speaker"
                Surface(
                    color = if (tabletSelected) GhAccentGreen.copy(alpha = 0.20f) else GhTileSurfaceElevated,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (tabletSelected) GhAccentGreen else GhCardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTablet() }
                ) {
                    Text(
                        text = "Tablet Speaker (Local ExoPlayer)",
                        color = if (tabletSelected) GhAccentGreen else GhTextPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                speakerTiles.forEach { spk ->
                    val endpoints = spk.ipAddress.split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .map { raw ->
                            val ip = raw.substringBefore(":")
                            val port = raw.substringAfter(":", "8009").toIntOrNull() ?: 8009
                            ip to port
                        }
                    if (endpoints.isNotEmpty()) {
                        val selected = activeOutputName.equals(spk.title, ignoreCase = true)
                        Surface(
                            color = if (selected) GhAccentGreen.copy(alpha = 0.20f) else GhTileSurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (selected) GhAccentGreen else GhCardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSpeaker(spk.title, endpoints) }
                        ) {
                            Text(
                                text = "${spk.title} (Google Cast)",
                                color = if (selected) GhAccentGreen else GhTextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = GhAccentGreen)
            }
        }
    )
}

// ---------------------------------------------------------------------------
// REUSABLE CARDS & HELPERS
// ---------------------------------------------------------------------------
@Composable
private fun SectionHeaderTitle(text: String) {
    Text(
        text = text,
        color = GhTextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.ExtraBold,
        fontFamily = GhFont
    )
}

@Composable
private fun OverviewStatCard(
    title: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = GhTileSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, GhCardBorder),
        modifier = modifier.height(76.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, color = GhTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                text = value,
                color = accent,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CompactStationTileCard(
    station: RadioStation,
    isPlaying: Boolean,
    nowPlaying: NowPlayingMetadata?,
    onPlay: () -> Unit,
    onToggleFav: () -> Unit,
    onOpenDetails: () -> Unit
) {
    Surface(
        color = if (isPlaying) GhTileGreenActive else GhTileSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (isPlaying) GhAccentGreen else GhCardBorder),
        modifier = Modifier
            .width(215.dp)
            .clickable { onOpenDetails() }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioStationBadgeAvatar(
                    faviconUrl = station.faviconUrl,
                    badgeCode = station.badgeCode,
                    accentColorHex = station.accentColorHex,
                    size = 40.dp
                )
                Row {
                    IconButton(onClick = onToggleFav, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = if (station.isFavourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "Favourite",
                            tint = if (station.isFavourite) GhAccentRed else GhTextSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    IconButton(onClick = onPlay, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = GhAccentGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Column {
                Text(
                    text = station.name,
                    color = GhTextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = nowPlaying?.primaryDisplayLine?.takeIf { nowPlaying.hasTrackInfo } ?: station.genre,
                    color = GhTextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AdaptiveStationGridCard(
    station: RadioStation,
    nowPlaying: NowPlayingMetadata?,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onToggleFav: () -> Unit,
    onOpenDetails: () -> Unit
) {
    Surface(
        color = if (isPlaying) GhTileGreenActive else GhTileSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (isPlaying) GhAccentGreen else GhCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDetails() }
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                RadioStationBadgeAvatar(
                    faviconUrl = station.faviconUrl,
                    badgeCode = station.badgeCode,
                    accentColorHex = station.accentColorHex,
                    size = 52.dp,
                    cornerRadius = 14.dp,
                    fontSize = 15.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onToggleFav, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = if (station.isFavourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "Favourite",
                            tint = if (station.isFavourite) GhAccentRed else GhTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    FilledIconButton(
                        onClick = onPlay,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isPlaying) GhAccentGreen else GhTileSurfaceElevated,
                            contentColor = if (isPlaying) Color(0xFF141518) else GhAccentGreen
                        ),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = station.name,
                    color = GhTextPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = nowPlaying?.primaryDisplayLine?.takeIf { nowPlaying.hasTrackInfo } ?: station.genre,
                    color = if (isPlaying) GhAccentGreen else GhTextSecondary,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LiveStationListRow(
    station: RadioStation,
    nowPlaying: NowPlayingMetadata?,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onToggleFav: () -> Unit,
    onOpenDetails: () -> Unit
) {
    val trackText = nowPlaying?.track?.takeIf { it.isNotBlank() } ?: station.genre
    val artistText = nowPlaying?.artist?.takeIf { it.isNotBlank() } ?: station.country

    Surface(
        color = if (isPlaying) GhTileGreenActive else GhTileSurface,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, if (isPlaying) GhAccentGreen else GhCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDetails() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                RadioStationBadgeAvatar(
                    faviconUrl = station.faviconUrl,
                    badgeCode = station.badgeCode,
                    accentColorHex = station.accentColorHex,
                    size = 42.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = station.name,
                            color = GhTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            color = GhAccentGreen.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "LIVE",
                                color = GhAccentGreen,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Text(
                        text = "$trackText • $artistText",
                        color = if (isPlaying) GhAccentGreen else GhTextSecondary,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleFav) {
                    Icon(
                        imageVector = if (station.isFavourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Favourite",
                        tint = if (station.isFavourite) GhAccentRed else GhTextSecondary
                    )
                }
                FilledIconButton(
                    onClick = onPlay,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isPlaying) GhAccentGreen else GhTileSurfaceElevated,
                        contentColor = if (isPlaying) Color(0xFF141518) else GhAccentGreen
                    ),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play"
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRowCard(
    item: RadioHistoryItem,
    onClick: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("EEE HH:mm", Locale.UK) }
    val durMins = (item.durationMs / 60_000L).toInt()
    val durLabel = if (durMins >= 1) "${durMins}m" else "${(item.durationMs / 1000L).coerceAtLeast(1)}s"

    Surface(
        color = GhTileSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, GhCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                RadioStationBadgeAvatar(
                    faviconUrl = item.faviconUrl,
                    badgeCode = item.badgeCode,
                    accentColorHex = item.accentColorHex,
                    size = 34.dp,
                    cornerRadius = 8.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.track,
                        color = GhTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${item.stationName} • ${item.artist}",
                        color = GhTextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmt.format(Date(item.timestampEpochMs)), color = GhTextSecondary, fontSize = 10.5.sp)
                Text(durLabel, color = GhAccentGreen, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DetailActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        color = if (highlighted) GhAccentGreen.copy(alpha = 0.20f) else GhTileSurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (highlighted) GhAccentGreen else GhCardBorder),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (highlighted) GhAccentGreen else GhTextPrimary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                color = if (highlighted) GhAccentGreen else GhTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}