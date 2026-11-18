package com.jarvis.smarthomelauncher.engines

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.Context
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// MEDIUM-TABLET (1280 x 800 dp) VIEWPORT DENSITY SCALER + USER DPI SCALING
// ---------------------------------------------------------------------------
@Composable
fun rememberMediumTabletViewportDensity(
    bridgeSettings: BridgeSettings
): Density = rememberMediumTabletViewportDensity(
    dpiScaleFactor = bridgeSettings.effectiveDpiScaleFactor
)

@Composable
fun rememberMediumTabletViewportDensity(
    dpiScaleFactor: Float = 1.0f
): Density {
    val configuration = LocalConfiguration.current
    val baseDensity = LocalDensity.current
    val screenW = configuration.screenWidthDp.toFloat().coerceAtLeast(320f)
    val screenH = configuration.screenHeightDp.toFloat().coerceAtLeast(320f)
    val clampedDpi = dpiScaleFactor.coerceIn(0.70f, 1.50f)
    val accessibleFontScale = baseDensity.fontScale.coerceIn(0.85f, 1.65f)

    return remember(screenW, screenH, baseDensity.density, accessibleFontScale, clampedDpi) {
        val isLandscape = screenW >= screenH
        val refW = if (isLandscape) 1280f else 800f
        val refH = if (isLandscape) 800f else 1280f
        val scaleW = screenW / refW
        val scaleH = screenH / refH
        val viewportScale = (((scaleW * 0.52f) + (scaleH * 0.48f)) * clampedDpi).coerceIn(0.50f, 2.10f)
        Density(
            density = baseDensity.density * viewportScale,
            fontScale = accessibleFontScale
        )
    }
}

// ---------------------------------------------------------------------------
// GRID-SNAPPING HELPER FOR OUTER 4-POINT CARD RESIZING
// ---------------------------------------------------------------------------
fun snapWeightToGridStep(
    rawWeight: Float,
    totalWeightSum: Float,
    gridDivisions: Int = 12,
    minWeight: Float = 0.50f,
    maxWeight: Float = totalWeightSum - minWeight
): Float {
    val step = (totalWeightSum / gridDivisions.coerceAtLeast(4).toFloat()).coerceAtLeast(0.08f)
    val snapped = (rawWeight / step).roundToInt() * step
    return snapped.coerceIn(minWeight, maxWeight)
}

// ---------------------------------------------------------------------------
// UNIFIED FULL-SCREEN GRID CARD MODEL & PERSISTENCE
// - Every Dashboard Card (Clock/Hero, Weather, Volume, plus any user-added
//   cards like Jaguar Garage, Live Radio, Security Center, Energy, etc.)
//   lives on the same unified tile-sized grid as device tiles.
// ---------------------------------------------------------------------------
data class UnifiedCardState(
    val id: String,
    val slot: DashboardCardSlot,
    val widgetType: DashboardWidgetType,
    val gridCol: Int,
    val gridRow: Int,
    val colSpan: Int,
    val rowSpan: Int,
    val zoomScale: Float = 1.0f,
    val isExtraCard: Boolean = false
)

fun defaultUnifiedDashboardCards(bridgeSettings: BridgeSettings): List<UnifiedCardState> {
    val heroWidget = bridgeSettings.dashboardSlotWidgetTypes[DashboardCardSlot.HERO_CLOCK.key]
        ?: DashboardWidgetType.CLOCK_HERO
    val weatherWidget = bridgeSettings.dashboardSlotWidgetTypes[DashboardCardSlot.ROOM_PHOTO.key]
        ?: DashboardWidgetType.WEATHER
    val volumeWidget = bridgeSettings.dashboardSlotWidgetTypes[DashboardCardSlot.VOLUME_SLIDERS.key]
        ?: DashboardWidgetType.VOLUME_SLIDERS

    val heroZoom = (bridgeSettings.dashboardCardZoomScales[DashboardCardSlot.HERO_CLOCK.key] ?: 1.0f)
        .coerceIn(0.70f, 1.40f)
    val weatherZoom = (bridgeSettings.dashboardCardZoomScales[DashboardCardSlot.ROOM_PHOTO.key] ?: 1.0f)
        .coerceIn(0.70f, 1.40f)
    val volumeZoom = (bridgeSettings.dashboardCardZoomScales[DashboardCardSlot.VOLUME_SLIDERS.key] ?: 1.0f)
        .coerceIn(0.70f, 1.40f)

    return listOf(
        UnifiedCardState(
            id = DashboardCardSlot.HERO_CLOCK.key,
            slot = DashboardCardSlot.HERO_CLOCK,
            widgetType = heroWidget,
            gridCol = 0,
            gridRow = 0,
            colSpan = 4,
            rowSpan = 2,
            zoomScale = heroZoom,
            isExtraCard = false
        ),
        UnifiedCardState(
            id = DashboardCardSlot.ROOM_PHOTO.key,
            slot = DashboardCardSlot.ROOM_PHOTO,
            widgetType = weatherWidget,
            gridCol = 4,
            gridRow = 0,
            colSpan = 4,
            rowSpan = 2,
            zoomScale = weatherZoom,
            isExtraCard = false
        ),
        UnifiedCardState(
            id = DashboardCardSlot.VOLUME_SLIDERS.key,
            slot = DashboardCardSlot.VOLUME_SLIDERS,
            widgetType = volumeWidget,
            gridCol = 0,
            gridRow = 2,
            colSpan = 3,
            rowSpan = 2,
            zoomScale = volumeZoom,
            isExtraCard = false
        )
    )
}

fun saveUnifiedDashboardCards(context: Context, cards: List<UnifiedCardState>) {
    val arr = JSONArray()
    for (c in cards) {
        if (c.slot == DashboardCardSlot.DEVICE_TILES && !c.isExtraCard) continue
        arr.put(
            JSONObject().apply {
                put("id", c.id)
                put("slot", c.slot.key)
                put("widgetType", c.widgetType.name)
                put("gridCol", c.gridCol)
                put("gridRow", c.gridRow)
                put("colSpan", c.colSpan.coerceIn(1, 12))
                put("rowSpan", c.rowSpan.coerceIn(1, 12))
                put("zoomScale", c.zoomScale.coerceIn(0.70f, 1.40f).toDouble())
                put("isExtraCard", c.isExtraCard)
            }
        )
    }
    DashboardLayoutStore.setUnifiedCardsJson(context, arr.toString())
}

fun loadUnifiedDashboardCards(
    context: Context,
    bridgeSettings: BridgeSettings
): List<UnifiedCardState> {
    val raw = DashboardLayoutStore.getUnifiedCardsJson(context)
    if (raw.isNullOrBlank()) {
        return defaultUnifiedDashboardCards(bridgeSettings)
    }
    return try {
        val arr = JSONArray(raw)
        val list = mutableListOf<UnifiedCardState>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val slotKey = o.optString("slot", DashboardCardSlot.ROOM_PHOTO.key)
            val slot = DashboardCardSlot.fromKey(slotKey) ?: DashboardCardSlot.ROOM_PHOTO
            val isExtra = o.optBoolean("isExtraCard", false)
            if (slot == DashboardCardSlot.DEVICE_TILES && !isExtra) continue
            val wt = runCatching {
                DashboardWidgetType.valueOf(o.optString("widgetType", slot.defaultWidgetType.name))
            }.getOrDefault(slot.defaultWidgetType)
            if (wt == DashboardWidgetType.DEVICE_TILES) continue
            list.add(
                UnifiedCardState(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    slot = slot,
                    widgetType = wt,
                    gridCol = o.optInt("gridCol", 0).coerceAtLeast(0),
                    gridRow = o.optInt("gridRow", 0).coerceAtLeast(0),
                    colSpan = o.optInt("colSpan", 3).coerceIn(2, 12),
                    rowSpan = o.optInt("rowSpan", 2).coerceIn(1, 12),
                    zoomScale = o.optDouble("zoomScale", 1.0).toFloat().coerceIn(0.70f, 1.40f),
                    isExtraCard = isExtra
                )
            )
        }
        if (list.isEmpty()) defaultUnifiedDashboardCards(bridgeSettings) else list
    } catch (_: Exception) {
        defaultUnifiedDashboardCards(bridgeSettings)
    }
}

