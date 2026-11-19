package com.jarvis.smarthomelauncher.widgets.commute.Widget

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.smarthomelauncher.SfHouseSunkenTileContainer
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.widgets.commute.Models.*
import com.jarvis.smarthomelauncher.widgets.commute.Repository.CommuteRepository
import com.jarvis.smarthomelauncher.widgets.commute.Settings.iconForTransportMode
import kotlinx.coroutines.delay

/**
 * Shared colour-coded status pill for Normal & Disruption views across Widget and Hub Popup.
 */
@Composable
internal fun CommuteStatusBadge(
    statusKind: ServiceStatusKind,
    delayMinutes: Int = 0
) {
    val (label, tint) = when (statusKind) {
        ServiceStatusKind.ON_TIME -> "ON TIME" to Color(0xFF22C55E)
        ServiceStatusKind.PLATFORM_CHANGED -> "PLATFORM CHANGED" to Color(0xFFF59E0B)
        ServiceStatusKind.DELAYED -> {
            val suffix = if (delayMinutes > 0) " +${delayMinutes}m" else ""
            "DELAYED$suffix" to Color(0xFFF97316)
        }
        ServiceStatusKind.CANCELLED -> "CANCELLED" to Color(0xFFEF4444)
        ServiceStatusKind.ENGINEERING_WORKS -> "ENGINEERING" to Color(0xFFF59E0B)
        ServiceStatusKind.SUSPENDED -> "SUSPENDED" to Color(0xFFEF4444)
        ServiceStatusKind.HEAVY_TRAFFIC -> "HEAVY TRAFFIC" to Color(0xFFF97316)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.18f))
            .border(1.dp, tint.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = tint,
            fontFamily = GhFont,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}

/**
 * Prominently highlights platform numbers and flashes amber when a platform change occurs.
 */
@Composable
internal fun PlatformBadgePill(
    platform: String,
    platformChanged: Boolean,
    previousPlatform: String = "",
    pulseAlpha: Float = 1f
) {
    val highlightColor = Color(0xFFF59E0B)
    val bgColor = if (platformChanged) {
        highlightColor.copy(alpha = 0.24f * pulseAlpha.coerceIn(0.45f, 1f))
    } else {
        GhTileSurfaceElevated
    }
    val borderColor = if (platformChanged) highlightColor else GhCardBorder

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(if (platformChanged) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (platformChanged) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = "Platform Changed",
                tint = highlightColor,
                modifier = Modifier.size(14.dp)
            )
        }
        val textLabel = when {
            platformChanged && previousPlatform.isNotBlank() -> "Plat $platform (was $previousPlatform)"
            platform.equals("Stop", ignoreCase = true) || platform.contains("km") -> platform
            else -> "Plat ${platform.ifBlank { "TBC" }}"
        }
        Text(
            text = textLabel,
            color = if (platformChanged) highlightColor else GhTextPrimary,
            fontFamily = GhFont,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}

