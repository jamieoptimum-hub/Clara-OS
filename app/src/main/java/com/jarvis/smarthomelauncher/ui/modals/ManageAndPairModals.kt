package com.jarvis.smarthomelauncher.ui.modals

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.Manifest
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// STAGE 3: PROTOCOL-DRIVEN UNIVERSAL DEVICE ONBOARDING MODAL
// - Handles any ResolvedDeviceIdentity using its OnboardingRequirement
//   (ZeroTouchReady, AutoProvisioningRequired, ShortCodeRequired,
//   PhysicalAcceptRequired, or InAppAccountWorkflowRequired) in 1 user decision.
// ---------------------------------------------------------------------------
@Composable
fun UniversalDeviceOnboardingModal(
    context: Context,
    identity: ResolvedDeviceIdentity,
    tiles: MutableList<SmartTile>,
    onStatusBanner: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val bridge = remember { DeviceRepository.loadBridge(context) }
    val compactInputStyle = TextStyle(fontSize = 13.sp, fontFamily = GhFont, color = GhTextPrimary)

    var targetRoom by remember(identity.identityKey) {
        mutableStateOf(
            identity.suggestedRoom.ifBlank {
                inferRoomFromDeviceTitle(identity.displayName, bridge.customRooms)
                    .ifBlank { DEFAULT_ROOMS.first() }
            }
        )
    }
    var shortCodeDraft by remember(identity.identityKey) { mutableStateOf("") }
    var wifiSsid by remember {
        mutableStateOf(
            TuyaDirectPairingManager.getSavedWifiSsid(context)
                .ifBlank { TuyaDirectPairingManager.detectCurrentWifiSsid(context) }
        )
    }
    var wifiPassword by remember {
        mutableStateOf(TuyaDirectPairingManager.getSavedWifiPassword(context, wifiSsid))
    }
    var showWifiPassword by remember { mutableStateOf(false) }
    var accountEmailDraft by remember(identity.identityKey) { mutableStateOf("") }
    var accountPasswordDraft by remember(identity.identityKey) { mutableStateOf("") }
    var showAccountPassword by remember { mutableStateOf(false) }
    var activeAccountWorkflowKey by remember(identity.identityKey) {
        mutableStateOf(
            (identity.onboardingRequirement as? OnboardingRequirement.InAppAccountWorkflowRequired)?.workflowKey.orEmpty()
        )
    }
    var isBusy by remember { mutableStateOf(false) }
    var statusNote by remember { mutableStateOf(identity.onboardingRequirement.requirementSummary) }
    var showNestModal by remember { mutableStateOf(false) }
    var showEzvizModal by remember { mutableStateOf(false) }
    var selectedIdentity by remember(identity.identityKey) { mutableStateOf(identity) }
    var selectedBlinkMode by remember(identity.identityKey) {
        val isApInitial = identity.mergedTransports.contains(TransportRadioType.WIFI_SOFTAP_BEACON) ||
                identity.protocolBadges.any { it.contains("AP", true) || it.contains("Hotspot", true) }
        mutableStateOf(if (isApInitial) TuyaPairingMode.AP_HOTSPOT else TuyaPairingMode.AUTO_COMBO_EZ)
    }
    var identifyRelayState by remember(identity.identityKey) { mutableStateOf(false) }

    val allResolvedIdentities by UniversalOnboardingEngine.resolvedIdentitiesFlow.collectAsState()
    val blinkingCandidatesRankedByRssi = remember(allResolvedIdentities, identity.identityKey) {
        allResolvedIdentities
            .filter { it.onboardingRequirement is OnboardingRequirement.AutoProvisioningRequired }
            .sortedByDescending { it.rssiDbm ?: -95 }
    }

    val availableWifi by JarvisLiveBridge.availableWifiNetworksFlow.collectAsState()
    val isScanningWifi by JarvisLiveBridge.isScanningWifiNetworksFlow.collectAsState()
    val isPairingTuya by JarvisLiveBridge.isPairingTuyaFlow.collectAsState()
    val pairingStatusText by JarvisLiveBridge.tuyaPairingStatusFlow.collectAsState()

    val roomOptions = remember(bridge.customRooms) {
        bridge.customRooms.ifEmpty { DEFAULT_ROOMS }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    Dialog(
        onDismissRequest = {
            if (isPairingTuya) TuyaDirectPairingManager.stopDirectPairing()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhTileSurface,
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.5.dp, GhAccentGreen.copy(alpha = 0.55f)),
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth(0.90f)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Surface(
                            color = GhAccentGreen.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = iconForTileType(identity.suggestedType),
                                    contentDescription = null,
                                    tint = GhAccentGreen,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = identity.displayName,
                                color = GhTextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            )
                            Text(
                                text = "${identity.deviceClass.label} \u2022 ${identity.deviceSubtype.label}",
                                color = GhAccentGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GhFont
                            )
                            Text(
                                text = identity.onboardingRequirement.requirementSummary,
                                color = GhTextSecondary,
                                fontSize = 11.sp,
                                fontFamily = GhFont,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    FilledTonalIconButton(
                        onClick = {
                            if (isPairingTuya) TuyaDirectPairingManager.stopDirectPairing()
                            onDismiss()
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider(color = GhTileSurfaceElevated)

                CleanDropdownSelector(
                    label = "Room Assignment",
                    selectedText = targetRoom,
                    options = roomOptions,
                    optionLabel = { it },
                    onOptionSelected = { targetRoom = it },
                    textStyle = compactInputStyle,
                    modifier = Modifier.fillMaxWidth()
                )

                when (val req = identity.onboardingRequirement) {
                    is OnboardingRequirement.ShortCodeRequired -> {
                        OutlinedTextField(
                            value = shortCodeDraft,
                            onValueChange = { shortCodeDraft = it.trim() },
                            label = { Text(req.hintLabel, fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is OnboardingRequirement.AutoProvisioningRequired -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = selectedBlinkMode != TuyaPairingMode.AP_HOTSPOT,
                                onClick = { selectedBlinkMode = TuyaPairingMode.BLE_DIRECT },
                                shape = RoundedCornerShape(50),
                                label = {
                                    Text("Fast Blinking", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            )
                            FilterChip(
                                selected = selectedBlinkMode == TuyaPairingMode.AP_HOTSPOT,
                                onClick = { selectedBlinkMode = TuyaPairingMode.AP_HOTSPOT },
                                shape = RoundedCornerShape(50),
                                label = {
                                    Text("Slow Blinking / AP Mode", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            )
                        }

                        // F-26: RSSI-Ranked Multi-Blinking Target Picker when multiple devices blink simultaneously
                        if (blinkingCandidatesRankedByRssi.size > 1) {
                            Text(
                                text = "Multiple Blinking Devices Detected (Ranked by Signal Strength):",
                                color = GhAccentGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(blinkingCandidatesRankedByRssi, key = { it.identityKey }) { candidate ->
                                    val isPicked = selectedIdentity.identityKey == candidate.identityKey
                                    val rssiText = candidate.rssiDbm?.let { "${it} dBm" } ?: "BLE/AP"
                                    FilterChip(
                                        selected = isPicked,
                                        onClick = { selectedIdentity = candidate },
                                        shape = RoundedCornerShape(50),
                                        label = {
                                            Text(
                                                text = "${candidate.displayName} ($rssiText)",
                                                fontSize = 10.5.sp,
                                                fontWeight = if (isPicked) FontWeight.Bold else FontWeight.Medium
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        WifiSsidSelectorDropdown(
                            selectedSsid = wifiSsid,
                            onSsidSelected = { wifiSsid = it },
                            availableNetworks = availableWifi,
                            isScanning = isScanningWifi,
                            onRefreshNetworks = {
                                scope.launch { TuyaDirectPairingManager.scanAvailableWifiNetworks(context) }
                            },
                            textStyle = compactInputStyle,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = wifiPassword,
                            onValueChange = { wifiPassword = it },
                            label = { Text("Home 2.4GHz Wi-Fi Password", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            visualTransformation = if (showWifiPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showWifiPassword = !showWifiPassword }) {
                                    Icon(
                                        imageVector = if (showWifiPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = null
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is OnboardingRequirement.InAppAccountWorkflowRequired -> {
                        if (activeAccountWorkflowKey.isBlank()) {
                            activeAccountWorkflowKey = req.workflowKey
                        }
                    }
                    else -> {}
                }

                if (activeAccountWorkflowKey.isNotBlank() &&
                    activeAccountWorkflowKey.uppercase(Locale.US) !in setOf("NEST", "EZVIZ")
                ) {
                    OutlinedTextField(
                        value = accountEmailDraft,
                        onValueChange = { accountEmailDraft = it.trim() },
                        label = { Text("Account Email / Username or App ID", fontSize = 11.sp) },
                        textStyle = compactInputStyle,
                        shape = RoundedCornerShape(14.dp),
                        colors = claraFieldColors(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = accountPasswordDraft,
                        onValueChange = { accountPasswordDraft = it },
                        label = { Text("Account Password or App Secret", fontSize = 11.sp) },
                        textStyle = compactInputStyle,
                        shape = RoundedCornerShape(14.dp),
                        colors = claraFieldColors(),
                        visualTransformation = if (showAccountPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showAccountPassword = !showAccountPassword }) {
                                Icon(
                                    imageVector = if (showAccountPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(
                    text = if (isPairingTuya) pairingStatusText.ifBlank { statusNote } else statusNote,
                    color = GhTextSecondary,
                    fontSize = 12.sp,
                    fontFamily = GhFont
                )
                LivePairingConnectionTimeline(targetKey = identity.identityKey)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // F-26: Identify Device (Toggle Relay) button to physically click/blink the target plug
                    if (selectedIdentity.ipAddress.isNotBlank() ||
                        selectedIdentity.hardwareSerialOrNodeId.isNotBlank()
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val nextRelay = !identifyRelayState
                                identifyRelayState = nextRelay
                                scope.launch(Dispatchers.IO) {
                                    val devId = selectedIdentity.hardwareSerialOrNodeId.ifBlank { selectedIdentity.cloudDeviceId }
                                    val ip = selectedIdentity.ipAddress.substringBefore(":").trim()
                                    when {
                                        ip.isNotBlank() && selectedIdentity.primaryProviderId.contains("KASA", true) ->
                                            TpLinkTapoKasaClient.setKasaRelayState(ip, nextRelay)
                                        ip.isNotBlank() && selectedIdentity.primaryProviderId.contains("SHELLY", true) ->
                                            SmartHomeApiClient.toggleLocalShellyRelay(ip, nextRelay)
                                        devId.isNotBlank() ->
                                            TuyaApiClient.sendSingleSocketPlugCommand(
                                                bridge = bridge,
                                                deviceId = devId,
                                                socketNumber = 1,
                                                turnOn = nextRelay,
                                                ipAddress = ip,
                                                localKey = selectedIdentity.localKeyOrPin
                                            )
                                    }
                                }
                                statusNote = "Sent Identify Relay Toggle (${if (nextRelay) "ON" else "OFF"}) to '${selectedIdentity.displayName}'."
                            },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0xFF00E5FF).copy(alpha = 0.18f),
                                contentColor = Color(0xFF00E5FF)
                            )
                        ) {
                            Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Identify (Toggle Relay)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    TextButton(
                        onClick = {
                            if (isPairingTuya) TuyaDirectPairingManager.stopDirectPairing()
                            onDismiss()
                        }
                    ) {
                        Text("Cancel", color = GhTextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (isBusy) return@Button
                            // Section 6: Enforce BSSID frequency (2400–2499 MHz) 2.4 GHz band check
                            if (selectedIdentity.onboardingRequirement is OnboardingRequirement.AutoProvisioningRequired &&
                                !TuyaDirectPairingManager.isPlugHotspotSsid(wifiSsid) &&
                                TuyaDirectPairingManager.validateSsidSupports24GhzByBssid(context, wifiSsid) ==
                                WifiBandValidationStatus.BLOCKED_5GHZ_OR_6GHZ_ONLY
                            ) {
                                statusNote = "'$wifiSsid' only broadcasts on 5 GHz / 6 GHz. Choose a 2.4 GHz or dual-band Wi-Fi network."
                                return@Button
                            }
                            isBusy = true
                            scope.launch {
                                var currentBridge = DeviceRepository.loadBridge(context)
                                if (activeAccountWorkflowKey.isNotBlank() &&
                                    accountEmailDraft.isNotBlank() &&
                                    accountPasswordDraft.isNotBlank()
                                ) {
                                    when (activeAccountWorkflowKey.uppercase(Locale.US)) {
                                        "TPLINK", "TAPO" -> {
                                            TpLinkTapoKasaClient.saveTpLinkCredentials(context, accountEmailDraft, accountPasswordDraft)
                                            currentBridge = currentBridge.copy(
                                                tplinkEmail = accountEmailDraft,
                                                tplinkPassword = accountPasswordDraft
                                            )
                                        }
                                        "EWELINK", "SONOFF" -> {
                                            EWeLinkSonoffClient.saveEWeLinkCredentials(context, accountEmailDraft, accountPasswordDraft)
                                            currentBridge = currentBridge.copy(
                                                ewelinkEmail = accountEmailDraft,
                                                ewelinkPassword = accountPasswordDraft
                                            )
                                        }
                                        "IMOU" -> {
                                            currentBridge = currentBridge.copy(
                                                imouAppId = accountEmailDraft,
                                                imouAppSecret = accountPasswordDraft
                                            )
                                        }
                                        "JAGUAR" -> {
                                            currentBridge = currentBridge.copy(
                                                jaguarEmail = accountEmailDraft,
                                                jaguarPassword = accountPasswordDraft
                                            )
                                        }
                                        "MERCEDES" -> {
                                            currentBridge = currentBridge.copy(
                                                mercedesEmail = accountEmailDraft,
                                                mercedesPassword = accountPasswordDraft
                                            )
                                        }
                                    }
                                    DeviceRepository.saveBridge(context, currentBridge)
                                }

                                val effectiveIdentity = if (selectedBlinkMode == TuyaPairingMode.AP_HOTSPOT &&
                                    !selectedIdentity.mergedTransports.contains(TransportRadioType.WIFI_SOFTAP_BEACON)
                                ) {
                                    selectedIdentity.copy(
                                        mergedTransports = listOf(TransportRadioType.WIFI_SOFTAP_BEACON) + selectedIdentity.mergedTransports
                                    )
                                } else {
                                    selectedIdentity
                                }
                                val res = UniversalOnboardingEngine.onboardResolvedIdentity(
                                    context = context,
                                    identity = effectiveIdentity,
                                    tiles = tiles,
                                    bridgeSettings = currentBridge,
                                    scope = scope,
                                    customRoomOverride = targetRoom,
                                    shortCodeInput = shortCodeDraft,
                                    wifiSsid = wifiSsid,
                                    wifiPassword = wifiPassword,
                                    onOpenAccountModal = { wfKey ->
                                        isBusy = false
                                        when (wfKey.uppercase(Locale.US)) {
                                            "NEST" -> showNestModal = true
                                            "EZVIZ" -> showEzvizModal = true
                                            else -> {
                                                activeAccountWorkflowKey = wfKey
                                                statusNote = "Enter your credentials above to link directly inside Clara."
                                            }
                                        }
                                    },
                                    onStatusUpdate = { msg -> statusNote = msg }
                                )
                                isBusy = false
                                if (res.isSuccess) {
                                    val tile = res.getOrThrow()
                                    onStatusBanner("SUCCESS: Connected '${tile.title}'!")
                                    onDismiss()
                                } else {
                                    val err = res.exceptionOrNull()?.message.orEmpty()
                                    if (err.startsWith("NEEDS_NEST_SIGN_IN", ignoreCase = true)) {
                                        showNestModal = true
                                    } else if (!err.startsWith("IN_APP_ACCOUNT_WORKFLOW")) {
                                        statusNote = err
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GhAccentGreen,
                            contentColor = Color(0xFF0F1114)
                        )
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBusy) "Connecting..." else identity.onboardingRequirement.actionButtonLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showNestModal) {
        NestWebLoginModal(
            initialEntryKey = shortCodeDraft,
            onSessionCaptured = {
                val curBridge = DeviceRepository.loadBridge(context)
                val preservedToken = com.jarvis.smarthomelauncher.core.ClaraSecretVault
                    .getNestSdmRefreshToken(context)
                    ?: curBridge.nestRefreshToken.takeIf { it.isNotBlank() && it != "nest_web_session_active" }
                        .orEmpty()
                val updatedBridge = curBridge.copy(nestRefreshToken = preservedToken)
                DeviceRepository.saveBridge(context, updatedBridge)
                PlayerBackgroundScope.launch {
                    val discoverRes = NestSdmApiClient.discoverNestDevices(updatedBridge)
                    withContext(Dispatchers.Main) {
                        val found = discoverRes.getOrNull().orEmpty()
                        if (found.isNotEmpty()) {
                            val newTiles = found.map { nd ->
                                val nestCaps = listOf(
                                    DeviceCapabilityDescriptor(
                                        key = "hvac_mode",
                                        label = "Heating Mode",
                                        kind = DeviceCapabilityKind.ENUM_MODE,
                                        isWritable = true,
                                        isPinnedToHero = true,
                                        currentValue = nd.hvacMode.ifBlank { "HEAT" },
                                        enumOptions = listOf("HEAT", "ECO", "OFF")
                                    ),
                                    DeviceCapabilityDescriptor(
                                        key = "target_temperature",
                                        label = "Target Setpoint",
                                        kind = DeviceCapabilityKind.NUMERIC_SLIDER,
                                        isWritable = true,
                                        isPinnedToHero = true,
                                        currentValue = "%.1f".format(Locale.US, nd.targetTempC),
                                        minValue = 9f,
                                        maxValue = 32f,
                                        stepValue = 0.5f,
                                        unit = "\u00B0C",
                                        rendererType = CapabilityRendererType.TEMPERATURE_DIAL
                                    ),
                                    DeviceCapabilityDescriptor(
                                        key = "ambient_temperature",
                                        label = "Room Temperature",
                                        kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                                        isWritable = false,
                                        isPinnedToHero = false,
                                        currentValue = "%.1f".format(Locale.US, nd.ambientTempC),
                                        unit = "\u00B0C",
                                        rendererType = CapabilityRendererType.SENSOR_BADGE
                                    ),
                                    DeviceCapabilityDescriptor(
                                        key = "humidity",
                                        label = "Ambient Humidity",
                                        kind = DeviceCapabilityKind.READ_ONLY_SENSOR,
                                        isWritable = false,
                                        isPinnedToHero = false,
                                        currentValue = nd.humidityPct.toString(),
                                        unit = "%",
                                        rendererType = CapabilityRendererType.SENSOR_BADGE
                                    )
                                )
                                SmartTile(
                                    title = nd.displayName,
                                    roomOrSubtitle = nd.roomName.ifBlank { targetRoom },
                                    type = nd.suggestedType,
                                    colSpan = if (nd.suggestedType.isCameraType()) 2 else 1,
                                    isVisible = true,
                                    isOnline = nd.isOnline,
                                    isActive = nd.hvacMode.equals("HEAT", ignoreCase = true),
                                    temperature = nd.ambientTempC,
                                    targetTemperature = nd.targetTempC,
                                    humidity = nd.humidityPct,
                                    hvacMode = nd.hvacMode,
                                    hvacStatus = nd.hvacStatus,
                                    entityId = nd.deviceName,
                                    serialNumber = nd.deviceName,
                                    companionAppPackage = "",
                                    capabilities = nestCaps,
                                    capabilitySchemaHash = CapabilitySchemaHasher.computeHash(nestCaps),
                                    deviceClassOverride = nd.suggestedType.toSemanticDeviceClass(),
                                    lifecycleState = if (nd.isOnline) DeviceLifecycleState.ONLINE else DeviceLifecycleState.OFFLINE,
                                    stateAuthorityTier = StateAuthorityTier.CLOUD_SHADOW_STATE,
                                    activeProviderId = "NEST_CLOUD"
                                )
                            }
                            val added = UniversalOnboardingEngine.onboardLegacyCandidateTiles(
                                context = context,
                                candidateTiles = newTiles,
                                tiles = tiles,
                                bridgeSettings = updatedBridge,
                                scope = scope,
                                sourceLabel = "Google Nest Onboarding"
                            )
                            DeviceRepository.saveTiles(context, tiles)
                            showNestModal = false
                            onStatusBanner("SUCCESS: Imported $added Google Nest Thermostat(s) to your dashboard!")
                            onDismiss()
                        } else {
                            onStatusBanner("Wait for your thermostats to load on the Nest screen, then tap 'Done & Import to Clara'.")
                        }
                    }
                }
            },
            onDismiss = { showNestModal = false }
        )
    }

    if (showEzvizModal) {
        EzvizOAuthWebSignInModal(
            context = context,
            appKey = "",
            onTokenCaptured = { capturedToken, capturedDomain ->
                EzvizCloudApiClient.saveEzvizOAuthTokens(
                    context = context,
                    accessToken = capturedToken,
                    areaDomain = capturedDomain
                )
                showEzvizModal = false
                PlayerBackgroundScope.launch {
                    val bridgeNow = DeviceRepository.loadBridge(context)
                    val cams = EzvizCloudApiClient.discoverBoundDoorbells(bridgeNow).getOrNull().orEmpty()
                    withContext(Dispatchers.Main) {
                        if (cams.isNotEmpty()) {
                            val newTiles = cams.map { cam ->
                                val detail = EzvizCloudApiClient.getCachedDeviceDetail(cam.deviceId)
                                val pin = detail?.verificationCode.orEmpty().ifBlank {
                                    EzvizCloudApiClient.getSavedEzvizPinForSerial(context, cam.deviceId)
                                }
                                val localCamIp = detail?.localIp.orEmpty()
                                SmartTile(
                                    title = cam.name,
                                    roomOrSubtitle = targetRoom.ifBlank { "Front Door" },
                                    type = TileType.EZVIZ_DOORBELL,
                                    colSpan = 2,
                                    isVisible = true,
                                    isOnline = cam.status.equals("online", true),
                                    ipAddress = localCamIp,
                                    serialNumber = cam.deviceId,
                                    secretCode = pin,
                                    channelId = "1",
                                    companionAppPackage = "",
                                    deviceClassOverride = DeviceClass.CAMERA,
                                    activeProviderId = if (localCamIp.isNotBlank()) "RTSP_ONVIF_LAN" else "EZVIZ_RTSP_CLOUD"
                                )
                            }
                            val added = UniversalOnboardingEngine.onboardLegacyCandidateTiles(
                                context = context,
                                candidateTiles = newTiles,
                                tiles = tiles,
                                bridgeSettings = bridgeNow,
                                scope = scope,
                                sourceLabel = "EZVIZ Onboarding"
                            )
                            onStatusBanner("SUCCESS: Imported $added EZVIZ camera(s) to your dashboard!")
                        }
                        onDismiss()
                    }
                }
            },
            onDismiss = { showEzvizModal = false }
        )
    }
}

// ---------------------------------------------------------------------------
// STEP-BY-STEP GUIDED DEVICE PAIRING POP-UP WIZARD
// - Guides the user through putting their device into pairing mode,
//   checking 5GHz vs 2.4GHz Wi-Fi frequency with automatic BLE fallback,
//   or linking Pro Breeze Tower Fans, Govee, Twinkly, Lytmi, Jaguar InControl,
//   Mercedes me, Plex, Matter, Tapo & Sonoff with zero synthetic dummy tiles.
// ---------------------------------------------------------------------------
@Composable
fun GuidedDevicePairingWizardModal(
    context: Context,
    initialBrand: BrandOption,
    tiles: MutableList<SmartTile>,
    onSyncTuyaEcosystemDevices: () -> Unit,
    onTriggerFullScan: () -> Unit,
    onStatusBanner: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val savedBridge = remember { DeviceRepository.loadBridge(context) }
    var selectedBrand by remember { mutableStateOf(initialBrand) }
    var pairingMode by remember { mutableStateOf(TuyaPairingMode.AUTO_COMBO_EZ) }
    var wifiSsid by remember { mutableStateOf(TuyaDirectPairingManager.detectCurrentWifiSsid(context)) }
    var wifiPassword by remember { mutableStateOf(TuyaDirectPairingManager.getSavedWifiPassword(context)) }
    var showWifiPassword by remember { mutableStateOf(false) }
    var targetRoom by remember(selectedBrand) {
        mutableStateOf(selectedBrand.defaultRoom.ifBlank { DEFAULT_ROOMS.first() })
    }

    var accountEmail by remember(selectedBrand) {
        mutableStateOf(
            when (selectedBrand) {
                BrandOption.TAPO -> TpLinkTapoKasaClient.getSavedTpLinkEmail(context)
                BrandOption.SONOFF -> EWeLinkSonoffClient.getSavedEWeLinkEmail(context)
                BrandOption.JAGUAR -> savedBridge.jaguarEmail
                BrandOption.MERCEDES -> savedBridge.mercedesEmail
                else -> ""
            }
        )
    }
    var accountPassword by remember(selectedBrand) {
        mutableStateOf(
            when (selectedBrand) {
                BrandOption.JAGUAR -> savedBridge.jaguarPassword
                BrandOption.MERCEDES -> savedBridge.mercedesPassword
                else -> ""
            }
        )
    }
    var vehiclePin by remember(selectedBrand) {
        mutableStateOf(
            when (selectedBrand) {
                BrandOption.JAGUAR -> savedBridge.jaguarPin
                BrandOption.MERCEDES -> savedBridge.mercedesPin
                else -> ""
            }
        )
    }
    var vehicleVin by remember(selectedBrand) {
        mutableStateOf(
            when (selectedBrand) {
                BrandOption.JAGUAR -> DeviceRepository.sanitizeRealVin(savedBridge.jaguarVin)
                BrandOption.MERCEDES -> DeviceRepository.sanitizeRealVin(savedBridge.mercedesVin)
                else -> ""
            }
        )
    }

    var lytmiIp by remember { mutableStateOf(savedBridge.lytmiIpAddress) }
    var lytmiDeviceId by remember { mutableStateOf(savedBridge.lytmiDeviceId) }
    var lytmiLocalKey by remember { mutableStateOf(savedBridge.lytmiLocalKey) }

    var goveeApiKey by remember { mutableStateOf(savedBridge.goveeApiKey) }
    var goveeIp by remember { mutableStateOf(savedBridge.goveeIpAddress) }
    var twinklyIp by remember { mutableStateOf(savedBridge.twinklyIpAddress) }

    var plexServerUrl by remember { mutableStateOf(savedBridge.plexServerUrl.ifBlank { "http://192.168.1.100:32400" }) }
    var plexToken by remember { mutableStateOf(savedBridge.plexToken) }

    var matterCode by remember { mutableStateOf("") }
    var nestSetupCode by remember { mutableStateOf("") }
    var showNestWebModalInWizard by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var statusNote by remember { mutableStateOf("") }

    val isPairingTuya by JarvisLiveBridge.isPairingTuyaFlow.collectAsState()
    val pairingStatusText by JarvisLiveBridge.tuyaPairingStatusFlow.collectAsState()
    val availableWifi by JarvisLiveBridge.availableWifiNetworksFlow.collectAsState()
    val isScanningWifi by JarvisLiveBridge.isScanningWifiNetworksFlow.collectAsState()

    val isTabletOn5Ghz = remember(wifiSsid, availableWifi) {
        TuyaDirectPairingManager.isTabletOn5GhzBand(context)
    }
    val currentWifiFreqMhz = remember(wifiSsid, availableWifi) {
        TuyaDirectPairingManager.getCurrentWifiFrequencyMhz(context)
    }

    val compactInputStyle = TextStyle(fontSize = 13.sp, fontFamily = GhFont, color = GhTextPrimary)

    fun startTuyaWifiAndBleBroadcast(defaultTitle: String, forcedType: TileType? = null) {
        TuyaDirectPairingManager.saveWifiCredentials(context, wifiSsid, wifiPassword)
        TuyaDirectPairingManager.startDirectPairing(
            context = context,
            ssid = wifiSsid,
            password = wifiPassword,
            mode = pairingMode,
            scope = scope,
            onDevicePaired = { packet ->
                scope.launch {
                    val isDual = packet.multiGangSocketCount >= 2
                    val resolvedType = forcedType ?: when {
                        selectedBrand == BrandOption.PRO_BREEZE -> TileType.PROBREEZE_FAN
                        selectedBrand == BrandOption.LYTMI -> TileType.LYTMI_SYNC_BOX
                        isDual -> TileType.SMARTKNIGHT_PLUG
                        else -> packet.suggestedType
                    }
                    val resolvedTitle = packet.name.ifBlank {
                        when {
                            selectedBrand == BrandOption.PRO_BREEZE -> "Pro Breeze Tower Fan"
                            selectedBrand == BrandOption.LYTMI -> "Lytmi TV Sync Box"
                            isDual -> "Smart Dual Socket"
                            else -> defaultTitle
                        }
                    }
                    val introspectedCaps = TuyaApiClient.capabilitiesByDevice[packet.gwId].orEmpty()
                    val hasPowerMeter = TuyaApiClient.hasPowerTelemetryByDevice[packet.gwId] ?: false
                    val baseTile = SmartTile(
                        title = resolvedTitle,
                        roomOrSubtitle = targetRoom,
                        type = resolvedType,
                        colSpan = 1,
                        isVisible = true,
                        isOnline = true,
                        isActive = true,
                        multiGangSocketCount = if (isDual && resolvedType != TileType.PROBREEZE_FAN) 2 else 1,
                        ipAddress = packet.ipAddress,
                        serialNumber = packet.gwId,
                        entityId = packet.gwId,
                        secretCode = packet.localKey,
                        hasExplicitPowerTelemetry = hasPowerMeter,
                        deviceClassOverride = resolvedType.toSemanticDeviceClass(hasPowerMeter),
                        lifecycleState = DeviceLifecycleState.ONLINE,
                        stateAuthorityTier = StateAuthorityTier.DEVICE_HARDWARE_TRUTH,
                        activeProviderId = if (packet.ipAddress.isNotBlank()) "TUYA_LAN_6668" else "TUYA_CLOUD",
                        capabilities = when {
                            introspectedCaps.isNotEmpty() -> introspectedCaps
                            resolvedType == TileType.PROBREEZE_FAN -> TuyaApiClient.defaultProBreezeFanCapabilities()
                            resolvedType == TileType.LYTMI_SYNC_BOX -> TuyaApiClient.defaultLytmiCapabilities()
                            else -> emptyList()
                        }
                    )
                    UniversalOnboardingEngine.onboardLegacyCandidateTiles(
                        context = context,
                        candidateTiles = listOf(baseTile),
                        tiles = tiles,
                        bridgeSettings = DeviceRepository.loadBridge(context),
                        scope = scope,
                        sourceLabel = "Guided Wi-Fi/BLE Pairing"
                    )
                    JarvisLiveBridge.recordOnboardingOutcome(succeeded = true, userDecisionCount = 1)
                    onSyncTuyaEcosystemDevices()
                    onStatusBanner("SUCCESS: Paired '${baseTile.title}' & added to dashboard!")
                    onDismiss()
                }
            }
        )
    }

    val wizardBlePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        TuyaDirectPairingManager.scanAvailableWifiNetworks(context)
    }

    LaunchedEffect(Unit) {
        val permsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missing = permsToRequest.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            wizardBlePermissionLauncher.launch(permsToRequest.toTypedArray())
        } else {
            TuyaDirectPairingManager.scanAvailableWifiNetworks(context)
        }
    }

    Dialog(
        onDismissRequest = {
            if (isPairingTuya) TuyaDirectPairingManager.stopDirectPairing()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhTileSurface,
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.5.dp, GhAccentGreen.copy(alpha = 0.55f)),
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = GhAccentGreen.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.AutoFixHigh,
                                    contentDescription = null,
                                    tint = GhAccentGreen,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "One-Step Automatic Device Pairing & Link",
                                color = GhTextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            )
                            Text(
                                text = "${selectedBrand.label} \u2022 Auto-Detects Capabilities, Gang Count & Power Meter",
                                color = GhAccentGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GhFont
                            )
                        }
                    }
                    FilledTonalIconButton(
                        onClick = {
                            if (isPairingTuya) TuyaDirectPairingManager.stopDirectPairing()
                            onDismiss()
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider(color = GhTileSurfaceElevated)

                val semanticClass = selectedBrand.defaultTileType.toSemanticDeviceClass()
                Surface(
                    color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.4f)),
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
                                text = "Capability Class: ${semanticClass.label}",
                                color = GhAccentGreen,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            )
                            Text(
                                text = semanticClass.roleDescription,
                                color = GhTextSecondary,
                                fontSize = 11.sp,
                                fontFamily = GhFont
                            )
                        }
                    }
                }

                when (selectedBrand) {
                    BrandOption.JAGUAR,
                    BrandOption.MERCEDES -> {
                        Text(
                            text = "Enter your ${selectedBrand.label} credentials (encrypted via Android KeyStore AES-256):",
                            color = GhTextSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = accountEmail,
                            onValueChange = { accountEmail = it },
                            label = { Text("${selectedBrand.defaultTileType.brandName} Email Address", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = accountPassword,
                            onValueChange = { accountPassword = it },
                            label = { Text("${selectedBrand.defaultTileType.brandName} Password", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = vehiclePin,
                                onValueChange = { vehiclePin = it },
                                label = { Text("4-Digit Remote PIN (Lock/Climate)", fontSize = 11.sp) },
                                textStyle = compactInputStyle,
                                shape = RoundedCornerShape(14.dp),
                                colors = claraFieldColors(),
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = vehicleVin,
                                onValueChange = { vehicleVin = it.uppercase() },
                                label = { Text("Real Vehicle VIN (Auto-Pulled if Blank)", fontSize = 11.sp) },
                                textStyle = compactInputStyle,
                                shape = RoundedCornerShape(14.dp),
                                colors = claraFieldColors(),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    BrandOption.GOVEE -> {
                        Text(
                            text = "Govee Local LAN (UDP 4001-4003) & Cloud API Setup:",
                            color = GhTextSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = goveeApiKey,
                            onValueChange = { goveeApiKey = it },
                            label = { Text("Govee Developer API Key (Optional if LAN Control is ON)", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = goveeIp,
                            onValueChange = { goveeIp = it },
                            label = { Text("Govee Local IP Address (Leave blank to auto-scan UDP 4001-4002)", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    BrandOption.TWINKLY -> {
                        Text(
                            text = "Twinkly XLED Local REST (/xled/v1/) & UDP 5555 Setup:",
                            color = GhTextSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = twinklyIp,
                            onValueChange = { twinklyIp = it },
                            label = { Text("Twinkly Local IP Address (Leave blank to auto-discover on Wi-Fi)", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    BrandOption.LYTMI -> {
                        Text(
                            text = "Pair a blinking Lytmi device over 2.4GHz Wi-Fi + BLE, OR enter known LAN 6668 details:",
                            color = GhTextSecondary,
                            fontSize = 12.sp
                        )
                        WifiSsidSelectorDropdown(
                            selectedSsid = wifiSsid,
                            onSsidSelected = { wifiSsid = it },
                            availableNetworks = availableWifi,
                            isScanning = isScanningWifi,
                            onRefreshNetworks = {
                                scope.launch { TuyaDirectPairingManager.scanAvailableWifiNetworks(context) }
                            },
                            textStyle = compactInputStyle,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = wifiPassword,
                            onValueChange = { wifiPassword = it },
                            label = { Text("Home 2.4GHz Wi-Fi Password", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            visualTransformation = if (showWifiPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showWifiPassword = !showWifiPassword }) {
                                    Icon(
                                        imageVector = if (showWifiPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = null
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = lytmiIp,
                                onValueChange = { lytmiIp = it },
                                label = { Text("Lytmi Local IP (Optional)", fontSize = 11.sp) },
                                textStyle = compactInputStyle,
                                shape = RoundedCornerShape(14.dp),
                                colors = claraFieldColors(),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = lytmiDeviceId,
                                onValueChange = { lytmiDeviceId = it },
                                label = { Text("Device ID / gwId (Optional)", fontSize = 11.sp) },
                                textStyle = compactInputStyle,
                                shape = RoundedCornerShape(14.dp),
                                colors = claraFieldColors(),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedTextField(
                            value = lytmiLocalKey,
                            onValueChange = { lytmiLocalKey = it },
                            label = { Text("16-Character Local Key (Optional for Direct AES-6668 LAN)", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    BrandOption.PLEX -> {
                        OutlinedTextField(
                            value = plexServerUrl,
                            onValueChange = { plexServerUrl = it },
                            label = { Text("Plex Server URL (e.g. http://192.168.1.50:32400)", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = plexToken,
                            onValueChange = { plexToken = it },
                            label = { Text("X-Plex-Token (Optional)", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    BrandOption.MATTER -> {
                        OutlinedTextField(
                            value = matterCode,
                            onValueChange = { matterCode = it },
                            label = { Text("11-Digit Matter Setup Code or MT:... QR String", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    BrandOption.NEST -> {
                        Surface(
                            color = GhTileSurfaceElevated.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "How to Add a Brand-New or Existing Nest Thermostat:",
                                    color = GhAccentGreen,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "1. On the physical Nest Thermostat wall screen, open Settings -> Network and connect it to your home Wi-Fi.\n" +
                                            "2. Enter the 7-character Entry Key (3rd-Gen / Nest E) or 11-digit Matter code from the thermostat screen below.\n" +
                                            "3. Or leave the code box blank and tap below to sign in with Google and import all Nest Thermostats automatically.",
                                    color = GhTextPrimary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        OutlinedTextField(
                            value = nestSetupCode,
                            onValueChange = { nestSetupCode = it.trim() },
                            label = { Text("7-Char Entry Key or 11-Digit Code (Or Leave Blank to Sign In)", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    BrandOption.IMOU -> {
                        Text(
                            text = "Enter your Imou Camera Safety Code / Password (printed on the camera sticker) for direct local RTSP video, or your Imou Cloud App Secret:",
                            color = GhTextSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = accountPassword,
                            onValueChange = { accountPassword = it },
                            label = { Text("Imou Camera Safety Code / Password (Local RTSP)", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = accountEmail,
                            onValueChange = { accountEmail = it },
                            label = { Text("Camera Local IP (Leave blank to auto-detect on Wi-Fi)", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    BrandOption.TAPO,
                    BrandOption.SONOFF -> {
                        Text(
                            text = "Enter your ${selectedBrand.label} account credentials so Clara can authenticate and discover your devices on local Wi-Fi:",
                            color = GhTextSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = accountEmail,
                            onValueChange = { accountEmail = it },
                            label = { Text("${selectedBrand.label} Email", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = accountPassword,
                            onValueChange = { accountPassword = it },
                            label = { Text("${selectedBrand.label} Password", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    BrandOption.SECURITY_HUB,
                    BrandOption.LG_THINQ -> {
                        Text(
                            text = if (selectedBrand == BrandOption.LG_THINQ) {
                                "Ensure your LG webOS TV is powered on and connected to the same Wi-Fi network, then tap below and press 'Accept' on your TV screen."
                            } else {
                                "Adds the unified Clara Security Center tile (Home, Away, Night & Vacation modes) to your dashboard."
                            },
                            color = GhTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    else -> {
                        AnimatedVisibility(visible = isTabletOn5Ghz && pairingMode == TuyaPairingMode.AUTO_COMBO_EZ) {
                            Surface(
                                color = GhTileWarmActive.copy(alpha = 0.75f),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, GhAccentWarm),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.BluetoothSearching,
                                        contentDescription = null,
                                        tint = GhAccentWarm,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "5GHz Tablet Wi-Fi Detected ($currentWifiFreqMhz MHz)",
                                            color = GhAccentWarm,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Clara automatically prioritizes Bluetooth LE + SoftAP bridging so 2.4GHz devices connect without switching your tablet's Wi-Fi.",
                                            color = GhTextPrimary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }

                        WifiSsidSelectorDropdown(
                            selectedSsid = wifiSsid,
                            onSsidSelected = { wifiSsid = it },
                            availableNetworks = availableWifi,
                            isScanning = isScanningWifi,
                            onRefreshNetworks = {
                                scope.launch { TuyaDirectPairingManager.scanAvailableWifiNetworks(context) }
                            },
                            textStyle = compactInputStyle,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = wifiPassword,
                            onValueChange = { wifiPassword = it },
                            label = { Text("Home 2.4GHz Wi-Fi Password", fontSize = 11.sp) },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            visualTransformation = if (showWifiPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showWifiPassword = !showWifiPassword }) {
                                    Icon(
                                        imageVector = if (showWifiPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = null
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                CleanDropdownSelector(
                    label = "Assign Room Location",
                    selectedText = targetRoom,
                    options = savedBridge.customRooms.ifEmpty { DEFAULT_ROOMS },
                    optionLabel = { it },
                    onOptionSelected = { targetRoom = it },
                    textStyle = compactInputStyle,
                    modifier = Modifier.fillMaxWidth()
                )

                Surface(
                    color = GhTileSurfaceElevated.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (isPairingTuya || isBusy) {
                            CircularProgressIndicator(color = GhAccentGreen)
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Radar,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Text(
                            text = when {
                                isPairingTuya -> pairingStatusText.ifBlank { "Broadcasting 2.4GHz Wi-Fi + BLE + SoftAP credentials to wireless device..." }
                                statusNote.isNotBlank() -> statusNote
                                else -> "Put your ${selectedBrand.label} into setup mode or enter your account details above, then tap below."
                            },
                            color = GhTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        LivePairingConnectionTimeline()

                        Button(
                            onClick = {
                                when (selectedBrand) {
                                    BrandOption.JAGUAR -> {
                                        isBusy = true
                                        statusNote = "Signing in to Jaguar InControl & fetching real vehicle telemetry..."
                                        scope.launch {
                                            val res = VehicleIntegrationClient.loginAndDiscoverJaguarVehicles(
                                                context = context,
                                                bridge = DeviceRepository.loadBridge(context),
                                                email = accountEmail,
                                                password = accountPassword,
                                                pin = vehiclePin,
                                                vinOverride = DeviceRepository.sanitizeRealVin(vehicleVin)
                                            )
                                            isBusy = false
                                            if (res.isSuccess) {
                                                val found = res.getOrThrow().map { it.copy(roomOrSubtitle = targetRoom) }
                                                UniversalOnboardingEngine.onboardCompatibilityTiles(
                                                    context = context,
                                                    candidateTiles = found,
                                                    tiles = tiles,
                                                    sourceLabel = "Jaguar InControl Wizard"
                                                )
                                                JarvisLiveBridge.recordOnboardingOutcome(succeeded = true, userDecisionCount = 1)
                                                onStatusBanner("SUCCESS: Linked Jaguar InControl (${found.first().title})!")
                                                onDismiss()
                                            } else {
                                                statusNote = res.exceptionOrNull()?.message ?: "Could not link Jaguar."
                                            }
                                        }
                                    }
                                    BrandOption.MERCEDES -> {
                                        isBusy = true
                                        statusNote = "Signing in to Mercedes me & fetching real vehicle telemetry..."
                                        scope.launch {
                                            val res = VehicleIntegrationClient.loginAndDiscoverMercedesVehicles(
                                                context = context,
                                                bridge = DeviceRepository.loadBridge(context),
                                                email = accountEmail,
                                                password = accountPassword,
                                                pin = vehiclePin,
                                                vinOverride = DeviceRepository.sanitizeRealVin(vehicleVin)
                                            )
                                            isBusy = false
                                            if (res.isSuccess) {
                                                val found = res.getOrThrow().map { it.copy(roomOrSubtitle = targetRoom) }
                                                UniversalOnboardingEngine.onboardCompatibilityTiles(
                                                    context = context,
                                                    candidateTiles = found,
                                                    tiles = tiles,
                                                    sourceLabel = "Mercedes me Wizard"
                                                )
                                                JarvisLiveBridge.recordOnboardingOutcome(succeeded = true, userDecisionCount = 1)
                                                onStatusBanner("SUCCESS: Linked Mercedes me (${found.first().title})!")
                                                onDismiss()
                                            } else {
                                                statusNote = res.exceptionOrNull()?.message ?: "Could not link Mercedes-Benz."
                                            }
                                        }
                                    }
                                    BrandOption.GOVEE -> {
                                        isBusy = true
                                        statusNote = "Scanning local UDP ports 4001-4003 & Govee Cloud API for Govee lights..."
                                        scope.launch {
                                            val cleanKey = goveeApiKey.trim()
                                            val cleanIp = goveeIp.trim()
                                            val updatedBridge = DeviceRepository.loadBridge(context).copy(
                                                goveeApiKey = cleanKey,
                                                goveeIpAddress = cleanIp
                                            )
                                            DeviceRepository.saveBridge(context, updatedBridge)
                                            val discovered = SmartHomeApiClient.discoverGoveeDevices(updatedBridge, timeoutMs = 1500L)
                                            isBusy = false
                                            if (discovered.isNotEmpty()) {
                                                val candidateTiles = discovered.map { dev ->
                                                    val devId = dev.hardwareIdOrMac.ifBlank { dev.ipAddress }
                                                    SmartTile(
                                                        title = dev.suggestedName,
                                                        roomOrSubtitle = targetRoom,
                                                        type = TileType.GOVEE_LIGHT,
                                                        colSpan = 1,
                                                        isVisible = true,
                                                        isOnline = true,
                                                        isActive = true,
                                                        ipAddress = dev.ipAddress,
                                                        serialNumber = devId,
                                                        entityId = devId,
                                                        secretCode = cleanKey,
                                                        companionAppPackage = "",
                                                        deviceClassOverride = DeviceClass.LIGHT,
                                                        activeProviderId = if (dev.ipAddress.isNotBlank()) "GOVEE_UDP_4003" else "GOVEE_CLOUD"
                                                    )
                                                }
                                                UniversalOnboardingEngine.onboardCompatibilityTiles(
                                                    context = context,
                                                    candidateTiles = candidateTiles,
                                                    tiles = tiles,
                                                    sourceLabel = "Govee Wizard"
                                                )
                                                JarvisLiveBridge.recordOnboardingOutcome(succeeded = true, userDecisionCount = 1)
                                                onStatusBanner("SUCCESS: Added ${discovered.size} Govee light(s) with 360\u00B0 RGB Wheel!")
                                                onDismiss()
                                            } else if (cleanIp.isNotBlank()) {
                                                val manualBase = SmartTile(
                                                    title = "Govee Ambient Light",
                                                    roomOrSubtitle = targetRoom,
                                                    type = TileType.GOVEE_LIGHT,
                                                    colSpan = 1,
                                                    isVisible = true,
                                                    isOnline = false,
                                                    isActive = false,
                                                    ipAddress = cleanIp,
                                                    serialNumber = cleanIp,
                                                    entityId = cleanIp,
                                                    secretCode = cleanKey,
                                                    companionAppPackage = "",
                                                    deviceClassOverride = DeviceClass.LIGHT,
                                                    activeProviderId = "GOVEE_UDP_4003"
                                                )
                                                val reachable = UniversalOnboardingEngine.verifyCandidateReachability(
                                                    context = context,
                                                    bridgeSettings = updatedBridge,
                                                    candidateTile = manualBase
                                                )
                                                UniversalOnboardingEngine.onboardCompatibilityTile(
                                                    context = context,
                                                    candidateTile = manualBase.copy(isOnline = reachable, isActive = reachable),
                                                    tiles = tiles,
                                                    sourceLabel = "Govee Manual IP"
                                                )
                                                onStatusBanner("SUCCESS: Linked Govee light at $cleanIp!")
                                                onDismiss()
                                            } else {
                                                statusNote = "No Govee devices responded on UDP 4001-4002 yet. Enable 'LAN Control' in the Govee Home app or enter your Govee API Key / Local IP above."
                                            }
                                        }
                                    }
                                    BrandOption.TWINKLY -> {
                                        isBusy = true
                                        statusNote = "Scanning local Wi-Fi (UDP 5555 & /xled/v1/gestalt) for Twinkly lights..."
                                        scope.launch {
                                            val cleanIp = twinklyIp.trim()
                                            val curBridge = DeviceRepository.loadBridge(context)
                                            val updatedBridge = curBridge.copy(twinklyIpAddress = cleanIp)
                                            DeviceRepository.saveBridge(context, updatedBridge)
                                            val discovered = buildList {
                                                if (cleanIp.isNotBlank()) {
                                                    SmartHomeApiClient.probeTwinklyGestaltOnPort80(cleanIp)?.let { add(it) }
                                                }
                                                addAll(SmartHomeApiClient.discoverTwinklyDevices(context, timeoutMs = 1500L))
                                            }.distinctBy { it.ipAddress }
                                            isBusy = false
                                            if (discovered.isNotEmpty()) {
                                                val candidateTiles = discovered.map { dev ->
                                                    val devId = dev.hardwareIdOrMac.ifBlank { dev.ipAddress }
                                                    SmartTile(
                                                        title = dev.suggestedName,
                                                        roomOrSubtitle = targetRoom,
                                                        type = TileType.TWINKLY_LIGHT,
                                                        colSpan = 1,
                                                        isVisible = true,
                                                        isOnline = true,
                                                        isActive = true,
                                                        ipAddress = dev.ipAddress,
                                                        serialNumber = devId,
                                                        entityId = devId,
                                                        companionAppPackage = "",
                                                        deviceClassOverride = DeviceClass.LIGHT,
                                                        activeProviderId = "TWINKLY_XLED"
                                                    )
                                                }
                                                UniversalOnboardingEngine.onboardCompatibilityTiles(
                                                    context = context,
                                                    candidateTiles = candidateTiles,
                                                    tiles = tiles,
                                                    sourceLabel = "Twinkly Wizard"
                                                )
                                                JarvisLiveBridge.recordOnboardingOutcome(succeeded = true, userDecisionCount = 1)
                                                onStatusBanner("SUCCESS: Added ${discovered.size} Twinkly light(s) with 360\u00B0 RGB Wheel!")
                                                onDismiss()
                                            } else if (cleanIp.isNotBlank()) {
                                                val manualBase = SmartTile(
                                                    title = "Twinkly LED Lights",
                                                    roomOrSubtitle = targetRoom,
                                                    type = TileType.TWINKLY_LIGHT,
                                                    colSpan = 1,
                                                    isVisible = true,
                                                    isOnline = false,
                                                    isActive = false,
                                                    ipAddress = cleanIp,
                                                    serialNumber = cleanIp,
                                                    entityId = cleanIp,
                                                    companionAppPackage = "",
                                                    deviceClassOverride = DeviceClass.LIGHT,
                                                    activeProviderId = "TWINKLY_XLED"
                                                )
                                                val reachable = UniversalOnboardingEngine.verifyCandidateReachability(
                                                    context = context,
                                                    bridgeSettings = updatedBridge,
                                                    candidateTile = manualBase
                                                )
                                                UniversalOnboardingEngine.onboardCompatibilityTile(
                                                    context = context,
                                                    candidateTile = manualBase.copy(isOnline = reachable, isActive = reachable),
                                                    tiles = tiles,
                                                    sourceLabel = "Twinkly Manual IP"
                                                )
                                                onStatusBanner("SUCCESS: Linked Twinkly lights at $cleanIp!")
                                                onDismiss()
                                            } else {
                                                statusNote = "No Twinkly lights found on Wi-Fi yet. Ensure your Twinkly controller is powered on or enter its Local IP above."
                                            }
                                        }
                                    }
                                    BrandOption.LYTMI -> {
                                        val cleanIp = lytmiIp.trim()
                                        val cleanId = lytmiDeviceId.trim()
                                        val cleanKey = lytmiLocalKey.trim()
                                        if (cleanIp.isNotBlank() || cleanId.isNotBlank()) {
                                            val curBridge = DeviceRepository.loadBridge(context)
                                            DeviceRepository.saveBridge(
                                                context,
                                                curBridge.copy(
                                                    lytmiIpAddress = cleanIp,
                                                    lytmiDeviceId = cleanId,
                                                    lytmiLocalKey = cleanKey
                                                )
                                            )
                                            val resolvedId = cleanId.ifBlank { cleanIp }
                                            if (cleanIp.isNotBlank()) {
                                                TuyaApiClient.registerDeviceLanAddress(resolvedId, cleanIp, cleanKey)
                                            }
                                            val lytmiCaps = TuyaApiClient.defaultLytmiCapabilities()
                                            val lytmiTile = SmartTile(
                                                title = "Lytmi TV Sync Box",
                                                roomOrSubtitle = targetRoom,
                                                type = TileType.LYTMI_SYNC_BOX,
                                                colSpan = 1,
                                                isVisible = true,
                                                isOnline = true,
                                                isActive = true,
                                                ipAddress = cleanIp,
                                                serialNumber = resolvedId,
                                                entityId = resolvedId,
                                                secretCode = cleanKey,
                                                capabilities = lytmiCaps,
                                                capabilitySchemaHash = CapabilitySchemaHasher.computeHash(lytmiCaps),
                                                deviceClassOverride = DeviceClass.MEDIA,
                                                deviceSubtypeOverride = DeviceSubtype.AMBIENT_SYNC,
                                                activeProviderId = if (cleanIp.isNotBlank()) "TUYA_LAN_6668" else "TUYA_CLOUD"
                                            )
                                            UniversalOnboardingEngine.onboardCompatibilityTile(
                                                context = context,
                                                candidateTile = lytmiTile,
                                                tiles = tiles,
                                                sourceLabel = "Lytmi Wizard"
                                            )
                                            onStatusBanner("SUCCESS: Linked Lytmi HDMI 2.1 Sync Box to dashboard!")
                                            onDismiss()
                                        } else {
                                            if (isPairingTuya) {
                                                TuyaDirectPairingManager.stopDirectPairing()
                                            } else {
                                                startTuyaWifiAndBleBroadcast(
                                                    defaultTitle = "Lytmi TV Sync Box",
                                                    forcedType = TileType.LYTMI_SYNC_BOX
                                                )
                                            }
                                        }
                                    }
                                    BrandOption.PRO_BREEZE -> {
                                        if (isPairingTuya) {
                                            TuyaDirectPairingManager.stopDirectPairing()
                                        } else {
                                            startTuyaWifiAndBleBroadcast(
                                                defaultTitle = "Pro Breeze Tower Fan",
                                                forcedType = TileType.PROBREEZE_FAN
                                            )
                                        }
                                    }
                                    BrandOption.PLEX -> {
                                        val curBridge = DeviceRepository.loadBridge(context)
                                        DeviceRepository.saveBridge(
                                            context,
                                            curBridge.copy(
                                                plexServerUrl = plexServerUrl.trim(),
                                                plexToken = plexToken.trim()
                                            )
                                        )
                                        onStatusBanner("SUCCESS: Linked Plex Media Server (${plexServerUrl.trim()})!")
                                        onDismiss()
                                    }
                                    BrandOption.SECURITY_HUB -> {
                                        val secTile = SmartTile(
                                            title = "Security Center",
                                            roomOrSubtitle = targetRoom,
                                            type = TileType.SECURITY_CENTER,
                                            colSpan = 1,
                                            isVisible = true,
                                            isActive = true,
                                            securityMode = savedBridge.securityCenterMode,
                                            deviceClassOverride = DeviceClass.SECURITY,
                                            lifecycleState = DeviceLifecycleState.ONLINE
                                        )
                                        UniversalOnboardingEngine.onboardCompatibilityTile(
                                            context = context,
                                            candidateTile = secTile,
                                            tiles = tiles,
                                            sourceLabel = "Security Center Setup"
                                        )
                                        onStatusBanner("SUCCESS: Added Clara Security Center to dashboard!")
                                        onDismiss()
                                    }
                                    BrandOption.MATTER -> {
                                        isBusy = true
                                        statusNote = "Commissioning Matter device..."
                                        scope.launch {
                                            val res = UniversalOnboardingEngine.onboardByUniversalShortCode(
                                                context = context,
                                                rawCode = matterCode,
                                                customTitle = "Matter Device",
                                                targetRoom = targetRoom,
                                                tiles = tiles,
                                                bridgeSettings = DeviceRepository.loadBridge(context),
                                                onStatusUpdate = { msg -> statusNote = msg }
                                            )
                                            isBusy = false
                                            if (res.isSuccess) {
                                                val created = res.getOrThrow()
                                                onStatusBanner("SUCCESS: Paired Matter device '${created.title}'!")
                                                onDismiss()
                                            } else {
                                                statusNote = res.exceptionOrNull()?.message ?: "Matter pairing failed."
                                            }
                                        }
                                    }
                                    BrandOption.NEST -> {
                                        val cleanNestCode = nestSetupCode.trim()
                                        if (cleanNestCode.isNotBlank()) {
                                            isBusy = true
                                            statusNote = "Pairing Nest Thermostat with code '$cleanNestCode'..."
                                            scope.launch {
                                                val res = UniversalOnboardingEngine.onboardByUniversalShortCode(
                                                    context = context,
                                                    rawCode = cleanNestCode,
                                                    customTitle = "$targetRoom Nest Thermostat".trim(),
                                                    targetRoom = targetRoom,
                                                    tiles = tiles,
                                                    bridgeSettings = DeviceRepository.loadBridge(context),
                                                    onStatusUpdate = { msg -> statusNote = msg }
                                                )
                                                isBusy = false
                                                if (res.isSuccess) {
                                                    val created = res.getOrThrow()
                                                    onStatusBanner("SUCCESS: Paired '${created.title}' directly to Clara!")
                                                    onDismiss()
                                                } else {
                                                    val errMsg = res.exceptionOrNull()?.message.orEmpty()
                                                    if (errMsg.startsWith("NEEDS_NEST_SIGN_IN", ignoreCase = true)) {
                                                        showNestWebModalInWizard = true
                                                    } else {
                                                        statusNote = errMsg.ifBlank { "Could not pair Nest Thermostat via code." }
                                                    }
                                                }
                                            }
                                        } else {
                                            showNestWebModalInWizard = true
                                        }
                                    }
                                    BrandOption.TAPO -> {
                                        isBusy = true
                                        statusNote = "Signing in to TP-Link & discovering Tapo / Kasa devices..."
                                        TpLinkTapoKasaClient.saveTpLinkCredentials(context, accountEmail, accountPassword)
                                        scope.launch {
                                            val res = TpLinkTapoKasaClient.loginAndDiscoverTpLinkDevices(context, accountEmail, accountPassword)
                                            isBusy = false
                                            if (res.isSuccess) {
                                                val found = res.getOrThrow()
                                                val candidateTiles = found.map { tk ->
                                                    val hasPower = tk.powerWatts > 0f || tk.totalEnergyKwh > 0f
                                                    SmartTile(
                                                        title = tk.name,
                                                        roomOrSubtitle = targetRoom,
                                                        type = tk.suggestedType,
                                                        isVisible = true,
                                                        isOnline = true,
                                                        isActive = tk.isActive,
                                                        isSocket2Active = tk.isSocket2Active,
                                                        multiGangSocketCount = tk.multiGangCount.coerceAtLeast(1),
                                                        socket1Name = tk.socket1Name.ifBlank { "Socket 1" },
                                                        socket2Name = tk.socket2Name.ifBlank { "Socket 2" },
                                                        ipAddress = tk.ipAddress,
                                                        serialNumber = tk.deviceId,
                                                        entityId = tk.deviceId,
                                                        powerWatts = tk.powerWatts,
                                                        voltageVolts = tk.voltageVolts,
                                                        currentMilliamps = tk.currentMilliamps,
                                                        totalEnergyKwh = tk.totalEnergyKwh,
                                                        hasExplicitPowerTelemetry = hasPower,
                                                        deviceClassOverride = tk.suggestedType.toSemanticDeviceClass(hasPower),
                                                        activeProviderId = if (tk.isKasaProtocol) "KASA_LAN_9999" else "TAPO_KLAP_LAN"
                                                    )
                                                }
                                                UniversalOnboardingEngine.onboardCompatibilityTiles(
                                                    context = context,
                                                    candidateTiles = candidateTiles,
                                                    tiles = tiles,
                                                    sourceLabel = "TP-Link Tapo/Kasa Wizard"
                                                )
                                                JarvisLiveBridge.recordOnboardingOutcome(succeeded = true, userDecisionCount = 1)
                                                onStatusBanner("SUCCESS: Imported ${found.size} TP-Link device(s)!")
                                                onDismiss()
                                            } else {
                                                statusNote = res.exceptionOrNull()?.message ?: "TP-Link sign-in failed."
                                            }
                                        }
                                    }
                                    BrandOption.SONOFF -> {
                                        isBusy = true
                                        statusNote = "Signing in to eWeLink & discovering Sonoff devices..."
                                        EWeLinkSonoffClient.saveEWeLinkCredentials(context, accountEmail, accountPassword)
                                        scope.launch {
                                            val res = EWeLinkSonoffClient.loginAndDiscoverEWeLinkDevices(context, accountEmail, accountPassword)
                                            isBusy = false
                                            if (res.isSuccess) {
                                                val found = res.getOrThrow()
                                                val candidateTiles = found.map { sn ->
                                                    val hasPower = sn.powerWatts > 0f
                                                    SmartTile(
                                                        title = sn.name,
                                                        roomOrSubtitle = targetRoom,
                                                        type = sn.suggestedType,
                                                        isVisible = true,
                                                        isOnline = true,
                                                        isActive = sn.isActive,
                                                        isSocket2Active = sn.isSocket2Active,
                                                        multiGangSocketCount = sn.multiGangCount.coerceAtLeast(1),
                                                        ipAddress = if (sn.ipAddress.isNotBlank()) "${sn.ipAddress}:${sn.port}" else "",
                                                        serialNumber = sn.deviceId,
                                                        entityId = sn.deviceId,
                                                        secretCode = sn.deviceKey,
                                                        powerWatts = sn.powerWatts,
                                                        voltageVolts = sn.voltageVolts,
                                                        currentMilliamps = sn.currentMilliamps,
                                                        hasExplicitPowerTelemetry = hasPower,
                                                        deviceClassOverride = sn.suggestedType.toSemanticDeviceClass(hasPower),
                                                        activeProviderId = "SONOFF_LAN_8081"
                                                    )
                                                }
                                                UniversalOnboardingEngine.onboardCompatibilityTiles(
                                                    context = context,
                                                    candidateTiles = candidateTiles,
                                                    tiles = tiles,
                                                    sourceLabel = "Sonoff eWeLink Wizard"
                                                )
                                                JarvisLiveBridge.recordOnboardingOutcome(succeeded = true, userDecisionCount = 1)
                                                onStatusBanner("SUCCESS: Imported ${found.size} Sonoff device(s)!")
                                                onDismiss()
                                            } else {
                                                statusNote = res.exceptionOrNull()?.message ?: "eWeLink sign-in failed."
                                            }
                                        }
                                    }
                                    BrandOption.IMOU -> {
                                        isBusy = true
                                        statusNote = "Locating Imou Camera on Wi-Fi & linking RTSP stream..."
                                        scope.launch {
                                            val cleanSafetyCode = accountPassword.trim()
                                            val scannedIps = EzvizLanWakeScanner.findLocalImouRtspIps(context, accountEmail.trim())
                                            val resolvedIp = accountEmail.trim().ifBlank { scannedIps.firstOrNull().orEmpty() }
                                            val camTile = SmartTile(
                                                title = if (targetRoom.isNotBlank()) "$targetRoom Imou Camera" else "Imou Camera",
                                                roomOrSubtitle = targetRoom,
                                                type = TileType.IMOU_CAMERA,
                                                colSpan = 2,
                                                isVisible = true,
                                                isOnline = resolvedIp.isNotBlank(),
                                                isLiveFeedActive = true,
                                                ipAddress = resolvedIp,
                                                secretCode = cleanSafetyCode,
                                                channelId = "1",
                                                companionAppPackage = "",
                                                deviceClassOverride = DeviceClass.CAMERA,
                                                activeProviderId = if (resolvedIp.isNotBlank()) "RTSP_ONVIF_LAN" else "IMOU_RTSP_CLOUD"
                                            )
                                            UniversalOnboardingEngine.onboardCompatibilityTile(
                                                context = context,
                                                candidateTile = camTile,
                                                tiles = tiles,
                                                sourceLabel = "Imou Camera Wizard"
                                            )
                                            JarvisLiveBridge.recordOnboardingOutcome(succeeded = true, userDecisionCount = 1)
                                            isBusy = false
                                            onStatusBanner("SUCCESS: Added '${camTile.title}' to your dashboard!")
                                            onDismiss()
                                        }
                                    }
                                    BrandOption.LG_THINQ -> {
                                        isBusy = true
                                        statusNote = "Searching network for your LG Smart TV \u2014 press 'Accept' on your TV screen when prompted..."
                                        scope.launch {
                                            val allLan = SmartHomeApiClient.discoverLanNetworkPass(context)
                                            val foundTv = allLan.firstOrNull { it.suggestedType == TileType.LG_TV }
                                            val tvIp = foundTv?.ipAddress?.substringBefore(":").orEmpty()
                                            if (tvIp.isNotBlank()) {
                                                val res = LgThinQWebOsClient.pairAndFetchTvDetails(context, tvIp)
                                                isBusy = false
                                                if (res.isSuccess) {
                                                    val mac = res.getOrThrow().first
                                                    UniversalOnboardingEngine.onboardCompatibilityTile(
                                                        context = context,
                                                        candidateTile = SmartTile(
                                                            title = foundTv?.suggestedName?.ifBlank { "LG Smart TV" } ?: "LG Smart TV",
                                                            roomOrSubtitle = targetRoom,
                                                            type = TileType.LG_TV,
                                                            isVisible = true,
                                                            isOnline = true,
                                                            isActive = true,
                                                            ipAddress = tvIp,
                                                            macAddress = CrossEcosystemIdentityCorrelator.normalizeMac(mac),
                                                            serialNumber = mac,
                                                            companionAppPackage = "",
                                                            deviceClassOverride = DeviceClass.MEDIA,
                                                            deviceSubtypeOverride = DeviceSubtype.TV,
                                                            activeProviderId = ProviderId.LG_WEBOS.id
                                                        ),
                                                        tiles = tiles,
                                                        sourceLabel = "LG webOS TV Wizard"
                                                    )
                                                    JarvisLiveBridge.recordOnboardingOutcome(succeeded = true, userDecisionCount = 1)
                                                    onStatusBanner("SUCCESS: Paired LG Smart TV ($tvIp)!")
                                                    onDismiss()
                                                } else {
                                                    JarvisLiveBridge.recordOnboardingOutcome(succeeded = false, userDecisionCount = 1)
                                                    statusNote = res.exceptionOrNull()?.message
                                                        ?: "Please press 'Accept' on your LG TV screen using your TV remote."
                                                }
                                            } else {
                                                isBusy = false
                                                statusNote = "We couldn't find your LG TV yet. Ensure the TV is switched on and connected to your home network."
                                            }
                                        }
                                    }
                                    else -> {
                                        if (isPairingTuya) {
                                            TuyaDirectPairingManager.stopDirectPairing()
                                        } else {
                                            startTuyaWifiAndBleBroadcast(
                                                defaultTitle = selectedBrand.defaultTileTitle
                                            )
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPairingTuya) GhAccentRed else GhAccentGreen,
                                contentColor = Color(0xFF0F1114)
                            )
                        ) {
                            Text(
                                text = if (isPairingTuya) "Stop Pairing Broadcast" else "Start Pairing / Link Now",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNestWebModalInWizard) {
        NestWebLoginModal(
            initialEntryKey = nestSetupCode,
            onSessionCaptured = {
                showNestWebModalInWizard = false
                isBusy = true
                statusNote = "Google Nest connected! Importing your thermostats..."
                val curBridge = DeviceRepository.loadBridge(context)
                val preservedToken = ClaraSecretVault.getNestSdmRefreshToken(context)
                    ?: curBridge.nestRefreshToken.takeIf { it.isNotBlank() && it != "nest_web_session_active" }
                        .orEmpty()
                val updatedBridge = curBridge.copy(nestRefreshToken = preservedToken)
                DeviceRepository.saveBridge(context, updatedBridge)
                PlayerBackgroundScope.launch {
                    val discoverRes = NestSdmApiClient.discoverNestDevices(updatedBridge)
                    withContext(Dispatchers.Main) {
                        isBusy = false
                        if (discoverRes.isSuccess) {
                            val found = discoverRes.getOrThrow()
                            val newTiles = found.map { nd ->
                                SmartTile(
                                    title = nd.displayName,
                                    roomOrSubtitle = nd.roomName.ifBlank { targetRoom },
                                    type = nd.suggestedType,
                                    colSpan = if (nd.suggestedType.isCameraType()) 2 else 1,
                                    isVisible = true,
                                    isOnline = nd.isOnline,
                                    isActive = nd.hvacMode.equals("HEAT", ignoreCase = true),
                                    temperature = nd.ambientTempC,
                                    targetTemperature = nd.targetTempC,
                                    humidity = nd.humidityPct,
                                    hvacMode = nd.hvacMode,
                                    hvacStatus = nd.hvacStatus,
                                    entityId = nd.deviceName,
                                    serialNumber = nd.deviceName,
                                    companionAppPackage = "",
                                    deviceClassOverride = nd.suggestedType.toSemanticDeviceClass(),
                                    lifecycleState = if (nd.isOnline) DeviceLifecycleState.ONLINE else DeviceLifecycleState.OFFLINE,
                                    stateAuthorityTier = StateAuthorityTier.CLOUD_SHADOW_STATE,
                                    activeProviderId = ProviderId.GOOGLE_NEST.id
                                )
                            }
                            val count = UniversalOnboardingEngine.onboardLegacyCandidateTiles(
                                context = context,
                                candidateTiles = newTiles,
                                tiles = tiles,
                                bridgeSettings = updatedBridge,
                                scope = scope,
                                sourceLabel = "Google Nest Wizard"
                            )
                            JarvisLiveBridge.recordOnboardingOutcome(succeeded = true, userDecisionCount = 1)
                            onStatusBanner("SUCCESS: Added $count Google Nest Thermostat(s)!")
                            onDismiss()
                        } else {
                            statusNote = "Wait for your thermostats to load on the Nest screen, then tap 'Done & Import to Clara'."
                        }
                    }
                }
            },
            onDismiss = { showNestWebModalInWizard = false }
        )
    }
}

// ---------------------------------------------------------------------------
// OUTCOME-BASED GUIDED DEVICE & ACCOUNT SETUP (Kept for Full Compatibility)
// ---------------------------------------------------------------------------
@Composable
fun ManualBrandSetupTab(
    context: Context,
    tiles: MutableList<SmartTile>,
    currentTempBridge: BridgeSettings,
    haUrl: String,
    onHaUrlChange: (String) -> Unit = {},
    haToken: String,
    onHaTokenChange: (String) -> Unit = {},
    imouAppId: String,
    onImouAppIdChange: (String) -> Unit,
    imouAppSecret: String,
    onImouAppSecretChange: (String) -> Unit,
    ezvizAppKey: String,
    onEzvizAppKeyChange: (String) -> Unit,
    ezvizAppSecret: String,
    onEzvizAppSecretChange: (String) -> Unit,
    nestRefreshToken: String,
    isSigningIntoNest: Boolean,
    onLaunchOneTapNestLogin: () -> Unit,
    selectedBrand: BrandOption?,
    onSelectedBrandChange: (BrandOption) -> Unit,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    deviceRoom: String,
    onDeviceRoomChange: (String) -> Unit,
    deviceSubType: TileType,
    onDeviceSubTypeChange: (TileType) -> Unit,
    deviceIp: String,
    onDeviceIpChange: (String) -> Unit,
    deviceCode: String,
    onDeviceCodeChange: (String) -> Unit,
    deviceSn: String,
    onDeviceSnChange: (String) -> Unit,
    deviceChannelId: String,
    onDeviceChannelIdChange: (String) -> Unit,
    deviceEntityId: String,
    onDeviceEntityIdChange: (String) -> Unit,
    deviceRtspUrl: String,
    onDeviceRtspUrlChange: (String) -> Unit,
    showAdvancedOptions: Boolean,
    onToggleAdvancedOptions: () -> Unit,
    compactInputTextStyle: TextStyle,
    onJumpToDirectPairing: (String) -> Unit,
    onNavigateToConnectedDevices: () -> Unit,
    onStatusBanner: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var activeGuidedWizardBrand by remember { mutableStateOf<BrandOption?>(null) }

    val featuredBrands = remember {
        listOf(
            BrandOption.NEST,
            BrandOption.PRO_BREEZE,
            BrandOption.JAGUAR,
            BrandOption.MERCEDES,
            BrandOption.LYTMI,
            BrandOption.GOVEE,
            BrandOption.TWINKLY,
            BrandOption.PLEX
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompactMobile = maxWidth < 600.dp

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Surface(
                    color = GhTileSurface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, GhTileSurfaceElevated),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Featured Integrations: Google Nest, Pro Breeze Fans, Vehicles, RGB & Media",
                            color = GhAccentGreen,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(featuredBrands, key = { it.name }) { brandOpt ->
                                val cardAccent = when (brandOpt) {
                                    BrandOption.NEST -> NestHeatingOrange
                                    BrandOption.PRO_BREEZE -> Color(0xFF00E5FF)
                                    BrandOption.JAGUAR -> Color(0xFF00E5A3)
                                    BrandOption.MERCEDES -> Color(0xFF00C6FF)
                                    BrandOption.LYTMI -> Color(0xFFE040FB)
                                    BrandOption.GOVEE -> Color(0xFF00E5FF)
                                    BrandOption.TWINKLY -> Color(0xFFFF4081)
                                    else -> Color(0xFFFFB300)
                                }
                                Surface(
                                    color = GhTileSurfaceElevated,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, cardAccent.copy(alpha = 0.55f)),
                                    modifier = Modifier
                                        .width(245.dp)
                                        .clickable {
                                            onSelectedBrandChange(brandOpt)
                                            activeGuidedWizardBrand = brandOpt
                                        }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = iconForTileType(brandOpt.defaultTileType),
                                                contentDescription = null,
                                                tint = cardAccent,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = brandOpt.defaultTileTitle,
                                                color = GhTextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            text = brandOpt.description,
                                            color = GhTextSecondary,
                                            fontSize = 10.5.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Tap to Link / Pair \u2192",
                                            color = cardAccent,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold
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
                    color = GhTileSurface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, GhTileSurfaceElevated),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "What Would You Like to Connect?",
                                color = GhTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            )
                            FilledTonalButton(
                                onClick = {
                                    activeGuidedWizardBrand = selectedBrand ?: BrandOption.SMART_LIFE
                                },
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = GhAccentGreen.copy(alpha = 0.2f),
                                    contentColor = GhAccentGreen
                                )
                            ) {
                                Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Launch Guided Pairing Wizard", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        CleanDropdownSelector(
                            label = "Choose Device Goal or Capability",
                            selectedText = selectedBrand?.label ?: "Select a device...",
                            options = BrandOption.entries,
                            optionLabel = { it.label },
                            optionSubtitle = { it.description },
                            onOptionSelected = { brand ->
                                onSelectedBrandChange(brand)
                            },
                            textStyle = compactInputTextStyle,
                            modifier = Modifier.fillMaxWidth()
                        )

                        val brand = selectedBrand
                        if (brand != null) {
                            HorizontalDivider(color = GhTileSurfaceElevated)

                            ResponsiveSplitRow(
                                isStacked = isCompactMobile,
                                firstContent = { mod ->
                                    OutlinedTextField(
                                        value = deviceName,
                                        onValueChange = onDeviceNameChange,
                                        label = { Text("Display Name on Dashboard", fontSize = 11.sp) },
                                        textStyle = compactInputTextStyle,
                                        shape = RoundedCornerShape(14.dp),
                                        colors = claraFieldColors(),
                                        modifier = mod,
                                        singleLine = true
                                    )
                                },
                                secondContent = { mod ->
                                    CleanDropdownSelector(
                                        label = "Room",
                                        selectedText = deviceRoom,
                                        options = currentTempBridge.customRooms.ifEmpty { DEFAULT_ROOMS },
                                        optionLabel = { it },
                                        onOptionSelected = onDeviceRoomChange,
                                        textStyle = compactInputTextStyle,
                                        modifier = mod
                                    )
                                }
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleAdvancedOptions() },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (showAdvancedOptions) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = null,
                                    tint = GhTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (showAdvancedOptions) "Hide Advanced Technical Settings" else "Advanced Technical Settings (Optional IP / VIN / Entity / PIN)",
                                    color = GhTextSecondary,
                                    fontSize = 11.sp
                                )
                            }

                            AnimatedVisibility(visible = showAdvancedOptions) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstContent = { mod ->
                                            OutlinedTextField(
                                                value = deviceIp,
                                                onValueChange = onDeviceIpChange,
                                                label = { Text("Local IP Address (Optional)", fontSize = 11.sp) },
                                                textStyle = compactInputTextStyle,
                                                shape = RoundedCornerShape(14.dp),
                                                colors = claraFieldColors(),
                                                modifier = mod,
                                                singleLine = true
                                            )
                                        },
                                        secondContent = { mod ->
                                            OutlinedTextField(
                                                value = deviceSn.ifBlank { deviceEntityId },
                                                onValueChange = {
                                                    onDeviceSnChange(it)
                                                    onDeviceEntityIdChange(it)
                                                },
                                                label = { Text("Device ID / VIN / Serial / MAC (Optional)", fontSize = 11.sp) },
                                                textStyle = compactInputTextStyle,
                                                shape = RoundedCornerShape(14.dp),
                                                colors = claraFieldColors(),
                                                modifier = mod,
                                                singleLine = true
                                            )
                                        }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (brand == BrandOption.NEST) {
                                    FilledTonalButton(
                                        onClick = onLaunchOneTapNestLogin,
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Text(
                                            text = if (isSigningIntoNest) "Signing in..." else if (nestRefreshToken.isNotBlank()) "Sync Google Nest" else "Sign in with Google",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                FilledTonalButton(
                                    onClick = { activeGuidedWizardBrand = brand },
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = GhTileSurfaceElevated,
                                        contentColor = GhAccentGreen
                                    )
                                ) {
                                    Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Start Pairing Wizard", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        val cleanIp = deviceIp.trim()
                                        val cleanSn = deviceSn.trim()
                                        val cleanEntity = deviceEntityId.trim()
                                        val hasExplicitIdentifier = cleanIp.isNotBlank() || cleanSn.isNotBlank() || cleanEntity.isNotBlank() ||
                                                brand in listOf(BrandOption.SECURITY_HUB, BrandOption.SYSTEM, BrandOption.ENERGY_WIDGET, BrandOption.PHOTO_SLIDESHOW)

                                        if (!hasExplicitIdentifier) {
                                            activeGuidedWizardBrand = brand
                                            return@Button
                                        }

                                        val cleanTitle = deviceName.trim().ifBlank { brand.defaultTileTitle }
                                        val isSystemWidgetBrand = brand in listOf(
                                            BrandOption.SECURITY_HUB,
                                            BrandOption.SYSTEM,
                                            BrandOption.ENERGY_WIDGET,
                                            BrandOption.PHOTO_SLIDESHOW
                                        )
                                        scope.launch {
                                            val verifiedOnline = if (isSystemWidgetBrand) {
                                                true
                                            } else {
                                                withContext(Dispatchers.IO) {
                                                    TuyaApiClient.isDeviceReachableOrOnline(
                                                        deviceId = cleanSn.ifBlank { cleanEntity },
                                                        ipAddress = cleanIp,
                                                        reportedCloudOnline = false
                                                    )
                                                }
                                            }
                                            val baseTile = SmartTile(
                                                title = cleanTitle,
                                                roomOrSubtitle = deviceRoom.trim(),
                                                type = deviceSubType,
                                                colSpan = if (deviceSubType.isCameraType() || deviceSubType == TileType.PHOTO_SLIDESHOW) 2 else 1,
                                                rowSpan = if (deviceSubType == TileType.PHOTO_SLIDESHOW) 2 else 1,
                                                isTall = deviceSubType == TileType.PHOTO_SLIDESHOW,
                                                isVisible = true,
                                                isOnline = verifiedOnline,
                                                multiGangSocketCount = 1,
                                                hasExplicitPowerTelemetry = false,
                                                ipAddress = cleanIp,
                                                secretCode = deviceCode.trim(),
                                                serialNumber = cleanSn,
                                                vehicleVin = if (deviceSubType.isVehicleType()) DeviceRepository.sanitizeRealVin(cleanSn) else "",
                                                channelId = deviceChannelId.trim().ifBlank { "1" },
                                                customRtspUrl = deviceRtspUrl.trim(),
                                                entityId = cleanEntity.ifBlank { cleanSn },
                                                companionAppPackage = "",
                                                deviceClassOverride = deviceSubType.toSemanticDeviceClass(),
                                                lifecycleState = if (verifiedOnline) DeviceLifecycleState.ONLINE else DeviceLifecycleState.RECOVERY,
                                                stateAuthorityTier = if (verifiedOnline) StateAuthorityTier.DEVICE_HARDWARE_TRUTH else StateAuthorityTier.CACHED_UI_STATE
                                            )
                                            UniversalOnboardingEngine.onboardLegacyCandidateTiles(
                                                context = context,
                                                candidateTiles = listOf(baseTile),
                                                tiles = tiles,
                                                bridgeSettings = currentTempBridge,
                                                scope = scope,
                                                sourceLabel = "Manual Brand Setup"
                                            )
                                            onStatusBanner(
                                                if (verifiedOnline) "Verified & added '$cleanTitle' to Dashboard!"
                                                else "Added '$cleanTitle' (Awaiting LAN verification)!"
                                            )
                                            onNavigateToConnectedDevices()
                                        }
                                    },
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = GhAccentGreen,
                                        contentColor = Color(0xFF0F1114)
                                    )
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Add to Dashboard", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    activeGuidedWizardBrand?.let { chosenBrand ->
        GuidedDevicePairingWizardModal(
            context = context,
            initialBrand = chosenBrand,
            tiles = tiles,
            onSyncTuyaEcosystemDevices = { onJumpToDirectPairing(deviceRoom) },
            onTriggerFullScan = {},
            onStatusBanner = onStatusBanner,
            onDismiss = { activeGuidedWizardBrand = null }
        )
    }
}

// ---------------------------------------------------------------------------
// DEDICATED "MANAGE DEVICES" SECTION
// - Displays Semantic DeviceClass, DeviceLifecycleState, Active Provider,
//   SHA-256 capabilitySchemaHash, and 1-tap "Clear 30m Hold" button
// - Toggle Single Socket (1 Gang) vs Dual Socket (2 Gang) & Power Meter on any plug
// - Unhide hidden tiles ("Unhide All" banner + "Hidden Only" filter + 1-tap Unhide)
// - "+ Create Group Card" & Group Card assignment dropdown for any tile
// - Custom Socket 1 & Socket 2 Name fields
// - Per-Device Capability & Button Inspector ("Discovered Hardware Controls,
//   Modes & Sensors") with live mode testing, button renaming & Pin to Hero Card
// ---------------------------------------------------------------------------
@Composable
fun ManageConnectedTilesTab(
    context: Context,
    tiles: MutableList<SmartTile>,
    editingTileIndex: Int?,
    onEditingTileIndexChange: (Int?) -> Unit,
    compactInputTextStyle: TextStyle,
    @Suppress("UNUSED_PARAMETER") onEnterInteractiveGridEditMode: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val bridge = remember { DeviceRepository.loadBridge(context) }
    
    var showIgnoredDevicesDialog by remember { mutableStateOf(false) }
    if (showIgnoredDevicesDialog) {
        val ignoredKeys = remember { mutableStateOf(DeviceRepository.getDismissedDeviceKeys(context).toList()) }
        AlertDialog(
            onDismissRequest = { showIgnoredDevicesDialog = false },
            title = { Text("Ignored Devices", fontFamily = GhFont, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
            text = {
                if (ignoredKeys.value.isEmpty()) {
                    Text("No devices are currently ignored.", fontFamily = GhFont)
                } else {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(ignoredKeys.value.size) { idx ->
                            val key = ignoredKeys.value[idx]
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(key, fontFamily = GhFont, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    DeviceRepository.unmarkDeviceDismissed(context, key)
                                    ignoredKeys.value = DeviceRepository.getDismissedDeviceKeys(context).toList()
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIgnoredDevicesDialog = false }) {
                    Text("Close", fontFamily = GhFont)
                }
            }
        )
    }

    if (tiles.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No devices or tiles on your dashboard yet.",
                color = GhTextSecondary
            )
        }
        return
    }

    val activeEditIdx = editingTileIndex?.takeIf { it in tiles.indices }
    var activeCloudPickerProvider by remember { mutableStateOf<SlideshowSourceType?>(null) }
    var showAdvancedTileSettings by remember(activeEditIdx) { mutableStateOf(false) }
    var isSyncingEzvizPinForTile by remember { mutableStateOf(false) }
    var showHiddenOnlyFilter by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showRemoveAllConfirmDialog by remember { mutableStateOf(false) }
    var newGroupTitleDraft by remember { mutableStateOf("Lighting Group") }
    var newGroupCategoryDraft by remember { mutableStateOf("LIGHTING") }

    var editingCapabilityKey by remember(activeEditIdx) { mutableStateOf<String?>(null) }
    var capabilityLabelDraft by remember(activeEditIdx) { mutableStateOf("") }

    val hiddenTilesCount = tiles.count { !it.isVisible }
    val groupCardTiles = remember(tiles.toList()) {
        tiles.filter { it.type == TileType.GROUP_CARD }
    }

    val sizeOptions = listOf(
        (1 to 1) to "Standard Tile (1x1)",
        (2 to 1) to "Wide Banner (2x1)",
        (2 to 2) to "Large Square (2x2)",
        (1 to 2) to "Tall Vertical (1x2)",
        (3 to 2) to "Extra Wide Frame (3x2)",
        (4 to 2) to "Full Width Banner (4x2)",
        (4 to 3) to "Panoramic Showcase (4x3)"
    )

    val slideshowIntervals = listOf(
        5 to "Every 5 Seconds",
        10 to "Every 10 Seconds (Default)",
        15 to "Every 15 Seconds",
        30 to "Every 30 Seconds",
        60 to "Every 1 Minute",
        300 to "Every 5 Minutes"
    )

    val tilePhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val idx = activeEditIdx ?: return@rememberLauncherForActivityResult
        if (uris.isNotEmpty() && idx in tiles.indices) {
            val currentTile = tiles[idx]
            val mergedUris = currentTile.slideshowLocalUris.toMutableList()
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                val str = uri.toString()
                if (str !in mergedUris) {
                    mergedUris.add(str)
                }
            }
            PhotoSlideshowResolver.clearCloudCache()
            tiles[idx] = currentTile.copy(slideshowLocalUris = mergedUris)
            DeviceRepository.saveTiles(context, tiles)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompactMobile = maxWidth < 600.dp
        
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = { showIgnoredDevicesDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp),
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Manage Ignored Devices", fontFamily = GhFont, fontSize = 11.sp, color = GhTextPrimary)
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                Surface(
                    color = GhTileSurface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, GhTileSurfaceElevated),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ResponsiveSplitRow(
                            isStacked = isCompactMobile,
                            firstContent = { mod ->
                                Column(modifier = mod) {
                                    Text(
                                        text = "Manage Devices (${tiles.size} total${if (hiddenTilesCount > 0) ", $hiddenTilesCount hidden" else ""})",
                                        color = GhTextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = GhFont
                                    )
                                    Text(
                                        text = "Show or unhide any tile, create Group Cards, rename individual plug sockets, or inspect & pin hardware capabilities.",
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
                                    if (hiddenTilesCount > 0) {
                                        FilterChip(
                                            selected = showHiddenOnlyFilter,
                                            onClick = { showHiddenOnlyFilter = !showHiddenOnlyFilter },
                                            shape = RoundedCornerShape(50),
                                            label = {
                                                Text(
                                                    text = "Hidden ($hiddenTilesCount)",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        )
                                        FilledTonalButton(
                                            onClick = {
                                                for (i in tiles.indices) {
                                                    if (!tiles[i].isVisible) {
                                                        tiles[i] = tiles[i].copy(isVisible = true)
                                                    }
                                                }
                                                showHiddenOnlyFilter = false
                                                DeviceRepository.saveTiles(context, tiles)
                                            },
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = GhAccentGreen.copy(alpha = 0.22f),
                                                contentColor = GhAccentGreen
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Unhide All ($hiddenTilesCount)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    Button(
                                        onClick = { showCreateGroupDialog = true },
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = GhAccentGreen,
                                            contentColor = Color(0xFF0F1114)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Create Group Card", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = { showRemoveAllConfirmDialog = true },
                                        shape = RoundedCornerShape(50),
                                        border = BorderStroke(1.dp, GhAccentRed.copy(alpha = 0.7f)),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = null,
                                            tint = GhAccentRed,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Remove All Devices",
                                            color = GhAccentRed,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }

            items(tiles.size, key = { idx -> tiles[idx].id }) { idx ->
                val tile = tiles[idx]
                if (showHiddenOnlyFilter && tile.isVisible) {
                    return@items
                }

                val isEditingThisTile = activeEditIdx == idx
                val currentSizeLabel = sizeOptions.firstOrNull { (wh, _) ->
                    wh.first == tile.colSpan && wh.second == tile.effectiveRowSpan
                }?.second ?: "Standard Tile (1x1)"
                val assignedGroupTitle = groupCardTiles.firstOrNull { it.id == tile.groupCardId }?.title
                val lifecycleState = tile.effectiveLifecycleState
                val lifecycleBadgeColor = Color(lifecycleState.badgeColorHex or 0xFF000000L)

                Surface(
                    color = GhTileSurface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(
                        1.dp,
                        when {
                            isEditingThisTile -> GhAccentBlue
                            !tile.isVisible -> GhAccentWarm.copy(alpha = 0.55f)
                            else -> GhTileSurfaceElevated
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
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
                                    color = GhTileSurfaceElevated,
                                    shape = CircleShape,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = iconForTileType(tile.type),
                                            contentDescription = null,
                                            tint = accentForTileType(tile),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = if (tile.isVisible) tile.title else "${tile.title} (HIDDEN)",
                                            color = if (tile.isVisible) GhTextPrimary else GhAccentWarm,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = GhFont,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Surface(
                                            color = lifecycleBadgeColor.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = lifecycleState.label,
                                                color = lifecycleBadgeColor,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                            )
                                        }
                                        if (tile.isManualHoldActive) {
                                            Surface(
                                                color = GhAccentWarm.copy(alpha = 0.20f),
                                                shape = RoundedCornerShape(50)
                                            ) {
                                                Text(
                                                    text = "30m Hold",
                                                    color = GhAccentWarm,
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    val roomInfo = if (tile.roomOrSubtitle.isNotBlank()) " \u2022 ${tile.roomOrSubtitle}" else ""
                                    val groupInfo = if (!assignedGroupTitle.isNullOrBlank()) " \u2022 In Group: $assignedGroupTitle" else ""
                                    Text(
                                        text = "${tile.deviceClass.label} (${tile.type.brandName})$roomInfo$groupInfo \u2022 $currentSizeLabel",
                                        color = GhTextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = GhFont,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = if (tile.isVisible) GhTileGreenActive.copy(alpha = 0.55f) else GhTileWarmActive.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(50),
                                    border = BorderStroke(
                                        1.dp,
                                        if (tile.isVisible) GhAccentGreen.copy(alpha = 0.5f) else GhAccentWarm
                                    ),
                                    modifier = Modifier.clickable {
                                        tiles[idx] = tile.copy(isVisible = !tile.isVisible)
                                        DeviceRepository.saveTiles(context, tiles)
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (tile.isVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                            contentDescription = null,
                                            tint = if (tile.isVisible) GhAccentGreen else GhAccentWarm,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = if (tile.isVisible) "Visible" else "Tap to Unhide",
                                            color = if (tile.isVisible) GhAccentGreen else GhAccentWarm,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = GhFont
                                        )
                                    }
                                }

                                FilledTonalButton(
                                    onClick = {
                                        onEditingTileIndexChange(if (isEditingThisTile) null else idx)
                                    },
                                    shape = RoundedCornerShape(50),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = if (isEditingThisTile) GhAccentBlue.copy(alpha = 0.25f) else GhTileSurfaceElevated,
                                        contentColor = if (isEditingThisTile) GhAccentBlue else GhTextPrimary
                                    ),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isEditingThisTile) Icons.Default.Check else Icons.Outlined.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isEditingThisTile) "Done" else "Edit",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = GhFont
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(visible = isEditingThisTile) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                HorizontalDivider(color = GhTileSurfaceElevated)

                                // OS Telemetry, Semantic Class Override & Manual Hold Strip
                                Surface(
                                    color = GhTileSurfaceElevated.copy(alpha = 0.45f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, GhCardBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val schemaShort = tile.capabilitySchemaHash.take(8).ifBlank { "default" }
                                        val providerDisplay = tile.activeProviderId.ifBlank { tile.type.brandName }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Provider: $providerDisplay \u2022 Schema Hash: $schemaShort \u2022 State v${tile.stateVersion} (${tile.stateAuthorityTier.label})",
                                                color = GhTextSecondary,
                                                fontSize = 10.5.sp,
                                                fontFamily = GhFont
                                            )
                                            if (tile.lastCausalActor.isNotBlank()) {
                                                Text(
                                                    text = "Last Actor: ${tile.lastCausalActor} (${tile.lastIntentAuthorityTier.label})",
                                                    color = GhAccentGreen,
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontFamily = GhFont
                                                )
                                            }
                                        }
                                        if (tile.isManualHoldActive) {
                                            FilledTonalButton(
                                                onClick = {
                                                    tiles[idx] = tile.copy(manualHoldUntilEpochMs = 0L)
                                                    DeviceRepository.saveTiles(context, tiles)
                                                },
                                                shape = RoundedCornerShape(50),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(28.dp),
                                                colors = ButtonDefaults.filledTonalButtonColors(
                                                    containerColor = GhAccentWarm.copy(alpha = 0.22f),
                                                    contentColor = GhAccentWarm
                                                )
                                            ) {
                                                Text("Clear 30m Hold", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }

                                ResponsiveSplitRow(
                                    isStacked = isCompactMobile,
                                    firstContent = { mod ->
                                        OutlinedTextField(
                                            value = tile.title,
                                            onValueChange = {
                                                tiles[idx] = tile.copy(title = it)
                                                DeviceRepository.saveTiles(context, tiles)
                                            },
                                            label = { Text("Display Name", fontSize = 11.sp) },
                                            textStyle = compactInputTextStyle,
                                            shape = RoundedCornerShape(14.dp),
                                            colors = claraFieldColors(),
                                            modifier = mod,
                                            singleLine = true
                                        )
                                    },
                                    secondContent = { mod ->
                                        CleanDropdownSelector(
                                            label = "Tile Size",
                                            selectedText = currentSizeLabel,
                                            options = sizeOptions,
                                            optionLabel = { it.second },
                                            onOptionSelected = { (wh, _) ->
                                                tiles[idx] = tile.copy(
                                                    colSpan = wh.first,
                                                    rowSpan = wh.second,
                                                    isTall = wh.second > 1
                                                )
                                                DeviceRepository.saveTiles(context, tiles)
                                            },
                                            textStyle = compactInputTextStyle,
                                            modifier = mod
                                        )
                                    }
                                )

                                // Semantic DeviceClass Selector
                                CleanDropdownSelector(
                                    label = "Semantic Device Class (Controls Hero Card & Automation Behavior)",
                                    selectedText = "${tile.deviceClass.label} \u2014 ${tile.deviceClass.roleDescription}",
                                    options = DeviceClass.entries,
                                    optionLabel = { it.label },
                                    optionSubtitle = { it.roleDescription },
                                    onOptionSelected = { chosenClass ->
                                        val updatedTile = tile.copy(
                                            deviceClassOverride = chosenClass,
                                            classificationSource = ClassificationSource.MANUAL_OVERRIDE,
                                            classificationConfidence = 1.0f,
                                            classificationVersion = CapabilityClassifier.CURRENT_VERSION,
                                            classificationLastEvaluatedEpochMs = System.currentTimeMillis()
                                        )
                                        tiles[idx] = updatedTile
                                        val devKey = updatedTile.serialNumber.ifBlank { updatedTile.entityId }.ifBlank { updatedTile.id }
                                        CapabilityClassifier.recordResolution(
                                            deviceOrTileKey = devKey,
                                            resolution = ClassificationResolution(
                                                deviceClass = chosenClass,
                                                deviceSubtype = updatedTile.deviceSubtype,
                                                suggestedTileType = updatedTile.type,
                                                confidence = 1.0f,
                                                source = ClassificationSource.MANUAL_OVERRIDE,
                                                version = CapabilityClassifier.CURRENT_VERSION,
                                                schemaHash = updatedTile.capabilitySchemaHash
                                            ),
                                            context = context
                                        )
                                        DeviceRepository.saveTiles(context, tiles)
                                    },
                                    textStyle = compactInputTextStyle,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // Socket Count Selector + Power Telemetry Toggle + Custom Socket Names
                                if (tile.isSwitchTile) {
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        item {
                                            Text(
                                                text = "Hardware Capabilities:",
                                                color = GhTextSecondary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        items(
                                            listOf(
                                                1 to "Single Socket (1 Gang)",
                                                2 to "Dual Socket (2 Gang)"
                                            )
                                        ) { (cnt, lbl) ->
                                            val isSel = (tile.multiGangSocketCount >= 2 && cnt == 2) ||
                                                    (tile.multiGangSocketCount < 2 && cnt == 1)
                                            FilterChip(
                                                selected = isSel,
                                                onClick = {
                                                    tiles[idx] = tile.copy(multiGangSocketCount = cnt)
                                                    DeviceRepository.saveTiles(context, tiles)
                                                },
                                                shape = RoundedCornerShape(50),
                                                label = { Text(lbl, fontSize = 11.sp) }
                                            )
                                        }
                                        item {
                                            FilterChip(
                                                selected = tile.hasPowerTelemetryCapability,
                                                onClick = {
                                                    tiles[idx] = tile.copy(
                                                        hasExplicitPowerTelemetry = !tile.hasPowerTelemetryCapability
                                                    )
                                                    DeviceRepository.saveTiles(context, tiles)
                                                },
                                                shape = RoundedCornerShape(50),
                                                label = {
                                                    Text(
                                                        text = if (tile.hasPowerTelemetryCapability) "Power Meter (W/kWh) \u2713" else "Enable Power Meter",
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            )
                                        }
                                        if (tile.multiGangSocketCount >= 2) {
                                            val isCurrentlySplit = tile.channelId.startsWith("SPLIT_SOCKET_")
                                            item {
                                                FilterChip(
                                                    selected = isCurrentlySplit,
                                                    onClick = {
                                                        onEditingTileIndexChange(null)
                                                        UniversalOnboardingEngine.splitOrMergeTwoGangTile(
                                                            context = context,
                                                            tile = tile,
                                                            tiles = tiles,
                                                            splitIntoTwo = !isCurrentlySplit
                                                        )
                                                    },
                                                    shape = RoundedCornerShape(50),
                                                    label = {
                                                        Text(
                                                            text = if (isCurrentlySplit) "Split into 2 Tiles \u2713 (Tap to Merge)" else "Split into 2 Tiles (Socket 1 & 2)",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    if (tile.multiGangSocketCount >= 2) {
                                        ResponsiveSplitRow(
                                            isStacked = isCompactMobile,
                                            firstContent = { mod ->
                                                OutlinedTextField(
                                                    value = tile.socket1Name,
                                                    onValueChange = {
                                                        tiles[idx] = tile.copy(socket1Name = it)
                                                        DeviceRepository.saveTiles(context, tiles)
                                                    },
                                                    label = { Text("Socket 1 Custom Name (e.g. Pond Pump)", fontSize = 11.sp) },
                                                    textStyle = compactInputTextStyle,
                                                    shape = RoundedCornerShape(14.dp),
                                                    colors = claraFieldColors(),
                                                    modifier = mod,
                                                    singleLine = true
                                                )
                                            },
                                            secondContent = { mod ->
                                                OutlinedTextField(
                                                    value = tile.socket2Name,
                                                    onValueChange = {
                                                        tiles[idx] = tile.copy(socket2Name = it)
                                                        DeviceRepository.saveTiles(context, tiles)
                                                    },
                                                    label = { Text("Socket 2 Custom Name (e.g. Garden Lights)", fontSize = 11.sp) },
                                                    textStyle = compactInputTextStyle,
                                                    shape = RoundedCornerShape(14.dp),
                                                    colors = claraFieldColors(),
                                                    modifier = mod,
                                                    singleLine = true
                                                )
                                            }
                                        )
                                    }
                                }

                                // =========================================================================
                                // PER-DEVICE CAPABILITY & BUTTON INSPECTOR ("Discovered Hardware Controls,
                                // Modes & Sensors") — Live Mode Testing, Inline Renaming & Hero Pinning
                                // =========================================================================
                                val effectiveCaps = remember(tile) {
                                    tile.capabilities.ifEmpty {
                                        SmartHomeApiClient.buildDefaultCapabilitiesForTile(tile)
                                    }
                                }
                                if (effectiveCaps.isNotEmpty() &&
                                    tile.type != TileType.CLOCK_WEATHER &&
                                    tile.type != TileType.PHOTO_SLIDESHOW &&
                                    tile.type != TileType.GROUP_CARD &&
                                    tile.type != TileType.MACRO_TILE
                                ) {
                                    Surface(
                                        color = GhTileSurfaceElevated.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, GhCardBorder),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Discovered Hardware Controls, Modes & Sensors (${effectiveCaps.size})",
                                                color = GhAccentGreen,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = GhFont
                                            )
                                            Text(
                                                text = "Test any switch, mode, slider, or timer live, tap the pencil icon to rename any button, or toggle 'Pinned to Hero Card'.",
                                                color = GhTextSecondary,
                                                fontSize = 11.sp,
                                                fontFamily = GhFont
                                            )

                                            effectiveCaps.forEach { cap ->
                                                val isEditingThisCap = editingCapabilityKey == "${tile.id}_${cap.key}"
                                                Surface(
                                                    color = GhTileSurface,
                                                    shape = RoundedCornerShape(12.dp),
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (cap.isPinnedToHero && cap.isWritable) GhAccentGreen.copy(alpha = 0.45f) else GhCardBorder
                                                    ),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
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
                                                                if (isEditingThisCap) {
                                                                    OutlinedTextField(
                                                                        value = capabilityLabelDraft,
                                                                        onValueChange = { capabilityLabelDraft = it },
                                                                        singleLine = true,
                                                                        textStyle = TextStyle(
                                                                            color = GhTextPrimary,
                                                                            fontSize = 12.sp,
                                                                            fontWeight = FontWeight.Bold,
                                                                            fontFamily = GhFont
                                                                        ),
                                                                        shape = RoundedCornerShape(10.dp),
                                                                        colors = claraFieldColors(),
                                                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                                                        keyboardActions = KeyboardActions(
                                                                            onDone = {
                                                                                val cleanLbl = capabilityLabelDraft.trim().ifBlank { cap.label }
                                                                                val updatedCaps = effectiveCaps.map { c ->
                                                                                    if (c.key == cap.key) c.copy(label = cleanLbl) else c
                                                                                }
                                                                                tiles[idx] = tile.copy(
                                                                                    capabilities = updatedCaps,
                                                                                    capabilitySchemaHash = CapabilitySchemaHasher.computeHash(updatedCaps)
                                                                                )
                                                                                DeviceRepository.saveTiles(context, tiles)
                                                                                editingCapabilityKey = null
                                                                            }
                                                                        ),
                                                                        modifier = Modifier.widthIn(min = 140.dp, max = 220.dp)
                                                                    )
                                                                    FilledTonalIconButton(
                                                                        onClick = {
                                                                            val cleanLbl = capabilityLabelDraft.trim().ifBlank { cap.label }
                                                                            val updatedCaps = effectiveCaps.map { c ->
                                                                                if (c.key == cap.key) c.copy(label = cleanLbl) else c
                                                                            }
                                                                            tiles[idx] = tile.copy(
                                                                                capabilities = updatedCaps,
                                                                                capabilitySchemaHash = CapabilitySchemaHasher.computeHash(updatedCaps)
                                                                            )
                                                                            DeviceRepository.saveTiles(context, tiles)
                                                                            editingCapabilityKey = null
                                                                        },
                                                                        modifier = Modifier.size(26.dp)
                                                                    ) {
                                                                        Icon(Icons.Default.Check, contentDescription = "Save", modifier = Modifier.size(14.dp))
                                                                    }
                                                                } else {
                                                                    Text(
                                                                        text = cap.label,
                                                                        color = GhTextPrimary,
                                                                        fontSize = 12.5.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                    IconButton(
                                                                        onClick = {
                                                                            capabilityLabelDraft = cap.label
                                                                            editingCapabilityKey = "${tile.id}_${cap.key}"
                                                                        },
                                                                        modifier = Modifier.size(22.dp)
                                                                    ) {
                                                                        Icon(
                                                                            imageVector = Icons.Outlined.Edit,
                                                                            contentDescription = "Rename Button",
                                                                            tint = GhAccentBlue,
                                                                            modifier = Modifier.size(13.dp)
                                                                        )
                                                                    }
                                                                    Surface(
                                                                        color = GhTileSurfaceElevated,
                                                                        shape = RoundedCornerShape(50)
                                                                    ) {
                                                                        Text(
                                                                            text = cap.kind.label,
                                                                            color = GhTextSecondary,
                                                                            fontSize = 9.5.sp,
                                                                            fontWeight = FontWeight.Medium,
                                                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                                        )
                                                                    }
                                                                }
                                                            }

                                                            if (cap.isWritable) {
                                                                FilterChip(
                                                                    selected = cap.isPinnedToHero,
                                                                    onClick = {
                                                                        val updatedCaps = effectiveCaps.map { c ->
                                                                            if (c.key == cap.key) c.copy(isPinnedToHero = !c.isPinnedToHero) else c
                                                                        }
                                                                        tiles[idx] = tile.copy(
                                                                            capabilities = updatedCaps,
                                                                            capabilitySchemaHash = CapabilitySchemaHasher.computeHash(updatedCaps)
                                                                        )
                                                                        DeviceRepository.saveTiles(context, tiles)
                                                                    },
                                                                    shape = RoundedCornerShape(50),
                                                                    label = {
                                                                        Text(
                                                                            text = if (cap.isPinnedToHero) "Pinned to Hero Card \u2713" else "Pin to Hero Card",
                                                                            fontSize = 10.sp,
                                                                            fontWeight = if (cap.isPinnedToHero) FontWeight.Bold else FontWeight.Medium
                                                                        )
                                                                    }
                                                                )
                                                            } else {
                                                                Text(
                                                                    text = "${cap.currentValue}${if (cap.unit.isNotBlank()) " ${cap.unit}" else ""}",
                                                                    color = GhAccentGreen,
                                                                    fontSize = 12.sp,
                                                                    fontWeight = FontWeight.ExtraBold
                                                                )
                                                            }
                                                        }

                                                        // Live Interactive Hardware Control Row
                                                        when (cap.kind) {
                                                            DeviceCapabilityKind.BOOLEAN_SWITCH -> {
                                                                val isBoolOn = cap.currentValue.equals("true", true) ||
                                                                        cap.currentValue.equals("on", true) ||
                                                                        cap.currentValue.equals("locked", true) ||
                                                                        cap.currentValue == "1"
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Text(
                                                                        text = "Live State: ${if (isBoolOn) "ON" else "OFF"}",
                                                                        color = if (isBoolOn) GhAccentGreen else GhTextSecondary,
                                                                        fontSize = 11.sp,
                                                                        fontWeight = FontWeight.SemiBold
                                                                    )
                                                                    Switch(
                                                                        checked = isBoolOn,
                                                                        onCheckedChange = { nextBool ->
                                                                            dispatchCapabilityCommand(
                                                                                context = context,
                                                                                tile = tile.copy(capabilities = effectiveCaps),
                                                                                capability = cap,
                                                                                newValue = nextBool.toString(),
                                                                                bridge = bridge,
                                                                                audioManager = audioManager,
                                                                                scope = scope,
                                                                                onTileUpdated = { updatedTile ->
                                                                                    tiles[idx] = updatedTile
                                                                                    DeviceRepository.saveTiles(context, tiles)
                                                                                }
                                                                            )
                                                                        },
                                                                        colors = SwitchDefaults.colors(
                                                                            checkedThumbColor = Color(0xFF0F1114),
                                                                            checkedTrackColor = GhAccentGreen
                                                                        )
                                                                    )
                                                                }
                                                            }
                                                            DeviceCapabilityKind.ENUM_MODE -> {
                                                                val options = cap.enumOptions.ifEmpty { listOf("off", "on", "last") }
                                                                LazyRow(
                                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                                    modifier = Modifier.fillMaxWidth()
                                                                ) {
                                                                    items(options) { opt ->
                                                                        val isSelected = cap.currentValue.equals(opt, ignoreCase = true)
                                                                        FilterChip(
                                                                            selected = isSelected,
                                                                            onClick = {
                                                                                dispatchCapabilityCommand(
                                                                                    context = context,
                                                                                    tile = tile.copy(capabilities = effectiveCaps),
                                                                                    capability = cap,
                                                                                    newValue = opt,
                                                                                    bridge = bridge,
                                                                                    audioManager = audioManager,
                                                                                    scope = scope,
                                                                                    onTileUpdated = { updatedTile ->
                                                                                        tiles[idx] = updatedTile
                                                                                        DeviceRepository.saveTiles(context, tiles)
                                                                                    }
                                                                                )
                                                                            },
                                                                            shape = RoundedCornerShape(50),
                                                                            label = {
                                                                                Text(
                                                                                    text = opt.uppercase(Locale.US),
                                                                                    fontSize = 10.5.sp,
                                                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                                                )
                                                                            }
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                            DeviceCapabilityKind.COUNTDOWN_TIMER -> {
                                                                val curSecs = cap.currentValue.toFloatOrNull()?.roundToInt() ?: 0
                                                                val timerPresets = listOf(
                                                                    0 to "Off",
                                                                    900 to "15m",
                                                                    1800 to "30m",
                                                                    3600 to "1h",
                                                                    7200 to "2h",
                                                                    14400 to "4h"
                                                                )
                                                                LazyRow(
                                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    modifier = Modifier.fillMaxWidth()
                                                                ) {
                                                                    items(timerPresets) { (secVal, label) ->
                                                                        val isSel = curSecs == secVal
                                                                        FilterChip(
                                                                            selected = isSel,
                                                                            onClick = {
                                                                                dispatchCapabilityCommand(
                                                                                    context = context,
                                                                                    tile = tile.copy(capabilities = effectiveCaps),
                                                                                    capability = cap,
                                                                                    newValue = secVal.toString(),
                                                                                    bridge = bridge,
                                                                                    audioManager = audioManager,
                                                                                    scope = scope,
                                                                                    onTileUpdated = { updatedTile ->
                                                                                        tiles[idx] = updatedTile
                                                                                        DeviceRepository.saveTiles(context, tiles)
                                                                                    }
                                                                                )
                                                                            },
                                                                            shape = RoundedCornerShape(50),
                                                                            label = { Text(label, fontSize = 10.5.sp) }
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                            DeviceCapabilityKind.NUMERIC_SLIDER -> {
                                                                val minV = cap.minValue
                                                                val maxV = cap.maxValue.coerceAtLeast(minV + 1f)
                                                                var sliderVal by remember(cap.key, cap.currentValue) {
                                                                    mutableFloatStateOf(
                                                                        (cap.currentValue.toFloatOrNull() ?: minV).coerceIn(minV, maxV)
                                                                    )
                                                                }
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                                ) {
                                                                    Slider(
                                                                        value = sliderVal,
                                                                        onValueChange = { sliderVal = it },
                                                                        onValueChangeFinished = {
                                                                            dispatchCapabilityCommand(
                                                                                context = context,
                                                                                tile = tile.copy(capabilities = effectiveCaps),
                                                                                capability = cap,
                                                                                newValue = "%.1f".format(Locale.US, sliderVal),
                                                                                bridge = bridge,
                                                                                audioManager = audioManager,
                                                                                scope = scope,
                                                                                onTileUpdated = { updatedTile ->
                                                                                    tiles[idx] = updatedTile
                                                                                    DeviceRepository.saveTiles(context, tiles)
                                                                                }
                                                                            )
                                                                        },
                                                                        valueRange = minV..maxV,
                                                                        modifier = Modifier
                                                                            .weight(1f)
                                                                            .height(22.dp),
                                                                        colors = SliderDefaults.colors(
                                                                            thumbColor = Color.White,
                                                                            activeTrackColor = GhAccentGreen
                                                                        )
                                                                    )
                                                                    Text(
                                                                        text = "${"%.1f".format(sliderVal)}${cap.unit}",
                                                                        color = GhAccentGreen,
                                                                        fontSize = 11.sp,
                                                                        fontWeight = FontWeight.Bold
                                                                    )
                                                                }
                                                            }
                                                            DeviceCapabilityKind.ACTION_TRIGGER -> {
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    horizontalArrangement = Arrangement.End
                                                                ) {
                                                                    FilledTonalButton(
                                                                        onClick = {
                                                                            dispatchCapabilityCommand(
                                                                                context = context,
                                                                                tile = tile.copy(capabilities = effectiveCaps),
                                                                                capability = cap,
                                                                                newValue = "TRIGGER",
                                                                                bridge = bridge,
                                                                                audioManager = audioManager,
                                                                                scope = scope,
                                                                                onTileUpdated = { updatedTile ->
                                                                                    tiles[idx] = updatedTile
                                                                                    DeviceRepository.saveTiles(context, tiles)
                                                                                }
                                                                            )
                                                                        },
                                                                        shape = RoundedCornerShape(50),
                                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                                        modifier = Modifier.height(28.dp)
                                                                    ) {
                                                                        Text("Test ${cap.label}", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                                                    }
                                                                }
                                                            }
                                                            DeviceCapabilityKind.READ_ONLY_SENSOR -> {}
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Group Card Assignment
                                if (tile.type != TileType.GROUP_CARD && tile.type != TileType.CLOCK_WEATHER && groupCardTiles.isNotEmpty()) {
                                    val groupChoices = listOf("" to "None (Show on Main Dashboard)") +
                                            groupCardTiles.map { it.id to "Group Card: ${it.title}" }
                                    CleanDropdownSelector(
                                        label = "Group Card Assignment",
                                        selectedText = groupChoices.firstOrNull { it.first == tile.groupCardId }?.second
                                            ?: "None (Show on Main Dashboard)",
                                        options = groupChoices,
                                        optionLabel = { it.second },
                                        onOptionSelected = { (grpId, _) ->
                                            tiles[idx] = tile.copy(groupCardId = grpId)
                                            DeviceRepository.saveTiles(context, tiles)
                                        },
                                        textStyle = compactInputTextStyle,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                if (tile.type == TileType.PHOTO_SLIDESHOW) {
                                    ResponsiveSplitRow(
                                        isStacked = isCompactMobile,
                                        firstWeight = 1.2f,
                                        secondWeight = 0.8f,
                                        firstContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Photo Source",
                                                selectedText = tile.slideshowSourceType.label,
                                                options = SlideshowSourceType.entries.filter {
                                                    it != SlideshowSourceType.CUSTOM_URLS || showAdvancedTileSettings
                                                },
                                                optionLabel = { it.label },
                                                optionSubtitle = { it.subtitle },
                                                onOptionSelected = { src ->
                                                    PhotoSlideshowResolver.clearCloudCache()
                                                    tiles[idx] = tile.copy(slideshowSourceType = src)
                                                    DeviceRepository.saveTiles(context, tiles)
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        },
                                        secondContent = { mod ->
                                            CleanDropdownSelector(
                                                label = "Slide Interval Speed",
                                                selectedText = slideshowIntervals.firstOrNull {
                                                    it.first == tile.slideshowIntervalSeconds
                                                }?.second ?: "Every ${tile.slideshowIntervalSeconds}s",
                                                options = slideshowIntervals,
                                                optionLabel = { it.second },
                                                onOptionSelected = { (sec, _) ->
                                                    tiles[idx] = tile.copy(slideshowIntervalSeconds = sec)
                                                    DeviceRepository.saveTiles(context, tiles)
                                                },
                                                textStyle = compactInputTextStyle,
                                                modifier = mod
                                            )
                                        }
                                    )

                                    when (tile.slideshowSourceType) {
                                        SlideshowSourceType.LOCAL_DEVICE -> {
                                            ResponsiveSplitRow(
                                                isStacked = isCompactMobile,
                                                firstContent = { mod ->
                                                    Text(
                                                        text = "Local Photos Selected: ${tile.slideshowLocalUris.size} photo(s)",
                                                        color = GhAccentPurple,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        modifier = mod
                                                    )
                                                },
                                                secondContent = {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        if (tile.slideshowLocalUris.isNotEmpty()) {
                                                            OutlinedButton(
                                                                onClick = {
                                                                    tiles[idx] = tile.copy(slideshowLocalUris = emptyList())
                                                                    DeviceRepository.saveTiles(context, tiles)
                                                                },
                                                                shape = RoundedCornerShape(50)
                                                            ) {
                                                                Text("Clear Photos", fontSize = 11.sp, color = GhAccentRed)
                                                            }
                                                        }
                                                        Button(
                                                            onClick = { tilePhotoPickerLauncher.launch(arrayOf("image/*")) },
                                                            shape = RoundedCornerShape(50),
                                                            colors = ButtonDefaults.buttonColors(
                                                                containerColor = GhAccentPurple,
                                                                contentColor = GhBackground
                                                            )
                                                        ) {
                                                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(15.dp))
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text("Pick Local Photos", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                        SlideshowSourceType.GOOGLE_PHOTOS,
                                        SlideshowSourceType.ONEDRIVE -> {
                                            val linkedCount = tile.slideshowCloudUrl.split(",", "\n").count { it.trim().startsWith("http") }
                                            ResponsiveSplitRow(
                                                isStacked = isCompactMobile,
                                                firstContent = { mod ->
                                                    Text(
                                                        text = if (linkedCount > 0) {
                                                            "${tile.slideshowSourceType.label} Connected ($linkedCount photos/albums linked) \u2713"
                                                        } else {
                                                            "Sign in to ${tile.slideshowSourceType.label} to select your albums:"
                                                        },
                                                        color = if (linkedCount > 0) GhAccentGreen else GhTextPrimary,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        modifier = mod
                                                    )
                                                },
                                                secondContent = {
                                                    Button(
                                                        onClick = { activeCloudPickerProvider = tile.slideshowSourceType },
                                                        shape = RoundedCornerShape(50),
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = GhAccentBlue,
                                                            contentColor = GhBackground
                                                        )
                                                    ) {
                                                        Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(15.dp))
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = if (tile.slideshowSourceType == SlideshowSourceType.GOOGLE_PHOTOS)
                                                                "Sign In & Select Albums"
                                                            else
                                                                "Sign In & Select Folders",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                        SlideshowSourceType.CUSTOM_URLS -> {}
                                    }
                                } else if (tile.type != TileType.CLOCK_WEATHER &&
                                    tile.type != TileType.MUSIC_PLAYER &&
                                    tile.type != TileType.JARVIS_SCENES
                                ) {
                                    CleanDropdownSelector(
                                        label = "Room Location",
                                        selectedText = tile.roomOrSubtitle.ifBlank { "Assign a Room (Optional)" },
                                        options = listOf("") + bridge.customRooms.ifEmpty { DEFAULT_ROOMS },
                                        optionLabel = { if (it.isBlank()) "No Room Label" else it },
                                        onOptionSelected = { chosenRoom ->
                                            tiles[idx] = tile.copy(roomOrSubtitle = chosenRoom)
                                            DeviceRepository.saveTiles(context, tiles)
                                        },
                                        textStyle = compactInputTextStyle,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                if (tile.type == TileType.EZVIZ_DOORBELL) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (tile.secretCode.isNotBlank())
                                                "EZVIZ Verification Code Linked (${tile.secretCode}) \u2713"
                                            else
                                                "Tap to auto-pull Verification Code (PIN) from EZVIZ Cloud:",
                                            color = if (tile.secretCode.isNotBlank()) GhAccentGreen else GhAccentWarm,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        FilledTonalButton(
                                            onClick = {
                                                if (isSyncingEzvizPinForTile) return@FilledTonalButton
                                                val savedEmail = EzvizCloudApiClient.getSavedEzvizEmail(context)
                                                val savedPass = EzvizCloudApiClient.getSavedEzvizPassword(context)
                                                if (savedEmail.isNotBlank() && savedPass.isNotBlank()) {
                                                    isSyncingEzvizPinForTile = true
                                                    scope.launch {
                                                        val res = EzvizCloudApiClient.loginAndDiscoverWithConsumerAccount(
                                                            context = context,
                                                            email = savedEmail,
                                                            password = savedPass
                                                        )
                                                        isSyncingEzvizPinForTile = false
                                                        val matched = res.getOrNull()?.firstOrNull {
                                                            it.serial.equals(tile.serialNumber.trim(), true)
                                                        } ?: res.getOrNull()?.firstOrNull()
                                                        if (matched != null && idx in tiles.indices) {
                                                            val pin = matched.verificationCode.ifBlank { tile.secretCode }
                                                            val ip = matched.localIp.ifBlank { tile.ipAddress }
                                                            val port = if (matched.localRtspPort > 0) matched.localRtspPort else 554
                                                            val rtsp = if (ip.isNotBlank() && pin.isNotBlank()) {
                                                                "rtsp://admin:${Uri.encode(pin)}@$ip:$port/Streaming/Channels/102"
                                                            } else tile.customRtspUrl
                                                            tiles[idx] = tiles[idx].copy(
                                                                serialNumber = matched.serial.ifBlank { tile.serialNumber },
                                                                secretCode = pin,
                                                                ipAddress = ip,
                                                                customRtspUrl = rtsp
                                                            )
                                                            DeviceRepository.saveTiles(context, tiles)
                                                        }
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(50),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Icon(Icons.Outlined.CloudSync, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (isSyncingEzvizPinForTile) "Pulling PIN..." else "Auto-Pull PIN & Stream",
                                                fontSize = 11.sp,
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
                                    Row(
                                        modifier = Modifier.clickable {
                                            showAdvancedTileSettings = !showAdvancedTileSettings
                                        },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (showAdvancedTileSettings) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                            contentDescription = null,
                                            tint = GhTextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (showAdvancedTileSettings) "Hide Advanced Technical Settings" else "Advanced Technical Settings",
                                            color = GhTextSecondary,
                                            fontSize = 11.sp,
                                            fontFamily = GhFont
                                        )
                                    }

                                    if (tile.needsCapabilitySync) {
                                        FilledTonalButton(
                                            onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    val learned = TuyaApiClient.capabilitiesByDevice[tile.serialNumber.ifBlank { tile.entityId }]
                                                        .orEmpty()
                                                        .ifEmpty { SmartHomeApiClient.buildDefaultCapabilitiesForTile(tile) }
                                                    if (learned.isNotEmpty()) {
                                                        val hash = CapabilitySchemaHasher.computeHash(learned)
                                                        withContext(Dispatchers.Main) {
                                                            val i = tiles.indexOfFirst { it.id == tile.id }
                                                            if (i >= 0) {
                                                                tiles[i] = tiles[i].copy(
                                                                    capabilities = learned,
                                                                    capabilitySchemaHash = hash,
                                                                    lifecycleState = if (tiles[i].isOnline) DeviceLifecycleState.OPERATIONAL else DeviceLifecycleState.OFFLINE
                                                                )
                                                                DeviceRepository.saveTiles(context, tiles)
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(50),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Sync Capabilities", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    var showRemovalDialog by remember { mutableStateOf(false) }
                                    if (showRemovalDialog) {
                                        DeviceRemovalDialog(
                                            context = context,
                                            tile = tile,
                                            bridge = bridge,
                                            onDismiss = { showRemovalDialog = false },
                                            onUnpaired = {
                                                DeviceRepository.unpairTileFromDashboard(context, tile, tiles)
                                                onEditingTileIndexChange(null)
                                            },
                                            onUnbound = {
                                                scope.launch {
                                                    DeviceRepository.unbindAndForgetTile(context, tile, bridge, tiles)
                                                    onEditingTileIndexChange(null)
                                                }
                                            }
                                        )
                                    }

                                    if (tile.effectiveLifecycleState == DeviceLifecycleState.UNPAIRED) {
                                        OutlinedButton(
                                            onClick = {
                                                val i = tiles.indexOfFirst { it.id == tile.id }
                                                if (i >= 0) {
                                                    val nextState = if (tile.isOnline) DeviceLifecycleState.ONLINE else DeviceLifecycleState.OFFLINE
                                                    val restoredTile = tile.copy(
                                                        isVisible = true,
                                                        lifecycleState = nextState
                                                    )
                                                    tiles[i] = restoredTile
                                                    DeviceRepository.unmarkDeviceDismissed(
                                                        context,
                                                        tile.canonicalGuid,
                                                        tile.resolvedCanonicalGuid()
                                                    )
                                                    DeviceLifecycleRecorder.recordTileTransition(
                                                        tile = restoredTile,
                                                        transition = MandatoryLifecycleTransition.REDISCOVERED,
                                                        previousState = DeviceLifecycleState.UNPAIRED.name,
                                                        newState = DeviceLifecycleState.DISCOVERABLE_AGAIN.name,
                                                        protocolResultCode = "DASHBOARD_RESTORED"
                                                    )
                                                    DeviceLifecycleRecorder.recordTileTransition(
                                                        tile = restoredTile,
                                                        transition = if (restoredTile.isOnline) {
                                                            MandatoryLifecycleTransition.ONLINE
                                                        } else {
                                                            MandatoryLifecycleTransition.OFFLINE
                                                        },
                                                        previousState = DeviceLifecycleState.DISCOVERABLE_AGAIN.name,
                                                        newState = nextState.name,
                                                        protocolResultCode = "RESTORE_STATE_${nextState.name}",
                                                        failureCategory = if (restoredTile.isOnline) null else FailureCategory.TRANSPORT_FAILURE
                                                    )
                                                    JarvisLiveBridge.updateDeviceLifecycleAndRecoveryStage(
                                                        tile = restoredTile,
                                                        lifecycleState = nextState,
                                                        emitFlightRecord = false
                                                    )
                                                    DeviceRepository.saveTiles(context, tiles)
                                                    onEditingTileIndexChange(null)
                                                }
                                            },
                                            shape = RoundedCornerShape(50),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GhAccentGreen)
                                        ) {
                                            Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Restore to Dashboard", fontSize = 11.sp)
                                        }
                                    } else if (tile.effectiveLifecycleState == DeviceLifecycleState.QUARANTINED_ERROR && (tile.type == TileType.LG_TV || tile.deviceSubtype == DeviceSubtype.TV)) {
                                        OutlinedButton(
                                            onClick = {
                                                LgThinQWebOsClient.clearPinnedTvCertForReverification(context, tile.ipAddress)
                                                DeviceLifecycleRecorder.recordTileTransition(
                                                    tile = tile,
                                                    transition = MandatoryLifecycleTransition.PAIRING_STARTED,
                                                    previousState = DeviceLifecycleState.QUARANTINED_ERROR.name,
                                                    newState = DeviceLifecycleState.CONNECTABLE.name,
                                                    activeTransport = ProviderId.LG_WEBOS.id,
                                                    vendorDriver = ProviderId.LG_WEBOS.displayName,
                                                    protocolResultCode = "SSAP_REVERIFY_START"
                                                )
                                                scope.launch(Dispatchers.IO) {
                                                    val pairStartMs = System.currentTimeMillis()
                                                    val res = LgThinQWebOsClient.pairAndFetchTvDetails(context, tile.ipAddress)
                                                    val pairLatMs = (System.currentTimeMillis() - pairStartMs).coerceAtLeast(1L)
                                                    if (res.isSuccess) {
                                                        val learnedMac = res.getOrNull()?.first.orEmpty().ifBlank { tile.macAddress }
                                                        withContext(Dispatchers.Main) {
                                                            val i = tiles.indexOfFirst { it.id == tile.id }
                                                            if (i >= 0) {
                                                                val repairedTile = tile.copy(
                                                                    lifecycleState = DeviceLifecycleState.ONLINE,
                                                                    isOnline = true,
                                                                    macAddress = CrossEcosystemIdentityCorrelator.normalizeMac(learnedMac).ifBlank { tile.macAddress }
                                                                )
                                                                tiles[i] = repairedTile
                                                                DeviceLifecycleRecorder.recordTileTransition(
                                                                    tile = repairedTile,
                                                                    transition = MandatoryLifecycleTransition.PAIRING_COMPLETED,
                                                                    previousState = DeviceLifecycleState.CONNECTABLE.name,
                                                                    newState = DeviceLifecycleState.CONNECTABLE.name,
                                                                    activeTransport = ProviderId.LG_WEBOS.id,
                                                                    vendorDriver = ProviderId.LG_WEBOS.displayName,
                                                                    latencyMs = pairLatMs,
                                                                    protocolResultCode = "SSAP_REPAIRED"
                                                                )
                                                                DeviceLifecycleRecorder.recordTileTransition(
                                                                    tile = repairedTile,
                                                                    transition = MandatoryLifecycleTransition.AUTH_SUCCESS,
                                                                    previousState = DeviceLifecycleState.CONNECTABLE.name,
                                                                    newState = DeviceLifecycleState.AUTHENTICATED.name,
                                                                    activeTransport = ProviderId.LG_WEBOS.id,
                                                                    vendorDriver = ProviderId.LG_WEBOS.displayName,
                                                                    protocolResultCode = "TLS_CERT_REPINNED"
                                                                )
                                                                DeviceLifecycleRecorder.recordTileTransition(
                                                                    tile = repairedTile,
                                                                    transition = MandatoryLifecycleTransition.ONLINE,
                                                                    previousState = DeviceLifecycleState.AUTHENTICATED.name,
                                                                    newState = DeviceLifecycleState.ONLINE.name,
                                                                    activeTransport = ProviderId.LG_WEBOS.id,
                                                                    vendorDriver = ProviderId.LG_WEBOS.displayName,
                                                                    protocolResultCode = "WEBOS_ONLINE"
                                                                )
                                                                JarvisLiveBridge.updateDeviceLifecycleAndRecoveryStage(
                                                                    tile = repairedTile,
                                                                    lifecycleState = DeviceLifecycleState.ONLINE,
                                                                    emitFlightRecord = false
                                                                )
                                                                DeviceRepository.saveTiles(context, tiles)
                                                                onEditingTileIndexChange(null)
                                                            }
                                                        }
                                                    } else {
                                                        val err = res.exceptionOrNull() ?: Exception("LG TV SSAP re-pair rejected")
                                                        DeviceLifecycleRecorder.recordTileTransition(
                                                            tile = tile,
                                                            transition = MandatoryLifecycleTransition.AUTH_FAILED,
                                                            previousState = DeviceLifecycleState.CONNECTABLE.name,
                                                            newState = DeviceLifecycleState.QUARANTINED_ERROR.name,
                                                            activeTransport = ProviderId.LG_WEBOS.id,
                                                            vendorDriver = ProviderId.LG_WEBOS.displayName,
                                                            latencyMs = pairLatMs,
                                                            protocolResultCode = "SSAP_REVERIFY_FAILED",
                                                            failureCategory = ErrorClassifier.classifyException(err)
                                                        )
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(50),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GhAccentRed)
                                        ) {
                                            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Re-verify & Re-pair TV", fontSize = 11.sp)
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { showRemovalDialog = true },
                                            shape = RoundedCornerShape(50),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GhAccentRed)
                                        ) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Remove Device", fontSize = 11.sp)
                                        }
                                    }
                                }

                                AnimatedVisibility(visible = showAdvancedTileSettings) {
                                    Surface(
                                        color = GhTileSurfaceElevated.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            val resolvedHardwareId = tile.serialNumber.ifBlank { tile.entityId }
                                            val discoveredLanIp = TuyaApiClient.resolveKnownLanIp(resolvedHardwareId)
                                            val discoveredLanMatch = TuyaApiClient.getLanDiscoveredDeviceIds().firstOrNull {
                                                resolvedHardwareId.isNotBlank() && it.endsWith(resolvedHardwareId.takeLast(4), ignoreCase = true)
                                            }.orEmpty()
                                            if (discoveredLanMatch.isNotBlank() && resolvedHardwareId.length < 10) {
                                                Text(
                                                    text = "Detected matching LAN 6668 broadcast ID on Wi-Fi: $discoveredLanMatch",
                                                    color = GhAccentGreen,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            ResponsiveSplitRow(
                                                isStacked = isCompactMobile,
                                                firstContent = { mod ->
                                                    OutlinedTextField(
                                                        value = tile.ipAddress.ifBlank { discoveredLanIp },
                                                        onValueChange = { nextIp ->
                                                            val cleanIp = nextIp.trim()
                                                            val updated = tile.copy(ipAddress = cleanIp)
                                                            tiles[idx] = updated
                                                            if (resolvedHardwareId.isNotBlank() && cleanIp.isNotBlank()) {
                                                                TuyaApiClient.registerDeviceLanAddress(resolvedHardwareId, cleanIp, tile.secretCode)
                                                            }
                                                            DeviceRepository.saveTiles(context, tiles)
                                                        },
                                                        label = { Text("Local IP Address (Port 6668 / LAN)", fontSize = 11.sp) },
                                                        textStyle = compactInputTextStyle,
                                                        shape = RoundedCornerShape(14.dp),
                                                        colors = claraFieldColors(),
                                                        modifier = mod,
                                                        singleLine = true
                                                    )
                                                },
                                                secondContent = { mod ->
                                                    OutlinedTextField(
                                                        value = tile.serialNumber.ifBlank { tile.entityId },
                                                        onValueChange = { nextId ->
                                                            val cleanId = nextId.trim()
                                                            val autoIp = TuyaApiClient.resolveKnownLanIp(cleanId).ifBlank { tile.ipAddress }
                                                            tiles[idx] = tile.copy(
                                                                serialNumber = cleanId,
                                                                entityId = cleanId,
                                                                ipAddress = autoIp
                                                            )
                                                            if (cleanId.isNotBlank() && autoIp.isNotBlank()) {
                                                                TuyaApiClient.registerDeviceLanAddress(cleanId, autoIp, tile.secretCode)
                                                            }
                                                            DeviceRepository.saveTiles(context, tiles)
                                                        },
                                                        label = { Text("Hardware Device ID (gwId / Serial / Entity)", fontSize = 11.sp) },
                                                        textStyle = compactInputTextStyle,
                                                        shape = RoundedCornerShape(14.dp),
                                                        colors = claraFieldColors(),
                                                        modifier = mod,
                                                        singleLine = true
                                                    )
                                                }
                                            )
                                            OutlinedTextField(
                                                value = tile.secretCode.ifBlank {
                                                    TuyaApiClient.resolveKnownLocalKey(resolvedHardwareId, tile.macAddress)
                                                },
                                                onValueChange = { nextKey ->
                                                    val cleanKey = nextKey.trim()
                                                    tiles[idx] = tile.copy(secretCode = cleanKey)
                                                    if (resolvedHardwareId.isNotBlank()) {
                                                        TuyaApiClient.registerDeviceLanAddress(
                                                            resolvedHardwareId,
                                                            tile.ipAddress.ifBlank { discoveredLanIp },
                                                            cleanKey
                                                        )
                                                    }
                                                    DeviceRepository.saveTiles(context, tiles)
                                                },
                                                label = {
                                                    Text(
                                                        when {
                                                            tile.type.isCameraType() -> "Camera Verification Code / Safety Password"
                                                            tile.isVehicleTile -> "4-Digit Vehicle Security PIN"
                                                            else -> "16-Character Tuya Local Key (Required for Direct LAN 6668)"
                                                        },
                                                        fontSize = 11.sp
                                                    )
                                                },
                                                textStyle = compactInputTextStyle,
                                                shape = RoundedCornerShape(14.dp),
                                                colors = claraFieldColors(),
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true
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

    if (showRemoveAllConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveAllConfirmDialog = false },
            containerColor = GhTileSurface,
            title = {
                Text(
                    text = "Remove All Devices & Reset Pairing Cache?",
                    color = GhTextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This removes all connected smart devices from your dashboard and clears cached device IDs so you can test pairing from a completely clean slate.",
                    color = GhTextSecondary,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onEditingTileIndexChange(null)
                        DeviceRepository.removeAllDevicesAndClearCaches(context, tiles)
                        showRemoveAllConfirmDialog = false
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GhAccentRed,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Remove All Devices", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveAllConfirmDialog = false }) {
                    Text("Cancel", color = GhTextSecondary)
                }
            }
        )
    }

    if (showCreateGroupDialog) {
        val categoryChoices = listOf(
            "LIGHTING" to "Lighting Group",
            "SOCKETS" to "Sockets & Smart Plugs Group",
            "HEATING" to "Heating & Climate Group",
            "CAMERAS" to "Cameras & Security Group",
            "MEDIA" to "TV & Media Group"
        )
        AlertDialog(
            onDismissRequest = { showCreateGroupDialog = false },
            containerColor = GhTileSurface,
            title = {
                Text(
                    text = "Create Dashboard Group Card",
                    color = GhTextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Group multiple devices (such as all Lights or all Garden Sockets) into a single card on your dashboard. Tapping the Group Card opens a pop-up window with all grouped devices.",
                        color = GhTextSecondary,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = newGroupTitleDraft,
                        onValueChange = { newGroupTitleDraft = it },
                        label = { Text("Group Card Name (e.g. Lighting, Garden Sockets)", fontSize = 11.sp) },
                        textStyle = compactInputTextStyle,
                        shape = RoundedCornerShape(14.dp),
                        colors = claraFieldColors(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    CleanDropdownSelector(
                        label = "Group Icon Style",
                        selectedText = categoryChoices.firstOrNull { it.first == newGroupCategoryDraft }?.second ?: "Lighting Group",
                        options = categoryChoices,
                        optionLabel = { it.second },
                        onOptionSelected = { (code, _) -> newGroupCategoryDraft = code },
                        textStyle = compactInputTextStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanName = newGroupTitleDraft.trim().ifBlank { "Device Group" }
                        tiles.add(
                            0,
                            SmartTile(
                                title = cleanName,
                                roomOrSubtitle = "Group Card",
                                type = TileType.GROUP_CARD,
                                colSpan = 2,
                                rowSpan = 1,
                                isVisible = true,
                                groupCategoryIcon = newGroupCategoryDraft
                            )
                        )
                        DeviceRepository.saveTiles(context, tiles)
                        showCreateGroupDialog = false
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GhAccentGreen,
                        contentColor = Color(0xFF0F1114)
                    )
                ) {
                    Text("Create Group Card", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGroupDialog = false }) {
                    Text("Cancel", color = GhTextSecondary)
                }
            }
        )
    }

    activeCloudPickerProvider?.let { providerToPick ->
        val idx = activeEditIdx
        CloudPhotosAlbumPickerModal(
            provider = providerToPick,
            onPhotosExtracted = { extractedUrls ->
                if (extractedUrls.isNotEmpty() && idx != null && idx in tiles.indices) {
                    val joined = extractedUrls.joinToString(",")
                    PhotoSlideshowResolver.clearCloudCache()
                    tiles[idx] = tiles[idx].copy(slideshowCloudUrl = joined)
                    DeviceRepository.saveTiles(context, tiles)
                }
                activeCloudPickerProvider = null
            },
            onDismiss = { activeCloudPickerProvider = null }
        )
    }
}
}
