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
// 0A. 7-DAY WEATHER FORECAST CARD (Point 13)
//     - Displays the full 7-day forecast inline with no pop-up button and
//       no pop-up modal when clicked.
// ---------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SfHouseWeatherCard(
    isEditMode: Boolean = false,
    onToggleEditMode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val weather by JarvisLiveBridge.weatherFlow.collectAsState()

    val forecastDays = remember(weather) {
        if (weather.dailyForecast.isNotEmpty()) {
            weather.dailyForecast.take(7)
        } else {
            WeatherApiClient.buildFallbackSevenDayForecast(
                baseTemp = weather.temperatureCelsius,
                baseCondition = weather.conditionText
            )
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            WeatherApiClient.refreshWeather(context)
            delay(15 * 60 * 1000L)
        }
    }

    SfHouseSunkenTileContainer(
        outerCornerRadius = 22.dp,
        innerCornerRadius = 16.dp,
        sunkenInsetPadding = 6.dp,
        modifier = modifier
            .fillMaxSize()
            .combinedClickable(
                onClick = {
                    // 7-Day Weather Card displays inline only — no pop-up modal on click
                },
                onLongClick = { onToggleEditMode() }
            )
    ) {
        val isDark = ClaraThemeManager.isDarkMode
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = if (isDark) {
                            listOf(
                                Color(0xFF1F2B3D).copy(alpha = 0.72f),
                                Color(0xFF1A222D).copy(alpha = 0.45f),
                                Color(0xFF262930)
                            )
                        } else {
                            listOf(
                                Color(0xFFE3F2FD),
                                Color(0xFFF5F9FF),
                                Color(0xFFFFFFFF)
                            )
                        },
                        start = Offset(0f, 0f),
                        end = Offset(600f, 600f)
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.38f)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (isDark) 0.08f else 0.45f),
                                Color.Transparent
                            )
                        )
                    )
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                val isCompactWidth = maxWidth < 240.dp
                val lowTempEstimate = weather.lowTemperatureCelsius
                val topIconSize = if (maxHeight >= 250.dp) 54.dp else 42.dp
                val topTempFontSize = if (maxHeight >= 250.dp) 32.sp else 26.sp

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            ColourfulWeatherHeroIllustration(
                                conditionText = weather.conditionText,
                                isDay = weather.isDay,
                                modifier = Modifier.size(topIconSize)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = "${weather.temperatureCelsius}\u00B0",
                                        color = GhTextPrimary,
                                        fontSize = topTempFontSize,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = GhFont,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                                        )
                                    )
                                    Text(
                                        text = "/$lowTempEstimate\u00B0C",
                                        color = GhTextSecondary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = GhFont,
                                        modifier = Modifier.padding(bottom = 3.dp, start = 2.dp)
                                    )
                                }
                                Text(
                                    text = weather.conditionText,
                                    color = GhAccentGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GhFont,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = GhCardBorder.copy(alpha = 0.65f),
                        thickness = 0.8.dp
                    )

                    LazyColumn(
                        userScrollEnabled = !isEditMode,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(forecastDays) { dayItem ->
                            val isToday = dayItem.dayLabel.equals("Today", ignoreCase = true)
                            Surface(
                                color = if (isToday) {
                                    GhAccentGreen.copy(alpha = 0.14f)
                                } else {
                                    GhTileSurfaceElevated.copy(alpha = 0.42f)
                                },
                                shape = RoundedCornerShape(9.dp),
                                border = if (isToday) {
                                    BorderStroke(0.8.dp, GhAccentGreen.copy(alpha = 0.45f))
                                } else null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = dayItem.dayLabel,
                                        color = if (isToday) GhAccentGreen else GhTextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.SemiBold,
                                        fontFamily = GhFont,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(0.95f)
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1.35f)
                                    ) {
                                        ColourfulWeatherHeroIllustration(
                                            conditionText = dayItem.conditionText,
                                            isDay = true,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = if (!isCompactWidth && dayItem.precipProbabilityPct >= 25) {
                                                "${dayItem.conditionText} (${dayItem.precipProbabilityPct}%)"
                                            } else {
                                                dayItem.conditionText
                                            },
                                            color = GhTextSecondary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = GhFont,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.End,
                                        modifier = Modifier.weight(0.75f)
                                    ) {
                                        Text(
                                            text = "${dayItem.maxTempCelsius}\u00B0",
                                            color = GhTextPrimary,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = GhFont
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "${dayItem.minTempCelsius}\u00B0",
                                            color = GhTextSecondary,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Medium,
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
}

// ---------------------------------------------------------------------------
// 0A-2. TODAY'S WEATHER COMPACT CARD (Point 13)
//       - Displays only today's weather centerpiece.
//       - Clicking the weather illustration image opens the
//         SevenDayWeatherForecastModal 7-day pop-up.
// ---------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SfHouseTodayWeatherCard(
    isEditMode: Boolean = false,
    onToggleEditMode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val weather by JarvisLiveBridge.weatherFlow.collectAsState()
    var showSevenDayForecastModal by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            WeatherApiClient.refreshWeather(context)
            delay(15 * 60 * 1000L)
        }
    }

    SfHouseSunkenTileContainer(
        outerCornerRadius = 22.dp,
        innerCornerRadius = 16.dp,
        sunkenInsetPadding = 6.dp,
        modifier = modifier
            .fillMaxSize()
            .combinedClickable(
                onClick = {},
                onLongClick = { onToggleEditMode() }
            )
    ) {
        val isDark = ClaraThemeManager.isDarkMode
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = if (isDark) {
                            listOf(
                                Color(0xFF1F2B3D).copy(alpha = 0.72f),
                                Color(0xFF1A222D).copy(alpha = 0.45f),
                                Color(0xFF262930)
                            )
                        } else {
                            listOf(
                                Color(0xFFE3F2FD),
                                Color(0xFFF5F9FF),
                                Color(0xFFFFFFFF)
                            )
                        },
                        start = Offset(0f, 0f),
                        end = Offset(600f, 600f)
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.38f)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (isDark) 0.08f else 0.45f),
                                Color.Transparent
                            )
                        )
                    )
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                val isCompactWidth = maxWidth < 240.dp
                val iconSize = if (isCompactWidth || maxHeight < 130.dp) 62.dp else 82.dp
                val tempFontSize = if (isCompactWidth || maxHeight < 130.dp) 38.sp else 48.sp
                val lowTempEstimate = weather.lowTemperatureCelsius

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (isCompactWidth) 12.dp else 18.dp)
                ) {
                    ColourfulWeatherHeroIllustration(
                        conditionText = weather.conditionText,
                        isDay = weather.isDay,
                        modifier = Modifier
                            .size(iconSize)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = !isEditMode) {
                                scope.launch { WeatherApiClient.refreshWeather(context) }
                                showSevenDayForecastModal = true
                            }
                    )
                    Column(
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "${weather.temperatureCelsius}\u00B0",
                                color = GhTextPrimary,
                                fontSize = tempFontSize,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = GhFont,
                                style = TextStyle(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                                )
                            )
                            Text(
                                text = "/$lowTempEstimate\u00B0C",
                                color = GhTextSecondary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GhFont,
                                modifier = Modifier.padding(bottom = 5.dp, start = 2.dp)
                            )
                        }
                        Text(
                            text = weather.conditionText,
                            color = GhAccentGreen,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    if (showSevenDayForecastModal) {
        SevenDayWeatherForecastModal(
            weatherInfo = weather,
            onRefresh = {
                scope.launch { WeatherApiClient.refreshWeather(context) }
            },
            onDismiss = { showSevenDayForecastModal = false }
        )
    }
}

