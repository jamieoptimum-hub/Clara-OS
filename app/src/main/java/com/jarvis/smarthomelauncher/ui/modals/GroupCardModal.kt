package com.jarvis.smarthomelauncher.ui.modals

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ---------------------------------------------------------------------------
// FIX #7: GROUP CARDS TILE CONTENT & EXPANDABLE POP-UP WINDOW
// - Allows users to group multiple device cards (e.g. "Lighting", "Garden Sockets",
//   "Heating") into a single dashboard Group Card.
// - Tapping the Group Card opens a pop-up window containing all member tiles,
//   plus an inline "+ Add / Remove Devices" checklist and inline group renaming.
// ---------------------------------------------------------------------------
fun resolveGroupCategoryIcon(iconKey: String): ImageVector = when (iconKey.uppercase()) {
    "LIGHTING", "LIGHT" -> Icons.Outlined.Lightbulb
    "SOCKETS", "PLUGS", "POWER" -> Icons.Outlined.Power
    "HEATING", "CLIMATE" -> Icons.Outlined.Thermostat
    "CAMERAS", "SECURITY" -> Icons.Outlined.Videocam
    "MEDIA", "TV" -> Icons.Outlined.Tv
    else -> Icons.Outlined.DashboardCustomize
}

fun doesTileMatchGroupCategoryOrRoom(
    candidate: SmartTile,
    groupTile: SmartTile,
    customRooms: List<String> = emptyList()
): Boolean {
    val groupTitleLower = groupTile.title.lowercase()
    val groupRoom = groupTile.roomOrSubtitle.trim()
    val candidateRoom = candidate.roomOrSubtitle.trim()
    if (candidateRoom.isNotBlank()) {
        if (groupRoom.isNotBlank() &&
            !groupRoom.equals("Group Card", ignoreCase = true) &&
            candidateRoom.equals(groupRoom, ignoreCase = true)
        ) {
            return true
        }
        val activeRooms = customRooms.ifEmpty { DEFAULT_ROOMS }
        val matchedRoomName = activeRooms.firstOrNull { room ->
            groupTitleLower.contains(room.lowercase()) ||
                    (groupRoom.isNotBlank() && !groupRoom.equals("Group Card", ignoreCase = true) && groupRoom.equals(room, ignoreCase = true))
        }
        if (matchedRoomName != null && candidateRoom.equals(matchedRoomName, ignoreCase = true)) {
            return true
        }
    }
    return when (groupTile.groupCategoryIcon.uppercase()) {
        "LIGHTING", "LIGHT" -> candidate.deviceClass == DeviceClass.LIGHT || candidate.isAmbientRgbTile
        "SOCKETS", "PLUGS", "POWER" -> candidate.deviceClass in setOf(DeviceClass.SWITCH, DeviceClass.ENERGY) || candidate.isSwitchTile
        "HEATING", "CLIMATE" -> candidate.deviceClass == DeviceClass.CLIMATE || candidate.isThermostatTile || candidate.isTowerFanTile
        "CAMERAS", "SECURITY" -> candidate.deviceClass in setOf(DeviceClass.CAMERA, DeviceClass.SECURITY) || candidate.isCameraTile || candidate.hasLockCapability
        "MEDIA", "TV" -> candidate.deviceClass == DeviceClass.MEDIA || candidate.isSpeakerTile
        else -> when {
            groupTitleLower.contains("light") || groupTitleLower.contains("lamp") ->
                candidate.deviceClass == DeviceClass.LIGHT || candidate.isAmbientRgbTile
            groupTitleLower.contains("socket") || groupTitleLower.contains("plug") ->
                candidate.deviceClass in setOf(DeviceClass.SWITCH, DeviceClass.ENERGY) || candidate.isSwitchTile
            groupTitleLower.contains("heat") || groupTitleLower.contains("climate") || groupTitleLower.contains("radiator") ->
                candidate.deviceClass == DeviceClass.CLIMATE || candidate.isThermostatTile || candidate.isTowerFanTile
            groupTitleLower.contains("camera") || groupTitleLower.contains("doorbell") || groupTitleLower.contains("security") ->
                candidate.deviceClass in setOf(DeviceClass.CAMERA, DeviceClass.SECURITY) || candidate.isCameraTile || candidate.hasLockCapability
            else -> false
        }
    }
}

