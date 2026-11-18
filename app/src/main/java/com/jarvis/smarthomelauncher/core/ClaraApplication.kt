package com.jarvis.smarthomelauncher.core

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import android.webkit.WebView
import com.facebook.soloader.SoLoader
import com.jarvis.smarthomelauncher.core.ClaraSecretVault
import com.thingclips.smart.home.sdk.ThingHomeSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.UUID

class ClaraApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkHealDebounceJob: Job? = null

    @Volatile
    private var lastKnownLocalIp: String = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        internalAppContext = applicationContext

        // Initialize Telemetry Database singleton FIRST (Change 1.1)
        TelemetryDatabaseHelper.getInstance(this)
        DiscoveryFlightRecorder.initialize(this)
        DeviceLifecycleRecorder.initialize(this)
        DeviceBlackBoxRecorder.initialize(this)

        val currentProcName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            packageName
        }
        val isMainProcess = currentProcName == packageName

        // Isolate WebView data directory if a secondary process (such as Tuya's :monitor) ever touches WebView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !isMainProcess) {
            runCatching {
                val suffix = currentProcName.substringAfter(":", "sub").replace(Regex("[^a-zA-Z0-9_]"), "_")
                WebView.setDataDirectorySuffix(suffix)
            }
        }

        // 1. Initialize SoLoader FIRST for native C++ libraries (Tuya & LibVLC)
        try {
            SoLoader.init(this, false)
        } catch (e: Exception) {
            Log.w(TAG, "SoLoader initialization note: ${e.message}")
        }

        // 2. Enable Tuya SDK debug logs strictly in debuggable builds
        val isDebuggableBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        try {
            ThingHomeSdk.setDebugMode(isDebuggableBuild)
        } catch (e: Exception) {
            Log.w(TAG, "ThingHomeSdk debug mode configuration note: ${e.message}")
        }

        // 3a. Initialize normalized SQLite Device Registry & migrate legacy SharedPreferences first
        // 3b. Consolidate legacy SharedPreferences credential stores into ClaraSecretVault BEFORE SDK init
        // 3c. Resolve Tuya AppKey & AppSecret from Manifest/local.properties or AndroidKeyStore vault
        // Skip UI, WebView, and repository preloading inside Tuya's background :monitor process
        if (!isMainProcess) {
            ensureTuyaSdkInitialized(this, false)
            return
        }

        runCatching {
            ClaraDeviceRegistryStore.getInstance(this).initializeAndMigrate()
        }.onFailure { e ->
            Log.e(TAG, "Device Registry initialization warning: ${e.message}", e)
        }

        runCatching {
            ClaraSecretVault.migrateAllLegacyStores(this)
        }.onFailure { e ->
            Log.w(TAG, "Legacy vault migration warning: ${e.message}")
        }

        ensureTuyaSdkInitialized(this, true)

        // 4. Provision a unique per-installation cryptographic household identity (Zero static fallbacks)
        ensureInstallationIdentityProvisioned(this)

        // 5. Initialize audio context and pre-load persisted state asynchronously off the UI thread
        ClaraInAppMusicPlayer.initContextIfNeeded(this)
        applicationScope.launch {
            runCatching {
                val bridge = DeviceRepository.loadBridge(this@ClaraApplication)
                val tiles = DeviceRepository.loadTiles(this@ClaraApplication)
                val rules = DeviceRepository.loadAutomations(this@ClaraApplication)
                DeviceRepository.rebuildDigitalHomeGraph(
                    context = this@ClaraApplication,
                    tiles = tiles,
                    bridge = bridge,
                    rules = rules
                )
            }
        }

        // 6. Register Stage 0 Autonomous Network & DHCP Change Monitor for 24/7 Wall-Tablet Resilience
        registerStageZeroNetworkMonitor()
    }

    /**
     * Stage 0 Self-Healing: Monitors Wi-Fi / Ethernet availability and DHCP IP changes at the OS level.
     * Whenever the router reboots, Wi-Fi reconnects, or the tablet's local IP changes, automatically
     * refreshes the Local Hub Server and emits a topology recovery event.
     */
    private fun registerStageZeroNetworkMonitor() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        runCatching {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    com.jarvis.smarthomelauncher.services.SmartHomeConnectivityService.onNetworkTransition(this@ClaraApplication)
                    scheduleStageZeroNetworkHeal("Wi-Fi / LAN network connected")
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    val currentIp = JarvisLiveBridge.resolveLocalTabletIp(this@ClaraApplication)
                    if (currentIp.isNotBlank() && currentIp != "127.0.0.1" && currentIp != lastKnownLocalIp) {
                        val previousIp = lastKnownLocalIp
                        lastKnownLocalIp = currentIp
                        if (previousIp.isNotBlank()) {
                            com.jarvis.smarthomelauncher.services.SmartHomeConnectivityService.onNetworkTransition(this@ClaraApplication)
                            scheduleStageZeroNetworkHeal("Tablet DHCP IP updated ($previousIp -> $currentIp)")
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Stage 0 Network Monitor: Primary LAN connection lost; awaiting reconnect")
                }
            })
        }
    }

    private fun scheduleStageZeroNetworkHeal(reason: String) {
        networkHealDebounceJob?.cancel()
        networkHealDebounceJob = applicationScope.launch {
            delay(650L)
            runCatching {
                lastKnownLocalIp = JarvisLiveBridge.resolveLocalTabletIp(this@ClaraApplication)
                JarvisLiveBridge.ensureLocalHubServerRunning(this@ClaraApplication)
                SmartHomeApiClient.listenForTuyaUdpBroadcasts(timeoutMs = 800L)
                JarvisLiveBridge.emitPlatformEvent(
                    JarvisPlatformEvent(
                        category = JarvisEventCategory.SELF_HEALING,
                        sourceDeviceTitle = "Stage 0 Network Supervisor",
                        eventType = "NETWORK_TOPOLOGY_VERIFIED",
                        summary = "Stage 0 Auto-Heal: $reason (Hub IP: $lastKnownLocalIp)"
                    )
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            runCatching {
                PhotoSlideshowResolver.clearCloudCache()
            }
        }
    }

    companion object {
        private const val TAG = "ClaraApplication"
        private const val IDENTITY_PREFS = "clara_installation_identity_v1"
        private const val KEY_INSTALL_UID = "install_household_uid"
        private const val KEY_INSTALL_SECRET = "install_household_secret"
        private const val KEY_LOCAL_HUB_API_TOKEN = "local_hub_api_token"

        @Volatile
        var instance: ClaraApplication? = null
            private set

        @Volatile
        private var internalAppContext: Context? = null

        /**
         * Canonical application Context for all hardware engines, repositories, and discovery scanners (F-15).
         * Eliminates fallback coupling to ClaraInAppMusicPlayer.currentAppContext().
         */
        val appContext: Context
            get() = internalAppContext
                ?: instance?.applicationContext
                ?: throw IllegalStateException("ClaraApplication.appContext accessed before Application.onCreate()")

        fun appContextOrNull(fallback: Context? = null): Context? {
            return fallback?.applicationContext ?: internalAppContext ?: instance?.applicationContext
        }

        fun requireAppContext(fallback: Context? = null): Context {
            return appContextOrNull(fallback) ?: appContext
        }

        @Volatile
        var isTuyaSdkInitialized: Boolean = false
            private set

        @Volatile
        private var hasLoggedDeferredInit: Boolean = false

        fun isTuyaSdkReady(context: Context? = null, isMainProcess: Boolean = true): Boolean {
            if (isTuyaSdkInitialized) return true
            val ctx = context ?: instance ?: return false
            return ensureTuyaSdkInitialized(ctx, isMainProcess)
        }

        @Synchronized
        fun ensureTuyaSdkInitialized(context: Context, isMainProcess: Boolean): Boolean {
            if (isTuyaSdkInitialized) return true
            val appContext = context.applicationContext as? Application ?: return false

            var manifestAppKey = ""
            var manifestAppSecret = ""
            runCatching {
                val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.packageManager.getApplicationInfo(
                        appContext.packageName,
                        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appContext.packageManager.getApplicationInfo(
                        appContext.packageName,
                        PackageManager.GET_META_DATA
                    )
                }
                val meta = ai.metaData
                if (meta != null) {
                    manifestAppKey = meta.getString("THING_SMART_APPKEY", "").orEmpty().trim()
                    manifestAppSecret = meta.getString("THING_SMART_SECRET", "").orEmpty().trim()
                }
            }

            var vaultAppKeyOverride = ""
            var vaultAppSecretOverride = ""
            var legacyAppKey = ""
            var rawVaultSecret = ""

            if (isMainProcess) {
                vaultAppKeyOverride = ClaraSecretVault.getTuyaSdkAppKeyOverride(appContext).orEmpty()
                vaultAppSecretOverride = ClaraSecretVault.getTuyaSdkAppSecretOverride(appContext).orEmpty()
                rawVaultSecret = ClaraSecretVault.getString(
                    appContext,
                    "google_home_jarvis_prefs_v10::tuya_access_secret"
                )?.trim().orEmpty()
                val legacyPrefs = appContext.getSharedPreferences("google_home_jarvis_prefs_v10", Context.MODE_PRIVATE)
                legacyAppKey = legacyPrefs.getString("tuya_access_id", "")?.trim().orEmpty()
            }

            // Prefer dedicated App SDK manifest placeholders (matching security-algorithm.aar) first,
            // then fall back to Vault credentials entered in Control Center.
            val appKey = manifestAppKey
                .ifBlank { vaultAppKeyOverride }
                .ifBlank { legacyAppKey }
                .ifBlank { DEFAULT_TUYA_ACCESS_ID.trim() }
            val appSecret = manifestAppSecret
                .ifBlank { vaultAppSecretOverride }
                .ifBlank { rawVaultSecret.trim() }
                .ifBlank { DEFAULT_TUYA_ACCESS_SECRET.trim() }

            if (appKey.isNotBlank() && appSecret.isNotBlank()) {
                return try {
                    ThingHomeSdk.init(appContext, appKey, appSecret)
                    isTuyaSdkInitialized = true
                    Log.i(TAG, "ThingHomeSdk initialized successfully (keyPrefix=${appKey.take(4)}...)")
                    true
                } catch (t: Throwable) {
                    Log.e(TAG, "ThingHomeSdk init error: ${t.message}", t)
                    false
                }
            } else {
                if (!hasLoggedDeferredInit) {
                    hasLoggedDeferredInit = true
                    Log.i(TAG, "ThingHomeSdk init deferred: awaiting Tuya AppKey & AppSecret in local.properties or Settings")
                }
                return false
            }
        }

        @Volatile
        private var volatileFallbackUid: String = ""

        @Volatile
        private var volatileFallbackSecret: String = ""

        @Volatile
        private var volatileFallbackHubToken: String = ""

        private fun generateRandomHex(byteCount: Int = 16): String {
            val bytes = ByteArray(byteCount).apply { SecureRandom().nextBytes(this) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private const val VAULT_KEY_INSTALL_UID = "${IDENTITY_PREFS}::$KEY_INSTALL_UID"
        private const val VAULT_KEY_INSTALL_SECRET = "${IDENTITY_PREFS}::$KEY_INSTALL_SECRET"
        private const val VAULT_KEY_LOCAL_HUB_API_TOKEN = "${IDENTITY_PREFS}::$KEY_LOCAL_HUB_API_TOKEN"

        @Synchronized
        fun ensureInstallationIdentityProvisioned(context: Context) {
            val appContext = context.applicationContext
            val legacyPrefs = appContext.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)

            val vaultUid = ClaraSecretVault.getString(appContext, VAULT_KEY_INSTALL_UID).orEmpty().trim()
            val rawVaultSecret = ClaraSecretVault.getString(appContext, VAULT_KEY_INSTALL_SECRET).orEmpty().trim()
            val rawVaultHubToken = ClaraSecretVault.getString(appContext, VAULT_KEY_LOCAL_HUB_API_TOKEN).orEmpty().trim()
            val vaultSecret = rawVaultSecret.trim()
            val vaultHubToken = rawVaultHubToken.trim()

            val existingUid = vaultUid.ifBlank {
                legacyPrefs.getString(KEY_INSTALL_UID, "").orEmpty().trim()
            }
            val existingSecret = vaultSecret.ifBlank {
                legacyPrefs.getString(KEY_INSTALL_SECRET, "").orEmpty().trim()
            }
            val existingHubToken = vaultHubToken.ifBlank {
                legacyPrefs.getString(KEY_LOCAL_HUB_API_TOKEN, "").orEmpty().trim()
            }

            val hexSuffix = generateRandomHex(16).take(16)
            val finalUid = existingUid.ifBlank { "clara_home_$hexSuffix" }
            val finalSecret = existingSecret.ifBlank {
                "Clara#" + generateRandomHex(16).take(20) + "!"
            }
            val finalHubToken = existingHubToken.ifBlank {
                UUID.randomUUID().toString().replace("-", "") + hexSuffix
            }

            volatileFallbackUid = finalUid
            volatileFallbackSecret = finalSecret
            volatileFallbackHubToken = finalHubToken

            if (vaultUid != finalUid) {
                ClaraSecretVault.putString(appContext, VAULT_KEY_INSTALL_UID, finalUid)
            }
            if (vaultSecret != finalSecret) {
                ClaraSecretVault.putString(appContext, VAULT_KEY_INSTALL_SECRET, finalSecret)
            }
            if (vaultHubToken != finalHubToken) {
                ClaraSecretVault.putString(appContext, VAULT_KEY_LOCAL_HUB_API_TOKEN, finalHubToken)
            }

            if (legacyPrefs.contains(KEY_INSTALL_UID) ||
                legacyPrefs.contains(KEY_INSTALL_SECRET) ||
                legacyPrefs.contains(KEY_LOCAL_HUB_API_TOKEN)
            ) {
                legacyPrefs.edit()
                    .remove(KEY_INSTALL_UID)
                    .remove(KEY_INSTALL_SECRET)
                    .remove(KEY_LOCAL_HUB_API_TOKEN)
                    .apply()
            }
        }

        fun getOrCreateInstallationUid(context: Context): String {
            ensureInstallationIdentityProvisioned(context)
            return ClaraSecretVault.getString(context.applicationContext, VAULT_KEY_INSTALL_UID)
                .orEmpty()
                .trim()
                .ifBlank { volatileFallbackUid }
        }

        fun getOrCreateInstallationSecret(context: Context): String {
            ensureInstallationIdentityProvisioned(context)
            return ClaraSecretVault.getString(context.applicationContext, VAULT_KEY_INSTALL_SECRET)
                .orEmpty()
                .trim()
                .ifBlank { volatileFallbackSecret }
        }

        fun getOrCreateLocalHubToken(context: Context): String {
            ensureInstallationIdentityProvisioned(context)
            return ClaraSecretVault.getString(context.applicationContext, VAULT_KEY_LOCAL_HUB_API_TOKEN)
                .orEmpty()
                .trim()
                .ifBlank { volatileFallbackHubToken }
        }
    }
}
