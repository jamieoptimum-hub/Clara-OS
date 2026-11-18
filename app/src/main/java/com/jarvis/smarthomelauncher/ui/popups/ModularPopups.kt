package com.jarvis.smarthomelauncher.ui.popups

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.lifecycle.ViewModelProvider
import com.jarvis.smarthomelauncher.ui.modals.DeviceControlModalViewModel
import com.jarvis.smarthomelauncher.ui.viewmodels.findComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.RadialThermostatDialGauge
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ModularPopupContainer(
    tile: SmartTile,
    availableRooms: List<String>,
    onAssignRoom: (String) -> Unit,
    onRequestCreateNewRoom: () -> Unit,
    onUserInteraction: () -> Unit,
    onClose: () -> Unit,
    onOpenFullDetailsModal: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val context = LocalContext.current
    val bridge = remember(tile.id) { DeviceRepository.loadBridge(context) }
    val activity = remember(context) { context.findComponentActivity() }
    val modalVm = remember(activity, context) {
        if (activity != null) {
            ViewModelProvider(activity)[DeviceControlModalViewModel::class.java]
        } else {
            DeviceControlModalViewModel(context.applicationContext as Application)
        }
    }
    val isRefreshing = modalVm.refreshingByTileId[tile.id] == true
    val feedbackBanner = modalVm.feedbackBannerByTileId[tile.id]

    val accent = accentForTileType(tile)
    var roomMenuExpanded by remember { mutableStateOf(false) }
    var showDeviceBlackBoxModal by remember { mutableStateOf(false) }
    val roomChoices = remember(availableRooms) {
        availableRooms.ifEmpty { DEFAULT_ROOMS }.distinct()
    }

    SfHouseSunkenTileContainer(
        outerCornerRadius = 26.dp,
        innerCornerRadius = 20.dp,
        sunkenInsetPadding = 5.dp,
        innerBorderColor = accent.copy(alpha = 0.55f),
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth(0.92f)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                    Surface(
                        color = accent.copy(alpha = 0.18f),
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = iconForTile(tile),
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tile.title,
                            color = GhTextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${tile.deviceSubtype.label} • ${if (tile.isOnline) "Online" else "Offline"}",
                            color = if (tile.isOnline) GhTextSecondary else GhAccentRed,
                            fontSize = 11.sp,
                            fontFamily = GhFont
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (tile.serviceClass == null) {
                        HeroRoomOptionBox(
                            roomName = tile.roomOrSubtitle,
                            activeColor = accent,
                            modifier = Modifier
                                .width(136.dp)
                                .height(34.dp),
                            onClick = {
                                onUserInteraction()
                                roomMenuExpanded = true
                            }
                        ) {
                            DropdownMenu(
                                expanded = roomMenuExpanded,
                                onDismissRequest = { roomMenuExpanded = false },
                                modifier = Modifier.background(GhTileSurfaceElevated)
                            ) {
                                roomChoices.forEach { room ->
                                    DropdownMenuItem(
                                        text = { Text(room, color = GhTextPrimary, fontSize = 13.sp) },
                                        onClick = {
                                            roomMenuExpanded = false
                                            onAssignRoom(room)
                                        }
                                    )
                                }
                                HorizontalDivider(color = GhCardBorder)
                                DropdownMenuItem(
                                    text = { Text("+ New Room...", color = GhAccentGreen, fontSize = 12.sp) },
                                    onClick = {
                                        roomMenuExpanded = false
                                        onRequestCreateNewRoom()
                                    }
                                )
                            }
                        }
                    }

                    if (tile.serviceClass == null && tile.isConfiguredAndLinked) {
                        FilledTonalIconButton(
                            onClick = {
                                onUserInteraction()
                                modalVm.refreshDevice(tile = tile, bridge = bridge)
                            },
                            enabled = !isRefreshing,
                            modifier = Modifier.size(34.dp)
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    color = accent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(15.dp)
                                )
                            } else {
                                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh State", modifier = Modifier.size(17.dp))
                            }
                        }

                        FilledTonalIconButton(
                            onClick = {
                                onUserInteraction()
                                showDeviceBlackBoxModal = true
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Outlined.Analytics, contentDescription = "Device Black Box", modifier = Modifier.size(17.dp))
                        }
                    }

                    if (onOpenFullDetailsModal != null &&
                        (tile.isThermostatTile || tile.deviceSubtype == DeviceSubtype.TV || tile.isSwitchTile || tile.capabilities.isNotEmpty())
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                onClose()
                                onOpenFullDetailsModal()
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Outlined.Tune, contentDescription = "Advanced Controls", modifier = Modifier.size(17.dp))
                        }
                    }

                    FilledTonalIconButton(
                        onClick = onClose,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(17.dp))
                    }
                }
            }

            if (tile.needsCapabilitySync) {
                Surface(
                    color = GhAccentWarm.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, GhAccentWarm),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Device bound — capability sync incomplete.",
                            color = GhTextPrimary,
                            fontSize = 11.5.sp,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(
                            onClick = {
                                onUserInteraction()
                                modalVm.retryCapabilitySyncOnly(tile = tile, bridge = bridge)
                            },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Sync Capabilities", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            AnimatedVisibility(visible = feedbackBanner != null) {
                if (feedbackBanner != null) {
                    val (ok, msg) = feedbackBanner
                    val tint = if (ok) GhAccentGreen else GhAccentRed
                    Surface(
                        color = tint.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, tint.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = msg,
                                color = GhTextPrimary,
                                fontSize = 11.5.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { modalVm.clearFeedback(tile.id) },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = GhTextSecondary, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = GhCardBorder)
            content()
        }
    }

    if (showDeviceBlackBoxModal) {
        FlightRecorderHubModal(
            initialDeviceIdFilter = tile.canonicalGuid.ifBlank { tile.resolvedCanonicalGuid() },
            onDismiss = { showDeviceBlackBoxModal = false }
        )
    }
}

