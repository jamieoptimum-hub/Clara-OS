package com.jarvis.smarthomelauncher.ui.widgets

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------------------
// 0B. EMBEDDED ROOM PHOTO SELECTOR PANEL (LEFT HALF OF CLOCK HERO CARD)
// ---------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SfHouseRoomSelectorPanel(
    selectedRoom: String,
    availableRooms: List<String>,
    isEditMode: Boolean = false,
    isDarkMode: Boolean = ClaraThemeManager.isDarkMode,
    greetingMessage: String = "",
    bridgeSettings: BridgeSettings = BridgeSettings(),
    onUpdateBridgeSettings: (BridgeSettings) -> Unit = {},
    onToggleThemeMode: () -> Unit = {},
    onSelectRoom: (String) -> Unit,
    onRequestCreateNewRoom: () -> Unit,
    onToggleEditMode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layoutStore = remember { com.jarvis.smarthomelauncher.core.DashboardLayoutStore }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var userMenuExpanded by remember { mutableStateOf(false) }
    var showUserManageModal by remember { mutableStateOf(false) }
    var pendingTargetUserProfile by remember { mutableStateOf<UserProfile?>(null) }
    var switchPinDraft by remember { mutableStateOf("") }
    var switchPinError by remember { mutableStateOf("") }
    var pendingOpenUserManagerAfterPin by remember { mutableStateOf(false) }
    var customPhotoTriggerNonce by remember { mutableIntStateOf(0) }
    var currentHour by remember {
        mutableIntStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            delay(30_000L)
        }
    }

    val activeProfile = bridgeSettings.activeUserProfile
    val userProfiles = bridgeSettings.userProfiles.ifEmpty { DEFAULT_USER_PROFILES }
    val effectiveGreeting = greetingMessage.ifBlank { timeOfDayGreetingText(currentHour) }

    fun applyUserProfileSwitch(nextProfile: UserProfile) {
        val updated = bridgeSettings.copy(
            activeUserProfileId = nextProfile.id,
            activeUserRole = nextProfile.role
        )
        DeviceRepository.saveBridge(context, updated)
        DeviceRepository.logAuditEvent(
            context = context,
            actorRole = nextProfile.role,
            actionType = "USER_PROFILE_SWITCH",
            targetName = nextProfile.name,
            detail = "Switched dashboard profile to ${nextProfile.name} (${nextProfile.role.label})",
            succeeded = true
        )
        onUpdateBridgeSettings(updated)
    }

    fun requestSwitchToUserProfile(targetProfile: UserProfile) {
        if (targetProfile.id == activeProfile.id) return
        val needsPinToLeaveCurrent = activeProfile.isParentalLocked || activeProfile.requirePinToSwitchOut
        val needsPinToEnterAdmin = !targetProfile.isParentalLocked &&
                targetProfile.role in listOf(UserRole.OWNER, UserRole.ADMIN) &&
                activeProfile.isParentalLocked

        if (needsPinToLeaveCurrent || needsPinToEnterAdmin) {
            pendingTargetUserProfile = targetProfile
            pendingOpenUserManagerAfterPin = false
            switchPinDraft = ""
            switchPinError = ""
        } else {
            applyUserProfileSwitch(targetProfile)
        }
    }

    fun requestOpenUserManagement() {
        if (activeProfile.isParentalLocked) {
            pendingTargetUserProfile = null
            pendingOpenUserManagerAfterPin = true
            switchPinDraft = ""
            switchPinError = ""
        } else {
            showUserManageModal = true
        }
    }

    val customPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            layoutStore.setRoomPhotoUri(context, "room_uri_${selectedRoom.lowercase(Locale.getDefault())}", uri.toString())
            customPhotoTriggerNonce++
        }
    }

    var loadedRoomBitmap by remember(selectedRoom, customPhotoTriggerNonce) {
        mutableStateOf<ImageBitmap?>(null)
    }

    LaunchedEffect(selectedRoom, customPhotoTriggerNonce) {
        val roomKey = "room_uri_${selectedRoom.lowercase(Locale.getDefault())}"
        val customUri = layoutStore.getRoomPhotoUri(context, roomKey).orEmpty()
        val targetSource = customUri.ifBlank { defaultPhotoUrlForRoom(selectedRoom) }
        val bmp = PhotoSlideshowResolver.loadBitmap(context, targetSource)
        loadedRoomBitmap = bmp
    }

    val allRoomChoices = remember(availableRooms) {
        val effectiveRooms = availableRooms.ifEmpty { DEFAULT_ROOMS }
        (listOf("All Rooms") + effectiveRooms)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = {
                    if (!isEditMode) {
                        dropdownExpanded = true
                    }
                },
                onLongClick = { onToggleEditMode() }
            )
    ) {
        // 1. Crossfading Room Visual (Rich Architectural Room Scene + High-Res Photo Bitmap)
        Crossfade(
            targetState = selectedRoom to loadedRoomBitmap,
            animationSpec = tween(durationMillis = 550),
            label = "RoomPhotoCrossfade"
        ) { (roomName, bitmap) ->
            Box(modifier = Modifier.fillMaxSize()) {
                MoodyRoomArchitecturalCanvas(
                    roomName = roomName,
                    modifier = Modifier.fillMaxSize()
                )
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = roomName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // 2. Top Vignette
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.46f)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0A0C0F).copy(alpha = 0.80f),
                            Color.Black.copy(alpha = 0.34f),
                            Color.Transparent
                        )
                    )
                )
        )

        val defaultAvatarPresets = remember {
            listOf(
                Triple("DEFAULT_1", "Mint Executive", Icons.Outlined.Person to 0xFFA3F0C1L),
                Triple("DEFAULT_2", "Cyan Pilot", Icons.Outlined.Face to 0xFF00C6FFL),
                Triple("DEFAULT_3", "Amber Creator", Icons.Outlined.EmojiEmotions to 0xFFFF9E44L),
                Triple("DEFAULT_4", "Royal Violet", Icons.Outlined.Psychology to 0xFFC084FCL),
                Triple("DEFAULT_5", "Coral Guardian", Icons.Outlined.Mood to 0xFFFF6B6BL),
                Triple("DEFAULT_6", "Emerald Star", Icons.Outlined.SupportAgent to 0xFF00E676L)
            )
        }

        var avatarUpdateNonce by remember { mutableIntStateOf(0) }
        var loadedAvatarBitmap by remember(activeProfile.id, activeProfile.avatarUriOrPreset, avatarUpdateNonce) {
            mutableStateOf<ImageBitmap?>(null)
        }
        LaunchedEffect(activeProfile.id, activeProfile.avatarUriOrPreset, avatarUpdateNonce) {
            val uriStr = activeProfile.avatarUriOrPreset
            loadedAvatarBitmap = if (uriStr.startsWith("content://") || uriStr.startsWith("file://") || uriStr.startsWith("http")) {
                PhotoSlideshowResolver.loadBitmap(context, uriStr)
            } else {
                null
            }
        }

        val customAvatarPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                val updatedProfiles = userProfiles.map { prof ->
                    if (prof.id == activeProfile.id) {
                        prof.copy(avatarUriOrPreset = uri.toString())
                    } else prof
                }
                val updatedBridge = bridgeSettings.copy(userProfiles = updatedProfiles)
                DeviceRepository.saveBridge(context, updatedBridge)
                onUpdateBridgeSettings(updatedBridge)
                avatarUpdateNonce++
            }
        }

        // Live Tablet Battery Percentage & Charging State
        var batteryPct by remember { mutableIntStateOf(100) }
        var isBatteryCharging by remember { mutableStateOf(false) }
        DisposableEffect(context) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent == null) return
                    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                    val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                    if (level >= 0 && scale > 0) {
                        batteryPct = ((level * 100f) / scale.toFloat()).roundToInt().coerceIn(0, 100)
                    }
                    isBatteryCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == android.os.BatteryManager.BATTERY_STATUS_FULL
                }
            }
            val sticky = context.registerReceiver(receiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) {
                val level = sticky.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = sticky.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                val status = sticky.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                if (level >= 0 && scale > 0) {
                    batteryPct = ((level * 100f) / scale.toFloat()).roundToInt().coerceIn(0, 100)
                }
                isBatteryCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == android.os.BatteryManager.BATTERY_STATUS_FULL
            }
            onDispose {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }

        // 3. Top-Left Square User Avatar Selector + Greeting Underneath
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 10.dp, top = 10.dp, end = 82.dp)
        ) {
            val matchedPreset = defaultAvatarPresets.firstOrNull { it.first == activeProfile.avatarUriOrPreset }
            val userBadgeColor = Color(
                ((matchedPreset?.third?.second ?: activeProfile.avatarColorHex) or 0xFF000000L).toInt()
            )
            val presetIcon = matchedPreset?.third?.first ?: Icons.Outlined.Person

            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Square-shaped User Avatar & Profile Selector Box
                Surface(
                    color = Color(0xFF10141A).copy(alpha = 0.88f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        width = 1.2.dp,
                        color = if (activeProfile.isParentalLocked) {
                            GhAccentWarm.copy(alpha = 0.82f)
                        } else {
                            userBadgeColor.copy(alpha = 0.72f)
                        }
                    ),
                    modifier = Modifier.clickable {
                        if (!isEditMode) {
                            userMenuExpanded = true
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(userBadgeColor.copy(alpha = 0.24f))
                                .border(1.dp, userBadgeColor.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                        ) {
                            val bmp = loadedAvatarBitmap
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = activeProfile.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = if (activeProfile.isParentalLocked) Icons.Outlined.Lock else presetIcon,
                                    contentDescription = activeProfile.name,
                                    tint = if (activeProfile.isParentalLocked) GhAccentWarm else userBadgeColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = activeProfile.name,
                                    color = Color.White,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = GhFont,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Switch User",
                                    tint = Color.White.copy(alpha = 0.82f),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            Text(
                                text = activeProfile.role.label,
                                color = if (activeProfile.isParentalLocked) GhAccentWarm else userBadgeColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GhFont,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Greeting Message ("Good Morning" / "Good Afternoon" / "Good Evening") Underneath
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0E1218).copy(alpha = 0.72f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Icon(
                        imageVector = when (currentHour) {
                            in 5..11 -> Icons.Outlined.WbSunny
                            in 12..17 -> Icons.Outlined.LightMode
                            else -> Icons.Outlined.NightsStay
                        },
                        contentDescription = null,
                        tint = userBadgeColor,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = effectiveGreeting,
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GhFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            DropdownMenu(
                expanded = userMenuExpanded,
                onDismissRequest = { userMenuExpanded = false },
                modifier = Modifier.background(GhTileSurfaceElevated)
            ) {
                userProfiles.forEach { profile ->
                    val isCurrent = profile.id == activeProfile.id
                    val pPreset = defaultAvatarPresets.firstOrNull { it.first == profile.avatarUriOrPreset }
                    val pColor = Color(((pPreset?.third?.second ?: profile.avatarColorHex) or 0xFF000000L).toInt())
                    val pIcon = pPreset?.third?.first ?: Icons.Outlined.Person
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(pColor.copy(alpha = 0.25f))
                                        .border(1.dp, pColor, RoundedCornerShape(6.dp))
                                ) {
                                    Icon(
                                        imageVector = pIcon,
                                        contentDescription = null,
                                        tint = pColor,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = profile.name,
                                        color = if (isCurrent) GhAccentGreen else GhTextPrimary,
                                        fontSize = 12.5.sp,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                        fontFamily = GhFont
                                    )
                                    Text(
                                        text = if (profile.isParentalLocked) "${profile.role.label} \u2022 Parental Lock ON" else profile.role.label,
                                        color = if (profile.isParentalLocked) GhAccentWarm else GhTextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                                if (profile.isParentalLocked) {
                                    Icon(
                                        imageVector = Icons.Outlined.Lock,
                                        contentDescription = "Parental Lock",
                                        tint = GhAccentWarm,
                                        modifier = Modifier.size(14.dp)
                                    )
                                } else if (isCurrent) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = GhAccentGreen,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        },
                        onClick = {
                            userMenuExpanded = false
                            requestSwitchToUserProfile(profile)
                        }
                    )
                }
                HorizontalDivider(color = GhCardBorder)
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Choose Default Avatar for ${activeProfile.name}:",
                                color = GhTextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                defaultAvatarPresets.forEach { (presetKey, _, iconAndColor) ->
                                    val (ic, hex) = iconAndColor
                                    val c = Color((hex or 0xFF000000L).toInt())
                                    val isSel = activeProfile.avatarUriOrPreset == presetKey
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(c.copy(alpha = if (isSel) 0.38f else 0.18f))
                                            .border(
                                                width = if (isSel) 1.5.dp else 0.8.dp,
                                                color = if (isSel) Color.White else c,
                                                shape = RoundedCornerShape(7.dp)
                                            )
                                            .clickable {
                                                val updatedProfiles = userProfiles.map { prof ->
                                                    if (prof.id == activeProfile.id) {
                                                        prof.copy(
                                                            avatarUriOrPreset = presetKey,
                                                            avatarColorHex = hex
                                                        )
                                                    } else prof
                                                }
                                                val updatedBridge = bridgeSettings.copy(userProfiles = updatedProfiles)
                                                DeviceRepository.saveBridge(context, updatedBridge)
                                                onUpdateBridgeSettings(updatedBridge)
                                                avatarUpdateNonce++
                                            }
                                    ) {
                                        Icon(
                                            imageVector = ic,
                                            contentDescription = null,
                                            tint = c,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    },
                    onClick = {}
                )
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AddPhotoAlternate,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Upload Custom Avatar Photo...",
                                color = GhTextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GhFont
                            )
                        }
                    },
                    onClick = {
                        userMenuExpanded = false
                        customAvatarPickerLauncher.launch(arrayOf("image/*"))
                    }
                )
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ManageAccounts,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Manage Users & Parental Locks...",
                                color = GhAccentGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            )
                        }
                    },
                    onClick = {
                        userMenuExpanded = false
                        requestOpenUserManagement()
                    }
                )
            }
        }

        // 4. Top-Right: Compact Dark / Light Theme Toggle Pill + Battery Status Underneath
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 10.dp, top = 10.dp)
        ) {
            Surface(
                color = Color(0xFF141820).copy(alpha = 0.85f),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.55f)),
                modifier = Modifier
                    .height(28.dp)
                    .clickable {
                        if (!isEditMode) {
                            onToggleThemeMode()
                        }
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = if (isDarkMode) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                        contentDescription = "Toggle Dark or Light Mode",
                        tint = if (isDarkMode) GhAccentGreen else Color(0xFFFFB300),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = if (isDarkMode) "Dark" else "Light",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GhFont,
                        maxLines = 1
                    )
                }
            }

            // Small Battery Icon & Percentage Underneath Dark / Light Selector
            val batteryTint = when {
                isBatteryCharging -> GhAccentGreen
                batteryPct <= 15 -> GhAccentRed
                batteryPct <= 30 -> GhAccentWarm
                else -> GhAccentGreen
            }
            val batteryIcon = when {
                isBatteryCharging -> Icons.Outlined.BatteryChargingFull
                batteryPct <= 15 -> Icons.Outlined.BatteryAlert
                batteryPct <= 50 -> Icons.Outlined.Battery4Bar
                else -> Icons.Outlined.BatteryFull
            }
            Surface(
                color = Color(0xFF141820).copy(alpha = 0.82f),
                shape = RoundedCornerShape(50),
                border = BorderStroke(0.8.dp, batteryTint.copy(alpha = 0.50f)),
                modifier = Modifier.height(22.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = batteryIcon,
                        contentDescription = "Battery $batteryPct%",
                        tint = batteryTint,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "$batteryPct%",
                        color = Color.White,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GhFont,
                        maxLines = 1
                    )
                }
            }
        }

        // 5. Smooth Dark Bottom Gradient Vignette
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.54f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.68f),
                            Color(0xFF0A0C0F).copy(alpha = 0.92f)
                        )
                    )
                )
        )

        // 6. Bottom-Left Room Selector Dropdown
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!isEditMode) {
                            dropdownExpanded = true
                        }
                    }
            ) {
                Icon(
                    imageVector = if (selectedRoom.equals("All Rooms", ignoreCase = true))
                        Icons.Outlined.GridView else Icons.Outlined.Room,
                    contentDescription = null,
                    tint = GhAccentGreen,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = selectedRoom,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GhFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Select Room",
                    tint = Color.White.copy(alpha = 0.90f),
                    modifier = Modifier.size(18.dp)
                )
            }

            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
                modifier = Modifier.background(GhTileSurfaceElevated)
            ) {
                allRoomChoices.forEach { roomOption ->
                    val isSelected = selectedRoom.equals(roomOption, ignoreCase = true)
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (roomOption.equals("All Rooms", true))
                                        Icons.Outlined.GridView else Icons.Outlined.Room,
                                    contentDescription = null,
                                    tint = if (isSelected) GhAccentGreen else GhTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = roomOption,
                                    color = if (isSelected) GhAccentGreen else GhTextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontFamily = GhFont
                                )
                            }
                        },
                        onClick = {
                            dropdownExpanded = false
                            onSelectRoom(roomOption)
                        }
                    )
                }
                HorizontalDivider(color = GhCardBorder)
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "+ Create New Room...",
                                color = GhAccentGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GhFont
                            )
                        }
                    },
                    onClick = {
                        dropdownExpanded = false
                        onRequestCreateNewRoom()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Image,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Change '$selectedRoom' Photo...",
                                color = GhTextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = GhFont
                            )
                        }
                    },
                    onClick = {
                        dropdownExpanded = false
                        customPhotoPickerLauncher.launch(arrayOf("image/*"))
                    }
                )
            }
        }
    }

    // Point 13: Parental Lock PIN Verification Dialog when switching out of a Child/Restricted profile
    if (pendingTargetUserProfile != null || pendingOpenUserManagerAfterPin) {
        val target = pendingTargetUserProfile
        val expectedPin = activeProfile.customPinCode
            .ifBlank { target?.customPinCode.orEmpty() }
            .ifBlank { bridgeSettings.rolePinCode }
            .trim()
        val isSettingInitialPin = expectedPin.isBlank()

        AlertDialog(
            onDismissRequest = {
                pendingTargetUserProfile = null
                pendingOpenUserManagerAfterPin = false
            },
            containerColor = GhTileSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = GhAccentWarm,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            isSettingInitialPin -> "Set Household Parental Lock PIN (4\u20138 Digits)"
                            target != null -> "Parental Lock: Enter PIN to Switch to ${target.name}"
                            else -> "Parental Lock: Enter PIN to Manage Users"
                        },
                        color = GhTextPrimary,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isSettingInitialPin) {
                            "Profile '${activeProfile.name}' is protected by Parental Lock, but no household PIN has been set yet. Create a 4\u20138 digit PIN to continue."
                        } else {
                            "Profile '${activeProfile.name}' is protected by Parental Lock. Enter your household PIN to continue."
                        },
                        color = GhTextSecondary,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = switchPinDraft,
                        onValueChange = {
                            switchPinDraft = it.filter { c -> c.isDigit() }.take(8)
                            switchPinError = ""
                        },
                        label = { Text("Security PIN Code (4\u20138 Digits)", fontSize = 12.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = claraFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (switchPinError.isNotBlank()) {
                        Text(
                            text = switchPinError,
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
                            if (switchPinDraft.length >= 4) {
                                val hashedPin = DeviceRepository.hashPinCode(switchPinDraft)
                                val updatedBridge = bridgeSettings.copy(rolePinCode = hashedPin)
                                DeviceRepository.saveBridge(context, updatedBridge)
                                onUpdateBridgeSettings(updatedBridge)
                                val chosenTarget = pendingTargetUserProfile
                                val shouldOpenMgr = pendingOpenUserManagerAfterPin
                                pendingTargetUserProfile = null
                                pendingOpenUserManagerAfterPin = false
                                if (chosenTarget != null) {
                                    applyUserProfileSwitch(chosenTarget)
                                } else if (shouldOpenMgr) {
                                    showUserManageModal = true
                                }
                            } else {
                                switchPinError = "Please enter a 4 to 8 digit PIN."
                            }
                        } else if (DeviceRepository.verifyPinCode(switchPinDraft, expectedPin)) {
                            val chosenTarget = pendingTargetUserProfile
                            val shouldOpenMgr = pendingOpenUserManagerAfterPin
                            pendingTargetUserProfile = null
                            pendingOpenUserManagerAfterPin = false
                            if (chosenTarget != null) {
                                applyUserProfileSwitch(chosenTarget)
                            } else if (shouldOpenMgr) {
                                showUserManageModal = true
                            }
                        } else {
                            switchPinError = "Incorrect Parental Lock PIN."
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
                        pendingTargetUserProfile = null
                        pendingOpenUserManagerAfterPin = false
                    }
                ) {
                    Text("Cancel", color = GhTextSecondary)
                }
            }
        )
    }

    if (showUserManageModal) {
        UserProfileManagementModal(
            bridgeSettings = bridgeSettings,
            onSaveBridgeSettings = { updatedBridge ->
                DeviceRepository.saveBridge(context, updatedBridge)
                onUpdateBridgeSettings(updatedBridge)
            },
            onDismiss = { showUserManageModal = false }
        )
    }
}

