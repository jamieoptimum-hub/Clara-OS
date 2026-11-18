package com.jarvis.smarthomelauncher.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class RadioEnvironmentSnapshot(
    val wifiFrequencyMhz: Int = 0,
    val wifiRssiDbm: Int = -127,
    val connectedSsid: String = "",
    val connectedBssid: String = "",
    val hasFineLocationPermission: Boolean = false,
    val hasBleScanPermission: Boolean = false,
    val hasBleConnectPermission: Boolean = false,
    val hasNearbyWifiPermission: Boolean = false,
    val isLocationServiceEnabled: Boolean = true,
    val isWifiEnabled: Boolean = true,
    val isBluetoothEnabled: Boolean = true
) {
    val isOn5GhzBand: Boolean
        get() = wifiFrequencyMhz >= 4900

    val hasWifiScanReadPermission: Boolean
        get() = hasFineLocationPermission || hasNearbyWifiPermission

    val permissionStateSummary: String
        get() = "loc=${if (hasFineLocationPermission) "OK" else "DENY"}," +
                "gps=${if (isLocationServiceEnabled) "ON" else "OFF"}," +
                "ble=${if (hasBleScanPermission && hasBleConnectPermission) "OK" else "DENY"}," +
                "wifi=${if (hasNearbyWifiPermission) "OK" else "DENY"}"

    fun missingPermissions(checkBle: Boolean, checkWifi: Boolean): List<String> {
        val missing = mutableListOf<String>()
        if (checkBle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasBleScanPermission) missing.add("BLUETOOTH_SCAN")
            if (!hasBleConnectPermission) missing.add("BLUETOOTH_CONNECT")
        }
        if (checkWifi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNearbyWifiPermission) missing.add("NEARBY_WIFI_DEVICES")
        }
        if ((checkBle || checkWifi) && !hasFineLocationPermission) {
            missing.add("ACCESS_FINE_LOCATION")
        }
        return missing
    }
}

object RadioEnvironmentProbe {
    private const val TAG = "ClaraRadioProbe"
    private val regLock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var activeWifiNetwork: Network? = null

    @Volatile
    private var liveWifiFrequencyMhz: Int = 0

    @Volatile
    private var liveWifiRssiDbm: Int = -127

    @Volatile
    private var liveConnectedSsid: String = ""

    @Volatile
    private var liveConnectedBssid: String = ""

    @Volatile
    private var callbackRegistered: Boolean = false

    @Volatile
    private var registeredWithLocationGrant: Boolean = false

    @Volatile
    private var networkCallbackInstance: ConnectivityManager.NetworkCallback? = null

