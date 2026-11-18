package com.jarvis.smarthomelauncher.widgets.radio.Widget

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.smarthomelauncher.SfHouseSunkenTileContainer
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioDashboardSnapshot
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioHistoryItem
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioStation
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioUiState
import com.jarvis.smarthomelauncher.widgets.radio.Repository.RadioRepository
import com.jarvis.smarthomelauncher.widgets.radio.Services.RadioImageMemoryCache
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RadioStationBadgeAvatar(
    faviconUrl: String,
    badgeCode: String,
    accentColorHex: Long,
    size: Dp = 36.dp,
    cornerRadius: Dp = 10.dp,
    fontSize: TextUnit = 11.sp
) {
    var bitmap by remember(faviconUrl) { mutableStateOf<ImageBitmap?>(null) }
    val stationAccent = Color(accentColorHex or 0xFF000000L)

    LaunchedEffect(faviconUrl) {
        if (faviconUrl.isNotBlank()) {
            bitmap = RadioImageMemoryCache.loadImage(faviconUrl)
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        stationAccent.copy(alpha = 0.28f),
                        GhTileSurface
                    )
                )
            )
            .border(
                width = 1.dp,
                color = stationAccent.copy(alpha = 0.55f),
                shape = RoundedCornerShape(cornerRadius)
            )
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = badgeCode,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp)
                    .clip(RoundedCornerShape((cornerRadius - 2.dp).coerceAtLeast(4.dp)))
            )
        } else {
            Text(
                text = badgeCode.take(4).ifBlank { "FM" },
                color = stationAccent,
                fontSize = fontSize,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = GhFont,
                maxLines = 1
            )
        }
    }
}

@Composable
fun RadioWidget(
    modifier: Modifier = Modifier,
    isEditMode: Boolean = false,
    onOpenRadioHub: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    val repository = remember(appContext) { RadioRepository.getInstance(appContext) }
    val uiState by repository.uiState.collectAsState()

    SfHouseSunkenTileContainer(
        outerCornerRadius = 22.dp,
        innerCornerRadius = 16.dp,
        sunkenInsetPadding = 6.dp,
        modifier = modifier.fillMaxSize()
    ) {
        when (val state = uiState) {
            is RadioUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = GhAccentGreen,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(26.dp)
                        )
                        Text(
                            text = "Loading stations...",
                            color = GhTextSecondary,
                            fontSize = 12.sp,
                            fontFamily = GhFont
                        )
                    }
                }
            }

            is RadioUiState.Empty -> {
                RadioStateFallbackView(
                    title = state.message,
                    actionLabel = "Open Radio Hub",
                    onAction = { if (!isEditMode) onOpenRadioHub() }
                )
            }

            is RadioUiState.Error -> {
                RadioStateFallbackView(
                    title = state.message,
                    actionLabel = "Retry",
                    onAction = { repository.refreshAll() }
                )
            }

            is RadioUiState.Ready -> {
                RadioDashboardReadyContent(
                    snapshot = state.snapshot,
                    isEditMode = isEditMode,
                    onOpenRadioHub = onOpenRadioHub,
                    onTogglePlayPause = { repository.togglePlayPause(state.snapshot.currentStation) },
                    onSelectStation = { station -> repository.playStation(station) }
                )
            }
        }
    }
}

