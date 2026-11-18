package com.jarvis.smarthomelauncher.ui.widgets

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ElectricMeter
import androidx.compose.material.icons.outlined.EnergySavingsLeaf
import androidx.compose.material.icons.outlined.Power
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Calendar
import java.util.Locale

private val WEEKDAY_LABELS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

// Vibrant multi-colour bar chart gradients
private val EnergyBarGradientPalette = listOf(
    listOf(Color(0xFFFF9E2C), Color(0xFFFF6D00)), // Warm Orange
    listOf(Color(0xFFFF4081), Color(0xFFD81B60)), // Coral Pink
    listOf(Color(0xFF9C27B0), Color(0xFF651FFF)), // Deep Violet Purple
    listOf(Color(0xFF00E5FF), Color(0xFF0091EA)), // Electric Cyan Blue
    listOf(Color(0xFF76FF03), Color(0xFF00C853)), // Neon Lime Green
    listOf(Color(0xFFFFD54F), Color(0xFFFF8F00)), // Golden Amber
    listOf(Color(0xFFE040FB), Color(0xFF7C4DFF))  // Magenta Purple
)

private val PlugRowAccentPalette = listOf(
    Color(0xFF00E5FF), // Cyan Blue
    Color(0xFFFF4081), // Coral Pink
    Color(0xFFFF9100), // Warm Orange
    Color(0xFF69F0AE)  // Neon Lime Green
)

private fun isCurrentlyNightTariffWindow(bridge: BridgeSettings = BridgeSettings()): Boolean {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val start = bridge.offPeakStartHour.coerceIn(0, 23)
    val end = bridge.offPeakEndHour.coerceIn(0, 23)
    return if (start <= end) {
        hour in start until end
    } else {
        hour >= start || hour < end
    }
}

/**
 * Builds the 7-day (Sun..Sat) energy series strictly from real measured device telemetry
 * and on-device time-series history (zero synthetic fallback multipliers).
 */
private fun buildWeeklyEnergySeries(
    allTiles: List<SmartTile>,
    telemetryHistory: List<TelemetryDataPoint> = emptyList()
): List<Float> {
    val monitoredTiles = allTiles.filter { it.hasPowerTelemetryCapability }
    val totalLiveWatts = monitoredTiles.sumOf { it.powerWatts.toDouble() }.toFloat()
    val totalStoredKwh = monitoredTiles.sumOf { it.totalEnergyKwh.toDouble() }.toFloat()

    val dayWattSums = FloatArray(7)
    val dayCounts = IntArray(7)
    val dayExplicitKwh = FloatArray(7)

    val cal = Calendar.getInstance()
    val todayDayIdx = (cal.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)
    val sevenDaysAgoMs = System.currentTimeMillis() - (7L * 24L * 3600_000L)

    for (pt in telemetryHistory) {
        if (pt.timestampEpochMs < sevenDaysAgoMs) continue
        cal.timeInMillis = pt.timestampEpochMs
        val dayIdx = (cal.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)
        when (pt.metricKey.lowercase(Locale.US)) {
            "energy_kwh", "total_kwh" -> {
                if (pt.value > dayExplicitKwh[dayIdx]) {
                    dayExplicitKwh[dayIdx] = pt.value
                }
            }
            "power_w" -> {
                if (pt.value >= 0f) {
                    dayWattSums[dayIdx] += pt.value
                    dayCounts[dayIdx]++
                }
            }
        }
    }

    return (0..6).map { dayIdx ->
        when {
            dayExplicitKwh[dayIdx] > 0f -> dayExplicitKwh[dayIdx]
            dayCounts[dayIdx] > 0 -> {
                val avgWatts = dayWattSums[dayIdx] / dayCounts[dayIdx]
                ((avgWatts * 24f) / 1000f).coerceAtLeast(0f)
            }
            dayIdx == todayDayIdx && totalStoredKwh > 0f -> totalStoredKwh
            dayIdx == todayDayIdx && totalLiveWatts > 0f -> (totalLiveWatts / 1000f).coerceAtLeast(0f)
            else -> 0f
        }
    }
}