// ---------------------------------------------------------------------------
// 25-STEP UNDO / REDO LAYOUT HISTORY STACK (LauncherUndoRedoManager)
// ---------------------------------------------------------------------------
data class TileGridLayoutSnapshot(
    val tileId: String,
    val colSpan: Int,
    val rowSpan: Int,
    val gridCol: Int,
    val gridRow: Int,
    val isTall: Boolean,
    val isVisible: Boolean,
    val groupCardId: String
)

data class LauncherLayoutSnapshot(
    val cardOrder: List<DashboardCardSlot>,
    val cardWidthWeights: Map<String, Float>,
    val cardHeightWeights: Map<String, Float>,
    val cardZoomScales: Map<String, Float>,
    val slotWidgetTypes: Map<String, DashboardWidgetType>,
    val tileCoordinates: List<TileGridLayoutSnapshot>,
    val unifiedCards: List<UnifiedCardState> = emptyList()
)

class LauncherUndoRedoManager(private val maxHistorySteps: Int = 25) {
    private val undoStack = ArrayDeque<LauncherLayoutSnapshot>()
    private val redoStack = ArrayDeque<LauncherLayoutSnapshot>()

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    val undoCount: Int
        get() = undoStack.size

    val redoCount: Int
        get() = redoStack.size

    private fun syncFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    fun captureCurrentSnapshot(
        cardOrder: List<DashboardCardSlot>,
        cardWidthWeights: Map<String, Float>,
        cardHeightWeights: Map<String, Float>,
        cardZoomScales: Map<String, Float>,
        slotWidgetTypes: Map<String, DashboardWidgetType>,
        tiles: List<SmartTile>,
        unifiedCards: List<UnifiedCardState> = emptyList()
    ): LauncherLayoutSnapshot {
        return LauncherLayoutSnapshot(
            cardOrder = cardOrder.toList(),
            cardWidthWeights = cardWidthWeights.toMap(),
            cardHeightWeights = cardHeightWeights.toMap(),
            cardZoomScales = cardZoomScales.toMap(),
            slotWidgetTypes = slotWidgetTypes.toMap(),
            tileCoordinates = tiles.map { t ->
                TileGridLayoutSnapshot(
                    tileId = t.id,
                    colSpan = t.colSpan,
                    rowSpan = t.effectiveRowSpan,
                    gridCol = t.gridCol,
                    gridRow = t.gridRow,
                    isTall = t.effectiveRowSpan > 1,
                    isVisible = t.isVisible,
                    groupCardId = t.groupCardId
                )
            },
            unifiedCards = unifiedCards.toList()
        )
    }

    fun pushSnapshot(snapshot: LauncherLayoutSnapshot) {
        if (undoStack.lastOrNull() == snapshot) return
        if (undoStack.size >= maxHistorySteps) {
            undoStack.removeFirst()
        }
        undoStack.addLast(snapshot)
        redoStack.clear()
        syncFlags()
    }

    fun pushSnapshot(
        cardOrder: List<DashboardCardSlot>,
        cardWidthWeights: Map<String, Float>,
        cardHeightWeights: Map<String, Float>,
        cardZoomScales: Map<String, Float>,
        slotWidgetTypes: Map<String, DashboardWidgetType>,
        tiles: List<SmartTile>,
        unifiedCards: List<UnifiedCardState> = emptyList()
    ) {
        pushSnapshot(
            captureCurrentSnapshot(
                cardOrder = cardOrder,
                cardWidthWeights = cardWidthWeights,
                cardHeightWeights = cardHeightWeights,
                cardZoomScales = cardZoomScales,
                slotWidgetTypes = slotWidgetTypes,
                tiles = tiles,
                unifiedCards = unifiedCards
            )
        )
    }

    fun undo(currentSnapshot: LauncherLayoutSnapshot): LauncherLayoutSnapshot? {
        if (undoStack.isEmpty()) return null
        val previous = undoStack.removeLast()
        if (redoStack.size >= maxHistorySteps) {
            redoStack.removeFirst()
        }
        redoStack.addLast(currentSnapshot)
        syncFlags()
        return previous
    }

    fun redo(currentSnapshot: LauncherLayoutSnapshot): LauncherLayoutSnapshot? {
        if (redoStack.isEmpty()) return null
        val next = redoStack.removeLast()
        if (undoStack.size >= maxHistorySteps) {
            undoStack.removeFirst()
        }
        undoStack.addLast(currentSnapshot)
        syncFlags()
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        syncFlags()
    }
}

@Composable
fun rememberLauncherUndoRedoManager(maxHistorySteps: Int = 25): LauncherUndoRedoManager {
    return remember(maxHistorySteps) {
        LauncherUndoRedoManager(maxHistorySteps = maxHistorySteps)
    }
}

// ---------------------------------------------------------------------------
// VOICE ASSISTANT COMPATIBILITY EXTENSIONS
// ---------------------------------------------------------------------------
fun ClaraVoiceAssistantManager.triggerImmediateVoicePrompt() {
    triggerManualWake("Listening...")
}

fun ClaraVoiceAssistantManager.configure(
    enableContinuousWakeWord: Boolean,
    @Suppress("UNUSED_PARAMETER") wakeWord: String
) {
    setAssistantEnabled(enableContinuousWakeWord)
}

// ---------------------------------------------------------------------------
// COMPACT SQUARE-TILE GRID METRICS (USED FOR FULL-SCREEN UNIFIED GRID)
// ---------------------------------------------------------------------------
data class ResponsiveGridMetrics(
    val gridColumns: Int,
    val visibleRows: Int,
    val outerPadding: Dp,
    val gridSpacing: Dp,
    val unitWidth: Dp,
    val unitHeight: Dp
)

