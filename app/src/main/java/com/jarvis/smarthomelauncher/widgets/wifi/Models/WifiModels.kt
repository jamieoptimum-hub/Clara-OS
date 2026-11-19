package com.jarvis.smarthomelauncher.widgets.wifi.Models

/**
 * CWAS Rule 12: Discoverable manifest metadata for Guest WiFi Widget v1.0.
 */
data class WifiWidgetManifestDescriptor(
    val id: String = "guest-wifi",
    val name: String = "Guest WiFi",
    val feature: String = "Guest WiFi Widget",
    val application: String = "Clara Smart Home",
    val version: String = "1.0",
    val description: String = "Allow guests to quickly connect to the household WiFi network using a QR code without requiring verbal password sharing.",
    val headerTitle: String = "Guest WiFi",
    val headerIcon: String = "wifi",
    val headerClickable: Boolean = true,
    val headerAction: String = "OpenGuestWifiPopup",
    val launchMode: String = "popup",
    val popupWidthFraction: Float = 0.60f,
    val popupHeightFraction: Float = 0.70f,
    val supportsPopup: Boolean = true,
    val supportsSettings: Boolean = true,
    val supportsNotifications: Boolean = true,
    val supportsAutomations: Boolean = true,
    val supportsVoiceAssistant: Boolean = true
)

/**
 * Standard Wi-Fi authentication types supported by ISO/IEC 18004 `WIFI:` URI schema on iOS & Android.
 */
enum class WifiSecurityProtocol(val qrToken: String, val displayName: String) {
    WPA2_WPA3("WPA", "WPA2 / WPA3 Personal"),
    SAE_WPA3("SAE", "WPA3-SAE Only"),
    WEP("WEP", "WEP (Legacy)"),
    OPEN("nopass", "Open Network (No Password)");

    val requiresPassword: Boolean
        get() = this != OPEN

    companion object {
        fun fromQrTokenOrName(raw: String): WifiSecurityProtocol {
            val cleaned = raw.trim()
            return entries.firstOrNull {
                it.name.equals(cleaned, ignoreCase = true) ||
                        it.qrToken.equals(cleaned, ignoreCase = true)
            } ?: WPA2_WPA3
        }
    }
}

/**
 * Network health state derived from live Android ConnectivityManager & WifiManager telemetry.
 */
enum class GuestNetworkHealthStatus(val label: String) {
    ONLINE("Online • Ready to Scan"),
    LIMITED_NO_INTERNET("Connected • No Internet"),
    PAUSED_EXPIRED("Guest Access Expired"),
    DISCONNECTED("Wi-Fi Offline")
}

/**
 * Popup sections inside the 60% x 70% Guest WiFi Hub Popup.
 */
enum class WifiPopupSection(val title: String) {
    QR_AND_CREDENTIALS("WiFi & QR"),
    CONNECTED_GUESTS("Guests"),
    SECURITY("Security")
}

/**
 * Duration presets for temporary guest Wi-Fi access windows.
 */
enum class TemporaryAccessDurationPreset(val label: String, val durationMinutes: Int) {
    OFF("Unlimited (Always Active)", 0),
    TWO_HOURS("2 Hours", 120),
    FOUR_HOURS("4 Hours", 240),
    EIGHT_HOURS("8 Hours", 480),
    TWENTY_FOUR_HOURS("24 Hours", 1440),
    WEEKEND_48H("48 Hours (Weekend)", 2880)
}

/**
 * Notification events emitted by Guest WiFi Widget.
 */
enum class WifiNotificationEventType(val displayName: String) {
    NEW_GUEST_CONNECTED("New Guest Device Connected"),
    TEMPORARY_ACCESS_EXPIRING("Temporary Access Expiring Soon"),
    TEMPORARY_ACCESS_EXPIRED("Temporary Access Expired"),
    PASSWORD_ROTATED("Guest Password Rotated"),
    NETWORK_OFFLINE_ALERT("Household Wi-Fi Offline")
}

/**
 * Smart Home automation hooks exposed by Guest WiFi Widget.
 */
enum class WifiAutomationHook(val displayName: String, val eventCode: String) {
    ENABLE_GUEST_ROLE_WHEN_ISOLATED("Enforce Clara Guest Lock when Isolation active", "WIFI_ENFORCE_GUEST_LOCK"),
    ANNOUNCE_NEW_GUEST_TTS("Voice announce new guest device connection", "WIFI_ANNOUNCE_NEW_GUEST"),
    AUTO_ROTATE_ON_EXPIRY("Auto-rotate password when temporary window expires", "WIFI_AUTO_ROTATE_ON_EXPIRY")
}

/**
 * Database-backed Guest WiFi configuration (Password itself is stored encrypted in ClaraSecretVault).
 */
