package com.jarvis.smarthomelauncher.ui.screens

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.viewmodels.OnboardingWizardViewModel
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun CategorizedSetupWizardDialog(
    tiles: MutableList<SmartTile>,
    bridgeSettings: BridgeSettings,
    onSaveAll: (BridgeSettings) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onToggleEditMode: () -> Unit = {},
    onDismiss: () -> Unit
) {
    SetupWizardFullScreen(
        tiles = tiles,
        bridgeSettings = bridgeSettings,
        initialSection = SettingsSidebarSection.DEVICE_CONFIG,
        initialTab = SetupPageTab.AUTO_DISCOVER,
        onSaveAll = onSaveAll,
        onOpenNotificationAccess = onOpenNotificationAccess,
        onToggleEditMode = onToggleEditMode,
        onDismiss = onDismiss
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardFullScreen(
    tiles: MutableList<SmartTile>,
    bridgeSettings: BridgeSettings,
    automationRulesExternal: MutableList<AutomationRule>? = null,
    initialSection: SettingsSidebarSection = SettingsSidebarSection.DEVICE_CONFIG,
    initialTab: SetupPageTab = SetupPageTab.AUTO_DISCOVER,
    onSaveAll: (BridgeSettings) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onToggleEditMode: () -> Unit = {},
    onSpeak: (String) -> Unit = {},
    onTriggerVoicePrompt: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    
    val activity = remember(context) { 
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<androidx.activity.ComponentActivity>()
            .firstOrNull() 
    }
    val wizardVm = remember(activity, context) {
        if (activity != null) {
            androidx.lifecycle.ViewModelProvider(activity as androidx.lifecycle.ViewModelStoreOwner)[com.jarvis.smarthomelauncher.ui.viewmodels.OnboardingWizardViewModel::class.java]
        } else {
            com.jarvis.smarthomelauncher.ui.viewmodels.OnboardingWizardViewModel(context.applicationContext as android.app.Application)
        }
    }
    
    // Trigger one-time initialization of the UI state from BridgeSettings
    LaunchedEffect(bridgeSettings) {
        wizardVm.initializeSetupUiState(bridgeSettings, initialSection, initialTab)
    }

    // Temporary UI State moved to wizardVm
    
    val internalAutomationRules = remember {
        mutableStateListOf<AutomationRule>().apply {
            addAll(DeviceRepository.loadAutomations(context))
        }
    }
    val automationRules = automationRulesExternal ?: internalAutomationRules

    var showSpeakerGroupsDrawer by remember { mutableStateOf(false) }
    var pendingNestEntryKeyForWebModal by remember { mutableStateOf("") }
    var editingTileIndex by remember { mutableStateOf<Int?>(null) }
    
    var isSigningIntoNest by remember { mutableStateOf(false) }
    var showNestWebLoginModal by remember { mutableStateOf(false) }

    var showSelfHealingLogPanel by remember { mutableStateOf(false) }
    val integrationHealthMap by JarvisLiveBridge.integrationHealthFlow.collectAsState()
    val selfHealingLogs by JarvisLiveBridge.selfHealingLogsFlow.collectAsState()
    val discoveredMeshPeers by JarvisLiveBridge.discoveredMeshPeersFlow.collectAsState()

    LaunchedEffect(
        wizardVm.haUrl, wizardVm.haToken, wizardVm.imouAppId, wizardVm.imouAppSecret, wizardVm.imouApiHost,
        wizardVm.ezvizAppKey, wizardVm.ezvizAppSecret, wizardVm.ezvizApiHost,
        wizardVm.nestProjectId, wizardVm.nestClientId, wizardVm.nestClientSecret, wizardVm.nestRefreshToken, wizardVm.nestPubSubSubscription,
        wizardVm.tuyaAccessId, wizardVm.tuyaAccessSecret, wizardVm.tuyaUid,
        wizardVm.tplinkEmail, wizardVm.tplinkPassword, wizardVm.ewelinkEmail, wizardVm.ewelinkPassword,
        wizardVm.mqttBrokerHost, wizardVm.mqttBrokerPort, wizardVm.mqttUsername, wizardVm.mqttPassword,
        wizardVm.selectedMusicProvider, wizardVm.selectedThemeMode, wizardVm.selectedAccentPreset, wizardVm.customAccentColorArgb,
        wizardVm.selectedClockFaceStyle, wizardVm.selectedHeroTimeoutSec, wizardVm.keepScreenAwake, wizardVm.selectedSleepTimeoutSec,
        wizardVm.enablePresenceWake, wizardVm.enableVoiceAnnouncements, wizardVm.selectedCloudAutoPauseSec,
        wizardVm.showRoomLabelsOnTiles, wizardVm.customRoomsList, wizardVm.dashboardGreetingName,
        wizardVm.enableVoiceControl, wizardVm.enableWakeWordListening, wizardVm.voiceWakeWord,
        wizardVm.localLlmEndpointUrl, wizardVm.smartThingsToken, wizardVm.teslaWallConnectorIp, wizardVm.frigateHostUrl,
        wizardVm.enableNestMiniDoorbellChime, wizardVm.nestMiniDoorbellVolume, wizardVm.doorbellChimeMessage,
        wizardVm.doorbellChimeTargetIps, wizardVm.customDoorbellMp3Uri, wizardVm.customAlarmMp3Uri,
        wizardVm.enableSwipeableSlideshowPage, wizardVm.showClockOnSlideshowPage, wizardVm.slideshowSourceType,
        wizardVm.slideshowCloudUrl, wizardVm.slideshowLocalUris, wizardVm.slideshowIntervalSeconds, wizardVm.slideshowIncludeSubfolders,
        wizardVm.selectedDashboardTemplate, wizardVm.dashboardSlotWidgetTypes, wizardVm.dashboardCardWidthWeights,
        wizardVm.dashboardCardHeightWeights, wizardVm.dashboardCardZoomScales,
        wizardVm.selectedDpiPreset, wizardVm.customDpiScalePct, wizardVm.selectedGridSizePreset,
        wizardVm.selectedNavDockPosition, wizardVm.invertNavOrder, wizardVm.activeUserRole, wizardVm.rolePinCode, wizardVm.requirePinForAdminAndVehicles,
        wizardVm.autoOnboardNewDevicesEnabled,
        wizardVm.tariffDayRateText, wizardVm.tariffNightRateText, wizardVm.tariffStandingChargeText,
        wizardVm.currencySymbol, wizardVm.offPeakStartHour, wizardVm.offPeakEndHour,
        wizardVm.securityHomeTempText, wizardVm.securityAwayTempText, wizardVm.securityNightTempText, wizardVm.securityVacationTempText
    ) {
        delay(250L)
        DeviceRepository.saveBridge(context, wizardVm.currentTempBridge(bridgeSettings))
    }

    val settingsPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val newList = wizardVm.slideshowLocalUris.toMutableList()
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                val str = uri.toString()
                if (str !in newList) {
                    newList.add(str)
                }
            }
            PhotoSlideshowResolver.clearCloudCache()
            wizardVm.slideshowLocalUris = newList
            val updated = wizardVm.currentTempBridge(bridgeSettings)
            DeviceRepository.saveBridge(context, updated)
            wizardVm.statusBanner = "SUCCESS: Selected ${newList.size} local photo(s) for slideshow!"
        }
    }

    val doorbellMp3PickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            wizardVm.customDoorbellMp3Uri = uri.toString()
            JarvisLiveBridge.activeCustomMp3Uri = wizardVm.customDoorbellMp3Uri
            wizardVm.statusBanner = "SUCCESS: Selected custom Doorbell MP3 file!"
        }
    }

    val alarmMp3PickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            wizardVm.customAlarmMp3Uri = uri.toString()
            wizardVm.statusBanner = "SUCCESS: Selected custom Security Alarm MP3 file!"
        }
    }

    var isScanningCastSpeakers by remember { mutableStateOf(false) }

    val discoveredCastDevices by JarvisLiveBridge.discoveredCastDevicesFlow.collectAsState()
    val castSpeakerGroups by JarvisLiveBridge.castSpeakerGroupsFlow.collectAsState()

    val hiddenTilesCount = tiles.count { !it.isVisible }

    DisposableEffect(Unit) {
        CameraStreamSessionManager.muteAll()
        onDispose {
            TuyaDirectPairingManager.stopPairing()
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            wizardVm.statusBanner = "Microphone permission granted! Hands-free '${wizardVm.voiceWakeWord}' voice control is active."
        } else {
            wizardVm.statusBanner = "Microphone permission is required for voice control."
        }
    }

    fun refreshCastSpeakersList() {
        if (isScanningCastSpeakers) return
        scope.launch {
            isScanningCastSpeakers = true
            val found = GoogleCastLanClient.discoverCastDevices(context)
            isScanningCastSpeakers = false
            wizardVm.statusBanner = if (found.isNotEmpty()) {
                "Found ${found.size} speaker(s) on your Wi-Fi!"
            } else {
                "No speakers found yet. Ensure your speakers are powered on and connected to Wi-Fi."
            }
        }
    }

    val compactInputTextStyle = TextStyle(fontSize = 13.sp, fontFamily = GhFont, color = GhTextPrimary)

    fun syncDiscoveredNestThermostatsToDashboard() {
        val preservedRefresh = com.jarvis.smarthomelauncher.core.ClaraSecretVault
            .getNestSdmRefreshToken(context)
            ?: wizardVm.nestRefreshToken.takeIf { it.isNotBlank() && it != "nest_web_session_active" }.orEmpty()
        wizardVm.nestRefreshToken = preservedRefresh
        val updatedBridge = wizardVm.currentTempBridge(bridgeSettings).copy(nestRefreshToken = preservedRefresh)
        DeviceRepository.saveBridge(context, updatedBridge)
        PlayerBackgroundScope.launch {
            val discoverRes = NestSdmApiClient.discoverNestDevices(updatedBridge)
            withContext(Dispatchers.Main) {
                isSigningIntoNest = false
                if (discoverRes.isSuccess) {
                    val found = discoverRes.getOrThrow()
                    var count = 0
                    for (nd in found) {
                        val nestIdentity = ResolvedDeviceIdentity(
                            identityKey = "id_cloud_${nd.deviceName.lowercase(Locale.US)}",
                            displayName = nd.displayName,
                            suggestedRoom = nd.roomName.ifBlank { inferRoomFromDeviceTitle(nd.displayName, wizardVm.customRoomsList) },
                            deviceClass = nd.suggestedType.toSemanticDeviceClass(),
                            suggestedType = nd.suggestedType,
                            combinedConfidence = 0.96f,
                            primaryProviderId = ProviderId.GOOGLE_NEST.id,
                            fallbackProviderIds = emptyList(),
                            onboardingRequirement = OnboardingRequirement.ZeroTouchReady,
                            cloudDeviceId = nd.deviceName,
                            hardwareSerialOrNodeId = nd.deviceName,
                            isActive = nd.hvacMode.equals("HEAT", ignoreCase = true),
                            isOnline = nd.isOnline,
                            temperatureC = nd.ambientTempC,
                            targetTempC = nd.targetTempC,
                            humidityPct = nd.humidityPct,
                            hvacMode = nd.hvacMode,
                            hvacStatus = nd.hvacStatus,
                            protocolBadges = listOf("Climate Link")
                        )
                        val onboardRes = UniversalOnboardingEngine.onboardResolvedIdentity(
                            context = context,
                            identity = nestIdentity,
                            tiles = tiles,
                            bridgeSettings = updatedBridge,
                            scope = scope
                        )
                        if (onboardRes.isSuccess) {
                            count++
                        }
                    }
                    if (count > 0) {
                        DeviceRepository.saveTiles(context, tiles)
                    }
                    wizardVm.statusBanner = "SUCCESS: Imported $count Google Nest Thermostat(s) to your dashboard!"
                } else {
                    wizardVm.statusBanner = "Wait for your thermostats to load on the Nest screen, then tap 'Done & Import to Clara'."
                }
            }
        }
    }

    // Section 3: Never auto-trigger LAN/BLE/AP scans before the user chooses a Step 1 mode
    LaunchedEffect(showSpeakerGroupsDrawer) {
        if (showSpeakerGroupsDrawer && discoveredCastDevices.isEmpty()) {
            refreshCastSpeakersList()
        }
    }

    val handleFinishAndCloseWizard = {
        TuyaDirectPairingManager.stopPairing()
        CameraStreamSessionManager.muteAll()
        val updated = wizardVm.currentTempBridge(bridgeSettings)
        DeviceRepository.saveBridge(context, updated)
        DeviceRepository.saveTiles(context, tiles)
        DeviceRepository.saveAutomations(context, automationRules)
        onSaveAll(updated)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(GhBackground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* Consume background touches */ }
    ) {
        val isWideTabletLayout = maxWidth >= 720.dp
        val isCompactMobile = maxWidth < 600.dp
        val outerHPad = if (isCompactMobile) 12.dp else 20.dp
        val outerVPad = if (isCompactMobile) 10.dp else 16.dp

        @Composable
        fun ColumnScope.RenderActiveSectionContent() {
            if (wizardVm.statusBanner.isNotBlank()) {
                Surface(
                    color = GhTileSurfaceElevated,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = wizardVm.statusBanner,
                            color = if (wizardVm.statusBanner.startsWith("SUCCESS") || wizardVm.statusBanner.startsWith("Added") || wizardVm.statusBanner.startsWith("Saved") || wizardVm.statusBanner.startsWith("Paired") || wizardVm.statusBanner.startsWith("Connected") || wizardVm.statusBanner.startsWith("Executed") || wizardVm.statusBanner.startsWith("Found") || wizardVm.statusBanner.startsWith("Tested") || wizardVm.statusBanner.startsWith("Accent") || wizardVm.statusBanner.startsWith("Hero") || wizardVm.statusBanner.startsWith("Clock") || wizardVm.statusBanner.startsWith("Applied") || wizardVm.statusBanner.startsWith("Self-Healing") || wizardVm.statusBanner.startsWith("Digital"))
                                GhAccentGreen else GhAccentWarm,
                            fontSize = 12.sp,
                            fontFamily = GhFont,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { wizardVm.statusBanner = "" }, modifier = Modifier.size(22.dp)) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = GhTextSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            when (wizardVm.activeSidebarSection) {
                // =============================================================
                // SECTION 1: DETERMINISTIC SINGLE-DEVICE ONBOARDING WIZARD
                // =============================================================
                SettingsSidebarSection.DEVICE_CONFIG -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = if (showSpeakerGroupsDrawer) GhAccentGreen else GhTileSurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (showSpeakerGroupsDrawer) GhAccentGreen else GhCardBorder
                            ),
                            modifier = Modifier
                                .height(34.dp)
                                .clickable { showSpeakerGroupsDrawer = !showSpeakerGroupsDrawer }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.SpeakerGroup,
                                    contentDescription = null,
                                    tint = if (showSpeakerGroupsDrawer) Color(0xFF141518) else GhAccentBlue,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = if (showSpeakerGroupsDrawer) "Back to Add Device Wizard" else "Speaker Groups (${discoveredCastDevices.size})",
                                    color = if (showSpeakerGroupsDrawer) Color(0xFF141518) else GhTextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        if (showSpeakerGroupsDrawer) {
                            CastSpeakersAndGroupsTab(
                                context = context,
                                tiles = tiles,
                                discoveredCastDevices = discoveredCastDevices,
                                castSpeakerGroups = castSpeakerGroups,
                                isScanningCast = isScanningCastSpeakers,
                                compactInputTextStyle = compactInputTextStyle,
                                onScanCastDevices = { refreshCastSpeakersList() },
                                onTestChimeOnTile = { targetTile ->
                                    GoogleCastLanClient.playDoorbellChimeOnNestMinis(
                                        scope = scope,
                                        bridge = wizardVm.currentTempBridge(bridgeSettings),
                                        tiles = listOf(targetTile),
                                        customAnnouncementText = wizardVm.doorbellChimeMessage
                                    )
                                    wizardVm.statusBanner = "Playing doorbell chime on '${targetTile.title}'..."
                                },
                                onStatusBanner = { wizardVm.statusBanner = it }
                            )
                        } else {
                            UnifiedScanAllTab(
                                context = context,
                                tiles = tiles,
                                compactInputTextStyle = compactInputTextStyle,
                                onSetHaUrl = { newUrl ->
                                    wizardVm.haUrl = newUrl
                                    wizardVm.statusBanner = "Connected to Home Assistant!"
                                },
                                onStatusBanner = { wizardVm.statusBanner = it }
                            )
                        }
                    }
                }

                SettingsSidebarSection.CUSTOMISE_DASHBOARD -> {
                    ManageConnectedTilesTab(
                        context = context,
                        tiles = tiles,
                        editingTileIndex = editingTileIndex,
                        onEditingTileIndexChange = { editingTileIndex = it },
                        compactInputTextStyle = compactInputTextStyle,
                        onEnterInteractiveGridEditMode = onToggleEditMode
                    )
                }

                SettingsSidebarSection.AUTOMATIONS -> {
                    AutomationsAndSchedulesSection(
                        context = context,
                        tiles = tiles,
                        automationRules = automationRules,
                        bridgeSettings = wizardVm.currentTempBridge(bridgeSettings),
                        audioManager = audioManager,
                        compactInputTextStyle = compactInputTextStyle,
                        onSpeak = onSpeak,
                        onStatusBanner = { wizardVm.statusBanner = it }
                    )
                }

                SettingsSidebarSection.HOME_HEALTH -> {
                    HomeHealthAndDiagnosticsSection(
                        context = context,
                        tiles = tiles,
                        automationRules = automationRules,
                        bridgeSettings = wizardVm.currentTempBridge(bridgeSettings),
                        isCompactMobile = isCompactMobile,
                        compactInputTextStyle = compactInputTextStyle,
                        onStatusBanner = { wizardVm.statusBanner = it }
                    )
                }

                SettingsSidebarSection.APP_SETTINGS -> {
                    val heroTimeoutOptions = listOf(
                        5 to "5 Seconds",
                        8 to "8 Seconds",
                        10 to "10 Seconds",
                        12 to "12 Seconds (Default)",
                        15 to "15 Seconds",
                        20 to "20 Seconds",
                        30 to "30 Seconds",
                        45 to "45 Seconds",
                        60 to "1 Minute",
                        120 to "2 Minutes"
                    )
                    val sleepOptions = listOf(
                        15 to "15 Seconds",
                        30 to "30 Seconds",
                        60 to "1 Minute",
                        90 to "90 Seconds (Default)",
                        120 to "2 Minutes",
                        180 to "3 Minutes",
                        300 to "5 Minutes",
                        600 to "10 Minutes",
                        900 to "15 Minutes",
                        1800 to "30 Minutes",
                        0 to "Never (Keep Screen Always On)"
                    )
                    val cloudAutoPauseOptions = listOf(
                        60 to "1 Minute (Maximum Quota Saver)",
                        120 to "2 Minutes (Recommended)",
                        300 to "5 Minutes",
                        0 to "Never Auto-Pause Cloud Streams"
                    )
                    val wakeWordOptions = listOf(
                        "Hey Clara" to "\"Hey Clara\" (Default)",
                        "Clara" to "\"Clara\"",
                        "Hey Jarvis" to "\"Hey Jarvis\"",
                        "Jarvis" to "\"Jarvis\""
                    )
                    val slideshowIntervalOptions = listOf(
                        5 to "Every 5 Seconds",
                        10 to "Every 10 Seconds (Default)",
                        15 to "Every 15 Seconds",
                        30 to "Every 30 Seconds",
                        60 to "Every 1 Minute",
                        300 to "Every 5 Minutes"
                    )
                    val hasCameraOrMediaTiles = remember(tiles.toList()) {
                        tiles.any { it.isCameraTile || it.type == TileType.MUSIC_PLAYER }
                    }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // CARD 1: 360° RGB ACCENT COLOUR WHEEL, HERO TIMER & CLOCK STYLE
                        item {
                            SunkenWrapHeightCard(
                                outerCornerRadius = 20.dp,
                                innerCornerRadius = 15.dp,
                                sunkenInsetPadding = 5.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Text(
                                        text = "360\u00B0 RGB Accent Colour Wheel, Clock Style & Hero Card Timer",
                                        color = GhTextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = GhFont
                                    )

                                    InteractiveRgbColorWheelPicker(
                                        currentColorArgb = wizardVm.customAccentColorArgb,
                                        selectedPreset = wizardVm.selectedAccentPreset,
                                        isCompactMobile = isCompactMobile,
                                        onColorSelected = { newArgb, matchedPreset ->
                                            wizardVm.customAccentColorArgb = newArgb
                                            if (matchedPreset != null) {
                                                wizardVm.selectedAccentPreset = matchedPreset
                                            }
                                            DeviceRepository.applyThemeMode(
                                                mode = wizardVm.selectedThemeMode,
                                                accentPreset = wizardVm.selectedAccentPreset,
                                                customAccentColorArgb = newArgb
                                            )
                                        }
                                    )

                                    HorizontalDivider(color = GhCardBorder)

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Hero Card Auto-Revert Timer (When a Tile is Clicked)",
                                                selectedText = heroTimeoutOptions.firstOrNull { it.first == wizardVm.selectedHeroTimeoutSec }?.second
                                                    ?: "$wizardVm.selectedHeroTimeoutSec Seconds",
                                                options = heroTimeoutOptions,
                                                optionLabel = { it.second },
                                                onOptionSelected = { (sec, label) ->
                                                    wizardVm.selectedHeroTimeoutSec = sec
                                                    wizardVm.statusBanner = "Hero Card timer set to $label"
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Dashboard Clock Face Style (7 Styles)",
                                                selectedText = wizardVm.selectedClockFaceStyle.label,
                                                options = ClockFaceStyle.entries,
                                                optionLabel = { it.label },
                                                optionSubtitle = { it.subtitle },
                                                onOptionSelected = { style ->
                                                    wizardVm.selectedClockFaceStyle = style
                                                    wizardVm.statusBanner = "Clock face updated to ${style.label}"
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        // CARD 2: DASHBOARD TEMPLATES, NAVIGATION DOCKING & GLOBAL DPI SCALING
                        item {
                            SunkenWrapHeightCard(
                                outerCornerRadius = 20.dp,
                                innerCornerRadius = 15.dp,
                                sunkenInsetPadding = 5.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Text(
                                        text = "Dashboard Layout Templates, Navigation Docking & Global DPI Scaling",
                                        color = GhTextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = GhFont
                                    )

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Dashboard Layout Template (Interchangeable Cards)",
                                                selectedText = wizardVm.selectedDashboardTemplate.label,
                                                options = DashboardTemplate.entries,
                                                optionLabel = { it.label },
                                                optionSubtitle = { it.subtitle },
                                                onOptionSelected = { template ->
                                                    wizardVm.selectedDashboardTemplate = template
                                                    wizardVm.dashboardSlotWidgetTypes = template.slotWidgets
                                                    wizardVm.dashboardCardWidthWeights = template.widthWeights
                                                    wizardVm.dashboardCardHeightWeights = template.heightWeights
                                                    wizardVm.statusBanner = "Applied template: ${template.label}"
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Navigation Bar Dock Position (Left / Right / Top / Bottom)",
                                                selectedText = wizardVm.selectedNavDockPosition.label,
                                                options = NavDockPosition.entries,
                                                optionLabel = { it.label },
                                                onOptionSelected = { pos ->
                                                    wizardVm.selectedNavDockPosition = pos
                                                    wizardVm.statusBanner = "Navigation bar docked to ${pos.label}"
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        }
                                    )

                                    CleanDropdownSelector(
                                        label = "Dashboard Grid Size & Tile Density (6\u00D73 / 8\u00D74 / 10\u00D75 / 12\u00D76)",
                                        selectedText = wizardVm.selectedGridSizePreset.label,
                                        options = DashboardGridSizePreset.entries,
                                        optionLabel = { it.label },
                                        optionSubtitle = { it.subtitle },
                                        onOptionSelected = { preset ->
                                            wizardVm.selectedGridSizePreset = preset
                                            DeviceRepository.saveBridge(context, wizardVm.currentTempBridge(bridgeSettings).copy(gridSizePreset = preset))
                                            wizardVm.statusBanner = "Grid size set to ${preset.label}"
                                        },
                                        textStyle = compactInputTextStyle,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Global Viewport DPI Scale Preset",
                                                selectedText = wizardVm.selectedDpiPreset.label,
                                                options = DpiScalePreset.entries,
                                                optionLabel = { it.label },
                                                onOptionSelected = { preset ->
                                                    wizardVm.selectedDpiPreset = preset
                                                    if (preset != DpiScalePreset.CUSTOM) {
                                                        wizardVm.customDpiScalePct = (preset.scaleFactor * 100f).roundToInt()
                                                    }
                                                    wizardVm.statusBanner = "Global DPI scale set to ${preset.label}"
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            Row(
                                                modifier = mod,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Invert Navigation Icon Order",
                                                        color = GhTextPrimary,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = "Reverses the order of icons on the navigation dock",
                                                        color = GhTextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                                SfHouseMintPillSwitch(
                                                    checked = wizardVm.invertNavOrder,
                                                    onCheckedChange = { wizardVm.invertNavOrder = it }
                                                )
                                            }
                                        }
                                    )

                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Fine-Tune Global DPI Scale (${wizardVm.customDpiScalePct}%)",
                                                color = GhTextSecondary,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            TextButton(
                                                onClick = {
                                                    wizardVm.selectedDpiPreset = DpiScalePreset.DEFAULT
                                                    wizardVm.customDpiScalePct = 100
                                                    wizardVm.dashboardCardZoomScales = DashboardCardSlot.defaultZoomMap
                                                }
                                            ) {
                                                Text("Reset 100%", fontSize = 11.sp, color = GhAccentGreen)
                                            }
                                        }
                                        Slider(
                                            value = wizardVm.customDpiScalePct.toFloat(),
                                            onValueChange = { v ->
                                                wizardVm.selectedDpiPreset = DpiScalePreset.CUSTOM
                                                wizardVm.customDpiScalePct = v.roundToInt().coerceIn(70, 150)
                                            },
                                            valueRange = 70f..140f,
                                            modifier = Modifier.height(24.dp),
                                            colors = SliderDefaults.colors(
                                                thumbColor = Color.White,
                                                activeTrackColor = GhAccentGreen
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // CARD 3: APPEARANCE, SCREEN TIMEOUT & KEEP SCREEN AWAKE
                        item {
                            SunkenWrapHeightCard(
                                outerCornerRadius = 20.dp,
                                innerCornerRadius = 15.dp,
                                sunkenInsetPadding = 5.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Text(
                                        text = "Appearance, Screen Timeout & Keep Screen Awake",
                                        color = GhTextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = GhFont
                                    )

                                    Surface(
                                        color = if (wizardVm.keepScreenAwake) GhTileGreenActive else GhTileSurfaceElevated.copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (wizardVm.keepScreenAwake) GhAccentGreen.copy(alpha = 0.5f) else GhCardBorder
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.LightMode,
                                                    contentDescription = null,
                                                    tint = if (wizardVm.keepScreenAwake) GhAccentGreen else GhAccentWarm,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column {
                                                    Text(
                                                        text = "Keep Screen Awake (Always On)",
                                                        color = GhTextPrimary,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = if (wizardVm.keepScreenAwake) {
                                                            "Screen will stay awake continuously and never dim or sleep."
                                                        } else {
                                                            "Screen will automatically sleep after the Screen Timeout selected below."
                                                        },
                                                        color = GhTextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }
                                            SfHouseMintPillSwitch(
                                                checked = wizardVm.keepScreenAwake,
                                                onCheckedChange = { awake ->
                                                    wizardVm.keepScreenAwake = awake
                                                    if (!awake && wizardVm.selectedSleepTimeoutSec == 0) {
                                                        wizardVm.selectedSleepTimeoutSec = 90
                                                    }
                                                    wizardVm.statusBanner = if (awake) {
                                                        "Keep Screen Awake enabled (screen will stay on)."
                                                    } else {
                                                        "Keep Screen Awake disabled (using ${wizardVm.selectedSleepTimeoutSec}s timeout)."
                                                    }
                                                }
                                            )
                                        }
                                    }

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Dashboard Theme (Light / Dark / Auto)",
                                                selectedText = wizardVm.selectedThemeMode.label,
                                                options = AppThemeMode.entries,
                                                optionLabel = { it.label },
                                                onOptionSelected = { mode ->
                                                    wizardVm.selectedThemeMode = mode
                                                    DeviceRepository.applyThemeMode(
                                                        mode = mode,
                                                        accentPreset = wizardVm.selectedAccentPreset,
                                                        customAccentColorArgb = wizardVm.customAccentColorArgb
                                                    )
                                                    wizardVm.statusBanner = "Saved theme: ${mode.label}"
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Screen Timeout (Inactivity Sleep Timer)",
                                                selectedText = if (wizardVm.keepScreenAwake) {
                                                    "Keep Screen Awake Active (Always On)"
                                                } else {
                                                    sleepOptions.firstOrNull { it.first == wizardVm.selectedSleepTimeoutSec }?.second
                                                        ?: "${wizardVm.selectedSleepTimeoutSec} Seconds"
                                                },
                                                options = sleepOptions,
                                                optionLabel = { it.second },
                                                onOptionSelected = { (sec, label) ->
                                                    wizardVm.selectedSleepTimeoutSec = sec
                                                    wizardVm.keepScreenAwake = (sec == 0)
                                                    wizardVm.statusBanner = "Screen timeout set to $label"
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        }
                                    )

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Cloud Camera Quota Saver (Auto-Pause)",
                                                selectedText = cloudAutoPauseOptions.firstOrNull { it.first == wizardVm.selectedCloudAutoPauseSec }?.second
                                                    ?: "2 Minutes (Recommended)",
                                                options = cloudAutoPauseOptions,
                                                optionLabel = { it.second },
                                                onOptionSelected = { (sec, label) ->
                                                    wizardVm.selectedCloudAutoPauseSec = sec
                                                    wizardVm.statusBanner = "Cloud stream auto-pause set to $label"
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Default Music Player Service",
                                                selectedText = wizardVm.selectedMusicProvider.label,
                                                options = MusicProvider.entries,
                                                optionLabel = { it.label },
                                                onOptionSelected = { provider ->
                                                    wizardVm.selectedMusicProvider = provider
                                                    wizardVm.statusBanner = "Default music service set to ${provider.label}"
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        }
                                    )

                                    HorizontalDivider(color = GhCardBorder)

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        horizontalSpacing = 16.dp,
                                        firstContent = { mod ->
                                            Row(
                                                modifier = mod,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Show Room & Telemetry Subtitles on Tiles",
                                                        color = GhTextPrimary,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = "Display room, wattage & status badges on tiles",
                                                        color = GhTextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                                SfHouseMintPillSwitch(
                                                    checked = wizardVm.showRoomLabelsOnTiles,
                                                    onCheckedChange = { show ->
                                                        wizardVm.showRoomLabelsOnTiles = show
                                                    }
                                                )
                                            }
                                        },
                                        secondContent = { mod ->
                                            Row(
                                                modifier = mod,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Camera & Sensor Presence Wake",
                                                        color = GhTextPrimary,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = "Wake screen automatically when you approach",
                                                        color = GhTextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                                SfHouseMintPillSwitch(
                                                    checked = wizardVm.enablePresenceWake,
                                                    onCheckedChange = { enabled ->
                                                        wizardVm.enablePresenceWake = enabled
                                                    }
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // CARD 4: PHOTO SLIDESHOW WIDGET & SWIPABLE DASHBOARD PAGE
                        item {
                            SunkenWrapHeightCard(
                                outerCornerRadius = 20.dp,
                                innerCornerRadius = 15.dp,
                                sunkenInsetPadding = 5.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            Column(modifier = mod) {
                                                Text(
                                                    text = "Photo Slideshow (Hero Card Slide & Swipable Full-Screen Page)",
                                                    color = GhTextPrimary,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = GhFont
                                                )
                                                Text(
                                                    text = "Use local device media, Google Photos, or OneDrive inside the Hero Card slideshow slide or swipable full-screen page.",
                                                    color = GhTextSecondary,
                                                    fontSize = 11.sp,
                                                    fontFamily = GhFont
                                                )
                                            }
                                        },
                                        secondContent = {
                                            FilledTonalButton(
                                                onClick = {
                                                    val existingIdx = tiles.indexOfFirst { it.type == TileType.PHOTO_SLIDESHOW }
                                                    if (existingIdx in tiles.indices) {
                                                        tiles[existingIdx] = tiles[existingIdx].copy(
                                                            isVisible = true
                                                        )
                                                        DeviceRepository.saveTiles(context, tiles)
                                                        wizardVm.statusBanner = "SUCCESS: Updated 'Family Photos' slideshow configuration!"
                                                    } else {
                                                        scope.launch {
                                                            val slideshowIdentity = ResolvedDeviceIdentity(
                                                                identityKey = "id_system_photo_slideshow",
                                                                displayName = "Family Photos",
                                                                suggestedRoom = "Photo Frame",
                                                                deviceClass = DeviceClass.NETWORK,
                                                                suggestedType = TileType.PHOTO_SLIDESHOW,
                                                                combinedConfidence = 1.0f,
                                                                primaryProviderId = ProviderId.SYSTEM_LOCAL.id,
                                                                fallbackProviderIds = emptyList(),
                                                                onboardingRequirement = OnboardingRequirement.ZeroTouchReady
                                                            )
                                                            val onboardRes = UniversalOnboardingEngine.onboardResolvedIdentity(
                                                                context = context,
                                                                identity = slideshowIdentity,
                                                                tiles = tiles,
                                                                bridgeSettings = wizardVm.currentTempBridge(bridgeSettings),
                                                                scope = scope
                                                            )
                                                            if (onboardRes.isSuccess) {
                                                                val createdId = onboardRes.getOrThrow().id
                                                                val idx = tiles.indexOfFirst { it.id == createdId }
                                                                if (idx in tiles.indices) {
                                                                    tiles[idx] = tiles[idx].copy(
                                                                        colSpan = 2,
                                                                        rowSpan = 2,
                                                                        isTall = true
                                                                    )
                                                                    DeviceRepository.saveTiles(context, tiles)
                                                                }
                                                            }
                                                            wizardVm.statusBanner = "SUCCESS: Linked 'Family Photos' slideshow configuration!"
                                                        }
                                                    }
                                                },
                                                shape = RoundedCornerShape(9.dp),
                                                colors = ButtonDefaults.filledTonalButtonColors(
                                                    containerColor = GhTileSurfaceElevated,
                                                    contentColor = GhAccentGreen
                                                )
                                            ) {
                                                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Sync Slideshow Config", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    )

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        horizontalSpacing = 16.dp,
                                        firstContent = { mod ->
                                            Row(
                                                modifier = mod,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Enable Swipable Full-Screen Slideshow Page",
                                                        color = GhTextPrimary,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = "Swipe left/right on dashboard to view slideshow & swipe back for controls",
                                                        color = GhTextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                                SfHouseMintPillSwitch(
                                                    checked = wizardVm.enableSwipeableSlideshowPage,
                                                    onCheckedChange = { enabled ->
                                                        wizardVm.enableSwipeableSlideshowPage = enabled
                                                    }
                                                )
                                            }
                                        },
                                        secondContent = { mod ->
                                            Row(
                                                modifier = mod,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Show Clock & Weather on Slideshow Page",
                                                        color = GhTextPrimary,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = "Display corner time & weather badge on full-screen photos",
                                                        color = GhTextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                                SfHouseMintPillSwitch(
                                                    checked = wizardVm.showClockOnSlideshowPage,
                                                    onCheckedChange = { show ->
                                                        wizardVm.showClockOnSlideshowPage = show
                                                    }
                                                )
                                            }
                                        }
                                    )

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstWeight = 1.2f,
                                        secondWeight = 0.8f,
                                        firstContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Default Slideshow Photo Source",
                                                selectedText = wizardVm.slideshowSourceType.label,
                                                options = SlideshowSourceType.entries.filter { it != SlideshowSourceType.CUSTOM_URLS || wizardVm.showAdvancedSlideshowLinkField },
                                                optionLabel = { it.label },
                                                optionSubtitle = { it.subtitle },
                                                onOptionSelected = { src ->
                                                    wizardVm.slideshowSourceType = src
                                                    PhotoSlideshowResolver.clearCloudCache()
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Slide Interval Speed",
                                                selectedText = slideshowIntervalOptions.firstOrNull { it.first == wizardVm.slideshowIntervalSeconds }?.second
                                                    ?: "Every ${wizardVm.slideshowIntervalSeconds}s",
                                                options = slideshowIntervalOptions,
                                                optionLabel = { it.second },
                                                onOptionSelected = { (sec, _) ->
                                                    wizardVm.slideshowIntervalSeconds = sec
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        }
                                    )

                                    when (wizardVm.slideshowSourceType) {
                                        SlideshowSourceType.LOCAL_DEVICE -> {
                                            ResponsiveSplitRow(
                                                isStacked = isCompactMobile,
                                                firstContent = { mod ->
                                                    Text(
                                                        text = "Selected Local Photos: ${wizardVm.slideshowLocalUris.size} photo(s)",
                                                        color = GhAccentGreen,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        modifier = mod
                                                    )
                                                },
                                                secondContent = {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        if (wizardVm.slideshowLocalUris.isNotEmpty()) {
                                                            OutlinedButton(
                                                                onClick = {
                                                                    wizardVm.slideshowLocalUris = emptyList()
                                                                    wizardVm.statusBanner = "Cleared local slideshow photos."
                                                                },
                                                                shape = RoundedCornerShape(9.dp)
                                                            ) {
                                                                Text("Clear Photos", fontSize = 11.sp, color = GhAccentRed)
                                                            }
                                                        }
                                                        Button(
                                                            onClick = {
                                                                settingsPhotoPickerLauncher.launch(arrayOf("image/*"))
                                                            },
                                                            shape = RoundedCornerShape(9.dp),
                                                            colors = ButtonDefaults.buttonColors(
                                                                containerColor = GhAccentGreen,
                                                                contentColor = Color(0xFF141518)
                                                            )
                                                        ) {
                                                            Icon(Icons.Outlined.Collections, contentDescription = null, modifier = Modifier.size(15.dp))
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text("Pick Local Photos", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                        SlideshowSourceType.GOOGLE_PHOTOS,
                                        SlideshowSourceType.ONEDRIVE -> {
                                            val linkedCount = wizardVm.slideshowCloudUrl.split(",", "\n").count { it.trim().startsWith("http") }
                                            ResponsiveSplitRow(
                                                isStacked = isCompactMobile,
                                                firstContent = { mod ->
                                                    Text(
                                                        text = if (linkedCount > 0) {
                                                            "${wizardVm.slideshowSourceType.label} Connected ($linkedCount photos/albums linked) \u2713"
                                                        } else {
                                                            "Sign in to ${wizardVm.slideshowSourceType.label} to select the albums you want:"
                                                        },
                                                        color = if (linkedCount > 0) GhAccentGreen else GhTextPrimary,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        modifier = mod
                                                    )
                                                },
                                                secondContent = {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        Button(
                                                            onClick = {
                                                                wizardVm.activeCloudPhotoPickerProvider = wizardVm.slideshowSourceType
                                                            },
                                                            shape = RoundedCornerShape(9.dp),
                                                            colors = ButtonDefaults.buttonColors(
                                                                containerColor = GhAccentGreen,
                                                                contentColor = Color(0xFF141518)
                                                            )
                                                        ) {
                                                            Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(15.dp))
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text(
                                                                text = if (wizardVm.slideshowSourceType == SlideshowSourceType.GOOGLE_PHOTOS)
                                                                    "Sign In & Select Albums"
                                                                else
                                                                    "Sign In & Select Folders",
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                        TextButton(onClick = { wizardVm.showAdvancedSlideshowLinkField = !wizardVm.showAdvancedSlideshowLinkField }) {
                                                            Text(
                                                                text = if (wizardVm.showAdvancedSlideshowLinkField) "Hide Link" else "Advanced Link",
                                                                fontSize = 11.sp,
                                                                color = GhTextSecondary
                                                            )
                                                        }
                                                    }
                                                }
                                            )
                                            if (wizardVm.showAdvancedSlideshowLinkField) {
                                                OutlinedTextField(
                                                    value = wizardVm.slideshowCloudUrl,
                                                    onValueChange = { url ->
                                                        wizardVm.slideshowCloudUrl = url
                                                        PhotoSlideshowResolver.clearCloudCache()
                                                    },
                                                    label = {
                                                        Text(
                                                            if (wizardVm.slideshowSourceType == SlideshowSourceType.GOOGLE_PHOTOS)
                                                                "Google Photos Shared Album Link"
                                                            else
                                                                "OneDrive Shared Folder / Album Link",
                                                            fontSize = 11.sp
                                                        )
                                                    },
                                                    textStyle = compactInputTextStyle,
                                                    shape = RoundedCornerShape(14.dp),
                                                    colors = claraFieldColors(),
                                                    singleLine = true,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                        SlideshowSourceType.CUSTOM_URLS -> {
                                            OutlinedTextField(
                                                value = wizardVm.slideshowCloudUrl,
                                                onValueChange = { url ->
                                                    wizardVm.slideshowCloudUrl = url
                                                    PhotoSlideshowResolver.clearCloudCache()
                                                },
                                                label = {
                                                    Text("Image Links (Comma Separated)", fontSize = 11.sp)
                                                },
                                                textStyle = compactInputTextStyle,
                                                shape = RoundedCornerShape(14.dp),
                                                colors = claraFieldColors(),
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // CARD 5: GOOGLE NEST MINI DOORBELL CHIME, CUSTOM MP3 ALERTS & ANNOUNCEMENTS
                        item {
                            SunkenWrapHeightCard(
                                outerCornerRadius = 20.dp,
                                innerCornerRadius = 15.dp,
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
                                                    text = "Google Nest Mini Doorbell Chime, Custom MP3s & Announcements",
                                                    color = GhTextPrimary,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = GhFont
                                                )
                                                Text(
                                                    text = "Stream a custom MP3 file from this tablet (port ${JarvisLiveBridge.LOCAL_HUB_PORT}) or spoken announcement to your Nest Mini speakers.",
                                                    color = GhTextSecondary,
                                                    fontSize = 11.sp,
                                                    fontFamily = GhFont
                                                )
                                            }
                                        },
                                        secondContent = {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                FilledTonalButton(
                                                    onClick = {
                                                        GoogleCastLanClient.playDoorbellChimeOnNestMinis(
                                                            scope = scope,
                                                            bridge = wizardVm.currentTempBridge(bridgeSettings),
                                                            tiles = tiles,
                                                            customAnnouncementText = wizardVm.doorbellChimeMessage
                                                        )
                                                        wizardVm.statusBanner = "Playing test doorbell chime on Nest Mini(s)..."
                                                    },
                                                    shape = RoundedCornerShape(9.dp),
                                                    colors = ButtonDefaults.filledTonalButtonColors(
                                                        containerColor = GhTileSurfaceElevated,
                                                        contentColor = GhAccentGreen
                                                    )
                                                ) {
                                                    Icon(Icons.Outlined.Doorbell, contentDescription = null, modifier = Modifier.size(15.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Test Chime", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                                SfHouseMintPillSwitch(
                                                    checked = wizardVm.enableNestMiniDoorbellChime,
                                                    onCheckedChange = { enabled ->
                                                        wizardVm.enableNestMiniDoorbellChime = enabled
                                                    }
                                                )
                                            }
                                        }
                                    )

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            Surface(
                                                color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                                                shape = RoundedCornerShape(12.dp),
                                                border = BorderStroke(1.dp, GhCardBorder),
                                                modifier = mod
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = if (wizardVm.customDoorbellMp3Uri.isNotBlank()) "Custom Doorbell MP3 Linked \u2713" else "Custom Doorbell MP3 (Optional)",
                                                            color = if (wizardVm.customDoorbellMp3Uri.isNotBlank()) GhAccentGreen else GhTextPrimary,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(
                                                            text = if (wizardVm.customDoorbellMp3Uri.isNotBlank()) {
                                                                Uri.parse(wizardVm.customDoorbellMp3Uri).lastPathSegment ?: "Selected MP3"
                                                            } else {
                                                                "Uses built-in harmonic chime if unset"
                                                            },
                                                            color = GhTextSecondary,
                                                            fontSize = 10.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        if (wizardVm.customDoorbellMp3Uri.isNotBlank()) {
                                                            TextButton(onClick = { wizardVm.customDoorbellMp3Uri = "" }) {
                                                                Text("Clear", fontSize = 10.5.sp, color = GhAccentRed)
                                                            }
                                                        }
                                                        FilledTonalButton(
                                                            onClick = { doorbellMp3PickerLauncher.launch(arrayOf("audio/*")) },
                                                            shape = RoundedCornerShape(8.dp),
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                            modifier = Modifier.height(28.dp)
                                                        ) {
                                                            Text("Pick MP3", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        secondContent = { mod ->
                                            Surface(
                                                color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                                                shape = RoundedCornerShape(12.dp),
                                                border = BorderStroke(1.dp, GhCardBorder),
                                                modifier = mod
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = if (wizardVm.customAlarmMp3Uri.isNotBlank()) "Custom Security Alarm MP3 \u2713" else "Security Alarm MP3 (Optional)",
                                                            color = if (wizardVm.customAlarmMp3Uri.isNotBlank()) GhAccentGreen else GhTextPrimary,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(
                                                            text = if (wizardVm.customAlarmMp3Uri.isNotBlank()) {
                                                                Uri.parse(wizardVm.customAlarmMp3Uri).lastPathSegment ?: "Selected MP3"
                                                            } else {
                                                                "Streamed to speakers on Away/Night alerts"
                                                            },
                                                            color = GhTextSecondary,
                                                            fontSize = 10.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        if (wizardVm.customAlarmMp3Uri.isNotBlank()) {
                                                            TextButton(onClick = { wizardVm.customAlarmMp3Uri = "" }) {
                                                                Text("Clear", fontSize = 10.5.sp, color = GhAccentRed)
                                                            }
                                                        }
                                                        FilledTonalButton(
                                                            onClick = { alarmMp3PickerLauncher.launch(arrayOf("audio/*")) },
                                                            shape = RoundedCornerShape(8.dp),
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                            modifier = Modifier.height(28.dp)
                                                        ) {
                                                            Text("Pick MP3", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    )

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstWeight = 1.2f,
                                        secondWeight = 0.8f,
                                        firstContent = { mod ->
                                            OutlinedTextField(
                                                value = wizardVm.doorbellChimeMessage,
                                                onValueChange = {
                                                    wizardVm.doorbellChimeMessage = it
                                                },
                                                label = { Text("Spoken Doorbell Announcement on Nest Mini", fontSize = 11.sp) },
                                                textStyle = compactInputTextStyle,
                                                shape = RoundedCornerShape(14.dp),
                                                colors = claraFieldColors(),
                                                singleLine = true,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            Column(modifier = mod) {
                                                Text(
                                                    text = "Chime Volume: $wizardVm.nestMiniDoorbellVolume%",
                                                    color = GhAccentGreen,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Slider(
                                                    value = wizardVm.nestMiniDoorbellVolume.toFloat(),
                                                    onValueChange = { v ->
                                                        wizardVm.nestMiniDoorbellVolume = v.roundToInt().coerceIn(20, 100)
                                                    },
                                                    valueRange = 20f..100f,
                                                    modifier = Modifier.height(26.dp),
                                                    colors = SliderDefaults.colors(
                                                        thumbColor = Color.White,
                                                        activeTrackColor = GhAccentGreen
                                                    )
                                                )
                                            }
                                        }
                                    )

                                    if (discoveredCastDevices.isNotEmpty()) {
                                        Text(
                                            text = "Select Which Speakers Ring (Leave all unselected to ring all speakers):",
                                            color = GhTextSecondary,
                                            fontSize = 11.sp
                                        )
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            items(discoveredCastDevices.filter { !it.isCastGroup }, key = { it.id }) { dev ->
                                                val key = "${dev.ipAddress}:${dev.port}"
                                                val isPicked = key in wizardVm.doorbellChimeTargetIps
                                                FilterChip(
                                                    selected = isPicked,
                                                    onClick = {
                                                        val next = wizardVm.doorbellChimeTargetIps.toMutableSet()
                                                        if (!next.add(key)) next.remove(key)
                                                        wizardVm.doorbellChimeTargetIps = next
                                                    },
                                                    shape = RoundedCornerShape(50),
                                                    label = { Text(dev.name, fontSize = 11.sp) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // CARD 6: VOICE CONTROL, HANDS-FREE WAKE WORD & LOCAL LAN LLM
                        item {
                            SunkenWrapHeightCard(
                                outerCornerRadius = 20.dp,
                                innerCornerRadius = 15.dp,
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
                                                    text = "Voice Control, Wake Word & Digital Home Graph AI",
                                                    color = GhTextPrimary,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = GhFont
                                                )
                                                Text(
                                                    text = "Say \"$wizardVm.voiceWakeWord, is my Jaguar locked?\", \"set Lytmi to HDMI 2\", or \"why did my routine fail?\".",
                                                    color = GhTextSecondary,
                                                    fontSize = 11.sp,
                                                    fontFamily = GhFont
                                                )
                                            }
                                        },
                                        secondContent = {
                                            FilledTonalButton(
                                                onClick = {
                                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                    onTriggerVoicePrompt()
                                                },
                                                shape = RoundedCornerShape(9.dp),
                                                colors = ButtonDefaults.filledTonalButtonColors(
                                                    containerColor = GhAccentGreen.copy(alpha = 0.22f),
                                                    contentColor = GhAccentGreen
                                                )
                                            ) {
                                                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Test Voice Command", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    )

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Hands-Free Wake Word Phrase",
                                                selectedText = wakeWordOptions.firstOrNull { it.first.equals(wizardVm.voiceWakeWord, true) }?.second
                                                    ?: "\"$wizardVm.voiceWakeWord\"",
                                                options = wakeWordOptions,
                                                optionLabel = { it.second },
                                                onOptionSelected = { (word, _) ->
                                                    wizardVm.voiceWakeWord = word
                                                    wizardVm.statusBanner = "Wake word set to \"$word\""
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            OutlinedTextField(
                                                value = wizardVm.localLlmEndpointUrl,
                                                onValueChange = { wizardVm.localLlmEndpointUrl = it },
                                                label = { Text("Optional Local LAN LLM URL (Ollama / OpenAI-Compatible)", fontSize = 11.sp) },
                                                textStyle = compactInputTextStyle,
                                                shape = RoundedCornerShape(14.dp),
                                                colors = claraFieldColors(),
                                                singleLine = true,
                                                modifier = mod
                                            )
                                        }
                                    )

                                    HorizontalDivider(color = GhCardBorder)

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        horizontalSpacing = 16.dp,
                                        firstContent = { mod ->
                                            Row(
                                                modifier = mod,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Always-On Wake Word Listening",
                                                        color = GhTextPrimary,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = "Listen for \"$wizardVm.voiceWakeWord\" (ducks music automatically)",
                                                        color = GhTextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                                SfHouseMintPillSwitch(
                                                    checked = wizardVm.enableWakeWordListening,
                                                    onCheckedChange = { enabled ->
                                                        wizardVm.enableWakeWordListening = enabled
                                                        if (enabled) {
                                                            wizardVm.enableVoiceControl = true
                                                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                        }
                                                    }
                                                )
                                            }
                                        },
                                        secondContent = { mod ->
                                            Row(
                                                modifier = mod,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Spoken Voice Replies (TTS)",
                                                        color = GhTextPrimary,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = "Speak confirmation after voice commands",
                                                        color = GhTextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                                SfHouseMintPillSwitch(
                                                    checked = wizardVm.enableVoiceAnnouncements,
                                                    onCheckedChange = { enabled ->
                                                        wizardVm.enableVoiceAnnouncements = enabled
                                                    }
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // CARD 6B: ECOSYSTEM BRIDGES (SMARTTHINGS, TESLA WALL CONNECTOR, FRIGATE NVR & HOME ASSISTANT)
                        item {
                            SunkenWrapHeightCard(
                                outerCornerRadius = 20.dp,
                                innerCornerRadius = 15.dp,
                                sunkenInsetPadding = 5.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    val sdkReadyNow = ClaraApplication.isTuyaSdkReady(context)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Tuya / SmartLife SDK, Ecosystem Aggregators & Local Bridges",
                                                color = GhTextPrimary,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = GhFont
                                            )
                                            Text(
                                                text = if (sdkReadyNow) {
                                                    "ThingHomeSdk: Initialized & Ready"
                                                } else {
                                                    "ThingHomeSdk: Uninitialized — Enter Tuya AppKey / Access ID & AppSecret below"
                                                },
                                                color = if (sdkReadyNow) GhAccentGreen else GhAccentWarm,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = {
                                                DeviceRepository.saveBridge(context, wizardVm.currentTempBridge(bridgeSettings))
                                                ClaraApplication.ensureTuyaSdkInitialized(context, true)
                                                wizardVm.statusBanner = if (ClaraApplication.isTuyaSdkReady(context)) {
                                                    "SUCCESS: Tuya Smart Home SDK initialized!"
                                                } else {
                                                    "Saved keys — if SDK still reports uninitialized, attach ClaraApplication.kt to verify BuildConfig / t_s.bmp."
                                                }
                                            },
                                            shape = RoundedCornerShape(9.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = GhAccentGreen.copy(alpha = 0.22f),
                                                contentColor = GhAccentGreen
                                            )
                                        ) {
                                            Text("Initialize Tuya SDK", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            OutlinedTextField(
                                                value = wizardVm.tuyaAccessId,
                                                onValueChange = { wizardVm.tuyaAccessId = it.trim() },
                                                label = { Text("Tuya AppKey / IoT Access ID", fontSize = 11.sp) },
                                                textStyle = compactInputTextStyle,
                                                shape = RoundedCornerShape(14.dp),
                                                colors = claraFieldColors(),
                                                singleLine = true,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            OutlinedTextField(
                                                value = wizardVm.tuyaAccessSecret,
                                                onValueChange = { wizardVm.tuyaAccessSecret = it.trim() },
                                                label = { Text("Tuya AppSecret / IoT Access Secret", fontSize = 11.sp) },
                                                textStyle = compactInputTextStyle,
                                                visualTransformation = PasswordVisualTransformation(),
                                                shape = RoundedCornerShape(14.dp),
                                                colors = claraFieldColors(),
                                                singleLine = true,
                                                modifier = mod
                                            )
                                        }
                                    )
                                    OutlinedTextField(
                                        value = wizardVm.tuyaUid,
                                        onValueChange = { wizardVm.tuyaUid = it.trim() },
                                        label = { Text("Optional Tuya Cloud UID (Leave blank for silent local UID pairing)", fontSize = 11.sp) },
                                        textStyle = compactInputTextStyle,
                                        shape = RoundedCornerShape(14.dp),
                                        colors = claraFieldColors(),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    HorizontalDivider(color = GhCardBorder)
                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            OutlinedTextField(
                                                value = wizardVm.smartThingsToken,
                                                onValueChange = { wizardVm.smartThingsToken = it },
                                                label = { Text("Samsung SmartThings Personal Access Token (PAT)", fontSize = 11.sp) },
                                                textStyle = compactInputTextStyle,
                                                visualTransformation = PasswordVisualTransformation(),
                                                shape = RoundedCornerShape(14.dp),
                                                colors = claraFieldColors(),
                                                singleLine = true,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            OutlinedTextField(
                                                value = wizardVm.teslaWallConnectorIp,
                                                onValueChange = { wizardVm.teslaWallConnectorIp = it },
                                                label = { Text("Tesla Gen 3 Wall Connector Local IP (/api/1/vitals)", fontSize = 11.sp) },
                                                textStyle = compactInputTextStyle,
                                                shape = RoundedCornerShape(14.dp),
                                                colors = claraFieldColors(),
                                                singleLine = true,
                                                modifier = mod
                                            )
                                        }
                                    )
                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            OutlinedTextField(
                                                value = wizardVm.haUrl,
                                                onValueChange = { wizardVm.haUrl = it },
                                                label = { Text("Home Assistant URL (e.g. http://homeassistant.local:8123)", fontSize = 11.sp) },
                                                textStyle = compactInputTextStyle,
                                                shape = RoundedCornerShape(14.dp),
                                                colors = claraFieldColors(),
                                                singleLine = true,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            OutlinedTextField(
                                                value = wizardVm.frigateHostUrl,
                                                onValueChange = { wizardVm.frigateHostUrl = it },
                                                label = { Text("Frigate NVR Host URL (Optional Local AI Camera Bridge)", fontSize = 11.sp) },
                                                textStyle = compactInputTextStyle,
                                                shape = RoundedCornerShape(14.dp),
                                                colors = claraFieldColors(),
                                                singleLine = true,
                                                modifier = mod
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        // CARD 7: SMART ELECTRICITY TARIFF RATES & HOME PROFILE
                        item {
                            ElectricityTariffAndProfileCard(
                                tariffDayRateText = wizardVm.tariffDayRateText,
                            onTariffDayRateChange = { wizardVm.tariffDayRateText = it },
                            tariffNightRateText = wizardVm.tariffNightRateText,
                            onTariffNightRateChange = { wizardVm.tariffNightRateText = it },
                            tariffStandingChargeText = wizardVm.tariffStandingChargeText,
                            onTariffStandingChargeChange = { wizardVm.tariffStandingChargeText = it },
                                compactInputTextStyle = compactInputTextStyle
                            )
                        }

                        // CARD 7B: TARIFF WINDOW HOURS, CURRENCY, SECURITY TEMPERATURES & HOUSEHOLD ROOMS
                        item {
                            val hourOptions = remember {
                                (0..23).map { h -> h to "%02d:00".format(Locale.US, h) }
                            }
                            val currencyChoices = remember {
                                listOf("\u00A3" to "\u00A3 (GBP)", "$" to "$ (USD)", "\u20AC" to "\u20AC (EUR)")
                            }
                            var newRoomInput by remember { mutableStateOf("") }

                            SunkenWrapHeightCard(
                                outerCornerRadius = 20.dp,
                                innerCornerRadius = 15.dp,
                                sunkenInsetPadding = 5.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = "Off-Peak Tariff Window, Currency, Security Mode Setpoints & Rooms",
                                        color = GhTextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = GhFont
                                    )

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Currency Symbol",
                                                selectedText = currencyChoices.firstOrNull { it.first == wizardVm.currencySymbol }?.second
                                                    ?: wizardVm.currencySymbol,
                                                options = currencyChoices,
                                                optionLabel = { it.second },
                                                onOptionSelected = { (sym, _) ->
                                                    wizardVm.currencySymbol = sym
                                                    wizardVm.statusBanner = "Currency updated to $sym"
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            Row(
                                                modifier = mod,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                CleanDropdownSelector(
                                                    label = "Off-Peak Start",
                                                    selectedText = "%02d:00".format(Locale.US, wizardVm.offPeakStartHour),
                                                    options = hourOptions,
                                                    optionLabel = { it.second },
                                                    onOptionSelected = { (h, _) -> wizardVm.offPeakStartHour = h },
                                                    textStyle = compactInputTextStyle,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                CleanDropdownSelector(
                                                    label = "Off-Peak End",
                                                    selectedText = "%02d:00".format(Locale.US, wizardVm.offPeakEndHour),
                                                    options = hourOptions,
                                                    optionLabel = { it.second },
                                                    onOptionSelected = { (h, _) -> wizardVm.offPeakEndHour = h },
                                                    textStyle = compactInputTextStyle,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    )

                                    Text(
                                        text = "Security Center Mode Target Temperatures (\u00B0C):",
                                        color = GhTextSecondary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            Row(
                                                modifier = mod,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                OutlinedTextField(
                                                    value = wizardVm.securityHomeTempText,
                                                    onValueChange = { wizardVm.securityHomeTempText = it },
                                                    label = { androidx.compose.material3.Text("Home \u00B0C", fontSize = 11.sp) },
                                                    textStyle = compactInputTextStyle,
                                                    shape = RoundedCornerShape(14.dp),
                                                    colors = claraFieldColors(),
                                                    singleLine = true,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                OutlinedTextField(
                                                    value = wizardVm.securityAwayTempText,
                                                    onValueChange = { wizardVm.securityAwayTempText = it },
                                                    label = { androidx.compose.material3.Text("Away \u00B0C", fontSize = 11.sp) },
                                                    textStyle = compactInputTextStyle,
                                                    shape = RoundedCornerShape(14.dp),
                                                    colors = claraFieldColors(),
                                                    singleLine = true,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        },
                                        secondContent = { mod ->
                                            Row(
                                                modifier = mod,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                OutlinedTextField(
                                                    value = wizardVm.securityNightTempText,
                                                    onValueChange = { wizardVm.securityNightTempText = it },
                                                    label = { androidx.compose.material3.Text("Night \u00B0C", fontSize = 11.sp) },
                                                    textStyle = compactInputTextStyle,
                                                    shape = RoundedCornerShape(14.dp),
                                                    colors = claraFieldColors(),
                                                    singleLine = true,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                OutlinedTextField(
                                                    value = wizardVm.securityVacationTempText,
                                                    onValueChange = { wizardVm.securityVacationTempText = it },
                                                    label = { androidx.compose.material3.Text("Vacation \u00B0C", fontSize = 11.sp) },
                                                    textStyle = compactInputTextStyle,
                                                    shape = RoundedCornerShape(14.dp),
                                                    colors = claraFieldColors(),
                                                    singleLine = true,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    )

                                    HorizontalDivider(color = GhCardBorder)

                                    Text(
                                        text = "Household Rooms (Tap \u2715 to remove any room, or add a new room):",
                                        color = GhTextSecondary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        items(wizardVm.customRoomsList.toList(), key = { it }) { roomName ->
                                            InputChip(
                                                selected = true,
                                                onClick = {
                                                    if (wizardVm.customRoomsList.size > 1) {
                                                        wizardVm.customRoomsList = wizardVm.customRoomsList.filter { it != roomName }
                                                    }
                                                },
                                                shape = RoundedCornerShape(50),
                                                label = { androidx.compose.material3.Text(roomName, fontSize = 11.sp) },
                                                trailingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = "Remove $roomName",
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = newRoomInput,
                                            onValueChange = { newRoomInput = it },
                                            label = { Text("Add Room Name", fontSize = 11.sp) },
                                            textStyle = compactInputTextStyle,
                                            shape = RoundedCornerShape(14.dp),
                                            colors = claraFieldColors(),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(
                                            onClick = {
                                                val clean = newRoomInput.trim()
                                                if (clean.isNotBlank() && wizardVm.customRoomsList.none { it.equals(clean, ignoreCase = true) }) {
                                                    val updated = wizardVm.customRoomsList.toMutableList()
                                                    updated.add(clean)
                                                    wizardVm.customRoomsList = updated
                                                    newRoomInput = ""
                                                }
                                            },
                                            enabled = newRoomInput.trim().isNotBlank(),
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = GhAccentGreen,
                                                contentColor = Color(0xFF141518)
                                            )
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Add Room", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // CARD 8: ROLE-BASED ACCESS CONTROL (RBAC), SECURITY PIN & AUDIT LOG
                        item {
                            UserRolesAndPinSecurityCard(
                                context = context,
                                activeUserRole = wizardVm.activeUserRole,
                                onActiveUserRoleChange = { wizardVm.activeUserRole = it },
                                rolePinCode = wizardVm.rolePinCode,
                                onRolePinCodeChange = { wizardVm.rolePinCode = it },
                                requirePinForAdminAndVehicles = wizardVm.requirePinForAdminAndVehicles,
                                onRequirePinChange = { wizardVm.requirePinForAdminAndVehicles = it },
                                isCompactMobile = isCompactMobile,
                                compactInputTextStyle = compactInputTextStyle,
                                onStatusBanner = { wizardVm.statusBanner = it }
                            )
                        }

                        // CARD 9: HOME HEALTH DIAGNOSTICS, SELF-HEALING & ENCRYPTED BACKUP / MESH SYNC
                        item {
                            SunkenWrapHeightCard(
                                outerCornerRadius = 20.dp,
                                innerCornerRadius = 15.dp,
                                sunkenInsetPadding = 5.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Home Health Diagnostics & Self-Healing Summary",
                                                color = GhTextPrimary,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = GhFont
                                            )
                                            Text(
                                                text = "Local Hub HTTP/Webhook Server active at http://${JarvisLiveBridge.resolveLocalTabletIp(context)}:${JarvisLiveBridge.LOCAL_HUB_PORT} \u2022 ${discoveredMeshPeers.size} Clara Mesh peer(s)",
                                                color = GhAccentGreen,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilledTonalButton(
                                                onClick = { showSelfHealingLogPanel = !showSelfHealingLogPanel },
                                                shape = RoundedCornerShape(9.dp),
                                                colors = ButtonDefaults.filledTonalButtonColors(
                                                    containerColor = GhTileSurfaceElevated,
                                                    contentColor = Color(0xFF00E5FF)
                                                )
                                            ) {
                                                Text(
                                                    text = "Self-Heal Log (${selfHealingLogs.size})",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            FilledTonalButton(
                                                onClick = { wizardVm.activeSidebarSection = SettingsSidebarSection.HOME_HEALTH },
                                                shape = RoundedCornerShape(9.dp),
                                                colors = ButtonDefaults.filledTonalButtonColors(
                                                    containerColor = GhAccentGreen.copy(alpha = 0.22f),
                                                    contentColor = GhAccentGreen
                                                )
                                            ) {
                                                Icon(Icons.Outlined.HealthAndSafety, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text("Open Full Health Dashboard", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        integrationHealthMap.values.forEach { rec ->
                                            Surface(
                                                color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                                                shape = RoundedCornerShape(10.dp),
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (rec.isOnline) GhCardBorder else GhAccentWarm
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = rec.displayName,
                                                            color = GhTextPrimary,
                                                            fontSize = 12.5.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(
                                                            text = rec.statusMessage,
                                                            color = GhTextSecondary,
                                                            fontSize = 10.5.sp
                                                        )
                                                    }
                                                    Text(
                                                        text = "${rec.averageLatencyMs} ms",
                                                        color = if (rec.isOnline) GhAccentGreen else GhAccentWarm,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.ExtraBold
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    AnimatedVisibility(visible = showSelfHealingLogPanel) {
                                        Surface(
                                            color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                                            shape = RoundedCornerShape(14.dp),
                                            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.45f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "Autonomous Self-Healing History",
                                                        color = Color(0xFF00E5FF),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    if (selfHealingLogs.isNotEmpty()) {
                                                        TextButton(onClick = { DeviceRepository.clearSelfHealingLogs(context) }) {
                                                            Text("Clear", fontSize = 11.sp, color = GhAccentRed)
                                                        }
                                                    }
                                                }
                                                if (selfHealingLogs.isEmpty()) {
                                                    Text(
                                                        text = "All devices responding normally \u2014 no self-healing actions needed yet.",
                                                        color = GhTextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                } else {
                                                    selfHealingLogs.take(6).forEach { log ->
                                                        Text(
                                                            text = "\u2022 [${log.stageLabel}] ${log.deviceTitle}: ${log.diagnosticDetail}",
                                                            color = if (log.succeeded) GhAccentGreen else GhAccentWarm,
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

                        // CARD 9B: AES-256 ENCRYPTED BACKUP & TABLET MIGRATION
                        item {
                            EncryptedBackupAndMigrationCard(
                                context = context,
                                tiles = tiles,
                                automationRules = automationRules,
                                compactInputTextStyle = compactInputTextStyle,
                                onStatusBanner = { wizardVm.statusBanner = it }
                            )
                        }

                        // CARD 10: DOORBELL & MEDIA NOTIFICATION BRIDGE
                        item {
                            SunkenWrapHeightCard(
                                outerCornerRadius = 20.dp,
                                innerCornerRadius = 15.dp,
                                sunkenInsetPadding = 5.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            Column(modifier = mod) {
                                                Text(
                                                    text = "Doorbell & Media Session Bridge",
                                                    color = GhTextPrimary,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = if (hasCameraOrMediaTiles) {
                                                        "Intercepts local doorbell events & Android MediaSession track metadata directly inside Clara."
                                                    } else {
                                                        "Grant Notification Listener access to enable automatic doorbell wake & media session sync."
                                                    },
                                                    color = GhTextSecondary,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        },
                                        secondContent = {
                                            FilledTonalButton(
                                                onClick = onOpenNotificationAccess,
                                                shape = RoundedCornerShape(9.dp),
                                                colors = ButtonDefaults.filledTonalButtonColors(
                                                    containerColor = GhTileSurfaceElevated,
                                                    contentColor = GhAccentGreen
                                                )
                                            ) {
                                                Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Notification Access", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isWideTabletLayout) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = outerHPad, vertical = outerVPad),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                SfHouseSunkenTileContainer(
                    outerCornerRadius = 22.dp,
                    innerCornerRadius = 16.dp,
                    sunkenInsetPadding = 6.dp,
                    modifier = Modifier
                        .width(252.dp)
                        .fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Settings,
                                    contentDescription = null,
                                    tint = GhAccentGreen,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Control Center",
                                    color = GhTextPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GhFont
                                )
                            }

                            HorizontalDivider(color = GhCardBorder)

                            SettingsSidebarSection.entries.forEach { section ->
                                val isSelected = wizardVm.activeSidebarSection == section
                                val sectionLabel = if (section == SettingsSidebarSection.CUSTOMISE_DASHBOARD && hiddenTilesCount > 0) {
                                    "${section.label} ($hiddenTilesCount hidden)"
                                } else {
                                    section.label
                                }
                                Surface(
                                    color = if (isSelected) GhTileGreenActive else Color.Transparent,
                                    shape = RoundedCornerShape(14.dp),
                                    border = if (isSelected) BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.55f)) else null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { wizardVm.activeSidebarSection = section }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val sectionIcon = when (section) {
                                            SettingsSidebarSection.DEVICE_CONFIG -> Icons.Outlined.AddCircleOutline
                                            SettingsSidebarSection.CUSTOMISE_DASHBOARD -> Icons.Outlined.DashboardCustomize
                                            SettingsSidebarSection.AUTOMATIONS -> Icons.Outlined.AutoAwesome
                                            SettingsSidebarSection.HOME_HEALTH -> Icons.Outlined.HealthAndSafety
                                            SettingsSidebarSection.APP_SETTINGS -> Icons.Outlined.Tune
                                        }
                                        Icon(
                                            imageVector = sectionIcon,
                                            contentDescription = null,
                                            tint = if (isSelected) GhAccentGreen else GhTextSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = sectionLabel,
                                                color = if (isSelected) GhTextPrimary else GhTextSecondary,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                fontFamily = GhFont,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = section.subtitle,
                                                color = GhTextSecondary.copy(alpha = 0.8f),
                                                fontSize = 10.sp,
                                                fontFamily = GhFont,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = handleFinishAndCloseWizard,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 11.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GhAccentGreen,
                                contentColor = Color(0xFF141518)
                            )
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Done", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    RenderActiveSectionContent()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = outerHPad, vertical = outerVPad),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SunkenWrapHeightCard(
                    outerCornerRadius = 18.dp,
                    innerCornerRadius = 14.dp,
                    sunkenInsetPadding = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Settings,
                                    contentDescription = null,
                                    tint = GhAccentGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Control Center",
                                    color = GhTextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GhFont
                                )
                            }
                            Button(
                                onClick = handleFinishAndCloseWizard,
                                shape = RoundedCornerShape(9.dp),
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GhAccentGreen,
                                    contentColor = Color(0xFF141518)
                                )
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Done", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(SettingsSidebarSection.entries) { section ->
                                val isSelected = wizardVm.activeSidebarSection == section
                                val sectionLabel = if (section == SettingsSidebarSection.CUSTOMISE_DASHBOARD && hiddenTilesCount > 0) {
                                    "${section.label} ($hiddenTilesCount hidden)"
                                } else {
                                    section.label
                                }
                                val sectionIcon = when (section) {
                                    SettingsSidebarSection.DEVICE_CONFIG -> Icons.Outlined.AddCircleOutline
                                    SettingsSidebarSection.CUSTOMISE_DASHBOARD -> Icons.Outlined.DashboardCustomize
                                    SettingsSidebarSection.AUTOMATIONS -> Icons.Outlined.AutoAwesome
                                    SettingsSidebarSection.HOME_HEALTH -> Icons.Outlined.HealthAndSafety
                                    SettingsSidebarSection.APP_SETTINGS -> Icons.Outlined.Tune
                                }
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { wizardVm.activeSidebarSection = section },
                                    shape = RoundedCornerShape(9.dp),
                                    leadingIcon = {
                                        Icon(
                                            imageVector = sectionIcon,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = sectionLabel,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    RenderActiveSectionContent()
                }
            }
        }

        wizardVm.activeCloudPhotoPickerProvider?.let { providerToPick ->
            CloudPhotosAlbumPickerModal(
                provider = providerToPick,
                onPhotosExtracted = { extractedUrls ->
                    if (extractedUrls.isNotEmpty()) {
                        wizardVm.slideshowCloudUrl = extractedUrls.joinToString(",")
                        PhotoSlideshowResolver.clearCloudCache()
                        val updated = wizardVm.currentTempBridge(bridgeSettings).copy(slideshowCloudUrl = wizardVm.slideshowCloudUrl)
                        DeviceRepository.saveBridge(context, updated)
                        wizardVm.statusBanner = "SUCCESS: Linked ${extractedUrls.size} photo(s) from ${providerToPick.label}!"
                    }
                    wizardVm.activeCloudPhotoPickerProvider = null
                },
                onDismiss = { wizardVm.activeCloudPhotoPickerProvider = null }
            )
        }

        if (showNestWebLoginModal) {
            NestWebLoginModal(
                initialEntryKey = pendingNestEntryKeyForWebModal,
                onSessionCaptured = {
                    showNestWebLoginModal = false
                    pendingNestEntryKeyForWebModal = ""
                    isSigningIntoNest = true
                    wizardVm.statusBanner = "Google Nest connected! Importing your thermostats..."
                    syncDiscoveredNestThermostatsToDashboard()
                },
                onDismiss = {
                    showNestWebLoginModal = false
                    pendingNestEntryKeyForWebModal = ""
                    isSigningIntoNest = false
                }
            )
        }
    }
}