fun computeResponsiveGridMetrics(
    availableWidth: Dp,
    availableHeight: Dp,
    requiredTileRows: Int = 0,
    dashboardTemplate: DashboardTemplate = DashboardTemplate.STANDARD_QUAD,
    gridSizePreset: DashboardGridSizePreset = DashboardGridSizePreset.COMPACT
): ResponsiveGridMetrics {
    val safeW = if (availableWidth != Dp.Infinity && availableWidth.value.isFinite() && availableWidth > 160.dp) {
        availableWidth
    } else {
        1180.dp
    }
    val safeH = if (availableHeight != Dp.Infinity && availableHeight.value.isFinite() && availableHeight > 120.dp) {
        availableHeight
    } else {
        720.dp
    }

    val isCompactMobile = safeW < 560.dp
    val gridSpacing = if (isCompactMobile) 6.dp else gridSizePreset.spacingDp.dp
    val outerPadding = if (isCompactMobile) 4.dp else 8.dp

    val baseColumns = when {
        safeW >= 900.dp -> gridSizePreset.tabletCols
        safeW >= 680.dp -> gridSizePreset.mediumCols
        safeW >= 480.dp -> gridSizePreset.compactCols
        else -> gridSizePreset.mobileCols
    }
    val gridColumns = when (dashboardTemplate) {
        DashboardTemplate.SECURITY_COMMAND -> baseColumns.coerceAtLeast(4)
        else -> baseColumns
    }

    val visibleRows = when {
        safeH >= 480.dp -> gridSizePreset.tabletVisibleRows
        safeH >= 340.dp -> (gridSizePreset.tabletVisibleRows - 1).coerceAtLeast(3)
        requiredTileRows >= 3 && safeH >= 280.dp -> 3
        else -> 2
    }

    val totalHorizontalSpacing = gridSpacing * (gridColumns - 1)
    val unitWidth = ((safeW - totalHorizontalSpacing) / gridColumns).coerceAtLeast(62.dp)

    val totalVerticalSpacing = gridSpacing * (visibleRows - 1).coerceAtLeast(1)
    val rawRowHeight = (safeH - totalVerticalSpacing) / visibleRows
    val unitHeight = minOf(rawRowHeight, unitWidth * 0.98f).coerceIn(64.dp, 108.dp)

    return ResponsiveGridMetrics(
        gridColumns = gridColumns,
        visibleRows = visibleRows,
        outerPadding = outerPadding,
        gridSpacing = gridSpacing,
        unitWidth = unitWidth,
        unitHeight = unitHeight
    )
}

// ---------------------------------------------------------------------------
// 2D UNIFIED GRID PACKING ENGINE FOR CARDS + FREE-ROAM TILES
// - Cards and tiles share the exact same cell coordinates `(col, row)`.
// - Displaced tiles pushed out of the way by a resized or moved card are
//   relocated to the nearest adjacent open cell and persisted via
//   `commitUnifiedCardAndTileCollisions`.
// ---------------------------------------------------------------------------
data class BentoTilePlacement(
    val tile: SmartTile,
    val col: Int,
    val row: Int,
    val colSpan: Int,
    val rowSpan: Int
)

fun rectsOverlap(
    c1: Int, r1: Int, w1: Int, h1: Int,
    c2: Int, r2: Int, w2: Int, h2: Int
): Boolean {
    return c1 < c2 + w2 && c1 + w1 > c2 && r1 < r2 + h2 && r1 + h1 > r2
}

private fun clampedRowOrZero(row: Int): Int = if (row < 0) 0 else row

fun computeUnifiedDashboardPlacements(
    cards: List<UnifiedCardState>,
    tiles: List<SmartTile>,
    gridColumns: Int,
    maxRowSpanLimit: Int = 4
): Pair<List<UnifiedCardState>, List<BentoTilePlacement>> {
    val safeCols = gridColumns.coerceIn(2, 62)
    val fullRowMask = (1L shl safeCols) - 1L
    val maxSearchRow = maxOf(64, (tiles.size * 2 / safeCols) + 48)
    var rowMasks = LongArray(maxSearchRow + maxRowSpanLimit + 8)
    var firstNonFullRow = 0

    fun ensureRowCapacity(targetRow: Int) {
        if (targetRow >= rowMasks.size) {
            rowMasks = rowMasks.copyOf(maxOf(targetRow + 32, rowMasks.size * 2))
        }
    }

    fun canFitAt(col: Int, row: Int, cSpan: Int, rSpan: Int): Boolean {
        if (col < 0 || row < 0 || col + cSpan > safeCols) return false
        ensureRowCapacity(row + rSpan)
        val spanMask = ((1L shl cSpan) - 1L) shl col
        for (dr in 0 until rSpan) {
            if ((rowMasks[row + dr] and spanMask) != 0L) return false
        }
        return true
    }

    fun markOccupied(col: Int, row: Int, cSpan: Int, rSpan: Int) {
        ensureRowCapacity(row + rSpan)
        val spanMask = ((1L shl cSpan) - 1L) shl col
        for (dr in 0 until rSpan) {
            rowMasks[row + dr] = rowMasks[row + dr] or spanMask
        }
        while (firstNonFullRow < rowMasks.size &&
            (rowMasks[firstNonFullRow] and fullRowMask) == fullRowMask
        ) {
            firstNonFullRow++
        }
    }

    // 1. Place Dashboard Widget Cards on the Unified Grid
    val resolvedCards = ArrayList<UnifiedCardState>(cards.size)
    for (card in cards) {
        val cSpan = card.colSpan.coerceIn(2, safeCols)
        val rSpan = card.rowSpan.coerceIn(1, 12)
        val maxColStart = (safeCols - cSpan).coerceAtLeast(0)
        val prefCol = card.gridCol.coerceIn(0, maxColStart)
        val prefRow = card.gridRow.coerceIn(0, maxSearchRow)
        if (canFitAt(prefCol, prefRow, cSpan, rSpan)) {
            markOccupied(prefCol, prefRow, cSpan, rSpan)
            resolvedCards.add(card.copy(gridCol = prefCol, gridRow = prefRow, colSpan = cSpan, rowSpan = rSpan))
        } else {
            var placed = false
            var r = firstNonFullRow
            while (!placed && r <= maxSearchRow) {
                for (c in 0..maxColStart) {
                    if (canFitAt(c, r, cSpan, rSpan)) {
                        markOccupied(c, r, cSpan, rSpan)
                        resolvedCards.add(card.copy(gridCol = c, gridRow = r, colSpan = cSpan, rowSpan = rSpan))
                        placed = true
                        break
                    }
                }
                r++
            }
        }
    }

    // 2. Place Device Tiles on the Same Unified Grid (Pass 1: Non-Colliding Explicit Coordinates)
    val resolvedTilesById = HashMap<String, BentoTilePlacement>(tiles.size)
    for (tile in tiles) {
        val cSpan = tile.colSpan.coerceIn(1, safeCols)
        val rSpan = tile.effectiveRowSpan.coerceIn(1, maxRowSpanLimit)
        if (tile.gridCol >= 0 && tile.gridRow >= 0) {
            val clampedCol = tile.gridCol.coerceIn(0, (safeCols - cSpan).coerceAtLeast(0))
            val clampedRow = tile.gridRow.coerceIn(0, maxSearchRow)
            if (canFitAt(clampedCol, clampedRow, cSpan, rSpan)) {
                markOccupied(clampedCol, clampedRow, cSpan, rSpan)
                resolvedTilesById[tile.id] = BentoTilePlacement(
                    tile = tile,
                    col = clampedCol,
                    row = clampedRow,
                    colSpan = cSpan,
                    rowSpan = rSpan
                )
            }
        }
    }

    // 3. Place Displaced (Pushed by Card Resize/Move) or Unpositioned Tiles into the Nearest Open Cell
    for (tile in tiles) {
        if (resolvedTilesById.containsKey(tile.id)) continue
        val cSpan = tile.colSpan.coerceIn(1, safeCols)
        val rSpan = tile.effectiveRowSpan.coerceIn(1, maxRowSpanLimit)
        val maxColStart = (safeCols - cSpan).coerceAtLeast(0)

        var placedCol = 0
        var placedRow = firstNonFullRow
        var foundSpot = false

        if (tile.gridCol >= 0 && tile.gridRow >= 0) {
            val prefR = tile.gridRow.coerceIn(0, maxSearchRow)
            val prefC = tile.gridCol.coerceIn(0, maxColStart)
            for (r in prefR..maxSearchRow) {
                if ((rowMasks[r] and fullRowMask) == fullRowMask) continue
                for (offset in 0..maxColStart) {
                    val leftC = prefC - offset
                    if (leftC >= 0 && canFitAt(leftC, r, cSpan, rSpan)) {
                        placedCol = leftC
                        placedRow = r
                        foundSpot = true
                        break
                    }
                    val rightC = prefC + offset
                    if (offset > 0 && rightC <= maxColStart && canFitAt(rightC, r, cSpan, rSpan)) {
                        placedCol = rightC
                        placedRow = r
                        foundSpot = true
                        break
                    }
                }
                if (foundSpot) break
            }
        }

        if (!foundSpot) {
            var r = firstNonFullRow
            while (!foundSpot && r <= maxSearchRow) {
                if ((rowMasks[r] and fullRowMask) != fullRowMask) {
                    for (c in 0..maxColStart) {
                        if (canFitAt(c, r, cSpan, rSpan)) {
                            placedCol = c
                            placedRow = r
                            foundSpot = true
                            break
                        }
                    }
                }
                r++
            }
        }

        markOccupied(placedCol, placedRow, cSpan, rSpan)
        resolvedTilesById[tile.id] = BentoTilePlacement(
            tile = tile,
            col = placedCol,
            row = clampedRowOrZero(placedRow),
            colSpan = cSpan,
            rowSpan = rSpan
        )
    }

    return resolvedCards to tiles.mapNotNull { resolvedTilesById[it.id] }
}

