package com.jarvis.smarthomelauncher.widgets.football.Popup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Stadium
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.smarthomelauncher.SfHouseSunkenTileContainer
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.widgets.football.Models.*
import com.jarvis.smarthomelauncher.widgets.football.Repository.FootballRepository
import com.jarvis.smarthomelauncher.widgets.football.Services.FootballInsightRulesEngine
import com.jarvis.smarthomelauncher.widgets.football.Services.HybridLiveFootballDataProvider
import com.jarvis.smarthomelauncher.widgets.football.Settings.FootballSettingsContent
import com.jarvis.smarthomelauncher.widgets.football.Settings.FootballSettingsDialog
import com.jarvis.smarthomelauncher.widgets.football.Widget.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CWAS Rule 5 & Rule 6: Football Hub v3.0 Popup (95% width x 92% height).
 * Opens as an overlay Dialog keeping the Clara dashboard visible behind it.
 */
@Composable
fun FootballHubPopup(
    repository: FootballRepository,
    initialTab: FootballHubTab = FootballHubTab.OVERVIEW,
    initialFixture: FootballFixture? = null,
    onDismiss: () -> Unit
) {
    val uiState by repository.uiState.collectAsState()
    val context = LocalContext.current

    var activeTab by remember(initialTab) { mutableStateOf(initialTab) }
    var selectedCompetitionId by remember { mutableStateOf<String?>(null) }
    var selectedTeamFilterId by remember { mutableStateOf<String?>(null) }
    var selectedFixtureForDetail by remember(initialFixture) { mutableStateOf(initialFixture) }
    var showSettingsModal by remember { mutableStateOf(false) }

    var voiceInputQuery by remember { mutableStateOf("") }
    var showVoiceBar by remember { mutableStateOf(false) }
    var voiceAnswerBanner by remember { mutableStateOf<String?>(null) }

    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            nowEpochMs = System.currentTimeMillis()
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
        SfHouseSunkenTileContainer(
            outerCornerRadius = 28.dp,
            innerCornerRadius = 22.dp,
            sunkenInsetPadding = 4.dp,
            innerBackgroundColor = GhBackground,
            innerBorderColor = GhCardBorder,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 18.dp)
            ) {
                // ============================================================
                // TOP BAR: Hub Title, Live Indicator, Voice Ask, Refresh, Settings, Close
                // ============================================================
                val readySnap = (uiState as? FootballWidgetUiState.Ready)?.snapshot
                FootballHubTopBar(
                    snapshot = readySnap,
                    onToggleVoiceBar = { showVoiceBar = !showVoiceBar },
                    onRefresh = { repository.triggerRefresh(forceRemote = true) },
                    onOpenSettings = { showSettingsModal = true },
                    onDismiss = onDismiss
                )

                if (showVoiceBar) {
                    Spacer(modifier = Modifier.height(10.dp))
                    FootballVoiceCommandBar(
                        query = voiceInputQuery,
                        answerBanner = voiceAnswerBanner,
                        onQueryChange = { voiceInputQuery = it },
                        onRunQuery = { spoken ->
                            val resp = repository.handleVoiceCommand(spoken)
                            voiceAnswerBanner = if (resp.handled) {
                                resp.spokenText
                            } else {
                                "Try: 'When does Arsenal play next', 'Show Premier League table', 'What channel is the Liverpool match on', or 'Show live scores'."
                            }
                            resp.targetTab?.let { activeTab = it }
                            resp.targetCompetitionId?.let { selectedCompetitionId = it }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ============================================================
                // 8-TAB NAVIGATION BAR
                // ============================================================
                FootballHubTabsRow(
                    activeTab = activeTab,
                    liveMatchCount = readySnap?.liveMatches?.size ?: 0,
                    onSelectTab = { activeTab = it }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // ============================================================
                // TAB CONTENT BY CORE STATE (CWAS Rule 11)
                // ============================================================
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (val state = uiState) {
                        is FootballWidgetUiState.Loading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = GhAccentGreen)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Loading live fixtures, standings & club feeds...",
                                        color = GhTextSecondary,
                                        fontFamily = GhFont,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }

                        is FootballWidgetUiState.Empty -> {
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
                                        text = "Welcome to Football Hub v3.0 — Add up to 10 favourite clubs below to unlock live match centre, broadcast schedules, league context, squads, and news.",
                                        color = GhTextPrimary,
                                        fontFamily = GhFont,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                FootballSettingsContent(
                                    repository = repository,
                                    favouriteTeams = emptyList(),
                                    preferences = repository.getPreferencesSync(),
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        is FootballWidgetUiState.Error -> {
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
                                        fontSize = 12.sp
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

                        is FootballWidgetUiState.Ready -> {
                            val snap = state.snapshot
                            when (activeTab) {
                                FootballHubTab.OVERVIEW -> OverviewTabContent(
                                    snapshot = snap,
                                    nowEpochMs = nowEpochMs,
                                    onSelectFixture = { selectedFixtureForDetail = it },
                                    onSelectTeam = { teamId ->
                                        selectedTeamFilterId = teamId
                                        activeTab = FootballHubTab.PLAYERS
                                    },
                                    onOpenTables = { compId ->
                                        selectedCompetitionId = compId
                                        activeTab = FootballHubTab.TABLES
                                    }
                                )

                                FootballHubTab.FIXTURES -> FixturesTabContent(
                                    snapshot = snap,
                                    nowEpochMs = nowEpochMs,
                                    onSelectFixture = { selectedFixtureForDetail = it }
                                )

                                FootballHubTab.LIVE -> LiveMatchCentreTabContent(
                                    snapshot = snap,
                                    onSelectFixture = { selectedFixtureForDetail = it },
                                    onWatchChannel = { ch -> launchBroadcasterChannel(context, ch) }
                                )

                                FootballHubTab.TABLES -> TablesTabContent(
                                    snapshot = snap,
                                    selectedCompetitionId = selectedCompetitionId,
                                    onSelectCompetition = { selectedCompetitionId = it }
                                )

                                FootballHubTab.TEAMS -> TeamsTabContent(
                                    snapshot = snap,
                                    nowEpochMs = nowEpochMs,
                                    onSelectFixture = { selectedFixtureForDetail = it },
                                    onOpenTeamSquad = { teamId ->
                                        selectedTeamFilterId = teamId
                                        activeTab = FootballHubTab.PLAYERS
                                    },
                                    onManageTeams = { showSettingsModal = true }
                                )

                                FootballHubTab.COMPETITIONS -> CompetitionsTabContent(
                                    snapshot = snap,
                                    nowEpochMs = nowEpochMs,
                                    selectedCompetitionId = selectedCompetitionId,
                                    onSelectCompetition = { selectedCompetitionId = it },
                                    onSelectFixture = { selectedFixtureForDetail = it }
                                )

                                FootballHubTab.PLAYERS -> PlayersTabContent(
                                    snapshot = snap,
                                    selectedTeamId = selectedTeamFilterId,
                                    selectedCompetitionId = selectedCompetitionId,
                                    onSelectTeam = { selectedTeamFilterId = it },
                                    onSelectCompetition = { selectedCompetitionId = it }
                                )

                                FootballHubTab.NEWS -> NewsTabContent(
                                    snapshot = snap,
                                    onOpenArticle = { article ->
                                        openExternalUrlSafely(context, article.articleUrl)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ====================================================================
        // FIXTURE DETAILS DRILL-DOWN POPUP
        // ====================================================================
        val detailFixture = selectedFixtureForDetail
        if (detailFixture != null) {
            FixtureDetailsPopupDialog(
                repository = repository,
                fixture = detailFixture,
                onDismiss = { selectedFixtureForDetail = null },
                onViewCompetition = { compId ->
                    selectedFixtureForDetail = null
                    selectedCompetitionId = compId
                    activeTab = FootballHubTab.COMPETITIONS
                },
                onViewTeam = { clubName ->
                    val readySnap = (uiState as? FootballWidgetUiState.Ready)?.snapshot
                    val matchedFav = readySnap?.favouriteTeams?.firstOrNull {
                        HybridLiveFootballDataProvider.clubsMatch(it.name, clubName)
                    }
                    selectedFixtureForDetail = null
                    if (matchedFav != null) {
                        selectedTeamFilterId = matchedFav.teamId
                        activeTab = FootballHubTab.PLAYERS
                    } else {
                        activeTab = FootballHubTab.TEAMS
                    }
                },
                onWatchChannel = { channel ->
                    launchBroadcasterChannel(context, channel)
                }
            )
        }

        // ====================================================================
        // SETTINGS DIALOG
        // ====================================================================
        if (showSettingsModal) {
            val readySnap = (uiState as? FootballWidgetUiState.Ready)?.snapshot
            FootballSettingsDialog(
                repository = repository,
                favouriteTeams = readySnap?.favouriteTeams.orEmpty(),
                preferences = readySnap?.preferences ?: repository.getPreferencesSync(),
                onDismiss = { showSettingsModal = false }
            )
        }
    }
}

@Composable
private fun FootballHubTopBar(
    snapshot: FootballDashboardSnapshot?,
    onToggleVoiceBar: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
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
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GhTileSurfaceElevated)
                    .border(1.dp, GhCardBorder, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SportsSoccer,
                    contentDescription = null,
                    tint = GhAccentGreen,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Football Hub",
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    snapshot?.displayMode?.let { mode ->
                        val (label, tint) = when (mode) {
                            FootballWidgetDisplayMode.LIVE -> "LIVE MODE" to Color(0xFFEF4444)
                            FootballWidgetDisplayMode.MATCH_DAY -> "MATCH DAY" to GhAccentWarm
                            FootballWidgetDisplayMode.NORMAL -> "ACTIVE" to GhAccentGreen
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(tint.copy(alpha = 0.16f))
                                .border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(7.dp))
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
                    text = snapshot?.activeProviderLabel ?: "Clara Live Football Intelligence",
                    color = GhTextSecondary,
                    fontFamily = GhFont,
                    fontSize = 12.sp
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HubActionIconButton(
                icon = Icons.Default.Mic,
                contentDescription = "Ask Clara Football",
                onClick = onToggleVoiceBar
            )
            HubActionIconButton(
                icon = Icons.Default.Refresh,
                contentDescription = "Refresh Live Feeds",
                onClick = onRefresh
            )
            HubActionIconButton(
                icon = Icons.Default.Settings,
                contentDescription = "Football Hub Settings",
                onClick = onOpenSettings
            )
            HubActionIconButton(
                icon = Icons.Default.Close,
                contentDescription = "Close Football Hub",
                onClick = onDismiss
            )
        }
    }
}

@Composable
private fun HubActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
private fun FootballVoiceCommandBar(
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
                        text = "Ask Clara (e.g. 'When does Arsenal play next', 'Show Premier League table', 'Show live scores')",
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
                "Show live scores",
                "Show today's matches",
                "Show Premier League table",
                "Has the lineup been announced"
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
private fun FootballHubTabsRow(
    activeTab: FootballHubTab,
    liveMatchCount: Int,
    onSelectTab: (FootballHubTab) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(FootballHubTab.entries) { tab ->
            val isSelected = tab == activeTab
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) GhAccentGreen else GhTileSurface)
                    .border(
                        width = 1.dp,
                        color = if (isSelected) GhAccentGreen else GhCardBorder,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelectTab(tab) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = tab.title,
                    color = if (isSelected) Color(0xFF101418) else GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold
                )
                if (tab == FootballHubTab.LIVE && liveMatchCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isSelected) Color(0xFF101418) else Color(0xFFEF4444))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = liveMatchCount.toString(),
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
// Sections: Favourite Teams, Next Match, Live Matches, Current Form,
//           League Position, Upcoming Fixtures (+ RulesEngine Insights)
// ============================================================================
@Composable
private fun OverviewTabContent(
    snapshot: FootballDashboardSnapshot,
    nowEpochMs: Long,
    onSelectFixture: (FootballFixture) -> Unit,
    onSelectTeam: (String) -> Unit,
    onOpenTables: (String) -> Unit
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
                    items(snapshot.insights) { insight ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(GhTileSurface)
                                .border(1.dp, GhAccentGreen.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = GhAccentGreen,
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

        // Section 1: Favourite Teams
        item {
            HubSectionHeader(title = "Favourite Teams")
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(snapshot.teamCards) { card ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(GhTileSurface)
                            .border(
                                1.dp,
                                if (card.favouriteEntry.isPrimaryFavourite) GhAccentGreen else GhCardBorder,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { onSelectTeam(card.favouriteEntry.teamId) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FootballClubBadge(
                            badgeUrl = card.favouriteEntry.badgeUrl,
                            clubName = card.favouriteEntry.shortName,
                            primaryColorHex = card.favouriteEntry.primaryColorHex,
                            size = 36.dp
                        )
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = card.favouriteEntry.shortName,
                                    color = GhTextPrimary,
                                    fontFamily = GhFont,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (card.favouriteEntry.isPrimaryFavourite) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = Color(0xFFFBBF24),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Text(
                                text = card.standing?.let {
                                    "${FootballInsightRulesEngine.ordinal(it.position)} • ${it.points} pts"
                                } ?: SupportedCompetition.fromIdOrName(card.favouriteEntry.primaryCompetitionId)?.displayName.orEmpty(),
                                color = GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // Section 2 & 4 & 5: Next Match + Current Form & League Position Side-by-Side
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(modifier = Modifier.weight(1.35f)) {
                    HubSectionHeader(title = "Next Match")
                    Spacer(modifier = Modifier.height(8.dp))
                    val hero = snapshot.heroFixture
                    if (hero != null) {
                        HubFixtureCard(
                            fixture = hero,
                            nowEpochMs = nowEpochMs,
                            onClick = { onSelectFixture(hero) }
                        )
                    } else {
                        EmptySectionCard("No upcoming fixture scheduled.")
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HubSectionHeader(title = "League Position & Current Form")
                    val leagueSnap = snapshot.primaryLeagueSnapshot
                    if (leagueSnap != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(GhTileSurface)
                                .border(1.dp, GhCardBorder, RoundedCornerShape(16.dp))
                                .clickable { onOpenTables(leagueSnap.competitionId) }
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = leagueSnap.teamName,
                                        color = GhTextPrimary,
                                        fontFamily = GhFont,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${leagueSnap.competitionName} • ${leagueSnap.zone.label}",
                                        color = GhTextSecondary,
                                        fontFamily = GhFont,
                                        fontSize = 12.sp
                                    )
                                }
                                Text(
                                    text = FootballInsightRulesEngine.ordinal(leagueSnap.position),
                                    color = GhAccentGreen,
                                    fontFamily = GhFont,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                StatMetricChip("Points", "${leagueSnap.points}")
                                StatMetricChip("Played", "${leagueSnap.gamesPlayed}")
                                StatMetricChip(
                                    "Goal Diff",
                                    if (leagueSnap.goalDifference >= 0) "+${leagueSnap.goalDifference}" else "${leagueSnap.goalDifference}"
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Current Form (Last 5)",
                                    color = GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (leagueSnap.recentForm.isNotEmpty()) {
                                    FootballFormPillsRow(form = leagueSnap.recentForm, pillSize = 22.dp)
                                } else {
                                    Text("N/A", color = GhTextSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        EmptySectionCard("Standings data unavailable for selected competition.")
                    }
                }
            }
        }

        // Section 3: Live Matches
        item {
            HubSectionHeader(title = "Live Matches (${snapshot.liveMatches.size})")
            Spacer(modifier = Modifier.height(8.dp))
            if (snapshot.liveMatches.isEmpty()) {
                EmptySectionCard("No matches live right now across your tracked competitions.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    snapshot.liveMatches.take(4).forEach { live ->
                        HubFixtureCard(
                            fixture = live,
                            nowEpochMs = nowEpochMs,
                            onClick = { onSelectFixture(live) }
                        )
                    }
                }
            }
        }

        // Section 6: Upcoming Fixtures
        item {
            HubSectionHeader(title = "Upcoming Fixtures")
            Spacer(modifier = Modifier.height(8.dp))
            if (snapshot.upcomingFixtures.isEmpty()) {
                EmptySectionCard("No additional upcoming fixtures in the 30-day window.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    snapshot.upcomingFixtures.forEach { fix ->
                        HubFixtureCard(
                            fixture = fix,
                            nowEpochMs = nowEpochMs,
                            onClick = { onSelectFixture(fix) }
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// TAB 2: FIXTURES PAGE
// Grouping: Today, Tomorrow, This Week, Next Week, This Month
// Card shows: teamBadges, teams, stadium, kickoff, competition, channel, countdown, lineupStatus
// ============================================================================
@Composable
private fun FixturesTabContent(
    snapshot: FootballDashboardSnapshot,
    nowEpochMs: Long,
    onSelectFixture: (FootballFixture) -> Unit
) {
    var favOnly by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tap any fixture for broadcast platforms, formations, lineups, table context & head-to-head",
                color = GhTextSecondary,
                fontFamily = GhFont,
                fontSize = 12.sp
            )
            FilterChip(
                selected = favOnly,
                onClick = { favOnly = !favOnly },
                label = { Text("Favourite Teams Only", fontSize = 12.sp) }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        val grouped = remember(snapshot.allGroupedFixtures, favOnly) {
            if (!favOnly) {
                snapshot.allGroupedFixtures
            } else {
                snapshot.allGroupedFixtures
                    .mapValues { (_, list) -> list.filter { it.involvesFavouriteTeam } }
                    .filterValues { it.isNotEmpty() }
            }
        }

        if (grouped.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptySectionCard("No fixtures match the current filter in the next 30 days.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                grouped.forEach { (group, fixtures) ->
                    item(key = "hdr_${group.name}") {
                        HubSectionHeader(title = "${group.title} (${fixtures.size})")
                    }
                    items(fixtures, key = { it.id }) { fix ->
                        HubFixtureCard(
                            fixture = fix,
                            nowEpochMs = nowEpochMs,
                            onClick = { onSelectFixture(fix) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HubFixtureCard(
    fixture: FootballFixture,
    nowEpochMs: Long,
    onClick: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val zdt = Instant.ofEpochMilli(fixture.kickoffEpochMs).atZone(zone)
    val dateKickoff = zdt.format(DateTimeFormatter.ofPattern("EEE dd MMM • HH:mm", Locale.UK))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GhTileSurface)
            .border(
                width = 1.dp,
                color = when {
                    fixture.status.isLive -> Color(0xFFEF4444)
                    fixture.involvesPrimaryFavourite -> GhAccentGreen.copy(alpha = 0.7f)
                    else -> GhCardBorder
                },
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onClick() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
                    text = fixture.competitionName,
                    color = GhAccentGreen,
                    fontFamily = GhFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• $dateKickoff",
                    color = GhTextSecondary,
                    fontFamily = GhFont,
                    fontSize = 12.sp
                )
            }
            TvChannelPill(channel = fixture.broadcast.displayChannel)
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
                FootballClubBadge(
                    badgeUrl = fixture.homeTeam.badgeUrl,
                    clubName = fixture.homeTeam.shortName,
                    primaryColorHex = fixture.homeTeam.primaryColorHex,
                    size = 38.dp
                )
                Text(
                    text = fixture.homeTeam.name,
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(GhSunkenWell)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (fixture.status.isLive || fixture.status.isFinished) {
                        "${fixture.homeScore ?: 0} - ${fixture.awayScore ?: 0} (${fixture.formattedLiveMinute})"
                    } else {
                        "VS"
                    },
                    color = if (fixture.status.isLive) Color(0xFFEF4444) else GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = fixture.awayTeam.name,
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End
                )
                FootballClubBadge(
                    badgeUrl = fixture.awayTeam.badgeUrl,
                    clubName = fixture.awayTeam.shortName,
                    primaryColorHex = fixture.awayTeam.primaryColorHex,
                    size = 38.dp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (fixture.stadiumName.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stadium,
                            contentDescription = null,
                            tint = GhTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = fixture.stadiumName,
                            color = GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 12.sp
                        )
                    }
                }
                if (!fixture.status.isLive && !fixture.status.isFinished) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = GhAccentWarm,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = formatCountdownLabel(fixture.kickoffEpochMs, nowEpochMs),
                            color = GhAccentWarm,
                            fontFamily = GhFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            LineupStatusBadge(state = fixture.lineupState)
        }
    }
}

// ============================================================================
// TAB 3: LIVE MATCH CENTRE
// Shows: matchMinute, score, goalscorers, yellowCards, redCards, substitutions, channel
// ============================================================================
@Composable
private fun LiveMatchCentreTabContent(
    snapshot: FootballDashboardSnapshot,
    onSelectFixture: (FootballFixture) -> Unit,
    onWatchChannel: (String) -> Unit
) {
    if (snapshot.liveMatches.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.SportsSoccer,
                contentDescription = null,
                tint = GhTextSecondary,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "No Live Matches Right Now",
                color = GhTextPrimary,
                fontFamily = GhFont,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Live Match Centre activates automatically at kickoff with real-time goals, cards, and substitutions.",
                color = GhTextSecondary,
                fontFamily = GhFont,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(snapshot.liveMatches, key = { it.id }) { match ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(GhTileSurface)
                        .border(1.5.dp, Color(0xFFEF4444).copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                        .clickable { onSelectFixture(match) }
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFEF4444))
                                    .padding(horizontal = 9.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = match.formattedLiveMinute,
                                    color = Color.White,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Text(
                                text = match.competitionName,
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(GhTileSurfaceElevated)
                                .border(1.dp, GhAccentGreen, RoundedCornerShape(8.dp))
                                .clickable { onWatchChannel(match.broadcast.displayChannel) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LiveTv,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Watch ${match.broadcast.displayChannel}",
                                color = GhAccentGreen,
                                fontFamily = GhFont,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
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
                            FootballClubBadge(
                                badgeUrl = match.homeTeam.badgeUrl,
                                clubName = match.homeTeam.shortName,
                                primaryColorHex = match.homeTeam.primaryColorHex,
                                size = 44.dp
                            )
                            Text(
                                text = match.homeTeam.name,
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "${match.homeScore ?: 0}  -  ${match.awayScore ?: 0}",
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = match.awayTeam.name,
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.End
                            )
                            FootballClubBadge(
                                badgeUrl = match.awayTeam.badgeUrl,
                                clubName = match.awayTeam.shortName,
                                primaryColorHex = match.awayTeam.primaryColorHex,
                                size = 44.dp
                            )
                        }
                    }

                    HorizontalDivider(color = GhCardBorder)

                    LiveEventBreakdownRow(
                        label = "⚽ Goalscorers",
                        items = match.goalscorers.map {
                            "${it.primaryPlayerName} (${it.formattedMinute})"
                        }
                    )
                    LiveEventBreakdownRow(
                        label = "🟨 Yellow Cards",
                        items = match.yellowCards.map {
                            "${it.primaryPlayerName} (${it.formattedMinute})"
                        }
                    )
                    LiveEventBreakdownRow(
                        label = "🟥 Red Cards",
                        items = match.redCards.map {
                            "${it.primaryPlayerName} (${it.formattedMinute})"
                        }
                    )
                    LiveEventBreakdownRow(
                        label = "🔄 Substitutions",
                        items = match.substitutions.map {
                            val subOff = it.secondaryPlayerName?.let { off -> " for $off" }.orEmpty()
                            "${it.primaryPlayerName}$subOff (${it.formattedMinute})"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveEventBreakdownRow(label: String, items: List<String>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = GhTextSecondary,
            fontFamily = GhFont,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(135.dp)
        )
        Text(
            text = if (items.isEmpty()) "None" else items.joinToString(" • "),
            color = if (items.isEmpty()) GhTextSecondary else GhTextPrimary,
            fontFamily = GhFont,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

// ============================================================================
// TAB 4: TABLES PAGE
// All 10 columns + Promotion / European / Relegation zones + Highlight Favourites
// ============================================================================
@Composable
private fun TablesTabContent(
    snapshot: FootballDashboardSnapshot,
    selectedCompetitionId: String?,
    onSelectCompetition: (String) -> Unit
) {
    val availableComps = remember(snapshot.standingsByCompetition, snapshot.preferences) {
        val favIds = snapshot.preferences.favouriteCompetitionIds
        snapshot.standingsByCompetition.keys
            .mapNotNull { SupportedCompetition.fromIdOrName(it) }
            .sortedWith(
                compareByDescending<SupportedCompetition> { it.id in favIds }
                    .thenBy { it.ordinal }
            )
    }

    val activeComp = remember(selectedCompetitionId, availableComps) {
        availableComps.firstOrNull { it.id == selectedCompetitionId }
            ?: availableComps.firstOrNull()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(availableComps) { comp ->
                val selected = comp.id == activeComp?.id
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) GhAccentGreen.copy(alpha = 0.2f) else GhTileSurface)
                        .border(
                            1.dp,
                            if (selected) GhAccentGreen else GhCardBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelectCompetition(comp.id) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = comp.displayName,
                        color = if (selected) GhAccentGreen else GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        ZoneLegendStrip()

        Spacer(modifier = Modifier.height(10.dp))

        val rows = activeComp?.let { snapshot.standingsByCompetition[it.id] }.orEmpty()
        if (rows.isEmpty()) {
            EmptySectionCard("No league standings available for this competition.")
        } else {
            FullLeagueTableComponent(
                rows = rows,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ZoneLegendStrip() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ZoneLegendDot("Champions League / Promotion", Color(0xFF22C55E))
        ZoneLegendDot("Europa / Conference League", Color(0xFF3B82F6))
        ZoneLegendDot("Play-Off Places", Color(0xFFF59E0B))
        ZoneLegendDot("Relegation Zone", Color(0xFFEF4444))
    }
}

@Composable
private fun ZoneLegendDot(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            color = GhTextSecondary,
            fontFamily = GhFont,
            fontSize = 11.sp
        )
    }
}

@Composable
internal fun FullLeagueTableComponent(
    rows: List<LeagueStandingRow>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(GhTileSurface)
            .border(1.dp, GhCardBorder, RoundedCornerShape(16.dp))
    ) {
        // Header Row with all 10 required columns + Form
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(GhSunkenWell)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TableHeaderCell("Pos", Modifier.width(42.dp), TextAlign.Center)
            TableHeaderCell("Team", Modifier.weight(1f), TextAlign.Start)
            TableHeaderCell("P", Modifier.width(36.dp), TextAlign.Center)
            TableHeaderCell("W", Modifier.width(36.dp), TextAlign.Center)
            TableHeaderCell("D", Modifier.width(36.dp), TextAlign.Center)
            TableHeaderCell("L", Modifier.width(36.dp), TextAlign.Center)
            TableHeaderCell("GF", Modifier.width(40.dp), TextAlign.Center)
            TableHeaderCell("GA", Modifier.width(40.dp), TextAlign.Center)
            TableHeaderCell("GD", Modifier.width(44.dp), TextAlign.Center)
            TableHeaderCell("Pts", Modifier.width(46.dp), TextAlign.Center)
            TableHeaderCell("Form", Modifier.width(124.dp), TextAlign.End)
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(rows, key = { "${it.competitionId}_${it.position}_${it.team.id}" }) { row ->
                val zoneColor = when (row.zone) {
                    StandingZone.CHAMPIONS_LEAGUE,
                    StandingZone.PROMOTION -> Color(0xFF22C55E)
                    StandingZone.EUROPEAN -> Color(0xFF3B82F6)
                    StandingZone.PLAYOFF -> Color(0xFFF59E0B)
                    StandingZone.RELEGATION -> Color(0xFFEF4444)
                    StandingZone.NONE -> Color.Transparent
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (row.isFavouriteTeam) GhAccentGreen.copy(alpha = 0.14f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.width(42.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(22.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(zoneColor)
                        )
                        Text(
                            text = "${row.position}",
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FootballClubBadge(
                            badgeUrl = row.team.badgeUrl,
                            clubName = row.team.shortName,
                            primaryColorHex = row.team.primaryColorHex,
                            size = 26.dp
                        )
                        Text(
                            text = row.team.name,
                            color = if (row.isFavouriteTeam) GhAccentGreen else GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 13.sp,
                            fontWeight = if (row.isFavouriteTeam) FontWeight.ExtraBold else FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    TableValueCell("${row.played}", Modifier.width(36.dp))
                    TableValueCell("${row.won}", Modifier.width(36.dp))
                    TableValueCell("${row.drawn}", Modifier.width(36.dp))
                    TableValueCell("${row.lost}", Modifier.width(36.dp))
                    TableValueCell("${row.goalsFor}", Modifier.width(40.dp))
                    TableValueCell("${row.goalsAgainst}", Modifier.width(40.dp))
                    TableValueCell(
                        if (row.goalDifference >= 0) "+${row.goalDifference}" else "${row.goalDifference}",
                        Modifier.width(44.dp)
                    )
                    Text(
                        text = "${row.points}",
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(46.dp)
                    )

                    Box(
                        modifier = Modifier.width(124.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        if (row.recentForm.isNotEmpty()) {
                            FootballFormPillsRow(form = row.recentForm, pillSize = 18.dp)
                        } else {
                            Text("-", color = GhTextSecondary, fontSize = 12.sp)
                        }
                    }
                }
                HorizontalDivider(color = GhCardBorder.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun TableHeaderCell(text: String, modifier: Modifier, align: TextAlign) {
    Text(
        text = text,
        color = GhTextSecondary,
        fontFamily = GhFont,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        textAlign = align,
        modifier = modifier
    )
}

@Composable
private fun TableValueCell(text: String, modifier: Modifier) {
    Text(
        text = text,
        color = GhTextSecondary,
        fontFamily = GhFont,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = modifier
    )
}

// ============================================================================
// TAB 5: TEAMS PAGE (Adaptive Grid)
// Card shows: badge, name, position, points, form, nextFixture
// ============================================================================
@Composable
private fun TeamsTabContent(
    snapshot: FootballDashboardSnapshot,
    nowEpochMs: Long,
    onSelectFixture: (FootballFixture) -> Unit,
    onOpenTeamSquad: (String) -> Unit,
    onManageTeams: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tracked Favourite Clubs (${snapshot.teamCards.size}/${snapshot.preferences.maximumTeams})",
                color = GhTextPrimary,
                fontFamily = GhFont,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = onManageTeams,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GhTileSurfaceElevated,
                    contentColor = GhAccentGreen
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Manage Clubs", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 310.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(snapshot.teamCards, key = { it.favouriteEntry.teamId }) { card ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GhTileSurface)
                        .border(
                            1.dp,
                            if (card.favouriteEntry.isPrimaryFavourite) GhAccentGreen else GhCardBorder,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable { onOpenTeamSquad(card.favouriteEntry.teamId) }
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
                            FootballClubBadge(
                                badgeUrl = card.favouriteEntry.badgeUrl,
                                clubName = card.favouriteEntry.shortName,
                                primaryColorHex = card.favouriteEntry.primaryColorHex,
                                size = 42.dp
                            )
                            Column {
                                Text(
                                    text = card.favouriteEntry.name,
                                    color = GhTextPrimary,
                                    fontFamily = GhFont,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = SupportedCompetition.fromIdOrName(card.favouriteEntry.primaryCompetitionId)?.displayName.orEmpty(),
                                    color = GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        card.standing?.let { st ->
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = FootballInsightRulesEngine.ordinal(st.position),
                                    color = GhAccentGreen,
                                    fontFamily = GhFont,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "${st.points} pts",
                                    color = GhTextPrimary,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Recent Form", color = GhTextSecondary, fontSize = 12.sp)
                        if (card.recentForm.isNotEmpty()) {
                            FootballFormPillsRow(form = card.recentForm, pillSize = 19.dp)
                        } else {
                            Text("No recent form", color = GhTextSecondary, fontSize = 11.sp)
                        }
                    }

                    val next = card.liveFixture ?: card.nextFixture
                    if (next != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(GhSunkenWell)
                                .clickable { onSelectFixture(next) }
                                .padding(10.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = if (next.status.isLive) {
                                        "LIVE: ${next.homeTeam.shortName} ${next.homeScore ?: 0}-${next.awayScore ?: 0} ${next.awayTeam.shortName}"
                                    } else {
                                        "Next: ${next.homeTeam.shortName} vs ${next.awayTeam.shortName}"
                                    },
                                    color = GhTextPrimary,
                                    fontFamily = GhFont,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${formatKickoffDayShort(next.kickoffEpochMs, nowEpochMs)} ${formatKickoffTime24h(next.kickoffEpochMs)} • ${next.broadcast.displayChannel}",
                                    color = GhAccentGreen,
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
}

// ============================================================================
// TAB 6: COMPETITIONS PAGE
// Favourite competitions first; Sections: Table, Fixtures, Results, Top Scorers, Form Table
// ============================================================================
@Composable
private fun CompetitionsTabContent(
    snapshot: FootballDashboardSnapshot,
    nowEpochMs: Long,
    selectedCompetitionId: String?,
    onSelectCompetition: (String) -> Unit,
    onSelectFixture: (FootballFixture) -> Unit
) {
    val orderedCompetitions = remember(snapshot.preferences.favouriteCompetitionIds) {
        val favSet = snapshot.preferences.favouriteCompetitionIds.toSet()
        SupportedCompetition.entries.sortedWith(
            compareByDescending<SupportedCompetition> { it.id in favSet }
                .thenBy { it.ordinal }
        )
    }

    val activeComp = remember(selectedCompetitionId, orderedCompetitions) {
        orderedCompetitions.firstOrNull { it.id == selectedCompetitionId }
            ?: orderedCompetitions.first()
    }

    var subSection by remember { mutableStateOf(CompetitionSubSection.TABLE) }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(orderedCompetitions) { comp ->
                val isSelected = comp.id == activeComp.id
                val isFav = comp.id in snapshot.preferences.favouriteCompetitionIds
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) GhAccentGreen.copy(alpha = 0.2f) else GhTileSurface)
                        .border(
                            1.dp,
                            if (isSelected) GhAccentGreen else GhCardBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelectCompetition(comp.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (isFav) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    Text(
                        text = comp.displayName,
                        color = if (isSelected) GhAccentGreen else GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompetitionSubSection.entries.forEach { sec ->
                val selected = sec == subSection
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) GhTileSurfaceElevated else GhSunkenWell)
                        .border(
                            1.dp,
                            if (selected) GhAccentGreen else GhCardBorder,
                            RoundedCornerShape(9.dp)
                        )
                        .clickable { subSection = sec }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = sec.title,
                        color = if (selected) GhAccentGreen else GhTextSecondary,
                        fontFamily = GhFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (subSection) {
            CompetitionSubSection.TABLE -> {
                val rows = snapshot.standingsByCompetition[activeComp.id].orEmpty()
                if (rows.isEmpty()) {
                    EmptySectionCard("No standings table published for ${activeComp.displayName}.")
                } else {
                    FullLeagueTableComponent(rows = rows, modifier = Modifier.fillMaxSize())
                }
            }

            CompetitionSubSection.FIXTURES -> {
                val compFixtures = snapshot.allGroupedFixtures.values.flatten()
                    .filter { it.competitionId == activeComp.id }
                if (compFixtures.isEmpty()) {
                    EmptySectionCard("No upcoming fixtures scheduled in ${activeComp.displayName}.")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 20.dp)
                    ) {
                        items(compFixtures, key = { it.id }) { fix ->
                            HubFixtureCard(
                                fixture = fix,
                                nowEpochMs = nowEpochMs,
                                onClick = { onSelectFixture(fix) }
                            )
                        }
                    }
                }
            }

            CompetitionSubSection.RESULTS -> {
                val compResults = snapshot.recentResults.filter { it.competitionId == activeComp.id }
                if (compResults.isEmpty()) {
                    EmptySectionCard("No recent completed matches in ${activeComp.displayName}.")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 20.dp)
                    ) {
                        items(compResults, key = { it.id }) { res ->
                            HubFixtureCard(
                                fixture = res,
                                nowEpochMs = nowEpochMs,
                                onClick = { onSelectFixture(res) }
                            )
                        }
                    }
                }
            }

            CompetitionSubSection.TOP_SCORERS -> {
                val scorers = snapshot.topScorersByCompetition[activeComp.id].orEmpty()
                PlayerStatsList(
                    players = scorers,
                    emptyMessage = "No top scorer statistics available yet for ${activeComp.displayName}.",
                    primaryMetricLabel = "Goals",
                    metricExtractor = { "${it.goals}" }
                )
            }

            CompetitionSubSection.FORM_TABLE -> {
                val baseRows = snapshot.standingsByCompetition[activeComp.id].orEmpty()
                val formSorted = remember(baseRows) {
                    baseRows
                        .map { row ->
                            val formPts = row.recentForm.sumOf {
                                when (it) {
                                    FormResult.WIN -> 3
                                    FormResult.DRAW -> 1
                                    FormResult.LOSS -> 0
                                }
                            }
                            row to formPts
                        }
                        .sortedWith(
                            compareByDescending<Pair<LeagueStandingRow, Int>> { it.second }
                                .thenByDescending { it.first.goalDifference }
                        )
                        .mapIndexed { idx, (r, formPts) ->
                            r.copy(
                                position = idx + 1,
                                played = r.recentForm.size,
                                won = r.recentForm.count { it == FormResult.WIN },
                                drawn = r.recentForm.count { it == FormResult.DRAW },
                                lost = r.recentForm.count { it == FormResult.LOSS },
                                points = formPts
                            )
                        }
                }
                if (formSorted.isEmpty()) {
                    EmptySectionCard("Form table unavailable for ${activeComp.displayName}.")
                } else {
                    FullLeagueTableComponent(rows = formSorted, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

// ============================================================================
// TAB 7: PLAYERS PAGE
// Sections: Squad, Top Scorers, Top Assists, Injuries, Suspensions
// ============================================================================
@Composable
private fun PlayersTabContent(
    snapshot: FootballDashboardSnapshot,
    selectedTeamId: String?,
    selectedCompetitionId: String?,
    onSelectTeam: (String) -> Unit,
    onSelectCompetition: (String) -> Unit
) {
    var activeSection by remember { mutableStateOf(PlayersSubSection.SQUAD) }
    val currentTeam = remember(selectedTeamId, snapshot.favouriteTeams) {
        snapshot.favouriteTeams.firstOrNull { it.teamId == selectedTeamId }
            ?: snapshot.primaryTeam
            ?: snapshot.favouriteTeams.firstOrNull()
    }
    val currentCompId = selectedCompetitionId
        ?: currentTeam?.primaryCompetitionId
        ?: SupportedCompetition.PREMIER_LEAGUE.id

    Column(modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayersSubSection.entries.forEach { sec ->
                val selected = sec == activeSection
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) GhAccentGreen else GhTileSurface)
                        .border(
                            1.dp,
                            if (selected) GhAccentGreen else GhCardBorder,
                            RoundedCornerShape(9.dp)
                        )
                        .clickable { activeSection = sec }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = sec.title,
                        color = if (selected) Color(0xFF101418) else GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (activeSection == PlayersSubSection.SQUAD) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(snapshot.favouriteTeams) { fav ->
                    val selected = fav.teamId == currentTeam?.teamId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (selected) GhTileSurfaceElevated else GhSunkenWell)
                            .border(
                                1.dp,
                                if (selected) GhAccentGreen else GhCardBorder,
                                RoundedCornerShape(9.dp)
                            )
                            .clickable { onSelectTeam(fav.teamId) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = fav.name,
                            color = if (selected) GhAccentGreen else GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            val squad = currentTeam?.let { snapshot.squadByTeamId[it.teamId] }.orEmpty()
            PlayerStatsList(
                players = squad,
                emptyMessage = "No active squad roster returned yet.",
                primaryMetricLabel = "Apps / Goals",
                metricExtractor = { "${it.appearances} apps • ${it.goals}G ${it.assists}A" }
            )
        } else if (activeSection == PlayersSubSection.TOP_SCORERS || activeSection == PlayersSubSection.TOP_ASSISTS) {
            val comps = snapshot.topScorersByCompetition.keys
                .union(snapshot.topAssistsByCompetition.keys)
                .mapNotNull { SupportedCompetition.fromIdOrName(it) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(comps) { comp ->
                    val selected = comp.id == currentCompId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (selected) GhTileSurfaceElevated else GhSunkenWell)
                            .border(
                                1.dp,
                                if (selected) GhAccentGreen else GhCardBorder,
                                RoundedCornerShape(9.dp)
                            )
                            .clickable { onSelectCompetition(comp.id) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = comp.displayName,
                            color = if (selected) GhAccentGreen else GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (activeSection == PlayersSubSection.TOP_SCORERS) {
                PlayerStatsList(
                    players = snapshot.topScorersByCompetition[currentCompId].orEmpty(),
                    emptyMessage = "No top scorers recorded for this competition.",
                    primaryMetricLabel = "Goals",
                    metricExtractor = { "${it.goals} goals (${it.assists} ast)" }
                )
            } else {
                PlayerStatsList(
                    players = snapshot.topAssistsByCompetition[currentCompId].orEmpty(),
                    emptyMessage = "No top assists recorded for this competition.",
                    primaryMetricLabel = "Assists",
                    metricExtractor = { "${it.assists} assists (${it.goals} goals)" }
                )
            }
        } else {
            val wantSuspensions = activeSection == PlayersSubSection.SUSPENSIONS
            val filteredIssues = snapshot.availabilityIssues.filter { it.isSuspension == wantSuspensions }
            if (filteredIssues.isEmpty()) {
                EmptySectionCard(
                    if (wantSuspensions) "No current player suspensions across your favourite clubs."
                    else "No reported injuries across your favourite clubs."
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    items(filteredIssues, key = { it.id }) { issue ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(GhTileSurface)
                                .border(1.dp, GhCardBorder, RoundedCornerShape(12.dp))
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = issue.playerName,
                                    color = GhTextPrimary,
                                    fontFamily = GhFont,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${issue.teamName} • ${issue.reason}",
                                    color = GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = issue.statusLabel,
                                    color = if (issue.isSuspension) Color(0xFFEF4444) else GhAccentWarm,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (issue.expectedReturn.isNotBlank()) {
                                    Text(
                                        text = "Return: ${issue.expectedReturn}",
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
    }
}

@Composable
private fun PlayerStatsList(
    players: List<FootballPlayerStatEntry>,
    emptyMessage: String,
    primaryMetricLabel: String,
    metricExtractor: (FootballPlayerStatEntry) -> String
) {
    if (players.isEmpty()) {
        EmptySectionCard(emptyMessage)
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            items(players.size) { index ->
                val p = players[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(GhTileSurface)
                        .border(1.dp, GhCardBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "${index + 1}",
                            color = GhAccentGreen,
                            fontFamily = GhFont,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.width(26.dp)
                        )
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = p.name,
                                    color = GhTextPrimary,
                                    fontFamily = GhFont,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                p.shirtNumber?.let { num ->
                                    Text(
                                        text = "#$num",
                                        color = GhTextSecondary,
                                        fontFamily = GhFont,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Text(
                                text = listOfNotNull(
                                    p.teamName.takeIf { it.isNotBlank() },
                                    p.position.takeIf { it.isNotBlank() },
                                    p.nationality.takeIf { it.isNotBlank() }
                                ).joinToString(" • "),
                                color = GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = metricExtractor(p),
                            color = GhAccentGreen,
                            fontFamily = GhFont,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = primaryMetricLabel,
                            color = GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// TAB 8: NEWS PAGE
// Categories: Club News, Transfer News, Injury News, Manager News, Competition News
// ============================================================================
@Composable
private fun NewsTabContent(
    snapshot: FootballDashboardSnapshot,
    onOpenArticle: (FootballNewsArticle) -> Unit
) {
    var selectedCategory by remember { mutableStateOf(FootballNewsCategory.ALL) }

    val filteredArticles = remember(snapshot.newsArticles, selectedCategory) {
        if (selectedCategory == FootballNewsCategory.ALL) {
            snapshot.newsArticles
        } else {
            snapshot.newsArticles.filter { it.category == selectedCategory }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(FootballNewsCategory.entries) { cat ->
                val selected = cat == selectedCategory
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) GhAccentGreen else GhTileSurface)
                        .border(
                            1.dp,
                            if (selected) GhAccentGreen else GhCardBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { selectedCategory = cat }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = cat.title,
                        color = if (selected) Color(0xFF101418) else GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredArticles.isEmpty()) {
            EmptySectionCard("No live articles currently in ${selectedCategory.title}.")
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filteredArticles, key = { it.id }) { article ->
                    val pubTime = Instant.ofEpochMilli(article.publishedEpochMs)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("dd MMM • HH:mm", Locale.UK))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(GhTileSurface)
                            .border(
                                1.dp,
                                if (article.involvesFavouriteTeam) GhAccentGreen.copy(alpha = 0.6f) else GhCardBorder,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { onOpenArticle(article) }
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = article.sourceName,
                                    color = GhAccentGreen,
                                    fontFamily = GhFont,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "• ${article.category.title} • $pubTime",
                                    color = GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 11.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = "Read Article",
                                tint = GhTextSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        Text(
                            text = article.title,
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (article.summary.isNotBlank()) {
                            Text(
                                text = article.summary,
                                color = GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 13.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// FIXTURE DETAILS DRILL-DOWN POPUP (Full Spec Implementation)
// ============================================================================
@Composable
private fun FixtureDetailsPopupDialog(
    repository: FootballRepository,
    fixture: FootballFixture,
    onDismiss: () -> Unit,
    onViewCompetition: (String) -> Unit,
    onViewTeam: (String) -> Unit,
    onWatchChannel: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var detail by remember(fixture.id) { mutableStateOf<FootballFixtureDetail?>(null) }
    var isLoading by remember(fixture.id) { mutableStateOf(true) }
    var reminderSet by remember(fixture.id) { mutableStateOf(false) }

    LaunchedEffect(fixture.id) {
        isLoading = true
        val loaded = repository.loadFixtureDetail(fixture)
        detail = loaded
        reminderSet = loaded.hasReminderSet
        isLoading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        SfHouseSunkenTileContainer(
            outerCornerRadius = 26.dp,
            innerCornerRadius = 20.dp,
            sunkenInsetPadding = 4.dp,
            innerBackgroundColor = GhBackground,
            innerBorderColor = GhCardBorder,
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .fillMaxHeight(0.88f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                val activeFix = detail?.fixture ?: fixture
                val koFormatted = Instant.ofEpochMilli(activeFix.kickoffEpochMs)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("EEEE dd MMM yyyy • HH:mm", Locale.UK))

                // Header: homeTeam, awayTeam, competition, kickoff, channel
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${activeFix.competitionName} • $koFormatted",
                            color = GhAccentGreen,
                            fontFamily = GhFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${activeFix.homeTeam.name} vs ${activeFix.awayTeam.name}",
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TvChannelPill(channel = activeFix.broadcast.displayChannel)
                        HubActionIconButton(
                            icon = Icons.Default.Close,
                            contentDescription = "Close Details",
                            onClick = onDismiss
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Quick Actions Bar: Add Reminder, View Competition, View Team, Watch Channel
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailQuickActionButton(
                        label = if (reminderSet) "Reminder Active" else FixtureQuickAction.ADD_REMINDER.label,
                        icon = if (reminderSet) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
                        highlighted = reminderSet,
                        onClick = {
                            scope.launch {
                                reminderSet = repository.toggleMatchReminder(activeFix)
                            }
                        }
                    )
                    DetailQuickActionButton(
                        label = FixtureQuickAction.VIEW_COMPETITION.label,
                        icon = Icons.Default.EmojiEvents,
                        highlighted = false,
                        onClick = { onViewCompetition(activeFix.competitionId) }
                    )
                    DetailQuickActionButton(
                        label = "${FixtureQuickAction.VIEW_TEAM.label} (${activeFix.homeTeam.shortName})",
                        icon = Icons.Default.Groups,
                        highlighted = false,
                        onClick = { onViewTeam(activeFix.homeTeam.name) }
                    )
                    DetailQuickActionButton(
                        label = FixtureQuickAction.WATCH_CHANNEL.label,
                        icon = Icons.Default.LiveTv,
                        highlighted = true,
                        onClick = { onWatchChannel(activeFix.broadcast.displayChannel) }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isLoading && detail == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = GhAccentGreen)
                    }
                } else {
                    val d = detail
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        // 1. Match Overview
                        item {
                            DetailCard(title = "Match Overview") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    StatMetricChip(
                                        "Venue",
                                        activeFix.stadiumName.ifBlank { "Stadium TBC" }
                                    )
                                    StatMetricChip(
                                        "Referee",
                                        activeFix.refereeName.ifBlank { "Official TBC" }
                                    )
                                    StatMetricChip(
                                        "Weather",
                                        activeFix.stadiumWeather?.formattedSummary ?: "Forecast pending"
                                    )
                                }
                            }
                        }

                        // 2. Broadcast Information (mainChannel, alternateChannels, streamingPlatforms)
                        item {
                            val bc = d?.broadcast ?: activeFix.broadcast
                            DetailCard(title = "Broadcast & Streaming Information") {
                                Text(
                                    text = "Main TV Channel: ${bc.mainChannel}",
                                    color = GhTextPrimary,
                                    fontFamily = GhFont,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Alternate Channels: ${bc.alternateChannels.joinToString(", ").ifBlank { "None listed" }}",
                                    color = GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "Streaming Platforms: ${bc.streamingPlatforms.joinToString(", ").ifBlank { "Broadcaster Companion App" }}",
                                    color = GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // 3. Lineups (Formation, Starting XI, Substitutes, Status Messages)
                        item {
                            val lState = d?.lineupState ?: activeFix.lineupState
                            DetailCard(title = "Lineups • ${lState.detailStatusMessage}") {
                                val homeLu = d?.homeLineup
                                val awayLu = d?.awayLineup
                                if (homeLu != null || awayLu != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        LineupColumn(
                                            lineup = homeLu,
                                            fallbackTeamName = activeFix.homeTeam.name,
                                            modifier = Modifier.weight(1f)
                                        )
                                        LineupColumn(
                                            lineup = awayLu,
                                            fallbackTeamName = activeFix.awayTeam.name,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                } else {
                                    Text(
                                        text = lState.detailStatusMessage,
                                        color = GhTextSecondary,
                                        fontFamily = GhFont,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        // 4. Recent Form (5 matches for both clubs)
                        item {
                            DetailCard(title = "Recent Form (Last 5 Matches)") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(activeFix.homeTeam.name, color = GhTextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        if (d?.homeRecentForm?.isNotEmpty() == true) {
                                            FootballFormPillsRow(form = d.homeRecentForm, pillSize = 21.dp)
                                        } else {
                                            Text("Form pending", color = GhTextSecondary, fontSize = 12.sp)
                                        }
                                    }
                                    Column(
                                        horizontalAlignment = Alignment.End,
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(activeFix.awayTeam.name, color = GhTextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        if (d?.awayRecentForm?.isNotEmpty() == true) {
                                            FootballFormPillsRow(form = d.awayRecentForm, pillSize = 21.dp)
                                        } else {
                                            Text("Form pending", color = GhTextSecondary, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }

                        // 5. Context-Aware League Table (Highlights competing clubs)
                        if (d?.contextLeagueTable?.isNotEmpty() == true) {
                            item {
                                DetailCard(title = "League Table Context (${activeFix.competitionName})") {
                                    val contextRows = remember(d.contextLeagueTable, activeFix) {
                                        val matchPositions = d.contextLeagueTable
                                            .filter {
                                                HybridLiveFootballDataProvider.clubsMatch(it.team.name, activeFix.homeTeam.name) ||
                                                        HybridLiveFootballDataProvider.clubsMatch(it.team.name, activeFix.awayTeam.name)
                                            }
                                            .map { it.position }
                                        if (matchPositions.isEmpty()) {
                                            d.contextLeagueTable.take(8)
                                        } else {
                                            d.contextLeagueTable.filter { row ->
                                                row.position <= 2 || matchPositions.any { pos -> kotlin.math.abs(row.position - pos) <= 2 }
                                            }.distinctBy { it.position }
                                        }
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        contextRows.forEach { row ->
                                            val isMatchClub = HybridLiveFootballDataProvider.clubsMatch(row.team.name, activeFix.homeTeam.name) ||
                                                    HybridLiveFootballDataProvider.clubsMatch(row.team.name, activeFix.awayTeam.name)
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        if (isMatchClub) GhAccentGreen.copy(alpha = 0.16f) else GhSunkenWell
                                                    )
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "${row.position}. ${row.team.name}",
                                                    color = if (isMatchClub) GhAccentGreen else GhTextPrimary,
                                                    fontFamily = GhFont,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isMatchClub) FontWeight.Bold else FontWeight.Medium
                                                )
                                                Text(
                                                    text = "P${row.played} • GD ${if (row.goalDifference >= 0) "+${row.goalDifference}" else "${row.goalDifference}"} • ${row.points} pts",
                                                    color = GhTextPrimary,
                                                    fontFamily = GhFont,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 6. Head-to-Head (Last 10 previous meetings)
                        item {
                            DetailCard(title = "Head-to-Head (Previous Meetings)") {
                                val h2h = d?.headToHeadMeetings.orEmpty()
                                if (h2h.isEmpty()) {
                                    Text(
                                        text = "No recent head-to-head meetings recorded in feed.",
                                        color = GhTextSecondary,
                                        fontSize = 12.sp
                                    )
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        h2h.take(10).forEach { m ->
                                            val dateStr = Instant.ofEpochMilli(m.kickoffEpochMs)
                                                .atZone(ZoneId.systemDefault())
                                                .format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.UK))
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(GhSunkenWell)
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(dateStr, color = GhTextSecondary, fontSize = 12.sp)
                                                Text(
                                                    text = "${m.homeTeam.shortName} ${m.homeScore ?: 0} - ${m.awayScore ?: 0} ${m.awayTeam.shortName}",
                                                    color = GhTextPrimary,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp
                                                )
                                                Text(m.competitionName, color = GhTextSecondary, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 7. Injuries & Suspensions
                        item {
                            DetailCard(title = "Injuries & Suspensions") {
                                val injuries = d?.injuries.orEmpty()
                                val suspensions = d?.suspensions.orEmpty()
                                if (injuries.isEmpty() && suspensions.isEmpty()) {
                                    Text(
                                        text = "No confirmed injuries or suspensions reported for either squad.",
                                        color = GhTextSecondary,
                                        fontSize = 12.sp
                                    )
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        (suspensions + injuries).forEach { issue ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "${issue.playerName} (${issue.teamName})",
                                                    color = GhTextPrimary,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = "${if (issue.isSuspension) "Suspended" else "Injured"} • ${issue.reason}",
                                                    color = if (issue.isSuspension) Color(0xFFEF4444) else GhAccentWarm,
                                                    fontSize = 12.sp
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
    }
}

@Composable
private fun LineupColumn(
    lineup: TeamLineup?,
    fallbackTeamName: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(GhSunkenWell)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "${lineup?.teamName ?: fallbackTeamName} (${lineup?.formation ?: "TBC"})",
            color = GhAccentGreen,
            fontFamily = GhFont,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        if (lineup != null && lineup.startingEleven.isNotEmpty()) {
            Text("Starting XI", color = GhTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            lineup.startingEleven.forEach { p ->
                val num = p.shirtNumber?.let { "$it. " }.orEmpty()
                Text(
                    text = "$num${p.name} ${p.position}".trim(),
                    color = GhTextPrimary,
                    fontSize = 12.sp
                )
            }
            if (lineup.substitutes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("Substitutes", color = GhTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                lineup.substitutes.forEach { sub ->
                    val num = sub.shirtNumber?.let { "$it. " }.orEmpty()
                    Text(
                        text = "$num${sub.name}",
                        color = GhTextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            Text("Lineup pending confirmation", color = GhTextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DetailQuickActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlighted) GhAccentGreen else GhTileSurface)
            .border(1.dp, if (highlighted) GhAccentGreen else GhCardBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (highlighted) Color(0xFF101418) else GhAccentGreen,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = label,
            color = if (highlighted) Color(0xFF101418) else GhTextPrimary,
            fontFamily = GhFont,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DetailCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(GhTileSurface)
            .border(1.dp, GhCardBorder, RoundedCornerShape(15.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = GhTextPrimary,
            fontFamily = GhFont,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
private fun HubSectionHeader(title: String) {
    Text(
        text = title,
        color = GhTextPrimary,
        fontFamily = GhFont,
        fontSize = 16.sp,
        fontWeight = FontWeight.ExtraBold
    )
}

@Composable
private fun StatMetricChip(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(GhSunkenWell)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text = label, color = GhTextSecondary, fontFamily = GhFont, fontSize = 10.sp)
        Text(
            text = value,
            color = GhTextPrimary,
            fontFamily = GhFont,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptySectionCard(message: String) {
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

private fun launchBroadcasterChannel(context: Context, channelLabel: String) {
    val lower = channelLabel.lowercase(Locale.UK)
    val (pkgCandidates, webFallback) = when {
        lower.contains("sky") -> listOf("com.bskyb.skygo", "com.bskyb.sportnews") to "https://www.skysports.com/watch"
        lower.contains("tnt") || lower.contains("discovery") ->
            listOf("com.discovery.plus", "uk.co.bt.sport") to "https://www.discoveryplus.com/gb/sport"
        lower.contains("bbc") || lower.contains("iplayer") ->
            listOf("bbc.iplayer.android") to "https://www.bbc.co.uk/iplayer"
        lower.contains("itv") ->
            listOf("air.ITVMobilePlayer") to "https://www.itv.com"
        lower.contains("prime") || lower.contains("amazon") ->
            listOf("com.amazon.avod.thirdpartyclient") to "https://www.amazon.co.uk/gp/video/storefront"
        lower.contains("apple") || lower.contains("mls") ->
            listOf("com.apple.atve.androidtv.appletv") to "https://tv.apple.com"
        else -> emptyList<String>() to "https://www.live-footballontv.com/"
    }

    for (pkg in pkgCandidates) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching {
                context.startActivity(launchIntent)
                return
            }
        }
    }
    openExternalUrlSafely(context, webFallback)
}

private fun openExternalUrlSafely(context: Context, url: String) {
    if (!url.startsWith("http", ignoreCase = true)) return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}