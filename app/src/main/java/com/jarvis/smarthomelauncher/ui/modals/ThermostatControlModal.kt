package com.jarvis.smarthomelauncher.ui.modals

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// RADIAL THERMOSTAT DIAL GAUGE (USED IN POPUP MODAL)
// ---------------------------------------------------------------------------
@Composable
fun RadialThermostatDialGauge(
    targetTemp: Float,
    ambientTemp: Float,
    minTemp: Float = 10f,
    maxTemp: Float = 32f,
    isActiveHeating: Boolean,
    isEcoMode: Boolean,
    statusLabel: String,
    dialSize: Dp = 210.dp,
    onDecreaseTemp: () -> Unit,
    onIncreaseTemp: () -> Unit
) {
    val progress = ((targetTemp - minTemp) / (maxTemp - minTemp)).coerceIn(0f, 1f)
    val activeAccent = when {
        isActiveHeating -> NestHeatingOrange
        isEcoMode -> Color(0xFF00C6FF)
        else -> GhTextSecondary
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(dialSize)
            .semantics {
                contentDescription = "Thermostat Dial: $statusLabel"
                stateDescription = "Target ${"%.1f".format(targetTemp)} degrees Celsius, Room ${"%.1f".format(ambientTemp)} degrees"
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val tickCount = 36
            val startAngleDeg = 135f
            val sweepAngleDeg = 270f
            val radius = size.minDimension / 2f
            val outerTickRadius = radius * 0.95f
            val innerTickRadius = radius * 0.86f
            val activeTicks = (progress * tickCount).toInt()

            // Ambient radial halo behind the gauge (Fix #4)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        activeAccent.copy(alpha = if (isActiveHeating || isEcoMode) 0.24f else 0.06f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )

            for (i in 0..tickCount) {
                val angleDeg = startAngleDeg + (sweepAngleDeg * i / tickCount.toFloat())
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val cosA = cos(angleRad).toFloat()
                val sinA = sin(angleRad).toFloat()

                val startPt = Offset(
                    x = center.x + outerTickRadius * cosA,
                    y = center.y + outerTickRadius * sinA
                )
                val endPt = Offset(
                    x = center.x + innerTickRadius * cosA,
                    y = center.y + innerTickRadius * sinA
                )
                val tickColor = if (i <= activeTicks && (isActiveHeating || isEcoMode)) {
                    Color.White.copy(alpha = 0.72f)
                } else {
                    Color.White.copy(alpha = 0.18f)
                }
                drawLine(
                    color = tickColor,
                    start = startPt,
                    end = endPt,
                    strokeWidth = if (i % 6 == 0) 5f else 3f,
                    cap = StrokeCap.Round
                )
            }

            val arcRadius = radius * 0.75f
            val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
            val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
            val currentSweep = (sweepAngleDeg * progress).coerceAtLeast(10f)

            drawCircle(
                color = Color(0xFF15171B),
                radius = arcRadius,
                center = center,
                style = Stroke(width = 10.dp.toPx())
            )

            if (isActiveHeating || isEcoMode) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = ThermostatSpectrumColors,
                        center = center
                    ),
                    startAngle = startAngleDeg,
                    sweepAngle = currentSweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            val thumbAngleDeg = startAngleDeg + currentSweep
            val thumbRad = Math.toRadians(thumbAngleDeg.toDouble())
            val thumbCenter = Offset(
                x = center.x + arcRadius * cos(thumbRad).toFloat(),
                y = center.y + arcRadius * sin(thumbRad).toFloat()
            )
            drawCircle(
                color = activeAccent.copy(alpha = 0.42f),
                radius = 12f,
                center = thumbCenter
            )
            drawCircle(
                color = Color.White,
                radius = 6f,
                center = thumbCenter
            )

            val orbRadius = radius * 0.61f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = if (isActiveHeating) {
                        listOf(
                            Color(0xFFFF8A00).copy(alpha = 0.52f),
                            Color(0xFFE65100).copy(alpha = 0.26f),
                            Color(0xFF17191E)
                        )
                    } else if (isEcoMode) {
                        listOf(
                            Color(0xFF00C6FF).copy(alpha = 0.48f),
                            Color(0xFF0072FF).copy(alpha = 0.24f),
                            Color(0xFF16181E)
                        )
                    } else {
                        listOf(
                            Color(0xFF393F4D),
                            Color(0xFF222630),
                            Color(0xFF16181E)
                        )
                    },
                    center = Offset(center.x, center.y - orbRadius * 0.22f),
                    radius = orbRadius * 1.35f
                ),
                radius = orbRadius,
                center = center
            )
            drawCircle(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.24f),
                        Color.White.copy(alpha = 0.05f)
                    )
                ),
                radius = orbRadius,
                center = center,
                style = Stroke(width = 1.3.dp.toPx())
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Text(
                text = statusLabel.uppercase(),
                color = if (isActiveHeating) NestHeatingOrange else Color.White.copy(alpha = 0.85f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                fontFamily = GhFont
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${"%.1f".format(targetTemp)}\u00B0C",
                color = GhTextPrimary,
                fontSize = if (dialSize >= 175.dp) 30.sp else 24.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = GhFont,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Room ${"%.1f".format(ambientTemp)}\u00B0C",
                color = GhTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GhFont
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${minTemp.toInt()}\u00B0C",
                color = GhTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFF00C6FF), Color(0xFF0072FF))
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(11.dp))
                        .semantics {
                            role = Role.Button
                            contentDescription = "Decrease Target Temperature"
                        }
                        .clickable { onDecreaseTemp() }
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFFFF4081), Color(0xFFFF6D00))
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(11.dp))
                        .semantics {
                            role = Role.Button
                            contentDescription = "Increase Target Temperature"
                        }
                        .clickable { onIncreaseTemp() }
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Text(
                text = "${maxTemp.toInt()}\u00B0C",
                color = GhTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GhTuyaRadiatorContent(
    tile: SmartTile,
    @Suppress("UNUSED_PARAMETER") onUpdate: (SmartTile) -> Unit,
    onOpenModal: () -> Unit = {},
    onLongPressEdit: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onOpenModal() },
                onLongClick = { onLongPressEdit() }
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = tile.title,
            color = GhTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = GhFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ClimateModePill(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    activeColor: Color = GhAccentGreen,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) activeColor else GhCardBorder
        ),
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) {
                    Brush.verticalGradient(
                        colors = listOf(
                            activeColor.copy(alpha = 0.34f),
                            activeColor.copy(alpha = 0.14f)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            GhTileSurfaceElevated,
                            GhTileSurfaceElevated.copy(alpha = 0.78f)
                        )
                    )
                }
            )
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) activeColor else GhTextSecondary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                color = if (isSelected) GhTextPrimary else GhTextSecondary,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