/**
 * Automatically resolves any collisions caused by resizing, dragging, swapping, or
 * adding a dashboard card, updates the coordinates of any displaced device tiles in `tiles`,
 * and persists both the card layout and tile coordinates immediately.
 */
fun commitUnifiedCardAndTileCollisions(
    context: Context,
    cards: MutableList<UnifiedCardState>,
    tiles: MutableList<SmartTile>,
    gridColumns: Int
) {
    val activeGroupCardIds = tiles.filter { it.type == TileType.GROUP_CARD }.map { it.id }.toSet()
    val gridEligibleTiles = tiles.filter { t ->
        if (t.serviceClass in setOf(ServiceClass.WEATHER, ServiceClass.MUSIC)) {
            return@filter false
        }
        val isGroupedInsideFolder = t.groupCardId.isNotBlank() && t.groupCardId in activeGroupCardIds
        !isGroupedInsideFolder && t.isVisible
    }

    val (resolvedCards, resolvedTilePlacements) = computeUnifiedDashboardPlacements(
        cards = cards.toList(),
        tiles = gridEligibleTiles,
        gridColumns = gridColumns.coerceAtLeast(2),
        maxRowSpanLimit = 4
    )

    val resolvedCardById = resolvedCards.associateBy { it.id }
    for (i in cards.indices) {
        val rc = resolvedCardById[cards[i].id]
        if (rc != null && cards[i] != rc) {
            cards[i] = rc
        }
    }

    val placementByTileId = resolvedTilePlacements.associateBy { it.tile.id }
    var anyTileMoved = false
    for (i in tiles.indices) {
        val current = tiles[i]
        val placement = placementByTileId[current.id] ?: continue
        if (current.gridCol != placement.col || current.gridRow != placement.row) {
            tiles[i] = current.copy(
                gridCol = placement.col,
                gridRow = placement.row
            )
            anyTileMoved = true
        }
    }
    if (anyTileMoved) {
        DeviceRepository.saveTiles(context, tiles)
    }
}

fun computeBentoPlacements(
    tiles: List<SmartTile>,
    gridColumns: Int,
    maxRowSpanLimit: Int = 4
): List<BentoTilePlacement> {
    return computeUnifiedDashboardPlacements(
        cards = emptyList(),
        tiles = tiles,
        gridColumns = gridColumns,
        maxRowSpanLimit = maxRowSpanLimit
    ).second
}

fun computeLauncherLiveReflowPlacements(
    basePlacements: List<BentoTilePlacement>,
    draggedTileId: String?,
    hoverCol: Int,
    hoverRow: Int,
    gridColumns: Int,
    isHoveringOverFolder: Boolean,
    occupiedByCards: Set<Pair<Int, Int>> = emptySet()
): List<BentoTilePlacement> {
    if (draggedTileId == null || isHoveringOverFolder) return basePlacements
    val draggedPlacement = basePlacements.firstOrNull { it.tile.id == draggedTileId } ?: return basePlacements
    val safeCols = gridColumns.coerceIn(2, 62)
    val maxSearchRow = maxOf(64, (basePlacements.size * 2 / safeCols) + 48)
    var targetCol = hoverCol.coerceIn(0, (safeCols - draggedPlacement.colSpan).coerceAtLeast(0))
    val targetRow = hoverRow.coerceIn(0, maxSearchRow)

    if (targetCol == draggedPlacement.col && targetRow == draggedPlacement.row) {
        return basePlacements
    }

    var rowMasks = LongArray(maxSearchRow + 16)
    fun ensureRowCapacity(r: Int) {
        if (r >= rowMasks.size) {
            rowMasks = rowMasks.copyOf(maxOf(r + 32, rowMasks.size * 2))
        }
    }
    for ((c, r) in occupiedByCards) {
        if (c in 0 until safeCols && r >= 0) {
            ensureRowCapacity(r + 1)
            rowMasks[r] = rowMasks[r] or (1L shl c)
        }
    }

    fun canFitAt(col: Int, row: Int, cSpan: Int, rSpan: Int): Boolean {
        if (col < 0 || row < 0 || col + cSpan > safeCols) return false
        ensureRowCapacity(row + rSpan)
        val spanMask = ((1L shl cSpan) - 1L) shl col
        for (dr in 0 until rSpan) {
            if ((rowMasks[row + dr] and spanMask) != 0L) return false
        }
        return true
    }

    fun markOccupied(col: Int, row: Int, cSpan: Int, rSpan: Int) {
        ensureRowCapacity(row + rSpan)
        val spanMask = ((1L shl cSpan) - 1L) shl col
        for (dr in 0 until rSpan) {
            rowMasks[row + dr] = rowMasks[row + dr] or spanMask
        }
    }

    if (!canFitAt(targetCol, targetRow, draggedPlacement.colSpan, draggedPlacement.rowSpan)) {
        return basePlacements
    }

    val resolvedById = HashMap<String, BentoTilePlacement>(basePlacements.size)

    val widerOverlappedTile = basePlacements.firstOrNull { other ->
        other.tile.id != draggedTileId &&
                other.colSpan > draggedPlacement.colSpan &&
                rectsOverlap(
                    targetCol, targetRow, draggedPlacement.colSpan, draggedPlacement.rowSpan,
                    other.col, other.row, other.colSpan, other.rowSpan
                )
    }

    var preferredShiftColForWider: Int? = null
    if (widerOverlappedTile != null && draggedPlacement.row == widerOverlappedTile.row) {
        if (draggedPlacement.col < widerOverlappedTile.col) {
            preferredShiftColForWider = draggedPlacement.col
            targetCol = (draggedPlacement.col + widerOverlappedTile.colSpan)
                .coerceIn(0, (safeCols - draggedPlacement.colSpan).coerceAtLeast(0))
        } else if (draggedPlacement.col >= widerOverlappedTile.col + widerOverlappedTile.colSpan) {
            preferredShiftColForWider = (draggedPlacement.col + draggedPlacement.colSpan - widerOverlappedTile.colSpan)
                .coerceIn(0, (safeCols - widerOverlappedTile.colSpan).coerceAtLeast(0))
            targetCol = widerOverlappedTile.col
        }
    }

    markOccupied(targetCol, targetRow, draggedPlacement.colSpan, draggedPlacement.rowSpan)
    resolvedById[draggedTileId] = draggedPlacement.copy(col = targetCol, row = targetRow)

    val displacedTiles = mutableListOf<BentoTilePlacement>()
    for (p in basePlacements) {
        if (p.tile.id == draggedTileId) continue
        if (canFitAt(p.col, p.row, p.colSpan, p.rowSpan)) {
            markOccupied(p.col, p.row, p.colSpan, p.rowSpan)
            resolvedById[p.tile.id] = p
        } else {
            displacedTiles.add(p)
        }
    }

    for (displaced in displacedTiles) {
        val candidateColsOnSameRow = buildList {
            if (displaced.tile.id == widerOverlappedTile?.tile?.id && preferredShiftColForWider != null) {
                add(preferredShiftColForWider to displaced.row)
            }
            add(draggedPlacement.col.coerceIn(0, (safeCols - displaced.colSpan).coerceAtLeast(0)) to draggedPlacement.row)
            for (c in 0..(safeCols - displaced.colSpan).coerceAtLeast(0)) {
                add(c to displaced.row)
            }
            for (c in 0..(safeCols - displaced.colSpan).coerceAtLeast(0)) {
                add(c to draggedPlacement.row)
            }
        }

        var placed = false
        for ((candC, candR) in candidateColsOnSameRow) {
            if (canFitAt(candC, candR, displaced.colSpan, displaced.rowSpan)) {
                markOccupied(candC, candR, displaced.colSpan, displaced.rowSpan)
                resolvedById[displaced.tile.id] = displaced.copy(col = candC, row = candR)
                placed = true
                break
            }
        }

        if (!placed) {
            var r = 0
            while (!placed && r <= 40) {
                for (c in 0..(safeCols - displaced.colSpan).coerceAtLeast(0)) {
                    if (canFitAt(c, r, displaced.colSpan, displaced.rowSpan)) {
                        markOccupied(c, r, displaced.colSpan, displaced.rowSpan)
                        resolvedById[displaced.tile.id] = displaced.copy(col = c, row = r)
                        placed = true
                        break
                    }
                }
                r++
            }
        }
    }

    return basePlacements.mapNotNull { resolvedById[it.tile.id] }
}

