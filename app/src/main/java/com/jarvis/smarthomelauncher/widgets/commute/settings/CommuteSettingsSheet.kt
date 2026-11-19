package com.jarvis.smarthomelauncher.widgets.commute.Settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.jarvis.smarthomelauncher.widgets.commute.Models.*
import com.jarvis.smarthomelauncher.widgets.commute.Repository.CommuteRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Maps each SupportedTransportMode to its canonical Material vector icon.
 */
internal fun iconForTransportMode(mode: SupportedTransportMode): ImageVector = when (mode) {
    SupportedTransportMode.RAIL -> Icons.Default.Train
    SupportedTransportMode.UNDERGROUND -> Icons.Default.Subway
    SupportedTransportMode.BUS -> Icons.Default.DirectionsBus
    SupportedTransportMode.TRAM -> Icons.Default.Tram
    SupportedTransportMode.FERRY -> Icons.Default.DirectionsBoat
    SupportedTransportMode.DRIVING -> Icons.Default.DirectionsCar
    SupportedTransportMode.WALKING -> Icons.Default.DirectionsWalk
    SupportedTransportMode.CYCLING -> Icons.Default.DirectionsBike
}

/**
 * CWAS Rule 8 & Rule 16: Standalone SF-House Settings & Route Configuration Dialog for Commute Hub v2.0.
 */
