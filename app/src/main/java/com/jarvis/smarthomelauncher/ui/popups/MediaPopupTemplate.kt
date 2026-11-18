package com.jarvis.smarthomelauncher.ui.popups

import androidx.compose.runtime.Composable
import com.jarvis.smarthomelauncher.core.*

@Composable
fun MediaPopupTemplate(
    tile: SmartTile,
    bridgeSettings: BridgeSettings,
    availableRooms: List<String>,
    onUpdateTile: (SmartTile) -> Unit,
    @Suppress("UNUSED_PARAMETER") onUpdateBridgeSettings: (BridgeSettings) -> Unit,
    onAssignRoom: (String) -> Unit,
    onRequestCreateNewRoom: () -> Unit,
    onUserInteraction: () -> Unit,
    onClose: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onExpandCameraFullScreen: () -> Unit,
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
        ModularMediaPopupContent(
            tile = tile,
            bridgeSettings = bridgeSettings,
            onUpdateTile = onUpdateTile,
            onUserInteraction = onUserInteraction,
            onOpenFullDetailsModal = onOpenFullDetailsModal
        )
    }
}
