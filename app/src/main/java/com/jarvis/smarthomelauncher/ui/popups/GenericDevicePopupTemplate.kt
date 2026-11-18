package com.jarvis.smarthomelauncher.ui.popups

import androidx.compose.runtime.Composable
import com.jarvis.smarthomelauncher.core.*

@Composable
fun GenericDevicePopupTemplate(
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
    ModularPopupContainer(
        tile = tile,
        availableRooms = availableRooms,
        onAssignRoom = onAssignRoom,
        onRequestCreateNewRoom = onRequestCreateNewRoom,
        onUserInteraction = onUserInteraction,
        onClose = onClose,
        onOpenFullDetailsModal = onOpenFullDetailsModal
    ) {
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
