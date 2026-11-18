package com.jarvis.smarthomelauncher

import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// PROPORTIONAL 2D BENTO GRID DIMENSIONS & PLAIN-LANGUAGE SIZING LABELS
// ---------------------------------------------------------------------------
val TILE_UNIT_HEIGHT: Dp = 86.dp
val TILE_GRID_SPACING: Dp = 8.dp

fun calculateProportionalTileHeight(
    rowSpan: Int,
    unitHeight: Dp = TILE_UNIT_HEIGHT,
    gridSpacing: Dp = TILE_GRID_SPACING
): Dp {
    val clamped = rowSpan.coerceIn(1, 12)
    return (unitHeight * clamped) + (gridSpacing * (clamped - 1))
}

// ---------------------------------------------------------------------------
// OUTER 4-POINT CARD RESIZE HANDLE DOT (USED ON SELECTED CARDS ONLY, NEVER TILES)
// ---------------------------------------------------------------------------
@Composable
internal fun LauncherResizeHandleDot(
    color: Color,
    size: Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .shadow(elevation = 6.dp, shape = CircleShape)
            .clip(CircleShape)
            .background(Color.White)
            .border(width = 3.dp, color = color, shape = CircleShape)
    )
}

// ---------------------------------------------------------------------------
// MOVABLE, 4-POINT GRID-SNAPPED RESIZABLE, ZOOMABLE & WIDGET-SWAPPABLE CARD
// - Nothing highlights green when entering Edit Mode.
// - Only when touched (`isSelectedForSwap == true`) does the card highlight,
//   apply a subtle 2.5.dp blur & light 22% scrim so the widget stays visible,
//   show its 4-point outer resize handles, and display the centered controls.
// ---------------------------------------------------------------------------
@Composable
fun ResizableMovableDashboardCardContainer(
    slot: DashboardCardSlot,
    isEditMode: Boolean,
    isSelectedForSwap: Boolean,
    @Suppress("UNUSED_PARAMETER") isOtherCardSelected: Boolean,
    isBeingDragged: Boolean,
    isDropTargetHighlighted: Boolean,
    dragOffset: Offset,
    widthWeight: Float,
    heightWeight: Float,
    zoomScale: Float = 1.0f,
    activeWidgetType: DashboardWidgetType = slot.defaultWidgetType,
    onSelectOrSwapCard: () -> Unit,
    onDragCardStart: () -> Unit,
    onDragCardDelta: (Offset) -> Unit,
    onDragCardEnd: () -> Unit,
    onResizeWidthDeltaPx: (deltaPx: Float, isLeftEdge: Boolean) -> Unit,
    onResizeHeightDeltaPx: (deltaPx: Float, isTopEdge: Boolean) -> Unit,
    onZoomChange: (Float) -> Unit = {},
    onChangeWidgetType: (DashboardWidgetType) -> Unit = {},
    onResetCardSize: () -> Unit,
    onDeleteCard: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val baseDensity = LocalDensity.current
    val clampedZoom = zoomScale.coerceIn(0.70f, 1.40f)
    val accessibleCardFontScale = baseDensity.fontScale.coerceIn(0.85f, 1.65f)
    val cardScaledDensity = remember(baseDensity.density, accessibleCardFontScale, clampedZoom) {
        Density(
            density = baseDensity.density * clampedZoom,
            fontScale = accessibleCardFontScale
        )
    }

    var showWidgetTypeMenu by remember { mutableStateOf(false) }

    val currentOnSelectOrSwap by rememberUpdatedState(onSelectOrSwapCard)
    val currentOnDragStart by rememberUpdatedState(onDragCardStart)
    val currentOnDragDelta by rememberUpdatedState(onDragCardDelta)
    val currentOnDragEnd by rememberUpdatedState(onDragCardEnd)
    val currentOnResizeWidth by rememberUpdatedState(onResizeWidthDeltaPx)
    val currentOnResizeHeight by rememberUpdatedState(onResizeHeightDeltaPx)

    val widthPct = ((widthWeight / slot.defaultWidthWeight) * 100f).roundToInt().coerceIn(35, 240)
    val heightPct = ((heightWeight / slot.defaultHeightWeight) * 100f).roundToInt().coerceIn(35, 240)
    val zoomPct = (clampedZoom * 100f).roundToInt()

    val isCardHighlighted = isEditMode && (isSelectedForSwap || isBeingDragged || isDropTargetHighlighted)

    val animatedScale by animateFloatAsState(
        targetValue = when {
            isBeingDragged -> 1.025f
            isDropTargetHighlighted -> 0.985f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "CardLauncherScale"
    )
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isBeingDragged) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "CardLauncherAlpha"
    )

    Box(
        modifier = modifier
            .zIndex(if (isBeingDragged) 40f else if (isSelectedForSwap || isDropTargetHighlighted) 25f else 1f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = if (isBeingDragged) dragOffset.x else 0f
                    translationY = if (isBeingDragged) dragOffset.y else 0f
                    scaleX = animatedScale
                    scaleY = animatedScale
                    alpha = animatedAlpha
                }
        ) {
            // 1. Underlying Widget Content (Light 2.5.dp blur ONLY when this card is selected)
            val blurRadius = if (isEditMode && isSelectedForSwap) 2.5.dp else 0.dp
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(22.dp))
                    .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
            ) {
                CompositionLocalProvider(LocalDensity provides cardScaledDensity) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        content()
                    }
                }
            }

            // 2. Subtle Scrim When Selected + Tap-to-Select Receiver in Edit Mode
            if (isEditMode) {
                val scrimAlpha = when {
                    isSelectedForSwap -> 0.22f
                    isDropTargetHighlighted -> 0.18f
                    else -> 0.0f
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.Black.copy(alpha = scrimAlpha))
                        .then(
                            if (!isSelectedForSwap) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentOnSelectOrSwap()
                                }
                            } else {
                                Modifier
                            }
                        )
                )
            }

            // 3. Highlight Border, 4-Point Outer Resize Handles & Centered Control Hub ONLY When Selected
            AnimatedVisibility(
                visible = isCardHighlighted,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(32f)
            ) {
                val frameColor = when {
                    isDropTargetHighlighted || isBeingDragged -> GhAccentGreen
                    else -> Color(0xFF00E5FF)
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // Highlight Border around the Selected Card
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(
                                width = 2.4.dp,
                                color = frameColor,
                                shape = RoundedCornerShape(22.dp)
                            )
                    )

                    // Tap background of selected card to deselect
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                currentOnSelectOrSwap()
                            }
                    )

                    // Optional Top-Right Delete Button for Extra Added Widget Cards
                    if (onDeleteCard != null) {
                        Surface(
                            color = Color(0xFF14171D).copy(alpha = 0.95f),
                            shape = CircleShape,
                            border = BorderStroke(1.2.dp, GhAccentRed),
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(30.dp)
                                .zIndex(42f)
                                .clickable { onDeleteCard() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove Card",
                                    tint = GhAccentRed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // =================================================================
                    // LARGE CENTERED CARD EDITING CONTROL HUB
                    // =================================================================
                    Surface(
                        color = Color(0xFF101319).copy(alpha = 0.94f),
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(1.5.dp, frameColor),
                        shadowElevation = 12.dp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(10.dp)
                            .zIndex(40f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { /* Consume touches inside hub */ }
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Row 1: Large Drag Handle Pill + Widget Picker Dropdown Button
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Large Drag Handle Button
                                Surface(
                                    color = frameColor.copy(alpha = 0.20f),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.2.dp, frameColor),
                                    modifier = Modifier
                                        .height(42.dp)
                                        .pointerInput(slot.key) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    currentOnDragStart()
                                                },
                                                onDragEnd = { currentOnDragEnd() },
                                                onDragCancel = { currentOnDragEnd() },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    currentOnDragDelta(dragAmount)
                                                }
                                            )
                                        }
                                        .clickable {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            currentOnSelectOrSwap()
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DragIndicator,
                                            contentDescription = "Drag Card",
                                            tint = frameColor,
                                            modifier = Modifier.size(19.dp)
                                        )
                                        Text(
                                            text = "Drag",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = GhFont
                                        )
                                    }
                                }

                                // Large Widget Picker Dropdown Button
                                Box {
                                    Surface(
                                        color = GhTileSurfaceElevated,
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.2.dp, GhAccentGreen.copy(alpha = 0.75f)),
                                        modifier = Modifier
                                            .height(42.dp)
                                            .clickable {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                showWidgetTypeMenu = true
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Widgets,
                                                contentDescription = "Choose Widget",
                                                tint = GhAccentGreen,
                                                modifier = Modifier.size(17.dp)
                                            )
                                            Text(
                                                text = if (slot == DashboardCardSlot.HERO_CLOCK) {
                                                    "Clock Slot: ${activeWidgetType.shortLabel}"
                                                } else {
                                                    activeWidgetType.shortLabel
                                                },
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontFamily = GhFont,
                                                maxLines = 1
                                            )
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowDown,
                                                contentDescription = "Open Widget Menu",
                                                tint = GhAccentGreen,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = showWidgetTypeMenu,
                                        onDismissRequest = { showWidgetTypeMenu = false },
                                        modifier = Modifier
                                            .widthIn(min = 240.dp)
                                            .background(GhTileSurfaceElevated)
                                    ) {
                                        DashboardWidgetType.entries
                                            .filter { it != DashboardWidgetType.DEVICE_TILES }
                                            .forEach { wt ->
                                                val isCurrent = wt == activeWidgetType
                                                DropdownMenuItem(
                                                    text = {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = if (isCurrent) Icons.Default.CheckCircle else Icons.Outlined.Widgets,
                                                                contentDescription = null,
                                                                tint = if (isCurrent) GhAccentGreen else GhTextSecondary,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                            Text(
                                                                text = wt.label,
                                                                color = if (isCurrent) GhAccentGreen else GhTextPrimary,
                                                                fontSize = 13.5.sp,
                                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                                                fontFamily = GhFont
                                                            )
                                                        }
                                                    },
                                                    onClick = {
                                                        showWidgetTypeMenu = false
                                                        onChangeWidgetType(wt)
                                                    }
                                                )
                                            }
                                    }
                                }
                            }

                            // Row 2: Large Zoom Controls (- / 100% / +) + Grid Size Reset Button
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    color = GhTileSurfaceElevated,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, GhCardBorder),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            color = Color(0xFF1A1E26),
                                            shape = CircleShape,
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clickable {
                                                    onZoomChange((clampedZoom - 0.05f).coerceIn(0.70f, 1.40f))
                                                }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Remove,
                                                    contentDescription = "Zoom Out",
                                                    tint = GhAccentGreen,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "Zoom ${zoomPct}%",
                                            color = Color.White,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = GhFont,
                                            modifier = Modifier.clickable {
                                                onZoomChange(1.0f)
                                            }
                                        )
                                        Surface(
                                            color = Color(0xFF1A1E26),
                                            shape = CircleShape,
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clickable {
                                                    onZoomChange((clampedZoom + 0.05f).coerceIn(0.70f, 1.40f))
                                                }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Add,
                                                    contentDescription = "Zoom In",
                                                    tint = GhAccentGreen,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                Surface(
                                    color = GhTileSurfaceElevated,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, GhCardBorder),
                                    modifier = Modifier
                                        .height(36.dp)
                                        .clickable {
                                            onZoomChange(1.0f)
                                            onResetCardSize()
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.RestartAlt,
                                            contentDescription = "Reset Card Size",
                                            tint = GhAccentGreen,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = "${widthPct}%\u00D7${heightPct}%",
                                            color = GhAccentGreen,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = GhFont
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 4-Point Outer Resize Handle Dots on the Selected Card
                    LauncherResizeHandleDot(
                        color = frameColor,
                        size = 15.dp,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 3.dp)
                    )
                    LauncherResizeHandleDot(
                        color = frameColor,
                        size = 15.dp,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 3.dp)
                    )
                    LauncherResizeHandleDot(
                        color = frameColor,
                        size = 15.dp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 3.dp)
                    )
                    LauncherResizeHandleDot(
                        color = frameColor,
                        size = 15.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 3.dp)
                    )
                    LauncherResizeHandleDot(
                        color = frameColor,
                        size = 17.dp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 4.dp, bottom = 4.dp)
                    )
                }
            }
        }

        // Static Untranslated Edge Resize Touch Zones ONLY When This Card Is Selected
        if (isEditMode && isSelectedForSwap) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(28.dp)
                    .fillMaxHeight(0.70f)
                    .zIndex(35f)
                    .pointerInput(slot.key) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            currentOnResizeWidth(dragAmount.x, true)
                        }
                    }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(28.dp)
                    .fillMaxHeight(0.70f)
                    .zIndex(35f)
                    .pointerInput(slot.key) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            currentOnResizeWidth(dragAmount.x, false)
                        }
                    }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.70f)
                    .height(26.dp)
                    .zIndex(35f)
                    .pointerInput(slot.key) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            currentOnResizeHeight(dragAmount.y, true)
                        }
                    }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.70f)
                    .height(26.dp)
                    .zIndex(35f)
                    .pointerInput(slot.key) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            currentOnResizeHeight(dragAmount.y, false)
                        }
                    }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(36.dp)
                    .zIndex(36f)
                    .pointerInput(slot.key) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            currentOnResizeWidth(dragAmount.x, false)
                            currentOnResizeHeight(dragAmount.y, false)
                        }
                    }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 4. VIBRANT CATEGORY-COLOURED DEVICE TILE CARD
