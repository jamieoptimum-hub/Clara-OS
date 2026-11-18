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
    onUpdateBridgeSettings: (BridgeSettings) -> Unit,
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
        when {
            tile.isThermostatTile -> {
                ThermostatPopupTemplate(
                    tile = tile,
                    bridgeSettings = bridgeSettings,
                    availableRooms = availableRooms,
                    onUpdateTile = onUpdateTile,
                    onUpdateBridgeSettings = onUpdateBridgeSettings,
                    onAssignRoom = onAssignRoom,
                    onRequestCreateNewRoom = onRequestCreateNewRoom,
                    onUserInteraction = onUserInteraction,
                    onClose = onClose,
                    onExpandCameraFullScreen = onExpandCameraFullScreen,
                    onOpenFullDetailsModal = onOpenFullDetailsModal
                )
            }
            tile.deviceClass == DeviceClass.LIGHT || tile.isAmbientRgbTile -> {
                LightPopupTemplate(
                    tile = tile,
                    bridgeSettings = bridgeSettings,
                    availableRooms = availableRooms,
                    onUpdateTile = onUpdateTile,
                    onUpdateBridgeSettings = onUpdateBridgeSettings,
                    onAssignRoom = onAssignRoom,
                    onRequestCreateNewRoom = onRequestCreateNewRoom,
                    onUserInteraction = onUserInteraction,
                    onClose = onClose,
                    onExpandCameraFullScreen = onExpandCameraFullScreen,
                    onOpenFullDetailsModal = onOpenFullDetailsModal
                )
            }
            tile.deviceSubtype == DeviceSubtype.TV ||
                    tile.isSpeakerTile ||
                    tile.isVolumeHeroRequested ||
                    tile.isRadioHeroRequested -> {
                MediaPopupTemplate(
                    tile = tile,
                    bridgeSettings = bridgeSettings,
                    availableRooms = availableRooms,
                    onUpdateTile = onUpdateTile,
                    onUpdateBridgeSettings = onUpdateBridgeSettings,
                    onAssignRoom = onAssignRoom,
                    onRequestCreateNewRoom = onRequestCreateNewRoom,
                    onUserInteraction = onUserInteraction,
                    onClose = onClose,
                    onExpandCameraFullScreen = onExpandCameraFullScreen,
                    onOpenFullDetailsModal = onOpenFullDetailsModal
                )
            }
            else -> {
                GenericDevicePopupTemplate(
                    tile = tile,
                    bridgeSettings = bridgeSettings,
                    availableRooms = availableRooms,
                    onUpdateTile = onUpdateTile,
                    onUpdateBridgeSettings = onUpdateBridgeSettings,
                    onAssignRoom = onAssignRoom,
                    onRequestCreateNewRoom = onRequestCreateNewRoom,
                    onUserInteraction = onUserInteraction,
                    onClose = onClose,
                    onExpandCameraFullScreen = onExpandCameraFullScreen,
                    onOpenFullDetailsModal = onOpenFullDetailsModal
                )
            }
        }
    }
}
