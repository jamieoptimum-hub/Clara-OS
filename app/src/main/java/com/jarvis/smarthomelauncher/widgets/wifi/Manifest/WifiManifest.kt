package com.jarvis.smarthomelauncher.widgets.wifi.Manifest

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jarvis.smarthomelauncher.core.DashboardWidgetType
import com.jarvis.smarthomelauncher.ui.widgets.WidgetRegistry
import com.jarvis.smarthomelauncher.widgets.wifi.Popup.WifiHubPopup
import com.jarvis.smarthomelauncher.widgets.wifi.Widget.WifiWidget

object WifiManifest {
    fun register() {
        WidgetRegistry.register(DashboardWidgetType.WIFI) { context ->
            var showPopup by remember { mutableStateOf(false) }

            WifiWidget(
                modifier = Modifier.fillMaxSize(),
                onOpenWifiHub = { 
                    showPopup = true 
                }
            )

            if (showPopup) {
                WifiHubPopup(
                    onDismiss = { showPopup = false }
                )
            }
        }
    }
}