fun commitPlacementsToTiles(
    tiles: MutableList<SmartTile>,
    placements: List<BentoTilePlacement>
) {
    val map = placements.associateBy { it.tile.id }
    for (i in tiles.indices) {
        val p = map[tiles[i].id]
        if (p != null) {
            tiles[i] = tiles[i].copy(
                gridCol = p.col,
                gridRow = p.row
            )
        }
    }
}

fun moveTileToGridPosition(
    tiles: MutableList<SmartTile>,
    activePlacements: List<BentoTilePlacement>,
    tileId: String,
    targetCol: Int,
    targetRow: Int,
    gridColumns: Int,
    occupiedByCards: Set<Pair<Int, Int>> = emptySet()
) {
    val reflowed = computeLauncherLiveReflowPlacements(
        basePlacements = activePlacements,
        draggedTileId = tileId,
        hoverCol = targetCol,
        hoverRow = targetRow,
        gridColumns = gridColumns,
        isHoveringOverFolder = false,
        occupiedByCards = occupiedByCards
    )
    commitPlacementsToTiles(tiles, reflowed)
}

private fun brandOptionForTileType(tileType: TileType): BrandOption = when (tileType) {
    TileType.PROBREEZE_FAN -> BrandOption.PRO_BREEZE
    TileType.SMARTKNIGHT_PLUG -> BrandOption.SMARTKNIGHT
    TileType.SMARTLIFE_DEVICE -> BrandOption.SMART_LIFE
    TileType.GOVEE_LIGHT -> BrandOption.GOVEE
    TileType.TWINKLY_LIGHT -> BrandOption.TWINKLY
    TileType.LYTMI_SYNC_BOX -> BrandOption.LYTMI
    TileType.HUE_LIGHT -> BrandOption.HUE
    TileType.TUYA_RADIATOR -> BrandOption.TUYA
    TileType.NEST_THERMOSTAT, TileType.NEST_DOORBELL, TileType.NEST_MINI -> BrandOption.NEST
    TileType.IMOU_CAMERA -> BrandOption.IMOU
    TileType.EZVIZ_DOORBELL -> BrandOption.EZVIZ
    TileType.JAGUAR_VEHICLE -> BrandOption.JAGUAR
    TileType.MERCEDES_VEHICLE -> BrandOption.MERCEDES
    TileType.LG_TV -> BrandOption.LG_THINQ
    TileType.AIRROBO_VACUUM -> BrandOption.AIRROBO
    TileType.TAPO_DEVICE -> BrandOption.TAPO
    TileType.SONOFF_SWITCH -> BrandOption.SONOFF
    TileType.SHELLY_RELAY -> BrandOption.SHELLY
    TileType.MATTER_DEVICE -> BrandOption.MATTER
    TileType.MQTT_DEVICE -> BrandOption.MQTT_TASMOTA_ESPHOME
    else -> BrandOption.SMART_LIFE
}

/**
 * Checks whether an already-discovered hardware device of [tileType] is waiting in our
 * local/cloud discovery caches and not yet added to [existingTiles]. If found, builds a
 * fully linked [SmartTile] with real hardware identifiers so zero manual input is required.
 */
