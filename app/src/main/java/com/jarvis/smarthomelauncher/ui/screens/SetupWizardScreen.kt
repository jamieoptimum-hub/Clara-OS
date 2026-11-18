package com.jarvis.smarthomelauncher.ui.screens

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
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

    val internalAutomationRules = remember {
        mutableStateListOf<AutomationRule>().apply {
            addAll(DeviceRepository.loadAutomations(context))
        }
    }
    val automationRules = automationRulesExternal ?: internalAutomationRules

    var activeSidebarSection by remember {
        mutableStateOf(
            if (initialTab == SetupPageTab.CONNECTED_DEVICES) SettingsSidebarSection.CUSTOMISE_DASHBOARD
            else initialSection
        )
    }

    var showSpeakerGroupsDrawer by remember { mutableStateOf(false) }
    var pendingNestEntryKeyForWebModal by remember { mutableStateOf("") }
    var editingTileIndex by remember { mutableStateOf<Int?>(null) }
    var statusBanner by remember { mutableStateOf("") }

    var haUrl by remember { mutableStateOf(bridgeSettings.haUrl) }
    var haToken by remember { mutableStateOf(bridgeSettings.haToken) }

    var imouAppId by remember { mutableStateOf(bridgeSettings.imouAppId) }
    var imouAppSecret by remember { mutableStateOf(bridgeSettings.imouAppSecret) }
    var imouApiHost by remember { mutableStateOf(bridgeSettings.imouApiHost) }

    var ezvizAppKey by remember { mutableStateOf(bridgeSettings.ezvizAppKey) }
    var ezvizAppSecret by remember { mutableStateOf(bridgeSettings.ezvizAppSecret) }
    var ezvizApiHost by remember { mutableStateOf(bridgeSettings.ezvizApiHost) }

    var nestProjectId by remember { mutableStateOf(bridgeSettings.nestProjectId) }
    var nestClientId by remember { mutableStateOf(bridgeSettings.nestClientId) }
    var nestClientSecret by remember { mutableStateOf(bridgeSettings.nestClientSecret) }
    var nestRefreshToken by remember { mutableStateOf(bridgeSettings.nestRefreshToken) }
    var nestPubSubSub by remember { mutableStateOf(bridgeSettings.nestPubSubSubscription) }
    var isSigningIntoNest by remember { mutableStateOf(false) }
    var showNestWebLoginModal by remember { mutableStateOf(false) }

    var tuyaAccessId by remember { mutableStateOf(bridgeSettings.tuyaAccessId) }
    var tuyaAccessSecret by remember { mutableStateOf(bridgeSettings.tuyaAccessSecret) }
    var tuyaUid by remember { mutableStateOf(bridgeSettings.tuyaUid) }

    var tplinkEmail by remember {
        mutableStateOf(bridgeSettings.tplinkEmail.ifBlank { TpLinkTapoKasaClient.getSavedTpLinkEmail(context) })
    }
    var tplinkPassword by remember {
        mutableStateOf(bridgeSettings.tplinkPassword.ifBlank { TpLinkTapoKasaClient.getSavedTpLinkPassword(context) })
    }
    var ewelinkEmail by remember {
        mutableStateOf(bridgeSettings.ewelinkEmail.ifBlank { EWeLinkSonoffClient.getSavedEWeLinkEmail(context) })
    }
    var ewelinkPassword by remember {
        mutableStateOf(bridgeSettings.ewelinkPassword.ifBlank { EWeLinkSonoffClient.getSavedEWeLinkPassword(context) })
    }
    var mqttBrokerHost by remember {
        mutableStateOf(bridgeSettings.mqttBrokerHost.ifBlank { MqttAndDiyClient.getMqttHost(context) })
    }
    var mqttBrokerPort by remember {
        mutableIntStateOf(bridgeSettings.mqttBrokerPort.takeIf { it > 0 } ?: MqttAndDiyClient.getMqttPort(context))
    }
    var mqttUsername by remember {
        mutableStateOf(bridgeSettings.mqttUsername.ifBlank { MqttAndDiyClient.getMqttUsername(context) })
    }
    var mqttPassword by remember {
        mutableStateOf(bridgeSettings.mqttPassword.ifBlank { MqttAndDiyClient.getMqttPassword(context) })
    }

    var selectedMusicProvider by remember { mutableStateOf(bridgeSettings.musicProvider) }
    var selectedThemeMode by remember { mutableStateOf(bridgeSettings.appThemeMode) }
    var selectedAccentPreset by remember { mutableStateOf(bridgeSettings.accentColorPreset) }
    var customAccentColorArgb by remember { mutableLongStateOf(bridgeSettings.customAccentColorArgb) }
    var selectedClockFaceStyle by remember { mutableStateOf(bridgeSettings.clockFaceStyle) }
    var selectedHeroTimeoutSec by remember { mutableIntStateOf(bridgeSettings.heroCardTimeoutSeconds) }
    var keepScreenAwake by remember { mutableStateOf(bridgeSettings.keepScreenAwake) }
    var selectedSleepTimeoutSec by remember { mutableIntStateOf(bridgeSettings.sleepTimeoutSeconds) }
    var enablePresenceWake by remember { mutableStateOf(bridgeSettings.enablePresenceWake) }
    var enableVoiceAnnouncements by remember { mutableStateOf(bridgeSettings.enableVoiceAnnouncements) }
    var selectedCloudAutoPauseSec by remember { mutableIntStateOf(bridgeSettings.cloudStreamAutoPauseSeconds) }
    var showRoomLabelsOnTiles by remember { mutableStateOf(bridgeSettings.showRoomLabelsOnTiles) }
    val customRoomsList = remember {
        mutableStateListOf<String>().apply { addAll(bridgeSettings.customRooms) }
    }
    var dashboardGreetingName by remember { mutableStateOf(bridgeSettings.dashboardGreetingName) }

    var enableVoiceControl by remember { mutableStateOf(bridgeSettings.enableVoiceControl) }
    var enableWakeWordListening by remember { mutableStateOf(bridgeSettings.enableWakeWordListening) }
    var voiceWakeWord by remember { mutableStateOf(bridgeSettings.voiceWakeWord) }
    var localLlmEndpointUrl by remember { mutableStateOf(bridgeSettings.localLlmEndpointUrl) }
    var smartThingsToken by remember { mutableStateOf(bridgeSettings.smartThingsToken) }
    var teslaWallConnectorIp by remember { mutableStateOf(bridgeSettings.teslaWallConnectorIp) }
    var frigateHostUrl by remember { mutableStateOf(bridgeSettings.frigateHostUrl) }

    var enableNestMiniDoorbellChime by remember { mutableStateOf(bridgeSettings.enableNestMiniDoorbellChime) }
    var nestMiniDoorbellVolume by remember { mutableIntStateOf(bridgeSettings.nestMiniDoorbellVolume) }
    var doorbellChimeMessage by remember { mutableStateOf(bridgeSettings.doorbellChimeMessage) }
    var doorbellChimeTargetIps by remember { mutableStateOf(bridgeSettings.doorbellChimeTargetIps) }
    var customDoorbellMp3Uri by remember { mutableStateOf(bridgeSettings.customDoorbellMp3Uri) }
    var customAlarmMp3Uri by remember { mutableStateOf(bridgeSettings.customAlarmMp3Uri) }

    var enableSwipeableSlideshowPage by remember { mutableStateOf(bridgeSettings.enableSwipeableSlideshowPage) }
    var showClockOnSlideshowPage by remember { mutableStateOf(bridgeSettings.showClockOnSlideshowPage) }
    var slideshowSourceType by remember { mutableStateOf(bridgeSettings.slideshowSourceType) }
    var slideshowCloudUrl by remember { mutableStateOf(bridgeSettings.slideshowCloudUrl) }
    val slideshowLocalUris = remember {
        mutableStateListOf<String>().apply { addAll(bridgeSettings.slideshowLocalUris) }
    }
    var slideshowIntervalSeconds by remember { mutableIntStateOf(bridgeSettings.slideshowIntervalSeconds) }
    var slideshowIncludeSubfolders by remember { mutableStateOf(bridgeSettings.slideshowIncludeSubfolders) }
    var activeCloudPhotoPickerProvider by remember { mutableStateOf<SlideshowSourceType?>(null) }
    var showAdvancedSlideshowLinkField by remember { mutableStateOf(false) }

    // Layout Templates, DPI Scaling, Nav Docking, Tariff & RBAC State
    var selectedDashboardTemplate by remember { mutableStateOf(bridgeSettings.dashboardTemplate) }
    var dashboardSlotWidgetTypes by remember { mutableStateOf(bridgeSettings.dashboardSlotWidgetTypes) }
    var dashboardCardWidthWeights by remember { mutableStateOf(bridgeSettings.dashboardCardWidthWeights) }
    var dashboardCardHeightWeights by remember { mutableStateOf(bridgeSettings.dashboardCardHeightWeights) }
    var dashboardCardZoomScales by remember { mutableStateOf(bridgeSettings.dashboardCardZoomScales) }
    var selectedDpiPreset by remember { mutableStateOf(bridgeSettings.dpiScalePreset) }
    var customDpiScalePct by remember { mutableIntStateOf(bridgeSettings.customDpiScalePct) }
    var selectedGridSizePreset by remember { mutableStateOf(bridgeSettings.gridSizePreset) }
    var selectedNavDockPosition by remember { mutableStateOf(bridgeSettings.navDockPosition) }
    var invertNavOrder by remember { mutableStateOf(bridgeSettings.invertNavOrder) }
    var activeUserRole by remember { mutableStateOf(bridgeSettings.activeUserRole) }
    var rolePinCode by remember { mutableStateOf(bridgeSettings.rolePinCode) }
    var requirePinForAdminAndVehicles by remember { mutableStateOf(bridgeSettings.requirePinForAdminAndVehicles) }
    var autoOnboardNewDevicesEnabled by remember { mutableStateOf(bridgeSettings.autoOnboardNewDevicesEnabled) }

    var tariffDayRateText by remember {
        mutableStateOf("%.2f".format(Locale.US, bridgeSettings.electricityTariffDayRateGbp))
    }
    var tariffNightRateText by remember {
        mutableStateOf("%.2f".format(Locale.US, bridgeSettings.electricityTariffNightRateGbp))
    }
    var tariffStandingChargeText by remember {
        mutableStateOf("%.2f".format(Locale.US, bridgeSettings.electricityStandingChargeGbp))
    }
    var currencySymbol by remember {
        mutableStateOf(bridgeSettings.currencySymbol.ifBlank { "\u00A3" })
    }
    var offPeakStartHour by remember {
        mutableIntStateOf(bridgeSettings.offPeakStartHour.coerceIn(0, 23))
    }
    var offPeakEndHour by remember {
        mutableIntStateOf(bridgeSettings.offPeakEndHour.coerceIn(0, 23))
    }
    var securityHomeTempText by remember {
        mutableStateOf("%.1f".format(Locale.US, bridgeSettings.securityHomeTempC))
    }
    var securityAwayTempText by remember {
        mutableStateOf("%.1f".format(Locale.US, bridgeSettings.securityAwayTempC))
    }
    var securityNightTempText by remember {
        mutableStateOf("%.1f".format(Locale.US, bridgeSettings.securityNightTempC))
    }
    var securityVacationTempText by remember {
        mutableStateOf("%.1f".format(Locale.US, bridgeSettings.securityVacationTempC))
    }

    var showSelfHealingLogPanel by remember { mutableStateOf(false) }
    val integrationHealthMap by JarvisLiveBridge.integrationHealthFlow.collectAsState()
    val selfHealingLogs by JarvisLiveBridge.selfHealingLogsFlow.collectAsState()
    val discoveredMeshPeers by JarvisLiveBridge.discoveredMeshPeersFlow.collectAsState()

    fun currentTempBridge(): BridgeSettings {
        val latestSaved = DeviceRepository.loadBridge(context)
        return latestSaved.copy(
            haUrl = haUrl,
            haToken = haToken,
            imouAppId = imouAppId,
            imouAppSecret = imouAppSecret,
            imouApiHost = imouApiHost,
            ezvizAppKey = ezvizAppKey,
            ezvizAppSecret = ezvizAppSecret,
            ezvizApiHost = ezvizApiHost,
            nestProjectId = nestProjectId,
            nestClientId = nestClientId,
            nestClientSecret = nestClientSecret,
            nestRefreshToken = nestRefreshToken,
            nestPubSubSubscription = nestPubSubSub,
            tuyaAccessId = tuyaAccessId,
            tuyaAccessSecret = tuyaAccessSecret,
            tuyaUid = tuyaUid,
            tplinkEmail = tplinkEmail,
            tplinkPassword = tplinkPassword,
            ewelinkEmail = ewelinkEmail,
            ewelinkPassword = ewelinkPassword,
            mqttBrokerHost = mqttBrokerHost,
            mqttBrokerPort = mqttBrokerPort,
            mqttUsername = mqttUsername,
            mqttPassword = mqttPassword,
            jaguarEmail = latestSaved.jaguarEmail.ifBlank { bridgeSettings.jaguarEmail },
            jaguarPassword = latestSaved.jaguarPassword.ifBlank { bridgeSettings.jaguarPassword },
            jaguarPin = latestSaved.jaguarPin.ifBlank { bridgeSettings.jaguarPin },
            jaguarVin = latestSaved.jaguarVin.ifBlank { bridgeSettings.jaguarVin },
            mercedesEmail = latestSaved.mercedesEmail.ifBlank { bridgeSettings.mercedesEmail },
            mercedesPassword = latestSaved.mercedesPassword.ifBlank { bridgeSettings.mercedesPassword },
            mercedesPin = latestSaved.mercedesPin.ifBlank { bridgeSettings.mercedesPin },
            mercedesVin = latestSaved.mercedesVin.ifBlank { bridgeSettings.mercedesVin },
            lytmiIpAddress = latestSaved.lytmiIpAddress.ifBlank { bridgeSettings.lytmiIpAddress },
            lytmiDeviceId = latestSaved.lytmiDeviceId.ifBlank { bridgeSettings.lytmiDeviceId },
            lytmiLocalKey = latestSaved.lytmiLocalKey.ifBlank { bridgeSettings.lytmiLocalKey },
            goveeApiKey = latestSaved.goveeApiKey.ifBlank { bridgeSettings.goveeApiKey },
            goveeIpAddress = latestSaved.goveeIpAddress.ifBlank { bridgeSettings.goveeIpAddress },
            twinklyIpAddress = latestSaved.twinklyIpAddress.ifBlank { bridgeSettings.twinklyIpAddress },
            plexServerUrl = latestSaved.plexServerUrl.ifBlank { bridgeSettings.plexServerUrl },
            plexToken = latestSaved.plexToken.ifBlank { bridgeSettings.plexToken },
            musicProvider = selectedMusicProvider,
            appThemeMode = selectedThemeMode,
            accentColorPreset = selectedAccentPreset,
            customAccentColorArgb = customAccentColorArgb,
            clockFaceStyle = selectedClockFaceStyle,
            heroCardTimeoutSeconds = selectedHeroTimeoutSec,
            keepScreenAwake = keepScreenAwake,
            sleepTimeoutSeconds = selectedSleepTimeoutSec,
            enablePresenceWake = enablePresenceWake,
            enableVoiceAnnouncements = enableVoiceAnnouncements,
            cloudStreamAutoPauseSeconds = selectedCloudAutoPauseSec,
            showRoomLabelsOnTiles = showRoomLabelsOnTiles,
            customRooms = customRoomsList.toList(),
            dashboardGreetingName = dashboardGreetingName,
            enableVoiceControl = enableVoiceControl,
            enableWakeWordListening = enableWakeWordListening,
            voiceWakeWord = voiceWakeWord,
            enableNestMiniDoorbellChime = enableNestMiniDoorbellChime,
            nestMiniDoorbellVolume = nestMiniDoorbellVolume,
            doorbellChimeMessage = doorbellChimeMessage,
            doorbellChimeTargetIps = doorbellChimeTargetIps,
            customDoorbellMp3Uri = customDoorbellMp3Uri,
            customAlarmMp3Uri = customAlarmMp3Uri,
            enableSwipeableSlideshowPage = enableSwipeableSlideshowPage,
            showClockOnSlideshowPage = showClockOnSlideshowPage,
            slideshowSourceType = slideshowSourceType,
            slideshowCloudUrl = slideshowCloudUrl,
            slideshowLocalUris = slideshowLocalUris.toList(),
            slideshowIntervalSeconds = slideshowIntervalSeconds,
            slideshowIncludeSubfolders = slideshowIncludeSubfolders,
            dashboardCardOrder = latestSaved.dashboardCardOrder.ifEmpty { bridgeSettings.dashboardCardOrder },
            dashboardCardWidthWeights = dashboardCardWidthWeights,
            dashboardCardHeightWeights = dashboardCardHeightWeights,
            dashboardCardZoomScales = dashboardCardZoomScales,
            dashboardSlotWidgetTypes = dashboardSlotWidgetTypes,
            dpiScalePreset = selectedDpiPreset,
            customDpiScalePct = customDpiScalePct,
            gridSizePreset = selectedGridSizePreset,
            dashboardTemplate = selectedDashboardTemplate,
            navDockPosition = selectedNavDockPosition,
            invertNavOrder = invertNavOrder,
            activeUserRole = activeUserRole,
            rolePinCode = rolePinCode,
            requirePinForAdminAndVehicles = requirePinForAdminAndVehicles,
            autoOnboardNewDevicesEnabled = autoOnboardNewDevicesEnabled,
            userProfiles = latestSaved.userProfiles.ifEmpty { bridgeSettings.userProfiles },
            activeUserProfileId = latestSaved.activeUserProfileId.ifBlank { bridgeSettings.activeUserProfileId },
            customRadioStations = latestSaved.customRadioStations,
            activeRadioStationId = latestSaved.activeRadioStationId.ifBlank { bridgeSettings.activeRadioStationId },
            electricityTariffDayRateGbp = tariffDayRateText.toFloatOrNull() ?: bridgeSettings.electricityTariffDayRateGbp,
            electricityTariffNightRateGbp = tariffNightRateText.toFloatOrNull() ?: bridgeSettings.electricityTariffNightRateGbp,
            electricityStandingChargeGbp = tariffStandingChargeText.toFloatOrNull() ?: bridgeSettings.electricityStandingChargeGbp,
            currencySymbol = currencySymbol.ifBlank { "\u00A3" },
            offPeakStartHour = offPeakStartHour.coerceIn(0, 23),
            offPeakEndHour = offPeakEndHour.coerceIn(0, 23),
            securityCenterMode = latestSaved.securityCenterMode,
            securityHomeTempC = (securityHomeTempText.toFloatOrNull() ?: bridgeSettings.securityHomeTempC).coerceIn(5f, 35f),
            securityAwayTempC = (securityAwayTempText.toFloatOrNull() ?: bridgeSettings.securityAwayTempC).coerceIn(5f, 35f),
            securityNightTempC = (securityNightTempText.toFloatOrNull() ?: bridgeSettings.securityNightTempC).coerceIn(5f, 35f),
            securityVacationTempC = (securityVacationTempText.toFloatOrNull() ?: bridgeSettings.securityVacationTempC).coerceIn(5f, 35f),
            localLlmEndpointUrl = localLlmEndpointUrl,
            smartThingsToken = smartThingsToken.trim(),
            frigateHostUrl = frigateHostUrl.trim(),
            teslaAccessToken = latestSaved.teslaAccessToken.ifBlank { bridgeSettings.teslaAccessToken },
            teslaVin = latestSaved.teslaVin.ifBlank { bridgeSettings.teslaVin },
            teslaWallConnectorIp = teslaWallConnectorIp.trim()
        )
    }

    LaunchedEffect(
        haUrl, haToken, imouAppId, imouAppSecret, ezvizAppKey, ezvizAppSecret,
        nestRefreshToken, tuyaAccessId, tuyaAccessSecret, tuyaUid,
        tplinkEmail, tplinkPassword, ewelinkEmail, ewelinkPassword,
        mqttBrokerHost, mqttBrokerPort, mqttUsername, mqttPassword,
        selectedMusicProvider, selectedThemeMode, selectedAccentPreset, customAccentColorArgb,
        selectedClockFaceStyle, selectedHeroTimeoutSec,
        keepScreenAwake, selectedSleepTimeoutSec, enablePresenceWake,
        enableVoiceAnnouncements, selectedCloudAutoPauseSec, showRoomLabelsOnTiles, customRoomsList.toList(),
        dashboardGreetingName, enableVoiceControl, enableWakeWordListening, voiceWakeWord,
        localLlmEndpointUrl, smartThingsToken, teslaWallConnectorIp, frigateHostUrl,
        enableNestMiniDoorbellChime, nestMiniDoorbellVolume, doorbellChimeMessage,
        doorbellChimeTargetIps, customDoorbellMp3Uri, customAlarmMp3Uri,
        enableSwipeableSlideshowPage, showClockOnSlideshowPage,
        slideshowSourceType, slideshowCloudUrl, slideshowLocalUris.toList(), slideshowIntervalSeconds, slideshowIncludeSubfolders,
        selectedDashboardTemplate, dashboardSlotWidgetTypes,
        dashboardCardWidthWeights, dashboardCardHeightWeights, dashboardCardZoomScales,
        selectedDpiPreset, customDpiScalePct, selectedGridSizePreset,
        selectedNavDockPosition, invertNavOrder, activeUserRole, rolePinCode, requirePinForAdminAndVehicles,
        autoOnboardNewDevicesEnabled,
        tariffDayRateText, tariffNightRateText, tariffStandingChargeText,
        currencySymbol, offPeakStartHour, offPeakEndHour,
        securityHomeTempText, securityAwayTempText, securityNightTempText, securityVacationTempText
    ) {
        delay(250L)
        DeviceRepository.saveBridge(context, currentTempBridge())
    }

    val settingsPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                val str = uri.toString()
                if (str !in slideshowLocalUris) {
                    slideshowLocalUris.add(str)
                }
            }
            PhotoSlideshowResolver.clearCloudCache()
            val updated = currentTempBridge().copy(slideshowLocalUris = slideshowLocalUris.toList())
            DeviceRepository.saveBridge(context, updated)
            statusBanner = "SUCCESS: Selected ${slideshowLocalUris.size} local photo(s) for slideshow!"
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
            customDoorbellMp3Uri = uri.toString()
            JarvisLiveBridge.activeCustomMp3Uri = customDoorbellMp3Uri
            statusBanner = "SUCCESS: Selected custom Doorbell MP3 file!"
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
            customAlarmMp3Uri = uri.toString()
            statusBanner = "SUCCESS: Selected custom Security Alarm MP3 file!"
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
            statusBanner = "Microphone permission granted! Hands-free '$voiceWakeWord' voice control is active."
        } else {
            statusBanner = "Microphone permission is required for voice control."
        }
    }

    fun refreshCastSpeakersList() {
        if (isScanningCastSpeakers) return
        scope.launch {
            isScanningCastSpeakers = true
            val found = GoogleCastLanClient.discoverCastDevices(context)
            isScanningCastSpeakers = false
            statusBanner = if (found.isNotEmpty()) {
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
            ?: nestRefreshToken.takeIf { it.isNotBlank() && it != "nest_web_session_active" }.orEmpty()
        nestRefreshToken = preservedRefresh
        val updatedBridge = currentTempBridge().copy(nestRefreshToken = preservedRefresh)
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
                            suggestedRoom = nd.roomName.ifBlank { inferRoomFromDeviceTitle(nd.displayName, customRoomsList) },
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
                    statusBanner = "SUCCESS: Imported $count Google Nest Thermostat(s) to your dashboard!"
                } else {
                    statusBanner = "Wait for your thermostats to load on the Nest screen, then tap 'Done & Import to Clara'."
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
        val updated = currentTempBridge()
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
            if (statusBanner.isNotBlank()) {
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
                            text = statusBanner,
                            color = if (statusBanner.startsWith("SUCCESS") || statusBanner.startsWith("Added") || statusBanner.startsWith("Saved") || statusBanner.startsWith("Paired") || statusBanner.startsWith("Connected") || statusBanner.startsWith("Executed") || statusBanner.startsWith("Found") || statusBanner.startsWith("Tested") || statusBanner.startsWith("Accent") || statusBanner.startsWith("Hero") || statusBanner.startsWith("Clock") || statusBanner.startsWith("Applied") || statusBanner.startsWith("Self-Healing") || statusBanner.startsWith("Digital"))
                                GhAccentGreen else GhAccentWarm,
                            fontSize = 12.sp,
                            fontFamily = GhFont,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { statusBanner = "" }, modifier = Modifier.size(22.dp)) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = GhTextSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            when (activeSidebarSection) {
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
                                        bridge = currentTempBridge(),
                                        tiles = listOf(targetTile),
                                        customAnnouncementText = doorbellChimeMessage
                                    )
                                    statusBanner = "Playing doorbell chime on '${targetTile.title}'..."
                                },
                                onStatusBanner = { statusBanner = it }
                            )
                        } else {
                            UnifiedScanAllTab(
                                context = context,
                                tiles = tiles,
                                compactInputTextStyle = compactInputTextStyle,
                                onSetHaUrl = { newUrl ->
                                    haUrl = newUrl
                                    statusBanner = "Connected to Home Assistant!"
                                },
                                onStatusBanner = { statusBanner = it }
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
                        bridgeSettings = currentTempBridge(),
                        audioManager = audioManager,
                        compactInputTextStyle = compactInputTextStyle,
                        onSpeak = onSpeak,
                        onStatusBanner = { statusBanner = it }
                    )
                }

                SettingsSidebarSection.HOME_HEALTH -> {
                    HomeHealthAndDiagnosticsSection(
                        context = context,
                        tiles = tiles,
                        automationRules = automationRules,
                        bridgeSettings = currentTempBridge(),
                        isCompactMobile = isCompactMobile,
                        compactInputTextStyle = compactInputTextStyle,
                        onStatusBanner = { statusBanner = it }
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
                                        currentColorArgb = customAccentColorArgb,
                                        selectedPreset = selectedAccentPreset,
                                        isCompactMobile = isCompactMobile,
                                        onColorSelected = { newArgb, matchedPreset ->
                                            customAccentColorArgb = newArgb
                                            if (matchedPreset != null) {
                                                selectedAccentPreset = matchedPreset
                                            }
                                            DeviceRepository.applyThemeMode(
                                                mode = selectedThemeMode,
                                                accentPreset = selectedAccentPreset,
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
                                                selectedText = heroTimeoutOptions.firstOrNull { it.first == selectedHeroTimeoutSec }?.second
                                                    ?: "$selectedHeroTimeoutSec Seconds",
                                                options = heroTimeoutOptions,
                                                optionLabel = { it.second },
                                                onOptionSelected = { (sec, label) ->
                                                    selectedHeroTimeoutSec = sec
                                                    statusBanner = "Hero Card timer set to $label"
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Dashboard Clock Face Style (7 Styles)",
                                                selectedText = selectedClockFaceStyle.label,
                                                options = ClockFaceStyle.entries,
                                                optionLabel = { it.label },
                                                optionSubtitle = { it.subtitle },
                                                onOptionSelected = { style ->
                                                    selectedClockFaceStyle = style
                                                    statusBanner = "Clock face updated to ${style.label}"
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
                                                selectedText = selectedDashboardTemplate.label,
                                                options = DashboardTemplate.entries,
                                                optionLabel = { it.label },
                                                optionSubtitle = { it.subtitle },
                                                onOptionSelected = { template ->
                                                    selectedDashboardTemplate = template
                                                    dashboardSlotWidgetTypes = template.slotWidgets
                                                    dashboardCardWidthWeights = template.widthWeights
                                                    dashboardCardHeightWeights = template.heightWeights
                                                    statusBanner = "Applied template: ${template.label}"
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Navigation Bar Dock Position (Left / Right / Top / Bottom)",
                                                selectedText = selectedNavDockPosition.label,
                                                options = NavDockPosition.entries,
                                                optionLabel = { it.label },
                                                onOptionSelected = { pos ->
                                                    selectedNavDockPosition = pos
                                                    statusBanner = "Navigation bar docked to ${pos.label}"
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        }
                                    )

                                    CleanDropdownSelector(
                                        label = "Dashboard Grid Size & Tile Density (6\u00D73 / 8\u00D74 / 10\u00D75 / 12\u00D76)",
                                        selectedText = "${selectedGridSizePreset.label} \u2014 ${selectedGridSizePreset.subtitle}",
                                        options = DashboardGridSizePreset.entries,
                                        optionLabel = { it.label },
                                        optionSubtitle = { it.subtitle },
                                        onOptionSelected = { preset ->
                                            selectedGridSizePreset = preset
                                            DeviceRepository.saveBridge(context, currentTempBridge().copy(gridSizePreset = preset))
                                            statusBanner = "Grid size set to ${preset.label}"
                                        },
                                        textStyle = compactInputTextStyle,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Global Viewport DPI Scale Preset",
                                                selectedText = selectedDpiPreset.label,
                                                options = DpiScalePreset.entries,
                                                optionLabel = { it.label },
                                                onOptionSelected = { preset ->
                                                    selectedDpiPreset = preset
                                                    if (preset != DpiScalePreset.CUSTOM) {
                                                        customDpiScalePct = (preset.scaleFactor * 100f).roundToInt()
                                                    }
                                                    statusBanner = "Global DPI scale set to ${preset.label}"
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
                                                    checked = invertNavOrder,
                                                    onCheckedChange = { invertNavOrder = it }
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
                                                text = "Fine-Tune Global DPI Scale (${customDpiScalePct}%)",
                                                color = GhTextSecondary,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            TextButton(
                                                onClick = {
                                                    selectedDpiPreset = DpiScalePreset.DEFAULT
                                                    customDpiScalePct = 100
                                                    dashboardCardZoomScales = DashboardCardSlot.defaultZoomMap
                                                }
                                            ) {
                                                Text("Reset 100%", fontSize = 11.sp, color = GhAccentGreen)
                                            }
                                        }
                                        Slider(
                                            value = customDpiScalePct.toFloat(),
                                            onValueChange = { v ->
                                                selectedDpiPreset = DpiScalePreset.CUSTOM
                                                customDpiScalePct = v.roundToInt().coerceIn(70, 150)
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
                                        color = if (keepScreenAwake) GhTileGreenActive else GhTileSurfaceElevated.copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (keepScreenAwake) GhAccentGreen.copy(alpha = 0.5f) else GhCardBorder
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
                                                    tint = if (keepScreenAwake) GhAccentGreen else GhAccentWarm,
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
                                                        text = if (keepScreenAwake) {
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
                                                checked = keepScreenAwake,
                                                onCheckedChange = { awake ->
                                                    keepScreenAwake = awake
                                                    if (!awake && selectedSleepTimeoutSec == 0) {
                                                        selectedSleepTimeoutSec = 90
                                                    }
                                                    statusBanner = if (awake) {
                                                        "Keep Screen Awake enabled (screen will stay on)."
                                                    } else {
                                                        "Keep Screen Awake disabled (using ${selectedSleepTimeoutSec}s timeout)."
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
                                                selectedText = selectedThemeMode.label,
                                                options = AppThemeMode.entries,
                                                optionLabel = { it.label },
                                                onOptionSelected = { mode ->
                                                    selectedThemeMode = mode
                                                    DeviceRepository.applyThemeMode(
                                                        mode = mode,
                                                        accentPreset = selectedAccentPreset,
                                                        customAccentColorArgb = customAccentColorArgb
                                                    )
                                                    statusBanner = "Saved theme: ${mode.label}"
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Screen Timeout (Inactivity Sleep Timer)",
                                                selectedText = if (keepScreenAwake) {
                                                    "Keep Screen Awake Active (Always On)"
                                                } else {
                                                    sleepOptions.firstOrNull { it.first == selectedSleepTimeoutSec }?.second
                                                        ?: "${selectedSleepTimeoutSec} Seconds"
                                                },
                                                options = sleepOptions,
                                                optionLabel = { it.second },
                                                onOptionSelected = { (sec, label) ->
                                                    selectedSleepTimeoutSec = sec
                                                    keepScreenAwake = (sec == 0)
                                                    statusBanner = "Screen timeout set to $label"
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
                                                selectedText = cloudAutoPauseOptions.firstOrNull { it.first == selectedCloudAutoPauseSec }?.second
                                                    ?: "2 Minutes (Recommended)",
                                                options = cloudAutoPauseOptions,
                                                optionLabel = { it.second },
                                                onOptionSelected = { (sec, label) ->
                                                    selectedCloudAutoPauseSec = sec
                                                    statusBanner = "Cloud stream auto-pause set to $label"
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Default Music Player Service",
                                                selectedText = selectedMusicProvider.label,
                                                options = MusicProvider.entries,
                                                optionLabel = { it.label },
                                                onOptionSelected = { provider ->
                                                    selectedMusicProvider = provider
                                                    statusBanner = "Default music service set to ${provider.label}"
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
                                                    checked = showRoomLabelsOnTiles,
                                                    onCheckedChange = { show ->
                                                        showRoomLabelsOnTiles = show
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
                                                    checked = enablePresenceWake,
                                                    onCheckedChange = { enabled ->
                                                        enablePresenceWake = enabled
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
                                                            isVisible = true,
                                                            slideshowSourceType = slideshowSourceType,
                                                            slideshowCloudUrl = slideshowCloudUrl,
                                                            slideshowLocalUris = slideshowLocalUris.toList(),
                                                            slideshowIntervalSeconds = slideshowIntervalSeconds
                                                        )
                                                        DeviceRepository.saveTiles(context, tiles)
                                                        statusBanner = "SUCCESS: Updated 'Family Photos' slideshow configuration!"
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
                                                                bridgeSettings = currentTempBridge(),
                                                                scope = scope
                                                            )
                                                            if (onboardRes.isSuccess) {
                                                                val createdId = onboardRes.getOrThrow().id
                                                                val idx = tiles.indexOfFirst { it.id == createdId }
                                                                if (idx in tiles.indices) {
                                                                    tiles[idx] = tiles[idx].copy(
                                                                        colSpan = 2,
                                                                        rowSpan = 2,
                                                                        isTall = true,
                                                                        slideshowSourceType = slideshowSourceType,
                                                                        slideshowCloudUrl = slideshowCloudUrl,
                                                                        slideshowLocalUris = slideshowLocalUris.toList(),
                                                                        slideshowIntervalSeconds = slideshowIntervalSeconds
                                                                    )
                                                                    DeviceRepository.saveTiles(context, tiles)
                                                                }
                                                            }
                                                            statusBanner = "SUCCESS: Linked 'Family Photos' slideshow configuration!"
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
                                                    checked = enableSwipeableSlideshowPage,
                                                    onCheckedChange = { enabled ->
                                                        enableSwipeableSlideshowPage = enabled
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
                                                    checked = showClockOnSlideshowPage,
                                                    onCheckedChange = { show ->
                                                        showClockOnSlideshowPage = show
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
                                                selectedText = slideshowSourceType.label,
                                                options = SlideshowSourceType.entries.filter { it != SlideshowSourceType.CUSTOM_URLS || showAdvancedSlideshowLinkField },
                                                optionLabel = { it.label },
                                                optionSubtitle = { it.subtitle },
                                                onOptionSelected = { src ->
                                                    slideshowSourceType = src
                                                    PhotoSlideshowResolver.clearCloudCache()
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Slide Interval Speed",
                                                selectedText = slideshowIntervalOptions.firstOrNull { it.first == slideshowIntervalSeconds }?.second
                                                    ?: "Every ${slideshowIntervalSeconds}s",
                                                options = slideshowIntervalOptions,
                                                optionLabel = { it.second },
                                                onOptionSelected = { (sec, _) ->
                                                    slideshowIntervalSeconds = sec
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        }
                                    )

                                    when (slideshowSourceType) {
                                        SlideshowSourceType.LOCAL_DEVICE -> {
                                            ResponsiveSplitRow(
                                                isStacked = isCompactMobile,
                                                firstContent = { mod ->
                                                    Text(
                                                        text = "Selected Local Photos: ${slideshowLocalUris.size} photo(s)",
                                                        color = GhAccentGreen,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        modifier = mod
                                                    )
                                                },
                                                secondContent = {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        if (slideshowLocalUris.isNotEmpty()) {
                                                            OutlinedButton(
                                                                onClick = {
                                                                    slideshowLocalUris.clear()
                                                                    statusBanner = "Cleared local slideshow photos."
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
                                            val linkedCount = slideshowCloudUrl.split(",", "\n").count { it.trim().startsWith("http") }
                                            ResponsiveSplitRow(
                                                isStacked = isCompactMobile,
                                                firstContent = { mod ->
                                                    Text(
                                                        text = if (linkedCount > 0) {
                                                            "${slideshowSourceType.label} Connected ($linkedCount photos/albums linked) \u2713"
                                                        } else {
                                                            "Sign in to ${slideshowSourceType.label} to select the albums you want:"
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
                                                                activeCloudPhotoPickerProvider = slideshowSourceType
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
                                                                text = if (slideshowSourceType == SlideshowSourceType.GOOGLE_PHOTOS)
                                                                    "Sign In & Select Albums"
                                                                else
                                                                    "Sign In & Select Folders",
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                        TextButton(onClick = { showAdvancedSlideshowLinkField = !showAdvancedSlideshowLinkField }) {
                                                            Text(
                                                                text = if (showAdvancedSlideshowLinkField) "Hide Link" else "Advanced Link",
                                                                fontSize = 11.sp,
                                                                color = GhTextSecondary
                                                            )
                                                        }
                                                    }
                                                }
                                            )
                                            if (showAdvancedSlideshowLinkField) {
                                                OutlinedTextField(
                                                    value = slideshowCloudUrl,
                                                    onValueChange = { url ->
                                                        slideshowCloudUrl = url
                                                        PhotoSlideshowResolver.clearCloudCache()
                                                    },
                                                    label = {
                                                        Text(
                                                            if (slideshowSourceType == SlideshowSourceType.GOOGLE_PHOTOS)
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
                                                value = slideshowCloudUrl,
                                                onValueChange = { url ->
                                                    slideshowCloudUrl = url
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
                                                            bridge = currentTempBridge(),
                                                            tiles = tiles,
                                                            customAnnouncementText = doorbellChimeMessage
                                                        )
                                                        statusBanner = "Playing test doorbell chime on Nest Mini(s)..."
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
                                                    checked = enableNestMiniDoorbellChime,
                                                    onCheckedChange = { enabled ->
                                                        enableNestMiniDoorbellChime = enabled
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
                                                            text = if (customDoorbellMp3Uri.isNotBlank()) "Custom Doorbell MP3 Linked \u2713" else "Custom Doorbell MP3 (Optional)",
                                                            color = if (customDoorbellMp3Uri.isNotBlank()) GhAccentGreen else GhTextPrimary,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(
                                                            text = if (customDoorbellMp3Uri.isNotBlank()) {
                                                                Uri.parse(customDoorbellMp3Uri).lastPathSegment ?: "Selected MP3"
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
                                                        if (customDoorbellMp3Uri.isNotBlank()) {
                                                            TextButton(onClick = { customDoorbellMp3Uri = "" }) {
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
                                                            text = if (customAlarmMp3Uri.isNotBlank()) "Custom Security Alarm MP3 \u2713" else "Security Alarm MP3 (Optional)",
                                                            color = if (customAlarmMp3Uri.isNotBlank()) GhAccentGreen else GhTextPrimary,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(
                                                            text = if (customAlarmMp3Uri.isNotBlank()) {
                                                                Uri.parse(customAlarmMp3Uri).lastPathSegment ?: "Selected MP3"
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
                                                        if (customAlarmMp3Uri.isNotBlank()) {
                                                            TextButton(onClick = { customAlarmMp3Uri = "" }) {
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
                                                value = doorbellChimeMessage,
                                                onValueChange = {
                                                    doorbellChimeMessage = it
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
                                                    text = "Chime Volume: $nestMiniDoorbellVolume%",
                                                    color = GhAccentGreen,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Slider(
                                                    value = nestMiniDoorbellVolume.toFloat(),
                                                    onValueChange = { v ->
                                                        nestMiniDoorbellVolume = v.roundToInt().coerceIn(20, 100)
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
                                                val isPicked = key in doorbellChimeTargetIps
                                                FilterChip(
                                                    selected = isPicked,
                                                    onClick = {
                                                        val next = doorbellChimeTargetIps.toMutableSet()
                                                        if (!next.add(key)) next.remove(key)
                                                        doorbellChimeTargetIps = next
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
                                                    text = "Say \"$voiceWakeWord, is my Jaguar locked?\", \"set Lytmi to HDMI 2\", or \"why did my routine fail?\".",
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
                                                selectedText = wakeWordOptions.firstOrNull { it.first.equals(voiceWakeWord, true) }?.second
                                                    ?: "\"$voiceWakeWord\"",
                                                options = wakeWordOptions,
                                                optionLabel = { it.second },
                                                onOptionSelected = { (word, _) ->
                                                    voiceWakeWord = word
                                                    statusBanner = "Wake word set to \"$word\""
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            OutlinedTextField(
                                                value = localLlmEndpointUrl,
                                                onValueChange = { localLlmEndpointUrl = it },
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
                                                        text = "Listen for \"$voiceWakeWord\" (ducks music automatically)",
                                                        color = GhTextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                                SfHouseMintPillSwitch(
                                                    checked = enableWakeWordListening,
                                                    onCheckedChange = { enabled ->
                                                        enableWakeWordListening = enabled
                                                        if (enabled) {
                                                            enableVoiceControl = true
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
                                                    checked = enableVoiceAnnouncements,
                                                    onCheckedChange = { enabled ->
                                                        enableVoiceAnnouncements = enabled
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
                                                DeviceRepository.saveBridge(context, currentTempBridge())
                                                ClaraApplication.ensureTuyaSdkInitialized(context, true)
                                                statusBanner = if (ClaraApplication.isTuyaSdkReady(context)) {
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
                                                value = tuyaAccessId,
                                                onValueChange = { tuyaAccessId = it.trim() },
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
                                                value = tuyaAccessSecret,
                                                onValueChange = { tuyaAccessSecret = it.trim() },
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
                                        value = tuyaUid,
                                        onValueChange = { tuyaUid = it.trim() },
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
                                                value = smartThingsToken,
                                                onValueChange = { smartThingsToken = it },
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
                                                value = teslaWallConnectorIp,
                                                onValueChange = { teslaWallConnectorIp = it },
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
                                                value = haUrl,
                                                onValueChange = { haUrl = it },
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
                                                value = frigateHostUrl,
                                                onValueChange = { frigateHostUrl = it },
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
                                tariffDayRateText = tariffDayRateText,
                                onTariffDayRateChange = { tariffDayRateText = it },
                                tariffNightRateText = tariffNightRateText,
                                onTariffNightRateChange = { tariffNightRateText = it },
                                tariffStandingChargeText = tariffStandingChargeText,
                                onTariffStandingChargeChange = { tariffStandingChargeText = it },
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
                                                selectedText = currencyChoices.firstOrNull { it.first == currencySymbol }?.second
                                                    ?: currencySymbol,
                                                options = currencyChoices,
                                                optionLabel = { it.second },
                                                onOptionSelected = { (sym, _) ->
                                                    currencySymbol = sym
                                                    statusBanner = "Currency updated to $sym"
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
                                                    selectedText = "%02d:00".format(Locale.US, offPeakStartHour),
                                                    options = hourOptions,
                                                    optionLabel = { it.second },
                                                    onOptionSelected = { (h, _) -> offPeakStartHour = h },
                                                    textStyle = compactInputTextStyle,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                CleanDropdownSelector(
                                                    label = "Off-Peak End",
                                                    selectedText = "%02d:00".format(Locale.US, offPeakEndHour),
                                                    options = hourOptions,
                                                    optionLabel = { it.second },
                                                    onOptionSelected = { (h, _) -> offPeakEndHour = h },
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
                                                    value = securityHomeTempText,
                                                    onValueChange = { securityHomeTempText = it },
                                                    label = { Text("Home \u00B0C", fontSize = 11.sp) },
                                                    textStyle = compactInputTextStyle,
                                                    shape = RoundedCornerShape(14.dp),
                                                    colors = claraFieldColors(),
                                                    singleLine = true,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                OutlinedTextField(
                                                    value = securityAwayTempText,
                                                    onValueChange = { securityAwayTempText = it },
                                                    label = { Text("Away \u00B0C", fontSize = 11.sp) },
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
                                                    value = securityNightTempText,
                                                    onValueChange = { securityNightTempText = it },
                                                    label = { Text("Night \u00B0C", fontSize = 11.sp) },
                                                    textStyle = compactInputTextStyle,
                                                    shape = RoundedCornerShape(14.dp),
                                                    colors = claraFieldColors(),
                                                    singleLine = true,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                OutlinedTextField(
                                                    value = securityVacationTempText,
                                                    onValueChange = { securityVacationTempText = it },
                                                    label = { Text("Vacation \u00B0C", fontSize = 11.sp) },
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
                                        items(customRoomsList.toList(), key = { it }) { roomName ->
                                            InputChip(
                                                selected = true,
                                                onClick = {
                                                    if (customRoomsList.size > 1) {
                                                        customRoomsList.remove(roomName)
                                                    }
                                                },
                                                shape = RoundedCornerShape(50),
                                                label = { Text(roomName, fontSize = 11.sp) },
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
                                                if (clean.isNotBlank() && customRoomsList.none { it.equals(clean, ignoreCase = true) }) {
                                                    customRoomsList.add(clean)
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
                                activeUserRole = activeUserRole,
                                onActiveUserRoleChange = { activeUserRole = it },
                                rolePinCode = rolePinCode,
                                onRolePinCodeChange = { rolePinCode = it },
                                requirePinForAdminAndVehicles = requirePinForAdminAndVehicles,
                                onRequirePinChange = { requirePinForAdminAndVehicles = it },
                                isCompactMobile = isCompactMobile,
                                compactInputTextStyle = compactInputTextStyle,
                                onStatusBanner = { statusBanner = it }
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
                                                onClick = { activeSidebarSection = SettingsSidebarSection.HOME_HEALTH },
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
                                onStatusBanner = { statusBanner = it }
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
                                val isSelected = activeSidebarSection == section
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
                                        .clickable { activeSidebarSection = section }
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
                                val isSelected = activeSidebarSection == section
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
                                    onClick = { activeSidebarSection = section },
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

        activeCloudPhotoPickerProvider?.let { providerToPick ->
            CloudPhotosAlbumPickerModal(
                provider = providerToPick,
                onPhotosExtracted = { extractedUrls ->
                    if (extractedUrls.isNotEmpty()) {
                        slideshowCloudUrl = extractedUrls.joinToString(",")
                        PhotoSlideshowResolver.clearCloudCache()
                        val updated = currentTempBridge().copy(slideshowCloudUrl = slideshowCloudUrl)
                        DeviceRepository.saveBridge(context, updated)
                        statusBanner = "SUCCESS: Linked ${extractedUrls.size} photo(s) from ${providerToPick.label}!"
                    }
                    activeCloudPhotoPickerProvider = null
                },
                onDismiss = { activeCloudPhotoPickerProvider = null }
            )
        }

        if (showNestWebLoginModal) {
            NestWebLoginModal(
                initialEntryKey = pendingNestEntryKeyForWebModal,
                onSessionCaptured = {
                    showNestWebLoginModal = false
                    pendingNestEntryKeyForWebModal = ""
                    isSigningIntoNest = true
                    statusBanner = "Google Nest connected! Importing your thermostats..."
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
