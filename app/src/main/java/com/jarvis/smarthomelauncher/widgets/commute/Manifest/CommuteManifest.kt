package com.jarvis.smarthomelauncher.widgets.commute.Manifest

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jarvis.smarthomelauncher.core.DashboardWidgetType
import com.jarvis.smarthomelauncher.ui.widgets.WidgetContext
import com.jarvis.smarthomelauncher.ui.widgets.WidgetRegistry
import com.jarvis.smarthomelauncher.widgets.commute.Models.*
import com.jarvis.smarthomelauncher.widgets.commute.Popup.CommuteHubPopup
import com.jarvis.smarthomelauncher.widgets.commute.Repository.CommuteAutomationDispatchEvent
import com.jarvis.smarthomelauncher.widgets.commute.Repository.CommuteRepository
import com.jarvis.smarthomelauncher.widgets.commute.Settings.CommuteSettingsDialog
import com.jarvis.smarthomelauncher.widgets.commute.Widget.CommuteWidget
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId

/**
 * CWAS Rule 1, Rule 12, Rule 14 & Rule 19: Self-contained manifest and registration entry point
 * for Commute Hub v2.0. Discovered automatically via DefaultWidgetsRegistrar without modifying
 * code outside widgets/commute/.
 */
object CommuteManifest {

    val descriptor = CommuteWidgetManifestDescriptor()

    const val MANIFEST_JSON: String = """
        {
          "id": "commute-hub",
          "name": "Commute Hub",
          "feature": "Commute Hub",
          "application": "Clara Smart Home",
          "version": "2.0",
          "description": "Provide a personalised transport and commute experience allowing users to quickly assess travel conditions, departure times, delays and disruptions before leaving home.",
          "supportsPopup": true,
          "supportsSettings": true,
          "supportsNotifications": true,
          "supportsAutomations": true,
          "supportsVoiceAssistant": true,
          "popup": {
            "name": "Commute Hub",
            "launchMode": "popup",
            "width": "90%",
            "height": "92%"
          }
        }
    """

    /**
     * Exposes real-time Leave-By calculations to Clara's household ecosystem (CWAS Rule 19).
     */
    fun leaveByCalculationsFlow(context: Context): StateFlow<List<CommuteLeaveByExport>> {
        return CommuteRepository.getInstance(context).leaveByExports
    }

    /**
     * Exposes natural-language Commute voice command handling to ClaraVoiceAssistantManager.
     */
    fun handleVoiceCommand(context: Context, utterance: String): CommuteVoiceCommandResponse {
        return CommuteRepository.getInstance(context).handleVoiceCommand(utterance)
    }

    /**
     * Registers Commute Hub v2.0 with Clara's central WidgetRegistry.
     */
    fun register() {
        WidgetRegistry.register(DashboardWidgetType.COMMUTE) { widgetContext ->
            val androidContext = LocalContext.current
            val repository = remember(androidContext) {
                CommuteRepository.getInstance(androidContext.applicationContext)
            }
            val uiState by repository.uiState.collectAsState()

            var showHubPopup by remember { mutableStateOf(false) }
            var hubInitialTab by remember { mutableStateOf(CommuteHubTab.OVERVIEW) }
            var showSettingsPopup by remember { mutableStateOf(false) }

            // Bind Commute Smart Home Automations to Clara's live dashboard & system services
            LaunchedEffect(repository, widgetContext) {
                repository.automationEvents.collect { event ->
                    executeCommuteAutomation(
                        event = event,
                        androidContext = androidContext,
                        widgetContext = widgetContext,
                        onOpenHubTab = { targetTab ->
                            hubInitialTab = targetTab
                            showHubPopup = true
                        }
                    )
                }
            }

            CommuteWidget(
                repository = repository,
                onOpenCommuteHub = { requestedTab ->
                    val currentMode = (uiState as? CommuteWidgetUiState.Ready)?.snapshot?.displayMode
                    hubInitialTab = requestedTab ?: if (currentMode == CommuteWidgetDisplayMode.DISRUPTION) {
                        CommuteHubTab.DISRUPTIONS
                    } else {
                        CommuteHubTab.OVERVIEW
                    }
                    showHubPopup = true
                },
                onOpenSettings = {
                    showSettingsPopup = true
                },
                modifier = Modifier.fillMaxSize()
            )

            if (showHubPopup) {
                CommuteHubPopup(
                    repository = repository,
                    initialTab = hubInitialTab,
                    onDismiss = { showHubPopup = false }
                )
            }

            if (showSettingsPopup) {
                CommuteSettingsDialog(
                    repository = repository,
                    onDismiss = { showSettingsPopup = false }
                )
            }
        }
    }

    private fun executeCommuteAutomation(
        event: CommuteAutomationDispatchEvent,
        androidContext: Context,
        @Suppress("UNUSED_PARAMETER") widgetContext: WidgetContext,
        onOpenHubTab: (CommuteHubTab) -> Unit
    ) {
        when (event.hook) {
            CommuteAutomationHook.OPEN_WEEKDAY_MORNING_DASHBOARD -> {
                onOpenHubTab(CommuteHubTab.OVERVIEW)
            }

            CommuteAutomationHook.ADJUST_ALARM_FOR_DELAYS -> {
                val svc = event.service ?: return
                val walkMins = event.route?.walkToStationMinutes ?: 8
                // Schedule an updated wake/departure alarm 25 minutes + walk time prior to the delayed train
                val adjustedAlarmEpochMs = svc.expectedDepartureEpochMs - ((walkMins + 25) * 60_000L)
                if (adjustedAlarmEpochMs > System.currentTimeMillis()) {
                    val alarmZdt = Instant.ofEpochMilli(adjustedAlarmEpochMs).atZone(ZoneId.systemDefault())
                    runCatching {
                        val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(AlarmClock.EXTRA_HOUR, alarmZdt.hour)
                            putExtra(AlarmClock.EXTRA_MINUTES, alarmZdt.minute)
                            putExtra(
                                AlarmClock.EXTRA_MESSAGE,
                                "Clara Commute (${svc.expectedDepartureTime} to ${svc.destinationName})"
                            )
                            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        androidContext.startActivity(alarmIntent)
                    }
                }
            }

            CommuteAutomationHook.NOTIFY_DELAY_OVER_10_MIN,
            CommuteAutomationHook.ANNOUNCE_PLATFORM_CHANGES,
            CommuteAutomationHook.DISPLAY_DISRUPTIONS_ON_DASHBOARD -> {
                // Spoken TTS announcements, system notifications, and dashboard Disruption Mode
                // overrides are executed directly by CommuteRepository's state & TTS engine.
            }
        }
    }
}