private fun tryResolveUnaddedDiscoveredHardwareTile(
    context: Context,
    tileType: TileType,
    customTitle: String,
    roomToAssign: String,
    targetCol: Int,
    targetRow: Int,
    spanPair: Pair<Int, Int>,
    existingTiles: List<SmartTile>
): SmartTile? {
    fun isAlreadyOnDashboard(vararg idsOrIps: String): Boolean {
        for (raw in idsOrIps) {
            val clean = raw.trim()
            if (clean.isBlank()) continue
            val cleanNoPort = clean.substringBefore(":")
            if (existingTiles.any {
                    it.serialNumber.equals(clean, true) ||
                            it.entityId.equals(clean, true) ||
                            it.ipAddress.equals(clean, true) ||
                            (cleanNoPort.isNotBlank() && it.ipAddress.substringBefore(":").equals(cleanNoPort, true))
                }
            ) {
                return true
            }
        }
        return false
    }

    when (tileType) {
        TileType.TAPO_DEVICE -> {
            val candidate = MultiProtocolLiveBridge.discoveredTapoKasaDevicesFlow.value.firstOrNull {
                !it.requiresAuth && !isAlreadyOnDashboard(it.deviceId, it.ipAddress)
            } ?: return null
            val hasPower = candidate.powerWatts > 0f || candidate.totalEnergyKwh > 0f
            val base = SmartTile(
                title = customTitle.ifBlank { candidate.name },
                roomOrSubtitle = roomToAssign,
                type = TileType.TAPO_DEVICE,
                colSpan = spanPair.first,
                rowSpan = spanPair.second,
                gridCol = targetCol,
                gridRow = targetRow,
                isTall = spanPair.second > 1,
                isVisible = true,
                isOnline = true,
                isActive = candidate.isActive,
                isSocket2Active = candidate.isSocket2Active,
                multiGangSocketCount = candidate.multiGangCount.coerceAtLeast(1),
                socket1Name = candidate.socket1Name.ifBlank { "Socket 1" },
                socket2Name = candidate.socket2Name.ifBlank { "Socket 2" },
                powerWatts = candidate.powerWatts,
                voltageVolts = candidate.voltageVolts,
                currentMilliamps = candidate.currentMilliamps,
                totalEnergyKwh = candidate.totalEnergyKwh,
                ipAddress = candidate.ipAddress,
                serialNumber = candidate.deviceId,
                entityId = candidate.deviceId,
                hasExplicitPowerTelemetry = hasPower
            )
            return base.copy(capabilities = SmartHomeApiClient.buildDefaultCapabilitiesForTile(base))
        }

        TileType.SONOFF_SWITCH -> {
            val candidate = MultiProtocolLiveBridge.discoveredSonoffDevicesFlow.value.firstOrNull {
                !it.requiresAuth && !isAlreadyOnDashboard(it.deviceId, it.ipAddress)
            } ?: return null
            val endpoint = if (candidate.ipAddress.isNotBlank()) "${candidate.ipAddress}:${candidate.port}" else ""
            val base = SmartTile(
                title = customTitle.ifBlank { candidate.name },
                roomOrSubtitle = roomToAssign,
                type = TileType.SONOFF_SWITCH,
                colSpan = spanPair.first,
                rowSpan = spanPair.second,
                gridCol = targetCol,
                gridRow = targetRow,
                isTall = spanPair.second > 1,
                isVisible = true,
                isOnline = true,
                isActive = candidate.isActive,
                isSocket2Active = candidate.isSocket2Active,
                multiGangSocketCount = candidate.multiGangCount.coerceAtLeast(1),
                powerWatts = candidate.powerWatts,
                voltageVolts = candidate.voltageVolts,
                currentMilliamps = candidate.currentMilliamps,
                ipAddress = endpoint,
                serialNumber = candidate.deviceId,
                entityId = candidate.deviceId,
                secretCode = candidate.deviceKey,
                hasExplicitPowerTelemetry = candidate.powerWatts > 0f
            )
            return base.copy(capabilities = SmartHomeApiClient.buildDefaultCapabilitiesForTile(base))
        }

        TileType.MATTER_DEVICE -> {
            val candidate = MultiProtocolLiveBridge.discoveredMatterDevicesFlow.value.firstOrNull {
                it.isCommissionedOperational && !isAlreadyOnDashboard(it.nodeId, it.ipAddress)
            } ?: return null
            val base = SmartTile(
                title = customTitle.ifBlank { candidate.displayName },
                roomOrSubtitle = roomToAssign,
                type = candidate.suggestedType,
                colSpan = spanPair.first,
                rowSpan = spanPair.second,
                gridCol = targetCol,
                gridRow = targetRow,
                isTall = spanPair.second > 1,
                isVisible = true,
                isOnline = true,
                isActive = true,
                ipAddress = candidate.ipAddress,
                serialNumber = candidate.nodeId,
                entityId = candidate.nodeId
            )
            return base.copy(capabilities = SmartHomeApiClient.buildDefaultCapabilitiesForTile(base))
        }

        TileType.IMOU_CAMERA -> {
            val candidate = ImouCloudApiClient.getCachedCameras().firstOrNull {
                !isAlreadyOnDashboard(it.deviceId)
            } ?: return null
            val savedCode = ImouCloudApiClient.getSavedDeviceSafetyCode(candidate.deviceId)
            return SmartTile(
                title = customTitle.ifBlank { candidate.name },
                roomOrSubtitle = roomToAssign,
                type = TileType.IMOU_CAMERA,
                colSpan = spanPair.first,
                rowSpan = spanPair.second,
                gridCol = targetCol,
                gridRow = targetRow,
                isTall = spanPair.second > 1,
                isVisible = true,
                isOnline = candidate.status.equals("online", true),
                serialNumber = candidate.deviceId,
                channelId = candidate.channelId,
                secretCode = savedCode,
                supportsPtz = ImouCloudApiClient.isPtzSupported(candidate.deviceId),
                companionAppPackage = "com.mm.android.direct.imou"
            )
        }

        TileType.EZVIZ_DOORBELL -> {
            val detail = EzvizCloudApiClient.getCachedDeviceDetail("")?.takeIf {
                !isAlreadyOnDashboard(it.serial, it.localIp)
            } ?: return null
            val pin = detail.verificationCode.ifBlank {
                EzvizCloudApiClient.getSavedEzvizPinForSerial(context, detail.serial)
            }
            return SmartTile(
                title = customTitle.ifBlank { detail.name },
                roomOrSubtitle = roomToAssign,
                type = TileType.EZVIZ_DOORBELL,
                colSpan = spanPair.first,
                rowSpan = spanPair.second,
                gridCol = targetCol,
                gridRow = targetRow,
                isTall = spanPair.second > 1,
                isVisible = true,
                isOnline = detail.isOnline,
                ipAddress = detail.localIp,
                serialNumber = detail.serial,
                secretCode = pin,
                channelId = "1",
                companionAppPackage = "com.videogo"
            )
        }

        else -> return null
    }
}