// ---------------------------------------------------------------------------
// SPLINE ENERGY GRAPH WITH DASHED GRID & FLOATING PEAK CALLOUT
// ---------------------------------------------------------------------------
@Composable
fun SplineEnergyChartWithPeakCallout(
    weeklySeries: List<Float>,
    unitSuffix: String = "kWh",
    modifier: Modifier = Modifier
) {
    val safeSeries = if (weeklySeries.size >= 7) weeklySeries.take(7) else List(7) { 0f }
    val rawPeak = safeSeries.maxOrNull() ?: 0f
    val maxVal = rawPeak.coerceAtLeast(0.5f) * 1.22f
    val todayIdx = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)
    val peakIndex = if (rawPeak > 0f) {
        safeSeries.indices.maxByOrNull { safeSeries[it] } ?: todayIdx
    } else {
        todayIdx
    }
    val peakValue = safeSeries[peakIndex]
    val totalWeekly = safeSeries.sum()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Weekly electricity spline chart. Peak usage ${"%.2f".format(peakValue)} $unitSuffix on ${WEEKDAY_LABELS[peakIndex]}, 7-day total ${"%.2f".format(totalWeekly)} $unitSuffix."
            },
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val chartWidthDp = maxWidth
            val chartHeightDp = maxHeight

            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

                for (row in 1..3) {
                    val y = h * (row / 4f)
                    drawLine(
                        color = Color.White.copy(alpha = 0.08f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dashEffect
                    )
                }

                val points = safeSeries.mapIndexed { idx, value ->
                    val x = (w / (safeSeries.size - 1).coerceAtLeast(1)) * idx
                    val normalized = if (rawPeak > 0f) {
                        (value / maxVal).coerceIn(0.06f, 0.92f)
                    } else {
                        0.06f
                    }
                    val y = h * (1f - normalized)
                    Offset(x, y)
                }

                for (pt in points) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.05f),
                        start = Offset(pt.x, 0f),
                        end = Offset(pt.x, h),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dashEffect
                    )
                }

                if (points.size >= 2) {
                    val strokePath = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        for (i in 0 until points.size - 1) {
                            val p0 = points[i]
                            val p1 = points[i + 1]
                            val ctrlX = (p0.x + p1.x) / 2f
                            cubicTo(ctrlX, p0.y, ctrlX, p1.y, p1.x, p1.y)
                        }
                    }

                    val fillPath = Path().apply {
                        addPath(strokePath)
                        lineTo(points.last().x, h)
                        lineTo(points.first().x, h)
                        close()
                    }

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF00E5FF).copy(alpha = if (rawPeak > 0f) 0.38f else 0.10f),
                                Color(0xFF9C27B0).copy(alpha = if (rawPeak > 0f) 0.18f else 0.04f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = h
                        )
                    )

                    drawPath(
                        path = strokePath,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFFFF9100),
                                Color(0xFFFF4081),
                                Color(0xFF9C27B0),
                                Color(0xFF00E5FF),
                                Color(0xFF76FF03)
                            )
                        ),
                        style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round)
                    )

                    val peakPt = points[peakIndex]
                    drawCircle(
                        color = Color(0xFF00E5FF).copy(alpha = 0.38f),
                        radius = 10.dp.toPx(),
                        center = peakPt
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 4.5.dp.toPx(),
                        center = peakPt
                    )
                }
            }

            val peakFractionX = peakIndex.toFloat() / (safeSeries.size - 1).coerceAtLeast(1)
            val tooltipX = (chartWidthDp * peakFractionX - 36.dp).coerceIn(4.dp, (chartWidthDp - 80.dp).coerceAtLeast(4.dp))
            val normPeak = if (rawPeak > 0f) (peakValue / maxVal).coerceIn(0.15f, 0.88f) else 0.15f
            val peakFractionY = 1f - normPeak
            val tooltipY = (chartHeightDp * peakFractionY - 30.dp).coerceAtLeast(0.dp)

            Surface(
                color = Color(0xFF282C35),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.6f)),
                shadowElevation = 6.dp,
                modifier = Modifier.offset(x = tooltipX, y = tooltipY)
            ) {
                Text(
                    text = "${"%.2f".format(peakValue)} ${unitSuffix.uppercase()}",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = GhFont,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            WEEKDAY_LABELS.forEachIndexed { idx, day ->
                val isPeak = idx == peakIndex
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = day,
                        color = if (isPeak) Color.White else GhTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = if (isPeak) FontWeight.ExtraBold else FontWeight.Medium,
                        fontFamily = GhFont
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .width(14.dp)
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (isPeak) Color(0xFF00E5FF) else Color.Transparent)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// MULTI-COLOUR VERTICAL BAR CHART
// ---------------------------------------------------------------------------
@Composable
fun MultiColourWeeklyBarChart(
    weeklySeries: List<Float>,
    unitSuffix: String = "KWH",
    modifier: Modifier = Modifier
) {
    val safeSeries = if (weeklySeries.size >= 7) weeklySeries.take(7) else List(7) { 0f }
    val rawPeak = safeSeries.maxOrNull() ?: 0f
    val maxBar = rawPeak.coerceAtLeast(0.5f) * 1.22f
    val todayIdx = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)
    val peakIndex = if (rawPeak > 0f) {
        safeSeries.indices.maxByOrNull { safeSeries[it] } ?: todayIdx
    } else {
        todayIdx
    }
    val peakVal = safeSeries[peakIndex]
    val totalWeeklyBar = safeSeries.sum()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Weekly energy bar chart. Peak usage ${"%.2f".format(peakVal)} $unitSuffix on ${WEEKDAY_LABELS[peakIndex]}, 7-day total ${"%.2f".format(totalWeeklyBar)} $unitSuffix."
            },
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val chartW = maxWidth
            val chartH = maxHeight

            Canvas(modifier = Modifier.fillMaxSize()) {
                val barCount = safeSeries.size
                val slotW = size.width / barCount
                val barW = (slotW * 0.48f).coerceAtMost(26.dp.toPx())
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)

                for (r in 1..3) {
                    val y = size.height * (r / 4f)
                    drawLine(
                        color = Color.White.copy(alpha = 0.06f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dashEffect
                    )
                }

                safeSeries.forEachIndexed { idx, value ->
                    val frac = if (value > 0f && rawPeak > 0f) {
                        (value / maxBar).coerceIn(0.12f, 0.86f)
                    } else {
                        0.05f
                    }
                    val barH = size.height * frac
                    val left = (slotW * idx) + (slotW - barW) / 2f
                    val top = size.height - barH
                    val gradientColors = if (value > 0f) {
                        EnergyBarGradientPalette[idx % EnergyBarGradientPalette.size]
                    } else {
                        listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.06f))
                    }

                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = gradientColors,
                            startY = top,
                            endY = size.height
                        ),
                        topLeft = Offset(left, top),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(9.dp.toPx(), 9.dp.toPx())
                    )
                }
            }

            val slotFraction = (peakIndex + 0.5f) / safeSeries.size.toFloat()
            val pillX = (chartW * slotFraction - 36.dp).coerceIn(2.dp, (chartW - 76.dp).coerceAtLeast(2.dp))
            val peakFrac = if (peakVal > 0f && rawPeak > 0f) {
                (peakVal / maxBar).coerceIn(0.12f, 0.86f)
            } else {
                0.08f
            }
            val pillY = (chartH * (1f - peakFrac) - 26.dp).coerceAtLeast(0.dp)

            Surface(
                color = Color(0xFF282C35),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                shadowElevation = 4.dp,
                modifier = Modifier.offset(x = pillX, y = pillY)
            ) {
                Text(
                    text = "${"%.2f".format(peakVal)} $unitSuffix",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = GhFont,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            WEEKDAY_LABELS.forEachIndexed { idx, day ->
                val isPeak = idx == peakIndex
                val barAccent = EnergyBarGradientPalette[idx % EnergyBarGradientPalette.size].first()
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = day,
                        color = if (isPeak) Color.White else GhTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = if (isPeak) FontWeight.ExtraBold else FontWeight.Medium,
                        fontFamily = GhFont
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .width(14.dp)
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (isPeak) barAccent else Color.Transparent)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// DASHBOARD ELECTRICITY CONSUMED TILE CONTENT (Zero Synthetic Fallbacks)
// ---------------------------------------------------------------------------
@Composable
fun GhEnergyMonitorTileContent(
    tile: SmartTile,
    allTiles: List<SmartTile>,
    onOpenDetailModal: () -> Unit
) {
    val context = LocalContext.current
    val bridge = remember { DeviceRepository.loadBridge(context) }
    val telemetryHistory by JarvisLiveBridge.telemetryHistoryFlow.collectAsState()

    val monitoredTiles = remember(allTiles) {
        allTiles.filter { it.hasPowerTelemetryCapability }
    }
    val totalLiveWatts = remember(monitoredTiles) {
        monitoredTiles.sumOf { it.powerWatts.toDouble() }.toFloat()
    }
    val totalKwh = remember(monitoredTiles) {
        monitoredTiles.sumOf { it.totalEnergyKwh.toDouble() }.toFloat().coerceAtLeast(0f)
    }

    val currency = bridge.currencySymbol.ifBlank { "\u00A3" }
    val isNightRate = remember(bridge.offPeakStartHour, bridge.offPeakEndHour) {
        isCurrentlyNightTariffWindow(bridge)
    }
    val activeRateGbp = if (isNightRate) bridge.electricityTariffNightRateGbp else bridge.electricityTariffDayRateGbp
    val estimatedCostGbp = remember(totalKwh, activeRateGbp) {
        totalKwh * activeRateGbp
    }
    val weeklySeries = remember(allTiles, telemetryHistory) {
        buildWeeklyEnergySeries(allTiles, telemetryHistory)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable { onOpenDetailModal() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Color(0xFFFF9100).copy(alpha = 0.20f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFFF9100).copy(alpha = 0.5f)),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Bolt,
                            contentDescription = null,
                            tint = Color(0xFFFF9100),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = tile.title.ifBlank { "Energy Saving" },
                        color = GhTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GhFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${"%.1f".format(totalLiveWatts)} W \u2022 ${"%.2f".format(totalKwh)} kWh ($currency${"%.2f".format(estimatedCostGbp)})",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GhFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                color = GhTileSurfaceElevated,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
            ) {
                Text(
                    text = if (isNightRate) "Night $currency${"%.2f".format(activeRateGbp)}" else "Day $currency${"%.2f".format(activeRateGbp)}",
                    color = GhAccentGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        MultiColourWeeklyBarChart(
            weeklySeries = weeklySeries,
            unitSuffix = "KWH",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

// ---------------------------------------------------------------------------
// FULL ELECTRICITY NAVIGATION PAGE (LEFT RAIL "ELECTRICITY" DESTINATION)
// - Smart Tariff Cost Analytics (Day/Night £/kWh + Standing Charge)
// - On-Device Time-Series Telemetry History (Zero Synthetic Fallbacks)
// - Capability-Aware Monitored Smart Plugs List
// ---------------------------------------------------------------------------
@Composable
fun EnergyOverviewDashboardSection(
    allTiles: List<SmartTile>,
    onUpdateTile: (SmartTile) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bridge = remember { DeviceRepository.loadBridge(context) }
    val telemetryHistory by JarvisLiveBridge.telemetryHistoryFlow.collectAsState()
    var showOnlyPowerCapablePlugs by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        DeviceRepository.loadTelemetryHistory(context)
    }

    val powerCapablePlugs = remember(allTiles) {
        allTiles.filter { it.hasPowerTelemetryCapability }
    }
    val allSwitchTiles = remember(allTiles) {
        allTiles.filter { it.isSwitchTile || it.hasPowerTelemetryCapability }
    }
    val displayedPlugTiles = remember(powerCapablePlugs, allSwitchTiles, showOnlyPowerCapablePlugs) {
        if (showOnlyPowerCapablePlugs && powerCapablePlugs.isNotEmpty()) {
            powerCapablePlugs
        } else {
            allSwitchTiles
        }
    }

    val totalLiveWatts = powerCapablePlugs.sumOf { it.powerWatts.toDouble() }.toFloat()
    val totalKwh = powerCapablePlugs.sumOf { it.totalEnergyKwh.toDouble() }.toFloat().coerceAtLeast(0f)

    val weeklySeries = remember(allTiles, telemetryHistory) {
        buildWeeklyEnergySeries(allTiles, telemetryHistory)
    }
    val weeklyTotalKwh = remember(weeklySeries, totalKwh) {
        val seriesSum = weeklySeries.sum()
        if (seriesSum > 0f) seriesSum else totalKwh
    }

    val currency = bridge.currencySymbol.ifBlank { "\u00A3" }
    val isNightWindow = remember(bridge.offPeakStartHour, bridge.offPeakEndHour) {
        isCurrentlyNightTariffWindow(bridge)
    }
    val dayRateGbp = bridge.electricityTariffDayRateGbp.coerceAtLeast(0.01f)
    val nightRateGbp = bridge.electricityTariffNightRateGbp.coerceAtLeast(0.01f)
    val standingChargeGbp = bridge.electricityStandingChargeGbp.coerceAtLeast(0f)
    val activeRateGbp = if (isNightWindow) nightRateGbp else dayRateGbp
    val offPeakHoursSpan = if (bridge.offPeakStartHour <= bridge.offPeakEndHour) {
        (bridge.offPeakEndHour - bridge.offPeakStartHour).coerceIn(1, 23)
    } else {
        (24 - bridge.offPeakStartHour + bridge.offPeakEndHour).coerceIn(1, 23)
    }
    val nightFraction = (offPeakHoursSpan / 24f).coerceIn(0.05f, 0.95f)
    val blendedRateGbp = (dayRateGbp * (1f - nightFraction)) + (nightRateGbp * nightFraction)
    val activeDaysCount = remember(weeklySeries) {
        weeklySeries.count { it > 0f }
    }
    val weeklyEstimatedCostGbp = (weeklyTotalKwh * blendedRateGbp) + (standingChargeGbp * activeDaysCount)
    val liveHourlyBurnGbp = (totalLiveWatts / 1000f) * activeRateGbp

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Smart Tariff & Live Cost Summary Strip
        SfHouseSunkenTileContainer(
            outerCornerRadius = 18.dp,
            innerCornerRadius = 14.dp,
            sunkenInsetPadding = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.EnergySavingsLeaf,
                        contentDescription = null,
                        tint = GhAccentGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (isNightWindow) {
                            "Off-Peak Night Tariff Active ($currency${"%.2f".format(nightRateGbp)}/kWh)"
                        } else {
                            "Standard Day Tariff Active ($currency${"%.2f".format(dayRateGbp)}/kWh)"
                        },
                        color = GhTextPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GhFont
                    )
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        Surface(
                            color = GhTileSurfaceElevated,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, GhCardBorder)
                        ) {
                            Text(
                                text = "Live Burn: $currency${"%.3f".format(liveHourlyBurnGbp)}/hr",
                                color = Color(0xFF00E5FF),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    item {
                        Surface(
                            color = GhTileSurfaceElevated,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, GhCardBorder)
                        ) {
                            Text(
                                text = "Standing: $currency${"%.2f".format(standingChargeGbp)}/day",
                                color = GhTextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    item {
                        Surface(
                            color = GhAccentGreen.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.55f))
                        ) {
                            Text(
                                text = "Measured Cost: $currency${"%.2f".format(weeklyEstimatedCostGbp)}",
                                color = GhAccentGreen,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Top Row: Multi-Colour Energy Saving Bar Chart Card + Spline Curve Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(215.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SfHouseSunkenTileContainer(
                outerCornerRadius = 22.dp,
                innerCornerRadius = 16.dp,
                sunkenInsetPadding = 6.dp,
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(Color(0xFFFF9100), Color(0xFFFF4081))
                                        )
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ElectricMeter,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Energy Saving",
                                    color = GhTextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GhFont
                                )
                                Text(
                                    text = "7-Day Measured: ${"%.2f".format(weeklyTotalKwh)} kWh ($currency${"%.2f".format(weeklyEstimatedCostGbp)})",
                                    color = GhTextSecondary,
                                    fontSize = 10.5.sp,
                                    fontFamily = GhFont
                                )
                            }
                        }

                        Surface(
                            color = GhTileSurfaceElevated,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, GhCardBorder)
                        ) {
                            Text(
                                text = "${telemetryHistory.size} Samples",
                                color = GhTextPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    MultiColourWeeklyBarChart(
                        weeklySeries = weeklySeries,
                        unitSuffix = "KWH",
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }

            SfHouseSunkenTileContainer(
                outerCornerRadius = 22.dp,
                innerCornerRadius = 16.dp,
                sunkenInsetPadding = 6.dp,
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Electricity Consumed",
                                color = GhTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            )
                            Text(
                                text = "Live Load: ${"%.1f".format(totalLiveWatts)} W \u2022 Cumulative: ${"%.2f".format(totalKwh)} kWh",
                                color = Color(0xFF00E5FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GhFont
                            )
                        }

                        Surface(
                            color = Color(0xFF00E5FF).copy(alpha = 0.16f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = "Live Telemetry",
                                color = Color(0xFF00E5FF),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    SplineEnergyChartWithPeakCallout(
                        weeklySeries = weeklySeries,
                        unitSuffix = "kWh",
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }

        // 3. Bottom List: Individual Smart Plugs & Switches Power Breakdown
        SfHouseSunkenTileContainer(
            outerCornerRadius = 22.dp,
            innerCornerRadius = 16.dp,
            sunkenInsetPadding = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showOnlyPowerCapablePlugs && powerCapablePlugs.isNotEmpty()) {
                            "Power-Metering Smart Plugs (${powerCapablePlugs.size})"
                        } else {
                            "All Smart Plugs & Switches (${allSwitchTiles.size})"
                        },
                        color = GhTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GhFont
                    )

                    if (allSwitchTiles.size > powerCapablePlugs.size && powerCapablePlugs.isNotEmpty()) {
                        FilterChip(
                            selected = !showOnlyPowerCapablePlugs,
                            onClick = { showOnlyPowerCapablePlugs = !showOnlyPowerCapablePlugs },
                            shape = RoundedCornerShape(50),
                            label = {
                                Text(
                                    text = if (showOnlyPowerCapablePlugs) {
                                        "Show All Switches (${allSwitchTiles.size})"
                                    } else {
                                        "Metered Only (${powerCapablePlugs.size})"
                                    },
                                    fontSize = 10.5.sp
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(displayedPlugTiles, key = { _, it -> "energy_plug_${it.id}" }) { idx, plug ->
                        val rowAccent = PlugRowAccentPalette[idx % PlugRowAccentPalette.size]
                        val isAnyOn = if (plug.multiGangSocketCount >= 2) {
                            plug.isActive || plug.isSocket2Active
                        } else {
                            plug.isActive
                        }
                        val plugCostGbp = plug.totalEnergyKwh * activeRateGbp

                        Surface(
                            color = GhTileSurfaceElevated,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isAnyOn) rowAccent.copy(alpha = 0.55f) else GhCardBorder
                            )
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
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isAnyOn) {
                                                    Brush.radialGradient(
                                                        colors = listOf(rowAccent, rowAccent.copy(alpha = 0.55f))
                                                    )
                                                } else {
                                                    Brush.radialGradient(
                                                        colors = listOf(GhTileSurface, GhTileSurface)
                                                    )
                                                }
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (isAnyOn) Color.White.copy(alpha = 0.45f) else GhCardBorder,
                                                shape = CircleShape
                                            )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Power,
                                            contentDescription = null,
                                            tint = if (isAnyOn) Color(0xFF101215) else GhTextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))
                                    val inferredAppliance = plug.inferredApplianceCategory
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = plug.title,
                                                color = GhTextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = GhFont,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (inferredAppliance != ApplianceFingerprintCategory.UNKNOWN) {
                                                Surface(
                                                    color = rowAccent.copy(alpha = 0.16f),
                                                    shape = RoundedCornerShape(50),
                                                    border = BorderStroke(0.8.dp, rowAccent.copy(alpha = 0.55f))
                                                ) {
                                                    Text(
                                                        text = "X-Ray: ${inferredAppliance.label}",
                                                        color = rowAccent,
                                                        fontSize = 9.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = GhFont,
                                                        maxLines = 1,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = buildString {
                                                append(if (isAnyOn) "ON" else "OFF")
                                                if (plug.hasPowerTelemetryCapability) {
                                                    append(" \u2022 ${"%.1f".format(plug.powerWatts)} W")
                                                    if (plug.voltageVolts > 0f) append(" \u2022 ${"%.0f".format(plug.voltageVolts)} V")
                                                    if (plug.totalEnergyKwh > 0f) {
                                                        append(" \u2022 ${"%.2f".format(plug.totalEnergyKwh)} kWh ($currency${"%.2f".format(plugCostGbp)})")
                                                    }
                                                } else {
                                                    append(" \u2022 Relay Switch")
                                                }
                                            },
                                            color = if (isAnyOn) rowAccent else GhTextSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = if (isAnyOn) FontWeight.SemiBold else FontWeight.Normal,
                                            fontFamily = GhFont
                                        )
                                    }
                                }

                                Switch(
                                    checked = isAnyOn,
                                    onCheckedChange = { targetOn ->
                                        onUpdateTile(
                                            plug.copy(
                                                isActive = targetOn,
                                                isSocket2Active = if (plug.multiGangSocketCount >= 2) targetOn else plug.isSocket2Active
                                            )
                                        )
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF0F1114),
                                        checkedTrackColor = rowAccent,
                                        uncheckedThumbColor = GhTextSecondary,
                                        uncheckedTrackColor = GhTileSurface
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// POPUP MODAL WHEN TAPPING THE ELECTRICITY CONSUMED DASHBOARD TILE
// ---------------------------------------------------------------------------
@Composable
fun EnergyMonitorDetailModal(
    allTiles: List<SmartTile>,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhBackground,
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.5.dp, GhTileSurfaceElevated),
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.84f)
                .padding(12.dp)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = Color(0xFFFF9100),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Electricity, Tariff & Power Analytics",
                            color = GhTextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                EnergyOverviewDashboardSection(
                    allTiles = allTiles,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    }
}
