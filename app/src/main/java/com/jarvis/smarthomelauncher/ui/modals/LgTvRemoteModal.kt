package com.jarvis.smarthomelauncher.ui.modals

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun LgTvRemoteModal(
    tile: SmartTile,
    bridgeSettings: BridgeSettings,
    onUpdateTile: (SmartTile) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rowStepPx = with(density) { 54.dp.toPx() }

    var activeIp by remember(tile.ipAddress) { mutableStateOf(tile.ipAddress.trim()) }
    var tvMac by remember(tile.macAddress, tile.serialNumber, activeIp) {
        mutableStateOf(
            CrossEcosystemIdentityCorrelator.normalizeMac(
                tile.macAddress.ifBlank { tile.serialNumber }
            ).ifBlank {
                CrossEcosystemIdentityCorrelator.normalizeMac(
                    LgThinQWebOsClient.getSavedMacAddress(context, activeIp)
                )
            }
        )
    }
    var isTvPairedState by remember(activeIp, tvMac) {
        mutableStateOf(
            LgThinQWebOsClient.hasVerifiedVaultKey(context, activeIp, tvMac, tile.serialNumber)
        )
    }
    var liveVolume by remember(tile.id, tile.volume) {
        mutableFloatStateOf(tile.volume.coerceIn(0f, 100f))
    }
    var isMuted by remember { mutableStateOf(false) }
    var isSyncingTv by remember { mutableStateOf(false) }
    var isPairingTv by remember { mutableStateOf(false) }
    var statusText by remember(isTvPairedState, tile.isActive) {
        mutableStateOf(
            when {
                !isTvPairedState -> "Pairing Required \u2014 Switch TV on, tap 'Pair TV', and press Accept on TV"
                tile.isActive -> "Connected \u2014 Drag the handle (\u2261) on any app to reorder"
                else -> "Standby \u2014 Tap Power ON, Sync TV Apps, or select an app to wake TV"
            }
        )
    }

    // Saves IP, MAC, and live volume into DeviceRepository / Registry WITHOUT triggering outbound power-off dispatch
    fun persistTvMetadataWithoutPowerDispatch(
        resolvedHost: String,
        resolvedMac: String,
        updatedVolume: Float = liveVolume,
        markOnlineAndActive: Boolean = false
    ) {
        val cleanHost = resolvedHost.substringBefore(":").trim().ifBlank { activeIp }
        val cleanMac = CrossEcosystemIdentityCorrelator.normalizeMac(resolvedMac).ifBlank { tvMac }
        if (cleanHost.isNotBlank()) {
            DeviceRepository.updateDeviceEndpointAndMac(
                context = context,
                canonicalGuid = tile.canonicalGuid.ifBlank { tile.resolvedCanonicalGuid() },
                newHostIp = cleanHost,
                newPort = 3001,
                learnedMac = cleanMac
            )
        }
        val liveTiles = UniversalOnboardingEngine.activeDashboardTilesRef
        if (liveTiles != null) {
            val idx = liveTiles.indexOfFirst { it.id == tile.id }
            if (idx >= 0) {
                val cur = liveTiles[idx]
                liveTiles[idx] = cur.copy(
                    ipAddress = cleanHost.ifBlank { cur.ipAddress },
                    macAddress = cleanMac.ifBlank { cur.macAddress },
                    serialNumber = cleanMac.ifBlank { cur.serialNumber },
                    volume = updatedVolume,
                    isOnline = if (markOnlineAndActive) true else cur.isOnline,
                    isActive = if (markOnlineAndActive) true else cur.isActive,
                    lifecycleState = if (markOnlineAndActive) DeviceLifecycleState.OPERATIONAL else cur.lifecycleState
                )
                DeviceRepository.saveTiles(context, liveTiles)
            }
        }
    }

    val tvApps = remember {
        mutableStateListOf<LgTvAppShortcut>().apply {
            addAll(LgThinQWebOsClient.loadTvApps(context))
        }
    }
    val discoveredCatalogApps = remember {
        mutableStateListOf<LgTvAppShortcut>().apply {
            val loaded = LgThinQWebOsClient.loadTvApps(context)
            addAll(loaded)
            LgTvAppOption.entries.forEach { opt ->
                if (none { it.appId.equals(opt.appId, ignoreCase = true) || it.label.equals(opt.label, ignoreCase = true) }) {
                    add(
                        LgTvAppShortcut(
                            id = "default_${opt.name}",
                            label = opt.label,
                            appId = opt.appId,
                            isHdmiInput = opt.name.startsWith("HDMI")
                        )
                    )
                }
            }
        }
    }
    var isEditingApps by remember { mutableStateOf(false) }
    var customShowNameDraft by remember { mutableStateOf("") }
    var customShowPidDraft by remember { mutableStateOf("") }
    var iboChannelNameDraft by remember { mutableStateOf("") }
    var iboChannelNumberDraft by remember { mutableStateOf("") }

    // Drag-and-drop state for TV Apps list
    var draggedAppId by remember { mutableStateOf<String?>(null) }
    var draggedDeltaY by remember { mutableFloatStateOf(0f) }

    fun mergeIntoCatalog(apps: List<LgTvAppShortcut>) {
        for (app in apps) {
            val existingIdx = discoveredCatalogApps.indexOfFirst {
                it.appId.equals(app.appId, ignoreCase = true) || it.label.equals(app.label, ignoreCase = true)
            }
            if (existingIdx >= 0) {
                discoveredCatalogApps[existingIdx] = app
            } else {
                discoveredCatalogApps.add(app)
            }
        }
    }

    suspend fun pairTvExplicitly() {
        isPairingTv = true
        statusText = "Connecting to LG TV \u2014 Press 'Accept' on your TV screen using your TV remote..."
        val resolvedIp = LgThinQWebOsClient.resolveOrAutoDiscoverTvIp(context, activeIp)
        if (resolvedIp.isBlank()) {
            isPairingTv = false
            statusText = "We couldn't find your LG TV \u2014 ensure it is switched on and connected to your home network"
            return
        }
        activeIp = resolvedIp
        val res = LgThinQWebOsClient.pairAndFetchTvDetails(
            context = context,
            ipAddress = resolvedIp,
            forceClearStaleTlsPin = true
        )
        isPairingTv = false
        if (res.isSuccess) {
            val (fetchedMac, syncedApps) = res.getOrThrow()
            val cleanMac = CrossEcosystemIdentityCorrelator.normalizeMac(fetchedMac).ifBlank { tvMac }
            if (cleanMac.isNotBlank()) {
                tvMac = cleanMac
            }
            isTvPairedState = LgThinQWebOsClient.hasVerifiedVaultKey(context, resolvedIp, cleanMac, tile.serialNumber)
            LgThinQWebOsClient.lastKnownTvVolumeByIp[resolvedIp]?.let { liveVolume = it.toFloat() }
            persistTvMetadataWithoutPowerDispatch(
                resolvedHost = resolvedIp,
                resolvedMac = cleanMac,
                updatedVolume = liveVolume,
                markOnlineAndActive = true
            )
            tvApps.clear()
            tvApps.addAll(syncedApps)
            mergeIntoCatalog(syncedApps)
            statusText = "Paired & Connected \u2014 Synced ${syncedApps.size} installed TV apps"
        } else {
            statusText = res.exceptionOrNull()?.message
                ?: "Confirmation needed on TV \u2014 press 'Accept' on your LG TV screen"
        }
    }

    suspend fun autoDiscoverAndSyncTv(showSearchingStatus: Boolean) {
        val resolvedIp = LgThinQWebOsClient.resolveOrAutoDiscoverTvIp(context, activeIp)
        if (resolvedIp.isBlank()) {
            isSyncingTv = false
            statusText = if (tvMac.isNotBlank()) {
                "Standby / Offline \u2014 Tap Power ON to send Wake-on-LAN ($tvMac)"
            } else {
                "Offline \u2014 Switch your LG TV on to connect"
            }
            return
        }

        activeIp = resolvedIp
        val observedMac = CrossEcosystemIdentityCorrelator.normalizeMac(
            LgThinQWebOsClient.resolveMacFromArpTable(resolvedIp)
                .ifBlank { LgThinQWebOsClient.getSavedMacAddress(context, resolvedIp) }
        )
        if (observedMac.isNotBlank() && tvMac.isBlank()) {
            tvMac = observedMac
        }
        isTvPairedState = LgThinQWebOsClient.hasVerifiedVaultKey(context, resolvedIp, tvMac, tile.serialNumber)

        if (!isTvPairedState) {
            isSyncingTv = false
            statusText = "Pairing Required \u2014 Tap 'Pair TV' above and press Accept on your TV screen"
            return
        }

        if (showSearchingStatus) {
            isSyncingTv = true
            statusText = "Syncing apps & live volume from $resolvedIp..."
        }

        val cleanHost = resolvedIp.substringBefore(":").trim()
        var ssapPortOpen = SmartHomeApiClient.isPortOpenInternal(cleanHost, 3001, 450) ||
                SmartHomeApiClient.isPortOpenInternal(cleanHost, 3000, 450)

        if (!ssapPortOpen && showSearchingStatus) {
            if (tvMac.isNotBlank()) {
                statusText = "Waking TV via Wake-on-LAN ($tvMac)..."
                LgThinQWebOsClient.sendWakeOnLanMagicPacket(tvMac, cleanHost)
                repeat(6) {
                    kotlinx.coroutines.delay(750L)
                    ssapPortOpen = SmartHomeApiClient.isPortOpenInternal(cleanHost, 3001, 350) ||
                            SmartHomeApiClient.isPortOpenInternal(cleanHost, 3000, 350)
                    if (ssapPortOpen) return@repeat
                }
            }
            if (!ssapPortOpen) {
                isSyncingTv = false
                statusText = if (tvMac.isNotBlank()) {
                    "TV still waking \u2014 wait a moment and tap Sync TV Apps again"
                } else {
                    "TV is in standby \u2014 switch it on with your physical remote"
                }
                return
            }
        }

        val volResp = LgThinQWebOsClient.sendSsapCommandWithResponse(context, cleanHost, "ssap://audio/getVolume")
        val queriedVol = volResp?.optJSONObject("payload")?.optInt("volume", -1)?.takeIf { it in 0..100 }
            ?: volResp?.optInt("volume", -1)?.takeIf { it in 0..100 }
        if (queriedVol != null) {
            liveVolume = queriedVol.toFloat()
        }

        val syncRes = LgThinQWebOsClient.syncInstalledAppsFromTv(context, cleanHost)
        isSyncingTv = false
        if (syncRes.isSuccess) {
            val syncedApps = syncRes.getOrThrow()
            val latestMac = CrossEcosystemIdentityCorrelator.normalizeMac(
                LgThinQWebOsClient.getSavedMacAddress(context, cleanHost)
            ).ifBlank { tvMac }
            if (latestMac.isNotBlank()) {
                tvMac = latestMac
            }
            persistTvMetadataWithoutPowerDispatch(
                resolvedHost = cleanHost,
                resolvedMac = latestMac,
                updatedVolume = liveVolume,
                markOnlineAndActive = true
            )
            tvApps.clear()
            tvApps.addAll(syncedApps)
            mergeIntoCatalog(syncedApps)
            statusText = if (showSearchingStatus) {
                "Connected \u2014 Synced ${syncedApps.size} TV apps (Vol ${liveVolume.roundToInt()}%)"
            } else {
                "Connected \u2014 Drag the handle (\u2261) on any app to reorder"
            }
        } else {
            val errMsg = syncRes.exceptionOrNull()?.message.orEmpty()
            if (errMsg.contains("certificate", true) || errMsg.contains("auth", true) || errMsg.contains("rejected", true)) {
                isTvPairedState = false
                statusText = "TV Security Key Changed \u2014 Tap 'Pair TV' and press Accept on your TV"
            } else {
                statusText = if (showSearchingStatus) {
                    "TV did not respond \u2014 check that the TV screen is turned on"
                } else {
                    "Standby \u2014 Tap Power ON or Sync TV Apps to wake TV"
                }
            }
        }
    }

    LaunchedEffect(tile.id) {
        if (LgThinQWebOsClient.hasVerifiedVaultKey(context, activeIp, tvMac, tile.serialNumber)) {
            autoDiscoverAndSyncTv(showSearchingStatus = false)
        }
    }

    val effectiveTile = remember(tile, activeIp, tvMac, liveVolume) {
        tile.copy(
            ipAddress = activeIp.ifBlank { tile.ipAddress },
            serialNumber = tvMac.ifBlank { tile.serialNumber },
            macAddress = tvMac.ifBlank { tile.macAddress },
            volume = liveVolume
        )
    }

    fun sendKey(btn: LgRemoteButton, label: String) {
        LgThinQWebOsClient.sendRemoteButton(
            context = context,
            tile = effectiveTile,
            bridge = bridgeSettings,
            button = btn,
            scope = scope
        )
        statusText = "Sent '$label' to ${tile.title}"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhTileSurface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.5.dp, GhTileSurfaceElevated),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Surface(
                            color = if (tile.isActive) GhAccentBlue.copy(alpha = 0.22f) else GhTileSurfaceElevated,
                            shape = CircleShape,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Tv,
                                    contentDescription = null,
                                    tint = if (tile.isActive) GhAccentBlue else GhTextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "${tile.title} \u2014 LG ThinQ Apps & Remote",
                                color = GhTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = statusText,
                                color = GhAccentGreen,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isTvPairedState) {
                            FilledTonalButton(
                                onClick = {
                                    if (!isPairingTv) {
                                        scope.launch {
                                            pairTvExplicitly()
                                        }
                                    }
                                },
                                enabled = !isPairingTv,
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = GhAccentWarm.copy(alpha = 0.22f),
                                    contentColor = GhAccentWarm
                                ),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (isPairingTv) "Accept on TV..." else "Pair TV",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = {
                                if (!isSyncingTv && isTvPairedState) {
                                    scope.launch {
                                        autoDiscoverAndSyncTv(showSearchingStatus = true)
                                    }
                                }
                            },
                            enabled = isTvPairedState && !isSyncingTv && !isPairingTv,
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = GhTileSurfaceElevated,
                                contentColor = GhAccentBlue
                            ),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (isSyncingTv) "Syncing..." else "Sync TV Apps",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        FilledTonalButton(
                            onClick = { isEditingApps = !isEditingApps },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isEditingApps) GhAccentGreen.copy(alpha = 0.22f) else GhTileSurfaceElevated,
                                contentColor = if (isEditingApps) GhAccentGreen else GhTextPrimary
                            ),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                imageVector = if (isEditingApps) Icons.Default.Check else Icons.Outlined.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isEditingApps) "Done" else "Manage Apps",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        FilledTonalIconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                HorizontalDivider(color = GhTileSurfaceElevated)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // LEFT PANE: DRAG-AND-DROP REORDERABLE APP & HDMI INPUT PICKER
                    Surface(
                        color = GhTileSurfaceElevated.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, GhTileSurfaceElevated),
                        modifier = Modifier
                            .weight(0.95f)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "TV Apps & HDMI Inputs (${tvApps.size})",
                                    color = GhTextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Drag \u2261 to reorder",
                                    color = GhTextSecondary,
                                    fontSize = 10.sp
                                )
                            }

                            AnimatedVisibility(visible = isEditingApps) {
                                Surface(
                                    color = GhTileSurface,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.45f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Tap any discovered app, show, or input to show or hide it:",
                                            color = GhTextSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            items(discoveredCatalogApps, key = { "toggle_${it.id}_${it.label}" }) { candidate ->
                                                val isEnabledInList = tvApps.any {
                                                    it.label.equals(candidate.label, ignoreCase = true) ||
                                                            (candidate.contentId.isBlank() && it.contentId.isBlank() && it.appId.equals(candidate.appId, ignoreCase = true))
                                                }
                                                FilterChip(
                                                    selected = isEnabledInList,
                                                    onClick = {
                                                        if (isEnabledInList) {
                                                            val target = tvApps.firstOrNull {
                                                                it.label.equals(candidate.label, ignoreCase = true) ||
                                                                        (candidate.contentId.isBlank() && it.contentId.isBlank() && it.appId.equals(candidate.appId, ignoreCase = true))
                                                            }
                                                            if (target != null && tvApps.size > 1) {
                                                                val updatedList = LgThinQWebOsClient.removeTvApp(context, target.id)
                                                                tvApps.clear()
                                                                tvApps.addAll(updatedList)
                                                                statusText = "Hidden '${candidate.label}'"
                                                            }
                                                        } else {
                                                            val updatedList = LgThinQWebOsClient.addOrUpdateTvApp(
                                                                context = context,
                                                                label = candidate.label,
                                                                appId = candidate.appId,
                                                                contentId = candidate.contentId
                                                            )
                                                            tvApps.clear()
                                                            tvApps.addAll(updatedList)
                                                            statusText = "Added '${candidate.label}'"
                                                        }
                                                    },
                                                    shape = RoundedCornerShape(50),
                                                    label = { Text(candidate.label, fontSize = 11.sp) }
                                                )
                                            }
                                        }
                                        LaunchedEffect(discoveredCatalogApps.size) {
                                            LgThinQWebOsClient.recordDiscoveredInstalledApps(context, discoveredCatalogApps)
                                        }
                                        HorizontalDivider(color = GhTileSurfaceElevated)
                                        Text(
                                            text = "Add BBC iPlayer Show Deep-Link (e.g. EastEnders = b006m86d):",
                                            color = GhAccentGreen,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = customShowNameDraft,
                                                onValueChange = { customShowNameDraft = it },
                                                label = { Text("Show Name", fontSize = 10.sp) },
                                                singleLine = true,
                                                shape = RoundedCornerShape(10.dp),
                                                colors = claraFieldColors(),
                                                modifier = Modifier.weight(1f)
                                            )
                                            OutlinedTextField(
                                                value = customShowPidDraft,
                                                onValueChange = { customShowPidDraft = it.trim() },
                                                label = { Text("BBC PID (b006m86d)", fontSize = 10.sp) },
                                                singleLine = true,
                                                shape = RoundedCornerShape(10.dp),
                                                colors = claraFieldColors(),
                                                modifier = Modifier.weight(1f)
                                            )
                                            Button(
                                                onClick = {
                                                    val cleanShow = customShowNameDraft.trim()
                                                    val cleanPid = customShowPidDraft.trim()
                                                        .substringAfterLast("/")
                                                        .substringBefore("?")
                                                        .ifBlank { "b006m86d" }
                                                    if (cleanShow.isNotBlank()) {
                                                        val updatedList = LgThinQWebOsClient.addOrUpdateTvApp(
                                                            context = context,
                                                            label = cleanShow,
                                                            appId = "bbc",
                                                            contentId = cleanPid,
                                                            autoConfirmSelect = true
                                                        )
                                                        tvApps.clear()
                                                        tvApps.addAll(updatedList)
                                                        mergeIntoCatalog(updatedList)
                                                        customShowNameDraft = ""
                                                        customShowPidDraft = ""
                                                        statusText = "Added '$cleanShow' iPlayer shortcut!"
                                                    }
                                                },
                                                enabled = customShowNameDraft.trim().isNotBlank(),
                                                shape = RoundedCornerShape(50),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = GhAccentGreen,
                                                    contentColor = Color(0xFF141518)
                                                ),
                                                modifier = Modifier.height(36.dp)
                                            ) {
                                                Text("+ Add Show", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        HorizontalDivider(color = GhTileSurfaceElevated)
                                        Text(
                                            text = "Map IBO Pro Player IPTV Channel (e.g. Sky Sports Main Event = 401):",
                                            color = Color(0xFF00E5FF),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = iboChannelNameDraft,
                                                onValueChange = { iboChannelNameDraft = it },
                                                label = { Text("Channel Name (Sky Sports)", fontSize = 10.sp) },
                                                singleLine = true,
                                                shape = RoundedCornerShape(10.dp),
                                                colors = claraFieldColors(),
                                                modifier = Modifier.weight(1.2f)
                                            )
                                            OutlinedTextField(
                                                value = iboChannelNumberDraft,
                                                onValueChange = { iboChannelNumberDraft = it.filter { ch -> ch.isDigit() } },
                                                label = { Text("Ch # (401)", fontSize = 10.sp) },
                                                singleLine = true,
                                                shape = RoundedCornerShape(10.dp),
                                                colors = claraFieldColors(),
                                                modifier = Modifier.weight(0.8f)
                                            )
                                            Button(
                                                onClick = {
                                                    val cleanChName = iboChannelNameDraft.trim()
                                                    val chNum = iboChannelNumberDraft.trim().toIntOrNull() ?: 0
                                                    if (cleanChName.isNotBlank() && chNum > 0) {
                                                        val updatedList = LgThinQWebOsClient.addOrUpdateTvApp(
                                                            context = context,
                                                            label = "$cleanChName (#$chNum)",
                                                            appId = "com.ibo.pro",
                                                            autoConfirmSelect = true,
                                                            iboChannelNumber = chNum
                                                        )
                                                        tvApps.clear()
                                                        tvApps.addAll(updatedList)
                                                        mergeIntoCatalog(updatedList)
                                                        val nextMappings = bridgeSettings.iboChannelMappings.toMutableMap().apply {
                                                            put(cleanChName, chNum)
                                                        }
                                                        DeviceRepository.saveBridge(
                                                            context,
                                                            bridgeSettings.copy(iboChannelMappings = nextMappings)
                                                        )
                                                        iboChannelNameDraft = ""
                                                        iboChannelNumberDraft = ""
                                                        statusText = "Mapped '$cleanChName' to IBO Ch #$chNum!"
                                                    }
                                                },
                                                enabled = iboChannelNameDraft.trim().isNotBlank() &&
                                                        (iboChannelNumberDraft.trim().toIntOrNull() ?: 0) > 0,
                                                shape = RoundedCornerShape(50),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFF00E5FF),
                                                    contentColor = Color(0xFF141518)
                                                ),
                                                modifier = Modifier.height(36.dp)
                                            ) {
                                                Text("+ Map Ch", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }

                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(tvApps, key = { _, item -> item.id }) { idx, appShortcut ->
                                    val activeSource = tile.activeMediaSource
                                    val isCurrentApp = activeSource.equals(appShortcut.label, ignoreCase = true)
                                    val isBeingDragged = draggedAppId == appShortcut.id

                                    Surface(
                                        color = when {
                                            isBeingDragged -> GhTileBlueActive
                                            isCurrentApp -> GhTileBlueActive.copy(alpha = 0.7f)
                                            else -> GhTileSurface
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(
                                            if (isBeingDragged) 2.dp else 1.dp,
                                            if (isBeingDragged || isCurrentApp) GhAccentBlue else GhTileSurfaceElevated
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .zIndex(if (isBeingDragged) 10f else 0f)
                                            .graphicsLayer {
                                                translationY = if (isBeingDragged) draggedDeltaY else 0f
                                                scaleX = if (isBeingDragged) 1.02f else 1f
                                                scaleY = if (isBeingDragged) 1.02f else 1f
                                            }
                                            .clickable {
                                                val updated = effectiveTile.copy(
                                                    isActive = true,
                                                    activeMediaSource = appShortcut.label
                                                )
                                                onUpdateTile(updated)
                                                LgThinQWebOsClient.launchAppShortcut(
                                                    context = context,
                                                    tile = updated,
                                                    bridge = bridgeSettings,
                                                    shortcut = appShortcut,
                                                    scope = scope
                                                )
                                                statusText = "Launching ${appShortcut.label} on ${tile.title}..."
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 9.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                // Drag Handle for instant vertical drag-and-drop reordering
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier
                                                        .size(28.dp)
                                                        .pointerInput(appShortcut.id) {
                                                            detectDragGestures(
                                                                onDragStart = {
                                                                    draggedAppId = appShortcut.id
                                                                    draggedDeltaY = 0f
                                                                },
                                                                onDragEnd = {
                                                                    draggedAppId = null
                                                                    draggedDeltaY = 0f
                                                                    LgThinQWebOsClient.saveTvApps(context, tvApps)
                                                                    statusText = "Saved new TV app order!"
                                                                },
                                                                onDragCancel = {
                                                                    draggedAppId = null
                                                                    draggedDeltaY = 0f
                                                                    LgThinQWebOsClient.saveTvApps(context, tvApps)
                                                                },
                                                                onDrag = { change, dragAmount ->
                                                                    change.consume()
                                                                    draggedDeltaY += dragAmount.y
                                                                    val currentIdx = tvApps.indexOfFirst { it.id == appShortcut.id }
                                                                    if (currentIdx >= 0) {
                                                                        if (draggedDeltaY > rowStepPx && currentIdx < tvApps.lastIndex) {
                                                                            val item = tvApps.removeAt(currentIdx)
                                                                            tvApps.add(currentIdx + 1, item)
                                                                            draggedDeltaY -= rowStepPx
                                                                        } else if (draggedDeltaY < -rowStepPx && currentIdx > 0) {
                                                                            val item = tvApps.removeAt(currentIdx)
                                                                            tvApps.add(currentIdx - 1, item)
                                                                            draggedDeltaY += rowStepPx
                                                                        }
                                                                    }
                                                                }
                                                            )
                                                        }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.DragHandle,
                                                        contentDescription = "Drag to Reorder",
                                                        tint = if (isBeingDragged) GhAccentBlue else GhTextSecondary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    color = if (appShortcut.isHdmiInput) GhAccentWarm.copy(alpha = 0.18f)
                                                    else GhAccentBlue.copy(alpha = 0.18f),
                                                    shape = CircleShape,
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = if (appShortcut.isHdmiInput) Icons.Outlined.SettingsInputHdmi else Icons.Outlined.PlayCircle,
                                                            contentDescription = null,
                                                            tint = if (appShortcut.isHdmiInput) GhAccentWarm else GhAccentBlue,
                                                            modifier = Modifier.size(15.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(
                                                    text = appShortcut.label,
                                                    color = GhTextPrimary,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            if (isEditingApps) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    IconButton(
                                                        onClick = {
                                                            if (idx > 0) {
                                                                val item = tvApps.removeAt(idx)
                                                                tvApps.add(idx - 1, item)
                                                                LgThinQWebOsClient.saveTvApps(context, tvApps)
                                                            }
                                                        },
                                                        enabled = idx > 0,
                                                        modifier = Modifier.size(26.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.KeyboardArrowUp,
                                                            contentDescription = "Move Up",
                                                            tint = if (idx > 0) GhAccentBlue else GhTextSecondary.copy(alpha = 0.3f),
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            if (idx < tvApps.lastIndex) {
                                                                val item = tvApps.removeAt(idx)
                                                                tvApps.add(idx + 1, item)
                                                                LgThinQWebOsClient.saveTvApps(context, tvApps)
                                                            }
                                                        },
                                                        enabled = idx < tvApps.lastIndex,
                                                        modifier = Modifier.size(26.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.KeyboardArrowDown,
                                                            contentDescription = "Move Down",
                                                            tint = if (idx < tvApps.lastIndex) GhAccentBlue else GhTextSecondary.copy(alpha = 0.3f),
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            val updatedList = LgThinQWebOsClient.removeTvApp(context, appShortcut.id)
                                                            tvApps.clear()
                                                            tvApps.addAll(updatedList)
                                                        },
                                                        modifier = Modifier.size(26.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.DeleteOutline,
                                                            contentDescription = "Remove App",
                                                            tint = GhAccentRed,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.ChevronRight,
                                                    contentDescription = "Launch",
                                                    tint = GhTextSecondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // RIGHT PANE: FULL HARDWARE TV REMOTE CONTROL
                    Surface(
                        color = GhTileSurfaceElevated.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, GhTileSurfaceElevated),
                        modifier = Modifier
                            .weight(1.15f)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        val nextOn = !tile.isActive
                                        val updated = effectiveTile.copy(isActive = nextOn)
                                        onUpdateTile(updated)
                                        statusText = if (nextOn) "Connected \u2014 Turning TV ON" else "Standby \u2014 Turning TV OFF"
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (tile.isActive) GhAccentGreen else GhAccentRed,
                                        contentColor = GhBackground
                                    ),
                                    modifier = Modifier.height(38.dp)
                                ) {
                                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (tile.isActive) "ON" else "OFF", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                RemoteTopPillButton(
                                    label = "Home",
                                    icon = Icons.Outlined.Home,
                                    modifier = Modifier.weight(1f)
                                ) { sendKey(LgRemoteButton.HOME, "Home") }
                                RemoteTopPillButton(
                                    label = "Back",
                                    icon = Icons.Default.ArrowBack,
                                    modifier = Modifier.weight(1f)
                                ) { sendKey(LgRemoteButton.BACK, "Back") }
                                RemoteTopPillButton(
                                    label = "Menu",
                                    icon = Icons.Outlined.Settings,
                                    modifier = Modifier.weight(1f)
                                ) { sendKey(LgRemoteButton.MENU, "Menu") }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        color = GhTileSurface,
                                        shape = RoundedCornerShape(18.dp),
                                        border = BorderStroke(1.dp, GhTileSurfaceElevated),
                                        modifier = Modifier.width(64.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(vertical = 8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    val nextVol = (liveVolume + 2f).coerceAtMost(100f)
                                                    liveVolume = nextVol
                                                    persistTvMetadataWithoutPowerDispatch(activeIp, tvMac, updatedVolume = nextVol)
                                                    LgThinQWebOsClient.stepVolume(context, effectiveTile, volumeUp = true, scope = scope)
                                                    statusText = "Volume Up (${nextVol.roundToInt()}%)"
                                                },
                                                modifier = Modifier.size(48.dp)
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = "Volume Up", tint = GhAccentBlue)
                                            }
                                            Text(
                                                text = "VOL\n${liveVolume.roundToInt()}%",
                                                color = GhTextSecondary,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            IconButton(
                                                onClick = {
                                                    val nextVol = (liveVolume - 2f).coerceAtLeast(0f)
                                                    liveVolume = nextVol
                                                    persistTvMetadataWithoutPowerDispatch(activeIp, tvMac, updatedVolume = nextVol)
                                                    LgThinQWebOsClient.stepVolume(context, effectiveTile, volumeUp = false, scope = scope)
                                                    statusText = "Volume Down (${nextVol.roundToInt()}%)"
                                                },
                                                modifier = Modifier.size(48.dp)
                                            ) {
                                                Icon(Icons.Default.Remove, contentDescription = "Volume Down", tint = GhTextPrimary)
                                            }
                                        }
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            isMuted = !isMuted
                                            LgThinQWebOsClient.toggleMute(context, effectiveTile, isMuted, scope)
                                            statusText = if (isMuted) "TV Muted" else "TV Unmuted"
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (isMuted) GhAccentRed.copy(alpha = 0.25f) else GhTileSurface,
                                            contentColor = if (isMuted) GhAccentRed else GhTextPrimary
                                        ),
                                        modifier = Modifier
                                            .width(64.dp)
                                            .height(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isMuted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                                            contentDescription = if (isMuted) "Unmute TV" else "Mute TV",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(184.dp)
                                        .clip(CircleShape)
                                        .background(GhTileSurface)
                                        .border(1.5.dp, GhTileSurfaceElevated, CircleShape)
                                ) {
                                    IconButton(
                                        onClick = { sendKey(LgRemoteButton.UP, "Up") },
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .padding(top = 4.dp)
                                            .size(48.dp)
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Navigate Up", tint = GhTextPrimary, modifier = Modifier.size(28.dp))
                                    }
                                    IconButton(
                                        onClick = { sendKey(LgRemoteButton.DOWN, "Down") },
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 4.dp)
                                            .size(48.dp)
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Navigate Down", tint = GhTextPrimary, modifier = Modifier.size(28.dp))
                                    }
                                    IconButton(
                                        onClick = { sendKey(LgRemoteButton.LEFT, "Left") },
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .padding(start = 4.dp)
                                            .size(48.dp)
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Navigate Left", tint = GhTextPrimary, modifier = Modifier.size(28.dp))
                                    }
                                    IconButton(
                                        onClick = { sendKey(LgRemoteButton.RIGHT, "Right") },
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .padding(end = 4.dp)
                                            .size(48.dp)
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Navigate Right", tint = GhTextPrimary, modifier = Modifier.size(28.dp))
                                    }
                                    Surface(
                                        color = GhAccentBlue.copy(alpha = 0.22f),
                                        shape = CircleShape,
                                        border = BorderStroke(1.5.dp, GhAccentBlue),
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clickable { sendKey(LgRemoteButton.OK, "OK / Select") }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text("OK", color = GhAccentBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        color = GhTileSurface,
                                        shape = RoundedCornerShape(18.dp),
                                        border = BorderStroke(1.dp, GhTileSurfaceElevated),
                                        modifier = Modifier.width(64.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(vertical = 8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            IconButton(
                                                onClick = { sendKey(LgRemoteButton.CH_UP, "Channel Up") },
                                                modifier = Modifier.size(48.dp)
                                            ) {
                                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Channel Up", tint = GhAccentBlue)
                                            }
                                            Text("CH", color = GhTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            IconButton(
                                                onClick = { sendKey(LgRemoteButton.CH_DOWN, "Channel Down") },
                                                modifier = Modifier.size(48.dp)
                                            ) {
                                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Channel Down", tint = GhTextPrimary)
                                            }
                                        }
                                    }

                                    FilledTonalButton(
                                        onClick = { sendKey(LgRemoteButton.EXIT, "Exit") },
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = GhTileSurface,
                                            contentColor = GhTextPrimary
                                        ),
                                        modifier = Modifier
                                            .width(64.dp)
                                            .height(48.dp)
                                    ) {
                                        Text("EXIT", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Surface(
                                color = GhTileSurface,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, GhTileSurfaceElevated),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { sendKey(LgRemoteButton.REWIND, "Rewind") },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.FastRewind, contentDescription = "Rewind", tint = GhTextPrimary)
                                    }
                                    IconButton(
                                        onClick = { sendKey(LgRemoteButton.PAUSE, "Pause") },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.Pause, contentDescription = "Pause", tint = GhTextPrimary)
                                    }
                                    FilledIconButton(
                                        onClick = { sendKey(LgRemoteButton.PLAY, "Play") },
                                        modifier = Modifier.size(48.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = GhAccentGreen.copy(alpha = 0.25f),
                                            contentColor = GhAccentGreen
                                        )
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                                    }
                                    IconButton(
                                        onClick = { sendKey(LgRemoteButton.STOP, "Stop") },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.Stop, contentDescription = "Stop", tint = GhTextPrimary)
                                    }
                                    IconButton(
                                        onClick = { sendKey(LgRemoteButton.FAST_FORWARD, "Fast Forward") },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.FastForward, contentDescription = "Fast Forward", tint = GhTextPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteTopPillButton(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = GhTileSurface,
            contentColor = GhTextPrimary
        ),
        modifier = modifier.height(38.dp)
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(15.dp), tint = GhAccentBlue)
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