data class GuestWifiConfiguration(
    val ssid: String = "",
    val networkDisplayName: String = "Household Guest WiFi",
    val securityProtocol: WifiSecurityProtocol = WifiSecurityProtocol.WPA2_WPA3,
    val isHiddenSsid: Boolean = false,
    val showQrCodeOnWidget: Boolean = true,
    val showSsidOnWidget: Boolean = true,
    val showWifiNameOnWidget: Boolean = true,
    val showGuestStatusOnWidget: Boolean = true,
    val showConnectedGuestsOnWidget: Boolean = true,
    val guestMonitoringEnabled: Boolean = true,
    val allowPasswordRotation: Boolean = true,
    val allowTemporaryAccess: Boolean = true,
    val temporaryAccessActive: Boolean = false,
    val temporaryAccessExpiresEpochMs: Long = 0L,
    val temporaryAccessDurationMinutes: Int = 0,
    val guestIsolationEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val enabledNotificationEvents: Set<WifiNotificationEventType> = WifiNotificationEventType.entries.toSet(),
    val automationsEnabled: Boolean = true,
    val enabledAutomationHooks: Set<WifiAutomationHook> = setOf(
        WifiAutomationHook.AUTO_ROTATE_ON_EXPIRY
    ),
    val lastPasswordRotatedEpochMs: Long = 0L
) {
    fun isTemporaryAccessExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        return temporaryAccessActive &&
                temporaryAccessExpiresEpochMs > 0L &&
                nowEpochMs >= temporaryAccessExpiresEpochMs
    }
}

/**
 * Pure boolean grid representation of an ISO/IEC 18004 QR Code symbol.
 */
data class WifiQrMatrix(
    val size: Int,
    val modules: BooleanArray,
    val encodedPayload: String
) {
    fun isDark(x: Int, y: Int): Boolean {
        if (x !in 0 until size || y !in 0 until size) return false
        return modules[y * size + x]
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WifiQrMatrix) return false
        return size == other.size &&
                encodedPayload == other.encodedPayload &&
                modules.contentEquals(other.modules)
    }

    override fun hashCode(): Int {
        var result = size
        result = 31 * result + modules.contentHashCode()
        result = 31 * result + encodedPayload.hashCode()
        return result
    }
}

/**
 * Live OS-level Wi-Fi & LAN interface telemetry.
 */
data class LiveWifiLinkTelemetry(
    val isWifiConnected: Boolean,
    val hasValidatedInternet: Boolean,
    val activeHardwareSsid: String,
    val rssiDbm: Int,
    val signalPercent: Int,
    val linkSpeedMbps: Int,
    val frequencyMhz: Int,
    val bandLabel: String,
    val localIpAddress: String,
    val gatewayIpAddress: String,
    val subnetPrefix: String
)

/**
 * Connected client device discovered on the local network and persisted in SQLite guest history.
 */
data class ConnectedGuestDevice(
    val id: String,
    val ipAddress: String,
    val macAddress: String = "",
    val hostName: String,
    val vendorOrCategory: String = "Guest Device",
    val isCurrentlyReachable: Boolean = true,
    val latencyMs: Int? = null,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    val isKnownSmartHomeDevice: Boolean = false
)

/**
 * Audit record stored in SQLite whenever the guest password is updated or rotated.
 */
data class PasswordRotationRecord(
    val id: Long = 0L,
    val rotatedAtEpochMs: Long,
    val reason: String,
    val ssid: String,
    val maskedPreview: String
)

/**
 * Complete Ready-state snapshot powering both the 1–2m scannable Dashboard Widget and 60% x 70% Popup.
 */
data class GuestWifiDashboardSnapshot(
    val config: GuestWifiConfiguration,
    val passwordPlaintext: String,
    val wifiQrPayload: String,
    val qrMatrix: WifiQrMatrix,
    val healthStatus: GuestNetworkHealthStatus,
    val linkTelemetry: LiveWifiLinkTelemetry,
    val connectedGuests: List<ConnectedGuestDevice>,
    val activeGuestCount: Int,
    val rotationHistory: List<PasswordRotationRecord>,
    val providerLabel: String,
    val lastUpdatedEpochMs: Long
)

/**
 * CWAS Rule 11: Mandatory core states (Loading, Empty, Error, Ready).
 */
sealed interface WifiWidgetUiState {
    data object Loading : WifiWidgetUiState

    data class Empty(
        val title: String = "Guest WiFi Not Configured",
        val message: String = "Tap Guest WiFi to set your network SSID and password for instant QR scanning."
    ) : WifiWidgetUiState

    data class Error(
        val message: String = "Unable to generate Guest WiFi QR",
        val technicalDetail: String = ""
    ) : WifiWidgetUiState

    data class Ready(val snapshot: GuestWifiDashboardSnapshot) : WifiWidgetUiState
}

/**
 * Natural-language voice assistant response for ClaraVoiceAssistantManager.
 */
data class WifiVoiceCommandResponse(
    val handled: Boolean,
    val spokenText: String,
    val openPopupSection: WifiPopupSection? = null
)