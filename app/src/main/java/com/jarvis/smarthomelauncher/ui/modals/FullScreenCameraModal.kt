package com.jarvis.smarthomelauncher.ui.modals

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.app.PendingIntent
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Hd
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sd
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// ---------------------------------------------------------------------------
// AUTOMATIC PTZ CAPABILITY & NETWORK HEALTH INTROSPECTION HELPERS
// ---------------------------------------------------------------------------

private suspend fun detectCameraPtzCapability(
    tile: SmartTile,
    bridgeSettings: BridgeSettings
): Boolean = withContext(Dispatchers.IO) {
    val hasExplicitPtzCap = tile.capabilities.any { cap ->
        cap.key.contains("ptz", ignoreCase = true) ||
                cap.rendererType == CapabilityRendererType.D_PAD
    }
    if (hasExplicitPtzCap) {
        return@withContext true
    }

    val lowerTitle = tile.title.lowercase()
    val hasPanTiltNameHint = lowerTitle.contains("pt") ||
            lowerTitle.contains("pan") ||
            lowerTitle.contains("tilt") ||
            lowerTitle.contains("dome") ||
            lowerTitle.contains("cruiser") ||
            lowerTitle.contains("ranger") ||
            lowerTitle.contains("360")

    // Fixed doorbells do not have pan/tilt motors unless explicitly indicated
    if ((tile.deviceSubtype == DeviceSubtype.DOORBELL || lowerTitle.contains("doorbell")) && !hasPanTiltNameHint) {
        return@withContext false
    }

    if (tile.supportsPtz && tile.type != TileType.IMOU_CAMERA) {
        return@withContext true
    }

    if (tile.resolvedProviderId == ProviderId.IMOU_CLOUD || tile.type == TileType.IMOU_CAMERA) {
        var cleanSn = tile.serialNumber.trim()
        val discovered = runCatching { ImouCloudApiClient.discoverBoundCameras(bridgeSettings) }.getOrNull()?.getOrNull()
        if (cleanSn.isBlank() && !discovered.isNullOrEmpty()) {
            val matched = discovered.firstOrNull { it.name.equals(tile.title, ignoreCase = true) }
                ?: discovered.firstOrNull()
            cleanSn = matched?.deviceId.orEmpty()
        }

        if (cleanSn.isNotBlank() && ImouCloudApiClient.isPtzSupported(cleanSn)) {
            return@withContext true
        }
    }

    // Fallback: Probe local CGI / ONVIF PTZ capability when streaming locally on any camera
    val cleanIp = tile.ipAddress.trim().substringBefore(":")
    if (cleanIp.isNotBlank()) {
        try {
            val url = URL("http://$cleanIp/cgi-bin/ptz.cgi?action=getStatus&channel=1")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1200
                readTimeout = 1200
            }
            val code = conn.responseCode
            conn.disconnect()
            // 200 OK or 401 Unauthorized indicates the CGI PTZ service endpoint exists on the camera
            if (code == 200 || code == 401) {
                return@withContext true
            }
        } catch (_: Exception) {
        }
    }

    hasPanTiltNameHint
}