@Composable
fun ModularThermostatPopupContent(
    tile: SmartTile,
    onUpdateTile: (SmartTile) -> Unit,
    onUserInteraction: () -> Unit
) {
    val isEco = tile.hvacMode.equals("ECO", ignoreCase = true)
    val isOff = !tile.isActive || tile.hvacMode.equals("OFF", ignoreCase = true)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        RadialThermostatDialGauge(
            targetTemp = tile.targetTemperature,
            ambientTemp = tile.temperature,
            minTemp = 9f,
            maxTemp = 32f,
            isActiveHeating = tile.isActuallyHeating,
            isEcoMode = isEco,
            statusLabel = when {
                tile.isActuallyHeating -> "Heating"
                isEco -> "Eco Mode"
                !isOff -> "Holding Temp"
                else -> "Off"
            },
            dialSize = 196.dp,
            onDecreaseTemp = {
                onUserInteraction()
                val next = (tile.targetTemperature - 0.5f).coerceAtLeast(9f)
                onUpdateTile(tile.copy(targetTemperature = next, isActive = true, hvacMode = "HEAT"))
            },
            onIncreaseTemp = {
                onUserInteraction()
                val next = (tile.targetTemperature + 0.5f).coerceAtMost(32f)
                onUpdateTile(tile.copy(targetTemperature = next, isActive = true, hvacMode = "HEAT"))
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                Triple("Heat", Icons.Outlined.LocalFireDepartment, !isOff && !isEco),
                Triple("Eco", Icons.Outlined.Eco, isEco),
                Triple("Boost", Icons.Outlined.Bolt, tile.hvacMode.equals("BOOST", true)),
                Triple("Off", Icons.Default.PowerSettingsNew, isOff)
            ).forEach { (label, icon, selected) ->
                HeroRemoteQuickBtn(
                    label = label,
                    icon = icon,
                    isHighlighted = selected,
                    accentColor = if (label == "Heat" || label == "Boost") NestHeatingOrange else GhAccentGreen,
                    modifier = Modifier.weight(1f)
                ) {
                    onUserInteraction()
                    val mode = label.uppercase(Locale.US)
                    onUpdateTile(
                        tile.copy(
                            isActive = mode != "OFF",
                            hvacMode = mode,
                            targetTemperature = if (mode == "BOOST") 24f else tile.targetTemperature
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun ModularLightAndRgbPopupContent(
    tile: SmartTile,
    bridgeSettings: BridgeSettings,
    onUpdateTile: (SmartTile) -> Unit,
    onUserInteraction: () -> Unit
) {
    val accent = accentForTileType(tile)
    var brightness by remember(tile.id, tile.volume) { mutableFloatStateOf(tile.volume.coerceIn(1f, 100f)) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeroSocketPowerButtonCard(
            title = tile.title,
            subtitle = if (tile.isActive) "ON • ${brightness.roundToInt()}%" else "OFF",
            isOn = tile.isActive,
            accentColor = accent,
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
        ) {
            onUserInteraction()
            onUpdateTile(tile.copy(isActive = !tile.isActive))
        }

        if (tile.hasDimmingCapability) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Brightness", color = GhTextSecondary, fontSize = 12.sp)
                    Text("${brightness.roundToInt()}%", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = brightness,
                    onValueChange = {
                        onUserInteraction()
                        brightness = it
                    },
                    onValueChangeFinished = {
                        onUpdateTile(tile.copy(isActive = true, volume = brightness))
                    },
                    valueRange = 1f..100f,
                    colors = SliderDefaults.colors(activeTrackColor = accent, thumbColor = Color.White)
                )
            }
        }

        if (tile.isAmbientRgbTile) {
            if (tile.hasLytmiOrSyncBoxTraits) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(1, 2, 3, 4)) { hdmi ->
                        FilterChip(
                            selected = tile.lytmiHdmiInput == hdmi,
                            onClick = {
                                onUserInteraction()
                                onUpdateTile(tile.copy(isActive = true, lytmiHdmiInput = hdmi))
                            },
                            label = { Text("HDMI $hdmi", fontSize = 11.sp) }
                        )
                    }
                    items(listOf("VIDEO", "GAME", "MUSIC", "SCENE", "COLOR")) { mode ->
                        FilterChip(
                            selected = tile.lytmiSyncMode.equals(mode, true),
                            onClick = {
                                onUserInteraction()
                                onUpdateTile(tile.copy(isActive = true, lytmiSyncMode = mode))
                            },
                            label = { Text(mode, fontSize = 11.sp) }
                        )
                    }
                }
            }

            InteractiveRgbColorWheelPicker(
                currentColorArgb = tile.lytmiColorArgb,
                selectedPreset = bridgeSettings.accentColorPreset,
                isCompactMobile = true,
                onColorSelected = { newArgb, _ ->
                    onUserInteraction()
                    onUpdateTile(
                        tile.copy(
                            isActive = true,
                            lytmiColorArgb = newArgb,
                            lytmiSyncMode = "COLOR"
                        )
                    )
                }
            )
        }
    }
}

@Composable
fun ModularMediaPopupContent(
    tile: SmartTile,
    bridgeSettings: BridgeSettings,
    onUpdateTile: (SmartTile) -> Unit,
    onUserInteraction: () -> Unit,
    onOpenFullDetailsModal: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var vol by remember(tile.id, tile.volume) { mutableFloatStateOf(tile.volume.coerceIn(0f, 100f)) }
    var tvMuted by remember(tile.id) { mutableStateOf(false) }
    val isTvTile = tile.deviceSubtype == DeviceSubtype.TV || tile.type == TileType.LG_TV
    val hasTvVaultKey = remember(tile.id, tile.ipAddress, tile.macAddress) {
        !isTvTile || LgThinQWebOsClient.hasVerifiedVaultKey(context, tile.ipAddress, tile.macAddress, tile.serialNumber)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (isTvTile) {
            if (!hasTvVaultKey) {
                Surface(
                    color = GhAccentWarm.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, GhAccentWarm),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TV Pairing Required \u2014 Press Accept on your LG TV screen",
                            color = GhTextPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = onOpenFullDetailsModal,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GhAccentWarm,
                                contentColor = Color(0xFF0F1114)
                            )
                        ) {
                            Text("Pair TV", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            val shortcuts = remember { LgThinQWebOsClient.loadTvApps(context) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeroRemoteQuickBtn(
                    label = if (tile.isActive) "Power ON" else "Power OFF",
                    icon = Icons.Default.PowerSettingsNew,
                    isHighlighted = tile.isActive,
                    modifier = Modifier.weight(1f)
                ) {
                    onUserInteraction()
                    onUpdateTile(tile.copy(isActive = !tile.isActive))
                }
                HeroRemoteQuickBtn(
                    label = "Home",
                    icon = Icons.Outlined.Home,
                    modifier = Modifier.weight(1f)
                ) {
                    onUserInteraction()
                    LgThinQWebOsClient.sendRemoteButton(context, tile, bridgeSettings, LgRemoteButton.HOME, scope)
                }
                HeroRemoteQuickBtn(
                    label = "Full Remote",
                    icon = Icons.Outlined.SettingsRemote,
                    modifier = Modifier.weight(1f)
                ) {
                    onOpenFullDetailsModal()
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shortcuts, key = { it.id }) { app ->
                    FilterChip(
                        selected = tile.activeMediaSource.equals(app.label, true),
                        onClick = {
                            onUserInteraction()
                            LgThinQWebOsClient.launchAppShortcut(context, tile, bridgeSettings, app, scope)
                        },
                        label = { Text(app.label, fontSize = 11.sp) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeroRemoteQuickBtn(
                    label = "Vol -",
                    icon = Icons.Default.Remove,
                    modifier = Modifier.weight(1f)
                ) {
                    onUserInteraction()
                    vol = (vol - 2f).coerceAtLeast(0f)
                    LgThinQWebOsClient.stepVolume(context, tile, volumeUp = false, scope = scope)
                }
                HeroRemoteQuickBtn(
                    label = if (tvMuted) "Muted" else "Vol ${vol.roundToInt()}%",
                    icon = if (tvMuted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                    isHighlighted = tvMuted,
                    modifier = Modifier.weight(1f)
                ) {
                    onUserInteraction()
                    tvMuted = !tvMuted
                    LgThinQWebOsClient.toggleMute(context, tile, tvMuted, scope)
                }
                HeroRemoteQuickBtn(
                    label = "Vol +",
                    icon = Icons.Default.Add,
                    modifier = Modifier.weight(1f)
                ) {
                    onUserInteraction()
                    vol = (vol + 2f).coerceAtMost(100f)
                    LgThinQWebOsClient.stepVolume(context, tile, volumeUp = true, scope = scope)
                }
            }
        } else {
            if (tile.serviceClass == ServiceClass.RADIO || tile.isRadioHeroRequested) {
                val stations = bridgeSettings.allRadioStations
                val activeRadio by ClaraInAppMusicPlayer.activeRadioStationFlow.collectAsState()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(stations, key = { it.id }) { station ->
                        FilterChip(
                            selected = activeRadio?.id == station.id,
                            onClick = {
                                onUserInteraction()
                                ClaraInAppMusicPlayer.toggleRadioStation(station, audioManager, scope)
                            },
                            label = { Text(station.name, fontSize = 11.sp) }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Volume", color = GhTextSecondary, fontSize = 12.sp)
                    Text("${vol.roundToInt()}%", color = GhAccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = vol,
                    onValueChange = {
                        onUserInteraction()
                        vol = it
                    },
                    onValueChangeFinished = {
                        onUpdateTile(tile.copy(volume = vol, isActive = vol > 0.5f))
                    },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(activeTrackColor = GhAccentGreen, thumbColor = Color.White)
                )
            }
        }
    }
}

@Composable
fun ModularGenericDevicePopupContent(
    tile: SmartTile,
    bridgeSettings: BridgeSettings,
    onUpdateTile: (SmartTile) -> Unit,
    onUserInteraction: () -> Unit,
    onExpandCameraFullScreen: () -> Unit,
    onOpenFullDetailsModal: () -> Unit
) {
    val accent = accentForTileType(tile)

    when {
        tile.isCameraTile -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                ) {
                    SmartCameraStreamResolverView(
                        tile = tile.copy(isLiveFeedActive = true),
                        bridgeSettings = bridgeSettings,
                        enableAudio = true,
                        cropToFillTile = true,
                        onUpdateTile = onUpdateTile,
                        onVideoSurfaceTap = onExpandCameraFullScreen
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    HeroRemoteQuickBtn(
                        label = "Fullscreen & PTZ",
                        icon = Icons.Outlined.Fullscreen,
                        isHighlighted = true,
                        modifier = Modifier.weight(1f),
                        onClick = onExpandCameraFullScreen
                    )
                    HeroRemoteQuickBtn(
                        label = if (tile.isLiveFeedActive) "Pin Live Tile ON" else "Pin Live Tile",
                        icon = Icons.Outlined.Videocam,
                        isHighlighted = tile.isLiveFeedActive,
                        modifier = Modifier.weight(1f)
                    ) {
                        onUserInteraction()
                        onUpdateTile(tile.copy(isLiveFeedActive = !tile.isLiveFeedActive))
                    }
                }
            }
        }

        tile.isMultiGangSwitch -> {
            val s1On = tile.isSocketActive(1)
            val s2On = tile.isSocketActive(2)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    HeroSocketPowerButtonCard(
                        title = tile.socket1Name.ifBlank { "Socket 1" },
                        subtitle = if (s1On) "Powered ON" else "Powered OFF",
                        isOn = s1On,
                        accentColor = accent,
                        modifier = Modifier
                            .weight(1f)
                            .height(104.dp)
                    ) {
                        onUserInteraction()
                        onUpdateTile(tile.withUpdatedSocketState(1, !s1On))
                    }
                    HeroSocketPowerButtonCard(
                        title = tile.socket2Name.ifBlank { "Socket 2" },
                        subtitle = if (s2On) "Powered ON" else "Powered OFF",
                        isOn = s2On,
                        accentColor = accent,
                        modifier = Modifier
                            .weight(1f)
                            .height(104.dp)
                    ) {
                        onUserInteraction()
                        onUpdateTile(tile.withUpdatedSocketState(2, !s2On))
                    }
                }
                if (tile.hasPowerTelemetryCapability) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        EmbeddedTelemetryBadge("Live Power", "${"%.1f".format(tile.powerWatts)} W", accent, Modifier.weight(1f))
                        EmbeddedTelemetryBadge("Total Energy", "${"%.2f".format(tile.totalEnergyKwh)} kWh", GhAccentGreen, Modifier.weight(1f))
                    }
                }
            }
        }

        tile.isTowerFanTile -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TowerFanHeroIllustration(
                    isActive = tile.isActive,
                    speedLevel = tile.fanSpeedLevel,
                    isOscillating = tile.fanOscillating,
                    isIonizerActive = tile.fanIonizerActive,
                    accentColor = accent,
                    modifier = Modifier.size(120.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    HeroRemoteQuickBtn(
                        label = if (tile.isActive) "Power ON" else "Power OFF",
                        icon = Icons.Default.PowerSettingsNew,
                        isHighlighted = tile.isActive,
                        accentColor = accent,
                        modifier = Modifier.weight(1f)
                    ) {
                        onUserInteraction()
                        onUpdateTile(tile.copy(isActive = !tile.isActive))
                    }
                    HeroRemoteQuickBtn(
                        label = "Oscillate",
                        icon = Icons.Outlined.SyncAlt,
                        isHighlighted = tile.fanOscillating,
                        accentColor = accent,
                        modifier = Modifier.weight(1f)
                    ) {
                        onUserInteraction()
                        onUpdateTile(tile.copy(isActive = true, fanOscillating = !tile.fanOscillating))
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items((1..6).toList()) { spd ->
                        FilterChip(
                            selected = tile.fanSpeedLevel == spd && tile.isActive,
                            onClick = {
                                onUserInteraction()
                                onUpdateTile(tile.copy(isActive = true, fanSpeedLevel = spd, volume = (spd / 6f) * 100f))
                            },
                            label = { Text("Speed $spd", fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        tile.deviceSubtype == DeviceSubtype.VACUUM -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RoboticVacuumHeroIllustration(
                    isCleaning = tile.isActive,
                    accentColor = accent,
                    modifier = Modifier.size(116.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("CLEAN" to "Auto", "SPOT" to "Spot", "TURBO" to "Turbo", "DOCK" to "Dock").forEach { (code, lbl) ->
                        HeroRemoteQuickBtn(
                            label = lbl,
                            icon = if (code == "DOCK") Icons.Outlined.Home else Icons.Outlined.CleaningServices,
                            isHighlighted = if (code == "DOCK") !tile.isActive else (tile.isActive && tile.hvacMode.equals(code, true)),
                            accentColor = accent,
                            modifier = Modifier.weight(1f)
                        ) {
                            onUserInteraction()
                            onUpdateTile(tile.copy(isActive = code != "DOCK", hvacMode = code))
                        }
                    }
                }
            }
        }

        tile.isVehicleTile -> {
            val isJaguar = tile.type == TileType.JAGUAR_VEHICLE || tile.title.contains("Jaguar", true)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExecutiveVehicleCarIllustration(
                    isJaguar = isJaguar,
                    isLocked = tile.vehicleLocked,
                    isClimateActive = tile.vehicleClimateActive,
                    isCharging = tile.vehicleIsCharging,
                    accentColor = accent,
                    modifier = Modifier
                        .width(210.dp)
                        .height(88.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    EmbeddedTelemetryBadge("Charge / Fuel", "${tile.vehicleFuelOrBatteryPct}%", accent, Modifier.weight(1f))
                    EmbeddedTelemetryBadge("Range", "${tile.vehicleRangeMiles} mi", GhAccentGreen, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    HeroRemoteQuickBtn(
                        label = if (tile.vehicleLocked) "Locked" else "Unlocked",
                        icon = if (tile.vehicleLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                        isHighlighted = tile.vehicleLocked,
                        accentColor = accent,
                        modifier = Modifier.weight(1f)
                    ) {
                        onUserInteraction()
                        val nextLock = !tile.vehicleLocked
                        onUpdateTile(tile.copy(vehicleLocked = nextLock, isActive = nextLock, hvacMode = "VEHICLE_LOCK"))
                    }
                    HeroRemoteQuickBtn(
                        label = if (tile.vehicleClimateActive) "Climate ON" else "Pre-Condition",
                        icon = Icons.Outlined.Thermostat,
                        isHighlighted = tile.vehicleClimateActive,
                        accentColor = NestHeatingOrange,
                        modifier = Modifier.weight(1f)
                    ) {
                        onUserInteraction()
                        onUpdateTile(tile.copy(vehicleClimateActive = !tile.vehicleClimateActive, hvacMode = "VEHICLE_CLIMATE"))
                    }
                }
            }
        }

        tile.isGuestWifiHeroRequested -> {
            val cfg = bridgeSettings.guestWifiConfig
            val wifiPayload = "WIFI:T:${cfg.securityType};S:${cfg.ssid};P:${cfg.password};;"
            val qrMatrix = remember(wifiPayload) { encodeIso18004QrMatrix(wifiPayload) }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.size(164.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        val modules = qrMatrix.size.coerceAtLeast(1)
                        val cell = size.minDimension / modules
                        for (r in qrMatrix.indices) {
                            for (c in qrMatrix[r].indices) {
                                if (qrMatrix[r][c]) {
                                    drawRect(
                                        color = Color.Black,
                                        topLeft = Offset(c * cell, r * cell),
                                        size = Size(cell, cell)
                                    )
                                }
                            }
                        }
                    }
                }
                Text("Network: ${cfg.ssid}", color = GhTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Scan camera to join Guest Wi-Fi", color = GhTextSecondary, fontSize = 11.sp)
            }
        }

        else -> {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroSocketPowerButtonCard(
                    title = tile.title,
                    subtitle = if (tile.isActive) "Powered ON" else "Powered OFF",
                    isOn = tile.isActive,
                    accentColor = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                ) {
                    onUserInteraction()
                    onUpdateTile(tile.copy(isActive = !tile.isActive))
                }
                if (tile.hasPowerTelemetryCapability) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        EmbeddedTelemetryBadge("Live Power", "${"%.1f".format(tile.powerWatts)} W", accent, Modifier.weight(1f))
                        EmbeddedTelemetryBadge("Total Energy", "${"%.2f".format(tile.totalEnergyKwh)} kWh", GhAccentGreen, Modifier.weight(1f))
                    }
                }
                if (tile.isSwitchTile || tile.capabilities.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onOpenFullDetailsModal,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, GhCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Timers, Sockets & Hardware Capabilities", color = GhTextPrimary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
