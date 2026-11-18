package com.jarvis.smarthomelauncher.ui.widgets

import androidx.compose.runtime.Composable
import com.jarvis.smarthomelauncher.core.DashboardWidgetType

/**
 * Central registry for the plug-and-play widget architecture.
 */
object WidgetRegistry {
    private val widgets = mutableMapOf<DashboardWidgetType, @Composable (WidgetContext) -> Unit>()

    /**
     * Registers a renderer for a specific dashboard widget type.
     */
    fun register(type: DashboardWidgetType, renderer: @Composable (WidgetContext) -> Unit) {
        widgets[type] = renderer
    }

    /**
     * Renders a registered widget type, or falls back to the provided content if not registered.
     */
    @Composable
    fun RenderWidget(
        type: DashboardWidgetType,
        context: WidgetContext,
        fallback: @Composable () -> Unit
    ) {
        val renderer = widgets[type]
        if (renderer != null) {
            renderer(context)
        } else {
            fallback()
        }
    }
}
