package com.jarvis.smarthomelauncher.ui.widgets

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Automatically builds a rich 3-stop gradient from whatever accent colour is currently selected
 * on the RGB wheel (deeper rich base -> pure accent -> luminous highlight tint).
 */
fun buildDynamicAccentGradient(baseAccent: Color): List<Color> {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(baseAccent.toArgb(), hsv)
    val deepHue = (hsv[0] - 8f + 360f) % 360f
    val highlightHue = (hsv[0] + 14f) % 360f
    val deepColor = Color(
        android.graphics.Color.HSVToColor(
            floatArrayOf(
                deepHue,
                (hsv[1] * 1.08f).coerceIn(0.25f, 1f),
                (hsv[2] * 0.78f).coerceIn(0.30f, 1f)
            )
        )
    )
    val highlightColor = Color(
        android.graphics.Color.HSVToColor(
            floatArrayOf(
                highlightHue,
                (hsv[1] * 0.72f).coerceIn(0.15f, 1f),
                (hsv[2] * 1.15f + 0.08f).coerceIn(0.55f, 1f)
            )
        )
    )
    return listOf(deepColor, baseAccent, highlightColor)
}

// ---------------------------------------------------------------------------
// 2. SLEEK COMPACT AUDIO & SPEAKER VOLUME WIDGET CARD
//    - Matches the exact header, 12.5.sp typography, 30.dp icon badges,
//      and compact row proportions of the other dashboard cards.
// ---------------------------------------------------------------------------
private data class VolumeChannelRowItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val volumePct: Float,
    val isMutedOrOff: Boolean,
    val icon: ImageVector,
    val backingTile: SmartTile? = null,
    val castDevice: DiscoveredCastDevice? = null,
    val isMasterMusic: Boolean = false
)