    fun attachContext(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ensureWifiCallbackRegisteredApi31(appCtx)
        }
    }

    private fun sanitizeSsid(rawSsid: String?): String {
        val trimmed = rawSsid?.trim().orEmpty().removeSurrounding("\"")
        return if (trimmed.isBlank() || trimmed.equals("<unknown ssid>", ignoreCase = true)) "" else trimmed
    }

    private fun hasLocationOrNearbyWifiGrant(ctx: Context): Boolean {
        val fineLoc = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val nearbyWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED
        } else false
        return fineLoc || nearbyWifi
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun ensureWifiCallbackRegisteredApi31(ctx: Context) {
        val hasGrantNow = hasLocationOrNearbyWifiGrant(ctx)
        if (callbackRegistered && (registeredWithLocationGrant || !hasGrantNow)) return
        synchronized(regLock) {
            if (callbackRegistered && (registeredWithLocationGrant || !hasGrantNow)) return
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            networkCallbackInstance?.let { oldCb ->
                runCatching { cm.unregisterNetworkCallback(oldCb) }
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    activeWifiNetwork = network
                    extractWifiInfoFromCapabilities(networkCapabilities)
                }

                override fun onLost(network: Network) {
                    if (activeWifiNetwork == network) {
                        activeWifiNetwork = null
                        liveWifiFrequencyMhz = 0
                        liveWifiRssiDbm = -127
                        liveConnectedSsid = ""
                        liveConnectedBssid = ""
                    }
                }
            }
            runCatching {
                cm.registerNetworkCallback(request, callback)
                networkCallbackInstance = callback
                callbackRegistered = true
                registeredWithLocationGrant = hasGrantNow
                cm.activeNetwork?.let { active ->
                    cm.getNetworkCapabilities(active)?.let { caps ->
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            activeWifiNetwork = active
                            extractWifiInfoFromCapabilities(caps)
                        }
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) registration note: ${e.message}")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun extractWifiInfoFromCapabilities(caps: NetworkCapabilities) {
        val wifiInfo = caps.transportInfo as? WifiInfo ?: return
        val freq = wifiInfo.frequency
        if (freq in 2400..7125) {
            liveWifiFrequencyMhz = freq
        }
        val rssi = wifiInfo.rssi
        if (rssi in -126..0) {
            liveWifiRssiDbm = rssi
        }
        val cleanSsid = sanitizeSsid(wifiInfo.ssid)
        if (cleanSsid.isNotEmpty()) {
            liveConnectedSsid = cleanSsid
        }
        val cleanBssid = wifiInfo.bssid?.trim().orEmpty()
        if (cleanBssid.isNotEmpty() && cleanBssid != "02:00:00:00:00:00") {
            liveConnectedBssid = cleanBssid
        }
    }

    @Suppress("DEPRECATION")
    private fun readLegacyWifiSnapshotPreApi31(ctx: Context): Triple<Int, Int, Pair<String, String>> {
        return runCatching {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            val freq = (info?.frequency ?: 0).takeIf { it in 2400..7125 } ?: 0
            val rssi = (info?.rssi ?: -127).takeIf { it in -126..0 } ?: -127
            val ssid = sanitizeSsid(info?.ssid)
            val bssid = info?.bssid?.trim().orEmpty().takeUnless { it == "02:00:00:00:00:00" }.orEmpty()
            Triple(freq, rssi, ssid to bssid)
        }.getOrDefault(Triple(0, -127, "" to ""))
    }

    fun snapshot(context: Context? = appContext): RadioEnvironmentSnapshot {
        val ctx = ClaraApplication.appContextOrNull(context) ?: appContext ?: return RadioEnvironmentSnapshot()
        if (appContext == null) appContext = ctx

        fun granted(perm: String): Boolean =
            ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

        val fineLoc = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val bleScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN)
        } else fineLoc
        val bleConn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else true
        val nearbyWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else fineLoc

        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isLocationEnabled = runCatching {
            if (lm == null) {
                true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }.getOrDefault(true)

        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val isWifiEnabled = runCatching { wm?.isWifiEnabled ?: true }.getOrDefault(true)

        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val isBtEnabled = runCatching { bm?.adapter?.isEnabled ?: true }.getOrDefault(true)

        val (freq, rssi, ssidAndBssid) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ensureWifiCallbackRegisteredApi31(ctx)
            if (liveWifiFrequencyMhz == 0) {
                runCatching {
                    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    cm?.activeNetwork?.let { net ->
                        cm.getNetworkCapabilities(net)?.let { caps ->
                            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                extractWifiInfoFromCapabilities(caps)
                            }
                        }
                    }
                }
            }
            Triple(liveWifiFrequencyMhz, liveWifiRssiDbm, liveConnectedSsid to liveConnectedBssid)
        } else {
            readLegacyWifiSnapshotPreApi31(ctx)
        }

        return RadioEnvironmentSnapshot(
            wifiFrequencyMhz = freq,
            wifiRssiDbm = rssi,
            connectedSsid = ssidAndBssid.first,
            connectedBssid = ssidAndBssid.second,
            hasFineLocationPermission = fineLoc,
            hasBleScanPermission = bleScan,
            hasBleConnectPermission = bleConn,
            hasNearbyWifiPermission = nearbyWifi,
            isLocationServiceEnabled = isLocationEnabled,
            isWifiEnabled = isWifiEnabled,
            isBluetoothEnabled = isBtEnabled
        )
    }

    fun currentWifiFrequencyMhz(context: Context? = appContext): Int =
        snapshot(context).wifiFrequencyMhz

    fun currentWifiRssiDbm(context: Context? = appContext): Int =
        snapshot(context).wifiRssiDbm

    fun currentConnectedSsid(context: Context? = appContext): String =
        snapshot(context).connectedSsid

    fun isTabletOn5GhzBand(context: Context? = appContext): Boolean =
        snapshot(context).isOn5GhzBand

    fun hasWifiScanReadPermission(context: Context? = appContext): Boolean =
        snapshot(context).hasWifiScanReadPermission

    fun currentPermissionStateSummary(context: Context? = appContext): String =
        snapshot(context).permissionStateSummary
}

