package com.jarvis.smarthomelauncher

import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

enum class SettingsSidebarSection(val label: String, val subtitle: String) {
    DEVICE_CONFIG("Add & Link Devices", "Guided single-device setup wizard"),
    CUSTOMISE_DASHBOARD("Manage Devices", "Show, unhide, group & edit tiles"),
    AUTOMATIONS("Automations & Schedules", "Routines, schedules & rules"),
    HOME_HEALTH("Home Health & Diagnostics", "Self-healing, latency, audit & backup"),
    APP_SETTINGS("Application Settings", "RGB colour wheel, DPI, templates & voice")
}

enum class SetupPageTab(val label: String) {
    AUTO_DISCOVER("Add a Device"),
    ADD_NEW_DEVICE("Guided Device Setup"),
    CONNECTED_DEVICES("Manage Tiles")
}

fun TileType.isCameraType(): Boolean =
    this == TileType.IMOU_CAMERA ||
            this == TileType.EZVIZ_DOORBELL ||
            this == TileType.NEST_DOORBELL ||
            this == TileType.RING_CAMERA

@Composable
fun ResponsiveSplitRow(
    isStacked: Boolean,
    horizontalSpacing: Dp = 12.dp,
    verticalSpacing: Dp = 10.dp,
    firstWeight: Float = 1f,
    secondWeight: Float = 1f,
    firstContent: @Composable (Modifier) -> Unit,
    secondContent: @Composable (Modifier) -> Unit
) {
    if (isStacked) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing)
        ) {
            firstContent(Modifier.fillMaxWidth())
            secondContent(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            firstContent(Modifier.weight(firstWeight))
            secondContent(Modifier.weight(secondWeight))
        }
    }
}

/**
 * Wrap-height recessed sunken card container with Ultra-HD gradient & top specular gloss sheen
 * safe for use inside LazyColumn items and non-weighted Column headers/footers.
 */
@Composable
internal fun SunkenWrapHeightCard(
    modifier: Modifier = Modifier,
    outerCornerRadius: Dp = 20.dp,
    innerCornerRadius: Dp = 15.dp,
    sunkenInsetPadding: Dp = 5.dp,
    innerBorderColor: Color = GhCardBorder,
    innerBackgroundColor: Color = GhTileSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = ClaraThemeManager.isDarkMode
    val cardTopTint = if (innerBackgroundColor != GhTileSurface) {
        innerBackgroundColor
    } else if (isDark) {
        Color(0xFF2C313B)
    } else {
        Color(0xFFFFFFFF)
    }
    val cardBottomTint = if (innerBackgroundColor != GhTileSurface) {
        innerBackgroundColor.copy(alpha = 0.94f)
    } else if (isDark) {
        Color(0xFF1F2229)
    } else {
        Color(0xFFEEF3F9)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(outerCornerRadius))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        GhSunkenWellShadow,
                        GhSunkenWell
                    )
                )
            )
            .border(
                width = 1.2.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.78f),
                        GhSunkenWellHighlight.copy(alpha = 0.65f)
                    )
                ),
                shape = RoundedCornerShape(outerCornerRadius)
            )
            .padding(sunkenInsetPadding)
    ) {
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(innerCornerRadius),
            border = BorderStroke(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        if (isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.90f),
                        innerBorderColor
                    )
                )
            ),
            shadowElevation = if (isDark) 4.dp else 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(innerCornerRadius))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(cardTopTint, cardBottomTint)
                    )
                )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content
            )
        }
    }
}

