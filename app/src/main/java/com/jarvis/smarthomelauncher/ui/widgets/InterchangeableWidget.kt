package com.jarvis.smarthomelauncher.ui.widgets

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import androidx.compose.runtime.Composable

/**
 * Universal Interchangeable Dashboard Widget Renderer:
 * Allows any of the dashboard card slots to render any `DashboardWidgetType` chosen by the user.
 */
@Composable
fun RenderInterchangeableDashboardWidget(
    widgetType: DashboardWidgetType,
    allTiles: List<SmartTile>,
    automationRules: List<AutomationRule>,
    bridgeSettings: BridgeSettings,
    isEditMode: Boolean,
    isMusicPlaying: Boolean,
    currentTrack: String,
    masterMusicVolume: Float,
    onUpdateTile: (SmartTile) -> Unit,
    onUpdateMasterMusicVolume: (Float) -> Unit,
    onActivateHeroDial: (SmartTile) -> Unit,
    onSelectSecurityMode: (SecurityCenterMode) -> Unit,
    onOpenSetupWizard: () -> Unit,
    onOpenCamerasView: () -> Unit,
    onOpenRoutinesView: () -> Unit,
    onToggleEditMode: () -> Unit,
    onUpdateBridgeSettings: (BridgeSettings) -> Unit = {},
    defaultSlotFallbackContent: @Composable () -> Unit
) {
    val context = WidgetContext(
        allTiles = allTiles,
        automationRules = automationRules,
        bridgeSettings = bridgeSettings,
        isEditMode = isEditMode,
        isMusicPlaying = isMusicPlaying,
        currentTrack = currentTrack,
        masterMusicVolume = masterMusicVolume,
        onUpdateTile = onUpdateTile,
        onUpdateMasterMusicVolume = onUpdateMasterMusicVolume,
        onActivateHeroDial = onActivateHeroDial,
        onSelectSecurityMode = onSelectSecurityMode,
        onOpenSetupWizard = onOpenSetupWizard,
        onOpenCamerasView = onOpenCamerasView,
        onOpenRoutinesView = onOpenRoutinesView,
        onToggleEditMode = onToggleEditMode,
        onUpdateBridgeSettings = onUpdateBridgeSettings
    )

    WidgetRegistry.RenderWidget(
        type = widgetType,
        context = context,
        fallback = defaultSlotFallbackContent
    )
}