@Composable
fun CommuteSettingsDialog(
    repository: CommuteRepository,
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
                                imageVector = Icons.Default.Train,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Commute Hub Settings",
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Saved multi-modal routes, station walk buffers, disruption alerts & automations",
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
                            contentDescription = "Close Commute Settings",
                            tint = GhTextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                CommuteSettingsContent(
                    repository = repository,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun CommuteSettingsContent(
    repository: CommuteRepository,
    onRouteSavedCallback: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    var localRoutes by remember { mutableStateOf(repository.getSavedRoutesSync()) }
    var localPrefs by remember { mutableStateOf(repository.getPreferencesSync()) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    // Route Builder state
    var routeNameInput by remember { mutableStateOf("Home to Work") }
    var selectedMode by remember { mutableStateOf(SupportedTransportMode.RAIL) }
    var selectedWindow by remember { mutableStateOf(CommuteTimeWindow.MORNING) }
    var walkMinutes by remember { mutableIntStateOf(8) }

    var originQuery by remember { mutableStateOf("") }
    var originCandidates by remember { mutableStateOf<List<TransportStopEndpoint>>(emptyList()) }
    var selectedOrigin by remember { mutableStateOf<TransportStopEndpoint?>(null) }
    var isSearchingOrigin by remember { mutableStateOf(false) }
    var originDebounceJob by remember { mutableStateOf<Job?>(null) }

    var destQuery by remember { mutableStateOf("") }
    var destCandidates by remember { mutableStateOf<List<TransportStopEndpoint>>(emptyList()) }
    var selectedDestination by remember { mutableStateOf<TransportStopEndpoint?>(null) }
    var isSearchingDest by remember { mutableStateOf(false) }
    var destDebounceJob by remember { mutableStateOf<Job?>(null) }

    // Provider credentials state
    var tflKeyText by remember { mutableStateOf(repository.getTflApiKey().orEmpty()) }
    var revealTflKey by remember { mutableStateOf(false) }
    var darwinGatewayText by remember { mutableStateOf(repository.getDarwinGatewayUrl()) }

    fun persistPreferences(updated: CommuteHubPreferences) {
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
                        .border(1.dp, GhAccentGreen.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
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
        // 1. SAVED COMMUTE ROUTES LIST (STAR PRIMARY, ADD RETURN, REMOVE)
        // ====================================================================
        item {
            CommuteSettingsSectionCard(
                title = "Saved Commute Routes (${localRoutes.size})",
                subtitle = "Star your primary route to prioritise its live departures, disruptions, and Leave-By countdown on the room dashboard."
            ) {
                if (localRoutes.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(GhSunkenWell)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No commute routes configured yet. Build your first route below.",
                            color = GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        localRoutes.forEach { route ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(GhSunkenWell)
                                    .border(
                                        1.dp,
                                        if (route.isPrimaryRoute) GhAccentGreen else GhCardBorder,
                                        RoundedCornerShape(12.dp)
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
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(GhTileSurfaceElevated),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = iconForTransportMode(route.mode),
                                            contentDescription = null,
                                            tint = GhAccentGreen,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = route.routeName,
                                                color = GhTextPrimary,
                                                fontFamily = GhFont,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (route.isPrimaryRoute) {
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
                                            text = "${route.origin.compactDisplayName} → ${route.destination.compactDisplayName} • ${route.timeWindow.label} • ${route.walkToStationMinutes}m walk",
                                            color = GhTextSecondary,
                                            fontFamily = GhFont,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    // One-tap create reverse Evening/Morning return route
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                val reverseWindow = if (route.timeWindow == CommuteTimeWindow.MORNING) {
                                                    CommuteTimeWindow.EVENING
                                                } else {
                                                    CommuteTimeWindow.MORNING
                                                }
                                                val reverseRoute = SavedCommuteRoute(
                                                    routeId = "route_${UUID.randomUUID().toString().take(8)}",
                                                    routeName = "${route.destination.name} to ${route.origin.name}",
                                                    mode = route.mode,
                                                    origin = route.destination,
                                                    destination = route.origin,
                                                    walkToStationMinutes = route.walkToStationMinutes,
                                                    timeWindow = reverseWindow,
                                                    isPrimaryRoute = false,
                                                    sortOrder = localRoutes.size
                                                )
                                                localRoutes = repository.saveCommuteRoute(reverseRoute)
                                                statusBanner = "Added return route '${reverseRoute.routeName}'."
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SwapVert,
                                            contentDescription = "Create Return Route",
                                            tint = GhAccentGreen
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                localRoutes = repository.setPrimaryCommuteRoute(route.routeId)
                                                statusBanner = "'${route.routeName}' set as primary commute route."
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (route.isPrimaryRoute) Icons.Default.Star else Icons.Default.StarOutline,
                                            contentDescription = "Set Primary Route",
                                            tint = if (route.isPrimaryRoute) Color(0xFFFBBF24) else GhTextSecondary
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                localRoutes = repository.deleteCommuteRoute(route.routeId)
                                                statusBanner = "Removed '${route.routeName}'."
                                            }
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
                        }
                    }
                }
            }
        }

        // ====================================================================
        // 2. ADD NEW MULTI-MODAL ROUTE BUILDER (LIVE STATION / LOCATION SEARCH)
        // ====================================================================
        item {
            CommuteSettingsSectionCard(
                title = "Add New Saved Route",
                subtitle = "Choose a transport mode, search live UK Rail stations, TfL stops, or addresses, and save your commute."
            ) {
                // Preset route names from JSON spec
                Text(
                    text = "Route Name & Quick Presets",
                    color = GhTextSecondary,
                    fontFamily = GhFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val examples = listOf(
                        "Home to Work",
                        "Home to London Bridge",
                        "Home to School",
                        "Home to Airport"
                    )
                    items(examples) { preset ->
                        val active = routeNameInput.equals(preset, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (active) GhAccentGreen.copy(alpha = 0.2f) else GhSunkenWell)
                                .border(
                                    1.dp,
                                    if (active) GhAccentGreen else GhCardBorder,
                                    RoundedCornerShape(9.dp)
                                )
                                .clickable { routeNameInput = preset }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = preset,
                                color = if (active) GhAccentGreen else GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = routeNameInput,
                    onValueChange = { routeNameInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Custom Route Name", fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = GhTextPrimary,
                        unfocusedTextColor = GhTextPrimary,
                        focusedBorderColor = GhAccentGreen,
                        unfocusedBorderColor = GhCardBorder,
                        focusedContainerColor = GhSunkenWell,
                        unfocusedContainerColor = GhSunkenWell,
                        focusedLabelColor = GhAccentGreen,
                        unfocusedLabelColor = GhTextSecondary
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Transport Mode (All 8 Supported Modes)",
                    color = GhTextSecondary,
                    fontFamily = GhFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SupportedTransportMode.entries) { mode ->
                        val selected = mode == selectedMode
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) GhAccentGreen else GhSunkenWell)
                                .border(
                                    1.dp,
                                    if (selected) GhAccentGreen else GhCardBorder,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    selectedMode = mode
                                    selectedOrigin = null
                                    selectedDestination = null
                                    originCandidates = emptyList()
                                    destCandidates = emptyList()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = iconForTransportMode(mode),
                                contentDescription = null,
                                tint = if (selected) Color(0xFF101418) else GhAccentGreen,
                                modifier = Modifier.size(16.dp)
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

                Spacer(modifier = Modifier.height(12.dp))

                // Origin & Destination Live Search Columns
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EndpointSearchBox(
                        label = "Origin Station / Stop",
                        placeholder = "e.g. Edenbridge, East Croydon, Reading...",
                        query = originQuery,
                        selectedEndpoint = selectedOrigin,
                        candidates = originCandidates,
                        isLoading = isSearchingOrigin,
                        onQueryChange = { q ->
                            originQuery = q
                            selectedOrigin = null
                            originDebounceJob?.cancel()
                            originDebounceJob = scope.launch {
                                delay(280L)
                                if (q.trim().length >= 2) {
                                    isSearchingOrigin = true
                                    originCandidates = repository.searchStopsAndLocations(q, selectedMode)
                                    isSearchingOrigin = false
                                } else {
                                    originCandidates = emptyList()
                                }
                            }
                        },
                        onSelectCandidate = { chosen ->
                            selectedOrigin = chosen
                            originQuery = chosen.compactDisplayName
                            originCandidates = emptyList()
                        },
                        modifier = Modifier.weight(1f)
                    )

                    EndpointSearchBox(
                        label = "Destination Station / Stop",
                        placeholder = "e.g. London Bridge, Victoria, Canary Wharf...",
                        query = destQuery,
                        selectedEndpoint = selectedDestination,
                        candidates = destCandidates,
                        isLoading = isSearchingDest,
                        onQueryChange = { q ->
                            destQuery = q
                            selectedDestination = null
                            destDebounceJob?.cancel()
                            destDebounceJob = scope.launch {
                                delay(280L)
                                if (q.trim().length >= 2) {
                                    isSearchingDest = true
                                    destCandidates = repository.searchStopsAndLocations(q, selectedMode)
                                    isSearchingDest = false
                                } else {
                                    destCandidates = emptyList()
                                }
                            }
                        },
                        onSelectCandidate = { chosen ->
                            selectedDestination = chosen
                            destQuery = chosen.compactDisplayName
                            destCandidates = emptyList()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Commute Window & Walk Time to Station
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CommuteTimeWindow.entries.forEach { win ->
                            val selected = win == selectedWindow
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (selected) GhTileSurfaceElevated else GhSunkenWell)
                                    .border(
                                        1.dp,
                                        if (selected) GhAccentGreen else GhCardBorder,
                                        RoundedCornerShape(9.dp)
                                    )
                                    .clickable { selectedWindow = win }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = win.label,
                                    color = if (selected) GhAccentGreen else GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Walk to Stop:",
                            color = GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 12.sp
                        )
                        listOf(0, 5, 8, 12, 15, 20).forEach { mins ->
                            val active = walkMinutes == mins
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) GhAccentGreen else GhSunkenWell)
                                    .clickable { walkMinutes = mins }
                                    .padding(horizontal = 9.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "${mins}m",
                                    color = if (active) Color(0xFF101418) else GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        val origin = selectedOrigin
                        val dest = selectedDestination
                        if (routeNameInput.isBlank()) {
                            statusBanner = "Please enter a route name."
                            return@Button
                        }
                        if (origin == null || dest == null) {
                            statusBanner = "Please search and select both an Origin and Destination stop from the live dropdown."
                            return@Button
                        }
                        scope.launch {
                            val newRoute = SavedCommuteRoute(
                                routeId = "route_${UUID.randomUUID().toString().take(8)}",
                                routeName = routeNameInput.trim(),
                                mode = selectedMode,
                                origin = origin,
                                destination = dest,
                                walkToStationMinutes = walkMinutes,
                                timeWindow = selectedWindow,
                                isPrimaryRoute = localRoutes.isEmpty(),
                                sortOrder = localRoutes.size
                            )
                            localRoutes = repository.saveCommuteRoute(newRoute)
                            statusBanner = "Saved route '${newRoute.routeName}' (${origin.compactDisplayName} → ${dest.compactDisplayName})."
                            selectedOrigin = null
                            selectedDestination = null
                            originQuery = ""
                            destQuery = ""
                            onRouteSavedCallback?.invoke()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GhAccentGreen,
                        contentColor = Color(0xFF101418)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Save Route to Commute Hub",
                        fontFamily = GhFont,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // ====================================================================
        // 3. DISRUPTION OVERRIDE & ALERT THRESHOLDS
        // ====================================================================
        item {
            CommuteSettingsSectionCard(
                title = "Disruption Override & Leave-By Thresholds",
                subtitle = "Configure when Disruptions automatically override Normal mode on the room dashboard."
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Activate Disruption Mode on Dashboard",
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Automatically switches the widget to Disruption Mode when delays, cancellations, or engineering works hit your route.",
                            color = GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = localPrefs.autoDisruptionModeEnabled,
                        onCheckedChange = {
                            persistPreferences(localPrefs.copy(autoDisruptionModeEnabled = it))
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Delay Alert Threshold: ${localPrefs.delayAlertThresholdMinutes} mins",
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 10, 15, 20).forEach { mins ->
                            val active = localPrefs.delayAlertThresholdMinutes == mins
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) GhAccentGreen else GhSunkenWell)
                                    .clickable {
                                        persistPreferences(localPrefs.copy(delayAlertThresholdMinutes = mins))
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

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Leave Home Reminder Window: ${localPrefs.leaveHomeBufferMinutes} mins before Leave-By",
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 10, 15, 20).forEach { mins ->
                            val active = localPrefs.leaveHomeBufferMinutes == mins
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) GhAccentGreen else GhSunkenWell)
                                    .clickable {
                                        persistPreferences(localPrefs.copy(leaveHomeBufferMinutes = mins))
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
        // 4. NOTIFICATIONS (ALL 6 EVENTS) & AUTOMATIONS (ALL 5 HOOKS)
        // ====================================================================
        item {
            CommuteSettingsSectionCard(
                title = "Notifications & Clara Smart Home Automations",
                subtitle = "Configure real-time commute alerts, voice platform announcements, and weekday morning routines."
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
                            text = "Enable Commute Notifications",
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Switch(
                        checked = localPrefs.notificationsEnabled,
                        onCheckedChange = {
                            persistPreferences(localPrefs.copy(notificationsEnabled = it))
                        }
                    )
                }

                if (localPrefs.notificationsEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CommuteNotificationEventType.entries.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pair.forEach { ev ->
                                    val enabled = ev in localPrefs.enabledNotificationEvents
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (enabled) GhTileSurfaceElevated else GhSunkenWell)
                                            .border(
                                                1.dp,
                                                if (enabled) GhAccentGreen.copy(alpha = 0.55f) else GhCardBorder,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                val nextSet = if (enabled) {
                                                    localPrefs.enabledNotificationEvents - ev
                                                } else {
                                                    localPrefs.enabledNotificationEvents + ev
                                                }
                                                persistPreferences(localPrefs.copy(enabledNotificationEvents = nextSet))
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = ev.displayName,
                                            color = if (enabled) GhTextPrimary else GhTextSecondary,
                                            fontFamily = GhFont,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Checkbox(checked = enabled, onCheckedChange = null)
                                    }
                                }
                                if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = GhCardBorder)
                Spacer(modifier = Modifier.height(12.dp))

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
                            text = "Enable Commute Smart Home Automations",
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Switch(
                        checked = localPrefs.automationsEnabled,
                        onCheckedChange = {
                            persistPreferences(localPrefs.copy(automationsEnabled = it))
                        }
                    )
                }

                if (localPrefs.automationsEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CommuteAutomationHook.entries.forEach { hook ->
                            val enabled = hook in localPrefs.enabledAutomationHooks
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (enabled) GhTileSurfaceElevated else GhSunkenWell)
                                    .border(
                                        1.dp,
                                        if (enabled) GhAccentGreen.copy(alpha = 0.55f) else GhCardBorder,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        val nextHooks = if (enabled) {
                                            localPrefs.enabledAutomationHooks - hook
                                        } else {
                                            localPrefs.enabledAutomationHooks + hook
                                        }
                                        persistPreferences(localPrefs.copy(enabledAutomationHooks = nextHooks))
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = hook.displayName,
                                    color = if (enabled) GhTextPrimary else GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Checkbox(checked = enabled, onCheckedChange = null)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Weekday Morning Auto-Open Time: %02d:%02d".format(
                                localPrefs.weekdayMorningPopupHour,
                                localPrefs.weekdayMorningPopupMinute
                            ),
                            color = GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(6 to 30, 7 to 0, 7 to 30, 8 to 0, 8 to 30).forEach { (h, m) ->
                                val active = localPrefs.weekdayMorningPopupHour == h &&
                                        localPrefs.weekdayMorningPopupMinute == m
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (active) GhAccentGreen else GhSunkenWell)
                                        .clickable {
                                            persistPreferences(
                                                localPrefs.copy(
                                                    weekdayMorningPopupHour = h,
                                                    weekdayMorningPopupMinute = m
                                                )
                                            )
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "%02d:%02d".format(h, m),
                                        color = if (active) Color(0xFF101418) else GhTextSecondary,
                                        fontFamily = GhFont,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ====================================================================
        // 5. LIVE TRANSPORT ENDPOINTS & CLARA SECRET VAULT TFL KEY
        // ====================================================================
        item {
            CommuteSettingsSectionCard(
                title = "Transport Data Gateways & ClaraSecretVault Key",
                subtitle = "National Rail Darwin, TfL Unified API, and OSRM work out of the box. Optionally add a TfL API key for higher rate limits or a custom Darwin JSON proxy."
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = tflKeyText,
                        onValueChange = { tflKeyText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        visualTransformation = if (revealTflKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        label = { Text("Optional TfL Unified API App Key (ClaraSecretVault)", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Key, contentDescription = null, tint = GhTextSecondary)
                        },
                        trailingIcon = {
                            IconButton(onClick = { revealTflKey = !revealTflKey }) {
                                Icon(
                                    imageVector = if (revealTflKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
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
                            unfocusedContainerColor = GhSunkenWell,
                            focusedLabelColor = GhAccentGreen,
                            unfocusedLabelColor = GhTextSecondary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = darwinGatewayText,
                        onValueChange = { darwinGatewayText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("National Rail Darwin JSON Gateway URL", fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = GhTextPrimary,
                            unfocusedTextColor = GhTextPrimary,
                            focusedBorderColor = GhAccentGreen,
                            unfocusedBorderColor = GhCardBorder,
                            focusedContainerColor = GhSunkenWell,
                            unfocusedContainerColor = GhSunkenWell,
                            focusedLabelColor = GhAccentGreen,
                            unfocusedLabelColor = GhTextSecondary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            repository.setTflApiKey(tflKeyText)
                            repository.setDarwinGatewayUrl(darwinGatewayText)
                            statusBanner = "Saved transport gateway settings."
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GhAccentGreen,
                            contentColor = Color(0xFF101418)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(54.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Apply", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun EndpointSearchBox(
    label: String,
    placeholder: String,
    query: String,
    selectedEndpoint: TransportStopEndpoint?,
    candidates: List<TransportStopEndpoint>,
    isLoading: Boolean,
    onQueryChange: (String) -> Unit,
    onSelectCandidate: (TransportStopEndpoint) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(label, fontSize = 12.sp) },
            placeholder = { Text(placeholder, color = GhTextSecondary, fontSize = 12.sp) },
            leadingIcon = {
                Icon(
                    imageVector = if (selectedEndpoint != null) Icons.Default.Check else Icons.Default.Search,
                    contentDescription = null,
                    tint = if (selectedEndpoint != null) GhAccentGreen else GhTextSecondary
                )
            },
            trailingIcon = {
                if (isLoading) {
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
                unfocusedBorderColor = if (selectedEndpoint != null) GhAccentGreen.copy(alpha = 0.7f) else GhCardBorder,
                focusedContainerColor = GhSunkenWell,
                unfocusedContainerColor = GhSunkenWell,
                focusedLabelColor = GhAccentGreen,
                unfocusedLabelColor = GhTextSecondary
            ),
            shape = RoundedCornerShape(12.dp)
        )

        if (candidates.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(GhTileSurfaceElevated)
                    .border(1.dp, GhCardBorder, RoundedCornerShape(12.dp))
            ) {
                candidates.take(6).forEach { stop ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectCandidate(stop) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = stop.compactDisplayName,
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (stop.zoneOrRegion.isNotBlank()) {
                            Text(
                                text = stop.zoneOrRegion,
                                color = GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }
                    HorizontalDivider(color = GhCardBorder.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun CommuteSettingsSectionCard(
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