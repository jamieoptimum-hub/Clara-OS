package com.jarvis.smarthomelauncher

import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.SpeakerGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// GOOGLE NEST MINI SPEAKERS & MULTI-ROOM AUDIO GROUPS TAB
// ---------------------------------------------------------------------------
@Composable
fun CastSpeakersAndGroupsTab(
    context: Context,
    tiles: MutableList<SmartTile>,
    discoveredCastDevices: List<DiscoveredCastDevice>,
    castSpeakerGroups: List<CastSpeakerGroup>,
    isScanningCast: Boolean,
    compactInputTextStyle: TextStyle,
    onScanCastDevices: () -> Unit,
    onTestChimeOnTile: (SmartTile) -> Unit,
    onStatusBanner: (String) -> Unit
) {
    var newGroupName by remember { mutableStateOf("Downstairs Speakers") }
    var selectedMemberIds by remember(discoveredCastDevices) {
        mutableStateOf(
            discoveredCastDevices.filter { !it.isCastGroup }
                .map { it.id }
                .toSet()
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompactMobile = maxWidth < 600.dp

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                SunkenWrapHeightCard(
                    outerCornerRadius = 18.dp,
                    innerCornerRadius = 14.dp,
                    sunkenInsetPadding = 5.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        ResponsiveSplitRow(
                            isStacked = isCompactMobile,
                            firstContent = { mod ->
                                Column(modifier = mod) {
                                    Text(
                                        text = "Google Nest Mini Speakers & Multi-Room Audio",
                                        color = GhTextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = GhFont
                                    )
                                    Text(
                                        text = "Automatically discovers speakers on your Wi-Fi for live volume control, doorbell chimes, and multi-room music.",
                                        color = GhTextSecondary,
                                        fontSize = 12.sp,
                                        fontFamily = GhFont
                                    )
                                }
                            },
                            secondContent = {
                                Button(
                                    onClick = onScanCastDevices,
                                    shape = RoundedCornerShape(9.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = GhAccentGreen,
                                        contentColor = Color(0xFF141518)
                                    )
                                ) {
                                    Icon(Icons.Outlined.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isScanningCast) "Finding Speakers..." else "Find Speakers",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        )
                    }
                }
            }

            if (discoveredCastDevices.isNotEmpty()) {
                val individualSpeakers = discoveredCastDevices.filter { !it.isCastGroup }.ifEmpty { discoveredCastDevices }
                item {
                    SunkenWrapHeightCard(
                        outerCornerRadius = 18.dp,
                        innerCornerRadius = 14.dp,
                        sunkenInsetPadding = 5.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ResponsiveSplitRow(
                                isStacked = isCompactMobile,
                                firstContent = { mod ->
                                    Column(modifier = mod) {
                                        Text(
                                            text = "Create Multi-Room Speaker Group",
                                            color = GhAccentGreen,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = GhFont
                                        )
                                        Text(
                                            text = "Select the speakers you want to group together so you can control their volume and play announcements across all of them at once.",
                                            color = GhTextSecondary,
                                            fontSize = 11.sp,
                                            fontFamily = GhFont
                                        )
                                    }
                                },
                                secondContent = {
                                    if (individualSpeakers.size >= 2) {
                                        FilledTonalButton(
                                            onClick = {
                                                val allGroup = CastSpeakerGroup(
                                                    name = "All Home Speakers",
                                                    memberIps = individualSpeakers.map { "${it.ipAddress}:${it.port}" },
                                                    memberNames = individualSpeakers.map { it.name },
                                                    volumePercent = individualSpeakers.map { it.volumePercent }.average().toFloat()
                                                )
                                                val downstairsKeywords = listOf("living", "lounge", "kitchen", "dining", "hall", "office", "study", "downstairs")
                                                val downstairsMembers = individualSpeakers.filter { spk ->
                                                    downstairsKeywords.any { kw -> spk.name.contains(kw, ignoreCase = true) }
                                                }
                                                val groupsToSave = mutableListOf(allGroup)
                                                if (downstairsMembers.size >= 2 && downstairsMembers.size < individualSpeakers.size) {
                                                    groupsToSave.add(
                                                        CastSpeakerGroup(
                                                            name = "Downstairs Speakers",
                                                            memberIps = downstairsMembers.map { "${it.ipAddress}:${it.port}" },
                                                            memberNames = downstairsMembers.map { it.name },
                                                            volumePercent = downstairsMembers.map { it.volumePercent }.average().toFloat()
                                                        )
                                                    )
                                                }
                                                val updatedGroups = castSpeakerGroups.toMutableList().apply {
                                                    for (g in groupsToSave) {
                                                        removeAll { it.name.equals(g.name, ignoreCase = true) }
                                                        add(0, g)
                                                    }
                                                }
                                                DeviceRepository.saveCastGroups(context, updatedGroups)
                                                for (g in groupsToSave) {
                                                    if (tiles.none { it.title.equals(g.name, ignoreCase = true) }) {
                                                        UniversalOnboardingEngine.onboardCompatibilityTile(
                                                            context = context,
                                                            candidateTile = SmartTile(
                                                                title = g.name,
                                                                roomOrSubtitle = g.memberNames.joinToString(" + "),
                                                                type = TileType.NEST_MINI,
                                                                colSpan = 1,
                                                                isVisible = true,
                                                                isOnline = true,
                                                                volume = g.volumePercent,
                                                                ipAddress = g.memberIps.joinToString(","),
                                                                entityId = g.id,
                                                                companionAppPackage = "com.google.android.apps.chromecast.app",
                                                                deviceClassOverride = DeviceClass.MEDIA,
                                                                deviceSubtypeOverride = DeviceSubtype.CAST_GROUP,
                                                                activeProviderId = ProviderId.GOOGLE_CAST.id
                                                            ),
                                                            tiles = tiles,
                                                            sourceLabel = "Google Cast Auto-Group"
                                                        )
                                                    }
                                                }
                                                onStatusBanner("SUCCESS: Auto-created '${groupsToSave.joinToString(" & ") { it.name }}' with ${individualSpeakers.size} speakers!")
                                            },
                                            shape = RoundedCornerShape(9.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = GhAccentGreen.copy(alpha = 0.20f),
                                                contentColor = GhAccentGreen
                                            )
                                        ) {
                                            Icon(Icons.Outlined.SpeakerGroup, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "1-Tap: Auto-Group All (${individualSpeakers.size})",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            )

                            ResponsiveSplitRow(
                                isStacked = isCompactMobile,
                                firstContent = { mod ->
                                    OutlinedTextField(
                                        value = newGroupName,
                                        onValueChange = { newGroupName = it },
                                        label = { Text("Speaker Group Name", fontSize = 11.sp) },
                                        textStyle = compactInputTextStyle,
                                        shape = RoundedCornerShape(14.dp),
                                        colors = claraFieldColors(),
                                        singleLine = true,
                                        modifier = mod
                                    )
                                },
                                secondContent = {
                                    Button(
                                        onClick = {
                                            val chosenMembers = discoveredCastDevices.filter {
                                                it.id in selectedMemberIds
                                            }
                                            if (chosenMembers.isEmpty()) {
                                                onStatusBanner("Select at least one speaker below to create a group.")
                                                return@Button
                                            }
                                            val cleanName = newGroupName.trim().ifBlank { "All Speakers" }
                                            val group = CastSpeakerGroup(
                                                name = cleanName,
                                                memberIps = chosenMembers.map { "${it.ipAddress}:${it.port}" },
                                                memberNames = chosenMembers.map { it.name },
                                                volumePercent = chosenMembers.map { it.volumePercent }.average().toFloat()
                                            )
                                            val updatedGroups = castSpeakerGroups.toMutableList().apply {
                                                removeAll { it.name.equals(cleanName, ignoreCase = true) }
                                                add(0, group)
                                            }
                                            DeviceRepository.saveCastGroups(context, updatedGroups)

                                            val joinedIps = group.memberIps.joinToString(",")
                                            UniversalOnboardingEngine.onboardCompatibilityTile(
                                                context = context,
                                                candidateTile = SmartTile(
                                                    title = group.name,
                                                    roomOrSubtitle = group.memberNames.joinToString(" + "),
                                                    type = TileType.NEST_MINI,
                                                    colSpan = 1,
                                                    isVisible = true,
                                                    isOnline = true,
                                                    volume = group.volumePercent,
                                                    ipAddress = joinedIps,
                                                    entityId = group.id,
                                                    companionAppPackage = "com.google.android.apps.chromecast.app",
                                                    deviceClassOverride = DeviceClass.MEDIA,
                                                    deviceSubtypeOverride = DeviceSubtype.CAST_GROUP,
                                                    activeProviderId = ProviderId.GOOGLE_CAST.id
                                                ),
                                                tiles = tiles,
                                                sourceLabel = "Google Cast Group Builder"
                                            )
                                            onStatusBanner("SUCCESS: Created speaker group '$cleanName' and added to Volume card!")
                                        },
                                        shape = RoundedCornerShape(9.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = GhAccentGreen,
                                            contentColor = Color(0xFF141518)
                                        )
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Create Speaker Group", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            )

                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(discoveredCastDevices, key = { "pick_${it.id}" }) { dev ->
                                    val isSelected = dev.id in selectedMemberIds
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            val next = selectedMemberIds.toMutableSet()
                                            if (!next.add(dev.id)) next.remove(dev.id)
                                            selectedMemberIds = next
                                        },
                                        shape = RoundedCornerShape(50),
                                        label = { Text("${dev.name} (${dev.volumePercent.roundToInt()}%)", fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (castSpeakerGroups.isNotEmpty()) {
                item {
                    Text(
                        text = "Saved Speaker Groups (${castSpeakerGroups.size})",
                        color = GhAccentGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(castSpeakerGroups, key = { "saved_grp_${it.id}" }) { grp ->
                    val alreadyOnDashboard = tiles.any { it.entityId == grp.id || it.title.equals(grp.name, true) }
                    SunkenWrapHeightCard(
                        outerCornerRadius = 16.dp,
                        innerCornerRadius = 12.dp,
                        sunkenInsetPadding = 4.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = grp.name,
                                    color = GhTextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = grp.memberNames.joinToString(" + "),
                                    color = GhTextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!alreadyOnDashboard) {
                                    FilledTonalButton(
                                        onClick = {
                                            UniversalOnboardingEngine.onboardCompatibilityTile(
                                                context = context,
                                                candidateTile = SmartTile(
                                                    title = grp.name,
                                                    roomOrSubtitle = grp.memberNames.joinToString(" + "),
                                                    type = TileType.NEST_MINI,
                                                    colSpan = 1,
                                                    isVisible = true,
                                                    isOnline = true,
                                                    volume = grp.volumePercent,
                                                    ipAddress = grp.memberIps.joinToString(","),
                                                    entityId = grp.id,
                                                    companionAppPackage = "com.google.android.apps.chromecast.app",
                                                    deviceClassOverride = DeviceClass.MEDIA,
                                                    deviceSubtypeOverride = DeviceSubtype.CAST_GROUP,
                                                    activeProviderId = ProviderId.GOOGLE_CAST.id
                                                ),
                                                tiles = tiles,
                                                sourceLabel = "Google Cast Saved Group"
                                            )
                                            onStatusBanner("Added group '${grp.name}' to Volume card!")
                                        },
                                        shape = RoundedCornerShape(9.dp)
                                    ) {
                                        Text("Add to Volume Card", fontSize = 11.sp)
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        val next = castSpeakerGroups.filterNot { it.id == grp.id }
                                        DeviceRepository.saveCastGroups(context, next)
                                        onStatusBanner("Removed speaker group '${grp.name}'.")
                                    }
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Group", tint = GhAccentRed)
                                }
                            }
                        }
                    }
                }
            }

            if (discoveredCastDevices.isNotEmpty()) {
                item {
                    Text(
                        text = "Discovered Speakers (${discoveredCastDevices.size})",
                        color = GhAccentGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(discoveredCastDevices, key = { "dev_${it.id}" }) { dev ->
                    val endpoint = "${dev.ipAddress}:${dev.port}"
                    val existingTileIdx = tiles.indexOfFirst {
                        it.type == TileType.NEST_MINI &&
                                (it.ipAddress == endpoint || it.ipAddress == dev.ipAddress || it.title.equals(dev.name, true))
                    }
                    DiscoveredItemRow(
                        title = dev.name,
                        subtitle = "${dev.modelName} \u2022 ${dev.statusText} \u2022 Volume ${dev.volumePercent.roundToInt()}%",
                        icon = if (dev.isCastGroup) Icons.Outlined.SpeakerGroup else Icons.Outlined.Speaker,
                        alreadyAdded = existingTileIdx >= 0,
                        secondaryActionLabel = if (existingTileIdx >= 0) "Test Chime" else null,
                        onSecondaryAction = if (existingTileIdx >= 0) {
                            { onTestChimeOnTile(tiles[existingTileIdx]) }
                        } else null,
                        onAdd = {
                            UniversalOnboardingEngine.onboardCompatibilityTile(
                                context = context,
                                candidateTile = SmartTile(
                                    title = dev.name,
                                    roomOrSubtitle = inferRoomFromDeviceTitle(dev.name),
                                    type = TileType.NEST_MINI,
                                    colSpan = 1,
                                    isVisible = true,
                                    isOnline = true,
                                    volume = dev.volumePercent,
                                    ipAddress = endpoint,
                                    entityId = dev.id,
                                    companionAppPackage = "com.google.android.apps.chromecast.app",
                                    deviceClassOverride = DeviceClass.MEDIA,
                                    deviceSubtypeOverride = if (dev.isCastGroup) DeviceSubtype.CAST_GROUP else DeviceSubtype.SPEAKER,
                                    activeProviderId = ProviderId.GOOGLE_CAST.id
                                ),
                                tiles = tiles,
                                sourceLabel = "Google Cast Discovery"
                            )
                            onStatusBanner("Added '${dev.name}' to Volume card!")
                        }
                    )
                }
            }
        }
    }
}
