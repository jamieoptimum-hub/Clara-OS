package com.jarvis.smarthomelauncher.widgets.football.Manifest

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jarvis.smarthomelauncher.core.ClaraInAppMusicPlayer
import com.jarvis.smarthomelauncher.core.ClaraThemeManager
import com.jarvis.smarthomelauncher.core.DashboardWidgetType
import com.jarvis.smarthomelauncher.ui.widgets.WidgetContext
import com.jarvis.smarthomelauncher.ui.widgets.WidgetRegistry
import com.jarvis.smarthomelauncher.widgets.football.Models.*
import com.jarvis.smarthomelauncher.widgets.football.Popup.FootballHubPopup
import com.jarvis.smarthomelauncher.widgets.football.Repository.FootballAutomationDispatchEvent
import com.jarvis.smarthomelauncher.widgets.football.Repository.FootballCalendarTimelineEntry
import com.jarvis.smarthomelauncher.widgets.football.Repository.FootballRepository
import com.jarvis.smarthomelauncher.widgets.football.Settings.FootballSettingsDialog
import com.jarvis.smarthomelauncher.widgets.football.Widget.FootballWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * CWAS Rule 1, Rule 12 & Rule 14: Self-contained manifest and registration entry point for Football Hub v3.0.
 * Automatically discovered by DefaultWidgetsRegistrar without modifying files outside widgets/football/.
 */
object FootballManifest {

    val descriptor = FootballWidgetManifestDescriptor()

    const val MANIFEST_JSON: String = """
        {
          "id": "football-hub",
          "name": "Football Hub",
          "feature": "Football Hub Widget",
          "application": "Clara Smart Home",
          "version": "3.0",
          "description": "Provide a premium football dashboard experience that allows users to follow favourite clubs, competitions and matches from across a room while providing a deep football hub experience when opened.",
          "supportsPopup": true,
          "supportsSettings": true,
          "supportsNotifications": true,
          "supportsAutomations": true,
          "supportsVoiceAssistant": true,
          "popup": {
            "name": "Football Hub",
            "launchMode": "popup",
            "width": "95%",
            "height": "92%"
          }
        }
    """

    /**
     * Exposes Football Hub's household calendar timeline stream for ecosystem cooperation (CWAS Rule 19).
     */
    fun timelineEntriesFlow(context: Context): StateFlow<List<FootballCalendarTimelineEntry>> {
        return FootballRepository.getInstance(context).timelineEntries
    }

    /**
     * Exposes natural-language Football voice query handling to ClaraVoiceAssistantManager.
     */
    fun handleVoiceCommand(context: Context, utterance: String): FootballVoiceCommandResponse {
        return FootballRepository.getInstance(context).handleVoiceCommand(utterance)
    }

    /**
     * Registers Football Hub v3.0 with Clara's central WidgetRegistry.
     */
    fun register() {
        WidgetRegistry.register(DashboardWidgetType.FOOTBALL) { widgetContext ->
            val androidContext = LocalContext.current
            val coroutineScope = rememberCoroutineScope()
            val repository = remember(androidContext) {
                FootballRepository.getInstance(androidContext.applicationContext)
            }
            val uiState by repository.uiState.collectAsState()

            var showHubPopup by remember { mutableStateOf(false) }
            var hubInitialTab by remember { mutableStateOf(FootballHubTab.OVERVIEW) }
            var hubInitialFixture by remember { mutableStateOf<FootballFixture?>(null) }
            var showSettingsPopup by remember { mutableStateOf(false) }

            // Bind Match-Day Smart Home Automation Events to Clara's live device & audio context
            LaunchedEffect(repository, widgetContext) {
                repository.automationEvents.collect { event ->
                    executeSmartHomeAutomation(
                        event = event,
                        androidContext = androidContext,
                        widgetContext = widgetContext,
                        scope = coroutineScope,
                        onOpenLiveHub = { liveFixture ->
                            hubInitialTab = FootballHubTab.LIVE
                            hubInitialFixture = liveFixture
                            showHubPopup = true
                        }
                    )
                }
            }

            FootballWidget(
                repository = repository,
                onOpenFootballHub = { clickedFixture ->
                    val currentMode = (uiState as? FootballWidgetUiState.Ready)?.snapshot?.displayMode
                    hubInitialTab = if (clickedFixture == null && currentMode == FootballWidgetDisplayMode.LIVE) {
                        FootballHubTab.LIVE
                    } else {
                        FootballHubTab.OVERVIEW
                    }
                    hubInitialFixture = clickedFixture
                    showHubPopup = true
                },
                onOpenSettings = {
                    showSettingsPopup = true
                },
                modifier = Modifier.fillMaxSize()
            )

            if (showHubPopup) {
                FootballHubPopup(
                    repository = repository,
                    initialTab = hubInitialTab,
                    initialFixture = hubInitialFixture,
                    onDismiss = {
                        showHubPopup = false
                        hubInitialFixture = null
                    }
                )
            }

            if (showSettingsPopup) {
                val readySnapshot = (uiState as? FootballWidgetUiState.Ready)?.snapshot
                FootballSettingsDialog(
                    repository = repository,
                    favouriteTeams = readySnapshot?.favouriteTeams.orEmpty(),
                    preferences = readySnapshot?.preferences ?: repository.getPreferencesSync(),
                    onDismiss = { showSettingsPopup = false }
                )
            }
        }
    }

