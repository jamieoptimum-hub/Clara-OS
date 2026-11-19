package com.jarvis.smarthomelauncher.widgets.wifi.Popup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.smarthomelauncher.SfHouseSunkenTileContainer
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.widgets.wifi.Models.*
import com.jarvis.smarthomelauncher.widgets.wifi.Repository.WifiRepository
import com.jarvis.smarthomelauncher.widgets.wifi.Settings.WifiSettingsContent
import com.jarvis.smarthomelauncher.widgets.wifi.Settings.WifiSettingsDialog
import com.jarvis.smarthomelauncher.widgets.wifi.Widget.WifiQrCodeCanvas
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CWAS Rule 5 & Rule 6: Guest WiFi v1.0 Popup Hub (60% width x 70% height).
 * Opens as an overlay Dialog keeping the Clara dashboard visible behind it.
 */
@Composable
fun WifiHubPopup(
    repository: WifiRepository,
    initialSection: WifiPopupSection = WifiPopupSection.QR_AND_CREDENTIALS,
    onDismiss: () -> Unit
) {
    val uiState by repository.uiState.collectAsState()

    var activeSection by remember(initialSection) { mutableStateOf(initialSection) }
    var isPasswordRevealed by remember { mutableStateOf(false) }
    var copiedBannerText by remember { mutableStateOf<String?>(null) }
    var showSettingsModal by remember { mutableStateOf(false) }

    var showVoiceBar by remember { mutableStateOf(false) }
    var voiceQuery by remember { mutableStateOf("") }
    var voiceResponseText by remember { mutableStateOf<String?>(null) }

    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            nowEpochMs = System.currentTimeMillis()
        }
    }

    // Re-hide password automatically when switching away from the credentials view
    LaunchedEffect(activeSection) {
        if (activeSection != WifiPopupSection.QR_AND_CREDENTIALS) {
            isPasswordRevealed = false
        }
    }

    // Auto-clear transient copy confirmation banner after 2.8 seconds
    LaunchedEffect(copiedBannerText) {
        if (copiedBannerText != null) {
            delay(2800L)
            copiedBannerText = null
        }
    }

    Dialog(
        onDismissRequest = {
            isPasswordRevealed = false
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        SfHouseSunkenTileContainer(
            outerCornerRadius = 26.dp,
            innerCornerRadius = 20.dp,
            sunkenInsetPadding = 4.dp,
            innerBackgroundColor = GhBackground,
            innerBorderColor = GhCardBorder,
            modifier = Modifier
                .fillMaxWidth(0.60f)
                .fillMaxHeight(0.70f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                val readySnapshot = (uiState as? WifiWidgetUiState.Ready)?.snapshot

                // ============================================================
                // TOP BAR: Title, Network Status Pill, Voice, Refresh, Settings, Close
                // ============================================================
                WifiPopupTopBar(
                    snapshot = readySnapshot,
                    onToggleVoiceBar = { showVoiceBar = !showVoiceBar },
                    onRefresh = { repository.triggerRefresh(forceLanScan = true) },
                    onOpenSettings = { showSettingsModal = true },
                    onDismiss = {
                        isPasswordRevealed = false
                        onDismiss()
                    }
                )

                if (showVoiceBar) {
                    Spacer(modifier = Modifier.height(10.dp))
                    WifiVoiceCommandBar(
                        query = voiceQuery,
                        answerBanner = voiceResponseText,
                        onQueryChange = { voiceQuery = it },
                        onRunQuery = { spoken ->
                            val resp = repository.handleVoiceCommand(spoken)
                            voiceResponseText = if (resp.handled) {
                                resp.spokenText
                            } else {
                                "Try: 'Show Guest WiFi QR', 'How many guests are connected to WiFi', or 'Rotate Guest WiFi password'."
                            }
                            resp.openPopupSection?.let { activeSection = it }
                        }
                    )
                }

                if (!copiedBannerText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(GhTileSurfaceElevated)
                            .border(1.dp, GhAccentGreen.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = GhAccentGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = copiedBannerText.orEmpty(),
                            color = GhAccentGreen,
                            fontFamily = GhFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ============================================================
                // SECTION TABS: WiFi & QR | Guests | Security
                // ============================================================
                if (uiState is WifiWidgetUiState.Ready) {
                    WifiPopupSectionTabs(
                        activeSection = activeSection,
                        activeGuestCount = readySnapshot?.activeGuestCount ?: 0,
                        guestMonitoringEnabled = readySnapshot?.config?.guestMonitoringEnabled ?: true,
                        onSelectSection = { activeSection = it }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // ============================================================
                // BODY CONTENT BY CWAS CORE STATE
                // ============================================================
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (val state = uiState) {
                        is WifiWidgetUiState.Loading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = GhAccentGreen)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Loading Guest WiFi & scanning local network...",
                                        color = GhTextSecondary,
                                        fontFamily = GhFont,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        is WifiWidgetUiState.Empty -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(GhTileSurface)
                                        .border(1.dp, GhAccentGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = "Set up your household or guest Wi-Fi SSID and password below to enable instant 1–2m QR code scanning.",
                                        color = GhTextPrimary,
                                        fontFamily = GhFont,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                WifiSettingsContent(
                                    repository = repository,
                                    onSavedCallback = { activeSection = WifiPopupSection.QR_AND_CREDENTIALS },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        is WifiWidgetUiState.Error -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = GhAccentWarm,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = state.message,
                                    color = GhTextPrimary,
                                    fontFamily = GhFont,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (state.technicalDetail.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = state.technicalDetail,
                                        color = GhTextSecondary,
                                        fontFamily = GhFont,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { repository.triggerRefresh(forceLanScan = true) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = GhAccentGreen,
                                        contentColor = Color(0xFF101418)
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Retry", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        is WifiWidgetUiState.Ready -> {
                            val snap = state.snapshot
                            when (activeSection) {
                                WifiPopupSection.QR_AND_CREDENTIALS -> QrAndCredentialsSection(
                                    repository = repository,
                                    snapshot = snap,
                                    nowEpochMs = nowEpochMs,
                                    isPasswordRevealed = isPasswordRevealed,
                                    onToggleRevealPassword = { isPasswordRevealed = !isPasswordRevealed },
                                    onCopiedFeedback = { msg -> copiedBannerText = msg }
                                )

                                WifiPopupSection.CONNECTED_GUESTS -> ConnectedGuestsSection(
                                    repository = repository,
                                    snapshot = snap
                                )

                                WifiPopupSection.SECURITY -> SecurityControlsSection(
                                    repository = repository,
                                    snapshot = snap,
                                    nowEpochMs = nowEpochMs,
                                    onStatusFeedback = { msg -> copiedBannerText = msg }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showSettingsModal) {
            WifiSettingsDialog(
                repository = repository,
                onDismiss = { showSettingsModal = false }
            )
        }
    }
}

@Composable
private fun WifiPopupTopBar(
    snapshot: GuestWifiDashboardSnapshot?,
    onToggleVoiceBar: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val isOffline = snapshot?.healthStatus == GuestNetworkHealthStatus.DISCONNECTED

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(GhTileSurfaceElevated)
                    .border(1.dp, GhCardBorder, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isOffline) Icons.Default.WifiOff else Icons.Default.Wifi,
                    contentDescription = null,
                    tint = if (isOffline) GhAccentRed else GhAccentGreen,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Guest WiFi",
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    snapshot?.let { snap ->
                        val (badgeLabel, badgeTint) = when (snap.healthStatus) {
                            GuestNetworkHealthStatus.ONLINE -> "ONLINE" to GhAccentGreen
                            GuestNetworkHealthStatus.LIMITED_NO_INTERNET -> "NO INTERNET" to GhAccentWarm
                            GuestNetworkHealthStatus.PAUSED_EXPIRED -> "EXPIRED" to GhAccentRed
                            GuestNetworkHealthStatus.DISCONNECTED -> "OFFLINE" to GhAccentRed
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(badgeTint.copy(alpha = 0.16f))
                                .border(1.dp, badgeTint.copy(alpha = 0.6f), RoundedCornerShape(7.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = badgeLabel,
                                color = badgeTint,
                                fontFamily = GhFont,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    text = snapshot?.config?.networkDisplayName ?: "Scan QR code with your phone camera to connect",
                    color = GhTextSecondary,
                    fontFamily = GhFont,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            WifiPopupIconButton(
                icon = Icons.Default.Mic,
                contentDescription = "Voice Commands",
                onClick = onToggleVoiceBar
            )
            WifiPopupIconButton(
                icon = Icons.Default.Refresh,
                contentDescription = "Refresh Network & Guests",
                onClick = onRefresh
            )
            WifiPopupIconButton(
                icon = Icons.Default.Settings,
                contentDescription = "Guest WiFi Settings",
                onClick = onOpenSettings
            )
            WifiPopupIconButton(
                icon = Icons.Default.Close,
                contentDescription = "Close Guest WiFi",
                onClick = onDismiss
            )
        }
    }
}

@Composable
private fun WifiPopupIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(GhTileSurfaceElevated)
            .border(1.dp, GhCardBorder, CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = GhTextPrimary,
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
private fun WifiVoiceCommandBar(
    query: String,
    answerBanner: String?,
    onQueryChange: (String) -> Unit,
    onRunQuery: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GhTileSurface)
            .border(1.dp, GhCardBorder, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(
                        text = "Ask Clara (e.g. 'How many guests are connected to WiFi')",
                        color = GhTextSecondary,
                        fontSize = 12.sp
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = GhTextPrimary,
                    unfocusedTextColor = GhTextPrimary,
                    focusedBorderColor = GhAccentGreen,
                    unfocusedBorderColor = GhCardBorder,
                    focusedContainerColor = GhSunkenWell,
                    unfocusedContainerColor = GhSunkenWell
                ),
                shape = RoundedCornerShape(10.dp)
            )
            Button(
                onClick = { onRunQuery(query) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = GhAccentGreen,
                    contentColor = Color(0xFF101418)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Text("Ask", fontWeight = FontWeight.Bold)
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val presets = listOf(
                "Show Guest WiFi QR",
                "How many guests are connected to WiFi",
                "Rotate Guest WiFi password"
            )
            items(presets) { preset ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(GhSunkenWell)
                        .border(1.dp, GhCardBorder, RoundedCornerShape(8.dp))
                        .clickable {
                            onQueryChange(preset)
                            onRunQuery(preset)
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = preset,
                        color = GhTextSecondary,
                        fontFamily = GhFont,
                        fontSize = 11.sp
                    )
                }
            }
        }

        if (!answerBanner.isNullOrBlank()) {
            Text(
                text = answerBanner,
                color = GhAccentGreen,
                fontFamily = GhFont,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun WifiPopupSectionTabs(
    activeSection: WifiPopupSection,
    activeGuestCount: Int,
    guestMonitoringEnabled: Boolean,
    onSelectSection: (WifiPopupSection) -> Unit
) {
    val visibleTabs = remember(guestMonitoringEnabled) {
        if (guestMonitoringEnabled) {
            WifiPopupSection.entries
        } else {
            WifiPopupSection.entries.filter { it != WifiPopupSection.CONNECTED_GUESTS }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        visibleTabs.forEach { section ->
            val selected = section == activeSection
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) GhAccentGreen else GhTileSurface)
                    .border(
                        1.dp,
                        if (selected) GhAccentGreen else GhCardBorder,
                        RoundedCornerShape(11.dp)
                    )
                    .clickable { onSelectSection(section) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = section.title,
                    color = if (selected) Color(0xFF101418) else GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold
                )
                if (section == WifiPopupSection.CONNECTED_GUESTS) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) Color(0xFF101418) else GhTileSurfaceElevated)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = activeGuestCount.toString(),
                            color = if (selected) Color.White else GhAccentGreen,
                            fontFamily = GhFont,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// SECTION 1: QR CODE & WIFI CREDENTIALS
// Shows: 1-2m QR Code, SSID beneath QR, hidden-by-default Password, Reveal & Copy buttons
// ============================================================================
@Composable
private fun QrAndCredentialsSection(
    repository: WifiRepository,
    snapshot: GuestWifiDashboardSnapshot,
    nowEpochMs: Long,
    isPasswordRevealed: Boolean,
    onToggleRevealPassword: () -> Unit,
    onCopiedFeedback: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val cfg = snapshot.config
    val isExpired = cfg.isTemporaryAccessExpired(nowEpochMs)

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Left Column: Large 1–2m Scannable QR + Visible SSID Beneath QR Code
        Column(
            modifier = Modifier
                .weight(0.95f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(GhTileSurface)
                .border(1.dp, GhCardBorder, RoundedCornerShape(16.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Point Phone Camera to Join",
                color = GhTextSecondary,
                fontFamily = GhFont,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isExpired) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = GhAccentRed,
                            modifier = Modifier.size(38.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Temporary Window Expired",
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Extend access or rotate password in the Security tab.",
                            color = GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    WifiQrCodeCanvas(
                        qrMatrix = snapshot.qrMatrix,
                        cornerRadius = 16.dp,
                        quietZoneModules = 4,
                        modifier = Modifier.fillMaxHeight()
                    )
                }
            }

            // Mandatory: SSID visibly positioned directly beneath QR code
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(GhSunkenWell)
                        .border(1.dp, GhCardBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        tint = GhAccentGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = cfg.ssid,
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = cfg.securityProtocol.displayName,
                    color = GhTextSecondary,
                    fontFamily = GhFont,
                    fontSize = 11.sp
                )
            }
        }

        // Right Column: Credentials (Hidden Password + Reveal + Copy) & Live Link Telemetry
        LazyColumn(
            modifier = Modifier
                .weight(1.05f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GhTileSurface)
                        .border(1.dp, GhCardBorder, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "WiFi Credentials",
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // SSID Row + Copy
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(GhSunkenWell)
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Network Name (SSID)",
                                    color = GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = cfg.ssid,
                                    color = GhTextPrimary,
                                    fontFamily = GhFont,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (repository.copySsidToClipboard()) {
                                        onCopiedFeedback("Copied SSID '${cfg.ssid}' to clipboard")
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy SSID",
                                    tint = GhAccentGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // Password Row: Hidden until explicitly requested + Reveal + Copy
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(GhSunkenWell)
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Password",
                                    color = GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 11.sp
                                )
                                val displayedPass = when {
                                    !cfg.securityProtocol.requiresPassword -> "No Password (Open Network)"
                                    isPasswordRevealed -> snapshot.passwordPlaintext.ifBlank { "(Empty)" }
                                    else -> "••••••••••••"
                                }
                                Text(
                                    text = displayedPass,
                                    color = GhTextPrimary,
                                    fontFamily = if (isPasswordRevealed) FontFamily.Monospace else GhFont,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (cfg.securityProtocol.requiresPassword) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = onToggleRevealPassword,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isPasswordRevealed) {
                                                Icons.Default.VisibilityOff
                                            } else {
                                                Icons.Default.Visibility
                                            },
                                            contentDescription = if (isPasswordRevealed) {
                                                "Hide Password"
                                            } else {
                                                "Reveal Password"
                                            },
                                            tint = GhTextPrimary,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            if (repository.copyPasswordToClipboard()) {
                                                onCopiedFeedback("Copied WiFi password to clipboard")
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy Password",
                                            tint = GhAccentGreen,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (cfg.allowPasswordRotation && cfg.securityProtocol.requiresPassword) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    repository.rotateGuestPassword(
                                        wordFriendly = true,
                                        reason = "Quick rotation from Guest WiFi Popup"
                                    )
                                    onCopiedFeedback("Rotated Guest WiFi password & regenerated QR code")
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Autorenew,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Rotate Password & Regenerate QR",
                                color = GhAccentGreen,
                                fontFamily = GhFont,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Live Link & Guest Status Card
            item {
                val link = snapshot.linkTelemetry
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GhTileSurface)
                        .border(1.dp, GhCardBorder, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Live Network & Guest Status",
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryMiniTile(
                            label = "Band & Signal",
                            value = if (link.isWifiConnected) "${link.bandLabel} • ${link.signalPercent}%" else "Offline",
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryMiniTile(
                            label = "Active Guests",
                            value = "${snapshot.activeGuestCount} connected",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryMiniTile(
                            label = "Link Speed",
                            value = if (link.linkSpeedMbps > 0) "${link.linkSpeedMbps} Mbps" else "LAN Link",
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryMiniTile(
                            label = "Isolation",
                            value = if (cfg.guestIsolationEnabled) "Enabled" else "Standard",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (cfg.temporaryAccessActive && cfg.temporaryAccessExpiresEpochMs > nowEpochMs) {
                        val minsLeft = ((cfg.temporaryAccessExpiresEpochMs - nowEpochMs) / 60_000L).coerceAtLeast(1L)
                        val hrs = minsLeft / 60L
                        val remMins = minsLeft % 60L
                        val countdownText = if (hrs > 0) "${hrs}h ${remMins}m remaining" else "${remMins}m remaining"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(GhSunkenWell)
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = GhAccentWarm,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Temporary Access: $countdownText",
                                color = GhAccentWarm,
                                fontFamily = GhFont,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// SECTION 2: CONNECTED GUESTS MONITORING
// Shows: Connected Devices list, Device Count, Live Connection Status
// ============================================================================
@Composable
private fun ConnectedGuestsSection(
    repository: WifiRepository,
    snapshot: GuestWifiDashboardSnapshot
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(GhTileSurface)
                .border(1.dp, GhCardBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Router,
                    contentDescription = null,
                    tint = GhAccentGreen,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = "${snapshot.activeGuestCount} Active Guest ${if (snapshot.activeGuestCount == 1) "Device" else "Devices"}",
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Subnet ${snapshot.linkTelemetry.subnetPrefix.ifBlank { "LAN" }}.0/24 • Gateway ${snapshot.linkTelemetry.gatewayIpAddress.ifBlank { "Auto" }}",
                        color = GhTextSecondary,
                        fontFamily = GhFont,
                        fontSize = 11.sp
                    )
                }
            }

            OutlinedButton(
                onClick = { repository.triggerRefresh(forceLanScan = true) },
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = GhAccentGreen,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Scan LAN",
                    color = GhAccentGreen,
                    fontFamily = GhFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (snapshot.connectedGuests.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(GhTileSurface)
                    .border(1.dp, GhCardBorder, RoundedCornerShape(14.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Devices,
                        contentDescription = null,
                        tint = GhTextSecondary,
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No Guest Devices Detected Yet",
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "When visitors scan the Guest WiFi QR code, their connected devices appear here automatically.",
                        color = GhTextSecondary,
                        fontFamily = GhFont,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(snapshot.connectedGuests, key = { it.id }) { guest ->
                    val seenTime = Instant.ofEpochMilli(guest.lastSeenEpochMs)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(GhTileSurface)
                            .border(
                                1.dp,
                                if (guest.isCurrentlyReachable) GhAccentGreen.copy(alpha = 0.45f) else GhCardBorder,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(GhSunkenWell),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Devices,
                                    contentDescription = null,
                                    tint = if (guest.isCurrentlyReachable) GhAccentGreen else GhTextSecondary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = guest.hostName,
                                    color = GhTextPrimary,
                                    fontFamily = GhFont,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val macSegment = if (guest.macAddress.isNotBlank()) " • ${guest.macAddress}" else ""
                                Text(
                                    text = "${guest.ipAddress}$macSegment • ${guest.vendorOrCategory}",
                                    color = GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            val statusText = if (guest.isCurrentlyReachable) {
                                val rtt = guest.latencyMs?.let { " (${it}ms)" }.orEmpty()
                                "Connected$rtt"
                            } else {
                                "Idle • $seenTime"
                            }
                            Text(
                                text = statusText,
                                color = if (guest.isCurrentlyReachable) GhAccentGreen else GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Last seen $seenTime",
                                color = GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// SECTION 3: SECURITY CONTROLS
// Supports: Password Rotation, Temporary Access Timers, Guest Isolation, Audit Log
// ============================================================================
@Composable
private fun SecurityControlsSection(
    repository: WifiRepository,
    snapshot: GuestWifiDashboardSnapshot,
    nowEpochMs: Long,
    onStatusFeedback: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val cfg = snapshot.config

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // 1. Guest Isolation Control
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(GhTileSurface)
                    .border(1.dp, GhCardBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = GhAccentGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "Guest Network Isolation",
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Separates smart-home devices from visitor monitoring & enforces Clara guest protection.",
                                color = GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Switch(
                        checked = cfg.guestIsolationEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                repository.setGuestIsolationEnabled(enabled)
                                onStatusFeedback(
                                    if (enabled) "Guest Isolation enabled" else "Guest Isolation disabled"
                                )
                            }
                        }
                    )
                }
            }
        }

        // 2. Temporary Guest Access Window
        if (cfg.allowTemporaryAccess) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GhTileSurface)
                        .border(1.dp, GhCardBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = GhAccentWarm,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "Temporary Guest Access Window",
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            val subtitle = if (cfg.temporaryAccessActive && cfg.temporaryAccessExpiresEpochMs > nowEpochMs) {
                                val expStr = Instant.ofEpochMilli(cfg.temporaryAccessExpiresEpochMs)
                                    .atZone(ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK))
                                "Active until $expStr"
                            } else if (cfg.isTemporaryAccessExpired(nowEpochMs)) {
                                "Expired — Choose a duration below to re-enable QR scanning"
                            } else {
                                "Always active — Select a preset to limit guest QR validity"
                            }
                            Text(
                                text = subtitle,
                                color = GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TemporaryAccessDurationPreset.entries.forEach { preset ->
                            val isSelected = (!cfg.temporaryAccessActive && preset == TemporaryAccessDurationPreset.OFF) ||
                                    (cfg.temporaryAccessActive && cfg.temporaryAccessDurationMinutes == preset.durationMinutes)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        if (isSelected) GhAccentGreen else GhSunkenWell
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) GhAccentGreen else GhCardBorder,
                                        RoundedCornerShape(9.dp)
                                    )
                                    .clickable {
                                        scope.launch {
                                            repository.applyTemporaryAccessPreset(preset)
                                            onStatusFeedback("Temporary access set to ${preset.label}")
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = preset.label,
                                    color = if (isSelected) Color(0xFF101418) else GhTextPrimary,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Password Rotation Controls & Audit Log
        if (cfg.allowPasswordRotation) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GhTileSurface)
                        .border(1.dp, GhCardBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = GhAccentGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "Dynamic Password Rotation",
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Generates a fresh cryptographic passphrase in ClaraSecretVault and updates the QR code immediately.",
                                color = GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    repository.rotateGuestPassword(
                                        wordFriendly = true,
                                        reason = "Rotated memorable passphrase"
                                    )
                                    onStatusFeedback("Generated memorable guest passphrase & updated QR")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GhAccentGreen,
                                contentColor = Color(0xFF101418)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Rotate (Memorable)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    repository.rotateGuestPassword(
                                        wordFriendly = false,
                                        reason = "Rotated 16-char high-entropy password"
                                    )
                                    onStatusFeedback("Generated 16-char high-entropy password & updated QR")
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Rotate (16-Char Random)",
                                color = GhAccentGreen,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (snapshot.rotationHistory.isNotEmpty()) {
                        HorizontalDivider(color = GhCardBorder)
                        Text(
                            text = "Recent Rotation Audit Log",
                            color = GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        snapshot.rotationHistory.take(4).forEach { rec ->
                            val ts = Instant.ofEpochMilli(rec.rotatedAtEpochMs)
                                .atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.UK))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(GhSunkenWell)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$ts • ${rec.reason}",
                                    color = GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = rec.maskedPreview,
                                    color = GhTextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryMiniTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(GhSunkenWell)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = GhTextSecondary,
            fontFamily = GhFont,
            fontSize = 10.sp
        )
        Text(
            text = value,
            color = GhTextPrimary,
            fontFamily = GhFont,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}