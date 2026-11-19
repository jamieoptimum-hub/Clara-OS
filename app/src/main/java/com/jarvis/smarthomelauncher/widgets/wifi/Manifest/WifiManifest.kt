package com.jarvis.smarthomelauncher.widgets.wifi.Manifest

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jarvis.smarthomelauncher.core.DashboardWidgetType
import com.jarvis.smarthomelauncher.core.UserRole
import com.jarvis.smarthomelauncher.integrations.JarvisLiveBridge
import com.jarvis.smarthomelauncher.ui.widgets.WidgetContext
import com.jarvis.smarthomelauncher.ui.widgets.WidgetRegistry
import com.jarvis.smarthomelauncher.widgets.wifi.Models.*
import com.jarvis.smarthomelauncher.widgets.wifi.Popup.WifiHubPopup
import com.jarvis.smarthomelauncher.widgets.wifi.Repository.WifiAutomationDispatchEvent
import com.jarvis.smarthomelauncher.widgets.wifi.Repository.WifiRepository
import com.jarvis.smarthomelauncher.widgets.wifi.Settings.WifiSettingsDialog
import com.jarvis.smarthomelauncher.widgets.wifi.Widget.WifiWidget

/**
 * CWAS Rule 1, Rule 12 & Rule 14: Self-contained manifest and registration entry point for Guest WiFi v1.0.
 * Automatically discovered by DefaultWidgetsRegistrar without modifying files outside widgets/wifi/.
 */
object WifiManifest {

    val descriptor = WifiWidgetManifestDescriptor()

    const val MANIFEST_JSON: String = """
        {
          "id": "guest-wifi",
          "name": "Guest WiFi",
          "feature": "Guest WiFi Widget",
          "application": "Clara Smart Home",
          "version": "1.0",
          "description": "Allow guests to quickly connect to the household WiFi network using a QR code without requiring verbal password sharing.",
          "supportsPopup": true,
          "supportsSettings": true,
          "supportsNotifications": true,
          "supportsAutomations": true,
          "supportsVoiceAssistant": true,
          "popup": {
            "name": "Guest WiFi",
            "launchMode": "popup",
            "widthPercent": 60,
            "heightPercent": 70
          }
        }
    """

    /**
     * Exposes natural-language Guest WiFi voice query handling to ClaraVoiceAssistantManager.
     */
    fun handleVoiceCommand(context: Context, utterance: String): WifiVoiceCommandResponse {
        return WifiRepository.getInstance(context).handleVoiceCommand(utterance)
    }

    /**
     * Registers Guest WiFi v1.0 with Clara's central WidgetRegistry.
     */
    fun register() {
        WidgetRegistry.register(DashboardWidgetType.WIFI) { widgetContext ->
            val androidContext = LocalContext.current
            val repository = remember(androidContext) {
                WifiRepository.getInstance(androidContext.applicationContext)
            }
            val discoveredCastDevices by JarvisLiveBridge.discoveredCastDevicesFlow.collectAsState()

            var showHubPopup by remember { mutableStateOf(false) }
            var hubInitialSection by remember { mutableStateOf(WifiPopupSection.QR_AND_CREDENTIALS) }
            var showSettingsPopup by remember { mutableStateOf(false) }

            // Feed known household smart-home IPs into WifiRepository so Guest Isolation
            // separates permanent smart-home endpoints from visitor devices.
            LaunchedEffect(widgetContext.bridgeSettings, discoveredCastDevices) {
                val bridge = widgetContext.bridgeSettings
                val knownIps = buildSet {
                    add(bridge.lytmiIpAddress)
                    add(bridge.goveeIpAddress)
                    add(bridge.twinklyIpAddress)
                    add(bridge.powerwallHostIp)
                    add(bridge.teslaWallConnectorIp)
                    addAll(bridge.doorbellChimeTargetIps)
                    discoveredCastDevices.forEach { add(it.ipAddress) }
                }.map { it.substringBefore(":").trim() }
                    .filter { it.isNotBlank() }
                    .toSet()

                repository.updateKnownSmartHomeEndpoints(
                    ips = knownIps,
                    macs = emptySet()
                )
            }

            // Bind Guest WiFi Security & Automation hooks to Clara's live BridgeSettings
            LaunchedEffect(repository, widgetContext) {
                repository.automationEvents.collect { event ->
                    executeWifiAutomation(
                        event = event,
                        widgetContext = widgetContext
                    )
                }
            }

            WifiWidget(
                repository = repository,
                onOpenGuestWifiPopup = {
                    hubInitialSection = WifiPopupSection.QR_AND_CREDENTIALS
                    showHubPopup = true
                },
                onOpenSettings = {
                    showSettingsPopup = true
                },
                modifier = Modifier.fillMaxSize()
            )

            if (showHubPopup) {
                WifiHubPopup(
                    repository = repository,
                    initialSection = hubInitialSection,
                    onDismiss = { showHubPopup = false }
                )
            }

            if (showSettingsPopup) {
                WifiSettingsDialog(
                    repository = repository,
                    onDismiss = { showSettingsPopup = false }
                )
            }
        }
    }

    private fun executeWifiAutomation(
        event: WifiAutomationDispatchEvent,
        widgetContext: WidgetContext
    ) {
        when (event.hook) {
            WifiAutomationHook.ENABLE_GUEST_ROLE_WHEN_ISOLATED -> {
                val currentBridge = widgetContext.bridgeSettings
                if (currentBridge.activeUserRole != UserRole.GUEST &&
                    currentBridge.requirePinForAdminAndVehicles
                ) {
                    widgetContext.onUpdateBridgeSettings(
                        currentBridge.copy(activeUserRole = UserRole.GUEST)
                    )
                }
            }

            WifiAutomationHook.ANNOUNCE_NEW_GUEST_TTS,
            WifiAutomationHook.AUTO_ROTATE_ON_EXPIRY -> {
                // Handled directly inside WifiRepository (TTS announcement & ClaraSecretVault rotation)
            }
        }
    }
}