@Composable
private fun RadioStateFallbackView(
    title: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Radio,
            contentDescription = null,
            tint = GhAccentGreen,
            modifier = Modifier.size(30.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            color = GhTextPrimary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = GhFont
        )
        Spacer(modifier = Modifier.height(10.dp))
        Surface(
            color = GhTileSurfaceElevated,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.6f)),
            modifier = Modifier.clickable { onAction() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, tint = GhAccentGreen, modifier = Modifier.size(14.dp))
                Text(actionLabel, color = GhAccentGreen, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RadioDashboardReadyContent(
    snapshot: RadioDashboardSnapshot,
    isEditMode: Boolean,
    onOpenRadioHub: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSelectStation: (RadioStation) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "liveRadioPulse")
    val livePulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        val showExtendedInsights = maxHeight >= 255.dp
        val showActivityFooter = maxHeight >= 185.dp
        val heroStationFontSize = if (maxWidth >= 300.dp) 18.5.sp else 16.sp

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. CLICKABLE WIDGET HEADER -> OPENS RADIO HUB POPUP
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !isEditMode) { onOpenRadioHub() }
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Radio,
                        contentDescription = "Radio",
                        tint = GhAccentGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Radio",
                            color = GhTextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont,
                            maxLines = 1
                        )
                        Text(
                            text = snapshot.dashboardSummary,
                            color = if (snapshot.isPlaying) GhAccentGreen else GhTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = GhFont,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = if (snapshot.isPlaying) GhAccentGreen.copy(alpha = 0.18f) else GhTileSurfaceElevated,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            1.dp,
                            if (snapshot.isPlaying) GhAccentGreen.copy(alpha = 0.65f) else GhCardBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (snapshot.isPlaying) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(GhAccentGreen.copy(alpha = livePulseAlpha))
                                )
                            }
                            Text(
                                text = if (snapshot.isPlaying) "LIVE" else "${snapshot.activity.favouriteCount} Favs",
                                color = if (snapshot.isPlaying) GhAccentGreen else GhTextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = GhFont
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Open Radio Hub",
                        tint = GhTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // 2. CROSS-ROOM HERO NOW PLAYING SECTION (Glanceable from 2-4m, zero oversized artwork)
            val heroStation = snapshot.currentStation
            if (heroStation != null) {
                val nowMeta = snapshot.nowPlaying
                val trackLine = when {
                    nowMeta != null && nowMeta.track.isNotBlank() -> nowMeta.track
                    else -> heroStation.genre
                }
                val artistLine = when {
                    nowMeta != null && nowMeta.artist.isNotBlank() && !nowMeta.artist.equals(heroStation.name, ignoreCase = true) ->
                        "${nowMeta.artist} • ${snapshot.activeOutputName}"
                    nowMeta != null && nowMeta.showOrPresenter.isNotBlank() && nowMeta.showOrPresenter != trackLine ->
                        "${nowMeta.showOrPresenter} • ${snapshot.activeOutputName}"
                    else ->
                        snapshot.activeOutputName
                }

                Surface(
                    color = if (snapshot.isPlaying) GhTileGreenActive else GhTileSurfaceElevated,
                    shape = RoundedCornerShape(13.dp),
                    border = BorderStroke(
                        width = if (snapshot.isPlaying) 1.2.dp else 1.dp,
                        color = if (snapshot.isPlaying) GhAccentGreen.copy(alpha = 0.58f) else GhCardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RadioStationBadgeAvatar(
                                faviconUrl = heroStation.faviconUrl,
                                badgeCode = heroStation.badgeCode,
                                accentColorHex = heroStation.accentColorHex,
                                size = 38.dp,
                                cornerRadius = 10.dp,
                                fontSize = 11.5.sp
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(1.dp)
                            ) {
                                Text(
                                    text = heroStation.name,
                                    color = GhTextPrimary,
                                    fontSize = heroStationFontSize,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = GhFont,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = trackLine,
                                    color = if (snapshot.isPlaying) GhAccentGreen else GhTextPrimary.copy(alpha = 0.9f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GhFont,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = artistLine,
                                    color = GhTextSecondary,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = GhFont,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (snapshot.isPlaying) GhAccentGreen else GhTileSurface
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (snapshot.isPlaying) Color.White.copy(alpha = 0.35f) else GhAccentGreen.copy(alpha = 0.6f),
                                        shape = CircleShape
                                    )
                                    .clickable(enabled = !isEditMode) { onTogglePlayPause() }
                            ) {
                                Icon(
                                    imageVector = if (snapshot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (snapshot.isPlaying) "Pause Radio" else "Play Radio",
                                    tint = if (snapshot.isPlaying) Color(0xFF141518) else GhAccentGreen,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                        }

                        // Playback / Broadcast Progress Bar
                        val progressFraction = when {
                            nowMeta?.progressPercent != null -> (nowMeta.progressPercent / 100f).coerceIn(0.05f, 1f)
                            snapshot.isPlaying -> 1f
                            else -> 0f
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
                                    .fillMaxWidth(progressFraction)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                GhAccentGreen.copy(alpha = if (nowMeta?.progressPercent == null && snapshot.isPlaying) livePulseAlpha else 1f),
                                                Color(0xFF00E5FF)
                                            )
                                        )
                                    )
                            )
                        }
                    }
                }
            }

            // 3. ONE-TAP FAVOURITE STATION CHIPS (Max 6)
            val favChips = snapshot.favourites
                .ifEmpty { snapshot.allStations }
                .take(snapshot.settings.maxWidgetFavourites.coerceIn(3, 6))

            if (favChips.isNotEmpty()) {
                LazyRow(
                    userScrollEnabled = !isEditMode,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(favChips, key = { it.id }) { st ->
                        val isCurrentPlaying = snapshot.currentStation?.id == st.id && snapshot.isPlaying
                        Surface(
                            color = if (isCurrentPlaying) GhAccentGreen.copy(alpha = 0.20f) else GhTileSurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                width = if (isCurrentPlaying) 1.2.dp else 1.dp,
                                color = if (isCurrentPlaying) GhAccentGreen else GhCardBorder
                            ),
                            modifier = Modifier
                                .height(34.dp)
                                .clickable(enabled = !isEditMode) { onSelectStation(st) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                RadioStationBadgeAvatar(
                                    faviconUrl = st.faviconUrl,
                                    badgeCode = st.badgeCode,
                                    accentColorHex = st.accentColorHex,
                                    size = 20.dp,
                                    cornerRadius = 5.dp,
                                    fontSize = 8.sp
                                )
                                Text(
                                    text = st.name,
                                    color = if (isCurrentPlaying) GhAccentGreen else GhTextPrimary,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isCurrentPlaying) FontWeight.Bold else FontWeight.SemiBold,
                                    fontFamily = GhFont,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // 4. RECENTLY PLAYED (MAX 3) & HABIT RECOMMENDATIONS (MAX 3) ON TALLER SLOTS
            if (showExtendedInsights) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Recently Played Column (max 3)
                    Surface(
                        color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(0.8.dp, GhCardBorder),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "RECENTLY PLAYED",
                                color = GhTextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = GhFont
                            )
                            val recent3 = snapshot.recentlyPlayed.take(3)
                            if (recent3.isEmpty()) {
                                Text(
                                    text = "Play a station to log track history",
                                    color = GhTextSecondary,
                                    fontSize = 10.sp,
                                    maxLines = 2
                                )
                            } else {
                                recent3.forEach { item ->
                                    RecentCompactRow(
                                        item = item,
                                        onTap = {
                                            snapshot.allStations.firstOrNull { it.id == item.stationId }
                                                ?.let(onSelectStation)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Recommended Stations Column (max 3)
                    if (snapshot.settings.showRecommendationsOnWidget) {
                        Surface(
                            color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(11.dp),
                            border = BorderStroke(0.8.dp, GhCardBorder),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "RECOMMENDED",
                                    color = GhTextSecondary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = GhFont
                                )
                                snapshot.recommendedStations.take(3).forEach { rec ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable(enabled = !isEditMode) { onSelectStation(rec) }
                                            .padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = rec.name,
                                            color = GhTextPrimary,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = rec.badgeCode,
                                            color = GhAccentGreen,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // 5. RADIO ACTIVITY TELEMETRY STRIP
            if (showActivityFooter) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RadioTelemetryPill(
                        label = "Today",
                        value = snapshot.activity.todayListeningFormatted,
                        accent = GhAccentGreen,
                        modifier = Modifier.weight(1f)
                    )
                    RadioTelemetryPill(
                        label = "Favourites",
                        value = "${snapshot.activity.favouriteCount}",
                        accent = Color(0xFF00E5FF),
                        modifier = Modifier.weight(1f)
                    )
                    RadioTelemetryPill(
                        label = "Played",
                        value = "${snapshot.activity.stationsPlayedToday} today",
                        accent = GhAccentWarm,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentCompactRow(
    item: RadioHistoryItem,
    onTap: () -> Unit
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.UK) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onTap() }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${item.stationName} • ${item.track}",
            color = GhTextPrimary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = timeFmt.format(Date(item.timestampEpochMs)),
            color = GhTextSecondary,
            fontSize = 9.5.sp
        )
    }
}

@Composable
private fun RadioTelemetryPill(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = GhTileSurfaceElevated.copy(alpha = 0.72f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, GhCardBorder),
        modifier = modifier.height(26.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = GhTextSecondary,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = value,
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }
    }
}