// ---------------------------------------------------------------------------
// 7-DAY WEATHER FORECAST POPUP MODAL (OPENED WHEN CLICKING TODAY'S WEATHER ICON)
// ---------------------------------------------------------------------------
@Composable
fun SevenDayWeatherForecastModal(
    weatherInfo: WeatherInfo,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val forecastDays = remember(weatherInfo) {
        if (weatherInfo.dailyForecast.isNotEmpty()) {
            weatherInfo.dailyForecast
        } else {
            WeatherApiClient.buildFallbackSevenDayForecast(
                baseTemp = weatherInfo.temperatureCelsius,
                baseCondition = weatherInfo.conditionText
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhTileSurface,
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.5.dp, GhCardBorder),
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth(0.92f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ColourfulWeatherHeroIllustration(
                            conditionText = weatherInfo.conditionText,
                            isDay = weatherInfo.isDay,
                            modifier = Modifier.size(46.dp)
                        )
                        Column {
                            Text(
                                text = "7-Day Weather Forecast",
                                color = GhTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            )
                            Text(
                                text = "Currently ${weatherInfo.temperatureCelsius}\u00B0C \u2022 ${weatherInfo.conditionText} (Low ${weatherInfo.lowTemperatureCelsius}\u00B0C)",
                                color = GhAccentGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GhFont
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(
                            onClick = onRefresh,
                            modifier = Modifier.size(34.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = GhTileSurfaceElevated,
                                contentColor = GhAccentGreen
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Refresh Forecast",
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        FilledTonalIconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(34.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = GhTileSurfaceElevated,
                                contentColor = GhTextPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = GhCardBorder)

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(forecastDays) { dayItem ->
                        val isToday = dayItem.dayLabel.equals("Today", ignoreCase = true)
                        Surface(
                            color = if (isToday) GhTileGreenActive else GhTileSurfaceElevated,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isToday) GhAccentGreen.copy(alpha = 0.65f) else GhCardBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1.1f)) {
                                    Text(
                                        text = dayItem.dayLabel,
                                        color = if (isToday) GhAccentGreen else GhTextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = GhFont
                                    )
                                    if (dayItem.dateSubtitle.isNotBlank()) {
                                        Text(
                                            text = dayItem.dateSubtitle,
                                            color = GhTextSecondary,
                                            fontSize = 11.sp,
                                            fontFamily = GhFont
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1.4f)
                                ) {
                                    ColourfulWeatherHeroIllustration(
                                        conditionText = dayItem.conditionText,
                                        isDay = true,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Column {
                                        Text(
                                            text = dayItem.conditionText,
                                            color = GhTextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = GhFont,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (dayItem.precipProbabilityPct > 0) {
                                            Text(
                                                text = "\uD83D\uDCA7 ${dayItem.precipProbabilityPct}% chance of rain",
                                                color = Color(0xFF00E5FF),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                fontFamily = GhFont
                                            )
                                        }
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End,
                                    modifier = Modifier.weight(0.9f)
                                ) {
                                    Text(
                                        text = "${dayItem.maxTempCelsius}\u00B0C",
                                        color = GhTextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = GhFont
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${dayItem.minTempCelsius}\u00B0C",
                                        color = GhTextSecondary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
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

/**
 * 3D Duo-Tone Weather Illustration with Soft Ambient Sun Glow.
 */
@Composable
internal fun ColourfulWeatherHeroIllustration(
    conditionText: String,
    isDay: Boolean,
    modifier: Modifier = Modifier
) {
    val isRain = conditionText.contains("Rain", ignoreCase = true) ||
            conditionText.contains("Showers", ignoreCase = true) ||
            conditionText.contains("Storm", ignoreCase = true)

    Canvas(
        modifier = modifier.semantics {
            contentDescription = "Weather illustration: $conditionText"
        }
    ) {
        val w = size.width
        val h = size.height
        val sunCenter = Offset(w * 0.58f, h * 0.34f)
        val sunRadius = w * 0.26f

        if (isDay) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFFD600).copy(alpha = 0.50f),
                        Color(0xFFFF8A00).copy(alpha = 0.16f),
                        Color.Transparent
                    ),
                    center = sunCenter,
                    radius = sunRadius * 1.85f
                ),
                radius = sunRadius * 1.85f,
                center = sunCenter
            )
            drawCircle(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFFFF176), Color(0xFFFF8F00)),
                    startY = sunCenter.y - sunRadius,
                    endY = sunCenter.y + sunRadius
                ),
                radius = sunRadius,
                center = sunCenter
            )
        } else {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF7C4DFF).copy(alpha = 0.45f),
                        Color.Transparent
                    ),
                    center = sunCenter,
                    radius = sunRadius * 1.7f
                ),
                radius = sunRadius * 1.7f,
                center = sunCenter
            )
            drawCircle(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFE0E7FF), Color(0xFF9575CD)),
                    startY = sunCenter.y - sunRadius,
                    endY = sunCenter.y + sunRadius
                ),
                radius = sunRadius * 0.9f,
                center = sunCenter
            )
        }

        val cloudBaseTop = h * 0.42f
        val cloudBaseBottom = h * 0.78f
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White, Color(0xFFD5DCE6)),
                startY = cloudBaseTop,
                endY = cloudBaseBottom
            ),
            topLeft = Offset(w * 0.08f, cloudBaseTop),
            size = Size(w * 0.80f, cloudBaseBottom - cloudBaseTop),
            cornerRadius = CornerRadius(h * 0.20f, h * 0.20f)
        )
        drawCircle(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White, Color(0xFFE2E8F0))
            ),
            radius = w * 0.24f,
            center = Offset(w * 0.38f, h * 0.46f)
        )
        drawCircle(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White, Color(0xFFD5DCE6))
            ),
            radius = w * 0.18f,
            center = Offset(w * 0.62f, h * 0.52f)
        )

        if (isRain) {
            val dropColor = Color(0xFF00E5FF)
            val xs = listOf(w * 0.28f, w * 0.48f, w * 0.68f)
            xs.forEach { x ->
                drawLine(
                    color = dropColor,
                    start = Offset(x, h * 0.82f),
                    end = Offset(x - w * 0.05f, h * 0.95f),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