// ---------------------------------------------------------------------------
// INTERACTIVE 360° RGB COLOUR WHEEL + BRIGHTNESS SLIDER + LIVE HEX PREVIEW
// ---------------------------------------------------------------------------
@Composable
fun InteractiveRgbColorWheelPicker(
    currentColorArgb: Long,
    selectedPreset: AppAccentColorPreset,
    isCompactMobile: Boolean,
    onColorSelected: (Long, AppAccentColorPreset?) -> Unit
) {
    val initialHsv = remember(currentColorArgb) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(currentColorArgb.toInt(), hsv)
        hsv
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0].coerceIn(0f, 360f)) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1].coerceIn(0f, 1f)) }
    var brightnessValue by remember { mutableFloatStateOf(initialHsv[2].coerceIn(0.25f, 1f)) }

    LaunchedEffect(currentColorArgb) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(currentColorArgb.toInt(), hsv)
        hue = hsv[0].coerceIn(0f, 360f)
        saturation = hsv[1].coerceIn(0f, 1f)
        brightnessValue = hsv[2].coerceIn(0.25f, 1f)
    }

    val liveArgb = remember(hue, saturation, brightnessValue) {
        val intColor = android.graphics.Color.HSVToColor(
            floatArrayOf(hue, saturation, brightnessValue)
        )
        intColor.toLong() and 0xFFFFFFFFL
    }
    val liveColor = Color(liveArgb.toInt())
    val hexString = remember(liveArgb) {
        "#%06X".format(liveArgb and 0xFFFFFFL)
    }

    val sweepColors = remember {
        listOf(
            Color(0xFFFF0000),
            Color(0xFFFFFF00),
            Color(0xFF00FF00),
            Color(0xFF00FFFF),
            Color(0xFF0000FF),
            Color(0xFFFF00FF),
            Color(0xFFFF0000)
        )
    }

    fun updateFromWheelTouch(offset: Offset, wheelSizePx: Float) {
        val radius = wheelSizePx / 2f
        if (radius <= 0f) return
        val dx = offset.x - radius
        val dy = offset.y - radius
        val dist = hypot(dx, dy)
        val newSat = (dist / radius).coerceIn(0f, 1f)
        val angleDeg = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()) + 360f) % 360f
        hue = angleDeg
        saturation = newSat
        val updatedInt = android.graphics.Color.HSVToColor(
            floatArrayOf(angleDeg, newSat, brightnessValue)
        )
        onColorSelected(updatedInt.toLong() and 0xFFFFFFFFL, null)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ResponsiveSplitRow(
            isStacked = isCompactMobile,
            firstWeight = 0.95f,
            secondWeight = 1.25f,
            firstContent = { mod ->
                Box(
                    modifier = mod.padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val wheelSize = if (isCompactMobile) 168.dp else 184.dp
                    Canvas(
                        modifier = Modifier
                            .size(wheelSize)
                            .clip(CircleShape)
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    updateFromWheelTouch(
                                        offset = offset,
                                        wheelSizePx = size.width.toFloat()
                                    )
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    updateFromWheelTouch(
                                        offset = change.position,
                                        wheelSizePx = size.width.toFloat()
                                    )
                                }
                            }
                    ) {
                        val radius = size.minDimension / 2f

                        // 1. 360-degree Hue sweep gradient
                        drawCircle(
                            brush = Brush.sweepGradient(
                                colors = sweepColors,
                                center = center
                            ),
                            radius = radius,
                            center = center
                        )

                        // 2. Radial saturation gradient (white center -> saturated edge)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White, Color.Transparent),
                                center = center,
                                radius = radius
                            ),
                            radius = radius,
                            center = center
                        )

                        // 3. Brightness dimming overlay when brightness slider < 100%
                        val dimAlpha = (1f - brightnessValue).coerceIn(0f, 0.75f)
                        if (dimAlpha > 0.01f) {
                            drawCircle(
                                color = Color.Black.copy(alpha = dimAlpha),
                                radius = radius,
                                center = center
                            )
                        }

                        // Outer bezel ring
                        drawCircle(
                            color = Color.White.copy(alpha = 0.28f),
                            radius = radius - 1.dp.toPx(),
                            center = center,
                            style = Stroke(width = 2.dp.toPx())
                        )

                        // 4. Current selected colour thumb indicator
                        val rad = Math.toRadians(hue.toDouble())
                        val thumbDist = saturation * (radius - 8.dp.toPx())
                        val thumbCenter = Offset(
                            x = center.x + (cos(rad) * thumbDist).toFloat(),
                            y = center.y + (sin(rad) * thumbDist).toFloat()
                        )
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.65f),
                            radius = 12.dp.toPx(),
                            center = thumbCenter
                        )
                        drawCircle(
                            color = liveColor,
                            radius = 9.5.dp.toPx(),
                            center = thumbCenter
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 9.5.dp.toPx(),
                            center = thumbCenter,
                            style = Stroke(width = 2.4.dp.toPx())
                        )
                    }
                }
            },
            secondContent = { mod ->
                Column(
                    modifier = mod,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Live Hex Preview + Reset Default button
                    Surface(
                        color = GhTileSurfaceElevated,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.5.dp, liveColor.copy(alpha = 0.75f)),
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
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(liveColor)
                                        .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                                )
                                Column {
                                    Text(
                                        text = "Live Accent Colour: $hexString",
                                        color = GhTextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = GhFont
                                    )
                                    Text(
                                        text = "Drag anywhere on the 360\u00B0 RGB wheel to tint all buttons & active tiles",
                                        color = GhTextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = GhFont
                                    )
                                }
                            }
                            TextButton(
                                onClick = {
                                    onColorSelected(
                                        DEFAULT_ACCENT_COLOR_ARGB,
                                        AppAccentColorPreset.MINT_GREEN
                                    )
                                }
                            ) {
                                Text(
                                    text = "Reset Default",
                                    color = liveColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Brightness / Value Slider
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Accent Brightness / Intensity",
                                color = GhTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GhFont
                            )
                            Text(
                                text = "${(brightnessValue * 100).roundToInt()}%",
                                color = liveColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            )
                        }
                        Slider(
                            value = brightnessValue,
                            onValueChange = { nextVal ->
                                brightnessValue = nextVal.coerceIn(0.25f, 1f)
                                val updatedInt = android.graphics.Color.HSVToColor(
                                    floatArrayOf(hue, saturation, brightnessValue)
                                )
                                onColorSelected(updatedInt.toLong() and 0xFFFFFFFFL, null)
                            },
                            valueRange = 0.25f..1f,
                            modifier = Modifier.height(26.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = liveColor,
                                inactiveTrackColor = GhTileSurfaceElevated
                            )
                        )
                    }

                    // Quick-Pick Swatch Row to jump the RGB wheel to popular presets
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(AppAccentColorPreset.entries) { preset ->
                            val presetArgb = preset.darkHex or 0xFF000000L
                            val isSel = (liveArgb and 0xFFFFFFL) == (presetArgb and 0xFFFFFFL) ||
                                    (selectedPreset == preset && (liveArgb and 0xFFFFFFL) == (preset.darkHex and 0xFFFFFFL))
                            val swatchColor = Color(presetArgb.toInt())
                            Surface(
                                color = if (isSel) Color(preset.activeSurfaceDarkHex) else GhTileSurfaceElevated,
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(
                                    width = if (isSel) 1.6.dp else 1.dp,
                                    color = if (isSel) swatchColor else GhCardBorder
                                ),
                                modifier = Modifier
                                    .height(32.dp)
                                    .clickable {
                                        onColorSelected(presetArgb, preset)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(13.dp)
                                            .clip(CircleShape)
                                            .background(swatchColor)
                                            .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                                    )
                                    Text(
                                        text = preset.label.substringBefore(" ("),
                                        color = if (isSel) swatchColor else GhTextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                        fontFamily = GhFont
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// SMART ELECTRICITY TARIFF RATES (£/kWh) & HOME PROFILE CARD
// ---------------------------------------------------------------------------
@Composable
fun ElectricityTariffAndProfileCard(
    tariffDayRateText: String,
    onTariffDayRateChange: (String) -> Unit,
    tariffNightRateText: String,
    onTariffNightRateChange: (String) -> Unit,
    tariffStandingChargeText: String,
    onTariffStandingChargeChange: (String) -> Unit,
    compactInputTextStyle: TextStyle,
    modifier: Modifier = Modifier
) {
    SunkenWrapHeightCard(
        outerCornerRadius = 20.dp,
        innerCornerRadius = 15.dp,
        sunkenInsetPadding = 5.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Smart Electricity Tariff Rates (\u00A3/kWh) & Home Profile",
                color = GhTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = GhFont
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = tariffDayRateText,
                    onValueChange = onTariffDayRateChange,
                    label = { Text("Day Rate (\u00A3/kWh)", fontSize = 11.sp) },
                    textStyle = compactInputTextStyle,
                    shape = RoundedCornerShape(14.dp),
                    colors = claraFieldColors(),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = tariffNightRateText,
                    onValueChange = onTariffNightRateChange,
                    label = { Text("Off-Peak Night (\u00A3/kWh)", fontSize = 11.sp) },
                    textStyle = compactInputTextStyle,
                    shape = RoundedCornerShape(14.dp),
                    colors = claraFieldColors(),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = tariffStandingChargeText,
                    onValueChange = onTariffStandingChargeChange,
                    label = { Text("Standing (\u00A3/day)", fontSize = 11.sp) },
                    textStyle = compactInputTextStyle,
                    shape = RoundedCornerShape(14.dp),
                    colors = claraFieldColors(),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// POINT 13: USER PROFILES, PARENTAL LOCKS, EDIT-MODE SHIELD & PIN SECURITY CARD
// ---------------------------------------------------------------------------
@Composable
fun UserRolesAndPinSecurityCard(
    context: Context,
    activeUserRole: UserRole,
    onActiveUserRoleChange: (UserRole) -> Unit,
    rolePinCode: String,
    onRolePinCodeChange: (String) -> Unit,
    requirePinForAdminAndVehicles: Boolean,
    onRequirePinChange: (Boolean) -> Unit,
    isCompactMobile: Boolean,
    compactInputTextStyle: TextStyle,
    onStatusBanner: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val securityAuditLogs by JarvisLiveBridge.securityAuditLogsFlow.collectAsState()
    var showAuditLogPanel by remember { mutableStateOf(false) }
    var showManageUsersModal by remember { mutableStateOf(false) }
    var persistedBridge by remember { mutableStateOf(DeviceRepository.loadBridge(context)) }

    val profiles = remember(persistedBridge.userProfiles) {
        persistedBridge.userProfiles.ifEmpty { DEFAULT_USER_PROFILES }
    }
    val activeProfile = remember(persistedBridge.activeUserProfileId, profiles, activeUserRole) {
        profiles.firstOrNull { it.id == persistedBridge.activeUserProfileId }
            ?: profiles.firstOrNull { it.role == activeUserRole }
            ?: profiles.first()
    }

    SunkenWrapHeightCard(
        outerCornerRadius = 20.dp,
        innerCornerRadius = 15.dp,
        sunkenInsetPadding = 5.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Household Profiles, Parental Locks & Edit-Mode Shield",
                        color = GhTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GhFont
                    )
                    Text(
                        text = "Parental-locked profiles require a PIN to switch out of a restricted/child profile and block entering Edit Mode, deleting tiles/cards, unlocking vehicles/doors, or opening Control Center.",
                        color = GhTextSecondary,
                        fontSize = 11.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { showManageUsersModal = true },
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = GhAccentGreen.copy(alpha = 0.20f),
                            contentColor = GhAccentGreen
                        )
                    ) {
                        Icon(Icons.Outlined.ManageAccounts, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Manage Users (${profiles.size})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    FilledTonalButton(
                        onClick = { showAuditLogPanel = !showAuditLogPanel },
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = GhTileSurfaceElevated,
                            contentColor = GhAccentBlue
                        )
                    ) {
                        Icon(Icons.Outlined.Security, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (showAuditLogPanel) "Hide Audit Log" else "Audit Log (${securityAuditLogs.size})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Household User Profiles & Per-User Parental Lock Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                items(profiles, key = { it.id }) { prof ->
                    val isCurrent = prof.id == activeProfile.id
                    val pColor = Color((prof.avatarColorHex or 0xFF000000L).toInt())
                    Surface(
                        color = if (isCurrent) GhTileGreenActive else GhTileSurfaceElevated,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            1.2.dp,
                            when {
                                isCurrent -> GhAccentGreen
                                prof.isParentalLocked -> GhAccentWarm.copy(alpha = 0.65f)
                                else -> GhCardBorder
                            }
                        ),
                        modifier = Modifier.clickable {
                            val updatedBridge = persistedBridge.copy(
                                activeUserProfileId = prof.id,
                                activeUserRole = prof.role
                            )
                            persistedBridge = updatedBridge
                            DeviceRepository.saveBridge(context, updatedBridge)
                            onActiveUserRoleChange(prof.role)
                            onStatusBanner("Switched active profile to ${prof.name} (${if (prof.isParentalLocked) "Parental Lock ON" else prof.role.label})")
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(pColor.copy(alpha = 0.25f))
                                    .border(1.dp, pColor, CircleShape)
                            ) {
                                Text(
                                    text = prof.name.take(1).uppercase(Locale.getDefault()),
                                    color = pColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Column {
                                Text(
                                    text = prof.name,
                                    color = if (isCurrent) GhAccentGreen else GhTextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (prof.isParentalLocked) "Parental Lock ON" else prof.role.label,
                                    color = if (prof.isParentalLocked) GhAccentWarm else GhTextSecondary,
                                    fontSize = 9.5.sp
                                )
                            }
                            Icon(
                                imageVector = if (prof.isParentalLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                contentDescription = "Toggle Parental Lock",
                                tint = if (prof.isParentalLocked) GhAccentWarm else GhTextSecondary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable {
                                        val nextLocked = !prof.isParentalLocked
                                        val effectivePin = rolePinCode.trim()
                                            .ifBlank { persistedBridge.rolePinCode.trim() }
                                            .ifBlank { prof.customPinCode.trim() }
                                        if (nextLocked && effectivePin.length < 4) {
                                            onStatusBanner("Enter a 4\u20138 digit Parental Lock PIN below before enabling Parental Lock on '${prof.name}'.")
                                            return@clickable
                                        }
                                        val nextRole = if (nextLocked && prof.role in listOf(UserRole.OWNER, UserRole.ADMIN)) {
                                            UserRole.CHILD
                                        } else if (!nextLocked && prof.role == UserRole.CHILD) {
                                            UserRole.FAMILY
                                        } else {
                                            prof.role
                                        }
                                        val updatedList = profiles.map { existing ->
                                            if (existing.id == prof.id) {
                                                existing.copy(
                                                    isParentalLocked = nextLocked,
                                                    requirePinToSwitchOut = nextLocked,
                                                    role = nextRole
                                                )
                                            } else existing
                                        }
                                        val updatedBridge = persistedBridge.copy(
                                            userProfiles = updatedList,
                                            rolePinCode = rolePinCode.trim().ifBlank { persistedBridge.rolePinCode },
                                            activeUserRole = if (prof.id == activeProfile.id) nextRole else persistedBridge.activeUserRole
                                        )
                                        persistedBridge = updatedBridge
                                        DeviceRepository.saveBridge(context, updatedBridge)
                                        if (prof.id == activeProfile.id) {
                                            onActiveUserRoleChange(nextRole)
                                        }
                                        onStatusBanner("${prof.name}: Parental Lock ${if (nextLocked) "ENABLED" else "DISABLED"}")
                                    }
                            )
                        }
                    }
                }
            }

            ResponsiveSplitRow(
                isStacked = isCompactMobile,
                firstContent = { mod ->
                    CleanDropdownSelector(
                        label = "Active Dashboard Role",
                        selectedText = "${activeUserRole.label} \u2014 ${activeUserRole.subtitle}",
                        options = UserRole.entries,
                        optionLabel = { it.label },
                        optionSubtitle = { it.subtitle },
                        onOptionSelected = { nextRole ->
                            onActiveUserRoleChange(nextRole)
                            val matchedProf = profiles.firstOrNull { it.role == nextRole }
                            if (matchedProf != null) {
                                val updatedBridge = persistedBridge.copy(
                                    activeUserProfileId = matchedProf.id,
                                    activeUserRole = nextRole
                                )
                                persistedBridge = updatedBridge
                                DeviceRepository.saveBridge(context, updatedBridge)
                            }
                            DeviceRepository.logAuditEvent(
                                context = context,
                                actorRole = nextRole,
                                actionType = "ROLE_SWITCH",
                                targetName = "Dashboard Profile",
                                detail = "Switched active role to ${nextRole.label}",
                                succeeded = true
                            )
                            onStatusBanner("Active role set to ${nextRole.label}")
                        },
                        textStyle = compactInputTextStyle,
                        modifier = mod
                    )
                },
                secondContent = { mod ->
                    OutlinedTextField(
                        value = rolePinCode,
                        onValueChange = { onRolePinCodeChange(it.filter { c -> c.isDigit() }.take(8)) },
                        label = { Text("Parental Lock & Vehicle Security PIN (Encrypted)", fontSize = 11.sp) },
                        textStyle = compactInputTextStyle,
                        shape = RoundedCornerShape(14.dp),
                        colors = claraFieldColors(),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = mod
                    )
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Require PIN for Control Center, Edit Mode, Tile Deletion & Vehicle/Door Unlock",
                        color = GhTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Enforces the Parental Lock & Edit-Mode Shield across all restricted profiles (or all profiles when switched ON)",
                        color = GhTextSecondary,
                        fontSize = 11.sp
                    )
                }
                SfHouseMintPillSwitch(
                    checked = requirePinForAdminAndVehicles,
                    onCheckedChange = onRequirePinChange
                )
            }

            AnimatedVisibility(visible = showAuditLogPanel) {
                val fmt = remember { SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()) }
                Surface(
                    color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, GhCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Recent Security, Parental Lock & Vehicle Audit Events",
                                color = GhAccentGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (securityAuditLogs.isNotEmpty()) {
                                TextButton(onClick = { DeviceRepository.clearAuditLogs(context) }) {
                                    Text("Clear", fontSize = 11.sp, color = GhAccentRed)
                                }
                            }
                        }
                        if (securityAuditLogs.isEmpty()) {
                            Text(
                                text = "No audit events recorded yet.",
                                color = GhTextSecondary,
                                fontSize = 11.sp
                            )
                        } else {
                            securityAuditLogs.take(8).forEach { entry ->
                                Text(
                                    text = "${fmt.format(Date(entry.timestampEpochMs))} [${entry.actorRole.label}] ${entry.targetName}: ${entry.detail}",
                                    color = GhTextPrimary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showManageUsersModal) {
        UserProfileManagementModal(
            bridgeSettings = persistedBridge.copy(
                activeUserRole = activeUserRole,
                rolePinCode = rolePinCode,
                requirePinForAdminAndVehicles = requirePinForAdminAndVehicles
            ),
            onSaveBridgeSettings = { updated ->
                persistedBridge = updated
                DeviceRepository.saveBridge(context, updated)
                onActiveUserRoleChange(updated.activeUserRole)
                onRolePinCodeChange(updated.rolePinCode)
                onRequirePinChange(updated.requirePinForAdminAndVehicles)
                onStatusBanner("Saved household user profiles & Parental Lock settings!")
            },
            onDismiss = { showManageUsersModal = false }
        )
    }
}

// ---------------------------------------------------------------------------
// AES-256 ENCRYPTED BACKUP & ONE-TAP TABLET MIGRATION CARD
// ---------------------------------------------------------------------------
@Composable
fun EncryptedBackupAndMigrationCard(
    context: Context,
    tiles: MutableList<SmartTile>,
    automationRules: MutableList<AutomationRule>,
    compactInputTextStyle: TextStyle,
    onStatusBanner: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var backupPassphraseDraft by remember { mutableStateOf("") }
    var backupPayloadDraft by remember { mutableStateOf("") }

    SunkenWrapHeightCard(
        outerCornerRadius = 20.dp,
        innerCornerRadius = 15.dp,
        sunkenInsetPadding = 5.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Backup,
                    contentDescription = null,
                    tint = GhAccentGreen,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = "AES-256 Encrypted Backup & One-Tap Tablet Migration",
                        color = GhTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GhFont
                    )
                    Text(
                        text = "Export or import all devices, capabilities, rooms, automations, and encrypted credentials across tablets.",
                        color = GhTextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            OutlinedTextField(
                value = backupPassphraseDraft,
                onValueChange = { backupPassphraseDraft = it },
                label = { Text("Encryption Passphrase", fontSize = 11.sp) },
                textStyle = compactInputTextStyle,
                shape = RoundedCornerShape(12.dp),
                colors = claraFieldColors(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = backupPayloadDraft,
                onValueChange = { backupPayloadDraft = it },
                label = { Text("Encrypted Backup Bundle (CLARA_BACKUP_V1:...)", fontSize = 11.sp) },
                textStyle = compactInputTextStyle,
                shape = RoundedCornerShape(12.dp),
                colors = claraFieldColors(),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                FilledTonalButton(
                    onClick = {
                        val exported = DeviceRepository.exportEncryptedBackupJson(
                            context = context,
                            passphrase = backupPassphraseDraft
                        )
                        backupPayloadDraft = exported
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("ClaraEncryptedBackup", exported))
                        onStatusBanner("SUCCESS: Exported encrypted backup bundle & copied to clipboard!")
                    },
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Export & Copy Bundle", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        val res = DeviceRepository.importEncryptedBackupJson(
                            context = context,
                            rawBackupPayload = backupPayloadDraft,
                            passphrase = backupPassphraseDraft
                        )
                        if (res.isSuccess) {
                            val restoredTiles = DeviceRepository.loadTiles(context)
                            tiles.clear()
                            tiles.addAll(restoredTiles)
                            val restoredRules = DeviceRepository.loadAutomations(context)
                            automationRules.clear()
                            automationRules.addAll(restoredRules)
                            onStatusBanner("SUCCESS: Restored ${res.getOrThrow()} tiles & settings from encrypted backup!")
                        } else {
                            onStatusBanner(res.exceptionOrNull()?.message ?: "Backup restore failed.")
                        }
                    },
                    enabled = backupPayloadDraft.isNotBlank(),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GhAccentGreen,
                        contentColor = Color(0xFF141518)
                    )
                ) {
                    Text("Restore Bundle", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// HOME HEALTH, AUTONOMOUS 4-STAGE SELF-HEALING & DIAGNOSTICS DASHBOARD SECTION
// ---------------------------------------------------------------------------
@Composable
fun HomeHealthAndDiagnosticsSection(
    context: Context,
    tiles: MutableList<SmartTile>,
    automationRules: MutableList<AutomationRule>,
    bridgeSettings: BridgeSettings,
    isCompactMobile: Boolean,
    compactInputTextStyle: TextStyle,
    onStatusBanner: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val integrationHealthMap by JarvisLiveBridge.integrationHealthFlow.collectAsState()
    val selfHealingLogs by JarvisLiveBridge.selfHealingLogsFlow.collectAsState()
    val securityAuditLogs by JarvisLiveBridge.securityAuditLogsFlow.collectAsState()
    val discoveredMeshPeers by JarvisLiveBridge.discoveredMeshPeersFlow.collectAsState()
    val homeGraph by JarvisLiveBridge.digitalHomeGraphFlow.collectAsState()

    var isRunningSelfHealSweep by remember { mutableStateOf(false) }
    var showFlightRecorderModal by remember { mutableStateOf(false) }
    var showTimelineReplayModal by remember { mutableStateOf(false) }
    var selectedBlackBoxGuid by remember { mutableStateOf<String?>(null) }
    var xrayPendingRemovalTile by remember { mutableStateOf<SmartTile?>(null) }

    val controllableDevices = remember(tiles.toList()) {
        tiles.filter {
            it.type != TileType.CLOCK_WEATHER &&
                    it.type != TileType.GROUP_CARD &&
                    it.type != TileType.PHOTO_SLIDESHOW &&
                    it.type != TileType.MACRO_TILE
        }
    }

    val lowBatteryTiles = remember(controllableDevices) {
        controllableDevices.filter {
            (it.isVehicleTile && it.isConfiguredAndLinked && it.vehicleFuelOrBatteryPct in 1..20) ||
                    (it.type == TileType.AIRROBO_VACUUM && it.volume.roundToInt() in 1..20) ||
                    (it.batteryPercent in 1..20)
        }
    }

    val openOrUnlockedPerimeterTiles = remember(controllableDevices) {
        controllableDevices.filter {
            (it.isVehicleTile && it.isConfiguredAndLinked && !it.vehicleLocked) ||
                    (it.type == TileType.SMART_LOCK && !it.isActive) ||
                    (it.type == TileType.SMART_COVER && it.isActive)
        }
    }

    val reliabilityKpis by JarvisLiveBridge.reliabilityKpisFlow.collectAsState()
    val pollAvgLatencyMs by JarvisLiveBridge.pollAverageLatencyMsFlow.collectAsState()
    val pollSuccessRatePct by JarvisLiveBridge.pollSuccessRatePctFlow.collectAsState()
    val circuitRejections by JarvisLiveBridge.circuitBreakerRejectionsFlow.collectAsState()
    val snapshotRecoveries by JarvisLiveBridge.jsonCorruptionRecoveriesFlow.collectAsState()
    val deviceObservabilityMap by JarvisLiveBridge.deviceObservabilityFlow.collectAsState()
    val providerHealthMap by JarvisLiveBridge.providerHealthFlow.collectAsState()
    val accountHealthMap by JarvisLiveBridge.accountHealthFlow.collectAsState()
    val failoverPoliciesMap by JarvisLiveBridge.failoverPoliciesFlow.collectAsState()
    val shadowQueue by JarvisLiveBridge.shadowQueueFlow.collectAsState()
    val deviceEvents by JarvisLiveBridge.deviceEventsFlow.collectAsState()
    val isPrimaryAuthority by JarvisLiveBridge.isPrimaryAutomationAuthorityFlow.collectAsState()

    LaunchedEffect(tiles.size, bridgeSettings) {
        JarvisLiveBridge.seedDeviceObservabilityForTiles(tiles)
        JarvisLiveBridge.syncAccountHealthFromBridge(context, bridgeSettings)
    }

    fun formatRelativeAge(epochMs: Long): String {
        if (epochMs <= 0L) return "Never"
        val diffSec = ((System.currentTimeMillis() - epochMs).coerceAtLeast(0L)) / 1000L
        return when {
            diffSec < 5L -> "Just now"
            diffSec < 60L -> "${diffSec}s ago"
            diffSec < 3600L -> "${diffSec / 60L}m ago"
            diffSec < 86400L -> "${diffSec / 3600L}h ago"
            else -> "${diffSec / 86400L}d ago"
        }
    }

    fun formatTokenExpiryCountdown(expiresAtEpochMs: Long): String {
        if (expiresAtEpochMs <= 0L) return "Persistent / Local"
        val remSec = (expiresAtEpochMs - System.currentTimeMillis()) / 1000L
        return when {
            remSec <= 0L -> "Expired"
            remSec < 3600L -> "In ${remSec / 60L}m"
            remSec < 86400L -> "In ${remSec / 3600L}h"
            else -> "In ${remSec / 86400L}d"
        }
    }

    val avgLatencyMs = remember(integrationHealthMap) {
        val records = integrationHealthMap.values
        if (records.isEmpty()) 16L else records.map { it.averageLatencyMs }.average().roundToInt().toLong()
    }
    val allTokensValid = remember(integrationHealthMap, accountHealthMap) {
        integrationHealthMap.values.all { it.tokenValid } &&
                accountHealthMap.values.none { it.mfaRequired || it.permissionsRevoked || it.oauthStatus == AccountAuthStatus.EXPIRED }
    }
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxSize()
    ) {
        // 1. Device Guardian Dashboard & 7 Live Clara Reliability KPIs Card
        item {
            SunkenWrapHeightCard(
                outerCornerRadius = 20.dp,
                innerCornerRadius = 15.dp,
                sunkenInsetPadding = 5.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ResponsiveSplitRow(
                        isStacked = isCompactMobile,
                        firstContent = { mod ->
                            Column(modifier = mod) {
                                Text(
                                    text = "Device Guardian Dashboard & Clara OS Reliability KPIs",
                                    color = GhTextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GhFont
                                )
                                val meshRoleLabel = if (isPrimaryAuthority) "Primary Mesh Authority" else "Hot-Standby Mesh Replica"
                                val tuyaSdkStatus = if (com.jarvis.smarthomelauncher.core.ClaraApplication.isTuyaSdkReady(context)) "Ready" else "Missing/Uninitialized"
                                Text(
                                    text = "Hub: http://${JarvisLiveBridge.resolveLocalTabletIp(context)}:${JarvisLiveBridge.LOCAL_HUB_PORT} \u2022 $meshRoleLabel (${discoveredMeshPeers.size} peer(s)) \u2022 Graph v${homeGraph.graphVersion} \u2022 ThingHomeSdk: $tuyaSdkStatus",
                                    color = GhAccentGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        },
                        secondContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        selectedBlackBoxGuid = null
                                        showTimelineReplayModal = true
                                    },
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color(0xFF34D399).copy(alpha = 0.18f),
                                        contentColor = Color(0xFF34D399)
                                    )
                                ) {
                                    Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Timeline Replay",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                FilledTonalButton(
                                    onClick = {
                                        selectedBlackBoxGuid = null
                                        showFlightRecorderModal = true
                                    },
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color(0xFF38BDF8).copy(alpha = 0.18f),
                                        contentColor = Color(0xFF38BDF8)
                                    )
                                ) {
                                    Icon(Icons.Outlined.Analytics, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Flight Recorder",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Button(
                                    onClick = {
                                        if (!isRunningSelfHealSweep) {
                                            isRunningSelfHealSweep = true
                                            scope.launch {
                                                RecoveryEngine.runProactiveGuardianSweep(
                                                    context = context,
                                                    tiles = tiles,
                                                    bridge = bridgeSettings,
                                                    scope = scope
                                                )
                                                val sampleTile = controllableDevices.firstOrNull { !it.isOnline }
                                                    ?: controllableDevices.firstOrNull()
                                                if (sampleTile != null) {
                                                    val (_, summary) = RecoveryEngine.executeAutonomousRecovery(
                                                        context = context,
                                                        tile = sampleTile,
                                                        bridge = bridgeSettings,
                                                        tiles = tiles
                                                    )
                                                    onStatusBanner("Proactive Guardian & 8-Stage Recovery Complete: $summary")
                                                } else {
                                                    DeviceRepository.rebuildDigitalHomeGraph(context, tiles, bridgeSettings, automationRules)
                                                    onStatusBanner("Proactive Guardian Sweep, Account Tokens & Provider Health verified!")
                                                }
                                                isRunningSelfHealSweep = false
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = GhAccentGreen,
                                        contentColor = Color(0xFF141518)
                                    )
                                ) {
                                    Icon(Icons.Outlined.HealthAndSafety, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isRunningSelfHealSweep) "Running 8-Stage Check..." else "Run 8-Stage Recovery Check",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    )

                    // F-05, F-07, F-16: Split Poll-Health vs Command-Latency KPIs, Circuit Rejections & Snapshot Guard
                    val kpiItems = remember(
                        reliabilityKpis,
                        pollAvgLatencyMs,
                        pollSuccessRatePct,
                        circuitRejections,
                        snapshotRecoveries
                    ) {
                        listOf(
                            Triple("Command Success", "${"%.1f".format(Locale.US, reliabilityKpis.commandSuccessRatePct)}%", "Outbound ACK KPI"),
                            Triple("Poll Health", "${"%.1f".format(Locale.US, pollSuccessRatePct)}% (${pollAvgLatencyMs}ms)", "Background Poll KPI"),
                            Triple("Circuit Rejections", "$circuitRejections", "Open-Circuit Drops"),
                            Triple("Snapshot Recoveries", "$snapshotRecoveries", "JSON Backup Restores"),
                            Triple("Discovery Success", "${"%.1f".format(Locale.US, reliabilityKpis.discoverySuccessRatePct)}%", "Target \u2265 95%"),
                            Triple("Onboarding Success", "${"%.1f".format(Locale.US, reliabilityKpis.onboardingSuccessRatePct)}%", "Target \u2265 90%"),
                            Triple("Auto-Recovery", "${"%.1f".format(Locale.US, reliabilityKpis.autoRecoverySuccessRatePct)}%", "9-Stage Self-Heal"),
                            Triple("False Success Rate", "${"%.1f".format(Locale.US, reliabilityKpis.falseSuccessRatePct)}%", "Hardware Truth: 0%")
                        )
                    }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(kpiItems) { (title, metricVal, targetSub) ->
                            Surface(
                                color = GhTileSurfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.55f))
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text(title, color = GhTextSecondary, fontSize = 10.sp)
                                    Text(
                                        text = metricVal,
                                        color = GhAccentGreen,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Text(targetSub, color = Color(0xFF00E5FF), fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    val localHubBearerToken = remember {
                        ClaraApplication.getOrCreateLocalHubToken(context)
                    }
                    val maskedHubToken = remember(localHubBearerToken) {
                        if (localHubBearerToken.length > 8) {
                            "${localHubBearerToken.take(4)}\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022${localHubBearerToken.takeLast(4)}"
                        } else {
                            "Provisioned"
                        }
                    }
                    Surface(
                        color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, GhCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Key,
                                    contentDescription = null,
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(16.dp)
                                )
                                Column {
                                    Text(
                                        text = "Local Hub API Bearer Token: $maskedHubToken",
                                        color = GhTextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Required in Authorization: Bearer <token> or ?token= for /api/v1/webhook and /api/v1/command",
                                        color = GhTextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            FilledTonalButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("ClaraLocalHubToken", localHubBearerToken))
                                    onStatusBanner("Copied Local Hub API Bearer Token to clipboard!")
                                },
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Copy Webhook Token", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Top Summary Pill Row: Online/Offline, Shadow Queue, Low Battery, Doors/Vehicles, Latency
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            Surface(
                                color = GhTileSurfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text("Devices Online / Offline", color = GhTextSecondary, fontSize = 10.sp)
                                    Text(
                                        text = "${homeGraph.onlineDeviceCount} Online \u2022 ${homeGraph.offlineDeviceCount} Offline",
                                        color = GhAccentGreen,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                        item {
                            Surface(
                                color = GhTileSurfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (shadowQueue.isEmpty()) GhCardBorder else GhAccentWarm
                                )
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text("Coalescing Intent Queue", color = GhTextSecondary, fontSize = 10.sp)
                                    Text(
                                        text = if (shadowQueue.isEmpty()) "0 Queued (All Confirmed)" else "${shadowQueue.size} Shadow-Queued for Wake",
                                        color = if (shadowQueue.isEmpty()) GhTextPrimary else GhAccentWarm,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                        item {
                            Surface(
                                color = GhTileSurfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (lowBatteryTiles.isEmpty()) GhCardBorder else GhAccentWarm
                                )
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text("Low-Battery Alerts", color = GhTextSecondary, fontSize = 10.sp)
                                    Text(
                                        text = if (lowBatteryTiles.isEmpty()) "0 Alerts (All Charged)" else "${lowBatteryTiles.size} Low (${lowBatteryTiles.joinToString { it.title }})",
                                        color = if (lowBatteryTiles.isEmpty()) GhTextPrimary else GhAccentWarm,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                        item {
                            Surface(
                                color = GhTileSurfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (openOrUnlockedPerimeterTiles.isEmpty()) GhCardBorder else Color(0xFF00E5FF)
                                )
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text("Doors, Covers & Vehicles", color = GhTextSecondary, fontSize = 10.sp)
                                    Text(
                                        text = if (openOrUnlockedPerimeterTiles.isEmpty()) {
                                            "All Locked & Closed \u2713"
                                        } else {
                                            "${openOrUnlockedPerimeterTiles.size} Unlocked / Open"
                                        },
                                        color = if (openOrUnlockedPerimeterTiles.isEmpty()) GhAccentGreen else Color(0xFF00E5FF),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                        item {
                            Surface(
                                color = GhTileSurfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, GhCardBorder)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text("Avg LAN Latency & Tokens", color = GhTextSecondary, fontSize = 10.sp)
                                    Text(
                                        text = "${avgLatencyMs} ms \u2022 ${if (allTokensValid) "Tokens Valid \u2713" else "Token Refresh Needed"}",
                                        color = if (allTokensValid) GhAccentGreen else GhAccentWarm,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }

                    // Per-Integration & Per-Provider Health Table
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        integrationHealthMap.values.forEach { rec ->
                            Surface(
                                color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (rec.isOnline && rec.tokenValid) GhCardBorder else GhAccentWarm
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = rec.displayName,
                                            color = GhTextPrimary,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${rec.statusMessage} \u2022 Token: ${if (rec.tokenValid) "Valid" else "Expired"}",
                                            color = GhTextSecondary,
                                            fontSize = 10.5.sp
                                        )
                                    }
                                    Text(
                                        text = "${rec.averageLatencyMs} ms",
                                        color = if (rec.isOnline) GhAccentGreen else GhAccentWarm,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                        providerHealthMap.values.forEach { prov ->
                            Surface(
                                color = GhTileSurfaceElevated.copy(alpha = 0.42f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (prov.isHealthy) GhCardBorder else GhAccentRed.copy(alpha = 0.6f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Provider: ${prov.displayName} (${prov.providerId})",
                                            color = GhTextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Rolling Success: ${"%.1f".format(Locale.US, prov.rollingSuccessRatePct)}% \u2022 Failures: ${prov.consecutiveFailures}",
                                            color = GhTextSecondary,
                                            fontSize = 10.sp
                                        )
                                    }
                                    Text(
                                        text = if (prov.isHealthy) "${prov.averageLatencyMs} ms \u2713" else "Circuit Open",
                                        color = if (prov.isHealthy) Color(0xFF00E5FF) else GhAccentRed,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 1A-2. Account Health & OAuth Lifecycle Registry Card
        item {
            SunkenWrapHeightCard(
                outerCornerRadius = 20.dp,
                innerCornerRadius = 15.dp,
                sunkenInsetPadding = 5.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Account Health & OAuth Token Lifecycle",
                                color = Color(0xFF00E5FF),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tracks OAuth status, token expiry countdowns, MFA challenges, permission revocations & last refresh.",
                                color = GhTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    RecoveryEngine.runProactiveGuardianSweep(context, tiles, bridgeSettings, scope)
                                    onStatusBanner("Refreshed & verified all linked cloud OAuth sessions!")
                                }
                            },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Refresh Tokens", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    accountHealthMap.values.forEach { acc ->
                        val badgeColor = Color(acc.oauthStatus.badgeColorHex or 0xFF000000L)
                        Surface(
                            color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (acc.mfaRequired || acc.permissionsRevoked) GhAccentRed else badgeColor.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = acc.displayName,
                                            color = GhTextPrimary,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Surface(
                                            color = badgeColor.copy(alpha = 0.18f),
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = acc.oauthStatus.label,
                                                color = badgeColor,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                            )
                                        }
                                        if (acc.mfaRequired) {
                                            Surface(
                                                color = GhAccentRed.copy(alpha = 0.22f),
                                                shape = RoundedCornerShape(50)
                                            ) {
                                                Text(
                                                    text = "MFA Required",
                                                    color = GhAccentRed,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        if (acc.permissionsRevoked) {
                                            Surface(
                                                color = GhAccentRed.copy(alpha = 0.22f),
                                                shape = RoundedCornerShape(50)
                                            ) {
                                                Text(
                                                    text = "Permissions Revoked",
                                                    color = GhAccentRed,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "${acc.statusDetail} \u2022 Expiry: ${formatTokenExpiryCountdown(acc.tokenExpiresAtEpochMs)} \u2022 Last Refresh: ${formatRelativeAge(acc.lastRefreshEpochMs)}",
                                        color = GhTextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 1B. Stage 5 Per-Device Guardian Dashboard Card (Availability, Last Seen, Provider, Latency, Failures, Recoveries)
        item {
            SunkenWrapHeightCard(
                outerCornerRadius = 20.dp,
                innerCornerRadius = 15.dp,
                sunkenInsetPadding = 5.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Device X-Ray & Guardian Telemetry (${controllableDevices.size} Devices)",
                                color = GhAccentGreen,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Canonical GUIDs, CapabilityOrigin Provenance, Active Provider, Latency, Recovery Attempts & 1-Tap Snapshot Restore.",
                                color = GhTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    val restored = DeviceRepository.loadTiles(context)
                                    if (restored.isNotEmpty()) {
                                        tiles.clear()
                                        tiles.addAll(restored)
                                        onStatusBanner("SUCCESS: Restored ${restored.size} verified tile(s) from persistence snapshot!")
                                    } else {
                                        onStatusBanner("No backup snapshot restoration needed — current tile store is healthy.")
                                    }
                                },
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Restore Snapshot", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (controllableDevices.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    DeviceRepository.removeAllDevicesAndClearCaches(context, tiles)
                                    onStatusBanner("Removed all devices & cleared pairing caches!")
                                },
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, GhAccentRed.copy(alpha = 0.7f)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    tint = GhAccentRed,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Remove All Devices", fontSize = 11.sp, color = GhAccentRed, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    controllableDevices.take(24).forEach { devTile ->
                        val obs = deviceObservabilityMap[devTile.id]
                        val policy = failoverPoliciesMap[devTile.id]
                        val lifecycle = obs?.lifecycleState ?: devTile.effectiveLifecycleState
                        val lcColor = Color(lifecycle.badgeColorHex or 0xFF000000L)
                        val activeProv = obs?.activeProviderId?.ifBlank { devTile.activeProviderId }
                            ?: devTile.activeProviderId.ifBlank { devTile.type.brandName }
                        val fallbacks = (policy?.orderedFallbackProviderIds ?: devTile.fallbackProviderIds)
                            .joinToString(" \u2192 ")
                        val availPct = obs?.availabilityPct ?: if (devTile.isOnline) 100f else 80f
                        val cmdRate = obs?.commandSuccessRatePct ?: 100f
                        val latMs = obs?.averageLatencyMs ?: 12L
                        val failCount = obs?.failureCount ?: 0
                        val recAttempts = obs?.recoveryAttempts ?: 0
                        val lastSeenStr = formatRelativeAge(obs?.lastSeenEpochMs ?: devTile.lastConfirmedEpochMs)

                        Surface(
                            color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, lcColor.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = devTile.title,
                                            color = GhTextPrimary,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Surface(
                                            color = lcColor.copy(alpha = 0.18f),
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = lifecycle.label,
                                                color = lcColor,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                            )
                                        }
                                        Surface(
                                            color = GhAccentBlue.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = devTile.deviceSubtype.label,
                                                color = GhAccentBlue,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    val canonicalGuid = devTile.resolvedCanonicalGuid()
                                    val trustedCapCount = devTile.capabilities.count { it.origin.isHardwareTrusted }
                                    val totalCapCount = devTile.capabilities.size
                                    val originBadge = if (totalCapCount > 0) {
                                        "Origin: $trustedCapCount/$totalCapCount Hardware-Verified"
                                    } else {
                                        "Origin: Discovery Probed"
                                    }
                                    Text(
                                        text = buildString {
                                            append("GUID: $canonicalGuid \u2022 $originBadge\n")
                                            append("Provider: $activeProv")
                                            if (fallbacks.isNotBlank()) append(" (\u2192 $fallbacks)")
                                            append(" \u2022 Last Seen: $lastSeenStr")
                                            append(" \u2022 Failures: $failCount \u2022 Recoveries: $recAttempts")
                                        },
                                        color = GhTextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "${"%.0f".format(Locale.US, availPct)}% Avail \u2022 ${"%.0f".format(Locale.US, cmdRate)}% ACK",
                                            color = if (availPct >= 95f) GhAccentGreen else GhAccentWarm,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                        Text(
                                            text = "${latMs}ms \u2022 v${devTile.stateVersion}",
                                            color = GhTextSecondary,
                                            fontSize = 9.5.sp
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            selectedBlackBoxGuid = devTile.canonicalGuid.ifBlank { devTile.resolvedCanonicalGuid() }
                                            showTimelineReplayModal = true
                                        },
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = Color(0xFF34D399).copy(alpha = 0.18f),
                                            contentColor = Color(0xFF34D399)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Text("Replay", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            selectedBlackBoxGuid = devTile.canonicalGuid.ifBlank { devTile.resolvedCanonicalGuid() }
                                            showFlightRecorderModal = true
                                        },
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = Color(0xFFF97316).copy(alpha = 0.18f),
                                            contentColor = Color(0xFFF97316)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Text("Black Box", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            scope.launch {
                                                val (_, msg) = RecoveryEngine.executeAutonomousRecovery(
                                                    context = context,
                                                    tile = devTile,
                                                    bridge = bridgeSettings,
                                                    tiles = tiles
                                                )
                                                onStatusBanner(msg)
                                            }
                                        },
                                        shape = RoundedCornerShape(50),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Text("Heal", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    IconButton(
                                        onClick = { xrayPendingRemovalTile = devTile },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Remove Device",
                                            tint = GhAccentRed,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. 8-Stage Autonomous Recovery Engine Log Card (Stages 0..7)
        item {
            SunkenWrapHeightCard(
                outerCornerRadius = 20.dp,
                innerCornerRadius = 15.dp,
                sunkenInsetPadding = 5.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "8-Stage Autonomous Recovery Engine Log (${selfHealingLogs.size})",
                                color = Color(0xFF00E5FF),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Stage 0: Validation \u2192 Stage 1: ARP IP Refresh \u2192 Stage 2: mDNS/UDP Rediscovery \u2192 Stage 3: Token Renewal \u2192 Stage 4: Socket Rebuild \u2192 Stage 5: Reprovision \u2192 Stage 6: Provider Failover \u2192 Stage 7: Shadow Queue",
                                color = GhTextSecondary,
                                fontSize = 10.5.sp
                            )
                        }
                        if (selfHealingLogs.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { DeviceRepository.clearSelfHealingLogs(context) },
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = GhAccentRed, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear Log", fontSize = 11.sp, color = GhAccentRed)
                            }
                        }
                    }
                    if (selfHealingLogs.isEmpty()) {
                        Text(
                            text = "All integrations and LAN devices are responding normally \u2014 no recovery actions needed yet.",
                            color = GhTextSecondary,
                            fontSize = 12.sp
                        )
                    } else {
                        selfHealingLogs.take(10).forEach { log ->
                            Surface(
                                color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (log.succeeded) GhAccentGreen.copy(alpha = 0.45f) else GhAccentWarm
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${log.stageLabel} \u2022 ${log.deviceTitle}",
                                            color = if (log.succeeded) GhAccentGreen else GhAccentWarm,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = fmt.format(Date(log.timestampEpochMs)),
                                            color = GhTextSecondary,
                                            fontSize = 10.sp
                                        )
                                    }
                                    Text(
                                        text = log.diagnosticDetail,
                                        color = GhTextPrimary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2B. Stage 4 Causal Event Sourcing Log & Active Coalescing Intent Queue Card
        item {
            SunkenWrapHeightCard(
                outerCornerRadius = 20.dp,
                innerCornerRadius = 15.dp,
                sunkenInsetPadding = 5.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Causal Event Sourcing Log (${deviceEvents.size}) & Shadow Queue (${shadowQueue.size})",
                        color = GhAccentGreen,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Every state transition, hardware ACK, 30-minute manual hold, capability evolution, and provider failover with causal actor attribution.",
                        color = GhTextSecondary,
                        fontSize = 11.sp
                    )
                    if (shadowQueue.isNotEmpty()) {
                        shadowQueue.forEach { intent ->
                            Surface(
                                color = GhTileWarmActive.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, GhAccentWarm),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "SHADOW_QUEUED: ${intent.tileTitle} \u2192 ${if (intent.desiredIsActive) "ON" else "OFF"} (by ${intent.actorName})",
                                        color = GhAccentWarm,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = intent.intentAuthorityTier.label,
                                        color = GhTextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                    if (deviceEvents.isEmpty()) {
                        Text(
                            text = "No device state transitions recorded in this session yet.",
                            color = GhTextSecondary,
                            fontSize = 12.sp
                        )
                    } else {
                        deviceEvents.take(12).forEach { ev ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(GhTileSurfaceElevated.copy(alpha = 0.55f))
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "[${ev.eventType.label}] ${ev.deviceTitle} \u2022 ${ev.newStateSummary}",
                                        color = GhTextPrimary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Actor: ${ev.actorName} (${ev.source}) \u2022 v${ev.stateVersion} \u2022 ${fmt.format(Date(ev.timestampEpochMs))}",
                                        color = GhTextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Security Audit Log Card
        item {
            SunkenWrapHeightCard(
                outerCornerRadius = 20.dp,
                innerCornerRadius = 15.dp,
                sunkenInsetPadding = 5.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Security, Lock & Vehicle Audit Trail (${securityAuditLogs.size})",
                                color = GhAccentGreen,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Records all Security Center mode transitions, smart lock actions, vehicle lock/climate commands, and role switches.",
                                color = GhTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        if (securityAuditLogs.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { DeviceRepository.clearAuditLogs(context) },
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Clear Audit Trail", fontSize = 11.sp, color = GhAccentRed)
                            }
                        }
                    }

                    if (securityAuditLogs.isEmpty()) {
                        Text(
                            text = "No security audit events recorded yet.",
                            color = GhTextSecondary,
                            fontSize = 12.sp
                        )
                    } else {
                        securityAuditLogs.take(10).forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(GhTileSurfaceElevated.copy(alpha = 0.55f))
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "[${entry.actorRole.label}] ${entry.targetName} \u2014 ${entry.detail}",
                                        color = if (entry.succeeded) GhTextPrimary else GhAccentRed,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = fmt.format(Date(entry.timestampEpochMs)),
                                        color = GhTextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                                Icon(
                                    imageVector = if (entry.succeeded) Icons.Default.CheckCircle else Icons.Outlined.ReportProblem,
                                    contentDescription = null,
                                    tint = if (entry.succeeded) GhAccentGreen else GhAccentRed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Encrypted Backup & Migration Bundle Card
        item {
            EncryptedBackupAndMigrationCard(
                context = context,
                tiles = tiles,
                automationRules = automationRules,
                compactInputTextStyle = compactInputTextStyle,
                onStatusBanner = onStatusBanner
            )
        }
    }

    if (showFlightRecorderModal) {
        FlightRecorderHubModal(
            initialDeviceIdFilter = selectedBlackBoxGuid,
            onDismiss = { showFlightRecorderModal = false }
        )
    }

    if (showTimelineReplayModal) {
        DeterministicTimelineReplayModal(
            initialDeviceGuid = selectedBlackBoxGuid,
            onDismiss = { showTimelineReplayModal = false }
        )
    }

    xrayPendingRemovalTile?.let { target ->
        DeviceRemovalDialog(
            context = context,
            tile = target,
            bridge = bridgeSettings,
            onDismiss = { xrayPendingRemovalTile = null },
            onUnpaired = {
                DeviceRepository.unpairTileFromDashboard(context, target, tiles)
                onStatusBanner("Hid '${target.title}' from dashboard (credentials retained).")
                xrayPendingRemovalTile = null
            },
            onUnbound = {
                scope.launch {
                    DeviceRepository.unbindAndForgetTile(context, target, bridgeSettings, tiles)
                    onStatusBanner("Unbound & forgot '${target.title}'.")
                }
                xrayPendingRemovalTile = null
            }
        )
    }
}

// ---------------------------------------------------------------------------
// LIVE SMARTLIFE-STYLE CONNECTION TIMELINE (Scanning -> Found -> Wi-Fi -> Registering -> Ready)
// ---------------------------------------------------------------------------
@Composable
fun LivePairingConnectionTimeline(
    targetKey: String? = null,
    modifier: Modifier = Modifier
) {
    val activeStage by JarvisLiveBridge.tuyaPairingStageFlow.collectAsState()
    val activeStageTitle by JarvisLiveBridge.activePairingStageTitleFlow.collectAsState()
    val history by JarvisLiveBridge.tuyaPairingTimelineHistoryFlow.collectAsState()
    val isPairing by JarvisLiveBridge.isPairingTuyaFlow.collectAsState()
    val activeTargetKey by JarvisLiveBridge.activeTimelineTargetKeyFlow.collectAsState()
    val liveStatus by JarvisLiveBridge.tuyaPairingStatusFlow.collectAsState()

    if (activeStage == PairingTimelineStage.IDLE) return
    if (targetKey != null && activeTargetKey != targetKey) return

    val orderedStages = remember {
        listOf(
            PairingTimelineStage.SCANNING_DEVICE,
            PairingTimelineStage.DEVICE_FOUND,
            PairingTimelineStage.CONNECTING_WIFI,
            PairingTimelineStage.REGISTERING_CLOUD,
            PairingTimelineStage.INITIALIZING_TILE
        )
    }

    Surface(
        color = GhTileSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.2.dp,
            when (activeStage) {
                PairingTimelineStage.FAILED -> GhAccentRed.copy(alpha = 0.7f)
                PairingTimelineStage.COMPLETED -> GhAccentGreen
                else -> Color(0xFF00E5FF).copy(alpha = 0.65f)
            }
        ),
        modifier = modifier.fillMaxWidth()
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = when (activeStage) {
                            PairingTimelineStage.COMPLETED -> Icons.Filled.CheckCircle
                            PairingTimelineStage.FAILED -> Icons.Outlined.ReportProblem
                            else -> Icons.Outlined.Radar
                        },
                        contentDescription = null,
                        tint = when (activeStage) {
                            PairingTimelineStage.COMPLETED -> GhAccentGreen
                            PairingTimelineStage.FAILED -> GhAccentRed
                            else -> Color(0xFF00E5FF)
                        },
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Live Connection Timeline",
                        color = GhTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GhFont
                    )
                }
                Surface(
                    color = GhTileSurfaceElevated,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = activeStageTitle.ifBlank { activeStage.title },
                        color = when (activeStage) {
                            PairingTimelineStage.COMPLETED -> GhAccentGreen
                            PairingTimelineStage.FAILED -> GhAccentRed
                            else -> Color(0xFF00E5FF)
                        },
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            if (liveStatus.isNotBlank()) {
                Text(
                    text = liveStatus,
                    color = if (activeStage == PairingTimelineStage.FAILED) GhAccentRed else GhAccentGreen,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GhFont
                )
            }

            HorizontalDivider(color = GhTileSurfaceElevated)

            orderedStages.forEach { stage ->
                val historyEntry = history.firstOrNull { it.stage == stage }
                val isCompleted = activeStage == PairingTimelineStage.COMPLETED ||
                        (activeStage.stepIndex > stage.stepIndex && activeStage != PairingTimelineStage.FAILED)
                val isCurrent = activeStage == stage && isPairing
                val isPending = !isCompleted && !isCurrent

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isCompleted -> GhAccentGreen.copy(alpha = 0.22f)
                                    isCurrent -> Color(0xFF00E5FF).copy(alpha = 0.22f)
                                    else -> GhTileSurfaceElevated
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = when {
                                    isCompleted -> GhAccentGreen
                                    isCurrent -> Color(0xFF00E5FF)
                                    else -> GhCardBorder
                                },
                                shape = CircleShape
                            )
                    ) {
                        when {
                            isCompleted -> Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            isCurrent -> CircularProgressIndicator(
                                color = Color(0xFF00E5FF),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                            else -> Text(
                                text = "${stage.stepIndex}",
                                color = GhTextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = historyEntry?.title?.takeIf { it.isNotBlank() } ?: stage.title,
                            color = when {
                                isCompleted -> GhAccentGreen
                                isCurrent -> Color.White
                                else -> GhTextSecondary
                            },
                            fontSize = 12.5.sp,
                            fontWeight = if (isCurrent || isCompleted) FontWeight.Bold else FontWeight.Medium,
                            fontFamily = GhFont
                        )
                        Text(
                            text = historyEntry?.detail ?: stage.defaultSubtitle,
                            color = if (isPending) GhTextSecondary.copy(alpha = 0.65f) else GhTextSecondary,
                            fontSize = 10.5.sp,
                            fontFamily = GhFont,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