// ---------------------------------------------------------------------------
// QUICK ADD CARD OR TILE MODAL
// - System widgets (Group Folder, Energy Chart, Photo Frame, Security Center)
//   are placed immediately at (targetGridCol, targetGridRow).
// - Hardware presets either link an already-discovered unadded device at
//   (targetGridCol, targetGridRow) or launch the GuidedDevicePairingWizardModal
//   so zero unconfigured placeholder tiles are ever added to the dashboard.
// ---------------------------------------------------------------------------
@Composable
fun QuickAddCardOrTileModal(
    context: Context,
    tiles: MutableList<SmartTile>,
    targetGridCol: Int,
    targetGridRow: Int,
    selectedRoomFilter: String,
    onAddNewWidgetCardAtCell: ((DashboardWidgetType, Int, Int) -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") onSelectWidgetForCardSlot: ((DashboardCardSlot, DashboardWidgetType) -> Unit)? = null,
    onOpenFullSetupWizard: () -> Unit,
    onRecordUndoSnapshot: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val hiddenTiles = remember(tiles.toList()) {
        tiles.filter { !it.isVisible && it.type != TileType.CLOCK_WEATHER }
    }
    var customTileTitle by remember { mutableStateOf("") }
    var activePairingBrand by remember { mutableStateOf<BrandOption?>(null) }
    var activePairingSpan by remember { mutableStateOf(1 to 1) }

    val roomToAssign = remember(selectedRoomFilter) {
        if (selectedRoomFilter.equals("All Rooms", ignoreCase = true)) "" else selectedRoomFilter
    }

    val widgetCardOptions = remember {
        DashboardWidgetType.entries.filter { it != DashboardWidgetType.DEVICE_TILES }
    }

    val quickTilePresets = remember {
        listOf(
            Triple("Device Group Folder", TileType.GROUP_CARD, 2 to 1),
            Triple("Energy Saving Chart", TileType.ENERGY_MONITOR, 2 to 1),
            Triple("Photo Slideshow Frame", TileType.PHOTO_SLIDESHOW, 2 to 2),
            Triple("Security Center Tile", TileType.SECURITY_CENTER, 1 to 1),
            Triple("Smart Tower Fan", TileType.PROBREEZE_FAN, 1 to 1),
            Triple("Smart Plug / Socket", TileType.SMARTKNIGHT_PLUG, 1 to 1),
            Triple("RGBIC Ambient Light", TileType.GOVEE_LIGHT, 1 to 1),
            Triple("Smart LED Lights", TileType.TWINKLY_LIGHT, 1 to 1),
            Triple("HDMI Ambient Sync Box", TileType.LYTMI_SYNC_BOX, 1 to 1),
            Triple("Smart Bulb / Light", TileType.HUE_LIGHT, 1 to 1),
            Triple("Radiator / Thermostat", TileType.TUYA_RADIATOR, 1 to 1),
            Triple("Security Camera", TileType.IMOU_CAMERA, 2 to 1),
            Triple("Video Doorbell", TileType.EZVIZ_DOORBELL, 2 to 1),
            Triple("Connected EV / Vehicle (JLR)", TileType.JAGUAR_VEHICLE, 1 to 1),
            Triple("Connected Vehicle (Mercedes)", TileType.MERCEDES_VEHICLE, 1 to 1),
            Triple("Smart TV (webOS)", TileType.LG_TV, 1 to 1),
            Triple("Robot Vacuum", TileType.AIRROBO_VACUUM, 1 to 1)
        )
    }

    val systemWidgetTileTypes = remember {
        setOf(
            TileType.GROUP_CARD,
            TileType.ENERGY_MONITOR,
            TileType.PHOTO_SLIDESHOW,
            TileType.SECURITY_CENTER
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhTileSurface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.5.dp, GhAccentGreen.copy(alpha = 0.65f)),
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.86f)
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Surface(
                            color = GhAccentGreen.copy(alpha = 0.20f),
                            shape = CircleShape,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = GhAccentGreen,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Add New Card or Pair Device at Grid (${targetGridCol + 1}, ${targetGridRow + 1})",
                                color = GhTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Places a dashboard widget card or pairs a real smart device into this grid slot",
                                color = GhTextSecondary,
                                fontSize = 11.5.sp,
                                fontFamily = GhFont,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = {
                                onDismiss()
                                onOpenFullSetupWizard()
                            },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = GhTileSurfaceElevated,
                                contentColor = GhAccentGreen
                            ),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Outlined.Radar, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Auto-Discover",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
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

                HorizontalDivider(color = GhCardBorder)

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    // 1. ADD A NEW DASHBOARD WIDGET CARD ALONGSIDE EXISTING CARDS
                    if (onAddNewWidgetCardAtCell != null) {
                        item {
                            Text(
                                text = "1. Add a New Dashboard Widget Card (3\u00D72 Card):",
                                color = Color(0xFF00E5FF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            )
                        }
                        items(widgetCardOptions.chunked(2)) { cardPair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                for (wt in cardPair) {
                                    Surface(
                                        color = GhTileSurfaceElevated,
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.45f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                onRecordUndoSnapshot()
                                                onAddNewWidgetCardAtCell(wt, targetGridCol, targetGridRow)
                                                onDismiss()
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Widgets,
                                                contentDescription = null,
                                                tint = Color(0xFF00E5FF),
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = wt.label,
                                                    color = GhTextPrimary,
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "Adds new card at (${targetGridCol + 1}, ${targetGridRow + 1})",
                                                    color = GhTextSecondary,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }
                                }
                                if (cardPair.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        item {
                            HorizontalDivider(color = GhCardBorder)
                        }
                    }

                    // 2. Unhide / Place Existing Hidden Devices at this Grid Spot
                    if (hiddenTiles.isNotEmpty()) {
                        item {
                            Text(
                                text = "Hidden Devices Ready to Place (${hiddenTiles.size})",
                                color = GhAccentWarm,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            )
                        }
                        item {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(hiddenTiles, key = { "unhide_${it.id}" }) { hidden ->
                                    Surface(
                                        color = GhTileSurfaceElevated,
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, GhAccentWarm.copy(alpha = 0.6f)),
                                        modifier = Modifier.clickable {
                                            onRecordUndoSnapshot()
                                            val idx = tiles.indexOfFirst { it.id == hidden.id }
                                            if (idx in tiles.indices) {
                                                tiles[idx] = tiles[idx].copy(
                                                    isVisible = true,
                                                    gridCol = targetGridCol.coerceAtLeast(0),
                                                    gridRow = targetGridRow.coerceAtLeast(0)
                                                )
                                                DeviceRepository.saveTiles(context, tiles)
                                            }
                                            onDismiss()
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = iconForTileType(hidden.type),
                                                contentDescription = null,
                                                tint = GhAccentWarm,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "Place '${hidden.title}' Here",
                                                color = GhTextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Add a System Widget Tile or Pair a Hardware Device at (targetGridCol, targetGridRow)
                    item {
                        OutlinedTextField(
                            value = customTileTitle,
                            onValueChange = { customTileTitle = it },
                            label = { Text("Optional Custom Name (leave blank for hardware name)", fontSize = 11.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        Text(
                            text = "2. Add a System Widget or Pair Hardware at Grid (${targetGridCol + 1}, ${targetGridRow + 1}):",
                            color = GhAccentGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont
                        )
                    }

                    items(quickTilePresets.chunked(2)) { rowPair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            for ((defaultLabel, tileType, spanPair) in rowPair) {
                                val isSystemWidget = tileType in systemWidgetTileTypes
                                Surface(
                                    color = GhTileSurfaceElevated,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSystemWidget) GhAccentGreen.copy(alpha = 0.45f) else GhCardBorder
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            if (isSystemWidget) {
                                                onRecordUndoSnapshot()
                                                val finalTitle = customTileTitle.trim().ifBlank { defaultLabel }
                                                scope.launch {
                                                    val bridge = DeviceRepository.loadBridge(context)
                                                    val widgetIdentity = ResolvedDeviceIdentity(
                                                        identityKey = "id_widget_${tileType.name.lowercase()}_${UUID.randomUUID()}",
                                                        displayName = finalTitle,
                                                        suggestedRoom = roomToAssign,
                                                        deviceClass = tileType.toSemanticDeviceClass(),
                                                        suggestedType = tileType,
                                                        combinedConfidence = 1.0f,
                                                        primaryProviderId = ProviderId.SYSTEM_LOCAL.id,
                                                        fallbackProviderIds = emptyList(),
                                                        onboardingRequirement = OnboardingRequirement.ZeroTouchReady,
                                                        isActive = true,
                                                        isOnline = true
                                                    )
                                                    val res = UniversalOnboardingEngine.onboardResolvedIdentity(
                                                        context = context,
                                                        identity = widgetIdentity,
                                                        tiles = tiles,
                                                        bridgeSettings = bridge,
                                                        scope = scope
                                                    )
                                                    if (res.isSuccess) {
                                                        val created = res.getOrThrow()
                                                        val idx = tiles.indexOfFirst { it.id == created.id }
                                                        if (idx in tiles.indices) {
                                                            tiles[idx] = tiles[idx].copy(
                                                                colSpan = spanPair.first,
                                                                rowSpan = spanPair.second,
                                                                gridCol = targetGridCol.coerceAtLeast(0),
                                                                gridRow = targetGridRow.coerceAtLeast(0),
                                                                isTall = spanPair.second > 1,
                                                                isVisible = true
                                                            )
                                                            DeviceRepository.saveTiles(context, tiles)
                                                        }
                                                    }
                                                    onDismiss()
                                                }
                                            } else {
                                                // 1. Check if an unadded discovered hardware device of this type is already in cache
                                                val linkedFromCache = tryResolveUnaddedDiscoveredHardwareTile(
                                                    context = context,
                                                    tileType = tileType,
                                                    customTitle = customTileTitle.trim(),
                                                    roomToAssign = roomToAssign,
                                                    targetCol = targetGridCol.coerceAtLeast(0),
                                                    targetRow = targetGridRow.coerceAtLeast(0),
                                                    spanPair = spanPair,
                                                    existingTiles = tiles
                                                )
                                                if (linkedFromCache != null) {
                                                    onRecordUndoSnapshot()
                                                    scope.launch {
                                                        val bridge = DeviceRepository.loadBridge(context)
                                                        val hwId = linkedFromCache.serialNumber.ifBlank { linkedFromCache.entityId }
                                                        val cachedIdentity = ResolvedDeviceIdentity(
                                                            identityKey = "id_quick_${tileType.name.lowercase()}_${hwId.ifBlank { linkedFromCache.ipAddress }}",
                                                            displayName = linkedFromCache.title,
                                                            suggestedRoom = linkedFromCache.roomOrSubtitle,
                                                            deviceClass = linkedFromCache.deviceClass,
                                                            suggestedType = linkedFromCache.type,
                                                            combinedConfidence = 0.96f,
                                                            primaryProviderId = linkedFromCache.resolvedProviderId.id,
                                                            fallbackProviderIds = linkedFromCache.fallbackProviderIds,
                                                            onboardingRequirement = OnboardingRequirement.ZeroTouchReady,
                                                            ipAddress = linkedFromCache.ipAddress.substringBefore(":"),
                                                            port = linkedFromCache.ipAddress.substringAfter(":", "0").toIntOrNull() ?: 0,
                                                            hardwareSerialOrNodeId = linkedFromCache.serialNumber,
                                                            cloudDeviceId = linkedFromCache.entityId,
                                                            localKeyOrPin = linkedFromCache.secretCode,
                                                            multiGangCount = linkedFromCache.multiGangSocketCount,
                                                            socket1Name = linkedFromCache.socket1Name,
                                                            socket2Name = linkedFromCache.socket2Name,
                                                            isActive = linkedFromCache.isActive,
                                                            isSocket2Active = linkedFromCache.isSocket2Active,
                                                            isOnline = linkedFromCache.isOnline,
                                                            powerWatts = linkedFromCache.powerWatts,
                                                            voltageVolts = linkedFromCache.voltageVolts,
                                                            currentMilliamps = linkedFromCache.currentMilliamps,
                                                            totalEnergyKwh = linkedFromCache.totalEnergyKwh,
                                                            hasPowerTelemetry = linkedFromCache.hasExplicitPowerTelemetry,
                                                            capabilities = linkedFromCache.capabilities
                                                        )
                                                        val res = UniversalOnboardingEngine.onboardResolvedIdentity(
                                                            context = context,
                                                            identity = cachedIdentity,
                                                            tiles = tiles,
                                                            bridgeSettings = bridge,
                                                            scope = scope
                                                        )
                                                        if (res.isSuccess) {
                                                            val created = res.getOrThrow()
                                                            val idx = tiles.indexOfFirst { it.id == created.id }
                                                            if (idx in tiles.indices) {
                                                                tiles[idx] = tiles[idx].copy(
                                                                    channelId = linkedFromCache.channelId,
                                                                    supportsPtz = linkedFromCache.supportsPtz,
                                                                    companionAppPackage = linkedFromCache.companionAppPackage.ifBlank { tiles[idx].companionAppPackage },
                                                                    colSpan = spanPair.first,
                                                                    rowSpan = spanPair.second,
                                                                    gridCol = targetGridCol.coerceAtLeast(0),
                                                                    gridRow = targetGridRow.coerceAtLeast(0),
                                                                    isTall = spanPair.second > 1,
                                                                    isVisible = true
                                                                )
                                                                DeviceRepository.saveTiles(context, tiles)
                                                            }
                                                        }
                                                        onDismiss()
                                                    }
                                                } else {
                                                    // 2. Otherwise open the Guided Device Pairing Wizard for this hardware brand
                                                    activePairingSpan = spanPair
                                                    activePairingBrand = brandOptionForTileType(tileType)
                                                }
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = iconForTileType(tileType),
                                            contentDescription = null,
                                            tint = GhAccentGreen,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = defaultLabel,
                                                color = GhTextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = if (isSystemWidget) {
                                                    "Size ${spanPair.first}\u00D7${spanPair.second} \u2022 Tap to Place"
                                                } else {
                                                    "Size ${spanPair.first}\u00D7${spanPair.second} \u2022 Pair & Link Hardware"
                                                },
                                                color = GhTextSecondary,
                                                fontSize = 10.5.sp
                                            )
                                        }
                                    }
                                }
                            }
                            if (rowPair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    activePairingBrand?.let { brandToPair ->
        val initialIds = remember(brandToPair) { tiles.map { it.id }.toSet() }
        GuidedDevicePairingWizardModal(
            context = context,
            initialBrand = brandToPair,
            tiles = tiles,
            onSyncTuyaEcosystemDevices = {
                scope.launch {
                    val bridge = DeviceRepository.loadBridge(context)
                    TuyaApiClient.syncLiveTuyaStates(bridge, tiles)
                }
            },
            onTriggerFullScan = {
                scope.launch {
                    val bridge = DeviceRepository.loadBridge(context)
                    runAutomaticEnvironmentDiscoveryAndSelfHealing(
                        context = context,
                        bridgeSettings = bridge,
                        tiles = tiles,
                        scope = scope,
                        includeFullLanScan = true
                    )
                }
            },
            onStatusBanner = {},
            onDismiss = {
                // Position any newly paired device at (targetGridCol, targetGridRow)
                val newlyAddedIdx = tiles.indexOfLast { it.id !in initialIds }
                if (newlyAddedIdx in tiles.indices) {
                    onRecordUndoSnapshot()
                    val added = tiles[newlyAddedIdx]
                    val customName = customTileTitle.trim()
                    tiles[newlyAddedIdx] = added.copy(
                        title = if (customName.isNotBlank()) customName else added.title,
                        roomOrSubtitle = added.roomOrSubtitle.ifBlank { roomToAssign },
                        colSpan = activePairingSpan.first,
                        rowSpan = activePairingSpan.second,
                        isTall = activePairingSpan.second > 1,
                        gridCol = targetGridCol.coerceAtLeast(0),
                        gridRow = targetGridRow.coerceAtLeast(0)
                    )
                    DeviceRepository.saveTiles(context, tiles)
                    activePairingBrand = null
                    onDismiss()
                } else {
                    activePairingBrand = null
                }
            }
        )
    }
}