// ---------------------------------------------------------------------------
// FULL THERMOSTAT & CLIMATE POPUP MODAL
// ---------------------------------------------------------------------------
@Composable
fun ThermostatControlModal(
    tile: SmartTile,
    onUpdateTile: (SmartTile) -> Unit,
    onDismiss: () -> Unit
) {
    val tempSliderCap = remember(tile.capabilities) {
        tile.capabilities.firstOrNull {
            it.rendererType == CapabilityRendererType.TEMPERATURE_DIAL ||
                    CapabilityClassifier.inferRendererType(it) == CapabilityRendererType.TEMPERATURE_DIAL
        }
    }
    val isNestProvider = resolveEffectiveProviderKey(tile).contains("NEST") || tile.entityId.startsWith("enterprises/")
    val isEcoMode = tile.hvacMode.equals("ECO", ignoreCase = true)
    val isBoostMode = tile.hvacMode.equals("BOOST", ignoreCase = true)
    val isHeatingOn = if (isNestProvider) {
        tile.hvacMode.equals("HEAT", ignoreCase = true) || isBoostMode
    } else {
        tile.isActive && !tile.hvacMode.equals("OFF", ignoreCase = true) && !isEcoMode
    }
    val isActivelyHeating = tile.isActuallyHeating
    val activeAccent = if (isActivelyHeating) NestHeatingOrange else GhAccentGreen
    val isOff = !isHeatingOn && !isEcoMode
    val minTempLimit = tempSliderCap?.minValue?.takeIf { it > 0f } ?: 5f
    val maxTempLimit = tempSliderCap?.maxValue?.takeIf { it > minTempLimit }
        ?: if (tile.deviceSubtype == DeviceSubtype.THERMOSTAT) 32f else 35f
    val step = (tempSliderCap?.stepValue ?: 0.5f).coerceAtLeast(0.5f)

    val presetTemps = listOf(16f, 18f, 20f, 21f, 22f, 24f)
    val isDark = ClaraThemeManager.isDarkMode

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(
                1.5.dp,
                if (isActivelyHeating) NestHeatingOrange.copy(alpha = 0.65f) else GhCardBorder
            ),
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.90f)
                .padding(16.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(
                        colors = if (isActivelyHeating) {
                            listOf(
                                Color(0xFF422615),
                                NestHeatingGlowSurface,
                                Color(0xFF211612)
                            )
                        } else if (isDark) {
                            listOf(
                                Color(0xFF2C313C),
                                Color(0xFF252830),
                                Color(0xFF1C1F26)
                            )
                        } else {
                            listOf(
                                Color(0xFFFFFFFF),
                                Color(0xFFF6F9FC),
                                Color(0xFFEBF0F7)
                            )
                        }
                    )
                )
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Top Specular Gloss Overlay (Fix #4)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = if (isDark) 0.07f else 0.40f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = activeAccent.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.Thermostat,
                                        contentDescription = null,
                                        tint = activeAccent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = tile.title,
                                    color = GhTextPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GhFont
                                )
                                Text(
                                    text = tile.roomOrSubtitle.ifBlank { "Climate Control" },
                                    color = GhTextSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = GhFont
                                )
                            }
                        }
                        FilledTonalIconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    HorizontalDivider(color = GhTileSurfaceElevated)

                    RadialThermostatDialGauge(
                        targetTemp = tile.targetTemperature,
                        ambientTemp = tile.temperature,
                        minTemp = minTempLimit,
                        maxTemp = maxTempLimit,
                        isActiveHeating = isActivelyHeating,
                        isEcoMode = isEcoMode || (isHeatingOn && !isActivelyHeating),
                        statusLabel = when {
                            isBoostMode -> "Boosting To"
                            isActivelyHeating -> "Heating To"
                            isHeatingOn -> "Target Reached"
                            isEcoMode -> "Eco Mode"
                            else -> "System Off"
                        },
                        dialSize = 216.dp,
                        onDecreaseTemp = {
                            val nextTemp = (tile.targetTemperature - step).coerceAtLeast(minTempLimit)
                            onUpdateTile(
                                tile.copy(
                                    targetTemperature = nextTemp,
                                    hvacMode = "HEAT",
                                    hvacStatus = if (nextTemp > tile.temperature) "HEATING" else "OFF",
                                    isActive = true
                                )
                            )
                        },
                        onIncreaseTemp = {
                            val nextTemp = (tile.targetTemperature + step).coerceAtMost(maxTempLimit)
                            onUpdateTile(
                                tile.copy(
                                    targetTemperature = nextTemp,
                                    hvacMode = "HEAT",
                                    hvacStatus = if (nextTemp > tile.temperature) "HEATING" else "OFF",
                                    isActive = true
                                )
                            )
                        }
                    )

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(presetTemps) { preset ->
                            val isPresetSelected = abs(tile.targetTemperature - preset) < 0.25f && isHeatingOn
                            FilterChip(
                                selected = isPresetSelected,
                                onClick = {
                                    onUpdateTile(
                                        tile.copy(
                                            targetTemperature = preset,
                                            hvacMode = "HEAT",
                                            hvacStatus = if (preset > tile.temperature) "HEATING" else "OFF",
                                            isActive = true
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(50),
                                label = {
                                    Text(
                                        text = "${preset.toInt()}\u00B0C",
                                        fontSize = 12.sp,
                                        fontWeight = if (isPresetSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ClimateModePill(
                            label = "Heat",
                            icon = Icons.Outlined.LocalFireDepartment,
                            isSelected = isHeatingOn && !isBoostMode,
                            activeColor = NestHeatingOrange,
                            modifier = Modifier.weight(1f)
                        ) {
                            onUpdateTile(
                                tile.copy(
                                    hvacMode = "HEAT",
                                    hvacStatus = if (tile.targetTemperature > tile.temperature) "HEATING" else "OFF",
                                    isActive = true
                                )
                            )
                        }
                        ClimateModePill(
                            label = "Eco",
                            icon = Icons.Outlined.Eco,
                            isSelected = isEcoMode,
                            activeColor = Color(0xFF00C6FF),
                            modifier = Modifier.weight(1f)
                        ) {
                            onUpdateTile(
                                tile.copy(
                                    hvacMode = "ECO",
                                    hvacStatus = "OFF",
                                    targetTemperature = if (isNestProvider) tile.targetTemperature else 18.0f,
                                    isActive = !isNestProvider
                                )
                            )
                        }
                        ClimateModePill(
                            label = "Boost",
                            icon = Icons.Outlined.Bolt,
                            isSelected = isBoostMode,
                            activeColor = NestHeatingOrange,
                            modifier = Modifier.weight(1f)
                        ) {
                            onUpdateTile(
                                tile.copy(
                                    hvacMode = "BOOST",
                                    hvacStatus = "HEATING",
                                    targetTemperature = 24.0f,
                                    isActive = true
                                )
                            )
                        }
                        ClimateModePill(
                            label = "Off",
                            icon = Icons.Default.PowerSettingsNew,
                            isSelected = isOff,
                            activeColor = GhTextSecondary,
                            modifier = Modifier.weight(1f)
                        ) {
                            onUpdateTile(
                                tile.copy(
                                    hvacMode = "OFF",
                                    hvacStatus = "OFF",
                                    isActive = false
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
