package com.jarvis.smarthomelauncher.widgets.football.Widget

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Stadium
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.smarthomelauncher.SfHouseSunkenTileContainer
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.widgets.football.Models.*
import com.jarvis.smarthomelauncher.widgets.football.Repository.FootballRepository
import com.jarvis.smarthomelauncher.widgets.football.Services.FootballInsightRulesEngine
import com.jarvis.smarthomelauncher.widgets.football.Settings.ClubMonogramBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object FootballBadgeMemoryCache {
    private val cache = object : LruCache<String, ImageBitmap>(96) {}

    fun get(url: String): ImageBitmap? = synchronized(cache) { cache.get(url) }

    fun put(url: String, bitmap: ImageBitmap) = synchronized(cache) {
        cache.put(url, bitmap)
    }
}

@Composable
internal fun FootballClubBadge(
    badgeUrl: String,
    clubName: String,
    primaryColorHex: String = "#1E88E5",
    size: Dp = 36.dp
) {
    val cleanUrl = badgeUrl.trim()
    var loadedBitmap by remember(cleanUrl) {
        mutableStateOf(if (cleanUrl.isNotEmpty()) FootballBadgeMemoryCache.get(cleanUrl) else null)
    }

    LaunchedEffect(cleanUrl) {
        if (cleanUrl.startsWith("http", ignoreCase = true) && loadedBitmap == null) {
            val fetched = withContext(Dispatchers.IO) {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 6_000
                        readTimeout = 6_000
                        setRequestProperty("User-Agent", "ClaraSmartHome/3.0")
                    }
                    if (conn.responseCode in 200..299) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    conn?.disconnect()
                }
            }
            if (fetched != null) {
                FootballBadgeMemoryCache.put(cleanUrl, fetched)
                loadedBitmap = fetched
            }
        }
    }

    val bmp = loadedBitmap
    if (bmp != null) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(GhSunkenWell)
                .border(1.dp, GhCardBorder, CircleShape)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bmp,
                contentDescription = clubName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        ClubMonogramBadge(
            name = clubName,
            hexColor = primaryColorHex,
            sizeDp = size.value.toInt()
        )
    }
}

/**
 * Form indicator Colours strictly matching Football Hub v3.0 JSON:
 * win = green, draw = orange, loss = red.
 */
