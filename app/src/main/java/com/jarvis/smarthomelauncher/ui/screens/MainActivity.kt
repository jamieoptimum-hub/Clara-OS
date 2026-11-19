package com.jarvis.smarthomelauncher.ui.screens

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*

import com.jarvis.smarthomelauncher.ui.widgets.*

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.*
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    companion object {
        private const val TOUCH_STATE_THROTTLE_MS = 5_000L
    }

    private var presenceWakeManager: PresenceWakeManager? = null

    @Volatile
    private var rawLastInteractionTimeMs: Long = System.currentTimeMillis()
    private val lastInteractionTimeMs = mutableLongStateOf(System.currentTimeMillis())
    private val isScreenSleeping = mutableStateOf(false)
    private val isKeepAwakeModalActive = mutableStateOf(false)

    private val keepScreenAwakeState = mutableStateOf(false)
    private val sleepTimeoutSecondsState = mutableIntStateOf(90)
    private val enablePresenceWakeState = mutableStateOf(true)
    private val enableVoiceAnnouncementsState = mutableStateOf(true)
    private val dpiScaleFactorState = mutableFloatStateOf(1.0f)

    private fun startConnectivityServiceIfPermitted() {
        val canStartConnectedDeviceFgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (canStartConnectedDeviceFgs) {
            runCatching {
                val serviceIntent = Intent(this, com.jarvis.smarthomelauncher.services.SmartHomeConnectivityService::class.java)
                ContextCompat.startForegroundService(this, serviceIntent)
            }
        }
    }

    private val startupPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        hideSystemBars()
        startConnectivityServiceIfPermitted()
        if (isScreenSleeping.value && enablePresenceWakeState.value) {
            presenceWakeManager?.startMonitoring()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        DefaultWidgetsRegistrar.initialize()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        hideSystemBars()
        installPersistentImmersiveBarShield()

        val initialBridge = DeviceRepository.loadBridge(this)
        keepScreenAwakeState.value = initialBridge.keepScreenAwake
        sleepTimeoutSecondsState.intValue = initialBridge.sleepTimeoutSeconds
        enablePresenceWakeState.value = initialBridge.enablePresenceWake
        enableVoiceAnnouncementsState.value = initialBridge.enableVoiceAnnouncements
        dpiScaleFactorState.floatValue = initialBridge.effectiveDpiScaleFactor

        ClaraInAppMusicPlayer.initContextIfNeeded(this)
        if (NativeMusicCatalogClient.isSpotifySignedIn(this)) {
            SpotifyHiddenWebPlayer.ensureInitialized(this)
        }

        // Start the background connectivity service once Android 14+ connectedDevice permissions are granted
        startConnectivityServiceIfPermitted()

        presenceWakeManager = PresenceWakeManager(this) {
            runOnUiThread {
                wakeScreenUp()
            }
        }

        // Request BLUETOOTH_SCAN, BLUETOOTH_CONNECT, Location, Nearby Wi-Fi & Audio permissions on startup
        try {
            val neededPermissions = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                neededPermissions.add(Manifest.permission.RECORD_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                neededPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                neededPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    neededPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    neededPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    neededPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    neededPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
                }
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    neededPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            if (neededPermissions.isNotEmpty()) {
                startupPermissionsLauncher.launch(neededPermissions.toTypedArray())
            }
        } catch (_: Exception) {
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Keep user inside Home Launcher
            }
        })

        setContent {
            val sleeping by isScreenSleeping
            val keepAwakeModal by isKeepAwakeModalActive
            val keepScreenAwake by keepScreenAwakeState
            val sleepTimeoutSec by sleepTimeoutSecondsState
            val dpiScaleFactor by dpiScaleFactorState
            val isDarkTheme = ClaraThemeManager.isDarkMode
            val activeAccentPreset = ClaraThemeManager.accentPreset
            val activeCustomAccentArgb = ClaraThemeManager.customAccentColorArgb

            LaunchedEffect(sleeping, keepAwakeModal, keepScreenAwake, sleepTimeoutSec) {
                if (keepScreenAwake && sleeping) {
                    wakeScreenUp()
                }
                while (!sleeping) {
                    delay(1000L)
                    val now = System.currentTimeMillis()
                    if (keepAwakeModal || keepScreenAwake || sleepTimeoutSec <= 0) {
                        rawLastInteractionTimeMs = now
                    } else {
                        val timeoutMs = sleepTimeoutSec * 1000L
                        val elapsed = now - rawLastInteractionTimeMs
                        if (elapsed >= timeoutMs) {
                            enterSleepMode()
                        } else if (now - lastInteractionTimeMs.longValue >= TOUCH_STATE_THROTTLE_MS) {
                            lastInteractionTimeMs.longValue = rawLastInteractionTimeMs
                        }
                    }
                }
            }

            val colorScheme = remember(isDarkTheme, activeAccentPreset, activeCustomAccentArgb) {
                if (isDarkTheme) {
                    darkColorScheme(
                        background = GhBackground,
                        surface = GhTileSurface,
                        primary = GhAccentGreen,
                        secondary = GhAccentWarm
                    )
                } else {
                    lightColorScheme(
                        background = GhBackground,
                        surface = GhTileSurface,
                        primary = GhAccentGreen,
                        secondary = GhAccentWarm
                    )
                }
            }

            val scaledViewportDensity = rememberMediumTabletViewportDensity(
                dpiScaleFactor = dpiScaleFactor
            )

            CompositionLocalProvider(LocalDensity provides scaledViewportDensity) {
                MaterialTheme(colorScheme = colorScheme) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial)
                                        val now = System.currentTimeMillis()
                                        rawLastInteractionTimeMs = now
                                        if (isScreenSleeping.value) {
                                            wakeScreenUp()
                                        } else if (now - lastInteractionTimeMs.longValue >= TOUCH_STATE_THROTTLE_MS) {
                                            lastInteractionTimeMs.longValue = now
                                        }
                                    }
                                }
                            }
                    ) {
                        GoogleHomeLauncherScreen(
                            isScreenSleeping = sleeping,
                            lastTouchTimeMs = lastInteractionTimeMs.longValue,
                            onUserWakeSignal = { wakeScreenUp() },
                            onKeepAwakeModalChanged = { active -> isKeepAwakeModalActive.value = active },
                            onAppBehaviorSettingsChanged = { updatedBridge ->
                                keepScreenAwakeState.value = updatedBridge.keepScreenAwake
                                sleepTimeoutSecondsState.intValue = updatedBridge.sleepTimeoutSeconds
                                enablePresenceWakeState.value = updatedBridge.enablePresenceWake
                                enableVoiceAnnouncementsState.value = updatedBridge.enableVoiceAnnouncements
                                dpiScaleFactorState.floatValue = updatedBridge.effectiveDpiScaleFactor
                                if (updatedBridge.keepScreenAwake || updatedBridge.sleepTimeoutSeconds <= 0) {
                                    wakeScreenUp()
                                }
                                if (!updatedBridge.enablePresenceWake) {
                                    presenceWakeManager?.stopMonitoring()
                                }
                            },
                            onSpeak = { message -> speakJarvis(message) }
                        )
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun installPersistentImmersiveBarShield() {
        val decor = window.decorView
        decor.setOnSystemUiVisibilityChangeListener { visibility ->
            if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                hideSystemBars()
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(decor) { view, insets ->
            if (insets.isVisible(WindowInsetsCompat.Type.statusBars()) ||
                insets.isVisible(WindowInsetsCompat.Type.navigationBars())
            ) {
                hideSystemBars()
            }
            ViewCompat.onApplyWindowInsets(view, insets)
        }
    }

    private fun enterSleepMode() {
        if (isScreenSleeping.value || keepScreenAwakeState.value) return
        isScreenSleeping.value = true
        val params = window.attributes
        params.screenBrightness = 0.0f
        window.attributes = params
        if (enablePresenceWakeState.value) {
            presenceWakeManager?.startMonitoring()
        }
    }

    private fun wakeScreenUp() {
        val now = System.currentTimeMillis()
        rawLastInteractionTimeMs = now
        lastInteractionTimeMs.longValue = now
        if (!isScreenSleeping.value) return
        isScreenSleeping.value = false
        presenceWakeManager?.stopMonitoring()
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        wakeScreenUp()
        hideSystemBars()
        if (NativeMusicCatalogClient.isSpotifySignedIn(this)) {
            SpotifyHiddenWebPlayer.installChromiumAudioFocusShield(this)
            SpotifyHiddenWebPlayer.ensureInitialized(this)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            wakeScreenUp()
            hideSystemBars()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
        } catch (_: Exception) {
        }
    }

    private fun speakJarvis(text: String) {
        if (!enableVoiceAnnouncementsState.value) return
        ClaraVoiceAssistantManager.speak(this, text)
    }

    override fun onDestroy() {
        presenceWakeManager?.stopMonitoring()
        super.onDestroy()
    }
}

internal fun iconForNavDestination(dest: DashboardNavDestination): ImageVector = when (dest) {
    DashboardNavDestination.HOME -> Icons.Outlined.GridView
    DashboardNavDestination.CAMERAS -> Icons.Outlined.Videocam
    DashboardNavDestination.PLAYER -> Icons.Outlined.MusicNote
    DashboardNavDestination.ROUTINES -> Icons.Outlined.LocationOn
    DashboardNavDestination.ELECTRICITY -> Icons.Outlined.BarChart
    DashboardNavDestination.SETTINGS -> Icons.Outlined.Settings
}

// ---------------------------------------------------------------------------
// MAIN SF-HOUSE SMART HOME OS LAUNCHER SCREEN
// - Unified Full-Screen Grid Engine with Automatic Collision Commit
// - Per-User Parental Lock & Edit-Mode Shield
// - Closed-Loop Command Verification & 30-Minute Manual Hold Stamping
// - Interactive User Profile Selector on Photo Room Selector Card
// - Live Radio Card & Cast Routing
// ---------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GoogleHomeLauncherScreen(
    isScreenSleeping: Boolean,
    @Suppress("UNUSED_PARAMETER") lastTouchTimeMs: Long = System.currentTimeMillis(),
    onUserWakeSignal: () -> Unit,
    onKeepAwakeModalChanged: (Boolean) -> Unit,
    onAppBehaviorSettingsChanged: (BridgeSettings) -> Unit = {},
    onSpeak: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    
    val activity = LocalContext.current as androidx.activity.ComponentActivity
    val viewModel = remember(activity) {
        androidx.lifecycle.ViewModelProvider(activity)[com.jarvis.smarthomelauncher.ui.viewmodels.DashboardViewModel::class.java]
    }

    var bridgeSettings by viewModel::bridgeSettings
    val tiles = viewModel.tiles
    val automationRules = viewModel.automationRules
    val uiState by viewModel.uiState.collectAsState()

    // Unified Grid Cards State (Clock/Hero, Weather, Volume + any extra user-added cards)
    val unifiedCards = remember {
        mutableStateListOf<UnifiedCardState>().apply {
            addAll(loadUnifiedDashboardCards(context, bridgeSettings))
        }
    }
    var currentUnifiedGridColumns by remember { mutableIntStateOf(8) }

    val dashboardCardOrder = remember {
        mutableStateListOf<DashboardCardSlot>().apply {
            val loadedSlots = bridgeSettings.dashboardCardOrder.mapNotNull { DashboardCardSlot.fromKey(it) }
            addAll(loadedSlots)
            DashboardCardSlot.entries.forEach { slot ->
                if (slot !in this) add(slot)
            }
        }
    }

    val cardWidthWeights = remember {
        mutableStateMapOf<String, Float>().apply {
            DashboardCardSlot.entries.forEach { slot ->
                put(
                    slot.key,
                    (bridgeSettings.dashboardCardWidthWeights[slot.key] ?: slot.defaultWidthWeight)
                        .coerceIn(0.45f, 2.07f)
                )
            }
        }
    }

    val cardHeightWeights = remember {
        mutableStateMapOf<String, Float>().apply {
            DashboardCardSlot.entries.forEach { slot ->
                put(
                    slot.key,
                    (bridgeSettings.dashboardCardHeightWeights[slot.key] ?: slot.defaultHeightWeight)
                        .coerceIn(0.42f, 1.73f)
                )
            }
        }
    }

    val cardZoomScales = remember {
        mutableStateMapOf<String, Float>().apply {
            DashboardCardSlot.entries.forEach { slot ->
                put(
                    slot.key,
                    (bridgeSettings.dashboardCardZoomScales[slot.key] ?: 1.0f).coerceIn(0.70f, 1.40f)
                )
            }
        }
    }

    val slotWidgetTypes = remember {
        mutableStateMapOf<String, DashboardWidgetType>().apply {
            DashboardCardSlot.entries.forEach { slot ->
                put(
                    slot.key,
                    bridgeSettings.dashboardSlotWidgetTypes[slot.key] ?: slot.defaultWidgetType
                )
            }
        }
    }

    // 25-Step Undo / Redo Layout History Manager
    val undoRedoManager = rememberLauncherUndoRedoManager(maxHistorySteps = 25)

    fun captureCurrentLayoutSnapshot(): LauncherLayoutSnapshot {
        return undoRedoManager.captureCurrentSnapshot(
            cardOrder = dashboardCardOrder,
            cardWidthWeights = cardWidthWeights,
            cardHeightWeights = cardHeightWeights,
            cardZoomScales = cardZoomScales,
            slotWidgetTypes = slotWidgetTypes,
            tiles = tiles,
            unifiedCards = unifiedCards
        )
    }

    fun recordLayoutUndoSnapshot() {
        undoRedoManager.pushSnapshot(captureCurrentLayoutSnapshot())
    }

    /**
     * Commits any card-to-tile collisions so tiles pushed out of the way by a resized
     * or moved card have their updated `(gridCol, gridRow)` persisted to `DeviceRepository`.
     */
    fun persistDashboardCardLayout() {
        commitUnifiedCardAndTileCollisions(
            context = context,
            cards = unifiedCards,
            tiles = tiles.toMutableList(),
            gridColumns = currentUnifiedGridColumns
        )
        saveUnifiedDashboardCards(context, unifiedCards)
        unifiedCards.filter { !it.isExtraCard }.forEach { c ->
            cardZoomScales[c.slot.key] = c.zoomScale
            slotWidgetTypes[c.slot.key] = c.widgetType
        }
        val updated = bridgeSettings.copy(
            dashboardCardOrder = dashboardCardOrder.map { it.key },
            dashboardCardWidthWeights = cardWidthWeights.toMap(),
            dashboardCardHeightWeights = cardHeightWeights.toMap(),
            dashboardCardZoomScales = cardZoomScales.toMap(),
            dashboardSlotWidgetTypes = slotWidgetTypes.toMap()
        )
        viewModel.updateBridgeSettings(context, updated)
    }

    fun applyLayoutSnapshot(snapshot: LauncherLayoutSnapshot) {
        if (snapshot.unifiedCards.isNotEmpty()) {
            unifiedCards.clear()
            unifiedCards.addAll(snapshot.unifiedCards)
            saveUnifiedDashboardCards(context, unifiedCards)
        }
        dashboardCardOrder.clear()
        dashboardCardOrder.addAll(snapshot.cardOrder)
        cardWidthWeights.clear()
        cardWidthWeights.putAll(snapshot.cardWidthWeights)
        cardHeightWeights.clear()
        cardHeightWeights.putAll(snapshot.cardHeightWeights)
        cardZoomScales.clear()
        cardZoomScales.putAll(snapshot.cardZoomScales)
        slotWidgetTypes.clear()
        slotWidgetTypes.putAll(snapshot.slotWidgetTypes)

        val tileSnapById = snapshot.tileCoordinates.associateBy { it.tileId }
        val updatedTiles = tiles.map { t ->
            val snap = tileSnapById[t.id]
            if (snap != null) {
                t.copy(
                    colSpan = snap.colSpan,
                    rowSpan = snap.rowSpan,
                    gridCol = snap.gridCol,
                    gridRow = snap.gridRow,
                    isTall = snap.isTall,
                    isVisible = snap.isVisible,
                    groupCardId = snap.groupCardId
                )
            } else {
                t
            }
        }
        viewModel.saveTiles(context, updatedTiles)
    }

    var selectedCardIdForMove by remember { mutableStateOf<String?>(null) }
    var draggedCardId by remember { mutableStateOf<String?>(null) }
    var cardDragOffset by remember { mutableStateOf(Offset.Zero) }

    // Quick Add Card / Tile & Entertainment Hub Modal State
    var showQuickAddModal by remember { mutableStateOf(false) }
    var quickAddGridCell by remember { mutableStateOf(3 to 2) }
    var showEntertainmentHubModal by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ClaraEntertainmentStore.ensureInitialized(context)
    }

    var activeNavDestination by remember { mutableStateOf(DashboardNavDestination.HOME) }
    val selectedRoomFilter = uiState.selectedRoomFilter
    var showAddRoomDialog by remember { mutableStateOf(false) }
    var newRoomNameDraft by remember { mutableStateOf("") }
    var pendingTileIdForNewRoom by remember { mutableStateOf<String?>(null) }
    var requestedHeroPage by remember { mutableStateOf<Int?>(null) }
    var detailModalTile by remember { mutableStateOf<SmartTile?>(null) }

    var activeHeroDialTileId by remember { mutableStateOf<String?>(null) }
    var activeHeroTransientTile by remember { mutableStateOf<SmartTile?>(null) }
    var heroDialRemainingSeconds by remember { mutableIntStateOf(0) }
    var heroDialInteractionNonce by remember { mutableIntStateOf(0) }

    var showSetupWizard by remember { mutableStateOf(false) }
    var showRemovalDialogForTile by remember { mutableStateOf<com.jarvis.smarthomelauncher.core.SmartTile?>(null) }
    var wizardInitialSection by remember { mutableStateOf(SettingsSidebarSection.DEVICE_CONFIG) }
    var lastWizardDismissTimeMs by remember { mutableLongStateOf(0L) }

    val isEditMode = uiState.isEditMode
    var selectedTileIdForMove by remember { mutableStateOf<String?>(null) }

    // Automatically exit Edit Mode if the active user switches to a Parental-Locked profile
    LaunchedEffect(bridgeSettings.activeUserProfileId, bridgeSettings.isParentalLockActive) {
        if (bridgeSettings.isParentalLockActive && isEditMode) {
            viewModel.setEditMode(false)
            selectedTileIdForMove = null
            selectedCardIdForMove = null
        }
    }

    // RBAC & Parental Lock PIN Verification Modal State
    var showPinPromptDialog by remember { mutableStateOf(false) }
    var pinPromptReason by remember { mutableStateOf("Enter Security PIN") }
    var enteredPinDraft by remember { mutableStateOf("") }
    var pinErrorText by remember { mutableStateOf("") }
    var pendingProtectedAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun requestProtectedAction(
        reason: String,
        requiresAdminAccess: Boolean = false,
        requiresEditLayout: Boolean = false,
        requiresVehicleOrLockAccess: Boolean = false,
        onAuthorized: () -> Unit
    ) {
        val activeProfile = bridgeSettings.activeUserProfile
        val isParentalLocked = bridgeSettings.isParentalLockActive
        val configuredPin = activeProfile.customPinCode.ifBlank { bridgeSettings.rolePinCode }

        val needsPinShield = isParentalLocked ||
                (configuredPin.isNotBlank() && (
                        bridgeSettings.requirePinForAdminAndVehicles ||
                                (requiresAdminAccess && !activeProfile.canAccessSettings) ||
                                (requiresEditLayout && !activeProfile.canEditLayout) ||
                                (requiresVehicleOrLockAccess && !activeProfile.canControlVehiclesAndLocks)
                        ))

        if (needsPinShield) {
            pinPromptReason = reason
            enteredPinDraft = ""
            pinErrorText = ""
            pendingProtectedAction = onAuthorized
            showPinPromptDialog = true
        } else {
            onAuthorized()
        }
    }

    fun toggleOrActivateHeroTile(targetTile: SmartTile) {
        val isTransientHeroCard = targetTile.isVolumeHeroRequested ||
                targetTile.isRadioHeroRequested ||
                targetTile.isFootballHeroRequested ||
                targetTile.isCalendarHeroRequested ||
                targetTile.isGuestWifiHeroRequested
        when {
            targetTile.isTvTrackerHeroRequested -> {
                activeHeroDialTileId = null
                activeHeroTransientTile = null
                showEntertainmentHubModal = true
            }
            isTransientHeroCard -> {
                if (activeHeroDialTileId == targetTile.id &&
                    activeHeroTransientTile?.deviceSubtype == targetTile.deviceSubtype &&
                    activeHeroTransientTile?.isVolumeHeroRequested == targetTile.isVolumeHeroRequested
                ) {
                    activeHeroDialTileId = null
                    activeHeroTransientTile = null
                } else {
                    activeHeroTransientTile = targetTile
                    activeHeroDialTileId = targetTile.id
                    heroDialInteractionNonce++
                }
            }
            targetTile.type == TileType.MACRO_TILE -> {
                val ruleIdx = automationRules.indexOfFirst { r ->
                    r.id == targetTile.entityId ||
                            r.id == targetTile.serialNumber ||
                            r.name.equals(targetTile.title, ignoreCase = true)
                }
                if (ruleIdx in automationRules.indices) {
                    val rule = automationRules[ruleIdx]
                    val updatedTiles = tiles.map { if (it.id == targetTile.id) it.copy(isActive = true) else it }
                    viewModel.saveTiles(context, updatedTiles)
                    AutomationEngine.executeRuleActions(
                        rule = rule,
                        tiles = updatedTiles.toMutableList(),
                        bridge = bridgeSettings,
                        audioManager = audioManager,
                        scope = scope,
                        context = context,
                        onSpeak = onSpeak,
                        triggerSource = "Dashboard Macro Tile"
                    )
                    val updatedRules = automationRules.mapIndexed { idx, r ->
                        if (idx == ruleIdx) r.copy(lastTriggeredEpochMs = System.currentTimeMillis()) else r
                    }
                    viewModel.saveAutomations(context, updatedRules)
                    onSpeak("Running ${rule.name}")
                    scope.launch {
                        delay(1500L)
                        val resetTiles = tiles.map { if (it.id == targetTile.id) it.copy(isActive = false) else it }
                        viewModel.saveTiles(context, resetTiles)
                    }
                } else {
                    onSpeak("Macro ${targetTile.title} not found.")
                }
            }
            targetTile.type == TileType.SECURITY_CENTER -> {
                requestProtectedAction(
                    reason = "Enter PIN to Change Security Mode",
                    requiresVehicleOrLockAccess = true
                ) {
                    val modes = SecurityCenterMode.entries
                    val nextIdx = (modes.indexOf(bridgeSettings.securityCenterMode) + 1) % modes.size
                    val nextMode = modes[nextIdx]
                    val updatedBridge = AutomationEngine.applySecurityCenterMode(
                        mode = nextMode,
                        context = context,
                        tiles = tiles.toMutableList(),
                        bridge = bridgeSettings,
                        audioManager = audioManager,
                        scope = scope,
                        onSpeak = onSpeak
                    )
                    viewModel.updateBridgeSettings(context, updatedBridge)
                }
            }
            targetTile.type == TileType.CLOCK_WEATHER -> {
                activeHeroDialTileId = null
                activeHeroTransientTile = null
            }
            targetTile.type == TileType.ENERGY_MONITOR -> {
                activeHeroDialTileId = null
                activeHeroTransientTile = null
                activeNavDestination = DashboardNavDestination.ELECTRICITY
            }
            targetTile.type == TileType.JARVIS_SCENES -> {
                activeHeroDialTileId = null
                activeHeroTransientTile = null
                activeNavDestination = DashboardNavDestination.ROUTINES
            }
            targetTile.type == TileType.MUSIC_PLAYER -> {
                activeHeroDialTileId = null
                activeHeroTransientTile = null
                requestedHeroPage = 0
            }
            activeHeroDialTileId == targetTile.id && activeHeroTransientTile == null -> {
                activeHeroDialTileId = null
            }
            else -> {
                activeHeroTransientTile = null
                activeHeroDialTileId = targetTile.id
                heroDialInteractionNonce++
            }
        }
    }

    LaunchedEffect(activeHeroDialTileId, heroDialInteractionNonce, bridgeSettings.heroCardTimeoutSeconds) {
        if (activeHeroDialTileId != null) {
            heroDialRemainingSeconds = bridgeSettings.heroCardTimeoutSeconds.coerceIn(5, 300)
            while (heroDialRemainingSeconds > 0 && activeHeroDialTileId != null) {
                delay(1000L)
                heroDialRemainingSeconds--
            }
            if (heroDialRemainingSeconds <= 0) {
                activeHeroDialTileId = null
                activeHeroTransientTile = null
            }
        }
    }

    val liveTrack by JarvisLiveBridge.activeTrackFlow.collectAsState()
    val liveIsPlaying by JarvisLiveBridge.isPlayingFlow.collectAsState()
    val isVoiceListening by ClaraVoiceAssistantManager.isAwakeForCommandFlow.collectAsState()
    var localIsPlaying by remember { mutableStateOf(false) }
    val isMusicActivelyPlaying = liveIsPlaying || localIsPlaying
    val weather by JarvisLiveBridge.weatherFlow.collectAsState()

    LaunchedEffect(bridgeSettings, weather.isDay) {
        DeviceRepository.applyThemeMode(
            mode = bridgeSettings.appThemeMode,
            accentPreset = bridgeSettings.accentColorPreset,
            customAccentColorArgb = bridgeSettings.customAccentColorArgb
        )
        onAppBehaviorSettingsChanged(bridgeSettings)
    }

    LaunchedEffect(bridgeSettings.musicProvider) {
        ClaraInAppMusicPlayer.initContextIfNeeded(context)
        if (NativeMusicCatalogClient.isSpotifySignedIn(context)) {
            SpotifyHiddenWebPlayer.ensureInitialized(context) {
                ClaraInAppMusicPlayer.skipToNext(audioManager, scope)
            }
        }
    }

    // Start Embedded Port 8089 Local Hub Server, Event Bus Subscriber & Environment Discovery
    LaunchedEffect(Unit) {
        DeviceRepository.loadCastGroups(context)
        JarvisLiveBridge.ensureLocalHubServerRunning(context) { action, payload ->
            scope.launch {
                val spokenCmd = payload.optString("command", action)
                if (spokenCmd.isNotBlank()) {
                    AutomationEngine.processSpokenVoiceCommand(
                        rawSpokenText = spokenCmd,
                        context = context,
                        rules = automationRules.toMutableList(),
                        tiles = tiles.toMutableList(),
                        bridge = bridgeSettings,
                        audioManager = audioManager,
                        scope = scope,
                        onSpeak = onSpeak
                    )
                }
            }
        }
        AutomationEngine.ensureEventBusSubscriberRunning(
            context = context,
            rules = automationRules,
            tiles = tiles,
            bridgeProvider = { bridgeSettings },
            audioManager = audioManager,
            scope = scope,
            onSpeak = onSpeak
        )
        runAutomaticEnvironmentDiscoveryAndSelfHealing(
            context = context,
            bridgeSettings = bridgeSettings,
            tiles = tiles,
            scope = scope,
            includeFullLanScan = false
        )
    }

    val voiceAssistantManager = remember {
        ClaraVoiceAssistantManager(
            context = context,
            onCommandRecognized = { recognizedCommand ->
                onUserWakeSignal()
                val lowerCmd = recognizedCommand.lowercase(Locale.getDefault())
                if (lowerCmd.contains("control center") || lowerCmd.contains("settings")) {
                    requestProtectedAction(
                        reason = "Parental Lock: Enter PIN to Open Control Center",
                        requiresAdminAccess = true
                    ) {
                        onSpeak("Opening Control Center.")
                        wizardInitialSection = SettingsSidebarSection.DEVICE_CONFIG
                        showSetupWizard = true
                    }
                } else if (lowerCmd.contains("rearrange") || lowerCmd.contains("edit layout") || lowerCmd.contains("customize layout")) {
                    requestProtectedAction(
                        reason = "Parental Lock: Enter PIN to Edit Dashboard Layout",
                        requiresEditLayout = true
                    ) {
                        onSpeak("Layout customization enabled.")
                        selectedCardIdForMove = null
                        selectedTileIdForMove = null
                        viewModel.setEditMode(true)
                    }
                } else {
                    AutomationEngine.processSpokenVoiceCommand(
                        rawSpokenText = recognizedCommand,
                        context = context,
                        rules = automationRules,
                        tiles = tiles,
                        bridge = bridgeSettings,
                        audioManager = audioManager,
                        scope = scope,
                        onSpeak = onSpeak
                    )
                }
            },
            onWakeWordDetected = {
                onUserWakeSignal()
            }
        )
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceAssistantManager.triggerImmediateVoicePrompt()
        }
    }

    fun triggerVoiceAssistantPrompt(ignoreWizardDismissGuard: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!ignoreWizardDismissGuard && (showSetupWizard || (now - lastWizardDismissTimeMs) < 500L)) {
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            voiceAssistantManager.triggerImmediateVoicePrompt()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(
        bridgeSettings.enableVoiceControl,
        bridgeSettings.enableWakeWordListening,
        bridgeSettings.voiceWakeWord,
        showSetupWizard,
        isScreenSleeping
    ) {
        if (showSetupWizard ||
            isScreenSleeping ||
            !bridgeSettings.enableVoiceControl ||
            !bridgeSettings.enableWakeWordListening
        ) {
            voiceAssistantManager.configure(
                enableContinuousWakeWord = false,
                wakeWord = bridgeSettings.voiceWakeWord
            )
        } else {
            delay(500L)
            voiceAssistantManager.configure(
                enableContinuousWakeWord = true,
                wakeWord = bridgeSettings.voiceWakeWord
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceAssistantManager.stopListening()
        }
    }

    var draggedTileId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var hoverGridCol by remember { mutableIntStateOf(0) }
    var hoverGridRow by remember { mutableIntStateOf(0) }

    var ringingDoorbellTile by remember { mutableStateOf<SmartTile?>(null) }
    var ringingPendingIntent by remember { mutableStateOf<PendingIntent?>(null) }
    var expandedFeedTile by remember { mutableStateOf<SmartTile?>(null) }

    val totalPages = if (bridgeSettings.enableSwipeableSlideshowPage) 2 else 1
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { totalPages })

    LaunchedEffect(bridgeSettings.enableSwipeableSlideshowPage) {
        if (!bridgeSettings.enableSwipeableSlideshowPage && pagerState.currentPage != 0) {
            pagerState.scrollToPage(0)
        }
    }

    val activeHeroDialTile = remember(activeHeroDialTileId, activeHeroTransientTile, tiles.toList()) {
        val id = activeHeroDialTileId ?: return@remember null
        val fromTiles = tiles.firstOrNull { it.id == id }
        val transient = activeHeroTransientTile?.takeIf { it.id == id }
        when {
            fromTiles != null && transient != null && (
                    transient.isVolumeHeroRequested ||
                            transient.isRadioHeroRequested ||
                            transient.isTvTrackerHeroRequested ||
                            transient.isFootballHeroRequested ||
                            transient.isCalendarHeroRequested ||
                            transient.isGuestWifiHeroRequested
                    ) ->
                fromTiles.copy(
                    volume = transient.volume,
                    forceVolumeHeroMode = transient.forceVolumeHeroMode,
                    deviceClassOverride = transient.deviceClassOverride ?: fromTiles.deviceClassOverride,
                    deviceSubtypeOverride = transient.deviceSubtypeOverride ?: fromTiles.deviceSubtypeOverride,
                    serviceClassOverride = transient.serviceClassOverride ?: fromTiles.serviceClassOverride
                )
            fromTiles != null -> fromTiles
            else -> transient
        }
    }

    val anyInlineLiveStreamActive = tiles.any { it.isLiveFeedActive } ||
            activeHeroDialTile?.isCameraTile == true ||
            activeNavDestination == DashboardNavDestination.CAMERAS
    val isModalOpen = ringingDoorbellTile != null || expandedFeedTile != null || showSetupWizard || detailModalTile != null || showQuickAddModal || showEntertainmentHubModal
    val shouldKeepScreenAwake = isModalOpen || anyInlineLiveStreamActive || isEditMode

    LaunchedEffect(shouldKeepScreenAwake) {
        onKeepAwakeModalChanged(shouldKeepScreenAwake)
    }

    LaunchedEffect(tiles.toList(), activeHeroDialTileId, expandedFeedTile, ringingDoorbellTile, showSetupWizard, activeNavDestination) {
        if (showSetupWizard) {
            CameraStreamSessionManager.muteAll()
            CameraStreamSessionManager.releaseAllExcept(emptySet())
        } else {
            if (expandedFeedTile == null && ringingDoorbellTile == null) {
                CameraStreamSessionManager.muteAll()
            }
            val activeIds = buildSet {
                tiles.filter { it.isLiveFeedActive }.forEach { add(it.id) }
                if (activeHeroDialTile?.isCameraTile == true) {
                    add(activeHeroDialTile.id)
                }
                if (activeNavDestination == DashboardNavDestination.CAMERAS) {
                    tiles.filter { it.isCameraTile }.forEach { add(it.id) }
                }
                expandedFeedTile?.let { add(it.id) }
                ringingDoorbellTile?.let { add(it.id) }
            }
            CameraStreamSessionManager.releaseAllExcept(activeIds)
        }
    }

    val isOverlayModalOpen = isModalOpen

    val doorbellEvent by JarvisLiveBridge.doorbellRingFlow.collectAsState()
    LaunchedEffect(doorbellEvent) {
        val evt = doorbellEvent ?: return@LaunchedEffect
        onUserWakeSignal()
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            AutomationEngine.evaluateAllRules(
                context = context,
                rules = automationRules,
                tiles = tiles,
                bridge = bridgeSettings,
                audioManager = audioManager,
                scope = scope,
                onSpeak = onSpeak
            )
        }
        if (bridgeSettings.enableNestMiniDoorbellChime) {
            GoogleCastLanClient.playDoorbellChimeOnNestMinis(
                scope = scope,
                bridge = bridgeSettings,
                tiles = tiles,
                customAnnouncementText = bridgeSettings.doorbellChimeMessage.ifBlank {
                    "${evt.title}. ${evt.message}"
                }
            )
        }
        val targetType = if (evt.sourcePackage.contains("ezviz", ignoreCase = true) ||
            evt.sourcePackage.contains("videogo", ignoreCase = true)
        ) {
            TileType.EZVIZ_DOORBELL
        } else {
            TileType.NEST_DOORBELL
        }
        val matchedTile = tiles.firstOrNull { it.type == targetType }
            ?: tiles.firstOrNull { it.deviceSubtype == DeviceSubtype.DOORBELL }
            ?: tiles.firstOrNull { it.isCameraTile }

        if (matchedTile != null && matchedTile.isCameraTile) {
            scope.launch {
                SmartHomeApiClient.captureDvrEventSnapshot(
                    context = context,
                    tile = matchedTile,
                    bridge = bridgeSettings,
                    aiEventType = CameraAiEventType.DOORBELL_RING,
                    summaryText = "${evt.title}: ${evt.message}"
                )
            }
        }

        ringingPendingIntent = evt.contentIntent
        ringingDoorbellTile = matchedTile
        onSpeak("${evt.title}. ${evt.message}")
        JarvisLiveBridge.clearDoorbellEvent()
    }

    val handleUpdateAnyTile: (SmartTile) -> Unit = { updated ->
        if (activeHeroTransientTile?.id == updated.id) {
            activeHeroTransientTile = updated
        }
        val idx = tiles.indexOfFirst { it.id == updated.id }
        if (idx in tiles.indices) {
            val previous = tiles[idx]
            val cleanSensorUnit = if (updated.sensorUnit == "VOLUME_HERO" || updated.sensorUnit == "RADIO_HERO") {
                previous.sensorUnit
            } else {
                updated.sensorUnit
            }
            val isSystemWidgetTile = updated.type in setOf(
                TileType.CLOCK_WEATHER,
                TileType.GROUP_CARD,
                TileType.MACRO_TILE,
                TileType.PHOTO_SLIDESHOW,
                TileType.ENERGY_MONITOR,
                TileType.JARVIS_SCENES,
                TileType.SECURITY_CENTER,
                TileType.MUSIC_PLAYER
            )
            val isTransientVolumeUpdate = updated.forceVolumeHeroMode && !previous.forceVolumeHeroMode
            val nowActuationMs = System.currentTimeMillis()
            val manualActorName = "Manual User (${bridgeSettings.activeUserProfile.name})"
            val tileToPersist = DeviceRepository.stampOutboundIntentAuthority(
                tile = updated.copy(
                    sensorUnit = cleanSensorUnit,
                    forceVolumeHeroMode = false,
                    deviceClassOverride = if (isTransientVolumeUpdate) previous.deviceClassOverride else updated.deviceClassOverride,
                    deviceSubtypeOverride = if (isTransientVolumeUpdate) previous.deviceSubtypeOverride else updated.deviceSubtypeOverride,
                    transactionState = if (isSystemWidgetTile) CommandTransactionState.IDLE else CommandTransactionState.PENDING_ACK,
                    lastUserActuationEpochMs = nowActuationMs
                ),
                intentTier = IntentAuthorityTier.MANUAL_USER,
                actorName = manualActorName,
                nowMs = nowActuationMs
            )

            // Block unlocking vehicles or smart locks under Parental Lock without PIN
            val isUnlockingVehicleOrDoor = (tileToPersist.isVehicleTile && previous.vehicleLocked && !tileToPersist.vehicleLocked) ||
                    ((tileToPersist.deviceSubtype == DeviceSubtype.LOCK || tileToPersist.hasLockCapability) &&
                            !tileToPersist.isVehicleTile && previous.isActive && !tileToPersist.isActive)

            val commitUpdate: (Boolean) -> Unit = { pinAuthorizedFlag ->
                // F-04 & F-21: Immediately lock PENDING_ACK (15s); 30m Manual Hold is stamped on tileToPersist.manualHoldUntilEpochMs
                if (!isSystemWidgetTile) {
                    DeviceRepository.markTilePendingAck(tileToPersist.id)
                }
                tiles[idx] = tileToPersist
                val sharedHwId = tileToPersist.serialNumber.ifBlank { tileToPersist.entityId }.trim()
                if (sharedHwId.isNotBlank() && tileToPersist.isMultiGangSwitch) {
                    for (sIdx in tiles.indices) {
                        if (sIdx != idx) {
                            val sib = tiles[sIdx]
                            val sibHwId = sib.serialNumber.ifBlank { sib.entityId }.trim()
                            if (sibHwId.equals(sharedHwId, ignoreCase = true)) {
                                tiles[sIdx] = sib.copy(
                                    isActive = tileToPersist.isActive,
                                    isSocket2Active = tileToPersist.isSocket2Active,
                                    socket1Name = tileToPersist.socket1Name,
                                    socket2Name = tileToPersist.socket2Name,
                                    powerWatts = tileToPersist.powerWatts,
                                    voltageVolts = tileToPersist.voltageVolts,
                                    currentMilliamps = tileToPersist.currentMilliamps,
                                    totalEnergyKwh = tileToPersist.totalEnergyKwh,
                                    capabilities = tileToPersist.capabilities,
                                    lastUserActuationEpochMs = nowActuationMs
                                )
                            }
                        }
                    }
                }
                viewModel.saveTiles(context, tiles)

                if (tileToPersist.type == TileType.MUSIC_PLAYER) {
                    ClaraInAppMusicPlayer.setVolumePercent(tileToPersist.volume)
                }

                if (!isSystemWidgetTile && !tileToPersist.isGroupCardTile && !tileToPersist.isMacroTile) {
                    val devId = tileToPersist.serialNumber.ifBlank { tileToPersist.entityId }.trim()
                    val applyVerifiedOutcome: (SmartTile, Boolean, String) -> Unit = { finalTile, ok, diagMsg ->
                        val curIdx = tiles.indexOfFirst { it.id == finalTile.id }
                        if (curIdx in tiles.indices) {
                            tiles[curIdx] = if (ok) {
                                DeviceRepository.confirmTileHardwareAck(finalTile.id)
                                finalTile
                            } else {
                                val hasQueued = JarvisLiveBridge.hasQueuedIntentsForDevice(previous.id)
                                val consecutiveFails = TuyaApiClient.consecutiveFailuresByDevice[devId] ?: 0
                                val isAuthFailure = diagMsg.contains("NEEDS_REAUTH", ignoreCase = true) ||
                                        diagMsg.contains("401") ||
                                        diagMsg.contains("403") ||
                                        diagMsg.contains("expired", ignoreCase = true)
                                val keepOnline = !isAuthFailure && previous.isOnline && consecutiveFails < 3
                                previous.copy(
                                    isOnline = keepOnline,
                                    lifecycleState = when {
                                        isAuthFailure -> DeviceLifecycleState.QUARANTINED_ERROR
                                        keepOnline -> DeviceLifecycleState.RECOVERY
                                        else -> DeviceLifecycleState.QUARANTINED_ERROR
                                    },
                                    transactionState = if (hasQueued) {
                                        CommandTransactionState.SHADOW_QUEUED
                                    } else {
                                        CommandTransactionState.FAILED_ROLLED_BACK
                                    }
                                )
                            }
                            viewModel.saveTiles(context, tiles)
                        }
                    }

                    if (devId.isNotBlank() && !devId.contains(".") && tileToPersist.ipAddress.isNotBlank()) {
                        TuyaApiClient.registerDeviceLanAddress(
                            deviceId = devId,
                            ipAddress = tileToPersist.ipAddress,
                            localKey = tileToPersist.secretCode
                        )
                    }
                    // F-13: Detect single-socket changes across dynamic N-gang capabilities (socket_1..socket_N)
                    val changedDynamicSockets = (1..maxOf(tileToPersist.socketCount(), 2)).filter { outletIdx ->
                        previous.isSocketActive(outletIdx) != tileToPersist.isSocketActive(outletIdx)
                    }
                    val targetSocketNumber: Int? = if (changedDynamicSockets.size == 1 && tileToPersist.isMultiGangSwitch) {
                        changedDynamicSockets.first()
                    } else null

                    dispatchDeviceUpdate(
                        updated = tileToPersist,
                        bridge = bridgeSettings,
                        audioManager = audioManager,
                        scope = scope,
                        singleSocketNumber = targetSocketNumber,
                        pinAuthorized = pinAuthorizedFlag,
                        intentTier = IntentAuthorityTier.MANUAL_USER,
                        actorName = manualActorName,
                        onTransactionResult = applyVerifiedOutcome
                    )
                }

                scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    AutomationEngine.evaluateAllRules(
                        context = context,
                        rules = automationRules,
                        tiles = tiles,
                        bridge = bridgeSettings,
                        audioManager = audioManager,
                        scope = scope,
                        onSpeak = onSpeak
                    )
                }
            }

            if (isUnlockingVehicleOrDoor) {
                requestProtectedAction(
                    reason = "Parental Lock: Enter PIN to Unlock ${tileToPersist.title}",
                    requiresVehicleOrLockAccess = true
                ) {
                    commitUpdate(true)
                }
            } else {
                commitUpdate(false)
            }
        }
    }

    val orderedFirstGroup = remember(bridgeSettings.invertNavOrder) {
        val base = listOf(
            DashboardNavDestination.HOME,
            DashboardNavDestination.CAMERAS,
            DashboardNavDestination.PLAYER
        )
        if (bridgeSettings.invertNavOrder) {
            listOf(
                DashboardNavDestination.SETTINGS,
                DashboardNavDestination.ELECTRICITY,
                DashboardNavDestination.ROUTINES
            )
        } else base
    }

    val orderedSecondGroup = remember(bridgeSettings.invertNavOrder) {
        val base = listOf(
            DashboardNavDestination.ROUTINES,
            DashboardNavDestination.ELECTRICITY,
            DashboardNavDestination.SETTINGS
        )
        if (bridgeSettings.invertNavOrder) {
            listOf(
                DashboardNavDestination.PLAYER,
                DashboardNavDestination.CAMERAS,
                DashboardNavDestination.HOME
            )
        } else base
    }

    fun handleSelectNavDestination(dest: DashboardNavDestination) {
        if (dest == DashboardNavDestination.SETTINGS) {
            requestProtectedAction(
                reason = "Parental Lock: Enter PIN to Open Control Center",
                requiresAdminAccess = true
            ) {
                voiceAssistantManager.stopListening()
                expandedFeedTile = null
                CameraStreamSessionManager.muteAll()
                wizardInitialSection = SettingsSidebarSection.DEVICE_CONFIG
                showSetupWizard = true
            }
        } else {
            activeNavDestination = dest
        }
    }

    fun handleToggleEditModeFromNavBar() {
        if (!isEditMode) {
            requestProtectedAction(
                reason = "Parental Lock: Enter PIN to Edit Dashboard Layout",
                requiresEditLayout = true
            ) {
                activeNavDestination = DashboardNavDestination.HOME
                selectedCardIdForMove = null
                selectedTileIdForMove = null
                viewModel.setEditMode(true)
            }
        } else {
            viewModel.setEditMode(false)
            selectedTileIdForMove = null
            draggedTileId = null
            dragOffset = Offset.Zero
            selectedCardIdForMove = null
            draggedCardId = null
            cardDragOffset = Offset.Zero
            persistDashboardCardLayout()
            DeviceRepository.saveTiles(context, tiles)
        }
    }

    @Composable
    fun RenderVerticalNavDock() {
        val isDark = ClaraThemeManager.isDarkMode
        Box(
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.1.dp, GhCardBorder),
                shadowElevation = 0.dp,
                modifier = Modifier
                    .width(46.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.verticalGradient(
                            colors = if (isDark) {
                                listOf(
                                    Color(0xFF303540),
                                    GhNavSurface,
                                    Color(0xFF1F2229)
                                )
                            } else {
                                listOf(
                                    Color(0xFFFFFFFF),
                                    Color(0xFFF4F7FB),
                                    Color(0xFFE9EEF5)
                                )
                            }
                        )
                    )
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    orderedFirstGroup.forEach { dest ->
                        val isSelected = activeNavDestination == dest
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) GhAccentGreen else Color.Transparent)
                                .clickable { handleSelectNavDestination(dest) }
                        ) {
                            Icon(
                                imageVector = iconForNavDestination(dest),
                                contentDescription = dest.label,
                                tint = if (isSelected) Color(0xFF141518) else GhTextSecondary,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (isVoiceListening) GhAccentGreen else GhTileSurfaceElevated)
                            .border(
                                width = 1.3.dp,
                                color = if (isVoiceListening) Color.White else GhAccentGreen.copy(alpha = 0.65f),
                                shape = CircleShape
                            )
                            .clickable {
                                triggerVoiceAssistantPrompt(ignoreWizardDismissGuard = true)
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Talk to Clara",
                            tint = if (isVoiceListening) Color(0xFF141518) else GhAccentGreen,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    orderedSecondGroup.forEach { dest ->
                        val isSelected = activeNavDestination == dest
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) GhAccentGreen else Color.Transparent)
                                .clickable { handleSelectNavDestination(dest) }
                        ) {
                            Icon(
                                imageVector = iconForNavDestination(dest),
                                contentDescription = dest.label,
                                tint = if (isSelected) Color(0xFF141518) else GhTextSecondary,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }

                    // Edit Dashboard Layout Button on Vertical Floating Nav Bar
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (isEditMode) Color(0xFF00E5FF) else GhTileSurfaceElevated.copy(alpha = 0.75f)
                            )
                            .border(
                                width = 1.1.dp,
                                color = if (isEditMode) Color.White else Color(0xFF00E5FF).copy(alpha = 0.55f),
                                shape = CircleShape
                            )
                            .clickable { handleToggleEditModeFromNavBar() }
                    ) {
                        Icon(
                            imageVector = if (isEditMode) Icons.Default.Check else Icons.Outlined.Edit,
                            contentDescription = if (isEditMode) "Done Editing Dashboard" else "Edit Dashboard Layout",
                            tint = if (isEditMode) Color(0xFF141518) else Color(0xFF00E5FF),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun RenderHorizontalNavDock() {
        Surface(
            color = GhNavSurface,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, GhCardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                orderedFirstGroup.forEach { dest ->
                    val isSelected = activeNavDestination == dest
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) GhAccentGreen else Color.Transparent)
                            .clickable { handleSelectNavDestination(dest) }
                    ) {
                        Icon(
                            imageVector = iconForNavDestination(dest),
                            contentDescription = dest.label,
                            tint = if (isSelected) Color(0xFF141518) else GhTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isVoiceListening) GhAccentGreen else GhTileSurfaceElevated)
                        .border(
                            width = 1.2.dp,
                            color = if (isVoiceListening) Color.White else GhAccentGreen.copy(alpha = 0.65f),
                            shape = CircleShape
                        )
                        .clickable {
                            triggerVoiceAssistantPrompt(ignoreWizardDismissGuard = true)
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Talk to Clara",
                        tint = if (isVoiceListening) Color(0xFF141518) else GhAccentGreen,
                        modifier = Modifier.size(21.dp)
                    )
                }

                orderedSecondGroup.forEach { dest ->
                    val isSelected = activeNavDestination == dest
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) GhAccentGreen else Color.Transparent)
                            .clickable { handleSelectNavDestination(dest) }
                    ) {
                        Icon(
                            imageVector = iconForNavDestination(dest),
                            contentDescription = dest.label,
                            tint = if (isSelected) Color(0xFF141518) else GhTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Edit Dashboard Layout Button on Horizontal Floating Nav Bar
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isEditMode) Color(0xFF00E5FF) else GhTileSurfaceElevated.copy(alpha = 0.75f)
                        )
                        .border(
                            width = 1.1.dp,
                            color = if (isEditMode) Color.White else Color(0xFF00E5FF).copy(alpha = 0.55f),
                            shape = CircleShape
                        )
                        .clickable { handleToggleEditModeFromNavBar() }
                ) {
                    Icon(
                        imageVector = if (isEditMode) Icons.Default.Check else Icons.Outlined.Edit,
                        contentDescription = if (isEditMode) "Done Editing Dashboard" else "Edit Dashboard Layout",
                        tint = if (isEditMode) Color(0xFF141518) else Color(0xFF00E5FF),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GhBackground)
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = bridgeSettings.enableSwipeableSlideshowPage && !isEditMode && !isOverlayModalOpen && activeNavDestination == DashboardNavDestination.HOME,
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            if (pageIndex == 1 && bridgeSettings.enableSwipeableSlideshowPage) {
                FullScreenPhotoSlideshowPage(
                    bridgeSettings = bridgeSettings,
                    tiles = tiles,
                    onUpdateBridgeSettings = { updatedBridge ->
                        bridgeSettings = updatedBridge
                        onAppBehaviorSettingsChanged(updatedBridge)
                        DeviceRepository.saveBridge(context, updatedBridge)
                    },
                    onReturnToDashboard = {
                        scope.launch {
                            pagerState.animateScrollToPage(0)
                        }
                    }
                )
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isWideTablet = maxWidth >= 600.dp
                    val navDock = if (isWideTablet) bridgeSettings.navDockPosition else NavDockPosition.BOTTOM

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                horizontal = if (isWideTablet) 16.dp else 10.dp,
                                vertical = if (isWideTablet) 12.dp else 8.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (navDock == NavDockPosition.TOP) {
                            RenderHorizontalNavDock()
                        }

                        // Contextual Edit-Mode Bar with "+ Add Card / Tile" and Undo / Redo History Controls
                        AnimatedVisibility(visible = isEditMode) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = if (navDock == NavDockPosition.LEFT) 60.dp else 0.dp,
                                        end = if (navDock == NavDockPosition.RIGHT) 60.dp else 0.dp
                                    ),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Edit Mode: Tap any card or tile to edit/resize, or tap any empty grid square to move or add a card/tile",
                                    color = GhAccentGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = GhFont,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            quickAddGridCell = 3 to 2
                                            showQuickAddModal = true
                                        },
                                        shape = RoundedCornerShape(9.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = Color(0xFF00E5FF).copy(alpha = 0.22f),
                                            contentColor = Color(0xFF00E5FF)
                                        ),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "Add Card or Tile",
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Add Card / Tile",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            val currentSnap = captureCurrentLayoutSnapshot()
                                            val previousSnap = undoRedoManager.undo(currentSnap)
                                            if (previousSnap != null) {
                                                applyLayoutSnapshot(previousSnap)
                                            }
                                        },
                                        enabled = undoRedoManager.canUndo,
                                        shape = RoundedCornerShape(9.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = GhTileSurfaceElevated,
                                            contentColor = GhAccentGreen
                                        ),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Undo,
                                            contentDescription = "Undo Layout Edit",
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Undo",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            val currentSnap = captureCurrentLayoutSnapshot()
                                            val nextSnap = undoRedoManager.redo(currentSnap)
                                            if (nextSnap != null) {
                                                applyLayoutSnapshot(nextSnap)
                                            }
                                        },
                                        enabled = undoRedoManager.canRedo,
                                        shape = RoundedCornerShape(9.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = GhTileSurfaceElevated,
                                            contentColor = GhAccentGreen
                                        ),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Redo,
                                            contentDescription = "Redo Layout Edit",
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Redo",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    TextButton(
                                        onClick = {
                                            recordLayoutUndoSnapshot()
                                            unifiedCards.clear()
                                            unifiedCards.addAll(defaultUnifiedDashboardCards(BridgeSettings()))
                                            selectedCardIdForMove = null
                                            draggedCardId = null
                                            cardDragOffset = Offset.Zero
                                            persistDashboardCardLayout()
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Reset Cards", color = GhAccentGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }

                                    TextButton(
                                        onClick = {
                                            recordLayoutUndoSnapshot()
                                            for (i in tiles.indices) {
                                                tiles[i] = tiles[i].copy(
                                                    gridCol = -1,
                                                    gridRow = -1,
                                                    colSpan = 1,
                                                    rowSpan = 1,
                                                    isTall = false
                                                )
                                            }
                                            selectedTileIdForMove = null
                                            draggedTileId = null
                                            dragOffset = Offset.Zero
                                            DeviceRepository.saveTiles(context, tiles)
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Reset Tiles", color = GhAccentGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }

                                    Button(
                                        onClick = {
                                            viewModel.setEditMode(false)
                                            selectedTileIdForMove = null
                                            draggedTileId = null
                                            dragOffset = Offset.Zero
                                            selectedCardIdForMove = null
                                            draggedCardId = null
                                            cardDragOffset = Offset.Zero
                                            persistDashboardCardLayout()
                                            DeviceRepository.saveTiles(context, tiles)
                                        },
                                        shape = RoundedCornerShape(9.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = GhAccentGreen,
                                            contentColor = Color(0xFF141518)
                                        ),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Done Editing", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }

                        // MAIN BODY: DOCKABLE LEFT/RIGHT NAV + UNIFIED FULL-SCREEN GRID CANVAS
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (navDock == NavDockPosition.LEFT) {
                                RenderVerticalNavDock()
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                val musicTile = tiles.firstOrNull { it.type == TileType.MUSIC_PLAYER }

                                @Composable
                                fun RenderUnifiedCardContent(card: UnifiedCardState) {
                                    if (card.slot == DashboardCardSlot.HERO_CLOCK && !card.isExtraCard) {
                                        SfHouseClockAndWeatherHeroBox(
                                            bridgeSettings = bridgeSettings,
                                            allTiles = tiles,
                                            isEditMode = isEditMode,
                                            selectedRoom = selectedRoomFilter,
                                            availableRooms = bridgeSettings.customRooms,
                                            onSelectRoom = { chosenRoom ->
                                                viewModel.selectRoomFilter(chosenRoom)
                                            },
                                            onRequestCreateNewRoom = {
                                                pendingTileIdForNewRoom = null
                                                newRoomNameDraft = ""
                                                showAddRoomDialog = true
                                            },
                                            isMusicPlaying = isMusicActivelyPlaying,
                                            currentTrack = liveTrack,
                                            requestedHeroPage = requestedHeroPage,
                                            onRequestedHeroPageHandled = {
                                                requestedHeroPage = null
                                            },
                                            rightPaneWidgetType = card.widgetType,
                                            automationRules = automationRules,
                                            masterMusicVolume = musicTile?.volume ?: 60f,
                                            onUpdateTile = handleUpdateAnyTile,
                                            onUpdateMasterMusicVolume = { newVol ->
                                                if (musicTile != null) {
                                                    handleUpdateAnyTile(musicTile.copy(volume = newVol))
                                                } else {
                                                    ClaraInAppMusicPlayer.setVolumePercent(newVol)
                                                }
                                            },
                                            onSelectSecurityMode = { nextMode ->
                                                requestProtectedAction(
                                                    reason = "Parental Lock: Enter PIN to Change Security Mode",
                                                    requiresVehicleOrLockAccess = true
                                                ) {
                                                    bridgeSettings = AutomationEngine.applySecurityCenterMode(
                                                        mode = nextMode,
                                                        context = context,
                                                        tiles = tiles,
                                                        bridge = bridgeSettings,
                                                        audioManager = audioManager,
                                                        scope = scope,
                                                        onSpeak = onSpeak
                                                    )
                                                }
                                            },
                                            onOpenCamerasView = {
                                                activeNavDestination = DashboardNavDestination.CAMERAS
                                            },
                                            onOpenRoutinesView = {
                                                activeNavDestination = DashboardNavDestination.ROUTINES
                                            },
                                            onOpenSetupWizard = {
                                                requestProtectedAction(
                                                    reason = "Parental Lock: Enter PIN to Open Control Center",
                                                    requiresAdminAccess = true
                                                ) {
                                                    wizardInitialSection = SettingsSidebarSection.DEVICE_CONFIG
                                                    showSetupWizard = true
                                                }
                                            },
                                            onToggleEditMode = {
                                                requestProtectedAction(
                                                    reason = "Parental Lock: Enter PIN to Edit Dashboard Layout",
                                                    requiresEditLayout = true
                                                ) {
                                                    selectedTileIdForMove = null
                                                    selectedCardIdForMove = if (isEditMode && selectedCardIdForMove == card.id) null else card.id
                                                    viewModel.setEditMode(true)
                                                }
                                            },
                                            onTriggerVoicePrompt = {
                                                triggerVoiceAssistantPrompt(ignoreWizardDismissGuard = false)
                                            },
                                            onOpenThermostatDial = { thermTile ->
                                                toggleOrActivateHeroTile(thermTile)
                                            },
                                            onUpdateBridgeSettings = { updatedBridge ->
                                                bridgeSettings = updatedBridge
                                                onAppBehaviorSettingsChanged(updatedBridge)
                                                DeviceRepository.saveBridge(context, updatedBridge)
                                            },
                                            onOpenFullMusicPage = {
                                                activeNavDestination = DashboardNavDestination.PLAYER
                                            }
                                        )
                                        return
                                    }

                                    RenderInterchangeableDashboardWidget(
                                        widgetType = card.widgetType,
                                        allTiles = tiles,
                                        automationRules = automationRules,
                                        bridgeSettings = bridgeSettings,
                                        isEditMode = isEditMode,
                                        isMusicPlaying = isMusicActivelyPlaying,
                                        currentTrack = liveTrack,
                                        masterMusicVolume = musicTile?.volume ?: 60f,
                                        onUpdateTile = handleUpdateAnyTile,
                                        onUpdateMasterMusicVolume = { newVol ->
                                            if (musicTile != null) {
                                                handleUpdateAnyTile(musicTile.copy(volume = newVol))
                                            } else {
                                                ClaraInAppMusicPlayer.setVolumePercent(newVol)
                                            }
                                        },
                                        onActivateHeroDial = { clickedTile ->
                                            toggleOrActivateHeroTile(clickedTile)
                                        },
                                        onSelectSecurityMode = { nextMode ->
                                            requestProtectedAction(
                                                reason = "Parental Lock: Enter PIN to Change Security Mode",
                                                requiresVehicleOrLockAccess = true
                                            ) {
                                                bridgeSettings = AutomationEngine.applySecurityCenterMode(
                                                    mode = nextMode,
                                                    context = context,
                                                    tiles = tiles,
                                                    bridge = bridgeSettings,
                                                    audioManager = audioManager,
                                                    scope = scope,
                                                    onSpeak = onSpeak
                                                )
                                            }
                                        },
                                        onOpenSetupWizard = {
                                            requestProtectedAction(
                                                reason = "Parental Lock: Enter PIN to Open Control Center",
                                                requiresAdminAccess = true
                                            ) {
                                                wizardInitialSection = SettingsSidebarSection.DEVICE_CONFIG
                                                showSetupWizard = true
                                            }
                                        },
                                        onOpenCamerasView = {
                                            activeNavDestination = DashboardNavDestination.CAMERAS
                                        },
                                        onOpenRoutinesView = {
                                            activeNavDestination = DashboardNavDestination.ROUTINES
                                        },
                                        onToggleEditMode = {
                                            requestProtectedAction(
                                                reason = "Parental Lock: Enter PIN to Edit Dashboard Layout",
                                                requiresEditLayout = true
                                            ) {
                                                selectedTileIdForMove = null
                                                selectedCardIdForMove = if (isEditMode && selectedCardIdForMove == card.id) null else card.id
                                                viewModel.setEditMode(true)
                                            }
                                        },
                                        onUpdateBridgeSettings = { updatedBridge ->
                                            bridgeSettings = updatedBridge
                                            onAppBehaviorSettingsChanged(updatedBridge)
                                            DeviceRepository.saveBridge(context, updatedBridge)
                                        }
                                    ) {
                                        SfHouseWeatherCard(
                                            isEditMode = isEditMode,
                                            onToggleEditMode = {
                                                requestProtectedAction(
                                                    reason = "Parental Lock: Enter PIN to Edit Dashboard Layout",
                                                    requiresEditLayout = true
                                                ) {
                                                    selectedTileIdForMove = null
                                                    selectedCardIdForMove = if (isEditMode && selectedCardIdForMove == card.id) null else card.id
                                                    viewModel.setEditMode(true)
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }

                                when (activeNavDestination) {
                                    DashboardNavDestination.PLAYER -> {
                                        IntegratedMusicPlayerSection(
                                            initialProvider = bridgeSettings.musicProvider,
                                            bridgeSettings = bridgeSettings,
                                            isPlaying = isMusicActivelyPlaying,
                                            currentTrack = liveTrack,
                                            masterVolume = musicTile?.volume ?: 60f,
                                            onMasterVolumeChange = { newVol ->
                                                if (musicTile != null) {
                                                    handleUpdateAnyTile(musicTile.copy(volume = newVol))
                                                } else {
                                                    ClaraInAppMusicPlayer.setVolumePercent(newVol)
                                                }
                                            },
                                            onTogglePlay = {
                                                localIsPlaying = ClaraInAppMusicPlayer.togglePlayPause(audioManager, scope)
                                            },
                                            onSkip = {
                                                ClaraInAppMusicPlayer.skipToNext(audioManager, scope)
                                            },
                                            onPrevious = {
                                                ClaraInAppMusicPlayer.skipToPrevious(audioManager, scope)
                                            },
                                            onOpenNativeApp = {
                                                openExternalApp(context, bridgeSettings.musicProvider.packageName)
                                            },
                                            showCloseButton = false,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    DashboardNavDestination.CAMERAS -> {
                                        SfHouseCameraMultiViewSection(
                                            allTiles = tiles,
                                            bridgeSettings = bridgeSettings,
                                            onUpdateTile = handleUpdateAnyTile,
                                            onExpandCamera = { camTile ->
                                                expandedFeedTile = camTile
                                            },
                                            onOpenAddCameraWizard = {
                                                requestProtectedAction(
                                                    reason = "Parental Lock: Enter PIN to Open Control Center",
                                                    requiresAdminAccess = true
                                                ) {
                                                    wizardInitialSection = SettingsSidebarSection.DEVICE_CONFIG
                                                    showSetupWizard = true
                                                }
                                            },
                                            onSelectSecurityMode = { nextMode ->
                                                requestProtectedAction(
                                                    reason = "Parental Lock: Enter PIN to Change Security Mode",
                                                    requiresVehicleOrLockAccess = true
                                                ) {
                                                    bridgeSettings = AutomationEngine.applySecurityCenterMode(
                                                        mode = nextMode,
                                                        context = context,
                                                        tiles = tiles,
                                                        bridge = bridgeSettings,
                                                        audioManager = audioManager,
                                                        scope = scope,
                                                        onSpeak = onSpeak
                                                    )
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    DashboardNavDestination.ELECTRICITY -> {
                                        EnergyOverviewDashboardSection(
                                            allTiles = tiles,
                                            onUpdateTile = handleUpdateAnyTile,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    DashboardNavDestination.ROUTINES -> {
                                        AutomationsAndSchedulesSection(
                                            context = context,
                                            tiles = tiles,
                                            automationRules = automationRules,
                                            bridgeSettings = bridgeSettings,
                                            audioManager = audioManager,
                                            compactInputTextStyle = TextStyle(fontSize = 13.sp, fontFamily = GhFont, color = GhTextPrimary),
                                            onSpeak = onSpeak,
                                            onStatusBanner = {}
                                        )
                                    }

                                    else -> {
                                        // =============================================================
                                        // UNIFIED FULL-SCREEN BENTO GRID (CARDS + FREE-ROAM TILES)
                                        // =============================================================
                                        val gridScrollState = rememberScrollState()
                                        var tileDragOriginCol by remember { mutableIntStateOf(0) }
                                        var tileDragOriginRow by remember { mutableIntStateOf(0) }
                                        var accumTileDragOffset by remember { mutableStateOf(Offset.Zero) }

                                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                            val activeGroupCardIds = remember(tiles.toList()) {
                                                tiles.filter { it.type == TileType.GROUP_CARD }.map { it.id }.toSet()
                                            }
                                            val displayedTiles = remember(
                                                tiles.toList(),
                                                isEditMode,
                                                activeGroupCardIds,
                                                selectedRoomFilter
                                            ) {
                                                tiles.filter { t ->
                                                    if (t.serviceClass in setOf(ServiceClass.WEATHER, ServiceClass.MUSIC)) {
                                                        return@filter false
                                                    }
                                                    val isGroupedInsideFolder = t.groupCardId.isNotBlank() && t.groupCardId in activeGroupCardIds
                                                    val passesVisibility = !isGroupedInsideFolder && (isEditMode || t.isVisible)
                                                    if (!passesVisibility) return@filter false

                                                    if (selectedRoomFilter.equals("All Rooms", ignoreCase = true)) {
                                                        true
                                                    } else {
                                                        t.roomOrSubtitle.equals(selectedRoomFilter, ignoreCase = true)
                                                    }
                                                }
                                            }

                                            val gridMetrics = remember(maxWidth, maxHeight, bridgeSettings.dashboardTemplate, bridgeSettings.gridSizePreset) {
                                                computeResponsiveGridMetrics(
                                                    availableWidth = maxWidth,
                                                    availableHeight = maxHeight,
                                                    requiredTileRows = 4,
                                                    dashboardTemplate = bridgeSettings.dashboardTemplate,
                                                    gridSizePreset = bridgeSettings.gridSizePreset
                                                )
                                            }
                                            val gridColumns = gridMetrics.gridColumns
                                            val visibleRows = gridMetrics.visibleRows
                                            val gridSpacing = gridMetrics.gridSpacing
                                            val colWidth = gridMetrics.unitWidth
                                            val dynamicUnitHeight = gridMetrics.unitHeight

                                            LaunchedEffect(gridColumns) {
                                                currentUnifiedGridColumns = gridColumns
                                            }

                                            val (resolvedCards, baseBentoPlacements) = remember(
                                                unifiedCards.toList(),
                                                displayedTiles,
                                                gridColumns
                                            ) {
                                                computeUnifiedDashboardPlacements(
                                                    cards = unifiedCards.toList(),
                                                    tiles = displayedTiles,
                                                    gridColumns = gridColumns,
                                                    maxRowSpanLimit = 4
                                                )
                                            }

                                            val occupiedByCards = remember(resolvedCards) {
                                                buildSet {
                                                    for (c in resolvedCards) {
                                                        for (dr in 0 until c.rowSpan) {
                                                            for (dc in 0 until c.colSpan) {
                                                                add((c.gridCol + dc) to (c.gridRow + dr))
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            val hoveredGroupPlacement = remember(draggedTileId, hoverGridCol, hoverGridRow, baseBentoPlacements) {
                                                val draggingId = draggedTileId ?: return@remember null
                                                val draggingTile = tiles.firstOrNull { it.id == draggingId } ?: return@remember null
                                                if (draggingTile.type == TileType.GROUP_CARD || draggingTile.type == TileType.CLOCK_WEATHER) {
                                                    return@remember null
                                                }
                                                baseBentoPlacements.firstOrNull { p ->
                                                    p.tile.type == TileType.GROUP_CARD &&
                                                            p.tile.id != draggingId &&
                                                            rectsOverlap(
                                                                hoverGridCol, hoverGridRow, 1, 1,
                                                                p.col, p.row, p.colSpan, p.rowSpan
                                                            )
                                                }
                                            }

                                            val liveBentoPlacements = remember(
                                                baseBentoPlacements,
                                                draggedTileId,
                                                hoverGridCol,
                                                hoverGridRow,
                                                gridColumns,
                                                hoveredGroupPlacement,
                                                occupiedByCards
                                            ) {
                                                computeLauncherLiveReflowPlacements(
                                                    basePlacements = baseBentoPlacements,
                                                    draggedTileId = draggedTileId,
                                                    hoverCol = hoverGridCol,
                                                    hoverRow = hoverGridRow,
                                                    gridColumns = gridColumns,
                                                    isHoveringOverFolder = hoveredGroupPlacement != null,
                                                    occupiedByCards = occupiedByCards
                                                )
                                            }

                                            val maxOccupiedRow = remember(resolvedCards, liveBentoPlacements, baseBentoPlacements) {
                                                val maxCardRow = resolvedCards.maxOfOrNull { it.gridRow + it.rowSpan } ?: 2
                                                val maxLiveRow = liveBentoPlacements.maxOfOrNull { it.row + it.rowSpan } ?: 1
                                                val maxBaseRow = baseBentoPlacements.maxOfOrNull { it.row + it.rowSpan } ?: 1
                                                maxOf(maxCardRow, maxLiveRow, maxBaseRow).coerceAtLeast(visibleRows)
                                            }
                                            val totalRows = if (isEditMode) {
                                                (maxOccupiedRow + 2).coerceAtLeast(visibleRows + 1)
                                            } else {
                                                maxOccupiedRow.coerceAtLeast(visibleRows)
                                            }
                                            val totalGridHeight = (dynamicUnitHeight * totalRows) +
                                                    (gridSpacing * (totalRows - 1).coerceAtLeast(0))

                                            val colStepPx = with(density) { (colWidth + gridSpacing).toPx() }
                                            val rowStepPx = with(density) { (dynamicUnitHeight + gridSpacing).toPx() }.coerceAtLeast(1f)
                                            val viewportHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                                            val scrollOffsetPx = gridScrollState.value.toFloat()
                                            val visibleMinRow = ((scrollOffsetPx / rowStepPx).toInt() - 2).coerceAtLeast(0)
                                            val visibleMaxRow = ((scrollOffsetPx + viewportHeightPx) / rowStepPx).toInt() + 2

                                            val visibleBentoPlacements = remember(
                                                liveBentoPlacements,
                                                visibleMinRow,
                                                visibleMaxRow,
                                                draggedTileId,
                                                selectedTileIdForMove
                                            ) {
                                                liveBentoPlacements.filter { p ->
                                                    val bottomRow = p.row + p.rowSpan - 1
                                                    (bottomRow >= visibleMinRow && p.row <= visibleMaxRow) ||
                                                            p.tile.id == draggedTileId ||
                                                            p.tile.id == selectedTileIdForMove
                                                }
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .verticalScroll(
                                                        state = gridScrollState,
                                                        enabled = isEditMode || totalRows > visibleRows
                                                    )
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(totalGridHeight)
                                                ) {
                                                    // 1. Unified Tile-Sized Grid Cells in Edit Mode
                                                    if (isEditMode) {
                                                        for (r in 0 until totalRows) {
                                                            for (c in 0 until gridColumns) {
                                                                key("unified_grid_cell_${c}_${r}") {
                                                                    val cellX = (colWidth + gridSpacing) * c
                                                                    val cellY = (dynamicUnitHeight + gridSpacing) * r
                                                                    val isMoveReady = selectedTileIdForMove != null || selectedCardIdForMove != null

                                                                    Box(
                                                                        contentAlignment = Alignment.Center,
                                                                        modifier = Modifier
                                                                            .offset(x = cellX, y = cellY)
                                                                            .width(colWidth)
                                                                            .height(dynamicUnitHeight)
                                                                            .zIndex(0f)
                                                                            .clip(RoundedCornerShape(18.dp))
                                                                            .background(
                                                                                Color.White.copy(alpha = if (isMoveReady) 0.045f else 0.025f)
                                                                            )
                                                                            .border(
                                                                                width = 1.dp,
                                                                                color = if (isMoveReady) {
                                                                                    Color(0xFF00E5FF).copy(alpha = 0.35f)
                                                                                } else {
                                                                                    Color.White.copy(alpha = 0.09f)
                                                                                },
                                                                                shape = RoundedCornerShape(18.dp)
                                                                            )
                                                                            .clickable {
                                                                                val movingTileId = selectedTileIdForMove
                                                                                val movingCardId = selectedCardIdForMove
                                                                                when {
                                                                                    movingTileId != null -> {
                                                                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                                        commitPlacementsToTiles(tiles, baseBentoPlacements)
                                                                                        recordLayoutUndoSnapshot()
                                                                                        moveTileToGridPosition(
                                                                                            tiles = tiles,
                                                                                            activePlacements = baseBentoPlacements,
                                                                                            tileId = movingTileId,
                                                                                            targetCol = c,
                                                                                            targetRow = r,
                                                                                            gridColumns = gridColumns,
                                                                                            occupiedByCards = occupiedByCards
                                                                                        )
                                                                                        selectedTileIdForMove = null
                                                                                        DeviceRepository.saveTiles(context, tiles)
                                                                                    }
                                                                                    movingCardId != null -> {
                                                                                        val cIdx = unifiedCards.indexOfFirst { it.id == movingCardId }
                                                                                        if (cIdx in unifiedCards.indices) {
                                                                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                                            commitPlacementsToTiles(tiles, baseBentoPlacements)
                                                                                            recordLayoutUndoSnapshot()
                                                                                            val curCard = unifiedCards[cIdx]
                                                                                            val clampedC = c.coerceIn(0, (gridColumns - curCard.colSpan).coerceAtLeast(0))
                                                                                            unifiedCards[cIdx] = curCard.copy(
                                                                                                gridCol = clampedC,
                                                                                                gridRow = r.coerceAtLeast(0)
                                                                                            )
                                                                                            selectedCardIdForMove = null
                                                                                            persistDashboardCardLayout()
                                                                                        }
                                                                                    }
                                                                                    else -> {
                                                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                                        quickAddGridCell = c to r
                                                                                        showQuickAddModal = true
                                                                                    }
                                                                                }
                                                                            }
                                                                    ) {
                                                                        if (isMoveReady) {
                                                                            Box(
                                                                                modifier = Modifier
                                                                                    .size(7.dp)
                                                                                    .clip(CircleShape)
                                                                                    .background(Color(0xFF00E5FF).copy(alpha = 0.65f))
                                                                            )
                                                                        } else {
                                                                            Icon(
                                                                                imageVector = Icons.Default.Add,
                                                                                contentDescription = "Add Card or Tile Here",
                                                                                tint = Color.White.copy(alpha = 0.22f),
                                                                                modifier = Modifier.size(18.dp)
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }

                                                    // 2. Render Dashboard Widget Cards on the Unified Grid
                                                    for (card in resolvedCards) {
                                                        key("unified_card_${card.id}") {
                                                            val cardTargetX = (colWidth + gridSpacing) * card.gridCol
                                                            val cardTargetY = (dynamicUnitHeight + gridSpacing) * card.gridRow
                                                            val cardTargetW = (colWidth * card.colSpan) + (gridSpacing * (card.colSpan - 1))
                                                            val cardTargetH = (dynamicUnitHeight * card.rowSpan) + (gridSpacing * (card.rowSpan - 1))

                                                            val animCardX by animateDpAsState(
                                                                targetValue = cardTargetX,
                                                                animationSpec = spring(
                                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                                    stiffness = Spring.StiffnessMedium
                                                                ),
                                                                label = "UnifiedCardX_${card.id}"
                                                            )
                                                            val animCardY by animateDpAsState(
                                                                targetValue = cardTargetY,
                                                                animationSpec = spring(
                                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                                    stiffness = Spring.StiffnessMedium
                                                                ),
                                                                label = "UnifiedCardY_${card.id}"
                                                            )
                                                            val animCardW by animateDpAsState(
                                                                targetValue = cardTargetW,
                                                                animationSpec = spring(
                                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                                    stiffness = Spring.StiffnessMedium
                                                                ),
                                                                label = "UnifiedCardW_${card.id}"
                                                            )
                                                            val animCardH by animateDpAsState(
                                                                targetValue = cardTargetH,
                                                                animationSpec = spring(
                                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                                    stiffness = Spring.StiffnessMedium
                                                                ),
                                                                label = "UnifiedCardH_${card.id}"
                                                            )

                                                            val isSelectedCard = selectedCardIdForMove == card.id
                                                            val isBeingDraggedCard = draggedCardId == card.id
                                                            var localCardDragAccum by remember(card.id) { mutableStateOf(Offset.Zero) }
                                                            var accumCardResizeX by remember(card.id) { mutableFloatStateOf(0f) }
                                                            var accumCardResizeY by remember(card.id) { mutableFloatStateOf(0f) }

                                                            val stepW = (colStepPx * 0.52f).coerceAtLeast(42f)
                                                            val stepH = (rowStepPx * 0.52f).coerceAtLeast(42f)

                                                            ResizableMovableDashboardCardContainer(
                                                                slot = card.slot,
                                                                isEditMode = isEditMode,
                                                                isSelectedForSwap = isSelectedCard,
                                                                isOtherCardSelected = selectedCardIdForMove != null && selectedCardIdForMove != card.id,
                                                                isBeingDragged = isBeingDraggedCard,
                                                                isDropTargetHighlighted = false,
                                                                dragOffset = if (isBeingDraggedCard) localCardDragAccum else Offset.Zero,
                                                                widthWeight = (card.colSpan / 4f) * card.slot.defaultWidthWeight,
                                                                heightWeight = (card.rowSpan / 2f) * card.slot.defaultHeightWeight,
                                                                zoomScale = card.zoomScale,
                                                                activeWidgetType = card.widgetType,
                                                                onSelectOrSwapCard = {
                                                                    selectedTileIdForMove = null
                                                                    val currentSel = selectedCardIdForMove
                                                                    if (currentSel == null) {
                                                                        selectedCardIdForMove = card.id
                                                                    } else if (currentSel == card.id) {
                                                                        selectedCardIdForMove = null
                                                                    } else {
                                                                        val idxA = unifiedCards.indexOfFirst { it.id == currentSel }
                                                                        val idxB = unifiedCards.indexOfFirst { it.id == card.id }
                                                                        if (idxA in unifiedCards.indices && idxB in unifiedCards.indices) {
                                                                            commitPlacementsToTiles(tiles, baseBentoPlacements)
                                                                            recordLayoutUndoSnapshot()
                                                                            val cA = unifiedCards[idxA]
                                                                            val cB = unifiedCards[idxB]
                                                                            unifiedCards[idxA] = cA.copy(
                                                                                gridCol = cB.gridCol,
                                                                                gridRow = cB.gridRow
                                                                            )
                                                                            unifiedCards[idxB] = cB.copy(
                                                                                gridCol = cA.gridCol,
                                                                                gridRow = cA.gridRow
                                                                            )
                                                                            persistDashboardCardLayout()
                                                                        }
                                                                        selectedCardIdForMove = null
                                                                    }
                                                                },
                                                                onDragCardStart = {
                                                                    selectedTileIdForMove = null
                                                                    commitPlacementsToTiles(tiles, baseBentoPlacements)
                                                                    localCardDragAccum = Offset.Zero
                                                                    cardDragOffset = Offset.Zero
                                                                    draggedCardId = card.id
                                                                    selectedCardIdForMove = card.id
                                                                },
                                                                onDragCardDelta = { delta ->
                                                                    if (draggedCardId == card.id) {
                                                                        localCardDragAccum += delta
                                                                        cardDragOffset = localCardDragAccum
                                                                    }
                                                                },
                                                                onDragCardEnd = {
                                                                    if (draggedCardId == card.id) {
                                                                        val colDelta = if (colStepPx > 0f) (localCardDragAccum.x / colStepPx).roundToInt() else 0
                                                                        val rowDelta = if (rowStepPx > 0f) (localCardDragAccum.y / rowStepPx).roundToInt() else 0
                                                                        draggedCardId = null
                                                                        localCardDragAccum = Offset.Zero
                                                                        cardDragOffset = Offset.Zero
                                                                        if (colDelta != 0 || rowDelta != 0) {
                                                                            val cIdx = unifiedCards.indexOfFirst { it.id == card.id }
                                                                            if (cIdx in unifiedCards.indices) {
                                                                                recordLayoutUndoSnapshot()
                                                                                val cur = unifiedCards[cIdx]
                                                                                val nextC = (card.gridCol + colDelta).coerceIn(0, (gridColumns - cur.colSpan).coerceAtLeast(0))
                                                                                val nextR = (card.gridRow + rowDelta).coerceIn(0, 30)
                                                                                unifiedCards[cIdx] = cur.copy(gridCol = nextC, gridRow = nextR)
                                                                                persistDashboardCardLayout()
                                                                            }
                                                                        }
                                                                    }
                                                                },
                                                                onResizeWidthDeltaPx = { deltaPx, isLeftEdge ->
                                                                    val signedDelta = if (isLeftEdge) -deltaPx else deltaPx
                                                                    accumCardResizeX += signedDelta
                                                                    if (kotlin.math.abs(accumCardResizeX) >= stepW) {
                                                                        val colStep = if (accumCardResizeX > 0f) 1 else -1
                                                                        accumCardResizeX = 0f
                                                                        val cIdx = unifiedCards.indexOfFirst { it.id == card.id }
                                                                        if (cIdx in unifiedCards.indices) {
                                                                            commitPlacementsToTiles(tiles, baseBentoPlacements)
                                                                            recordLayoutUndoSnapshot()
                                                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                            val cur = unifiedCards[cIdx]
                                                                            val nextSpan = (cur.colSpan + colStep).coerceIn(2, gridColumns)
                                                                            val nextCol = if (isLeftEdge && nextSpan != cur.colSpan) {
                                                                                (cur.gridCol - colStep).coerceIn(0, (gridColumns - nextSpan).coerceAtLeast(0))
                                                                            } else {
                                                                                cur.gridCol.coerceIn(0, (gridColumns - nextSpan).coerceAtLeast(0))
                                                                            }
                                                                            unifiedCards[cIdx] = cur.copy(
                                                                                gridCol = nextCol,
                                                                                colSpan = nextSpan
                                                                            )
                                                                            persistDashboardCardLayout()
                                                                        }
                                                                    }
                                                                },
                                                                onResizeHeightDeltaPx = { deltaPx, isTopEdge ->
                                                                    val signedDelta = if (isTopEdge) -deltaPx else deltaPx
                                                                    accumCardResizeY += signedDelta
                                                                    if (kotlin.math.abs(accumCardResizeY) >= stepH) {
                                                                        val rowStep = if (accumCardResizeY > 0f) 1 else -1
                                                                        accumCardResizeY = 0f
                                                                        val cIdx = unifiedCards.indexOfFirst { it.id == card.id }
                                                                        if (cIdx in unifiedCards.indices) {
                                                                            commitPlacementsToTiles(tiles, baseBentoPlacements)
                                                                            recordLayoutUndoSnapshot()
                                                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                            val cur = unifiedCards[cIdx]
                                                                            val nextSpan = (cur.rowSpan + rowStep).coerceIn(1, 12)
                                                                            val nextRow = if (isTopEdge && nextSpan != cur.rowSpan) {
                                                                                (cur.gridRow - rowStep).coerceAtLeast(0)
                                                                            } else {
                                                                                cur.gridRow
                                                                            }
                                                                            unifiedCards[cIdx] = cur.copy(
                                                                                gridRow = nextRow,
                                                                                rowSpan = nextSpan
                                                                            )
                                                                            persistDashboardCardLayout()
                                                                        }
                                                                    }
                                                                },
                                                                onZoomChange = { nextZoom ->
                                                                    val cIdx = unifiedCards.indexOfFirst { it.id == card.id }
                                                                    if (cIdx in unifiedCards.indices) {
                                                                        recordLayoutUndoSnapshot()
                                                                        unifiedCards[cIdx] = unifiedCards[cIdx].copy(
                                                                            zoomScale = nextZoom.coerceIn(0.70f, 1.40f)
                                                                        )
                                                                        persistDashboardCardLayout()
                                                                    }
                                                                },
                                                                onChangeWidgetType = { nextWidget ->
                                                                    val cIdx = unifiedCards.indexOfFirst { it.id == card.id }
                                                                    if (cIdx in unifiedCards.indices) {
                                                                        recordLayoutUndoSnapshot()
                                                                        unifiedCards[cIdx] = unifiedCards[cIdx].copy(
                                                                            widgetType = nextWidget
                                                                        )
                                                                        persistDashboardCardLayout()
                                                                    }
                                                                },
                                                                onResetCardSize = {
                                                                    val cIdx = unifiedCards.indexOfFirst { it.id == card.id }
                                                                    if (cIdx in unifiedCards.indices) {
                                                                        commitPlacementsToTiles(tiles, baseBentoPlacements)
                                                                        recordLayoutUndoSnapshot()
                                                                        val defaultCols = if (card.slot == DashboardCardSlot.VOLUME_SLIDERS) 3 else 4
                                                                        unifiedCards[cIdx] = unifiedCards[cIdx].copy(
                                                                            colSpan = defaultCols,
                                                                            rowSpan = 2,
                                                                            zoomScale = 1.0f
                                                                        )
                                                                        persistDashboardCardLayout()
                                                                    }
                                                                },
                                                                onDeleteCard = {
                                                                    requestProtectedAction(
                                                                        reason = "Parental Lock: Enter PIN to Remove Card",
                                                                        requiresEditLayout = true
                                                                    ) {
                                                                        recordLayoutUndoSnapshot()
                                                                        unifiedCards.removeAll { it.id == card.id }
                                                                        if (selectedCardIdForMove == card.id) {
                                                                            selectedCardIdForMove = null
                                                                        }
                                                                        persistDashboardCardLayout()
                                                                    }
                                                                },
                                                                modifier = Modifier
                                                                    .offset(x = animCardX, y = animCardY)
                                                                    .width(animCardW)
                                                                    .height(animCardH)
                                                            ) {
                                                                RenderUnifiedCardContent(card)
                                                            }
                                                        }
                                                    }

                                                    // 3. Snap Silhouette for Dragged Free-Roam Tile
                                                    val activeDraggedLivePlacement = remember(draggedTileId, liveBentoPlacements) {
                                                        liveBentoPlacements.firstOrNull { it.tile.id == draggedTileId }
                                                    }
                                                    if (isEditMode && activeDraggedLivePlacement != null && hoveredGroupPlacement == null) {
                                                        val snapX by animateDpAsState(
                                                            targetValue = (colWidth + gridSpacing) * activeDraggedLivePlacement.col,
                                                            animationSpec = spring(
                                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                                stiffness = Spring.StiffnessMedium
                                                            ),
                                                            label = "SnapSilhouetteX"
                                                        )
                                                        val snapY by animateDpAsState(
                                                            targetValue = (dynamicUnitHeight + gridSpacing) * activeDraggedLivePlacement.row,
                                                            animationSpec = spring(
                                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                                stiffness = Spring.StiffnessMedium
                                                            ),
                                                            label = "SnapSilhouetteY"
                                                        )
                                                        val snapW = (colWidth * activeDraggedLivePlacement.colSpan) +
                                                                (gridSpacing * (activeDraggedLivePlacement.colSpan - 1))
                                                        val snapH = (dynamicUnitHeight * activeDraggedLivePlacement.rowSpan) +
                                                                (gridSpacing * (activeDraggedLivePlacement.rowSpan - 1))

                                                        Surface(
                                                            color = GhAccentGreen.copy(alpha = 0.14f),
                                                            shape = RoundedCornerShape(18.dp),
                                                            border = BorderStroke(
                                                                width = 2.dp,
                                                                brush = Brush.verticalGradient(
                                                                    colors = listOf(
                                                                        GhAccentGreen,
                                                                        Color(0xFF00E5FF)
                                                                    )
                                                                )
                                                            ),
                                                            modifier = Modifier
                                                                .offset(x = snapX, y = snapY)
                                                                .width(snapW)
                                                                .height(snapH)
                                                                .zIndex(1f)
                                                        ) {}
                                                    }

                                                    // 4. Render Free-Roam Device Tiles on the Unified Grid (Viewport-Virtualized)
                                                    for (placement in visibleBentoPlacements) {
                                                        val tile = placement.tile
                                                        key(tile.id) {
                                                            val currentBasePlacements by rememberUpdatedState(baseBentoPlacements)
                                                            val currentLivePlacements by rememberUpdatedState(liveBentoPlacements)
                                                            val currentHoveredGroup by rememberUpdatedState(hoveredGroupPlacement)
                                                            val isSelectedForSwap = selectedTileIdForMove == tile.id
                                                            val isBeingDragged = draggedTileId == tile.id
                                                            val isDropTarget = hoveredGroupPlacement?.tile?.id == tile.id

                                                            val targetColForBox = if (isBeingDragged) tileDragOriginCol else placement.col
                                                            val targetRowForBox = if (isBeingDragged) tileDragOriginRow else placement.row

                                                            val animatedXOffset by animateDpAsState(
                                                                targetValue = (colWidth + gridSpacing) * targetColForBox,
                                                                animationSpec = spring(
                                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                                    stiffness = if (isBeingDragged) Spring.StiffnessHigh else Spring.StiffnessMedium
                                                                ),
                                                                label = "TileGridX_${tile.id}"
                                                            )
                                                            val animatedYOffset by animateDpAsState(
                                                                targetValue = (dynamicUnitHeight + gridSpacing) * targetRowForBox,
                                                                animationSpec = spring(
                                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                                    stiffness = if (isBeingDragged) Spring.StiffnessHigh else Spring.StiffnessMedium
                                                                ),
                                                                label = "TileGridY_${tile.id}"
                                                            )

                                                            val tileWidth by animateDpAsState(
                                                                targetValue = (colWidth * placement.colSpan) + (gridSpacing * (placement.colSpan - 1)),
                                                                animationSpec = spring(
                                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                                    stiffness = Spring.StiffnessMedium
                                                                ),
                                                                label = "TileWidthSpring_${tile.id}"
                                                            )

                                                            var edgeAccumX by remember(tile.id) { mutableFloatStateOf(0f) }
                                                            var edgeAccumY by remember(tile.id) { mutableFloatStateOf(0f) }

                                                            fun applySelectedTileEdgeResize(newCols: Int, newRows: Int) {
                                                                commitPlacementsToTiles(tiles, currentBasePlacements)
                                                                recordLayoutUndoSnapshot()
                                                                val idxNow = tiles.indexOfFirst { it.id == tile.id }
                                                                if (idxNow in tiles.indices) {
                                                                    val clampedC = newCols.coerceIn(1, gridColumns.coerceAtLeast(1))
                                                                    val clampedR = newRows.coerceIn(1, 4)
                                                                    val safeGridCol = if (placement.col + clampedC > gridColumns) {
                                                                        (gridColumns - clampedC).coerceAtLeast(0)
                                                                    } else {
                                                                        placement.col
                                                                    }
                                                                    tiles[idxNow] = tiles[idxNow].copy(
                                                                        colSpan = clampedC,
                                                                        rowSpan = clampedR,
                                                                        isTall = clampedR > 1,
                                                                        gridCol = safeGridCol,
                                                                        gridRow = placement.row
                                                                    )
                                                                    DeviceRepository.saveTiles(context, tiles)
                                                                }
                                                            }

                                                            Box(
                                                                modifier = Modifier
                                                                    .offset(x = animatedXOffset, y = animatedYOffset)
                                                                    .width(tileWidth)
                                                                    .zIndex(if (isBeingDragged) 35f else if (isSelectedForSwap || isDropTarget) 20f else 2f)
                                                            ) {
                                                                GoogleHomeTileCard(
                                                                    tile = tile.copy(
                                                                        colSpan = placement.colSpan,
                                                                        rowSpan = placement.rowSpan,
                                                                        isTall = placement.rowSpan > 1
                                                                    ),
                                                                    bridgeSettings = bridgeSettings,
                                                                    isOverlayModalOpen = isOverlayModalOpen,
                                                                    isEditMode = isEditMode,
                                                                    isSelectedForSwap = isSelectedForSwap,
                                                                    isBeingDragged = isBeingDragged,
                                                                    isDropTargetHighlighted = isDropTarget,
                                                                    dragOffset = if (isBeingDragged) accumTileDragOffset else Offset.Zero,
                                                                    unitHeight = dynamicUnitHeight,
                                                                    unitWidth = colWidth,
                                                                    gridSpacing = gridSpacing,
                                                                    maxGridCols = gridColumns,
                                                                    maxGridRows = 4,
                                                                    musicProvider = bridgeSettings.musicProvider,
                                                                    isMusicPlaying = isMusicActivelyPlaying,
                                                                    currentTrack = liveTrack,
                                                                    allTiles = tiles,
                                                                    onOpenSetupWizard = {
                                                                        if (!isEditMode) {
                                                                            requestProtectedAction(
                                                                                reason = "Parental Lock: Enter PIN to Open Control Center",
                                                                                requiresAdminAccess = true
                                                                            ) {
                                                                                wizardInitialSection = SettingsSidebarSection.DEVICE_CONFIG
                                                                                showSetupWizard = true
                                                                            }
                                                                        }
                                                                    },
                                                                    onToggleEditMode = {
                                                                        val enteringEdit = !isEditMode
                                                                        if (enteringEdit) {
                                                                            requestProtectedAction(
                                                                                reason = "Parental Lock: Enter PIN to Edit Dashboard Layout",
                                                                                requiresEditLayout = true
                                                                            ) {
                                                                                commitPlacementsToTiles(tiles, currentBasePlacements)
                                                                                selectedCardIdForMove = null
                                                                                selectedTileIdForMove = tile.id
                                                                                viewModel.setEditMode(true)
                                                                            }
                                                                        } else {
                                                                            viewModel.setEditMode(false)
                                                                            selectedTileIdForMove = null
                                                                            selectedCardIdForMove = null
                                                                            persistDashboardCardLayout()
                                                                            DeviceRepository.saveTiles(context, tiles)
                                                                        }
                                                                    },
                                                                    onSelectOrSwap = {
                                                                        selectedCardIdForMove = null
                                                                        val currentSelectedId = selectedTileIdForMove
                                                                        if (currentSelectedId == null) {
                                                                            selectedTileIdForMove = tile.id
                                                                        } else if (currentSelectedId == tile.id) {
                                                                            selectedTileIdForMove = null
                                                                        } else {
                                                                            commitPlacementsToTiles(tiles, currentBasePlacements)
                                                                            recordLayoutUndoSnapshot()
                                                                            val movingIdx = tiles.indexOfFirst { it.id == currentSelectedId }
                                                                            val movingTile = if (movingIdx >= 0) tiles[movingIdx] else null
                                                                            if (tile.type == TileType.GROUP_CARD &&
                                                                                movingTile != null &&
                                                                                movingTile.type != TileType.GROUP_CARD &&
                                                                                movingTile.type != TileType.CLOCK_WEATHER
                                                                            ) {
                                                                                tiles[movingIdx] = movingTile.copy(groupCardId = tile.id)
                                                                                selectedTileIdForMove = null
                                                                                DeviceRepository.saveTiles(context, tiles)
                                                                            } else {
                                                                                moveTileToGridPosition(
                                                                                    tiles = tiles,
                                                                                    activePlacements = currentBasePlacements,
                                                                                    tileId = currentSelectedId,
                                                                                    targetCol = placement.col,
                                                                                    targetRow = placement.row,
                                                                                    gridColumns = gridColumns,
                                                                                    occupiedByCards = occupiedByCards
                                                                                )
                                                                                selectedTileIdForMove = null
                                                                                DeviceRepository.saveTiles(context, tiles)
                                                                            }
                                                                        }
                                                                    },
                                                                    onDragStart = {
                                                                        selectedCardIdForMove = null
                                                                        commitPlacementsToTiles(tiles, currentBasePlacements)
                                                                        recordLayoutUndoSnapshot()
                                                                        val baseOrigin = currentBasePlacements.firstOrNull { it.tile.id == tile.id }
                                                                        tileDragOriginCol = baseOrigin?.col ?: placement.col
                                                                        tileDragOriginRow = baseOrigin?.row ?: placement.row
                                                                        accumTileDragOffset = Offset.Zero
                                                                        dragOffset = Offset.Zero
                                                                        hoverGridCol = tileDragOriginCol
                                                                        hoverGridRow = tileDragOriginRow
                                                                        draggedTileId = tile.id
                                                                        selectedTileIdForMove = tile.id
                                                                    },
                                                                    onDragDelta = { delta ->
                                                                        accumTileDragOffset += delta
                                                                        dragOffset = accumTileDragOffset
                                                                        val colDelta = if (colStepPx > 0f) (accumTileDragOffset.x / colStepPx).roundToInt() else 0
                                                                        val rowDelta = if (rowStepPx > 0f) (accumTileDragOffset.y / rowStepPx).roundToInt() else 0
                                                                        val nextCol = (tileDragOriginCol + colDelta)
                                                                            .coerceIn(0, (gridColumns - placement.colSpan).coerceAtLeast(0))
                                                                        val nextRow = (tileDragOriginRow + rowDelta)
                                                                            .coerceIn(0, (totalRows - 1).coerceAtLeast(0))
                                                                        if (nextCol != hoverGridCol || nextRow != hoverGridRow) {
                                                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                            hoverGridCol = nextCol
                                                                            hoverGridRow = nextRow
                                                                        }
                                                                    },
                                                                    onDragEnd = {
                                                                        val dropGroup = currentHoveredGroup?.tile
                                                                        val finalPlacementsToCommit = currentLivePlacements
                                                                        draggedTileId = null
                                                                        accumTileDragOffset = Offset.Zero
                                                                        dragOffset = Offset.Zero

                                                                        if (dropGroup != null &&
                                                                            tile.type != TileType.GROUP_CARD &&
                                                                            tile.type != TileType.CLOCK_WEATHER
                                                                        ) {
                                                                            val idxNow = tiles.indexOfFirst { it.id == tile.id }
                                                                            if (idxNow >= 0) {
                                                                                tiles[idxNow] = tiles[idxNow].copy(groupCardId = dropGroup.id)
                                                                            }
                                                                        } else {
                                                                            commitPlacementsToTiles(tiles, finalPlacementsToCommit)
                                                                        }
                                                                        DeviceRepository.saveTiles(context, tiles)
                                                                    },
                                                                    onToggleColSpan = {
                                                                        val nextCols = if (placement.colSpan >= 3) 1 else placement.colSpan + 1
                                                                        applySelectedTileEdgeResize(nextCols, placement.rowSpan)
                                                                    },
                                                                    onToggleHeight = {
                                                                        val nextRows = if (placement.rowSpan >= 3) 1 else placement.rowSpan + 1
                                                                        applySelectedTileEdgeResize(placement.colSpan, nextRows)
                                                                    },
                                                                    onResizeTile = { newColSpan, newRowSpan ->
                                                                        applySelectedTileEdgeResize(newColSpan, newRowSpan)
                                                                    },
                                                                    onDeleteTile = {
                                                                        requestProtectedAction(
                                                                            reason = "Parental Lock: Enter PIN to Delete ${tile.title}",
                                                                            requiresEditLayout = true
                                                                        ) {
                                                                            commitPlacementsToTiles(tiles, currentBasePlacements)
                                                                            showRemovalDialogForTile = tile
                                                                        }
                                                                    },
                                                                    onUpdateTile = { updated ->
                                                                        if (isEditMode) {
                                                                            selectedCardIdForMove = null
                                                                            selectedTileIdForMove = tile.id
                                                                            return@GoogleHomeTileCard
                                                                        }
                                                                        handleUpdateAnyTile(updated)
                                                                    },
                                                                    onUpdateSpecificTile = handleUpdateAnyTile,
                                                                    onExpandCamera = {
                                                                        if (isEditMode) {
                                                                            selectedCardIdForMove = null
                                                                            selectedTileIdForMove = tile.id
                                                                        } else {
                                                                            toggleOrActivateHeroTile(tile)
                                                                        }
                                                                    },
                                                                    onOpenMusicApp = {
                                                                        activeNavDestination = DashboardNavDestination.PLAYER
                                                                    },
                                                                    onToggleMusicPlay = {
                                                                        if (!isEditMode) {
                                                                            localIsPlaying = ClaraInAppMusicPlayer.togglePlayPause(audioManager, scope)
                                                                        }
                                                                    },
                                                                    onSkipTrack = {
                                                                        if (!isEditMode) {
                                                                            ClaraInAppMusicPlayer.skipToNext(audioManager, scope)
                                                                        }
                                                                    },
                                                                    onPreviousTrack = {
                                                                        if (!isEditMode) {
                                                                            ClaraInAppMusicPlayer.skipToPrevious(audioManager, scope)
                                                                        }
                                                                    },
                                                                    onExecuteScene = { sceneName ->
                                                                        if (!isEditMode) {
                                                                            executeHomeRoutine(
                                                                                sceneName = sceneName,
                                                                                tiles = tiles,
                                                                                bridge = bridgeSettings,
                                                                                audioManager = audioManager,
                                                                                scope = scope,
                                                                                context = context,
                                                                                onSpeak = onSpeak
                                                                            )
                                                                        }
                                                                    },
                                                                    onTriggerVoicePrompt = {
                                                                        if (!isEditMode) {
                                                                            triggerVoiceAssistantPrompt(ignoreWizardDismissGuard = false)
                                                                        }
                                                                    },
                                                                    onActivateHeroDial = { clickedTile ->
                                                                        toggleOrActivateHeroTile(clickedTile)
                                                                    }
                                                                )

                                                                // Outer 4-Edge Grid-Snap Resize Zones ONLY when this tile is selected
                                                                if (isEditMode && isSelectedForSwap) {
                                                                    val stepW = (colStepPx * 0.55f).coerceAtLeast(40f)
                                                                    val stepH = (rowStepPx * 0.55f).coerceAtLeast(40f)

                                                                    Box(
                                                                        modifier = Modifier
                                                                            .align(Alignment.CenterEnd)
                                                                            .width(18.dp)
                                                                            .fillMaxHeight(0.70f)
                                                                            .zIndex(25f)
                                                                            .pointerInput(tile.id, placement.colSpan, placement.rowSpan) {
                                                                                detectDragGestures(
                                                                                    onDragStart = { edgeAccumX = 0f },
                                                                                    onDrag = { change, dragAmount ->
                                                                                        change.consume()
                                                                                        edgeAccumX += dragAmount.x
                                                                                        if (edgeAccumX >= stepW) {
                                                                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                                            applySelectedTileEdgeResize(placement.colSpan + 1, placement.rowSpan)
                                                                                            edgeAccumX = 0f
                                                                                        } else if (edgeAccumX <= -stepW) {
                                                                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                                            applySelectedTileEdgeResize(placement.colSpan - 1, placement.rowSpan)
                                                                                            edgeAccumX = 0f
                                                                                        }
                                                                                    }
                                                                                )
                                                                            }
                                                                    )

                                                                    Box(
                                                                        modifier = Modifier
                                                                            .align(Alignment.BottomCenter)
                                                                            .fillMaxWidth(0.70f)
                                                                            .height(18.dp)
                                                                            .zIndex(25f)
                                                                            .pointerInput(tile.id, placement.colSpan, placement.rowSpan) {
                                                                                detectDragGestures(
                                                                                    onDragStart = { edgeAccumY = 0f },
                                                                                    onDrag = { change, dragAmount ->
                                                                                        change.consume()
                                                                                        edgeAccumY += dragAmount.y
                                                                                        if (edgeAccumY >= stepH) {
                                                                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                                            applySelectedTileEdgeResize(placement.colSpan, placement.rowSpan + 1)
                                                                                            edgeAccumY = 0f
                                                                                        } else if (edgeAccumY <= -stepH) {
                                                                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                                            applySelectedTileEdgeResize(placement.colSpan, placement.rowSpan - 1)
                                                                                            edgeAccumY = 0f
                                                                                        }
                                                                                    }
                                                                                )
                                                                            }
                                                                    )
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

                            if (navDock == NavDockPosition.RIGHT) {
                                RenderVerticalNavDock()
                            }
                        }

                        if (navDock == NavDockPosition.BOTTOM) {
                            RenderHorizontalNavDock()
                        }
                    }
                }
            }
        }

        // Quick Add Card / Tile Modal when tapping empty grid space or "+ Add Card / Tile" in Edit Mode
        if (showQuickAddModal) {
            QuickAddCardOrTileModal(
                context = context,
                tiles = tiles,
                targetGridCol = quickAddGridCell.first,
                targetGridRow = quickAddGridCell.second,
                selectedRoomFilter = selectedRoomFilter,
                onAddNewWidgetCardAtCell = { chosenWidget, targetCol, targetRow ->
                    val newCard = UnifiedCardState(
                        id = "extra_card_${UUID.randomUUID()}",
                        slot = DashboardCardSlot.ROOM_PHOTO,
                        widgetType = chosenWidget,
                        gridCol = targetCol.coerceAtLeast(0),
                        gridRow = targetRow.coerceAtLeast(0),
                        colSpan = 3,
                        rowSpan = 2,
                        zoomScale = 1.0f,
                        isExtraCard = true
                    )
                    unifiedCards.add(newCard)
                    persistDashboardCardLayout()
                },
                onOpenFullSetupWizard = {
                    showQuickAddModal = false
                    requestProtectedAction(
                        reason = "Parental Lock: Enter PIN to Open Control Center",
                        requiresAdminAccess = true
                    ) {
                        wizardInitialSection = SettingsSidebarSection.DEVICE_CONFIG
                        showSetupWizard = true
                    }
                },
                onRecordUndoSnapshot = { recordLayoutUndoSnapshot() },
                onDismiss = { showQuickAddModal = false }
            )
        }

        if (showEntertainmentHubModal) {
            EntertainmentHubModal(
                allTiles = tiles,
                bridgeSettings = bridgeSettings,
                onDismiss = { showEntertainmentHubModal = false }
            )
        }

        // Optional Detailed / Advanced Controls Modal when tapped in the Hero Card
        detailModalTile?.let { modalTile ->
            when {
                modalTile.isThermostatTile -> {
                    ThermostatControlModal(
                        tile = modalTile,
                        onUpdateTile = { updated ->
                            handleUpdateAnyTile(updated)
                            detailModalTile = updated
                        },
                        onDismiss = { detailModalTile = null }
                    )
                }
                modalTile.deviceSubtype == DeviceSubtype.TV || modalTile.type == TileType.LG_TV -> {
                    LgTvRemoteModal(
                        tile = modalTile,
                        bridgeSettings = bridgeSettings,
                        onUpdateTile = { updated ->
                            handleUpdateAnyTile(updated)
                            detailModalTile = updated
                        },
                        onDismiss = { detailModalTile = null }
                    )
                }
                modalTile.isSwitchTile || modalTile.capabilities.isNotEmpty() -> {
                    SmartKnightLiveDpsModal(
                        tile = modalTile,
                        onUpdateTile = { updated ->
                            handleUpdateAnyTile(updated)
                            detailModalTile = updated
                        },
                        onDismiss = { detailModalTile = null }
                    )
                }
                else -> {
                    detailModalTile = null
                }
            }
        }

        // RBAC & Parental Lock PIN Prompt Modal
        if (showPinPromptDialog) {
            val activeProf = bridgeSettings.activeUserProfile
            val expectedPin = activeProf.customPinCode.ifBlank { bridgeSettings.rolePinCode }
            val isSettingInitialPin = expectedPin.isBlank()
            AlertDialog(
                onDismissRequest = {
                    showPinPromptDialog = false
                    pendingProtectedAction = null
                },
                containerColor = GhTileSurface,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = GhAccentGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isSettingInitialPin) "Set Household Parental Lock PIN" else pinPromptReason,
                            color = GhTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isSettingInitialPin) {
                            Text(
                                text = "Profile '${activeProf.name}' has Parental Lock enabled, but no PIN is saved yet. Enter a 4–8 digit PIN to save and unlock:",
                                color = GhTextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        OutlinedTextField(
                            value = enteredPinDraft,
                            onValueChange = {
                                enteredPinDraft = it.filter { c -> c.isDigit() }.take(8)
                                pinErrorText = ""
                            },
                            label = { Text("Security PIN Code", fontSize = 12.sp) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (pinErrorText.isNotBlank()) {
                            Text(
                                text = pinErrorText,
                                color = GhAccentRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (isSettingInitialPin) {
                                if (enteredPinDraft.length >= 4) {
                                    val hashedPin = DeviceRepository.hashPinCode(enteredPinDraft)
                                    val updatedBridge = bridgeSettings.copy(rolePinCode = hashedPin)
                                    bridgeSettings = updatedBridge
                                    DeviceRepository.saveBridge(context, updatedBridge)
                                    val actionToRun = pendingProtectedAction
                                    showPinPromptDialog = false
                                    pendingProtectedAction = null
                                    actionToRun?.invoke()
                                } else {
                                    pinErrorText = "Please enter at least 4 digits for your PIN."
                                }
                            } else if (DeviceRepository.verifyPinCode(enteredPinDraft, expectedPin)) {
                                val actionToRun = pendingProtectedAction
                                showPinPromptDialog = false
                                pendingProtectedAction = null
                                actionToRun?.invoke()
                            } else {
                                pinErrorText = "Incorrect PIN code."
                                DeviceRepository.logAuditEvent(
                                    context = context,
                                    actorRole = bridgeSettings.activeUserRole,
                                    actionType = "PIN_AUTH_FAILED",
                                    targetName = pinPromptReason,
                                    detail = "Incorrect PIN entered under profile ${activeProf.name}",
                                    succeeded = false
                                )
                            }
                        },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GhAccentGreen,
                            contentColor = Color(0xFF141518)
                        )
                    ) {
                        Text(
                            text = if (isSettingInitialPin) "Save PIN & Unlock" else "Unlock",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showPinPromptDialog = false
                            pendingProtectedAction = null
                        }
                    ) {
                        Text("Cancel", color = GhTextSecondary)
                    }
                }
            )
        }

        // "+" Add New Room Modal Dialog
        if (showAddRoomDialog) {
            AlertDialog(
                onDismissRequest = {
                    showAddRoomDialog = false
                    pendingTileIdForNewRoom = null
                },
                containerColor = GhTileSurface,
                title = {
                    Text(
                        text = "Create New Room",
                        color = GhTextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    OutlinedTextField(
                        value = newRoomNameDraft,
                        onValueChange = { newRoomNameDraft = it },
                        label = { Text("Room Name (e.g. Living Room, Kitchen, Garden)", fontSize = 12.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = claraFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val clean = newRoomNameDraft.trim()
                            if (clean.isNotBlank()) {
                                bridgeSettings = DeviceRepository.addCustomRoom(context, clean, bridgeSettings)
                                val targetTileId = pendingTileIdForNewRoom
                                if (targetTileId != null) {
                                    val tIdx = tiles.indexOfFirst { it.id == targetTileId }
                                    if (tIdx in tiles.indices) {
                                        handleUpdateAnyTile(tiles[tIdx].copy(roomOrSubtitle = clean))
                                    }
                                } else {
                                    viewModel.selectRoomFilter(clean)
                                }
                            }
                            pendingTileIdForNewRoom = null
                            showAddRoomDialog = false
                        },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GhAccentGreen,
                            contentColor = Color(0xFF141518)
                        )
                    ) {
                        Text("Create Room", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            pendingTileIdForNewRoom = null
                            showAddRoomDialog = false
                        }
                    ) {
                        Text("Cancel", color = GhTextSecondary)
                    }
                }
            )
        }

        if (ringingDoorbellTile != null) {
            ringingDoorbellTile?.let { doorbell ->
                FullScreenCameraModal(
                    tile = doorbell,
                    bridgeSettings = bridgeSettings,
                    isEmergencyRing = true,
                    pendingIntent = ringingPendingIntent,
                    onUpdateTile = { updated ->
                        val idx = tiles.indexOfFirst { it.id == updated.id }
                        if (idx >= 0) {
                            tiles[idx] = tiles[idx].copy(
                                useSubStream = updated.useSubStream,
                                ipAddress = updated.ipAddress,
                                serialNumber = updated.serialNumber,
                                secretCode = updated.secretCode
                            )
                            DeviceRepository.saveTiles(context, tiles)
                        }
                        ringingDoorbellTile = updated
                    },
                    onSpeak = onSpeak,
                    onDismiss = {
                        ringingDoorbellTile = null
                        ringingPendingIntent = null
                        JarvisLiveBridge.clearDoorbellEvent()
                    }
                )
            }
        }

        if (expandedFeedTile != null && ringingDoorbellTile == null && !showSetupWizard) {
            expandedFeedTile?.let { cam ->
                FullScreenCameraModal(
                    tile = cam,
                    bridgeSettings = bridgeSettings,
                    isEmergencyRing = false,
                    pendingIntent = null,
                    onUpdateTile = { updated ->
                        val idx = tiles.indexOfFirst { it.id == updated.id }
                        if (idx >= 0) {
                            tiles[idx] = tiles[idx].copy(
                                useSubStream = updated.useSubStream,
                                ipAddress = updated.ipAddress,
                                serialNumber = updated.serialNumber,
                                secretCode = updated.secretCode
                            )
                            DeviceRepository.saveTiles(context, tiles)
                        }
                        expandedFeedTile = updated
                    },
                    onSpeak = onSpeak,
                    onDismiss = { expandedFeedTile = null }
                )
            }
        }

        if (showSetupWizard) {
            SetupWizardFullScreen(
                tiles = tiles.toMutableList(),
                bridgeSettings = bridgeSettings,
                automationRulesExternal = automationRules.toMutableList(),
                initialSection = wizardInitialSection,
                onSaveAll = { updatedBridge ->
                    lastWizardDismissTimeMs = System.currentTimeMillis()
                    expandedFeedTile = null
                    viewModel.updateBridgeSettings(context, updatedBridge)
                    onAppBehaviorSettingsChanged(updatedBridge)
                    if (!selectedRoomFilter.equals("All Rooms", ignoreCase = true) &&
                        tiles.any { it.isVisible && !it.roomOrSubtitle.equals(selectedRoomFilter, ignoreCase = true) }
                    ) {
                        viewModel.selectRoomFilter("All Rooms")
                    }
                    commitUnifiedCardAndTileCollisions(
                        context = context,
                        cards = unifiedCards,
                        tiles = tiles,
                        gridColumns = currentUnifiedGridColumns
                    )
                    DeviceRepository.saveBridge(context, updatedBridge)
                    DeviceRepository.saveTiles(context, tiles)
                    DeviceRepository.saveAutomations(context, automationRules)
                    showSetupWizard = false
                },
                onOpenNotificationAccess = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                onToggleEditMode = {
                    lastWizardDismissTimeMs = System.currentTimeMillis()
                    expandedFeedTile = null
                    showSetupWizard = false
                    requestProtectedAction(
                        reason = "Parental Lock: Enter PIN to Edit Dashboard Layout",
                        requiresEditLayout = true
                    ) {
                        selectedCardIdForMove = null
                        selectedTileIdForMove = null
                        viewModel.setEditMode(true)
                    }
                },
                onSpeak = onSpeak,
                onTriggerVoicePrompt = {
                    lastWizardDismissTimeMs = System.currentTimeMillis()
                    showSetupWizard = false
                    scope.launch {
                        delay(350L)
                        triggerVoiceAssistantPrompt(ignoreWizardDismissGuard = true)
                    }
                },
                onDismiss = {
                    lastWizardDismissTimeMs = System.currentTimeMillis()
                    expandedFeedTile = null
                    val reloaded = DeviceRepository.loadBridge(context)
                    bridgeSettings = reloaded
                    onAppBehaviorSettingsChanged(reloaded)
                    if (!selectedRoomFilter.equals("All Rooms", ignoreCase = true) &&
                        tiles.any { it.isVisible && !it.roomOrSubtitle.equals(selectedRoomFilter, ignoreCase = true) }
                    ) {
                        viewModel.selectRoomFilter("All Rooms")
                    }
                    commitUnifiedCardAndTileCollisions(
                        context = context,
                        cards = unifiedCards,
                        tiles = tiles,
                        gridColumns = currentUnifiedGridColumns
                    )
                    DeviceRepository.saveTiles(context, tiles)
                    DeviceRepository.saveAutomations(context, automationRules)
                    showSetupWizard = false
                }
            )
        }

        if (showRemovalDialogForTile != null) {
            val t = showRemovalDialogForTile!!
            com.jarvis.smarthomelauncher.ui.modals.DeviceRemovalDialog(
                context = context,
                tile = t,
                bridge = bridgeSettings,
                onDismiss = { showRemovalDialogForTile = null },
                onUnpaired = {
                    recordLayoutUndoSnapshot()
                    if (activeHeroDialTileId == t.id) {
                        activeHeroDialTileId = null
                        activeHeroTransientTile = null
                    }
                    com.jarvis.smarthomelauncher.CameraStreamSessionManager.releaseSession(t.id)
                    if (t.type == com.jarvis.smarthomelauncher.core.TileType.GROUP_CARD) {
                        for (i in tiles.indices) {
                            if (tiles[i].groupCardId == t.id) {
                                tiles[i] = tiles[i].copy(groupCardId = "")
                            }
                        }
                    }
                    DeviceRepository.unpairTileFromDashboard(context, t, tiles)
                    showRemovalDialogForTile = null
                },
                onUnbound = {
                    recordLayoutUndoSnapshot()
                    if (activeHeroDialTileId == t.id) {
                        activeHeroDialTileId = null
                        activeHeroTransientTile = null
                    }
                    com.jarvis.smarthomelauncher.CameraStreamSessionManager.releaseSession(t.id)
                    if (t.type == com.jarvis.smarthomelauncher.core.TileType.GROUP_CARD) {
                        for (i in tiles.indices) {
                            if (tiles[i].groupCardId == t.id) {
                                tiles[i] = tiles[i].copy(groupCardId = "")
                            }
                        }
                    }
                    scope.launch {
                        DeviceRepository.unbindAndForgetTile(context, t, bridgeSettings, tiles)
                    }
                    showRemovalDialogForTile = null
                }
            )
        }

        val dialTile = activeHeroDialTile
                if (dialTile != null) {
                    if (true) {
                        com.jarvis.smarthomelauncher.ui.popups.DeviceControlPopupHost(
                    tile = dialTile,
                    bridgeSettings = bridgeSettings,
                    availableRooms = bridgeSettings.customRooms,
                    onUpdateTile = { updated ->
                        handleUpdateAnyTile(updated)
                    },
                    onUpdateBridgeSettings = { updatedBridge ->
                        bridgeSettings = updatedBridge
                        onAppBehaviorSettingsChanged(updatedBridge)
                        DeviceRepository.saveBridge(context, updatedBridge)
                    },
                    onAssignRoom = { chosenRoom ->
                        handleUpdateAnyTile(dialTile.copy(roomOrSubtitle = chosenRoom))
                        if (chosenRoom.isNotBlank() &&
                            bridgeSettings.customRooms.none { it.equals(chosenRoom, ignoreCase = true) }
                        ) {
                            bridgeSettings = DeviceRepository.addCustomRoom(
                                context = context,
                                newRoomName = chosenRoom,
                                currentBridge = bridgeSettings
                            )
                        }
                    },
                    onRequestCreateNewRoom = {
                        pendingTileIdForNewRoom = dialTile.id
                        newRoomNameDraft = ""
                        showAddRoomDialog = true
                    },
                    onUserInteraction = {
                        heroDialInteractionNonce++
                    },
                    onClose = {
                        activeHeroDialTileId = null
                        activeHeroTransientTile = null
                    },
                    onExpandCameraFullScreen = {
                        expandedFeedTile = dialTile
                    },
                    onOpenFullDetailsModal = {
                        detailModalTile = dialTile
                    }
                )
            }
        }

        if (isScreenSleeping && ringingDoorbellTile == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { onUserWakeSignal() }
            )
        }
    }
}
