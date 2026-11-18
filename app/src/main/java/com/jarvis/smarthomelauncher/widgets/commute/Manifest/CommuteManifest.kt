package com.jarvis.smarthomelauncher.widgets.commute.Manifest

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jarvis.smarthomelauncher.core.DashboardWidgetType
import com.jarvis.smarthomelauncher.ui.widgets.WidgetRegistry
import com.jarvis.smarthomelauncher.widgets.commute.Popup.CommuteHubPopup
import com.jarvis.smarthomelauncher.widgets.commute.Widget.CommuteWidget

object CommuteManifest {
    fun register() {
        WidgetRegistry.register(DashboardWidgetType.COMMUTE) { context ->
            var showPopup by remember { mutableStateOf(false) }

            CommuteWidget(
                modifier = Modifier.fillMaxSize(),
                onOpenCommuteHub = { 
                    showPopup = true 
                }
            )

            if (showPopup) {
                CommuteHubPopup(
                    onDismiss = { showPopup = false }
                )
            }
        }
    }
}
