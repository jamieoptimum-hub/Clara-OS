package com.jarvis.smarthomelauncher.widgets.radio.Settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.smarthomelauncher.SfHouseMintPillSwitch
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioScheduledRoutine
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioUserSettings

@Composable
fun RadioSettingsPopup(
    settings: RadioUserSettings,
    scheduledRoutines: List<RadioScheduledRoutine>,
    onUpdateSettings: (RadioUserSettings) -> Unit,
    onAddCustomStation: (name: String, streamUrl: String, genre: String, badge: String) -> Unit,
    onDeleteRoutine: (String) -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    var customName by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    var customGenre by remember { mutableStateOf("") }
    var customBadge by remember { mutableStateOf("") }

    val countryOptions = remember {
        listOf(
            "GB" to "United Kingdom",
            "IE" to "Ireland",
            "US" to "United States",
            "AU" to "Australia",
            "CA" to "Canada",
            "DE" to "Germany",
            "FR" to "France"
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhTileSurface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.5.dp, GhCardBorder),
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth(0.90f)
                .fillMaxHeight(0.85f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
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
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = null,
                            tint = GhAccentGreen,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = "Radio Settings & Custom Streams",
                                color = GhTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            )
                            Text(
                                text = "Stored persistently in local Clara SQLite storage",
                                color = GhTextSecondary,
                                fontSize = 11.sp,
                                fontFamily = GhFont
                            )
                        }
                    }

                    FilledTonalIconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(34.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = GhTileSurfaceElevated,
                            contentColor = GhTextPrimary
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(17.dp))
                    }
                }

                HorizontalDivider(color = GhCardBorder)

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    item {
                        Surface(
                            color = GhTileSurfaceElevated,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, GhCardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Dashboard & Metadata Preferences",
                                    color = GhTextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GhFont
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Live Track & Artist Stream Metadata",
                                            color = GhTextPrimary,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Resolves live song & presenter details via BBC RMS and ICY stream headers",
                                            color = GhTextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                    SfHouseMintPillSwitch(
                                        checked = settings.enableLiveTrackMetadata,
                                        onCheckedChange = {
                                            onUpdateSettings(settings.copy(enableLiveTrackMetadata = it))
                                        }
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Show Habit Recommendations on Widget",
                                            color = GhTextPrimary,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Displays recommended stations when card height allows",
                                            color = GhTextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                    SfHouseMintPillSwitch(
                                        checked = settings.showRecommendationsOnWidget,
                                        onCheckedChange = {
                                            onUpdateSettings(settings.copy(showRecommendationsOnWidget = it))
                                        }
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Favourite Station Chips on Widget",
                                        color = GhTextPrimary,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf(3, 4, 5, 6).forEach { count ->
                                            val selected = settings.maxWidgetFavourites == count
                                            Surface(
                                                color = if (selected) GhAccentGreen.copy(alpha = 0.22f) else GhTileSurface,
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (selected) GhAccentGreen else GhCardBorder
                                                ),
                                                modifier = Modifier.clickable {
                                                    onUpdateSettings(settings.copy(maxWidgetFavourites = count))
                                                }
                                            ) {
                                                Text(
                                                    text = "$count",
                                                    color = if (selected) GhAccentGreen else GhTextSecondary,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Local Area Region (Discover & Popular)",
                                        color = GhTextPrimary,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(countryOptions) { (code, label) ->
                                            val selected = settings.preferredCountryCode.equals(code, ignoreCase = true)
                                            Surface(
                                                color = if (selected) GhAccentGreen.copy(alpha = 0.22f) else GhTileSurface,
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(1.dp, if (selected) GhAccentGreen else GhCardBorder),
                                                modifier = Modifier.clickable {
                                                    onUpdateSettings(settings.copy(preferredCountryCode = code))
                                                }
                                            ) {
                                                Text(
                                                    text = "$label ($code)",
                                                    color = if (selected) GhAccentGreen else GhTextSecondary,
                                                    fontSize = 11.5.sp,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Surface(
                            color = GhTileSurfaceElevated,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, GhCardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Add Custom Stream URL (MP3 / AAC / HLS)",
                                    color = GhTextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = customName,
                                        onValueChange = { customName = it },
                                        label = { Text("Station Name") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1.4f)
                                    )
                                    OutlinedTextField(
                                        value = customBadge,
                                        onValueChange = { customBadge = it.take(4) },
                                        label = { Text("Badge") },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.6f)
                                    )
                                }
                                OutlinedTextField(
                                    value = customUrl,
                                    onValueChange = { customUrl = it },
                                    label = { Text("Direct Stream URL (https://...)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = customGenre,
                                        onValueChange = { customGenre = it },
                                        label = { Text("Genre / Tagline") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = {
                                            if (customName.isNotBlank() && customUrl.startsWith("http", ignoreCase = true)) {
                                                onAddCustomStation(customName, customUrl, customGenre, customBadge)
                                                customName = ""
                                                customUrl = ""
                                                customGenre = ""
                                                customBadge = ""
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = GhAccentGreen,
                                            contentColor = Color(0xFF141518)
                                        )
                                    ) {
                                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Save Stream", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    if (scheduledRoutines.isNotEmpty()) {
                        item {
                            Text(
                                text = "Active Radio Alarms & Automations (${scheduledRoutines.size})",
                                color = GhTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(scheduledRoutines, key = { it.id }) { routine ->
                            Surface(
                                color = GhTileSurfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, GhCardBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${routine.stationName} • ${routine.label}",
                                            color = GhTextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = routine.formattedSummary(),
                                            color = GhAccentGreen,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    IconButton(onClick = { onDeleteRoutine(routine.id) }) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete Routine",
                                            tint = GhAccentRed
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(
                                onClick = onClearHistory,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, GhAccentRed.copy(alpha = 0.6f))
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = GhAccentRed, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Clear Listening History", color = GhAccentRed, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}