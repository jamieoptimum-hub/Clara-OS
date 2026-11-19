package com.jarvis.smarthomelauncher.widgets.wifi.Widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.smarthomelauncher.SfHouseSunkenTileContainer
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.widgets.wifi.Models.*
import com.jarvis.smarthomelauncher.widgets.wifi.Repository.WifiRepository
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.min

/**
 * High-contrast, integer-pixel-snapped ISO/IEC 18004 QR Code renderer with a mandatory
 * 4-module pure-white quiet zone so smartphone cameras reliably scan from 1–2 metres away.
 */
@Composable
internal fun WifiQrCodeCanvas(
    qrMatrix: WifiQrMatrix,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    quietZoneModules: Int = 4
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color.White)
            .border(1.5.dp, GhCardBorder, RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val matrixSize = qrMatrix.size
            if (matrixSize <= 0) return@Canvas

            val totalModules = matrixSize + (quietZoneModules * 2)
            val canvasSide = min(size.width, size.height)

            // Snap each module to whole device pixels when possible to eliminate anti-aliased seams
            val rawCellPx = canvasSide / totalModules.toFloat()
            val cellPx = if (rawCellPx >= 3f) floor(rawCellPx) else rawCellPx
            val qrDrawSide = cellPx * matrixSize
            val startX = floor((size.width - qrDrawSide) / 2f)
            val startY = floor((size.height - qrDrawSide) / 2f)

            drawRoundRect(
                color = Color.White,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
            )

            val moduleColor = Color(0xFF0A0D12)
            for (row in 0 until matrixSize) {
                val y = startY + row * cellPx
                var col = 0
                while (col < matrixSize) {
                    if (qrMatrix.isDark(col, row)) {
                        var runEnd = col + 1
                        while (runEnd < matrixSize && qrMatrix.isDark(runEnd, row)) {
                            runEnd++
                        }
                        val x = startX + col * cellPx
                        val runWidth = (runEnd - col) * cellPx
                        drawRect(
                            color = moduleColor,
                            topLeft = Offset(x, y),
                            size = Size(runWidth + 0.35f, cellPx + 0.35f)
                        )
                        col = runEnd
                    } else {
                        col++
                    }
                }
            }
        }
    }
}

/**
 * CWAS Rule 5, 6, 11 & 13: Guest WiFi v1.0 Dashboard Experience.
 * Provides zero-interaction guest connectivity via a large 1–2m scannable QR code,
 * visible SSID beneath the QR code, and live network / guest count status.
 * Password remains strictly hidden on the dashboard.
 */