@Composable
fun SfHouseRoomPhotoSelectorCard(
    selectedRoom: String,
    availableRooms: List<String>,
    onSelectRoom: (String) -> Unit,
    onRequestCreateNewRoom: () -> Unit,
    onToggleEditMode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    SfHouseSunkenTileContainer(
        outerCornerRadius = 22.dp,
        innerCornerRadius = 16.dp,
        sunkenInsetPadding = 6.dp,
        modifier = modifier.fillMaxSize()
    ) {
        SfHouseRoomSelectorPanel(
            selectedRoom = selectedRoom,
            availableRooms = availableRooms,
            onSelectRoom = onSelectRoom,
            onRequestCreateNewRoom = onRequestCreateNewRoom,
            onToggleEditMode = onToggleEditMode,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
internal fun MoodyRoomArchitecturalCanvas(
    roomName: String,
    modifier: Modifier = Modifier
) {
    val lower = roomName.lowercase(Locale.getDefault())
    val (wallPrimary, neonGlow, sofaPrimary) = when {
        lower.contains("bed") -> Triple(
            Color(0xFF0F3842),
            Color(0xFF00F5D4),
            Color(0xFFA85428)
        )
        lower.contains("living") -> Triple(
            Color(0xFF133340),
            Color(0xFF00E5FF),
            Color(0xFFB85D2B)
        )
        lower.contains("kitchen") -> Triple(
            Color(0xFF1B2E36),
            Color(0xFFFFB703),
            Color(0xFF8D5B4C)
        )
        lower.contains("office") -> Triple(
            Color(0xFF142838),
            Color(0xFF7C4DFF),
            Color(0xFF9C522B)
        )
        lower.contains("garden") || lower.contains("driveway") -> Triple(
            Color(0xFF11352B),
            Color(0xFF00E676),
            Color(0xFF9E6238)
        )
        else -> Triple(
            Color(0xFF103740),
            Color(0xFF00F0FF),
            Color(0xFFA65226)
        )
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFF0A1E24),
                    wallPrimary,
                    Color(0xFF163D47),
                    Color(0xFF09171B)
                )
            )
        )

        val neonCenter = Offset(w * 0.56f, h * 0.25f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    neonGlow.copy(alpha = 0.65f),
                    neonGlow.copy(alpha = 0.22f),
                    Color.Transparent
                ),
                center = neonCenter,
                radius = w * 0.34f
            ),
            radius = w * 0.34f,
            center = neonCenter
        )

        val winLeft = w * 0.31f
        val winTop = h * 0.06f
        val winW = w * 0.16f
        val winH = h * 0.36f
        drawRoundRect(
            color = Color(0xFFE6ECEF),
            topLeft = Offset(winLeft, winTop),
            size = Size(winW, winH),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        for (b in 1..9) {
            val by = winTop + (winH * b / 10f)
            drawLine(
                color = Color(0xFFB0BEC5),
                start = Offset(winLeft + 3.dp.toPx(), by),
                end = Offset(winLeft + winW - 3.dp.toPx(), by),
                strokeWidth = 1.5.dp.toPx()
            )
        }

        val cursorPath = Path().apply {
            moveTo(w * 0.49f, h * 0.14f)
            lineTo(w * 0.54f, h * 0.26f)
            lineTo(w * 0.51f, h * 0.27f)
            lineTo(w * 0.52f, h * 0.31f)
            lineTo(w * 0.49f, h * 0.28f)
            close()
        }
        drawPath(
            path = cursorPath,
            color = Color.White,
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        )

        val sofaLeft = w * 0.27f
        val sofaTop = h * 0.44f
        val sofaW = w * 0.36f
        val sofaH = h * 0.29f
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(sofaPrimary, Color(0xFF6B3215)),
                startY = sofaTop,
                endY = sofaTop + sofaH
            ),
            topLeft = Offset(sofaLeft, sofaTop),
            size = Size(sofaW, sofaH),
            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFFC86A37), Color(0xFF783817)),
                startY = sofaTop + sofaH * 0.48f,
                endY = sofaTop + sofaH
            ),
            topLeft = Offset(sofaLeft - w * 0.01f, sofaTop + sofaH * 0.48f),
            size = Size(sofaW + w * 0.02f, sofaH * 0.48f),
            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
        )
        drawRoundRect(
            color = Color(0xFFD7CCC8),
            topLeft = Offset(sofaLeft + w * 0.02f, sofaTop + sofaH * 0.12f),
            size = Size(w * 0.08f, sofaH * 0.42f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        drawRoundRect(
            color = neonGlow.copy(alpha = 0.85f),
            topLeft = Offset(sofaLeft + sofaW * 0.64f, sofaTop + sofaH * 0.24f),
            size = Size(w * 0.07f, sofaH * 0.26f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )

        drawRect(
            color = Color(0xFF071317),
            topLeft = Offset(0f, 0f),
            size = Size(w * 0.22f, h)
        )
        drawRect(
            color = neonGlow.copy(alpha = 0.22f),
            topLeft = Offset(w * 0.02f, h * 0.05f),
            size = Size(w * 0.15f, h * 0.65f)
        )

        val plantCenter = Offset(w * 0.74f, h * 0.62f)
        drawCircle(
            color = Color(0xFF0A221E),
            radius = w * 0.10f,
            center = plantCenter
        )
        drawCircle(
            color = Color(0xFF0E2E28),
            radius = w * 0.08f,
            center = Offset(plantCenter.x - w * 0.03f, plantCenter.y - h * 0.10f)
        )
    }
}
