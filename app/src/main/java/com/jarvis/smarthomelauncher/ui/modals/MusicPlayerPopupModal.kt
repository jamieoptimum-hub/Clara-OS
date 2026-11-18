package com.jarvis.smarthomelauncher.ui.modals

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.roundToInt

private fun formatDurationMmSs(ms: Long, prefixMinus: Boolean = false): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val mins = totalSec / 60
    val secs = totalSec % 60
    val formatted = "%d:%02d".format(mins, secs)
    return if (prefixMinus) "-$formatted" else formatted
}

// ---------------------------------------------------------------------------
// IN-MEMORY BITMAP CACHE & THUMBNAIL LOADER FOR PLAYLIST & TRACK COVER ART
// ---------------------------------------------------------------------------
private object MusicCoverArtBitmapCache {
    private val cache = object : LruCache<String, ImageBitmap>(180) {}

    fun get(url: String): ImageBitmap? =
        if (url.isBlank()) null else cache.get(url)

    fun put(url: String, bitmap: ImageBitmap) {
        if (url.isNotBlank()) {
            cache.put(url, bitmap)
        }
    }
}

@Composable
internal fun MusicArtworkThumbnail(
    item: NativeMusicItem? = null,
    explicitArtworkUrl: String = "",
    fallbackIcon: ImageVector = Icons.Outlined.MusicNote,
    size: Dp = 40.dp,
    cornerRadius: Dp = 8.dp
) {
    val initialUrl = explicitArtworkUrl.ifBlank { item?.artworkUrl.orEmpty() }
    var resolvedUrl by remember(item?.id, initialUrl) { mutableStateOf(initialUrl) }
    var loadedBitmap by remember(resolvedUrl) {
        mutableStateOf(MusicCoverArtBitmapCache.get(resolvedUrl))
    }

    LaunchedEffect(item?.id, initialUrl) {
        var targetUrl = initialUrl
        if (targetUrl.isBlank() && item != null) {
            targetUrl = NativeMusicCatalogClient.resolveArtworkUrlForItem(item).orEmpty()
            if (targetUrl.isNotBlank()) {
                resolvedUrl = targetUrl
            }
        }
        if (targetUrl.startsWith("http")) {
            val cached = MusicCoverArtBitmapCache.get(targetUrl)
            if (cached != null) {
                loadedBitmap = cached
            } else {
                val bmp = withContext(Dispatchers.IO) {
                    try {
                        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 4000
                            readTimeout = 4000
                            setRequestProperty("User-Agent", "Mozilla/5.0")
                        }
                        if (conn.responseCode in 200..299) {
                            val decoded = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                            conn.disconnect()
                            decoded?.asImageBitmap()
                        } else {
                            conn.disconnect()
                            null
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                if (bmp != null) {
                    MusicCoverArtBitmapCache.put(targetUrl, bmp)
                    loadedBitmap = bmp
                }
            }
        }
    }

    val bmpToDraw = loadedBitmap
    if (bmpToDraw != null) {
        Image(
            bitmap = bmpToDraw,
            contentDescription = item?.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius))
        )
    } else {
        Surface(
            color = GhAccentGreen.copy(alpha = 0.18f),
            shape = RoundedCornerShape(cornerRadius),
            modifier = Modifier.size(size)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = fallbackIcon,
                    contentDescription = null,
                    tint = GhAccentGreen,
                    modifier = Modifier.size(size * 0.48f)
                )
            }
        }
    }
}

private enum class NativeMusicPopupTab(val shortLabel: String, val icon: ImageVector) {
    LIBRARY("Playlists", Icons.Outlined.LibraryMusic),
    PLEX("Plex Server", Icons.Outlined.Storage),
    SEARCH("Search", Icons.Outlined.Search),
    ACCOUNT("Account", Icons.Outlined.AccountCircle)
}

