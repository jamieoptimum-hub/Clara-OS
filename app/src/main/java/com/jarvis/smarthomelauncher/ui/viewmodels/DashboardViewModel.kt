package com.jarvis.smarthomelauncher.ui.viewmodels

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

fun Context.findComponentActivity(): ComponentActivity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<ComponentActivity>()
        .firstOrNull()

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    val tiles = mutableStateListOf<SmartTile>()
    var bridgeSettings by mutableStateOf(BridgeSettings())
    val automationRules = mutableStateListOf<AutomationRule>()

    init {
        observeReactiveRepositoryFlows()
        hydrateInitialStateAsync()
    }

    private fun hydrateInitialStateAsync() {
        val context = getApplication<Application>().applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            DeviceRepository.loadTiles(context)
            DeviceRepository.loadBridge(context)
            DeviceRepository.loadAutomations(context)
            DeviceRepository.loadTelemetryHistory(context)
            DeviceRepository.loadSecurityAuditLogs(context)
            DeviceRepository.loadSelfHealingLogs(context)
            DeviceRepository.loadDvrSnapshots(context)
            DeviceRepository.loadAutomationLogs(context)
        }
    }

    private fun observeReactiveRepositoryFlows() {
        viewModelScope.launch {
            launch {
                DeviceRepository.tilesFlow.collect { loadedTiles ->
                    for (t in loadedTiles) {
                        val idx = tiles.indexOfFirst { it.id == t.id }
                        if (idx != -1) {
                            if (tiles[idx] != t) {
                                tiles[idx] = t
                            }
                        } else {
                            tiles.add(t)
                        }
                    }
                    tiles.removeAll { currentTile -> loadedTiles.none { it.id == currentTile.id } }
                }
            }
            launch {
                DeviceRepository.bridgeSettingsFlow.collect { bridge ->
                    bridgeSettings = bridge
                }
            }
            launch {
                DeviceRepository.automationRulesFlow.collect { newRules ->
                    if (automationRules.toList() != newRules) {
                        automationRules.clear()
                        automationRules.addAll(newRules)
                    }
                }
            }
        }
    }
}

/**
 * Change 8.1 + Onboarding Sections 3–7:
 * Lifecycle-aware ViewModel driving the deterministic single-device onboarding wizard.
 * Never activates any radio scan until the user explicitly chooses a Step 1 pairing mode.
 */
class OnboardingWizardViewModel(application: Application) : AndroidViewModel(application) {

    private val appCtx: Context
        get() = getApplication<Application>().applicationContext

    private val _sessionState = MutableStateFlow(SingleDeviceWizardSessionState())
    val sessionState: StateFlow<SingleDeviceWizardSessionState> = _sessionState.asStateFlow()

    var showTroubleshootingGuide by mutableStateOf(false)
    var shortCodeInput by mutableStateOf("")
    var deviceSecretOrPinInput by mutableStateOf("")
    var accountEmailInput by mutableStateOf("")
    var accountPasswordInput by mutableStateOf("")
    var accountCountryCodeInput by mutableStateOf("44")
    var isAccountPasswordVisible by mutableStateOf(false)
    var availableTuyaHomes by mutableStateOf<List<TuyaHomeSummary>>(emptyList())
    var selectedTuyaHomeId by mutableStateOf(-1L)
    var isBusy by mutableStateOf(false)

    private var activeScanJob: Job? = null
    private var stageObserverJob: Job? = null

    init {
        observeEngineTimelineOutcomes()
    }