@Composable
internal fun OccupancyPill(occupancy: ServiceOccupancyLevel) {
    val tint = when (occupancy) {
        ServiceOccupancyLevel.LOW -> Color(0xFF22C55E)
        ServiceOccupancyLevel.MODERATE -> GhAccentBlue
        ServiceOccupancyLevel.BUSY -> Color(0xFFF59E0B)
        ServiceOccupancyLevel.FULL -> Color(0xFFEF4444)
        ServiceOccupancyLevel.UNKNOWN -> GhTextSecondary
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(GhSunkenWell)
            .border(1.dp, GhCardBorder, RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Groups,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = occupancy.label,
            color = tint,
            fontFamily = GhFont,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/**
 * CWAS Rule 5, 6, 11 & 13: Commute Hub v2.0 Dashboard Experience.
 * Designed for 2–4m glanceability (<3 seconds) inside an SF-House recessed slate container.
 * Clicking the header opens the 90% x 92% Commute Hub Popup.
 */
@Composable
fun CommuteWidget(
    repository: CommuteRepository,
    onOpenCommuteHub: (initialTab: CommuteHubTab?) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by repository.uiState.collectAsState()

    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            nowEpochMs = System.currentTimeMillis()
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "commute_alert_pulse")
    val alertAlpha by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "commute_pulse_alpha"
    )

    SfHouseSunkenTileContainer(
        outerCornerRadius = 22.dp,
        innerCornerRadius = 16.dp,
        sunkenInsetPadding = 4.dp,
        modifier = modifier.fillMaxSize()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onOpenCommuteHub(null) }
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            val cardHeight = maxHeight
            val showUpcomingStrip = cardHeight >= 250.dp
            val showInsightBanner = cardHeight >= 195.dp

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                val readySnapshot = (uiState as? CommuteWidgetUiState.Ready)?.snapshot

                // ============================================================
                // HEADER: "Commute" + train icon + Mode Pill + Settings
                // ============================================================
                CommuteWidgetHeader(
                    snapshot = readySnapshot,
                    alertAlpha = alertAlpha,
                    onHeaderClick = {
                        val targetTab = if (readySnapshot?.displayMode == CommuteWidgetDisplayMode.DISRUPTION) {
                            CommuteHubTab.DISRUPTIONS
                        } else {
                            CommuteHubTab.OVERVIEW
                        }
                        onOpenCommuteHub(targetTab)
                    },
                    onSettingsClick = onOpenSettings
                )

                Spacer(modifier = Modifier.height(6.dp))

                // ============================================================
                // BODY: CWAS Rule 11 Core States (Loading, Empty, Error, Ready)
                // ============================================================
                when (val state = uiState) {
                    is CommuteWidgetUiState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp,
                                    color = GhAccentGreen
                                )
                                Text(
                                    text = "Loading content...",
                                    color = GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    is CommuteWidgetUiState.Empty -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Train,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(30.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = state.title,
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = state.message,
                                color = GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(GhAccentGreen)
                                    .clickable { onOpenSettings() }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Configure Saved Route",
                                    color = Color(0xFF101418),
                                    fontFamily = GhFont,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    is CommuteWidgetUiState.Error -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = GhAccentWarm,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = state.message,
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(GhTileSurfaceElevated)
                                    .border(1.dp, GhCardBorder, RoundedCornerShape(10.dp))
                                    .clickable { repository.triggerRefresh(forceRemote = true) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    tint = GhAccentGreen,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Retry Sync",
                                    color = GhTextPrimary,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    is CommuteWidgetUiState.Ready -> {
                        CommuteReadyDashboardBody(
                            snapshot = state.snapshot,
                            nowEpochMs = nowEpochMs,
                            alertAlpha = alertAlpha,
                            showUpcomingServices = showUpcomingStrip,
                            showInsightBanner = showInsightBanner,
                            onOpenDepartures = { onOpenCommuteHub(CommuteHubTab.LIVE_DEPARTURES) },
                            onOpenPlanner = { onOpenCommuteHub(CommuteHubTab.JOURNEY_PLANNER) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommuteWidgetHeader(
    snapshot: CommuteDashboardSnapshot?,
    alertAlpha: Float,
    onHeaderClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val primaryMode = snapshot?.primaryRouteCard?.route?.mode ?: SupportedTransportMode.RAIL
    val isDisrupted = snapshot?.displayMode == CommuteWidgetDisplayMode.DISRUPTION

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onHeaderClick() },
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
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(GhTileSurfaceElevated)
                    .border(1.dp, GhCardBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = iconForTransportMode(primaryMode),
                    contentDescription = "Commute",
                    tint = if (isDisrupted) Color(0xFFEF4444) else GhAccentGreen,
                    modifier = Modifier.size(16.dp)
                )
            }

            Column {
                Text(
                    text = "Commute",
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                snapshot?.primaryRouteCard?.route?.routeName?.takeIf { it.isNotBlank() }?.let { routeTitle ->
                    Text(
                        text = routeTitle,
                        color = GhTextSecondary,
                        fontFamily = GhFont,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            when (snapshot?.displayMode) {
                CommuteWidgetDisplayMode.DISRUPTION -> {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFEF4444).copy(alpha = 0.2f))
                            .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .alpha(alertAlpha)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444))
                        )
                        Text(
                            text = "DISRUPTION",
                            color = Color(0xFFEF4444),
                            fontFamily = GhFont,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                CommuteWidgetDisplayMode.NORMAL -> {
                    val leaveText = snapshot.primaryRouteCard?.leaveHomeInMinutes
                        ?.takeIf { it in 0..90 }
                        ?.let { if (it <= 1) "LEAVE NOW" else "LEAVE IN ${it}M" }
                        ?: "GOOD SERVICE"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(GhAccentGreen.copy(alpha = 0.16f))
                            .border(1.dp, GhAccentGreen.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = leaveText,
                            color = GhAccentGreen,
                            fontFamily = GhFont,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                null -> Unit
            }

            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(GhSunkenWell)
                    .clickable { onSettingsClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Commute Settings",
                    tint = GhTextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun CommuteReadyDashboardBody(
    snapshot: CommuteDashboardSnapshot,
    nowEpochMs: Long,
    alertAlpha: Float,
    showUpcomingServices: Boolean,
    showInsightBanner: Boolean,
    onOpenDepartures: () -> Unit,
    onOpenPlanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryCard = snapshot.primaryRouteCard
    val hero = snapshot.heroService

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        if (hero != null && primaryCard != null) {
            if (snapshot.displayMode == CommuteWidgetDisplayMode.DISRUPTION) {
                // Critical Requirement: Disruptions override normal display mode,
                // while keeping the next departure always visible.
                DisruptionModeHeroPanel(
                    routeCard = primaryCard,
                    heroService = hero,
                    disruptions = snapshot.disruptions,
                    nowEpochMs = nowEpochMs,
                    alertAlpha = alertAlpha,
                    onClick = onOpenPlanner
                )
            } else {
                NormalModeHeroPanel(
                    routeCard = primaryCard,
                    heroService = hero,
                    nowEpochMs = nowEpochMs,
                    alertAlpha = alertAlpha,
                    onClick = onOpenDepartures
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(GhSunkenWell)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No live departures currently scheduled • Tap to open Journey Planner",
                    color = GhTextSecondary,
                    fontFamily = GhFont,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Upcoming Services Strip (up to 5 services, compact 2-4m rows)
        if (showUpcomingServices && snapshot.upcomingServices.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                snapshot.upcomingServices.take(3).forEach { svc ->
                    CompactUpcomingServiceRow(
                        service = svc,
                        pulseAlpha = alertAlpha,
                        onClick = onOpenDepartures
                    )
                }
            }
        }

        // RulesEngine Actionable Insight Banner
        val topInsight = snapshot.insights.firstOrNull()
        if (showInsightBanner && topInsight != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(GhSunkenWell)
                    .border(1.dp, GhCardBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = if (snapshot.displayMode == CommuteWidgetDisplayMode.DISRUPTION) {
                        GhAccentWarm
                    } else {
                        GhAccentGreen
                    },
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = topInsight.headline,
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Normal Mode Hero Journey + Live Status:
 * Shows origin, destination, nextDeparture, countdown, platform, journeyTime, delay, occupancy, status.
 */
@Composable
private fun NormalModeHeroPanel(
    routeCard: RouteLiveSummaryCard,
    heroService: CommuteDepartureService,
    nowEpochMs: Long,
    alertAlpha: Float,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(GhSunkenWell)
            .border(1.dp, GhCardBorder, RoundedCornerShape(13.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        // Origin -> Destination + Status Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${heroService.originName} → ${heroService.destinationName}",
                color = GhTextSecondary,
                fontFamily = GhFont,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            CommuteStatusBadge(
                statusKind = heroService.statusKind,
                delayMinutes = heroService.delayMinutes
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Large 2-4m Readable Next Departure Time + Countdown + Platform Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = heroService.expectedDepartureTime,
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Column(modifier = Modifier.padding(bottom = 3.dp)) {
                    Text(
                        text = formatDepartureCountdownLabel(heroService.expectedDepartureEpochMs, nowEpochMs),
                        color = GhAccentGreen,
                        fontFamily = GhFont,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    val durationPart = if (heroService.journeyDurationMinutes > 0) {
                        "${heroService.journeyDurationMinutes}m journey"
                    } else {
                        heroService.operatorOrLineName
                    }
                    Text(
                        text = durationPart,
                        color = GhTextSecondary,
                        fontFamily = GhFont,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }

            PlatformBadgePill(
                platform = heroService.displayPlatform,
                platformChanged = heroService.platformChanged,
                previousPlatform = heroService.previousPlatform,
                pulseAlpha = alertAlpha
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Live Status Strip: Leave-By Walk Reminder + Occupancy
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsWalk,
                    contentDescription = null,
                    tint = GhTextSecondary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "Leave by ${routeCard.leaveHomeAtFormatted} (${routeCard.route.walkToStationMinutes}m walk)",
                    color = GhTextSecondary,
                    fontFamily = GhFont,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            OccupancyPill(occupancy = heroService.occupancy)
        }
    }
}

/**
 * Disruption Mode Hero Panel (Auto-activates and overrides Normal Mode):
 * Keeps next departure visible while prominently displaying delay, cancelled,
 * engineeringWorks, platform changes, and alternativeRoute advice.
 */
@Composable
private fun DisruptionModeHeroPanel(
    routeCard: RouteLiveSummaryCard,
    heroService: CommuteDepartureService,
    disruptions: List<CommuteDisruptionItem>,
    nowEpochMs: Long,
    alertAlpha: Float,
    onClick: () -> Unit
) {
    val primaryDisruption = routeCard.activeDisruptions.firstOrNull()
        ?: disruptions.firstOrNull { it.affectsSavedRoute }
        ?: disruptions.firstOrNull()

    val hasEngineering = heroService.statusKind == ServiceStatusKind.ENGINEERING_WORKS ||
            primaryDisruption?.category == DisruptionCategory.ENGINEERING_WORKS

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(GhSunkenWell)
            .border(1.5.dp, Color(0xFFEF4444).copy(alpha = 0.78f), RoundedCornerShape(13.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${heroService.originName} → ${heroService.destinationName}",
                color = GhTextPrimary,
                fontFamily = GhFont,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            CommuteStatusBadge(
                statusKind = routeCard.overallStatus,
                delayMinutes = heroService.delayMinutes.coerceAtLeast(routeCard.maxDelayMinutes)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Next Departure Always Visible (Scheduled + Expected / Cancelled + Highlighted Platform)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = heroService.scheduledDepartureTime,
                    color = if (heroService.isCancelled || heroService.delayMinutes > 0) {
                        GhTextSecondary
                    } else {
                        GhTextPrimary
                    },
                    fontFamily = GhFont,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                if (heroService.isCancelled) {
                    Text(
                        text = "CANCELLED",
                        color = Color(0xFFEF4444),
                        fontFamily = GhFont,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                } else if (heroService.delayMinutes > 0) {
                    Text(
                        text = "→ ${heroService.expectedDepartureTime} (+${heroService.delayMinutes}m)",
                        color = Color(0xFFF97316),
                        fontFamily = GhFont,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                } else {
                    Text(
                        text = formatDepartureCountdownLabel(heroService.expectedDepartureEpochMs, nowEpochMs),
                        color = GhAccentWarm,
                        fontFamily = GhFont,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            PlatformBadgePill(
                platform = heroService.displayPlatform,
                platformChanged = heroService.platformChanged,
                previousPlatform = heroService.previousPlatform,
                pulseAlpha = alertAlpha
            )
        }

        // Engineering Works / Delay Detail
        val detailMessage = when {
            hasEngineering -> primaryDisruption?.summary ?: "Engineering works affecting route"
            heroService.statusDetailReason.isNotBlank() -> heroService.statusDetailReason
            primaryDisruption != null -> primaryDisruption.summary
            else -> ""
        }
        if (detailMessage.isNotBlank()) {
            Spacer(modifier = Modifier.height(5.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = if (hasEngineering) Icons.Default.Construction else Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = detailMessage,
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Actionable Alternative Route Guidance
        if (routeCard.alternativeRouteSummary.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AltRoute,
                    contentDescription = null,
                    tint = GhAccentGreen,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = routeCard.alternativeRouteSummary,
                    color = GhAccentGreen,
                    fontFamily = GhFont,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CompactUpcomingServiceRow(
    service: CommuteDepartureService,
    pulseAlpha: Float,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(GhSunkenWell)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = service.scheduledDepartureTime,
                color = GhTextPrimary,
                fontFamily = GhFont,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = service.destinationName,
                color = GhTextPrimary,
                fontFamily = GhFont,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val statusColor = when {
                service.isCancelled -> Color(0xFFEF4444)
                service.delayMinutes > 0 -> Color(0xFFF97316)
                else -> Color(0xFF22C55E)
            }
            val statusText = when {
                service.isCancelled -> "Canc"
                service.delayMinutes > 0 -> "Exp ${service.expectedDepartureTime}"
                else -> "On time"
            }
            Text(
                text = statusText,
                color = statusColor,
                fontFamily = GhFont,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            PlatformBadgePill(
                platform = service.displayPlatform,
                platformChanged = service.platformChanged,
                previousPlatform = service.previousPlatform,
                pulseAlpha = pulseAlpha
            )
        }
    }
}

internal fun formatDepartureCountdownLabel(departureEpochMs: Long, nowEpochMs: Long): String {
    val diffMs = departureEpochMs - nowEpochMs
    if (diffMs <= 30_000L) return "Due now"
    val mins = (diffMs / 60_000L).toInt()
    val hours = mins / 60
    val remMins = mins % 60
    return if (hours > 0) {
        "in ${hours}h ${remMins}m"
    } else {
        "in ${mins}m"
    }
}