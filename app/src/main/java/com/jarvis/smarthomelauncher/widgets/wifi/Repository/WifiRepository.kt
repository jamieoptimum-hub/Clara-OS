package com.jarvis.smarthomelauncher.widgets.wifi.Repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.jarvis.smarthomelauncher.core.ClaraApplication
import com.jarvis.smarthomelauncher.core.ClaraSecretVault
import com.jarvis.smarthomelauncher.widgets.wifi.Models.*
import com.jarvis.smarthomelauncher.widgets.wifi.Services.AndroidLanGuestWifiProvider
import com.jarvis.smarthomelauncher.widgets.wifi.Services.WifiDataProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Emitted when a Guest WiFi automation rule triggers (isolation lock, new guest TTS, expiry rotation).
 */
data class WifiAutomationDispatchEvent(
    val hook: WifiAutomationHook,
    val summary: String,
    val guestDevice: ConnectedGuestDevice? = null
)

/**
 * CWAS Rule 8: Dedicated SQLite database helper for Guest WiFi Widget v1.0.
 * Stores Guest WiFi configuration, discovered guest device telemetry, password rotation audit logs,
 * and notification deduplication keys. The Wi-Fi password itself is stored encrypted in ClaraSecretVault.
 */
class WifiDatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PREFERENCES (
                pref_key TEXT PRIMARY KEY NOT NULL,
                pref_value TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_GUEST_DEVICES (
                device_id TEXT PRIMARY KEY NOT NULL,
                ip_address TEXT NOT NULL,
                mac_address TEXT NOT NULL,
                host_name TEXT NOT NULL,
                vendor_category TEXT NOT NULL,
                is_reachable INTEGER NOT NULL DEFAULT 1,
                latency_ms INTEGER,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                is_smart_home_device INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ROTATION_HISTORY (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                rotated_at_ms INTEGER NOT NULL,
                reason TEXT NOT NULL,
                ssid TEXT NOT NULL,
                masked_preview TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_NOTIFICATION_LOG (
                dedup_key TEXT PRIMARY KEY NOT NULL,
                event_type TEXT NOT NULL,
                fired_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    companion object {
        private const val DATABASE_NAME = "clara_guest_wifi_v1.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_PREFERENCES = "wifi_preferences"
        const val TABLE_GUEST_DEVICES = "wifi_guest_devices"
        const val TABLE_ROTATION_HISTORY = "wifi_rotation_history"
        const val TABLE_NOTIFICATION_LOG = "wifi_notification_log"

        @Volatile
        private var instance: WifiDatabaseHelper? = null

        fun getInstance(context: Context): WifiDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: WifiDatabaseHelper(context).also { instance = it }
            }
        }
    }
}

/**
 * CWAS Rule 7: Repository-first single source of truth for Guest WiFi Widget v1.0.
 * Widget ↓ WifiRepository ↓ WifiDatabaseHelper (SQLite) + ClaraSecretVault ↓ WifiDataProvider.
 */
class WifiRepository private constructor(context: Context) {

    private val appContext: Context = ClaraApplication.requireAppContext(context)
    private val dbHelper: WifiDatabaseHelper = WifiDatabaseHelper.getInstance(appContext)
    private val dataProvider: WifiDataProvider = AndroidLanGuestWifiProvider(appContext)
    private val gson = Gson()
    private val refreshMutex = Mutex()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow<WifiWidgetUiState>(WifiWidgetUiState.Loading)
    val uiState: StateFlow<WifiWidgetUiState> = _uiState.asStateFlow()

    private val _liveHardwareSsid = MutableStateFlow("")
    val liveHardwareSsid: StateFlow<String> = _liveHardwareSsid.asStateFlow()

    private val _automationEvents = MutableSharedFlow<WifiAutomationDispatchEvent>(extraBufferCapacity = 8)
    val automationEvents: SharedFlow<WifiAutomationDispatchEvent> = _automationEvents.asSharedFlow()

    @Volatile
    private var knownSmartHomeIps: Set<String> = emptySet()

    @Volatile
    private var knownSmartHomeMacs: Set<String> = emptySet()

    private var monitorJob: Job? = null
    private var lastSubnetScanEpochMs: Long = 0L
    private var ttsEngine: TextToSpeech? = null

    @Volatile
    private var ttsReady: Boolean = false

    init {
        ensureNotificationChannel()
        repositoryScope.launch {
            refreshSnapshot(forceLanScan = true)
        }
        startAutonomousMonitoringLoop()
    }

    /**
     * Allows WifiManifest to supply Clara's registered smart-home tile IPs & MACs so Guest Isolation
     * cleanly separates permanent smart-home hardware from visitor phones/laptops.
     */
    fun updateKnownSmartHomeEndpoints(ips: Set<String>, macs: Set<String>) {
        val cleanedIps = ips.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val cleanedMacs = macs.map { it.trim().uppercase(Locale.UK) }.filter { it.isNotBlank() }.toSet()
        if (cleanedIps != knownSmartHomeIps || cleanedMacs != knownSmartHomeMacs) {
            knownSmartHomeIps = cleanedIps
            knownSmartHomeMacs = cleanedMacs
            triggerRefresh(forceLanScan = false)
        }
    }

    private fun startAutonomousMonitoringLoop() {
        monitorJob?.cancel()
        monitorJob = repositoryScope.launch {
            while (isActive) {
                delay(20_000L)
                val now = System.currentTimeMillis()
                val shouldScanSubnet = (now - lastSubnetScanEpochMs) >= 60_000L
                refreshSnapshot(forceLanScan = shouldScanSubnet)
            }
        }
    }

    fun triggerRefresh(forceLanScan: Boolean = true) {
        repositoryScope.launch {
            refreshSnapshot(forceLanScan = forceLanScan)
        }
    }

    suspend fun refreshSnapshot(forceLanScan: Boolean = false) = refreshMutex.withLock {
        try {
            var config = loadConfigurationFromDb()
            var password = getGuestPasswordFromVault()
            val nowMs = System.currentTimeMillis()

            val linkTelemetry = dataProvider.readLiveWifiLinkTelemetry()
            if (linkTelemetry.activeHardwareSsid.isNotBlank()) {
                _liveHardwareSsid.value = linkTelemetry.activeHardwareSsid
            }

            // Evaluate Temporary Guest Access Expiry & Auto-Rotation Policy
            if (config.temporaryAccessActive && config.temporaryAccessExpiresEpochMs > 0L) {
                val remainingMs = config.temporaryAccessExpiresEpochMs - nowMs
                if (remainingMs in 1..(15 * 60_000L) &&
                    config.notificationsEnabled &&
                    WifiNotificationEventType.TEMPORARY_ACCESS_EXPIRING in config.enabledNotificationEvents
                ) {
                    dispatchNotificationOnce(
                        dedupKey = "expiring_${config.temporaryAccessExpiresEpochMs}",
                        eventType = WifiNotificationEventType.TEMPORARY_ACCESS_EXPIRING,
                        title = "Guest WiFi Expiring Soon",
                        body = "Temporary guest access for ${config.ssid} expires in ${(remainingMs / 60_000L).coerceAtLeast(1)} minutes."
                    )
                } else if (remainingMs <= 0L) {
                    val expKey = "expired_${config.temporaryAccessExpiresEpochMs}"
                    if (config.notificationsEnabled &&
                        WifiNotificationEventType.TEMPORARY_ACCESS_EXPIRED in config.enabledNotificationEvents
                    ) {
                        dispatchNotificationOnce(
                            dedupKey = expKey,
                            eventType = WifiNotificationEventType.TEMPORARY_ACCESS_EXPIRED,
                            title = "Guest WiFi Access Expired",
                            body = "Temporary access window for ${config.ssid} has ended."
                        )
                    }
                    if (config.automationsEnabled &&
                        WifiAutomationHook.AUTO_ROTATE_ON_EXPIRY in config.enabledAutomationHooks &&
                        config.allowPasswordRotation &&
                        config.securityProtocol.requiresPassword
                    ) {
                        val newPass = dataProvider.generateStrongGuestPassword(wordFriendly = true)
                        saveGuestPasswordToVault(newPass)
                        password = newPass
                        recordRotationHistoryInDb(
                            ssid = config.ssid,
                            password = newPass,
                            reason = "Auto-rotated after temporary access window expired"
                        )
                        config = config.copy(
                            temporaryAccessActive = false,
                            temporaryAccessExpiresEpochMs = 0L,
                            lastPasswordRotatedEpochMs = nowMs
                        )
                        saveConfigurationToDb(config)
                        _automationEvents.tryEmit(
                            WifiAutomationDispatchEvent(
                                hook = WifiAutomationHook.AUTO_ROTATE_ON_EXPIRY,
                                summary = "Auto-rotated Guest WiFi password upon temporary window expiry"
                            )
                        )
                    }
                }
            }

            // Offline network alert check
            if (!linkTelemetry.isWifiConnected &&
                config.ssid.isNotBlank() &&
                config.notificationsEnabled &&
                WifiNotificationEventType.NETWORK_OFFLINE_ALERT in config.enabledNotificationEvents
            ) {
                val hourBucket = nowMs / 3_600_000L
                dispatchNotificationOnce(
                    dedupKey = "wifi_offline_$hourBucket",
                    eventType = WifiNotificationEventType.NETWORK_OFFLINE_ALERT,
                    title = "Household Wi-Fi Disconnected",
                    body = "Clara lost local Wi-Fi link; check router connectivity."
                )
            }

            // CWAS Rule 11: Empty state when user has not configured Guest Wi-Fi SSID yet
            if (config.ssid.isBlank()) {
                _uiState.value = WifiWidgetUiState.Empty(
                    title = "Guest WiFi Not Configured",
                    message = if (linkTelemetry.activeHardwareSsid.isNotBlank()) {
                        "Tap Guest WiFi to configure '${linkTelemetry.activeHardwareSsid}' or your dedicated Guest SSID."
                    } else {
                        "Tap Guest WiFi to enter your network SSID and password for instant QR scanning."
                    }
                )
                return@withLock
            }

            // Scan & reconcile connected guest devices when Guest Monitoring is enabled
            val guestDevices = if (config.guestMonitoringEnabled) {
                if (forceLanScan || loadGuestDevicesFromDb().isEmpty()) {
                    lastSubnetScanEpochMs = nowMs
                    val liveScanned = dataProvider.scanConnectedLanDevices(
                        linkTelemetry = linkTelemetry,
                        knownSmartHomeIps = knownSmartHomeIps,
                        knownSmartHomeMacs = knownSmartHomeMacs
                    )
                    reconcileScannedDevicesWithDb(liveScanned, config)
                } else {
                    loadGuestDevicesFromDb()
                }
            } else {
                emptyList()
            }

            val visibleGuests = if (config.guestIsolationEnabled) {
                guestDevices.filterNot { it.isKnownSmartHomeDevice }
            } else {
                guestDevices
            }

            val activeReachableGuests = visibleGuests.count { it.isCurrentlyReachable }

            val healthStatus = when {
                config.isTemporaryAccessExpired(nowMs) -> GuestNetworkHealthStatus.PAUSED_EXPIRED
                !linkTelemetry.isWifiConnected -> GuestNetworkHealthStatus.DISCONNECTED
                !linkTelemetry.hasValidatedInternet -> GuestNetworkHealthStatus.LIMITED_NO_INTERNET
                else -> GuestNetworkHealthStatus.ONLINE
            }

            val qrPayload = dataProvider.buildStandardWifiQrPayload(
                ssid = config.ssid,
                password = password,
                security = config.securityProtocol,
                isHidden = config.isHiddenSsid
            )
            val qrMatrix = dataProvider.generateQrMatrix(qrPayload)
            val rotationHistory = loadRotationHistoryFromDb(limit = 10)

            _uiState.value = WifiWidgetUiState.Ready(
                GuestWifiDashboardSnapshot(
                    config = config,
                    passwordPlaintext = password,
                    wifiQrPayload = qrPayload,
                    qrMatrix = qrMatrix,
                    healthStatus = healthStatus,
                    linkTelemetry = linkTelemetry,
                    connectedGuests = visibleGuests,
                    activeGuestCount = activeReachableGuests,
                    rotationHistory = rotationHistory,
                    providerLabel = dataProvider.providerName,
                    lastUpdatedEpochMs = nowMs
                )
            )
        } catch (e: Exception) {
            _uiState.value = WifiWidgetUiState.Error(
                message = "Unable to retrieve data",
                technicalDetail = e.message.orEmpty()
            )
        }
    }

    // ========================================================================
    // Configuration, Credentials & Security Actions
    // ========================================================================

    fun getConfigurationSync(): GuestWifiConfiguration = loadConfigurationFromDb()

    fun getPasswordSync(): String = getGuestPasswordFromVault()

    suspend fun saveCredentialsAndConfiguration(
        updatedConfig: GuestWifiConfiguration,
        newPassword: String
    ) = withContext(Dispatchers.IO) {
        val cleanSsid = updatedConfig.ssid.trim()
        val cleanPass = if (updatedConfig.securityProtocol.requiresPassword) newPassword else ""
        val previousPass = getGuestPasswordFromVault()
        val nowMs = System.currentTimeMillis()

        val finalConfig = if (cleanPass != previousPass && cleanSsid.isNotBlank()) {
            recordRotationHistoryInDb(
                ssid = cleanSsid,
                password = cleanPass,
                reason = "Credentials updated in Guest WiFi Settings"
            )
            updatedConfig.copy(ssid = cleanSsid, lastPasswordRotatedEpochMs = nowMs)
        } else {
            updatedConfig.copy(ssid = cleanSsid)
        }

        saveGuestPasswordToVault(cleanPass)
        saveConfigurationToDb(finalConfig)

        if (finalConfig.automationsEnabled &&
            finalConfig.guestIsolationEnabled &&
            WifiAutomationHook.ENABLE_GUEST_ROLE_WHEN_ISOLATED in finalConfig.enabledAutomationHooks
        ) {
            _automationEvents.tryEmit(
                WifiAutomationDispatchEvent(
                    hook = WifiAutomationHook.ENABLE_GUEST_ROLE_WHEN_ISOLATED,
                    summary = "Guest Isolation active on ${finalConfig.ssid}"
                )
            )
        }

        refreshSnapshot(forceLanScan = false)
    }

    suspend fun rotateGuestPassword(
        wordFriendly: Boolean = true,
        reason: String = "Manual password rotation"
    ): String = withContext(Dispatchers.IO) {
        val currentConfig = loadConfigurationFromDb()
        val generated = dataProvider.generateStrongGuestPassword(wordFriendly = wordFriendly)
        val nowMs = System.currentTimeMillis()

        saveGuestPasswordToVault(generated)
        val updatedConfig = currentConfig.copy(
            securityProtocol = if (currentConfig.securityProtocol == WifiSecurityProtocol.OPEN) {
                WifiSecurityProtocol.WPA2_WPA3
            } else {
                currentConfig.securityProtocol
            },
            temporaryAccessActive = false,
            temporaryAccessExpiresEpochMs = 0L,
            lastPasswordRotatedEpochMs = nowMs
        )
        saveConfigurationToDb(updatedConfig)

        if (updatedConfig.ssid.isNotBlank()) {
            recordRotationHistoryInDb(
                ssid = updatedConfig.ssid,
                password = generated,
                reason = reason
            )
        }

        if (updatedConfig.notificationsEnabled &&
            WifiNotificationEventType.PASSWORD_ROTATED in updatedConfig.enabledNotificationEvents
        ) {
            dispatchNotificationOnce(
                dedupKey = "rot_$nowMs",
                eventType = WifiNotificationEventType.PASSWORD_ROTATED,
                title = "Guest WiFi Password Rotated",
                body = "Dynamic QR code updated for ${updatedConfig.ssid.ifBlank { "Guest WiFi" }}."
            )
        }

        refreshSnapshot(forceLanScan = false)
        generated
    }

    suspend fun applyTemporaryAccessPreset(preset: TemporaryAccessDurationPreset) = withContext(Dispatchers.IO) {
        val current = loadConfigurationFromDb()
        val nowMs = System.currentTimeMillis()
        val updated = if (preset == TemporaryAccessDurationPreset.OFF || preset.durationMinutes <= 0) {
            current.copy(
                temporaryAccessActive = false,
                temporaryAccessExpiresEpochMs = 0L,
                temporaryAccessDurationMinutes = 0
            )
        } else {
            current.copy(
                temporaryAccessActive = true,
                temporaryAccessDurationMinutes = preset.durationMinutes,
                temporaryAccessExpiresEpochMs = nowMs + preset.durationMinutes * 60_000L
            )
        }
        saveConfigurationToDb(updated)
        refreshSnapshot(forceLanScan = false)
    }

    suspend fun setGuestIsolationEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        val current = loadConfigurationFromDb()
        val updated = current.copy(guestIsolationEnabled = enabled)
        saveConfigurationToDb(updated)
        if (enabled &&
            updated.automationsEnabled &&
            WifiAutomationHook.ENABLE_GUEST_ROLE_WHEN_ISOLATED in updated.enabledAutomationHooks
        ) {
            _automationEvents.tryEmit(
                WifiAutomationDispatchEvent(
                    hook = WifiAutomationHook.ENABLE_GUEST_ROLE_WHEN_ISOLATED,
                    summary = "Guest Isolation enabled"
                )
            )
        }
        refreshSnapshot(forceLanScan = false)
    }

    fun copyPasswordToClipboard(): Boolean {
        val pass = getGuestPasswordFromVault()
        if (pass.isBlank()) return false
        return runCatching {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            val clip = ClipData.newPlainText("Clara Guest WiFi Password", pass)
            cm.setPrimaryClip(clip)
            true
        }.getOrDefault(false)
    }

    fun copySsidToClipboard(): Boolean {
        val ssid = loadConfigurationFromDb().ssid
        if (ssid.isBlank()) return false
        return runCatching {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            val clip = ClipData.newPlainText("Clara Guest WiFi SSID", ssid)
            cm.setPrimaryClip(clip)
            true
        }.getOrDefault(false)
    }

    // ========================================================================
    // Voice Assistant Integration
    // ========================================================================

    fun handleVoiceCommand(rawUtterance: String): WifiVoiceCommandResponse {
        val q = rawUtterance.trim().lowercase(Locale.UK)
        val ready = (_uiState.value as? WifiWidgetUiState.Ready)?.snapshot

        if (q.contains("rotate") && q.contains("wifi")) {
            repositoryScope.launch { rotateGuestPassword(wordFriendly = true, reason = "Voice command rotation") }
            return WifiVoiceCommandResponse(
                handled = true,
                spokenText = "I have rotated the Guest Wi-Fi password and updated the dashboard QR code.",
                openPopupSection = WifiPopupSection.SECURITY
            )
        }

        if (q.contains("how many guest") || (q.contains("connected") && q.contains("wifi"))) {
            val count = ready?.activeGuestCount ?: 0
            val spoken = if (ready == null) {
                "Guest Wi-Fi is not configured yet."
            } else if (count == 1) {
                "There is currently 1 active guest device connected to ${ready.config.ssid}."
            } else {
                "There are currently $count active guest devices connected to ${ready.config.ssid}."
            }
            return WifiVoiceCommandResponse(
                handled = true,
                spokenText = spoken,
                openPopupSection = WifiPopupSection.CONNECTED_GUESTS
            )
        }

        if (q.contains("guest wifi") || q.contains("wifi qr") || q.contains("wifi network")) {
            val spoken = if (ready == null) {
                "Opening Guest Wi-Fi setup so you can configure your household network."
            } else {
                "Showing the QR code for ${ready.config.ssid}. Guests can scan the screen from up to two metres away."
            }
            return WifiVoiceCommandResponse(
                handled = true,
                spokenText = spoken,
                openPopupSection = WifiPopupSection.QR_AND_CREDENTIALS
            )
        }

        return WifiVoiceCommandResponse(handled = false, spokenText = "")
    }

    // ========================================================================
    // Internal SQLite & ClaraSecretVault Persistence
    // ========================================================================

    private fun getGuestPasswordFromVault(): String {
        return ClaraSecretVault.getString(appContext, VAULT_KEY_GUEST_WIFI_PASSWORD, "")
            .orEmpty()
    }

    private fun saveGuestPasswordToVault(password: String) {
        if (password.isEmpty()) {
            ClaraSecretVault.remove(appContext, VAULT_KEY_GUEST_WIFI_PASSWORD)
        } else {
            ClaraSecretVault.putString(appContext, VAULT_KEY_GUEST_WIFI_PASSWORD, password)
        }
    }

    private fun loadConfigurationFromDb(): GuestWifiConfiguration {
        dbHelper.readableDatabase.query(
            WifiDatabaseHelper.TABLE_PREFERENCES,
            arrayOf("pref_value"),
            "pref_key = ?",
            arrayOf(KEY_WIFI_CONFIG_JSON),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val raw = cursor.getString(0)
                return runCatching { gson.fromJson(raw, GuestWifiConfiguration::class.java) }
                    .getOrNull() ?: GuestWifiConfiguration()
            }
        }
        return GuestWifiConfiguration()
    }

    private fun saveConfigurationToDb(config: GuestWifiConfiguration) {
        val cv = ContentValues().apply {
            put("pref_key", KEY_WIFI_CONFIG_JSON)
            put("pref_value", gson.toJson(config))
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            WifiDatabaseHelper.TABLE_PREFERENCES,
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun reconcileScannedDevicesWithDb(
        scanned: List<ConnectedGuestDevice>,
        config: GuestWifiConfiguration
    ): List<ConnectedGuestDevice> {
        val existingById = loadGuestDevicesFromDb().associateBy { it.id }
        val nowMs = System.currentTimeMillis()
        val scannedIds = scanned.map { it.id }.toSet()
        val merged = ArrayList<ConnectedGuestDevice>()

        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // Mark previously seen devices that did not respond in this sweep as offline (retain last 24h)
            val cutoffMs = nowMs - 24L * 3_600_000L
            db.delete(
                WifiDatabaseHelper.TABLE_GUEST_DEVICES,
                "last_seen_ms < ?",
                arrayOf(cutoffMs.toString())
            )

            for ((id, prev) in existingById) {
                if (id !in scannedIds && prev.lastSeenEpochMs >= cutoffMs) {
                    val offlineCopy = prev.copy(isCurrentlyReachable = false, latencyMs = null)
                    upsertGuestDeviceRow(db, offlineCopy)
                    merged.add(offlineCopy)
                }
            }

            for (live in scanned) {
                val prior = existingById[live.id]
                val firstSeen = prior?.firstSeenEpochMs ?: nowMs
                val record = live.copy(
                    firstSeenEpochMs = firstSeen,
                    lastSeenEpochMs = nowMs,
                    isCurrentlyReachable = true
                )
                upsertGuestDeviceRow(db, record)
                merged.add(record)

                // Notify & optionally announce genuinely new non-smart-home guest connections
                if (prior == null && !record.isKnownSmartHomeDevice && existingById.isNotEmpty()) {
                    if (config.notificationsEnabled &&
                        WifiNotificationEventType.NEW_GUEST_CONNECTED in config.enabledNotificationEvents
                    ) {
                        dispatchNotificationOnce(
                            dedupKey = "guest_join_${record.id}_${nowMs / 3_600_000L}",
                            eventType = WifiNotificationEventType.NEW_GUEST_CONNECTED,
                            title = "New Guest Connected",
                            body = "${record.hostName} (${record.ipAddress}) joined ${config.ssid}"
                        )
                    }
                    if (config.automationsEnabled &&
                        WifiAutomationHook.ANNOUNCE_NEW_GUEST_TTS in config.enabledAutomationHooks
                    ) {
                        val spoken = "Guest device ${record.hostName} connected to household Wi-Fi."
                        speakTtsAnnouncement(spoken)
                        _automationEvents.tryEmit(
                            WifiAutomationDispatchEvent(
                                hook = WifiAutomationHook.ANNOUNCE_NEW_GUEST_TTS,
                                summary = spoken,
                                guestDevice = record
                            )
                        )
                    }
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return merged.sortedWith(
            compareByDescending<ConnectedGuestDevice> { it.isCurrentlyReachable }
                .thenByDescending { it.lastSeenEpochMs }
        )
    }

    private fun upsertGuestDeviceRow(db: SQLiteDatabase, device: ConnectedGuestDevice) {
        val cv = ContentValues().apply {
            put("device_id", device.id)
            put("ip_address", device.ipAddress)
            put("mac_address", device.macAddress)
            put("host_name", device.hostName)
            put("vendor_category", device.vendorOrCategory)
            put("is_reachable", if (device.isCurrentlyReachable) 1 else 0)
            if (device.latencyMs != null) put("latency_ms", device.latencyMs) else putNull("latency_ms")
            put("first_seen_ms", device.firstSeenEpochMs)
            put("last_seen_ms", device.lastSeenEpochMs)
            put("is_smart_home_device", if (device.isKnownSmartHomeDevice) 1 else 0)
        }
        db.insertWithOnConflict(
            WifiDatabaseHelper.TABLE_GUEST_DEVICES,
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun loadGuestDevicesFromDb(): List<ConnectedGuestDevice> {
        val list = mutableListOf<ConnectedGuestDevice>()
        dbHelper.readableDatabase.query(
            WifiDatabaseHelper.TABLE_GUEST_DEVICES,
            null,
            null,
            null,
            null,
            null,
            "is_reachable DESC, last_seen_ms DESC"
        ).use { cursor ->
            val latIdx = cursor.getColumnIndexOrThrow("latency_ms")
            while (cursor.moveToNext()) {
                list.add(
                    ConnectedGuestDevice(
                        id = cursor.getString(cursor.getColumnIndexOrThrow("device_id")),
                        ipAddress = cursor.getString(cursor.getColumnIndexOrThrow("ip_address")),
                        macAddress = cursor.getString(cursor.getColumnIndexOrThrow("mac_address")),
                        hostName = cursor.getString(cursor.getColumnIndexOrThrow("host_name")),
                        vendorOrCategory = cursor.getString(cursor.getColumnIndexOrThrow("vendor_category")),
                        isCurrentlyReachable = cursor.getInt(cursor.getColumnIndexOrThrow("is_reachable")) == 1,
                        latencyMs = if (cursor.isNull(latIdx)) null else cursor.getInt(latIdx),
                        firstSeenEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_ms")),
                        lastSeenEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_ms")),
                        isKnownSmartHomeDevice = cursor.getInt(cursor.getColumnIndexOrThrow("is_smart_home_device")) == 1
                    )
                )
            }
        }
        return list
    }

    private fun recordRotationHistoryInDb(ssid: String, password: String, reason: String) {
        val masked = when {
            password.isEmpty() -> "(Open Network)"
            password.length <= 4 -> "••••"
            else -> "${password.take(2)}${"•".repeat((password.length - 4).coerceAtMost(8))}${password.takeLast(2)}"
        }
        val cv = ContentValues().apply {
            put("rotated_at_ms", System.currentTimeMillis())
            put("reason", reason)
            put("ssid", ssid)
            put("masked_preview", masked)
        }
        dbHelper.writableDatabase.insert(WifiDatabaseHelper.TABLE_ROTATION_HISTORY, null, cv)
    }

    private fun loadRotationHistoryFromDb(limit: Int = 10): List<PasswordRotationRecord> {
        val list = mutableListOf<PasswordRotationRecord>()
        dbHelper.readableDatabase.query(
            WifiDatabaseHelper.TABLE_ROTATION_HISTORY,
            null,
            null,
            null,
            null,
            null,
            "rotated_at_ms DESC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    PasswordRotationRecord(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        rotatedAtEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("rotated_at_ms")),
                        reason = cursor.getString(cursor.getColumnIndexOrThrow("reason")),
                        ssid = cursor.getString(cursor.getColumnIndexOrThrow("ssid")),
                        maskedPreview = cursor.getString(cursor.getColumnIndexOrThrow("masked_preview"))
                    )
                )
            }
        }
        return list
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Clara Guest WiFi",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Guest device join alerts, temporary access expiry, and Wi-Fi security rotations"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun dispatchNotificationOnce(
        dedupKey: String,
        eventType: WifiNotificationEventType,
        title: String,
        body: String
    ) {
        if (hasNotificationFired(dedupKey)) return
        val cv = ContentValues().apply {
            put("dedup_key", dedupKey)
            put("event_type", eventType.name)
            put("fired_at_ms", System.currentTimeMillis())
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            WifiDatabaseHelper.TABLE_NOTIFICATION_LOG,
            null,
            cv,
            SQLiteDatabase.CONFLICT_IGNORE
        )
        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val notif = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            nm.notify(dedupKey.hashCode(), notif)
        }
    }

    private fun hasNotificationFired(dedupKey: String): Boolean {
        dbHelper.readableDatabase.query(
            WifiDatabaseHelper.TABLE_NOTIFICATION_LOG,
            arrayOf("dedup_key"),
            "dedup_key = ?",
            arrayOf(dedupKey),
            null,
            null,
            null
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun speakTtsAnnouncement(text: String) {
        if (text.isBlank()) return
        repositoryScope.launch(Dispatchers.Main) {
            if (ttsEngine == null) {
                ttsEngine = TextToSpeech(appContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ttsEngine?.language = Locale.UK
                        ttsReady = true
                        ttsEngine?.speak(text, TextToSpeech.QUEUE_ADD, null, "wifi_tts_${System.currentTimeMillis()}")
                    }
                }
            } else if (ttsReady) {
                ttsEngine?.speak(text, TextToSpeech.QUEUE_ADD, null, "wifi_tts_${System.currentTimeMillis()}")
            }
        }
    }

    companion object {
        private const val KEY_WIFI_CONFIG_JSON = "guest_wifi_configuration_v1"
        private const val VAULT_KEY_GUEST_WIFI_PASSWORD = "guest_wifi::network_password_v1"
        private const val NOTIFICATION_CHANNEL_ID = "clara_guest_wifi_v1"

        @Volatile
        private var instance: WifiRepository? = null

        fun getInstance(context: Context = ClaraApplication.appContext): WifiRepository {
            return instance ?: synchronized(this) {
                instance ?: WifiRepository(context).also { instance = it }
            }
        }
    }
}