private fun shouldPreferSdForNetworkConditions(
    context: Context,
    tile: SmartTile,
    isUsingCloudBandwidth: Boolean
): Boolean {
    if (isUsingCloudBandwidth) return true
    return try {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = cm?.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(activeNet) ?: return true
        val isUnmeteredWifiOrEthernet = (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val downstreamKbps = caps.linkDownstreamBandwidthKbps
        // Prefer SD sub-stream if on a metered connection, low bandwidth link, or lacking a local LAN IP
        !isUnmeteredWifiOrEthernet || (downstreamKbps in 1..4500) || tile.ipAddress.isBlank()
    } catch (_: Exception) {
        tile.useSubStream
    }
}

// ---------------------------------------------------------------------------
// CLEAN EDGE-TO-EDGE FULL-SCREEN CAMERA, PTZ & DOORBELL RING MODAL
// ---------------------------------------------------------------------------

@Composable
fun FullScreenCameraModal(
    tile: SmartTile,
    bridgeSettings: BridgeSettings,
    isEmergencyRing: Boolean,
    pendingIntent: PendingIntent?,
    onUpdateTile: (SmartTile) -> Unit = {},
    onSpeak: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentTile by rememberUpdatedState(tile)
    val session = remember(tile.id) {
        CameraStreamSessionManager.getOrCreate(context, tile.id)
    }

    var useSubStream by remember(tile.id) {
        mutableStateOf(
            shouldPreferSdForNetworkConditions(
                context = context,
                tile = tile,
                isUsingCloudBandwidth = session.isUsingCloudBandwidth
            )
        )
    }
    var isAudioEnabled by remember(tile.id) { mutableStateOf(true) }
    var isPtzSupported by remember(tile.id) { mutableStateOf(false) }
    var showPtzControls by remember(tile.id) { mutableStateOf(false) }
    var consecutiveBufferingTicks by remember(tile.id) { mutableIntStateOf(0) }

    // Automatically introspect whether this camera hardware supports PTZ movement
    LaunchedEffect(tile.id, tile.deviceSubtype, tile.supportsPtz, tile.capabilities, tile.serialNumber, tile.ipAddress) {
        val supported = detectCameraPtzCapability(tile, bridgeSettings)
        isPtzSupported = supported
        if (!supported) {
            showPtzControls = false
        }
    }

    // Automatically adapt stream quality (HD vs SD) based on connection type and buffering health
    LaunchedEffect(
        tile.id,
        session.isUsingCloudBandwidth,
        session.hasError,
        session.autoHealRequestNonce,
        session.streamStatus
    ) {
        // 1. If streaming over Cloud HLS or experiencing stream errors/reconnects, step down to SD automatically
        if ((session.isUsingCloudBandwidth || session.hasError || session.autoHealRequestNonce > 0) && !useSubStream) {
            useSubStream = true
            onUpdateTile(currentTile.copy(useSubStream = true))
            return@LaunchedEffect
        }

        // 2. Monitor buffering duration; if buffering persists on HD, switch to SD for smooth playback
        if (session.streamStatus.isNotEmpty() && !useSubStream) {
            consecutiveBufferingTicks = 0
            while (session.streamStatus.isNotEmpty() && !useSubStream) {
                delay(1000L)
                consecutiveBufferingTicks++
                if (consecutiveBufferingTicks >= 4) {
                    useSubStream = true
                    onUpdateTile(currentTile.copy(useSubStream = true))
                    break
                }
            }
        } else {
            consecutiveBufferingTicks = 0
        }

        // 3. When streaming locally over healthy unmetered Wi-Fi with zero errors, upgrade to HD automatically
        if (!session.isUsingCloudBandwidth &&
            session.resolvedUrl.startsWith("rtsp://", ignoreCase = true) &&
            !session.hasError &&
            session.autoHealRequestNonce == 0 &&
            session.streamStatus.isEmpty() &&
            useSubStream &&
            !shouldPreferSdForNetworkConditions(context, currentTile, isUsingCloudBandwidth = false)
        ) {
            delay(5000L)
            if (!session.isUsingCloudBandwidth && !session.hasError && session.streamStatus.isEmpty()) {
                useSubStream = false
                onUpdateTile(currentTile.copy(useSubStream = false))
            }
        }
    }

    val updatedTileForStream = remember(tile, useSubStream) {
        tile.copy(useSubStream = useSubStream)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        SmartCameraStreamResolverView(
            tile = updatedTileForStream,
            bridgeSettings = bridgeSettings,
            enableAudio = isAudioEnabled,
            cropToFillTile = false,
            onUpdateTile = onUpdateTile
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.8f),
                            Color.Transparent
                        )
                    )
                )
        )

        // TOP HEADER BAR (No location subtitle shown, matching thermostats)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (isEmergencyRing) GhAccentRed.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.15f),
                    shape = CircleShape,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isEmergencyRing) Icons.Default.RingVolume else Icons.Outlined.Videocam,
                            contentDescription = null,
                            tint = if (isEmergencyRing) GhAccentRed else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = tile.title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GhFont
                    )
                    if (isEmergencyRing) {
                        Text(
                            text = "Doorbell Ringing",
                            color = GhAccentRed,
                            fontSize = 12.sp,
                            fontFamily = GhFont,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = {
                        val nextSub = !useSubStream
                        useSubStream = nextSub
                        onUpdateTile(currentTile.copy(useSubStream = nextSub))
                        session.forceRetry()
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        contentColor = if (useSubStream) GhAccentWarm else GhAccentGreen
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .semantics {
                            contentDescription = "Stream Quality"
                            stateDescription = if (useSubStream) "Standard Definition" else "High Definition"
                        }
                ) {
                    Icon(
                        imageVector = if (useSubStream) Icons.Outlined.Sd else Icons.Outlined.Hd,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (useSubStream) "SD" else "HD",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                AnimatedVisibility(visible = isPtzSupported) {
                    FilledTonalButton(
                        onClick = { showPtzControls = !showPtzControls },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (showPtzControls) GhAccentBlue.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.6f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .semantics {
                                contentDescription = "Pan and Tilt Controls"
                                stateDescription = if (showPtzControls) "Visible" else "Hidden"
                            }
                    ) {
                        Icon(Icons.Outlined.OpenWith, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("PTZ", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                IconButton(
                    onClick = { isAudioEnabled = !isAudioEnabled },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .semantics {
                            contentDescription = "Camera Audio"
                            stateDescription = if (isAudioEnabled) "Unmuted" else "Muted"
                        }
                ) {
                    Icon(
                        imageVector = if (isAudioEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = null,
                        tint = Color.White
                    )
                }

                FilledTonalButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.65f),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .semantics {
                            contentDescription = "Close Fullscreen Camera"
                        }
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Close", fontSize = 13.sp, fontFamily = GhFont)
                }
            }
        }

        // INTERACTIVE PTZ D-PAD OVERLAY (Right Side - Only shown when hardware supports PTZ and toggled)
        if (isPtzSupported && showPtzControls) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val ok = SmartHomeApiClient.dispatchCameraPtz(currentTile, bridgeSettings, PtzDirection.UP)
                                if (!ok && currentTile.serialNumber.isBlank() && currentTile.ipAddress.isBlank()) {
                                    isPtzSupported = false
                                    showPtzControls = false
                                }
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Tilt Camera Up" }
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = Color.White)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    SmartHomeApiClient.dispatchCameraPtz(currentTile, bridgeSettings, PtzDirection.LEFT)
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "Pan Camera Left" }
                        ) {
                            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = null, tint = Color.White)
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    SmartHomeApiClient.dispatchCameraPtz(currentTile, bridgeSettings, PtzDirection.RIGHT)
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "Pan Camera Right" }
                        ) {
                            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Color.White)
                        }
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                SmartHomeApiClient.dispatchCameraPtz(currentTile, bridgeSettings, PtzDirection.DOWN)
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Tilt Camera Down" }
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.White)
                    }
                }
            }
        }

        // BOTTOM IN-APP ACTION BAR FOR WAKE / RECONNECT & QUICK DOORBELL RESPONSES
        Surface(
            color = Color.Black.copy(alpha = 0.72f),
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (pendingIntent != null) {
                            try {
                                pendingIntent.send()
                                return@Button
                            } catch (_: Exception) {
                            }
                        }
                        session.forceRetry()
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GhAccentGreen,
                        contentColor = GhBackground
                    )
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Wake & Reconnect Live", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                if (tile.deviceSubtype == DeviceSubtype.DOORBELL || isEmergencyRing) {
                    FilledTonalButton(
                        onClick = { onSpeak("Please leave the package by the front door.") },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = GhTileSurfaceElevated.copy(alpha = 0.85f),
                            contentColor = GhTextPrimary
                        )
                    ) {
                        Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Leave Package", fontSize = 12.sp)
                    }
                    FilledTonalButton(
                        onClick = { onSpeak("Someone will be right at the door.") },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = GhTileSurfaceElevated.copy(alpha = 0.85f),
                            contentColor = GhTextPrimary
                        )
                    ) {
                        Icon(Icons.Outlined.DirectionsRun, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Be Right There", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