@Composable
fun WifiWidget(
    repository: WifiRepository,
    onOpenGuestWifiPopup: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by repository.uiState.collectAsState()

    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowEpochMs = System.currentTimeMillis()
        }
    }

    SfHouseSunkenTileContainer(
        outerCornerRadius = 22.dp,
        innerCornerRadius = 16.dp,
        sunkenInsetPadding = 4.dp,
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onOpenGuestWifiPopup() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            val readySnapshot = (uiState as? WifiWidgetUiState.Ready)?.snapshot

            // ================================================================
            // HEADER: "Guest WiFi" + wifi icon + clickable OpenGuestWifiPopup
            // ================================================================
            WifiWidgetHeader(
                snapshot = readySnapshot,
                onHeaderClick = onOpenGuestWifiPopup,
                onSettingsClick = onOpenSettings
            )

            Spacer(modifier = Modifier.height(6.dp))

            // ================================================================
            // BODY: CWAS Rule 11 Core States (Loading, Empty, Error, Ready)
            // ================================================================
            when (val state = uiState) {
                is WifiWidgetUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp,
                                color = GhAccentGreen
                            )
                            Text(
                                text = "Loading content...",
                                color = GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                is WifiWidgetUiState.Empty -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCode2,
                            contentDescription = null,
                            tint = GhAccentGreen,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.title,
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = state.message,
                            color = GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(GhAccentGreen)
                                .clickable { onOpenSettings() }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Configure Guest WiFi",
                                color = Color(0xFF101418),
                                fontFamily = GhFont,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                is WifiWidgetUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = GhAccentWarm,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.message,
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(GhTileSurfaceElevated)
                                .border(1.dp, GhCardBorder, RoundedCornerShape(10.dp))
                                .clickable { repository.triggerRefresh(forceLanScan = true) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry",
                                tint = GhAccentGreen,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Retry",
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                is WifiWidgetUiState.Ready -> {
                    WifiReadyDashboardBody(
                        snapshot = state.snapshot,
                        nowEpochMs = nowEpochMs,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun WifiWidgetHeader(
    snapshot: GuestWifiDashboardSnapshot?,
    onHeaderClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onHeaderClick() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            val isOffline = snapshot?.healthStatus == GuestNetworkHealthStatus.DISCONNECTED
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(GhTileSurfaceElevated)
                    .border(1.dp, GhCardBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isOffline) Icons.Default.WifiOff else Icons.Default.Wifi,
                    contentDescription = "Guest WiFi",
                    tint = if (isOffline) GhAccentRed else GhAccentGreen,
                    modifier = Modifier.size(16.dp)
                )
            }

            Column {
                Text(
                    text = "Guest WiFi",
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (snapshot != null &&
                    snapshot.config.showWifiNameOnWidget &&
                    snapshot.config.networkDisplayName.isNotBlank() &&
                    !snapshot.config.networkDisplayName.equals("Guest WiFi", ignoreCase = true)
                ) {
                    Text(
                        text = snapshot.config.networkDisplayName,
                        color = GhTextSecondary,
                        fontFamily = GhFont,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (snapshot != null && snapshot.config.showGuestStatusOnWidget) {
                val (statusLabel, statusTint) = when (snapshot.healthStatus) {
                    GuestNetworkHealthStatus.ONLINE -> "SCAN TO JOIN" to GhAccentGreen
                    GuestNetworkHealthStatus.LIMITED_NO_INTERNET -> "LOCAL ONLY" to GhAccentWarm
                    GuestNetworkHealthStatus.PAUSED_EXPIRED -> "EXPIRED" to GhAccentRed
                    GuestNetworkHealthStatus.DISCONNECTED -> "OFFLINE" to GhAccentRed
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusTint.copy(alpha = 0.16f))
                        .border(1.dp, statusTint.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = statusLabel,
                        color = statusTint,
                        fontFamily = GhFont,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(GhSunkenWell)
                    .clickable { onSettingsClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Guest WiFi Settings",
                    tint = GhTextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * Primary Element: QRCode (1–2m scannable)
 * Secondary Element: SSID (always visible beneath QR code)
 * Tertiary Element: NetworkStatus & Connected Guests
 */
@Composable
private fun WifiReadyDashboardBody(
    snapshot: GuestWifiDashboardSnapshot,
    nowEpochMs: Long,
    modifier: Modifier = Modifier
) {
    val cfg = snapshot.config
    val isExpired = cfg.isTemporaryAccessExpired(nowEpochMs)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val showTertiaryStrip = maxHeight >= 185.dp &&
                (cfg.showGuestStatusOnWidget || cfg.showConnectedGuestsOnWidget)

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Primary Element: Large Dynamic QR Code (or Expired Guard)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isExpired) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(GhSunkenWell)
                            .border(1.dp, GhAccentRed.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = GhAccentRed,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Temporary Guest Access Ended",
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Tap Guest WiFi to renew window or rotate password",
                            color = GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else if (cfg.showQrCodeOnWidget) {
                    WifiQrCodeCanvas(
                        qrMatrix = snapshot.qrMatrix,
                        cornerRadius = 14.dp,
                        quietZoneModules = 4,
                        modifier = Modifier.fillMaxHeight()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(GhSunkenWell)
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Tap to open Guest WiFi QR & details",
                            color = GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // 2. Secondary Element: SSID visibly anchored beneath QR Code
            if (cfg.showSsidOnWidget) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(GhSunkenWell)
                        .border(1.dp, GhCardBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        tint = GhAccentGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = cfg.ssid,
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 3. Tertiary Element: Network Status + Connected Guests Count
            if (showTertiaryStrip) {
                Spacer(modifier = Modifier.height(5.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (cfg.showGuestStatusOnWidget) {
                        val bandAndSignal = if (snapshot.linkTelemetry.isWifiConnected) {
                            "${snapshot.linkTelemetry.bandLabel} • ${snapshot.linkTelemetry.signalPercent}%"
                        } else {
                            snapshot.healthStatus.label
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (cfg.guestIsolationEnabled) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = "Guest Isolation Active",
                                    tint = GhAccentGreen,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            Text(
                                text = bandAndSignal,
                                color = GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    if (cfg.showConnectedGuestsOnWidget && cfg.guestMonitoringEnabled) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Devices,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (snapshot.activeGuestCount == 1) {
                                    "1 guest"
                                } else {
                                    "${snapshot.activeGuestCount} guests"
                                },
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}