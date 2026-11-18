package com.jarvis.smarthomelauncher.widgets.radio.Manifest

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jarvis.smarthomelauncher.core.DashboardWidgetType
import com.jarvis.smarthomelauncher.ui.widgets.WidgetRegistry
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioWidgetMetadata
import com.jarvis.smarthomelauncher.widgets.radio.Popup.RadioHubPopup
import com.jarvis.smarthomelauncher.widgets.radio.Widget.RadioWidget

object RadioManifest {
    val metadata = RadioWidgetMetadata()

    fun register() {
        WidgetRegistry.register(DashboardWidgetType.RADIO) { context ->
            var showPopup by remember { mutableStateOf(false) }

            RadioWidget(
                modifier = Modifier.fillMaxSize(),
                isEditMode = context.isEditMode,
                onOpenRadioHub = {
                    if (!context.isEditMode) {
                        showPopup = true
                    }
                }
            )

            if (showPopup) {
                RadioHubPopup(
                    widgetContext = context,
                    onDismiss = { showPopup = false }
                )
            }
        }
    }
}