    private fun observeEngineTimelineOutcomes() {
        stageObserverJob?.cancel()
        stageObserverJob = viewModelScope.launch {
            UniversalOnboardingEngine.onboardingSessionStateFlow.collect { engineSession ->
                val cur = _sessionState.value
                if (cur.pairingSessionId.isBlank() || engineSession.pairingSessionId != cur.pairingSessionId) {
                    return@collect
                }
                if (cur.stepIndex < 4) return@collect
                when (engineSession.currentStage) {
                    UnifiedOnboardingStage.FAILED -> {
                        isBusy = false
                        _sessionState.update {
                            it.copy(
                                currentStage = UnifiedOnboardingStage.FAILED,
                                failureTitle = engineSession.failureTitle.ifBlank { "Setup Could Not Complete" },
                                failureRemediation = engineSession.failureRemediation.ifBlank {
                                    engineSession.statusDetail.ifBlank {
                                        "Check that the device is powered on, in setup mode, and within Wi-Fi range."
                                    }
                                }
                            )
                        }
                    }
                    UnifiedOnboardingStage.COMPLETED -> {
                        isBusy = false
                        _sessionState.update {
                            it.copy(
                                currentStage = UnifiedOnboardingStage.COMPLETED,
                                statusDetail = engineSession.statusDetail.ifBlank {
                                    "Device verified and added to your dashboard."
                                }
                            )
                        }
                    }
                    UnifiedOnboardingStage.AUTHENTICATING,
                    UnifiedOnboardingStage.ACTIVATING,
                    UnifiedOnboardingStage.CLOUD_REGISTERING,
                    UnifiedOnboardingStage.BINDING_KEYS,
                    UnifiedOnboardingStage.DISCOVERING_CAPABILITIES,
                    UnifiedOnboardingStage.INITIAL_SYNC -> {
                        _sessionState.update {
                            it.copy(
                                currentStage = engineSession.currentStage,
                                statusDetail = engineSession.statusDetail.ifBlank { engineSession.currentStage.consumerSubtitle }
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun resetWizardToStep1() {
        activeScanJob?.cancel()
        isBusy = false
        showTroubleshootingGuide = false
        shortCodeInput = ""
        deviceSecretOrPinInput = ""
        if (JarvisLiveBridge.isPairingTuyaFlow.value) {
            TuyaDirectPairingManager.stopDirectPairing()
        }
        UniversalOnboardingEngine.endSingleDevicePairingSession(_sessionState.value.pairingSessionId)
        _sessionState.value = SingleDeviceWizardSessionState()
    }

    fun selectEntryModeAndStartStep2(
        mode: OnboardingEntryMode,
        tiles: MutableList<SmartTile>
    ) {
        activeScanJob?.cancel()
        showTroubleshootingGuide = false
        val sessionId = UniversalOnboardingEngine.beginSingleDevicePairingSession(mode)
        val savedSsid = TuyaDirectPairingManager.getSavedWifiSsid(appCtx)
            .ifBlank { DiscoveryRadioCoordinator.getTabletConnectedSsid(appCtx) }
        val savedPass = TuyaDirectPairingManager.getSavedWifiPassword(appCtx, savedSsid)
        val bridge = DeviceRepository.loadBridge(appCtx)
        val defaultRoom = bridge.customRooms.firstOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_ROOMS.first()

        if (mode == OnboardingEntryMode.MATTER_OR_CLOUD) {
            _sessionState.value = SingleDeviceWizardSessionState(
                pairingSessionId = sessionId,
                entryMode = mode,
                currentStage = UnifiedOnboardingStage.SELECT_DEVICE,
                stepIndex = 2,
                selectedWifiSsid = savedSsid,
                wifiPassword = savedPass,
                assignedRoomName = defaultRoom
            )
            return
        }

        _sessionState.value = SingleDeviceWizardSessionState(
            pairingSessionId = sessionId,
            entryMode = mode,
            currentStage = UnifiedOnboardingStage.SCANNING_CANDIDATES,
            stepIndex = 2,
            selectedWifiSsid = savedSsid,
            wifiPassword = savedPass,
            assignedRoomName = defaultRoom,
            tabletActiveFrequencyMhz = TuyaDirectPairingManager.getCurrentWifiFrequencyMhz(appCtx)
        )
        runCandidateDiscoveryWithTimeout(mode, tiles, sessionId)
    }

    fun retryCandidateScan(tiles: MutableList<SmartTile>) {
        val cur = _sessionState.value
        val mode = cur.entryMode ?: return
        showTroubleshootingGuide = false
        val sessionId = cur.pairingSessionId.ifBlank {
            UniversalOnboardingEngine.beginSingleDevicePairingSession(mode)
        }
        _sessionState.update {
            it.copy(
                pairingSessionId = sessionId,
                isPureEzInvertedFlow = false,
                currentStage = UnifiedOnboardingStage.SCANNING_CANDIDATES,
                stepIndex = 2,
                candidates = emptyList(),
                selectedCandidateId = null,
                failureTitle = "",
                failureRemediation = ""
            )
        }
        runCandidateDiscoveryWithTimeout(mode, tiles, sessionId)
    }

    private fun runCandidateDiscoveryWithTimeout(
        mode: OnboardingEntryMode,
        tiles: MutableList<SmartTile>,
        sessionId: String
    ) {
        activeScanJob?.cancel()
        activeScanJob = viewModelScope.launch {
            isBusy = true
            // Section 4 & Section 7: 8s BLE setup scan pass inside a strict 20s overall discovery timeout
            val found = withTimeoutOrNull(20_000L) {
                UniversalOnboardingEngine.scanCandidatesForEntryMode(
                    context = appCtx,
                    entryMode = mode,
                    tiles = tiles,
                    pairingSessionId = sessionId,
                    bleScanDurationMs = 8_000L
                )
            }.orEmpty()
            isBusy = false

            val bridge = DeviceRepository.loadBridge(appCtx)
            if (found.isNotEmpty()) {
                val top = found.first()
                val inferredRoom = inferRoomFromDeviceTitle(top.displayName, bridge.customRooms)
                    .ifBlank { _sessionState.value.assignedRoomName.ifBlank { DEFAULT_ROOMS.first() } }
                _sessionState.update {
                    it.copy(
                        candidates = found,
                        selectedCandidateId = top.identityKey,
                        assignedDeviceName = top.displayName,
                        assignedRoomName = inferredRoom,
                        currentStage = UnifiedOnboardingStage.SELECT_DEVICE,
                        stepIndex = 2
                    )
                }
            } else {
                _sessionState.update {
                    it.copy(
                        candidates = emptyList(),
                        selectedCandidateId = null,
                        currentStage = UnifiedOnboardingStage.TIMEOUT_NO_DEVICE,
                        stepIndex = 2
                    )
                }
            }
        }
    }

    fun selectSingleCandidate(candidate: ResolvedDeviceIdentity) {
        val bridge = DeviceRepository.loadBridge(appCtx)
        val inferredRoom = inferRoomFromDeviceTitle(candidate.displayName, bridge.customRooms)
            .ifBlank { _sessionState.value.assignedRoomName.ifBlank { DEFAULT_ROOMS.first() } }
        _sessionState.update {
            it.copy(
                selectedCandidateId = candidate.identityKey,
                assignedDeviceName = candidate.displayName,
                assignedRoomName = inferredRoom,
                failureTitle = "",
                failureRemediation = ""
            )
        }
    }

    /**
     * Onboarding Section 4: Pure 2.4 GHz UDP EZ fallback when no BLE setup beacon is advertised.
     * Inverts Steps 2 and 3 (collects 2.4 GHz Wi-Fi first + single-blinking-device warning) and
     * checks isTabletOn5GhzBand() so 5 GHz tablet sessions are redirected to Slow Blinking / AP Mode.
     */
    fun activatePureEzInvertedFallback() {
        showTroubleshootingGuide = false
        val activeFreq = TuyaDirectPairingManager.getCurrentWifiFrequencyMhz(appCtx)
        val on5Ghz = TuyaDirectPairingManager.isTabletOn5GhzBand(appCtx)
        _sessionState.update {
            it.copy(
                entryMode = OnboardingEntryMode.FAST_BLINKING,
                isPureEzInvertedFlow = true,
                tabletOn5GhzWarning = on5Ghz,
                tabletActiveFrequencyMhz = activeFreq,
                currentStage = UnifiedOnboardingStage.CONFIGURE_WIFI,
                stepIndex = 2,
                assignedDeviceName = it.assignedDeviceName.ifBlank { "Smart Plug" },
                failureTitle = if (on5Ghz) "Tablet Connected on 5 GHz ($activeFreq MHz)" else "",
                failureRemediation = if (on5Ghz) {
                    "Pure Wi-Fi EZ broadcast cannot cross from a 5 GHz tablet link to a 2.4 GHz smart plug. Tap 'Switch to Slow Blinking / AP Mode' below (or connect this tablet to 2.4 GHz Wi-Fi)."
                } else ""
            )
        }
        refreshWifiNetworksWithBssidValidation()
    }

    fun refreshWifiNetworksWithBssidValidation() {
        viewModelScope.launch {
            val networks = DiscoveryRadioCoordinator.scanHomeWifiNetworksWithBandValidation(appCtx)
            val cur = _sessionState.value
            val currentConnected = DiscoveryRadioCoordinator.getTabletConnectedSsid(appCtx)
            val savedVaultSsid = TuyaDirectPairingManager.getSavedWifiSsid(appCtx)
            val chosenSsid = when {
                cur.selectedWifiSsid.isNotBlank() &&
                        !DiscoveryRadioCoordinator.isDeviceSetupHotspotSsid(cur.selectedWifiSsid) ->
                    cur.selectedWifiSsid
                savedVaultSsid.isNotBlank() &&
                        networks.any { it.ssid.equals(savedVaultSsid, ignoreCase = true) && it.bandValidationStatus.isCompatibleWith24GhzHardware } ->
                    networks.first { it.ssid.equals(savedVaultSsid, ignoreCase = true) }.ssid
                currentConnected.isNotBlank() &&
                        networks.any { it.ssid.equals(currentConnected, ignoreCase = true) && it.bandValidationStatus.isCompatibleWith24GhzHardware } ->
                    networks.first { it.ssid.equals(currentConnected, ignoreCase = true) }.ssid
                else -> networks.firstOrNull { it.bandValidationStatus.isCompatibleWith24GhzHardware }?.ssid
                    ?: cur.selectedWifiSsid
            }
            val passwordForSsid = if (chosenSsid != cur.selectedWifiSsid || cur.wifiPassword.isEmpty()) {
                TuyaDirectPairingManager.getSavedWifiPassword(appCtx, chosenSsid)
            } else {
                cur.wifiPassword
            }
            val activeFreq = TuyaDirectPairingManager.getCurrentWifiFrequencyMhz(appCtx)
            _sessionState.update {
                it.copy(
                    availableWifiNetworks = networks,
                    selectedWifiSsid = chosenSsid,
                    wifiPassword = passwordForSsid,
                    tabletActiveFrequencyMhz = activeFreq,
                    tabletOn5GhzWarning = it.isPureEzInvertedFlow && TuyaDirectPairingManager.isTabletOn5GhzBand(appCtx)
                )
            }
        }
    }

    fun selectWifiSsid(ssid: String) {
        val clean = ssid.trim()
        val prefilledPass = TuyaDirectPairingManager.getSavedWifiPassword(appCtx, clean)
        _sessionState.update {
            it.copy(
                selectedWifiSsid = clean,
                wifiPassword = prefilledPass,
                failureTitle = "",
                failureRemediation = ""
            )
        }
    }

    fun updateWifiPassword(password: String) {
        DiagnosticsSanitizer.registerTransientWifiSecretForRedaction(password)
        _sessionState.update { it.copy(wifiPassword = password) }
    }

    fun toggleWifiPasswordVisibility() {
        _sessionState.update { it.copy(isWifiPasswordVisible = !it.isWifiPasswordVisible) }
    }

    fun updateAssignedNameAndRoom(name: String? = null, room: String? = null) {
        _sessionState.update {
            it.copy(
                assignedDeviceName = name ?: it.assignedDeviceName,
                assignedRoomName = room ?: it.assignedRoomName
            )
        }
    }

    fun proceedFromStep2CandidateSelection() {
        val cur = _sessionState.value
        val candidate = cur.selectedCandidate ?: return
        when (cur.entryMode) {
            OnboardingEntryMode.FAST_BLINKING,
            OnboardingEntryMode.SLOW_BLINKING_AP -> {
                if (candidate.suggestedType == TileType.MATTER_DEVICE) {
                    _sessionState.update {
                        it.copy(
                            stepIndex = 3,
                            currentStage = UnifiedOnboardingStage.SELECT_DEVICE
                        )
                    }
                } else {
                    _sessionState.update {
                        it.copy(
                            stepIndex = 3,
                            currentStage = UnifiedOnboardingStage.CONFIGURE_WIFI,
                            failureTitle = "",
                            failureRemediation = ""
                        )
                    }
                    refreshWifiNetworksWithBssidValidation()
                }
            }
            OnboardingEntryMode.ALREADY_ON_NETWORK,
            OnboardingEntryMode.MATTER_OR_CLOUD,
            null -> {
                _sessionState.update {
                    it.copy(
                        stepIndex = 3,
                        currentStage = UnifiedOnboardingStage.SELECT_DEVICE,
                        failureTitle = "",
                        failureRemediation = ""
                    )
                }
            }
        }
    }

    /**
     * Onboarding Sections 4, 5 & 6:
     * Validates 2.4 GHz BSSID frequency support, blocks 5 GHz-only networks, enforces the 5 GHz
     * active-tablet guard for pure EZ broadcast, pre-fetches cloud pairing tokens online for AP Mode
     * before advancing to the Step 4 single-device Name & Room confirmation card, or launches
     * direct BLE / Pure EZ provisioning onto Step 4.
     */
    fun proceedFromWifiCredentialsStep(
        tiles: MutableList<SmartTile>,
        scope: CoroutineScope
    ) {
        val cur = _sessionState.value
        val ssid = cur.selectedWifiSsid.trim()
        if (ssid.isEmpty() || DiscoveryRadioCoordinator.isDeviceSetupHotspotSsid(ssid)) {
            _sessionState.update {
                it.copy(
                    failureTitle = "Select Your Home 2.4 GHz Wi-Fi",
                    failureRemediation = "Choose your home 2.4 GHz Wi-Fi network (not the device setup hotspot)."
                )
            }
            return
        }

        val optionBandStatus = cur.selectedWifiOption?.bandValidationStatus
            ?: TuyaDirectPairingManager.validateSsidSupports24GhzByBssid(appCtx, ssid)
        if (optionBandStatus == WifiBandValidationStatus.BLOCKED_5GHZ_OR_6GHZ_ONLY) {
            _sessionState.update {
                it.copy(
                    failureTitle = "2.4 GHz Wi-Fi Required",
                    failureRemediation = "'$ssid' only broadcasts on 5 GHz / 6 GHz. Smart plugs, lights, and sensors contain 2.4 GHz-only Wi-Fi radios — select a 2.4 GHz or dual-band mesh network."
                )
            }
            return
        }

        val targetMode = when {
            cur.entryMode == OnboardingEntryMode.SLOW_BLINKING_AP -> TuyaPairingMode.AP_HOTSPOT
            cur.isPureEzInvertedFlow -> TuyaPairingMode.PURE_EZ_BROADCAST
            else -> TuyaPairingMode.BLE_DIRECT
        }
        val preflight = TuyaDirectPairingManager.validateProvisioningPreflight(appCtx, ssid, targetMode)
        if (!preflight.passed) {
            _sessionState.update {
                it.copy(
                    tabletOn5GhzWarning = preflight.shouldRedirectPureEzToApMode,
                    tabletActiveFrequencyMhz = preflight.tabletFrequencyMhz,
                    failureTitle = preflight.failureTitle,
                    failureRemediation = preflight.remediationMessage
                )
            }
            return
        }

        TuyaDirectPairingManager.saveWifiCredentials(appCtx, ssid, cur.wifiPassword)

        // Section 5: Slow Blinking / AP Mode pre-fetches cloud tokens online in Step 3, then shows simplified Step 4 Name/Room card
        if (cur.entryMode == OnboardingEntryMode.SLOW_BLINKING_AP) {
            viewModelScope.launch {
                isBusy = true
                val tokenRes = TuyaDirectPairingManager.prefetchCloudPairingTokenSuspend(appCtx)
                isBusy = false
                if (tokenRes.isFailure) {
                    _sessionState.update {
                        it.copy(
                            failureTitle = "Could Not Prepare Cloud Pairing Token",
                            failureRemediation = tokenRes.exceptionOrNull()?.message
                                ?: "Ensure your tablet has internet access and you are signed in to SmartLife before switching to AP Mode."
                        )
                    }
                    return@launch
                }
                val (_, token) = tokenRes.getOrThrow()
                _sessionState.update {
                    it.copy(
                        preFetchedCloudToken = token,
                        stepIndex = 4,
                        currentStage = UnifiedOnboardingStage.SELECT_DEVICE,
                        failureTitle = "",
                        failureRemediation = ""
                    )
                }
            }
            return
        }

        // Section 4: Fast Blinking (BLE_DIRECT or Pure EZ Fallback) proceeds onto Step 4 Unified Provisioning
        executeSingleDeviceOnboarding(tiles = tiles, scope = scope)
    }

    fun executeSingleDeviceOnboarding(
        tiles: MutableList<SmartTile>,
        scope: CoroutineScope,
        onOpenExternalAccountFlow: ((String) -> Unit)? = null
    ) {
        if (isBusy) return
        val cur = _sessionState.value
        val candidate = cur.selectedCandidate
        val targetStepIndex = if (cur.entryMode == OnboardingEntryMode.SLOW_BLINKING_AP) 5 else 4

        viewModelScope.launch {
            isBusy = true
            var bridge = DeviceRepository.loadBridge(appCtx)

            if (accountEmailInput.isNotBlank() && accountPasswordInput.isNotBlank() && candidate != null) {
                when (candidate.resolvedProviderEnum) {
                    ProviderId.TPLINK_KLAP -> {
                        TpLinkTapoKasaClient.saveTpLinkCredentials(appCtx, accountEmailInput.trim(), accountPasswordInput)
                        bridge = bridge.copy(tplinkEmail = accountEmailInput.trim(), tplinkPassword = accountPasswordInput)
                        DeviceRepository.saveBridge(appCtx, bridge)
                    }
                    ProviderId.EWELINK_LAN -> {
                        EWeLinkSonoffClient.saveEWeLinkCredentials(appCtx, accountEmailInput.trim(), accountPasswordInput)
                        bridge = bridge.copy(ewelinkEmail = accountEmailInput.trim(), ewelinkPassword = accountPasswordInput)
                        DeviceRepository.saveBridge(appCtx, bridge)
                    }
                    else -> Unit
                }
            }

            _sessionState.update {
                it.copy(
                    stepIndex = targetStepIndex,
                    currentStage = UnifiedOnboardingStage.AUTHENTICATING,
                    statusDetail = "Authenticating with ${cur.assignedDeviceName.ifBlank { candidate?.displayName ?: "device" }}...",
                    failureTitle = "",
                    failureRemediation = ""
                )
            }

            // Pure UDP EZ Fallback (no BLE candidate card; Step 2 Wi-Fi -> Step 4 Broadcast)
            if (cur.entryMode == OnboardingEntryMode.FAST_BLINKING && cur.isPureEzInvertedFlow) {
                val ezIdentity = ResolvedDeviceIdentity(
                    identityKey = "ez_${cur.pairingSessionId}",
                    displayName = cur.assignedDeviceName.ifBlank { "Smart Plug" },
                    suggestedRoom = cur.assignedRoomName,
                    deviceClass = DeviceClass.SWITCH,
                    suggestedType = TileType.SMARTKNIGHT_PLUG,
                    combinedConfidence = 0.95f,
                    primaryProviderId = ProviderId.TUYA_LAN.id,
                    fallbackProviderIds = listOf(ProviderId.TUYA_CLOUD.id),
                    onboardingRequirement = OnboardingRequirement.AutoProvisioningRequired(
                        beaconName = cur.assignedDeviceName.ifBlank { "Smart Plug" },
                        macOrSsid = ""
                    ),
                    mergedTransports = listOf(TransportRadioType.TUYA_LAN_6668)
                )
                val res = UniversalOnboardingEngine.onboardResolvedIdentity(
                    context = appCtx,
                    identity = ezIdentity,
                    tiles = tiles,
                    bridgeSettings = bridge,
                    scope = scope,
                    customRoomOverride = cur.assignedRoomName,
                    wifiSsid = cur.selectedWifiSsid,
                    wifiPassword = cur.wifiPassword,
                    explicitPairingModeOverride = TuyaPairingMode.PURE_EZ_BROADCAST,
                    onStatusUpdate = { msg ->
                        _sessionState.update { s -> s.copy(statusDetail = msg) }
                    }
                )
                handleOnboardingResult(res)
                return@launch
            }

            if (candidate == null) {
                isBusy = false
                _sessionState.update {
                    it.copy(
                        currentStage = UnifiedOnboardingStage.FAILED,
                        failureTitle = "No Device Selected",
                        failureRemediation = "Go back and select a single device to continue."
                    )
                }
                return@launch
            }

            // Matter Setup Code path
            if (candidate.suggestedType == TileType.MATTER_DEVICE && shortCodeInput.isNotBlank()) {
                val res = UniversalOnboardingEngine.onboardByUniversalShortCode(
                    context = appCtx,
                    rawCode = shortCodeInput,
                    customTitle = cur.assignedDeviceName.ifBlank { candidate.displayName },
                    targetRoom = cur.assignedRoomName,
                    tiles = tiles,
                    bridgeSettings = bridge,
                    canonicalGuidHint = candidate.canonicalGuid,
                    onStatusUpdate = { msg -> _sessionState.update { s -> s.copy(statusDetail = msg) } }
                )
                handleOnboardingResult(res)
                return@launch
            }

            val explicitModeOverride: TuyaPairingMode? = when (cur.entryMode) {
                OnboardingEntryMode.FAST_BLINKING -> TuyaPairingMode.BLE_DIRECT
                OnboardingEntryMode.SLOW_BLINKING_AP -> TuyaPairingMode.AP_HOTSPOT
                else -> null
            }

            val updatedTitle = cur.assignedDeviceName.ifBlank { candidate.displayName }
            val mappedIdentity = candidate.copy(
                identityKey = candidate.canonicalGuid.ifBlank { candidate.identityKey },
                displayName = updatedTitle,
                suggestedRoom = cur.assignedRoomName,
                deviceSubtype = candidate.suggestedType.toDefaultDeviceSubtype(updatedTitle),
                localKeyOrPin = deviceSecretOrPinInput.trim().ifBlank { candidate.localKeyOrPin }
            )

            val res = UniversalOnboardingEngine.onboardResolvedIdentity(
                context = appCtx,
                identity = mappedIdentity,
                tiles = tiles,
                bridgeSettings = bridge,
                scope = scope,
                customRoomOverride = cur.assignedRoomName,
                shortCodeInput = shortCodeInput,
                wifiSsid = cur.selectedWifiSsid,
                wifiPassword = cur.wifiPassword,
                explicitPairingModeOverride = explicitModeOverride,
                onOpenAccountModal = { wfKey ->
                    isBusy = false
                    onOpenExternalAccountFlow?.invoke(wfKey)
                },
                onStatusUpdate = { msg ->
                    _sessionState.update { s -> s.copy(statusDetail = msg) }
                }
            )
            handleOnboardingResult(res)
        }
    }

    private fun handleOnboardingResult(res: Result<SmartTile>) {
        isBusy = false
        if (res.isSuccess) {
            val created = res.getOrThrow()
            _sessionState.update {
                it.copy(
                    currentStage = UnifiedOnboardingStage.COMPLETED,
                    boundCanonicalGuid = created.canonicalGuid,
                    needsCapabilitySyncRetryOnly = created.needsCapabilitySync,
                    statusDetail = if (created.needsCapabilitySync) {
                        "Bound '${created.title}' securely — tap Sync Capabilities if controls need refreshing."
                    } else {
                        "Connected '${created.title}' in ${created.roomOrSubtitle.ifBlank { "your home" }}!"
                    }
                )
            }
        } else {
            val errMsg = res.exceptionOrNull()?.message.orEmpty()
            if (!errMsg.startsWith("IN_APP_ACCOUNT_WORKFLOW")) {
                _sessionState.update {
                    it.copy(
                        currentStage = UnifiedOnboardingStage.FAILED,
                        failureTitle = JarvisLiveBridge.inferFailureHeaderTitle(errMsg),
                        failureRemediation = errMsg.ifBlank { "Could not complete setup. Check device power and try again." }
                    )
                }
            }
        }
    }

    fun submitShortCodeOnboarding(
        tiles: MutableList<SmartTile>,
        onNeedsNestSignIn: (String) -> Unit
    ) {
        val code = shortCodeInput.trim()
        if (code.isEmpty() || isBusy) return
        viewModelScope.launch {
            isBusy = true
            val cur = _sessionState.value
            _sessionState.update {
                it.copy(
                    stepIndex = 4,
                    currentStage = UnifiedOnboardingStage.AUTHENTICATING,
                    statusDetail = "Commissioning device with setup code...",
                    failureTitle = "",
                    failureRemediation = ""
                )
            }
            val bridge = DeviceRepository.loadBridge(appCtx)
            val res = UniversalOnboardingEngine.onboardByUniversalShortCode(
                context = appCtx,
                rawCode = code,
                customTitle = cur.assignedDeviceName,
                targetRoom = cur.assignedRoomName,
                tiles = tiles,
                bridgeSettings = bridge,
                onStatusUpdate = { msg -> _sessionState.update { s -> s.copy(statusDetail = msg) } }
            )
            isBusy = false
            if (res.isSuccess) {
                shortCodeInput = ""
                handleOnboardingResult(res)
            } else {
                val err = res.exceptionOrNull()?.message.orEmpty()
                if (err.startsWith("NEEDS_NEST_SIGN_IN", ignoreCase = true)) {
                    onNeedsNestSignIn(code)
                } else {
                    handleOnboardingResult(res)
                }
            }
        }
    }

    /**
     * Change 6.2: Official SmartLife / Tuya account login with EU/US/CN/IN region auto-detection
     * and primary Home synchronization into ClaraSecretVault and Dashboard tiles.
     */
    fun signInAndSyncOfficialSmartLifeAccount(
        tiles: MutableList<SmartTile>,
        scope: CoroutineScope,
        onStatusBanner: (String) -> Unit
    ) {
        val emailOrPhone = accountEmailInput.trim()
        val password = accountPasswordInput
        if (emailOrPhone.isEmpty() || password.isEmpty() || isBusy) return
        viewModelScope.launch {
            isBusy = true
            _sessionState.update {
                it.copy(
                    statusDetail = "Signing in to official SmartLife account & syncing primary Home...",
                    failureTitle = "",
                    failureRemediation = ""
                )
            }
            val bridge = DeviceRepository.loadBridge(appCtx)
            val loginRes = TuyaApiClient.loginWithEmailAndPassword(
                bridge = bridge,
                emailOrPhone = emailOrPhone,
                password = password,
                countryCode = accountCountryCodeInput.ifBlank { "44" }
            )
            if (loginRes.isFailure) {
                isBusy = false
                val msg = loginRes.exceptionOrNull()?.message ?: "SmartLife sign-in failed."
                _sessionState.update {
                    it.copy(
                        failureTitle = "SmartLife Sign-In Failed",
                        failureRemediation = msg
                    )
                }
                return@launch
            }

            val homes = TuyaApiClient.listAvailableTuyaHomes().getOrDefault(emptyList())
            availableTuyaHomes = homes
            selectedTuyaHomeId = ClaraSecretVault.getTuyaSelectedHomeId(appCtx)
                ?: homes.firstOrNull()?.homeId ?: -1L

            val discoveredDevices = loginRes.getOrThrow()
            var syncedCount = 0
            for (dev in discoveredDevices) {
                val knownIp = TuyaApiClient.resolveKnownLanIp(dev.deviceId)
                val roomHint = inferRoomFromDeviceTitle(dev.name, bridge.customRooms)
                    .ifBlank { _sessionState.value.assignedRoomName }
                val identity = ResolvedDeviceIdentity(
                    identityKey = "id_sn_${dev.deviceId.lowercase(Locale.US)}",
                    displayName = dev.name,
                    suggestedRoom = roomHint,
                    deviceClass = dev.deviceClass,
                    deviceSubtype = dev.deviceSubtype,
                    suggestedType = dev.suggestedType,
                    combinedConfidence = 0.98f,
                    primaryProviderId = if (knownIp.isNotBlank()) ProviderId.TUYA_LAN.id else ProviderId.TUYA_CLOUD.id,
                    fallbackProviderIds = listOf(ProviderId.TUYA_CLOUD.id),
                    onboardingRequirement = OnboardingRequirement.ZeroTouchReady,
                    ipAddress = knownIp,
                    port = if (knownIp.isNotBlank()) 6668 else 0,
                    hardwareSerialOrNodeId = dev.deviceId,
                    cloudDeviceId = dev.deviceId,
                    localKeyOrPin = dev.localKey,
                    multiGangCount = dev.multiGangSocketCount.coerceAtLeast(1),
                    socket1Name = dev.socket1Name,
                    socket2Name = dev.socket2Name,
                    isActive = dev.isActive,
                    isSocket2Active = dev.isSocket2Active,
                    isOnline = dev.isOnline,
                    temperatureC = dev.currentTempC,
                    targetTempC = dev.targetTempC,
                    levelOrVolumePct = dev.brightnessOrBatteryPct,
                    powerWatts = dev.powerWatts,
                    voltageVolts = dev.voltageVolts,
                    currentMilliamps = dev.currentMilliamps,
                    totalEnergyKwh = dev.totalEnergyKwh,
                    hasPowerTelemetry = dev.hasPowerTelemetry,
                    capabilities = dev.capabilities
                )
                if (UniversalOnboardingEngine.onboardResolvedIdentity(
                        context = appCtx,
                        identity = identity,
                        tiles = tiles,
                        bridgeSettings = bridge,
                        scope = scope
                    ).isSuccess
                ) {
                    syncedCount++
                }
            }
            isBusy = false
            onStatusBanner("SUCCESS: Linked SmartLife account & synchronized $syncedCount device(s) with local keys!")
            _sessionState.update {
                it.copy(
                    stepIndex = 4,
                    currentStage = UnifiedOnboardingStage.COMPLETED,
                    statusDetail = "SmartLife Home linked — $syncedCount device(s) synchronized with encrypted 16-byte local keys."
                )
            }
        }
    }

    fun selectAndResyncTuyaHome(
        homeId: Long,
        tiles: MutableList<SmartTile>,
        scope: CoroutineScope,
        onStatusBanner: (String) -> Unit
    ) {
        if (homeId <= 0L || isBusy) return
        selectedTuyaHomeId = homeId
        viewModelScope.launch {
            isBusy = true
            val syncRes = TuyaApiClient.selectAndSyncPrimaryTuyaHome(appCtx, preferredHomeId = homeId)
            val bridge = DeviceRepository.loadBridge(appCtx)
            var count = 0
            for (dev in syncRes.getOrDefault(emptyList())) {
                val knownIp = TuyaApiClient.localIpByDevice[dev.deviceId].orEmpty()
                val identity = ResolvedDeviceIdentity(
                    identityKey = "id_sn_${dev.deviceId.lowercase(Locale.US)}",
                    displayName = dev.name,
                    suggestedRoom = inferRoomFromDeviceTitle(dev.name, bridge.customRooms),
                    deviceClass = dev.deviceClass,
                    deviceSubtype = dev.deviceSubtype,
                    suggestedType = dev.suggestedType,
                    combinedConfidence = 0.98f,
                    primaryProviderId = if (knownIp.isNotBlank()) ProviderId.TUYA_LAN.id else ProviderId.TUYA_CLOUD.id,
                    fallbackProviderIds = listOf(ProviderId.TUYA_CLOUD.id),
                    onboardingRequirement = OnboardingRequirement.ZeroTouchReady,
                    ipAddress = knownIp,
                    port = if (knownIp.isNotBlank()) 6668 else 0,
                    hardwareSerialOrNodeId = dev.deviceId,
                    cloudDeviceId = dev.deviceId,
                    localKeyOrPin = dev.localKey,
                    multiGangCount = dev.multiGangSocketCount.coerceAtLeast(1),
                    isActive = dev.isActive,
                    isOnline = dev.isOnline,
                    capabilities = dev.capabilities,
                    hasPowerTelemetry = dev.hasPowerTelemetry
                )
                if (UniversalOnboardingEngine.onboardResolvedIdentity(appCtx, identity, tiles, bridge, scope).isSuccess) {
                    count++
                }
            }
            isBusy = false
            onStatusBanner("Synchronized $count device(s) from selected SmartLife Home.")
        }
    }
}
