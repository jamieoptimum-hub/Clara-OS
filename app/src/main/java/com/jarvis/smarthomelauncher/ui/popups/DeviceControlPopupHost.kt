package com.jarvis.smarthomelauncher.ui.popups

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.smarthomelauncher.core.*

@Composable
fun DeviceControlPopupHost(
    tile: SmartTile,
    bridgeSettings: BridgeSettings,
    availableRooms: List<String>,
    onUpdateTile: (SmartTile) -> Unit,
    @Suppress("UNUSED_PARAMETER") onUpdateBridgeSettings: (BridgeSettings) -> Unit,
    onAssignRoom: (String) -> Unit,
    onRequestCreateNewRoom: () -> Unit,
    onUserInteraction: () -> Unit,
    onClose: () -> Unit,
    onExpandCameraFullScreen: () -> Unit,
    onOpenFullDetailsModal: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        ModularPopupContainer(
            tile = tile,
            availableRooms = availableRooms,
            onAssignRoom = onAssignRoom,
            onRequestCreateNewRoom = onRequestCreateNewRoom,
            onUserInteraction = onUserInteraction,
            onClose = onClose,
            onOpenFullDetailsModal = onOpenFullDetailsModal
        ) {
            when {
                tile.isThermostatTile -> {
                    ModularThermostatPopupContent(
                        tile = tile,
                        onUpdateTile = onUpdateTile,
                        onUserInteraction = onUserInteraction
                    )
                }
                tile.deviceClass == DeviceClass.LIGHT || tile.isAmbientRgbTile -> {
                    ModularLightAndRgbPopupContent(
                        tile = tile,
                        bridgeSettings = bridgeSettings,
                        onUpdateTile = onUpdateTile,
                        onUserInteraction = onUserInteraction
                    )
                }
                tile.deviceSubtype == DeviceSubtype.TV ||
                        tile.isSpeakerTile ||
                        tile.isVolumeHeroRequested ||
                        tile.isRadioHeroRequested -> {
                    ModularMediaPopupContent(
                        tile = tile,
                        bridgeSettings = bridgeSettings,
                        onUpdateTile = onUpdateTile,
                        onUserInteraction = onUserInteraction,
                        onOpenFullDetailsModal = onOpenFullDetailsModal
                    )
                }
                else -> {
                    ModularGenericDevicePopupContent(
                        tile = tile,
                        bridgeSettings = bridgeSettings,
                        onUpdateTile = onUpdateTile,
                        onUserInteraction = onUserInteraction,
                        onExpandCameraFullScreen = onExpandCameraFullScreen,
                        onOpenFullDetailsModal = onOpenFullDetailsModal
                    )
                }
            }
        }
    }
}
