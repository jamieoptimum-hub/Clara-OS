package com.jarvis.smarthomelauncher.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.net.ConnectivityManager
import android.net.Network
import com.jarvis.smarthomelauncher.core.DeviceRepository
import com.jarvis.smarthomelauncher.core.SmartTile
import com.jarvis.smarthomelauncher.engines.AutomationEngine
import com.jarvis.smarthomelauncher.engines.RecoveryEngine
import com.jarvis.smarthomelauncher.engines.UniversalOnboardingEngine
import com.jarvis.smarthomelauncher.engines.runAutomaticEnvironmentDiscoveryAndSelfHealing
import com.jarvis.smarthomelauncher.integrations.EWeLinkSonoffClient
import com.jarvis.smarthomelauncher.integrations.GoogleCastLanClient
import com.jarvis.smarthomelauncher.integrations.JarvisLiveBridge
import com.jarvis.smarthomelauncher.integrations.MatterProtocolClient
import com.jarvis.smarthomelauncher.integrations.MqttAndDiyClient
import com.jarvis.smarthomelauncher.integrations.NestSdmApiClient
import com.jarvis.smarthomelauncher.integrations.SmartHomeApiClient
import com.jarvis.smarthomelauncher.integrations.TpLinkTapoKasaClient
import com.jarvis.smarthomelauncher.integrations.TuyaApiClient
import com.jarvis.smarthomelauncher.integrations.TuyaDirectPairingManager
import com.jarvis.smarthomelauncher.integrations.VehicleIntegrationClient
import com.jarvis.smarthomelauncher.integrations.syncLiveTuyaStates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SmartHomeConnectivityService : Service() {

    companion object {
        fun isPairingOrSoftApHandoffActive(context: Context): Boolean =
            JarvisLiveBridge.isPairingTuyaFlow.value ||
                    UniversalOnboardingEngine.isSingleSessionLocked() ||
                    TuyaDirectPairingManager.isConnectedToDeviceHotspot(context)

        fun onNetworkTransition(context: Context) {
            // Never purge pre-fetched tokens or newly bound keys during SoftAP / BLE provisioning
            if (isPairingOrSoftApHandoffActive(context)) return
            TuyaApiClient.clearAllDeviceCaches()
            GoogleCastLanClient.clearCache()
            com.jarvis.smarthomelauncher.integrations.LgThinQWebOsClient.disconnectAll()
            TpLinkTapoKasaClient.clearSessions()
            MqttAndDiyClient.disconnect()
            SmartHomeApiClient.disconnectHaWebSocket()
            MatterProtocolClient.disconnect()
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var networkTransitionCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var lastObservedNetworkHandle: Long = 0L

    override fun onCreate() {
        super.onCreate()
        val channelId = "connectivity_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Smart Home Connectivity",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Clara Smart Home Sync")
            .setContentText("Maintaining local & cloud connections")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }

        registerDefaultNetworkMonitor()
        startSynchronizationLoop()
        startAutomationLoop()
    }

    private fun registerDefaultNetworkMonitor() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val handle = network.networkHandle
                val prev = lastObservedNetworkHandle
                lastObservedNetworkHandle = handle
                if (prev != 0L && prev != handle && !isPairingOrSoftApHandoffActive(this@SmartHomeConnectivityService)) {
                    onNetworkTransition(this@SmartHomeConnectivityService)
                }
            }
        }
        networkTransitionCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    /**
     * Merges polled tile states into the current live registry without clobbering newly paired
     * devices, deleted/unbound devices, or tiles inside an active PENDING_ACK user lock.
     */
    private suspend fun mergePolledTilesSafely(
        beforeSnapshotById: Map<String, SmartTile>,
        polledList: List<SmartTile>
    ) = withContext(Dispatchers.Main) {
        if (isPairingOrSoftApHandoffActive(this@SmartHomeConnectivityService)) return@withContext
        val polledById = polledList.associateBy { it.id }
        val liveRef = UniversalOnboardingEngine.activeDashboardTilesRef
        val currentList = liveRef ?: DeviceRepository.loadTiles(this@SmartHomeConnectivityService).toMutableList()
        var anyChanged = false

        for (i in currentList.indices) {
            val currentTile = currentList[i]
            if (DeviceRepository.isTilePendingAckLocked(currentTile.id)) continue
            val beforeTile = beforeSnapshotById[currentTile.id] ?: continue
            val polledTile = polledById[currentTile.id] ?: continue
            if (polledTile != beforeTile && polledTile != currentTile) {
                val reconciled = DeviceRepository.reconcileRemoteTileUpdate(
                    existing = currentTile,
                    incoming = polledTile,
                    incomingAuthority = polledTile.stateAuthorityTier,
                    sourceName = "ConnectivityService"
                )
                if (reconciled != currentTile) {
                    currentList[i] = reconciled
                    anyChanged = true
                }
            }
        }

        if (anyChanged) {
            DeviceRepository.saveTiles(this@SmartHomeConnectivityService, currentList)
        }
    }

    private fun startSynchronizationLoop() {
        scope.launch {
            var tickCount = 0
            while (isActive) {
                // Pause background LAN/Cloud sweeps during active BLE / SoftAP / Single-Device onboarding
                if (isPairingOrSoftApHandoffActive(this@SmartHomeConnectivityService)) {
                    delay(3_000L)
                    continue
                }

                val bridgeSettings = DeviceRepository.loadBridge(this@SmartHomeConnectivityService)
                val initialTiles = DeviceRepository.loadTiles(this@SmartHomeConnectivityService)
                val beforeById = initialTiles.associateBy { it.id }
                val tiles = initialTiles.toMutableList()

                val currentTick = tickCount
                supervisorScope {
                    listOf(
                        async {
                            withTimeoutOrNull(5000L) {
                                if (NestSdmApiClient.isNestConfiguredAndLinked(bridgeSettings)) {
                                    if (currentTick % 4 == 0) {
                                        NestSdmApiClient.syncLiveNestStates(bridgeSettings, tiles)
                                    } else {
                                        NestSdmApiClient.pullAndAckPubSubEvents(bridgeSettings, tiles)
                                    }
                                }
                            }
                        },
                        async {
                            withTimeoutOrNull(5000L) {
                                TuyaApiClient.syncLiveTuyaStates(bridgeSettings, tiles)
                            }
                        },
                        async {
                            withTimeoutOrNull(2000L) {
                                TpLinkTapoKasaClient.syncLiveTapoKasaStates(this@SmartHomeConnectivityService, tiles)
                            }
                        },
                        async {
                            withTimeoutOrNull(2000L) {
                                EWeLinkSonoffClient.syncLiveSonoffStates(this@SmartHomeConnectivityService, tiles)
                            }
                        },
                        async {
                            withTimeoutOrNull(2000L) {
                                MatterProtocolClient.syncLiveMatterStates(this@SmartHomeConnectivityService, bridgeSettings, tiles)
                            }
                        },
                        async {
                            withTimeoutOrNull(2000L) {
                                GoogleCastLanClient.syncLiveNestMiniVolumes(this@SmartHomeConnectivityService, tiles)
                            }
                        },
                        async {
                            withTimeoutOrNull(5000L) {
                                VehicleIntegrationClient.syncVehicleStates(this@SmartHomeConnectivityService, bridgeSettings, tiles)
                            }
                        },
                        async {
                            withTimeoutOrNull(2000L) {
                                MqttAndDiyClient.syncLiveMqttAndTasmotaStates(this@SmartHomeConnectivityService, tiles)
                                if (MqttAndDiyClient.isConfigured(this@SmartHomeConnectivityService)) {
                                    MqttAndDiyClient.connectAndDiscoverMqttEntities(this@SmartHomeConnectivityService, bridgeSettings, tiles)
                                }
                            }
                        },
                        async {
                            withTimeoutOrNull(5000L) {
                                if (bridgeSettings.haUrl.isNotBlank() && bridgeSettings.haToken.isNotBlank()) {
                                    if (currentTick == 0) {
                                        SmartHomeApiClient.syncLiveTileStatesFromHa(bridgeSettings, tiles)
                                    }
                                    SmartHomeApiClient.ensureHaWebSocket(bridgeSettings, this@SmartHomeConnectivityService)
                                }
                            }
                        },
                        async {
                            withTimeoutOrNull(3000L) {
                                SmartHomeApiClient.syncLiveShellyStates(tiles)
                                SmartHomeApiClient.syncLiveGoveeStates(bridgeSettings, tiles)
                                SmartHomeApiClient.syncLiveTwinklyStates(bridgeSettings, tiles)
                                com.jarvis.smarthomelauncher.integrations.LgThinQWebOsClient.syncLiveLgTvStates(this@SmartHomeConnectivityService, tiles)
                            }
                        }
                    ).awaitAll()
                }

                if (!isPairingOrSoftApHandoffActive(this@SmartHomeConnectivityService)) {
                    if (tickCount % 4 == 0) {
                        RecoveryEngine.runProactiveGuardianSweep(
                            context = this@SmartHomeConnectivityService,
                            tiles = tiles,
                            bridge = bridgeSettings,
                            scope = scope
                        )
                    }

                    if (tickCount > 0 && tickCount % 8 == 0) {
                        runAutomaticEnvironmentDiscoveryAndSelfHealing(
                            context = this@SmartHomeConnectivityService,
                            bridgeSettings = bridgeSettings,
                            tiles = tiles,
                            scope = scope,
                            includeFullLanScan = false
                        )
                    }

                    mergePolledTilesSafely(beforeSnapshotById = beforeById, polledList = tiles)
                }

                tickCount++
                delay(15_000L)
            }
        }
    }

    private fun startAutomationLoop() {
        scope.launch {
            val audioManager = getSystemService(AudioManager::class.java) as AudioManager
            while (isActive) {
                if (!isPairingOrSoftApHandoffActive(this@SmartHomeConnectivityService)) {
                    val bridgeSettings = DeviceRepository.loadBridge(this@SmartHomeConnectivityService)
                    val initialTiles = DeviceRepository.loadTiles(this@SmartHomeConnectivityService)
                    val beforeById = initialTiles.associateBy { it.id }
                    val tiles = initialTiles.toMutableList()
                    val automationRules = DeviceRepository.loadAutomations(this@SmartHomeConnectivityService).toMutableList()

                    AutomationEngine.evaluateAllRules(
                        context = this@SmartHomeConnectivityService,
                        rules = automationRules,
                        tiles = tiles,
                        bridge = bridgeSettings,
                        audioManager = audioManager,
                        scope = scope,
                        onSpeak = { /* Headless service does not emit UI speaking directly */ }
                    )

                    if (tiles != initialTiles) {
                        mergePolledTilesSafely(beforeSnapshotById = beforeById, polledList = tiles)
                    }
                }
                delay(5_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        networkTransitionCallback?.let { cb ->
            val cm = getSystemService(ConnectivityManager::class.java)
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
        networkTransitionCallback = null
        super.onDestroy()
        scope.cancel()
    }
}
