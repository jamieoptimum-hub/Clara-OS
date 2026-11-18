package com.jarvis.smarthomelauncher.widgets.football.Manifest

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jarvis.smarthomelauncher.core.DashboardWidgetType
import com.jarvis.smarthomelauncher.ui.widgets.WidgetRegistry
import com.jarvis.smarthomelauncher.widgets.football.Popup.FootballHubPopup
import com.jarvis.smarthomelauncher.widgets.football.Widget.FootballWidget

object FootballManifest {
    fun register() {
        WidgetRegistry.register(DashboardWidgetType.FOOTBALL) { context ->
            var showPopup by remember { mutableStateOf(false) }

            FootballWidget(
                modifier = Modifier.fillMaxSize(),
                apiKey = "8973b1847e923e2dc40d9b4b0e5bd7fa", // Using a dummy placeholder or actual key if user provided
                onOpenFootballHub = { 
                    showPopup = true 
                }
            )

            if (showPopup) {
                FootballHubPopup(
                    apiKey = "8973b1847e923e2dc40d9b4b0e5bd7fa",
                    onDismiss = { showPopup = false }
                )
            }
        }
    }
}