@Composable
fun GhGroupCardTileContent(
    groupTile: SmartTile,
    memberTiles: List<SmartTile>,
    isDropTargetHighlighted: Boolean = false,
    onOpenGroupModal: () -> Unit,
    onToggleAllMembers: (Boolean) -> Unit
) {
    val activeCount = memberTiles.count { member ->
        if (member.multiGangSocketCount >= 2) member.isActive || member.isSocket2Active
        else member.isActive || member.isLiveFeedActive
    }
    val totalCount = memberTiles.size
    val anyActive = activeCount > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable { onOpenGroupModal() }
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header: Group Icon + Group Name + Master Group Switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    color = if (anyActive) GhAccentGreen.copy(alpha = 0.22f) else GhTileSurfaceElevated,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        1.dp,
                        if (anyActive) GhAccentGreen.copy(alpha = 0.6f) else Color.Transparent
                    ),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = resolveGroupCategoryIcon(groupTile.groupCategoryIcon),
                            contentDescription = null,
                            tint = if (anyActive) GhAccentGreen else GhAccentBlue,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = groupTile.title,
                        color = GhTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GhFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            isDropTargetHighlighted -> "Drop here to add to ${groupTile.title}!"
                            totalCount == 0 -> "Empty Group \u2022 Tap to add devices"
                            else -> "$activeCount of $totalCount active \u2022 Tap to open"
                        },
                        color = if (isDropTargetHighlighted || anyActive) GhAccentGreen else GhTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (isDropTargetHighlighted) FontWeight.Bold else FontWeight.Medium,
                        fontFamily = GhFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (totalCount > 0) {
                Switch(
                    checked = anyActive,
                    onCheckedChange = { nextOn -> onToggleAllMembers(nextOn) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF0F1114),
                        checkedTrackColor = GhAccentGreen,
                        uncheckedThumbColor = GhTextSecondary,
                        uncheckedTrackColor = GhTileSurfaceElevated
                    )
                )
            }
        }

        // Bottom Member Preview Chips
        if (memberTiles.isEmpty()) {
            Surface(
                color = GhTileSurfaceElevated.copy(alpha = 0.6f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, GhAccentBlue.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = GhAccentGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Tap to select devices or drag tiles here in Edit Mode",
                        color = GhTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                items(memberTiles.take(6), key = { "grp_preview_${it.id}" }) { member ->
                    val isMemberOn = if (member.multiGangSocketCount >= 2) {
                        member.isActive || member.isSocket2Active
                    } else {
                        member.isActive || member.isLiveFeedActive
                    }
                    Surface(
                        color = if (isMemberOn) GhAccentGreen.copy(alpha = 0.18f) else GhTileSurfaceElevated,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            1.dp,
                            if (isMemberOn) GhAccentGreen.copy(alpha = 0.55f) else Color.Transparent
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = iconForTile(member),
                                contentDescription = null,
                                tint = if (isMemberOn) GhAccentGreen else GhTextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = member.title,
                                color = if (isMemberOn) GhTextPrimary else GhTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 110.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GroupCardPopupModal(
    groupTile: SmartTile,
    allTiles: List<SmartTile>,
    bridgeSettings: BridgeSettings,
    musicProvider: MusicProvider,
    isMusicPlaying: Boolean,
    currentTrack: String,
    onUpdateGroupTile: (SmartTile) -> Unit,
    onUpdateMemberTile: (SmartTile) -> Unit,
    onDismiss: () -> Unit
) {
    val memberTiles = remember(allTiles, groupTile.id) {
        allTiles.filter { it.groupCardId == groupTile.id && it.id != groupTile.id }
    }
    val eligibleTilesForGroup = remember(allTiles, groupTile.id) {
        allTiles.filter {
            it.id != groupTile.id &&
                    it.type != TileType.GROUP_CARD &&
                    it.type != TileType.CLOCK_WEATHER
        }
    }

    var showManageMembersPanel by remember { mutableStateOf(memberTiles.isEmpty()) }
    var isEditingGroupName by remember { mutableStateOf(false) }
    var groupNameDraft by remember(groupTile.title) { mutableStateOf(groupTile.title) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhTileSurface,
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.5.dp, GhTileSurfaceElevated),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.90f)
                .padding(8.dp)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isCompactMobile = maxWidth < 620.dp
                val gridCols = when {
                    maxWidth < 560.dp -> 1
                    maxWidth < 920.dp -> 2
                    else -> 3
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (isCompactMobile) 14.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Top Bar: Group Icon + Editable Title + All ON / All OFF + Manage Devices + Close
                    ResponsiveSplitRow(
                        isStacked = isCompactMobile,
                        firstContent = { mod ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = mod
                            ) {
                                Surface(
                                    color = GhAccentGreen.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = resolveGroupCategoryIcon(groupTile.groupCategoryIcon),
                                            contentDescription = null,
                                            tint = GhAccentGreen,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    if (isEditingGroupName) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = groupNameDraft,
                                                onValueChange = { groupNameDraft = it },
                                                singleLine = true,
                                                textStyle = TextStyle(
                                                    color = GhTextPrimary,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = GhFont
                                                ),
                                                shape = RoundedCornerShape(10.dp),
                                                colors = claraFieldColors(),
                                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                                keyboardActions = KeyboardActions(
                                                    onDone = {
                                                        val clean = groupNameDraft.trim().ifBlank { groupTile.title }
                                                        onUpdateGroupTile(groupTile.copy(title = clean))
                                                        isEditingGroupName = false
                                                    }
                                                ),
                                                modifier = Modifier.widthIn(min = 160.dp, max = 260.dp)
                                            )
                                            FilledTonalIconButton(
                                                onClick = {
                                                    val clean = groupNameDraft.trim().ifBlank { groupTile.title }
                                                    onUpdateGroupTile(groupTile.copy(title = clean))
                                                    isEditingGroupName = false
                                                },
                                                modifier = Modifier.size(32.dp),
                                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                    containerColor = GhAccentGreen,
                                                    contentColor = Color(0xFF0F1114)
                                                )
                                            ) {
                                                Icon(Icons.Default.Check, contentDescription = "Save Group Name", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    } else {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "${groupTile.title} (${memberTiles.size} devices)",
                                                color = GhTextPrimary,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = GhFont
                                            )
                                            IconButton(
                                                onClick = {
                                                    groupNameDraft = groupTile.title
                                                    isEditingGroupName = true
                                                },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Edit,
                                                    contentDescription = "Rename Group Card",
                                                    tint = GhAccentBlue,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "Control individual devices below, or use '+ Add / Remove Devices' to manage this group",
                                        color = GhTextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        },
                        secondContent = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (memberTiles.isNotEmpty()) {
                                    FilledTonalButton(
                                        onClick = {
                                            memberTiles.forEach { m ->
                                                onUpdateMemberTile(
                                                    m.copy(
                                                        isActive = true,
                                                        isSocket2Active = if (m.multiGangSocketCount >= 2) true else m.isSocket2Active
                                                    )
                                                )
                                            }
                                        },
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = GhAccentGreen.copy(alpha = 0.22f),
                                            contentColor = GhAccentGreen
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("All ON", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            memberTiles.forEach { m ->
                                                onUpdateMemberTile(
                                                    m.copy(
                                                        isActive = false,
                                                        isSocket2Active = false
                                                    )
                                                )
                                            }
                                        },
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = GhTileSurfaceElevated,
                                            contentColor = GhTextSecondary
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text("All OFF", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                FilledTonalButton(
                                    onClick = { showManageMembersPanel = !showManageMembersPanel },
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = if (showManageMembersPanel) GhAccentBlue.copy(alpha = 0.25f) else GhTileSurfaceElevated,
                                        contentColor = GhAccentBlue
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(
                                        imageVector = if (showManageMembersPanel) Icons.Default.Check else Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (showManageMembersPanel) "Done Selecting" else "Add / Remove Devices",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                FilledTonalIconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                            }
                        }
                    )

                    HorizontalDivider(color = GhTileSurfaceElevated)

                    val autoMatchingCandidates = remember(eligibleTilesForGroup, groupTile, bridgeSettings.customRooms) {
                        eligibleTilesForGroup.filter {
                            doesTileMatchGroupCategoryOrRoom(it, groupTile, bridgeSettings.customRooms)
                        }
                    }

                    // Expandable Checklist to Add or Remove Any Tile to/from this Group Card
                    AnimatedVisibility(visible = showManageMembersPanel) {
                        Surface(
                            color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, GhAccentBlue.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Tick any device below to include it inside '${groupTile.title}' (untick to return it to the main dashboard):",
                                        color = GhTextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (autoMatchingCandidates.isNotEmpty()) {
                                        FilledTonalButton(
                                            onClick = {
                                                autoMatchingCandidates.forEach { match ->
                                                    if (match.groupCardId != groupTile.id) {
                                                        onUpdateMemberTile(
                                                            match.copy(
                                                                groupCardId = groupTile.id,
                                                                isVisible = true
                                                            )
                                                        )
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = GhAccentGreen.copy(alpha = 0.22f),
                                                contentColor = GhAccentGreen
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.AutoAwesome,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text(
                                                text = "Auto-Group by Room or Category (${autoMatchingCandidates.size})",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(eligibleTilesForGroup, key = { "assign_${it.id}" }) { candidate ->
                                        val isAssignedHere = candidate.groupCardId == groupTile.id
                                        FilterChip(
                                            selected = isAssignedHere,
                                            onClick = {
                                                val nextGroupId = if (isAssignedHere) "" else groupTile.id
                                                onUpdateMemberTile(
                                                    candidate.copy(
                                                        groupCardId = nextGroupId,
                                                        isVisible = true
                                                    )
                                                )
                                            },
                                            shape = RoundedCornerShape(50),
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = if (isAssignedHere) Icons.Default.Check else iconForTile(candidate),
                                                    contentDescription = null,
                                                    tint = if (isAssignedHere) GhAccentGreen else GhTextSecondary,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            },
                                            label = {
                                                Text(
                                                    text = candidate.title,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isAssignedHere) FontWeight.Bold else FontWeight.Medium
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Grid of Member Device Cards
                    if (memberTiles.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = resolveGroupCategoryIcon(groupTile.groupCategoryIcon),
                                    contentDescription = null,
                                    tint = GhTextSecondary,
                                    modifier = Modifier.size(44.dp)
                                )
                                Text(
                                    text = "No devices in '${groupTile.title}' yet.\nTap any device chip above to add it to this group!",
                                    color = GhTextSecondary,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                                if (autoMatchingCandidates.isNotEmpty()) {
                                    Button(
                                        onClick = {
                                            autoMatchingCandidates.forEach { match ->
                                                onUpdateMemberTile(
                                                    match.copy(
                                                        groupCardId = groupTile.id,
                                                        isVisible = true
                                                    )
                                                )
                                            }
                                        },
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = GhAccentGreen,
                                            contentColor = Color(0xFF0F1114)
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Auto-Group Matching Devices (${autoMatchingCandidates.size})",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(gridCols),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            items(memberTiles, key = { "grp_member_${it.id}" }) { member ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    GoogleHomeTileCard(
                                        tile = member.copy(colSpan = 1, rowSpan = 1, isTall = false),
                                        bridgeSettings = bridgeSettings,
                                        isOverlayModalOpen = false,
                                        isEditMode = false,
                                        isSelectedForSwap = false,
                                        unitHeight = 165.dp,
                                        musicProvider = musicProvider,
                                        isMusicPlaying = isMusicPlaying,
                                        currentTrack = currentTrack,
                                        allTiles = allTiles,
                                        onOpenSetupWizard = {},
                                        onToggleEditMode = {},
                                        onSelectOrSwap = {},
                                        onToggleColSpan = {},
                                        onToggleHeight = {},
                                        onDeleteTile = {
                                            onUpdateMemberTile(member.copy(groupCardId = ""))
                                        },
                                        onUpdateTile = { updatedMember ->
                                            onUpdateMemberTile(updatedMember)
                                        },
                                        onExpandCamera = {},
                                        onOpenMusicApp = {},
                                        onToggleMusicPlay = {},
                                        onSkipTrack = {},
                                        onPreviousTrack = {},
                                        onExecuteScene = {}
                                    )

                                    // Quick "Move back to Dashboard" link below each grouped card
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Text(
                                            text = "Ungroup to Dashboard",
                                            color = GhTextSecondary,
                                            fontSize = 10.sp,
                                            modifier = Modifier.clickable {
                                                onUpdateMemberTile(member.copy(groupCardId = ""))
                                            }
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
