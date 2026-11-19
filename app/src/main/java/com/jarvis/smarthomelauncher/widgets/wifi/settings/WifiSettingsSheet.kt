package com.jarvis.smarthomelauncher.widgets.wifi.Settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.smarthomelauncher.SfHouseSunkenTileContainer
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.widgets.wifi.Models.*
import com.jarvis.smarthomelauncher.widgets.wifi.Repository.WifiRepository
import kotlinx.coroutines.launch

/**
 * CWAS Rule 8 & Rule 16: Standalone SF-House Settings Dialog for Guest WiFi Widget v1.0.
 */
@Composable
fun WifiSettingsDialog(
    repository: WifiRepository,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        SfHouseSunkenTileContainer(
            outerCornerRadius = 26.dp,
            innerCornerRadius = 20.dp,
            sunkenInsetPadding = 4.dp,
            innerBackgroundColor = GhBackground,
            innerBorderColor = GhCardBorder,
            modifier = Modifier
                .fillMaxWidth(0.76f)
                .fillMaxHeight(0.84f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(22.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                                imageVector = Icons.Default.Wifi,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Guest WiFi Settings",
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Network credentials, dynamic QR options, guest monitoring & security policies",
                                color = GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 12.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(GhTileSurfaceElevated)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Settings",
                            tint = GhTextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                WifiSettingsContent(
                    repository = repository,
                    onSavedCallback = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun WifiSettingsContent(
    repository: WifiRepository,
    onSavedCallback: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val liveHardwareSsid by repository.liveHardwareSsid.collectAsState()

    var localConfig by remember { mutableStateOf(repository.getConfigurationSync()) }
    var ssidInput by remember { mutableStateOf(localConfig.ssid) }
    var displayNameInput by remember { mutableStateOf(localConfig.networkDisplayName) }
    var passwordInput by remember { mutableStateOf(repository.getPasswordSync()) }
    var revealPassword by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun persistConfigImmediate(updated: GuestWifiConfiguration) {
        localConfig = updated
        scope.launch {
            repository.saveCredentialsAndConfiguration(
                updatedConfig = updated.copy(
                    ssid = ssidInput.trim(),
                    networkDisplayName = displayNameInput.trim().ifBlank { "Household Guest WiFi" }
                ),
                newPassword = passwordInput
            )
        }
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        if (!statusMessage.isNullOrBlank()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(GhTileSurfaceElevated)
                        .border(1.dp, GhAccentGreen.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = statusMessage.orEmpty(),
                        color = GhAccentGreen,
                        fontFamily = GhFont,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // ====================================================================
        // 1. NETWORK CREDENTIALS & CLARA SECRET VAULT ENCRYPTED PASSWORD
        // ====================================================================
        item {
            WifiSettingsSectionCard(
                title = "Guest Network Credentials",
                subtitle = "Configure the SSID and password encoded into the scannable ISO/IEC 18004 QR code. Passwords are stored encrypted in ClaraSecretVault."
            ) {
                if (liveHardwareSsid.isNotBlank() && ssidInput != liveHardwareSsid) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(GhSunkenWell)
                            .border(1.dp, GhAccentGreen.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .clickable {
                                ssidInput = liveHardwareSsid
                                if (displayNameInput.isBlank()) {
                                    displayNameInput = "Household Guest WiFi"
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Detected Active Wi-Fi: $liveHardwareSsid",
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "Tap to Autofill SSID",
                            color = GhAccentGreen,
                            fontFamily = GhFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                OutlinedTextField(
                    value = displayNameInput,
                    onValueChange = { displayNameInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Friendly Network Label", fontSize = 12.sp) },
                    placeholder = { Text("e.g. Household Guest WiFi", color = GhTextSecondary, fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = GhTextPrimary,
                        unfocusedTextColor = GhTextPrimary,
                        focusedBorderColor = GhAccentGreen,
                        unfocusedBorderColor = GhCardBorder,
                        focusedContainerColor = GhSunkenWell,
                        unfocusedContainerColor = GhSunkenWell,
                        focusedLabelColor = GhAccentGreen,
                        unfocusedLabelColor = GhTextSecondary
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = ssidInput,
                    onValueChange = { ssidInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Wi-Fi Network Name (SSID)", fontSize = 12.sp) },
                    placeholder = { Text("Enter exact router SSID (case-sensitive)", color = GhTextSecondary, fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Wifi, contentDescription = null, tint = GhTextSecondary)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = GhTextPrimary,
                        unfocusedTextColor = GhTextPrimary,
                        focusedBorderColor = GhAccentGreen,
                        unfocusedBorderColor = GhCardBorder,
                        focusedContainerColor = GhSunkenWell,
                        unfocusedContainerColor = GhSunkenWell,
                        focusedLabelColor = GhAccentGreen,
                        unfocusedLabelColor = GhTextSecondary
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Authentication & Encryption Protocol",
                    color = GhTextSecondary,
                    fontFamily = GhFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WifiSecurityProtocol.entries.forEach { protocol ->
                        val selected = localConfig.securityProtocol == protocol
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) GhAccentGreen.copy(alpha = 0.2f) else GhSunkenWell
                                )
                                .border(
                                    1.dp,
                                    if (selected) GhAccentGreen else GhCardBorder,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    localConfig = localConfig.copy(securityProtocol = protocol)
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = protocol.displayName,
                                color = if (selected) GhAccentGreen else GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (localConfig.securityProtocol.requiresPassword) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            visualTransformation = if (revealPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            label = { Text("Wi-Fi Password (Hidden by Default)", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(imageVector = Icons.Default.Key, contentDescription = null, tint = GhTextSecondary)
                            },
                            trailingIcon = {
                                IconButton(onClick = { revealPassword = !revealPassword }) {
                                    Icon(
                                        imageVector = if (revealPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (revealPassword) "Hide Password" else "Reveal Password",
                                        tint = GhTextSecondary
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = GhTextPrimary,
                                unfocusedTextColor = GhTextPrimary,
                                focusedBorderColor = GhAccentGreen,
                                unfocusedBorderColor = GhCardBorder,
                                focusedContainerColor = GhSunkenWell,
                                unfocusedContainerColor = GhSunkenWell,
                                focusedLabelColor = GhAccentGreen,
                                unfocusedLabelColor = GhTextSecondary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val rotated = repository.rotateGuestPassword(
                                        wordFriendly = true,
                                        reason = "Generated passphrase in Settings"
                                    )
                                    passwordInput = rotated
                                    revealPassword = true
                                    statusMessage = "Generated new guest passphrase & refreshed QR."
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(54.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Autorenew,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Generate",
                                color = GhAccentGreen,
                                fontFamily = GhFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                WifiToggleSettingRow(
                    title = "Hidden SSID Broadcast",
                    subtitle = "Enable only if your guest router hides its SSID beacon (`H:true` QR flag).",
                    checked = localConfig.isHiddenSsid,
                    onCheckedChange = { localConfig = localConfig.copy(isHiddenSsid = it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        val trimmedSsid = ssidInput.trim()
                        if (trimmedSsid.isBlank()) {
                            statusMessage = "Please enter a valid Wi-Fi SSID."
                            return@Button
                        }
                        if (localConfig.securityProtocol.requiresPassword && passwordInput.length < 8 &&
                            localConfig.securityProtocol != WifiSecurityProtocol.WEP
                        ) {
                            statusMessage = "WPA2/WPA3 passwords must be at least 8 characters."
                            return@Button
                        }
                        scope.launch {
                            val updated = localConfig.copy(
                                ssid = trimmedSsid,
                                networkDisplayName = displayNameInput.trim().ifBlank { "Household Guest WiFi" }
                            )
                            localConfig = updated
                            repository.saveCredentialsAndConfiguration(
                                updatedConfig = updated,
                                newPassword = passwordInput
                            )
                            statusMessage = "Saved Guest WiFi credentials & regenerated dynamic QR code."
                            onSavedCallback?.invoke()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GhAccentGreen,
                        contentColor = Color(0xFF101418)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Save Guest WiFi & Update QR Code",
                        fontFamily = GhFont,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // ====================================================================
        // 2. WIDGET DISPLAY CONFIGURATION
        // ====================================================================
        item {
            WifiSettingsSectionCard(
                title = "Dashboard Widget Display Elements",
                subtitle = "Control which elements appear alongside the scannable QR code on the main dashboard."
            ) {
                WifiToggleSettingRow(
                    title = "Show Scannable QR Code",
                    subtitle = "Primary high-contrast QR symbol readable from 1–2 metres.",
                    checked = localConfig.showQrCodeOnWidget,
                    onCheckedChange = {
                        persistConfigImmediate(localConfig.copy(showQrCodeOnWidget = it))
                    }
                )
                WifiToggleSettingRow(
                    title = "Show Network SSID Beneath QR Code",
                    subtitle = "Displays the exact Wi-Fi SSID directly underneath the QR symbol.",
                    checked = localConfig.showSsidOnWidget,
                    onCheckedChange = {
                        persistConfigImmediate(localConfig.copy(showSsidOnWidget = it))
                    }
                )
                WifiToggleSettingRow(
                    title = "Show Friendly Network Name",
                    subtitle = "Shows your custom household label (e.g. 'Household Guest WiFi').",
                    checked = localConfig.showWifiNameOnWidget,
                    onCheckedChange = {
                        persistConfigImmediate(localConfig.copy(showWifiNameOnWidget = it))
                    }
                )
                WifiToggleSettingRow(
                    title = "Show Network Status Pill",
                    subtitle = "Displays live Online / Signal / Temporary Access status.",
                    checked = localConfig.showGuestStatusOnWidget,
                    onCheckedChange = {
                        persistConfigImmediate(localConfig.copy(showGuestStatusOnWidget = it))
                    }
                )
                WifiToggleSettingRow(
                    title = "Show Connected Guests Count",
                    subtitle = "Displays active connected guest count on the widget card.",
                    checked = localConfig.showConnectedGuestsOnWidget,
                    onCheckedChange = {
                        persistConfigImmediate(localConfig.copy(showConnectedGuestsOnWidget = it))
                    }
                )
            }
        }

        // ====================================================================
        // 3. GUEST MONITORING & SECURITY POLICIES
        // ====================================================================
        item {
            WifiSettingsSectionCard(
                title = "Guest Monitoring & Security Controls",
                subtitle = "Manage LAN guest discovery, password rotation permissions, temporary access windows, and smart-home isolation."
            ) {
                WifiToggleSettingRow(
                    title = "Enable Live Guest Device Monitoring",
                    subtitle = "Monitors active client devices and connection status on the local subnet.",
                    checked = localConfig.guestMonitoringEnabled,
                    onCheckedChange = {
                        persistConfigImmediate(localConfig.copy(guestMonitoringEnabled = it))
                    }
                )
                WifiToggleSettingRow(
                    title = "Enable Guest Isolation Mode",
                    subtitle = "Hides smart-home infrastructure endpoints from the guest list and enforces guest boundary rules.",
                    checked = localConfig.guestIsolationEnabled,
                    onCheckedChange = {
                        persistConfigImmediate(localConfig.copy(guestIsolationEnabled = it))
                    }
                )
                WifiToggleSettingRow(
                    title = "Allow One-Tap Password Rotation",
                    subtitle = "Allows instant passphrase regeneration and QR refresh inside the Guest WiFi Hub.",
                    checked = localConfig.allowPasswordRotation,
                    onCheckedChange = {
                        persistConfigImmediate(localConfig.copy(allowPasswordRotation = it))
                    }
                )
                WifiToggleSettingRow(
                    title = "Allow Temporary Access Windows",
                    subtitle = "Enables timed 2h / 4h / 8h / 24h / 48h guest access sessions.",
                    checked = localConfig.allowTemporaryAccess,
                    onCheckedChange = {
                        persistConfigImmediate(localConfig.copy(allowTemporaryAccess = it))
                    }
                )
            }
        }

        // ====================================================================
        // 4. NOTIFICATIONS & SMART HOME AUTOMATIONS
        // ====================================================================
        item {
            WifiSettingsSectionCard(
                title = "Notifications & Clara Smart Home Automations",
                subtitle = "Receive alerts when guests join or temporary access expires, and automate security actions."
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = GhAccentGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Enable Guest WiFi Notifications",
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Switch(
                        checked = localConfig.notificationsEnabled,
                        onCheckedChange = {
                            persistConfigImmediate(localConfig.copy(notificationsEnabled = it))
                        }
                    )
                }

                if (localConfig.notificationsEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        WifiNotificationEventType.entries.forEach { ev ->
                            val enabled = ev in localConfig.enabledNotificationEvents
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (enabled) GhTileSurfaceElevated else GhSunkenWell)
                                    .border(
                                        1.dp,
                                        if (enabled) GhAccentGreen.copy(alpha = 0.55f) else GhCardBorder,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        val nextSet = if (enabled) {
                                            localConfig.enabledNotificationEvents - ev
                                        } else {
                                            localConfig.enabledNotificationEvents + ev
                                        }
                                        persistConfigImmediate(localConfig.copy(enabledNotificationEvents = nextSet))
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = ev.displayName,
                                    color = if (enabled) GhTextPrimary else GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp
                                )
                                Checkbox(checked = enabled, onCheckedChange = null)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = GhCardBorder)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = GhAccentGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Enable Guest WiFi Automations",
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Switch(
                        checked = localConfig.automationsEnabled,
                        onCheckedChange = {
                            persistConfigImmediate(localConfig.copy(automationsEnabled = it))
                        }
                    )
                }

                if (localConfig.automationsEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        WifiAutomationHook.entries.forEach { hook ->
                            val enabled = hook in localConfig.enabledAutomationHooks
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (enabled) GhTileSurfaceElevated else GhSunkenWell)
                                    .border(
                                        1.dp,
                                        if (enabled) GhAccentGreen.copy(alpha = 0.55f) else GhCardBorder,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        val nextHooks = if (enabled) {
                                            localConfig.enabledAutomationHooks - hook
                                        } else {
                                            localConfig.enabledAutomationHooks + hook
                                        }
                                        persistConfigImmediate(localConfig.copy(enabledAutomationHooks = nextHooks))
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = hook.displayName,
                                    color = if (enabled) GhTextPrimary else GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Checkbox(checked = enabled, onCheckedChange = null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WifiSettingsSectionCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GhTileSurface)
            .border(1.dp, GhCardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            color = GhTextPrimary,
            fontFamily = GhFont,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            color = GhTextSecondary,
            fontFamily = GhFont,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun WifiToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = GhTextPrimary,
                fontFamily = GhFont,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = GhTextSecondary,
                fontFamily = GhFont,
                fontSize = 11.sp
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}