data class ResolvedMdnsService(
    val serviceName: String,
    val serviceType: String,
    val hostIp: String,
    val port: Int,
    val txtAttributes: Map<String, String>
)

object DiscoveryRadioCoordinator {
    private const val TAG = "ClaraRadioCoord"
    private const val MAX_LOCK_HOLD_MS = 20_000L

    private data class MulticastLease(
        val leaseId: UUID,
        val reason: String,
        val timeoutJob: Job
    )

    private val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lockMutex = Any()
    private val activeMulticastLeases = ConcurrentHashMap<UUID, MulticastLease>()

    @Volatile
    private var multicastLock: WifiManager.MulticastLock? = null

    private val nsdListenerSemaphore = Semaphore(permits = 6)
    private val nsdApi34ResolveSemaphore = Semaphore(permits = 16)
    private val nsdResolveMutex = Mutex()
    private val nsdDirectExecutor = Executor { command -> command.run() }

    suspend fun <T> withMulticastLock(
        context: Context?,
        reason: String = "discovery_scan",
        block: suspend () -> T
    ): T {
        val appCtx = ClaraApplication.appContextOrNull(context)
        val leaseId = acquireMulticastLockLease(appCtx, reason)
        return try {
            block()
        } finally {
            if (leaseId != null) {
                releaseMulticastLockLease(leaseId, reason, expiredByWatchdog = false)
            }
        }
    }

