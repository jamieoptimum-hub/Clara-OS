package com.jarvis.smarthomelauncher.ui.modals

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.CameraStreamSessionManager
import kotlinx.coroutines.launch

@Composable
fun DeviceRemovalDialog(
    context: Context,
    tile: SmartTile,
    bridge: BridgeSettings,
    onDismiss: () -> Unit,
    onUnpaired: () -> Unit,
    onUnbound: () -> Unit
) {
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GhTileSurface,
        titleContentColor = GhTextPrimary,
        textContentColor = GhTextSecondary,
        title = {
            Text("Remove ${tile.title}", fontFamily = GhFont, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        },
        text = {
            Text(
                "How would you like to remove this device?\n\n" +
                "Unpair: Hides it from your dashboard but keeps its settings and prevents it from showing up in discovery.\n\n" +
                "Unbind: Completely deletes it, wipes all stored credentials, and resets ownership.",
                fontFamily = GhFont,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        val unbindStartMs = System.currentTimeMillis()
                        // Unbind (Remove & Reset Ownership)
                        if (tile.type == TileType.MATTER_DEVICE || tile.serialNumber.startsWith("matter_") || tile.entityId.startsWith("matter_")) {
                            val serverHostIp = MatterProtocolClient.getSavedMatterServerHost(context)
                            val nodeId = tile.serialNumber.ifBlank { tile.entityId }
                            if (serverHostIp.isNotBlank() && nodeId.isNotBlank()) {
                                MatterProtocolClient.removeMatterNode(serverHostIp, nodeId)
                            }
                        } else if (tile.type == TileType.LG_TV || tile.deviceSubtype == DeviceSubtype.TV) {
                            LgThinQWebOsClient.clearPinnedTvCertForReverification(context, tile.ipAddress)
                        } else if (tile.activeProviderId.contains("TUYA", ignoreCase = true) ||
                            tile.type in setOf(TileType.SMARTKNIGHT_PLUG, TileType.SMARTLIFE_DEVICE, TileType.TUYA_RADIATOR, TileType.PROBREEZE_FAN, TileType.AIRROBO_VACUUM, TileType.LYTMI_SYNC_BOX)
                        ) {
                            val devId = tile.serialNumber.ifBlank { tile.entityId }
                            if (devId.isNotBlank()) {
                                TuyaApiClient.deleteDevice(bridge, devId)
                            }
                        }

                        // Wipe secrets
                        val cleanId = tile.serialNumber.ifBlank { tile.entityId }
                        if (cleanId.isNotBlank()) {
                            ClaraSecretVault.remove(context, "matter::noc::$cleanId")
                            ClaraSecretVault.remove(context, "tuya::localkey::$cleanId")
                            ClaraSecretVault.remove(context, "tuya::localkey::mac_${tile.macAddress.lowercase(java.util.Locale.US)}")
                            val cleanIp = tile.ipAddress.substringBefore(":").trim()
                            if (cleanIp.isNotBlank()) {
                                ClaraSecretVault.remove(context, "lg_webos::client_key::${cleanIp.lowercase(java.util.Locale.US)}")
                            }
                        }

                        val unbindLatencyMs = (System.currentTimeMillis() - unbindStartMs).coerceAtLeast(1L)
                        DeviceRepository.unmarkDeviceDismissed(
                            context,
                            tile.canonicalGuid,
                            tile.resolvedCanonicalGuid(),
                            tile.macAddress,
                            tile.ipAddress
                        )

                        DeviceLifecycleRecorder.recordTileTransition(
                            tile = tile,
                            transition = MandatoryLifecycleTransition.UNBOUND,
                            previousState = tile.effectiveLifecycleState.name,
                            newState = DeviceLifecycleState.UNBOUND.name,
                            latencyMs = unbindLatencyMs,
                            protocolResultCode = "OWNERSHIP_RESET_OK"
                        )
                        JarvisLiveBridge.removeDeviceObservability(tile.id)
                        CameraStreamSessionManager.releaseSession(tile.id)
                        onUnbound()
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = GhAccentRed, contentColor = Color.White)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Unbind", fontFamily = GhFont, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    DeviceRepository.markDeviceDismissed(context, tile)
                    DeviceLifecycleRecorder.recordTileTransition(
                        tile = tile,
                        transition = MandatoryLifecycleTransition.UNPAIRED,
                        previousState = tile.effectiveLifecycleState.name,
                        newState = DeviceLifecycleState.UNPAIRED.name,
                        protocolResultCode = "DASHBOARD_UNPAIRED_HIDDEN"
                    )
                    JarvisLiveBridge.updateDeviceLifecycleAndRecoveryStage(
                        tile = tile,
                        lifecycleState = DeviceLifecycleState.UNPAIRED,
                        emitFlightRecord = false
                    )
                    onUnpaired()
                    onDismiss()
                }
            ) {
                Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Unpair (Hide)", fontFamily = GhFont)
            }
        }
    )
}