//    - Shows the circular icon badge, the device name, and explicit
//      closed-loop verification states ("Sending...", "Auto-Healing...",
//      "Queued for Wake", "Tap to Link", "Offline", or "Hold • Xm").
// ---------------------------------------------------------------------------
private data class TileVisualPalette(
    val primaryColor: Color,
    val secondaryColor: Color,
    val activeGradient: List<Color>
)

private fun resolvePaletteForTile(tile: SmartTile): TileVisualPalette = when {
    tile.isThermostatTile && tile.isActuallyHeating -> TileVisualPalette(
        primaryColor = NestHeatingOrange,
        secondaryColor = NestHeatingDeepOrange,
        activeGradient = listOf(NestHeatingOrange, NestHeatingDeepOrange)
    )
    tile.isThermostatTile -> TileVisualPalette(
        primaryColor = Color(0xFFFF8A00),
        secondaryColor = Color(0xFF546E7A),
        activeGradient = listOf(Color(0xFF37474F), Color(0xFF263238))
    )
    tile.deviceClass == DeviceClass.VEHICLE && tile.vehicleIsEv -> TileVisualPalette(
        primaryColor = Color(0xFF00E5A3),
        secondaryColor = Color(0xFF009688),
        activeGradient = listOf(Color(0xFF00C853), Color(0xFF00695C))
    )
    tile.deviceClass == DeviceClass.VEHICLE -> TileVisualPalette(
        primaryColor = Color(0xFF00C6FF),
        secondaryColor = Color(0xFF0072FF),
        activeGradient = listOf(Color(0xFF00B4D8), Color(0xFF00509D))
    )
    tile.deviceSubtype == DeviceSubtype.TOWER_FAN || tile.isTowerFanTile -> TileVisualPalette(
        primaryColor = Color(0xFF00E5FF),
        secondaryColor = Color(0xFF0091EA),
        activeGradient = listOf(Color(0xFF00B4D8), Color(0xFF0077B6))
    )
    tile.deviceSubtype == DeviceSubtype.AMBIENT_SYNC || tile.isAmbientRgbTile -> {
        val customColor = Color((tile.lytmiColorArgb or 0xFF000000L).toInt())
        TileVisualPalette(
            primaryColor = customColor,
            secondaryColor = Color(0xFF00E5FF),
            activeGradient = buildDynamicAccentGradient(customColor)
        )
    }
    tile.deviceSubtype == DeviceSubtype.SECURITY_HUB || tile.isSecurityCenterTile -> TileVisualPalette(
        primaryColor = Color(0xFFFFB300),
        secondaryColor = Color(0xFFFF6D00),
        activeGradient = listOf(Color(0xFFFF9800), Color(0xFFE65100))
    )
    tile.isMacroTile -> TileVisualPalette(
        primaryColor = Color(0xFF00E5FF),
        secondaryColor = Color(0xFF7C4DFF),
        activeGradient = listOf(Color(0xFF00C6FF), Color(0xFF651FFF))
    )
    tile.deviceClass == DeviceClass.LIGHT -> TileVisualPalette(
        primaryColor = Color(0xFFFFD54F),
        secondaryColor = Color(0xFFFF8F00),
        activeGradient = listOf(Color(0xFFFFB300), Color(0xFFF57C00))
    )
    tile.deviceClass == DeviceClass.APPLIANCE -> TileVisualPalette(
        primaryColor = Color(0xFFFF8A47),
        secondaryColor = Color(0xFFE65100),
        activeGradient = listOf(Color(0xFFFF8A47), Color(0xFFD84315))
    )
    tile.deviceClass == DeviceClass.SWITCH || tile.deviceClass == DeviceClass.ENERGY || tile.isSwitchTile -> TileVisualPalette(
        primaryColor = Color(0xFF00D2FF),
        secondaryColor = Color(0xFF0072FF),
        activeGradient = listOf(Color(0xFF29B6F6), Color(0xFF0277BD))
    )
    tile.deviceClass == DeviceClass.MEDIA -> TileVisualPalette(
        primaryColor = Color(0xFFE040FB),
        secondaryColor = Color(0xFF7C4DFF),
        activeGradient = listOf(Color(0xFFAB47BC), Color(0xFF5E35B1))
    )
    tile.deviceClass == DeviceClass.CAMERA || tile.isCameraTile -> TileVisualPalette(
        primaryColor = Color(0xFF00E676),
        secondaryColor = Color(0xFF00B0FF),
        activeGradient = listOf(Color(0xFF00C853), Color(0xFF00838F))
    )
    tile.isGroupCardTile -> TileVisualPalette(
        primaryColor = GhAccentGreen,
        secondaryColor = Color(0xFF00C6FF),
        activeGradient = buildDynamicAccentGradient(GhAccentGreen)
    )
    else -> TileVisualPalette(
        primaryColor = GhAccentGreen,
        secondaryColor = Color(0xFF00C6FF),
        activeGradient = buildDynamicAccentGradient(GhAccentGreen)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GoogleHomeTileCard(
    tile: SmartTile,
    bridgeSettings: BridgeSettings,
    isOverlayModalOpen: Boolean,
    isEditMode: Boolean,
    isSelectedForSwap: Boolean,
    isBeingDragged: Boolean = false,
    isDropTargetHighlighted: Boolean = false,
    dragOffset: Offset = Offset.Zero,
    unitHeight: Dp = TILE_UNIT_HEIGHT,
    @Suppress("UNUSED_PARAMETER") unitWidth: Dp = 120.dp,
    gridSpacing: Dp = TILE_GRID_SPACING,
    @Suppress("UNUSED_PARAMETER") maxGridCols: Int = 5,
    @Suppress("UNUSED_PARAMETER") maxGridRows: Int = 4,
    musicProvider: MusicProvider,
    isMusicPlaying: Boolean,
    currentTrack: String,
    allTiles: List<SmartTile> = emptyList(),
    modifier: Modifier = Modifier,
    onOpenSetupWizard: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenQuickAddDevice: () -> Unit = onOpenSetupWizard,
    onToggleEditMode: () -> Unit,
    onSelectOrSwap: () -> Unit,
    onDragStart: () -> Unit = {},
    onDragDelta: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onMoveLeft: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onMoveRight: () -> Unit = {},
    onToggleColSpan: () -> Unit,
    onToggleHeight: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onResizeTile: (Int, Int) -> Unit = { _, _ -> },
    onDeleteTile: () -> Unit,
    onUpdateTile: (SmartTile) -> Unit,
    onUpdateSpecificTile: (SmartTile) -> Unit = onUpdateTile,
    onExpandCamera: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenMusicApp: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onToggleMusicPlay: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onSkipTrack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onPreviousTrack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onExecuteScene: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") onTriggerVoicePrompt: () -> Unit = {},
    onActivateHeroDial: (SmartTile) -> Unit = {}
) {
    val haptics = LocalHapticFeedback.current
    val baseDensity = LocalDensity.current
    val accessibleFontScale = baseDensity.fontScale.coerceIn(0.85f, 1.65f)
    val fixedFontDensity = remember(baseDensity.density, accessibleFontScale) {
        Density(density = baseDensity.density, fontScale = accessibleFontScale)
    }

    val targetCardHeight = calculateProportionalTileHeight(
        rowSpan = tile.effectiveRowSpan,
        unitHeight = unitHeight,
        gridSpacing = gridSpacing
    )
    val animatedCardHeight by animateDpAsState(
        targetValue = targetCardHeight,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "TileHeightSpring"
    )

    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragDelta by rememberUpdatedState(onDragDelta)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnSelectOrSwap by rememberUpdatedState(onSelectOrSwap)

    var showGroupCardPopupModal by remember(tile.id) { mutableStateOf(false) }
    var showEnergyMonitorModal by remember(tile.id) { mutableStateOf(false) }
    val isGroupCardTile = tile.type == TileType.GROUP_CARD
    val isEnergyMonitorTile = tile.type == TileType.ENERGY_MONITOR

    val queuedIntentForTile by remember(tile.id) {
        JarvisLiveBridge.shadowQueueFlow
            .map { queue ->
                val now = System.currentTimeMillis()
                queue.firstOrNull { it.tileId == tile.id && it.expiresAtEpochMs > now }
            }
            .distinctUntilChanged()
    }.collectAsState(initial = null)

    val latestCausalEvent by remember(tile.id) {
        JarvisLiveBridge.deviceEventsFlow
            .map { events -> events.firstOrNull { it.deviceId == tile.id } }
            .distinctUntilChanged()
    }.collectAsState(initial = null)

    // Criterion 11: Closed-Loop Transaction & Lifecycle Evaluation (F-04, F-07, F-12, F-30)
    val isUnlinked = !tile.isConfiguredAndLinked
    val isVerifyingControl = tile.isConfiguredAndLinked &&
            tile.effectiveLifecycleState == DeviceLifecycleState.DISCOVERED &&
            DeviceRepository.isTilePendingAckLocked(tile.id)
    val isNeedsReauth = tile.isConfiguredAndLinked &&
            tile.effectiveLifecycleState == DeviceLifecycleState.QUARANTINED_ERROR &&
            JarvisLiveBridge.accountHealthFlow.value.values.any { acc ->
                tile.activeProviderId.contains(acc.accountKey, ignoreCase = true) &&
                        acc.oauthStatus in setOf(AccountAuthStatus.EXPIRED, AccountAuthStatus.PERMISSIONS_REVOKED)
            }
    val isPendingAck = tile.isConfiguredAndLinked && (
            tile.transactionState == CommandTransactionState.PENDING_ACK ||
                    DeviceRepository.isTilePendingAckLocked(tile.id)
            )
    val isHealing = tile.isConfiguredAndLinked && (
            tile.effectiveLifecycleState == DeviceLifecycleState.RECOVERY ||
                    tile.transactionState == CommandTransactionState.HEALING_IN_PROGRESS
            )
    val isDegraded = tile.isConfiguredAndLinked && !isPendingAck && !isHealing && !isNeedsReauth &&
            tile.effectiveLifecycleState == DeviceLifecycleState.RECOVERY
    val isShadowQueued = tile.isConfiguredAndLinked && !tile.isOnline && !isNeedsReauth && (
            tile.transactionState == CommandTransactionState.SHADOW_QUEUED ||
                    queuedIntentForTile != null
            )
    val isOffline = tile.isConfiguredAndLinked && !isPendingAck && !isHealing && !isVerifyingControl && (
            isNeedsReauth ||
                    !tile.isOnline ||
                    tile.effectiveLifecycleState == DeviceLifecycleState.QUARANTINED_ERROR ||
                    tile.effectiveLifecycleState == DeviceLifecycleState.OFFLINE
            )
    val isManualHoldActive = (tile.isManualHoldActive || DeviceRepository.isTileInManualHoldWindow(tile.id)) &&
            !isUnlinked && !isOffline

    val dynamicSockets = remember(tile.capabilities) { tile.multiGangSocketCapabilities() }
    val isNewlyAutoAddedPlug = remember(tile.title, tile.isSwitchTile) {
        tile.isSwitchTile && Regex("\\[[0-9A-Fa-f]{4}\\]$").containsMatchIn(tile.title.trim())
    }

    val isTileActive = when {
        isUnlinked || isOffline -> false
        tile.isMacroTile -> tile.isActive
        tile.isVehicleTile -> tile.vehicleLocked
        tile.isAmbientRgbTile || tile.isTowerFanTile -> tile.isActive
        tile.isSecurityCenterTile -> tile.securityMode != SecurityCenterMode.HOME
        // Thermostat & Heater tiles glow orange ONLY when actively heating (targetTemperature > temperature + 0.05f)
        tile.isThermostatTile -> tile.isActuallyHeating
        tile.channelId == "SPLIT_SOCKET_1" -> tile.isSocketActive(1)
        tile.channelId == "SPLIT_SOCKET_2" -> tile.isSocketActive(2)
        dynamicSockets.isNotEmpty() -> dynamicSockets.indices.any { idx -> tile.isSocketActive(idx + 1) }
        tile.multiGangSocketCount >= 2 -> tile.isActive || tile.isSocket2Active
        tile.deviceSubtype in setOf(DeviceSubtype.SPEAKER, DeviceSubtype.CAST_GROUP) -> tile.volume > 0f && tile.isActive
        tile.isCameraTile -> tile.isLiveFeedActive || tile.isActive
        tile.isGroupCardTile ->
            allTiles.any { it.groupCardId == tile.id && it.isConfiguredAndLinked && it.isOnline && (it.isActive || it.isSocket2Active) }
        else -> tile.isActive
    }

    val palette = resolvePaletteForTile(tile)
    val innerBorder = when {
        isDropTargetHighlighted -> GhAccentGreen
        isBeingDragged -> Color.White
        isSelectedForSwap -> Color(0xFF00E5FF)
        isPendingAck || isHealing -> Color(0xFF00E5FF).copy(alpha = 0.78f)
        isShadowQueued -> GhAccentWarm.copy(alpha = 0.65f)
        isOffline -> GhAccentRed.copy(alpha = 0.55f)
        isUnlinked -> GhAccentWarm.copy(alpha = 0.55f)
        tile.isThermostatTile && tile.isActuallyHeating -> NestHeatingOrange.copy(alpha = 0.85f)
        tile.isThermostatTile -> GhCardBorder
        isTileActive -> palette.primaryColor.copy(alpha = 0.65f)
        else -> GhCardBorder
    }

    val launcherScale by animateFloatAsState(
        targetValue = when {
            isBeingDragged -> 1.06f
            isDropTargetHighlighted -> 1.03f
            isSelectedForSwap -> 1.02f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "TileLauncherScale"
    )
    val launcherAlpha by animateFloatAsState(
        targetValue = if (isBeingDragged) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "TileLauncherAlpha"
    )

    val inferredAppliance = AppliancePowerFingerprinter.inferApplianceFromTelemetry(
        powerWatts = tile.powerWatts,
        titleHint = "${tile.title} ${tile.socket1Name} ${tile.socket2Name}",
        previous = tile.detectedApplianceCategory
    )
    val hasApplianceXrayBadge = !isUnlinked && !isOffline && !isPendingAck && !isHealing && !isShadowQueued && !isDegraded &&
            !tile.isSubtypeConfirmed &&
            tile.isSwitchTile &&
            !tile.isThermostatTile &&
            !tile.isKitchenApplianceTile &&
            inferredAppliance != ApplianceFingerprintCategory.UNKNOWN &&
            (tile.powerWatts >= 2.5f || tile.title.contains(inferredAppliance.suggestedSocketName, ignoreCase = true))
    val hasStatusBadge = isUnlinked || isVerifyingControl || isNeedsReauth || isOffline || isPendingAck || isHealing || isShadowQueued || isDegraded
    val hasHoldBadge = !hasStatusBadge && isManualHoldActive && bridgeSettings.showRoomLabelsOnTiles
    val causalSubtitleText = remember(
        latestCausalEvent,
        tile.lastCausalActor,
        tile.roomOrSubtitle,
        tile.isThermostatTile,
        tile.temperature,
        tile.targetTemperature,
        tile.isActive,
        tile.hvacMode,
        tile.capabilities,
        bridgeSettings.showRoomLabelsOnTiles,
        bridgeSettings.showTemperatureOnTiles
    ) {
        if (tile.isThermostatTile) {
            // Item 3: Temperature displays only on the Hero Card dial by default unless showTemperatureOnTiles is enabled
            if (!bridgeSettings.showTemperatureOnTiles) {
                ""
            } else {
                val capTemp = tile.capabilities.firstOrNull {
                    it.key.lowercase(java.util.Locale.US) in setOf(
                        "temp_current", "current_temp", "ambient_temperature",
                        "va_temperature", "room_temp", "temp_indoor"
                    )
                }?.currentValue?.toFloatOrNull()
                val liveTemp = when {
                    capTemp != null && capTemp > 0f -> capTemp
                    tile.temperature > 0f -> tile.temperature
                    else -> tile.targetTemperature
                }
                val unit = tile.sensorUnit.ifBlank { "\u00B0C" }
                if (tile.isActive && !tile.hvacMode.equals("OFF", ignoreCase = true)) {
                    "Current: ${"%.1f".format(java.util.Locale.US, liveTemp)}$unit \u2022 Set: ${"%.1f".format(java.util.Locale.US, tile.targetTemperature)}$unit"
                } else {
                    "Current: ${"%.1f".format(java.util.Locale.US, liveTemp)}$unit"
                }
            }
        } else if (!bridgeSettings.showRoomLabelsOnTiles) {
            ""
        } else {
            val actor = latestCausalEvent?.actorName?.ifBlank { tile.lastCausalActor } ?: tile.lastCausalActor
            val summary = latestCausalEvent?.newStateSummary.orEmpty()
            when {
                actor.isNotBlank() && summary.isNotBlank() -> "$actor \u2022 $summary"
                actor.isNotBlank() -> actor
                else -> ""
            }
        }
    }
    val hasCausalBadge = !hasStatusBadge && !hasApplianceXrayBadge && !hasHoldBadge && causalSubtitleText.isNotBlank()
    val hasAnyBottomBadge = hasStatusBadge || hasApplianceXrayBadge || hasHoldBadge || hasCausalBadge

    // Proportional icon badge & typography sizing based on unitHeight
    val isExpandedSpan = tile.effectiveRowSpan >= 2 || tile.colSpan >= 2
    val badgeSize = (unitHeight * (if (hasAnyBottomBadge) 0.35f else if (isExpandedSpan) 0.44f else 0.40f)).coerceIn(26.dp, 44.dp)
    val iconGlyphSize = (badgeSize * 0.50f).coerceIn(14.dp, 22.dp)
    val titleFontSp = ((unitHeight.value * (if (tile.colSpan >= 2) 0.125f else 0.115f)).coerceIn(10.0f, 13.5f)).sp

    val talkBackContentDesc = buildString {
        append(tile.title)
        if (tile.roomOrSubtitle.isNotBlank()) {
            append(", ${tile.roomOrSubtitle}")
        }
        if (hasApplianceXrayBadge) {
            append(", ${inferredAppliance.label}")
        }
    }
    val talkBackStateDesc = when {
        isUnlinked -> "Unlinked, tap to link"
        isPendingAck -> "Sending command, awaiting hardware confirmation"
        isHealing -> "Auto-healing connection"
        isShadowQueued -> "Queued for wake"
        isOffline -> "Offline"
        tile.isThermostatTile -> "${if (isTileActive) "Heating" else "Off"}, ${"%.1f".format(tile.temperature)} degrees"
        isTileActive -> if (isManualHoldActive) "On, 30 minute manual hold active" else "On"
        else -> if (isManualHoldActive) "Off, 30 minute manual hold active" else "Off"
    }

    CompositionLocalProvider(LocalDensity provides fixedFontDensity) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(animatedCardHeight)
                .zIndex(if (isBeingDragged || isSelectedForSwap || isDropTargetHighlighted) 30f else 0f)
                .then(
                    if (isEditMode) {
                        Modifier.pointerInput(tile.id) {
                            detectDragGestures(
                                onDragStart = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentOnDragStart()
                                },
                                onDragEnd = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentOnDragEnd()
                                },
                                onDragCancel = { currentOnDragEnd() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    currentOnDragDelta(dragAmount)
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = if (isBeingDragged) dragOffset.x else 0f
                        translationY = if (isBeingDragged) dragOffset.y else 0f
                        scaleX = launcherScale
                        scaleY = launcherScale
                        alpha = launcherAlpha
                    }
            ) {
                SfHouseSunkenTileContainer(
                    outerCornerRadius = 18.dp,
                    innerCornerRadius = 14.dp,
                    sunkenInsetPadding = 4.dp,
                    innerBorderColor = innerBorder,
                    innerBackgroundColor = GhTileSurface,
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            contentDescription = talkBackContentDesc
                            stateDescription = talkBackStateDesc
                        }
                        .combinedClickable(
                            onClick = {
                                when {
                                    isEditMode -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        currentOnSelectOrSwap()
                                    }
                                    isGroupCardTile -> showGroupCardPopupModal = true
                                    isEnergyMonitorTile -> showEnergyMonitorModal = true
                                    else -> {
                                        onActivateHeroDial(tile)
                                    }
                                }
                            },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (!isEditMode) {
                                    onToggleEditMode()
                                } else {
                                    currentOnSelectOrSwap()
                                }
                            }
                        )
                ) {
                    val isDark = ClaraThemeManager.isDarkMode
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Tile Visual Content (Light 2.5.dp blur ONLY when selected in Edit Mode)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (isEditMode && isSelectedForSwap) Modifier.blur(2.5.dp) else Modifier)
                                .then(
                                    if (isTileActive) {
                                        Modifier.background(
                                            Brush.linearGradient(
                                                colors = palette.activeGradient,
                                                start = Offset(0f, 0f),
                                                end = Offset(400f, 400f)
                                            )
                                        )
                                    } else {
                                        Modifier.background(
                                            Brush.linearGradient(
                                                colors = if (isDark) {
                                                    listOf(
                                                        Color(0xFF2D313B),
                                                        Color(0xFF252830),
                                                        Color(0xFF20232A)
                                                    )
                                                } else {
                                                    listOf(
                                                        Color(0xFFFFFFFF),
                                                        Color(0xFFF6F8FC),
                                                        Color(0xFFEDF1F7)
                                                    )
                                                },
                                                start = Offset(0f, 0f),
                                                end = Offset(380f, 380f)
                                            )
                                        )
                                    }
                                )
                        ) {
                            when {
                                tile.type == TileType.PHOTO_SLIDESHOW && (tile.colSpan >= 2 || tile.effectiveRowSpan >= 2) -> {
                                    GhEdgeToEdgePhotoSlideshowTile(
                                        tile = tile,
                                        bridgeSettings = bridgeSettings,
                                        isEditMode = isEditMode,
                                        onUpdateTile = onUpdateTile,
                                        onToggleEditMode = onToggleEditMode,
                                        onToggleColSpan = onToggleColSpan,
                                        onToggleHeight = onToggleHeight,
                                        onDeleteTile = onDeleteTile
                                    )
                                }
                                tile.type == TileType.ENERGY_MONITOR && tile.colSpan >= 2 -> {
                                    GhEnergyMonitorTileContent(
                                        tile = tile,
                                        allTiles = allTiles,
                                        onOpenDetailModal = {
                                            if (!isEditMode) showEnergyMonitorModal = true
                                        }
                                    )
                                }

                                tile.type == TileType.GROUP_CARD && tile.colSpan >= 2 -> {
                                    val members = remember(allTiles, tile.id) {
                                        allTiles.filter { it.groupCardId == tile.id && it.id != tile.id }
                                    }
                                    GhGroupCardTileContent(
                                        groupTile = tile,
                                        memberTiles = members,
                                        isDropTargetHighlighted = isDropTargetHighlighted,
                                        onOpenGroupModal = {
                                            if (!isEditMode) showGroupCardPopupModal = true
                                        },
                                        onToggleAllMembers = { turnOn ->
                                            members.forEach { m ->
                                                onUpdateSpecificTile(
                                                    m.copy(
                                                        isActive = turnOn,
                                                        isSocket2Active = if (m.multiGangSocketCount >= 2) turnOn else m.isSocket2Active
                                                    )
                                                )
                                            }
                                        }
                                    )
                                }

                                tile.isCameraTile && tile.isLiveFeedActive && tile.isConfiguredAndLinked && (tile.colSpan >= 2 || tile.effectiveRowSpan >= 2) -> {
                                    GhEdgeToEdgeCameraTile(
                                        tile = tile,
                                        bridgeSettings = bridgeSettings,
                                        isOverlayModalOpen = isOverlayModalOpen,
                                        isEditMode = isEditMode,
                                        onUpdateTile = onUpdateTile,
                                        onExpandCamera = onExpandCamera,
                                        onToggleEditMode = onToggleEditMode,
                                        onToggleColSpan = onToggleColSpan,
                                        onToggleHeight = onToggleHeight,
                                        onDeleteTile = onDeleteTile
                                    )
                                }

                                else -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(badgeSize)
                                                .clip(CircleShape)
                                                .background(
                                                    when {
                                                        isPendingAck || isHealing -> Brush.radialGradient(
                                                            colors = listOf(
                                                                Color(0xFF00E5FF).copy(alpha = 0.28f),
                                                                Color(0xFF00E5FF).copy(alpha = 0.10f)
                                                            )
                                                        )
                                                        isOffline -> Brush.radialGradient(
                                                            colors = listOf(
                                                                GhAccentRed.copy(alpha = 0.24f),
                                                                GhAccentRed.copy(alpha = 0.08f)
                                                            )
                                                        )
                                                        isUnlinked || isShadowQueued -> Brush.radialGradient(
                                                            colors = listOf(
                                                                GhAccentWarm.copy(alpha = 0.24f),
                                                                GhAccentWarm.copy(alpha = 0.08f)
                                                            )
                                                        )
                                                        isTileActive -> Brush.verticalGradient(
                                                            colors = listOf(
                                                                Color.White.copy(alpha = 0.28f),
                                                                Color.White.copy(alpha = 0.14f)
                                                            )
                                                        )
                                                        else -> Brush.radialGradient(
                                                            colors = listOf(
                                                                palette.primaryColor.copy(alpha = 0.22f),
                                                                palette.primaryColor.copy(alpha = 0.10f)
                                                            )
                                                        )
                                                    }
                                                )
                                                .border(
                                                    width = 1.2.dp,
                                                    color = when {
                                                        isPendingAck || isHealing -> Color(0xFF00E5FF).copy(alpha = 0.75f)
                                                        isOffline -> GhAccentRed.copy(alpha = 0.55f)
                                                        isUnlinked || isShadowQueued -> GhAccentWarm.copy(alpha = 0.55f)
                                                        isTileActive -> Color.White.copy(alpha = 0.55f)
                                                        else -> palette.primaryColor.copy(alpha = 0.45f)
                                                    },
                                                    shape = CircleShape
                                                )
                                                .then(
                                                    if (!isEditMode &&
                                                        tile.isConfiguredAndLinked &&
                                                        !isGroupCardTile &&
                                                        !isEnergyMonitorTile &&
                                                        !tile.isCameraTile &&
                                                        tile.serviceClass == null &&
                                                        tile.type != TileType.CLOCK_WEATHER &&
                                                        tile.type != TileType.PHOTO_SLIDESHOW
                                                    ) {
                                                        Modifier.clickable {
                                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            val toggled = when {
                                                                tile.channelId == "SPLIT_SOCKET_1" ->
                                                                    tile.copy(isActive = !tile.isActive)
                                                                tile.channelId == "SPLIT_SOCKET_2" ->
                                                                    tile.copy(isSocket2Active = !tile.isSocket2Active)
                                                                tile.isThermostatTile -> {
                                                                    val nextOn = !tile.isActive || tile.hvacMode.equals("OFF", ignoreCase = true)
                                                                    tile.copy(
                                                                        isActive = nextOn,
                                                                        hvacMode = if (nextOn) "HEAT" else "OFF"
                                                                    )
                                                                }
                                                                tile.isVehicleTile -> {
                                                                    val nextLock = !tile.vehicleLocked
                                                                    tile.copy(
                                                                        vehicleLocked = nextLock,
                                                                        isActive = nextLock,
                                                                        hvacMode = "VEHICLE_LOCK"
                                                                    )
                                                                }
                                                                tile.multiGangSocketCount >= 2 -> {
                                                                    val nextOn = !(tile.isActive || tile.isSocket2Active)
                                                                    tile.copy(
                                                                        isActive = nextOn,
                                                                        isSocket2Active = nextOn
                                                                    )
                                                                }
                                                                else -> tile.copy(isActive = !tile.isActive)
                                                            }
                                                            onUpdateTile(toggled)
                                                        }
                                                    } else {
                                                        Modifier
                                                    }
                                                )
                                        ) {
                                            Icon(
                                                imageVector = when {
                                                    isPendingAck || isHealing -> Icons.Outlined.Sync
                                                    isOffline -> Icons.Outlined.CloudOff
                                                    tile.isGroupCardTile -> resolveGroupCategoryIcon(tile.groupCategoryIcon)
                                                    else -> iconForTile(tile)
                                                },
                                                contentDescription = tile.title,
                                                tint = when {
                                                    isPendingAck || isHealing -> Color(0xFF00E5FF)
                                                    isOffline -> GhAccentRed
                                                    isUnlinked || isShadowQueued -> GhAccentWarm
                                                    isTileActive -> Color.White
                                                    else -> palette.primaryColor
                                                },
                                                modifier = Modifier.size(iconGlyphSize)
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(if (hasAnyBottomBadge) 4.dp else 7.dp))

                                        Text(
                                            text = if (isEditMode && !tile.isVisible) "${tile.title} (Hidden)" else tile.title,
                                            color = if (isTileActive) Color.White else GhTextPrimary,
                                            fontSize = titleFontSp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = GhFont,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        // Closed-Loop Status Badge, 30-Minute Hold Badge, Identify Relay Badge, Smart Plug X-Ray, or Causal Subtitle
                                        if (hasStatusBadge) {
                                            Spacer(modifier = Modifier.height(3.dp))
                                            val activeQueuedIntent = queuedIntentForTile
                                            val queuedSummary = if (activeQueuedIntent != null) {
                                                "Queued (${if (activeQueuedIntent.desiredIsActive) "ON" else "OFF"})"
                                            } else {
                                                "Queued for Wake"
                                            }
                                            val (badgeColor, badgeText) = when {
                                                isVerifyingControl -> Color(0xFF00E5FF) to "Verifying Control..."
                                                isNeedsReauth -> GhAccentWarm to "Sign In Required"
                                                isPendingAck -> Color(0xFF00E5FF) to "Awaiting ACK..."
                                                isHealing -> Color(0xFF00E5FF) to "Self-Healing..."
                                                isShadowQueued -> GhAccentWarm to queuedSummary
                                                isDegraded -> GhAccentWarm to "Degraded Link"
                                                isOffline -> GhAccentRed to (
                                                        if (tile.effectiveLifecycleState == DeviceLifecycleState.QUARANTINED_ERROR)
                                                            "Attention Needed" else "Offline"
                                                        )
                                                else -> GhAccentWarm to "Tap to Link"
                                            }
                                            Surface(
                                                color = badgeColor.copy(alpha = 0.18f),
                                                shape = RoundedCornerShape(50),
                                                border = BorderStroke(0.9.dp, badgeColor.copy(alpha = 0.65f)),
                                                modifier = if ((isUnlinked || isNeedsReauth) && !isEditMode) {
                                                    Modifier.clickable { onOpenSetupWizard() }
                                                } else {
                                                    Modifier
                                                }
                                            ) {
                                                Text(
                                                    text = badgeText,
                                                    color = badgeColor,
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontFamily = GhFont,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        } else if (isNewlyAutoAddedPlug && !isEditMode) {
                                            // F-26 & F-27: One-tap Identify (Click Relay) badge on newly auto-added plugs
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Surface(
                                                color = Color(0xFF00E5FF).copy(alpha = 0.18f),
                                                shape = RoundedCornerShape(50),
                                                border = BorderStroke(0.9.dp, Color(0xFF00E5FF).copy(alpha = 0.65f)),
                                                modifier = Modifier.clickable {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    onUpdateTile(tile.copy(isActive = !tile.isActive))
                                                }
                                            ) {
                                                Text(
                                                    text = "Identify (Click Relay)",
                                                    color = Color(0xFF00E5FF),
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontFamily = GhFont,
                                                    maxLines = 1,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        } else if (hasApplianceXrayBadge && bridgeSettings.showRoomLabelsOnTiles) {
                                            Spacer(modifier = Modifier.height(3.dp))
                                            val xrayLabel = buildString {
                                                append(inferredAppliance.suggestedSocketName.ifBlank { inferredAppliance.label })
                                                if (tile.powerWatts >= 1f) {
                                                    append(" \u2022 ${"%.0f".format(tile.powerWatts)}W")
                                                }
                                            }
                                            Surface(
                                                color = if (isTileActive) Color.Black.copy(alpha = 0.24f) else GhTileSurfaceElevated,
                                                shape = RoundedCornerShape(50),
                                                border = BorderStroke(
                                                    0.8.dp,
                                                    if (isTileActive) Color.White.copy(alpha = 0.45f) else palette.primaryColor.copy(alpha = 0.45f)
                                                )
                                            ) {
                                                Text(
                                                    text = xrayLabel,
                                                    color = if (isTileActive) Color.White else palette.primaryColor,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = GhFont,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                )
                                            }
                                        } else if (hasHoldBadge) {
                                            Spacer(modifier = Modifier.height(3.dp))
                                            val holdMinsLeft = (((tile.manualHoldUntilEpochMs - System.currentTimeMillis()).coerceAtLeast(0L) / 60_000L) + 1L)
                                                .coerceAtMost(60L)
                                            val holdActor = tile.lastCausalActor.takeIf { it.isNotBlank() }?.let { " \u2022 $it" }.orEmpty()
                                            Surface(
                                                color = if (isTileActive) Color.Black.copy(alpha = 0.24f) else GhTileSurfaceElevated,
                                                shape = RoundedCornerShape(50),
                                                border = BorderStroke(
                                                    0.8.dp,
                                                    if (isTileActive) Color.White.copy(alpha = 0.45f) else GhAccentWarm.copy(alpha = 0.55f)
                                                )
                                            ) {
                                                Text(
                                                    text = "Hold ${holdMinsLeft}m$holdActor",
                                                    color = if (isTileActive) Color.White else GhAccentWarm,
                                                    fontSize = 8.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = GhFont,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                )
                                            }
                                        } else if (hasCausalBadge) {
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text(
                                                text = causalSubtitleText,
                                                color = if (isTileActive) Color.White.copy(alpha = 0.86f) else GhTextSecondary,
                                                fontSize = 8.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = GhFont,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // =========================================================
                        // SELECTED-TILE-ONLY EDIT OVERLAY (Light 24% scrim, zero circles)
                        // =========================================================
                        if (isEditMode && (isSelectedForSwap || isBeingDragged || isDropTargetHighlighted)) {
                            val handleAccent = if (isDropTargetHighlighted) GhAccentGreen else Color(0xFF00E5FF)

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.24f))
                                    .border(
                                        width = 2.2.dp,
                                        color = handleAccent,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                            ) {
                                // Top-Right Remove Tile Button
                                if (tile.type != TileType.CLOCK_WEATHER) {
                                    Surface(
                                        color = Color(0xFF14171D).copy(alpha = 0.95f),
                                        shape = CircleShape,
                                        border = BorderStroke(1.2.dp, GhAccentRed),
                                        shadowElevation = 6.dp,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .size(28.dp)
                                            .zIndex(20f)
                                            .clickable { onDeleteTile() }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Remove Tile",
                                                tint = GhAccentRed,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }

                                // Centered Move / Span Pill on Selected Tile
                                Surface(
                                    color = Color(0xFF101319).copy(alpha = 0.92f),
                                    shape = RoundedCornerShape(50),
                                    border = BorderStroke(1.2.dp, handleAccent),
                                    shadowElevation = 6.dp,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .clickable {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onToggleColSpan()
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DragIndicator,
                                            contentDescription = "Drag Tile",
                                            tint = handleAccent,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = "Drag \u2022 ${tile.colSpan}\u00D7${tile.effectiveRowSpan}",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = GhFont
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

    if (showGroupCardPopupModal && isGroupCardTile && !isEditMode) {
        GroupCardPopupModal(
            groupTile = tile,
            allTiles = allTiles,
            bridgeSettings = bridgeSettings,
            musicProvider = musicProvider,
            isMusicPlaying = isMusicPlaying,
            currentTrack = currentTrack,
            onUpdateGroupTile = onUpdateTile,
            onUpdateMemberTile = onUpdateSpecificTile,
            onDismiss = { showGroupCardPopupModal = false }
        )
    }

    if (showEnergyMonitorModal && isEnergyMonitorTile && !isEditMode) {
        EnergyMonitorDetailModal(
            allTiles = allTiles,
            onDismiss = { showEnergyMonitorModal = false }
        )
    }
}

fun iconForTile(tile: SmartTile): ImageVector {
    when (tile.serviceClass) {
        ServiceClass.RADIO -> return Icons.Outlined.Radio
        ServiceClass.TV_TRACKER -> return Icons.Outlined.LiveTv
        ServiceClass.CALENDAR_HUB -> return Icons.Outlined.CalendarMonth
        ServiceClass.FOOTBALL_TRACKER -> return Icons.Outlined.SportsSoccer
        ServiceClass.GUEST_WIFI -> return Icons.Outlined.QrCode2
        ServiceClass.MUSIC -> return Icons.Outlined.MusicNote
        ServiceClass.WEATHER -> return Icons.Outlined.Schedule
        else -> {}
    }
    return when (tile.deviceSubtype) {
        DeviceSubtype.THERMOSTAT,
        DeviceSubtype.RADIATOR,
        DeviceSubtype.TRV -> Icons.Outlined.Thermostat
        DeviceSubtype.TOWER_FAN -> Icons.Outlined.Air
        DeviceSubtype.AMBIENT_SYNC -> Icons.Outlined.SettingsInputHdmi
        DeviceSubtype.LED_STRIP -> Icons.Outlined.Palette
        DeviceSubtype.BULB -> Icons.Outlined.WbSunny
        DeviceSubtype.SMART_PLUG,
        DeviceSubtype.WALL_SOCKET,
        DeviceSubtype.RELAY -> Icons.Outlined.Power
        DeviceSubtype.TV -> Icons.Outlined.Tv
        DeviceSubtype.SPEAKER,
        DeviceSubtype.CAST_GROUP -> Icons.Outlined.Speaker
        DeviceSubtype.DOORBELL -> Icons.Outlined.Doorbell
        DeviceSubtype.CAMERA -> Icons.Outlined.Videocam
        DeviceSubtype.LOCK -> Icons.Outlined.Lock
        DeviceSubtype.BLIND,
        DeviceSubtype.GARAGE_DOOR -> Icons.Outlined.Blinds
        DeviceSubtype.SECURITY_HUB -> Icons.Outlined.Shield
        DeviceSubtype.VEHICLE -> Icons.Outlined.DirectionsCar
        DeviceSubtype.EV_CHARGER -> Icons.Outlined.Bolt
        DeviceSubtype.KETTLE,
        DeviceSubtype.COFFEE_MACHINE -> Icons.Outlined.Coffee
        DeviceSubtype.AIR_FRYER -> Icons.Outlined.LocalFireDepartment
        DeviceSubtype.WASHER -> Icons.Outlined.LocalLaundryService
        DeviceSubtype.POND_PUMP -> Icons.Outlined.WaterDrop
        DeviceSubtype.VACUUM -> Icons.Outlined.CleaningServices
        DeviceSubtype.SENSOR -> Icons.Outlined.Sensors
        DeviceSubtype.MATTER_BRIDGE,
        DeviceSubtype.THREAD_BORDER_ROUTER -> Icons.Outlined.Router
        DeviceSubtype.MQTT_BROKER -> Icons.Outlined.Wifi
        DeviceSubtype.GROUP -> Icons.Outlined.DashboardCustomize
        else -> iconForTileType(tile.type)
    }
}

fun iconForTileType(type: TileType): ImageVector = when (type) {
    TileType.CLOCK_WEATHER -> Icons.Outlined.Schedule
    TileType.IMOU_CAMERA, TileType.RING_CAMERA -> Icons.Outlined.Videocam
    TileType.NEST_DOORBELL, TileType.EZVIZ_DOORBELL -> Icons.Outlined.Doorbell
    TileType.NEST_THERMOSTAT, TileType.TUYA_RADIATOR -> Icons.Outlined.Thermostat
    TileType.SMARTKNIGHT_PLUG,
    TileType.SMARTLIFE_DEVICE,
    TileType.TAPO_DEVICE,
    TileType.SHELLY_RELAY,
    TileType.SONOFF_SWITCH -> Icons.Outlined.Power
    TileType.MATTER_DEVICE -> Icons.Outlined.Router
    TileType.MQTT_DEVICE -> Icons.Outlined.Wifi
    TileType.LG_TV -> Icons.Outlined.Tv
    TileType.HUE_LIGHT -> Icons.Outlined.WbSunny
    TileType.GOVEE_LIGHT -> Icons.Outlined.Palette
    TileType.TWINKLY_LIGHT -> Icons.Outlined.AutoAwesome
    TileType.AIRROBO_VACUUM -> Icons.Outlined.CleaningServices
    TileType.PROBREEZE_FAN -> Icons.Outlined.Air
    TileType.SMART_LOCK -> Icons.Outlined.Lock
    TileType.SMART_COVER -> Icons.Outlined.Blinds
    TileType.SMART_SENSOR -> Icons.Outlined.Sensors
    TileType.NEST_MINI -> Icons.Outlined.Speaker
    TileType.MUSIC_PLAYER -> Icons.Outlined.MusicNote
    TileType.JARVIS_SCENES -> Icons.Outlined.AutoAwesome
    TileType.PHOTO_SLIDESHOW -> Icons.Outlined.PhotoLibrary
    TileType.GROUP_CARD -> Icons.Outlined.DashboardCustomize
    TileType.ENERGY_MONITOR -> Icons.Outlined.Bolt
    TileType.MACRO_TILE -> Icons.Outlined.PlayCircle
    TileType.JAGUAR_VEHICLE,
    TileType.MERCEDES_VEHICLE -> Icons.Outlined.DirectionsCar
    TileType.LYTMI_SYNC_BOX -> Icons.Outlined.SettingsInputHdmi
    TileType.SECURITY_CENTER -> Icons.Outlined.Shield
}

fun accentForTileType(tile: SmartTile): Color = resolvePaletteForTile(tile).primaryColor

@Composable
fun GhMusicPlayerContent(
    provider: MusicProvider,
    isPlaying: Boolean,
    track: String,
    @Suppress("UNUSED_PARAMETER") volume: Float,
    @Suppress("UNUSED_PARAMETER") onVolumeChange: (Float) -> Unit,
    onOpenPopupPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkip: () -> Unit,
    onPrevious: () -> Unit
) {
    val activeCastTarget by JarvisLiveBridge.activeCastTargetNameFlow.collectAsState()

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .clickable { onOpenPopupPlayer() }
        ) {
            Surface(
                color = GhTileSurfaceElevated,
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.MusicNote,
                        contentDescription = "Open Music Browser",
                        tint = GhAccentGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.ifBlank { "Tap to Browse Music" },
                    color = GhTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = GhFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${provider.label} \u2022 $activeCastTarget",
                    color = GhTextSecondary,
                    fontSize = 10.sp,
                    fontFamily = GhFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = GhTextSecondary, modifier = Modifier.size(18.dp))
            }
            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(34.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = GhAccentGreen,
                    contentColor = Color(0xFF141518)
                )
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onSkip, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = GhTextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}
