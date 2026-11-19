package com.jarvis.smarthomelauncher.widgets.football.Settings

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.smarthomelauncher.SfHouseSunkenTileContainer
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.widgets.football.Models.*
import com.jarvis.smarthomelauncher.widgets.football.Repository.FootballRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * CWAS Rule 8 & Rule 16: Database-backed Settings & Club Configuration sheet for Football Hub v3.0.
 * Can be rendered either as a standalone modal dialog or embedded inside the Football Hub Popup.
 */
@Composable
fun FootballSettingsDialog(
    repository: FootballRepository,
    favouriteTeams: List<FavouriteTeamEntry>,
    preferences: FootballHubPreferences,
    onDismiss: () -> Unit
) {
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
                .fillMaxWidth(0.86f)
                .fillMaxHeight(0.88f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(22.dp)
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
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(GhTileSurfaceElevated)
                                .border(1.dp, GhCardBorder, RoundedCornerShape(11.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SportsSoccer,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Football Hub Settings",
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Favourite clubs (${favouriteTeams.size}/${preferences.maximumTeams}), competitions, live alerts & automations",
                                color = GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 12.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(GhTileSurfaceElevated)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Settings",
                            tint = GhTextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                FootballSettingsContent(
                    repository = repository,
                    favouriteTeams = favouriteTeams,
                    preferences = preferences,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun FootballSettingsContent(
    repository: FootballRepository,
    favouriteTeams: List<FavouriteTeamEntry>,
    preferences: FootballHubPreferences,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    var localPrefs by remember(preferences) { mutableStateOf(preferences) }
    var localTeams by remember(favouriteTeams) { mutableStateOf(favouriteTeams) }

    var selectedAddCompetition by remember {
        mutableStateOf(SupportedCompetition.PREMIER_LEAGUE)
    }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<FootballClubSummary>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var searchDebounceJob by remember { mutableStateOf<Job?>(null) }

    var apiKeyText by remember { mutableStateOf(repository.getApiFootballKey().orEmpty()) }
    var revealApiKey by remember { mutableStateOf(false) }

    LaunchedEffect(selectedAddCompetition) {
        isSearching = true
        searchResults = repository.searchClubsToFollow(searchQuery)
        isSearching = false
    }

    fun persistPrefs(updated: FootballHubPreferences) {
        localPrefs = updated
        scope.launch {
            repository.updatePreferences(updated)
        }
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        if (!statusBanner.isNullOrBlank()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(GhTileSurfaceElevated)
                        .border(1.dp, GhAccentGreen.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = statusBanner.orEmpty(),
                        color = GhAccentGreen,
                        fontFamily = GhFont,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // ====================================================================
        // 1. FAVOURITE CLUBS MANAGEMENT (UP TO 10 TEAMS + PRIMARY STAR)
        // ====================================================================
        item {
            SettingsSectionCard(
                title = "Favourite Teams (${localTeams.size}/${localPrefs.maximumTeams})",
                subtitle = "Star your primary club to prioritise its fixtures, form, and league snapshot on the room dashboard."
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Prioritise primary favourite club across widget & hub",
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 14.sp
                    )
                    Switch(
                        checked = localPrefs.prioritizeFavouriteTeam,
                        onCheckedChange = { checked ->
                            persistPrefs(localPrefs.copy(prioritizeFavouriteTeam = checked))
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (localTeams.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(GhSunkenWell)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No favourite teams saved yet. Search and add clubs below.",
                            color = GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        localTeams.forEach { team ->
                            val compName = SupportedCompetition.fromIdOrName(team.primaryCompetitionId)?.displayName
                                ?: team.primaryCompetitionId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(GhSunkenWell)
                                    .border(
                                        width = 1.dp,
                                        color = if (team.isPrimaryFavourite) GhAccentGreen else GhCardBorder,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    ClubMonogramBadge(
                                        name = team.shortName,
                                        hexColor = team.primaryColorHex,
                                        sizeDp = 34
                                    )
                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = team.name,
                                                color = GhTextPrimary,
                                                fontFamily = GhFont,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (team.isPrimaryFavourite) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(GhAccentGreen.copy(alpha = 0.18f))
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = "PRIMARY",
                                                        color = GhAccentGreen,
                                                        fontFamily = GhFont,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = compName,
                                            color = GhTextSecondary,
                                            fontFamily = GhFont,
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                localTeams = repository.setPrimaryFavouriteTeam(team.teamId)
                                                statusBanner = "${team.name} set as primary favourite club."
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (team.isPrimaryFavourite) Icons.Default.Star else Icons.Default.StarOutline,
                                            contentDescription = "Set Primary Favourite",
                                            tint = if (team.isPrimaryFavourite) Color(0xFFFBBF24) else GhTextSecondary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                localTeams = repository.removeFavouriteTeam(team.teamId)
                                                statusBanner = "Removed ${team.name}."
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Remove Club",
                                            tint = GhAccentRed
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = GhCardBorder)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Add Club (Select Primary Competition & Search Live Directory)",
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SupportedCompetition.entries) { comp ->
                        val isSelected = comp == selectedAddCompetition
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) GhAccentGreen.copy(alpha = 0.2f) else GhSunkenWell)
                                .border(
                                    1.dp,
                                    if (isSelected) GhAccentGreen else GhCardBorder,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedAddCompetition = comp }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = comp.displayName,
                                color = if (isSelected) GhAccentGreen else GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { next ->
                        searchQuery = next
                        searchDebounceJob?.cancel()
                        searchDebounceJob = scope.launch {
                            delay(280L)
                            isSearching = true
                            searchResults = repository.searchClubsToFollow(next)
                            isSearching = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = "Search clubs (e.g. Arsenal, Liverpool, Real Madrid, Celtic)...",
                            color = GhTextSecondary,
                            fontSize = 13.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = GhTextSecondary
                        )
                    },
                    trailingIcon = {
                        if (isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = GhAccentGreen
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = GhTextPrimary,
                        unfocusedTextColor = GhTextPrimary,
                        focusedBorderColor = GhAccentGreen,
                        unfocusedBorderColor = GhCardBorder,
                        focusedContainerColor = GhSunkenWell,
                        unfocusedContainerColor = GhSunkenWell
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                val filteredCandidates = remember(searchResults, localTeams) {
                    searchResults.filter { candidate ->
                        localTeams.none { saved ->
                            saved.name.equals(candidate.name, ignoreCase = true)
                        }
                    }.take(12)
                }

                if (filteredCandidates.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        filteredCandidates.chunked(2).forEach { rowClubs ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowClubs.forEach { club ->
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(GhTileSurfaceElevated)
                                            .border(1.dp, GhCardBorder, RoundedCornerShape(10.dp))
                                            .clickable {
                                                scope.launch {
                                                    val res = repository.addFavouriteTeam(
                                                        club = club,
                                                        primaryCompetition = selectedAddCompetition,
                                                        makePrimaryFavourite = localTeams.isEmpty()
                                                    )
                                                    res.onSuccess { updatedList ->
                                                        localTeams = updatedList
                                                        statusBanner = "Added ${club.name} (${selectedAddCompetition.displayName})."
                                                    }.onFailure { err ->
                                                        statusBanner = err.message ?: "Unable to add team."
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            ClubMonogramBadge(
                                                name = club.abbreviation,
                                                hexColor = club.primaryColorHex,
                                                sizeDp = 26
                                            )
                                            Text(
                                                text = club.name,
                                                color = GhTextPrimary,
                                                fontFamily = GhFont,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "Add ${club.name}",
                                            tint = GhAccentGreen,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                if (rowClubs.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // ====================================================================
        // 2. FAVOURITE COMPETITIONS (ALL 17 SUPPORTED COMPETITIONS)
        // ====================================================================
        item {
            SettingsSectionCard(
                title = "Tracked & Favourite Competitions",
                subtitle = "Favourite competitions appear first in Tables, Fixtures, and Competitions views."
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SupportedCompetition.entries.chunked(3).forEach { rowComps ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowComps.forEach { comp ->
                                val isFav = comp.id in localPrefs.favouriteCompetitionIds
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isFav) GhAccentGreen.copy(alpha = 0.16f) else GhSunkenWell
                                        )
                                        .border(
                                            1.dp,
                                            if (isFav) GhAccentGreen else GhCardBorder,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            val updatedIds = if (isFav) {
                                                (localPrefs.favouriteCompetitionIds - comp.id)
                                                    .ifEmpty { listOf(comp.id) }
                                            } else {
                                                localPrefs.favouriteCompetitionIds + comp.id
                                            }
                                            persistPrefs(localPrefs.copy(favouriteCompetitionIds = updatedIds))
                                        }
                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = comp.displayName,
                                            color = if (isFav) GhTextPrimary else GhTextSecondary,
                                            fontFamily = GhFont,
                                            fontSize = 12.sp,
                                            fontWeight = if (isFav) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = comp.countryOrRegion,
                                            color = GhTextSecondary,
                                            fontFamily = GhFont,
                                            fontSize = 10.sp
                                        )
                                    }
                                    if (isFav) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = GhAccentGreen,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            repeat(3 - rowComps.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // ====================================================================
        // 3. DISPLAY MODES & REMINDER TIMING
        // ====================================================================
        item {
            SettingsSectionCard(
                title = "Match-Day Mode & Kickoff Reminders",
                subtitle = "Configure when the dashboard automatically transitions to Match-Day Mode before kickoff."
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Activate Match-Day Mode: ${localPrefs.matchDayLeadHours} hours before kickoff",
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(4, 6, 8, 12).forEach { hrs ->
                            val active = localPrefs.matchDayLeadHours == hrs
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) GhAccentGreen else GhSunkenWell)
                                    .clickable {
                                        persistPrefs(localPrefs.copy(matchDayLeadHours = hrs))
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "${hrs}h",
                                    color = if (active) Color(0xFF101418) else GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Match Reminder Lead Time: ${localPrefs.reminderLeadMinutes} mins before kickoff",
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(15, 30, 60).forEach { mins ->
                            val active = localPrefs.reminderLeadMinutes == mins
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) GhAccentGreen else GhSunkenWell)
                                    .clickable {
                                        persistPrefs(localPrefs.copy(reminderLeadMinutes = mins))
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "${mins}m",
                                    color = if (active) Color(0xFF101418) else GhTextSecondary,
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

        // ====================================================================
        // 4. NOTIFICATIONS (ALL 9 EVENTS)
        // ====================================================================
        item {
            SettingsSectionCard(
                title = "Live Match & Club Notifications",
                subtitle = "Receive real-time alerts for goals, red cards, lineups, kickoffs, and favourite club news."
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
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = GhAccentGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Enable Football Hub Notifications",
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Switch(
                        checked = localPrefs.notificationsEnabled,
                        onCheckedChange = { enabled ->
                            persistPrefs(localPrefs.copy(notificationsEnabled = enabled))
                        }
                    )
                }

                if (localPrefs.notificationsEnabled) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FootballNotificationEventType.entries.chunked(3).forEach { rowEvents ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowEvents.forEach { ev ->
                                    val enabled = ev in localPrefs.enabledNotificationEvents
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (enabled) GhTileSurfaceElevated else GhSunkenWell
                                            )
                                            .border(
                                                1.dp,
                                                if (enabled) GhAccentGreen.copy(alpha = 0.6f) else GhCardBorder,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                val nextSet = if (enabled) {
                                                    localPrefs.enabledNotificationEvents - ev
                                                } else {
                                                    localPrefs.enabledNotificationEvents + ev
                                                }
                                                persistPrefs(localPrefs.copy(enabledNotificationEvents = nextSet))
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = ev.displayName,
                                            color = if (enabled) GhTextPrimary else GhTextSecondary,
                                            fontFamily = GhFont,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Checkbox(
                                            checked = enabled,
                                            onCheckedChange = null
                                        )
                                    }
                                }
                                repeat(3 - rowEvents.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // ====================================================================
        // 5. SMART HOME AUTOMATION INTEGRATION (ALL 6 HOOKS)
        // ====================================================================
        item {
            SettingsSectionCard(
                title = "Clara Smart Home Match-Day Automations",
                subtitle = "Trigger household devices, lights, audio ducking, and voice announcements automatically."
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
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = GhAccentGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Enable Match-Day Smart Home Automations",
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Switch(
                        checked = localPrefs.automationsEnabled,
                        onCheckedChange = { enabled ->
                            persistPrefs(localPrefs.copy(automationsEnabled = enabled))
                        }
                    )
                }

                if (localPrefs.automationsEnabled) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FootballAutomationHook.entries.chunked(2).forEach { rowHooks ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowHooks.forEach { hook ->
                                    val enabled = hook in localPrefs.enabledAutomationHooks
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (enabled) GhTileSurfaceElevated else GhSunkenWell)
                                            .border(
                                                1.dp,
                                                if (enabled) GhAccentGreen.copy(alpha = 0.6f) else GhCardBorder,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                val nextHooks = if (enabled) {
                                                    localPrefs.enabledAutomationHooks - hook
                                                } else {
                                                    localPrefs.enabledAutomationHooks + hook
                                                }
                                                persistPrefs(localPrefs.copy(enabledAutomationHooks = nextHooks))
                                            }
                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = hook.displayName,
                                            color = if (enabled) GhTextPrimary else GhTextSecondary,
                                            fontFamily = GhFont,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Checkbox(
                                            checked = enabled,
                                            onCheckedChange = null
                                        )
                                    }
                                }
                                if (rowHooks.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // ====================================================================
        // 6. CLARA SECRET VAULT: OPTIONAL API-FOOTBALL V3 KEY
        // ====================================================================
        item {
            SettingsSectionCard(
                title = "Data Provider & ClaraSecretVault API Key",
                subtitle = "Live multi-competition scores, broadcasts, standings, weather, and news work automatically. Optionally add an API-Football v3 key in ClaraSecretVault for extended player/H2H telemetry."
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = apiKeyText,
                        onValueChange = { apiKeyText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        visualTransformation = if (revealApiKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        placeholder = {
                            Text(
                                text = "Optional API-Football v3 Key (stored encrypted in ClaraSecretVault)",
                                color = GhTextSecondary,
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = GhTextSecondary
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { revealApiKey = !revealApiKey }) {
                                Icon(
                                    imageVector = if (revealApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle Key Visibility",
                                    tint = GhTextSecondary
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = GhTextPrimary,
                            unfocusedTextColor = GhTextPrimary,
                            focusedBorderColor = GhAccentGreen,
                            unfocusedBorderColor = GhCardBorder,
                            focusedContainerColor = GhSunkenWell,
                            unfocusedContainerColor = GhSunkenWell
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            repository.setApiFootballKey(apiKeyText)
                            statusBanner = if (apiKeyText.isBlank()) {
                                "Cleared API-Football key; using live keyless feeds."
                            } else {
                                "Saved API-Football key securely in ClaraSecretVault."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GhAccentGreen,
                            contentColor = Color(0xFF101418)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(52.dp)
                    ) {
                        Text(
                            text = "Save Key",
                            fontFamily = GhFont,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GhTileSurface)
            .border(1.dp, GhCardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            color = GhTextPrimary,
            fontFamily = GhFont,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            color = GhTextSecondary,
            fontFamily = GhFont,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

@Composable
internal fun ClubMonogramBadge(
    name: String,
    hexColor: String,
    sizeDp: Int = 32
) {
    val parsedColor = remember(hexColor) {
        runCatching { Color(android.graphics.Color.parseColor(hexColor)) }
            .getOrDefault(Color(0xFF1E88E5))
    }
    val initials = remember(name) {
        name.trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .let { parts ->
                if (parts.size >= 2) {
                    "${parts[0].first()}${parts[1].first()}".uppercase()
                } else {
                    name.take(3).uppercase()
                }
            }
    }

    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(parsedColor.copy(alpha = 0.24f))
            .border(1.5.dp, parsedColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            color = GhTextPrimary,
            fontFamily = GhFont,
            fontSize = (sizeDp * 0.34f).sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}