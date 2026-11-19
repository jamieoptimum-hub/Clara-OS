package com.jarvis.smarthomelauncher.widgets.commute.Popup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessible
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.smarthomelauncher.SfHouseSunkenTileContainer
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.widgets.commute.Models.*
import com.jarvis.smarthomelauncher.widgets.commute.Repository.CommuteRepository
import com.jarvis.smarthomelauncher.widgets.commute.Settings.CommuteSettingsContent
import com.jarvis.smarthomelauncher.widgets.commute.Settings.CommuteSettingsDialog
import com.jarvis.smarthomelauncher.widgets.commute.Settings.EndpointSearchBox
import com.jarvis.smarthomelauncher.widgets.commute.Settings.iconForTransportMode
import com.jarvis.smarthomelauncher.widgets.commute.Widget.CommuteStatusBadge
import com.jarvis.smarthomelauncher.widgets.commute.Widget.OccupancyPill
import com.jarvis.smarthomelauncher.widgets.commute.Widget.PlatformBadgePill
import com.jarvis.smarthomelauncher.widgets.commute.Widget.formatDepartureCountdownLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * CWAS Rule 5 & Rule 6: Commute Hub v2.0 Popup (90% width x 92% height).
 * Opens as an overlay Dialog keeping the Clara dashboard visible behind it.
 */