// ---------------------------------------------------------------------------
// INTEGRATED FULL-PAGE MUSIC & CAST SECTION (NO POP-UP MODAL)
// - Defaults to the Playlists tab
// - Cast & Speakers is a simple button in the top bar that expands/collapses
//   the speaker & multi-room cast drawer inline
// ---------------------------------------------------------------------------
@Composable
fun IntegratedMusicPlayerSection(
    @Suppress("UNUSED_PARAMETER") initialProvider: MusicProvider,
    bridgeSettings: BridgeSettings = BridgeSettings(),
    isPlaying: Boolean,
    currentTrack: String,
    masterVolume: Float,
    onMasterVolumeChange: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onSkip: () -> Unit,
    onPrevious: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenNativeApp: () -> Unit,
    showCloseButton: Boolean = false,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val activeProvider = bridgeSettings.musicProvider

    var activeTab by remember { mutableStateOf(NativeMusicPopupTab.LIBRARY) }
    var isCastExpanded by remember { mutableStateOf(false) }
    var isScanningCast by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    val libraryItems = remember { mutableStateListOf<NativeMusicItem>() }
    val plexLibraryItems = remember { mutableStateListOf<NativeMusicItem>() }
    var isLoadingPlexLibrary by remember { mutableStateOf(false) }
    var openedPlaylist by remember { mutableStateOf<NativeMusicItem?>(null) }
    val openedPlaylistTracks = remember { mutableStateListOf<NativeMusicItem>() }
    var isLoadingPlaylistTracks by remember { mutableStateOf(false) }

    var showAddPlaylistRow by remember { mutableStateOf(false) }
    var playlistSearchQuery by remember { mutableStateOf("") }
    var isBuildingPlaylistFromSearch by remember { mutableStateOf(false) }
    val playlistCatalogMatches = remember { mutableStateListOf<NativeMusicItem>() }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchingCatalog by remember { mutableStateOf(false) }
    val searchResults = remember { mutableStateListOf<NativeMusicItem>() }

    var isSpotifyConnected by remember { mutableStateOf(NativeMusicCatalogClient.isSpotifySignedIn(context)) }
    var isYouTubeConnected by remember { mutableStateOf(NativeMusicCatalogClient.isYouTubeSignedIn(context)) }
    var webLoginProvider by remember { mutableStateOf<MusicProvider?>(null) }

    val isMusicSyncing by JarvisLiveBridge.isMusicSyncingFlow.collectAsState()
    val musicSyncProgress by JarvisLiveBridge.musicSyncProgressFlow.collectAsState()
    val musicSyncStatus by JarvisLiveBridge.musicSyncStatusFlow.collectAsState()

    val trackPositionMs by JarvisLiveBridge.trackPositionMsFlow.collectAsState()
    val trackDurationMs by JarvisLiveBridge.trackDurationMsFlow.collectAsState()
    val activeArtworkUrl by ClaraInAppMusicPlayer.activeArtworkUrlFlow.collectAsState()

    val discoveredCastDevices by JarvisLiveBridge.discoveredCastDevicesFlow.collectAsState()
    val castSpeakerGroups by JarvisLiveBridge.castSpeakerGroupsFlow.collectAsState()
    val activeCastTarget by JarvisLiveBridge.activeCastTargetNameFlow.collectAsState()

    val compactInputStyle = TextStyle(fontSize = 12.sp, fontFamily = GhFont, color = GhTextPrimary)

    fun reloadLocalLibrary() {
        val local = NativeMusicCatalogClient.loadLibraryAndPlaylists(context, activeProvider)
        libraryItems.clear()
        libraryItems.addAll(local)
        if (bridgeSettings.plexServerUrl.isNotBlank()) {
            scope.launch {
                isLoadingPlexLibrary = true
                val plexItems = NativeMusicCatalogClient.fetchPlexPlaylistsAndTracks(bridgeSettings)
                plexLibraryItems.clear()
                plexLibraryItems.addAll(plexItems)
                isLoadingPlexLibrary = false
                for (plexItem in plexItems) {
                    if (libraryItems.none { it.id == plexItem.id }) {
                        libraryItems.add(plexItem)
                    }
                }
            }
        } else {
            plexLibraryItems.clear()
        }
    }

    fun openPlaylistInsidePopup(playlistItem: NativeMusicItem) {
        openedPlaylist = playlistItem
        scope.launch {
            isLoadingPlaylistTracks = true
            val tracks = NativeMusicCatalogClient.fetchTracksForPlaylist(
                context = context,
                playlist = playlistItem,
                bridge = bridgeSettings
            )
            openedPlaylistTracks.clear()
            openedPlaylistTracks.addAll(tracks)
            isLoadingPlaylistTracks = false
        }
    }

    fun searchCatalogForPlaylistCreation(queryToSearch: String) {
        val clean = queryToSearch.trim()
        if (clean.isBlank()) return
        scope.launch {
            isBuildingPlaylistFromSearch = true
            val res = NativeMusicCatalogClient.searchMusicCatalog(context, activeProvider, clean)
            isBuildingPlaylistFromSearch = false
            if (res.isSuccess) {
                playlistCatalogMatches.clear()
                playlistCatalogMatches.addAll(res.getOrThrow().take(8))
            } else {
                statusMessage = res.exceptionOrNull()?.message ?: "Could not search catalog"
            }
        }
    }

    fun createPlaylistMixFromQuery(rawQuery: String, customArtwork: String = "", trackCountHint: Int = 0) {
        val clean = rawQuery.trim()
        if (clean.isBlank()) return
        scope.launch {
            isBuildingPlaylistFromSearch = true
            var coverToUse = customArtwork
            var countToUse = trackCountHint
            if (coverToUse.isBlank()) {
                val res = NativeMusicCatalogClient.searchMusicCatalog(context, activeProvider, clean)
                val found = res.getOrNull().orEmpty()
                coverToUse = found.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty()
                if (countToUse == 0) countToUse = found.size
            }
            val formattedTitle = clean.split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            val newMixItem = NativeMusicItem(
                id = UUID.randomUUID().toString(),
                title = formattedTitle,
                subtitle = "Custom Playlist",
                provider = activeProvider,
                itemType = NativeMusicItemType.PLAYLIST,
                playbackUriOrQuery = clean,
                artworkUrl = coverToUse,
                trackCount = countToUse,
                isSavedInLibrary = true
            )
            val updated = NativeMusicCatalogClient.saveItemToLocalLibrary(context, newMixItem)
            libraryItems.clear()
            libraryItems.addAll(updated)
            playlistSearchQuery = ""
            playlistCatalogMatches.clear()
            isBuildingPlaylistFromSearch = false
            showAddPlaylistRow = false
            statusMessage = "Created playlist '$formattedTitle'!"
            openPlaylistInsidePopup(newMixItem)
        }
    }

    fun performNativeSearch(queryToRun: String) {
        val clean = queryToRun.trim()
        if (clean.isBlank()) return
        scope.launch {
            isSearchingCatalog = true
            val res = NativeMusicCatalogClient.searchMusicCatalog(context, activeProvider, clean)
            isSearchingCatalog = false
            if (res.isSuccess) {
                searchResults.clear()
                searchResults.addAll(res.getOrThrow())
            } else {
                statusMessage = res.exceptionOrNull()?.message ?: "Search error"
            }
        }
    }

    LaunchedEffect(activeProvider, isSpotifyConnected, bridgeSettings.plexServerUrl, bridgeSettings.plexToken) {
        ClaraInAppMusicPlayer.initContextIfNeeded(context)
        if (activeProvider == MusicProvider.SPOTIFY && isSpotifyConnected) {
            SpotifyHiddenWebPlayer.ensureInitialized(context) {
                ClaraInAppMusicPlayer.skipToNext(audioManager, scope)
            }
        }
        reloadLocalLibrary()
    }

    LaunchedEffect(Unit) {
        if (discoveredCastDevices.isEmpty()) {
            isScanningCast = true
            GoogleCastLanClient.discoverCastDevices(context, includeFullPortSweep = false)
            isScanningCast = false
        }
    }

    val quickMoodQueries = listOf(
        "Chill Hits",
        "80s & 90s Hits",
        "Acoustic Morning",
        "Upbeat Workout",
        "Lo-Fi Beats",
        "Top Pop Hits",
        "House & Dance"
    )

    val isCurrentProviderConnected = if (activeProvider == MusicProvider.SPOTIFY) {
        isSpotifyConnected
    } else {
        isYouTubeConnected
    }

    SfHouseSunkenTileContainer(
        outerCornerRadius = 22.dp,
        innerCornerRadius = 16.dp,
        sunkenInsetPadding = 6.dp,
        modifier = modifier.fillMaxSize()
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompactMobile = maxWidth < 600.dp
            val gridCols = if (isCompactMobile) 1 else 2

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = if (isCompactMobile) 10.dp else 16.dp,
                        vertical = if (isCompactMobile) 10.dp else 12.dp
                    ),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Bar: Playlists / Search / Account Tabs + Expandable "Cast & Speakers" Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val visiblePopupTabs = remember(bridgeSettings.plexServerUrl) {
                        NativeMusicPopupTab.entries.filter { tab ->
                            tab != NativeMusicPopupTab.PLEX || bridgeSettings.plexServerUrl.isNotBlank()
                        }
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        items(visiblePopupTabs) { tab ->
                            val isSel = activeTab == tab
                            val labelText = when (tab) {
                                NativeMusicPopupTab.ACCOUNT ->
                                    if (isCompactMobile) "Account" else "${activeProvider.label} Account"
                                NativeMusicPopupTab.PLEX ->
                                    if (plexLibraryItems.isNotEmpty()) "Plex (${plexLibraryItems.size})" else "Plex Server"
                                else -> tab.shortLabel
                            }
                            Surface(
                                color = if (isSel) GhAccentGreen else GhTileSurfaceElevated,
                                shape = RoundedCornerShape(9.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSel) GhAccentGreen else GhCardBorder
                                ),
                                modifier = Modifier
                                    .height(34.dp)
                                    .clickable {
                                        activeTab = tab
                                        if (tab != NativeMusicPopupTab.LIBRARY) {
                                            openedPlaylist = null
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = if (isCompactMobile) 11.dp else 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = null,
                                        tint = if (isSel) Color(0xFF141518) else GhTextSecondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = labelText,
                                        color = if (isSel) Color(0xFF141518) else GhTextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                                        fontFamily = GhFont,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Simple Button that Expands / Collapses Cast & Speakers
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = if (isCastExpanded) GhAccentGreen else GhTileSurfaceElevated,
                            shape = RoundedCornerShape(9.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isCastExpanded || activeCastTarget != "Tablet Speaker") GhAccentGreen else GhCardBorder
                            ),
                            modifier = Modifier
                                .height(34.dp)
                                .clickable { isCastExpanded = !isCastExpanded }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Cast,
                                    contentDescription = "Cast & Speakers",
                                    tint = if (isCastExpanded) Color(0xFF141518) else GhAccentGreen,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Cast & Speakers (${discoveredCastDevices.size})",
                                    color = if (isCastExpanded) Color(0xFF141518) else GhTextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = GhFont,
                                    maxLines = 1
                                )
                                Icon(
                                    imageVector = if (isCastExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = if (isCastExpanded) Color(0xFF141518) else GhTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (showCloseButton) {
                            FilledTonalIconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(34.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = GhTileSurfaceElevated,
                                    contentColor = GhTextPrimary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Expandable Cast & Speakers Drawer
                AnimatedVisibility(visible = isCastExpanded) {
                    Surface(
                        color = GhSunkenWell,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Cast & Speakers \u2022 Active: $activeCastTarget",
                                        color = GhAccentGreen,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = GhFont
                                    )
                                    Text(
                                        text = "Tap any speaker or group below to route audio and adjust volume",
                                        color = GhTextSecondary,
                                        fontSize = 10.sp,
                                        fontFamily = GhFont
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Surface(
                                        color = if (activeCastTarget == "Tablet Speaker") GhTileGreenActive else GhTileSurfaceElevated,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (activeCastTarget == "Tablet Speaker") GhAccentGreen else GhCardBorder
                                        ),
                                        modifier = Modifier
                                            .height(28.dp)
                                            .clickable {
                                                ClaraInAppMusicPlayer.selectTabletSpeakerOutput(scope)
                                                statusMessage = "Switched audio output to This Tablet"
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.TabletAndroid,
                                                contentDescription = null,
                                                tint = GhAccentGreen,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = "This Tablet",
                                                color = GhTextPrimary,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }

                                    Surface(
                                        color = GhTileSurfaceElevated,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, GhCardBorder),
                                        modifier = Modifier
                                            .height(28.dp)
                                            .clickable {
                                                scope.launch {
                                                    isScanningCast = true
                                                    GoogleCastLanClient.discoverCastDevices(context)
                                                    isScanningCast = false
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Refresh,
                                                contentDescription = null,
                                                tint = GhTextSecondary,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = if (isScanningCast) "Scanning..." else "Scan Wi-Fi",
                                                color = GhTextPrimary,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(castSpeakerGroups, key = { "cast_grp_${it.id}" }) { group ->
                                    var grpVol by remember(group.id, group.volumePercent) {
                                        mutableFloatStateOf(group.volumePercent)
                                    }
                                    val isGroupActive = activeCastTarget == group.name
                                    Surface(
                                        color = GhTileSurface,
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isGroupActive) GhAccentGreen else GhCardBorder
                                        ),
                                        modifier = Modifier
                                            .width(230.dp)
                                            .clickable {
                                                val endpoints = group.memberIps.map { raw ->
                                                    val host = raw.substringBefore(":")
                                                    val port = raw.substringAfter(":", "8009").toIntOrNull() ?: 8009
                                                    host to port
                                                }
                                                ClaraInAppMusicPlayer.selectCastSpeakerOutput(
                                                    targetName = group.name,
                                                    endpoints = endpoints,
                                                    scope = scope
                                                )
                                                statusMessage = "Casting directly to group '${group.name}'!"
                                            }
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = if (isGroupActive) "${group.name} \u2713" else group.name,
                                                    color = if (isGroupActive) GhAccentGreen else GhTextPrimary,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = "${grpVol.roundToInt()}%",
                                                    color = GhAccentGreen,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            Slider(
                                                value = grpVol,
                                                onValueChange = { nextV ->
                                                    grpVol = nextV
                                                    scope.launch {
                                                        group.memberIps.forEach { raw ->
                                                            val host = raw.substringBefore(":")
                                                            val port = raw.substringAfter(":", "8009").toIntOrNull() ?: 8009
                                                            GoogleCastLanClient.setCastDeviceVolume(host, port, nextV)
                                                        }
                                                    }
                                                },
                                                valueRange = 0f..100f,
                                                modifier = Modifier.height(22.dp),
                                                colors = SliderDefaults.colors(
                                                    thumbColor = Color.White,
                                                    activeTrackColor = GhAccentGreen
                                                )
                                            )
                                        }
                                    }
                                }

                                items(discoveredCastDevices, key = { "cast_dev_${it.ipAddress}:${it.port}" }) { dev ->
                                    var devVol by remember(dev.id, dev.volumePercent) {
                                        mutableFloatStateOf(dev.volumePercent)
                                    }
                                    val isDevActive = activeCastTarget == dev.name
                                    Surface(
                                        color = GhTileSurface,
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isDevActive) GhAccentGreen else GhCardBorder
                                        ),
                                        modifier = Modifier
                                            .width(230.dp)
                                            .clickable {
                                                ClaraInAppMusicPlayer.selectCastSpeakerOutput(
                                                    targetName = dev.name,
                                                    endpoints = listOf(dev.ipAddress to dev.port),
                                                    scope = scope
                                                )
                                                statusMessage = "Casting directly to '${dev.name}'!"
                                            }
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = if (isDevActive) "${dev.name} \u2713" else dev.name,
                                                    color = if (isDevActive) GhAccentGreen else GhTextPrimary,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = "${devVol.roundToInt()}%",
                                                    color = GhAccentGreen,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            Slider(
                                                value = devVol,
                                                onValueChange = { nextV ->
                                                    devVol = nextV
                                                    scope.launch {
                                                        GoogleCastLanClient.setCastDeviceVolume(dev.ipAddress, dev.port, nextV)
                                                    }
                                                },
                                                valueRange = 0f..100f,
                                                modifier = Modifier.height(22.dp),
                                                colors = SliderDefaults.colors(
                                                    thumbColor = Color.White,
                                                    activeTrackColor = GhAccentGreen
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = isMusicSyncing) {
                    Surface(
                        color = GhTileBlueActive.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, GhAccentBlue.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = musicSyncStatus.ifBlank { "Syncing ${activeProvider.label} library..." },
                                    color = GhTextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = GhFont,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${(musicSyncProgress * 100).roundToInt().coerceIn(0, 100)}%",
                                    color = GhAccentGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GhFont
                                )
                            }
                            LinearProgressIndicator(
                                progress = { musicSyncProgress.coerceIn(0f, 1f) },
                                color = GhAccentGreen,
                                trackColor = GhTileSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(50))
                            )
                        }
                    }
                }

                if (statusMessage.isNotBlank() && !isMusicSyncing) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = GhTileSurfaceElevated,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = statusMessage,
                                color = GhAccentGreen,
                                fontSize = 11.sp,
                                fontFamily = GhFont,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { statusMessage = "" }, modifier = Modifier.size(18.dp)) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = GhTextSecondary, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    color = GhSunkenWell.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, GhCardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (activeTab) {
                        NativeMusicPopupTab.LIBRARY -> {
                            val currentPlaylist = openedPlaylist
                            if (currentPlaylist != null) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            FilledTonalButton(
                                                onClick = { openedPlaylist = null },
                                                shape = RoundedCornerShape(9.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(30.dp)
                                            ) {
                                                Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Back", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                            MusicArtworkThumbnail(
                                                item = currentPlaylist,
                                                fallbackIcon = Icons.Outlined.QueueMusic,
                                                size = 32.dp,
                                                cornerRadius = 6.dp
                                            )
                                            Text(
                                                text = "${currentPlaylist.title} (${openedPlaylistTracks.size})",
                                                color = GhTextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                fontFamily = GhFont,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (openedPlaylistTracks.isNotEmpty()) {
                                            Button(
                                                onClick = {
                                                    ClaraInAppMusicPlayer.playTrackQueue(
                                                        items = openedPlaylistTracks.toList(),
                                                        startIndex = 0,
                                                        scope = scope
                                                    )
                                                    statusMessage = "Playing '${currentPlaylist.title}' on $activeCastTarget"
                                                },
                                                shape = RoundedCornerShape(9.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = GhAccentGreen,
                                                    contentColor = Color(0xFF141518)
                                                ),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                modifier = Modifier.height(30.dp)
                                            ) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Play All", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }

                                    if (isLoadingPlaylistTracks) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Loading songs in '${currentPlaylist.title}'...",
                                                color = GhAccentGreen,
                                                fontSize = 13.sp,
                                                fontFamily = GhFont
                                            )
                                        }
                                    } else if (openedPlaylistTracks.isEmpty()) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = if (isCurrentProviderConnected) {
                                                        "No tracks cached yet for '${currentPlaylist.title}'. Tap 'Retry Loading Songs' or resync your ${activeProvider.label} library."
                                                    } else {
                                                        "Tap 'Sync Account' in the top right to import your songs from ${activeProvider.label}."
                                                    },
                                                    color = GhTextSecondary,
                                                    fontSize = 12.sp,
                                                    fontFamily = GhFont,
                                                    textAlign = TextAlign.Center
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    if (isCurrentProviderConnected) {
                                                        FilledTonalButton(
                                                            onClick = { openPlaylistInsidePopup(currentPlaylist) },
                                                            shape = RoundedCornerShape(9.dp),
                                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                                containerColor = GhTileSurfaceElevated,
                                                                contentColor = GhAccentGreen
                                                            )
                                                        ) {
                                                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text("Retry Loading Songs", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                                        }
                                                    }
                                                    Button(
                                                        onClick = { webLoginProvider = activeProvider },
                                                        shape = RoundedCornerShape(9.dp),
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = GhAccentGreen,
                                                            contentColor = Color(0xFF141518)
                                                        )
                                                    ) {
                                                        Text(
                                                            text = if (isCurrentProviderConnected) "Resync ${activeProvider.label} Library" else "Sign in & Sync ${activeProvider.label}",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(gridCols),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            itemsIndexed(openedPlaylistTracks, key = { idx, tr -> "${tr.id}_$idx" }) { idx, trackItem ->
                                                NativeMusicItemRow(
                                                    item = trackItem,
                                                    showSaveButton = false,
                                                    showDeleteButton = false,
                                                    onPlay = {
                                                        ClaraInAppMusicPlayer.playTrackQueue(
                                                            items = openedPlaylistTracks.toList(),
                                                            startIndex = idx,
                                                            scope = scope
                                                        )
                                                        statusMessage = "Playing '${trackItem.title}' on $activeCastTarget"
                                                    },
                                                    onSave = {},
                                                    onDelete = {}
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            Text(
                                                text = "My ${activeProvider.label} Playlists (${libraryItems.size})",
                                                color = GhTextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                fontFamily = GhFont,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                FilledTonalButton(
                                                    onClick = { showAddPlaylistRow = !showAddPlaylistRow },
                                                    shape = RoundedCornerShape(9.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                    colors = ButtonDefaults.filledTonalButtonColors(
                                                        containerColor = GhAccentGreen.copy(alpha = 0.18f),
                                                        contentColor = GhAccentGreen
                                                    ),
                                                    modifier = Modifier.height(30.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = if (showAddPlaylistRow) Icons.Default.Close else Icons.Default.Add,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = if (showAddPlaylistRow) "Close" else "Add Playlist",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }

                                                FilledTonalButton(
                                                    onClick = {
                                                        webLoginProvider = activeProvider
                                                    },
                                                    shape = RoundedCornerShape(9.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                    colors = ButtonDefaults.filledTonalButtonColors(
                                                        containerColor = GhTileSurfaceElevated,
                                                        contentColor = GhAccentGreen
                                                    ),
                                                    modifier = Modifier.height(30.dp)
                                                ) {
                                                    Icon(Icons.Outlined.CloudSync, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = if (isMusicSyncing) "Syncing ${(musicSyncProgress * 100).roundToInt()}%..." else "Sync Account",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }
                                    )

                                    AnimatedVisibility(visible = showAddPlaylistRow) {
                                        Surface(
                                            color = GhTileSurface,
                                            shape = RoundedCornerShape(14.dp),
                                            border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.45f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    OutlinedTextField(
                                                        value = playlistSearchQuery,
                                                        onValueChange = {
                                                            playlistSearchQuery = it
                                                            if (it.isBlank()) {
                                                                playlistCatalogMatches.clear()
                                                            }
                                                        },
                                                        label = { Text("Search artist, genre, or mood...", fontSize = 11.sp) },
                                                        textStyle = compactInputStyle,
                                                        shape = RoundedCornerShape(12.dp),
                                                        colors = claraFieldColors(),
                                                        leadingIcon = {
                                                            Icon(Icons.Outlined.Search, contentDescription = null, tint = GhAccentGreen, modifier = Modifier.size(18.dp))
                                                        },
                                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                                        keyboardActions = KeyboardActions(
                                                            onSearch = { searchCatalogForPlaylistCreation(playlistSearchQuery) }
                                                        ),
                                                        singleLine = true,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Button(
                                                        onClick = { createPlaylistMixFromQuery(playlistSearchQuery) },
                                                        enabled = playlistSearchQuery.trim().isNotBlank() && !isBuildingPlaylistFromSearch,
                                                        shape = RoundedCornerShape(9.dp),
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = GhAccentGreen,
                                                            contentColor = Color(0xFF141518)
                                                        ),
                                                        modifier = Modifier.height(42.dp)
                                                    ) {
                                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = if (isBuildingPlaylistFromSearch) "Creating..." else "Create Mix",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }

                                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    items(quickMoodQueries) { mood ->
                                                        SuggestionChip(
                                                            onClick = { createPlaylistMixFromQuery(mood) },
                                                            shape = RoundedCornerShape(50),
                                                            label = { Text("+ $mood", fontSize = 11.sp) },
                                                            modifier = Modifier.height(28.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(gridCols),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        itemsIndexed(libraryItems, key = { _, it -> it.id }) { _, item ->
                                            NativeMusicItemRow(
                                                item = item,
                                                showSaveButton = false,
                                                showDeleteButton = true,
                                                onPlay = {
                                                    if (item.itemType == NativeMusicItemType.PLAYLIST ||
                                                        item.itemType == NativeMusicItemType.STATION
                                                    ) {
                                                        openPlaylistInsidePopup(item)
                                                    } else {
                                                        ClaraInAppMusicPlayer.playTrackQueue(
                                                            items = listOf(item),
                                                            startIndex = 0,
                                                            scope = scope
                                                        )
                                                        statusMessage = "Playing '${item.title}' on $activeCastTarget"
                                                    }
                                                },
                                                onSave = {},
                                                onDelete = {
                                                    val updated = NativeMusicCatalogClient.removeItemFromLocalLibrary(
                                                        context = context,
                                                        itemId = item.id,
                                                        provider = activeProvider
                                                    )
                                                    libraryItems.clear()
                                                    libraryItems.addAll(updated)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        NativeMusicPopupTab.SEARCH -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        label = { Text("Search any song, artist, or album...", fontSize = 11.sp) },
                                        textStyle = compactInputStyle,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = claraFieldColors(),
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Search, contentDescription = null, tint = GhAccentGreen, modifier = Modifier.size(18.dp))
                                        },
                                        trailingIcon = {
                                            if (searchQuery.isNotBlank()) {
                                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = GhTextSecondary, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        },
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { performNativeSearch(searchQuery) }),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = { performNativeSearch(searchQuery) },
                                        shape = RoundedCornerShape(9.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = GhAccentGreen,
                                            contentColor = Color(0xFF141518)
                                        ),
                                        modifier = Modifier.height(44.dp)
                                    ) {
                                        Text(
                                            text = if (isSearchingCatalog) "..." else "Search",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(quickMoodQueries) { mood ->
                                        SuggestionChip(
                                            onClick = {
                                                searchQuery = mood
                                                performNativeSearch(mood)
                                            },
                                            shape = RoundedCornerShape(50),
                                            label = { Text(mood, fontSize = 11.sp) },
                                            modifier = Modifier.height(28.dp)
                                        )
                                    }
                                }

                                if (searchResults.isEmpty() && !isSearchingCatalog) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Type a song or artist above, or tap a quick mix.",
                                            color = GhTextSecondary,
                                            fontSize = 12.sp,
                                            fontFamily = GhFont
                                        )
                                    }
                                } else {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(gridCols),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        itemsIndexed(searchResults, key = { idx, it -> "${it.id}_$idx" }) { idx, item ->
                                            NativeMusicItemRow(
                                                item = item,
                                                showSaveButton = true,
                                                showDeleteButton = false,
                                                onPlay = {
                                                    ClaraInAppMusicPlayer.playTrackQueue(
                                                        items = searchResults.toList(),
                                                        startIndex = idx,
                                                        scope = scope
                                                    )
                                                    statusMessage = "Playing '${item.title}' on $activeCastTarget"
                                                },
                                                onSave = {
                                                    val updated = NativeMusicCatalogClient.saveItemToLocalLibrary(context, item)
                                                    libraryItems.clear()
                                                    libraryItems.addAll(updated)
                                                    statusMessage = "Saved '${item.title}' to Liked Songs!"
                                                },
                                                onDelete = {}
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        NativeMusicPopupTab.ACCOUNT -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (activeProvider == MusicProvider.SPOTIFY) {
                                    item {
                                        Surface(
                                            color = GhTileSurface,
                                            shape = RoundedCornerShape(16.dp),
                                            border = BorderStroke(
                                                1.dp,
                                                if (isSpotifyConnected) GhAccentGreen.copy(alpha = 0.5f) else GhCardBorder
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(18.dp)) {
                                                ResponsiveSplitRow(
                                                    isStacked = isCompactMobile,
                                                    firstContent = { mod ->
                                                        Column(modifier = mod) {
                                                            Text(
                                                                text = if (isSpotifyConnected) "Spotify Account Connected \u2713" else "Sign in to Spotify",
                                                                color = if (isSpotifyConnected) GhAccentGreen else GhTextPrimary,
                                                                fontSize = 15.sp,
                                                                fontWeight = FontWeight.SemiBold
                                                            )
                                                            Text(
                                                                text = "Sign in on the Spotify web login page to automatically sync all of your personal Playlists, Cover Art, and Liked Songs into Clara.",
                                                                color = GhTextSecondary,
                                                                fontSize = 11.sp
                                                            )
                                                        }
                                                    },
                                                    secondContent = {
                                                        Row(
                                                            horizontalArrangement = Arrangement.spacedBy(
                                                                8.dp
                                                            )
                                                        ) {
                                                            if (isSpotifyConnected) {
                                                                OutlinedButton(
                                                                    onClick = {
                                                                        SpotifyHiddenWebPlayer.pausePlayback()
                                                                        NativeMusicCatalogClient.signOutProvider(
                                                                            context,
                                                                            MusicProvider.SPOTIFY
                                                                        )
                                                                        isSpotifyConnected = false
                                                                        openedPlaylist = null
                                                                        openedPlaylistTracks.clear()
                                                                        reloadLocalLibrary()
                                                                        statusMessage =
                                                                            "Signed out of Spotify."
                                                                    },
                                                                    shape = RoundedCornerShape(9.dp)
                                                                ) {
                                                                    Text(
                                                                        "Disconnect",
                                                                        fontSize = 11.sp,
                                                                        color = GhAccentRed
                                                                    )
                                                                }
                                                            }
                                                            Button(
                                                                onClick = {
                                                                    webLoginProvider =
                                                                        MusicProvider.SPOTIFY
                                                                },
                                                                shape = RoundedCornerShape(9.dp),
                                                                colors = ButtonDefaults.buttonColors(
                                                                    containerColor = GhAccentGreen,
                                                                    contentColor = Color(0xFF141518)
                                                                )
                                                            ) {
                                                                Text(
                                                                    text = if (isSpotifyConnected) "Resync Spotify Library" else "Sign in to Spotify",
                                                                    fontSize = 11.sp,
                                                                    fontWeight = FontWeight.SemiBold
                                                                )
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    item {
                                        Surface(
                                            color = GhTileSurface,
                                            shape = RoundedCornerShape(16.dp),
                                            border = BorderStroke(
                                                1.dp,
                                                if (isYouTubeConnected) GhAccentGreen.copy(alpha = 0.5f) else GhCardBorder
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(18.dp)) {
                                                ResponsiveSplitRow(
                                                    isStacked = isCompactMobile,
                                                    firstContent = { mod ->
                                                        Column(modifier = mod) {
                                                            Text(
                                                                text = if (isYouTubeConnected) "YouTube Music Connected \u2713" else "Sign in to YouTube Music",
                                                                color = if (isYouTubeConnected) GhAccentGreen else GhTextPrimary,
                                                                fontSize = 15.sp,
                                                                fontWeight = FontWeight.SemiBold
                                                            )
                                                            Text(
                                                                text = "Sign in on the Google login page to automatically sync your YouTube Music playlists and liked tracks.",
                                                                color = GhTextSecondary,
                                                                fontSize = 11.sp
                                                            )
                                                        }
                                                    },
                                                    secondContent = {
                                                        Row(
                                                            horizontalArrangement = Arrangement.spacedBy(
                                                                8.dp
                                                            )
                                                        ) {
                                                            if (isYouTubeConnected) {
                                                                OutlinedButton(
                                                                    onClick = {
                                                                        NativeMusicCatalogClient.signOutProvider(
                                                                            context,
                                                                            MusicProvider.YOUTUBE_MUSIC
                                                                        )
                                                                        isYouTubeConnected = false
                                                                        openedPlaylist = null
                                                                        openedPlaylistTracks.clear()
                                                                        reloadLocalLibrary()
                                                                        statusMessage =
                                                                            "Disconnected YouTube Music account."
                                                                    },
                                                                    shape = RoundedCornerShape(9.dp)
                                                                ) {
                                                                    Text(
                                                                        "Disconnect",
                                                                        fontSize = 11.sp,
                                                                        color = GhAccentRed
                                                                    )
                                                                }
                                                            }
                                                            Button(
                                                                onClick = {
                                                                    webLoginProvider =
                                                                        MusicProvider.YOUTUBE_MUSIC
                                                                },
                                                                shape = RoundedCornerShape(9.dp),
                                                                colors = ButtonDefaults.buttonColors(
                                                                    containerColor = GhAccentGreen,
                                                                    contentColor = Color(0xFF141518)
                                                                )
                                                            ) {
                                                                Text(
                                                                    text = if (isYouTubeConnected) "Resync YouTube Music" else "Sign in to YouTube Music",
                                                                    fontSize = 11.sp,
                                                                    fontWeight = FontWeight.SemiBold
                                                                )
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        NativeMusicPopupTab.PLEX -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Plex Media Server (${plexLibraryItems.size} Libraries & Playlists)",
                                                color = GhTextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = GhFont,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = bridgeSettings.plexServerUrl,
                                                color = GhAccentGreen,
                                                fontSize = 10.5.sp,
                                                fontFamily = GhFont,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = { reloadLocalLibrary() },
                                            shape = RoundedCornerShape(9.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = GhTileSurfaceElevated,
                                                contentColor = GhAccentGreen
                                            ),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isLoadingPlexLibrary) "Syncing..." else "Refresh Plex",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    if (isLoadingPlexLibrary) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Loading playlists & music libraries from Plex Media Server...",
                                                color = GhAccentGreen,
                                                fontSize = 12.sp,
                                                fontFamily = GhFont
                                            )
                                        }
                                    } else if (plexLibraryItems.isEmpty()) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "No audio playlists found on ${bridgeSettings.plexServerUrl}. Ensure your Plex server is online and X-Plex-Token is configured if required.",
                                                color = GhTextSecondary,
                                                fontSize = 12.sp,
                                                fontFamily = GhFont,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    } else {
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(gridCols),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            itemsIndexed(plexLibraryItems, key = { _, it -> it.id }) { _, plexItem ->
                                                NativeMusicItemRow(
                                                    item = plexItem,
                                                    showSaveButton = false,
                                                    showDeleteButton = false,
                                                    onPlay = {
                                                        if (plexItem.itemType == NativeMusicItemType.PLAYLIST) {
                                                            activeTab = NativeMusicPopupTab.LIBRARY
                                                            openPlaylistInsidePopup(plexItem)
                                                        } else {
                                                            ClaraInAppMusicPlayer.playTrackQueue(
                                                                items = listOf(plexItem),
                                                                startIndex = 0,
                                                                scope = scope
                                                            )
                                                            statusMessage = "Playing '${plexItem.title}' from Plex on $activeCastTarget"
                                                        }
                                                    },
                                                    onSave = {},
                                                    onDelete = {}
                                                )
                                            }
                                        }
                                    }
                                }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom Now-Playing Transport & Volume Bar
                Surface(
                    color = GhTileSurfaceElevated,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        val safeDuration = trackDurationMs.coerceAtLeast(1L)
                        val clampedPos = trackPositionMs.coerceIn(0L, safeDuration)
                        val remainingMs = (safeDuration - clampedPos).coerceAtLeast(0L)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = formatDurationMmSs(clampedPos, prefixMinus = false),
                                color = GhTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = GhFont
                            )
                            Slider(
                                value = if (trackDurationMs > 0L) clampedPos.toFloat() else 0f,
                                onValueChange = { newPosFloat ->
                                    ClaraInAppMusicPlayer.seekTo(newPosFloat.toLong())
                                },
                                valueRange = 0f..(safeDuration.toFloat()),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(20.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = GhAccentGreen,
                                    inactiveTrackColor = GhTileSurface
                                )
                            )
                            Text(
                                text = formatDurationMmSs(remainingMs, prefixMinus = true),
                                color = GhTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = GhFont
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                MusicArtworkThumbnail(
                                    explicitArtworkUrl = activeArtworkUrl,
                                    fallbackIcon = Icons.Outlined.MusicNote,
                                    size = 34.dp,
                                    cornerRadius = 8.dp
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = currentTrack,
                                        color = GhTextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = GhFont,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Output: $activeCastTarget",
                                        color = GhAccentGreen,
                                        fontSize = 10.sp,
                                        fontFamily = GhFont,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(onClick = onPrevious, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = GhTextPrimary)
                                }
                                FilledIconButton(
                                    onClick = onTogglePlay,
                                    modifier = Modifier.size(34.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = GhAccentGreen,
                                        contentColor = Color(0xFF141518)
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = onSkip, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = GhTextPrimary)
                                }
                            }

                            if (!isCompactMobile) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .weight(0.85f)
                                        .padding(start = 12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.VolumeUp,
                                        contentDescription = null,
                                        tint = GhTextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Slider(
                                        value = masterVolume,
                                        onValueChange = { nextVol ->
                                            onMasterVolumeChange(nextVol)
                                            ClaraInAppMusicPlayer.setVolumePercent(nextVol)
                                        },
                                        valueRange = 0f..100f,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(22.dp),
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color.White,
                                            activeTrackColor = GhAccentGreen
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    webLoginProvider?.let { targetProv ->
        MusicWebLoginModal(
            provider = targetProv,
            onSessionCaptured = {
                webLoginProvider = null
                isSpotifyConnected = NativeMusicCatalogClient.isSpotifySignedIn(context)
                isYouTubeConnected = NativeMusicCatalogClient.isYouTubeSignedIn(context)
                if (targetProv == MusicProvider.SPOTIFY) {
                    SpotifyHiddenWebPlayer.reloadSessionWithCookies(context)
                }
                reloadLocalLibrary()
                activeTab = NativeMusicPopupTab.LIBRARY
                scope.launch {
                    val res = NativeMusicCatalogClient.syncCloudPlaylists(context, targetProv, bridgeSettings)
                    reloadLocalLibrary()
                    openedPlaylist?.let { currentlyOpen ->
                        openPlaylistInsidePopup(currentlyOpen)
                    }
                    val count = res.getOrNull()?.size ?: libraryItems.size
                    statusMessage = "SUCCESS: Synced $count playlists from ${targetProv.label}!"
                }
            },
            onDismiss = { webLoginProvider = null }
        )
    }
}

@Composable
fun MusicPlayerPopupModal(
    initialProvider: MusicProvider,
    bridgeSettings: BridgeSettings = BridgeSettings(),
    isPlaying: Boolean,
    currentTrack: String,
    masterVolume: Float,
    onMasterVolumeChange: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onSkip: () -> Unit,
    onPrevious: () -> Unit,
    onOpenNativeApp: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .padding(8.dp)
        ) {
            IntegratedMusicPlayerSection(
                initialProvider = initialProvider,
                bridgeSettings = bridgeSettings,
                isPlaying = isPlaying,
                currentTrack = currentTrack,
                masterVolume = masterVolume,
                onMasterVolumeChange = onMasterVolumeChange,
                onTogglePlay = onTogglePlay,
                onSkip = onSkip,
                onPrevious = onPrevious,
                onOpenNativeApp = onOpenNativeApp,
                showCloseButton = true,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
internal fun NativeMusicItemRow(
    item: NativeMusicItem,
    showSaveButton: Boolean,
    showDeleteButton: Boolean,
    onPlay: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    val isPlaylistOrStation = item.itemType == NativeMusicItemType.PLAYLIST ||
            item.itemType == NativeMusicItemType.STATION

    Surface(
        color = GhTileSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, GhCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                MusicArtworkThumbnail(
                    item = item,
                    fallbackIcon = when (item.itemType) {
                        NativeMusicItemType.PLAYLIST -> Icons.Outlined.QueueMusic
                        NativeMusicItemType.STATION -> Icons.Outlined.Radio
                        else -> Icons.Outlined.MusicNote
                    },
                    size = 40.dp,
                    cornerRadius = 8.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        color = GhTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = GhFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.subtitle.ifBlank { item.itemType.label },
                        color = GhTextSecondary,
                        fontSize = 10.sp,
                        fontFamily = GhFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showSaveButton) {
                    FilledTonalIconButton(
                        onClick = onSave,
                        modifier = Modifier.size(28.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = GhTileSurfaceElevated,
                            contentColor = GhAccentGreen
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Save to Library", modifier = Modifier.size(15.dp))
                    }
                }
                FilledTonalIconButton(
                    onClick = onPlay,
                    modifier = Modifier.size(28.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = GhAccentGreen.copy(alpha = 0.22f),
                        contentColor = GhAccentGreen
                    )
                ) {
                    Icon(
                        imageVector = if (isPlaylistOrStation) Icons.Outlined.FolderOpen else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaylistOrStation) "Open Playlist" else "Play Song",
                        modifier = Modifier.size(16.dp)
                    )
                }
                if (showDeleteButton) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Remove from Library",
                            tint = GhTextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}