@Composable
internal fun FootballFormPillsRow(
    form: List<FormResult>,
    pillSize: Dp = 20.dp
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        form.takeLast(5).forEach { result ->
            val bgColor = when (result) {
                FormResult.WIN -> Color(0xFF22C55E)
                FormResult.DRAW -> Color(0xFFF97316)
                FormResult.LOSS -> Color(0xFFEF4444)
            }
            Box(
                modifier = Modifier
                    .size(pillSize)
                    .clip(RoundedCornerShape(6.dp))
                    .background(bgColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = result.code,
                    color = Color.White,
                    fontFamily = GhFont,
                    fontSize = (pillSize.value * 0.52f).sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
internal fun LineupStatusBadge(state: LineupAvailabilityState) {
    val (tint, bg) = when (state) {
        LineupAvailabilityState.AVAILABLE -> Color(0xFF22C55E) to Color(0xFF22C55E).copy(alpha = 0.16f)
        LineupAvailabilityState.EXPECTED_SOON -> Color(0xFFF97316) to Color(0xFFF97316).copy(alpha = 0.16f)
        LineupAvailabilityState.NOT_AVAILABLE -> GhTextSecondary to GhSunkenWell
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Groups,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = "XI: ${state.widgetLabel}",
            color = tint,
            fontFamily = GhFont,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/**
 * CWAS Rule 5, 6, 11, 13: Football Hub v3.0 Dashboard Experience.
 * Designed for 2–4m passive awareness inside an SF-House recessed slate container.
 * Clicking the header opens the 95% x 92% Football Hub Popup.
 */
@Composable
fun FootballWidget(
    repository: FootballRepository,
    onOpenFootballHub: (initialFixture: FootballFixture?) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by repository.uiState.collectAsState()

    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            nowEpochMs = System.currentTimeMillis()
        }
    }

    SfHouseSunkenTileContainer(
        outerCornerRadius = 22.dp,
        innerCornerRadius = 16.dp,
        sunkenInsetPadding = 4.dp,
        modifier = modifier.fillMaxSize()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onOpenFootballHub(null) }
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            val cardHeight = maxHeight
            val showExtendedRows = cardHeight >= 255.dp
            val showInsightBanner = cardHeight >= 195.dp

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // ============================================================
                // HEADER: "Football" + sports_soccer + Mode Pill + Settings
                // ============================================================
                val readySnapshot = (uiState as? FootballWidgetUiState.Ready)?.snapshot
                FootballWidgetHeader(
                    displayMode = readySnapshot?.displayMode,
                    primaryClubName = readySnapshot?.primaryTeam?.shortName,
                    onHeaderClick = { onOpenFootballHub(null) },
                    onSettingsClick = onOpenSettings
                )

                Spacer(modifier = Modifier.height(6.dp))

                // ============================================================
                // BODY: CWAS Rule 11 Core States (Loading, Empty, Error, Ready)
                // ============================================================
                when (val state = uiState) {
                    is FootballWidgetUiState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp,
                                    color = GhAccentGreen
                                )
                                Text(
                                    text = "Loading content...",
                                    color = GhTextSecondary,
                                    fontFamily = GhFont,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    is FootballWidgetUiState.Empty -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.SportsSoccer,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(30.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = state.title,
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = state.message,
                                color = GhTextSecondary,
                                fontFamily = GhFont,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(GhAccentGreen)
                                    .clickable { onOpenSettings() }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Select Favourite Clubs",
                                    color = Color(0xFF101418),
                                    fontFamily = GhFont,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    is FootballWidgetUiState.Error -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = GhAccentWarm,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = state.message,
                                color = GhTextPrimary,
                                fontFamily = GhFont,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(GhTileSurfaceElevated)
                                    .border(1.dp, GhCardBorder, RoundedCornerShape(10.dp))
                                    .clickable { repository.triggerRefresh(forceRemote = true) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    tint = GhAccentGreen,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Retry Sync",
                                    color = GhTextPrimary,
                                    fontFamily = GhFont,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    is FootballWidgetUiState.Ready -> {
                        FootballReadyDashboardBody(
                            snapshot = state.snapshot,
                            nowEpochMs = nowEpochMs,
                            showInsightBanner = showInsightBanner,
                            showUpcomingList = showExtendedRows,
                            onSelectFixture = { fixture -> onOpenFootballHub(fixture) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FootballWidgetHeader(
    displayMode: FootballWidgetDisplayMode?,
    primaryClubName: String?,
    onHeaderClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "football_live_pulse")
    val liveAlpha by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "live_dot_alpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onHeaderClick() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(GhTileSurfaceElevated)
                    .border(1.dp, GhCardBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SportsSoccer,
                    contentDescription = "Football",
                    tint = GhAccentGreen,
                    modifier = Modifier.size(16.dp)
                )
            }

            Column {
                Text(
                    text = "Football",
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (!primaryClubName.isNullOrBlank()) {
                    Text(
                        text = primaryClubName,
                        color = GhTextSecondary,
                        fontFamily = GhFont,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            when (displayMode) {
                FootballWidgetDisplayMode.LIVE -> {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFEF4444).copy(alpha = 0.2f))
                            .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .alpha(liveAlpha)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444))
                        )
                        Text(
                            text = "LIVE",
                            color = Color(0xFFEF4444),
                            fontFamily = GhFont,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                FootballWidgetDisplayMode.MATCH_DAY -> {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(GhAccentWarm.copy(alpha = 0.18f))
                            .border(1.dp, GhAccentWarm, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "MATCH DAY",
                            color = GhAccentWarm,
                            fontFamily = GhFont,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                FootballWidgetDisplayMode.NORMAL -> {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(GhSunkenWell)
                            .border(1.dp, GhCardBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "HUB",
                            color = GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                null -> Unit
            }

            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(GhSunkenWell)
                    .clickable { onSettingsClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Football Settings",
                    tint = GhTextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun FootballReadyDashboardBody(
    snapshot: FootballDashboardSnapshot,
    nowEpochMs: Long,
    showInsightBanner: Boolean,
    showUpcomingList: Boolean,
    onSelectFixture: (FootballFixture) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        val hero = snapshot.heroFixture
        if (hero != null) {
            when (snapshot.displayMode) {
                FootballWidgetDisplayMode.LIVE -> {
                    LiveModeHeroPanel(
                        fixture = hero,
                        onClick = { onSelectFixture(hero) }
                    )
                }

                FootballWidgetDisplayMode.MATCH_DAY -> {
                    MatchDayHeroPanel(
                        fixture = hero,
                        nowEpochMs = nowEpochMs,
                        onClick = { onSelectFixture(hero) }
                    )
                }

                FootballWidgetDisplayMode.NORMAL -> {
                    NormalModeHeroPanel(
                        fixture = hero,
                        nowEpochMs = nowEpochMs,
                        onClick = { onSelectFixture(hero) }
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(GhSunkenWell)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No scheduled fixtures in window • Tap to browse tables & clubs",
                    color = GhTextSecondary,
                    fontFamily = GhFont,
                    fontSize = 13.sp
                )
            }
        }

        // League Snapshot + 5-Match Recent Form Strip (2–4m glanceable)
        val leagueSnap = snapshot.primaryLeagueSnapshot
        if (leagueSnap != null) {
            Spacer(modifier = Modifier.height(6.dp))
            LeagueSnapshotAndFormStrip(snapshot = leagueSnap)
        }

        // Upcoming Fixtures Compact Rows (when widget slot is tall)
        if (showUpcomingList && snapshot.upcomingFixtures.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                snapshot.upcomingFixtures.take(2).forEach { upFix ->
                    CompactUpcomingFixtureRow(
                        fixture = upFix,
                        onClick = { onSelectFixture(upFix) }
                    )
                }
            }
        }

        // RulesEngine Insight Banner
        val topInsight = snapshot.insights.firstOrNull()
        if (showInsightBanner && topInsight != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(GhSunkenWell)
                    .border(1.dp, GhCardBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = GhAccentGreen,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = topInsight.headline,
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Normal Mode Hero Fixture:
 * Shows teamBadges, homeTeam, awayTeam, kickoffTime, competition, broadcastChannel, countdown.
 */
@Composable
private fun NormalModeHeroPanel(
    fixture: FootballFixture,
    nowEpochMs: Long,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(GhSunkenWell)
            .border(1.dp, GhCardBorder, RoundedCornerShape(13.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = fixture.competitionName.uppercase(Locale.UK),
                color = GhAccentGreen,
                fontFamily = GhFont,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            TvChannelPill(channel = fixture.broadcast.displayChannel)
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                FootballClubBadge(
                    badgeUrl = fixture.homeTeam.badgeUrl,
                    clubName = fixture.homeTeam.shortName,
                    primaryColorHex = fixture.homeTeam.primaryColorHex,
                    size = 34.dp
                )
                Text(
                    text = fixture.homeTeam.shortName,
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                if (fixture.status.isFinished) {
                    Text(
                        text = "${fixture.homeScore ?: 0} - ${fixture.awayScore ?: 0}",
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "FT",
                        color = GhTextSecondary,
                        fontFamily = GhFont,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = formatKickoffTime24h(fixture.kickoffEpochMs),
                        color = GhTextPrimary,
                        fontFamily = GhFont,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = formatKickoffDayShort(fixture.kickoffEpochMs, nowEpochMs),
                        color = GhAccentGreen,
                        fontFamily = GhFont,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = fixture.awayTeam.shortName,
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End
                )
                FootballClubBadge(
                    badgeUrl = fixture.awayTeam.badgeUrl,
                    clubName = fixture.awayTeam.shortName,
                    primaryColorHex = fixture.awayTeam.primaryColorHex,
                    size = 34.dp
                )
            }
        }

        if (!fixture.status.isFinished) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = GhTextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = formatCountdownLabel(fixture.kickoffEpochMs, nowEpochMs),
                        color = GhTextSecondary,
                        fontFamily = GhFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                LineupStatusBadge(state = fixture.lineupState)
            }
        }
    }
}

/**
 * Match-Day Mode Hero Panel (Auto-activates 8h before kickoff):
 * Shows countdown, channel, stadium, weather, lineupStatus.
 */
@Composable
private fun MatchDayHeroPanel(
    fixture: FootballFixture,
    nowEpochMs: Long,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(GhSunkenWell)
            .border(1.dp, GhAccentWarm.copy(alpha = 0.65f), RoundedCornerShape(13.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${fixture.competitionName} • ${formatCountdownLabel(fixture.kickoffEpochMs, nowEpochMs)}",
                color = GhAccentWarm,
                fontFamily = GhFont,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TvChannelPill(channel = fixture.broadcast.displayChannel)
        }

        Spacer(modifier = Modifier.height(7.dp))

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
                FootballClubBadge(
                    badgeUrl = fixture.homeTeam.badgeUrl,
                    clubName = fixture.homeTeam.shortName,
                    primaryColorHex = fixture.homeTeam.primaryColorHex,
                    size = 36.dp
                )
                Text(
                    text = fixture.homeTeam.shortName,
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = formatKickoffTime24h(fixture.kickoffEpochMs),
                color = GhTextPrimary,
                fontFamily = GhFont,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(horizontal = 10.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = fixture.awayTeam.shortName,
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End
                )
                FootballClubBadge(
                    badgeUrl = fixture.awayTeam.badgeUrl,
                    clubName = fixture.awayTeam.shortName,
                    primaryColorHex = fixture.awayTeam.primaryColorHex,
                    size = 36.dp
                )
            }
        }

        Spacer(modifier = Modifier.height(7.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (fixture.stadiumName.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stadium,
                            contentDescription = null,
                            tint = GhTextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = fixture.stadiumName,
                            color = GhTextSecondary,
                            fontFamily = GhFont,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                fixture.stadiumWeather?.let { weather ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cloud,
                            contentDescription = null,
                            tint = GhAccentBlue,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = weather.formattedSummary,
                            color = GhTextPrimary,
                            fontFamily = GhFont,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            LineupStatusBadge(state = fixture.lineupState)
        }
    }
}

/**
 * Live Mode Hero Panel (Auto-activates at kickoff):
 * Shows liveScore, matchMinute, goalscorers, cards, channel.
 */
@Composable
private fun LiveModeHeroPanel(
    fixture: FootballFixture,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(GhSunkenWell)
            .border(1.5.dp, Color(0xFFEF4444).copy(alpha = 0.75f), RoundedCornerShape(13.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${fixture.competitionName} • ${fixture.formattedLiveMinute}",
                color = Color(0xFFEF4444),
                fontFamily = GhFont,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
            TvChannelPill(channel = fixture.broadcast.displayChannel)
        }

        Spacer(modifier = Modifier.height(7.dp))

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
                FootballClubBadge(
                    badgeUrl = fixture.homeTeam.badgeUrl,
                    clubName = fixture.homeTeam.shortName,
                    primaryColorHex = fixture.homeTeam.primaryColorHex,
                    size = 38.dp
                )
                Text(
                    text = fixture.homeTeam.shortName,
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(GhTileSurfaceElevated)
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${fixture.homeScore ?: 0}  -  ${fixture.awayScore ?: 0}",
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = fixture.awayTeam.shortName,
                    color = GhTextPrimary,
                    fontFamily = GhFont,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End
                )
                FootballClubBadge(
                    badgeUrl = fixture.awayTeam.badgeUrl,
                    clubName = fixture.awayTeam.shortName,
                    primaryColorHex = fixture.awayTeam.primaryColorHex,
                    size = 38.dp
                )
            }
        }

        val scorersSummary = remember(fixture.events) {
            fixture.goalscorers.joinToString(", ") { "${it.primaryPlayerName} ${it.formattedMinute}" }
        }
        val redCount = fixture.redCards.size
        val yellowCount = fixture.yellowCards.size

        if (scorersSummary.isNotBlank() || redCount > 0 || yellowCount > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (scorersSummary.isNotBlank()) "⚽ $scorersSummary" else "Live match in progress",
                    color = GhTextSecondary,
                    fontFamily = GhFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (yellowCount > 0) {
                        Text(
                            text = "🟨 $yellowCount",
                            color = Color(0xFFFBBF24),
                            fontFamily = GhFont,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (redCount > 0) {
                        Text(
                            text = "🟥 $redCount",
                            color = Color(0xFFEF4444),
                            fontFamily = GhFont,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LeagueSnapshotAndFormStrip(snapshot: LeaguePositionSnapshot) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(GhTileSurfaceElevated)
            .border(1.dp, GhCardBorder, RoundedCornerShape(11.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = FootballInsightRulesEngine.ordinal(snapshot.position),
                color = GhAccentGreen,
                fontFamily = GhFont,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "${snapshot.points} pts",
                color = GhTextPrimary,
                fontFamily = GhFont,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "P${snapshot.gamesPlayed} • GD ${if (snapshot.goalDifference >= 0) "+${snapshot.goalDifference}" else "${snapshot.goalDifference}"}",
                color = GhTextSecondary,
                fontFamily = GhFont,
                fontSize = 12.sp
            )
        }

        if (snapshot.recentForm.isNotEmpty()) {
            FootballFormPillsRow(form = snapshot.recentForm, pillSize = 19.dp)
        }
    }
}

@Composable
private fun CompactUpcomingFixtureRow(
    fixture: FootballFixture,
    onClick: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val zdt = Instant.ofEpochMilli(fixture.kickoffEpochMs).atZone(zone)
    val dateTimeStr = zdt.format(DateTimeFormatter.ofPattern("EEE dd MMM • HH:mm", Locale.UK))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(GhSunkenWell)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${fixture.homeTeam.shortName} vs ${fixture.awayTeam.shortName}",
            color = GhTextPrimary,
            fontFamily = GhFont,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$dateTimeStr • ${fixture.broadcast.displayChannel}",
            color = GhTextSecondary,
            fontFamily = GhFont,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
internal fun TvChannelPill(channel: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(GhTileSurfaceElevated)
            .border(1.dp, GhCardBorder, RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.LiveTv,
            contentDescription = null,
            tint = GhAccentGreen,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = channel,
            color = GhTextPrimary,
            fontFamily = GhFont,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun formatKickoffTime24h(epochMs: Long): String {
    return Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK))
}

internal fun formatKickoffDayShort(epochMs: Long, nowMs: Long): String {
    val zone = ZoneId.systemDefault()
    val targetDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (targetDate) {
        today -> "TODAY"
        today.plusDays(1) -> "TOMORROW"
        else -> targetDate.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)).uppercase(Locale.UK)
    }
}

internal fun formatCountdownLabel(kickoffEpochMs: Long, nowEpochMs: Long): String {
    val diffMs = kickoffEpochMs - nowEpochMs
    if (diffMs <= 0L) return "Kickoff due"
    val totalMinutes = diffMs / 60_000L
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "Kickoff in ${days}d ${hours}h"
        hours > 0 -> "Kickoff in ${hours}h ${minutes}m"
        else -> "Kickoff in ${minutes}m"
    }
}