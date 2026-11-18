package com.jarvis.smarthomelauncher.ui.modals

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.viewmodels.findComponentActivity
import com.jarvis.smarthomelauncher.ui.widgets.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// SMART PLUG / SOCKET LIVE TELEMETRY, CAPABILITY EXPLORER & SOCKET RENAME MODAL
// - Strictly reflects the introspected hardware gang count (1-Gang vs 2-Gang)
//   and power-metering capability (zero dummy spoof buttons or fake 230.0 V).
// - Dispatches socket toggles immediately across Tuya LAN 6668, Tapo/Kasa,
//   Sonoff, Shelly Gen1/Gen2/Gen3, Tasmota, Matter, MQTT, and Home Assistant.
// - Includes the Universal Capability Explorer to control, rename, and pin
//   any discovered hardware DP / capability to the Hero Card.
// ---------------------------------------------------------------------------
/**
 * Change 8.1 + Change 3.2:
 * Lifecycle-aware ViewModel backing device control popups and telemetry modals so single-device
 * refresh & capability sync survive recomposition and configuration changes cleanly.
 */
class DeviceControlModalViewModel(application: Application) : AndroidViewModel(application) {
    private val appCtx: Context
        get() = getApplication<Application>().applicationContext

    val refreshingByTileId = mutableStateMapOf<String, Boolean>()
    val feedbackBannerByTileId = mutableStateMapOf<String, Pair<Boolean, String>>()
    val syncedTileOverrideById = mutableStateMapOf<String, SmartTile>()

    fun clearFeedback(tileId: String) {
        feedbackBannerByTileId.remove(tileId)
    }

    fun recordLocalMutation(updated: SmartTile) {
        syncedTileOverrideById[updated.id] = updated
    }

    fun refreshDevice(
        tile: SmartTile,
        bridge: BridgeSettings,
        onSynced: ((SmartTile) -> Unit)? = null
    ) {
        if (refreshingByTileId[tile.id] == true) return
        refreshingByTileId[tile.id] = true
        feedbackBannerByTileId.remove(tile.id)
        viewModelScope.launch {
            val res = refreshSingleDeviceTelemetrySnapshot(
                context = appCtx,
                tile = syncedTileOverrideById[tile.id] ?: tile,
                bridge = bridge
            )
            refreshingByTileId[tile.id] = false
            if (res.isSuccess) {
                val (syncedTile, msg) = res.getOrThrow()
                syncedTileOverrideById[tile.id] = syncedTile
                feedbackBannerByTileId[tile.id] = true to msg
                onSynced?.invoke(syncedTile)
            } else {
                feedbackBannerByTileId[tile.id] = false to (
                        res.exceptionOrNull()?.message ?: "Device did not respond within 5 seconds."
                        )
            }
        }
    }

    fun retryCapabilitySyncOnly(
        tile: SmartTile,
        bridge: BridgeSettings,
        onSynced: ((SmartTile) -> Unit)? = null
    ) {
        refreshDevice(tile = tile, bridge = bridge) { synced ->
            val hydratedCaps = synced.capabilities.ifEmpty {
                SmartHomeApiClient.buildDefaultCapabilitiesForTile(synced)
            }
            val promoted = synced.copy(
                capabilities = hydratedCaps,
                capabilitySchemaHash = CapabilitySchemaHasher.computeHash(hydratedCaps),
                lifecycleState = if (synced.isOnline) DeviceLifecycleState.OPERATIONAL else DeviceLifecycleState.OFFLINE
            )
            syncedTileOverrideById[tile.id] = promoted
            viewModelScope.launch(Dispatchers.Main) {
                val live = UniversalOnboardingEngine.activeDashboardTilesRef
                if (live != null) {
                    val idx = live.indexOfFirst { it.id == tile.id }
                    if (idx >= 0) {
                        live[idx] = promoted
                        DeviceRepository.saveTiles(appCtx, live)
                    }
                }
            }
            onSynced?.invoke(promoted)
        }
    }
}