@Composable
fun SfHouseVolumeSlidersBox(
    allTiles: List<SmartTile>,
    masterMusicVolume: Float,
    onUpdateTile: (SmartTile) -> Unit,
    onUpdateMasterMusicVolume: (Float) -> Unit,
    onActivateHeroDial: (SmartTile) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val discoveredCast by JarvisLiveBridge.discoveredCastDevicesFlow.collectAsState()
    val activeCastTarget by JarvisLiveBridge.activeCastTargetNameFlow.collectAsState()

    val volumeRows = remember(allTiles, discoveredCast, masterMusicVolume) {
        val list = mutableListOf<VolumeChannelRowItem>()
        val audioTiles = allTiles.filter { tile ->
            val hasVolumeCap = tile.capabilities.any { cap ->
                CapabilityClassifier.inferRendererType(cap) == CapabilityRendererType.VOLUME_SLIDER
            } || tile.deviceSubtype in setOf(
                DeviceSubtype.SPEAKER,
                DeviceSubtype.CAST_GROUP,
                DeviceSubtype.TV,
                DeviceSubtype.MUSIC_PLAYER
            )
            tile.isVisible && tile.isConfiguredAndLinked && hasVolumeCap &&
                    !(tile.title.equals("Google Nest Mini", ignoreCase = true) && !tile.isOnline)
        }
        for (t in audioTiles) {
            val isMusic = t.serviceClass == ServiceClass.MUSIC || t.deviceSubtype == DeviceSubtype.MUSIC_PLAYER
            val isTv = t.deviceSubtype == DeviceSubtype.TV
            val matchedCast = if (t.deviceSubtype in setOf(DeviceSubtype.SPEAKER, DeviceSubtype.CAST_GROUP)) {
                discoveredCast.firstOrNull { c ->
                    (t.entityId.isNotBlank() && c.id.equals(t.entityId, ignoreCase = true)) ||
                            (t.ipAddress.isNotBlank() && t.ipAddress.contains(c.ipAddress)) ||
                            c.name.equals(t.title, ignoreCase = true)
                }
            } else null
            val vol = when {
                isMusic -> masterMusicVolume
                matchedCast != null && t.volume <= 0.5f -> matchedCast.volumePercent
                else -> t.volume
            }.coerceIn(0f, 100f)
            val isMutedChannel = when {
                isMusic -> vol <= 0.5f
                matchedCast != null -> matchedCast.isMuted || vol <= 0.5f
                else -> vol <= 0.5f
            }
            val subLabel = when {
                isTv -> "Smart TV Audio"
                isMusic -> "Master Media"
                else -> t.roomOrSubtitle.ifBlank { "Smart Speaker" }
            }
            list.add(
                VolumeChannelRowItem(
                    key = "tile_vol_${t.id}",
                    title = t.title,
                    subtitle = subLabel,
                    volumePct = vol,
                    isMutedOrOff = isMutedChannel,
                    icon = when {
                        isTv -> Icons.Outlined.Tv
                        isMusic -> Icons.Outlined.MusicNote
                        t.deviceSubtype == DeviceSubtype.CAST_GROUP -> Icons.Outlined.SpeakerGroup
                        else -> Icons.Outlined.Speaker
                    },
                    backingTile = if (matchedCast != null && t.ipAddress.isBlank()) {
                        t.copy(ipAddress = "${matchedCast.ipAddress}:${matchedCast.port}", entityId = matchedCast.id)
                    } else t,
                    castDevice = matchedCast,
                    isMasterMusic = isMusic
                )
            )
        }
        for (cast in discoveredCast) {
            if (cast.name.equals("Google Nest Mini", ignoreCase = true)) continue
            val alreadyInList = list.any {
                it.title.equals(cast.name, ignoreCase = true) ||
                        it.backingTile?.ipAddress?.contains(cast.ipAddress) == true
            }
            if (!alreadyInList) {
                list.add(
                    VolumeChannelRowItem(
                        key = "cast_vol_${cast.id}",
                        title = cast.name,
                        subtitle = if (cast.isCastGroup) "Speaker Group" else cast.modelName,
                        volumePct = cast.volumePercent.coerceIn(0f, 100f),
                        isMutedOrOff = cast.isMuted || cast.volumePercent <= 0.5f,
                        icon = if (cast.isCastGroup) Icons.Outlined.SpeakerGroup else Icons.Outlined.Speaker,
                        castDevice = cast
                    )
                )
            }
        }
        if (list.isEmpty()) {
            list.add(
                VolumeChannelRowItem(
                    key = "master_tablet_volume",
                    title = "Tablet Speaker",
                    subtitle = "Built-In Audio",
                    volumePct = masterMusicVolume.coerceIn(0f, 100f),
                    isMutedOrOff = masterMusicVolume <= 0.5f,
                    icon = Icons.Outlined.VolumeUp,
                    isMasterMusic = true
                )
            )
        }
        list
    }

    fun applyRowVolume(rowItem: VolumeChannelRowItem, targetVol: Float) {
        val clamped = targetVol.coerceIn(0f, 100f)
        rowItem.castDevice?.let { cast ->
            val updatedCastList = JarvisLiveBridge.discoveredCastDevicesFlow.value.map { d ->
                if (d.id == cast.id || d.ipAddress == cast.ipAddress) {
                    d.copy(volumePercent = clamped, isMuted = clamped <= 0.5f)
                } else d
            }
            JarvisLiveBridge.discoveredCastDevicesFlow.value = updatedCastList
        }
        when {
            rowItem.isMasterMusic -> {
                onUpdateMasterMusicVolume(clamped)
            }
            rowItem.backingTile != null -> {
                onUpdateTile(
                    rowItem.backingTile.copy(
                        volume = clamped,
                        isActive = clamped > 0.5f
                    )
                )
            }
            rowItem.castDevice != null -> {
                val cast = rowItem.castDevice
                scope.launch {
                    GoogleCastLanClient.setCastDeviceVolume(cast.ipAddress, cast.port, clamped)
                }
            }
        }
    }

    val listState = rememberLazyListState()

    SfHouseSunkenTileContainer(
        outerCornerRadius = 22.dp,
        innerCornerRadius = 16.dp,
        sunkenInsetPadding = 6.dp,
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Matching Compact Top Header Row (Matches Garage, Radio & Security Cards)
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
                    Icon(
                        imageVector = Icons.Outlined.VolumeUp,
                        contentDescription = null,
                        tint = GhAccentGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Speakers & Audio",
                            color = GhTextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Active Output: $activeCastTarget",
                            color = GhTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = GhFont,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    color = GhTileSurfaceElevated,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, GhCardBorder)
                ) {
                    Text(
                        text = "${volumeRows.size} ${if (volumeRows.size == 1) "Zone" else "Zones"}",
                        color = GhAccentGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(volumeRows, key = { it.key }) { rowItem ->
                    SfHouseCleanVolumeSliderRow(
                        title = rowItem.title,
                        volumePct = rowItem.volumePct,
                        isMuted = rowItem.isMutedOrOff,
                        icon = rowItem.icon,
                        onToggleMute = {
                            val nextVol = if (rowItem.isMutedOrOff || rowItem.volumePct <= 1f) 60f else 0f
                            applyRowVolume(rowItem, nextVol)
                        },
                        onStepVolume = { delta ->
                            applyRowVolume(rowItem, rowItem.volumePct + delta)
                        },
                        onOpenDial = {
                            when {
                                rowItem.backingTile != null -> {
                                    val base = rowItem.backingTile
                                    val effectiveVol = if (rowItem.isMasterMusic) masterMusicVolume else base.volume
                                    val volumeSubtype = if (base.ipAddress.contains(",")) {
                                        DeviceSubtype.CAST_GROUP
                                    } else {
                                        DeviceSubtype.SPEAKER
                                    }
                                    onActivateHeroDial(
                                        base.copy(
                                            volume = effectiveVol,
                                            forceVolumeHeroMode = true,
                                            deviceClassOverride = DeviceClass.MEDIA,
                                            deviceSubtypeOverride = volumeSubtype
                                        )
                                    )
                                }
                                rowItem.castDevice != null -> {
                                    val cast = rowItem.castDevice
                                    onActivateHeroDial(
                                        SmartTile(
                                            id = "cast_hero_${cast.id}",
                                            title = cast.name,
                                            roomOrSubtitle = cast.modelName,
                                            type = TileType.NEST_MINI,
                                            volume = cast.volumePercent,
                                            isActive = !cast.isMuted && cast.volumePercent > 0.5f,
                                            ipAddress = "${cast.ipAddress}:${cast.port}",
                                            entityId = cast.id,
                                            forceVolumeHeroMode = true,
                                            deviceClassOverride = DeviceClass.MEDIA,
                                            deviceSubtypeOverride = if (cast.isCastGroup) {
                                                DeviceSubtype.CAST_GROUP
                                            } else {
                                                DeviceSubtype.SPEAKER
                                            }
                                        )
                                    )
                                }
                                else -> {
                                    val musicTile = allTiles.firstOrNull { it.type == TileType.MUSIC_PLAYER }
                                    val targetTile = musicTile?.copy(
                                        volume = masterMusicVolume,
                                        forceVolumeHeroMode = true,
                                        deviceClassOverride = DeviceClass.MEDIA,
                                        deviceSubtypeOverride = DeviceSubtype.SPEAKER
                                    ) ?: SmartTile(
                                        id = "master_tablet_speaker",
                                        title = "Tablet Speaker",
                                        type = TileType.NEST_MINI,
                                        volume = masterMusicVolume,
                                        isActive = masterMusicVolume > 0.5f,
                                        forceVolumeHeroMode = true,
                                        deviceClassOverride = DeviceClass.MEDIA,
                                        deviceSubtypeOverride = DeviceSubtype.SPEAKER
                                    )
                                    onActivateHeroDial(targetTile)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SfHouseCleanVolumeSliderRow(
    title: String,
    volumePct: Float,
    isMuted: Boolean,
    icon: ImageVector,
    onToggleMute: () -> Unit,
    onStepVolume: (Float) -> Unit,
    onOpenDial: () -> Unit
) {
    val isActiveChannel = !isMuted && volumePct > 0.5f
    val activeAccent = GhAccentGreen
    val clampedPct = if (isMuted) 0f else volumePct.coerceIn(0f, 100f)

    Surface(
        color = GhTileSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isActiveChannel) activeAccent.copy(alpha = 0.35f) else GhCardBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDial() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Compact 30.dp Rounded-Square Icon Badge (Tap to Mute/Unmute)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isActiveChannel) activeAccent.copy(alpha = 0.20f) else GhTileSurface
                    )
                    .border(
                        width = 1.dp,
                        color = if (isActiveChannel) activeAccent.copy(alpha = 0.55f) else GhCardBorder,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onToggleMute() }
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.Outlined.VolumeOff else icon,
                    contentDescription = title,
                    tint = if (isActiveChannel) activeAccent else GhTextSecondary,
                    modifier = Modifier.size(15.dp)
                )
            }

            // Middle Column: Compact 12.5.sp Title + Sleek 4.dp Progress Bar
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        color = GhTextPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GhFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (isMuted) "Muted" else "${clampedPct.roundToInt()}%",
                        color = if (isActiveChannel) activeAccent else GhTextSecondary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = GhFont
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(GhTileSurface)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((clampedPct / 100f).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(activeAccent, Color(0xFF00E5FF))
                                )
                            )
                    )
                }
            }

            // Compact Quick-Step (- / +) Micro-Buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    color = GhTileSurface,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, GhCardBorder),
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { onStepVolume(-5f) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "-",
                            color = GhTextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                Surface(
                    color = if (isActiveChannel) activeAccent.copy(alpha = 0.18f) else GhTileSurface,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isActiveChannel) activeAccent.copy(alpha = 0.45f) else GhCardBorder
                    ),
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { onStepVolume(5f) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "+",
                            color = if (isActiveChannel) activeAccent else GhTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 3. DEDICATED CAMERA MULTI-VIEW, AI EVENT TAGGING & SECURITY QUICK-BAR PAGE
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SfHouseCameraMultiViewSection(
    allTiles: List<SmartTile>,
    bridgeSettings: BridgeSettings,
    onUpdateTile: (SmartTile) -> Unit,
    onExpandCamera: (SmartTile) -> Unit,
    onOpenAddCameraWizard: () -> Unit,
    onSelectSecurityMode: ((SecurityCenterMode) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraTiles = remember(allTiles) {
        allTiles.filter { it.isCameraTile }
    }

    var isMultiGridMode by remember { mutableStateOf(false) }
    var gridPageIndex by remember { mutableIntStateOf(0) }
    val totalGridPages = remember(cameraTiles.size) {
        ((cameraTiles.size + 3) / 4).coerceAtLeast(1)
    }
    val safeGridPageIndex = gridPageIndex.coerceIn(0, (totalGridPages - 1).coerceAtLeast(0))
    var selectedSingleCameraId by remember(cameraTiles) {
        mutableStateOf(cameraTiles.firstOrNull()?.id.orEmpty())
    }
    var cameraDropdownExpanded by remember { mutableStateOf(false) }
    var showDvrHistoryModal by remember { mutableStateOf(false) }
    var selectedAiFilter by remember { mutableStateOf(CameraAiEventType.NONE) }
    var isCapturingSnapshot by remember { mutableStateOf(false) }
    val activeSecurityMode by JarvisLiveBridge.securityCenterModeFlow.collectAsState()
    val dvrSnapshots by JarvisLiveBridge.dvrSnapshotsFlow.collectAsState()
    val currentCameraTiles by rememberUpdatedState(cameraTiles)

    val selectedCameraTile = remember(cameraTiles, selectedSingleCameraId) {
        cameraTiles.firstOrNull { it.id == selectedSingleCameraId } ?: cameraTiles.firstOrNull()
    }

    LaunchedEffect(Unit) {
        DeviceRepository.loadDvrSnapshots(context)
    }

    // Autonomous Camera AI & Motion Event Monitor: Polls local CGI/ISAPI motion & AI state
    // and automatically captures DVR snapshots when new activity is detected.
    LaunchedEffect(cameraTiles.size) {
        if (cameraTiles.isEmpty()) return@LaunchedEffect
        while (true) {
            for (cam in currentCameraTiles) {
                val detectedAi = SmartHomeApiClient.pollCameraMotionOrAiEvent(cam)
                val nowMs = System.currentTimeMillis()
                if (detectedAi != null && detectedAi != CameraAiEventType.NONE) {
                    val cooldownElapsed = (nowMs - cam.lastCameraEventTimestampMs) > 20_000L
                    if (cam.lastCameraAiEvent != detectedAi || cooldownElapsed) {
                        val updatedCam = cam.copy(
                            lastCameraAiEvent = detectedAi,
                            lastCameraEventTimestampMs = nowMs
                        )
                        onUpdateTile(updatedCam)
                        SmartHomeApiClient.captureDvrEventSnapshot(
                            context = context,
                            tile = updatedCam,
                            bridge = bridgeSettings,
                            aiEventType = detectedAi,
                            summaryText = "${detectedAi.label} auto-detected on ${updatedCam.title}"
                        )
                    }
                } else if (cam.lastCameraAiEvent != CameraAiEventType.NONE &&
                    cam.lastCameraEventTimestampMs > 0L &&
                    (nowMs - cam.lastCameraEventTimestampMs) > 45_000L
                ) {
                    onUpdateTile(cam.copy(lastCameraAiEvent = CameraAiEventType.NONE))
                }
            }
            delay(8_000L)
        }
    }

    LaunchedEffect(cameraTiles.size, isMultiGridMode, safeGridPageIndex, selectedCameraTile?.id) {
        if (isMultiGridMode) {
            cameraTiles.drop(safeGridPageIndex * 4).take(4).forEach { cam ->
                if (!cam.isLiveFeedActive) {
                    onUpdateTile(cam.copy(isLiveFeedActive = true))
                }
            }
        } else if (selectedCameraTile != null && !selectedCameraTile.isLiveFeedActive) {
            onUpdateTile(selectedCameraTile.copy(isLiveFeedActive = true))
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // TOP BAR: Camera Picker + Security Center Mode Pills + DVR Snapshot Controls + 1/4x4 View Toggle
        SfHouseSunkenTileContainer(
            outerCornerRadius = 18.dp,
            innerCornerRadius = 14.dp,
            sunkenInsetPadding = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Videocam,
                        contentDescription = null,
                        tint = GhAccentGreen,
                        modifier = Modifier.size(20.dp)
                    )

                    if (cameraTiles.isEmpty()) {
                        Text(
                            text = "No Cameras Linked Yet",
                            color = GhTextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = GhFont
                        )
                    } else {
                        Box {
                            Surface(
                                color = GhTileSurfaceElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, GhCardBorder),
                                modifier = Modifier
                                    .height(34.dp)
                                    .clickable { cameraDropdownExpanded = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = if (isMultiGridMode) {
                                            "Grid (${cameraTiles.size.coerceAtMost(4)} Active)"
                                        } else {
                                            selectedCameraTile?.title ?: "Select Camera"
                                        },
                                        color = GhTextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = GhFont,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Select Camera",
                                        tint = GhAccentGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = cameraDropdownExpanded,
                                onDismissRequest = { cameraDropdownExpanded = false },
                                modifier = Modifier.background(GhTileSurfaceElevated)
                            ) {
                                cameraTiles.forEach { cam ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = iconForTileType(cam.type),
                                                    contentDescription = null,
                                                    tint = if (cam.id == selectedCameraTile?.id && !isMultiGridMode) GhAccentGreen else GhTextSecondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = cam.title,
                                                    color = GhTextPrimary,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    fontFamily = GhFont
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedSingleCameraId = cam.id
                                            isMultiGridMode = false
                                            cameraDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Security Center Mode Quick-Bar Pills (Home / Away / Night / Vacation)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(SecurityCenterMode.entries) { mode ->
                            val isSel = activeSecurityMode == mode
                            Surface(
                                color = if (isSel) GhAccentGreen.copy(alpha = 0.22f) else GhTileSurfaceElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSel) GhAccentGreen else GhCardBorder
                                ),
                                modifier = Modifier
                                    .height(30.dp)
                                    .clickable {
                                        JarvisLiveBridge.securityCenterModeFlow.value = mode
                                        DeviceRepository.saveBridge(context, bridgeSettings.copy(securityCenterMode = mode))
                                        onSelectSecurityMode?.invoke(mode)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Shield,
                                        contentDescription = null,
                                        tint = if (isSel) GhAccentGreen else GhTextSecondary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = mode.label,
                                        color = if (isSel) GhAccentGreen else GhTextSecondary,
                                        fontSize = 10.5.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                        fontFamily = GhFont
                                    )
                                }
                            }
                        }
                    }
                }

                // Right Controls: Capture DVR Snapshot + DVR History + 1 Camera / 4x4 Multi-Stream
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (selectedCameraTile != null) {
                        Surface(
                            color = GhTileSurfaceElevated,
                            shape = RoundedCornerShape(9.dp),
                            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                            modifier = Modifier
                                .height(34.dp)
                                .clickable {
                                    if (!isCapturingSnapshot) {
                                        isCapturingSnapshot = true
                                        scope.launch {
                                            SmartHomeApiClient.captureDvrEventSnapshot(
                                                context = context,
                                                tile = selectedCameraTile,
                                                bridge = bridgeSettings,
                                                aiEventType = selectedCameraTile.lastCameraAiEvent.takeIf { it != CameraAiEventType.NONE }
                                                    ?: CameraAiEventType.MOTION,
                                                summaryText = "Manual DVR Snapshot (${selectedCameraTile.title})"
                                            )
                                            isCapturingSnapshot = false
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
                                    imageVector = Icons.Outlined.PhotoCamera,
                                    contentDescription = "Capture Snapshot",
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (isCapturingSnapshot) "Saving..." else "Snapshot",
                                    color = GhTextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = GhFont
                                )
                            }
                        }
                    }

                    Surface(
                        color = GhTileSurfaceElevated,
                        shape = RoundedCornerShape(9.dp),
                        border = BorderStroke(1.dp, GhCardBorder),
                        modifier = Modifier
                            .height(34.dp)
                            .clickable { showDvrHistoryModal = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = "DVR History",
                                tint = GhAccentWarm,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "DVR (${dvrSnapshots.size})",
                                color = GhTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GhFont
                            )
                        }
                    }

                    Surface(
                        color = if (!isMultiGridMode) GhAccentGreen else GhTileSurfaceElevated,
                        shape = RoundedCornerShape(9.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .clickable { isMultiGridMode = false }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CropSquare,
                                contentDescription = "Single Camera View",
                                tint = if (!isMultiGridMode) Color(0xFF141518) else GhTextPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "1 Cam",
                                color = if (!isMultiGridMode) Color(0xFF141518) else GhTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GhFont
                            )
                        }
                    }

                    Surface(
                        color = if (isMultiGridMode) GhAccentGreen else GhTileSurfaceElevated,
                        shape = RoundedCornerShape(9.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .clickable {
                                if (isMultiGridMode && totalGridPages > 1) {
                                    gridPageIndex = (safeGridPageIndex + 1) % totalGridPages
                                } else {
                                    isMultiGridMode = true
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.GridView,
                                contentDescription = "Multi-Camera Grid",
                                tint = if (isMultiGridMode) Color(0xFF141518) else GhTextPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = if (totalGridPages > 1) {
                                    "Grid (${safeGridPageIndex + 1}/$totalGridPages)"
                                } else {
                                    "2x2 Grid"
                                },
                                color = if (isMultiGridMode) Color(0xFF141518) else GhTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GhFont
                            )
                        }
                    }
                }
            }
        }

        // AUTONOMOUS CAMERA AI & MOTION EVENT MONITOR + DVR FILTER STRIP
        if (selectedCameraTile != null) {
            val liveAiEvent = selectedCameraTile.lastCameraAiEvent
            val liveAiColor = Color(liveAiEvent.badgeColorHex or 0xFF000000L)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = if (liveAiEvent != CameraAiEventType.NONE) liveAiColor.copy(alpha = 0.22f) else GhTileSurfaceElevated,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            1.dp,
                            if (liveAiEvent != CameraAiEventType.NONE) liveAiColor else GhAccentGreen.copy(alpha = 0.55f)
                        ),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (liveAiEvent != CameraAiEventType.NONE) liveAiColor else GhAccentGreen)
                            )
                            Text(
                                text = if (liveAiEvent != CameraAiEventType.NONE) {
                                    "Live AI: ${liveAiEvent.label}"
                                } else {
                                    "AI Monitor Armed \u2022 All Clear"
                                },
                                color = if (liveAiEvent != CameraAiEventType.NONE) liveAiColor else GhAccentGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            )
                        }
                    }

                    listOf(
                        CameraAiEventType.NONE,
                        CameraAiEventType.PERSON,
                        CameraAiEventType.VEHICLE,
                        CameraAiEventType.PACKAGE,
                        CameraAiEventType.MOTION
                    ).forEach { aiType ->
                        val isFilterSelected = selectedAiFilter == aiType
                        val isLiveDetected = liveAiEvent == aiType && aiType != CameraAiEventType.NONE
                        val badgeColor = Color(aiType.badgeColorHex or 0xFF000000L)
                        val matchCount = if (aiType == CameraAiEventType.NONE) {
                            dvrSnapshots.size
                        } else {
                            dvrSnapshots.count { it.aiEventType == aiType }
                        }
                        Surface(
                            color = if (isFilterSelected || isLiveDetected) badgeColor.copy(alpha = 0.24f) else GhTileSurfaceElevated,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                1.dp,
                                if (isFilterSelected || isLiveDetected) badgeColor else GhCardBorder
                            ),
                            modifier = Modifier
                                .height(26.dp)
                                .clickable {
                                    selectedAiFilter = aiType
                                    if (aiType != CameraAiEventType.NONE && matchCount > 0) {
                                        showDvrHistoryModal = true
                                    }
                                }
                        ) {
                            Text(
                                text = if (aiType == CameraAiEventType.NONE) {
                                    "All ($matchCount)"
                                } else if (matchCount > 0) {
                                    "${aiType.label} ($matchCount)"
                                } else {
                                    aiType.label
                                },
                                color = if (isFilterSelected || isLiveDetected) badgeColor else GhTextSecondary,
                                fontSize = 10.sp,
                                fontWeight = if (isFilterSelected || isLiveDetected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                if (dvrSnapshots.isNotEmpty()) {
                    val filteredSnapshots = if (selectedAiFilter == CameraAiEventType.NONE) {
                        dvrSnapshots
                    } else {
                        dvrSnapshots.filter { it.aiEventType == selectedAiFilter }
                    }
                    val latest = filteredSnapshots.firstOrNull() ?: dvrSnapshots.first()
                    Text(
                        text = "Latest DVR: ${latest.summaryText}",
                        color = Color(0xFF00E5FF),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { showDvrHistoryModal = true }
                    )
                }
            }
        }

        if (cameraTiles.isEmpty()) {
            SfHouseSunkenTileContainer(
                outerCornerRadius = 22.dp,
                innerCornerRadius = 16.dp,
                sunkenInsetPadding = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Videocam,
                        contentDescription = null,
                        tint = GhAccentGreen,
                        modifier = Modifier.size(42.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "No Security Cameras or Doorbells Added Yet",
                        color = GhTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = GhFont
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onOpenAddCameraWizard,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GhAccentGreen,
                            contentColor = Color(0xFF141518)
                        )
                    ) {
                        Text("+ Add Camera", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (!isMultiGridMode && selectedCameraTile != null) {
            SfHouseSunkenTileContainer(
                outerCornerRadius = 22.dp,
                innerCornerRadius = 16.dp,
                sunkenInsetPadding = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                GhEdgeToEdgeCameraTile(
                    tile = selectedCameraTile.copy(isLiveFeedActive = true),
                    bridgeSettings = bridgeSettings,
                    isOverlayModalOpen = false,
                    isEditMode = false,
                    onUpdateTile = onUpdateTile,
                    onExpandCamera = { onExpandCamera(selectedCameraTile) },
                    onToggleEditMode = {},
                    onToggleColSpan = {},
                    onToggleHeight = {},
                    onDeleteTile = {}
                )
            }
        } else {
            val gridSlots = remember(cameraTiles, safeGridPageIndex) {
                val pageOffset = safeGridPageIndex * 4
                (0 until 4).map { idx -> cameraTiles.getOrNull(pageOffset + idx) }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (rowIdx in 0..1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        for (colIdx in 0..1) {
                            val slotIndex = rowIdx * 2 + colIdx
                            val camTile = gridSlots[slotIndex]

                            SfHouseSunkenTileContainer(
                                outerCornerRadius = 20.dp,
                                innerCornerRadius = 14.dp,
                                sunkenInsetPadding = 5.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                if (camTile != null) {
                                    GhEdgeToEdgeCameraTile(
                                        tile = camTile.copy(isLiveFeedActive = true, useSubStream = true),
                                        bridgeSettings = bridgeSettings,
                                        isOverlayModalOpen = false,
                                        isEditMode = false,
                                        onUpdateTile = onUpdateTile,
                                        onExpandCamera = { onExpandCamera(camTile) },
                                        onToggleEditMode = {},
                                        onToggleColSpan = {},
                                        onToggleHeight = {},
                                        onDeleteTile = {}
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable { onOpenAddCameraWizard() },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                imageVector = Icons.Outlined.VideocamOff,
                                                contentDescription = null,
                                                tint = GhTextSecondary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "Camera Slot ${slotIndex + 1} \u2022 Tap to Add",
                                                color = GhTextSecondary,
                                                fontSize = 11.sp,
                                                fontFamily = GhFont
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

    if (showDvrHistoryModal) {
        val displayedSnapshots = if (selectedAiFilter == CameraAiEventType.NONE) {
            dvrSnapshots
        } else {
            dvrSnapshots.filter { it.aiEventType == selectedAiFilter }.ifEmpty { dvrSnapshots }
        }
        DvrSnapshotHistoryModal(
            snapshots = displayedSnapshots,
            onClearAll = {
                DeviceRepository.clearDvrSnapshots(context)
            },
            onDismiss = { showDvrHistoryModal = false }
        )
    }
}

// ---------------------------------------------------------------------------
// DVR EVENT SNAPSHOT HISTORY MODAL
// ---------------------------------------------------------------------------
@Composable
fun DvrSnapshotHistoryModal(
    snapshots: List<DvrEventSnapshot>,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("EEE dd MMM, HH:mm:ss", Locale.getDefault()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhTileSurface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.5.dp, GhCardBorder),
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.84f)
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PhotoCamera,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = "Local DVR Event Snapshot History (${snapshots.size})",
                                color = GhTextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Stored locally on this tablet in encrypted app storage",
                                color = GhTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (snapshots.isNotEmpty()) {
                            OutlinedButton(
                                onClick = onClearAll,
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = GhAccentRed, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear All", fontSize = 11.sp, color = GhAccentRed)
                            }
                        }
                        FilledTonalIconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                HorizontalDivider(color = GhCardBorder)

                if (snapshots.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No DVR snapshots recorded yet. Tap 'Snapshot' or tag an AI event on the Camera page.",
                            color = GhTextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(snapshots, key = { it.id }) { snap ->
                            val badgeColor = Color(snap.aiEventType.badgeColorHex or 0xFF000000L)
                            var loadedBmp by remember(snap.id) { mutableStateOf<ImageBitmap?>(null) }

                            LaunchedEffect(snap.localFilePathOrUri) {
                                if (snap.localFilePathOrUri.isNotBlank()) {
                                    loadedBmp = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val path = Uri.parse(snap.localFilePathOrUri).path
                                            if (!path.isNullOrBlank() && File(path).exists()) {
                                                BitmapFactory.decodeFile(path)?.asImageBitmap()
                                            } else null
                                        }.getOrNull()
                                    }
                                }
                            }

                            Surface(
                                color = GhTileSurfaceElevated,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.55f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(128.dp)
                                            .height(74.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFF141820)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val bmp = loadedBmp
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp,
                                                contentDescription = snap.summaryText,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Outlined.Videocam,
                                                contentDescription = null,
                                                tint = badgeColor,
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Surface(
                                                color = badgeColor.copy(alpha = 0.22f),
                                                shape = RoundedCornerShape(50)
                                            ) {
                                                Text(
                                                    text = snap.aiEventType.label,
                                                    color = badgeColor,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                            Text(
                                                text = snap.cameraTitle,
                                                color = GhTextPrimary,
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Text(
                                            text = snap.summaryText,
                                            color = GhTextPrimary,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = fmt.format(Date(snap.timestampEpochMs)),
                                            color = GhTextSecondary,
                                            fontSize = 11.sp
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
// EDGE-TO-EDGE CAMERA TILE (USED IN DEDICATED CAMERA PAGE & MODALS)
// ---------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GhEdgeToEdgeCameraTile(
    tile: SmartTile,
    bridgeSettings: BridgeSettings,
    isOverlayModalOpen: Boolean,
    isEditMode: Boolean,
    onUpdateTile: (SmartTile) -> Unit,
    onExpandCamera: () -> Unit,
    onToggleEditMode: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onToggleColSpan: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onToggleHeight: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onDeleteTile: () -> Unit
) {
    var isOverlayVisible by remember(tile.id, tile.isLiveFeedActive) {
        mutableStateOf(true)
    }
    var isInlineAudioEnabled by remember(tile.id) { mutableStateOf(false) }
    var overlayRevealNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(
        tile.isLiveFeedActive,
        isOverlayModalOpen,
        isEditMode,
        isOverlayVisible,
        overlayRevealNonce
    ) {
        if (tile.isLiveFeedActive && !isOverlayModalOpen && !isEditMode && isOverlayVisible) {
            delay(4500L)
            isOverlayVisible = false
        }
    }

    val shouldShowHeaderOverlay = !tile.isLiveFeedActive || isEditMode || isOverlayVisible

    val handleTileTap = {
        if (!isEditMode) {
            if (tile.isLiveFeedActive && !isOverlayModalOpen) {
                if (!isOverlayVisible) {
                    isOverlayVisible = true
                    overlayRevealNonce++
                } else {
                    onExpandCamera()
                }
            } else {
                onExpandCamera()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GhTileSurface)
            .combinedClickable(
                enabled = !isEditMode,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { handleTileTap() },
                onLongClick = { onToggleEditMode() }
            )
    ) {
        if (tile.isLiveFeedActive && !isOverlayModalOpen) {
            SmartCameraStreamResolverView(
                tile = tile,
                bridgeSettings = bridgeSettings,
                enableAudio = isInlineAudioEnabled,
                cropToFillTile = true,
                onUpdateTile = onUpdateTile,
                onVideoSurfaceTap = { handleTileTap() }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = iconForTileType(tile.type),
                    contentDescription = tile.title,
                    tint = if (tile.isLiveFeedActive) GhAccentGreen else Color(0xFF00E676),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = tile.title,
                    color = GhTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = GhFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        AnimatedVisibility(
            visible = tile.isLiveFeedActive && shouldShowHeaderOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(GhAccentGreen)
                    )
                    Text(
                        text = tile.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = GhFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (tile.lastCameraAiEvent != CameraAiEventType.NONE) {
                        val aiColor = Color(tile.lastCameraAiEvent.badgeColorHex or 0xFF000000L)
                        Surface(
                            color = aiColor.copy(alpha = 0.28f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, aiColor)
                        ) {
                            Text(
                                text = tile.lastCameraAiEvent.label,
                                color = Color.White,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { isInlineAudioEnabled = !isInlineAudioEnabled },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = if (isInlineAudioEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                            contentDescription = "Audio",
                            tint = if (isInlineAudioEnabled) GhAccentGreen else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = onExpandCamera,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Fullscreen,
                            contentDescription = "Fullscreen",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    SfHouseMintPillSwitch(
                        checked = tile.isLiveFeedActive,
                        onCheckedChange = { active ->
                            onUpdateTile(tile.copy(isLiveFeedActive = active))
                        }
                    )
                }
            }
        }
    }
}