    private fun acquireMulticastLockLease(context: Context?, reason: String): UUID? {
        if (context == null) return null
        val leaseId = UUID.randomUUID()
        synchronized(lockMutex) {
            val watchdogJob = coordScope.launch {
                delay(MAX_LOCK_HOLD_MS)
                releaseMulticastLockLease(leaseId, reason, expiredByWatchdog = true)
            }
            activeMulticastLeases[leaseId] = MulticastLease(
                leaseId = leaseId,
                reason = reason,
                timeoutJob = watchdogJob
            )
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lock = multicastLock ?: wm?.createMulticastLock("clara_discovery_multicast_lock")?.apply {
                    setReferenceCounted(false)
                }?.also { multicastLock = it }
                if (lock != null && !lock.isHeld) {
                    lock.acquire()
                    Log.d(TAG, "Acquired Wi-Fi MulticastLock ($reason, lease=$leaseId, activeLeases=${activeMulticastLeases.size})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "MulticastLock acquire note: ${e.message}")
            }
        }
        return leaseId
    }

    private fun releaseMulticastLockLease(
        leaseId: UUID,
        reason: String,
        expiredByWatchdog: Boolean
    ) {
        synchronized(lockMutex) {
            val removed = activeMulticastLeases.remove(leaseId) ?: return
            if (!expiredByWatchdog) {
                removed.timeoutJob.cancel()
            }
            if (activeMulticastLeases.isEmpty()) {
                runCatching {
                    if (multicastLock?.isHeld == true) {
                        multicastLock?.release()
                        if (expiredByWatchdog) {
                            Log.w(TAG, "Safety watchdog released Wi-Fi MulticastLock after ${MAX_LOCK_HOLD_MS}ms ($reason, lease=$leaseId)")
                        } else {
                            Log.d(TAG, "Released Wi-Fi MulticastLock ($reason, lease=$leaseId)")
                        }
                    }
                }
            } else if (expiredByWatchdog) {
                Log.w(TAG, "Expired stale MulticastLock lease $leaseId ($reason); lock remains held for ${activeMulticastLeases.size} active lease(s)")
            }
        }
    }

    suspend fun discoverMdnsServices(
        context: Context,
        serviceTypes: List<String>,
        timeoutMs: Long = 1500L,
        transportLabel: String = "MDNS"
    ): List<ResolvedMdnsService> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val nsdManager = appCtx.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.FAILED,
                transportSource = transportLabel,
                vendor = serviceTypes.joinToString(","),
                qualificationStage = "NsdManager.init",
                outcomeReason = "Android NsdManager service unavailable"
            )
            return@withContext emptyList()
        }
        val distinctTypes = serviceTypes.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (distinctTypes.isEmpty()) return@withContext emptyList()

        withMulticastLock(appCtx, "mdns:$transportLabel") {
            val resolvedMap = ConcurrentHashMap<String, ResolvedMdnsService>()
            val pendingSightings = ConcurrentHashMap<String, Pair<NsdServiceInfo, String>>()
            val resolveJobs = ConcurrentHashMap.newKeySet<Job>()

            kotlinx.coroutines.supervisorScope {
                val activeRegistrations = mutableListOf<Pair<NsdManager.DiscoveryListener, AtomicBoolean>>()

                for (regType in distinctTypes) {
                    val startedFlag = AtomicBoolean(false)
                    val listener = object : NsdManager.DiscoveryListener {
                        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                            startedFlag.set(false)
                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.FAILED,
                                transportSource = transportLabel,
                                vendor = regType,
                                qualificationStage = "NsdManager.startDiscovery",
                                outcomeReason = "Start discovery failed (errorCode=$errorCode)"
                            )
                        }

                        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                            startedFlag.set(false)
                        }

                        override fun onDiscoveryStarted(serviceType: String?) {
                            startedFlag.set(true)
                        }

                        override fun onDiscoveryStopped(serviceType: String?) {
                            startedFlag.set(false)
                        }

                        override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                            val info = serviceInfo ?: return
                            val rawName = info.serviceName?.trim().orEmpty()
                            val rawType = (info.serviceType ?: regType).trim()
                            val sightingKey = "${rawType.lowercase(Locale.US)}::${rawName.lowercase(Locale.US)}"

                            DiscoveryFlightRecorder.record(
                                eventType = DiscoveryEventType.ADVERTISEMENT_RECEIVED,
                                transportSource = transportLabel,
                                hardwareId = rawName,
                                vendor = rawType,
                                confidenceScore = 0.15f,
                                qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                                outcomeReason = "mDNS PTR/SRV advertisement received"
                            )

                            if (pendingSightings.putIfAbsent(sightingKey, info to rawType) == null) {
                                val job = launch(Dispatchers.IO) {
                                    val resolved = resolveServiceSerialized(nsdManager, info, rawType, transportLabel)
                                    if (resolved != null) {
                                        resolvedMap["${resolved.hostIp}:${resolved.port}:${resolved.serviceType}:${resolved.serviceName}"] = resolved
                                    }
                                }
                                resolveJobs.add(job)
                            } else {
                                DiscoveryFlightRecorder.record(
                                    eventType = DiscoveryEventType.FILTERED,
                                    transportSource = transportLabel,
                                    hardwareId = rawName,
                                    vendor = rawType,
                                    confidenceScore = 0.15f,
                                    qualificationStage = "STAGE_1_DISCOVERED_SERVICE",
                                    outcomeReason = "Suppressed duplicate mDNS PTR sighting in active scan window"
                                )
                            }
                        }

                        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
                    }

                    activeRegistrations.add(listener to startedFlag)
                }

                try {
                    val windowJobs = activeRegistrations.mapIndexed { idx, (listener, startedFlag) ->
                        launch(Dispatchers.IO) {
                            nsdListenerSemaphore.withPermit {
                                val regType = distinctTypes[idx]
                                try {
                                    nsdManager.discoverServices(regType, NsdManager.PROTOCOL_DNS_SD, listener)
                                    startedFlag.set(true)
                                    delay(timeoutMs)
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (e: Exception) {
                                    startedFlag.set(false)
                                    DiscoveryFlightRecorder.record(
                                        eventType = DiscoveryEventType.FAILED,
                                        transportSource = transportLabel,
                                        vendor = regType,
                                        qualificationStage = "NsdManager.discoverServices",
                                        outcomeReason = "${e.javaClass.simpleName}: ${e.message ?: "mDNS discovery exception"}"
                                    )
                                } finally {
                                    withContext(NonCancellable) {
                                        if (startedFlag.getAndSet(false)) {
                                            runCatching { nsdManager.stopServiceDiscovery(listener) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    windowJobs.forEach { it.join() }
                    val allDrained = withTimeoutOrNull(900L) {
                        resolveJobs.forEach { it.join() }
                        true
                    }
                    if (allDrained == null) {
                        DiscoveryFlightRecorder.record(
                            eventType = DiscoveryEventType.TIMEOUT,
                            transportSource = transportLabel,
                            vendor = distinctTypes.joinToString(","),
                            qualificationStage = "NsdManager.resolveDrain",
                            outcomeReason = "mDNS resolution drain window timed out after 900ms"
                        )
                    }
                } finally {
                    withContext(NonCancellable) {
                        for ((listener, startedFlag) in activeRegistrations) {
                            if (startedFlag.getAndSet(false)) {
                                runCatching { nsdManager.stopServiceDiscovery(listener) }
                            }
                        }
                    }
                }
            }
            resolvedMap.values.toList()
        }
    }

    private suspend fun resolveServiceSerialized(
        nsdManager: NsdManager,
        serviceInfo: NsdServiceInfo,
        fallbackType: String,
        transportLabel: String
    ): ResolvedMdnsService? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveServiceParallelApi34(nsdManager, serviceInfo, fallbackType, transportLabel)
        } else {
            resolveServiceLegacyPreApi34(nsdManager, serviceInfo, fallbackType, transportLabel)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun resolveServiceParallelApi34(
        nsdManager: NsdManager,
        serviceInfo: NsdServiceInfo,
        fallbackType: String,
        transportLabel: String
    ): ResolvedMdnsService? = nsdApi34ResolveSemaphore.withPermit {
        val rawName = serviceInfo.serviceName?.trim().orEmpty()
        val outcome: Pair<ResolvedMdnsService?, Int> = withTimeoutOrNull(1100L) {
            suspendCancellableCoroutine<Pair<ResolvedMdnsService?, Int>> { cont ->
                val completed = AtomicBoolean(false)
                val unregistered = AtomicBoolean(false)

                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        unregistered.set(true)
                        if (completed.compareAndSet(false, true) && cont.isActive) {
                            cont.resume(null to errorCode)
                        }
                    }

                    override fun onServiceUpdated(updatedInfo: NsdServiceInfo) {
                        val addresses = updatedInfo.hostAddresses
                        val preferredAddress = addresses.firstOrNull { addr ->
                            addr is Inet4Address && !addr.isLoopbackAddress && !addr.hostAddress.isNullOrBlank()
                        } ?: addresses.firstOrNull { addr ->
                            !addr.isLoopbackAddress && !addr.hostAddress.isNullOrBlank()
                        }
                        val hostIp = preferredAddress?.hostAddress
                            ?.substringBefore("%")
                            ?.trim()
                            .orEmpty()

                        if (hostIp.isBlank() || hostIp == "127.0.0.1" || updatedInfo.port <= 0) {
                            return
                        }
                        if (!completed.compareAndSet(false, true)) return
                        if (unregistered.compareAndSet(false, true)) {
                            runCatching { nsdManager.unregisterServiceInfoCallback(this) }
                        }
                        if (!cont.isActive) return

                        val txtMap = LinkedHashMap<String, String>()
                        runCatching {
                            for ((k, v) in updatedInfo.attributes.orEmpty()) {
                                if (!k.isNullOrBlank()) {
                                    txtMap[k.trim()] = v?.let { String(it, Charsets.UTF_8).trim() }.orEmpty()
                                }
                            }
                        }
                        cont.resume(
                            ResolvedMdnsService(
                                serviceName = updatedInfo.serviceName?.trim().orEmpty().ifBlank { rawName },
                                serviceType = (updatedInfo.serviceType ?: fallbackType).trim(),
                                hostIp = hostIp,
                                port = updatedInfo.port,
                                txtAttributes = txtMap
                            ) to 0
                        )
                    }

                    override fun onServiceLost() {}

                    override fun onServiceInfoCallbackUnregistered() {
                        unregistered.set(true)
                    }
                }

                cont.invokeOnCancellation {
                    if (unregistered.compareAndSet(false, true)) {
                        runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
                    }
                }

                try {
                    nsdManager.registerServiceInfoCallback(serviceInfo, nsdDirectExecutor, callback)
                } catch (_: Exception) {
                    unregistered.set(true)
                    if (completed.compareAndSet(false, true) && cont.isActive) {
                        cont.resume(null to -2)
                    }
                }
            }
        } ?: (null to -3)

        val resolved = outcome.first
        if (resolved != null) {
            recordResolvedMdnsSighting(resolved, transportLabel)
            return@withPermit resolved
        }
        recordFailedMdnsSighting(
            rawName = rawName,
            fallbackType = fallbackType,
            transportLabel = transportLabel,
            errorCode = outcome.second,
            stageName = "NsdManager.registerServiceInfoCallback"
        )
        null
    }

    private suspend fun resolveServiceLegacyPreApi34(
        nsdManager: NsdManager,
        serviceInfo: NsdServiceInfo,
        fallbackType: String,
        transportLabel: String
    ): ResolvedMdnsService? {
        val rawName = serviceInfo.serviceName?.trim().orEmpty()
        var lastErrorCode = -3
        for (attempt in 0..2) {
            val outcome: Pair<ResolvedMdnsService?, Int> = nsdResolveMutex.withLock {
                withTimeoutOrNull(1100L) {
                    suspendCancellableCoroutine<Pair<ResolvedMdnsService?, Int>> { cont ->
                        val completed = AtomicBoolean(false)
                        try {
                            @Suppress("DEPRECATION")
                            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(si: NsdServiceInfo?, errorCode: Int) {
                                    if (completed.compareAndSet(false, true) && cont.isActive) {
                                        cont.resume(null to errorCode)
                                    }
                                }

                                override fun onServiceResolved(resolved: NsdServiceInfo?) {
                                    if (!completed.compareAndSet(false, true) || !cont.isActive) return
                                    @Suppress("DEPRECATION")
                                    val hostIp = resolved?.host?.hostAddress?.trim().orEmpty()
                                    if (resolved == null || hostIp.isBlank() || hostIp == "127.0.0.1") {
                                        cont.resume(null to -1)
                                        return
                                    }
                                    val txtMap = LinkedHashMap<String, String>()
                                    runCatching {
                                        for ((k, v) in resolved.attributes.orEmpty()) {
                                            if (!k.isNullOrBlank()) {
                                                txtMap[k.trim()] = v?.let { String(it, Charsets.UTF_8).trim() }.orEmpty()
                                            }
                                        }
                                    }
                                    cont.resume(
                                        ResolvedMdnsService(
                                            serviceName = resolved.serviceName?.trim().orEmpty(),
                                            serviceType = (resolved.serviceType ?: fallbackType).trim(),
                                            hostIp = hostIp,
                                            port = resolved.port,
                                            txtAttributes = txtMap
                                        ) to 0
                                    )
                                }
                            })
                        } catch (_: Exception) {
                            if (completed.compareAndSet(false, true) && cont.isActive) {
                                cont.resume(null to -2)
                            }
                        }
                    }
                } ?: (null to -3)
            }
            val resolved = outcome.first
            lastErrorCode = outcome.second
            if (resolved != null) {
                recordResolvedMdnsSighting(resolved, transportLabel)
                return resolved
            }
            if (lastErrorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < 2) {
                delay(85L * (attempt + 1))
                continue
            } else {
                break
            }
        }
        recordFailedMdnsSighting(
            rawName = rawName,
            fallbackType = fallbackType,
            transportLabel = transportLabel,
            errorCode = lastErrorCode,
            stageName = "NsdManager.resolveService"
        )
        return null
    }

    private fun recordResolvedMdnsSighting(
        resolved: ResolvedMdnsService,
        transportLabel: String
    ) {
        val extractedMac = CrossEcosystemIdentityCorrelator.extractMacFromApplicationPayload(
            ipAddress = resolved.hostIp,
            mdnsTxtAttributes = resolved.txtAttributes
        )
        val txtSummary = resolved.txtAttributes.entries.joinToString(", ") { "${it.key}=${it.value}" }
        DiscoveryFlightRecorder.record(
            eventType = DiscoveryEventType.IDENTIFIED,
            transportSource = transportLabel,
            hardwareId = resolved.serviceName,
            macAddress = extractedMac,
            ipAddress = resolved.hostIp,
            port = resolved.port,
            vendor = resolved.serviceType,
            confidenceScore = 0.35f,
            qualificationStage = "STAGE_2_POTENTIAL_DEVICE",
            outcomeReason = "mDNS service resolved (${resolved.txtAttributes.size} TXT attrs)",
            rawPayload = txtSummary
        )
    }

    private fun recordFailedMdnsSighting(
        rawName: String,
        fallbackType: String,
        transportLabel: String,
        errorCode: Int,
        stageName: String
    ) {
        when (errorCode) {
            -1 -> DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.FILTERED,
                transportSource = transportLabel,
                hardwareId = rawName,
                vendor = fallbackType,
                qualificationStage = stageName,
                outcomeReason = "Filtered loopback or empty mDNS host address"
            )
            -3 -> DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.TIMEOUT,
                transportSource = transportLabel,
                hardwareId = rawName,
                vendor = fallbackType,
                qualificationStage = stageName,
                outcomeReason = "mDNS service resolution timed out after 1100ms"
            )
            else -> DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.FAILED,
                transportSource = transportLabel,
                hardwareId = rawName,
                vendor = fallbackType,
                qualificationStage = stageName,
                outcomeReason = "mDNS service resolution failed (errorCode=$errorCode)"
            )
        }
    }

    // -------------------------------------------------------------------------
    // ONBOARDING SECTIONS 1, 4 & 6: ISOLATED RADIO PASSES, 5 GHZ TABLET GUARD,
    // BSSID 2.4 GHZ MESH VALIDATION & STALE SOFTAP TIMESTAMP PURGE
    // -------------------------------------------------------------------------
    data class FreshSoftApSighting(
        val ssid: String,
        val bssid: String,
        val frequencyMhz: Int,
        val rssiDbm: Int,
        val ageMs: Long
    )

    private val isolatedRadioPassMutex = Mutex()

    @Volatile
    private var currentActiveRadioPass: OnboardingScanRadioPass? = null

    fun activeRadioPassOrNull(): OnboardingScanRadioPass? = currentActiveRadioPass

    suspend fun <T> withIsolatedRadioPass(
        pass: OnboardingScanRadioPass,
        reason: String = pass.name,
        block: suspend () -> T
    ): T = isolatedRadioPassMutex.withLock {
        val startMs = SystemClock.elapsedRealtime()
        currentActiveRadioPass = pass
        DiscoveryFlightRecorder.record(
            eventType = DiscoveryEventType.STARTED,
            transportSource = pass.name,
            vendor = pass.label,
            confidenceScore = 1.0f,
            qualificationStage = "RADIO_PASS_START",
            outcomeReason = "Entered isolated radio scan pass ($reason)",
            protocolResultCode = "PASS_LOCKED"
        )
        try {
            block()
        } finally {
            val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(0L)
            currentActiveRadioPass = null
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.IDENTIFIED,
                transportSource = pass.name,
                vendor = pass.label,
                confidenceScore = 1.0f,
                qualificationStage = "RADIO_PASS_END",
                outcomeReason = "Completed isolated radio scan pass ($reason)",
                durationMs = elapsed,
                protocolResultCode = "PASS_RELEASED"
            )
        }
    }

    fun getTabletActiveWifiFrequencyMhz(context: Context?): Int =
        RadioEnvironmentProbe.currentWifiFrequencyMhz(context)

    fun isTabletOn5GhzBand(context: Context?): Boolean =
        RadioEnvironmentProbe.isTabletOn5GhzBand(context)

    fun getTabletConnectedSsid(context: Context?): String =
        RadioEnvironmentProbe.currentConnectedSsid(context)

    fun isDeviceSetupHotspotSsid(ssid: String): Boolean {
        val clean = ssid.trim()
        if (clean.isEmpty()) return false
        val lower = clean.lowercase(Locale.US)
        return lower.startsWith("smartlife-") ||
                lower.startsWith("smartlife_") ||
                lower.startsWith("sl-") ||
                lower.startsWith("sl_") ||
                lower.startsWith("tuyasmart-") ||
                lower.startsWith("grid-") ||
                lower.startsWith("shelly") ||
                lower.startsWith("tapo_") ||
                lower.startsWith("tp-link_") ||
                lower.startsWith("itead-") ||
                lower.startsWith("sonoff-") ||
                lower.startsWith("tasmota") ||
                lower.startsWith("esp_") ||
                lower.startsWith("lytmi-") ||
                lower.startsWith("probreeze-")
    }

    private fun hasWifiScanReadPermission(context: Context): Boolean =
        RadioEnvironmentProbe.hasWifiScanReadPermission(context)

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun scanFreshSetupSoftApHotspots(
        context: Context,
        freshCutoffSeconds: Long = 15L,
        throttledCutoffSeconds: Long = 40L
    ): List<FreshSoftApSighting> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        if (!hasWifiScanReadPermission(appCtx)) {
            DiscoveryFlightRecorder.record(
                eventType = DiscoveryEventType.FAILED,
                transportSource = "SOFT_AP_SCAN",
                vendor = "Android WifiManager",
                qualificationStage = "PERMISSION_CHECK",
                outcomeReason = "Missing Fine Location / Nearby Wi-Fi permission for SoftAP scan",
                protocolResultCode = "PERM_DENIED"
            )
            return@withContext emptyList()
        }

        val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return@withContext emptyList()

        val scanTriggered = runCatching { wm.startScan() }.getOrDefault(false)
        if (scanTriggered) {
            delay(1_150L)
        }

        val nowBootMicros = runCatching { SystemClock.elapsedRealtimeNanos() / 1_000L }
            .getOrElse { SystemClock.elapsedRealtime() * 1_000L }
        val maxAgeSeconds = if (scanTriggered) freshCutoffSeconds else throttledCutoffSeconds
        val maxAgeMicros = maxAgeSeconds * 1_000_000L

        val rawResults: List<ScanResult> = runCatching { wm.scanResults.orEmpty() }.getOrDefault(emptyList())
        val freshBySsid = LinkedHashMap<String, FreshSoftApSighting>()

        for (sr in rawResults) {
            val ssid = sr.SSID?.trim().orEmpty()
            if (!isDeviceSetupHotspotSsid(ssid)) continue

            val ageMicros = (nowBootMicros - sr.timestamp).coerceAtLeast(0L)
            val ageMs = ageMicros / 1_000L
            if (ageMicros > maxAgeMicros) {
                DiscoveryFlightRecorder.record(
                    eventType = DiscoveryEventType.FILTERED,
                    transportSource = "SOFT_AP_SCAN",
                    hardwareId = ssid,
                    macAddress = CrossEcosystemIdentityCorrelator.normalizeMac(sr.BSSID),
                    vendor = ssid,
                    qualificationStage = "STALE_HOTSPOT_FILTER",
                    outcomeReason = "Purged cached setup hotspot '$ssid' (age=${ageMs / 1000}s > ${maxAgeSeconds}s cutoff, throttled=${!scanTriggered})",
                    wifiFrequencyMhz = sr.frequency,
                    rssiDbm = sr.level,
                    protocolResultCode = "STALE_AP_CACHED"
                )
                continue
            }

            val sighting = FreshSoftApSighting(
                ssid = ssid,
                bssid = sr.BSSID.orEmpty(),
                frequencyMhz = sr.frequency,
                rssiDbm = sr.level,
                ageMs = ageMs
            )
            val existing = freshBySsid[ssid]
            if (existing == null || sighting.rssiDbm > existing.rssiDbm) {
                freshBySsid[ssid] = sighting
            }
        }

        freshBySsid.values.sortedByDescending { it.rssiDbm }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun scanHomeWifiNetworksWithBandValidation(
        context: Context,
        maxCacheAgeSeconds: Long = 60L
    ): List<ScannedWifiNetworkOption> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        if (!hasWifiScanReadPermission(appCtx)) return@withContext emptyList()
        val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return@withContext emptyList()

        val scanTriggered = runCatching { wm.startScan() }.getOrDefault(false)
        if (scanTriggered) {
            delay(850L)
        }

        val nowBootMicros = runCatching { SystemClock.elapsedRealtimeNanos() / 1_000L }
            .getOrElse { SystemClock.elapsedRealtime() * 1_000L }
        val maxAgeMicros = maxCacheAgeSeconds * 1_000_000L
        val grouped = LinkedHashMap<String, MutableList<ScanResult>>()

        for (sr in runCatching { wm.scanResults.orEmpty() }.getOrDefault(emptyList())) {
            val ssid = sr.SSID?.trim().orEmpty()
            if (ssid.isEmpty() || isDeviceSetupHotspotSsid(ssid)) continue
            val ageMicros = (nowBootMicros - sr.timestamp).coerceAtLeast(0L)
            if (ageMicros > maxAgeMicros) continue
            grouped.getOrPut(ssid) { mutableListOf() }.add(sr)
        }

        grouped.entries.map { (ssid, bssids) ->
            val freqs = bssids.map { it.frequency }.filter { it > 0 }.distinct().sorted()
            val strongestRssi = bssids.maxOfOrNull { it.level } ?: -80
            val caps = bssids.firstOrNull()?.capabilities.orEmpty().uppercase(Locale.US)
            val isSecure = caps.contains("WPA") || caps.contains("WEP") || caps.contains("SAE") || caps.contains("PSK")
            ScannedWifiNetworkOption(
                ssid = ssid,
                bssidCount = bssids.mapNotNull { it.BSSID?.lowercase(Locale.US) }.distinct().size.coerceAtLeast(1),
                frequenciesMhz = freqs,
                strongestRssiDbm = strongestRssi,
                requiresPassword = isSecure
            )
        }.sortedWith(
            compareByDescending<ScannedWifiNetworkOption> { it.bandValidationStatus.isCompatibleWith24GhzHardware }
                .thenByDescending { it.strongestRssiDbm }
        )
    }
}