    private fun executeSmartHomeAutomation(
        event: FootballAutomationDispatchEvent,
        androidContext: Context,
        widgetContext: WidgetContext,
        scope: CoroutineScope,
        onOpenLiveHub: (FootballFixture) -> Unit
    ) {
        when (event.hook) {
            FootballAutomationHook.TURN_TV_ON_AT_KICKOFF -> {
                widgetContext.allTiles
                    .filter { tile ->
                        tile.type.name.contains("TV", ignoreCase = true) ||
                                tile.deviceSubtype.name.contains("TV", ignoreCase = true)
                    }
                    .forEach { tvTile ->
                        if (!tvTile.isActive) {
                            widgetContext.onUpdateTile(tvTile.copy(isActive = true))
                        }
                    }
            }

            FootballAutomationHook.OPEN_BROADCASTER_APP -> {
                val lower = event.fixture.broadcast.displayChannel.lowercase(Locale.UK)
                val candidates = when {
                    lower.contains("sky") -> listOf("com.bskyb.skygo", "com.bskyb.sportnews")
                    lower.contains("tnt") || lower.contains("discovery") ->
                        listOf("com.discovery.plus", "uk.co.bt.sport")
                    lower.contains("bbc") || lower.contains("iplayer") ->
                        listOf("bbc.iplayer.android")
                    lower.contains("itv") -> listOf("air.ITVMobilePlayer")
                    lower.contains("prime") || lower.contains("amazon") ->
                        listOf("com.amazon.avod.thirdpartyclient")
                    lower.contains("apple") || lower.contains("mls") ->
                        listOf("com.apple.atve.androidtv.appletv")
                    else -> emptyList()
                }
                for (pkg in candidates) {
                    val intent = androidContext.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { androidContext.startActivity(intent) }
                        break
                    }
                }
            }

            FootballAutomationHook.CHANGE_LIGHTS_TO_TEAM_COLOURS -> {
                runCatching {
                    val parsedArgb = android.graphics.Color.parseColor(event.primaryColorHex).toLong() and 0xFFFFFFFFL
                    ClaraThemeManager.customAccentColorArgb = parsedArgb
                }
                widgetContext.allTiles
                    .filter { tile ->
                        tile.type.name.contains("LIGHT", ignoreCase = true) ||
                                tile.deviceSubtype.name.contains("LIGHT", ignoreCase = true)
                    }
                    .forEach { lightTile ->
                        if (!lightTile.isActive) {
                            widgetContext.onUpdateTile(lightTile.copy(isActive = true))
                        }
                    }
            }

            FootballAutomationHook.PAUSE_MUSIC_AT_KICKOFF -> {
                ClaraInAppMusicPlayer.stopRadioPlayback(scope)
                if (widgetContext.isMusicPlaying) {
                    val audioManager = androidContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (audioManager != null) {
                        ClaraInAppMusicPlayer.togglePlayPause(audioManager, scope)
                    }
                }
            }

            FootballAutomationHook.SWITCH_DASHBOARD_LIVE_MODE -> {
                onOpenLiveHub(event.fixture)
            }

            FootballAutomationHook.ANNOUNCE_LINEUP_RELEASE -> {
                // Spoken announcement is dispatched directly by FootballRepository's TTS engine
            }
        }
    }
}