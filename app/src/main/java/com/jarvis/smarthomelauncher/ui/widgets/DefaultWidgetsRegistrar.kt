package com.jarvis.smarthomelauncher.ui.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.rememberCoroutineScope
import com.jarvis.smarthomelauncher.core.DashboardWidgetType
import com.jarvis.smarthomelauncher.core.DeviceClass
import com.jarvis.smarthomelauncher.core.DeviceSubtype
import com.jarvis.smarthomelauncher.core.ServiceClass
import com.jarvis.smarthomelauncher.core.SmartTile
import com.jarvis.smarthomelauncher.core.TileType
import com.jarvis.smarthomelauncher.core.ClaraInAppMusicPlayer
import com.jarvis.smarthomelauncher.SfHouseSunkenTileContainer
import com.jarvis.smarthomelauncher.GhMusicPlayerContent

object DefaultWidgetsRegistrar {
    fun initialize() {
        WidgetRegistry.register(DashboardWidgetType.WEATHER) { context ->
            SfHouseWeatherCard(
                isEditMode = context.isEditMode,
                onToggleEditMode = context.onToggleEditMode,
                modifier = Modifier.fillMaxSize()
            )
        }

        WidgetRegistry.register(DashboardWidgetType.WEATHER_TODAY) { context ->
            SfHouseTodayWeatherCard(
                isEditMode = context.isEditMode,
                onToggleEditMode = context.onToggleEditMode,
                modifier = Modifier.fillMaxSize()
            )
        }

        WidgetRegistry.register(DashboardWidgetType.VOLUME_SLIDERS) { context ->
            SfHouseVolumeSlidersBox(
                allTiles = context.allTiles,
                masterMusicVolume = context.masterMusicVolume,
                onUpdateTile = context.onUpdateTile,
                onUpdateMasterMusicVolume = context.onUpdateMasterMusicVolume,
                onActivateHeroDial = context.onActivateHeroDial,
                modifier = Modifier.fillMaxSize()
            )
        }

        WidgetRegistry.register(DashboardWidgetType.VEHICLES) { context ->
            SfHouseVehiclesGarageCard(
                allTiles = context.allTiles,
                onUpdateTile = context.onUpdateTile,
                onActivateHeroDial = context.onActivateHeroDial,
                onOpenSetupWizard = context.onOpenSetupWizard,
                modifier = Modifier.fillMaxSize()
            )
        }

        WidgetRegistry.register(DashboardWidgetType.SECURITY_CENTER) { context ->
            SfHouseSecurityCenterCard(
                bridgeSettings = context.bridgeSettings,
                allTiles = context.allTiles,
                onSelectSecurityMode = context.onSelectSecurityMode,
                onOpenCamerasView = context.onOpenCamerasView,
                modifier = Modifier.fillMaxSize()
            )
        }

        WidgetRegistry.register(DashboardWidgetType.CALENDAR) { context ->
        }

        com.jarvis.smarthomelauncher.widgets.football.Manifest.FootballManifest.register()
        com.jarvis.smarthomelauncher.widgets.radio.Manifest.RadioManifest.register()
        com.jarvis.smarthomelauncher.widgets.commute.Manifest.CommuteManifest.register()
        com.jarvis.smarthomelauncher.widgets.wifi.Manifest.WifiManifest.register()

        WidgetRegistry.register(DashboardWidgetType.TV_TRACKER) { context ->
        }

        WidgetRegistry.register(DashboardWidgetType.HOME_HEALTH) { context ->
            SfHouseHomeHealthCard(
                onOpenSettingsDiagnostics = context.onOpenSetupWizard,
                modifier = Modifier.fillMaxSize()
            )
        }

        WidgetRegistry.register(DashboardWidgetType.ENERGY) { context ->
            val energyTile = remember(context.allTiles) {
                context.allTiles.firstOrNull { it.type == TileType.ENERGY_MONITOR }
                    ?: SmartTile(title = "Energy Saving", type = TileType.ENERGY_MONITOR)
            }
            SfHouseSunkenTileContainer(
                outerCornerRadius = 22.dp,
                innerCornerRadius = 16.dp,
                sunkenInsetPadding = 6.dp,
                modifier = Modifier.fillMaxSize()
            ) {
                GhEnergyMonitorTileContent(
                    tile = energyTile,
                    allTiles = context.allTiles,
                    onOpenDetailModal = context.onOpenSetupWizard
                )
            }
        }

        WidgetRegistry.register(DashboardWidgetType.MEDIA) { context ->
            val localCtx = LocalContext.current
            val scope = rememberCoroutineScope()
            val audioManager = remember { localCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

            SfHouseSunkenTileContainer(
                outerCornerRadius = 22.dp,
                innerCornerRadius = 16.dp,
                sunkenInsetPadding = 6.dp,
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    GhMusicPlayerContent(
                        provider = context.bridgeSettings.musicProvider,
                        isPlaying = context.isMusicPlaying,
                        track = context.currentTrack,
                        volume = context.masterMusicVolume,
                        onVolumeChange = context.onUpdateMasterMusicVolume,
                        onOpenPopupPlayer = context.onOpenSetupWizard,
                        onTogglePlay = { ClaraInAppMusicPlayer.togglePlayPause(audioManager, scope) },
                        onSkip = { ClaraInAppMusicPlayer.skipToNext(audioManager, scope) },
                        onPrevious = { ClaraInAppMusicPlayer.skipToPrevious(audioManager, scope) }
                    )
                }
            }
        }
    }
}