@Composable
fun CommuteHubPopup(
    repository: CommuteRepository,
    initialTab: CommuteHubTab = CommuteHubTab.OVERVIEW,
    onDismiss: () -> Unit
) {
    val uiState by repository.uiState.collectAsState()

    var activeTab by remember(initialTab) { mutableStateOf(initialTab) }
    var selectedRouteIdForBoard by remember { mutableStateOf<String?>(null) }
    var selectedStationForDetails by remember { mutableStateOf<TransportStopEndpoint?>(null) }
    var showSettingsModal by remember { mutableStateOf(false) }

    var showVoiceBar by remember { mutableStateOf(false) }
    var voiceQuery by remember { mutableStateOf("") }
    var voiceAnswerBanner by remember { mutableStateOf<String?>(null) }

    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            nowEpochMs = System.currentTimeMillis()
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "commute_popup_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "commute_popup_pulse_alpha"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        SfHouseSunkenTileContainer(
            outerCornerRadius = 28.dp,
            innerCornerRadius = 22.dp,
            sunkenInsetPadding = 4.dp,
            innerBackgroundColor = GhBackground,
            innerBorderColor = GhCardBorder,
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .fillMaxHeight(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 18.dp)
            ) {
                val readySnap = (uiState as? CommuteWidgetUiState.Ready)?.snapshot

                // ============================================================
                // TOP BAR: Hub Title, Live Mode Pill, Voice, Refresh, Settings, Close
                // ============================================================
                CommuteHubTopBar(
                    snapshot = readySnap,
                    onToggleVoiceBar = { showVoiceBar = !showVoiceBar },
                    onRefresh = { repository.triggerRefresh(forceRemote = true) },
                    onOpenSettings = { showSettingsModal = true },
                    onDismiss = onDismiss
                )

                if (showVoiceBar) {
                    Spacer(modifier = Modifier.height(10.dp))
                    CommuteVoiceCommandBar(
                        query = voiceQuery,
                        answerBanner = voiceAnswerBanner,
                        onQueryChange = { voiceQuery = it },
                        onRunQuery = { spoken ->
                            val resp = repository.handleVoiceCommand(spoken)
                            voiceAnswerBanner = if (resp.handled) {
                                resp.spokenText
                            } else {
                                "Try: 'How is my commute today', 'When is my next train', 'Any delays on my route', 'What platform is my train departing from', or 'Show live departures'."
                            }
                            resp.targetTab?.let { activeTab = it }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ============================================================
                // 6-TAB NAVIGATION BAR
                // ============================================================
                CommuteHubTabsRow(
                    activeTab = activeTab,
                    disruptionCount = readySnap?.disruptions?.count { it.affectsSavedRoute } ?: 0,
                    onSelectTab = { activeTab = it }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // ============================================================
                // TAB CONTENT BY CORE STATE (CWAS Rule 11)
                // ============================================================
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (val state = uiState) {
                        is CommuteWidgetUiState.Loading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = GhAccentGreen)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Loading live departure boards, platforms & disruption feeds...",
                                        color = GhTextSecondary,
                                        fontFamily = GhFont,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }

                        is CommuteWidgetUiState.Empty -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(GhTileSurface)
                                        .border(1.dp, GhAccentGreen.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                        .padding(14.dp)
                                ) {
                                    Text(
                                        text = "Welcome to Commute Hub v2.0 — Add your Home to Work, station, or road commute routes below to unlock live departure boards, platform alerts, disruption overrides, and multi-modal journey planning.",
                                        color = GhTextPrimary,
                                        fontFamily = GhFont,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                CommuteSettingsContent(
                                    repository = repository,
                                    onRouteSavedCallback = { activeTab = CommuteHubTab.OVERVIEW },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        is CommuteWidgetUiState.Error -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = GhAccentWarm,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = state.message,
                                    color = GhTextPrimary,
                                    fontFamily = GhFont,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (state.technicalDetail.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = state.technicalDetail,
                                        color = GhTextSecondary,
                                        fontFamily = GhFont,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = { repository.triggerRefresh(forceRemote = true) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = GhAccentGreen,
                                        contentColor = Color(0xFF101418)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Retry Live Sync", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        is CommuteWidgetUiState.Ready -> {
                            val snap = state.snapshot
                            when (activeTab) {
                                CommuteHubTab.OVERVIEW -> OverviewTabContent(
                                    snapshot = snap,
                                    nowEpochMs = nowEpochMs,
                                    pulseAlpha = pulseAlpha,
                                    onOpenRouteDepartures = { routeId ->
                                        selectedRouteIdForBoard = routeId
                                        activeTab = CommuteHubTab.LIVE_DEPARTURES
                                    },
                                    onOpenDisruptions = {
                                        activeTab = CommuteHubTab.DISRUPTIONS
                                    },
                                    onOpenPlanner = {
                                        activeTab = CommuteHubTab.JOURNEY_PLANNER
                                    }
                                )

                                CommuteHubTab.ROUTES -> RoutesTabContent(
                                    repository = repository,
                                    snapshot = snap,
                                    pulseAlpha = pulseAlpha,
                                    onOpenRouteBoard = { routeId ->
                                        selectedRouteIdForBoard = routeId
                                        activeTab = CommuteHubTab.LIVE_DEPARTURES
                                    },
                                    onOpenSettings = { showSettingsModal = true }
                                )

                                CommuteHubTab.LIVE_DEPARTURES -> LiveDeparturesTabContent(
                                    snapshot = snap,
                                    selectedRouteId = selectedRouteIdForBoard,
                                    nowEpochMs = nowEpochMs,
                                    pulseAlpha = pulseAlpha,
                                    onSelectRoute = { selectedRouteIdForBoard = it },
                                    onOpenStation = { stop ->
                                        selectedStationForDetails = stop
                                        activeTab = CommuteHubTab.STATIONS
                                    }
                                )

                                CommuteHubTab.STATIONS -> StationsTabContent(
                                    repository = repository,
                                    snapshot = snap,
                                    selectedStation = selectedStationForDetails,
                                    pulseAlpha = pulseAlpha,
                                    onSelectStation = { selectedStationForDetails = it }
                                )

                                CommuteHubTab.DISRUPTIONS -> DisruptionsTabContent(
                                    snapshot = snap,
                                    onOpenJourneyPlanner = {
                                        activeTab = CommuteHubTab.JOURNEY_PLANNER
                                    }
                                )

                                CommuteHubTab.JOURNEY_PLANNER -> JourneyPlannerTabContent(
                                    repository = repository,
                                    snapshot = snap
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showSettingsModal) {
            CommuteSettingsDialog(
                repository = repository,
                onDismiss = { showSettingsModal = false }
            )
        }
    }
}

@Composable
private fun CommuteHubTopBar(
    snapshot: CommuteDashboardSnapshot?,
    onToggleVoiceBar: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDisrupted = snapshot?.displayMode == CommuteWidgetDisplayMode.DISRUPTION
    val primaryMode = snapshot?.primaryRouteCard?.route?.mode ?: SupportedTransportMode.RAIL

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
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GhTileSurfaceElevated)
                    .border(1.dp, GhCardBorder, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = iconForTransportMode(primaryMode),
                    contentDescription = null,
                    tint = if (isDisrupted) Color(0xFFEF4444) else GhAccentGreen,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Commute Hub",
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    snapshot?.displayMode?.let { mode ->
                        val (label, tint) = when (mode) {
                            CommuteWidgetDisplayMode.DISRUPTION -> "DISRUPTION OVERRIDE" to Color(0xFFEF4444)
                            CommuteWidgetDisplayMode.NORMAL -> "GOOD SERVICE" to GhAccentGreen
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(tint.copy(alpha = 0.16f))
                                .border(1.dp, tint.copy(alpha = 0.65f), RoundedCornerShape(7.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = label,
                                color = tint,
                                fontFamily = GhFont,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    text = snapshot?.providerLabel ?: "Clara Multi-Modal Transport Intelligence",
                    color = GhTextSecondary,
                    fontFamily = GhFont,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CommutePopupIconButton(
                icon = Icons.Default.Mic,
                contentDescription = "Voice Commute Assistant",
                onClick = onToggleVoiceBar
            )
            CommutePopupIconButton(
                icon = Icons.Default.Refresh,
                contentDescription = "Refresh Live Departures",
                onClick = onRefresh
            )
            CommutePopupIconButton(
                icon = Icons.Default.Settings,
                contentDescription = "Commute Hub Settings",
                onClick = onOpenSettings
            )
            CommutePopupIconButton(
                icon = Icons.Default.Close,
                contentDescription = "Close Commute Hub",
                onClick = onDismiss
            )
        }
    }
}

@Composable
private fun CommutePopupIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(GhTileSurfaceElevated)
            .border(1.dp, GhCardBorder, CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = GhTextPrimary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun CommuteVoiceCommandBar(
    query: String,
    answerBanner: String?,
    onQueryChange: (String) -> Unit,
    onRunQuery: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GhTileSurface)
            .border(1.dp, GhCardBorder, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(
                        text = "Ask Clara (e.g. 'How is my commute today', 'What platform is my train departing from')",
                        color = GhTextSecondary,
                        fontSize = 12.sp
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = GhTextPrimary,
                    unfocusedTextColor = GhTextPrimary,
                    focusedBorderColor = GhAccentGreen,
                    unfocusedBorderColor = GhCardBorder,
                    focusedContainerColor = GhSunkenWell,
                    unfocusedContainerColor = GhSunkenWell
                ),
                shape = RoundedCornerShape(10.dp)
            )
            Button(
                onClick = { onRunQuery(query) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = GhAccentGreen,
                    contentColor = Color(0xFF101418)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(50.dp)
            ) {
                Text("Ask", fontWeight = FontWeight.Bold)
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val presets = listOf(
                "How is my commute today",
                "When is my next train",
                "Any delays on my route",
                "What platform is my train departing from",
                "Show live departures"
            )
            items(presets) { preset ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(GhSunkenWell)
                        .border(1.dp, GhCardBorder, RoundedCornerShape(8.dp))
                        .clickable {
                            onQueryChange(preset)
                            onRunQuery(preset)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = preset,
                        color = GhTextSecondary,
                        fontFamily = GhFont,
                        fontSize = 11.sp
                    )
                }
            }
        }

        if (!answerBanner.isNullOrBlank()) {
            Text(
                text = answerBanner,
                color = GhAccentGreen,
                fontFamily = GhFont,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun CommuteHubTabsRow(
    activeTab: CommuteHubTab,
    disruptionCount: Int,
    onSelectTab: (CommuteHubTab) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(CommuteHubTab.entries) { tab ->
            val selected = tab == activeTab
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) GhAccentGreen else GhTileSurface)
                    .border(
                        1.dp,
                        if (selected) GhAccentGreen else GhCardBorder,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelectTab(tab) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = tab.title,
                    color = if (selected) Color(0xFF101418) else GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold
                )
                if (tab == CommuteHubTab.DISRUPTIONS && disruptionCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) Color(0xFF101418) else Color(0xFFEF4444))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = disruptionCount.toString(),
                            color = Color.White,
                            fontFamily = GhFont,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// TAB 1: OVERVIEW PAGE
// Sections: RulesEngine Insights, Morning Commute, Evening Commute,
//           Active Disruptions & Upcoming Departures
// ============================================================================
@Composable
private fun OverviewTabContent(
    snapshot: CommuteDashboardSnapshot,
    nowEpochMs: Long,
    pulseAlpha: Float,
    onOpenRouteDepartures: (String) -> Unit,
    onOpenDisruptions: () -> Unit,
    onOpenPlanner: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // RulesEngine Insights Strip
        if (snapshot.insights.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(snapshot.insights, key = { it.id }) { insight ->
                        val isAlert = insight.ruleType != CommuteInsightRuleType.LEAVE_HOME_REMINDER
                        val borderTint = if (isAlert) Color(0xFFF59E0B) else GhAccentGreen
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(GhTileSurface)
                                .border(1.dp, borderTint.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = borderTint,
                                modifier = Modifier.size(16.dp)
                            )
                            Column {
                                Text(
                                    text = insight.headline,
                                    color = GhTextPrimary,
                                    fontFamily = GhFont,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (insight.supportingDetail.isNotBlank()) {
                                    Text(
                                        text = "${insight.ruleType.ruleName} • ${insight.supportingDetail}",
                                        color = GhTextSecondary,
                                        fontFamily = GhFont,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Morning & Evening Commute Side-by-Side
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CommuteSectionHeader("Morning Commute")
                    if (snapshot.morningRouteCards.isEmpty()) {
                        CommuteEmptyCard("No Morning Commute route saved.")
                    } else {
                        snapshot.morningRouteCards.forEach { card ->
                            RouteOverviewSummaryCard(
                                card = card,
                                nowEpochMs = nowEpochMs,
                                pulseAlpha = pulseAlpha,
                                onClick = { onOpenRouteDepartures(card.route.routeId) }
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CommuteSectionHeader("Evening Commute")
                    if (snapshot.eveningRouteCards.isEmpty()) {
                        CommuteEmptyCard("No Evening Commute route saved — use the Swap icon in Settings to create a return leg.")
                    } else {
                        snapshot.eveningRouteCards.forEach { card ->
                            RouteOverviewSummaryCard(
                                card = card,
                                nowEpochMs = nowEpochMs,
                                pulseAlpha = pulseAlpha,
                                onClick = { onOpenRouteDepartures(card.route.routeId) }
                            )
                        }
                    }
                }
            }
        }

        // Active Route Disruptions Banner
        val affectedDisruptions = snapshot.disruptions.filter { it.affectsSavedRoute }
        if (affectedDisruptions.isNotEmpty()) {
            item {
                CommuteSectionHeader("Active Disruptions Affecting Your Routes (${affectedDisruptions.size})")
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    affectedDisruptions.take(3).forEach { dis ->
                        DisruptionDetailCard(
                            disruption = dis,
                            onPlanAlternative = onOpenPlanner,
                            modifier = Modifier.clickable { onOpenDisruptions() }
                        )
                    }
                }
            }
        }

        // Upcoming Departures on Primary Route
        item {
            val primaryName = snapshot.primaryRouteCard?.route?.routeName ?: "Primary Route"
            CommuteSectionHeader("Upcoming Departures • $primaryName")
            Spacer(modifier = Modifier.height(8.dp))
            val services = snapshot.primaryRouteCard?.upcomingServices.orEmpty()
            if (services.isEmpty()) {
                CommuteEmptyCard("No live departures currently listed for $primaryName.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    services.take(5).forEach { svc ->
                        ExpandableDepartureBoardRow(
                            service = svc,
                            nowEpochMs = nowEpochMs,
                            pulseAlpha = pulseAlpha
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteOverviewSummaryCard(
    card: RouteLiveSummaryCard,
    nowEpochMs: Long,
    pulseAlpha: Float,
    onClick: () -> Unit
) {
    val next = card.nextService
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GhTileSurface)
            .border(
                1.dp,
                when {
                    card.overallStatus.isDisrupted -> Color(0xFFEF4444).copy(alpha = 0.7f)
                    card.route.isPrimaryRoute -> GhAccentGreen.copy(alpha = 0.7f)
                    else -> GhCardBorder
                },
                RoundedCornerShape(16.dp)
            )
            .clickable { onClick() }
            .padding(14.dp),
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
                Icon(
                    imageVector = iconForTransportMode(card.route.mode),
                    contentDescription = null,
                    tint = GhAccentGreen,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = card.route.routeName,
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            CommuteStatusBadge(
                statusKind = card.overallStatus,
                delayMinutes = card.maxDelayMinutes
            )
        }

        Text(
            text = "${card.route.origin.compactDisplayName} → ${card.route.destination.compactDisplayName}",
            color = GhTextSecondary,
            fontFamily = GhFont,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (next != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = next.expectedDepartureTime,
                        color = if (next.isCancelled) Color(0xFFEF4444) else GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    if (!next.isCancelled) {
                        Text(
                            text = formatDepartureCountdownLabel(next.expectedDepartureEpochMs, nowEpochMs),
                            color = GhAccentGreen,
                            fontFamily = GhFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                }
                PlatformBadgePill(
                    platform = next.displayPlatform,
                    platformChanged = next.platformChanged,
                    previousPlatform = next.previousPlatform,
                    pulseAlpha = pulseAlpha
                )
            }

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
                        text = "Leave by ${card.leaveHomeAtFormatted} (${card.route.walkToStationMinutes}m walk)",
                        color = GhTextSecondary,
                        fontFamily = GhFont,
                        fontSize = 11.sp
                    )
                }
                OccupancyPill(occupancy = next.occupancy)
            }
        } else {
            Text(
                text = "No scheduled departures right now",
                color = GhTextSecondary,
                fontFamily = GhFont,
                fontSize = 12.sp
            )
        }
    }
}

// ============================================================================
// TAB 2: ROUTES PAGE
// Manage & inspect all Saved Commute Routes
// ============================================================================
@Composable
private fun RoutesTabContent(
    repository: CommuteRepository,
    snapshot: CommuteDashboardSnapshot,
    pulseAlpha: Float,
    onOpenRouteBoard: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Saved Commute Routes (${snapshot.allRouteCards.size})",
                color = GhTextPrimary,
                fontFamily = GhFont,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GhAccentGreen,
                    contentColor = Color(0xFF101418)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Add / Configure Routes", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(snapshot.allRouteCards, key = { it.route.routeId }) { card ->
                val r = card.route
                val next = card.nextService
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GhTileSurface)
                        .border(
                            1.dp,
                            if (r.isPrimaryRoute) GhAccentGreen else GhCardBorder,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable { onOpenRouteBoard(r.routeId) }
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(GhSunkenWell),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = iconForTransportMode(r.mode),
                                    contentDescription = null,
                                    tint = GhAccentGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = r.routeName,
                                    color = GhTextPrimary,
                                    fontFamily = GhFont,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${r.origin.compactDisplayName} → ${r.destination.compactDisplayName} • ${r.mode.displayName} • ${r.timeWindow.label}",
                                    color = GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CommuteStatusBadge(
                                statusKind = card.overallStatus,
                                delayMinutes = card.maxDelayMinutes
                            )
                            IconButton(
                                onClick = {
                                    scope.launch { repository.setPrimaryCommuteRoute(r.routeId) }
                                }
                            ) {
                                Icon(
                                    imageVector = if (r.isPrimaryRoute) Icons.Default.Star else Icons.Default.StarOutline,
                                    contentDescription = "Primary Route",
                                    tint = if (r.isPrimaryRoute) Color(0xFFFBBF24) else GhTextSecondary
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch { repository.deleteCommuteRoute(r.routeId) }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete Route",
                                    tint = GhAccentRed
                                )
                            }
                        }
                    }

                    if (next != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(GhSunkenWell)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Next: ${next.scheduledDepartureTime} (Exp ${next.expectedDepartureTime}) • Leave home by ${card.leaveHomeAtFormatted}",
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            PlatformBadgePill(
                                platform = next.displayPlatform,
                                platformChanged = next.platformChanged,
                                previousPlatform = next.previousPlatform,
                                pulseAlpha = pulseAlpha
                            )
                        }
                    }

                    if (card.alternativeRouteSummary.isNotBlank()) {
                        Text(
                            text = "Alternative: ${card.alternativeRouteSummary}",
                            color = GhAccentGreen,
                            fontFamily = GhFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// TAB 3: LIVE DEPARTURES BOARD
// Expandable calling-point timeline, platform change highlights, occupancy
// ============================================================================
@Composable
private fun LiveDeparturesTabContent(
    snapshot: CommuteDashboardSnapshot,
    selectedRouteId: String?,
    nowEpochMs: Long,
    pulseAlpha: Float,
    onSelectRoute: (String) -> Unit,
    onOpenStation: (TransportStopEndpoint) -> Unit
) {
    val activeCard = remember(selectedRouteId, snapshot.allRouteCards, snapshot.primaryRouteCard) {
        snapshot.allRouteCards.firstOrNull { it.route.routeId == selectedRouteId }
            ?: snapshot.primaryRouteCard
            ?: snapshot.allRouteCards.firstOrNull()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(snapshot.allRouteCards, key = { it.route.routeId }) { card ->
                val isSel = card.route.routeId == activeCard?.route?.routeId
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSel) GhAccentGreen.copy(alpha = 0.2f) else GhTileSurface)
                        .border(
                            1.dp,
                            if (isSel) GhAccentGreen else GhCardBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelectRoute(card.route.routeId) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = iconForTransportMode(card.route.mode),
                        contentDescription = null,
                        tint = if (isSel) GhAccentGreen else GhTextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = card.route.routeName,
                        color = if (isSel) GhAccentGreen else GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 13.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (activeCard == null) {
            CommuteEmptyCard("Select or add a saved route to view live departures.")
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${activeCard.route.origin.compactDisplayName} → ${activeCard.route.destination.compactDisplayName}",
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = { onOpenStation(activeCard.route.origin) },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "Inspect ${activeCard.route.origin.name} Facilities",
                        color = GhAccentGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (activeCard.upcomingServices.isEmpty()) {
                CommuteEmptyCard("No live departures returned for ${activeCard.route.origin.name} right now.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(activeCard.upcomingServices, key = { it.serviceId }) { service ->
                        ExpandableDepartureBoardRow(
                            service = service,
                            nowEpochMs = nowEpochMs,
                            pulseAlpha = pulseAlpha
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableDepartureBoardRow(
    service: CommuteDepartureService,
    nowEpochMs: Long,
    pulseAlpha: Float
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GhTileSurface)
            .border(
                1.dp,
                when {
                    service.isCancelled -> Color(0xFFEF4444).copy(alpha = 0.7f)
                    service.platformChanged -> Color(0xFFF59E0B)
                    else -> GhCardBorder
                },
                RoundedCornerShape(14.dp)
            )
            .clickable { if (service.callingPoints.isNotEmpty()) expanded = !expanded }
            .padding(14.dp)
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
                Column {
                    Text(
                        text = service.scheduledDepartureTime,
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = if (service.isCancelled) {
                            "Cancelled"
                        } else {
                            formatDepartureCountdownLabel(service.expectedDepartureEpochMs, nowEpochMs)
                        },
                        color = if (service.isCancelled) Color(0xFFEF4444) else GhAccentGreen,
                        fontFamily = GhFont,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = service.destinationName,
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val arrSegment = if (service.scheduledArrivalTime.isNotBlank()) {
                        " • Arr ${service.scheduledArrivalTime}"
                    } else ""
                    val durSegment = if (service.journeyDurationMinutes > 0) {
                        " (${service.journeyDurationMinutes}m)"
                    } else ""
                    Text(
                        text = "${service.operatorOrLineName}$arrSegment$durSegment",
                        color = GhTextSecondary,
                        fontFamily = GhFont,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OccupancyPill(occupancy = service.occupancy)
                CommuteStatusBadge(
                    statusKind = service.statusKind,
                    delayMinutes = service.delayMinutes
                )
                PlatformBadgePill(
                    platform = service.displayPlatform,
                    platformChanged = service.platformChanged,
                    previousPlatform = service.previousPlatform,
                    pulseAlpha = pulseAlpha
                )
                if (service.callingPoints.isNotEmpty()) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Calling Points",
                        tint = GhTextSecondary
                    )
                }
            }
        }

        if (service.statusDetailReason.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = service.statusDetailReason,
                color = Color(0xFFF59E0B),
                fontFamily = GhFont,
                fontSize = 11.sp
            )
        }

        AnimatedVisibility(visible = expanded && service.callingPoints.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(GhSunkenWell)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = "Calling Points (${service.callingPoints.size} stops)",
                    color = GhTextSecondary,
                    fontFamily = GhFont,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                service.callingPoints.forEach { cp ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (cp.crsOrCode.isNotBlank()) "${cp.stationName} (${cp.crsOrCode})" else cp.stationName,
                            color = if (cp.isCancelled) Color(0xFFEF4444) else GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 12.sp
                        )
                        Text(
                            text = if (cp.isCancelled) {
                                "Cancelled"
                            } else if (cp.expectedTime != cp.scheduledTime) {
                                "${cp.scheduledTime} → ${cp.expectedTime}"
                            } else {
                                cp.scheduledTime
                            },
                            color = if (cp.expectedTime != cp.scheduledTime) Color(0xFFF97316) else GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// TAB 4: STATIONS PAGE
// Shows: liveDepartures, liveArrivals, parking, taxiAvailability, facilities, accessibility
// ============================================================================
@Composable
private fun StationsTabContent(
    repository: CommuteRepository,
    snapshot: CommuteDashboardSnapshot,
    selectedStation: TransportStopEndpoint?,
    pulseAlpha: Float,
    onSelectStation: (TransportStopEndpoint) -> Unit
) {
    val scope = rememberCoroutineScope()
    val savedStations = remember(snapshot.savedRoutes) {
        snapshot.savedRoutes
            .flatMap { listOf(it.origin, it.destination) }
            .distinctBy { it.id }
    }

    val activeStation = selectedStation ?: savedStations.firstOrNull()

    var searchQuery by remember { mutableStateOf("") }
    var searchCandidates by remember { mutableStateOf<List<TransportStopEndpoint>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    var loadedDetails by remember(activeStation?.id) {
        mutableStateOf(activeStation?.let { snapshot.stationDetailsByStopId[it.id] })
    }
    var isLoadingDetails by remember(activeStation?.id) { mutableStateOf(false) }

    LaunchedEffect(activeStation?.id) {
        val st = activeStation ?: return@LaunchedEffect
        val preloaded = snapshot.stationDetailsByStopId[st.id]
        if (preloaded != null) {
            loadedDetails = preloaded
        } else {
            isLoadingDetails = true
            loadedDetails = repository.loadStationFacilityDetails(st)
            isLoadingDetails = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            EndpointSearchBox(
                label = "Search Any UK Rail or TfL Station",
                placeholder = "Type station name (e.g. London Bridge, Gatwick Airport, Reading)...",
                query = searchQuery,
                selectedEndpoint = null,
                candidates = searchCandidates,
                isLoading = isSearching,
                onQueryChange = { q ->
                    searchQuery = q
                    searchJob?.cancel()
                    searchJob = scope.launch {
                        delay(280L)
                        if (q.trim().length >= 2) {
                            isSearching = true
                            searchCandidates = repository.searchStopsAndLocations(q, SupportedTransportMode.RAIL)
                            isSearching = false
                        } else {
                            searchCandidates = emptyList()
                        }
                    }
                },
                onSelectCandidate = { chosen ->
                    searchQuery = ""
                    searchCandidates = emptyList()
                    onSelectStation(chosen)
                },
                modifier = Modifier.weight(1f)
            )
        }

        if (savedStations.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(savedStations, key = { it.id }) { st ->
                    val isSel = st.id == activeStation?.id
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) GhAccentGreen else GhTileSurface)
                            .border(
                                1.dp,
                                if (isSel) GhAccentGreen else GhCardBorder,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectStation(st) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = st.compactDisplayName,
                            color = if (isSel) Color(0xFF101418) else GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoadingDetails && loadedDetails == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GhAccentGreen)
            }
        } else if (loadedDetails == null) {
            CommuteEmptyCard("Select or search a station above to inspect live departures, arrivals, parking, taxi ranks, facilities, and step-free accessibility.")
        } else {
            val details = loadedDetails!!
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // Station Facility Grid: Parking, Taxi Availability, Accessibility, Facilities
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StationMetaTile(
                            icon = Icons.Default.LocalParking,
                            title = "Parking",
                            detail = details.parkingSummary,
                            modifier = Modifier.weight(1f)
                        )
                        StationMetaTile(
                            icon = Icons.Default.LocalTaxi,
                            title = "Taxi Availability",
                            detail = details.taxiAvailability,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StationMetaTile(
                            icon = Icons.Default.Accessible,
                            title = "Accessibility",
                            detail = details.accessibilitySummary,
                            modifier = Modifier.weight(1f)
                        )
                        StationMetaTile(
                            icon = Icons.Default.Storefront,
                            title = "Station Facilities",
                            detail = details.facilities.joinToString(" • ").ifBlank { "Standard concourse facilities" },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Live Departures & Live Arrivals Side-by-Side
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CommuteSectionHeader("Live Departures (${details.liveDepartures.size})")
                            if (details.liveDepartures.isEmpty()) {
                                CommuteEmptyCard("No live departures listed.")
                            } else {
                                details.liveDepartures.take(8).forEach { dep ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(GhTileSurface)
                                            .border(1.dp, GhCardBorder, RoundedCornerShape(10.dp))
                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${dep.scheduledDepartureTime} → ${dep.destinationName}",
                                                color = GhTextPrimary,
                                                fontFamily = GhFont,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${dep.operatorOrLineName} • Exp ${dep.expectedDepartureTime}",
                                                color = GhTextSecondary,
                                                fontFamily = GhFont,
                                                fontSize = 11.sp
                                            )
                                        }
                                        PlatformBadgePill(
                                            platform = dep.displayPlatform,
                                            platformChanged = dep.platformChanged,
                                            pulseAlpha = pulseAlpha
                                        )
                                    }
                                }
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CommuteSectionHeader("Live Arrivals (${details.liveArrivals.size})")
                            if (details.liveArrivals.isEmpty()) {
                                CommuteEmptyCard("No live arrivals listed for this stop.")
                            } else {
                                details.liveArrivals.take(8).forEach { arr ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(GhTileSurface)
                                            .border(1.dp, GhCardBorder, RoundedCornerShape(10.dp))
                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${arr.scheduledArrivalTime} from ${arr.originName}",
                                                color = GhTextPrimary,
                                                fontFamily = GhFont,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${arr.operatorOrLineName} • ${arr.statusLabel}",
                                                color = GhTextSecondary,
                                                fontFamily = GhFont,
                                                fontSize = 11.sp
                                            )
                                        }
                                        PlatformBadgePill(
                                            platform = arr.platform,
                                            platformChanged = false
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

@Composable
private fun StationMetaTile(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(GhTileSurface)
            .border(1.dp, GhCardBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = GhAccentGreen,
            modifier = Modifier.size(20.dp)
        )
        Column {
            Text(
                text = title,
                color = GhTextPrimary,
                fontFamily = GhFont,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = detail,
                color = GhTextSecondary,
                fontFamily = GhFont,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ============================================================================
// TAB 5: DISRUPTION CENTRE
// Categories: Delays, Cancellations, Engineering Works, Service Suspensions, Heavy Traffic
// ============================================================================
@Composable
private fun DisruptionsTabContent(
    snapshot: CommuteDashboardSnapshot,
    onOpenJourneyPlanner: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf<DisruptionCategory?>(null) }

    val filtered = remember(snapshot.disruptions, selectedCategory) {
        if (selectedCategory == null) {
            snapshot.disruptions
        } else {
            snapshot.disruptions.filter { it.category == selectedCategory }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                val isAll = selectedCategory == null
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isAll) GhAccentGreen else GhTileSurface)
                        .border(1.dp, if (isAll) GhAccentGreen else GhCardBorder, RoundedCornerShape(10.dp))
                        .clickable { selectedCategory = null }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "All (${snapshot.disruptions.size})",
                        color = if (isAll) Color(0xFF101418) else GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            items(DisruptionCategory.entries) { category ->
                val selected = category == selectedCategory
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) GhAccentGreen else GhTileSurface)
                        .border(1.dp, if (selected) GhAccentGreen else GhCardBorder, RoundedCornerShape(10.dp))
                        .clickable { selectedCategory = category }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = category.label,
                        color = if (selected) Color(0xFF101418) else GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            CommuteEmptyCard("Good service — No active disruptions reported in this category.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filtered, key = { it.id }) { item ->
                    DisruptionDetailCard(
                        disruption = item,
                        onPlanAlternative = onOpenJourneyPlanner
                    )
                }
            }
        }
    }
}

@Composable
private fun DisruptionDetailCard(
    disruption: CommuteDisruptionItem,
    onPlanAlternative: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = when (disruption.category) {
        DisruptionCategory.CANCELLATIONS,
        DisruptionCategory.SERVICE_SUSPENSIONS -> Color(0xFFEF4444)
        else -> Color(0xFFF59E0B)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(GhTileSurface)
            .border(
                width = if (disruption.affectsSavedRoute) 1.5.dp else 1.dp,
                color = if (disruption.affectsSavedRoute) accent else GhCardBorder,
                shape = RoundedCornerShape(15.dp)
            )
            .padding(14.dp),
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
                Icon(
                    imageVector = iconForTransportMode(disruption.mode),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = disruption.lineOrRouteName,
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                if (disruption.affectsSavedRoute) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(accent.copy(alpha = 0.2f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "YOUR ROUTE",
                            color = accent,
                            fontFamily = GhFont,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            Text(
                text = "${disruption.category.label} • ${disruption.severityLabel}",
                color = accent,
                fontFamily = GhFont,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = disruption.summary,
            color = GhTextSecondary,
            fontFamily = GhFont,
            fontSize = 13.sp
        )

        if (disruption.alternativeRouteAdvice.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.AltRoute,
                        contentDescription = null,
                        tint = GhAccentGreen,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = disruption.alternativeRouteAdvice,
                        color = GhAccentGreen,
                        fontFamily = GhFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                TextButton(onClick = onPlanAlternative) {
                    Text(
                        text = "Re-Route →",
                        color = GhAccentGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// ============================================================================
// TAB 6: JOURNEY PLANNER
// Multi-Modal Options: Fastest Route, Cheapest Route, Fewest Changes, Earliest Arrival
// ============================================================================
@Composable
private fun JourneyPlannerTabContent(
    repository: CommuteRepository,
    snapshot: CommuteDashboardSnapshot
) {
    val scope = rememberCoroutineScope()
    val defaultRoute = snapshot.primaryRouteCard?.route ?: snapshot.savedRoutes.firstOrNull()

    var plannerMode by remember(defaultRoute?.mode) {
        mutableStateOf(defaultRoute?.mode ?: SupportedTransportMode.RAIL)
    }
    var originEndpoint by remember(defaultRoute?.origin) { mutableStateOf(defaultRoute?.origin) }
    var destEndpoint by remember(defaultRoute?.destination) { mutableStateOf(defaultRoute?.destination) }

    var originQuery by remember(defaultRoute?.origin) {
        mutableStateOf(defaultRoute?.origin?.compactDisplayName.orEmpty())
    }
    var destQuery by remember(defaultRoute?.destination) {
        mutableStateOf(defaultRoute?.destination?.compactDisplayName.orEmpty())
    }

    var originCandidates by remember { mutableStateOf<List<TransportStopEndpoint>>(emptyList()) }
    var destCandidates by remember { mutableStateOf<List<TransportStopEndpoint>>(emptyList()) }
    var searchingOrigin by remember { mutableStateOf(false) }
    var searchingDest by remember { mutableStateOf(false) }
    var originSearchJob by remember { mutableStateOf<Job?>(null) }
    var destSearchJob by remember { mutableStateOf<Job?>(null) }

    var journeyOptions by remember { mutableStateOf<List<PlannedJourneyOption>>(emptyList()) }
    var isPlanning by remember { mutableStateOf(false) }
    var selectedBadgeFilter by remember { mutableStateOf<JourneyOptionBadge?>(null) }

    fun executePlan() {
        val o = originEndpoint ?: return
        val d = destEndpoint ?: return
        scope.launch {
            isPlanning = true
            journeyOptions = repository.planJourneys(o, d, plannerMode)
            isPlanning = false
        }
    }

    LaunchedEffect(defaultRoute?.routeId) {
        if (originEndpoint != null && destEndpoint != null && journeyOptions.isEmpty()) {
            executePlan()
        }
    }

    val displayedOptions = remember(journeyOptions, selectedBadgeFilter) {
        val filter = selectedBadgeFilter ?: return@remember journeyOptions
        val matching = journeyOptions.filter { filter in it.badges }
        matching.ifEmpty { journeyOptions }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Mode Selector
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SupportedTransportMode.entries) { mode ->
                val selected = mode == plannerMode
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) GhAccentGreen else GhTileSurface)
                        .border(1.dp, if (selected) GhAccentGreen else GhCardBorder, RoundedCornerShape(10.dp))
                        .clickable { plannerMode = mode }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = iconForTransportMode(mode),
                        contentDescription = null,
                        tint = if (selected) Color(0xFF101418) else GhAccentGreen,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = mode.displayName,
                        color = if (selected) Color(0xFF101418) else GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Origin / Swap / Destination / Plan Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            EndpointSearchBox(
                label = "From",
                placeholder = "Origin station or place...",
                query = originQuery,
                selectedEndpoint = originEndpoint,
                candidates = originCandidates,
                isLoading = searchingOrigin,
                onQueryChange = { q ->
                    originQuery = q
                    originEndpoint = null
                    originSearchJob?.cancel()
                    originSearchJob = scope.launch {
                        delay(280L)
                        if (q.trim().length >= 2) {
                            searchingOrigin = true
                            originCandidates = repository.searchStopsAndLocations(q, plannerMode)
                            searchingOrigin = false
                        } else {
                            originCandidates = emptyList()
                        }
                    }
                },
                onSelectCandidate = { chosen ->
                    originEndpoint = chosen
                    originQuery = chosen.compactDisplayName
                    originCandidates = emptyList()
                },
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = {
                    val tmpEnd = originEndpoint
                    val tmpQ = originQuery
                    originEndpoint = destEndpoint
                    originQuery = destQuery
                    destEndpoint = tmpEnd
                    destQuery = tmpQ
                },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(GhTileSurfaceElevated)
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = "Swap Origin and Destination",
                    tint = GhAccentGreen
                )
            }

            EndpointSearchBox(
                label = "To",
                placeholder = "Destination station or place...",
                query = destQuery,
                selectedEndpoint = destEndpoint,
                candidates = destCandidates,
                isLoading = searchingDest,
                onQueryChange = { q ->
                    destQuery = q
                    destEndpoint = null
                    destSearchJob?.cancel()
                    destSearchJob = scope.launch {
                        delay(280L)
                        if (q.trim().length >= 2) {
                            searchingDest = true
                            destCandidates = repository.searchStopsAndLocations(q, plannerMode)
                            searchingDest = false
                        } else {
                            destCandidates = emptyList()
                        }
                    }
                },
                onSelectCandidate = { chosen ->
                    destEndpoint = chosen
                    destQuery = chosen.compactDisplayName
                    destCandidates = emptyList()
                },
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = { executePlan() },
                enabled = originEndpoint != null && destEndpoint != null && !isPlanning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GhAccentGreen,
                    contentColor = Color(0xFF101418)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .height(52.dp)
            ) {
                Text("Plan Journey", fontWeight = FontWeight.ExtraBold)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Option Ranking Filter Chips: All, Fastest, Cheapest, Fewest Changes, Earliest Arrival
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                val active = selectedBadgeFilter == null
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (active) GhTileSurfaceElevated else GhSunkenWell)
                        .border(1.dp, if (active) GhAccentGreen else GhCardBorder, RoundedCornerShape(9.dp))
                        .clickable { selectedBadgeFilter = null }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "All Options",
                        color = if (active) GhAccentGreen else GhTextSecondary,
                        fontFamily = GhFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            items(JourneyOptionBadge.entries) { badge ->
                val active = badge == selectedBadgeFilter
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (active) GhTileSurfaceElevated else GhSunkenWell)
                        .border(1.dp, if (active) GhAccentGreen else GhCardBorder, RoundedCornerShape(9.dp))
                        .clickable { selectedBadgeFilter = badge }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = badge.label,
                        color = if (active) GhAccentGreen else GhTextSecondary,
                        fontFamily = GhFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isPlanning) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GhAccentGreen)
            }
        } else if (displayedOptions.isEmpty()) {
            CommuteEmptyCard("Select Origin and Destination stops above and tap 'Plan Journey' to compare fastest, cheapest, fewest-change, and earliest-arrival routes.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(displayedOptions, key = { it.id }) { option ->
                    PlannedJourneyOptionCard(option = option)
                }
            }
        }
    }
}

@Composable
private fun PlannedJourneyOptionCard(option: PlannedJourneyOption) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GhTileSurface)
            .border(
                1.dp,
                if (JourneyOptionBadge.FASTEST_ROUTE in option.badges) GhAccentGreen.copy(alpha = 0.7f) else GhCardBorder,
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (option.badges.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                option.badges.forEach { badge ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(GhAccentGreen.copy(alpha = 0.18f))
                            .border(1.dp, GhAccentGreen.copy(alpha = 0.6f), RoundedCornerShape(7.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = badge.label.uppercase(Locale.UK),
                            color = GhAccentGreen,
                            fontFamily = GhFont,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${option.departureTime}  →  ${option.arrivalTime}",
                color = GhTextPrimary,
                fontFamily = GhFont,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${option.totalDurationMinutes} mins",
                    color = GhAccentGreen,
                    fontFamily = GhFont,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = if (option.changesCount == 0) "Direct" else "${option.changesCount} chg",
                    color = GhTextSecondary,
                    fontFamily = GhFont,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                option.estimatedFareGbp?.takeIf { it > 0.0 }?.let { fare ->
                    Text(
                        text = "£${"%.2f".format(Locale.UK, fare)}",
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(GhSunkenWell)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            option.legs.forEach { leg ->
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
                            imageVector = iconForTransportMode(leg.mode),
                            contentDescription = null,
                            tint = GhAccentGreen,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "${leg.departureTime} ${leg.fromStopName} → ${leg.toStopName} (${leg.lineOrInstruction})",
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "${leg.durationMinutes}m",
                        color = GhTextSecondary,
                        fontFamily = GhFont,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (option.disruptionNote.isNotBlank()) {
            Text(
                text = "⚠ ${option.disruptionNote}",
                color = Color(0xFFF59E0B),
                fontFamily = GhFont,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun CommuteSectionHeader(title: String) {
    Text(
        text = title,
        color = GhTextPrimary,
        fontFamily = GhFont,
        fontSize = 16.sp,
        fontWeight = FontWeight.ExtraBold
    )
}

@Composable
private fun CommuteEmptyCard(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GhTileSurface)
            .border(1.dp, GhCardBorder, RoundedCornerShape(14.dp))
            .padding(18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = GhTextSecondary,
            fontFamily = GhFont,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}