@Composable
fun SmartKnightLiveDpsModal(
    tile: SmartTile,
    onUpdateTile: (SmartTile) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val bridge = remember { DeviceRepository.loadBridge(context) }
    val activity = remember(context) { context.findComponentActivity() }
    val modalVm = remember(activity, context) {
        if (activity != null) {
            ViewModelProvider(activity)[DeviceControlModalViewModel::class.java]
        } else {
            DeviceControlModalViewModel(context.applicationContext as Application)
        }
    }

    LaunchedEffect(tile) {
        modalVm.syncedTileOverrideById[tile.id] = tile
    }
    val displayTile = modalVm.syncedTileOverrideById[tile.id] ?: tile
    val isRefreshingTelemetry = modalVm.refreshingByTileId[tile.id] == true
    val refreshFeedbackBanner = modalVm.feedbackBannerByTileId[tile.id]

    val commitTileMutation: (SmartTile) -> Unit = { mutated ->
        modalVm.recordLocalMutation(mutated)
        onUpdateTile(mutated)
    }

    // Updates modal & dashboard state after dispatchCapabilityCommand WITHOUT triggering a duplicate MainActivity dispatch
    val persistCapabilityStateOnly: (SmartTile) -> Unit = { updatedCapTile ->
        modalVm.recordLocalMutation(updatedCapTile)
        val liveTiles = UniversalOnboardingEngine.activeDashboardTilesRef
        if (liveTiles != null) {
            val idx = liveTiles.indexOfFirst { it.id == updatedCapTile.id }
            if (idx in liveTiles.indices) {
                liveTiles[idx] = updatedCapTile
                DeviceRepository.saveTiles(context, liveTiles)
            }
        }
    }

    val totalGangCount = maxOf(displayTile.multiGangSocketCount, displayTile.socketCount())
    val isDualSocket = totalGangCount >= 2
    val socket1On = displayTile.isSocketActive(1)
    val socket2On = displayTile.isSocketActive(2)
    val currentAmps = displayTile.currentMilliamps / 1000f
    val showPowerTelemetry = displayTile.hasPowerTelemetryCapability

    val effectiveCapabilities = remember(displayTile) {
        displayTile.capabilities.ifEmpty {
            SmartHomeApiClient.buildDefaultCapabilitiesForTile(displayTile)
        }
    }

    var isEditingMainTitle by remember(tile.id) { mutableStateOf(false) }
    var mainTitleDraft by remember(tile.id, displayTile.title) { mutableStateOf(displayTile.title) }

    var isEditingSocket1 by remember(tile.id) { mutableStateOf(false) }
    var socket1Draft by remember(tile.id, displayTile.socket1Name) {
        mutableStateOf(displayTile.socket1Name.ifBlank { "Socket 1" })
    }

    var isEditingSocket2 by remember(tile.id) { mutableStateOf(false) }
    var socket2Draft by remember(tile.id, displayTile.socket2Name) {
        mutableStateOf(displayTile.socket2Name.ifBlank { "Socket 2" })
    }

    var editingCapabilityKey by remember(tile.id) { mutableStateOf<String?>(null) }
    var capabilityLabelDraft by remember(tile.id) { mutableStateOf("") }

    fun dispatchModalSocketToggle(
        updatedTile: SmartTile,
        @Suppress("UNUSED_PARAMETER") socketNumber: Int,
        @Suppress("UNUSED_PARAMETER") turnOn: Boolean
    ) {
        commitTileMutation(updatedTile)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhTileSurface,
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.5.dp, GhTileSurfaceElevated),
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .fillMaxHeight(0.88f)
                .padding(12.dp)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isCompactMobile = maxWidth < 560.dp

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (isCompactMobile) 16.dp else 22.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header Row: Icon + Editable Device Title + Hardware Badges + Refresh & Close
                    ResponsiveSplitRow(
                        isStacked = isCompactMobile,
                        firstContent = { mod ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = mod
                            ) {
                                Surface(
                                    color = if (socket1On || (isDualSocket && socket2On))
                                        GhAccentGreen.copy(alpha = 0.22f)
                                    else
                                        GhTileSurfaceElevated,
                                    shape = CircleShape,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Outlined.Bolt,
                                            contentDescription = null,
                                            tint = if (socket1On || (isDualSocket && socket2On)) GhAccentGreen else GhAccentBlue,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    if (isEditingMainTitle) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = mainTitleDraft,
                                                onValueChange = { mainTitleDraft = it },
                                                singleLine = true,
                                                textStyle = TextStyle(
                                                    color = GhTextPrimary,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = GhFont
                                                ),
                                                shape = RoundedCornerShape(10.dp),
                                                colors = claraFieldColors(),
                                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                                keyboardActions = KeyboardActions(
                                                    onDone = {
                                                        val clean = mainTitleDraft.trim().ifBlank { displayTile.title }
                                                        commitTileMutation(displayTile.copy(title = clean))
                                                        isEditingMainTitle = false
                                                    }
                                                ),
                                                modifier = Modifier.widthIn(min = 160.dp, max = 260.dp)
                                            )
                                            FilledTonalIconButton(
                                                onClick = {
                                                    val clean = mainTitleDraft.trim().ifBlank { displayTile.title }
                                                    commitTileMutation(displayTile.copy(title = clean))
                                                    isEditingMainTitle = false
                                                },
                                                modifier = Modifier.size(32.dp),
                                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                    containerColor = GhAccentGreen.copy(alpha = 0.22f),
                                                    contentColor = GhAccentGreen
                                                )
                                            ) {
                                                Icon(Icons.Default.Check, contentDescription = "Save Name", modifier = Modifier.size(16.dp))
                                            }
                                            IconButton(
                                                onClick = {
                                                    mainTitleDraft = displayTile.title
                                                    isEditingMainTitle = false
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = GhTextSecondary, modifier = Modifier.size(15.dp))
                                            }
                                        }
                                    } else {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "${displayTile.title} \u2014 Sockets, Timers & Capabilities",
                                                color = GhTextPrimary,
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            IconButton(
                                                onClick = {
                                                    mainTitleDraft = displayTile.title
                                                    isEditingMainTitle = true
                                                },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Edit,
                                                    contentDescription = "Rename Device",
                                                    tint = GhAccentBlue,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }
                                    }
                                    val providerLabel = displayTile.activeProviderId.ifBlank { displayTile.deviceSubtype.label }
                                    Text(
                                        text = if (isDualSocket) {
                                            "$providerLabel \u2022 Dual-Socket Hardware \u2022 Rename individual outlets or pin capabilities to the Hero Card"
                                        } else {
                                            "$providerLabel \u2022 Single-Socket Hardware \u2022 Manage hardware DPs and Hero Card shortcuts"
                                        },
                                        color = GhTextSecondary,
                                        fontSize = 11.5.sp
                                    )
                                }
                            }
                        },
                        secondContent = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Read-Only Hardware Gang Count Badge (True to Paired Device)
                                Surface(
                                    color = GhTileSurfaceElevated,
                                    shape = RoundedCornerShape(50),
                                    border = BorderStroke(1.dp, GhAccentBlue.copy(alpha = 0.35f)),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Power,
                                            contentDescription = null,
                                            tint = GhAccentBlue,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "${totalGangCount}-Gang Hardware",
                                            color = GhAccentBlue,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                if (showPowerTelemetry) {
                                    Surface(
                                        color = GhAccentGreen.copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(50),
                                        border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.55f)),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 11.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.ElectricMeter,
                                                contentDescription = null,
                                                tint = GhAccentGreen,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "Power Meter Active",
                                                color = GhAccentGreen,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                FilledTonalButton(
                                    onClick = {
                                        modalVm.refreshDevice(tile = displayTile, bridge = bridge)
                                    },
                                    enabled = !isRefreshingTelemetry,
                                    shape = RoundedCornerShape(50),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = GhTileSurfaceElevated,
                                        contentColor = GhAccentBlue
                                    ),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    if (isRefreshingTelemetry) {
                                        CircularProgressIndicator(
                                            color = GhAccentBlue,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    } else {
                                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                    }
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = if (isRefreshingTelemetry) "Syncing..." else "Refresh",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                FilledTonalButton(
                                    onClick = onDismiss,
                                    shape = RoundedCornerShape(50),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = GhTileSurfaceElevated,
                                        contentColor = GhTextPrimary
                                    ),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Close", fontSize = 11.5.sp)
                                }
                            }
                        }
                    )

                    HorizontalDivider(color = GhTileSurfaceElevated)

                    AnimatedVisibility(visible = refreshFeedbackBanner != null) {
                        val banner = refreshFeedbackBanner
                        if (banner != null) {
                            val (okBanner, bannerMsg) = banner
                            val bannerTint = if (okBanner) GhAccentGreen else GhAccentRed
                            Surface(
                                color = bannerTint.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, bannerTint.copy(alpha = 0.55f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = bannerMsg,
                                        color = GhTextPrimary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { modalVm.clearFeedback(tile.id) },
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Dismiss",
                                            tint = GhTextSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val inferredAppliance = displayTile.inferredApplianceCategory
                    if (inferredAppliance != ApplianceFingerprintCategory.UNKNOWN) {
                        val suggestedName = inferredAppliance.suggestedSocketName.ifBlank { inferredAppliance.label }
                        Surface(
                            color = GhAccentGreen.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.AutoAwesome,
                                        contentDescription = null,
                                        tint = GhAccentGreen,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "Smart Plug X-Ray: ${inferredAppliance.label}",
                                            color = GhTextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = GhFont
                                        )
                                        Text(
                                            text = "Inferred from live power signature (${"%.1f".format(tile.powerWatts)} W)",
                                            color = GhTextSecondary,
                                            fontSize = 11.sp,
                                            fontFamily = GhFont
                                        )
                                    }
                                }
                                AssistChip(
                                    onClick = {
                                        socket1Draft = suggestedName
                                        val updated = if (isDualSocket) {
                                            tile.copy(
                                                socket1Name = suggestedName,
                                                detectedApplianceCategory = inferredAppliance
                                            )
                                        } else {
                                            mainTitleDraft = suggestedName
                                            tile.copy(
                                                title = suggestedName,
                                                socket1Name = suggestedName,
                                                detectedApplianceCategory = inferredAppliance
                                            )
                                        }
                                        onUpdateTile(updated)
                                    },
                                    shape = RoundedCornerShape(50),
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.DriveFileRenameOutline,
                                            contentDescription = null,
                                            tint = GhAccentGreen,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = "Auto-Name from Power Signature",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // =========================================================================
                    // 1. INDIVIDUAL SOCKET CONTROL & INLINE PENCIL-ICON RENAMING CARDS
                    // =========================================================================
                    if (isDualSocket) {
                        ResponsiveSplitRow(
                            isStacked = isCompactMobile,
                            firstContent = { mod ->
                                SocketControlAndRenameCard(
                                    badgeLabel = "SOCKET 1 (LEFT)",
                                    socketName = displayTile.socket1Name.ifBlank { "Socket 1" },
                                    isEditingName = isEditingSocket1,
                                    nameDraft = socket1Draft,
                                    onNameDraftChange = { socket1Draft = it },
                                    onStartEditName = {
                                        socket1Draft = displayTile.socket1Name.ifBlank { "Socket 1" }
                                        isEditingSocket1 = true
                                    },
                                    onSaveName = {
                                        val clean = socket1Draft.trim().ifBlank { "Socket 1" }
                                        commitTileMutation(displayTile.copy(socket1Name = clean))
                                        isEditingSocket1 = false
                                    },
                                    onCancelEdit = {
                                        socket1Draft = displayTile.socket1Name.ifBlank { "Socket 1" }
                                        isEditingSocket1 = false
                                    },
                                    isOn = socket1On,
                                    onToggle = { nextOn ->
                                        val updated = displayTile.copy(isActive = nextOn)
                                        dispatchModalSocketToggle(updated, socketNumber = 1, turnOn = nextOn)
                                    },
                                    modifier = mod
                                )
                            },
                            secondContent = { mod ->
                                SocketControlAndRenameCard(
                                    badgeLabel = "SOCKET 2 (RIGHT)",
                                    socketName = displayTile.socket2Name.ifBlank { "Socket 2" },
                                    isEditingName = isEditingSocket2,
                                    nameDraft = socket2Draft,
                                    onNameDraftChange = { socket2Draft = it },
                                    onStartEditName = {
                                        socket2Draft = displayTile.socket2Name.ifBlank { "Socket 2" }
                                        isEditingSocket2 = true
                                    },
                                    onSaveName = {
                                        val clean = socket2Draft.trim().ifBlank { "Socket 2" }
                                        commitTileMutation(displayTile.copy(socket2Name = clean))
                                        isEditingSocket2 = false
                                    },
                                    onCancelEdit = {
                                        socket2Draft = displayTile.socket2Name.ifBlank { "Socket 2" }
                                        isEditingSocket2 = false
                                    },
                                    isOn = socket2On,
                                    onToggle = { nextOn ->
                                        val updated = displayTile.withUpdatedSocketState(2, nextOn)
                                        dispatchModalSocketToggle(updated, socketNumber = 2, turnOn = nextOn)
                                    },
                                    modifier = mod
                                )
                            }
                        )

                        val extraSocketCaps = remember(effectiveCapabilities) {
                            effectiveCapabilities.filter { cap ->
                                cap.key.matches(Regex("^(?:socket|switch)_([3-9]|\\d{2,})$", RegexOption.IGNORE_CASE))
                            }.sortedBy { cap ->
                                cap.key.substringAfterLast("_").toIntOrNull() ?: 3
                            }
                        }
                        extraSocketCaps.forEach { extraCap ->
                            val outletNum = extraCap.key.substringAfterLast("_").toIntOrNull() ?: 3
                            val isOutletOn = displayTile.isSocketActive(outletNum)
                            val isEditingThisOutlet = editingCapabilityKey == extraCap.key
                            SocketControlAndRenameCard(
                                badgeLabel = "SOCKET $outletNum",
                                socketName = extraCap.label.ifBlank { "Socket $outletNum" },
                                isEditingName = isEditingThisOutlet,
                                nameDraft = if (isEditingThisOutlet) capabilityLabelDraft else extraCap.label.ifBlank { "Socket $outletNum" },
                                onNameDraftChange = { capabilityLabelDraft = it },
                                onStartEditName = {
                                    capabilityLabelDraft = extraCap.label.ifBlank { "Socket $outletNum" }
                                    editingCapabilityKey = extraCap.key
                                },
                                onSaveName = {
                                    val clean = capabilityLabelDraft.trim().ifBlank { "Socket $outletNum" }
                                    val updated = displayTile.withUpdatedSocketState(
                                        socketIndexOneBased = outletNum,
                                        active = isOutletOn,
                                        labelOverride = clean
                                    )
                                    commitTileMutation(updated)
                                    editingCapabilityKey = null
                                },
                                onCancelEdit = {
                                    editingCapabilityKey = null
                                },
                                isOn = isOutletOn,
                                onToggle = { nextOn ->
                                    dispatchCapabilityCommand(
                                        context = context,
                                        tile = displayTile.withUpdatedSocketState(outletNum, nextOn),
                                        capability = extraCap,
                                        newValue = nextOn.toString(),
                                        bridge = bridge,
                                        audioManager = audioManager,
                                        scope = scope,
                                        onTileUpdated = persistCapabilityStateOnly
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        SocketControlAndRenameCard(
                            badgeLabel = "PRIMARY SMART SOCKET",
                            socketName = displayTile.title.ifBlank { "Smart Plug" },
                            isEditingName = isEditingSocket1,
                            nameDraft = socket1Draft,
                            onNameDraftChange = { socket1Draft = it },
                            onStartEditName = {
                                socket1Draft = displayTile.title.ifBlank { "Smart Plug" }
                                isEditingSocket1 = true
                            },
                            onSaveName = {
                                val clean = socket1Draft.trim().ifBlank { displayTile.title }
                                commitTileMutation(displayTile.copy(title = clean, socket1Name = clean))
                                isEditingSocket1 = false
                            },
                            onCancelEdit = {
                                socket1Draft = displayTile.title
                                isEditingSocket1 = false
                            },
                            isOn = socket1On,
                            onToggle = { nextOn ->
                                val updated = displayTile.copy(
                                    isActive = nextOn,
                                    powerWatts = if (nextOn) displayTile.powerWatts else 0f
                                )
                                dispatchModalSocketToggle(updated, socketNumber = 1, turnOn = nextOn)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // =========================================================================
                    // 2. CONDITIONAL LIVE ELECTRICAL TELEMETRY CARDS (Zero Fake 230.0 V)
                    // =========================================================================
                    AnimatedVisibility(visible = showPowerTelemetry) {
                        val dayRate = bridge.electricityTariffDayRateGbp.coerceAtLeast(0.05f)
                        val voltageDisplay = if (displayTile.voltageVolts > 0f) {
                            "${"%.1f".format(displayTile.voltageVolts)} V"
                        } else {
                            "-- V"
                        }
                        val voltageSubtitle = if (displayTile.voltageVolts > 0f) "AC Supply" else "Not Reported"

                        if (isCompactMobile) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    TelemetryMetricCard(
                                        title = "Live Power",
                                        value = "${"%.1f".format(displayTile.powerWatts)} W",
                                        subtitle = if (displayTile.powerWatts > 5f) "Active Load" else "0W Standby",
                                        icon = Icons.Outlined.Bolt,
                                        accentColor = if (displayTile.powerWatts > 5f) GhAccentWarm else GhAccentGreen,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TelemetryMetricCard(
                                        title = "Mains Voltage",
                                        value = voltageDisplay,
                                        subtitle = voltageSubtitle,
                                        icon = Icons.Outlined.ElectricalServices,
                                        accentColor = GhAccentBlue,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    TelemetryMetricCard(
                                        title = "Live Current",
                                        value = "${"%.2f".format(currentAmps)} A",
                                        subtitle = "${displayTile.currentMilliamps.roundToInt()} mA draw",
                                        icon = Icons.Outlined.Speed,
                                        accentColor = GhAccentPurple,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TelemetryMetricCard(
                                        title = "Total Usage",
                                        value = "${"%.3f".format(displayTile.totalEnergyKwh)} kWh",
                                        subtitle = "Est. \u00A3${"%.2f".format(displayTile.totalEnergyKwh * dayRate)}",
                                        icon = Icons.Outlined.EnergySavingsLeaf,
                                        accentColor = GhAccentGreen,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                TelemetryMetricCard(
                                    title = "Live Power",
                                    value = "${"%.1f".format(displayTile.powerWatts)} W",
                                    subtitle = if (displayTile.powerWatts > 5f) "Active Load" else "0W Standby",
                                    icon = Icons.Outlined.Bolt,
                                    accentColor = if (displayTile.powerWatts > 5f) GhAccentWarm else GhAccentGreen,
                                    modifier = Modifier.weight(1f)
                                )
                                TelemetryMetricCard(
                                    title = "Mains Voltage",
                                    value = voltageDisplay,
                                    subtitle = voltageSubtitle,
                                    icon = Icons.Outlined.ElectricalServices,
                                    accentColor = GhAccentBlue,
                                    modifier = Modifier.weight(1f)
                                )
                                TelemetryMetricCard(
                                    title = "Live Current",
                                    value = "${"%.2f".format(currentAmps)} A",
                                    subtitle = "${displayTile.currentMilliamps.roundToInt()} mA draw",
                                    icon = Icons.Outlined.Speed,
                                    accentColor = GhAccentPurple,
                                    modifier = Modifier.weight(1f)
                                )
                                TelemetryMetricCard(
                                    title = "Total Usage",
                                    value = "${"%.3f".format(displayTile.totalEnergyKwh)} kWh",
                                    subtitle = "Est. \u00A3${"%.2f".format(displayTile.totalEnergyKwh * dayRate)}",
                                    icon = Icons.Outlined.EnergySavingsLeaf,
                                    accentColor = GhAccentGreen,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // =========================================================================
                    // 3. UNIVERSAL HARDWARE CAPABILITY EXPLORER & HERO CARD PINNING
                    // =========================================================================
                    Surface(
                        color = GhTileSurfaceElevated.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, GhCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Discovered Hardware Capabilities & Hero Shortcuts (${effectiveCapabilities.size})",
                                        color = GhTextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = GhFont
                                    )
                                    Text(
                                        text = "Control any DP / feature below, tap the pencil icon to rename it, or toggle 'Pinned to Hero' to show it on the Hero Card.",
                                        color = GhTextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = GhFont
                                    )
                                }
                            }

                            effectiveCapabilities.forEach { cap ->
                                val isEditingThisCap = editingCapabilityKey == cap.key
                                Surface(
                                    color = GhTileSurface,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (cap.isPinnedToHero) GhAccentGreen.copy(alpha = 0.45f) else GhCardBorder
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                if (isEditingThisCap) {
                                                    OutlinedTextField(
                                                        value = capabilityLabelDraft,
                                                        onValueChange = { capabilityLabelDraft = it },
                                                        singleLine = true,
                                                        textStyle = TextStyle(
                                                            color = GhTextPrimary,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        ),
                                                        shape = RoundedCornerShape(10.dp),
                                                        colors = claraFieldColors(),
                                                        modifier = Modifier.widthIn(min = 140.dp, max = 220.dp)
                                                    )
                                                    FilledTonalIconButton(
                                                        onClick = {
                                                            val cleanLbl = capabilityLabelDraft.trim().ifBlank { cap.label }
                                                            val updatedList = effectiveCapabilities.map {
                                                                if (it.key == cap.key) it.copy(label = cleanLbl) else it
                                                            }
                                                            onUpdateTile(tile.copy(capabilities = updatedList))
                                                            editingCapabilityKey = null
                                                        },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Icon(Icons.Default.Check, contentDescription = "Save", modifier = Modifier.size(14.dp))
                                                    }
                                                } else {
                                                    Text(
                                                        text = cap.label,
                                                        color = GhTextPrimary,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    IconButton(
                                                        onClick = {
                                                            capabilityLabelDraft = cap.label
                                                            editingCapabilityKey = cap.key
                                                        },
                                                        modifier = Modifier.size(22.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Outlined.Edit,
                                                            contentDescription = "Rename Capability",
                                                            tint = GhAccentBlue,
                                                            modifier = Modifier.size(13.dp)
                                                        )
                                                    }
                                                    Surface(
                                                        color = GhTileSurfaceElevated,
                                                        shape = RoundedCornerShape(50)
                                                    ) {
                                                        val humanPillLabel = when (cap.kind) {
                                                            DeviceCapabilityKind.BOOLEAN_SWITCH -> "Switch"
                                                            DeviceCapabilityKind.COUNTDOWN_TIMER -> "Timer"
                                                            DeviceCapabilityKind.READ_ONLY_SENSOR -> "Sensor"
                                                            DeviceCapabilityKind.ENUM_MODE -> "Mode"
                                                            DeviceCapabilityKind.NUMERIC_SLIDER -> "Level"
                                                            DeviceCapabilityKind.ACTION_TRIGGER -> "Action"
                                                        }
                                                        Text(
                                                            text = humanPillLabel,
                                                            color = GhTextSecondary,
                                                            fontSize = 9.5.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            // Pin / Unpin to Hero Card Chip
                                            if (cap.isWritable) {
                                                FilterChip(
                                                    selected = cap.isPinnedToHero,
                                                    onClick = {
                                                        val updatedList = effectiveCapabilities.map {
                                                            if (it.key == cap.key) it.copy(isPinnedToHero = !it.isPinnedToHero) else it
                                                        }
                                                        onUpdateTile(tile.copy(capabilities = updatedList))
                                                    },
                                                    shape = RoundedCornerShape(50),
                                                    label = {
                                                        Text(
                                                            text = if (cap.isPinnedToHero) "Pinned to Hero \u2713" else "Pin to Hero",
                                                            fontSize = 10.sp,
                                                            fontWeight = if (cap.isPinnedToHero) FontWeight.Bold else FontWeight.Medium
                                                        )
                                                    }
                                                )
                                            } else {
                                                Text(
                                                    text = "${cap.currentValue}${if (cap.unit.isNotBlank()) " ${cap.unit}" else ""}",
                                                    color = GhAccentGreen,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        // Interactive Control by Capability Kind
                                        when (cap.kind) {
                                            DeviceCapabilityKind.BOOLEAN_SWITCH -> {
                                                val isBoolOn = cap.currentValue.equals("true", true) ||
                                                        cap.currentValue.equals("on", true) ||
                                                        cap.currentValue.equals("locked", true) ||
                                                        cap.currentValue == "1"
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "Current State: ${if (isBoolOn) "ON" else "OFF"}",
                                                        color = if (isBoolOn) GhAccentGreen else GhTextSecondary,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Switch(
                                                        checked = isBoolOn,
                                                        onCheckedChange = { nextBool ->
                                                            dispatchCapabilityCommand(
                                                                context = context,
                                                                tile = displayTile.copy(capabilities = effectiveCapabilities),
                                                                capability = cap,
                                                                newValue = nextBool.toString(),
                                                                bridge = bridge,
                                                                audioManager = audioManager,
                                                                scope = scope,
                                                                onTileUpdated = persistCapabilityStateOnly
                                                            )
                                                        },
                                                        colors = SwitchDefaults.colors(
                                                            checkedThumbColor = Color(0xFF0F1114),
                                                            checkedTrackColor = GhAccentGreen
                                                        )
                                                    )
                                                }
                                            }
                                            DeviceCapabilityKind.ENUM_MODE -> {
                                                val options = cap.enumOptions.ifEmpty { listOf("off", "on", "last") }
                                                LazyRow(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    items(options) { opt ->
                                                        val isSelected = cap.currentValue.equals(opt, ignoreCase = true)
                                                        FilterChip(
                                                            selected = isSelected,
                                                            onClick = {
                                                                dispatchCapabilityCommand(
                                                                    context = context,
                                                                    tile = displayTile.copy(capabilities = effectiveCapabilities),
                                                                    capability = cap,
                                                                    newValue = opt,
                                                                    bridge = bridge,
                                                                    audioManager = audioManager,
                                                                    scope = scope,
                                                                    onTileUpdated = persistCapabilityStateOnly
                                                                )
                                                            },
                                                            shape = RoundedCornerShape(50),
                                                            label = {
                                                                Text(
                                                                    text = opt.uppercase(Locale.US),
                                                                    fontSize = 11.sp,
                                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                                )
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                            DeviceCapabilityKind.COUNTDOWN_TIMER -> {
                                                val curSecs = cap.currentValue.toFloatOrNull()?.roundToInt() ?: 0
                                                val timerPresets = listOf(
                                                    0 to "Off",
                                                    900 to "15m",
                                                    1800 to "30m",
                                                    3600 to "1h",
                                                    7200 to "2h",
                                                    14400 to "4h"
                                                )
                                                LazyRow(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    items(timerPresets) { (secVal, label) ->
                                                        val isSel = curSecs == secVal
                                                        FilterChip(
                                                            selected = isSel,
                                                            onClick = {
                                                                dispatchCapabilityCommand(
                                                                    context = context,
                                                                    tile = displayTile.copy(capabilities = effectiveCapabilities),
                                                                    capability = cap,
                                                                    newValue = secVal.toString(),
                                                                    bridge = bridge,
                                                                    audioManager = audioManager,
                                                                    scope = scope,
                                                                    onTileUpdated = persistCapabilityStateOnly
                                                                )
                                                            },
                                                            shape = RoundedCornerShape(50),
                                                            label = { Text(label, fontSize = 11.sp) }
                                                        )
                                                    }
                                                }
                                            }
                                            DeviceCapabilityKind.NUMERIC_SLIDER -> {
                                                val minV = cap.minValue
                                                val maxV = cap.maxValue.coerceAtLeast(minV + 1f)
                                                var sliderVal by remember(cap.key, cap.currentValue) {
                                                    mutableFloatStateOf(
                                                        (cap.currentValue.toFloatOrNull() ?: minV).coerceIn(minV, maxV)
                                                    )
                                                }
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Slider(
                                                        value = sliderVal,
                                                        onValueChange = { sliderVal = it },
                                                        onValueChangeFinished = {
                                                            dispatchCapabilityCommand(
                                                                context = context,
                                                                tile = displayTile.copy(capabilities = effectiveCapabilities),
                                                                capability = cap,
                                                                newValue = "%.1f".format(Locale.US, sliderVal),
                                                                bridge = bridge,
                                                                audioManager = audioManager,
                                                                scope = scope,
                                                                onTileUpdated = persistCapabilityStateOnly
                                                            )
                                                        },
                                                        valueRange = minV..maxV,
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .height(24.dp),
                                                        colors = SliderDefaults.colors(
                                                            thumbColor = Color.White,
                                                            activeTrackColor = GhAccentGreen
                                                        )
                                                    )
                                                    Text(
                                                        text = "${"%.1f".format(sliderVal)}${cap.unit}",
                                                        color = GhAccentGreen,
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                            DeviceCapabilityKind.ACTION_TRIGGER -> {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.End
                                                ) {
                                                    FilledTonalButton(
                                                        onClick = {
                                                            dispatchCapabilityCommand(
                                                                context = context,
                                                                tile = displayTile.copy(capabilities = effectiveCapabilities),
                                                                capability = cap,
                                                                newValue = "TRIGGER",
                                                                bridge = bridge,
                                                                audioManager = audioManager,
                                                                scope = scope,
                                                                onTileUpdated = persistCapabilityStateOnly
                                                            )
                                                        },
                                                        shape = RoundedCornerShape(50),
                                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                                        modifier = Modifier.height(30.dp)
                                                    ) {
                                                        Text("Trigger ${cap.label}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                            DeviceCapabilityKind.READ_ONLY_SENSOR -> {}
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
}

@Composable
private fun SocketControlAndRenameCard(
    badgeLabel: String,
    socketName: String,
    isEditingName: Boolean,
    nameDraft: String,
    onNameDraftChange: (String) -> Unit,
    onStartEditName: () -> Unit,
    onSaveName: () -> Unit,
    onCancelEdit: () -> Unit,
    isOn: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (isOn) GhTileGreenActive else GhTileSurfaceElevated,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            width = 1.5.dp,
            color = if (isOn) GhAccentGreen.copy(alpha = 0.7f) else GhTileSurfaceElevated
        ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = badgeLabel,
                    color = if (isOn) GhAccentGreen else GhTextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )

                if (isEditingName) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = onNameDraftChange,
                            singleLine = true,
                            textStyle = TextStyle(
                                color = GhTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            ),
                            shape = RoundedCornerShape(10.dp),
                            colors = claraFieldColors(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onSaveName() }),
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalIconButton(
                            onClick = onSaveName,
                            modifier = Modifier.size(32.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = GhAccentGreen,
                                contentColor = GhBackground
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Save Socket Name",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(
                            onClick = onCancelEdit,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = GhTextSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = socketName,
                            color = GhTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Surface(
                            color = GhTileSurface.copy(alpha = 0.7f),
                            shape = CircleShape,
                            border = BorderStroke(1.dp, GhAccentBlue.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .size(26.dp)
                                .clickable { onStartEditName() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = "Edit Socket Name",
                                    tint = GhAccentBlue,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }

                Text(
                    text = if (isOn) "Status: Powered ON" else "Status: Powered OFF",
                    color = if (isOn) GhAccentGreen else GhTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Button(
                onClick = { onToggle(!isOn) },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isOn) GhAccentGreen else GhTileSurface,
                    contentColor = if (isOn) GhBackground else GhTextPrimary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.height(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isOn) "ON" else "OFF",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
internal fun TelemetryMetricCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = GhTileSurfaceElevated,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(title, color = GhTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            Text(value, color = GhTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(subtitle, color = accentColor.copy(alpha = 0.9f), fontSize = 11.sp, maxLines = 1)
        }
    }
}
