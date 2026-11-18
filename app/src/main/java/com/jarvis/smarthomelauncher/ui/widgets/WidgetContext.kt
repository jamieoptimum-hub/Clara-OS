package com.jarvis.smarthomelauncher.ui.widgets

import com.jarvis.smarthomelauncher.core.AutomationRule
import com.jarvis.smarthomelauncher.core.BridgeSettings
import com.jarvis.smarthomelauncher.core.SecurityCenterMode
import com.jarvis.smarthomelauncher.core.SmartTile

/**
 * Context passed to widgets in the plug-and-play architecture, encapsulating common dependencies.
 */
data class WidgetContext(
    val allTiles: List<SmartTile>,
    val automationRules: List<AutomationRule>,
    val bridgeSettings: BridgeSettings,
    val isEditMode: Boolean,
    val isMusicPlaying: Boolean,
    val currentTrack: String,
    val masterMusicVolume: Float,
    val onUpdateTile: (SmartTile) -> Unit,
    val onUpdateMasterMusicVolume: (Float) -> Unit,
    val onActivateHeroDial: (SmartTile) -> Unit,
    val onSelectSecurityMode: (SecurityCenterMode) -> Unit,
    val onOpenSetupWizard: () -> Unit,
    val onOpenCamerasView: () -> Unit,
    val onOpenRoutinesView: () -> Unit,
    val onToggleEditMode: () -> Unit,
    val onUpdateBridgeSettings: (BridgeSettings) -> Unit = {}
)
