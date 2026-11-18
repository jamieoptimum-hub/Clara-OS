package com.jarvis.smarthomelauncher

import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.viewmodels.OnboardingWizardViewModel
import com.jarvis.smarthomelauncher.ui.viewmodels.findComponentActivity
import com.jarvis.smarthomelauncher.ui.widgets.*
import androidx.lifecycle.ViewModelProvider

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// SOFT, UNIFORM INPUT & DROPDOWN STYLING
// ---------------------------------------------------------------------------
@Composable
fun claraFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = GhTileSurfaceElevated,
    unfocusedContainerColor = GhTileSurfaceElevated.copy(alpha = 0.65f),
    focusedBorderColor = GhAccentBlue,
    unfocusedBorderColor = GhTextSecondary.copy(alpha = 0.20f),
    focusedLabelColor = GhAccentBlue,
    unfocusedLabelColor = GhTextSecondary,
    focusedTextColor = GhTextPrimary,
    unfocusedTextColor = GhTextPrimary,
    cursorColor = GhAccentBlue
)

// ---------------------------------------------------------------------------
// REUSABLE CLEAN DROPDOWN SELECTOR
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> CleanDropdownSelector(
    label: String,
    selectedText: String,
    options: List<T>,
    optionLabel: (T) -> String,
    optionSubtitle: ((T) -> String)? = null,
    onOptionSelected: (T) -> Unit,
    textStyle: TextStyle,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            textStyle = textStyle,
            shape = RoundedCornerShape(14.dp),
            colors = claraFieldColors(),
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            singleLine = true
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(GhTileSurfaceElevated)
        ) {
            options.forEach { item ->
                val title = optionLabel(item)
                val sub = optionSubtitle?.invoke(item) ?: ""
                DropdownMenuItem(
                    text = {
                        Column(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                text = title,
                                color = GhTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GhFont
                            )
                            if (sub.isNotBlank()) {
                                Text(
                                    text = sub,
                                    color = GhTextSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = GhFont
                                )
                            }
                        }
                    },
                    onClick = {
                        onOptionSelected(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// CENTER-SNAPPING 24-HOUR CLOCK WHEEL PICKER (Hours 00..23 & Minutes 00..59 by 1)
// ---------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScrollableClockWheelPicker(
    title: String,
    selectedHour: Int,
    onHourChange: (Int) -> Unit,
    selectedMinute: Int,
    onMinuteChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val itemHeight = 38.dp
    val wheelHeight = 114.dp
    val itemHeightPx = with(density) { itemHeight.toPx() }

    val hours = remember { (0..23).toList() }
    val minutes = remember { (0..59).toList() }

    val hourListState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedHour.coerceIn(0, 23),
        initialFirstVisibleItemScrollOffset = 0
    )
    val minuteListState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedMinute.coerceIn(0, 59),
        initialFirstVisibleItemScrollOffset = 0
    )

    val hourSnapFling = rememberSnapFlingBehavior(lazyListState = hourListState)
    val minuteSnapFling = rememberSnapFlingBehavior(lazyListState = minuteListState)

    LaunchedEffect(selectedHour) {
        val target = selectedHour.coerceIn(0, 23)
        if (!hourListState.isScrollInProgress &&
            (hourListState.firstVisibleItemIndex != target || hourListState.firstVisibleItemScrollOffset != 0)
        ) {
            hourListState.animateScrollToItem(target, 0)
        }
    }

    LaunchedEffect(selectedMinute) {
        val target = selectedMinute.coerceIn(0, 59)
        if (!minuteListState.isScrollInProgress &&
            (minuteListState.firstVisibleItemIndex != target || minuteListState.firstVisibleItemScrollOffset != 0)
        ) {
            minuteListState.animateScrollToItem(target, 0)
        }
    }

    LaunchedEffect(hourListState.isScrollInProgress) {
        if (!hourListState.isScrollInProgress) {
            val rawIdx = hourListState.firstVisibleItemIndex
            val offset = hourListState.firstVisibleItemScrollOffset
            val snappedIdx = (if (offset > itemHeightPx / 2f) rawIdx + 1 else rawIdx).coerceIn(0, 23)
            if (snappedIdx != selectedHour) {
                onHourChange(snappedIdx)
            }
            if (rawIdx != snappedIdx || offset != 0) {
                hourListState.animateScrollToItem(snappedIdx, 0)
            }
        }
    }

    LaunchedEffect(minuteListState.isScrollInProgress) {
        if (!minuteListState.isScrollInProgress) {
            val rawIdx = minuteListState.firstVisibleItemIndex
            val offset = minuteListState.firstVisibleItemScrollOffset
            val snappedIdx = (if (offset > itemHeightPx / 2f) rawIdx + 1 else rawIdx).coerceIn(0, 59)
            if (snappedIdx != selectedMinute) {
                onMinuteChange(snappedIdx)
            }
            if (rawIdx != snappedIdx || offset != 0) {
                minuteListState.animateScrollToItem(snappedIdx, 0)
            }
        }
    }

    val centeredNumberStyle = TextStyle(
        fontFamily = GhFont,
        textAlign = TextAlign.Center,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )

    Surface(
        color = GhTileSurfaceElevated.copy(alpha = 0.65f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, GhTextSecondary.copy(alpha = 0.18f)),
        modifier = modifier.fillMaxWidth()
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isNarrowMobile = maxWidth < 520.dp

            @Composable
            fun ReadoutColumn() {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        color = GhTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = GhFont
                    )
                    Text(
                        text = "%02d:%02d".format(selectedHour, selectedMinute),
                        color = GhAccentBlue,
                        fontSize = if (isNarrowMobile) 28.sp else 32.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GhFont
                    )
                    Text(
                        text = "Scroll wheels or tap numbers",
                        color = GhTextSecondary.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontFamily = GhFont
                    )
                }
            }

            @Composable
            fun WheelsAndNudgeRow() {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (isNarrowMobile) 8.dp else 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("HR", color = GhTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .width(if (isNarrowMobile) 68.dp else 76.dp)
                                .height(wheelHeight)
                                .clip(RoundedCornerShape(14.dp))
                                .background(GhTileSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp)
                                    .height(itemHeight)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(GhAccentBlue.copy(alpha = 0.22f))
                                    .border(
                                        1.dp,
                                        GhAccentBlue.copy(alpha = 0.55f),
                                        RoundedCornerShape(10.dp)
                                    )
                            )
                            LazyColumn(
                                state = hourListState,
                                flingBehavior = hourSnapFling,
                                contentPadding = PaddingValues(vertical = itemHeight),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(hours.size) { h ->
                                    val isSel = h == selectedHour
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(itemHeight)
                                            .clickable {
                                                onHourChange(h)
                                                scope.launch { hourListState.animateScrollToItem(h, 0) }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "%02d".format(h),
                                            color = if (isSel) GhAccentBlue else GhTextSecondary.copy(alpha = 0.65f),
                                            fontSize = if (isSel) 20.sp else 14.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                            style = centeredNumberStyle
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("", fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier.height(wheelHeight),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = ":",
                                color = GhTextPrimary,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                style = centeredNumberStyle
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("MIN", color = GhTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .width(if (isNarrowMobile) 68.dp else 76.dp)
                                .height(wheelHeight)
                                .clip(RoundedCornerShape(14.dp))
                                .background(GhTileSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp)
                                    .height(itemHeight)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(GhAccentGreen.copy(alpha = 0.22f))
                                    .border(
                                        1.dp,
                                        GhAccentGreen.copy(alpha = 0.55f),
                                        RoundedCornerShape(10.dp)
                                    )
                            )
                            LazyColumn(
                                state = minuteListState,
                                flingBehavior = minuteSnapFling,
                                contentPadding = PaddingValues(vertical = itemHeight),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(minutes.size) { m ->
                                    val isSel = m == selectedMinute
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(itemHeight)
                                            .clickable {
                                                onMinuteChange(m)
                                                scope.launch { minuteListState.animateScrollToItem(m, 0) }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "%02d".format(m),
                                            color = if (isSel) GhAccentGreen else GhTextSecondary.copy(alpha = 0.65f),
                                            fontSize = if (isSel) 20.sp else 14.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                            style = centeredNumberStyle
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("", fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Column(
                            modifier = Modifier.height(wheelHeight),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        val nextH = (selectedHour + 23) % 24
                                        onHourChange(nextH)
                                        scope.launch { hourListState.animateScrollToItem(nextH, 0) }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) { Text("-1h", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }

                                FilledTonalButton(
                                    onClick = {
                                        val nextH = (selectedHour + 1) % 24
                                        onHourChange(nextH)
                                        scope.launch { hourListState.animateScrollToItem(nextH, 0) }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) { Text("+1h", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        val nextM = (selectedMinute + 59) % 60
                                        onMinuteChange(nextM)
                                        scope.launch { minuteListState.animateScrollToItem(nextM, 0) }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) { Text("-1m", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }

                                FilledTonalButton(
                                    onClick = {
                                        val nextM = (selectedMinute + 1) % 60
                                        onMinuteChange(nextM)
                                        scope.launch { minuteListState.animateScrollToItem(nextM, 0) }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) { Text("+1m", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                }
            }

            if (isNarrowMobile) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        ReadoutColumn()
                    }
                    WheelsAndNudgeRow()
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ReadoutColumn()
                    WheelsAndNudgeRow()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// IN-APP EZVIZ OAUTH / GOOGLE ACCOUNT PASSWORD SETUP MODAL
// ---------------------------------------------------------------------------
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EzvizOAuthWebSignInModal(
    context: Context,
    appKey: String = "",
    onTokenCaptured: (accessToken: String, areaDomain: String) -> Unit,
    onDismiss: () -> Unit
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhBackground,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, GhAccentBlue.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(GhTileSurface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "EZVIZ In-App Cloud Sign-In & Google Account Link",
                            color = GhTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont
                        )
                        Text(
                            text = "Signed up with Google? Tap 'Set Password for Google Account' below, enter your Gmail & the emailed code to set an EZVIZ password.",
                            color = GhTextSecondary,
                            fontSize = 11.sp,
                            fontFamily = GhFont,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                webViewRef?.loadUrl("https://i.ezvizlife.com/user/userAction!goForgetPassword.action")
                            },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = GhAccentGreen.copy(alpha = 0.22f),
                                contentColor = GhAccentGreen
                            )
                        ) {
                            Text("Set Password for Google Account", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text("Close", fontSize = 11.sp, color = GhTextPrimary)
                        }
                    }
                }

                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                private fun inspectUrlAndCookiesForTokens(url: String) {
                                    val normalized = url.replace("#", "?")
                                    val uri = runCatching { Uri.parse(normalized) }.getOrNull()
                                    val urlToken = uri?.getQueryParameter("access_token")
                                        ?: uri?.getQueryParameter("accessToken")
                                        ?: uri?.getQueryParameter("sessionId")
                                        ?: ""
                                    val area = uri?.getQueryParameter("areaDomain")
                                        ?: uri?.getQueryParameter("openApiDomain")
                                        ?: DEFAULT_EZVIZ_API_HOST
                                    val refresh = uri?.getQueryParameter("refresh_token").orEmpty()
                                    val expSec = uri?.getQueryParameter("expires_in")?.toLongOrNull() ?: 604800L

                                    if (urlToken.isNotBlank()) {
                                        EzvizCloudApiClient.saveEzvizOAuthTokens(
                                            context = context,
                                            accessToken = urlToken,
                                            refreshToken = refresh,
                                            areaDomain = area,
                                            expiresInSec = expSec
                                        )
                                        onTokenCaptured(urlToken, area)
                                        return
                                    }

                                    val rawCookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
                                    if (rawCookies.isNotBlank()) {
                                        val cookieMap = rawCookies.split(";").associate { part ->
                                            val k = part.substringBefore("=").trim()
                                            val v = part.substringAfter("=", "").trim()
                                            k to v
                                        }
                                        val cookieToken = cookieMap["accessToken"]
                                            ?: cookieMap["access_token"]
                                            ?: cookieMap["sessionId"]
                                            ?: ""
                                        if (cookieToken.isNotBlank()) {
                                            EzvizCloudApiClient.saveEzvizOAuthTokens(
                                                context = context,
                                                accessToken = cookieToken,
                                                areaDomain = DEFAULT_EZVIZ_API_HOST
                                            )
                                            onTokenCaptured(cookieToken, DEFAULT_EZVIZ_API_HOST)
                                        }
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val nextUrl = request?.url?.toString().orEmpty()
                                    if (nextUrl.isNotBlank()) {
                                        inspectUrlAndCookiesForTokens(nextUrl)
                                    }
                                    return false
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (!url.isNullOrBlank()) {
                                        inspectUrlAndCookiesForTokens(url)
                                    }
                                }
                            }

                            val startUrl = if (appKey.isNotBlank()) {
                                "https://openauth.ezvizlife.com/oauth/authorize?response_type=token&client_id=${Uri.encode(appKey.trim())}&redirect_uri=${Uri.encode("https://open.ezvizlife.com/")}&scope=all"
                            } else {
                                "https://i.ezvizlife.com/user/userAction!goForgetPassword.action"
                            }
                            loadUrl(startUrl)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
fun UnifiedScanAllTab(
    context: Context,
    tiles: MutableList<SmartTile>,
    compactInputTextStyle: TextStyle,
    onSetHaUrl: (String) -> Unit,
    onStatusBanner: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findComponentActivity() }
    val wizardVm = remember(activity, context) {
        if (activity != null) {
            ViewModelProvider(activity)[OnboardingWizardViewModel::class.java]
        } else {
            OnboardingWizardViewModel(context.applicationContext as android.app.Application)
        }
    }

    val state by wizardVm.sessionState.collectAsState()
    val bridge = remember(tiles.size) { DeviceRepository.loadBridge(context) }
    val roomOptions = remember(bridge.customRooms) {
        bridge.customRooms.ifEmpty { DEFAULT_ROOMS }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    var showFlightRecorderModal by remember { mutableStateOf(false) }
    var showEzvizOAuthModal by remember { mutableStateOf(false) }
    var showNestOAuthModal by remember { mutableStateOf(false) }
    var pendingNestEntryKey by remember { mutableStateOf("") }
    var activeBrandModal by remember { mutableStateOf<BrandOption?>(null) }

    val radioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val pendingMode = state.entryMode ?: OnboardingEntryMode.FAST_BLINKING
        wizardVm.selectEntryModeAndStartStep2(pendingMode, tiles)
    }

    fun startModeWithPermissions(mode: OnboardingEntryMode) {
        if (mode == OnboardingEntryMode.MATTER_OR_CLOUD) {
            wizardVm.selectEntryModeAndStartStep2(mode, tiles)
            return
        }
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mode == OnboardingEntryMode.FAST_BLINKING) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        val missing = needed.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            wizardVm.selectEntryModeAndStartStep2(mode, tiles)
            radioPermissionLauncher.launch(needed.toTypedArray())
        } else {
            wizardVm.selectEntryModeAndStartStep2(mode, tiles)
        }
    }

    val totalSteps = if (state.entryMode == OnboardingEntryMode.SLOW_BLINKING_AP) 5 else 4

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Wizard Top Progress Header
        item {
            Surface(
                color = GhTileSurface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, GhTileSurfaceElevated),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.entryMode == null) {
                                "Step 1: Choose How Your Device Connects"
                            } else {
                                "Step ${state.stepIndex.coerceIn(1, totalSteps)} of $totalSteps \u2022 ${state.entryMode?.title.orEmpty()}"
                            },
                            color = GhTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont
                        )
                        Text(
                            text = state.currentStage.consumerSubtitle,
                            color = GhTextSecondary,
                            fontSize = 11.5.sp,
                            fontFamily = GhFont
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.entryMode != null) {
                            OutlinedButton(
                                onClick = { wizardVm.resetWizardToStep1() },
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Change Mode", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = GhTextPrimary)
                            }
                        }
                        FilledTonalButton(
                            onClick = { showFlightRecorderModal = true },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0xFF38BDF8).copy(alpha = 0.18f),
                                contentColor = Color(0xFF38BDF8)
                            )
                        ) {
                            Icon(Icons.Outlined.Analytics, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Flight Recorder", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // =====================================================================
        // STEP 1: PAIRING MODE SELECTOR (ZERO RADIO SCANS UNTIL MODE CHOSEN)
        // =====================================================================
        if (state.entryMode == null || state.currentStage == UnifiedOnboardingStage.SELECT_MODE) {
            items(OnboardingEntryMode.entries, key = { it.name }) { mode ->
                val modeIcon = when (mode) {
                    OnboardingEntryMode.FAST_BLINKING -> Icons.Outlined.BluetoothSearching
                    OnboardingEntryMode.SLOW_BLINKING_AP -> Icons.Outlined.WifiTethering
                    OnboardingEntryMode.ALREADY_ON_NETWORK -> Icons.Outlined.Router
                    OnboardingEntryMode.MATTER_OR_CLOUD -> Icons.Outlined.QrCode2
                }
                val accent = when (mode) {
                    OnboardingEntryMode.FAST_BLINKING -> GhAccentGreen
                    OnboardingEntryMode.SLOW_BLINKING_AP -> Color(0xFF00E5FF)
                    OnboardingEntryMode.ALREADY_ON_NETWORK -> GhAccentBlue
                    OnboardingEntryMode.MATTER_OR_CLOUD -> Color(0xFFC084FC)
                }
                Surface(
                    color = GhTileSurface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.2.dp, accent.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { startModeWithPermissions(mode) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                color = accent.copy(alpha = 0.18f),
                                shape = CircleShape,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(modeIcon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = mode.title,
                                    color = GhTextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GhFont
                                )
                                Text(
                                    text = mode.subtitle,
                                    color = GhTextSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = GhFont
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // =====================================================================
        // STEP 2A: SCANNING IN PROGRESS (ISOLATED RADIO PASS)
        // =====================================================================
        else if (state.currentStage == UnifiedOnboardingStage.SCANNING_CANDIDATES) {
            item {
                Surface(
                    color = GhTileSurface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = GhAccentGreen)
                        Text(
                            text = when (state.entryMode) {
                                OnboardingEntryMode.FAST_BLINKING -> "Scanning Nearby for Fast-Blinking Devices..."
                                OnboardingEntryMode.SLOW_BLINKING_AP -> "Scanning for Live Device Setup Hotspots..."
                                OnboardingEntryMode.ALREADY_ON_NETWORK -> "Scanning Your Home Network..."
                                else -> "Searching..."
                            },
                            color = GhTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont
                        )
                        Text(
                            text = when (state.entryMode) {
                                OnboardingEntryMode.FAST_BLINKING ->
                                    "Make sure one device is powered on and blinking rapidly (twice per second)."
                                OnboardingEntryMode.SLOW_BLINKING_AP ->
                                    "Make sure your device is blinking slowly (once every 2–3 seconds)."
                                OnboardingEntryMode.ALREADY_ON_NETWORK ->
                                    "Make sure your TV, speaker, relay, or camera is switched on."
                                else -> ""
                            },
                            color = GhTextSecondary,
                            fontSize = 12.sp,
                            fontFamily = GhFont,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // =====================================================================
        // STEP 2B: DISCOVERY TIMEOUT RECOVERY (Section 7 — 20s Timeout Actions)
        // =====================================================================
        else if (state.currentStage == UnifiedOnboardingStage.TIMEOUT_NO_DEVICE) {
            item {
                Surface(
                    color = GhTileSurface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.2.dp, GhAccentWarm.copy(alpha = 0.65f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Outlined.SearchOff, contentDescription = null, tint = GhAccentWarm, modifier = Modifier.size(26.dp))
                            Column {
                                Text(
                                    text = "We couldn't find your device yet",
                                    color = GhTextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GhFont
                                )
                                Text(
                                    text = "No new unconfigured device responded during this scan pass.",
                                    color = GhTextSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = GhFont
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { wizardVm.retryCandidateScan(tiles) },
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GhAccentGreen,
                                    contentColor = Color(0xFF0F1114)
                                )
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry Scan", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { wizardVm.resetWizardToStep1() },
                                shape = RoundedCornerShape(50)
                            ) {
                                Text("Switch Pairing Mode", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = GhTextPrimary)
                            }
                            FilledTonalButton(
                                onClick = { wizardVm.showTroubleshootingGuide = !wizardVm.showTroubleshootingGuide },
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = if (wizardVm.showTroubleshootingGuide) "Hide Guide" else "Show Troubleshooting Guide",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        if (state.entryMode == OnboardingEntryMode.FAST_BLINKING) {
                            HorizontalDivider(color = GhTileSurfaceElevated)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Older Wi-Fi-Only Smart Plug (No Bluetooth)?",
                                        color = GhTextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Some older 2.4 GHz plugs listen silently without Bluetooth. Enter Wi-Fi first to broadcast directly.",
                                        color = GhTextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                FilledTonalButton(
                                    onClick = { wizardVm.activatePureEzInvertedFallback() },
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color(0xFF00E5FF).copy(alpha = 0.18f),
                                        contentColor = Color(0xFF00E5FF)
                                    )
                                ) {
                                    Text("Use 2.4 GHz Wi-Fi Broadcast", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        AnimatedVisibility(visible = wizardVm.showTroubleshootingGuide) {
                            Surface(
                                color = GhTileSurfaceElevated.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Troubleshooting Checklist",
                                        color = GhAccentGreen,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "1. Hold the physical reset button on the plug or switch for 5–6 seconds until the indicator blinks.\n" +
                                                "2. Fast blinking (twice per second) = Fast Blinking mode; slow blinking (once every 2–3 seconds) = Slow Blinking / AP Mode.\n" +
                                                "3. For LG Smart TVs, ensure the TV screen is turned on and connected to your home router.\n" +
                                                "4. Ensure Bluetooth and Location Services are enabled in Android Settings.",
                                        color = GhTextPrimary,
                                        fontSize = 11.5.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // =====================================================================
        // MATTER QR / SETUP CODE & OFFICIAL CLOUD ACCOUNT LINKING
        // =====================================================================
        else if (state.entryMode == OnboardingEntryMode.MATTER_OR_CLOUD &&
            state.currentStage == UnifiedOnboardingStage.SELECT_DEVICE
        ) {
            item {
                Surface(
                    color = GhTileSurface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, GhTileSurfaceElevated),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Pair by Matter Setup Code or Nest Entry Key",
                            color = GhTextPrimary,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = wizardVm.shortCodeInput,
                                onValueChange = { wizardVm.shortCodeInput = it.trim() },
                                label = { Text("11-Digit Matter Code (or MT:...) or 7-Char Nest Key", fontSize = 11.sp) },
                                textStyle = compactInputTextStyle,
                                shape = RoundedCornerShape(14.dp),
                                colors = claraFieldColors(),
                                singleLine = true,
                                modifier = Modifier.weight(1.2f)
                            )
                            CleanDropdownSelector(
                                label = "Room",
                                selectedText = state.assignedRoomName,
                                options = roomOptions,
                                optionLabel = { it },
                                onOptionSelected = { wizardVm.updateAssignedNameAndRoom(room = it) },
                                textStyle = compactInputTextStyle,
                                modifier = Modifier.weight(0.8f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    wizardVm.submitShortCodeOnboarding(
                                        tiles = tiles,
                                        onNeedsNestSignIn = { key ->
                                            pendingNestEntryKey = key
                                            showNestOAuthModal = true
                                        }
                                    )
                                },
                                enabled = wizardVm.shortCodeInput.isNotBlank() && !wizardVm.isBusy,
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GhAccentGreen,
                                    contentColor = Color(0xFF0F1114)
                                )
                            ) {
                                Text(if (wizardVm.isBusy) "Pairing..." else "Pair with Code", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Change 6.2: Official SmartLife / Tuya Account Sign-In & Primary Home Sync
            item {
                Surface(
                    color = GhTileSurface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, GhAccentGreen.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Link Official SmartLife / Tuya Account",
                            color = GhAccentGreen,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont
                        )
                        Text(
                            text = "Synchronizes your primary SmartLife Home and encrypted local keys so plugs work locally in both SmartLife and Clara.",
                            color = GhTextSecondary,
                            fontSize = 11.5.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = wizardVm.accountCountryCodeInput,
                                onValueChange = { wizardVm.accountCountryCodeInput = it.filter { c -> c.isDigit() }.take(3) },
                                label = { Text("Code (+44)", fontSize = 11.sp) },
                                textStyle = compactInputTextStyle,
                                shape = RoundedCornerShape(14.dp),
                                colors = claraFieldColors(),
                                singleLine = true,
                                modifier = Modifier.width(105.dp)
                            )
                            OutlinedTextField(
                                value = wizardVm.accountEmailInput,
                                onValueChange = { wizardVm.accountEmailInput = it.trim() },
                                label = { Text("SmartLife Email or Phone", fontSize = 11.sp) },
                                textStyle = compactInputTextStyle,
                                shape = RoundedCornerShape(14.dp),
                                colors = claraFieldColors(),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = wizardVm.accountPasswordInput,
                                onValueChange = { wizardVm.accountPasswordInput = it },
                                label = { Text("SmartLife Password", fontSize = 11.sp) },
                                textStyle = compactInputTextStyle,
                                shape = RoundedCornerShape(14.dp),
                                colors = claraFieldColors(),
                                visualTransformation = if (wizardVm.isAccountPasswordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = {
                                        wizardVm.isAccountPasswordVisible = !wizardVm.isAccountPasswordVisible
                                    }) {
                                        Icon(
                                            imageVector = if (wizardVm.isAccountPasswordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                            contentDescription = null
                                        )
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (wizardVm.availableTuyaHomes.size > 1) {
                            CleanDropdownSelector(
                                label = "Primary SmartLife Home",
                                selectedText = wizardVm.availableTuyaHomes
                                    .firstOrNull { it.homeId == wizardVm.selectedTuyaHomeId }
                                    ?.let { "${it.name} (${it.deviceCount} devices)" }
                                    ?: "Select Home",
                                options = wizardVm.availableTuyaHomes,
                                optionLabel = { "${it.name} (${it.deviceCount} devices)" },
                                onOptionSelected = { chosen ->
                                    wizardVm.selectAndResyncTuyaHome(chosen.homeId, tiles, scope, onStatusBanner)
                                },
                                textStyle = compactInputTextStyle,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (state.failureTitle.isNotBlank()) {
                            Text(
                                text = "${state.failureTitle}: ${state.failureRemediation}",
                                color = GhAccentRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    wizardVm.signInAndSyncOfficialSmartLifeAccount(tiles, scope, onStatusBanner)
                                },
                                enabled = wizardVm.accountEmailInput.isNotBlank() &&
                                        wizardVm.accountPasswordInput.isNotBlank() &&
                                        !wizardVm.isBusy,
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GhAccentGreen,
                                    contentColor = Color(0xFF0F1114)
                                )
                            ) {
                                Text(
                                    text = if (wizardVm.isBusy) "Signing In & Syncing..." else "Sign In & Sync SmartLife Home",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Other Cloud Ecosystems (Google Nest, EZVIZ, TP-Link, Sonoff, Vehicles)
            item {
                Surface(
                    color = GhTileSurface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, GhTileSurfaceElevated),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Other Cloud & Brand Accounts",
                            color = GhTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val cloudBrands = listOf(
                                BrandOption.NEST,
                                BrandOption.EZVIZ,
                                BrandOption.TAPO,
                                BrandOption.SONOFF,
                                BrandOption.IMOU,
                                BrandOption.JAGUAR,
                                BrandOption.MERCEDES
                            )
                            items(cloudBrands, key = { it.name }) { brandOpt ->
                                OutlinedButton(
                                    onClick = {
                                        when (brandOpt) {
                                            BrandOption.NEST -> {
                                                pendingNestEntryKey = ""
                                                showNestOAuthModal = true
                                            }
                                            BrandOption.EZVIZ -> showEzvizOAuthModal = true
                                            else -> activeBrandModal = brandOpt
                                        }
                                    },
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Icon(iconForTileType(brandOpt.defaultTileType), contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(brandOpt.defaultTileType.brandName, fontSize = 12.sp, color = GhTextPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }

        else if (state.currentStage == UnifiedOnboardingStage.SELECT_DEVICE && state.stepIndex == 2) {
            items(state.candidates, key = { it.identityKey }) { candidate ->
                val isSelected = state.selectedCandidateId == candidate.identityKey
                Surface(
                    color = if (isSelected) GhTileGreenActive.copy(alpha = 0.45f) else GhTileSurface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) GhAccentGreen else GhTileSurfaceElevated
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { wizardVm.selectSingleCandidate(candidate) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { wizardVm.selectSingleCandidate(candidate) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = GhAccentGreen,
                                    unselectedColor = GhTextSecondary
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = GhTileSurfaceElevated,
                                shape = CircleShape,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = iconForTileType(candidate.suggestedType),
                                        contentDescription = null,
                                        tint = if (isSelected) GhAccentGreen else GhAccentBlue,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = candidate.displayName,
                                    color = GhTextPrimary,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GhFont,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitleParts = buildList {
                                    if (candidate.consumerSubtitle.isNotBlank()) add(candidate.consumerSubtitle)
                                    if (candidate.consumerSignalLabel.isNotBlank()) add(candidate.consumerSignalLabel)
                                }
                                Text(
                                    text = subtitleParts.joinToString(" \u2022 "),
                                    color = if (candidate.isWeakSignal) GhAccentWarm else GhTextSecondary,
                                    fontSize = 11.5.sp,
                                    fontFamily = GhFont
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { wizardVm.retryCandidateScan(tiles) },
                            shape = RoundedCornerShape(50)
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Rescan", fontSize = 12.sp, color = GhTextPrimary)
                        }
                        if (state.entryMode == OnboardingEntryMode.FAST_BLINKING) {
                            TextButton(onClick = { wizardVm.activatePureEzInvertedFallback() }) {
                                Text("Device not listed? Use 2.4 GHz Broadcast", fontSize = 11.5.sp, color = GhAccentBlue)
                            }
                        }
                    }

                    Button(
                        onClick = {
                            val picked = state.selectedCandidate
                            if (picked != null && picked.port == 8123 && picked.ipAddress.isNotBlank()) {
                                onSetHaUrl("http://${picked.ipAddress}:8123")
                                onStatusBanner("SUCCESS: Linked Home Assistant Hub (${picked.ipAddress})!")
                                wizardVm.resetWizardToStep1()
                            } else {
                                wizardVm.proceedFromStep2CandidateSelection()
                            }
                        },
                        enabled = state.selectedCandidate != null,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GhAccentGreen,
                            contentColor = Color(0xFF0F1114)
                        ),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                    ) {
                        Text("Next", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // =====================================================================
        // STEP 3: 2.4 GHZ WI-FI BSSID VALIDATION & PRE-FILLED CREDENTIALS
        // =====================================================================
        else if (state.currentStage == UnifiedOnboardingStage.CONFIGURE_WIFI) {
            item {
                val selectedWifiOpt = state.selectedWifiOption
                val bandStatus = selectedWifiOpt?.bandValidationStatus
                    ?: if (state.selectedWifiSsid.isNotBlank()) {
                        TuyaDirectPairingManager.validateSsidSupports24GhzByBssid(context, state.selectedWifiSsid)
                    } else {
                        WifiBandValidationStatus.UNKNOWN_UNSCANNED
                    }
                val isBlocked5GhzSsid = bandStatus == WifiBandValidationStatus.BLOCKED_5GHZ_OR_6GHZ_ONLY
                val isBlockedByTablet5GhzEz = state.isPureEzInvertedFlow && state.tabletOn5GhzWarning

                Surface(
                    color = GhTileSurface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, GhTileSurfaceElevated),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = if (state.isPureEzInvertedFlow) {
                                "Step 2: Select 2.4 GHz Home Wi-Fi (Broadcast Mode)"
                            } else {
                                "Step 3: Confirm 2.4 GHz Home Wi-Fi Credentials"
                            },
                            color = GhTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont
                        )

                        // Section 4: Pure UDP EZ single-blinking warning OR 5 GHz tablet redirect banner
                        if (state.isPureEzInvertedFlow) {
                            Surface(
                                color = if (isBlockedByTablet5GhzEz) {
                                    GhAccentRed.copy(alpha = 0.14f)
                                } else {
                                    GhAccentWarm.copy(alpha = 0.16f)
                                },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isBlockedByTablet5GhzEz) GhAccentRed else GhAccentWarm
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = if (isBlockedByTablet5GhzEz) {
                                            "Tablet Connected on 5 GHz (${state.tabletActiveFrequencyMhz} MHz) — Switch to AP Mode"
                                        } else {
                                            "Important: Blink Only ONE Device at a Time"
                                        },
                                        color = if (isBlockedByTablet5GhzEz) GhAccentRed else GhAccentWarm,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (isBlockedByTablet5GhzEz) {
                                            "Pure Wi-Fi EZ broadcast packets sent from a 5 GHz tablet radio are blocked from reaching 2.4 GHz smart plugs. Use Slow Blinking / AP Mode instead."
                                        } else {
                                            "Because this device does not use Bluetooth, make sure only ONE smart plug or switch is blinking rapidly before tapping Next."
                                        },
                                        color = GhTextPrimary,
                                        fontSize = 11.5.sp
                                    )
                                    if (isBlockedByTablet5GhzEz) {
                                        Button(
                                            onClick = {
                                                startModeWithPermissions(OnboardingEntryMode.SLOW_BLINKING_AP)
                                            },
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF00E5FF),
                                                contentColor = Color(0xFF0F1114)
                                            )
                                        ) {
                                            Text("Switch to Slow Blinking / AP Mode", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // BSSID-validated 2.4 GHz network selector
                        CleanDropdownSelector(
                            label = "Home 2.4 GHz Wi-Fi Network",
                            selectedText = state.selectedWifiSsid.ifBlank { "Select a 2.4 GHz Wi-Fi Network..." },
                            options = state.availableWifiNetworks,
                            optionLabel = { "${it.ssid} \u2022 ${it.bandValidationStatus.badgeLabel}" },
                            optionSubtitle = { it.inlineBandGuidance },
                            onOptionSelected = { wizardVm.selectWifiSsid(it.ssid) },
                            textStyle = compactInputTextStyle,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (state.selectedWifiSsid.isNotBlank()) {
                            Text(
                                text = selectedWifiOpt?.inlineBandGuidance ?: bandStatus.badgeLabel,
                                color = if (isBlocked5GhzSsid) GhAccentRed else GhAccentGreen,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        OutlinedTextField(
                            value = state.wifiPassword,
                            onValueChange = { wizardVm.updateWifiPassword(it) },
                            label = { Text("Home Wi-Fi Password (Pre-filled from Encrypted Vault)", fontSize = 11.sp) },
                            textStyle = compactInputTextStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            visualTransformation = if (state.isWifiPasswordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { wizardVm.toggleWifiPasswordVisibility() }) {
                                    Icon(
                                        imageVector = if (state.isWifiPasswordVisible) {
                                            Icons.Outlined.VisibilityOff
                                        } else {
                                            Icons.Outlined.Visibility
                                        },
                                        contentDescription = "Show or Hide Wi-Fi Password"
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // For Fast Blinking (BLE Direct / Pure EZ), allow confirming Name & Room right here before Step 4 provisioning
                        if (state.entryMode == OnboardingEntryMode.FAST_BLINKING) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = state.assignedDeviceName,
                                    onValueChange = { wizardVm.updateAssignedNameAndRoom(name = it) },
                                    label = { Text("Device Name", fontSize = 11.sp) },
                                    textStyle = compactInputTextStyle,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = claraFieldColors(),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                CleanDropdownSelector(
                                    label = "Room",
                                    selectedText = state.assignedRoomName,
                                    options = roomOptions,
                                    optionLabel = { it },
                                    onOptionSelected = { wizardVm.updateAssignedNameAndRoom(room = it) },
                                    textStyle = compactInputTextStyle,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        if (state.failureTitle.isNotBlank()) {
                            Text(
                                text = "${state.failureTitle}: ${state.failureRemediation}",
                                color = GhAccentRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { wizardVm.refreshWifiNetworksWithBssidValidation() },
                                shape = RoundedCornerShape(50)
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Rescan Wi-Fi", fontSize = 12.sp, color = GhTextPrimary)
                            }

                            Button(
                                onClick = { wizardVm.proceedFromWifiCredentialsStep(tiles, scope) },
                                enabled = state.selectedWifiSsid.isNotBlank() &&
                                        !isBlocked5GhzSsid &&
                                        !isBlockedByTablet5GhzEz &&
                                        !wizardVm.isBusy,
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GhAccentGreen,
                                    contentColor = Color(0xFF0F1114)
                                ),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = when {
                                        wizardVm.isBusy -> "Preparing Token..."
                                        state.entryMode == OnboardingEntryMode.SLOW_BLINKING_AP -> "Next"
                                        else -> "Connect Device"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // =====================================================================
        // STEP 3 (LAN) / STEP 4 (AP MODE): SINGLE-DEVICE NAME & ROOM CARD
        // (Replaces legacy multi-device AP picker per Section 5)
        // =====================================================================
        else if (state.currentStage == UnifiedOnboardingStage.SELECT_DEVICE && state.stepIndex in 3..4) {
            item {
                val candidate = state.selectedCandidate
                Surface(
                    color = GhTileSurface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.2.dp, GhAccentGreen.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Step ${state.stepIndex}: Name & Assign Your Device",
                            color = GhTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont
                        )
                        if (candidate?.requiresPhysicalTvAccept == true) {
                            Surface(
                                color = Color(0xFF00E5FF).copy(alpha = 0.14f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Have your LG TV remote ready — after tapping Connect TV, press 'Accept' on your TV screen.",
                                    color = GhTextPrimary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                        if (state.entryMode == OnboardingEntryMode.SLOW_BLINKING_AP && candidate != null) {
                            Text(
                                text = "Cloud pairing token ready. Tapping Connect Device will join '${candidate.softApSsid.ifBlank { candidate.displayName }}', deliver '${state.selectedWifiSsid}', and automatically rejoin your home Wi-Fi.",
                                color = GhTextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = state.assignedDeviceName,
                                onValueChange = { wizardVm.updateAssignedNameAndRoom(name = it) },
                                label = { Text("Device Name on Dashboard", fontSize = 11.sp) },
                                textStyle = compactInputTextStyle,
                                shape = RoundedCornerShape(14.dp),
                                colors = claraFieldColors(),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            CleanDropdownSelector(
                                label = "Room Assignment",
                                selectedText = state.assignedRoomName,
                                options = roomOptions,
                                optionLabel = { it },
                                onOptionSelected = { wizardVm.updateAssignedNameAndRoom(room = it) },
                                textStyle = compactInputTextStyle,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (candidate?.suggestedType == TileType.MATTER_DEVICE && !candidate.hasVerifiedVaultKey) {
                            OutlinedTextField(
                                value = wizardVm.shortCodeInput,
                                onValueChange = { wizardVm.shortCodeInput = it.trim() },
                                label = { Text("11-Digit Matter Setup Code (or Multi-Admin Dynamic Code)", fontSize = 11.sp) },
                                textStyle = compactInputTextStyle,
                                shape = RoundedCornerShape(14.dp),
                                colors = claraFieldColors(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (candidate?.suggestedType?.isCameraType() == true) {
                            OutlinedTextField(
                                value = wizardVm.deviceSecretOrPinInput,
                                onValueChange = { wizardVm.deviceSecretOrPinInput = it.trim() },
                                label = { Text("Camera Safety Code / Verification PIN (Printed on Label)", fontSize = 11.sp) },
                                textStyle = compactInputTextStyle,
                                shape = RoundedCornerShape(14.dp),
                                colors = claraFieldColors(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    wizardVm.executeSingleDeviceOnboarding(
                                        tiles = tiles,
                                        scope = scope,
                                        onOpenExternalAccountFlow = { wfKey ->
                                            when (wfKey.uppercase(Locale.US)) {
                                                "NEST" -> showNestOAuthModal = true
                                                "EZVIZ" -> showEzvizOAuthModal = true
                                                "TUYA" -> wizardVm.selectEntryModeAndStartStep2(
                                                    OnboardingEntryMode.MATTER_OR_CLOUD,
                                                    tiles
                                                )
                                                else -> Unit
                                            }
                                        }
                                    )
                                },
                                enabled = !wizardVm.isBusy &&
                                        (candidate?.suggestedType != TileType.MATTER_DEVICE ||
                                                candidate.hasVerifiedVaultKey ||
                                                wizardVm.shortCodeInput.isNotBlank()),
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GhAccentGreen,
                                    contentColor = Color(0xFF0F1114)
                                ),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = if (candidate?.requiresPhysicalTvAccept == true) {
                                        "Connect TV & Prompt Accept"
                                    } else {
                                        "Connect Device"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // =====================================================================
        // STEP 4 / STEP 5: UNIFIED ONBOARDING SEQUENCE, COMPLETION & RECOVERY
        // (Authenticate -> Activate -> Cloud Register -> Bind Keys -> Discover Capabilities -> Initial Sync)
        // =====================================================================
        else {
            item {
                val unifiedSequence = remember {
                    listOf(
                        UnifiedOnboardingStage.AUTHENTICATING,
                        UnifiedOnboardingStage.ACTIVATING,
                        UnifiedOnboardingStage.CLOUD_REGISTERING,
                        UnifiedOnboardingStage.BINDING_KEYS,
                        UnifiedOnboardingStage.DISCOVERING_CAPABILITIES,
                        UnifiedOnboardingStage.INITIAL_SYNC
                    )
                }
                val activeOrderIdx = unifiedSequence.indexOf(state.currentStage)
                val isCompleted = state.currentStage == UnifiedOnboardingStage.COMPLETED
                val isFailed = state.currentStage == UnifiedOnboardingStage.FAILED

                Surface(
                    color = GhTileSurface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(
                        1.2.dp,
                        when {
                            isFailed -> GhAccentRed.copy(alpha = 0.7f)
                            isCompleted -> GhAccentGreen
                            else -> Color(0xFF00E5FF).copy(alpha = 0.6f)
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            when {
                                isCompleted -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = GhAccentGreen, modifier = Modifier.size(26.dp))
                                isFailed -> Icon(Icons.Outlined.ReportProblem, contentDescription = null, tint = GhAccentRed, modifier = Modifier.size(26.dp))
                                else -> CircularProgressIndicator(color = Color(0xFF00E5FF), strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                            }
                            Column {
                                Text(
                                    text = when {
                                        isFailed -> state.failureTitle.ifBlank { "Setup Could Not Complete" }
                                        isCompleted -> "Device Connected!"
                                        else -> state.currentStage.stepTitle
                                    },
                                    color = when {
                                        isFailed -> GhAccentRed
                                        isCompleted -> GhAccentGreen
                                        else -> GhTextPrimary
                                    },
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GhFont
                                )
                                Text(
                                    text = if (isFailed) state.failureRemediation else state.statusDetail,
                                    color = GhTextSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = GhFont
                                )
                            }
                        }

                        HorizontalDivider(color = GhTileSurfaceElevated)

                        unifiedSequence.forEachIndexed { idx, stageItem ->
                            val stepDone = isCompleted || (!isFailed && activeOrderIdx > idx)
                            val stepCurrent = !isCompleted && !isFailed && activeOrderIdx == idx
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        stepDone -> Icons.Filled.CheckCircle
                                        stepCurrent -> Icons.Outlined.RadioButtonChecked
                                        else -> Icons.Outlined.RadioButtonUnchecked
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        stepDone -> GhAccentGreen
                                        stepCurrent -> Color(0xFF00E5FF)
                                        else -> GhTextSecondary.copy(alpha = 0.5f)
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stageItem.stepTitle,
                                    color = when {
                                        stepDone -> GhAccentGreen
                                        stepCurrent -> Color.White
                                        else -> GhTextSecondary
                                    },
                                    fontSize = 12.5.sp,
                                    fontWeight = if (stepCurrent || stepDone) FontWeight.Bold else FontWeight.Medium,
                                    fontFamily = GhFont
                                )
                            }
                        }

                        LivePairingConnectionTimeline()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            if (isFailed) {
                                OutlinedButton(
                                    onClick = { wizardVm.resetWizardToStep1() },
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text("Switch Pairing Mode", fontSize = 12.sp, color = GhTextPrimary)
                                }
                                Button(
                                    onClick = { wizardVm.executeSingleDeviceOnboarding(tiles, scope) },
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = GhAccentGreen,
                                        contentColor = Color(0xFF0F1114)
                                    )
                                ) {
                                    Text("Retry", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            } else if (isCompleted) {
                                Button(
                                    onClick = { wizardVm.resetWizardToStep1() },
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = GhAccentGreen,
                                        contentColor = Color(0xFF0F1114)
                                    )
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text("Add Another Device", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    activeBrandModal?.let { chosenBrand ->
        GuidedDevicePairingWizardModal(
            context = context,
            initialBrand = chosenBrand,
            tiles = tiles,
            onSyncTuyaEcosystemDevices = {},
            onTriggerFullScan = {},
            onStatusBanner = onStatusBanner,
            onDismiss = { activeBrandModal = null }
        )
    }

    if (showEzvizOAuthModal) {
        EzvizOAuthWebSignInModal(
            context = context,
            appKey = "",
            onTokenCaptured = { capturedToken, capturedDomain ->
                EzvizCloudApiClient.saveEzvizOAuthTokens(
                    context = context,
                    accessToken = capturedToken,
                    areaDomain = capturedDomain
                )
                showEzvizOAuthModal = false
                onStatusBanner("SUCCESS: EZVIZ account linked to vault!")
            },
            onDismiss = { showEzvizOAuthModal = false }
        )
    }

    if (showNestOAuthModal) {
        NestWebLoginModal(
            initialEntryKey = pendingNestEntryKey,
            onSessionCaptured = {
                val currentBridge = DeviceRepository.loadBridge(context)
                val preservedRefreshToken = ClaraSecretVault.getNestSdmRefreshToken(context)
                    ?: currentBridge.nestRefreshToken.takeIf { it.isNotBlank() && it != "nest_web_session_active" }
                        .orEmpty()
                val updatedBridge = currentBridge.copy(nestRefreshToken = preservedRefreshToken)
                DeviceRepository.saveBridge(context, updatedBridge)
                PlayerBackgroundScope.launch {
                    val discoverRes = NestSdmApiClient.discoverNestDevices(updatedBridge)
                    withContext(Dispatchers.Main) {
                        if (discoverRes.isSuccess) {
                            val found = discoverRes.getOrThrow()
                            var onboardedCount = 0
                            for (nd in found) {
                                val nestIdentity = ResolvedDeviceIdentity(
                                    identityKey = "id_cloud_${nd.deviceName.lowercase(Locale.US)}",
                                    displayName = nd.displayName,
                                    suggestedRoom = nd.roomName.ifBlank { inferRoomFromDeviceTitle(nd.displayName) },
                                    deviceClass = nd.suggestedType.toSemanticDeviceClass(),
                                    suggestedType = nd.suggestedType,
                                    combinedConfidence = 0.96f,
                                    primaryProviderId = ProviderId.GOOGLE_NEST.id,
                                    fallbackProviderIds = emptyList(),
                                    onboardingRequirement = OnboardingRequirement.ZeroTouchReady,
                                    cloudDeviceId = nd.deviceName,
                                    hardwareSerialOrNodeId = nd.deviceName,
                                    isActive = nd.hvacMode.equals("HEAT", ignoreCase = true),
                                    isOnline = nd.isOnline,
                                    temperatureC = nd.ambientTempC,
                                    targetTempC = nd.targetTempC,
                                    humidityPct = nd.humidityPct,
                                    hvacMode = nd.hvacMode,
                                    hvacStatus = nd.hvacStatus
                                )
                                if (UniversalOnboardingEngine.onboardResolvedIdentity(
                                        context = context,
                                        identity = nestIdentity,
                                        tiles = tiles,
                                        bridgeSettings = updatedBridge,
                                        scope = scope
                                    ).isSuccess
                                ) {
                                    onboardedCount++
                                }
                            }
                            showNestOAuthModal = false
                            pendingNestEntryKey = ""
                            onStatusBanner("SUCCESS: Added $onboardedCount Google Nest Thermostat(s)!")
                        }
                    }
                }
            },
            onDismiss = {
                showNestOAuthModal = false
                pendingNestEntryKey = ""
            }
        )
    }

    if (showFlightRecorderModal) {
        FlightRecorderHubModal(
            initialDeviceIdFilter = null,
            onDismiss = { showFlightRecorderModal = false }
        )
    }
}

// ---------------------------------------------------------------------------
// AVAILABLE WI-FI SSID DROPDOWN SELECTOR
// - Automatically requests Location / Nearby Wi-Fi permissions if missing
// - Observes JarvisLiveBridge.availableWifiNetworksFlow reactively
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiSsidSelectorDropdown(
    selectedSsid: String,
    onSsidSelected: (String) -> Unit,
    availableNetworks: List<ScannedWifiNetwork>,
    isScanning: Boolean,
    onRefreshNetworks: () -> Unit,
    textStyle: TextStyle,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    val liveFlowNetworks by JarvisLiveBridge.availableWifiNetworksFlow.collectAsState()
    val liveIsScanning by JarvisLiveBridge.isScanningWifiNetworksFlow.collectAsState()
    val effectiveNetworks = remember(availableNetworks, liveFlowNetworks) {
        (liveFlowNetworks + availableNetworks).distinctBy { it.ssid }
    }
    val effectiveScanning = isScanning || liveIsScanning

    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        scope.launch {
            val liveSsid = TuyaDirectPairingManager.detectCurrentWifiSsid(context)
            if (liveSsid.isNotBlank() &&
                !liveSsid.startsWith("SmartLife", ignoreCase = true) &&
                !liveSsid.startsWith("SL-", ignoreCase = true) &&
                selectedSsid.isBlank()
            ) {
                onSsidSelected(liveSsid)
            }
            TuyaDirectPairingManager.scanAvailableWifiNetworks(context)
            onRefreshNetworks()
        }
    }

    fun triggerScanWithPermissionCheck() {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocation) {
            val perms = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
            }.toTypedArray()
            wifiPermissionLauncher.launch(perms)
        } else {
            scope.launch {
                val liveSsid = TuyaDirectPairingManager.detectCurrentWifiSsid(context)
                if (liveSsid.isNotBlank() &&
                    !liveSsid.startsWith("SmartLife", ignoreCase = true) &&
                    !liveSsid.startsWith("SL-", ignoreCase = true) &&
                    selectedSsid.isBlank()
                ) {
                    onSsidSelected(liveSsid)
                }
                TuyaDirectPairingManager.scanAvailableWifiNetworks(context)
            }
            onRefreshNetworks()
        }
    }

    LaunchedEffect(Unit) {
        if (effectiveNetworks.isEmpty()) {
            val hasFine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (hasFine) {
                TuyaDirectPairingManager.scanAvailableWifiNetworks(context)
            }
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { nextExpanded ->
            expanded = nextExpanded
            if (nextExpanded) {
                triggerScanWithPermissionCheck()
            }
        },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedSsid,
            onValueChange = { onSsidSelected(it) },
            label = {
                Text(
                    text = if (effectiveScanning) "Scanning In-Range 2.4GHz Wi-Fi..." else "Home 2.4GHz Wi-Fi Network (${effectiveNetworks.size} found)",
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            textStyle = textStyle,
            shape = RoundedCornerShape(14.dp),
            colors = claraFieldColors(),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Wifi,
                    contentDescription = null,
                    tint = GhAccentGreen,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            triggerScanWithPermissionCheck()
                            expanded = true
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Scan Wi-Fi Networks",
                            tint = GhAccentBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            singleLine = true
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(GhTileSurfaceElevated)
        ) {
            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (effectiveScanning) "Scanning nearby Wi-Fi networks..." else "Rescan In-Range 2.4GHz Wi-Fi Networks",
                            color = GhAccentBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = GhFont
                        )
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            tint = GhAccentBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                },
                onClick = {
                    triggerScanWithPermissionCheck()
                }
            )

            if (effectiveNetworks.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Tap here to grant Location/Wi-Fi permission & scan in-range 2.4GHz networks, or type your Wi-Fi name in the box.",
                            color = GhAccentWarm,
                            fontSize = 11.sp,
                            fontFamily = GhFont
                        )
                    },
                    onClick = { triggerScanWithPermissionCheck() }
                )
            } else {
                HorizontalDivider(color = GhTileSurface)
                for (net in effectiveNetworks) {
                    val isPlugHotspot = DiscoveryRadioCoordinator.isDeviceSetupHotspotSsid(net.ssid)
                    val bssidBandStatus = TuyaDirectPairingManager.validateSsidSupports24GhzByBssid(context, net.ssid)
                    val isIncompatible5GhzOnly = !isPlugHotspot &&
                            bssidBandStatus == WifiBandValidationStatus.BLOCKED_5GHZ_OR_6GHZ_ONLY
                    DropdownMenuItem(
                        enabled = !isPlugHotspot && !isIncompatible5GhzOnly,
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = net.ssid,
                                        color = if (isIncompatible5GhzOnly) GhTextSecondary else GhTextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = GhFont,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = when {
                                            isPlugHotspot -> "Device Setup Hotspot (Select your Home Wi-Fi instead)"
                                            isIncompatible5GhzOnly -> "5 GHz / 6 GHz Only — Incompatible with 2.4 GHz smart devices"
                                            bssidBandStatus == WifiBandValidationStatus.VALID_DUAL_BAND_MESH ->
                                                "Dual-Band Mesh (2.4 GHz Verified) \u2713"
                                            net.is24Ghz -> "2.4 GHz Home Wi-Fi Ready \u2713"
                                            else -> net.bandLabel
                                        },
                                        color = when {
                                            isPlugHotspot -> GhAccentWarm
                                            isIncompatible5GhzOnly -> GhAccentRed
                                            else -> GhAccentGreen
                                        },
                                        fontSize = 10.sp,
                                        fontFamily = GhFont
                                    )
                                }
                                if (net.ssid == selectedSsid) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = GhAccentGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        onClick = {
                            if (!isPlugHotspot && !isIncompatible5GhzOnly) {
                                onSsidSelected(net.ssid)
                                expanded = false
                            }
                        }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// REUSABLE DISCOVERED ITEM ROW (Supports Add, Start Pairing, and Sign In / OAuth)
// ---------------------------------------------------------------------------
@Composable
fun DiscoveredItemRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    alreadyAdded: Boolean,
    primaryActionLabel: String = "Add",
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    onAdd: () -> Unit
) {
    Surface(
        color = GhTileSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, GhTileSurfaceElevated),
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
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    color = GhTileSurfaceElevated,
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = GhAccentBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        color = GhTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GhFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        color = GhTextSecondary,
                        fontSize = 11.sp,
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
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    FilledTonalButton(
                        onClick = onSecondaryAction,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = GhAccentGreen.copy(alpha = 0.2f),
                            contentColor = GhAccentGreen
                        )
                    ) {
                        Icon(
                            imageVector = if (secondaryActionLabel.contains("Sign In", true))
                                Icons.Outlined.AccountCircle else Icons.Outlined.AutoFixHigh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(secondaryActionLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (alreadyAdded) {
                    Surface(
                        color = GhAccentGreen.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = GhAccentGreen,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Added",
                                color = GhAccentGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GhFont
                            )
                        }
                    }
                } else {
                    FilledTonalButton(
                        onClick = onAdd,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (primaryActionLabel == "Add") GhAccentBlue.copy(alpha = 0.2f) else GhAccentGreen.copy(alpha = 0.22f),
                            contentColor = if (primaryActionLabel == "Add") GhAccentBlue else GhAccentGreen
                        )
                    ) {
                        Icon(
                            imageVector = when {
                                primaryActionLabel.contains("Sign In", true) || primaryActionLabel.contains("Link", true) -> Icons.Outlined.AccountCircle
                                primaryActionLabel.contains("Pairing", true) || primaryActionLabel.contains("Provision", true) -> Icons.Outlined.AutoFixHigh
                                else -> Icons.Default.Add
                            },
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(primaryActionLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
