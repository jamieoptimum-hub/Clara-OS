package com.jarvis.smarthomelauncher.ui.widgets

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.smarthomelauncher.ui.popups.ExecutiveVehicleCarIllustration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------------------
// 1. SWIPEABLE 3-IN-1 ROOM SELECTOR, CLOCK/WIDGET, MEDIA & SLIDESHOW HERO BOX
//    - Page 0 (Slide Right): Media Player with Soft Ambient Glow
//    - Page 1 (Center Default): 50/50 Split — Left Half ALWAYS keeps Interactive
//      Room Photo Selector with Unified Greeting & User Profile Pill (Top-Left),
//      Dark/Light Toggle (Top-Right) & Room Selector (Bottom-Left); Right Half
//      renders the Enlarged Clock OR any user-selected DashboardWidgetType
//    - Page 2 (Slide Left): Picture Slideshow
// ---------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SfHouseClockAndWeatherHeroBox(
    bridgeSettings: BridgeSettings,
    allTiles: List<SmartTile>,
    isEditMode: Boolean,
    selectedRoom: String = "All Rooms",
    availableRooms: List<String> = emptyList(),
    onSelectRoom: (String) -> Unit = {},
    onRequestCreateNewRoom: () -> Unit = {},
    isMusicPlaying: Boolean = false,
    currentTrack: String = "",
    requestedHeroPage: Int? = null,
    onRequestedHeroPageHandled: () -> Unit = {},
    rightPaneWidgetType: DashboardWidgetType = DashboardWidgetType.CLOCK_HERO,
    automationRules: List<AutomationRule> = emptyList(),
    masterMusicVolume: Float = 60f,
    onUpdateTile: (SmartTile) -> Unit = {},
    onUpdateMasterMusicVolume: (Float) -> Unit = {},
    onSelectSecurityMode: (SecurityCenterMode) -> Unit = {},
    onOpenCamerasView: () -> Unit = {},
    onOpenRoutinesView: () -> Unit = {},
    onOpenSetupWizard: () -> Unit,
    onToggleEditMode: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onTriggerVoicePrompt: () -> Unit,
    onOpenThermostatDial: (SmartTile) -> Unit,
    onUpdateBridgeSettings: (BridgeSettings) -> Unit = {},
    onOpenFullMusicPage: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val heroPagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })

    var showPagerDots by remember { mutableStateOf(true) }
    var pagerTouchNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(heroPagerState.currentPage, heroPagerState.isScrollInProgress, pagerTouchNonce) {
        showPagerDots = true
        if (!heroPagerState.isScrollInProgress) {
            delay(3000L)
            showPagerDots = false
        }
    }

    LaunchedEffect(requestedHeroPage) {
        if (requestedHeroPage != null && requestedHeroPage in 0..2) {
            heroPagerState.animateScrollToPage(requestedHeroPage)
            onRequestedHeroPageHandled()
        }
    }

    var showClockStyleModal by remember { mutableStateOf(false) }
    var showCastToMenu by remember { mutableStateOf(false) }
    val currentTime = ""
    val currentHourStr = ""
    val currentMinStr = ""
    val currentDate = ""
    var currentHourInt by remember {
        mutableIntStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    }

    val isVoiceListening by JarvisLiveBridge.isVoiceListeningFlow.collectAsState()
    val liveTrack by JarvisLiveBridge.activeTrackFlow.collectAsState()
    val livePlaying by JarvisLiveBridge.isPlayingFlow.collectAsState()
    val trackPosMs by JarvisLiveBridge.trackPositionMsFlow.collectAsState()
    val trackDurMs by JarvisLiveBridge.trackDurationMsFlow.collectAsState()
    val activeArtworkUrl by ClaraInAppMusicPlayer.activeArtworkUrlFlow.collectAsState()
    val activeCastTarget by JarvisLiveBridge.activeCastTargetNameFlow.collectAsState()
    val discoveredCastDevices by JarvisLiveBridge.discoveredCastDevicesFlow.collectAsState()
    val castSpeakerGroups by JarvisLiveBridge.castSpeakerGroupsFlow.collectAsState()

    val effectivePlaying = isMusicPlaying || livePlaying
    val effectiveTrack = currentTrack.ifBlank { liveTrack }

    var showSlideshowConfigModal by remember { mutableStateOf(false) }
    val fallbackSlideshowTile = remember(allTiles) {
        allTiles.firstOrNull {
            it.type == TileType.PHOTO_SLIDESHOW &&
                    (it.slideshowLocalUris.isNotEmpty() || it.slideshowCloudUrl.isNotBlank())
        }
    }
    val slideshowSource = if (bridgeSettings.slideshowLocalUris.isNotEmpty() || bridgeSettings.slideshowCloudUrl.isNotBlank()) {
        bridgeSettings.slideshowSourceType
    } else {
        fallbackSlideshowTile?.slideshowSourceType ?: bridgeSettings.slideshowSourceType
    }
    val slideshowLocalUris = if (bridgeSettings.slideshowLocalUris.isNotEmpty()) {
        bridgeSettings.slideshowLocalUris
    } else {
        fallbackSlideshowTile?.slideshowLocalUris.orEmpty()
    }
    val slideshowCloudUrl = if (bridgeSettings.slideshowCloudUrl.isNotBlank()) {
        bridgeSettings.slideshowCloudUrl
    } else {
        fallbackSlideshowTile?.slideshowCloudUrl.orEmpty()
    }
    val slideshowIntervalSec = bridgeSettings.slideshowIntervalSeconds.coerceAtLeast(3)

    var resolvedSlideshowItems by remember(slideshowSource, slideshowLocalUris, slideshowCloudUrl) {
        mutableStateOf<List<String>>(emptyList())
    }
    var currentSlideIndex by remember { mutableIntStateOf(0) }
    var currentSlideBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(slideshowSource, slideshowLocalUris, slideshowCloudUrl) {
        resolvedSlideshowItems = PhotoSlideshowResolver.resolveMediaItems(
            sourceType = slideshowSource,
            localUris = slideshowLocalUris,
            cloudUrl = slideshowCloudUrl
        )
        currentSlideIndex = 0
    }

    LaunchedEffect(resolvedSlideshowItems, currentSlideIndex, slideshowIntervalSec, heroPagerState.currentPage) {
        if (resolvedSlideshowItems.isEmpty()) {
            currentSlideBitmap = null
            return@LaunchedEffect
        }
        val safeIdx = currentSlideIndex.coerceIn(0, resolvedSlideshowItems.lastIndex)
        val bmp = PhotoSlideshowResolver.loadBitmap(context, resolvedSlideshowItems[safeIdx])
        if (bmp != null) {
            currentSlideBitmap = bmp
        }
        if (resolvedSlideshowItems.size > 1 && heroPagerState.currentPage == 2) {
            delay(slideshowIntervalSec * 1000L)
            currentSlideIndex = (safeIdx + 1) % resolvedSlideshowItems.size
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentHourInt = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            delay(30_000L)
        }
    }

    val isDark = ClaraThemeManager.isDarkMode

    SfHouseSunkenTileContainer(
        outerCornerRadius = 22.dp,
        innerCornerRadius = 16.dp,
        sunkenInsetPadding = 6.dp,
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            showPagerDots = true
                            pagerTouchNonce++
                        }
                    }
                }
                .background(
                    Brush.linearGradient(
                        colors = if (isDark) {
                            listOf(
                                Color(0xFF222834),
                                Color(0xFF262930),
                                Color(0xFF1D222B)
                            )
                        } else {
                            listOf(
                                Color(0xFFF3F7FC),
                                Color(0xFFFFFFFF),
                                Color(0xFFEAF0F8)
                            )
                        }
                    )
                )
        ) {
            HorizontalPager(
                state = heroPagerState,
                userScrollEnabled = !isEditMode,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    // =========================================================
                    // PAGE 0 (SLIDE RIGHT): EMBEDDED MEDIA PLAYER + AMBIENT GLOW
                    // =========================================================
                    0 -> {
                        val safeDur = trackDurMs.coerceAtLeast(1L)
                        val clampedPos = trackPosMs.coerceIn(0L, safeDur)
                        val remainingMs = (safeDur - clampedPos).coerceAtLeast(0L)

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            GhAccentGreen.copy(alpha = 0.12f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        MusicArtworkThumbnail(
                                            explicitArtworkUrl = activeArtworkUrl,
                                            fallbackIcon = Icons.Outlined.MusicNote,
                                            size = 52.dp,
                                            cornerRadius = 11.dp
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = effectiveTrack.ifBlank { "Not Playing" },
                                                color = GhTextPrimary,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = GhFont,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${bridgeSettings.musicProvider.label} \u2022 $activeCastTarget",
                                                color = GhAccentGreen,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                fontFamily = GhFont,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box {
                                            Surface(
                                                color = if (activeCastTarget != "Tablet Speaker") GhTileGreenActive else GhTileSurfaceElevated,
                                                shape = RoundedCornerShape(9.dp),
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (activeCastTarget != "Tablet Speaker") GhAccentGreen else GhCardBorder
                                                ),
                                                modifier = Modifier
                                                    .height(32.dp)
                                                    .clickable {
                                                        showCastToMenu = true
                                                        if (discoveredCastDevices.isEmpty()) {
                                                            scope.launch {
                                                                GoogleCastLanClient.discoverCastDevices(context, includeFullPortSweep = false)
                                                            }
                                                        }
                                                    }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Cast,
                                                        contentDescription = "Cast To",
                                                        tint = GhAccentGreen,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Text(
                                                        text = "Cast To",
                                                        color = GhTextPrimary,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontFamily = GhFont
                                                    )
                                                }
                                            }

                                            DropdownMenu(
                                                expanded = showCastToMenu,
                                                onDismissRequest = { showCastToMenu = false },
                                                modifier = Modifier.background(GhTileSurfaceElevated)
                                            ) {
                                                DropdownMenuItem(
                                                    text = {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Outlined.TabletAndroid,
                                                                contentDescription = null,
                                                                tint = if (activeCastTarget == "Tablet Speaker") GhAccentGreen else GhTextSecondary,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                            Text(
                                                                text = "This Tablet Speaker",
                                                                color = if (activeCastTarget == "Tablet Speaker") GhAccentGreen else GhTextPrimary,
                                                                fontSize = 12.sp,
                                                                fontWeight = if (activeCastTarget == "Tablet Speaker") FontWeight.Bold else FontWeight.Normal
                                                            )
                                                        }
                                                    },
                                                    onClick = {
                                                        showCastToMenu = false
                                                        ClaraInAppMusicPlayer.selectTabletSpeakerOutput(scope)
                                                    }
                                                )

                                                if (castSpeakerGroups.isNotEmpty() || discoveredCastDevices.isNotEmpty()) {
                                                    HorizontalDivider(color = GhCardBorder)
                                                }

                                                castSpeakerGroups.forEach { group ->
                                                    val isSel = activeCastTarget == group.name
                                                    DropdownMenuItem(
                                                        text = {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Outlined.SpeakerGroup,
                                                                    contentDescription = null,
                                                                    tint = if (isSel) GhAccentGreen else GhTextSecondary,
                                                                    modifier = Modifier.size(16.dp)
                                                                )
                                                                Text(
                                                                    text = "${group.name} (Group)",
                                                                    color = if (isSel) GhAccentGreen else GhTextPrimary,
                                                                    fontSize = 12.sp,
                                                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                                                )
                                                            }
                                                        },
                                                        onClick = {
                                                            showCastToMenu = false
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
                                                        }
                                                    )
                                                }

                                                discoveredCastDevices.forEach { dev ->
                                                    val isSel = activeCastTarget == dev.name
                                                    DropdownMenuItem(
                                                        text = {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Outlined.Speaker,
                                                                    contentDescription = null,
                                                                    tint = if (isSel) GhAccentGreen else GhTextSecondary,
                                                                    modifier = Modifier.size(16.dp)
                                                                )
                                                                Text(
                                                                    text = "${dev.name} (${dev.volumePercent.roundToInt()}%)",
                                                                    color = if (isSel) GhAccentGreen else GhTextPrimary,
                                                                    fontSize = 12.sp,
                                                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                                                )
                                                            }
                                                        },
                                                        onClick = {
                                                            showCastToMenu = false
                                                            ClaraInAppMusicPlayer.selectCastSpeakerOutput(
                                                                targetName = dev.name,
                                                                endpoints = listOf(dev.ipAddress to dev.port),
                                                                scope = scope
                                                            )
                                                        }
                                                    )
                                                }
                                            }
                                        }

                                        Surface(
                                            color = GhTileSurfaceElevated,
                                            shape = RoundedCornerShape(9.dp),
                                            border = BorderStroke(1.dp, GhCardBorder),
                                            modifier = Modifier
                                                .height(32.dp)
                                                .clickable { onOpenFullMusicPage() }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.QueueMusic,
                                                    contentDescription = "Playlists",
                                                    tint = GhAccentGreen,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Text(
                                                    text = "Playlists",
                                                    color = GhTextPrimary,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontFamily = GhFont
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = formatHeroTrackTime(clampedPos, prefixMinus = false),
                                        color = GhTextSecondary,
                                        fontSize = 10.sp,
                                        fontFamily = GhFont
                                    )
                                    Slider(
                                        value = if (trackDurMs > 0L) clampedPos.toFloat() else 0f,
                                        onValueChange = { newPos ->
                                            ClaraInAppMusicPlayer.seekTo(newPos.toLong())
                                        },
                                        valueRange = 0f..(safeDur.toFloat()),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(20.dp),
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color.White,
                                            activeTrackColor = GhAccentGreen,
                                            inactiveTrackColor = GhTileSurfaceElevated
                                        )
                                    )
                                    Text(
                                        text = formatHeroTrackTime(remainingMs, prefixMinus = true),
                                        color = GhTextSecondary,
                                        fontSize = 10.sp,
                                        fontFamily = GhFont
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = GhTileSurfaceElevated,
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clickable {
                                                ClaraInAppMusicPlayer.skipToPrevious(audioManager, scope)
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.SkipPrevious,
                                                contentDescription = "Previous",
                                                tint = GhTextPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(18.dp))
                                    Surface(
                                        color = GhAccentGreen,
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clickable {
                                                ClaraInAppMusicPlayer.togglePlayPause(audioManager, scope)
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (effectivePlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = "Play/Pause",
                                                tint = Color(0xFF141518),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(18.dp))
                                    Surface(
                                        color = GhTileSurfaceElevated,
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clickable {
                                                ClaraInAppMusicPlayer.skipToNext(audioManager, scope)
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.SkipNext,
                                                contentDescription = "Next",
                                                tint = GhTextPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // =========================================================
                    // PAGE 1 (CENTER DEFAULT): ADJUSTABLE SPLIT HALVES
                    // - Left Side: Interactive Room Photo Selector, Greeting & User Profile
                    // - Right Side: Enlarged Clock OR swapped widget (`rightPaneWidgetType`)
                    // =========================================================
                    1 -> {
                        LaunchedEffect(Unit) {
                            ClaraThemeManager.loadHeroClockSplitRatio(context)
                        }
                        val splitRatio = ClaraThemeManager.heroClockSplitRatio.coerceIn(0.25f, 0.75f)
                        val leftWeight = splitRatio
                        val rightWeight = (1f - splitRatio).coerceIn(0.25f, 0.75f)

                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val totalWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx().coerceAtLeast(1f) }
                            val rightPaneWidth = maxWidth * rightWeight
                            val clockDiameter = minOf(maxHeight * 0.94f, rightPaneWidth * 0.92f).coerceIn(104.dp, 236.dp)
                            val greetingStr = timeOfDayGreetingText(currentHourInt)

                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // LEFT SIDE: Interactive Room Photo & User Selector
                                Box(
                                    modifier = Modifier
                                        .weight(leftWeight)
                                        .fillMaxHeight()
                                        .padding(end = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    SfHouseRoomSelectorPanel(
                                        selectedRoom = selectedRoom,
                                        availableRooms = availableRooms,
                                        isEditMode = isEditMode,
                                        isDarkMode = isDark,
                                        greetingMessage = greetingStr,
                                        bridgeSettings = bridgeSettings,
                                        onUpdateBridgeSettings = onUpdateBridgeSettings,
                                        onToggleThemeMode = {
                                            val nextMode = if (isDark) AppThemeMode.LIGHT else AppThemeMode.DARK
                                            val updated = bridgeSettings.copy(appThemeMode = nextMode)
                                            DeviceRepository.saveBridge(context, updated)
                                            onUpdateBridgeSettings(updated)
                                        },
                                        onSelectRoom = onSelectRoom,
                                        onRequestCreateNewRoom = onRequestCreateNewRoom,
                                        onToggleEditMode = onToggleEditMode,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                // Central vertical divider (draggable directly in Normal Mode + top/bottom handles in Edit Mode)
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .width(24.dp)
                                        .fillMaxHeight()
                                        .pointerInput(totalWidthPx) {
                                            detectHorizontalDragGestures { change, dragAmount ->
                                                change.consume()
                                                showPagerDots = true
                                                pagerTouchNonce++
                                                val deltaFraction = dragAmount / totalWidthPx
                                                ClaraThemeManager.updateHeroClockSplitRatio(
                                                    context,
                                                    ClaraThemeManager.heroClockSplitRatio + deltaFraction
                                                )
                                            }
                                        }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(if (showPagerDots || isEditMode) 2.dp else 1.dp)
                                            .fillMaxHeight(0.84f)
                                            .background(
                                                if (showPagerDots || isEditMode) {
                                                    Brush.verticalGradient(
                                                        colors = listOf(
                                                            Color.Transparent,
                                                            GhAccentGreen.copy(alpha = 0.80f),
                                                            Color.Transparent
                                                        )
                                                    )
                                                } else {
                                                    Brush.verticalGradient(
                                                        colors = listOf(
                                                            Color.Transparent,
                                                            GhCardBorder,
                                                            Color.Transparent
                                                        )
                                                    )
                                                }
                                            )
                                    )

                                    if (isEditMode) {
                                        // Top & bottom handles sit above and below the centered Edit Mode overlay
                                        Surface(
                                            color = Color(0xFF101319),
                                            shape = RoundedCornerShape(50),
                                            border = BorderStroke(1.2.dp, GhAccentGreen),
                                            modifier = Modifier
                                                .align(Alignment.TopCenter)
                                                .padding(top = 6.dp)
                                                .width(12.dp)
                                                .height(22.dp)
                                        ) {}
                                        Surface(
                                            color = Color(0xFF101319),
                                            shape = RoundedCornerShape(50),
                                            border = BorderStroke(1.2.dp, GhAccentGreen),
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .padding(bottom = 6.dp)
                                                .width(12.dp)
                                                .height(22.dp)
                                        ) {}
                                    } else if (showPagerDots) {
                                        Surface(
                                            color = Color(0xFF101319),
                                            shape = RoundedCornerShape(50),
                                            border = BorderStroke(1.2.dp, GhAccentGreen),
                                            modifier = Modifier
                                                .width(10.dp)
                                                .height(34.dp)
                                        ) {}
                                    }
                                }

                                // RIGHT SIDE: Swaps between the Clock or any chosen Widget Type
                                Box(
                                    modifier = Modifier
                                        .weight(rightWeight)
                                        .fillMaxHeight()
                                        .padding(start = 4.dp)
                                        .combinedClickable(
                                            onClick = {
                                                if (!isEditMode && rightPaneWidgetType == DashboardWidgetType.CLOCK_HERO) {
                                                    showClockStyleModal = true
                                                }
                                            },
                                            onLongClick = { onToggleEditMode() }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (rightPaneWidgetType == DashboardWidgetType.CLOCK_HERO) {
                                        SelectableClockFaceCenterpiece(
                                            style = bridgeSettings.clockFaceStyle,
                                            timeText = currentTime,
                                            hourStr = currentHourStr,
                                            minStr = currentMinStr,
                                            conditionText = if (isVoiceListening) "Listening..." else currentDate,
                                            isVoiceListening = isVoiceListening,
                                            dialSize = clockDiameter,
                                            onClickClock = {
                                                if (!isEditMode) {
                                                    showClockStyleModal = true
                                                }
                                            }
                                        )
                                    } else {
                                        RenderInterchangeableDashboardWidget(
                                            widgetType = rightPaneWidgetType,
                                            allTiles = allTiles,
                                            automationRules = automationRules,
                                            bridgeSettings = bridgeSettings,
                                            isEditMode = isEditMode,
                                            isMusicPlaying = effectivePlaying,
                                            currentTrack = effectiveTrack,
                                            masterMusicVolume = masterMusicVolume,
                                            onUpdateTile = onUpdateTile,
                                            onUpdateMasterMusicVolume = onUpdateMasterMusicVolume,
                                            onActivateHeroDial = onOpenThermostatDial,
                                            onSelectSecurityMode = onSelectSecurityMode,
                                            onOpenSetupWizard = onOpenSetupWizard,
                                            onOpenCamerasView = onOpenCamerasView,
                                            onOpenRoutinesView = onOpenRoutinesView,
                                            onToggleEditMode = onToggleEditMode,
                                            onUpdateBridgeSettings = onUpdateBridgeSettings
                                        ) {
                                            SelectableClockFaceCenterpiece(
                                                style = bridgeSettings.clockFaceStyle,
                                                timeText = currentTime,
                                                hourStr = currentHourStr,
                                                minStr = currentMinStr,
                                                conditionText = if (isVoiceListening) "Listening..." else currentDate,
                                                isVoiceListening = isVoiceListening,
                                                dialSize = clockDiameter,
                                                onClickClock = {
                                                    if (!isEditMode) {
                                                        showClockStyleModal = true
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // =========================================================
                    // PAGE 2 (SLIDE LEFT): PICTURE SLIDESHOW
                    // =========================================================
                    2 -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    if (resolvedSlideshowItems.isEmpty()) {
                                        showSlideshowConfigModal = true
                                    } else {
                                        currentSlideIndex = (currentSlideIndex + 1) % resolvedSlideshowItems.size
                                    }
                                }
                        ) {
                            val bmp = currentSlideBitmap
                            if (bmp != null) {
                                Crossfade(
                                    targetState = bmp,
                                    animationSpec = tween(durationMillis = 700),
                                    label = "HeroSlideshowCrossfade"
                                ) { targetBmp ->
                                    Image(
                                        bitmap = targetBmp,
                                        contentDescription = "Slideshow Photo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Surface(
                                    color = Color.Black.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(10.dp)
                                        .clickable { showSlideshowConfigModal = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Collections,
                                            contentDescription = "Folders",
                                            tint = GhAccentGreen,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = "Folders (${currentSlideIndex + 1}/${resolvedSlideshowItems.size})",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.PhotoLibrary,
                                        contentDescription = null,
                                        tint = GhAccentGreen,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Picture Slideshow",
                                        color = GhTextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = GhFont
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Surface(
                                        color = GhAccentGreen,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.clickable { showSlideshowConfigModal = true }
                                    ) {
                                        Text(
                                            text = "Select Photo Folders",
                                            color = Color(0xFF141518),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Auto-Hiding 3-Dot Swipe Indicator at Bottom Center (Fades out after 3s of inactivity)
            AnimatedVisibility(
                visible = showPagerDots,
                enter = fadeIn(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(450)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (idx in 0..2) {
                        val isCurrent = heroPagerState.currentPage == idx
                        Box(
                            modifier = Modifier
                                .width(if (isCurrent) 16.dp else 5.dp)
                                .height(5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isCurrent) GhAccentGreen else GhTextSecondary.copy(alpha = 0.35f)
                                )
                                .clickable {
                                    scope.launch {
                                        heroPagerState.animateScrollToPage(idx)
                                    }
                                }
                        )
                    }
                }
            }
        }
    }

    if (showClockStyleModal) {
        ClockFaceSelectorModal(
            currentStyle = bridgeSettings.clockFaceStyle,
            onSelectStyle = { chosenStyle ->
                val updated = bridgeSettings.copy(clockFaceStyle = chosenStyle)
                DeviceRepository.saveBridge(context, updated)
                onUpdateBridgeSettings(updated)
                showClockStyleModal = false
            },
            onDismiss = { showClockStyleModal = false }
        )
    }

    if (showSlideshowConfigModal) {
        PhotoSlideshowConfigModal(
            title = "Picture Slideshow",
            initialSourceType = slideshowSource,
            initialLocalUris = slideshowLocalUris,
            initialCloudUrl = slideshowCloudUrl,
            initialIntervalSeconds = slideshowIntervalSec,
            initialIncludeSubfolders = bridgeSettings.slideshowIncludeSubfolders,
            initialSelectedFolders = emptyList(),
            onSave = { newSource, newLocalUris, newCloudUrl, newIntervalSec, includeSubs, _ ->
                PhotoSlideshowResolver.clearCloudCache()
                val updated = bridgeSettings.copy(
                    slideshowSourceType = newSource,
                    slideshowLocalUris = newLocalUris,
                    slideshowCloudUrl = newCloudUrl,
                    slideshowIntervalSeconds = newIntervalSec,
                    slideshowIncludeSubfolders = includeSubs
                )
                DeviceRepository.saveBridge(context, updated)
                onUpdateBridgeSettings(updated)
                showSlideshowConfigModal = false
            },
            onDismiss = { showSlideshowConfigModal = false }
        )
    }
}
