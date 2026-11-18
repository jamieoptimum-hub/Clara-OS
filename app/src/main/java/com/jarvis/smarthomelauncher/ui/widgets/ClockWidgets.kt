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

internal val SpectrumDialColors = listOf(
    Color(0xFF00E5A3), // Mint Teal
    Color(0xFF00C6FF), // Soft Cyan
    Color(0xFF7C4DFF), // Indigo Violet
    Color(0xFFFF4081), // Rose Pink
    Color(0xFFFF8A00), // Warm Amber
    Color(0xFF00E5A3)
)

// ---------------------------------------------------------------------------
// 7 SELECTABLE CLOCK FACE STYLES RENDERER & PICKER MODAL
// ---------------------------------------------------------------------------
@Composable
internal fun SelectableClockFaceCenterpiece(
    style: ClockFaceStyle,
    timeText: String,
    hourStr: String,
    minStr: String,
    conditionText: String,
    isVoiceListening: Boolean,
    dialSize: Dp,
    onClickClock: () -> Unit
) {
    val initialNow = remember { Date() }
    val initialCal = remember { Calendar.getInstance() }
    var liveTimeText by remember {
        mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(initialNow))
    }
    var liveHourStr by remember {
        mutableStateOf(SimpleDateFormat("HH", Locale.getDefault()).format(initialNow))
    }
    var liveMinStr by remember {
        mutableStateOf(SimpleDateFormat("mm", Locale.getDefault()).format(initialNow))
    }
    var liveDateStr by remember {
        mutableStateOf(SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(initialNow))
    }
    var liveHourInt by remember { mutableIntStateOf(initialCal.get(Calendar.HOUR_OF_DAY)) }
    var liveMinInt by remember { mutableIntStateOf(initialCal.get(Calendar.MINUTE)) }
    var liveSecInt by remember { mutableIntStateOf(initialCal.get(Calendar.SECOND)) }

    LaunchedEffect(Unit) {
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val hourFmt = SimpleDateFormat("HH", Locale.getDefault())
        val minFmt = SimpleDateFormat("mm", Locale.getDefault())
        val dateFmt = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        while (true) {
            val now = Date()
            val cal = Calendar.getInstance()
            liveTimeText = timeFmt.format(now)
            liveHourStr = hourFmt.format(now)
            liveMinStr = minFmt.format(now)
            liveDateStr = dateFmt.format(now)
            liveHourInt = cal.get(Calendar.HOUR_OF_DAY)
            liveMinInt = cal.get(Calendar.MINUTE)
            liveSecInt = cal.get(Calendar.SECOND)
            delay(1000L)
        }
    }

    @Suppress("NAME_SHADOWING")
    val timeText = liveTimeText.ifBlank { timeText }
    @Suppress("NAME_SHADOWING")
    val hourStr = liveHourStr.ifBlank { hourStr }
    @Suppress("NAME_SHADOWING")
    val minStr = liveMinStr.ifBlank { minStr }
    @Suppress("NAME_SHADOWING")
    val conditionText = if (isVoiceListening) "Listening..." else liveDateStr.ifBlank { conditionText }
    @Suppress("NAME_SHADOWING")
    val hourInt = liveHourInt
    @Suppress("NAME_SHADOWING")
    val minInt = liveMinInt
    @Suppress("NAME_SHADOWING")
    val secInt = liveSecInt

    val accentColor = GhAccentGreen
    val secFraction = (secInt / 60f).coerceIn(0f, 1f)
    val minFraction = ((minInt + secFraction) / 60f).coerceIn(0f, 1f)
    val hourFraction = (((hourInt % 12) + minFraction) / 12f).coerceIn(0f, 1f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(dialSize)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Clock: $timeText, $conditionText. Tap to change clock style."
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClickClock() }
    ) {
        when (style) {
            ClockFaceStyle.RADIAL_ORB -> {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.20f),
                                Color(0xFF00C6FF).copy(alpha = 0.08f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = r
                        ),
                        radius = r,
                        center = center
                    )

                    val tickOuter = r * 0.96f
                    val tickInner = r * 0.88f
                    val totalTicks = 40
                    val activeTickLimit = (secFraction * totalTicks).toInt()
                    for (i in 0 until totalTicks) {
                        val angleDeg = (i * (360f / totalTicks) - 90f + 360f) % 360f
                        val rad = Math.toRadians(angleDeg.toDouble())
                        val c = cos(rad).toFloat()
                        val s = sin(rad).toFloat()
                        val isPassed = i <= activeTickLimit
                        drawLine(
                            color = if (isPassed) Color.White.copy(alpha = 0.56f) else Color.White.copy(alpha = 0.16f),
                            start = Offset(center.x + tickInner * c, center.y + tickInner * s),
                            end = Offset(center.x + tickOuter * c, center.y + tickOuter * s),
                            strokeWidth = if (i % 5 == 0) 2.2.dp.toPx() else 1.4.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    val arcRadius = r * 0.76f
                    val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
                    val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
                    val sweep = (secFraction * 360f).coerceAtLeast(12f)

                    drawCircle(
                        color = Color(0xFF15171B),
                        radius = arcRadius,
                        center = center,
                        style = Stroke(width = 7.dp.toPx())
                    )
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = SpectrumDialColors,
                            center = center
                        ),
                        startAngle = 270f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = 4.8.dp.toPx(), cap = StrokeCap.Round)
                    )

                    val endRad = Math.toRadians((270f + sweep).toDouble())
                    val endPt = Offset(
                        x = center.x + arcRadius * cos(endRad).toFloat(),
                        y = center.y + arcRadius * sin(endRad).toFloat()
                    )
                    drawCircle(color = accentColor.copy(alpha = 0.38f), radius = 8.5.dp.toPx(), center = endPt)
                    drawCircle(color = Color(0xFF1C1E22), radius = 4.5.dp.toPx(), center = endPt)
                    drawCircle(
                        color = Color.White,
                        radius = 4.5.dp.toPx(),
                        center = endPt,
                        style = Stroke(width = 2.dp.toPx())
                    )

                    val outerKnobRadius = r * 0.63f
                    val innerKnobRadius = r * 0.53f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF32363F), Color(0xFF1A1C20)),
                            center = center,
                            radius = outerKnobRadius * 1.2f
                        ),
                        radius = outerKnobRadius,
                        center = center
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.16f),
                                Color(0xFF2B2F37),
                                Color(0xFF1E2127)
                            ),
                            center = Offset(center.x, center.y - innerKnobRadius * 0.2f),
                            radius = innerKnobRadius * 1.25f
                        ),
                        radius = innerKnobRadius,
                        center = center
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.18f),
                        radius = innerKnobRadius,
                        center = center,
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = timeText,
                        color = GhTextPrimary,
                        fontSize = if (dialSize >= 170.dp) 34.sp else 27.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = GhFont,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = conditionText,
                        color = if (isVoiceListening) accentColor else GhTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GhFont,
                        maxLines = 1
                    )
                }
            }

            ClockFaceStyle.MINIMAL_DIGITAL -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = timeText,
                        color = GhTextPrimary,
                        fontSize = if (dialSize >= 170.dp) 52.sp else 40.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = GhFont,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = accentColor.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f))
                    ) {
                        Text(
                            text = conditionText,
                            color = accentColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            ClockFaceStyle.CLASSIC_ANALOG -> {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f
                    drawCircle(
                        color = Color(0xFF17191C),
                        radius = r * 0.92f,
                        center = center
                    )
                    drawCircle(
                        color = accentColor.copy(alpha = 0.45f),
                        radius = r * 0.92f,
                        center = center,
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                    for (h in 0 until 12) {
                        val angleRad = Math.toRadians((h * 30.0) - 90.0)
                        val isQuarter = h % 3 == 0
                        val outerR = r * 0.85f
                        val innerR = if (isQuarter) r * 0.72f else r * 0.78f
                        drawLine(
                            color = if (isQuarter) accentColor else Color.White.copy(alpha = 0.35f),
                            start = Offset(center.x + innerR * cos(angleRad).toFloat(), center.y + innerR * sin(angleRad).toFloat()),
                            end = Offset(center.x + outerR * cos(angleRad).toFloat(), center.y + outerR * sin(angleRad).toFloat()),
                            strokeWidth = if (isQuarter) 3.dp.toPx() else 1.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    val hourRad = Math.toRadians((hourFraction * 360.0) - 90.0)
                    drawLine(
                        color = Color.White,
                        start = center,
                        end = Offset(center.x + (r * 0.46f) * cos(hourRad).toFloat(), center.y + (r * 0.46f) * sin(hourRad).toFloat()),
                        strokeWidth = 4.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    val minRad = Math.toRadians((minFraction * 360.0) - 90.0)
                    drawLine(
                        color = Color.White.copy(alpha = 0.9f),
                        start = center,
                        end = Offset(center.x + (r * 0.66f) * cos(minRad).toFloat(), center.y + (r * 0.66f) * sin(minRad).toFloat()),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    val secRad = Math.toRadians((secFraction * 360.0) - 90.0)
                    drawLine(
                        color = accentColor,
                        start = center,
                        end = Offset(center.x + (r * 0.76f) * cos(secRad).toFloat(), center.y + (r * 0.76f) * sin(secRad).toFloat()),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    drawCircle(color = accentColor, radius = 5.dp.toPx(), center = center)
                    drawCircle(color = Color(0xFF17191C), radius = 2.dp.toPx(), center = center)
                }
            }

            ClockFaceStyle.FLIP_RETRO -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = Color(0xFF17191C),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.2.dp, accentColor.copy(alpha = 0.45f)),
                        modifier = Modifier.size(width = 74.dp, height = 88.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = hourStr,
                                color = GhTextPrimary,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = GhFont
                            )
                            HorizontalDivider(
                                color = Color.Black.copy(alpha = 0.65f),
                                thickness = 1.5.dp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Text(
                        text = ":",
                        color = accentColor,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Surface(
                        color = Color(0xFF17191C),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.2.dp, accentColor.copy(alpha = 0.45f)),
                        modifier = Modifier.size(width = 74.dp, height = 88.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = minStr,
                                color = accentColor,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = GhFont
                            )
                            HorizontalDivider(
                                color = Color.Black.copy(alpha = 0.65f),
                                thickness = 1.5.dp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            ClockFaceStyle.NEON_RING -> {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f
                    val strokeW = 7.dp.toPx()

                    val rSec = r * 0.88f
                    drawCircle(color = Color(0xFF17191C), radius = rSec, center = center, style = Stroke(width = strokeW))
                    drawArc(
                        color = accentColor,
                        startAngle = 270f,
                        sweepAngle = (secFraction * 360f).coerceAtLeast(6f),
                        useCenter = false,
                        topLeft = Offset(center.x - rSec, center.y - rSec),
                        size = Size(rSec * 2f, rSec * 2f),
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )

                    val rMin = r * 0.74f
                    drawCircle(color = Color(0xFF17191C), radius = rMin, center = center, style = Stroke(width = strokeW))
                    drawArc(
                        color = accentColor.copy(alpha = 0.68f),
                        startAngle = 270f,
                        sweepAngle = (minFraction * 360f).coerceAtLeast(6f),
                        useCenter = false,
                        topLeft = Offset(center.x - rMin, center.y - rMin),
                        size = Size(rMin * 2f, rMin * 2f),
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )

                    val rHr = r * 0.60f
                    drawCircle(color = Color(0xFF17191C), radius = rHr, center = center, style = Stroke(width = strokeW))
                    drawArc(
                        color = Color.White.copy(alpha = 0.85f),
                        startAngle = 270f,
                        sweepAngle = (hourFraction * 360f).coerceAtLeast(6f),
                        useCenter = false,
                        topLeft = Offset(center.x - rHr, center.y - rHr),
                        size = Size(rHr * 2f, rHr * 2f),
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = timeText,
                        color = GhTextPrimary,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = GhFont
                    )
                    Text(
                        text = conditionText,
                        color = GhTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GhFont,
                        maxLines = 1
                    )
                }
            }

            ClockFaceStyle.EDITORIAL_STACKED -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(5.dp)
                            .height(if (dialSize >= 170.dp) 104.dp else 82.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(accentColor, Color(0xFF00E5FF))
                                )
                            )
                    )
                    Column(
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = hourStr,
                            color = GhTextPrimary,
                            fontSize = if (dialSize >= 170.dp) 46.sp else 36.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = GhFont,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                        Text(
                            text = minStr,
                            color = accentColor,
                            fontSize = if (dialSize >= 170.dp) 46.sp else 36.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = GhFont,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$conditionText \u2022 :%02d".format(secInt),
                            color = GhTextSecondary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont,
                            maxLines = 1
                        )
                    }
                }
            }

            ClockFaceStyle.CHRONOGRAPH_PRO -> {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF282D37), Color(0xFF14161B)),
                            center = center,
                            radius = r * 0.94f
                        ),
                        radius = r * 0.94f,
                        center = center
                    )
                    drawCircle(
                        color = accentColor.copy(alpha = 0.55f),
                        radius = r * 0.94f,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    for (t in 0 until 60) {
                        val deg = (t * 6.0) - 90.0
                        val rad = Math.toRadians(deg)
                        val isMajor = t % 5 == 0
                        val outerR = r * 0.90f
                        val innerR = if (isMajor) r * 0.79f else r * 0.85f
                        drawLine(
                            color = if (isMajor) accentColor else Color.White.copy(alpha = 0.28f),
                            start = Offset(center.x + innerR * cos(rad).toFloat(), center.y + innerR * sin(rad).toFloat()),
                            end = Offset(center.x + outerR * cos(rad).toFloat(), center.y + outerR * sin(rad).toFloat()),
                            strokeWidth = if (isMajor) 2.2.dp.toPx() else 1.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    // Upper & lower chronograph subdials
                    val subR = r * 0.22f
                    val topSub = Offset(center.x, center.y - r * 0.36f)
                    val botSub = Offset(center.x, center.y + r * 0.36f)
                    drawCircle(color = Color(0xFF101216), radius = subR, center = topSub)
                    drawCircle(color = Color.White.copy(alpha = 0.18f), radius = subR, center = topSub, style = Stroke(width = 1.dp.toPx()))
                    drawCircle(color = Color(0xFF101216), radius = subR, center = botSub)
                    drawCircle(color = Color.White.copy(alpha = 0.18f), radius = subR, center = botSub, style = Stroke(width = 1.dp.toPx()))

                    val hourRad = Math.toRadians((hourFraction * 360.0) - 90.0)
                    drawLine(
                        color = Color.White,
                        start = center,
                        end = Offset(center.x + (r * 0.45f) * cos(hourRad).toFloat(), center.y + (r * 0.45f) * sin(hourRad).toFloat()),
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    val minRad = Math.toRadians((minFraction * 360.0) - 90.0)
                    drawLine(
                        color = Color.White.copy(alpha = 0.92f),
                        start = center,
                        end = Offset(center.x + (r * 0.65f) * cos(minRad).toFloat(), center.y + (r * 0.65f) * sin(minRad).toFloat()),
                        strokeWidth = 2.8.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    val secRad = Math.toRadians((secFraction * 360.0) - 90.0)
                    drawLine(
                        color = Color(0xFFFF6D00),
                        start = center,
                        end = Offset(center.x + (r * 0.78f) * cos(secRad).toFloat(), center.y + (r * 0.78f) * sin(secRad).toFloat()),
                        strokeWidth = 1.8.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawCircle(color = Color(0xFFFF6D00), radius = 4.5.dp.toPx(), center = center)
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = dialSize * 0.18f)
                ) {
                    Text(
                        text = timeText,
                        color = accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = GhFont
                    )
                    Text(
                        text = conditionText,
                        color = GhTextSecondary,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GhFont,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun ClockFaceSelectorModal(
    currentStyle: ClockFaceStyle,
    onSelectStyle: (ClockFaceStyle) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhTileSurface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.5.dp, GhCardBorder),
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.90f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = GhAccentGreen,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Choose Clock Face Style",
                                color = GhTextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            )
                            Text(
                                text = "Select how your dashboard clock is displayed",
                                color = GhTextSecondary,
                                fontSize = 11.sp,
                                fontFamily = GhFont
                            )
                        }
                    }
                    FilledTonalIconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                    }
                }

                HorizontalDivider(color = GhCardBorder)

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(ClockFaceStyle.entries) { style ->
                        val isSelected = style == currentStyle
                        Surface(
                            color = if (isSelected) GhTileGreenActive else GhTileSurfaceElevated,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                1.2.dp,
                                if (isSelected) GhAccentGreen else GhCardBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectStyle(style) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = style.label,
                                        color = if (isSelected) GhAccentGreen else GhTextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = GhFont
                                    )
                                    Text(
                                        text = style.subtitle,
                                        color = GhTextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = GhFont
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = GhAccentGreen,
                                        modifier = Modifier.size(20.dp)
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
